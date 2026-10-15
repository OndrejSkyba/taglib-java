// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal;

import java.util.Objects;

/**
 * Metadata number parsing with partial values and explicit success status.
 * Parsing stops at NUL, accepts leading ASCII whitespace and radix prefixes, and
 * saturates on 64-bit overflow. Integer results keep the low 32 bits; their success
 * flag excludes both signed 32-bit endpoints.
 */
public final class TextNumbers {
  private TextNumbers() {
  }

  public record IntResult(int value, boolean ok) {
  }

  public record LongResult(long value, boolean ok) {
  }

  /** Returns the parsed value even when input is partial or out of range. */
  public static int parseInt(String value) {
    return (int) integer(value, 10, false).value();
  }

  /** Returns the parsed value even when input is partial or out of range. */
  public static long parseLong(String value, int base) {
    return extractLong(value, base).value();
  }

  /** Returns the unsigned value as long bits, even when input is partial or out of range. */
  public static long parseUnsignedLong(String value, int base) {
    return extractUnsignedLong(value, base).value();
  }

  public static IntResult extractInt(String value) {
    var result = integer(value, 10, false);
    long n = result.value();
    return new IntResult(
      (int) n, result.ok() && n > Integer.MIN_VALUE && n < Integer.MAX_VALUE);
  }

  public static LongResult extractLong(String value, int base) {
    return integer(value, base, false);
  }

  public static LongResult extractUnsignedLong(String value, int base) {
    return integer(value, base, true);
  }

  private static int digit(char c) {
    return c >= '0' && c <= '9'
      ? c - '0'
      : c >= 'a' && c <= 'z' ? c - 'a' + 10 : c >= 'A' && c <= 'Z' ? c - 'A' + 10 : -1;
  }

  private static LongResult integer(String value, int base, boolean unsigned) {
    Objects.requireNonNull(value);
    if (base != 0 && (base < 2 || base > 36)) {
      return new LongResult(0L, false);
    }
    int end = value.indexOf('\0');
    if (end < 0) {
      end = value.length();
    }
    int pos = 0;
    while (pos < end && " \t\n\r\f\u000b".indexOf(value.charAt(pos)) >= 0) {
      pos++;
    }
    boolean negative = pos < end && value.charAt(pos) == '-';
    if (pos < end && (value.charAt(pos) == '+' || value.charAt(pos) == '-')) {
      pos++;
    }
    if ((base == 0 || base == 16)
      && pos + 2 < end
      && value.charAt(pos) == '0'
      && (value.charAt(pos + 1) == 'x' || value.charAt(pos + 1) == 'X')
      && digit(value.charAt(pos + 2)) >= 0
      && digit(value.charAt(pos + 2)) < 16) {
      base = 16;
      pos += 2;
    }
    // The pinned Linux oracle's C23-capable libc recognizes binary prefixes.
    if ((base == 0 || base == 2)
      && pos + 2 < end
      && value.charAt(pos) == '0'
      && (value.charAt(pos + 1) == 'b' || value.charAt(pos + 1) == 'B')
      && (value.charAt(pos + 2) == '0' || value.charAt(pos + 2) == '1')) {
      base = 2;
      pos += 2;
    }
    if (base == 0) {
      base = pos < end && value.charAt(pos) == '0' ? 8 : 10;
    }
    int first = pos;
    boolean overflow = false;
    long n = 0;
    long limit = unsigned ? -1L : negative ? Long.MIN_VALUE : Long.MAX_VALUE;
    long quotient = Long.divideUnsigned(limit, base);
    int remainder = (int) Long.remainderUnsigned(limit, base);
    while (pos < end) {
      int digit = digit(value.charAt(pos));
      if (digit < 0 || digit >= base) {
        break;
      }
      if (!overflow) {
        int comparison = Long.compareUnsigned(n, quotient);
        if (comparison > 0 || comparison == 0 && digit > remainder) {
          overflow = true;
          n = limit;
        } else {
          n = n * base + digit;
        }
      }
      pos++;
    }
    long result = n;
    if (negative && !(unsigned && overflow)) {
      result = -result;
    }
    return new LongResult(result, pos > first && pos == end && !overflow);
  }

}
