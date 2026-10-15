package cz.skyba.taglib.parity.core.probes;

import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.id3.v2.FrameFactory;
import cz.skyba.taglib.id3.v2.Header;
import cz.skyba.taglib.id3.v2.TextIdentificationFrame;
import cz.skyba.taglib.internal.TextBytes;
import cz.skyba.taglib.parity.TextCharsets;
import cz.skyba.taglib.parity.support.FileRefAdapter;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.Variant;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Independent Java consumer; output is lossless UTF-8/binary hex.
 */
public class PrimitiveValuesAndMalformedTagsProbe {
  private static void value(String key, String text) {
    binary(key, text.getBytes(StandardCharsets.UTF_8));
  }

  private static void binary(String key, byte[] bytes) {
    System.out.println(key + "=" + HexFormat.of().formatHex(bytes));
  }

  private static void binary(String key, ByteVector bytes) {
    binary(key, bytes.data());
  }

  private static ByteVector unhex(String text) {
    return new ByteVector(HexFormat.of().parseHex(text));
  }

  private static Object rawType(Class<?> type, int code) throws ReflectiveOperationException {
    var getter = type.getMethod("code");
    if (type.isEnum()) {
      for (Object value : type.getEnumConstants()) {
        if (((Number) getter.invoke(value)).intValue() == code) return value;
      }
    }
    for (var constructor : type.getConstructors()) {
      if (constructor.getParameterCount() == 1 && constructor.getParameterTypes()[0] == int.class) {
        Object value = constructor.newInstance(code);
        if (((Number) getter.invoke(value)).intValue() == code) return value;
      }
    }
    for (Class<?> owner : new Class<?>[]{type, type.getEnclosingClass()}) {
      if (owner == null) continue;
      for (var method : owner.getMethods()) {
        if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())
          || method.getReturnType() != type
          || method.getParameterCount() != 1) continue;
        Class<?> parameter = method.getParameterTypes()[0];
        if (parameter != int.class && parameter != long.class) continue;
        Object value =
          parameter == int.class ? method.invoke(null, code) : method.invoke(null, (long) code);
        if (value != null && ((Number) getter.invoke(value)).intValue() == code) return value;
      }
    }
    return null;
  }

  private static int code(Object value) throws ReflectiveOperationException {
    return ((Number) value.getClass().getMethod("code").invoke(value)).intValue();
  }

  public static void main(String[] args) throws Exception {
    String operation = args[1], input = args[2];
    switch (operation) {
      case "resolver-defaults" -> {
        class Consumer extends cz.skyba.taglib.parity.support.FileRefAdapter.StreamTypeResolver {
          boolean read;
          cz.skyba.taglib.ReadStyle style;

          @Override
          public FileBase createFile(java.nio.file.Path path, ReadOptions options) {
            read = options.readAudioProperties();
            style = options.readStyle();
            return null;
          }

          @Override
          public FileBase createFileFromStream(cz.skyba.taglib.toolkit.IOStream stream, ReadOptions options) {
            read = options.readAudioProperties();
            style = options.readStyle();
            return null;
          }
        }
        var consumer = new Consumer();
        boolean path = input.startsWith("file:");
        boolean two = input.endsWith(":2");
        Class<?> owner =
          path
            ? cz.skyba.taglib.parity.support.FileRefAdapter.FileTypeResolver.class
            : cz.skyba.taglib.parity.support.FileRefAdapter.StreamTypeResolver.class;
        Class<?> argument = path ? java.nio.file.Path.class : cz.skyba.taglib.toolkit.IOStream.class;
        java.lang.reflect.Method method;
        try {
          // Compare C++ convenience defaults with the corresponding Java option constants.
          method = owner.getMethod(path ? "createFile" : "createFileFromStream", argument, ReadOptions.class);
        } catch (NoSuchMethodException missing) {
          value("present", "0");
          break;
        }
        Object source =
          path
            ? java.nio.file.Path.of("unused")
            : new cz.skyba.taglib.toolkit.ByteVectorStream(new ByteVector());
        method.invoke(consumer, source, two ? ReadOptions.TAGS_ONLY : ReadOptions.DEFAULT);
        value("present", "1");
        value("readAudioProperties", consumer.read ? "1" : "0");
        value("readStyle", Integer.toString(consumer.style.ordinal()));
      }
      case "picture-type" -> {
        int at = input.indexOf(':');
        String family = input.substring(0, at);
        int requested = Integer.parseInt(input.substring(at + 1));
        Class<?> owner =
          switch (family) {
            case "flac" -> cz.skyba.taglib.flac.Picture.class;
            case "apic" -> cz.skyba.taglib.id3.v2.AttachedPictureFrame.class;
            case "asf" -> cz.skyba.taglib.asf.Picture.class;
            default -> throw new IllegalArgumentException(family);
          };
        Class<?> type = Class.forName(owner.getName() + "$Type");
        Object raw = rawType(type, requested);
        value("present", raw == null ? "0" : "1");
        if (raw != null) {
          Object picture = owner.getConstructor().newInstance();
          owner.getMethod("setType", type).invoke(picture, raw);
          if (family.equals("flac"))
            owner.getMethod("setData", byte[].class).invoke(picture, (Object) new byte[]{'x'});
          if (family.equals("apic"))
            owner.getMethod("setPicture", ByteVector.class).invoke(picture, new ByteVector("x"));
          value("type", Integer.toString(code(owner.getMethod("type").invoke(picture))));
          value("name", (String) owner.getMethod("typeToString", type).invoke(null, raw));
          value(
            "normalized",
            Integer.toString(
              code(
                owner.getMethod("typeFromUInt", long.class).invoke(null, (long) requested))));
          if (!family.equals("asf")) {
            Object rendered = owner.getMethod("render").invoke(picture);
            if (rendered instanceof ByteVector bytes) binary("render", bytes);
            else binary("render", (byte[]) rendered);
          }
        }
      }
      case "rva-channel" -> {
        int requested = Integer.parseInt(input);
        Class<?> type = cz.skyba.taglib.id3.v2.RelativeVolumeFrame.ChannelType.class;
        Object raw = rawType(type, requested);
        value("present", raw == null ? "0" : "1");
        if (raw != null) {
          var frame = new cz.skyba.taglib.id3.v2.RelativeVolumeFrame();
          frame.setIdentification("x");
          frame
            .getClass()
            .getMethod("setVolumeAdjustmentIndex", int.class, type)
            .invoke(frame, 256, raw);
          for (Object channel : frame.channels()) value("channel", Integer.toString(code(channel)));
          binary("render", frame.render());
        }
      }
      case "stream-buffer-size" -> {
        java.lang.reflect.Method method = null;
        for (Class<?> owner = cz.skyba.taglib.toolkit.FileStream.class;
             owner != null;
             owner = owner.getSuperclass()) {
          try {
            var candidate = owner.getDeclaredMethod("bufferSize");
            int modifiers = candidate.getModifiers();
            if (java.lang.reflect.Modifier.isStatic(modifiers)
              && (java.lang.reflect.Modifier.isProtected(modifiers)
              || java.lang.reflect.Modifier.isPublic(modifiers))) {
              method = candidate;
              break;
            }
          } catch (NoSuchMethodException absent) {
            // A superclass may provide the compatible protected operation.
          }
        }
        if (method == null) value("present", "0");
        else {
          int modifiers = method.getModifiers();
          boolean accessible =
            java.lang.reflect.Modifier.isStatic(modifiers)
              && (java.lang.reflect.Modifier.isProtected(modifiers)
              || java.lang.reflect.Modifier.isPublic(modifiers));
          value("present", accessible ? "1" : "0");
          if (accessible) {
            method.setAccessible(true);
            value("size", ((Number) method.invoke(null)).toString());
          }
        }
      }
      case "variant-double" -> {
        double number =
          input.startsWith("bits:")
            ? Double.longBitsToDouble(Long.parseUnsignedLong(input.substring(5), 16))
            : Double.parseDouble(input);
        binary("bits", ByteVector.fromFloat64BE(number));
        value("render", new Variant(number).render());
      }
      case "variant-bytes" -> value("render", new Variant(unhex(input)).render());
      case "byte-numbers" -> {
        int at = input.indexOf(':');
        int offset = Integer.parseInt(input.substring(0, at));
        var data = unhex(input.substring(at + 1));
        value("uint", Long.toString(data.toUnsignedInt(offset)));
        value("ushort", Integer.toString(data.toUnsignedShort(offset, false)));
        value("ulonglong", Long.toUnsignedString(data.toUnsignedLong(offset)));
      }
      case "base64" -> {
        var decoded = ByteVector.fromBase64(unhex(input));
        binary("decoded", decoded);
        binary("encoded", decoded.toBase64());
      }
      case "string" -> {
        int at = input.indexOf(':');
        var text =
          TextBytes.decodeTerminated(unhex(input.substring(at + 1)).data(), TextCharsets.ALL[Integer.parseInt(input.substring(0, at))]);
        binary("text", TextBytes.encode(text, UTF_8));
        binary("utf8", ByteVector.fromText(text, UTF_8));
      }
      case "ape-item" -> {
        var item = new cz.skyba.taglib.ape.Item();
        item.parse(unhex(input));
        value("key", item.key());
        value("type", Integer.toString(item.type().ordinal()));
        binary("render", item.render());
      }
      case "flac-picture" -> {
        var picture = new cz.skyba.taglib.flac.Picture();
        value("valid", picture.parse(unhex(input).data()) ? "1" : "0");
        binary("render", picture.render());
      }
      case "ogg-header" -> {
        var header = new cz.skyba.taglib.ogg.PageHeader(unhex(input).data());
        value("valid", header.isValid() ? "1" : "0");
        value("size", Integer.toString(header.size()));
        value("dataSize", Integer.toString(header.dataSize()));
      }
      case "id3-header" -> {
        var header = new Header(unhex(input));
        value("version", Long.toString(header.majorVersion()));
        value("tagSize", Long.toString(header.tagSize()));
        binary("render", header.render());
      }
      case "id3-frame" -> {
        int at = input.indexOf(':');
        var header = new Header();
        header.setMajorVersion(Long.parseLong(input.substring(0, at)));
        var frame = FrameFactory.instance().createFrame(unhex(input.substring(at + 1)), header);
        value("present", frame != null ? "1" : "0");
        if (frame != null) {
          binary("id", frame.frameID());
          value("text", frame.toString());
          for (String field : frame.toStringList()) value("field", field);
          binary("render", frame.render());
        }
      }
      case "id3-encoding" -> {
        var frame = new TextIdentificationFrame(new ByteVector("TIT2"), ISO_8859_1);
        frame.header().setVersion(Long.parseLong(input));
        frame.setText("图");
        binary("render", frame.render());
      }
      default -> throw new IllegalArgumentException(operation);
    }
  }
}
