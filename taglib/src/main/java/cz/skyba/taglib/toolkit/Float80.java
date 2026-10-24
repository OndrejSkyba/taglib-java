// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.toolkit;

import java.io.Serial;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/// An exact finite extended-precision value represented by a nonnegative significand,
/// a power of two and a separate sign. Signed zero is preserved. Conversion to Java floating
/// point can round or overflow. Byte decoding maps infinity and NaN encodings to positive zero.
public final class Float80 extends Number {
  @Serial
  private static final long serialVersionUID = 1L;
  static final Float80 ZERO = new Float80(false, BigInteger.ZERO, 0);

  private final boolean negative;
  private final BigInteger significand;
  private final int binaryExponent;

  private Float80(boolean negative, BigInteger significand, int binaryExponent) {
    this.negative = negative;
    this.significand = significand;
    this.binaryExponent = binaryExponent;
  }

  static Float80 fromNativeFields(boolean negative, int exponent, long fraction) {
    if (exponent == 0x7fff) {
      return ZERO;
    }
    BigInteger n = BigInteger.valueOf(fraction & Long.MAX_VALUE);
    if (fraction < 0) {
      n = n.setBit(Long.SIZE - 1);
    }
    int power = exponent - 16383 - 63;
    if (exponent == 0) {
      // TagLib uses exponent 0 rather than 1 for denormals. Its ldexpl therefore
      // rounds half a native minimum subnormal; x87's default is ties-to-even.
      BigInteger quotient = n.shiftRight(1);
      if (n.testBit(0) && quotient.testBit(0)) {
        quotient = quotient.add(BigInteger.ONE);
      }
      n = quotient;
      power++;
    }
    if (n.signum() == 0) {
      return negative ? new Float80(true, BigInteger.ZERO, 0) : ZERO;
    }
    int trailing = n.getLowestSetBit();
    return new Float80(negative, n.shiftRight(trailing), power + trailing);
  }

  public boolean isNegative() {
    return negative;
  }

  public boolean isZero() {
    return significand.signum() == 0;
  }

  /// {@return the nonnegative significand, odd for nonzero values; the sign is supplied by [#isNegative()]}
  public BigInteger significand() {
    return significand;
  }

  public int binaryExponent() {
    return binaryExponent;
  }

  /// Returns the exact decimal value. [BigDecimal] does not preserve a zero
  /// sign; consult [#isNegative()] when that distinction matters.
  ///
  /// @return the exact decimal value
  public BigDecimal toBigDecimal() {
    BigInteger n = negative ? significand.negate() : significand;
    if (binaryExponent >= 0) {
      return new BigDecimal(n.shiftLeft(binaryExponent));
    }
    int scale = -binaryExponent;
    return new BigDecimal(n.multiply(BigInteger.valueOf(5).pow(scale)), scale);
  }

  /// Converts to a signed 32-bit integer by truncating fractional digits and retaining low bits
  /// when the result exceeds the integer range.
  @Override
  public int intValue() {
    return toBigDecimal().intValue();
  }

  /// Converts to a signed 64-bit integer by truncating fractional digits and retaining low bits
  /// when the result exceeds the integer range.
  @Override
  public long longValue() {
    return toBigDecimal().longValue();
  }

  /// Converts to a Java float, preserving signed zero. Precision can be rounded and a value
  /// outside the finite float range can become infinity.
  @Override
  public float floatValue() {
    return isZero() && negative ? -0.0f : toBigDecimal().floatValue();
  }

  /// Converts to a Java double, preserving signed zero. Precision can be rounded and a value
  /// outside the finite double range can become infinity.
  @Override
  public double doubleValue() {
    return isZero() && negative ? -0.0 : toBigDecimal().doubleValue();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Float80 value && negative == value.negative
      && binaryExponent == value.binaryExponent && significand.equals(value.significand);
  }

  @Override
  public int hashCode() {
    return Objects.hash(negative, significand, binaryExponent);
  }

  @Override
  public String toString() {
    return (negative ? "-" : "") + significand + "*2^" + binaryExponent;
  }
}
