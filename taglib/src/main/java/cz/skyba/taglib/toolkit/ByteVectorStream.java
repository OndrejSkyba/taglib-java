// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.toolkit;

/// A writable memory stream with independent initial byte storage.
/// The cursor can move beyond the data. Writing beyond the end or extending storage fills the
/// gap with zero bytes. Materialized storage must fit a Java byte array. The stream remains
/// open and usable after [#close()], which has no resources to release.
public class ByteVectorStream extends IOStream {
  private final ByteVector data;
  private long position;

  /// Creates a writable memory stream by copying the supplied bytes.
  ///
  /// @param data initial stream bytes
  public ByteVectorStream(ByteVector data) {
    this.data = new ByteVector(data);
  }

  /// Creates a writable memory stream by copying the supplied bytes.
  ///
  /// @param data initial stream bytes
  public ByteVectorStream(byte[] data) {
    this.data = new ByteVector(data);
  }

  @Override
  public String name() {
    return "";
  }

  @Override
  public ByteVector readBlock(long length) {
    if (length < 0) {
      throw new IllegalArgumentException("Negative block length");
    }
    if (length == 0 || position >= data.size()) {
      return new ByteVector();
    }
    var value = data.mid((int) position, (int) Math.min(length, data.size() - position));
    position += value.size();
    return value;
  }

  @Override
  public void writeBlock(ByteVector value) {
    int start = Math.toIntExact(position);
    if (start < 0) {
      throw new IllegalArgumentException("Negative write position");
    }
    int end = Math.addExact(start, value.size());
    // Keep native alias semantics valid when a caller writes the exposed data value.
    byte[] source = value.data();
    if (source == data.data()) {
      source = source.clone();
    }
    if (end > data.size()) {
      truncate(end);
    }
    data.set(start, source);
    position = end;
  }

  /// Inserts bytes or replaces a span in memory and positions the cursor after the inserted bytes.
  ///
  /// @param value   bytes to insert
  /// @param start   the nonnegative byte offset
  /// @param replace the nonnegative byte count to replace
  /// @throws IllegalArgumentException if the span is negative or a growing replacement exceeds storage
  /// @throws ArithmeticException      if the resulting span exceeds Java array representation
  @Override
  public void insert(ByteVector value, long start, long replace) {
    if (start < 0 || replace < 0) {
      throw new IllegalArgumentException("Invalid insert span");
    }
    long difference = value.size() - replace;
    if (difference < 0) {
      removeBlock(start + value.size(), -difference);
    } else if (difference > 0) {
      if (replace > length() - start) {
        throw new IllegalArgumentException("Insert replacement exceeds source");
      }
      int old = data.size();
      truncate(Math.addExact(length(), difference));
      data.set(Math.toIntExact(start + value.size()), data.data(),
        Math.toIntExact(start + replace), Math.toIntExact(old - start - replace));
    }
    seek(start);
    writeBlock(value);
  }

  /// Removes a byte span, moves the tail and positions the cursor at the resulting stored length.
  /// If the starting offset is beyond the data, storage is extended to that offset.
  ///
  /// @param start the nonnegative starting byte offset
  /// @param count the nonnegative byte count to remove
  /// @throws IllegalArgumentException if the span is negative
  /// @throws ArithmeticException      if span arithmetic or the resulting length exceeds representation
  @Override
  public void removeBlock(long start, long count) {
    if (start < 0 || count < 0) {
      throw new IllegalArgumentException("Negative remove span");
    }
    long read = Math.addExact(start, count), write = start;
    if (read < length()) {
      long amount = length() - read;
      data.set(Math.toIntExact(write), data.data(), Math.toIntExact(read), Math.toIntExact(amount));
      write += amount;
    }
    position = write;
    truncate(write);
  }

  @Override
  public boolean readOnly() {
    return false;
  }

  @Override
  public boolean isOpen() {
    return true;
  }

  @Override
  public void seek(long offset, Position origin) {
    long next =
      switch (origin) {
        case BEGINNING -> offset;
        case CURRENT -> Math.addExact(position, offset);
        case END -> Math.addExact(length(), offset);
      };
    if (next < 0) {
      throw new IllegalArgumentException("Negative seek position");
    }
    position = next;
  }

  @Override
  public long tell() {
    return position;
  }

  @Override
  public long length() {
    return data.size();
  }

  /// Changes the memory buffer length without moving the cursor. Extension introduces zero bytes.
  ///
  /// @param length the new nonnegative byte length
  /// @throws IllegalArgumentException if the length is negative
  /// @throws ArithmeticException      if the length exceeds Java array representation
  @Override
  public void truncate(long length) {
    if (length < 0) {
      throw new IllegalArgumentException("Negative stream length");
    }
    data.resize(Math.toIntExact(length));
  }

  /// Returns the live mutable byte vector backing this stream.
  /// Changes through it affect stream storage directly and do not adjust the cursor.
  ///
  /// @return the live storage vector
  public ByteVector data() {
    return data;
  }
}
