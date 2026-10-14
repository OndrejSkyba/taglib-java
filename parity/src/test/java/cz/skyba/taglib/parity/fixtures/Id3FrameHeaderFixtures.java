package cz.skyba.taglib.parity.fixtures;

import cz.skyba.taglib.parity.support.Parity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;

import static cz.skyba.taglib.parity.fixtures.Bytes.*;
import static cz.skyba.taglib.parity.fixtures.TagBytes.sync;

final class Id3FrameHeaderFixtures {
  private final StringBuilder rows = new StringBuilder();
  private int count;

  private void add(String kind, byte[] data, long version, long number, byte[] other) {
    rows.append(kind)
      .append('-')
      .append(count++)
      .append('\t')
      .append(kind)
      .append('\t')
      .append(CaseTable.hexField(data))
      .append('\t')
      .append(version)
      .append('\t')
      .append(Long.toUnsignedString(number))
      .append('\t')
      .append(CaseTable.hexField(other))
      .append('\n');
  }

  private static byte[] frame(byte[] body, int version, int flags) {
    return join(
      text("X001"),
      version == 3 ? uint(body.length, 4, false) : sync(body.length),
      uint(flags, 2, false),
      body);
  }

  private static byte[] compressed(byte[] input) {
    var d = new Deflater();
    d.setInput(input);
    d.finish();
    var out = new java.io.ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    while (!d.finished()) out.write(buffer, 0, d.deflate(buffer));
    d.end();
    return out.toByteArray();
  }

  static void generate(Path root) throws Exception {
    var g = new Id3FrameHeaderFixtures();
    var r = new FixtureRandom(0x232FBA5E);
    byte[] empty = new byte[0];
    for (int v : new int[]{3, 4})
      for (int f = 0; f < 65536; f++)
        g.add("H", join(text("AB12"), hex("00000100"), uint(f, 2, false)), v, f, text("NEWID"));
    for (long v : new long[]{0, 1, 2, 3, 4, 5, 255, 0x80000000L, 0xffffffffL})
      for (int n = 0; n < 15; n++)
        for (long size : new long[]{0, 1, 127, 128, 255, 256, 0xfffffff6L, 0xffffffffL}) {
          byte[] b = join(text("AB12"), uint(size, 4, false), hex("ffff"), text("TAIL"));
          g.add("H", slice(b, 0, n), v, size, slice(b, 0, n % 6));
        }
    for (int n = 128; n < 270; n++)
      for (int v : new int[]{4, 5})
        for (boolean both : new boolean[]{false, true}) {
          byte[] b = join(text("AB12"), uint(n, 4, false), new byte[2], new byte[n + 4]);
          System.arraycopy(text("NEXT"), 0, b, 10 + n, 4);
          if (both) {
            byte[] wire = uint(n, 4, false);
            int candidate = n;
            if (Arrays.stream(
                new int[]{wire[0] & 255, wire[1] & 255, wire[2] & 255, wire[3] & 255})
              .noneMatch(x -> x >= 128))
              candidate =
                ((wire[0] & 255) << 21)
                  + ((wire[1] & 255) << 14)
                  + ((wire[2] & 255) << 7)
                  + (wire[3] & 255);
            System.arraycopy(text("NEXT"), 0, b, 10 + candidate, 4);
          }
          g.add("H", b, v, n, text("XYZ"));
        }
    for (long v : new long[]{0, 1, 2, 3, 4, 5, 0xffffffffL})
      for (int n = 0; n < 22; n++)
        for (int f : new int[]{0, 1, 2, 4, 8, 0x40, 0x80, 0xff, 0xff00, 0xffff})
          g.add(
            "F",
            slice(frame(text("payload"), 4, f), 0, n),
            v,
            0xffffffffL,
            frame(text("changed"), 3, f));
    for (int v : new int[]{3, 4})
      for (int f : new int[]{0, 1, 2, 4, 8, 12, 0x40, 0x80, 0xc0, 0xff, 0xffff})
        for (long n : new long[]{0, 1, 3, 4, 5, 127, 128, 0xffffffffL})
          g.add(
            "F",
            join(
              text("X001"),
              v == 3 ? uint(n, 4, false) : sync(n),
              uint(f, 2, false),
              hex("00000003"),
              text("abcTAIL")),
            v,
            n,
            frame(text("update"), 4, 0));
    for (byte[] raw :
      new byte[][]{
        empty,
        text("a"),
        text("abcdef"),
        range(256),
        repeat('A', 1024),
        repeat('B', 2048),
        repeat('C', 10000)
      }) {
      byte[] z = compressed(raw);
      for (byte[] payload :
        new byte[][]{
          z,
          slice(z, 0, z.length - 1),
          slice(z, 0, z.length - 4),
          slice(z, 0, 2),
          join(z, text("garbage")),
          text("broken")
        }) {
        var sizes = new TreeSet<Long>();
        for (long n :
          new long[]{
            0,
            1,
            raw.length,
            Math.max(0, raw.length - 1),
            raw.length + 1,
            1024,
            64L * payload.length,
            64L * payload.length + 1,
            64 * 1024 * 1024,
            64 * 1024 * 1024 + 1
          })
          sizes.add(n);
        for (long n : sizes)
          for (int[] pair : new int[][]{{3, 0x80}, {4, 8}, {4, 12}, {4, 9}}) {
            byte[] b = frame(join(sync(n), payload), pair[0], pair[1]);
            g.add("F", b, pair[0], n, b);
          }
      }
    }
    for (int i = 0; i < 800; i++) {
      byte[] b = r.bytes(r.below(96)), other = r.bytes(r.below(32));
      g.add("F", b, r.choose(new Integer[]{0, 2, 3, 4, 5}), r.bits(32), other);
    }
    for (int e = 0; e < 5; e++)
      for (byte[] b :
        new byte[][]{
          empty,
          text("a"),
          hex("61006200"),
          new byte[2],
          hex("fffe410000004200"),
          hex("feff00410000"),
          hex("004100000042"),
          hex("410000"),
          hex("ff0000")
        })
        for (long pos : new long[]{0, 1, 2, 3, b.length, b.length + 1, 0xffffffffL})
          g.add("R", b, e, pos, empty);
    for (long v : new long[]{0, 2, 3, 4, 5, 0xffffffffL})
      for (int e = 0; e < 5; e++)
        for (String a : new String[]{"", "ascii", "café", "Žluť", "🎵"})
          for (String b : new String[]{"", "ascii", "café", "Žluť", "🎵"})
            g.add(
              "E", a.getBytes(StandardCharsets.UTF_8), e, v, b.getBytes(StandardCharsets.UTF_8));
    for (var pair : translations(Parity.corpus().getParent().getParent())) {
      String key = pair.getValue();
      for (String value : new String[]{key, key.toLowerCase(Locale.ROOT), key.toLowerCase(Locale.ROOT)})
        g.add("K", value.getBytes(StandardCharsets.UTF_8), 4, 0, text(pair.getKey()));
    }
    for (String[] pair :
      new String[][]{
        {"TRDA", "DATE"},
        {"TYER", "DATE"},
        {"TDAT", "DATE"},
        {"TIME", "DATE"},
        {"TORY", "ORIGINALDATE"},
        {"TXXX", "TITLE"},
        {"TIT2X", "title"},
        {"TIPL", "INVOLVEDPEOPLE"},
        {"WXXX", "URL"},
        {"UNKNOWN", ""},
        {"TDTG", "TAGGINGTIME"},
        {"TIT2", "títle"}
      })
      g.add("K", pair[1].getBytes(StandardCharsets.UTF_8), 4, 0, text(pair[0]));
    var files = new GeneratedFiles(root);
    files.text("cases.tsv", g.rows.toString());
  }

  static List<Map.Entry<String, String>> translations(Path source) throws Exception {
    String section = Files.readString(source.resolve("taglib/mpeg/id3v2/id3v2frame.cpp"))
      .split("constexpr std::array frameTranslation \\{", 2)[1].split("};", 2)[0];
    Matcher matcher = Pattern.compile("(?m)^\\s*std::pair\\(\"([^\"]+)\", \"([^\"]+)\"\\)").matcher(section);
    var pairs = new ArrayList<Map.Entry<String, String>>();
    while (matcher.find()) pairs.add(Map.entry(matcher.group(1), matcher.group(2)));
    return pairs;
  }
}
