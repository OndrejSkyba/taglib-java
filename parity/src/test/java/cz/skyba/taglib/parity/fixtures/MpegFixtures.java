package cz.skyba.taglib.parity.fixtures;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;

import static cz.skyba.taglib.parity.fixtures.Bytes.*;

final class MpegFixtures {
  private static final int[][][] BITRATES = {
    {
      {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0},
      {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0},
      {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0}
    },
    {
      {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0},
      {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0},
      {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0}
    }
  };

  private static int length(int v, int layer, int bitrate, int rate) {
    int b = BITRATES[v == 3 ? 0 : 1][layer - 1][bitrate],
      hz = new int[]{44100, 48000, 32000, 0}[rate] / (v == 3 ? 1 : v == 2 ? 2 : 4),
      samples = layer == 1 ? 384 : layer == 2 || v == 3 ? 1152 : 576;
    return b == 0 || hz == 0 ? 0 : samples * b * 125 / hz;
  }

  private static byte[] mpeg(int v, int layer, int b, int r, int mode, int flags) {
    byte[] h =
      new byte[]{
        (byte) 255,
        (byte) (224 | (v << 3) | ((4 - layer) << 1) | (flags & 1)),
        (byte) ((b << 4) | (r << 2)),
        (byte) ((mode << 6) | (flags & 12))
      };
    return join(h, new byte[Math.max(0, length(v, layer, b, r) - 4)]);
  }

  private static byte[] adts(int v, int r, int c, int n, boolean properties) {
    byte[] h =
      new byte[]{
        (byte) 255,
        (byte) (224 | (v << 3) | ((r + c) & 1)),
        (byte) (64 | (r << 2) | (c >> 2)),
        (byte)
          (((c & 3) << 6)
            | (properties ? 0x24 : ((c & 1) << 5) | ((r & 1) << 2))
            | ((n >> 11) & 3)),
        (byte) ((n >> 3) & 255),
        (byte) ((n & 7) << 5)
      };
    return join(h, new byte[Math.max(0, n - 6)]);
  }

  private static byte[] xing(String marker, long flags, long frames, long size) {
    return join(text(marker), pack(">III", flags, frames, size));
  }

  private static byte[] vbri(long frames, long size) {
    return join(text("VBRI"), new byte[6], pack(">II", size, frames), new byte[14]);
  }

  private static void raw(GeneratedFiles f, String kind, String name, byte[] b) throws Exception {
    f.raw(kind + "/" + name + ".bin", b);
  }

  static void headers(Path root) throws Exception {
    var f = new GeneratedFiles(root);
    for (int v : new int[]{0, 2, 3})
      for (int layer : new int[]{1, 2, 3})
        for (int b = 0; b < 16; b++)
          for (int r = 0; r < 4; r++) {
            int pad = (b + r) & 1, mode = (b + r) % 4, prot = r & 1, flags = (b % 4) << 2;
            byte[] h =
              new byte[]{
                (byte) 255,
                (byte) (224 | (v << 3) | ((4 - layer) << 1) | prot),
                (byte) ((b << 4) | (r << 2) | (pad << 1)),
                (byte) ((mode << 6) | flags)
              };
            int n = length(v, layer, b, r);
            if (n > 0) n += (layer == 1 ? 4 : 1) * pad;
            raw(
              f,
              "headers",
              String.format("mpeg-%d-%d-%02d-%d", v, layer, b, r),
              n == 0 ? h : join(h, new byte[Math.max(0, n - 4)], h));
          }
    byte[] base = hex("fffb9000");
    for (int at = 0; at < 4; at++)
      for (int bit = 0; bit < 8; bit++) {
        byte[] next = base.clone();
        next[at] ^= (byte) (1 << bit);
        raw(f, "headers", "next-mask-" + at + "-" + bit, join(base, new byte[413], next));
      }
    for (int n = 0; n < 5; n++)
      raw(f, "headers", "next-short-" + n, join(base, new byte[413], slice(base, 0, n)));
    for (int at = 0; at < 4; at++)
      for (int value : new int[]{0, 1, 127, 128, 255}) {
        byte[] h = base.clone();
        h[at] = (byte) value;
        raw(f, "headers", String.format("first-byte-%d-%03d", at, value), h);
      }
    for (int n = 0; n < 4; n++) raw(f, "headers", "first-short-" + n, slice(base, 0, n));
    raw(f, "headers", "invalid-file", base);
    for (int v : new int[]{2, 3})
      for (int r = 0; r < 16; r++)
        for (int c = 0; c < 8; c++)
          for (int n : new int[]{0, 7, 417, 8191}) {
            byte[] frame = adts(v, r, c, n, false);
            raw(
              f,
              "headers",
              String.format("adts-%d-%02d-%d-%04d", v, r, c, n),
              n == 0 ? frame : join(frame, slice(frame, 0, 6)));
          }
    for (int n = 0; n < 7; n++)
      raw(f, "headers", "adts-short-" + n, slice(hex("fff1508000e0"), 0, n));
    for (int second : new int[]{0xe0, 0xe1, 0xe8, 0xe9, 0xff})
      raw(
        f,
        "headers",
        String.format("adts-layer-reserved-%02x", second),
        new byte[]{(byte) 255, (byte) second, 80, (byte) 128, 0, (byte) 224});
    for (String marker : new String[]{"Xing", "Info"})
      for (long flags : new long[]{0, 1, 2, 3, 7, 15, 0x80000003L, 0xffffff03L})
        for (long frames : new long[]{0, 1, 0x7fffffff, 0x80000000L, 0xffffffffL})
          for (long size : new long[]{0, 1, 0x7fffffff, 0x80000000L, 0xffffffffL})
            raw(
              f,
              "xing",
              String.format("%s-%08x-%08x-%08x", marker, flags, frames, size),
              join(text("prefix!"), xing(marker, flags, frames, size)));
    for (long frames : new long[]{0, 1, 0x7fffffff, 0x80000000L, 0xffffffffL})
      for (long size : new long[]{0, 1, 0x7fffffff, 0x80000000L, 0xffffffffL})
        raw(f, "xing", String.format("VBRI-%08x-%08x", frames, size), vbri(frames, size));
    for (Object[] item :
      new Object[][]{
        {"Xing", xing("Xing", 3, 7, 11)},
        {"Info", xing("Info", 3, 13, 17)},
        {"VBRI", vbri(19, 23)}
      }) {
      byte[] b = (byte[]) item[1];
      for (int n = 0; n <= b.length; n++)
        raw(
          f,
          "xing",
          String.format("truncated-%s-%02d", item[0], n),
          join(text("prefix!"), slice(b, 0, n)));
    }
    byte[] x = xing("Xing", 3, 7, 11), i = xing("Info", 3, 13, 17), v = vbri(19, 23);
    String[] names = {
      "order-v-i-x",
      "order-i-x-v",
      "order-x-i-v",
      "rejected-xing-before-vbri",
      "rejected-xing-after-info",
      "rejected-info-before-vbri",
      "short-xing-after-vbri",
      "first-xing-zero",
      "first-info-zero",
      "first-vbri-zero"
    };
    byte[][] data = {
      join(v, i, x),
      join(i, x, v),
      join(x, i, v),
      join(xing("Xing", 1, 7, 11), v),
      join(i, xing("Xing", 1, 7, 11)),
      join(xing("Info", 2, 7, 11), v),
      join(v, text("Xing")),
      join(xing("Xing", 3, 0, 11), x),
      join(xing("Info", 3, 0, 11), i),
      join(vbri(0, 23), v)
    };
    for (int n = 0; n < names.length; n++) raw(f, "xing", names[n], data[n]);
    var random = new FixtureRandom(232);
    for (int n = 0; n < 64; n++)
      raw(f, "xing", String.format("no-marker-%02d", n), random.bytes(n));
  }

  private static byte[] times(byte[] b, int count) {
    return join(Collections.nCopies(count, b));
  }

  private static void put(GeneratedFiles f, String name, byte[] bytes) throws Exception {
    f.put(name + ".mp3", bytes);
  }

  static void properties(Path root) throws Exception {
    var f = new GeneratedFiles(root);
    for (int v : new int[]{0, 2, 3})
      for (int layer : new int[]{1, 2, 3})
        for (int b : new int[]{0, 1, 7, 14, 15})
          for (int r = 0; r < 4; r++)
            for (int mode = 0; mode < 4; mode++)
              put(
                f,
                "mpeg-" + v + "-" + layer + "-" + b + "-" + r + "-" + mode,
                times(mpeg(v, layer, b, r, mode, (mode % 2) | 12), 3));
    for (int v : new int[]{2, 3})
      for (int r : new int[]{0, 4, 11, 12, 13, 15})
        for (int c = 0; c < 8; c++)
          for (int n : new int[]{0, 7, 11, 17, 417, 8191})
            put(f, "adts-" + v + "-" + r + "-" + c + "-" + n, times(adts(v, r, c, n, true), 3));
    put(
      f,
      "adts-average-stable-tail",
      join(times(adts(2, 4, 2, 100, true), 15), times(adts(2, 4, 2, 300, true), 15)));
    var frames = new ArrayList<byte[]>();
    for (int n = 0; n < 40; n++) frames.add(adts(3, 4, 7, 60 + n * 13, true));
    put(f, "adts-average-changing", join(frames));
    byte[] base = mpeg(3, 3, 9, 0, 0, 0), header = slice(base, 0, 4);
    for (int pad : new int[]{0, 1, 7, 1023, 1024, 65535, 65536})
      put(f, "junk-" + pad, join(repeat('!', pad), times(base, 3)));
    for (int n = 0; n < 8; n++) put(f, "short-adts-" + n, slice(adts(2, 4, 1, 17, true), 0, n));
    for (int n = 0; n < 5; n++) put(f, "short-mpeg-" + n, slice(header, 0, n));
    put(f, "excluded-ff-sync", join(hex("ffff9000"), new byte[413], hex("ffff9000")));
    put(f, "last-invalid-mask", join(base, hex("fffa00c0")));
    for (String marker : new String[]{"Xing", "Info", "VBRI"})
      for (long frameCount : new long[]{0, 1, 82192, 82193, 0x7fffffff, 0x80000000L, 0xffffffffL})
        for (long size : new long[]{0, 1, 12345, 0x7fffffff, 0x80000000L, 0xffffffffL}) {
          byte[] payload =
            marker.equals("VBRI") ? vbri(frameCount, size) : xing(marker, 3, frameCount, size);
          put(
            f,
            String.format("vbr-%s-%08x-%08x", marker, frameCount, size),
            join(
              header,
              new byte[13],
              payload,
              new byte[base.length - 17 - payload.length],
              times(base, 2)));
        }
    for (long n : new long[]{82208220, 82208221, 82208222}) {
      byte[] payload = xing("Xing", 3, n, 0xffffffffL);
      put(
        f,
        "xing-length-int-boundary-" + n,
        join(header, payload, new byte[base.length - 4 - payload.length], base));
    }
    byte[] x = xing("Xing", 1, 7, 11), i = xing("Info", 3, 7, 11);
    put(
      f,
      "invalid-preferred-xing",
      join(header, i, x, new byte[base.length - 4 - x.length - i.length], base));
    put(f, "later-xing-ignored", join(base, header, i, new byte[base.length - 4 - i.length], base));
    byte[] id3 = hex("49443304000000000000"),
      legacy = join(text("TAG"), new byte[125]),
      ape = join(text("APETAGEX"), pack("<IIII", 2000, 32, 0, 0), new byte[8]);
    for (byte[] lead : new byte[][]{new byte[0], id3, join(text("junk!"), id3)})
      for (byte[] tail : new byte[][]{new byte[0], legacy, ape, join(ape, legacy)})
        put(
          f,
          "tag-boundaries-"
            + HexFormat.of().formatHex(lead)
            + "-"
            + HexFormat.of().formatHex(slice(tail, 0, 3))
            + "-"
            + tail.length,
          join(lead, times(base, 3), tail));
    f.copyExtensions("upstream-", ".mp3", ".mp3", ".aac");
  }
}
