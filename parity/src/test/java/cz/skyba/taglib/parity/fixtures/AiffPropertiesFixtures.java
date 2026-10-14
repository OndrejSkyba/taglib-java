package cz.skyba.taglib.parity.fixtures;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.*;

import static cz.skyba.taglib.parity.fixtures.Bytes.*;
import static cz.skyba.taglib.parity.fixtures.TagBytes.aiffChunk;

final class AiffPropertiesFixtures {
  private record Rate(BigInteger numerator, BigInteger denominator) {
    Rate(long n, long d) {
      this(BigInteger.valueOf(n), BigInteger.valueOf(d));
    }
  }

  private static byte[] extended(Rate value) {
    if (value.numerator.signum() == 0) return new byte[10];
    int sign = value.numerator.signum() < 0 ? 0x8000 : 0;
    BigInteger n = value.numerator.abs(), d = value.denominator;
    int e = n.bitLength() - d.bitLength();
    if ((e >= 0 ? n.compareTo(d.shiftLeft(e)) : n.shiftLeft(-e).compareTo(d)) < 0) e--;
    int shift = 63 - e;
    BigInteger significand =
      shift >= 0 ? n.shiftLeft(shift).divide(d) : n.divide(d.shiftLeft(-shift));
    return join(uint(sign + e + 16383, 2, false), uint(significand.longValue(), 8, false));
  }

  private static byte[] comm(Rate rate, long samples) {
    return join(pack(">HIH", 2, samples, 16), extended(rate));
  }

  private static byte[] aiff(List<byte[]> comms, List<byte[]> sounds, String type) {
    var chunks = new ArrayList<byte[]>();
    for (byte[] b : comms) chunks.add(aiffChunk("COMM", b));
    for (byte[] b : sounds) chunks.add(aiffChunk("SSND", b));
    return TagBytes.aiff(type, join(chunks));
  }

  private static byte[] aiff(byte[]... comms) {
    return aiff(Arrays.asList(comms), List.of(new byte[64]), "AIFF");
  }

  static void generate(Path root) throws Exception {
    var f = new GeneratedFiles(root);
    byte[] base = comm(new Rate(44100, 1), 44100);
    for (int n = 0; n < 40; n++)
      f.put(
        "comm-partial-" + n + ".aiff",
        aiff(slice(join(base, hex("4e4f4e450568656c6c6f"), new byte[8]), 0, n)));
    for (int n : new int[]{0, 1, 2, 7, 8, 64, 65535})
      f.put("ssnd-size-" + n + ".aiff", aiff(List.of(base), List.of(new byte[n]), "AIFF"));
    f.put("no-comm.aiff", aiff());
    f.put("no-ssnd.aiff", aiff(List.of(base), List.of(), "AIFF"));
    f.put("empty-first.aiff", aiff(new byte[0], base));
    f.put("short-first.aiff", aiff(text("!"), base));
    f.put("duplicate.aiff", aiff(base, comm(new Rate(48000, 1), 96000)));
    f.put("empty-second.aiff", aiff(base, new byte[0]));
    for (int e : new int[]{0, 1, 16382, 16383, 16398, 16414, 16415, 0x7fff, 0x8000, 0xffff})
      for (long m : new long[]{0, 1, Long.MAX_VALUE, Long.MIN_VALUE, -1})
        f.put(
          String.format("float80-%04x-%016x.aiff", e, m),
          aiff(join(slice(base, 0, 8), pack(">HQ", e, m))));
    var rates =
      new ArrayList<Rate>(
        List.of(
          new Rate(1, 1),
          new Rate(1, 2),
          new Rate(3, 2),
          new Rate(44100, 1),
          new Rate(88201, 2),
          new Rate(48000, 1),
          new Rate(2147483647, 1)));
    for (long[] pair : new long[][]{{88201, 2}, {2000, 1}, {4294967293L, 2}}) {
      int shift = pair[0] / pair[1] < 100000 ? 48 : 33;
      BigInteger scale = BigInteger.ONE.shiftLeft(shift);
      for (int delta : new int[]{-1, 0, 1})
        rates.add(
          new Rate(
            BigInteger.valueOf(pair[0])
              .multiply(scale)
              .add(BigInteger.valueOf(delta * pair[1])),
            BigInteger.valueOf(pair[1]).multiply(scale)));
    }
    for (int i = 0; i < rates.size(); i++)
      for (long samples : new long[]{0, 1, 44100, 0x7fffffff, 0xffffffffL})
        f.put("arithmetic-" + i + "-" + samples + ".aiff", aiff(comm(rates.get(i), samples)));
    for (int[] pair : new int[][]{{0, 2}, {2, 4}, {6, 2}})
      for (long v : new long[]{0, 1, 32767, 32768, 65535, 0x7fffffff, 0xffffffffL})
        if (v < (1L << (pair[1] * 8))) {
          byte[] b = base.clone();
          field(b, pair[0], pair[1], v, false);
          f.put("field-" + pair[0] + "-" + v + ".aiff", aiff(b));
        }
    for (byte[] type :
      new byte[][]{text("NONE"), text("ALAW"), text("sowt"), text("zzzz"), hex("00ff0080")})
      for (byte[] name :
        new byte[][]{
          new byte[0], text("hello"), hex("4c6174696eff"), text("a\0b"), repeat('x', 255)
        })
        for (int declared : new TreeSet<>(List.of(0, name.length, 255)))
          f.put(
            "compression-"
              + HexFormat.of().formatHex(type)
              + "-"
              + GeneratedFiles.hash(name).substring(0, 16)
              + "-"
              + declared
              + ".aifc",
            aiff(
              List.of(join(base, type, new byte[]{(byte) declared}, name)),
              List.of(new byte[64]),
              "AIFC"));
    f.copyExtensions("upstream-", "", ".aiff", ".aif", ".aifc", ".afc");
  }
}
