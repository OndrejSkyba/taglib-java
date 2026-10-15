package cz.skyba.taglib.parity.mpc.probes;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.mpc.MpcFile;
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
import java.util.Arrays;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

public class MpcFileProbe {
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

  static void state(String id, MpcFile f) {
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
        + p.mpcVersion()
        + "\t"
        + Long.toUnsignedString(p.sampleFrames())
        + "\t"
        + p.totalFrames()
        + "\t"
        + p.trackGain()
        + "\t"
        + p.trackPeak()
        + "\t"
        + p.albumGain()
        + "\t"
        + p.albumPeak()));
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
        once && at == 4 && count == 52 && ++descriptors > 1
          ? new ByteVector()
          : super.readBlock(count);
      return b;
    }
  }

  static void edit(MpcFile f, int mode) {
    if (mode < 8) {
      f.removeTags(TagSelections.mpc(mode));
      return;
    }
    if (mode == 8) {
      f.getOrCreateId3v1Tag().setTitle("created legacy");
      f.getOrCreateApeTag().setTitle("created APE");
      f.tag().setArtist("broadcast");
      return;
    }
    if (mode == 9) {
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

  static boolean undefinedMemoryGrowth(MpcFile f, ByteVector data) {
    if (f.hasApeTag() || f.apeTag() == null || f.apeTag().isEmpty()) return false;
    int at = data.size() - (f.hasId3v1Tag() ? 160 : 32);
    if (at < 0 || !data.mid(at, 8).equals(new ByteVector("APETAGEX"))) return false;
    long replacement =
      (data.toUnsignedInt(at + 12, false) + ((data.toUnsignedInt(at + 20, false) & 0x80000000L) != 0 ? 32 : 0))
        & 0xffffffffL;
    if (at + 32L >= replacement) return false;
    long rendered = 64;
    for (var i : f.apeTag().itemListMap().values()) rendered += i.render().size();
    return rendered > replacement
      && replacement > (f.id3v1Tag() != null && !f.id3v1Tag().isEmpty() ? 128 : 0);
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    try (var paths = Files.list(root.resolve("inputs"))) {
      for (var input : paths.sorted().toList()) {
        String id = input.getFileName().toString();
        byte[] data = Files.readAllBytes(input);
        for (var style : ReadStyle.values())
          for (boolean enabled : new boolean[]{false, true}) {
            String base = id + "/controls/" + style.ordinal() + "/" + b(enabled);
            try (var f = new MpcFile(input, new ReadOptions(enabled, style, ReadOptions.DEFAULT.maxMetadataBytes()))) {
              state(base + "/path", f);
            }
            for (int origin : new int[]{0, 7}) {
              var s = new ByteVectorStream(data);
              s.seek(origin);
              try (var m = new MpcFile(s, new ReadOptions(enabled, style, ReadOptions.DEFAULT.maxMetadataBytes()))) {
                state(base + "/io/" + origin, m);
              }
            }
          }
        for (int origin : new int[]{0, 7})
          for (long length :
            new long[]{-128, -1, 0, 1, 32, 128, Integer.MAX_VALUE, Long.MAX_VALUE})
            for (var style : ReadStyle.values()) {
              var s = new ByteVectorStream(data);
              try (var f = new MpcFile(s, ReadOptions.TAGS_ONLY)) {
                f.seek(origin);
                var p = new cz.skyba.taglib.mpc.Properties(f, length, style);
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
                    + p.mpcVersion()
                    + "\t"
                    + Long.toUnsignedString(p.sampleFrames())
                    + "\t"
                    + p.totalFrames()
                    + "\t"
                    + p.trackGain()
                    + "\t"
                    + p.trackPeak()
                    + "\t"
                    + p.albumGain()
                    + "\t"
                    + p.albumPeak()
                    + "\t"
                    + f.tell());
              }
            }
        for (int origin : new int[]{0, 7}) {
          var s = new ByteVectorStream(data);
          s.seek(origin);
          boolean supported = MpcFile.isSupported(s);
          System.out.println(id + "/support/" + origin + "\t" + b(supported) + "\t" + s.tell());
        }
        for (String name : List.of("sample.mpc", "sample.unknown"))
          for (int origin : new int[]{0, 7})
            for (boolean enabled : new boolean[]{false, true}) {
              var stream = new Named(data, name, false);
              stream.seek(origin);
              try (var ref = new cz.skyba.taglib.parity.support.FileRefAdapter(stream, enabled)) {
                var f = ref.file() instanceof MpcFile actual ? actual : null;
                String base = id + "/ref/" + name + "/" + origin + "/" + b(enabled);
                System.out.println(
                  base + "\t" + b(ref.isNull()) + "\t" + b(f != null));
                if (f != null) state(base, f);
              }
            }
        for (int mode = 0; mode < 11; mode++) {
          String base = id + "/edit/" + mode;
          var s = new ByteVectorStream(data);
          try (var m = new MpcFile(s)) {
            edit(m, mode);
            state(base + "/io", m);
            if (undefinedMemoryGrowth(m, new ByteVector(data))) {
              long before = m.tell();
              var footer = m.apeTag().footer().renderFooter();
              if (SaveOutcome.save(m)
                || m.tell() != before
                || !Arrays.equals(data, s.data().data())
                || !footer.equals(m.apeTag().footer().renderFooter()))
                throw new AssertionError(
                  "Undefined native memory span must reject without mutation");
              System.out.println(base + "/io/undefined-input\t" + hex(new ByteVector(data)));
            } else {
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
          }

          Path copy = root.resolve("outputs").resolve(id + "." + mode + ".java");
          Files.copy(input, copy, StandardCopyOption.REPLACE_EXISTING);
          try (var f = new MpcFile(copy)) {
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
    for (long length : new long[]{-10518287262L, -10518287260L, -10518287258L}) {
      try (var f =
             new MpcFile(new ByteVectorStream(Files.readAllBytes(root.resolve("inputs/sv7-prefix-056.mpc"))), ReadOptions.TAGS_ONLY)) {
        f.seek(0);
        var p = new cz.skyba.taglib.mpc.Properties(f, length);
        System.out.println("bitrate/lower-bound/" + length + "\t" + p.bitrate() + "\t" + f.tell());
      }
    }
    try (var f =
           new MpcFile(new ByteVectorStream(Files.readAllBytes(root.resolve("inputs/sv7-prefix-056.mpc"))), ReadOptions.TAGS_ONLY)) {
      f.seek(0);
      try {
        new cz.skyba.taglib.mpc.Properties(f, -10518287263L);
        throw new AssertionError("Native undefined negative cast must reject");
      } catch (IllegalArgumentException expected) {
        if (!expected.getMessage().contains("Undefined native")) throw expected;
      }
    }
    System.out.println("support/null\t" + b(MpcFile.isSupported(null)));
    System.out.println("open-stream/rejected\t" + b(OpenOutcome.rejected(
      () -> new FileStream(root.resolve("missing.mpc")))));
    System.out.println("open-file/rejected\t" + b(OpenOutcome.rejected(
      () -> new MpcFile(root.resolve("missing.mpc")))));
    var stream =
      new Named(
        Files.readAllBytes(root.resolve("inputs/sv7-prefix-056.mpc")), "sample.mpc", true);
    try (var ref = new cz.skyba.taglib.parity.support.FileRefAdapter(stream)) {
      var f = ref.file() instanceof MpcFile actual ? actual : null;
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
