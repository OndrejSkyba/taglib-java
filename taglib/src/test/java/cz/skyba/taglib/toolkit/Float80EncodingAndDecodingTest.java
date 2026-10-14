package cz.skyba.taglib.toolkit;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class Float80EncodingAndDecodingTest {
  private static ByteVector bytes(String value) {
    return new ByteVector(HexFormat.of().parseHex(value));
  }

  @Test
  void knownAiffRateAndExtendedPrecisionAreExact() {
    assertEquals(new BigDecimal("44100"), bytes("400eac44000000000000").toFloat80BE(0).toBigDecimal());
    Float80 beyondDouble = bytes("3fff8000000000000001").toFloat80BE(0);
    assertEquals(new BigInteger("9223372036854775809"), beyondDouble.significand());
    assertEquals(-63, beyondDouble.binaryExponent());
    assertEquals(new BigDecimal("1.000000000000000000108420217248550443400745280086994171142578125"),
      beyondDouble.toBigDecimal());
    assertEquals(1.0, beyondDouble.doubleValue());
    assertEquals(beyondDouble, bytes("0100000000000080ff3f").toFloat80LE(0));
  }

  @Test
  void fullNativeRangeDoesNotOverflowOrUnderflowTheExactValue() {
    Float80 maximum = bytes("7ffeffffffffffffffff").toFloat80BE(0);
    assertEquals(new BigInteger("18446744073709551615"), maximum.significand());
    assertEquals(16320, maximum.binaryExponent());
    assertEquals(Double.POSITIVE_INFINITY, maximum.doubleValue());
    Float80 minimum = bytes("00000000000000000002").toFloat80BE(0);
    assertEquals(BigInteger.ONE, minimum.significand());
    assertEquals(-16445, minimum.binaryExponent());
    assertTrue(minimum.toBigDecimal().signum() > 0);
    assertEquals(0.0, minimum.doubleValue());
  }

  @Test
  void nativeDenormalExponentQuirkUsesTiesToEven() {
    assertTrue(bytes("00000000000000000001").toFloat80BE(0).isZero());
    Float80 three = bytes("00000000000000000003").toFloat80BE(0);
    assertEquals(BigInteger.ONE, three.significand());
    assertEquals(-16444, three.binaryExponent());
    assertEquals(three, bytes("00000000000000000005").toFloat80BE(0));
    assertEquals(-16443, bytes("00000000000000000007").toFloat80BE(0).binaryExponent());
  }

  @Test
  void conversionToDoubleRoundsTheExactValueAtSubnormalBoundaries() {
    assertEquals(0.0, bytes("3bcc8000000000000000").toFloat80BE(0).doubleValue());
    assertEquals(Double.MIN_VALUE, bytes("3bcc8000000000000001").toFloat80BE(0).doubleValue());
  }

  @Test
  void negativeZeroIsRetainedAndExceptionalEncodingsReturnPositiveZero() {
    Float80 negativeZero = bytes("80000000000000000000").toFloat80BE(0);
    assertTrue(negativeZero.isNegative());
    assertTrue(negativeZero.isZero());
    assertEquals(Long.MIN_VALUE, Double.doubleToRawLongBits(negativeZero.doubleValue()));
    assertEquals(Integer.MIN_VALUE, Float.floatToRawIntBits(negativeZero.floatValue()));
    assertTrue(bytes("80000000000000000001").toFloat80BE(0).isNegative());
    for (String value : new String[]{"7fff8000000000000000", "ffffc000000000000001", "7fff0000000000000000"}) {
      Float80 result = bytes(value).toFloat80BE(0);
      assertTrue(result.isZero());
      assertFalse(result.isNegative());
    }
  }

  @Test
  void offsetsAreCheckedBeforeReading() {
    assertTrue(new ByteVector(9).toFloat80BE(0).isZero());
    assertTrue(new ByteVector(10).toFloat80LE(-1).isZero());
    assertTrue(new ByteVector(10).toFloat80LE(Integer.MAX_VALUE).isZero());
    assertTrue(bytes("7fff8000000000000000").toFloat80BE(0).isZero());
    var framed = bytes("1122400eac4400000000000033");
    assertEquals(44100.0, framed.toFloat80BE(2).doubleValue());
  }
}
