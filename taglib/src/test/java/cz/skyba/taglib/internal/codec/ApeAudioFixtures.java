package cz.skyba.taglib.internal.codec;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/**
 * Synthetic headers shared by focused regressions and the native property probe.
 */
public final class ApeAudioFixtures {
  private ApeAudioFixtures() {
  }

  public static Map<String, byte[]> cases() {
    var cases = new TreeMap<String, byte[]>();
    cases.put("ape-current.ape", ape(4000, 52, 4, 73728, 1000, 2, 24, 44100));
    cases.put("ape-garbage-prefix.ape", join(new byte[65534], cases.get("ape-current.ape")));
    cases.put("ape-extended-descriptor.ape", ape(4000, 80, 4, 73728, 1000, 2, 24, 44100));
    cases.put("ape-empty-current.ape", ape(4000, 52, 0, 73728, 0, 2, 24, 44100));
    cases.put("ape-signed-fields.ape", ape(4000, 52, 1, 0, 1000, 65535, 65534, 0xffffffffL));
    cases.put("ape-overflow-count.ape", ape(4000, 52, 0xffffffffL, 73728, 1000, 2, 16, 44100));
    cases.put("ape-short.ape", Arrays.copyOf(cases.get("ape-current.ape"), 40));
    byte[] badDescriptor = cases.get("ape-current.ape").clone();
    le(badDescriptor, 8, 4, 4);
    cases.put("ape-small-descriptor.ape", badDescriptor);
    for (int version : new int[]{3770, 3800, 3900, 3950})
      cases.put("ape-old-" + version + ".ape", oldApe(version, 4000, 4));
    cases.put("ape-empty-old.ape", oldApe(3950, 4000, 0));
    long flags = (9L << 23) | 0x1801;
    cases.put("wv-stereo.wv", wv(0x410, 10000, 0, 10000, flags, new byte[0]));
    cases.put("wv-hybrid.wv", wv(0x410, 10000, 0, 10000, flags | 8, new byte[0]));
    cases.put("wv-invalid-version.wv", wv(0x411, 10000, 0, 10000, flags, new byte[0]));
    cases.put(
      "wv-multichannel.wv",
      join(
        wv(0x407, 10000, 0, 10000, flags & ~0x1000, new byte[0]),
        wv(0x407, 10000, 0, 10000, (flags & ~0x800) | 4, new byte[0])));
    byte[] zeroBlocks = new byte[32 * 1030];
    for (int p = 0; p < zeroBlocks.length; p += 32)
      System.arraycopy(wv(0x410, 0, 0, 0, 0, new byte[0]), 0, zeroBlocks, p, 32);
    cases.put("wv-many-empty-blocks.wv", join(zeroBlocks, cases.get("wv-stereo.wv")));
    cases.put(
      "wv-custom-four-byte.wv",
      wv(
        0x410,
        10000,
        0,
        10000,
        (15L << 23) | 0x1801,
        new byte[]{0x27, 2, 0x40, 0x42, 0x0f, 2}));
    cases.put(
      "wv-custom-three-byte.wv",
      wv(
        0x410,
        10000,
        0,
        10000,
        (15L << 23) | 0x1801,
        new byte[]{0x67, 2, 0x40, 0x42, 0x0f, 0x7f}));
    cases.put(
      "wv-custom-invalid-width.wv",
      wv(
        0x410,
        10000,
        0,
        10000,
        (15L << 23) | 0x1801,
        new byte[]{0x27, 3, 0x40, 0x42, 0x0f, 2, 0, 0}));
    cases.put(
      "wv-dsd.wv", wv(0x410, 10000, 0, 10000, flags | 0x80000000L, new byte[]{0x0e, 1, 3, 0}));
    cases.put(
      "wv-dsd-invalid-shift.wv",
      wv(0x410, 10000, 0, 10000, flags | 0x80000000L, new byte[]{0x0e, 1, 33, 0}));
    cases.put(
      "wv-dsd-later-valid-shift.wv",
      wv(
        0x410,
        10000,
        0,
        10000,
        flags | 0x80000000L,
        new byte[]{0x0e, 1, 33, 0, 0x0e, 1, 2, 0}));
    cases.put(
      "wv-dsd-standard-ignores-custom.wv",
      wv(
        0x410,
        10000,
        0,
        10000,
        flags | 0x80000000L,
        new byte[]{0x27, 2, 0x40, 0x42, 0x0f, 0, 0x0e, 1, 1, 0}));
    byte[] first = wv(0x410, 0xffffffffL, 0, 100, flags, new byte[0]);
    byte[] last = wv(0x410, 0xffffffffL, 1000, 200, flags & ~0x800, new byte[0]);
    cases.put("wv-unknown-count.wv", join(first, new byte[1500], last));
    cases.put(
      "wv-spurious-tail.wv",
      join(
        first,
        new byte[1500],
        last,
        wv(0x411, 0, 8000, 1000, flags, new byte[0]),
        wv(0x410, 0, 8000, 131073, flags, new byte[0])));
    cases.put(
      "wv-wrapped-final-count.wv",
      join(first, wv(0x410, 0, 0xfffffff0L, 32, flags, new byte[0])));
    cases.put("mpc-sv7.mpc", mpc7(100));
    cases.put("mpc-sv7-overflow.mpc", mpc7(0xffffffffL));
    cases.put("mpc-sv7-zero.mpc", mpc7(0));
    cases.put("mpc-truncated-sv7.mpc", new byte[]{'M', 'P', '+', 7});
    byte[] oldMpc = new byte[56];
    le(oldMpc, 0, (192L << 23) | (5 << 11), 4);
    le(oldMpc, 4, 100, 4);
    cases.put("mpc-sv5.mpc", oldMpc);
    byte[] sh = streamHeader(8, 123456, 576, (2 << 13) | (5 << 4));
    byte[] rg = {
      1, (byte) 0xab, (byte) 0xcd, (byte) 0xfe, (byte) 0xdc, 0x12, 0x34, (byte) 0x80, 1, 0
    };
    cases.put("mpc-sv8-sh-first.mpc", join(ascii("MPCK"), packet("SH", sh), packet("RG", rg)));
    cases.put("mpc-sv8-rg-first.mpc", join(ascii("MPCK"), packet("RG", rg), packet("SH", sh)));
    cases.put("mpc-sv8-short-rg.mpc", join(ascii("MPCK"), packet("RG", Arrays.copyOf(rg, 9))));
    cases.put(
      "mpc-sv8-unknown-packet.mpc",
      join(
        ascii("MPCK"),
        packet("EI", new byte[]{1, 2, 3}),
        new byte[]{4, 5, 6},
        packet("SH", sh)));
    cases.put(
      "mpc-sv8-truncated-sh.mpc",
      join(ascii("MPCK"), packet("SH", new byte[]{0, 0, 0, 0, 8, (byte) 0xff, (byte) 0xff})));
    cases.put(
      "mpc-sv8-unsigned-count.mpc",
      join(ascii("MPCK"), packet("SH", streamHeader(8, -1L, 0, 0))));
    cases.put(
      "mpc-sv8-signed-version.mpc",
      join(ascii("MPCK"), packet("SH", streamHeader(0x88, 44100, 0, 0))));
    byte[] id3 = {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0};
    cases.put("mpc-sv8-id3-short.mpc", join(id3, ascii("MPCK"), packet("SH", sh)));
    cases.put("mpc-sv8-id3-padded.mpc", join(id3, ascii("MPCK"), packet("SH", sh), new byte[100]));
    cases.put("ape-id3-prefix.ape", join(id3, cases.get("ape-current.ape")));
    byte[] unusualId3 = id3.clone();
    unusualId3[3] = 5;
    cases.put("mpc-unrecognized-id3-version.mpc", join(unusualId3, cases.get("mpc-sv7.mpc")));
    byte[] invalidSizeId3 = id3.clone();
    invalidSizeId3[9] = (byte) 0x80;
    cases.put("mpc-invalid-id3-size.mpc", join(invalidSizeId3, cases.get("mpc-sv7.mpc")));
    byte[] footerId3 = id3.clone();
    footerId3[3] = 3;
    footerId3[5] = 16;
    cases.put("mpc-id3v3-footer.mpc", join(footerId3, new byte[10], cases.get("mpc-sv7.mpc")));
    return cases;
  }

  static byte[] ape(
    int version,
    int descriptor,
    long frames,
    long blocks,
    long last,
    int channels,
    int bits,
    long rate) {
    byte[] b = new byte[descriptor + 24];
    System.arraycopy(ascii("MAC "), 0, b, 0, 4);
    le(b, 4, version, 2);
    le(b, 8, descriptor, 4);
    le(b, descriptor + 4, blocks, 4);
    le(b, descriptor + 8, last, 4);
    le(b, descriptor + 12, frames, 4);
    le(b, descriptor + 16, bits, 2);
    le(b, descriptor + 18, channels, 2);
    le(b, descriptor + 20, rate, 4);
    return b;
  }

  static byte[] oldApe(int version, int compression, long frames) {
    byte[] b = new byte[76];
    System.arraycopy(ascii("MAC "), 0, b, 0, 4);
    le(b, 4, version, 2);
    le(b, 6, compression, 2);
    le(b, 10, 2, 2);
    le(b, 12, 44100, 4);
    le(b, 24, frames, 4);
    le(b, 28, 1000, 4);
    System.arraycopy(ascii("WAVEfmt "), 0, b, 48, 8);
    le(b, 74, 24, 2);
    return b;
  }

  static byte[] wv(int version, long samples, long index, long count, long flags, byte[] payload) {
    byte[] b = new byte[32 + payload.length];
    System.arraycopy(ascii("wvpk"), 0, b, 0, 4);
    le(b, 4, b.length - 8, 4);
    le(b, 8, version, 2);
    le(b, 12, samples, 4);
    le(b, 16, index, 4);
    le(b, 20, count, 4);
    le(b, 24, flags, 4);
    System.arraycopy(payload, 0, b, 32, payload.length);
    return b;
  }

  static byte[] mpc7(long frames) {
    byte[] b = new byte[56];
    System.arraycopy(new byte[]{'M', 'P', '+', 7}, 0, b, 0, 4);
    le(b, 4, frames, 4);
    le(b, 8, 1L << 16, 4);
    le(b, 12, 32768, 2);
    le(b, 14, -325, 2);
    le(b, 16, 16384, 2);
    le(b, 18, 127, 2);
    return b;
  }

  static byte[] streamHeader(int version, long samples, long silence, int flags) {
    return join(
      new byte[]{0, 0, 0, 0, (byte) version},
      varint(samples),
      varint(silence),
      new byte[]{(byte) (flags >>> 8), (byte) flags});
  }

  static byte[] packet(String type, byte[] data) {
    return join(ascii(type), varint(data.length + 3), data);
  }

  static byte[] varint(long value) {
    byte[] b = new byte[10];
    int p = 9;
    b[p] = (byte) (value & 127);
    while ((value >>>= 7) != 0) b[--p] = (byte) ((value & 127) | 128);
    return Arrays.copyOfRange(b, p, 10);
  }

  static void le(byte[] b, int p, long value, int size) {
    for (int i = 0; i < size; i++) b[p + i] = (byte) (value >>> (8 * i));
  }

  static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.ISO_8859_1);
  }

  static byte[] join(byte[]... parts) {
    var b = new ByteArrayOutputStream();
    for (byte[] part : parts) b.writeBytes(part);
    return b.toByteArray();
  }
}
