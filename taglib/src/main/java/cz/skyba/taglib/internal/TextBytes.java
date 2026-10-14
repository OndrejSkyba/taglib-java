// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal;

import cz.skyba.taglib.toolkit.Debug;
import cz.skyba.taglib.toolkit.TagString;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Native text decoding over borrowed byte ranges; returned strings own their characters.
 */
public final class TextBytes {
  private TextBytes() {
  }

  public static String terminated(byte[] bytes, int offset, int length, TagString.Type type) {
    String value = length == 0 ? "" : decode(bytes, offset, length, type);
    int nul = value.indexOf('\0');
    return nul < 0 ? value : value.substring(0, nul);
  }

  public static String decode(byte[] bytes, int offset, int length, TagString.Type type) {
    Objects.checkFromIndexSize(offset, length, bytes.length);
    if (type == TagString.Type.LATIN1) {
      return new String(bytes, offset, length, StandardCharsets.ISO_8859_1);
    }
    if (type == TagString.Type.UTF8) {
      try {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, offset, length)).toString();
      } catch (CharacterCodingException invalid) {
        if (Debug.mode() != Debug.Mode.RELEASE) {
          Debug.debug(new TagString("String::copyFromUTF8() - UTF8-CPP error: " + utf8Error(bytes, offset, length)));
        }
        return "";
      }
    }
    int start = offset;
    boolean big = type == TagString.Type.UTF16BE;
    if (type == TagString.Type.UTF16) {
      if (length < 2) {
        Debug.debug(new TagString("String::copyFromUTF16() - Invalid UTF16 string. Too short to have a BOM."));
        return "";
      }
      if (bytes[offset] == (byte) 0xfe && bytes[offset + 1] == (byte) 0xff) {
        big = true;
      } else if (bytes[offset] != (byte) 0xff || bytes[offset + 1] != (byte) 0xfe) {
        Debug.debug(new TagString("String::copyFromUTF16() - Invalid UTF16 string. BOM is broken."));
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
