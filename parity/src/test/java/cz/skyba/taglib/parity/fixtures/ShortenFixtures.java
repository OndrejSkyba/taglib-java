package cz.skyba.taglib.parity.fixtures;

import java.nio.file.Files;
import java.nio.file.Path;

import static cz.skyba.taglib.parity.fixtures.Bytes.*;

final class ShortenFixtures {
  private static String rice(long value, int k) {
    return "0".repeat((int) (value >>> k))
      + "1"
      + (k == 0
      ? ""
      : String.format("%" + k + "s", Long.toBinaryString(value & ((1L << k) - 1)))
      .replace(' ', '0'));
  }

  private static String integer(long v) {
    int k = Math.min(64 - Long.numberOfLeadingZeros(v), 31);
    return rice(k, 2) + rice(v, k);
  }

  private static byte[] shn(
    byte[] header, int version, long type, int channels, int block, int skip) {
    var bits = new StringBuilder();
    for (long v : new long[]{type, channels, block, 0, 0, skip}) bits.append(integer(v));
    for (int i = 0; i < skip; i++) bits.append(integer(i % 256));
    bits.append(rice(9, 2)).append(rice(header.length, 5));
    for (byte b : header) bits.append(rice(b & 255, 8));
    bits.append("0".repeat((32 - bits.length() % 32) % 32));
    byte[] wire = new byte[bits.length() / 8];
    for (int i = 0; i < wire.length; i++)
      wire[i] = (byte) Integer.parseInt(bits.substring(i * 8, i * 8 + 8), 2);
    return join(text("ajkg"), new byte[]{(byte) version}, wire);
  }

  private static byte[] wave(long rate, int bits, int channels, long data, int align) {
    return join(
      text("RIFF"),
      uint(data <= 0xffffffffL - 36 ? 36 + data : 0, 4, true),
      text("WAVEfmt "),
      pack("<IHHIIHH", 16, 1, channels, rate, (rate * align) & 0xffffffffL, align, bits),
      text("data"),
      uint(data, 4, true));
  }

  private static byte[] wave() {
    return wave(44100, 16, 2, 176400, 4);
  }

  private static void row(StringBuilder rows, String name, long[] values) {
    rows.append(name);
    for (int i = 0; i < values.length; i++)
      rows.append('\t')
        .append(i == 5 ? Long.toUnsignedString(values[i]) : Long.toString(values[i]));
    rows.append('\n');
  }

  static void properties(Path root) throws Exception {
    var f = new GeneratedFiles(root);
    var rows = new StringBuilder();
    long[] base = {2, 3, 2, 44100, 16, 44100};
    row(rows, "null", new long[6]);
    row(rows, "base", base);
    for (int i = 0; i < 6; i++)
      for (long v :
        i < 5
          ? new long[]{
          Integer.MIN_VALUE, -1000, -1, 0, 1, 2, 3, 32767, 32768, 65535, Integer.MAX_VALUE
        }
          : new long[]{0, 1, 44100, 0xffffffffL, Long.MAX_VALUE, Long.MIN_VALUE, -1}) {
        long[] b = base.clone();
        b[i] = v;
        row(rows, "field-" + i + "-" + (i == 5 ? Long.toUnsignedString(v) : Long.toString(v)), b);
      }
    for (long rate :
      new long[]{Integer.MIN_VALUE, -1, 0, 1, 1000, 2000, 44100, 48000, Integer.MAX_VALUE})
      for (long frames :
        new long[]{0, 1, 44100, 0xffffffffL, Long.MAX_VALUE, Long.MIN_VALUE, -1}) {
        row(
          rows,
          "duration-" + rate + "-" + Long.toUnsignedString(frames),
          new long[]{2, 3, 2, rate, 16, frames});
      }
    for (long rate : new long[]{0, 1, 44100, Integer.MAX_VALUE})
      for (long bits : new long[]{Integer.MIN_VALUE, -1, 0, 1, 16, 65535, Integer.MAX_VALUE})
        for (long channels : new long[]{-1, 0, 2, 8, Integer.MAX_VALUE}) {
          row(
            rows,
            "bitrate-" + rate + "-" + bits + "-" + channels,
            new long[]{2, 3, channels, rate, bits, 44100});
        }
    f.text("cases.tsv", rows.toString());
    for (int version : new int[]{1, 2, 3})
      for (long type : new long[]{0, 1, 2, 3, 4, 5, 6, 7, 255, 0x80000000L, 0xffffffffL})
        f.put("header-" + version + "-" + type + ".shn", shn(wave(), version, type, 2, 256, 0));
    for (long rate : new long[]{0, 1, 44100, 48000, 0x7fffffff, 0x80000000L, 0xffffffffL})
      for (long data : new long[]{0, 1, 176400, 0xffffffffL})
        f.put(
          "wave-rate-" + rate + "-" + data + ".shn",
          shn(wave(rate, 16, 2, data, 4), 2, 3, 2, 256, 0));
    for (int bits : new int[]{0, 1, 8, 16, 24, 32767, 32768, 65535})
      for (int channels : new int[]{1, 2, 8})
        f.put(
          "wave-width-" + bits + "-" + channels + ".shn",
          shn(wave(44100, bits, channels, 176400, 4), 2, 3, channels, 256, 0));
    for (int align : new int[]{0, 1, 2, 4, 65535})
      f.put(
        "wave-align-" + align + ".shn", shn(wave(44100, 16, 2, 176400, align), 2, 3, 2, 256, 0));
    for (int block : new int[]{1, 256, 65535})
      f.put("block-" + block + ".shn", shn(wave(), 2, 3, 2, block, 0));
    for (int skip : new int[]{0, 1, 16, 255})
      f.put("skip-" + skip + ".shn", shn(wave(), 2, 3, 2, 256, skip));
    for (int version : new int[]{1, 2, 3})
      for (long samples : new long[]{0, 1, 44100, 0xffffffffL})
        f.put(
          "aiff-" + version + "-" + samples + ".shn",
          shn(
            join(
              text("FORM"),
              uint(38, 4, false),
              text("AIFFCOMM"),
              pack(">IHIHHQ", 18, 2, samples, 16, 0x400e, 0xac44000000000000L),
              text("JUNK"),
              new byte[4]),
            version,
            3,
            2,
            256,
            0));
    f.copyExtensions("upstream-", "", ".shn");
  }

  static void trackerSupport(Path root) throws Exception {
    properties(root);
    var f = new GeneratedFiles(root);
    byte[] base = Files.readAllBytes(root.resolve("inputs/header-2-3.shn"));
    for (int n = 0; n < base.length; n++)
      f.put(String.format("prefix-%03d.shn", n), slice(base, 0, n));
    for (int version : new int[]{0, 4, 255})
      f.put(
        "unsupported-" + version + ".shn",
        join(slice(base, 0, 4), new byte[]{(byte) version}, slice(base, 5, base.length)));
    byte[] mod = new byte[1084];
    System.arraycopy(text("Title"), 0, mod, 0, 5);
    System.arraycopy(text("One"), 0, mod, 20, 3);
    System.arraycopy(text("Two"), 0, mod, 50, 3);
    System.arraycopy(text("M.K."), 0, mod, 1080, 4);
    f.raw("valid.mod", mod);
    byte[] it = new byte[192];
    System.arraycopy(text("IMPMTitle"), 0, it, 0, 9);
    f.raw("valid.it", it);
    byte[] xm = new byte[80];
    System.arraycopy(text("Extended Module: Title"), 0, xm, 0, 22);
    xm[37] = 26;
    System.arraycopy(text("Tracker"), 0, xm, 38, 7);
    field(xm, 58, 2, 260, true);
    field(xm, 60, 4, 20, true);
    f.raw("valid.xm", xm);
  }
}
