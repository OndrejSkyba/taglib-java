// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal;

import cz.skyba.taglib.toolkit.IOStream;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/// A read-only view of file or borrowed-stream bytes.
public final class BinaryFile implements AutoCloseable {
  private final FileChannel channel;
  private final Path path;
  private final IOStream stream;
  private final long size;
  private final long initialPosition;
  private final boolean retainReadData;

  public BinaryFile(Path path) throws IOException {
    this.path = path;
    stream = null;
    channel = FileChannel.open(path, StandardOpenOption.READ);
    size = channel.size();
    initialPosition = 0;
    retainReadData = true;
  }

  /// Borrows a caller-owned native-style stream; closing this view never closes the stream.
  public BinaryFile(IOStream stream) throws IOException {
    this(stream, false);
  }

  public BinaryFile(IOStream stream, boolean retainReadData) throws IOException {
    this.retainReadData = retainReadData;
    path = null;
    this.stream = Objects.requireNonNull(stream);
    channel = null;
    if (!stream.isOpen()) {
      throw new IOException("Stream is not open");
    }
    size = stream.length();
    initialPosition = stream.tell();
    if (size < 0) {
      throw new IOException("Negative stream length");
    }
  }

  /// Whether this construction pass can reuse immutable parsed data without bypassing custom I/O.
  public boolean retainReadData() {
    return retainReadData;
  }

  public long size() {
    return size;
  }

  /// The backing path, or null when this view borrows an IOStream.
  public Path path() {
    return path;
  }

  public Path requirePath() throws IOException {
    if (path == null) {
      throw new IOException("Operation requires a path-backed file");
    }
    return path;
  }

  /// Borrowed stream used for a native in-place save, never owned by this view.
  public IOStream stream() {
    return stream;
  }

  public IOStream requireStream() throws IOException {
    if (stream == null) {
      throw new IOException("Operation requires a borrowed stream");
    }
    return stream;
  }

  public long initialPosition() {
    return initialPosition;
  }

  /// Native readBlock semantics: EOF returns the available prefix and advances to that point.
  public byte[] readNative(long offset, int length) throws IOException {
    if (offset < 0 || length < 0) {
      throw new IOException("Invalid stream read");
    }
    if (stream != null) {
      stream.seek(offset);
      return stream.readBlock(length).toByteArray();
    }
    return read(Math.min(offset, size), Math.clamp(size - offset, 0, length));
  }

  public void seekNative(long offset) throws IOException {
    if (stream != null) {
      stream.seek(offset);
    }
  }

  public byte[] read(long offset, int length) throws IOException {
    if (offset < 0 || length < 0 || offset > size || length > size - offset) {
      throw new EOFException(
        "Read outside file: offset=" + offset + ", length=" + length + ", size=" + size);
    }
    if (stream != null) {
      stream.seek(offset);
      var data = stream.readBlock(length);
      if (data.size() != length) {
        throw new EOFException("Stream truncated while reading");
      }
      return data.toByteArray();
    }
    byte[] b = new byte[length];
    ByteBuffer buf = ByteBuffer.wrap(b);
    while (buf.hasRemaining()) {
      int n = channel.read(buf, offset + buf.position());
      if (n < 0) {
        throw new EOFException("File truncated while reading");
      }
    }
    return b;
  }

  public int u8(long o) throws IOException {
    return read(o, 1)[0] & 255;
  }

  public long u32be(long o) throws IOException {
    return Binary.u32be(read(o, 4), 0);
  }

  public long u32le(long o) throws IOException {
    return Binary.u32le(read(o, 4), 0);
  }

  public long u64be(long o) throws IOException {
    return Binary.u64be(read(o, 8), 0);
  }

  public long u64le(long o) throws IOException {
    return Binary.u64le(read(o, 8), 0);
  }

  public String ascii(long o, int n) throws IOException {
    return Binary.ascii(read(o, n), 0, n);
  }

  @Override
  public void close() throws IOException {
    if (channel != null) {
      channel.close();
    }
  }
}
