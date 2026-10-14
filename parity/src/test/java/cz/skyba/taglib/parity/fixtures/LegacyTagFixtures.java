package cz.skyba.taglib.parity.fixtures;

import cz.skyba.taglib.parity.support.Parity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;

import static cz.skyba.taglib.parity.fixtures.Bytes.*;
import static cz.skyba.taglib.parity.fixtures.TagBytes.riffChunk;
import static cz.skyba.taglib.parity.fixtures.TagBytes.wave;

final class LegacyTagFixtures {
  private final GeneratedFiles files;
  private final StringBuilder cases = new StringBuilder();

  private LegacyTagFixtures(Path root) throws Exception {
    files = new GeneratedFiles(root);
  }

  private void put(String kind, String name, byte[] bytes) throws Exception {
    files.put(name, bytes);
    cases.append(kind).append('\t').append(name).append('\n');
  }

  private static byte[] field(String id, byte[] b, long declared, boolean pad) {
    return join(text(id), uint(declared, 4, true), b, new byte[pad ? b.length % 2 : 0]);
  }

  private static byte[] field(String id, byte[] b) {
    return field(id, b, b.length, true);
  }

  static void id3v1(Path root) throws Exception {
    var f = new GeneratedFiles(root);
    for (String year : new String[]{"oops", "-1", "2026", "0000"})
      for (boolean v11 : new boolean[]{false, true}) {
        byte[] b = new byte[128];
        System.arraycopy(text("TAG"), 0, b, 0, 3);
        System.arraycopy(text(" Artist "), 0, b, 3, 8);
        b[33] = (byte) 0xc3;
        b[34] = (byte) 0xa9;
        System.arraycopy(Arrays.copyOf(text(year), 4), 0, b, 93, 4);
        System.arraycopy(text(" x "), 0, b, 97, 3);
        b[126] = (byte) (v11 ? 7 : 0);
        b[127] = (byte) 222;
        f.put(year + "-v" + (v11 ? 11 : 10) + ".bin", b);
      }
    byte[] base = Files.readAllBytes(root.resolve("inputs/oops-v11.bin"));
    f.put("empty.bin", new byte[0]);
    f.put("short.bin", slice(base, 0, 127));
    f.put("bad-signature.bin", join(text("XXX"), slice(base, 3, 128)));
    f.put("trailing.bin", join(base, text("junk")));
  }

  static void reparsing(Path root) throws Exception {
    var g = new LegacyTagFixtures(root);
    var f = g.files;
    for (int v = 0; v < 256; v++)
      for (int version : new int[]{10, 11}) {
        byte[] b = repeat(v, 128);
        System.arraycopy(text("TAG"), 0, b, 0, 3);
        b[125] = 0;
        b[126] = (byte) (version == 11 ? 7 : 0);
        b[127] = (byte) v;
        g.put("I", String.format("id3-byte-%03d-v%d", v, version), b);
      }
    byte[] base = new byte[128];
    System.arraycopy(text("TAG"), 0, base, 0, 3);
    base[126] = 7;
    for (Object[] field :
      new Object[][]{
        {3, 30, "title"},
        {33, 30, "artist"},
        {63, 30, "album"},
        {93, 4, "2026"},
        {97, 28, " comment "}
      })
      System.arraycopy(
        Arrays.copyOf(text((String) field[2]), (int) field[1]),
        0,
        base,
        (int) field[0],
        (int) field[1]);
    for (int n = 0; n < 129; n++) g.put("I", String.format("id3-short-%03d", n), slice(base, 0, n));
    for (byte[] year :
      new byte[][]{
        text("oops"),
        hex("2d310000"),
        text("0000"),
        text(" 12 "),
        text("2147"),
        text("4294"),
        hex("31007878")
      }) {
      byte[] b = base.clone();
      System.arraycopy(year, 0, b, 93, 4);
      g.put("I", "id3-year-" + HexFormat.of().formatHex(year), b);
    }
    g.put("I", "id3-bad-signature", join(text("XXX"), slice(base, 3, 128)));
    g.put("I", "id3-trailing", join(base, text("ignored trailing data")));
    byte[] normal =
      join(
        text("INFO"),
        field("INAM", text("first\0")),
        field("IART", text("artist\0")),
        field("ZZZZ", text("opaque\0")));
    for (int n = 0; n <= normal.length; n++)
      g.put("F", String.format("info-short-%03d", n), slice(normal, 0, n));
    for (int i = 0; i < 256; i++)
      for (int col : new int[]{0, 3}) {
        byte[] id = text("INAM");
        id[col] = (byte) i;
        g.put(
          "F",
          String.format("info-id-%d-%03d", col, i),
          join(text("INFO"), id, uint(6, 4, true), hex("636166c3a900")));
      }
    for (int tail = 0; tail < 8; tail++)
      for (int value : new int[]{0, 1, 7, 127, 255})
        g.put(
          "F",
          "info-tail-" + tail + "-" + value,
          join(normal, slice(join(text("INAM"), repeat(value, 4)), 0, tail)));
    for (int actual = 0; actual < 9; actual++)
      for (long declared : new long[]{0, 1, 2, 7, 8, 9, 127, 0xffffffffL})
        g.put(
          "F",
          "info-declared-" + actual + "-" + declared,
          join(text("INFO"), field("INAM", slice(text("abcdefgh"), 0, actual), declared, false)));
    for (byte[] value :
      new byte[][]{
        hex(""), hex("00"), text("a\0b"), hex("ff00"), hex("636166c3a900"), hex("c3"), hex("0000")
      })
      g.put(
        "F",
        "info-value-" + (value.length == 0 ? "empty" : HexFormat.of().formatHex(value)),
        join(text("NOPE"), field("INAM", value), field("INAM", text("last\0")), text("IART")));
    var r = new FixtureRandom(0x2302);
    for (int i = 0; i < 64; i++)
      g.put("F", String.format("info-random-%03d", i), r.bytes(r.below(65)));
    f.text("cases.tsv", g.cases.toString());

    var names = new StringBuilder();
    for (String name :
      new String[]{
        "xing.mp3", "tagged.tta", "tagged.wv", "mac-399-tagged.ape", "click.mpc", "no-tags.flac"
      }) {
      byte[] b = Files.readAllBytes(Parity.corpus().resolve(name));
      if (Arrays.equals(
        slice(b, Math.max(0, b.length - 128), Math.max(0, b.length - 125)), text("TAG")))
        b = slice(b, 0, b.length - 128);
      b = join(b, base);
      f.raw("parents/" + name, b);
      names.append(name).append('\n');
    }
    byte[] wave =
      wave(
        riffChunk("fmt ", pack("<HHIIHH", 1, 1, 8000, 16000, 2, 16)),
        riffChunk("LIST", normal),
        riffChunk("data", text("unchanged audio bytes")));
    f.raw("parents/info.wav", wave);
    names.append("info.wav\n");
    f.text("parent-cases.tsv", names.toString());
    f.raw("collision-real.ape", Files.readAllBytes(Parity.corpus().resolve("mac-399.ape")));
    f.raw("collision-empty.mpc", new byte[0]);
  }
}
