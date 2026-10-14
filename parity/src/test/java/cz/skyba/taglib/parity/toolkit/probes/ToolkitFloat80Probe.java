package cz.skyba.taglib.parity.toolkit.probes;

import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.Float80;

class ToolkitFloat80Probe {
  private static String canonical(Float80 value) {
    return (value.isNegative() ? "-" : "+")
      + value.significand()
      + "*2^"
      + value.binaryExponent()
      + ";double="
      + Long.toUnsignedString(Double.doubleToRawLongBits(value.doubleValue()));
  }

  private static ByteVector vector(int exponent, long fraction, boolean negative, boolean big) {
    byte[] bytes = new byte[10];
    int header = exponent | (negative ? 0x8000 : 0);
    bytes[0] = (byte) (header >>> 8);
    bytes[1] = (byte) header;
    for (int i = 0; i < 8; i++) bytes[2 + i] = (byte) (fraction >>> (56 - 8 * i));
    if (!big) {
      for (int i = 0; i < 5; i++) {
        byte temp = bytes[i];
        bytes[i] = bytes[9 - i];
        bytes[9 - i] = temp;
      }
    }
    return new ByteVector(bytes);
  }

  public static void main(String[] args) {
    var shortValue = new ByteVector(9).toFloat80BE(0);
    System.out.println("short=" + canonical(shortValue));
    var beyond = new ByteVector(10).toFloat80LE(1);
    System.out.println("beyond=" + canonical(beyond));
    var exceptional = vector(0x7fff, 0xc000000000000001L, true, true).toFloat80BE(0);
    System.out.println("exceptional=" + canonical(exceptional));
    int[] exponents = {
      0, 1, 2, 4096, 12288, 15306, 15307, 15308, 15309, 15310, 16382, 16383, 16384, 16385, 17406,
      17407, 17408, 32765, 32766, 32767
    };
    long[] fractions = {
      0,
      1,
      2,
      3,
      4,
      5,
      7,
      15,
      0x7fffffffffffffffL,
      0x8000000000000000L,
      0x8000000000000001L,
      0x8000000000000003L,
      0xfffffffffffffffdL,
      0xfffffffffffffffeL,
      0xffffffffffffffffL
    };
    int row = 0;
    for (boolean big : new boolean[]{true, false})
      for (boolean negative : new boolean[]{false, true})
        for (int exponent : exponents)
          for (long fraction : fractions) {
            var bytes = vector(exponent, fraction, negative, big);
            var value = big ? bytes.toFloat80BE(0) : bytes.toFloat80LE(0);
            System.out.println("float80." + row++ + "=" + canonical(value));
          }
    var framed = new ByteVector("xx");
    framed.append(vector(0x400e, 0xac44000000000000L, false, true));
    framed.append((byte) '!');
    System.out.println("offset=" + canonical(framed.toFloat80BE(2)));
  }
}
