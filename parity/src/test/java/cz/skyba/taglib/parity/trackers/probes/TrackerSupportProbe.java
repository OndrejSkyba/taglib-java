package cz.skyba.taglib.parity.trackers.probes;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.TagBase;
import cz.skyba.taglib.parity.support.FileRefAdapter;
import cz.skyba.taglib.parity.support.OpenOutcome;
import cz.skyba.taglib.parity.support.SaveOutcome;
import cz.skyba.taglib.shorten.ShortenFile;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.IOStream;
import cz.skyba.taglib.xm.XmFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

public class TrackerSupportProbe {
  static String hex(String s) {
    return HexFormat.of().formatHex(ByteVector.fromText(s, UTF_8).toByteArray());
  }

  static String hex(ByteVector b) {
    return HexFormat.of().formatHex(b.toByteArray());
  }

  static String map(cz.skyba.taglib.PropertyMap p) {
    var s = new StringBuilder().append(p.asMap().size());
    for (var v : p.asMap().entrySet()) {
      s.append(':').append(hex(v.getKey())).append(':').append(v.getValue().size());
      for (var t : v.getValue()) s.append(':').append(hex(t));
    }
    s.append(':').append(p.unsupportedData().size());
    for (var t : p.unsupportedData()) s.append(':').append(hex(t));
    return s.toString();
  }

  static void tag(String id, TagBase t, String tracker) {
    System.out.println(
      id
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
        + t.track()
        + "\t"
        + (t.isEmpty() ? 1 : 0)
        + "\t"
        + hex(tracker)
        + "\t"
        + map(t.properties()));
  }

  static final class Helpers extends cz.skyba.taglib.mod.FileBase {
    Helpers(IOStream s) {
      super(s);
    }

    public TagBase tag() {
      return null;
    }

    public AudioProperties audioProperties() {
      return null;
    }

    public void save() {
      throw new cz.skyba.taglib.TagLibException("Test save rejected");
    }

    public boolean readString(java.util.function.Consumer<String> o, long n) {
      return super.readString(o, n);
    }

    public boolean readByte(java.util.function.IntConsumer o) {
      return super.readByte(o);
    }

    public boolean readU16L(java.util.function.IntConsumer o) {
      return super.readU16L(o);
    }

    public boolean readU16B(java.util.function.IntConsumer o) {
      return super.readU16B(o);
    }

    public boolean readU32L(java.util.function.LongConsumer o) {
      return super.readU32L(o);
    }

    public boolean readU32B(java.util.function.LongConsumer o) {
      return super.readU32B(o);
    }

    public void writeString(String s, long n, byte pad) {
      super.writeString(s, n, pad);
    }

    public void writeByte(int v) {
      super.writeByte(v);
    }

    public void writeU16L(int v) {
      super.writeU16L(v);
    }

    public void writeU16B(int v) {
      super.writeU16B(v);
    }

    public void writeU32L(long v) {
      super.writeU32L(v);
    }

    public void writeU32B(long v) {
      super.writeU32B(v);
    }
  }

  static final class Named extends ByteVectorStream {
    String fileName;
    StringBuilder trace = new StringBuilder();
    boolean once, zeroLength;
    long onceOffset = 1080;
    int markers;

    Named(ByteVector b, String name, boolean once) {
      super(b);
      fileName = name;
      this.once = once;
    }

    public String name() {
      return fileName;
    }

    public long length() {
      return zeroLength ? 0 : super.length();
    }

    public ByteVector readBlock(long count) {
      long at = tell();
      var b = once && at == onceOffset && ++markers > 1 ? new ByteVector() : super.readBlock(count);
      trace.append(at).append(',').append(count).append(',').append(b.size()).append(';');
      return b;
    }
  }

  static void tags() {
    String[] texts = {"", "Title", "Müller € 🎵", " first\nsecond\rthird "};
    for (int i = 0; i < texts.length; i++) {
      var t = new cz.skyba.taglib.mod.Tag();
      var sh = new cz.skyba.taglib.shorten.Tag();
      for (TagBase b : List.of(t, sh)) {
        b.setTitle(texts[i]);
        b.setArtist(texts[i]);
        b.setAlbum(texts[i]);
        b.setComment(texts[i]);
        b.setGenre(texts[i]);
        b.setYear(0xffffffffL);
        b.setTrack(0xffffffffL);
      }
      t.setTrackerName(texts[i]);
      tag("tag/mod/" + i, t, t.trackerName());
      tag("tag/shorten/" + i, sh, "");
    }
    for (int mask = 0; mask < 64; mask++) {
      var builder = cz.skyba.taglib.PropertyMap.builder();
      String[] keys = {"TITLE", "COMMENT", "TRACKERNAME", "ARTIST", "UNKNOWN", "EMPTY"};
      for (int i = 0; i < 6; i++)
        if ((mask & (1 << i)) != 0)
          builder.put(
            keys[i],
            i == 5 ? List.of() : i % 2 == 0 ? List.of("first", "extra") : List.of("first"));
      var p = builder.addUnsupportedData("opaque").build();
      var t = new cz.skyba.taglib.mod.Tag();
      t.setTitle("old");
      t.setComment("old");
      t.setTrackerName("old");
      var sh = new cz.skyba.taglib.shorten.Tag();
      String before = map(p);
      var rejected = t.replaceProperties(p).rejected();
      var shRejected = sh.replaceProperties(p).rejected();
      System.out.println(
        "import/"
          + mask
          + "\t"
          + before
          + "\t"
          + map(p)
          + "\t"
          + map(rejected)
          + "\t"
          + map(shRejected));
      tag("import/mod/" + mask, t, t.trackerName());
      tag("import/shorten/" + mask, sh, "");
    }
  }

  static void helpers() {
    byte[] seed = {(byte) 255, 'a', 0, 'b', (byte) 128, 127, 0x12, 0x34};
    for (int prefix = 0; prefix <= 8; prefix++)
      for (long width : new long[]{0, 1, 2, 4, 8, 9, 2147483648L}) {
        var s = new ByteVectorStream(Arrays.copyOf(seed, prefix));
        var h = new Helpers(s);
        String[] out = {"unchanged"};
        boolean ok = h.readString(v -> out[0] = v, width);
        System.out.println(
          "helper/string/"
            + prefix
            + "/"
            + width
            + "\t"
            + (ok ? 1 : 0)
            + "\t"
            + hex(out[0])
            + "\t"
            + s.tell());
      }
    for (int prefix = 0; prefix <= 8; prefix++)
      for (int kind = 0; kind < 5; kind++) {
        var s = new ByteVectorStream(Arrays.copyOf(seed, prefix));
        var h = new Helpers(s);
        int[] a = {42}, b = {4242};
        long[] c = {424242};
        boolean ok =
          switch (kind) {
            case 0 -> h.readByte(v -> a[0] = v);
            case 1 -> h.readU16L(v -> b[0] = v);
            case 2 -> h.readU16B(v -> b[0] = v);
            case 3 -> h.readU32L(v -> c[0] = v);
            default -> h.readU32B(v -> c[0] = v);
          };
        System.out.println(
          "helper/number/"
            + prefix
            + "/"
            + kind
            + "\t"
            + (ok ? 1 : 0)
            + "\t"
            + (kind == 0 ? a[0] : kind < 3 ? b[0] : c[0])
            + "\t"
            + s.tell());
      }
    for (long v : new long[]{0, 1, 255, 65535, 65536, 4294967295L, 4294967296L, -1}) {
      var s = new ByteVectorStream(new byte[0]);
      var h = new Helpers(s);
      h.writeByte((int) v);
      h.writeU16L((int) v);
      h.writeU16B((int) v);
      h.writeU32L(v);
      h.writeU32B(v);
      System.out.println(
        "helper/write/" + Long.toUnsignedString(v) + "\t" + hex(s.data()) + "\t" + s.tell());
    }
    for (long width : new long[]{0, 1, 2, 4, 8})
      for (int pad : new int[]{0, 127, 255}) {
        var s = new ByteVectorStream(new byte[0]);
        new Helpers(s).writeString("€ÿx", width, (byte) pad);
        System.out.println(
          "helper/write-string/" + width + "/" + pad + "\t" + hex(s.data()) + "\t" + s.tell());
      }
  }

  static void shortenState(String id, ShortenFile f, long cursor) {
    if (f == null) {
      System.out.println(id + "/rejected\t1");
      return;
    }
    var s =
      new StringBuilder(id)
        .append('\t')
        .append(f.isOpen() ? 1 : 0)
        .append('\t')
        .append(f.isValid() ? 1 : 0)
        .append('\t')
        .append(f.tag() != null ? 1 : 0)
        .append('\t')
        .append(f.detailedProperties() != null ? 1 : 0)
        .append('\t')
        .append(cursor);
    var p = f.detailedProperties();
    if (p != null)
      s.append('\t')
        .append(p.lengthInMilliseconds())
        .append('\t')
        .append(p.lengthInSeconds())
        .append('\t')
        .append(p.bitrate())
        .append('\t')
        .append(p.sampleRate())
        .append('\t')
        .append(p.channels())
        .append('\t')
        .append(p.bitsPerSample())
        .append('\t')
        .append(p.shortenVersion())
        .append('\t')
        .append(p.fileType())
        .append('\t')
        .append(Long.toUnsignedString(p.sampleFrames()));
    System.out.println(s);
  }

  static ShortenFile open(Path path) {
    return OpenOutcome.file(() -> new ShortenFile(path));
  }

  static ShortenFile open(Path path, ReadOptions options) {
    return OpenOutcome.file(() -> new ShortenFile(path, options));
  }

  static ShortenFile open(cz.skyba.taglib.toolkit.IOStream stream) {
    return OpenOutcome.file(() -> new ShortenFile(stream));
  }

  static ShortenFile open(cz.skyba.taglib.toolkit.IOStream stream, ReadOptions options) {
    return OpenOutcome.file(() -> new ShortenFile(stream, options));
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    tags();
    helpers();
    try (var paths = Files.list(root.resolve("inputs"))) {
      for (var p : paths.sorted().toList()) {
        String id = p.getFileName().toString();
        var data = new ByteVector(Files.readAllBytes(p));
        for (int origin : new int[]{0, 7}) {
          var pref = new ByteVector(origin, (byte) 'p').append(data);
          var s = new Named(pref, "input.shn", false);
          s.seek(origin);
          try (var f = open(s)) {
            shortenState(id + "/default/io/" + origin, f, s.tell());
            System.out.println(id + "/trace/" + origin + "\t" + s.trace);
            if (f != null) {
              boolean saved = SaveOutcome.save(f);
              System.out.println(
                id + "/save/" + origin + "\t" + (saved ? 1 : 0) + "\t" + s.tell() + "\t" + hex(s.data()));
            }
            long before = s.tell();
            boolean supported = ShortenFile.isSupported(s);
            System.out.println(
              id
                + "/supported/"
                + origin
                + "\t"
                + (supported ? 1 : 0)
                + "\t"
                + before
                + "\t"
                + s.tell());
          }
        }
        for (var style : ReadStyle.values())
          for (boolean enabled : new boolean[]{false, true}) {
            String base = id + "/controls/" + style.ordinal() + "/" + (enabled ? 1 : 0);
            try (var f = open(p, new ReadOptions(enabled, style, ReadOptions.DEFAULT.maxMetadataBytes()))) {
              shortenState(base + "/path", f, f == null ? 0 : f.tell());
            }
            for (int origin : new int[]{0, 7}) {
              var pref = new ByteVector(origin, (byte) 'p').append(data);
              var s = new ByteVectorStream(pref);
              s.seek(origin);
              try (var f = open(s, new ReadOptions(enabled, style, ReadOptions.DEFAULT.maxMetadataBytes()))) {
                shortenState(base + "/io/" + origin, f, s.tell());
              }
            }
          }
        try (var f = open(p)) {
          shortenState(id + "/default/path", f, f == null ? 0 : f.tell());
        }
      }
    }
    try (var missing = open(root.resolve("missing.shn"))) {
      shortenState("shorten/missing", missing, -1);
    }
    var mod = new ByteVector(Files.readAllBytes(root.resolve("valid.mod")));
    for (String ext : List.of("mod", "module", "nst", "wow"))
      for (int origin : new int[]{0, 7}) {
        var s = new Named(mod, "input." + ext, true);
        s.seek(origin);
        try (var r = new FileRefAdapter(s)) {
          System.out.println(
            "fileref/"
              + ext
              + "/"
              + origin
              + "\t"
              + (r.isNull() ? 1 : 0)
              + "\t"
              + (r.file() != null ? 1 : 0)
              + "\t"
              + s.tell()
              + "\t"
              + s.trace);
          if (!r.isNull())
            tag(
              "fileref/tag/" + ext + "/" + origin,
              r.tag(),
              ((cz.skyba.taglib.mod.Tag) r.tag()).trackerName());
        }
      }
    var it = new ByteVector(Files.readAllBytes(root.resolve("valid.it")));
    var unnamed = new Named(it, "input.dat", false);
    try (var r = new FileRefAdapter(unnamed)) {
      System.out.println(
        "fileref/it-unknown\t" + (r.isNull() ? 1 : 0) + "\t" + (r.file() != null ? 1 : 0));
    }
    var shn = new ByteVector(Files.readAllBytes(root.resolve("inputs/header-2-3.shn")));
    for (int zero : new int[]{0, 1}) {
      var s = new Named(shn, "input.shn", false);
      s.zeroLength = zero != 0;
      try (var ref = new FileRefAdapter(s)) {
        System.out.println(
          "fileref/shorten/"
            + zero
            + "\t"
            + (ref.isNull() ? 1 : 0)
            + "\t"
            + (ref.file() instanceof ShortenFile ? 1 : 0)
            + "\t"
            + s.tell()
            + "\t"
            + s.trace);
        if (ref.isNull()) throw new AssertionError("Shorten ref");
        shortenState(
          "fileref/shorten-state/" + zero, (ShortenFile) ref.file(), s.tell());
      }
    }
    var xm = new ByteVector(Files.readAllBytes(root.resolve("valid.xm")));
    var once = new Named(xm, "input.xm", true);
    try (var file = new XmFile(once)) {
      if (!file.isValid()) throw new AssertionError("XM ref");
      once.onceOffset = 60;
      once.markers = 0;
      once.trace.setLength(0);
      file.tag().setTitle("Changed");
      boolean saved = SaveOutcome.save(file);
      System.out.println(
        "xm/one-read-save\t" + (saved ? 1 : 0) + "\t" + once.tell() + "\t" + hex(once.data()));
      System.out.println("xm/save-trace\t" + once.trace);
    }
  }
}
