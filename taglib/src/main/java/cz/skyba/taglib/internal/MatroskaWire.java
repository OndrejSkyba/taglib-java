// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal;


/// Shared bounded wire helpers for native elements and metadata transactions.
public final class MatroskaWire {
  private MatroskaWire() {
  }

  public static byte[] id(long value) {
    int n = value > 0xff_ffffL ? 4 : value > 0xffffL ? 3 : value > 255 ? 2 : 1;
    byte[] out = new byte[n];
    for (int i = n - 1; i >= 0; i--) {
      out[i] = (byte) value;
      value >>>= 8;
    }
    return out;
  }

  public static byte[] uint(long value) {
    int n = 1;
    while (n < 8 && value >>> (8 * n) != 0) {
      n++;
    }
    byte[] out = new byte[n];
    for (int i = n - 1; i >= 0; i--) {
      out[i] = (byte) value;
      value >>>= 8;
    }
    return out;
  }

  public static byte[] size(long value, int requested) {
    if (value < 0 || value >= (1L << 56)) {
      throw new IllegalArgumentException("Unrepresentable EBML size");
    }
    int n = requested > 0 ? requested : 1;
    while (n < 8 && value >= (1L << (7 * n))) {
      n++;
    }
    byte[] out = new byte[n];
    for (int i = n - 1; i >= 0; i--) {
      out[i] = (byte) value;
      value >>>= 8;
    }
    out[0] |= (byte) (1 << (8 - n));
    return out;
  }

  public static byte[] join(byte[]... values) {
    long total = 0;
    for (byte[] value : values) {
      total = Math.addExact(total, value.length);
    }
    if (total > Integer.MAX_VALUE - 8L) {
      throw new IllegalArgumentException("Matroska render exceeds allocation limit");
    }
    byte[] out = new byte[(int) total];
    int at = 0;
    for (byte[] value : values) {
      System.arraycopy(value, 0, out, at, value.length);
      at += value.length;
    }
    return out;
  }

  public static byte[] render(long id, byte[] body) {
    return join(id(id), size(body.length, 0), body);
  }

  public static int voidLength(long total) {
    total = Math.max(2, total);
    if (total > Integer.MAX_VALUE - 8L) {
      throw new IllegalArgumentException("Matroska Void exceeds allocation limit");
    }
    return (int) total;
  }

  public static byte[] voidElement(long total) {
    int length = voidLength(total);
    int width = Math.min(length - 1, 8);
    byte[] result = new byte[length];
    result[0] = (byte) 0xec;
    System.arraycopy(size(length - width - 1L, width), 0, result, 1, width);
    return result;
  }

  public static byte[] pad(byte[] rendered, long minimum) {
    return minimum >= rendered.length + 2L ? join(rendered, voidElement(minimum - rendered.length)) : rendered;
  }
}
