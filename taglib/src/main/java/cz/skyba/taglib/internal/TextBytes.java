// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal;

import cz.skyba.taglib.toolkit.Debug;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.*;

/**
 * Metadata text conversion over borrowed byte ranges and immutable Java strings.
 * Supports the standard Latin-1, UTF-8 and UTF-16 charsets with TagLib's conversion rules:
 * UTF-8 is strict; Latin-1 encoding keeps low bytes; UTF-16 preserves raw code units
 * and drops odd trailing bytes. UTF_16 requires a BOM when decoding and emits a
 * little-endian BOM when encoding. These differ from the JDK's replacement/BOM defaults.
 * Returned strings and encoded bytes own their storage.
 */
public final class TextBytes {
  private TextBytes() {
  }

  public static boolean isLatin1(String value) {
    for (int i = 0; i < value.length(); i++) {
      if (value.charAt(i) > 255) {
        return false;
      }
    }
    return true;
  }

  public static String decodeTerminated(byte[] bytes, Charset charset) {
    return decodeTerminated(bytes, 0, bytes.length, charset);
  }

  /**
   * Decodes the entire range before truncating at the first decoded NUL.
   */
  public static String decodeTerminated(byte[] bytes, int offset, int length, Charset charset) {
    if (length == 0) {
      if (!ISO_8859_1.equals(charset) && !UTF_8.equals(charset)) requireUtf16(charset);
      return "";
    }
    String value = decode(bytes, offset, length, charset);
    int nul = value.indexOf('\0');
    return nul < 0 ? value : value.substring(0, nul);
  }

  public static String decode(byte[] bytes, Charset charset) {
    return decode(bytes, 0, bytes.length, charset);
  }

  public static String decode(byte[] bytes, int offset, int length, Charset charset) {
    Objects.checkFromIndexSize(offset, length, bytes.length);
    if (ISO_8859_1.equals(charset)) {
      return new String(bytes, offset, length, ISO_8859_1);
    }
    if (UTF_8.equals(charset)) {
      try {
        return UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, offset, length)).toString();
      } catch (CharacterCodingException invalid) {
        if (Debug.mode() != Debug.Mode.RELEASE) {
          Debug.debug("String::copyFromUTF8() - UTF8-CPP error: " + utf8Error(bytes, offset, length));
        }
        return "";
      }
    }
    requireUtf16(charset);
    int start = offset;
    boolean big = UTF_16BE.equals(charset);
    if (UTF_16.equals(charset)) {
      if (length < 2) {
        Debug.debug("String::copyFromUTF16() - Invalid UTF16 string. Too short to have a BOM.");
        return "";
      }
      if (bytes[offset] == (byte) 0xfe && bytes[offset + 1] == (byte) 0xff) {
        big = true;
      } else if (bytes[offset] != (byte) 0xff || bytes[offset + 1] != (byte) 0xfe) {
        Debug.debug("String::copyFromUTF16() - Invalid UTF16 string. BOM is broken.");
        return "";
      }
      start += 2;
    }
    char[] result = new char[(offset + length - start) / 2];
    for (int i = 0; i < result.length; i++) {
      int a = bytes[start + 2 * i] & 255, b = bytes[start + 2 * i + 1] & 255;
      result[i] = (char) (big ? a << 8 | b : b << 8 | a);
    }
    return new String(result);
  }

  /**
   * Encodes all UTF-16 code units into independent bytes, preserving embedded NULs.
   * Latin-1 retains the low eight bits; malformed UTF-16 produces empty UTF-8 output.
   */
  public static byte[] encode(String value, Charset charset) {
    Objects.requireNonNull(value);
    if (UTF_8.equals(charset)) {
      try {
        var encoded = UTF_8.newEncoder().encode(CharBuffer.wrap(value));
        byte[] result = new byte[encoded.remaining()];
        encoded.get(result);
        return result;
      } catch (CharacterCodingException invalid) {
        if (Debug.mode() != Debug.Mode.RELEASE) {
          Debug.debug("String::data() - UTF8-CPP error: Invalid UTF-16");
        }
        return new byte[0];
      }
    }
    if (ISO_8859_1.equals(charset)) {
      byte[] result = new byte[value.length()];
      for (int i = 0; i < result.length; i++) {
        result[i] = (byte) value.charAt(i);
      }
      return result;
    }
    requireUtf16(charset);
    int start = UTF_16.equals(charset) ? 2 : 0;
    byte[] result = new byte[Math.addExact(start, Math.multiplyExact(value.length(), 2))];
    if (start == 2) {
      result[0] = (byte) 255;
      result[1] = (byte) 254;
    }
    boolean big = UTF_16BE.equals(charset);
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      result[start + 2 * i] = (byte) (big ? c >>> 8 : c);
      result[start + 2 * i + 1] = (byte) (big ? c : c >>> 8);
    }
    return result;
  }

  private static void requireUtf16(Charset charset) {
    Objects.requireNonNull(charset);
    if (!UTF_16.equals(charset) && !UTF_16BE.equals(charset) && !UTF_16LE.equals(charset)) {
      throw new IllegalArgumentException("Unsupported metadata charset: " + charset);
    }
  }

  /**
   * Classifies the first error as pinned UTF8-CPP does, without changing JDK decoding.
   */
  private static String utf8Error(byte[] bytes, int offset, int count) {
    for (int i = offset; i < offset + count; ) {
      int lead = bytes[i] & 255;
      int length = lead < 128 ? 1 : (lead & 0xe0) == 0xc0 ? 2
        : (lead & 0xf0) == 0xe0 ? 3 : (lead & 0xf8) == 0xf0 ? 4 : 0;
      if (length == 0) return "Invalid UTF-8";
      for (int j = 1; j < length; j++) {
        if (i + j >= offset + count) return "Not enough space";
        if ((bytes[i + j] & 0xc0) != 0x80) return "Invalid UTF-8";
      }
      int second = length > 1 ? bytes[i + 1] & 255 : 0;
      if (length == 2 && lead < 0xc2 || length == 3 && lead == 0xe0 && second < 0xa0
        || length == 4 && lead == 0xf0 && second < 0x90) return "Invalid UTF-8";
      if (length == 3 && lead == 0xed && second > 0x9f
        || length == 4 && (lead > 0xf4 || lead == 0xf4 && second > 0x8f)) return "Invalid code point";
      i += length;
    }
    return "Invalid UTF-8";
  }

}
