package cz.skyba.taglib.parity.fixtures;

import cz.skyba.taglib.parity.support.Parity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static cz.skyba.taglib.parity.fixtures.Bytes.*;

final class TrackerFixtures {
  private static byte[] s3m(byte[] orders, int samples) {
    byte[] b = new byte[96 + orders.length + samples * 2];
    b[28] = 26;
    b[29] = 16;
    System.arraycopy(text("SCRM"), 0, b, 44, 4);
    field(b, 32, 2, orders.length, true);
    field(b, 34, 2, samples, true);
    Arrays.fill(b, 64, 96, (byte) 255);
    System.arraycopy(orders, 0, b, 96, orders.length);
    for (int i = 0; i < samples; i++) {
      int offset = (b.length + 15) / 16 * 16;
      b = Arrays.copyOf(b, offset + 80);
      field(b, 96 + orders.length + 2 * i, 2, offset / 16, true);
    }
    return b;
  }

  private static byte[] s3m() {
    return s3m(hex("00fe01ff02"), 0);
  }

  private static byte[] it(byte[] orders, int instruments, int samples) {
    byte[] b = new byte[192 + orders.length + (instruments + samples) * 4];
    System.arraycopy(text("IMPM"), 0, b, 0, 4);
    field(b, 32, 2, orders.length, true);
    field(b, 34, 2, instruments, true);
    field(b, 36, 2, samples, true);
    Arrays.fill(b, 64, 128, (byte) 128);
    System.arraycopy(orders, 0, b, 192, orders.length);
    for (int i = 0; i < instruments + samples; i++) {
      int offset = b.length;
      field(b, 192 + orders.length + i * 4, 4, offset, true);
      b = Arrays.copyOf(b, offset + (i < instruments ? 58 : 46));
      System.arraycopy(text(i < instruments ? "IMPI" : "IMPS"), 0, b, offset, 4);
    }
    return b;
  }

  private static byte[] it() {
    return it(hex("00fe01ff02"), 0, 0);
  }

  private static final int[] XM_FIELDS = {1, 0, 8, 0, 0, 1, 6, 125};

  private static byte[] xm(int width, int[] fields, int version) {
    byte[] b = new byte[64];
    field(b, 58, 2, version, true);
    field(b, 60, 4, 4 + width, true);
    var header = new java.io.ByteArrayOutputStream();
    for (int v : fields) header.writeBytes(uint(v, 2, true));
    return join(b, slice(header.toByteArray(), 0, width));
  }

  private static byte[] xm(int... fields) {
    return xm(16, fields.length == 0 ? XM_FIELDS : fields, 260);
  }

  private static byte[] instrument(int count, long width) {
    byte[] b = new byte[33];
    field(b, 0, 4, 33, true);
    field(b, 27, 2, count, true);
    field(b, 29, 4, width, true);
    return b;
  }

  private static void put(GeneratedFiles f, String name, byte[] b) throws Exception {
    f.put(name, b);
  }

  static void properties(Path root) throws Exception {
    var f = new GeneratedFiles(root);
    var rows = new StringBuilder();
    String[] families = {"mod", "s3m", "it", "xm"};
    int[] setters = {3, 12, 15, 10};
    long[] values = {
      Long.MIN_VALUE,
      Integer.MIN_VALUE,
      -65537,
      -65536,
      -257,
      -256,
      -1,
      0,
      1,
      127,
      128,
      255,
      256,
      65535,
      65536,
      65537,
      Integer.MAX_VALUE,
      0xffffffffL,
      0x100000000L,
      Long.MAX_VALUE
    };
    for (int i = 0; i < families.length; i++)
      for (int field = -2; field < setters[i]; field++) {
        if (field == -1) continue;
        for (long v : values) {
          rows.append(families[i]).append('\t').append(field).append('\t').append(v).append('\n');
        }
      }
    f.text("cases.tsv", rows.toString());
    var identifiers =
      new ArrayList<byte[]>(
        List.of(
          text("M.K."),
          text("M!K!"),
          text("M&K!"),
          text("N.T."),
          text("CD81"),
          text("OKTA"),
          new byte[4],
          text("none")));
    for (String prefix : new String[]{"FLT", "TDZ"})
      for (int v : new int[]{0, 1, 9}) identifiers.add(text(prefix + v));
    for (int v : new int[]{0, 1, 9}) identifiers.add(text(v + "CHN"));
    for (String id : new String[]{"00CH", "01CH", "99CH", "00CN", "01CN", "99CN"})
      identifiers.add(text(id));
    for (byte[] id : identifiers)
      for (int length : new int[]{0, 1, 128, 255}) {
        byte[] b = new byte[1084];
        System.arraycopy(id, 0, b, 1080, 4);
        int n = Arrays.equals(id, new byte[4]) || Arrays.equals(id, text("none")) ? 15 : 31;
        b[20 + n * 30] = (byte) length;
        put(f, "mod-" + HexFormat.of().formatHex(id) + "-" + length + ".mod", b);
      }
    for (int size : new int[]{471, 700, 1080}) {
      byte[] b = new byte[size];
      b[470] = (byte) 255;
      put(f, "mod-short-no-identifier-" + size + ".mod", b);
    }
    byte[][] orders = {
      hex(""),
      hex("00"),
      hex("fe"),
      hex("ff"),
      hex("00fe01ff02"),
      hex("ff0001"),
      hex("fefefe"),
      hex("00010203")
    };
    for (byte[] order : orders)
      put(f, "s3m-orders-" + HexFormat.of().formatHex(order) + ".s3m", s3m(order, 0));
    for (int samples : new int[]{0, 1, 3, 31})
      put(f, "s3m-samples-" + samples + ".s3m", s3m(hex("00fe01ff02"), samples));
    String[] s3mShorts = {"patternCount", "flags", "trackerVersion", "fileFormatVersion"};
    for (int i = 0; i < s3mShorts.length; i++)
      for (int v : new int[]{0, 1, 32768, 65535}) {
        byte[] b = s3m();
        field(b, 36 + 2 * i, 2, v, true);
        put(f, "s3m-" + s3mShorts[i] + "-" + v + ".s3m", b);
      }
    String[] s3mBytes = {"globalVolume", "bpmSpeed", "tempo", "masterVolume"};
    for (int i = 0; i < s3mBytes.length; i++)
      for (int v : new int[]{0, 1, 127, 128, 255}) {
        byte[] b = s3m();
        b[48 + i] = (byte) v;
        put(f, "s3m-" + s3mBytes[i] + "-" + v + ".s3m", b);
      }
    for (int v : new int[]{0, 127, 128, 254, 255}) {
      byte[] b = s3m();
      Arrays.fill(b, 64, 96, (byte) v);
      put(f, "s3m-channel-setting-" + v + ".s3m", b);
    }
    byte[] nonzero = new byte[96 + 6656 * 2];
    nonzero[28] = 26;
    nonzero[29] = 16;
    nonzero[35] = 26;
    nonzero[36] = 16;
    System.arraycopy(text("SCRM"), 0, nonzero, 44, 4);
    Arrays.fill(nonzero, 64, 96, (byte) 255);
    put(f, "s3m-native-nonzero-origin.s3m", nonzero);
    for (byte[] order : orders)
      put(f, "it-orders-" + HexFormat.of().formatHex(order) + ".it", it(order, 0, 0));
    for (int[] pair : new int[][]{{0, 0}, {1, 0}, {0, 1}, {2, 3}})
      put(
        f,
        "it-counts-" + pair[0] + "-" + pair[1] + ".it",
        it(hex("00fe01ff02"), pair[0], pair[1]));
    String[] itShorts = {"patternCount", "version", "compatibleVersion", "flags", "special"};
    for (int i = 0; i < itShorts.length; i++)
      for (int v : new int[]{0, 1, 8, 32768, 65535}) {
        byte[] b = it();
        field(b, 38 + 2 * i, 2, v, true);
        put(f, "it-" + itShorts[i] + "-" + v + ".it", b);
      }
    String[] itBytes = {
      "globalVolume", "mixVolume", "bpmSpeed", "tempo", "panningSeparation", "pitchWheelDepth"
    };
    for (int i = 0; i < itBytes.length; i++)
      for (int v : new int[]{0, 1, 127, 128, 255}) {
        byte[] b = it();
        b[48 + i] = (byte) v;
        put(f, "it-" + itBytes[i] + "-" + v + ".it", b);
      }
    for (int panning : new int[]{0, 127, 128, 255})
      for (int volume : new int[]{0, 1, 127, 128, 255}) {
        byte[] b = it();
        Arrays.fill(b, 64, 128, (byte) panning);
        Arrays.fill(b, 128, 192, (byte) volume);
        put(f, "it-channel-" + panning + "-" + volume + ".it", b);
      }
    int[] partial = {65535, 65535, 8, 3, 2, 65535, 65535, 65535};
    byte[] full = pack("<8H", 65535, 65535, 8, 3, 2, 65535, 65535, 65535);
    for (int width = 0; width < 17; width++) {
      byte[] b = xm(width, partial, 260), h = slice(full, 0, width);
      int patterns = (h.length > 6 ? h[6] & 255 : 0) + (h.length > 7 ? (h[7] & 255) * 256 : 0),
        instruments = (h.length > 8 ? h[8] & 255 : 0) + (h.length > 9 ? (h[9] & 255) * 256 : 0);
      b = join(b, join(Collections.nCopies(patterns + instruments, uint(4, 4, true))));
      put(f, "xm-partial-header-" + width + ".xm", b);
    }
    String[] xmNames = {
      "lengthInPatterns", "restartPosition", "channels", "flags", "tempo", "bpmSpeed"
    };
    int[] indices = {0, 1, 2, 5, 6, 7};
    for (int i = 0; i < indices.length; i++)
      for (int v : new int[]{0, 1, 32768, 65535}) {
        int[] fields = XM_FIELDS.clone();
        fields[indices[i]] = v;
        put(f, "xm-" + xmNames[i] + "-" + v + ".xm", xm(fields));
      }
    for (int version : new int[]{0, 1, 260, 32768, 65535})
      put(f, "xm-version-" + version + ".xm", xm(16, XM_FIELDS, version));
    for (int width : new int[]{4, 5, 6, 7, 8, 9, 13}) {
      byte[] pattern = new byte[width];
      field(pattern, 0, 4, width, true);
      if (width > 7) pattern[7] = 3;
      put(
        f,
        "xm-pattern-width-" + width + ".xm",
        join(xm(1, 0, 8, 1, 0, 1, 6, 125), pattern, new byte[width > 7 ? 3 : 0]));
    }
    for (int width : new int[]{1, 2, 3, 4, 5, 17, 18, 19, 39, 40, 41}) {
      byte[] sample = new byte[width];
      field(sample, 0, 4, 5, true);
      put(
        f,
        "xm-sample-width-" + width + ".xm",
        join(xm(1, 0, 8, 0, 1, 1, 6, 125), instrument(2, width), sample, sample, new byte[10]));
    }
    put(
      f,
      "xm-zero-sample-header-size-and-aggregate.xm",
      join(
        xm(1, 0, 8, 0, 2, 1, 6, 125),
        instrument(1, 0),
        new byte[40],
        instrument(2, 0),
        new byte[80]));
    for (long declared : new long[]{100, 0xffffffffL}) {
      byte[] b = xm();
      field(b, 60, 4, declared, true);
      put(f, "xm-unknown-header-padding-" + declared + ".xm", b);
      put(
        f,
        "xm-unknown-pattern-padding-" + declared + ".xm",
        join(xm(1, 0, 8, 1, 0, 1, 6, 125), pack("<IBHH", declared, 0, 0, 0)));
      put(
        f,
        "xm-unknown-instrument-padding-" + declared + ".xm",
        join(xm(1, 0, 8, 0, 1, 1, 6, 125), uint(declared, 4, true), new byte[25]));
      put(
        f,
        "xm-unknown-sample-padding-" + declared + ".xm",
        join(xm(1, 0, 8, 0, 1, 1, 6, 125), instrument(1, declared), new byte[40]));
    }
    for (long length : new long[]{5, 0xffffffffL}) {
      byte[] sample = new byte[40];
      field(sample, 0, 4, length, true);
      put(
        f,
        "xm-unavailable-audio-" + length + ".xm",
        join(xm(1, 0, 8, 0, 1, 1, 6, 125), instrument(1, 40), sample));
    }
    try (var paths = Files.list(Parity.corpus())) {
      for (Path p : paths.sorted().toList())
        if (Arrays.stream(new String[]{".mod", ".s3m", ".it", ".xm"})
          .anyMatch(e -> p.toString().endsWith(e)))
          put(f, "upstream-" + p.getFileName(), Files.readAllBytes(p));
    }
  }

  static void files(Path root) throws Exception {
    properties(root);
    var f = new GeneratedFiles(root);
    String[] families = {"s3m", "it", "xm"};
    int[] cuts = {192, 260, 160};
    for (int i = 0; i < families.length; i++) {
      byte[] b = Files.readAllBytes(root.resolve("inputs/upstream-test." + families[i]));
      for (int n = 0; n <= cuts[i]; n++)
        f.raw("inputs/prefix-" + n + "." + families[i], slice(b, 0, n));
    }
    byte[] base = Files.readAllBytes(root.resolve("inputs/mod-4d2e4b2e-0.mod"));
    for (byte[] marker :
      new byte[][]{text("FLTx"), hex("54445a00"), text("xCHN"), text("1xCH"), text("x1CN")}) {
      byte[] b = base.clone();
      System.arraycopy(marker, 0, b, 1080, 4);
      f.raw("inputs/invalid-marker-" + HexFormat.of().formatHex(marker) + ".mod", b);
    }
  }
}
