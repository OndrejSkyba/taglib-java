// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.toolkit;

/**
 * A seekable byte stream with a shared mutable cursor.
 * Reads and writes operate at the cursor. Insertions, removals and truncation change storage
 * immediately; these operations are independent of audio-file metadata staging. Implementations
 * report storage failures through unchecked exceptions and define their own closed-stream behavior.
 */
public abstract class IOStream implements AutoCloseable {
  /**
   * The origin used to resolve a relative byte offset.
   */
  public enum Position {
    BEGINNING,
    CURRENT,
    END
  }

  /**
   * Returns the stream name used for diagnostics and format detection.
   *
   * @return a name, or an empty string for an unnamed stream
   */
  public abstract String name();

  /**
   * Reads up to the requested byte count and advances the cursor by the bytes returned.
   * At or beyond the end of input, returns empty data without advancing.
   *
   * @param length the maximum nonnegative byte count
   * @return available bytes, or an empty vector at end of input
   * @throws IllegalArgumentException     if the byte count is negative
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public abstract ByteVector readBlock(long length);

  /**
   * Writes the supplied bytes at the current cursor, advancing by the bytes written.
   * Storage is extended when necessary. Read-only and closed-stream behavior depends on
   * the implementation.
   *
   * @param data bytes to write
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public abstract void writeBlock(ByteVector data);

  /**
   * Inserts bytes at byte offset zero.
   * The operation can move the cursor.
   *
   * @param data bytes to insert
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public final void insert(ByteVector data) {
    insert(data, 0, 0);
  }

  /**
   * Inserts bytes at the selected byte offset.
   * The operation can move the cursor.
   *
   * @param data  bytes to insert
   * @param start the nonnegative byte offset
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public final void insert(ByteVector data, long start) {
    insert(data, start, 0);
  }

  /**
   * Inserts bytes at the selected byte offset.
   * Replaces the requested span and shifts subsequent bytes to accommodate the size difference.
   * The operation can move the cursor.
   *
   * @param data    bytes to insert
   * @param start   the nonnegative byte offset
   * @param replace the nonnegative byte count to replace
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public abstract void insert(ByteVector data, long start, long replace);

  /**
   * Removes a byte span and shifts subsequent bytes toward its starting offset.
   * The operation can move the cursor; its resulting position depends on the implementation.
   *
   * @param start  the nonnegative starting byte offset
   * @param length the nonnegative byte count to remove
   * @throws IllegalArgumentException     if the span is negative
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public abstract void removeBlock(long start, long length);

  /**
   * Reports whether ordinary writes are disabled for this stream.
   *
   * @return whether the stream is read-only
   */
  public abstract boolean readOnly();

  /**
   * Reports whether storage remains available through this stream.
   *
   * @return whether the stream is open
   */
  public abstract boolean isOpen();

  /**
   * Moves the cursor to an absolute byte offset.
   * An open stream permits positions beyond its end. A rejected seek leaves the cursor unchanged.
   *
   * @param offset the byte offset
   * @throws IllegalArgumentException     if the resulting offset is negative
   * @throws ArithmeticException          if the resulting offset overflows a Java {@code long}
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public final void seek(long offset) {
    seek(offset, Position.BEGINNING);
  }

  /**
   * Moves the cursor relative to the selected origin.
   * An open stream permits positions beyond its end. A rejected seek leaves the cursor unchanged.
   *
   * @param offset   the byte offset relative to the origin
   * @param position the reference origin
   * @throws IllegalArgumentException     if the resulting offset is negative
   * @throws ArithmeticException          if the resulting offset overflows a Java {@code long}
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public abstract void seek(long offset, Position position);

  /**
   * Clears stream status, such as an end-of-input flag, without changing stored bytes.
   * The base implementation has no status to clear.
   */
  public void clear() {
  }

  /**
   * Returns the current absolute byte offset.
   *
   * @return the stream cursor
   */
  public abstract long tell();

  /**
   * Returns the stored byte length.
   *
   * @return the byte length
   * @throws java.io.UncheckedIOException if querying storage fails
   */
  public abstract long length();

  /**
   * Changes the stored byte length without moving the logical cursor.
   * Shortening discards trailing bytes; extension introduces zero bytes.
   *
   * @param length the new nonnegative byte length
   * @throws IllegalArgumentException     if the length is negative
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public abstract void truncate(long length);

  /**
   * Releases resources owned by this stream. The base implementation has no resources
   * to release. Closing a borrowed view must not close its owner.
   *
   * @throws java.io.UncheckedIOException if releasing owned storage fails
   */
  @Override
  public void close() {
  }
}
