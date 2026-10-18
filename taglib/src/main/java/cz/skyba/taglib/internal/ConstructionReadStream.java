// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal;

import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.FileStream;
import cz.skyba.taglib.toolkit.IOStream;

import java.util.Objects;

/**
 * Bounded read window used only while constructing an ordinary owned file.
 */
public final class ConstructionReadStream extends IOStream {
  private static final int WINDOW_SIZE = 4096;
  private final IOStream source;
  private final boolean eligible;
  private final boolean defaultIo;
  private boolean active;
  private int windowSize;
  private long size = -1, windowStart = -1;
  private byte[] window;

  private ConstructionReadStream(IOStream source, boolean owned, boolean defaultIo) {
    this.source = Objects.requireNonNull(source);
    this.defaultIo = defaultIo;
    eligible = owned && source.getClass() == FileStream.class && !source.name().isEmpty();
  }

  public static ConstructionReadStream wrap(IOStream source, boolean owned) {
    return source instanceof ConstructionReadStream prepared ? prepared : new ConstructionReadStream(source, owned, false);
  }

  /**
   * Wraps I/O for AudioFile's built-in file adapter, which inherits the library's read and seek operations.
   */
  public static ConstructionReadStream wrapForFileRef(IOStream source, boolean owned) {
    return source instanceof ConstructionReadStream prepared ? prepared : new ConstructionReadStream(source, owned, true);
  }

  public boolean usesDefaultIo() {
    return defaultIo;
  }

  public IOStream source() {
    return source;
  }

  public void begin(boolean standardCallbacks, int maxBytes) {
    active = eligible && standardCallbacks;
    windowSize = Math.min(WINDOW_SIZE, maxBytes);
  }

  public boolean isBuffered() {
    return active;
  }

  public void finish() {
    active = false;
    invalidate();
  }

  private void invalidate() {
    window = null;
    windowStart = -1;
    size = -1;
  }

  @Override
  public String name() {
    return source.name();
  }

  @Override
  public boolean readOnly() {
    return source.readOnly();
  }

  @Override
  public boolean isOpen() {
    return source.isOpen();
  }

  @Override
  public long tell() {
    return source.tell();
  }

  @Override
  public void seek(long offset, Position origin) {
    source.seek(offset, origin);
  }

  @Override
  public void clear() {
    source.clear();
  }

  @Override
  public long length() {
    long current = source.length();
    if (size != current) {
      window = null;
      windowStart = -1;
    }
    size = current;
    return current;
  }

  @Override
  public ByteVector readBlock(long count) {
    if (!active || count <= 0 || count > windowSize || !source.isOpen()) {
      window = null;
      return source.readBlock(count);
    }
    long position = source.tell();
    if (size < 0) length();
    // Let the original stream handle EOF and positions outside the data.
    if (position < 0 || position > size || count > size - position) {
      window = null;
      return source.readBlock(count);
    }
    if (window == null || position < windowStart || position - windowStart > window.length - count) {
      source.seek(position);
      int wanted = (int) Math.min(windowSize, size - position);
      ByteVector loaded = source.readBlock(wanted);
      if (loaded.size() != wanted) {
        // A truncation or failed read must retain the original request's short-read behavior.
        invalidate();
        source.seek(position);
        return source.readBlock(count);
      }
      window = loaded.data();
      windowStart = position;
    }
    var result = new ByteVector((int) count);
    result.set(0, window, (int) (position - windowStart), (int) count);
    source.seek(position + count);
    return result;
  }

  @Override
  public void writeBlock(ByteVector data) {
    invalidate();
    source.writeBlock(data);
  }

  @Override
  public void insert(ByteVector data, long at, long replace) {
    invalidate();
    source.insert(data, at, replace);
  }

  @Override
  public void removeBlock(long at, long count) {
    invalidate();
    source.removeBlock(at, count);
  }

  @Override
  public void truncate(long length) {
    invalidate();
    source.truncate(length);
  }

  @Override
  public void close() {
    finish();
    source.close();
  }
}
