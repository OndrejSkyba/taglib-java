package cz.skyba.taglib.parity.fixtures;

import java.nio.file.Path;
import java.util.HexFormat;

import static cz.skyba.taglib.parity.fixtures.Bytes.*;
import static cz.skyba.taglib.parity.fixtures.Id3ReleaseFixtures.dff;
import static cz.skyba.taglib.parity.fixtures.Id3ReleaseFixtures.dffChunk;

final class DsdAudioPropertiesFixtures {
  private static void row(StringBuilder rows, String name, String kind, Object... fields) {
    rows.append(name).append('\t').append(kind);
    for (Object field : fields)
      rows.append('\t').append(field instanceof byte[] b ? HexFormat.of().formatHex(b) : field);
    rows.append('\n');
  }

  private static byte[] prop(byte[] marker, byte[] fs, byte[] channels) {
    return dffChunk("PROP", join(marker, dffChunk("FS  ", fs), dffChunk("CHNL", channels)));
  }

  static void generate(Path root) throws Exception {
    var f = new GeneratedFiles(root);
    var rows = new StringBuilder();
    byte[] fmt = pack("<IIIIIIqII", 1, 0, 2, 2, 2822400, 1, 2822400, 4096, 0),
      header = join(text("TTA1"), pack("<HHHII", 1, 2, 16, 44100, 162496));
    for (int n = 0; n < 53; n++) {
      row(rows, "dsf-partial-" + n, "dsf", slice(join(fmt, new byte[12]), 0, n));
    }
    for (int[] pair :
      new int[][]{{0, 4}, {4, 4}, {8, 4}, {12, 4}, {16, 4}, {20, 4}, {24, 8}, {32, 4}})
      for (long v :
        new long[]{
          0,
          1,
          127,
          255,
          32768,
          0x7fffffff,
          0x80000000L,
          0xffffffffL,
          Long.MAX_VALUE,
          Long.MIN_VALUE,
          -1
        })
        if (pair[1] == 8 || Long.compareUnsigned(v, 0xffffffffL) <= 0) {
          byte[] b = fmt.clone();
          field(b, pair[0], pair[1], v, true);
          row(rows, "dsf-field-" + pair[0] + "-" + Long.toUnsignedString(v), "dsf", b);
        }
    for (long samples :
      new long[]{2147483646L, 2147483647L, 4294967294L, 4294967295L, 4294967296L}) {
      byte[] b = fmt.clone();
      field(b, 16, 4, 1000, true);
      field(b, 24, 8, samples, true);
      row(rows, "dsf-duration-" + samples, "dsf", b);
    }
    for (int n = 0; n < 23; n++) {
      row(rows, "tta-partial-" + n, "tta", slice(join(header, new byte[4]), 0, n), 79517);
    }
    for (int version : new int[]{0, 1, 48, 49, 50, 57, 127, 128, 255}) {
      byte[] b = header.clone();
      b[3] = (byte) version;
      row(rows, "tta-version-" + version, "tta", b, 79517);
    }
    for (int[] pair : new int[][]{{6, 2}, {8, 2}, {10, 4}, {14, 4}})
      for (long v : new long[]{0, 1, 32767, 32768, 65535, 0x7fffffff, 0x80000000L, 0xffffffffL})
        if (v < (1L << (pair[1] * 8))) {
          byte[] b = header.clone();
          field(b, pair[0], pair[1], v, true);
          row(rows, "tta-field-" + pair[0] + "-" + v, "tta", b, 79517);
        }
    for (long length : new long[]{-1, 0, 1, 79517, 2147483647, 4294967295L, Long.MAX_VALUE}) {
      row(rows, "tta-stream-length-" + length, "tta", header, length);
    }
    for (long rate : new long[]{0, 1, 1000, 2822400, 0x7fffffff, 0x80000000L, 0xffffffffL})
      for (long samples :
        new long[]{0, 1, 2822400, 2147483646L, 2147483647L, Long.MAX_VALUE, Long.MIN_VALUE, -1})
        for (int channels : new int[]{0, 2, 65535}) {
          row(
            rows,
            "dff-" + rate + "-" + Long.toUnsignedString(samples) + "-" + channels,
            "dff",
            rate,
            channels,
            Long.toUnsignedString(samples),
            channels == 0 ? -1 : 5644);
        }
    for (long rate : new long[]{0, 1, 2822400, 0x7fffffff, 0x80000000L, 0xffffffffL})
      for (int channels : new int[]{0, 1, 2, 65535}) {
        byte[] prop = prop(text("SND "), uint(rate, 4, false), uint(channels, 2, false));
        f.put(
          "dff-raw-" + rate + "-" + channels + ".dff",
          dff(prop, dffChunk("DSD ", new byte[128])));
        for (long[] pair :
          new long[][]{{0, 75}, {1, 75}, {75, 75}, {0xffffffffL, 1}, {0xffffffffL, 65535}})
          f.put(
            "dff-dst-" + rate + "-" + channels + "-" + pair[0] + "-" + pair[1] + ".dff",
            dff(
              prop,
              dffChunk(
                "DST ",
                join(
                  dffChunk("FRTE", pack(">IH", pair[0], pair[1])),
                  dffChunk("DSTF", new byte[128])))));
      }
    byte[] fs = uint(2822400, 4, false),
      channels = uint(2, 2, false),
      prop = prop(text("SND "), fs, channels),
      audio = dffChunk("DSD ", new byte[128]);
    for (byte[] marker : new byte[][]{text("SND "), text("xxxx"), new byte[4], text("PROP")})
      f.put(
        "dff-prop-marker-" + HexFormat.of().formatHex(marker) + ".dff",
        dff(prop(marker, fs, channels), audio));
    for (int n = 0; n < 4; n++) {
      f.put("dff-short-prop-" + n + ".dff", dff(dffChunk("PROP", repeat('x', n)), audio));
      f.put(
        "dff-short-fs-" + n + ".dff", dff(prop(text("SND "), slice(fs, 0, n), channels), audio));
    }
    for (int n = 0; n < 2; n++)
      f.put(
        "dff-short-chnl-" + n + ".dff",
        dff(prop(text("SND "), fs, slice(channels, 0, n)), audio));
    for (int n = 0; n < 6; n++)
      f.put(
        "dff-short-frte-" + n + ".dff",
        dff(
          prop,
          dffChunk(
            "DST ",
            join(
              dffChunk("FRTE", slice(pack(">IH", 75, 75), 0, n)),
              dffChunk("DSTF", new byte[128])))));
    for (byte[] tail :
      new byte[][]{new byte[12], join(text("JUNK"), uint(-1, 8, false)), text("partial")})
      f.put(
        "dff-frte-later-" + HexFormat.of().formatHex(tail) + ".dff",
        dff(prop, dffChunk("DST ", join(dffChunk("FRTE", pack(">IH", 75, 75)), tail))));
    f.put(
      "dff-two-prop-fields.dff",
      dff(
        dffChunk("PROP", join(text("SND "), dffChunk("FS  ", fs))),
        dffChunk("PROP", join(text("SND "), dffChunk("CHNL", channels))),
        audio));
    for (byte[] lead : new byte[][]{new byte[0], hex("49443304000000000000")})
      for (byte[] tail : new byte[][]{new byte[0], join(text("TAG"), new byte[125])})
        f.put(
          "tta-tags-" + lead.length + "-" + tail.length + ".tta",
          join(lead, header, new byte[500], tail));
    for (int[] pair : new int[][]{{0, 4}, {4, 4}, {8, 4}, {24, 8}, {32, 4}})
      for (long value : new long[]{0, 1, 0x7fffffff, 0xffffffffL}) {
        byte[] b = fmt.clone();
        field(b, pair[0], pair[1], value, true);
        byte[] content =
          join(text("fmt "), uint(52, 8, true), b, text("data"), uint(44, 8, true), new byte[32]);
        f.put(
          "dsf-file-field-" + pair[0] + "-" + value + ".dsf",
          join(text("DSD "), pack("<QQQ", 28, content.length + 28, 0), content));
      }
    f.copyExtensions("upstream-", "", ".dsf", ".dff", ".tta");
    f.text("cases.tsv", rows.toString());
  }
}
