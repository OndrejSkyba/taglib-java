package cz.skyba.taglib.parity.ape.probes;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.ape.ApeFile;
import cz.skyba.taglib.parity.support.FileRefAdapter;
import cz.skyba.taglib.parity.support.OpenOutcome;
import cz.skyba.taglib.parity.support.SaveOutcome;
import cz.skyba.taglib.parity.support.TagSelections;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.FileStream;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

public class ApeFileProbe {
  static String hex(ByteVector b) {
    return b.toHex().toString();
  }

  static String hex(String s) {
    return hex(ByteVector.fromText(s, UTF_8));
  }

  static String list(List<String> v) {
    StringBuilder s = new StringBuilder(v.size() + ":");
    v.forEach(a -> s.append(hex(a)).append(','));
    return s.toString();
  }

  static String props(PropertyMap p) {
    StringBuilder s = new StringBuilder();
    p.asMap().forEach((k, v) -> s.append(hex(k)).append('=').append(list(v)).append(';'));
    return s + "/" + list(p.unsupportedData());
  }

  static int b(boolean v) {
    return v ? 1 : 0;
  }

  static void state(String id, ApeFile f) {
    var t = f.tag();
    var a = f.apeTag();
    var v = f.id3v1Tag();
    var p = f.detailedProperties();
    System.out.println(
      id
        + "/tag\t"
        + b(f.isOpen())
        + "\t"
        + b(f.isValid())
        + "\t"
        + b(t.isEmpty())
        + "\t"
        + hex(t.title())
        + "\t"
        + hex(t.artist())
        + "\t"
        + hex(t.album())
        + "\t"
        + hex(t.comment())
        + "\t"
        + hex(t.genre())
        + "\t"
        + t.year()
        + "\t"
        + t.track());
    System.out.println(id + "/properties\t" + props(f.properties()));
    StringBuilder children =
      new StringBuilder(
        id
          + "/children\t"
          + b(f.hasApeTag())
          + "\t"
          + b(f.hasId3v1Tag())
          + "\t"
          + b(a != null)
          + "\t"
          + b(v != null)
          + "\t");
    if (a != null) {
      var footer = a.footer();
      children
        .append(props(a.properties()))
        .append('|')
        .append(footer.version())
        .append('|')
        .append(footer.tagSize())
        .append('|')
        .append(footer.completeTagSize())
        .append('|')
        .append(footer.itemCount())
        .append('|')
        .append(b(footer.headerPresent()))
        .append('|')
        .append(b(footer.footerPresent()))
        .append('|')
        .append(b(footer.isHeader()))
        .append('|');
      a.itemListMap()
        .forEach(
          (k, i) ->
            children
              .append(hex(k))
              .append(':')
              .append(hex(i.key()))
              .append(':')
              .append(i.type().ordinal())
              .append(':')
              .append(b(i.isReadOnly()))
              .append(':')
              .append(list(i.values()))
              .append(':')
              .append(hex(i.binaryData()))
              .append(';'));
    } else children.append('-');
    System.out.println(children.append('\t').append(v == null ? "-" : props(v.properties())));
    System.out.println(
      id
        + "/audio\t"
        + b(p != null)
        + (p == null
        ? ""
        : "\t"
        + p.lengthInMilliseconds()
        + "\t"
        + p.lengthInSeconds()
        + "\t"
        + p.bitrate()
        + "\t"
        + p.sampleRate()
        + "\t"
        + p.channels()
        + "\t"
        + p.bitsPerSample()
        + "\t"
        + p.version()
        + "\t"
        + p.sampleFrames()));
  }

  static final class Named extends ByteVectorStream {
    String fileName;
    boolean once;
    int descriptors;

    Named(byte[] b, String n, boolean o) {
      super(b);
      fileName = n;
      once = o;
    }

    public String name() {
      return fileName;
    }

    public ByteVector readBlock(long count) {
      long at = tell();
      var b =
        once && at == 8 && count == 44 && ++descriptors > 1
          ? new ByteVector()
          : super.readBlock(count);
      return b;
    }
  }

  static void edit(ApeFile f, int mode) {
    if (mode < 4) {
      f.removeTags(TagSelections.ape(mode));
      return;
    }
    if (mode == 4) {
      f.getOrCreateId3v1Tag().setTitle("created legacy");
      f.getOrCreateApeTag().setTitle("created APE");
      f.tag().setArtist("broadcast");
      return;
    }
    if (mode == 5) {
      var p =
        PropertyMap.builder()
          .put("TITLE", List.of("one", "two"))
          .put("DATE", "2026")
          .put("ALBUMARTIST", "other")
          .put("ID3", "invalid")
          .build();
      System.out.println("rejection\t" + props(f.replaceProperties(p).rejected()));
      return;
    }
    f.getOrCreateApeTag().setData("OPAQUE", new ByteVector("retained"));
    f.tag().setTitle("Edited title");
    f.tag().setYear(0xffffffffL);
    f.tag().setTrack(0x80000000L);
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    try (var paths = Files.list(root.resolve("inputs"))) {
      for (var input : paths.sorted().toList()) {
        String id = input.getFileName().toString();
        // Native memory audio-property reads wrap for this descriptor; tag editing remains comparable.
        boolean wrappedDescriptor = id.equals("descriptor-51.ape");
        byte[] data = Files.readAllBytes(input);
        for (var style : ReadStyle.values())
          for (boolean enabled : new boolean[]{false, true}) {
            String base = id + "/controls/" + style.ordinal() + "/" + b(enabled);
            try (var f = new ApeFile(input, new ReadOptions(enabled, style, ReadOptions.DEFAULT.maxMetadataBytes()))) {
              state(base + "/path", f);
            }
            for (int origin : new int[]{0, 7}) {
              if (wrappedDescriptor && enabled) continue;
              var s = new ByteVectorStream(data);
              s.seek(origin);
              try (var m = new ApeFile(s, new ReadOptions(enabled, style, ReadOptions.DEFAULT.maxMetadataBytes()))) {
                state(base + "/io/" + origin, m);
              }
            }
          }
        for (int origin : new int[]{0, 7})
          for (long length : new long[]{-1, 0, 1, 128, Integer.MAX_VALUE, Long.MAX_VALUE})
            for (var style : ReadStyle.values()) {
              if (wrappedDescriptor) continue;
              var s = new ByteVectorStream(data);
              try (var f = new ApeFile(s, ReadOptions.TAGS_ONLY)) {
                f.seek(origin);
                var p = new cz.skyba.taglib.ape.Properties(f, length, style);
                System.out.println(
                  id
                    + "/direct-props/"
                    + origin
                    + "/"
                    + length
                    + "/"
                    + style.ordinal()
                    + "\t"
                    + p.lengthInMilliseconds()
                    + "\t"
                    + p.lengthInSeconds()
                    + "\t"
                    + p.bitrate()
                    + "\t"
                    + p.sampleRate()
                    + "\t"
                    + p.channels()
                    + "\t"
                    + p.bitsPerSample()
                    + "\t"
                    + p.version()
                    + "\t"
                    + p.sampleFrames());
              }
            }
        for (int origin : new int[]{0, 7}) {
          var s = new ByteVectorStream(data);
          s.seek(origin);
          boolean supported = ApeFile.isSupported(s);
          System.out.println(id + "/support/" + origin + "\t" + b(supported) + "\t" + s.tell());
        }
        for (String name : List.of("sample.ape", "sample.unknown"))
          for (int origin : new int[]{0, 7})
            for (boolean enabled : new boolean[]{false, true}) {
              if (wrappedDescriptor && enabled) continue;
              var stream = new Named(data, name, false);
              stream.seek(origin);
              try (var ref = new cz.skyba.taglib.parity.support.FileRefAdapter(stream, enabled)) {
                var f = ref.file() instanceof ApeFile actual ? actual : null;
                String base = id + "/ref/" + name + "/" + origin + "/" + b(enabled);
                System.out.println(
                  base + "\t" + b(ref.isNull()) + "\t" + b(f != null));
                if (f != null) state(base, f);
              }
            }
        for (int mode = 0; mode < 7; mode++) {
          String base = id + "/edit/" + mode;
          var s = new ByteVectorStream(data);
          try (var m = new ApeFile(s, wrappedDescriptor ? ReadOptions.TAGS_ONLY : ReadOptions.DEFAULT)) {
            m.seek(0);
            edit(m, mode);
            state(base + "/io", m);
            for (int n = 0; n < 2; n++) {
              boolean saved = SaveOutcome.save(m);
              System.out.println(
                base
                  + "/io/save/"
                  + n
                  + "\t"
                  + b(saved)
                  + "\t"
                  + m.tell()
                  + "\t"
                  + hex(s.data()));
              state(base + "/io/after/" + n, m);
            }
          }
          Path copy = root.resolve("outputs").resolve(id + "." + mode + ".java");
          Files.copy(input, copy, StandardCopyOption.REPLACE_EXISTING);
          try (var f = new ApeFile(copy, wrappedDescriptor ? ReadOptions.TAGS_ONLY : ReadOptions.DEFAULT)) {
            f.seek(0);
            edit(f, mode);
            state(base + "/path", f);
            for (int n = 0; n < 2; n++) {
              boolean saved = SaveOutcome.save(f);
              f.seek(f.tell());
              System.out.println(
                base
                  + "/path/save/"
                  + n
                  + "\t"
                  + b(saved)
                  + "\t"
                  + f.tell()
                  + "\t"
                  + hex(new ByteVector(Files.readAllBytes(copy))));
              state(base + "/path/after/" + n, f);
            }
          }
        }
      }
    }
    System.out.println("support/null\t" + b(ApeFile.isSupported(null)));
    System.out.println("open-stream/rejected\t" + b(OpenOutcome.rejected(
      () -> new FileStream(root.resolve("missing.ape")))));
    System.out.println("open-file/rejected\t" + b(OpenOutcome.rejected(
      () -> new ApeFile(root.resolve("missing.ape")))));
    var stream =
      new Named(Files.readAllBytes(root.resolve("inputs/prefix-128.ape")), "sample.ape", true);
    try (var ref = new cz.skyba.taglib.parity.support.FileRefAdapter(stream)) {
      var f = ref.file() instanceof ApeFile actual ? actual : null;
      System.out.println(
        "single-constructor/ref\t"
          + b(ref.isNull())
          + "\t"
          + b(f != null)
          + "\t"
          + stream.descriptors);
      if (f != null) state("single-constructor/ref", f);
    }
  }
}
