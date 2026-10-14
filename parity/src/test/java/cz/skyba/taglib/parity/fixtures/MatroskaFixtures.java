package cz.skyba.taglib.parity.fixtures;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static cz.skyba.taglib.parity.fixtures.Bytes.*;

final class MatroskaFixtures {
  private static byte[] e(long id, byte[] b) {
    return e(id, b, 1);
  }

  private static byte[] e(long id, byte[] b, int width) {
    while (b.length >= (1L << (7 * width)) - 1) width++;
    return join(
      uint(id, (64 - Long.numberOfLeadingZeros(id) + 7) / 8, false),
      uint((1L << (7 * width)) | b.length, width, false),
      b);
  }

  private static byte[] u(long id, long n) {
    return e(id, uint(n, Math.max(1, (64 - Long.numberOfLeadingZeros(n) + 7) / 8), false));
  }

  private static byte[] f(long id, double n) {
    return e(id, pack(">d", n));
  }

  private static byte[] t(long id, String value) {
    return e(id, value.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] info(double duration, long scale, byte[]... titles) {
    var parts = new ArrayList<byte[]>();
    parts.add(u(0x2ad7b1, scale));
    parts.add(f(0x4489, duration));
    for (byte[] title : titles) parts.add(e(0x7ba9, title));
    return e(0x1549a966, join(parts));
  }

  private static byte[] info() {
    return info(1000, 1000000, text("title"));
  }

  private static byte[] track(byte[] codec, long channels, long bits, double rate) {
    return e(
      0xae,
      join(e(0x86, codec), e(0xe1, join(u(0x9f, channels), u(0x6264, bits), f(0xb5, rate)))));
  }

  private static byte[] track() {
    return track(text("A_FLAC"), 2, 16, 44100);
  }

  private static byte[] header() {
    return join(e(0x4282, text("matroska")), u(0x4287, 4));
  }

  private static byte[] mka(byte[] header, byte[] segments) {
    return join(e(0x1a45dfa3, header), e(0x18538067, segments));
  }

  static void properties(Path root) throws Exception {
    var files = new GeneratedFiles(root);
    byte[] tracks = e(0x1654ae6b, track());
    for (byte[] value :
      new byte[][]{
        text(""),
        text("matroska"),
        text("webm"),
        text("custom"),
        hex("ff"),
        hex("757466382dc3a9"),
        text("a\0b"),
        repeat(' ', 12)
      })
      files.put(
        "doctype-" + HexFormat.of().formatHex(value) + ".mka",
        mka(join(e(0x4282, value), u(0x4287, 4)), join(info(), tracks)));
    for (int width = 0; width < 11; width++)
      files.put(
        "doc-version-" + width + ".mka",
        mka(
          join(e(0x4282, text("matroska")), e(0x4287, repeat(255, width))),
          join(info(), tracks)));
    for (byte[] tail : new byte[][]{hex("00"), hex("0000"), hex("42"), hex("4282"), hex("4282ff")})
      files.put(
        "partial-header-" + HexFormat.of().formatHex(tail) + ".mka",
        mka(join(e(0x4282, text("kept")), u(0x4287, 7), tail), join(info(), tracks)));
    files.put(
      "duplicate-header.mka",
      mka(
        join(
          e(0x4282, text("first")),
          u(0x4287, 1),
          e(0x4282, text("last")),
          u(0x4287, 0xffffffffL)),
        join(info(), tracks)));
    double[] durations = {
      0,
      -1,
      .125,
      .999,
      1,
      1000,
      1e-12,
      2147483646.5,
      2147483647,
      Double.NaN,
      Double.POSITIVE_INFINITY
    };
    String[] durationNames = {
      "0", "-1", "0.125", "0.999", "1", "1000", "1e-12", "2147483646.5", "2147483647", "nan", "inf"
    };
    for (int i = 0; i < durations.length; i++)
      for (long scale : new long[]{0, 1, 1000000, Long.MAX_VALUE, Long.MIN_VALUE, -1})
        files.put(
          "duration-" + durationNames[i] + "-" + Long.toUnsignedString(scale) + ".mka",
          mka(header(), join(info(durations[i], scale, text("title")), tracks)));
    for (byte[] codec :
      new byte[][]{
        text(""),
        text("A_FLAC"),
        text("A_PCM/INT/LIT"),
        text("A_AAC"),
        hex("ff"),
        hex("c3a9"),
        text("a\0b")
      })
      files.put(
        "codec-" + HexFormat.of().formatHex(codec) + ".mka",
        mka(header(), join(info(), e(0x1654ae6b, track(codec, 2, 16, 44100)))));
    for (byte[][] titles :
      new byte[][][]{
        {text(""), text("second")},
        {text("first"), text("second")},
        {hex("c3a9e99fb3")},
        {text("a\0b")},
        {hex("ff")},
        {hex("676f6f6400ff")},
        {}
      }) {
      var parts = new ArrayList<byte[]>();
      for (int i = 0; i < titles.length; i++) {
        if (i > 0) parts.add(text("|"));
        parts.add(titles[i]);
      }
      files.put(
        "title-" + GeneratedFiles.hash(join(parts)).substring(0, 16) + ".mka",
        mka(header(), join(info(1000, 1000000, titles), tracks)));
    }
    double[] rates = {
      -1, 0, .999, 44100.5, 2147483646.5, 2147483647, Double.NaN, Double.POSITIVE_INFINITY
    };
    String[] names = {"-1", "0", "0.999", "44100.5", "2147483646.5", "2147483647", "nan", "inf"};
    for (int i = 0; i < rates.length; i++)
      for (long[] pair :
        new long[][]{
          {0, 0}, {0, 16}, {2, 0}, {2, 16}, {0x80000000L, 16}, {2, 0x80000000L}, {-1, 16}
        })
        files.put(
          "track-" + names[i] + "-" + Long.toUnsignedString(pair[0]) + "-" + pair[1] + ".mka",
          mka(
            header(),
            join(
              info(),
              e(
                0x1654ae6b,
                join(
                  track(text("A_FLAC"), pair[0], pair[1], rates[i]),
                  track(text("fallback"), 1, 8, 48000))))));
    for (int width : new int[]{0, 1, 2, 3, 4, 5, 7, 8, 9})
      files.put(
        "float-width-" + width + ".mka",
        mka(
          header(),
          join(
            info(),
            e(
              0x1654ae6b,
              e(
                0xae,
                join(
                  e(0x86, text("width")),
                  e(
                    0xe1,
                    join(
                      u(0x9f, 2),
                      u(0x6264, 16),
                      e(0xb5, slice(pack(">d", 44100), 0, width))))))))));
    files.put(
      "two-audio.mka",
      mka(
        header(),
        join(
          info(),
          e(
            0x1654ae6b,
            join(track(text("first"), 2, 16, 44100), track(text("last"), 2, 16, 44100))))));
    files.put(
      "empty-first.mka",
      mka(
        header(),
        join(
          info(),
          e(
            0x1654ae6b,
            join(e(0xae, e(0x86, text("empty"))), track(text("last"), 2, 16, 44100))))));
    files.put(
      "codec-last.mka",
      mka(
        header(),
        join(
          info(),
          e(
            0x1654ae6b,
            e(
              0xae,
              join(
                e(0x86, text("first")),
                e(0x86, text("last")),
                e(0xe1, join(u(0x9f, 2), f(0xb5, 44100)))))))));
    files.put(
      "late-segment-properties.mka",
      mka(header(), join(e(0xec, new byte[600000]), info(), tracks)));
    files.copyExtensions("upstream-", "", ".mka", ".mkv", ".webm");
  }

  private static byte[] simple(String name, String value, int target) {
    return e(
      0x7373,
      join(e(0x63c0, u(0x68ca, target)), e(0x67c8, join(t(0x45a3, name), t(0x4487, value)))));
  }

  private static byte[] tags(String s) {
    return e(
      0x1254c367,
      join(
        simple("TITLE", s, 30),
        simple("TITLE", "Album", 50),
        simple("DATE_RECORDED", "2026-10-05", 30)));
  }

  private static byte[] attachment() {
    return e(
      0x1941a469,
      e(
        0x61a7,
        join(
          t(0x466e, "font.ttf"),
          e(0x4660, text("font/ttf")),
          u(0x46ae, 77),
          e(0x465c, hex("00ff464f4e54")))));
  }

  private static byte[] chapters() {
    return e(
      0x1043a770,
      e(
        0x45b9,
        join(
          u(0x45bc, 91),
          u(0x45db, 1),
          e(
            0xb6,
            join(
              u(0x73c4, 92),
              u(0x91, 123000),
              u(0x92, 999000),
              e(0x80, join(t(0x85, "Chapter"), e(0x437c, text("eng")))))))));
  }

  private static byte[] cues(int position) {
    return e(
      0x1c53bb6b,
      e(
        0xbb,
        join(
          u(0xb3, 0),
          e(
            0xb7,
            join(
              u(0xf7, 1),
              u(0xf1, position),
              u(0xf0, 3),
              u(0xb2, 10),
              u(0x5378, 1),
              u(0xea, position),
              e(0xdb, u(0x96, 7)))))));
  }

  private record Element(long id, byte[] bytes) {
  }

  static void mutable(Path root) throws Exception {
    var files = new GeneratedFiles(root);
    byte[] cluster = e(0x1f43b675, join(u(0xe7, 3), e(0xa3, hex("81000080617564696f44415441")))),
      info = info(1000, 1000000, text("Segment title")),
      tracks = e(0x1654ae6b, track()),
      header = e(0x1a45dfa3, header());
    for (String order : new String[]{"before", "after", "mixed"})
      for (int padding : new int[]{0, 1, 2, 16, 128, 300})
        for (int indexed = 0; indexed < 2; indexed++) {
          var fields =
            new ArrayList<Element>(
              List.of(new Element(0x1549a966, info), new Element(0x1654ae6b, tracks)));
          var data =
            new ArrayList<Element>(
              List.of(
                new Element(0x1254c367, tags("Old title")),
                new Element(0x1043a770, chapters()),
                new Element(0x1941a469, attachment())));
          if (padding != 0)
            for (int i = 0; i < data.size(); i++)
              data.set(
                i,
                new Element(data.get(i).id, join(data.get(i).bytes, e(0xec, new byte[padding]))));
          Element clusterElement = new Element(0x1f43b675, cluster),
            cuesElement = new Element(0x1c53bb6b, cues(0));
          if (order.equals("before")) {
            fields.addAll(data);
            fields.add(clusterElement);
            fields.add(cuesElement);
          } else if (order.equals("after")) {
            fields.add(clusterElement);
            fields.add(cuesElement);
            fields.addAll(data);
          } else {
            fields.add(data.get(0));
            fields.add(clusterElement);
            fields.addAll(data.subList(1, 3));
            fields.add(cuesElement);
          }
          byte[] head = new byte[0];
          for (int iteration = 0; iteration < 8; iteration++) {
            int position = head.length, clusterPosition = 0;
            var seeks = new ArrayList<byte[]>();
            for (Element element : fields) {
              seeks.add(
                e(0x4dbb, join(e(0x53ab, uint(element.id, 4, false)), u(0x53ac, position))));
              if (element.id == 0x1f43b675) clusterPosition = position;
              position += element.bytes.length;
            }
            for (int i = 0; i < fields.size(); i++)
              if (fields.get(i).id == 0x1c53bb6b)
                fields.set(i, new Element(0x1c53bb6b, cues(clusterPosition)));
            head =
              indexed == 1
                ? join(e(0x114d9b74, join(seeks)), e(0xec, new byte[32]))
                : new byte[0];
          }
          files.put(
            "layout-" + order + "-" + padding + "-" + indexed + ".mka",
            join(
              header,
              e(
                0x18538067,
                join(head, join(fields.stream().map(Element::bytes).toList())),
                8)));
        }
    for (int width : new int[]{1, 2, 8})
      files.put(
        "width-" + width + ".mka", join(header, e(0x18538067, join(info, tags("x")), width)));
    files.put("empty.mka", join(header, e(0x18538067, new byte[0], 8)));
    files.put("segment-title.mka", join(header, e(0x18538067, join(info, tracks, cluster), 8)));
    byte[] unknown = join(header, e(0x18538067, tags("Unknown"), 8));
    unknown =
      join(
        slice(unknown, 0, header.length + 4),
        new byte[]{1},
        repeat(255, 7),
        slice(unknown, header.length + 12, unknown.length));
    files.put("unknown-size.mka", unknown);
    files.put(
      "read-large.mka",
      join(
        header, e(0x18538067, join(e(0xec, new byte[600000]), info, tracks, tags("Late")), 8)));
    files.copyExtensions("upstream-", "", ".mka", ".mkv", ".webm");
  }
}
