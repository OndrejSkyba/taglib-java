// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal;

import java.nio.charset.StandardCharsets;

public final class Binary {
  private Binary() {
  }

  public static int u16be(byte[] b, int p) {
    return ((b[p] & 255) << 8) | (b[p + 1] & 255);
  }

  public static int u16le(byte[] b, int p) {
    return ((b[p + 1] & 255) << 8) | (b[p] & 255);
  }

  public static long u32be(byte[] b, int p) {
    return ((long) u16be(b, p) << 16) | u16be(b, p + 2);
  }

  public static long u32le(byte[] b, int p) {
    return ((long) u16le(b, p + 2) << 16) | u16le(b, p);
  }

  public static long u64be(byte[] b, int p) {
    return (u32be(b, p) << 32) | u32be(b, p + 4);
  }

  public static long u64le(byte[] b, int p) {
    return (u32le(b, p + 4) << 32) | u32le(b, p);
  }

  public static String ascii(byte[] b, int p, int n) {
    return new String(b, p, n, StandardCharsets.ISO_8859_1);
  }

  public static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.ISO_8859_1);
  }

  public static void put16be(byte[] b, int p, int n) {
    b[p] = (byte) (n >>> 8);
    b[p + 1] = (byte) n;
  }

  public static void put16le(byte[] b, int p, int n) {
    b[p] = (byte) n;
    b[p + 1] = (byte) (n >>> 8);
  }

  public static void put32be(byte[] b, int p, long n) {
    for (int i = 3; i >= 0; i--) {
      b[p + i] = (byte) n;
      n >>>= 8;
    }
  }

  public static void put32le(byte[] b, int p, long n) {
    for (int i = 0; i < 4; i++) {
      b[p + i] = (byte) n;
      n >>>= 8;
    }
  }

  public static void put64be(byte[] b, int p, long n) {
    for (int i = 7; i >= 0; i--) {
      b[p + i] = (byte) n;
      n >>>= 8;
    }
  }

  public static void put64le(byte[] b, int p, long n) {
    for (int i = 0; i < 8; i++) {
      b[p + i] = (byte) n;
      n >>>= 8;
    }
  }

  public static int synchsafe(byte[] b, int p) {
    return ((b[p] & 127) << 21)
      | ((b[p + 1] & 127) << 14)
      | ((b[p + 2] & 127) << 7)
      | (b[p + 3] & 127);
  }

  public static void putSynchsafe(byte[] b, int p, int n) {
    for (int i = 3; i >= 0; i--) {
      b[p + i] = (byte) (n & 127);
      n >>>= 7;
    }
  }
}
