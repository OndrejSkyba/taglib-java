package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.TagString;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

/**
 * Provided-header operation comparison, not a substitute for public factory acceptance.
 */
public class Id3PictureProbe {
  static ByteVector bytes(String s) {
    return s.equals("-") ? new ByteVector() : new ByteVector(HexFormat.of().parseHex(s));
  }

  static String hex(ByteVector v) {
    return v.toHex().toString();
  }

  static String string(String s) {
    return TagString.from8Bit(bytes(s).data(), TagString.Type.UTF8).toString();
  }

  static String text(String s) {
    return hex(new TagString(s).data(TagString.Type.UTF16BE));
  }

  static int b(boolean v) {
    return v ? 1 : 0;
  }

  static String fields(List<String> v) {
    var out = new StringBuilder(v.size() + ":");
    v.forEach(s -> out.append(text(s)).append('|'));
    return out.toString();
  }

  static String props(PropertyMap p) {
    var out = new StringBuilder();
    p.asMap().forEach((k, v) -> out.append(text(k)).append('=').append(fields(v)).append(';'));
    out.append('|');
    p.unsupportedData().forEach(s -> out.append(text(s)).append(','));
    return out.toString();
  }

  static class Utf8 extends Latin1StringHandler {
    public String parse(ByteVector data) {
      return new TagString(data, TagString.Type.UTF8).toString();
    }
  }

  static Frame create(String kind, String mode, ByteVector data, long version) {
    if (mode.equals("F")) {
      var h = new Frame.Header(data, version);
      if (kind.equals("V")) return new AttachedPictureFrameV22(data, h);
      if (version < 3) h.setFrameID(new ByteVector(kind.equals("O") ? "GEOB" : "APIC"));
      return kind.equals("O")
        ? new GeneralEncapsulatedObjectFrame(data, h)
        : new AttachedPictureFrame(data, h);
    }
    if (kind.equals("O"))
      return mode.equals("P")
        ? new GeneralEncapsulatedObjectFrame(data)
        : new GeneralEncapsulatedObjectFrame();
    return mode.equals("P") ? new AttachedPictureFrame(data) : new AttachedPictureFrame();
  }

  static void state(String id, Frame f) {
    var h = f.header();
    String detail;
    if (f instanceof AttachedPictureFrame a)
      detail =
        a.textEncoding().ordinal()
          + ":"
          + text(a.mimeType())
          + ":"
          + a.type().code()
          + ":"
          + text(a.description())
          + ":"
          + hex(a.picture())
          + ":"
          + b(a instanceof AttachedPictureFrameV22);
    else {
      var o = (GeneralEncapsulatedObjectFrame) f;
      detail =
        o.textEncoding().ordinal()
          + ":"
          + text(o.mimeType())
          + ":"
          + text(o.fileName())
          + ":"
          + text(o.description())
          + ":"
          + hex(o.object());
    }
    System.out.println(
      id
        + "\t"
        + hex(f.frameID())
        + "\t"
        + f.size()
        + "\t"
        + f.headerSize()
        + "\t"
        + h.version()
        + "\t"
        + text(f.toString())
        + "\t"
        + fields(f.toStringList())
        + "\t"
        + detail
        + "\t"
        + props(f.asProperties())
        + "\t"
        + b(h.tagAlterPreservation())
        + b(h.fileAlterPreservation())
        + b(h.readOnly())
        + b(h.groupingIdentity())
        + b(h.compression())
        + b(h.encryption())
        + b(h.unsynchronisation())
        + b(h.dataLengthIndicator()));
  }

  static void mutate(
    Frame f,
    TagString.Type encoding,
    String mime,
    String name,
    String description,
    ByteVector payload,
    long type) {
    f.setText("ignored");
    if (f instanceof AttachedPictureFrame a) {
      a.setTextEncoding(encoding);
      a.setMimeType(mime);
      a.setType(AttachedPictureFrame.typeFromUInt(type));
      a.setDescription(description);
      a.setPicture(payload);
    } else {
      var o = (GeneralEncapsulatedObjectFrame) f;
      o.setTextEncoding(encoding);
      o.setMimeType(mime);
      o.setFileName(name);
      o.setDescription(description);
      o.setObject(payload);
    }
  }

  static class PictureChild extends AttachedPictureFrame {
    int calls;

    PictureChild(ByteVector data) {
      super(data);
    }

    @Override
    protected void parseFields(ByteVector data) {
      calls++;
      super.parseFields(data);
    }
  }

  static class ObjectChild extends GeneralEncapsulatedObjectFrame {
    int calls;

    ObjectChild(ByteVector data) {
      super(data);
    }

    @Override
    protected void parseFields(ByteVector data) {
      calls++;
      super.parseFields(data);
    }
  }

  static class Throw extends Latin1StringHandler {
    int calls;
    final int fail;

    Throw(int n) {
      fail = n;
    }

    public String parse(ByteVector data) {
      if (++calls == fail) throw new IllegalArgumentException("callback");
      return "decoded:" + new TagString(data);
    }
  }

  static void helpers() {
    for (long n : new long[]{0, 1, 20, 21, 31, 255, 0xffffffffL, 0x100000000L, 0x100000003L, -1}) {
      var a = AttachedPictureFrame.typeFromUInt(n);
      var f = cz.skyba.taglib.flac.Picture.typeFromUInt(n);
      System.out.println(
        "helper/uint\t"
          + Long.toUnsignedString(n)
          + "\t"
          + a.code()
          + "\t"
          + text(AttachedPictureFrame.typeToString(a))
          + "\t"
          + f.code()
          + "\t"
          + text(cz.skyba.taglib.flac.Picture.typeToString(f)));
    }
    for (int n = 0; n < 256; n++) {
      var a = AttachedPictureFrame.typeFromByte((byte) n);
      System.out.println(
        "helper/byte\t"
          + n
          + "\t"
          + a.code()
          + "\t"
          + text(AttachedPictureFrame.typeToString(a)));
    }
    for (var a : AttachedPictureFrame.Type.values()) {
      var s = AttachedPictureFrame.typeToString(a);
      System.out.println(
        "helper/string\t"
          + a.code()
          + "\t"
          + text(s)
          + "\t"
          + AttachedPictureFrame.typeFromString(s).code()
          + "\t"
          + AttachedPictureFrame.typeFromString(new TagString(s).upper().toString()).code());
    }
    var apic = bytes("41504943000000100000006d696d650003646573630064617461");
    var a = new PictureChild(apic);
    System.out.println("virtual/picture-ctor\t" + a.calls);
    a.setData(apic);
    System.out.println("virtual/picture-reparse\t" + a.calls);
    state("virtual/picture-state", a);
    var geob = bytes("47454f42000000140000006d696d65006e616d6500646573630064617461");
    var o = new ObjectChild(geob);
    System.out.println("virtual/object-ctor\t" + o.calls);
    o.setData(geob);
    System.out.println("virtual/object-reparse\t" + o.calls);
    state("virtual/object-state", o);
    for (String kind : List.of("A", "O"))
      for (int fail = 1; fail <= 3; fail++) {
        var frame = create(kind, "B", new ByteVector(), 4);
        mutate(
          frame,
          TagString.Type.UTF8,
          "old-mime",
          "old-name",
          "old-desc",
          new ByteVector("old-body"),
          3);
        var handler = new Throw(fail);
        Id3Text.setHandler(handler);
        boolean threw = false;
        try {
          frame.setData(kind.equals("A") ? apic : geob);
        } catch (IllegalArgumentException expected) {
          threw = true;
        } finally {
          Id3Text.setHandler(null);
        }
        System.out.println(
          "callback/" + kind + "/" + fail + "\t" + b(threw) + "\t" + handler.calls);
        state("callback/" + kind + "/" + fail + "-state", frame);
      }
  }

  public static void main(String[] args) throws Exception {
    var utf8 = new Utf8();
    try (var input = Files.newBufferedReader(Path.of(args[0]))) {
      String line;
      while ((line = input.readLine()) != null) {
        var p = line.split("\t", -1);
        if (p.length != 13) throw new IllegalArgumentException(line);
        String id = p[0];
        long version = Long.parseUnsignedLong(p[3]);
        Id3Text.setHandler(p[4].equals("1") ? utf8 : null);
        var f = create(p[1], p[2], bytes(p[5]), version);
        var h = f.header();
        state(id + "/ctor", f);
        System.out.println(id + "/render\t" + hex(f.render()) + "\t" + b(h == f.header()));
        state(id + "/after-render", f);
        f.setData(bytes(p[6]));
        state(id + "/reparse", f);
        mutate(
          f,
          TagString.Type.values()[Integer.parseInt(p[7])],
          string(p[8]),
          string(p[9]),
          string(p[10]),
          bytes(p[11]),
          Long.parseUnsignedLong(p[12]));
        f.header().setVersion(version);
        state(id + "/mutate", f);
        System.out.println(id + "/mutate-render\t" + hex(f.render()));
        state(id + "/mutate-after", f);
        var copy = f.toStringList();
        copy.set(0, "detached");
        copy.add("added");
        System.out.println(id + "/list-copy\t" + fields(copy) + "\t" + fields(f.toStringList()));
        var data =
          f instanceof AttachedPictureFrame a
            ? a.picture()
            : ((GeneralEncapsulatedObjectFrame) f).object();
        data.append((byte) 'x');
        var original =
          f instanceof AttachedPictureFrame a
            ? a.picture()
            : ((GeneralEncapsulatedObjectFrame) f).object();
        System.out.println(id + "/bytes-copy\t" + hex(data) + "\t" + hex(original));
        f.setData(f.render());
        state(id + "/serialized-reparse", f);
        System.out.println(id + "/serialized-render\t" + hex(f.render()));
        Id3Text.setHandler(null);
      }
    } finally {
      Id3Text.setHandler(null);
    }
    helpers();
  }
}
