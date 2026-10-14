// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.toolkit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.NonWritableChannelException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;

/**
 * A file-backed byte stream that owns its file channel.
 * Path opening attempts read/write access and falls back to read-only access unless read-only
 * access was requested explicitly. Storage failures preserve their {@link IOException}
 * cause in {@link UncheckedIOException}. The logical cursor is independent of the channel's
 * position. Closing never saves audio metadata.
 */
public class FileStream extends IOStream {
  private static final int BUFFER_SIZE = 1024;
  private static final int COPY_BUFFER_SIZE = 64 * 1024;
  private final Path path;
  private FileChannel channel;
  private boolean readOnly = true;
  private long position;
  private boolean eof;

  /**
   * Returns the native-compatible block size. Internal file shifts may use larger batches.
   *
   * @return the buffer size in bytes
   */
  protected static int bufferSize() {
    return BUFFER_SIZE;
  }

  /**
   * Opens a file channel for the supplied path.
   * Writable opening falls back to read-only access if write access is unavailable.
   *
   * @param path the existing file to open
   * @throws UncheckedIOException if opening or inspecting storage fails
   * @see #FileStream(Path, boolean)
   */
  public FileStream(Path path) {
    this(path, false);
  }

  /**
   * Opens a file channel for the supplied path.
   * Writable opening falls back to read-only access if write access is unavailable.
   *
   * @param path         the existing file to open
   * @param openReadOnly whether to request read-only access
   * @throws UncheckedIOException if opening or inspecting storage fails
   */
  public FileStream(Path path, boolean openReadOnly) {
    this.path = Objects.requireNonNull(path);
    if (!openReadOnly) {
      try {
        channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        readOnly = false;
      } catch (IOException ignored) {
        /* Native falls back to read-only. */
      }
    }
    if (channel == null) {
      try {
        channel = FileChannel.open(path, StandardOpenOption.READ);
      } catch (IOException failure) {
        throw new UncheckedIOException("Cannot open audio stream: " + path, failure);
      }
    }
  }

  /**
   * Wraps a readable channel and takes ownership when initialization succeeds.
   * The logical cursor starts at the channel's current position. The stream is unnamed.
   * A rejected channel remains caller-owned; a non-writable channel is accepted as read-only.
   *
   * @param channel the readable channel to own
   * @throws UncheckedIOException                          if opening or inspecting storage fails
   * @throws java.nio.channels.NonReadableChannelException if the channel is not readable
   * @see #FileStream(FileChannel, boolean)
   */
  public FileStream(FileChannel channel) {
    this(channel, false);
  }

  /**
   * Wraps a readable channel and takes ownership when initialization succeeds.
   * The logical cursor starts at the channel's current position. The stream is unnamed.
   * A rejected channel remains caller-owned; a non-writable channel is accepted as read-only.
   *
   * @param channel      the readable channel to own
   * @param openReadOnly whether to request read-only access
   * @throws UncheckedIOException                          if opening or inspecting storage fails
   * @throws java.nio.channels.NonReadableChannelException if the channel is not readable
   */
  @SuppressWarnings("ResultOfMethodCallIgnored")
  public FileStream(FileChannel channel, boolean openReadOnly) {
    path = null;
    this.channel = Objects.requireNonNull(channel);
    readOnly = openReadOnly;
    try {
      position = channel.position();
      channel.read(ByteBuffer.allocate(0), position);
      if (!openReadOnly) {
        try {
          // A zero-byte positional write checks channel capability without changing its contents.
          channel.write(ByteBuffer.allocate(0), position);
        } catch (NonWritableChannelException ignored) {
          readOnly = true;
        }
      }
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot access audio stream channel", failure);
    }
  }

  @Override
  public String name() {
    return path == null ? "" : path.toString();
  }

  private static void message(String text) {
    Debug.debug(new TagString(text));
  }

  @Override
  public boolean readOnly() {
    return readOnly;
  }

  @Override
  public boolean isOpen() {
    return channel != null && channel.isOpen();
  }

  /**
   * Reads available bytes and advances the logical cursor. A closed stream returns empty data.
   * Seeking or clearing stream status permits another read after end of input.
   *
   * @param count the maximum nonnegative byte count
   * @return available bytes, or an empty vector at end of input or when closed
   * @throws IllegalArgumentException if the byte count is negative
   * @throws UncheckedIOException     if storage access fails
   */
  @Override
  public ByteVector readBlock(long count) {
    if (count < 0) {
      throw new IllegalArgumentException("Negative block length");
    }
    if (!isOpen()) {
      message("FileStream::readBlock() -- invalid file.");
      return new ByteVector();
    }
    if (count == 0) {
      return new ByteVector();
    }
    // Native large reads ask the base length helper, whose seeks clear EOF.
    if (count > BUFFER_SIZE) {
      count = Math.min(count, fileLength());
    }
    return readRaw(count);
  }

  private ByteVector readRaw(long count) {
    if (eof || count <= 0) {
      return new ByteVector();
    }
    try {
      int size = Math.toIntExact(Math.clamp(channel.size() - position, 0L, count));
      var bytes = ByteBuffer.allocate(size);
      while (bytes.hasRemaining()) {
        int read = channel.read(bytes, position);
        if (read < 0) {
          break;
        }
        if (read == 0) {
          throw new IOException("File read made no progress");
        }
        position += read;
      }
      eof = bytes.position() < count;
      return ByteVector.takeOwnership(bytes.hasRemaining()
        ? Arrays.copyOf(bytes.array(), bytes.position()) : bytes.array());
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio stream: " + name(), failure);

    }
  }

  /**
   * Writes bytes at the logical cursor and advances it. The call has no effect when
   * the stream is closed or read-only.
   *
   * @param data bytes to write
   * @throws UncheckedIOException if storage access fails
   */
  @Override
  public void writeBlock(ByteVector data) {
    if (!isOpen()) {
      message("FileStream::writeBlock() -- invalid file.");
      return;
    }
    if (readOnly()) {
      message("FileStream::writeBlock() -- read only file.");
      return;
    }
    writeRaw(data);
  }

  private void writeRaw(ByteVector data) {
    if (readOnly) {
      return; // Native C read-only mode also blocks its private fwrite path.
    }
    var bytes = ByteBuffer.wrap(data.data());
    try {
      while (bytes.hasRemaining()) {
        int written = channel.write(bytes, position);
        if (written <= 0) {
          throw new IOException("File write made no progress");
        }
        position += written;
      }
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot write audio stream: " + name(), failure);

    }
  }

  /**
   * Inserts bytes or replaces a span immediately, shifting the file tail as needed.
   * The call has no effect when the stream is closed or read-only. The cursor is not restored.
   *
   * @param data    bytes to insert
   * @param start   the nonnegative byte offset
   * @param replace the nonnegative byte count to replace
   * @throws IllegalArgumentException if the span is negative
   * @throws ArithmeticException      if span arithmetic overflows
   * @throws UncheckedIOException     if storage access fails
   */
  @Override
  public void insert(ByteVector data, long start, long replace) {
    if (!isOpen()) {
      message("FileStream::insert() -- invalid file.");
      return;
    }
    if (readOnly()) {
      message("FileStream::insert() -- read only file.");
      return;
    }
    if (start < 0 || replace < 0) {
      throw new IllegalArgumentException("Negative insert span");
    }
    if (data.size() == replace) {
      seek(start);
      writeBlock(data);
      return;
    }
    if (data.size() < replace) {
      seek(start);
      writeBlock(data);
      removeBlock(start + data.size(), replace - data.size());
      return;
    }
    long difference = data.size() - replace;
    // Keep enough lookahead to avoid overwriting unread bytes, but batch small shifts
    // so moving an audio payload does not require a read/write pair per kilobyte.
    long bufferLength = Math.max(COPY_BUFFER_SIZE, ((difference + BUFFER_SIZE - 1) / BUFFER_SIZE) * BUFFER_SIZE);
    long readPosition = Math.addExact(start, replace), writePosition = start;
    ByteVector buffer = new ByteVector(data);
    while (true) {
      seek(readPosition);
      var next = readRaw(bufferLength);
      if (next.size() < bufferLength) {
        clear();
      }
      readPosition = Math.addExact(readPosition, bufferLength);
      seek(writePosition);
      writeRaw(buffer);
      if (next.isEmpty()) {
        break;
      }
      writePosition = Math.addExact(writePosition, buffer.size());
      buffer = next;
    }
  }

  /**
   * Removes a span immediately, shifts remaining bytes and resizes the file.
   * The resulting cursor is at the new end of the shifted tail. A closed stream is unchanged.
   *
   * @param start the nonnegative starting byte offset
   * @param count the nonnegative byte count to remove
   * @throws IllegalArgumentException    if the span is negative
   * @throws UncheckedIOException        if storage access fails
   * @throws NonWritableChannelException if the channel cannot be resized
   */
  @Override
  public void removeBlock(long start, long count) {
    if (!isOpen()) {
      message("FileStream::removeBlock() -- invalid file.");
      return;
    }
    if (start < 0 || count < 0) {
      throw new IllegalArgumentException("Negative remove span");
    }
    long readPosition = Math.addExact(start, count), writePosition = start;
    while (true) {
      seek(readPosition);
      var next = readRaw(COPY_BUFFER_SIZE);
      if (next.size() < COPY_BUFFER_SIZE) {
        clear();
      }
      readPosition += next.size();
      seek(writePosition);
      writeRaw(next);
      writePosition += next.size();
      if (next.isEmpty()) {
        break;
      }
    }
    truncate(writePosition);
  }

  /**
   * Moves the logical cursor relative to the selected origin and clears end-of-input status.
   * The call has no effect when the stream is closed.
   *
   * @param offset the byte offset relative to the origin
   * @param origin the reference origin
   * @throws IllegalArgumentException if the resulting offset is negative
   * @throws ArithmeticException      if offset arithmetic overflows
   * @throws UncheckedIOException     if querying storage fails
   */
  @Override
  public void seek(long offset, Position origin) {
    if (!isOpen()) {
      message("FileStream::seek() -- invalid file.");
      return;
    }
    try {
      long next = switch (origin) {
        case BEGINNING -> offset;
        case CURRENT -> Math.addExact(position, offset);
        case END -> Math.addExact(channel.size(), offset);
      };
      if (next < 0) {
        throw new IllegalArgumentException("Negative seek position");
      }
      position = next;
      eof = false;
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot seek audio stream: " + name(), failure);
    }
  }

  @Override
  public void clear() {
    eof = false;
  }

  @Override
  public long tell() {
    return isOpen() ? position : 0;
  }

  /**
   * Returns the stored byte length, restoring the logical cursor and clearing end-of-input status.
   *
   * @return the byte length, or zero when closed
   * @throws UncheckedIOException if querying storage fails
   */
  @Override
  public long length() {
    return fileLength();
  }

  private long fileLength() {
    if (!isOpen()) {
      message("FileStream::length() -- invalid file.");
      return 0;
    }
    long original = tell();
    try {
      seek(0, Position.END);
      return tell();
    } finally {
      seek(original, Position.BEGINNING);
    }
  }

  /**
   * Resizes storage immediately without moving the logical cursor. Extension introduces zero
   * bytes. A closed stream is unchanged.
   *
   * @param length the new nonnegative byte length
   * @throws IllegalArgumentException    if the length is negative
   * @throws UncheckedIOException        if storage access fails
   * @throws NonWritableChannelException if the channel cannot be resized
   */
  @Override
  public void truncate(long length) {
    if (length < 0) {
      throw new IllegalArgumentException("Negative stream length");
    }
    if (!isOpen()) {
      return; // Safe Java outcome for the native invalid-handle boundary.
    }
    try {
      if (length > channel.size()) {
        var lastByte = ByteBuffer.wrap(new byte[1]);
        while (lastByte.hasRemaining()) {
          if (channel.write(lastByte, length - 1 + lastByte.position()) <= 0) {
            throw new IOException("File extension made no progress");
          }
        }
      } else {
        channel.truncate(length);
      }
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot resize audio stream: " + name(), failure);

    }
  }

  /**
   * Closes the owned channel. Repeated calls have no effect.
   *
   * @throws UncheckedIOException if closing storage fails
   */
  @Override
  public void close() {
    if (channel != null) {
      try {
        channel.close();
      } catch (IOException failure) {
        throw new UncheckedIOException("Cannot close audio stream: " + name(), failure);
      }
    }
  }
}
