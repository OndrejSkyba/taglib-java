package cz.skyba.taglib.parity.fixtures;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;

import static cz.skyba.taglib.parity.fixtures.Bytes.*;
import static cz.skyba.taglib.parity.fixtures.TagBytes.*;

final class ApeMusepackWavPackFixtures {
  private ApeMusepackWavPackFixtures() {
  }

  private static String u(long value) {
    return Long.toUnsignedString(value);
  }

  private static void put(GeneratedFiles f, String name, String extension, byte[] data)
    throws Exception {
    f.put(name + extension, data);
  }

  private static byte[] apeFile(
    long descriptor, long frames, long blocks, long last, int channels, long rate, int bits) {
    byte[] d = new byte[44];
    field(d, 0, 4, descriptor, true);
    return join(
      text("MAC "),
      pack("<H", 3990),
      new byte[2],
      d,
      new byte[(int) Math.max(0, Math.min(128, descriptor - 52))],
      pack("<HHIIIhhI", 2000, 0, blocks, last, frames, bits, channels, rate),
      new byte[52]);
  }

  static void apeFiles(Path root) throws Exception {
    var f = new GeneratedFiles(root);
    byte[] base = apeFile(52, 2, 100, 100, 2, 44100, 16);
    for (int n = 0; n <= base.length; n++)
      put(f, String.format("prefix-%03d", n), ".ape", slice(base, 0, n));
    for (int version : new int[]{3790, 3800, 3890, 3900, 3950, 3970})
      for (int compression : new int[]{2000, 4000})
        for (long frames : new long[]{0, 2, 0xffffffffL}) {
          byte[] h = new byte[26];
          field(h, 0, 2, compression, true);
          field(h, 4, 2, 2, true);
          field(h, 6, 4, 44100, true);
          field(h, 18, 4, frames, true);
          field(h, 22, 4, 100, true);
          byte[] fmt = new byte[28];
          System.arraycopy(text("WAVEfmt "), 0, fmt, 0, 8);
          field(fmt, 26, 2, 24, true);
          put(
            f,
            "old-" + version + "-" + compression + "-" + frames,
            ".ape",
            join(text("MAC "), pack("<H", version), h, new byte[16], fmt, new byte[52]));
        }
    for (long descriptor : new long[]{0, 1, 51, 52, 53, 64, 0xffffffffL})
      put(f, "descriptor-" + descriptor, ".ape", apeFile(descriptor, 2, 100, 100, 2, 44100, 16));
    for (long frames : new long[]{0, 1, 0xffffffffL})
      for (long rate : new long[]{0, 1, 44100, 0x80000000L, 0xffffffffL})
        put(
          f,
          "frames-" + frames + "-rate-" + rate,
          ".ape",
          apeFile(52, frames, 100, 100, 2, rate, 16));
    for (int value : new int[]{0, -1, -32768, 32767})
      put(
        f,
        "signed-" + value + "-" + value,
        ".ape",
        apeFile(52, 2, 100, 100, value, 44100, value));
    tagsAndFooters(f, ".ape", base);
    for (int prefix : new int[]{0, 7, 1021, 1022, 1023, 1024, 1030})
      put(f, "displaced-" + prefix, ".ape", join(repeat('X', prefix), base));
    for (int[] spec : new int[][]{{0, 4, 5}, {16, 4, 5}, {16, 255, 5}, {16, 4, 128}, {0, 3, 0}}) {
      byte[] header =
        join(
          text("ID3"), new byte[]{(byte) spec[1], 0, (byte) spec[0], 0, 0, 0, (byte) spec[2]});
      put(
        f,
        "id3-" + spec[0] + "-" + spec[1] + "-" + spec[2],
        ".ape",
        join(
          header,
          new byte[5],
          spec[0] == 16 ? new byte[10] : new byte[0],
          base,
          ape(apeBody(), true),
          legacy()));
    }
    for (int n : new int[]{0, 3, 8, 31, 32, 80, 127, 128, 131})
      put(f, "no-mac-" + n, ".ape", repeat('X', n));
    byte[] ambiguity = new byte[160];
    System.arraycopy(text("APETAGEX"), 0, ambiguity, 29, 8);
    put(f, "ape-id3-ambiguity", ".ape", ambiguity);
    f.copyExtensions("release-", ".ape", ".ape", ".mac");
  }

  private static void tagsAndFooters(GeneratedFiles f, String extension, byte[] base)
    throws Exception {
    for (boolean header : new boolean[]{false, true})
      for (boolean old : new boolean[]{false, true})
        put(
          f,
          "tags-" + (header ? 1 : 0) + "-" + (old ? 1 : 0),
          extension,
          join(base, ape(apeBody(), header), old ? legacy() : new byte[0]));
    put(f, "legacy-only", extension, join(base, legacy()));
    for (long size : new long[]{0, 16, 31, 32, 33, 160, 161, 0xfffffff0L, 0xffffffffL})
      for (long flags : new long[]{0, 0x80000000L, 0xffffffffL})
        put(
          f,
          "footer-" + size + "-" + flags,
          extension,
          join(base, ape(new byte[0], false, size, flags, 0)));
  }

  private static byte[] sv7(int version, long frames, long flags, long[] gains) {
    byte[] b = new byte[56];
    System.arraycopy(text("MP+"), 0, b, 0, 3);
    b[3] = (byte) version;
    field(b, 4, 4, frames, true);
    field(b, 8, 4, flags, true);
    for (int i = 0; i < 4; i++) field(b, 12 + 2 * i, 2, gains[i], true);
    return b;
  }

  private static byte[] variable(long value) {
    var parts = new ArrayList<Byte>();
    parts.add((byte) (value & 127));
    value >>>= 7;
    while (value != 0) {
      parts.add(0, (byte) ((value & 127) | 128));
      value >>>= 7;
    }
    byte[] b = new byte[parts.size()];
    for (int i = 0; i < b.length; i++) b[i] = parts.get(i);
    return b;
  }

  private static byte[] packet(String kind, byte[] data) {
    for (int width = 1; width < 11; width++) {
      byte[] n = variable(2 + width + data.length);
      if (n.length == width) return join(text(kind), n, data);
    }
    throw new IllegalArgumentException("Packet length");
  }

  private static byte[] streamHeader(long samples, long silence, int flags, int version) {
    return join(
      new byte[4],
      new byte[]{(byte) version},
      variable(samples),
      variable(silence),
      pack(">H", flags));
  }

  static void musepackFiles(Path root) throws Exception {
    var f = new GeneratedFiles(root);
    long[] defaultGains = {1, 2, 3, 4};
    byte[] base = sv7(7, 2, 0, defaultGains);
    for (int n = 0; n < 57; n++)
      put(f, String.format("sv7-prefix-%03d", n), ".mpc", slice(base, 0, n));
    for (int version : new int[]{0, 4, 6, 7, 8, 15, 255})
      put(f, "sv7-version-" + version, ".mpc", sv7(version, 2, 0, defaultGains));
    for (long frames : new long[]{0, 1, 2, 0xffffffffL})
      for (int rate = 0; rate < 4; rate++)
        put(
          f,
          "sv7-frames-" + frames + "-rate-" + rate,
          ".mpc",
          sv7(7, frames, rate << 16, defaultGains));
    for (long[] gains :
      new long[][]{
        {0, 0, 0, 0}, {1, -1, 65535, -32768}, {65535, 32767, 1, 32767}, {2, 2000, 4, -2000}
      })
      put(
        f,
        "sv7-gain-" + Arrays.toString(gains).replace('[', '(').replace(']', ')'),
        ".mpc",
        sv7(7, 2, 0, gains));
    for (int version : new int[]{0, 4, 5, 1023})
      for (long frames : new long[]{0, 1, 1010, 0xffffffffL}) {
        byte[] b = new byte[56];
        field(b, 0, 4, version << 11, true);
        field(b, 4, 4, frames, true);
        if (version < 5) field(b, 6, 2, frames & 65535, true);
        put(f, "old-" + version + "-" + frames, ".mpc", b);
      }
    for (long samples : new long[]{0, 1, 200, 66014, 0xffffffffL, Long.MIN_VALUE, -1L})
      for (int silence : new int[]{0, 1, 201})
        put(
          f,
          "sv8-samples-" + u(samples) + "-silence-" + silence,
          ".mpc",
          join(text("MPCK"), packet("SH", streamHeader(samples, silence, 16, 8))));
    for (int flags : new int[]{0, 16, 240, 0x2000, 0x4000, 0x6000, 0x8000, 0xe000, 65535})
      put(
        f,
        "sv8-flags-" + flags,
        ".mpc",
        join(text("MPCK"), packet("SH", streamHeader(200, 0, flags, 8))));
    for (int version : new int[]{0, 8, 127, 128, 255})
      put(
        f,
        "sv8-version-" + version,
        ".mpc",
        join(text("MPCK"), packet("SH", streamHeader(200, 0, 16, version))));
    byte[] sh = packet("SH", streamHeader(200, 0, 16, 8)),
      rg = packet("RG", join(new byte[]{1}, pack(">hhhh", -1, -32768, 32767, -10), new byte[1]));
    for (int n = 0; n <= sh.length; n++)
      put(f, String.format("sv8-sh-prefix-%03d", n), ".mpc", join(text("MPCK"), slice(sh, 0, n)));
    for (byte[] payload :
      new byte[][]{
        new byte[0],
        new byte[5],
        hex("000000000881"),
        hex("0000000008818181"),
        hex("0000000008018181")
      })
      put(
        f,
        "sv8-corrupt-sh-" + HexFormat.of().formatHex(payload),
        ".mpc",
        join(text("MPCK"), packet("SH", payload)));
    for (int n : new int[]{0, 1, 8, 9, 10, 11})
      put(
        f,
        "sv8-rg-size-" + n,
        ".mpc",
        join(text("MPCK"), packet("RG", slice(join(new byte[]{1}, repeat(255, 10)), 0, n))));
    put(f, "sv8-sh-then-rg", ".mpc", join(text("MPCK"), sh, rg));
    put(f, "sv8-rg-then-sh", ".mpc", join(text("MPCK"), rg, sh));
    put(
      f,
      "sv8-unknown-double-skip",
      ".mpc",
      join(text("MPCK"), packet("XX", text("ab")), text("XX"), sh));
    put(f, "sv8-unknown-no-padding", ".mpc", join(text("MPCK"), packet("XX", text("ab")), sh));
    put(f, "sv8-end", ".mpc", join(text("MPCK"), packet("SE", new byte[0])));
    for (long n : new long[]{0, 1, 2, 3, 4, 127, 128, 0xffffffffL, 0x100000000L, -1L})
      put(f, "sv8-size-" + u(n), ".mpc", join(text("MPCKSH"), variable(n), new byte[8]));
    put(f, "sv8-unterminated-size", ".mpc", join(text("MPCKSH"), repeat(255, 24)));
    tagsAndFooters(f, ".mpc", base);
    for (long flags : new long[]{0x80000000L, 0xffffffffL})
      put(
        f,
        "unsafe-footer-33-" + flags,
        ".mpc",
        join(slice(base, 0, 32), ape(new byte[0], false, 33, flags, 0)));
    for (int size : new int[]{0, 5, 127})
      for (byte[] tags :
        new byte[][]{
          new byte[0], ape(apeBody(), true), legacy(), join(ape(apeBody(), true), legacy())
        })
        put(
          f,
          "leading-id3-" + size + "-" + tags.length,
          ".mpc",
          join(
            text("ID3"),
            hex("040000000000"),
            new byte[]{(byte) size},
            repeat('X', size),
            base,
            tags));
    for (int[] v : new int[][]{{16, 4, 5}, {16, 255, 5}, {0, 4, 128}})
      put(
        f,
        "id3-header-" + v[0] + "-" + v[1] + "-" + v[2],
        ".mpc",
        join(
          text("ID3"),
          new byte[]{(byte) v[1], 0, (byte) v[0], 0, 0, 0, (byte) v[2]},
          repeat('X', 5),
          base,
          ape(apeBody(), true),
          legacy()));
    put(
      f,
      "leading-id3-sv8",
      ".mpc",
      join(hex("49443304000000000005"), repeat('X', 5), text("MPCK"), sh));
    for (int n : new int[]{0, 3, 8, 31, 32, 80, 128, 131})
      put(f, "no-magic-" + n, ".mpc", repeat('X', n));
    f.copyExtensions("release-", ".mpc", ".mpc");
  }

  private static byte[] wv(
    long size, int version, long frames, long index, long samples, long flags, byte[] body) {
    return join(
      text("wvpk"),
      pack("<IHBBIIIII", size, version, 0, 0, frames, index, samples, flags, 0),
      body);
  }

  private static byte[] wv() {
    return wv(24, 0x410, 200, 0, 100, 0x1801 | (9L << 23), new byte[0]);
  }

  static void wavpackFiles(Path root) throws Exception {
    var f = new GeneratedFiles(root);
    byte[] base = wv();
    long defaults = 0x1801 | (9L << 23);
    for (int n = 0; n < 33; n++) put(f, String.format("prefix-%03d", n), ".wv", slice(base, 0, n));
    for (long size : new long[]{0, 23, 24, 25, 1048575, 1048576, 1048577, 0xffffffffL})
      put(f, "block-size-" + size, ".wv", wv(size, 0x410, 200, 0, 100, defaults, new byte[0]));
    for (int v : new int[]{0x401, 0x402, 0x410, 0x411, 0x8000, 65535})
      put(f, "version-" + v, ".wv", wv(24, v, 200, 0, 100, defaults, new byte[0]));
    for (int rate = 0; rate < 16; rate++)
      for (int flags : new int[]{0, 4, 8, 0x1800, 0x1804, 0x1808, 0x1801 | (31 << 13)})
        put(
          f,
          "rate-" + rate + "-flags-" + flags,
          ".wv",
          wv(24, 0x410, 200, 0, 100, flags | ((long) rate << 23), new byte[0]));
    for (long frames : new long[]{0, 1, 0xfffffffeL, 0xffffffffL})
      put(f, "frames-" + frames, ".wv", wv(24, 0x410, frames, 0, 100, defaults, new byte[0]));
    for (long samples : new long[]{0, 1, 131072, 131073, 0xffffffffL})
      put(f, "samples-" + samples, ".wv", wv(24, 0x410, 200, 0, samples, defaults, new byte[0]));
    for (int size : new int[]{24, 25, 32, 1048576})
      put(
        f,
        "zero-block-" + size,
        ".wv",
        join(
          wv(size, 0x410, 200, 0, 0, defaults, new byte[0]),
          size < 64 ? repeat('X', size - 24) : new byte[0],
          base));
    put(
      f,
      "multichannel",
      ".wv",
      join(
        wv(24, 0x410, 200, 0, 100, 0x801 | (9L << 23), new byte[0]),
        wv(24, 0x410, 200, 0, 100, 0x1005 | (9L << 23), new byte[0])));
    put(
      f,
      "partial-second",
      ".wv",
      join(wv(24, 0x410, 200, 0, 100, 0x801 | (9L << 23), new byte[0]), slice(base, 0, 17)));
    for (long[] r :
      new long[][]{
        {24, 0x410, 5, 100},
        {25, 0x410, 5, 100},
        {1048576, 0x410, 5, 100},
        {24, 0x401, 5, 100},
        {24, 0x410, 131073, 100},
        {24, 0x410, 0, 100},
        {24, 0x410, 5, 0xfffffffeL}
      })
      put(
        f,
        "reverse-" + r[0] + "-" + r[1] + "-" + r[2] + "-" + r[3],
        ".wv",
        join(
          wv(24, 0x410, 0xffffffffL, 0, 100, defaults, new byte[0]),
          wv(r[0], (int) r[1], 200, r[3], r[2], defaults, new byte[0])));
    put(
      f,
      "reverse-spurious",
      ".wv",
      join(
        wv(24, 0x410, 0xffffffffL, 0, 100, defaults, new byte[0]),
        wv(24, 0x410, 200, 100, 100, defaults, new byte[0]),
        text("junkwvpk"),
        new byte[28]));
    for (int shift : new int[]{0, 1, 3, 15, 30, 31, 32, 255})
      put(
        f,
        "dsd-" + shift,
        ".wv",
        wv(
          28,
          0x410,
          200,
          0,
          100,
          0x80001800L | (9L << 23),
          new byte[]{14, 1, (byte) shift, 0}));
    for (byte[] body :
      new byte[][]{
        new byte[0],
        hex("27"),
        hex("2702e8030080"),
        hex("6702e80300ff"),
        hex("a7020000e8030000"),
        hex("a702"),
        hex("010178782702e8030000"),
        hex("27ff7878"),
        hex("0e0120000e010300")
      })
      for (boolean dsd : new boolean[]{false, true})
        put(
          f,
          "metadata-" + HexFormat.of().formatHex(body) + "-" + (dsd ? 1 : 0),
          ".wv",
          wv(
            24 + body.length,
            0x410,
            200,
            0,
            100,
            0x1801 | (15L << 23) | (dsd ? 0x80000000L : 0),
            body));
    put(
      f,
      "short-metadata",
      ".wv",
      wv(32, 0x410, 200, 0, 100, 0x1801 | (15L << 23), hex("2702e8030000")));
    tagsAndFooters(f, ".wv", base);
    for (int prefix : new int[]{1, 7, 1024})
      put(f, "displaced-" + prefix, ".wv", join(repeat('X', prefix), base));
    put(f, "leading-id3", ".wv", join(hex("49443304000000000000"), base));
    for (int n : new int[]{0, 3, 8, 31, 32, 80, 127, 128, 131})
      put(f, "no-magic-" + n, ".wv", repeat('X', n));
    byte[] ambiguity = new byte[160];
    System.arraycopy(text("APETAGEX"), 0, ambiguity, 29, 8);
    put(f, "ape-id3-ambiguity", ".wv", ambiguity);
    f.copyExtensions("release-", ".wv", ".wv");
  }
}
