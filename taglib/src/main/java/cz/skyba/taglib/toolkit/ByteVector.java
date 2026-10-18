// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.toolkit;

import cz.skyba.taglib.internal.TextBytes;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static java.lang.System.Logger.Level.DEBUG;

/**
 * A mutable byte sequence with copying constructors, binary searches and numeric conversions.
 * Constructor inputs and extracted ranges are copied. {@link #data()} exposes live storage;
 * {@link #toByteArray()} copies it. Byte indexes are zero-based. Numeric conversion defaults
 * to big-endian order, and incomplete integer fields are read from the bytes available.
 */
public final class ByteVector implements Comparable<ByteVector>, Iterable<Byte> {
  private static final System.Logger LOG = System.getLogger(ByteVector.class.getName());

  private byte[] data;

  /**
   * Creates an empty byte vector.
   */
  public ByteVector() {
    data = new byte[0];
  }

  /**
   * Creates a vector filled with zero bytes.
   *
   * @param size the nonnegative byte count
   * @throws NegativeArraySizeException if the size is negative
   */
  public ByteVector(int size) {
    this(size, (byte) 0);
  }

  /**
   * Creates a vector filled with the supplied byte.
   *
   * @param size  the nonnegative byte count
   * @param value the fill byte
   * @throws NegativeArraySizeException if the size is negative
   */
  public ByteVector(int size, byte value) {
    data = new byte[size];
    if (value != 0) Arrays.fill(data, value);
  }

  /**
   * Creates a one-byte vector.
   *
   * @param value the byte to store
   */
  public ByteVector(byte value) {
    data = new byte[]{value};
  }

  /**
   * Creates a vector by copying all supplied bytes.
   *
   * @param value source bytes
   */
  public ByteVector(byte[] value) {
    this(value, false);
  }

  private ByteVector(byte[] value, boolean owned) {
    data = owned ? value : value.clone();
  }

  static ByteVector takeOwnership(byte[] value) {
    return new ByteVector(value, true);
  }

  /**
   * Encodes metadata text into independent storage, including embedded NULs.
   * Supports the standard Latin-1, UTF-8 and UTF-16 charsets. Latin-1 keeps each
   * code unit's low byte; UTF-8 rejects malformed UTF-16 by returning empty bytes.
   * UTF-16 preserves raw code units, with a little-endian BOM for {@code UTF_16}.
   *
   * @param text    the text to encode
   * @param charset the metadata charset
   * @return encoded bytes
   * @throws IllegalArgumentException if the charset is unsupported
   */
  public static ByteVector fromText(String text, Charset charset) {
    return takeOwnership(TextBytes.encode(text, charset));
  }

  /**
   * Decodes a metadata field and ends the result at its first decoded NUL.
   * The entire byte sequence is decoded before truncation. Invalid UTF-8 or a
   * missing required UTF-16 BOM produces empty text. Explicit-endian UTF-16
   * preserves raw code units and ignores an odd trailing byte.
   *
   * @param charset a standard Latin-1, UTF-8 or UTF-16 charset
   * @return decoded field text
   * @throws IllegalArgumentException if the charset is unsupported
   */
  public String decodeTerminatedText(Charset charset) {
    return TextBytes.decodeTerminated(data, charset);
  }

  /**
   * Creates a vector by copying an exact prefix of the supplied array.
   *
   * @param value  source bytes
   * @param length the prefix byte count
   * @throws IndexOutOfBoundsException if the prefix is outside the array
   */
  public ByteVector(byte[] value, int length) {
    Objects.checkFromIndexSize(0, length, value.length);
    data = Arrays.copyOf(value, length);
  }

  /**
   * Creates an independent copy of a byte vector.
   *
   * @param value the vector to copy
   */
  public ByteVector(ByteVector value) {
    this(value.data);
  }

  /**
   * Creates an independent copy of an exact byte span.
   *
   * @param value  the source vector
   * @param offset the starting byte index
   * @param length the byte count
   * @throws IndexOutOfBoundsException if the span is outside the source
   */
  public ByteVector(ByteVector value, int offset, int length) {
    Objects.checkFromIndexSize(offset, length, value.size());
    data = Arrays.copyOfRange(value.data, offset, offset + length);
  }

  /**
   * Encodes a string as ISO-8859-1 and copies an exact byte prefix, including embedded NUL bytes.
   *
   * @param value  source text
   * @param length the encoded byte count
   * @throws IndexOutOfBoundsException if the prefix exceeds the encoded bytes
   */
  public ByteVector(String value, int length) {
    this(value.getBytes(StandardCharsets.ISO_8859_1), length);
  }

  /**
   * Encodes text as ISO-8859-1 up to its first NUL character.
   *
   * @param value source text
   */
  public ByteVector(String value) {
    int end = value.indexOf('\0');
    data = (end < 0 ? value : value.substring(0, end)).getBytes(StandardCharsets.ISO_8859_1);
  }

  public int size() {
    return data.length;
  }

  public boolean isEmpty() {
    return data.length == 0;
  }

  /**
   * Returns the live backing array. Array edits change this vector. Operations that replace
   * or resize storage can detach previously returned arrays.
   *
   * @return live byte storage
   */
  public byte[] data() {
    return data;
  }

  /**
   * Returns an independent copy of all stored bytes.
   *
   * @return copied bytes
   */
  public byte[] toByteArray() {
    return data.clone();
  }

  /**
   * Returns a byte, using zero when the index is outside this vector.
   *
   * @param index the byte index
   * @return the stored byte, or zero for an invalid index
   */
  public byte at(int index) {
    return index >= 0 && index < data.length ? data[index] : 0;
  }

  /**
   * Returns a byte at a valid index.
   *
   * @param index the byte index
   * @return the stored byte
   * @throws IndexOutOfBoundsException if the index is invalid
   * @see #at(int)
   */
  public byte get(int index) {
    return data[index];
  }

  public void set(int index, byte value) {
    data[index] = value;
  }

  /**
   * Copies bytes into an existing range without resizing this vector.
   *
   * @param index the starting byte index
   * @param value the bytes to copy
   * @throws IndexOutOfBoundsException if the complete value does not fit
   */
  public void set(int index, byte[] value) {
    System.arraycopy(value, 0, data, index, value.length);
  }

  /**
   * Copies a source range into an existing range without resizing this vector.
   * Overlapping source and destination ranges are supported.
   *
   * @param index the starting destination byte index
   * @param value the source bytes
   * @param offset the starting source byte index
   * @param length the byte count
   * @throws IndexOutOfBoundsException if either complete range is outside its storage
   */
  public void set(int index, byte[] value, int offset, int length) {
    System.arraycopy(value, offset, data, index, length);
  }

  /**
   * Replaces this vector with a copy of the supplied bytes.
   *
   * @param value the source bytes
   * @return this vector
   */
  public ByteVector setData(byte[] value) {
    data = value.clone();
    return this;
  }

  /**
   * Replaces this vector with a copy of the supplied bytes. The explicit prefix retains embedded NUL bytes.
   *
   * @param value  the source bytes
   * @param length the exact encoded byte count
   * @return this vector
   * @throws IndexOutOfBoundsException if the prefix exceeds the source
   */
  public ByteVector setData(byte[] value, int length) {
    data = new ByteVector(value, length).data;
    return this;
  }

  /**
   * Replaces this vector with a copy of ISO-8859-1 text. The explicit prefix retains embedded NUL bytes.
   *
   * @param value  the source text
   * @param length the exact encoded byte count
   * @return this vector
   * @throws IndexOutOfBoundsException if the prefix exceeds the source
   */
  public ByteVector setData(String value, int length) {
    data = new ByteVector(value, length).data;
    return this;
  }

  /**
   * Replaces this vector with a copy of ISO-8859-1 text. Encoding ends at the first NUL character.
   *
   * @param value the source text
   * @return this vector
   */
  public ByteVector setData(String value) {
    data = new ByteVector(value).data;
    return this;
  }

  /**
   * Copies a byte range, clamping its end to the available data.
   * An invalid starting index or negative length returns an empty vector. The range extends to the end.
   *
   * @param index the starting byte index
   * @return an independent byte vector
   */
  public ByteVector mid(int index) {
    return mid(index, Integer.MAX_VALUE);
  }

  /**
   * Copies a byte range, clamping its end to the available data.
   * An invalid starting index or negative length returns an empty vector.
   *
   * @param index  the starting byte index
   * @param length the maximum byte count
   * @return an independent byte vector
   */
  public ByteVector mid(int index, int length) {
    if (index < 0 || length < 0) {
      return new ByteVector();
    }
    int start = Math.min(index, size());
    return takeOwnership(
      Arrays.copyOfRange(data, start, start + Math.min(length, size() - start)));
  }

  /**
   * Searches forward for a nonempty byte sequence. The search starts at byte zero.
   *
   * @param pattern the byte sequence to find
   * @return the match index, or {@code -1} for no match
   */
  public int find(ByteVector pattern) {
    return find(pattern, 0, 1);
  }

  /**
   * Searches forward for a nonempty byte sequence.
   *
   * @param pattern the byte sequence to find
   * @param offset  the starting byte index
   * @return the match index, or {@code -1} for no match
   */
  public int find(ByteVector pattern, int offset) {
    return find(pattern, offset, 1);
  }

  /**
   * Searches forward for a nonempty byte sequence.
   * Candidates are spaced by the alignment from the starting position.
   *
   * @param pattern   the byte sequence to find
   * @param offset    the starting byte index
   * @param byteAlign the positive spacing between candidate matches
   * @return the match index, or {@code -1} for no match or invalid alignment
   */
  public int find(ByteVector pattern, int offset, int byteAlign) {
    if (offset < 0 || byteAlign <= 0 || pattern.isEmpty()) {
      return -1;
    }
    for (long i = offset; i <= (long) size() - pattern.size(); i += byteAlign) {
      if (data[(int) i] == pattern.data[0] && containsAt(pattern, (int) i)) {
        return (int) i;
      }
    }
    return -1;
  }

  /**
   * Searches forward for a byte value. The search starts at byte zero.
   *
   * @param value the byte to find
   * @return the match index, or {@code -1} for no match
   */
  public int find(byte value) {
    return find(value, 0, 1);
  }

  /**
   * Searches forward for a byte value.
   *
   * @param value  the byte to find
   * @param offset the starting byte index
   * @return the match index, or {@code -1} for no match
   */
  public int find(byte value, int offset) {
    return find(value, offset, 1);
  }

  /**
   * Searches forward for a byte value.
   * Candidates are spaced by the alignment from the starting position.
   *
   * @param value     the byte to find
   * @param offset    the starting byte index
   * @param byteAlign the positive spacing between candidate matches
   * @return the match index, or {@code -1} for no match or invalid alignment
   */
  public int find(byte value, int offset, int byteAlign) {
    if (offset < 0 || byteAlign <= 0) return -1;
    for (long i = offset; i < data.length; i += byteAlign) {
      if (data[(int) i] == value) return (int) i;
    }
    return -1;
  }

  /**
   * Searches backward for a nonempty byte sequence.
   * Offset zero selects the last possible match position. A positive offset selects the
   * starting match position; out-of-range offsets fall back to the end.
   *
   * @param pattern the byte sequence to find
   * @return the match index, or {@code -1} for no match
   */
  public int rfind(ByteVector pattern) {
    return rfind(pattern, 0, 1);
  }

  /**
   * Searches backward for a nonempty byte sequence.
   * Offset zero selects the last possible match position. A positive offset selects the
   * starting match position; out-of-range offsets fall back to the end.
   *
   * @param pattern the byte sequence to find
   * @param offset  the starting byte index, or zero to search from the end
   * @return the match index, or {@code -1} for no match
   */
  public int rfind(ByteVector pattern, int offset) {
    return rfind(pattern, offset, 1);
  }

  /**
   * Searches backward for a byte value.
   * Offset zero selects the last possible match position. A positive offset selects the
   * starting match position; out-of-range offsets fall back to the end.
   *
   * @param value the byte to find
   * @return the match index, or {@code -1} for no match
   */
  public int rfind(byte value) {
    return rfind(value, 0, 1);
  }

  /**
   * Searches backward for a byte value.
   * Offset zero selects the last possible match position. A positive offset selects the
   * starting match position; out-of-range offsets fall back to the end.
   *
   * @param value  the byte to find
   * @param offset the starting byte index, or zero to search from the end
   * @return the match index, or {@code -1} for no match
   */
  public int rfind(byte value, int offset) {
    return rfind(value, offset, 1);
  }

  /**
   * Searches backward for a byte value.
   * Offset zero selects the last possible match position. A positive offset selects the
   * starting match position; out-of-range offsets fall back to the end.
   * Candidates are spaced by the alignment from the starting position.
   *
   * @param value  the byte to find
   * @param offset the starting byte index, or zero to search from the end
   * @param align  the positive spacing between candidate matches
   * @return the match index, or {@code -1} for no match or invalid alignment
   */
  public int rfind(byte value, int offset, int align) {
    if (align <= 0 || data.length == 0) return -1;
    long reversedOffset = offset > 0 ? (long) data.length - offset - 1 : 0;
    if (reversedOffset < 0 || reversedOffset >= data.length) reversedOffset = 0;
    for (long i = data.length - 1L - reversedOffset; i >= 0; i -= align) {
      if (data[(int) i] == value) return (int) i;
    }
    return -1;
  }

  /**
   * Searches backward for a nonempty byte sequence.
   * Offset zero selects the last possible match position. A positive offset selects the
   * starting match position; out-of-range offsets fall back to the end.
   * Candidates are spaced by the alignment from the starting position.
   *
   * @param pattern   the byte sequence to find
   * @param offset    the starting byte index, or zero to search from the end
   * @param byteAlign the positive spacing between candidate matches
   * @return the match index, or {@code -1} for no match or invalid alignment
   */
  public int rfind(ByteVector pattern, int offset, int byteAlign) {
    if (pattern.isEmpty() || byteAlign <= 0 || pattern.size() > size()) {
      return -1;
    }
    // Native offset zero is a sentinel for searching from the end.
    long reversedOffset = offset > 0 ? (long) size() - offset - pattern.size() : 0;
    if (reversedOffset < 0 || reversedOffset >= size()) {
      reversedOffset = 0;
    }
    for (long i = (long) size() - pattern.size() - reversedOffset; i >= 0; i -= byteAlign) {
      if (data[(int) i] == pattern.data[0] && containsAt(pattern, (int) i)) {
        return (int) i;
      }
    }
    return -1;
  }

  /**
   * Tests whether a range of the pattern matches bytes at the selected vector offset.
   * Invalid spans and an empty pattern return {@code false}.
   *
   * @param pattern the comparison bytes
   * @param offset  the starting index in this vector
   * @return whether the selected bytes match
   */
  public boolean containsAt(ByteVector pattern, int offset) {
    return containsAt(pattern, offset, 0, pattern.size());
  }

  /**
   * Tests whether a range of the pattern matches bytes at the selected vector offset.
   * Invalid spans and an empty pattern return {@code false}.
   *
   * @param pattern       the comparison bytes
   * @param offset        the starting index in this vector
   * @param patternOffset the first byte index to compare in the pattern
   * @return whether the selected bytes match
   */
  public boolean containsAt(ByteVector pattern, int offset, int patternOffset) {
    return containsAt(pattern, offset, patternOffset, pattern.size());
  }

  /**
   * Tests whether a range of the pattern matches bytes at the selected vector offset.
   * Invalid spans and an empty pattern return {@code false}.
   *
   * @param pattern       the comparison bytes
   * @param offset        the starting index in this vector
   * @param patternOffset the first byte index to compare in the pattern
   * @param patternLength the exclusive end index in the pattern, clamped to its size
   * @return whether the selected bytes match
   */
  public boolean containsAt(ByteVector pattern, int offset, int patternOffset, int patternLength) {
    int end = Math.min(patternLength, pattern.size());
    int count = end - patternOffset;
    if (offset < 0
      || patternOffset < 0
      || patternOffset >= pattern.size()
      || end == 0
      || count < 0
      || offset > size() - count) {
      return false;
    }
    return Arrays.equals(data, offset, offset + count, pattern.data, patternOffset, end);
  }

  /**
   * Tests whether the complete, nonempty pattern occurs at byte offset zero.
   *
   * @param pattern the prefix to test
   * @return whether the prefix matches
   */
  public boolean startsWith(ByteVector pattern) {
    return containsAt(pattern, 0);
  }

  /**
   * Tests whether the complete, nonempty pattern occurs at the end of this vector.
   *
   * @param pattern the suffix to test
   * @return whether the suffix matches
   */
  public boolean endsWith(ByteVector pattern) {
    return containsAt(pattern, size() - pattern.size());
  }

  /**
   * Finds a suffix of this vector that matches a nonempty proper prefix of the pattern.
   * The pattern must not be longer than this vector.
   *
   * @param pattern the complete pattern
   * @return the suffix's starting byte index, or {@code -1} if no partial match is found
   */
  public int endsWithPartialMatch(ByteVector pattern) {
    if (pattern.size() > size()) {
      return -1;
    }
    for (int i = 1; i < pattern.size(); i++) {
      int start = size() - pattern.size() + i;
      if (containsAt(pattern, start, 0, pattern.size() - i)) {
        return start;
      }
    }
    return -1;
  }

  /**
   * Splits bytes at nonoverlapping delimiter matches into independent vectors.
   * A trailing empty field is omitted; other empty fields are retained.
   *
   * @param pattern the delimiter bytes
   * @return an independent mutable list of copied byte fields
   */
  public List<ByteVector> split(ByteVector pattern) {
    return split(pattern, 1, 0);
  }

  /**
   * Splits bytes at delimiter matches spaced by the alignment.
   * A trailing empty field is omitted; other empty fields are retained.
   *
   * @param pattern   the delimiter bytes
   * @param byteAlign the spacing between delimiter candidates
   * @return an independent mutable list of copied byte fields
   */
  public List<ByteVector> split(ByteVector pattern, int byteAlign) {
    return split(pattern, byteAlign, 0);
  }

  /**
   * Splits bytes at delimiter matches spaced by the alignment.
   * A trailing empty field is omitted; other empty fields are retained. The final field
   * contains the unsplit remainder when the field limit is reached.
   *
   * @param pattern   the delimiter bytes
   * @param byteAlign the spacing between delimiter candidates
   * @param max       the maximum field count, or zero for no limit
   * @return an independent mutable list of copied byte fields
   */
  public List<ByteVector> split(ByteVector pattern, int byteAlign, int max) {
    var result = new ArrayList<ByteVector>();
    int previous = 0;
    for (int offset = find(pattern, 0, byteAlign);
         offset >= 0 && (max == 0 || max > result.size() + 1);
         offset = find(pattern, offset + pattern.size(), byteAlign)) {
      result.add(mid(previous, offset - previous));
      previous = offset + pattern.size();
    }
    if (previous < size()) {
      result.add(mid(previous));
    }
    return result;
  }

  /**
   * Joins byte vectors into independent byte storage, separated by one ASCII space.
   *
   * @param values the byte vectors to join
   * @return the joined bytes
   * @throws ArithmeticException if the complete allocation size overflows
   */
  public static ByteVector join(List<ByteVector> values) {
    return join(values, new ByteVector(" "));
  }

  /**
   * Joins byte vectors into independent byte storage.
   *
   * @param values    the byte vectors to join
   * @param separator the bytes inserted between elements
   * @return the joined bytes
   * @throws ArithmeticException if the complete allocation size overflows
   */
  public static ByteVector join(List<ByteVector> values, ByteVector separator) {
    int length = 0;
    for (ByteVector value : values) {
      length = Math.addExact(length, value.size());
    }
    if (values.size() > 1) {
      length = Math.addExact(length, Math.multiplyExact(values.size() - 1, separator.size()));
    }
    byte[] result = new byte[length];
    int position = 0;
    boolean first = true;
    for (ByteVector value : values) {
      if (!first) {
        System.arraycopy(separator.data(), 0, result, position, separator.size());
        position += separator.size();
      }
      System.arraycopy(value.data(), 0, result, position, value.size());
      position += value.size();
      first = false;
    }
    return takeOwnership(result);
  }

  /**
   * Replaces every matching byte in place.
   *
   * @param oldByte the byte to replace
   * @param newByte its replacement
   * @return this vector
   */
  public ByteVector replace(byte oldByte, byte newByte) {
    for (int i = 0; i < size(); i++) {
      if (data[i] == oldByte) {
        data[i] = newByte;
      }
    }
    return this;
  }

  /**
   * Replaces nonoverlapping pattern matches in place; an empty pattern has no effect.
   *
   * @param pattern     the byte sequence to replace
   * @param replacement replacement bytes
   * @return this vector
   */
  public ByteVector replace(ByteVector pattern, ByteVector replacement) {
    int next = find(pattern);
    if (next < 0) {
      return this;
    }
    var out = new ByteArrayOutputStream();
    int start = 0;
    do {
      out.write(data, start, next - start);
      out.writeBytes(replacement.data);
      start = next + pattern.size();
      next = find(pattern, start);
    } while (next >= 0);
    out.write(data, start, size() - start);
    data = out.toByteArray();
    return this;
  }

  /**
   * Appends a copy of the supplied bytes.
   *
   * @param value the data to append
   * @return this vector
   * @throws ArithmeticException if the resulting byte count overflows
   */
  public ByteVector append(ByteVector value) {
    int old = size();
    byte[] source = value.data;
    resize(Math.addExact(old, source.length));
    System.arraycopy(source, 0, data, old, source.length);
    return this;
  }

  /**
   * Appends one byte.
   *
   * @param value the data to append
   * @return this vector
   * @throws ArithmeticException if the resulting byte count overflows
   */
  public ByteVector append(byte value) {
    return resize(Math.addExact(size(), 1), value);
  }

  /**
   * Removes all bytes from this vector.
   *
   * @return this vector
   */
  public ByteVector clear() {
    data = new byte[0];
    return this;
  }

  /**
   * Resizes this vector, discarding trailing bytes when shrinking and padding when extending. Extension uses zero bytes.
   *
   * @param size the new nonnegative byte count
   * @return this vector
   * @throws NegativeArraySizeException if the size is negative
   */
  public ByteVector resize(int size) {
    return resize(size, (byte) 0);
  }

  /**
   * Resizes this vector, discarding trailing bytes when shrinking and padding when extending.
   *
   * @param size    the new nonnegative byte count
   * @param padding the fill byte for extension
   * @return this vector
   * @throws NegativeArraySizeException if the size is negative
   */
  public ByteVector resize(int size, byte padding) {
    int old = data.length;
    data = Arrays.copyOf(data, size);
    if (size > old && padding != 0) {
      Arrays.fill(data, old, size, padding);
    }
    return this;
  }

  private long number(int offset, int length, boolean bigEndian) {
    if (offset < 0 || offset >= size()) {
      LOG.log(DEBUG, "No data available for numeric conversion; returning zero.");
      return 0;
    }
    int count = Math.min(length, size() - offset);
    long result = 0;
    for (int i = 0; i < count; i++) {
      result |= (data[offset + i] & 255L) << ((bigEndian ? count - 1 - i : i) * 8);
    }
    return result;
  }

  /**
   * Reads an unsigned 32-bit integer from available bytes.
   * Missing trailing bytes are not read; an invalid offset returns zero. The byte order is big-endian. The read starts at byte zero.
   *
   * @return the decoded value, or zero if no bytes are available
   */
  public long toUnsignedInt() {
    return toUnsignedInt(true);
  }

  /**
   * Reads an unsigned 32-bit integer from available bytes.
   * Missing trailing bytes are not read; an invalid offset returns zero. The read starts at byte zero.
   *
   * @param bigEndian {@code true} for most-significant byte first
   * @return the decoded value, or zero if no bytes are available
   */
  public long toUnsignedInt(boolean bigEndian) {
    return toUnsignedInt(0, 4, bigEndian);
  }

  /**
   * Reads an unsigned 32-bit integer from available bytes.
   * Missing trailing bytes are not read; an invalid offset returns zero. The byte order is big-endian.
   *
   * @param offset the starting byte index
   * @return the decoded value, or zero if no bytes are available
   */
  public long toUnsignedInt(int offset) {
    return toUnsignedInt(offset, true);
  }

  /**
   * Reads an unsigned 32-bit integer from available bytes.
   * Missing trailing bytes are not read; an invalid offset returns zero.
   *
   * @param offset    the starting byte index
   * @param bigEndian {@code true} for most-significant byte first
   * @return the decoded value, or zero if no bytes are available
   */
  public long toUnsignedInt(int offset, boolean bigEndian) {
    return toUnsignedInt(offset, 4, bigEndian);
  }

  /**
   * Reads an unsigned 32-bit integer from available bytes.
   * Missing trailing bytes are not read; an invalid offset returns zero. The byte order is big-endian.
   * Lengths above four use Java 32-bit shift rules; use lengths from one to four for ordinary 32-bit fields.
   *
   * @param offset the starting byte index
   * @param length the requested byte count, clamped to the available bytes
   * @return the decoded value, or zero if no bytes are available
   */
  public long toUnsignedInt(int offset, int length) {
    return toUnsignedInt(offset, length, true);
  }

  /**
   * Reads an unsigned 32-bit integer from available bytes.
   * Missing trailing bytes are not read; an invalid offset returns zero.
   * Lengths above four use Java 32-bit shift rules; use lengths from one to four for ordinary 32-bit fields.
   *
   * @param offset    the starting byte index
   * @param length    the requested byte count, clamped to the available bytes
   * @param bigEndian {@code true} for most-significant byte first
   * @return the decoded value, or zero if no bytes are available
   */
  public long toUnsignedInt(int offset, int length, boolean bigEndian) {
    if (offset < 0 || offset >= size()) {
      LOG.log(DEBUG, "No data available for numeric conversion; returning zero.");
      return 0;
    }
    int count = Math.clamp(length, 0, size() - offset), result = 0;
    // Native toNumber<uint> performs uint-width shifts rather than clamping length.
    // Counts above four invoke C++ undefined shifts; these reproduce the pinned
    // x86-64 oracle, not a portable guarantee for those out-of-width requests.
    for (int i = 0; i < count; i++) {
      result |= (data[offset + i] & 255) << ((bigEndian ? count - 1 - i : i) * 8);
    }
    return Integer.toUnsignedLong(result);
  }

  /**
   * Reads a signed 16-bit integer from available bytes.
   * Missing trailing bytes are not read; an invalid offset returns zero. The byte order is big-endian. The read starts at byte zero.
   *
   * @return the decoded value, or zero if no bytes are available
   */
  public short toShort() {
    return toShort(true);
  }

  /**
   * Reads a signed 16-bit integer from available bytes.
   * Missing trailing bytes are not read; an invalid offset returns zero. The byte order is big-endian.
   *
   * @param offset the starting byte index
   * @return the decoded value, or zero if no bytes are available
   */
  public short toShort(int offset) {
    return toShort(offset, true);
  }

  /**
   * Reads a signed 16-bit integer from available bytes.
   * Missing trailing bytes are not read; an invalid offset returns zero. The read starts at byte zero.
   *
   * @param bigEndian {@code true} for most-significant byte first
   * @return the decoded value, or zero if no bytes are available
   */
  public short toShort(boolean bigEndian) {
    return toShort(0, bigEndian);
  }

  /**
   * Reads a signed 16-bit integer from available bytes.
   * Missing trailing bytes are not read; an invalid offset returns zero.
   *
   * @param offset    the starting byte index
   * @param bigEndian {@code true} for most-significant byte first
   * @return the decoded value, or zero if no bytes are available
   */
  public short toShort(int offset, boolean bigEndian) {
    return (short) number(offset, 2, bigEndian);
  }

  /**
   * Reads an unsigned 16-bit integer from available bytes.
   * Missing trailing bytes are not read; an invalid offset returns zero. The byte order is big-endian. The read starts at byte zero.
   *
   * @return the decoded value, or zero if no bytes are available
   */
  public int toUnsignedShort() {
    return toUnsignedShort(true);
  }

  /**
   * Reads an unsigned 16-bit integer from available bytes.
   * Missing trailing bytes are not read; an invalid offset returns zero. The byte order is big-endian.
   *
   * @param offset the starting byte index
   * @return the decoded value, or zero if no bytes are available
   */
  public int toUnsignedShort(int offset) {
    return toUnsignedShort(offset, true);
  }

  /**
   * Reads an unsigned 16-bit integer from available bytes.
   * Missing trailing bytes are not read; an invalid offset returns zero. The read starts at byte zero.
   *
   * @param bigEndian {@code true} for most-significant byte first
   * @return the decoded value, or zero if no bytes are available
   */
  public int toUnsignedShort(boolean bigEndian) {
    return toUnsignedShort(0, bigEndian);
  }

  /**
   * Reads an unsigned 16-bit integer from available bytes.
   * Missing trailing bytes are not read; an invalid offset returns zero.
   *
   * @param offset    the starting byte index
   * @param bigEndian {@code true} for most-significant byte first
   * @return the decoded value, or zero if no bytes are available
   */
  public int toUnsignedShort(int offset, boolean bigEndian) {
    return (int) number(offset, 2, bigEndian);
  }

  /**
   * Reads a 64-bit integer bit pattern from available bytes.
   * Missing trailing bytes are not read; an invalid offset returns zero. The byte order is big-endian. The read starts at byte zero.
   *
   * @return the decoded value, or zero if no bytes are available
   */
  public long toLong() {
    return toLong(true);
  }

  /**
   * Reads a 64-bit integer bit pattern from available bytes.
   * Missing trailing bytes are not read; an invalid offset returns zero. The byte order is big-endian.
   *
   * @param offset the starting byte index
   * @return the decoded value, or zero if no bytes are available
   */
  public long toLong(int offset) {
    return toLong(offset, true);
  }

  /**
   * Reads a 64-bit integer bit pattern from available bytes.
   * Missing trailing bytes are not read; an invalid offset returns zero. The read starts at byte zero.
   *
   * @param bigEndian {@code true} for most-significant byte first
   * @return the decoded value, or zero if no bytes are available
   */
  public long toLong(boolean bigEndian) {
    return toLong(0, bigEndian);
  }

  /**
   * Reads a 64-bit integer bit pattern from available bytes.
   * Missing trailing bytes are not read; an invalid offset returns zero.
   *
   * @param offset    the starting byte index
   * @param bigEndian {@code true} for most-significant byte first
   * @return the decoded value, or zero if no bytes are available
   */
  public long toLong(int offset, boolean bigEndian) {
    return number(offset, 8, bigEndian);
  }

  /**
   * Reads a 64-bit integer bit pattern from available bytes.
   * Missing trailing bytes are not read; an invalid offset returns zero. Unsigned values retain all bits in a Java {@code long}. The byte order is big-endian. The read starts at byte zero.
   *
   * @return the decoded value, or zero if no bytes are available
   */
  public long toUnsignedLong() {
    return toUnsignedLong(true);
  }

  /**
   * Reads a 64-bit integer bit pattern from available bytes.
   * Missing trailing bytes are not read; an invalid offset returns zero. Unsigned values retain all bits in a Java {@code long}. The byte order is big-endian.
   *
   * @param offset the starting byte index
   * @return the decoded value, or zero if no bytes are available
   */
  public long toUnsignedLong(int offset) {
    return toUnsignedLong(offset, true);
  }

  /**
   * Reads a 64-bit integer bit pattern from available bytes.
   * Missing trailing bytes are not read; an invalid offset returns zero. Unsigned values retain all bits in a Java {@code long}. The read starts at byte zero.
   *
   * @param bigEndian {@code true} for most-significant byte first
   * @return the decoded value, or zero if no bytes are available
   */
  public long toUnsignedLong(boolean bigEndian) {
    return toLong(bigEndian);
  }

  /**
   * Reads a 64-bit integer bit pattern from available bytes.
   * Missing trailing bytes are not read; an invalid offset returns zero. Unsigned values retain all bits in a Java {@code long}.
   *
   * @param offset    the starting byte index
   * @param bigEndian {@code true} for most-significant byte first
   * @return the decoded value, or zero if no bytes are available
   */
  public long toUnsignedLong(int offset, boolean bigEndian) {
    return toLong(offset, bigEndian);
  }

  private boolean available(int offset, int width) {
    return offset >= 0 && offset <= size() - width;
  }

  /**
   * Decodes 32-bit IEEE-754 data in little-endian order.
   * An incomplete byte span returns zero.
   *
   * @param offset the starting byte index
   * @return the decoded value, or zero for an incomplete span
   */
  public float toFloat32LE(int offset) {
    return available(offset, 4) ? Float.intBitsToFloat((int) number(offset, 4, false)) : 0;
  }

  /**
   * Decodes 32-bit IEEE-754 data in big-endian order.
   * An incomplete byte span returns zero.
   *
   * @param offset the starting byte index
   * @return the decoded value, or zero for an incomplete span
   */
  public float toFloat32BE(int offset) {
    return available(offset, 4) ? Float.intBitsToFloat((int) number(offset, 4, true)) : 0;
  }

  /**
   * Decodes 64-bit IEEE-754 data in little-endian order.
   * An incomplete byte span returns zero.
   *
   * @param offset the starting byte index
   * @return the decoded value, or zero for an incomplete span
   */
  public double toFloat64LE(int offset) {
    return available(offset, 8) ? Double.longBitsToDouble(number(offset, 8, false)) : 0;
  }

  /**
   * Decodes 64-bit IEEE-754 data in big-endian order.
   * An incomplete byte span returns zero.
   *
   * @param offset the starting byte index
   * @return the decoded value, or zero for an incomplete span
   */
  public double toFloat64BE(int offset) {
    return available(offset, 8) ? Double.longBitsToDouble(number(offset, 8, true)) : 0;
  }

  /**
   * Decodes 80-bit finite extended-precision data in little-endian order.
   * An incomplete byte span returns zero. Infinity and NaN encodings also return positive zero.
   *
   * @param offset the starting byte index
   * @return the decoded value, or zero for an incomplete span
   */
  public Float80 toFloat80LE(int offset) {
    return toFloat80(offset, false);
  }

  /**
   * Decodes 80-bit finite extended-precision data in big-endian order.
   * An incomplete byte span returns zero. Infinity and NaN encodings also return positive zero.
   *
   * @param offset the starting byte index
   * @return the decoded value, or zero for an incomplete span
   */
  public Float80 toFloat80BE(int offset) {
    return toFloat80(offset, true);
  }

  private Float80 toFloat80(int offset, boolean bigEndian) {
    if (!available(offset, 10)) {
      LOG.log(DEBUG, "Not enough data for an 80-bit float at the requested offset; returning zero.");
      return Float80.ZERO;
    }
    int header = toUnsignedShort(offset + (bigEndian ? 0 : 8), bigEndian);
    int exponent = header & 0x7fff;
    if (exponent == 0x7fff) {
      LOG.log(DEBUG, "Infinity and NaN are unsupported for 80-bit floats; returning zero.");
      return Float80.ZERO;
    }
    return Float80.fromNativeFields((header & 0x8000) != 0, exponent,
      number(offset + (bigEndian ? 2 : 0), 8, bigEndian));
  }

  private static ByteVector fromNumber(long value, int width, boolean bigEndian) {
    var result = new ByteVector(width);
    for (int i = 0; i < width; i++) {
      result.data[i] = (byte) (value >>> ((bigEndian ? width - 1 - i : i) * 8));
    }
    return result;
  }

  /**
   * Encodes the low 32 bits of an integer into a new 4-byte vector.
   * The byte order is big-endian.
   *
   * @param value the value to encode
   * @return encoded bytes
   */
  public static ByteVector fromUnsignedInt(long value) {
    return fromUnsignedInt(value, true);
  }

  /**
   * Encodes the low 32 bits of an integer into a new 4-byte vector.
   *
   * @param value     the value to encode
   * @param bigEndian {@code true} for most-significant byte first
   * @return encoded bytes
   */
  public static ByteVector fromUnsignedInt(long value, boolean bigEndian) {
    return fromNumber(value, 4, bigEndian);
  }

  /**
   * Encodes the low 16 bits of an integer into a new 2-byte vector.
   * The byte order is big-endian.
   *
   * @param value the value to encode
   * @return encoded bytes
   */
  public static ByteVector fromShort(short value) {
    return fromShort(value, true);
  }

  /**
   * Encodes the low 16 bits of an integer into a new 2-byte vector.
   *
   * @param value     the value to encode
   * @param bigEndian {@code true} for most-significant byte first
   * @return encoded bytes
   */
  public static ByteVector fromShort(short value, boolean bigEndian) {
    return fromNumber(value, 2, bigEndian);
  }

  /**
   * Encodes the low 16 bits of an integer into a new 2-byte vector.
   * The byte order is big-endian.
   *
   * @param value the value to encode
   * @return encoded bytes
   */
  public static ByteVector fromUnsignedShort(int value) {
    return fromUnsignedShort(value, true);
  }

  /**
   * Encodes the low 16 bits of an integer into a new 2-byte vector.
   *
   * @param value     the value to encode
   * @param bigEndian {@code true} for most-significant byte first
   * @return encoded bytes
   */
  public static ByteVector fromUnsignedShort(int value, boolean bigEndian) {
    return fromNumber(value, 2, bigEndian);
  }

  /**
   * Encodes the low 64 bits of an integer into a new 8-byte vector.
   * The byte order is big-endian.
   *
   * @param value the value to encode
   * @return encoded bytes
   */
  public static ByteVector fromLong(long value) {
    return fromLong(value, true);
  }

  /**
   * Encodes the low 64 bits of an integer into a new 8-byte vector.
   *
   * @param value     the value to encode
   * @param bigEndian {@code true} for most-significant byte first
   * @return encoded bytes
   */
  public static ByteVector fromLong(long value, boolean bigEndian) {
    return fromNumber(value, 8, bigEndian);
  }

  /**
   * Encodes the low 64 bits of an integer into a new 8-byte vector.
   * The byte order is big-endian.
   *
   * @param value the value to encode
   * @return encoded bytes
   */
  public static ByteVector fromUnsignedLong(long value) {
    return fromUnsignedLong(value, true);
  }

  /**
   * Encodes the low 64 bits of an integer into a new 8-byte vector.
   *
   * @param value     the value to encode
   * @param bigEndian {@code true} for most-significant byte first
   * @return encoded bytes
   */
  public static ByteVector fromUnsignedLong(long value, boolean bigEndian) {
    return fromLong(value, bigEndian);
  }

  /**
   * Encodes an IEEE-754 value into a new 4-byte vector.
   * The byte order is little-endian.
   *
   * @param value the value to encode
   * @return encoded bytes
   */
  public static ByteVector fromFloat32LE(float value) {
    return fromNumber(Float.floatToRawIntBits(value), 4, false);
  }

  /**
   * Encodes an IEEE-754 value into a new 4-byte vector.
   * The byte order is big-endian.
   *
   * @param value the value to encode
   * @return encoded bytes
   */
  public static ByteVector fromFloat32BE(float value) {
    return fromNumber(Float.floatToRawIntBits(value), 4, true);
  }

  /**
   * Encodes an IEEE-754 value into a new 8-byte vector.
   * The byte order is little-endian.
   *
   * @param value the value to encode
   * @return encoded bytes
   */
  public static ByteVector fromFloat64LE(double value) {
    return fromNumber(Double.doubleToRawLongBits(value), 8, false);
  }

  /**
   * Encodes an IEEE-754 value into a new 8-byte vector.
   * The byte order is big-endian.
   *
   * @param value the value to encode
   * @return encoded bytes
   */
  public static ByteVector fromFloat64BE(double value) {
    return fromNumber(Double.doubleToRawLongBits(value), 8, true);
  }

  /**
   * Encodes ISO-8859-1 text into a new byte vector. Encoding ends at the first NUL character.
   *
   * @param value source text
   * @return encoded bytes
   */
  public static ByteVector fromCString(String value) {
    return new ByteVector(value);
  }

  /**
   * Encodes ISO-8859-1 text into a new byte vector.
   * The low 32 bits of length select an exact byte prefix, including NULs. A value of
   * {@code 0xffffffffL} selects text up to the first NUL character.
   *
   * @param value  source text
   * @param length the byte count, or {@code -1} for NUL-terminated text
   * @return encoded bytes
   * @throws IndexOutOfBoundsException if the requested prefix exceeds the encoded bytes
   */
  public static ByteVector fromCString(String value, long length) {
    long count = length & 0xffff_ffffL;
    if (count == 0xffff_ffffL) {
      return fromCString(value);
    }
    byte[] bytes = value.getBytes(StandardCharsets.ISO_8859_1);
    if (count > bytes.length) {
      throw new IndexOutOfBoundsException("C-string extent exceeds supplied bytes");
    }
    return new ByteVector(bytes, (int) count);
  }

  /**
   * Encodes all bytes as lowercase hexadecimal ASCII without separators.
   *
   * @return a new vector with two ASCII characters per input byte
   */
  public ByteVector toHex() {
    return new ByteVector(HexFormat.of().formatHex(data));
  }

  /**
   * Encodes all bytes as Base64 ASCII with padding and no line breaks.
   *
   * @return a new vector containing Base64 text
   */
  public ByteVector toBase64() {
    return takeOwnership(Base64.getEncoder().encode(data));
  }

  private static int base64(byte value) {
    int c = value & 255;
    return c >= 'A' && c <= 'Z'
      ? c - 'A'
      : c >= 'a' && c <= 'z'
      ? c - 'a' + 26
      : c >= '0' && c <= '9' ? c - '0' + 52 : c == '+' ? 62 : c == '/' ? 63 : -1;
  }

  /**
   * Decodes complete Base64 quartets. Whitespace, invalid input length and data after a padded
   * quartet produce an empty vector. Padding bits need not be canonical; if the third byte of
   * the final quartet is {@code =}, its fourth byte is ignored.
   *
   * @param input Base64 ASCII bytes
   * @return decoded bytes, or an empty vector for invalid input
   */
  public static ByteVector fromBase64(ByteVector input) {
    // Native requires complete quartets, accepts noncanonical padding bits and ignores
    // the fourth byte after '=' in position three, but rejects trailing data/whitespace.
    if (input.size() % 4 != 0) {
      return new ByteVector();
    }
    var out = new ByteArrayOutputStream();
    for (int i = 0; i < input.size(); i += 4) {
      int a = base64(input.data[i]), b = base64(input.data[i + 1]);
      if (a < 0 || b < 0) {
        return new ByteVector();
      }
      out.write((a << 2) | (b >> 4));
      if (input.data[i + 2] == '=') {
        return i + 4 == input.size() ? takeOwnership(out.toByteArray()) : new ByteVector();
      }
      int c = base64(input.data[i + 2]);
      if (c < 0) {
        return new ByteVector();
      }
      out.write((b << 4) | (c >> 2));
      if (input.data[i + 3] == '=') {
        return i + 4 == input.size() ? takeOwnership(out.toByteArray()) : new ByteVector();
      }
      int d = base64(input.data[i + 3]);
      if (d < 0) {
        return new ByteVector();
      }
      out.write((c << 6) | d);
    }
    return takeOwnership(out.toByteArray());
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ByteVector v && Arrays.equals(data, v.data);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(data);
  }

  /**
   * Compares byte values lexicographically as unsigned integers.
   *
   * @param other the byte vector to compare
   * @return a negative, zero or positive comparison result
   */
  @Override
  public int compareTo(ByteVector other) {
    return Arrays.compareUnsigned(data, other.data);
  }

  /**
   * Decodes all bytes as ISO-8859-1, including embedded NUL bytes.
   *
   * @return the decoded text
   */
  @Override
  public String toString() {
    return new String(data, StandardCharsets.ISO_8859_1);
  }

  /**
   * Iterates the live bytes in index order. Structural edits can change the remaining
   * iteration range. Removal through the iterator is unsupported.
   *
   * @return a live byte iterator
   */
  @Override
  public Iterator<Byte> iterator() {
    return new Iterator<>() {
      private int position;

      public boolean hasNext() {
        return position < size();
      }

      public Byte next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        return data[position++];
      }
    };
  }
}
