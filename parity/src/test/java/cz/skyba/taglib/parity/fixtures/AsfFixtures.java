package cz.skyba.taglib.parity.fixtures;

import cz.skyba.taglib.parity.support.Parity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static cz.skyba.taglib.parity.fixtures.Bytes.*;

final class AsfFixtures {
  static final String HEADER = "3026b2758e66cf11a6d900aa0062ce6c",
    FILE = "a1dcab8c47a9cf118ee400c00c205365",
    STREAM = "9107dcb7b7a9cf118ee600c00c205365",
    CONTENT = "3326b2758e66cf11a6d900aa0062ce6c",
    EXTENDED = "40a4d0d207e3d21197f000a0c95ea850",
    EXTENSION = "b503bf5f2ea9cf118ee300c00c205365",
    METADATA = "eacbf8c5af5b77488467aa8c44fa4cca",
    LIBRARY = "941c23449894d149a1411d134e457054",
    LIST = "4052d1861d31d011a3a400a0c90348f6";

  static byte[] object(String id, byte[] data) {
    return declared(id, data.length + 24, data);
  }

  private static byte[] declared(String id, long size, byte[] data) {
    return join(hex(id), uint(size, 8, true), data);
  }

  static byte[] utf16(String s) {
    return join(TagBytes.encoded(s, 4), new byte[2]);
  }

  private static byte[] attr(
    String name, int type, byte[] data, int kind, int stream, int language) {
    byte[] n = utf16(name);
    return join(
      kind == 0
        ? join(uint(n.length, 2, true), n, pack("<HH", type, data.length))
        : join(
        pack("<HHHHI", kind == 2 ? language : 0, stream, n.length, type, data.length), n),
      data);
  }

  private static byte[] ext(String name, int type, byte[] data) {
    return attr(name, type, data, 0, 0, 0);
  }

  private static byte[] scoped(String name, int type, byte[] data, int stream, int language) {
    return attr(name, type, data, 2, stream, language);
  }

  private static byte[] carrier(String id, byte[]... rows) {
    return object(id, join(uint(rows.length, 2, true), join(rows)));
  }

  private static byte[] extension(byte[]... children) {
    byte[] b = join(children);
    return object(
      EXTENSION, join(hex("11d2d3abbaa9cf118ee600c00c205365"), pack("<HI", 6, b.length), b));
  }

  private static byte[] content(String... values) {
    var sizes = new ArrayList<byte[]>();
    var data = new ArrayList<byte[]>();
    for (String value : values) {
      byte[] b = utf16(value);
      sizes.add(uint(b.length, 2, true));
      data.add(b);
    }
    return object(CONTENT, join(join(sizes), join(data)));
  }

  static byte[] fileData(long duration, long preroll) {
    byte[] b = new byte[80];
    field(b, 40, 8, duration, true);
    field(b, 56, 8, preroll, true);
    return b;
  }

  static byte[] streamData(int codec) {
    byte[] b = new byte[70];
    System.arraycopy(pack("<HHII", codec, 2, 44100, 16000), 0, b, 54, 12);
    field(b, 68, 2, 16, true);
    return b;
  }

  static byte[] header(byte[] body, int count, byte[] tail) {
    return join(hex(HEADER), pack("<QI", body.length + 30, count), hex("0102"), body, tail);
  }

  static byte[] base(byte[] file, byte[] stream, byte[]... extras) {
    return header(
      join(object(FILE, file), object(STREAM, stream), join(extras)),
      2 + extras.length,
      new byte[0]);
  }

  private static byte[] mutableBase(byte[]... extras) {
    return join(base(fileData(10000000, 0), streamData(0x161), extras), text("audio-opaque-bytes"));
  }

  static void mutable(Path root) throws Exception {
    var f = new GeneratedFiles(root);
    f.put("plain.wma", mutableBase());
    f.put("content.wma", mutableBase(content("Title", "Artist", "Copyright", "Comment", "Rating")));
    f.put("foreign.wma", text("foreign"));
    f.put("empty.wma", new byte[0]);
    for (int n = 16; n < 31; n++) f.put("partial-header-" + n + ".wma", slice(mutableBase(), 0, n));
    String[] carriers = {EXTENDED, METADATA, LIBRARY};
    for (int kind = 0; kind < 3; kind++) {
      byte[] c =
        carrier(
          carriers[kind],
          attr("WM/AlbumTitle", 0, utf16("album"), kind, 0, 0),
          attr("WM/AlbumTitle", 0, utf16("second"), kind, 43, 56),
          attr("Unknown", 1, text("opaque"), kind, 0, 0),
          attr("WM/TrackNumber", 3, uint(-1, 4, true), kind, 0, 0));
      f.put("carrier-" + kind + ".wma", mutableBase(kind == 0 ? c : extension(c)));
    }
    for (int type = 0; type < 7; type++)
      for (int n = 0; n < 10; n++) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i + 1);
        f.put(
          "partial-scalar-" + type + "-" + n + ".wma",
          mutableBase(carrier(EXTENDED, ext("WM/AlbumTitle", type, b))));
      }
    for (int type = 0; type < 256; type++) {
      byte[] p =
        join(
          new byte[]{(byte) type},
          uint(3, 4, true),
          utf16("image/test"),
          utf16("desc"),
          text("xyz"));
      f.put(
        "picture-type-" + type + ".wma", mutableBase(carrier(EXTENDED, ext("WM/Picture", 1, p))));
    }
    byte[] p =
      join(new byte[]{3}, uint(3, 4, true), utf16("image/test"), utf16("desc"), text("xyz"));
    for (int n = 0; n <= p.length; n++)
      f.put(
        "picture-prefix-" + n + ".wma",
        mutableBase(carrier(EXTENDED, ext("WM/Picture", 1, slice(p, 0, n)))));
    for (long n : new long[]{0, 1, 2, 4, 0x7fffffff, 0x80000000L, 0xffffffffL})
      f.put(
        "picture-length-" + n + ".wma",
        mutableBase(
          carrier(
            EXTENDED,
            ext(
              "WM/Picture",
              1,
              join(slice(p, 0, 1), uint(n, 4, true), slice(p, 5, p.length))))));
    for (String value : new String[]{"", "a\0b", "é音", "\ud800", "\udfff", "two words"})
      f.put(
        "string-" + GeneratedFiles.hash(TagBytes.encoded(value, 4)).substring(0, 8) + ".wma",
        mutableBase(
          carrier(
            EXTENDED, ext("WM/AlbumTitle", 0, utf16(value)), ext("Unknown", 0, utf16(value))),
          content(value, value, value, value, value)));
    f.put(
      "duplicates.wma",
      mutableBase(
        content("one", "one", "one", "one", "one"),
        content("two", "two", "two", "two", "two"),
        carrier(EXTENDED, ext("WM/AlbumTitle", 0, utf16("one"))),
        carrier(EXTENDED, ext("WM/AlbumTitle", 0, utf16("two"))),
        extension(carrier(METADATA, attr("WM/AlbumTitle", 0, utf16("three"), 1, 43, 0))),
        extension(carrier(LIBRARY, attr("WM/AlbumTitle", 0, utf16("four"), 2, 44, 56)))));
    f.put(
      "case-names.wma",
      mutableBase(
        carrier(
          EXTENDED,
          ext("WM/AlbumTitle", 0, utf16("one")),
          ext("wm/albumtitle", 0, utf16("two")),
          ext("REPLAYGAIN_TRACK_GAIN", 0, utf16("-6 dB")))));
    try (var paths = Files.list(Parity.corpus())) {
      for (Path path : paths.sorted().toList())
        if (path.toString().endsWith(".asf") || path.toString().endsWith(".wma"))
          f.put(
            "upstream-" + path.getFileName().toString().replace('.', '-') + ".wma",
            Files.readAllBytes(path));
    }
  }

  private static Map<String, List<byte[]>> encodingObjects() {
    var variants = new LinkedHashMap<String, List<byte[]>>();
    variants.put(
      "encoding-embedded-nul",
      List.of(
        content("head\0tail", "artist\0tail", "", "comment\0tail", ""),
        carrier(
          EXTENDED,
          ext("WM/AlbumTitle\0ignored", 0, utf16("album\0tail")),
          ext("Opaque\0ignored", 0, utf16("opaque\0tail")))));
    variants.put(
      "encoding-unpaired-utf16",
      List.of(
        content("\ud800", "", "", "", ""),
        carrier(EXTENDED, ext("WM/AlbumTitle", 0, utf16("\udc00")))));
    variants.put(
      "encoding-odd-utf16",
      List.of(
        carrier(EXTENDED, ext("WM/AlbumTitle", 0, join(TagBytes.encoded("odd", 4), hex("7f")))),
        extension(
          carrier(
            LIBRARY,
            scoped("WM/Genre", 0, join(TagBytes.encoded("Jazz", 4), hex("7f")), 0, 0)))));
    return variants;
  }

  private static long le(byte[] b, int at, int width) {
    long n = 0;
    for (int i = 0; i < width; i++) n |= (b[at + i] & 255L) << (8 * i);
    return n;
  }

  static void duplicateObjects(Path root) throws Exception {
    var f = new GeneratedFiles(root);
    byte[] source = Files.readAllBytes(Parity.corpus().resolve("silence-1.wma"));
    int oldsize = (int) le(source, 16, 8), count = (int) le(source, 24, 4);
    byte[] prefix = slice(source, 30, oldsize), tail = slice(source, oldsize, source.length);
    byte[]
      e1 =
      carrier(
        EXTENDED,
        ext("WM/AlbumTitle", 0, utf16("first")),
        ext("Opaque", 1, text("original"))),
      e2 =
        carrier(
          EXTENDED,
          ext("WM/AlbumTitle", 0, utf16("second")),
          ext("Opaque", 1, text("duplicate"))),
      l1 =
        carrier(
          LIBRARY,
          scoped("Opaque", 1, text("keep1"), 7, 9),
          scoped("PeakValue", 3, uint(17, 4, true), 2, 4)),
      l2 =
        carrier(
          LIBRARY,
          scoped("Opaque", 1, text("keep2"), 3, 8),
          scoped("WM/AlbumTitle", 0, utf16("scoped"), 1, 2)),
      metadata =
        carrier(
          METADATA,
          scoped("IsVBR", 2, uint(1, 2, true), 5, 0),
          scoped("WM/AlbumTitle", 0, utf16("stream"), 7, 0));
    byte[]
      large =
      carrier(
        LIBRARY,
        scoped("Opaque", 1, join(Collections.nCopies(256, range(256))), 0, 0),
        scoped("WM/MediaClassPrimaryID", 6, range(16), 0, 0),
        scoped("IsVBR", 2, uint(1, 2, true), 9, 3)),
      lower =
        carrier(
          EXTENDED,
          ext("wm/albumtitle", 0, utf16("opaque lower")),
          ext("WM/AlbumTitle", 0, utf16("canonical"))),
      reserved = extension(l1);
    System.arraycopy(range(18), 0, reserved, 24, 18);
    var variants = new LinkedHashMap<String, List<byte[]>>();
    variants.put("duplicate-extended", List.of(e1, e2));
    variants.put("case-sensitive", List.of(lower));
    variants.put(
      "numeric-legacy",
      List.of(
        carrier(
          EXTENDED,
          ext("WM/Track", 5, uint(65535, 2, true)),
          ext("IsVBR", 2, uint(65536, 4, true)))));
    variants.put("reserved-extension", List.of(reserved));
    variants.put("root-library-unknown", List.of(l1));
    variants.put("nested-extended-unknown", List.of(extension(e1)));
    variants.put(
      "duplicate-content",
      List.of(
        content("old", "old artist", "old copyright", "old comment", "old rating"),
        content("", "", "", "", "last rating")));
    variants.put("duplicate-library", List.of(extension(l1, l2, metadata)));
    variants.put("duplicate-extension", List.of(extension(l1, metadata), extension(l2)));
    variants.put("carrier-boundaries", List.of(e1, extension(l1, metadata, large)));
    variants.putAll(encodingObjects());
    for (var entry : variants.entrySet())
      f.raw(
        "physical/" + entry.getKey() + ".wma",
        header(join(prefix, join(entry.getValue())), count + entry.getValue().size(), tail));
    String[] sizeNames = {"zero", "prefix-only", "short", "beyond-file"};
    long[] sizes = {0, 30, oldsize - 4, source.length + 100};
    for (int i = 0; i < sizes.length; i++) {
      byte[] b = source.clone();
      field(b, 16, 8, sizes[i], true);
      f.raw("constructor/header-size-" + sizeNames[i] + ".wma", b);
    }
    String[] countNames = {
      "short", "extra-data-object", "missing-stream", "zero", "maximum", "above-maximum"
    };
    int[] counts = {count - 1, count + 1, count - 2, 0, 50000, 50001};
    for (int i = 0; i < counts.length; i++) {
      byte[] b = source.clone();
      field(b, 24, 4, counts[i], true);
      f.raw("constructor/object-count-" + countNames[i] + ".wma", b);
    }
    f.raw(
      "constructor/unknown-extended-type.wma",
      header(
        join(prefix, carrier(EXTENDED, ext("Opaque", 9, text("not-consumed")))),
        count + 1,
        tail));
    f.raw(
      "constructor/unknown-library-type.wma",
      header(
        join(
          prefix,
          extension(carrier(LIBRARY, scoped("Opaque", 9, text("not-consumed"), 7, 9)))),
        count + 1,
        tail));
    f.raw(
      "constructor/unknown-metadata-type.wma",
      header(
        join(
          prefix,
          extension(carrier(METADATA, scoped("Opaque", 9, text("not-consumed"), 7, 0)))),
        count + 1,
        tail));
    for (var entry : encodingObjects().entrySet())
      f.raw(
        "constructor/" + entry.getKey() + ".wma",
        header(join(prefix, join(entry.getValue())), count + entry.getValue().size(), tail));
    byte[] file = null, stream = null;
    int at = 30;
    for (int i = 0; i < count; i++) {
      int size = (int) le(source, at + 16, 8);
      byte[] b = slice(source, at, at + size);
      String id = HexFormat.of().formatHex(slice(b, 0, 16));
      if (id.equals(FILE)) file = b;
      if (id.equals(STREAM)) stream = b;
      at += size;
    }
    if (file == null || stream == null)
      throw new IllegalStateException("Missing ASF property objects");
    byte[] wrong = stream.clone();
    System.arraycopy(range(16), 0, wrong, 24, 16);
    byte[] shortFile = object(FILE, slice(file, 24, 87)),
      shortStream = object(STREAM, slice(stream, 24, 93));
    var constructor = new LinkedHashMap<String, List<byte[]>>();
    constructor.put("stream-non-audio-guid", List.of(file, wrong));
    constructor.put("stream-short-properties", List.of(file, shortStream));
    constructor.put("file-short-properties", List.of(shortFile, stream));
    constructor.put("duplicate-short-properties", List.of(file, stream, shortFile, shortStream));
    for (var entry : constructor.entrySet())
      f.raw(
        "constructor/" + entry.getKey() + ".wma",
        header(join(entry.getValue()), entry.getValue().size(), new byte[0]));
    var invalid = new LinkedHashMap<String, byte[]>();
    invalid.put("extension-short-extent", declared(EXTENSION, 45, new byte[0]));
    invalid.put(
      "extension-data-too-large", declared(EXTENSION, 46, join(new byte[18], uint(1, 4, true))));
    invalid.put("extension-child-short", extension(declared(LIBRARY, 23, new byte[0])));
    invalid.put("extended-short-extent", declared(EXTENDED, 25, new byte[0]));
    invalid.put("extended-beyond-file", declared(EXTENDED, source.length + 1, new byte[0]));
    invalid.put("extended-count-beyond-end", declared(EXTENDED, 26, uint(1, 2, true)));
    invalid.put(
      "extended-attribute-crosses-end",
      declared(EXTENDED, 28, join(uint(1, 2, true), ext("", 0, new byte[0]))));
    byte[] trailer = object("00112233445566778899aabbccddeeff", text("last-object-payload"));
    for (var entry : invalid.entrySet())
      f.raw(
        "constructor/" + entry.getKey() + ".wma",
        header(join(file, stream, entry.getValue(), trailer), 4, text("end-sentinel----")));
  }
}
