// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.toolkit;

import cz.skyba.taglib.internal.Ascii;
import cz.skyba.taglib.internal.TextBytes;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * A mutable UTF-16 text value with explicit binary encoding and numeric parsing operations.
 * Copies are independent wrappers over immutable Java text. Java-string construction and
 * mutation preserve embedded NUL characters; byte-vector construction stops after decoding
 * at the first NUL. Indexes count UTF-16 code units. Instances are not synchronized.
 */
public final class TagString implements Comparable<TagString>, Iterable<Character> {
  /**
   * Text encodings used for byte conversion. {@code UTF16} includes a byte-order mark; explicit-endian forms do not.
   */
  public enum Type {
    LATIN1,
    UTF16,
    UTF16BE,
    UTF8,
    UTF16LE
  }

  private String value;

  /**
   * Creates empty text.
   */
  public TagString() {
    this("");
  }

  /**
   * Creates a text value from all Java UTF-16 code units, preserving embedded NULs.
   *
   * @param value the text to store
   */
  public TagString(String value) {
    this.value = Objects.requireNonNull(value);
  }

  /**
   * Creates an independently mutable copy, preserving embedded NUL characters.
   *
   * @param other the text to copy
   */
  public TagString(TagString other) {
    value = Objects.requireNonNull(other).value;
  }

  /**
   * Creates a text value containing one UTF-16 code unit.
   *
   * @param value the character to store
   */
  public TagString(char value) {
    this(String.valueOf(value));
  }

  /**
   * Decodes bytes into text and ends the result at the first decoded NUL character. The encoding is ISO-8859-1.
   * Invalid UTF-8 or a missing required UTF-16 byte-order mark produces empty text.
   *
   * @param bytes the encoded bytes
   */
  public TagString(ByteVector bytes) {
    this(bytes, Type.LATIN1);
  }

  /**
   * Decodes bytes into text and ends the result at the first decoded NUL character.
   * Invalid UTF-8 or a missing required UTF-16 byte-order mark produces empty text.
   *
   * @param bytes the encoded bytes
   * @param type  the byte encoding
   */
  public TagString(ByteVector bytes, Type type) {
    value = bytes.isEmpty() ? "" : decode(bytes.data(), type);
    int nul = value.indexOf('\0');
    if (nul >= 0) {
      value = value.substring(0, nul);
    }
  }

  /**
   * Decodes ISO-8859-1 or UTF-8 bytes without truncating at embedded NUL characters. The encoding is ISO-8859-1.
   * Unsupported encodings and invalid UTF-8 produce empty text.
   *
   * @param bytes the encoded bytes
   * @return decoded text
   */
  public static TagString from8Bit(byte[] bytes) {
    return from8Bit(bytes, Type.LATIN1);
  }

  /**
   * Decodes ISO-8859-1 or UTF-8 bytes without truncating at embedded NUL characters.
   * Unsupported encodings and invalid UTF-8 produce empty text.
   *
   * @param bytes the encoded bytes
   * @param type  {@code LATIN1} or {@code UTF8}
   * @return decoded text
   */
  public static TagString from8Bit(byte[] bytes, Type type) {
    if (type != Type.LATIN1 && type != Type.UTF8) {
      Debug.debug(new TagString("String::String() -- std::string should not contain UTF16."));
      return new TagString();
    }
    return new TagString(decode(bytes, type));
  }

  private static String decode(byte[] bytes, Type type) {
    return TextBytes.decode(bytes, 0, bytes.length, type);
  }

  /**
   * Encodes all stored UTF-16 code units, including embedded NULs, into independent byte storage. The encoding is ISO-8859-1.
   * {@code LATIN1} retains each code unit's low eight bits. {@code UTF16} emits little-endian
   * bytes with a byte-order mark. Invalid UTF-16 input for UTF-8 encoding produces empty data.
   *
   * @return encoded bytes
   */
  public ByteVector data() {
    return data(Type.LATIN1);
  }

  /**
   * Encodes all stored UTF-16 code units, including embedded NULs, into independent byte storage.
   * {@code LATIN1} retains each code unit's low eight bits. {@code UTF16} emits little-endian
   * bytes with a byte-order mark. Invalid UTF-16 input for UTF-8 encoding produces empty data.
   *
   * @param type the output encoding
   * @return encoded bytes
   */
  public ByteVector data(Type type) {
    if (type == Type.UTF8) {
      try {
        var encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value));
        byte[] result = new byte[encoded.remaining()];
        encoded.get(result);
        return ByteVector.takeOwnership(result);
      } catch (CharacterCodingException invalid) {
        if (Debug.mode() != Debug.Mode.RELEASE) {
          Debug.debug(new TagString("String::data() - UTF8-CPP error: Invalid UTF-16"));
        }
        return new ByteVector();
      }
    }
    if (type == Type.LATIN1) {
      byte[] result = new byte[size()];
      for (int i = 0; i < result.length; i++) {
        result[i] = (byte) value.charAt(i);
      }
      return ByteVector.takeOwnership(result);
    }
    int start = type == Type.UTF16 ? 2 : 0;
    byte[] result = new byte[Math.addExact(start, Math.multiplyExact(size(), 2))];
    if (start == 2) {
      result[0] = (byte) 255;
      result[1] = (byte) 254;
    }
    boolean big = type == Type.UTF16BE;
    for (int i = 0; i < size(); i++) {
      char c = value.charAt(i);
      result[start + 2 * i] = (byte) (big ? c >>> 8 : c);
      result[start + 2 * i + 1] = (byte) (big ? c : c >>> 8);
    }
    return ByteVector.takeOwnership(result);
  }

  /**
   * Encodes the text into a new byte array, preserving embedded NUL characters. The encoding is ISO-8859-1.
   *
   * @return independent encoded bytes
   */
  public byte[] to8Bit() {
    return to8Bit(false);
  }

  /**
   * Encodes the text into a new byte array, preserving embedded NUL characters.
   *
   * @param unicode whether to use UTF-8 instead of the low eight bits of each code unit
   * @return independent encoded bytes
   */
  public byte[] to8Bit(boolean unicode) {
    return data(unicode ? Type.UTF8 : Type.LATIN1).data();
  }

  public int size() {
    return value.length();
  }

  public int length() {
    return size();
  }

  public boolean isEmpty() {
    return value.isEmpty();
  }

  /**
   * Returns a UTF-16 code unit; an index equal to the text length returns NUL.
   *
   * @param index the code-unit index
   * @return the stored code unit, or NUL at the end
   * @throws IndexOutOfBoundsException if the index is outside zero to length
   */
  public char at(int index) {
    return index == value.length() ? '\0' : value.charAt(index);
  }

  /**
   * Replaces a UTF-16 code unit. Writing NUL at the text length has no effect.
   *
   * @param index     the code-unit index
   * @param character the replacement code unit
   * @throws IndexOutOfBoundsException if the index is invalid for a replacement
   */
  public void set(int index, char character) {
    if (index == value.length() && character == 0) {
      return;
    }
    // Undefined native writes reject before changing state in Java.
    Objects.checkIndex(index, value.length());
    char[] chars = value.toCharArray();
    chars[index] = character;
    value = new String(chars);
  }

  /**
   * Searches forward for a literal UTF-16 code-unit sequence. Negative offsets return {@code -1}.
   *
   * @param pattern the text to find
   * @return the matching code-unit index, or {@code -1} if absent
   */
  public int find(TagString pattern) {
    return find(pattern, 0);
  }

  /**
   * Searches forward for a literal UTF-16 code-unit sequence. Negative offsets return {@code -1}.
   *
   * @param pattern the text to find
   * @param offset  the starting code-unit index
   * @return the matching code-unit index, or {@code -1} if absent
   */
  public int find(TagString pattern, int offset) {
    return offset < 0 ? -1 : value.indexOf(pattern.value, offset);
  }

  /**
   * Searches backward for a literal UTF-16 code-unit sequence. Negative offsets select the end of the text.
   *
   * @param pattern the text to find
   * @return the matching code-unit index, or {@code -1} if absent
   */
  public int rfind(TagString pattern) {
    return rfind(pattern, -1);
  }

  /**
   * Searches backward for a literal UTF-16 code-unit sequence. Negative offsets select the end of the text.
   *
   * @param pattern the text to find
   * @param offset  the starting code-unit index
   * @return the matching code-unit index, or {@code -1} if absent
   */
  public int rfind(TagString pattern, int offset) {
    return value.lastIndexOf(pattern.value, offset < 0 ? size() : offset);
  }

  /**
   * Tests whether this text starts with the supplied code-unit sequence.
   *
   * @param pattern the prefix to test
   * @return whether the prefix matches
   */
  public boolean startsWith(TagString pattern) {
    return value.startsWith(pattern.value);
  }

  /**
   * Returns an independent copy of a UTF-16 code-unit range. The range extends to the end of the text.
   *
   * @param position the starting code-unit index
   * @return copied text
   * @throws IndexOutOfBoundsException if the start or resulting range is invalid
   */
  public TagString substr(int position) {
    return substr(position, Integer.MAX_VALUE);
  }

  /**
   * Returns an independent copy of a UTF-16 code-unit range. The end is clamped to the available text.
   *
   * @param position the starting code-unit index
   * @param count    the maximum code-unit count
   * @return copied text
   * @throws IndexOutOfBoundsException if the start or resulting range is invalid
   */
  public TagString substr(int position, int count) {
    return new TagString(value.substring(position, position + Math.min(count, size() - position)));
  }

  /**
   * Appends all code units of another text value, preserving embedded NULs.
   *
   * @param other the text to append
   * @return this text value
   */
  public TagString append(TagString other) {
    Objects.requireNonNull(other);
    value = value + other.value;
    return this;
  }

  /**
   * Removes all code units.
   *
   * @return this text value
   */
  public TagString clear() {
    value = "";
    return this;
  }

  /**
   * Returns independent text with ASCII lowercase letters converted to uppercase.
   * Other characters are unchanged.
   *
   * @return uppercase text
   */
  public TagString upper() {
    return new TagString(Ascii.upper(value));
  }

  /**
   * Splits at literal delimiter matches, retaining empty fields, including a trailing empty field. The delimiter is one ASCII space.
   *
   * @return an independent mutable list of immutable text fields
   * @throws IllegalArgumentException if the delimiter is empty
   */
  public List<String> split() {
    return split(new TagString(" "));
  }

  /**
   * Splits at literal delimiter matches, retaining empty fields, including a trailing empty field.
   *
   * @param separator the nonempty delimiter
   * @return an independent mutable list of immutable text fields
   * @throws IllegalArgumentException if the delimiter is empty
   */
  public List<String> split(TagString separator) {
    if (separator.isEmpty()) {
      throw new IllegalArgumentException("Native split does not terminate for an empty separator");
    }
    var result = new ArrayList<String>();
    int start = 0, next;
    while ((next = value.indexOf(separator.value, start)) >= 0) {
      result.add(value.substring(start, next));
      start = next + separator.size();
    }
    result.add(value.substring(start));
    return result;
  }

  /**
   * Returns independent text without leading or trailing ASCII space, tab, newline,
   * form feed or carriage return. Other whitespace characters are retained.
   *
   * @return trimmed text
   */
  public TagString stripWhiteSpace() {
    int begin = 0, end = size();
    while (begin < end && "\t\n\f\r ".indexOf(value.charAt(begin)) >= 0) {
      begin++;
    }
    while (end > begin && "\t\n\f\r ".indexOf(value.charAt(end - 1)) >= 0) {
      end--;
    }
    return substr(begin, end - begin);
  }

  /**
   * Tests whether every UTF-16 code unit fits in an eight-bit value.
   *
   * @return whether all code units are below 256
   */
  public boolean isLatin1() {
    return value.chars().allMatch(c -> c < 256);
  }

  /**
   * Tests whether every code unit is an ASCII character.
   *
   * @return whether all code units are below 128
   */
  public boolean isAscii() {
    return value.chars().allMatch(c -> c < 128);
  }

  private static int digit(char c) {
    return c >= '0' && c <= '9'
      ? c - '0'
      : c >= 'a' && c <= 'z' ? c - 'a' + 10 : c >= 'A' && c <= 'Z' ? c - 'A' + 10 : -1;
  }

  private Variant.Extraction<Long> integer(int base, boolean unsigned) {
    if (base != 0 && (base < 2 || base > 36)) {
      return new Variant.Extraction<>(0L, false);
    }
    int end = value.indexOf('\0');
    if (end < 0) {
      end = size();
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
    return new Variant.Extraction<>(result, pos > first && pos == end && !overflow);
  }

  /**
   * Parses an integer from the leading text after optional whitespace and sign. The base is decimal.
   * Parsing stops at NUL or a non-digit. The value can contain a parsed prefix even when
   * the complete text is not a valid number; use an extraction method to check success.
   * The result retains the low 32 bits; the success flag excludes both signed 32-bit endpoints.
   *
   * @return the parsed value, without a success indication
   */
  public int toInt() {
    return extractInt().value();
  }

  /**
   * Parses an integer from the leading text after optional whitespace and sign. The base is decimal.
   * Parsing stops at NUL or a non-digit. The value can contain a parsed prefix even when
   * the success flag is false.
   * Success requires complete consumption before NUL and no overflow.
   * The result retains the low 32 bits; the success flag excludes both signed 32-bit endpoints.
   *
   * @return the value and parsing success flag
   */
  public Variant.Extraction<Integer> extractInt() {
    var result = integer(10, false);
    long n = result.value();
    return new Variant.Extraction<>(
      (int) n, result.ok() && n > Integer.MIN_VALUE && n < Integer.MAX_VALUE);
  }

  /**
   * Parses a signed 64-bit integer from the leading text after optional whitespace and sign. The base is decimal.
   * Parsing stops at NUL or a non-digit. The value can contain a parsed prefix even when
   * the complete text is not a valid number; use an extraction method to check success.
   *
   * @return the parsed value, without a success indication
   */
  public long toLong() {
    return toLong(10);
  }

  /**
   * Parses a signed 64-bit integer from the leading text after optional whitespace and sign.
   * Parsing stops at NUL or a non-digit. The value can contain a parsed prefix even when
   * the complete text is not a valid number; use an extraction method to check success.
   *
   * @param base a radix from 2 to 36, or zero to recognize decimal, octal, hex and binary prefixes
   * @return the parsed value, without a success indication
   */
  public long toLong(int base) {
    return integer(base, false).value();
  }

  /**
   * Parses a signed 64-bit integer from the leading text after optional whitespace and sign. The base is decimal.
   * Parsing stops at NUL or a non-digit. The value can contain a parsed prefix even when
   * the success flag is false.
   * Success requires complete consumption before NUL and no overflow.
   *
   * @return the value and parsing success flag
   */
  public Variant.Extraction<Long> extractLong() {
    return extractLong(10);
  }

  /**
   * Parses a signed 64-bit integer from the leading text after optional whitespace and sign.
   * Parsing stops at NUL or a non-digit. The value can contain a parsed prefix even when
   * the success flag is false.
   * Success requires complete consumption before NUL and no overflow.
   *
   * @param base a radix from 2 to 36, or zero to recognize decimal, octal, hex and binary prefixes
   * @return the value and parsing success flag
   */
  public Variant.Extraction<Long> extractLong(int base) {
    return integer(base, false);
  }

  /**
   * Parses an unsigned 64-bit integer from the leading text after optional whitespace and sign. The base is decimal.
   * Parsing stops at NUL or a non-digit. The value can contain a parsed prefix even when
   * the complete text is not a valid number; use an extraction method to check success.
   * The returned Java {@code long} retains all unsigned bits; a leading minus sign negates
   * the parsed unsigned magnitude modulo 2 to the power of 64.
   *
   * @return the parsed value, without a success indication
   */
  public long toUnsignedLong() {
    return toUnsignedLong(10);
  }

  /**
   * Parses an unsigned 64-bit integer from the leading text after optional whitespace and sign.
   * Parsing stops at NUL or a non-digit. The value can contain a parsed prefix even when
   * the complete text is not a valid number; use an extraction method to check success.
   * The returned Java {@code long} retains all unsigned bits; a leading minus sign negates
   * the parsed unsigned magnitude modulo 2 to the power of 64.
   *
   * @param base a radix from 2 to 36, or zero to recognize decimal, octal, hex and binary prefixes
   * @return the parsed value, without a success indication
   */
  public long toUnsignedLong(int base) {
    return integer(base, true).value();
  }

  /**
   * Parses an unsigned 64-bit integer from the leading text after optional whitespace and sign. The base is decimal.
   * Parsing stops at NUL or a non-digit. The value can contain a parsed prefix even when
   * the success flag is false.
   * Success requires complete consumption before NUL and no overflow.
   * The returned Java {@code long} retains all unsigned bits; a leading minus sign negates
   * the parsed unsigned magnitude modulo 2 to the power of 64.
   *
   * @return the value and parsing success flag
   */
  public Variant.Extraction<Long> extractUnsignedLong() {
    return extractUnsignedLong(10);
  }

  /**
   * Parses an unsigned 64-bit integer from the leading text after optional whitespace and sign.
   * Parsing stops at NUL or a non-digit. The value can contain a parsed prefix even when
   * the success flag is false.
   * Success requires complete consumption before NUL and no overflow.
   * The returned Java {@code long} retains all unsigned bits; a leading minus sign negates
   * the parsed unsigned magnitude modulo 2 to the power of 64.
   *
   * @param base a radix from 2 to 36, or zero to recognize decimal, octal, hex and binary prefixes
   * @return the value and parsing success flag
   */
  public Variant.Extraction<Long> extractUnsignedLong(int base) {
    return integer(base, true);
  }

  /**
   * Formats an integer as decimal text.
   *
   * @param value the value to format
   * @return decimal text
   */
  public static TagString number(int value) {
    return new TagString(Integer.toString(value));
  }

  /**
   * Formats an integer as decimal text.
   *
   * @param value the value to format
   * @return decimal text
   */
  public static TagString fromLong(long value) {
    return new TagString(Long.toString(value));
  }

  /**
   * Formats an unsigned 64-bit bit pattern as decimal text.
   *
   * @param value the value to format
   * @return decimal text
   */
  public static TagString fromUnsignedLong(long value) {
    return new TagString(Long.toUnsignedString(value));
  }

  @Override
  public String toString() {
    return value;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof TagString s && value.equals(s.value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  @Override
  public int compareTo(TagString other) {
    return value.compareTo(other.value);
  }

  /**
   * Iterates UTF-16 code units from a snapshot of the text. Later text edits do not change
   * that snapshot. The iterator does not support removal.
   *
   * @return a snapshot iterator
   */
  @Override
  public Iterator<Character> iterator() {
    String snapshot = value;
    return new Iterator<>() {
      private int index;

      public boolean hasNext() {
        return index < snapshot.length();
      }

      public Character next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        return snapshot.charAt(index++);
      }
    };
  }
}
