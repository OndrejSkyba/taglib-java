// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;

import cz.skyba.taglib.internal.FileOwnerStream;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.FileStream;
import cz.skyba.taglib.toolkit.IOStream;
import cz.skyba.taglib.toolkit.Variant;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Base class for typed audio files and custom format implementations.
 * Metadata edits are staged until {@link #save()}; operations that write immediately are
 * identified in their own contracts. Path constructors own their streams; stream
 * constructors borrow them. Read and cursor operations share the underlying stream cursor.
 * Protected write hooks allow format implementations to maintain their cached state.
 */
public abstract class FileBase implements AutoCloseable {
  /**
   * Controls whether tag families outside a selected save mask are removed or preserved.
   */
  public enum StripTags {STRIP_NONE, STRIP_OTHERS}

  /**
   * Controls whether common values are copied to other selected tag families during saving.
   */
  public enum DuplicateTags {DUPLICATE, DO_NOT_DUPLICATE}

  private final IOStream stream;
  private final boolean owner;
  private boolean valid = true;
  private IOStream ownerStream;

  /**
   * Initializes a format implementation with an owned path-backed stream.
   * The subclass is responsible for parsing and validating the audio format.
   *
   * @param path the file to open
   * @throws java.io.UncheckedIOException if opening storage fails
   */
  protected FileBase(Path path) {
    stream = new FileStream(path);
    owner = true;
  }

  /**
   * Initializes a format implementation with a borrowed stream that remains open when this file closes.
   * The subclass is responsible for parsing and validating the audio format.
   *
   * @param stream the open, seekable input stream
   * @throws IllegalStateException if the stream is closed
   */
  protected FileBase(IOStream stream) {
    this.stream = Objects.requireNonNull(stream);
    owner = false;
    if (!stream.isOpen()) {
      throw new IllegalStateException("Audio stream is closed: " + stream.name());
    }
  }

  /**
   * Returns the live mutable metadata view. Edits remain in memory until save().
   *
   * @return the tag view supplied by the format implementation
   */
  public abstract TagBase tag();

  /**
   * Returns parsed common audio properties.
   *
   * @return the properties, or {@code null} if unavailable or not requested
   */
  public abstract AudioProperties audioProperties();

  /**
   * Writes pending tag changes in place. A failure may leave partial changes on storage.
   *
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if saving is rejected
   * @throws IllegalStateException        if the file is closed
   */
  public abstract void save();

  /**
   * Checks that storage is open and permits writes.
   *
   * @throws IllegalStateException if the file is closed
   * @throws TagLibException       if storage is read-only
   */
  protected final void requireWritable() {
    if (!isOpen()) {
      throw new IllegalStateException("Audio file is closed: " + name());
    }
    if (readOnly()) {
      throw new TagLibException("Audio file is read-only: " + name());
    }
  }

  public String name() {
    return stream.name();
  }

  /**
   * Returns an immutable snapshot of the text properties exposed by the tag.
   *
   * @return a text property snapshot
   */
  public PropertyMap properties() {
    return tag().properties();
  }

  /**
   * Replaces the supported text properties in memory. Omitted keys are cleared; rejected values
   * are reported without preventing other values from being accepted. This does not save.
   * Unsupported-data identifiers do not request removal of opaque metadata; use
   * {@link #removeUnsupportedProperties(List)} for that operation.
   */
  public PropertyUpdateResult replaceProperties(PropertyMap values) {
    return tag().replaceProperties(values);
  }

  /**
   * Stages removal of opaque metadata identified by {@link PropertyMap#unsupportedData()}.
   * Call {@link #save()} to persist removal.
   *
   * @param values identifiers supplied by this file
   * @see TagBase#removeUnsupportedProperties(List)
   */
  public void removeUnsupportedProperties(List<String> values) {
    tag().removeUnsupportedProperties(values);
  }

  /**
   * Lists complex metadata categories present in the tag.
   * Presence does not indicate whether a category can be edited.
   *
   * @return the category names
   * @see #supportsPictureEditing()
   */
  public List<String> complexPropertyKeys() {
    return tag().complexPropertyKeys();
  }

  /**
   * Reports artwork editing support independently of existing artwork or storage writability.
   */
  public boolean supportsPictureEditing() {
    return tag().supportsPictureEditing();
  }

  /**
   * Returns structured values for a complex metadata category.
   * An absent or unsupported category returns an empty list. Field names and supported
   * categories depend on the format.
   *
   * @param key the category name
   * @return the category values
   * @see TagBase#complexProperties(String)
   */
  public List<Map<String, Variant>> complexProperties(String key) {
    return tag().complexProperties(key);
  }

  /**
   * Stages replacement of a complex metadata category using format-specific fields.
   * An empty list requests removal. Call {@link #save()} to persist accepted changes.
   *
   * @param key    the category name
   * @param values replacement structured values
   * @return {@code true} if accepted; {@code false} if the category is unsupported
   */
  public boolean setComplexProperties(String key, List<Map<String, Variant>> values) {
    return tag().setComplexProperties(key, values);
  }

  /**
   * Reads available bytes from the current cursor and advances by the bytes returned.
   *
   * @param length the maximum nonnegative byte count
   * @return available bytes; an empty vector at end of input
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if a format-specific metadata read limit is exceeded
   */
  public ByteVector readBlock(long length) {
    return stream.readBlock(length);
  }

  /**
   * Writes bytes at the current cursor through the format implementation.
   *
   * @param data bytes to write
   * @throws java.io.UncheckedIOException if storage access fails
   */
  protected void writeBlock(ByteVector data) {
    stream.writeBlock(data);
  }

  /**
   * Inserts bytes at the beginning of storage.
   *
   * @param data bytes to insert
   * @throws java.io.UncheckedIOException if storage access fails
   */
  protected void insert(ByteVector data) {
    insert(data, 0, 0);
  }

  /**
   * Inserts bytes at the selected byte offset.
   *
   * @param data  bytes to insert
   * @param start the byte offset for insertion
   * @throws java.io.UncheckedIOException if storage access fails
   */
  protected void insert(ByteVector data, long start) {
    insert(data, start, 0);
  }

  /**
   * Inserts bytes at the selected byte offset. The selected span is replaced; other bytes are shifted as needed.
   *
   * @param data    bytes to insert
   * @param start   the byte offset for insertion
   * @param replace the byte count to replace
   * @throws java.io.UncheckedIOException if storage access fails
   */
  protected void insert(ByteVector data, long start, long replace) {
    stream.insert(data, start, replace);
  }

  /**
   * Removes a byte span through the format implementation.
   *
   * @param start  the byte offset of the span
   * @param length the byte count to remove
   * @throws java.io.UncheckedIOException if storage access fails
   */
  protected void removeBlock(long start, long length) {
    stream.removeBlock(start, length);
  }

  public boolean readOnly() {
    return stream.readOnly();
  }

  public boolean isOpen() {
    return stream.isOpen();
  }

  public boolean isValid() {
    return isOpen() && valid;
  }

  /**
   * Moves the shared cursor to an absolute byte offset.
   *
   * @param offset the byte offset from the beginning
   * @throws IllegalArgumentException     if the resulting position is negative
   * @throws ArithmeticException          if the resulting position overflows
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public void seek(long offset) {
    stream.seek(offset);
  }

  /**
   * Moves the shared cursor relative to the selected origin.
   *
   * @param offset the byte offset relative to the origin
   * @param origin the reference position
   * @throws IllegalArgumentException     if the resulting position is negative
   * @throws ArithmeticException          if the resulting position overflows
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public void seek(long offset, IOStream.Position origin) {
    stream.seek(offset, origin);
  }

  public long tell() {
    return stream.tell();
  }

  public long length() {
    return stream.length();
  }

  /**
   * Clears stream status, such as the end-of-input flag, without changing metadata or stored bytes.
   */
  public void clear() {
    stream.clear();
  }

  /**
   * Changes the stored byte length through the format implementation.
   *
   * @param length the new nonnegative byte length
   * @throws java.io.UncheckedIOException if storage access fails
   */
  protected void truncate(long length) {
    stream.truncate(length);
  }

  /**
   * Borrows a stream view for format collaborators. Operations dispatch through this file's
   * virtual hooks; closing the view does not close this owner or its backing stream.
   */
  protected final IOStream ownerStream() {
    if (ownerStream == null) {
      ownerStream = new WritableOwnerStream(this);
    }
    return ownerStream;
  }

  private static final class WritableOwnerStream extends FileOwnerStream {
    WritableOwnerStream(FileBase owner) {
      super(owner);
    }

    @Override
    public boolean readOnly() {
      return owner.readOnly();
    }

    @Override
    public void writeBlock(ByteVector data) {
      owner.writeBlock(data);
    }

    @Override
    public void insert(ByteVector data, long start, long replace) {
      owner.insert(data, start, replace);
    }

    @Override
    public void removeBlock(long start, long length) {
      owner.removeBlock(start, length);
    }

    @Override
    public void truncate(long length) {
      owner.truncate(length);
    }
  }

  protected void setValid(boolean valid) {
    this.valid = valid;
  }

  protected static int bufferSize() {
    return 1024;
  }

  /**
   * Searches forward for a byte pattern and restores the original cursor.
   * The search starts at byte offset zero.
   *
   * @param pattern the byte sequence to find, at most 1,024 bytes long
   * @return the byte offset of a match, or {@code -1} if none is found
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public long find(ByteVector pattern) {
    return find(pattern, 0, new ByteVector());
  }

  /**
   * Searches forward for a byte pattern and restores the original cursor.
   *
   * @param pattern    the byte sequence to find, at most 1,024 bytes long
   * @param fromOffset the starting byte offset
   * @return the byte offset of a match, or {@code -1} if none is found
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public long find(ByteVector pattern, long fromOffset) {
    return find(pattern, fromOffset, new ByteVector());
  }

  /**
   * Searches forward for a byte pattern and restores the original cursor.
   * Stops at a block containing the stop marker when that block contains no pattern match.
   * An empty stop marker disables this condition.
   *
   * @param pattern    the byte sequence to find, at most 1,024 bytes long
   * @param fromOffset the starting byte offset
   * @param before     the optional stop marker; use an empty vector to disable it
   * @return the byte offset of a match, or {@code -1} if none is found
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public long find(ByteVector pattern, long fromOffset, ByteVector before) {
    if (pattern.size() > bufferSize()) {
      return -1;
    }
    long offset = fromOffset, original = tell();
    int partial = -1, beforePartial = -1;
    seek(fromOffset);
    try {
      for (var buffer = readBlock(bufferSize());
           !buffer.isEmpty();
           buffer = readBlock(bufferSize())) {
        if (partial >= 0
          && bufferSize() > partial
          && buffer.containsAt(pattern, 0, bufferSize() - partial, pattern.size())) {
          return offset - bufferSize() + partial;
        }
        if (!before.isEmpty()
          && beforePartial >= 0
          && bufferSize() > beforePartial
          && buffer.containsAt(before, 0, bufferSize() - beforePartial, before.size())) {
          return -1;
        }
        int location = buffer.find(pattern);
        if (location >= 0) {
          return offset + location;
        }
        if (!before.isEmpty() && buffer.find(before) >= 0) {
          return -1;
        }
        partial = buffer.endsWithPartialMatch(pattern);
        if (!before.isEmpty()) {
          beforePartial = buffer.endsWithPartialMatch(before);
        }
        offset += bufferSize();
      }
      clear();
      return -1;
    } finally {
      seek(original);
    }
  }

  /**
   * Searches backward for a byte pattern and restores the original cursor.
   * The search starts at the end of storage.
   * A match crossing the boundary between search blocks may not be found.
   *
   * @param pattern the byte sequence to find, at most 1,024 bytes long
   * @return the byte offset of a match, or {@code -1} if none is found
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public long rfind(ByteVector pattern) {
    return rfind(pattern, 0, new ByteVector());
  }

  /**
   * Searches backward for a byte pattern and restores the original cursor.
   * A starting offset of zero selects the end of storage.
   * A match crossing the boundary between search blocks may not be found.
   *
   * @param pattern    the byte sequence to find, at most 1,024 bytes long
   * @param fromOffset the starting byte offset
   * @return the byte offset of a match, or {@code -1} if none is found
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public long rfind(ByteVector pattern, long fromOffset) {
    return rfind(pattern, fromOffset, new ByteVector());
  }

  /**
   * Searches backward for a byte pattern and restores the original cursor.
   * A starting offset of zero selects the end of storage.
   * Stops at a block containing the stop marker when that block contains no pattern match.
   * An empty stop marker disables this condition.
   * A match crossing the boundary between search blocks may not be found.
   *
   * @param pattern    the byte sequence to find, at most 1,024 bytes long
   * @param fromOffset the starting byte offset
   * @param before     the optional stop marker; use an empty vector to disable it
   * @return the byte offset of a match, or {@code -1} if none is found
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public long rfind(ByteVector pattern, long fromOffset, ByteVector before) {
    if (pattern.size() > bufferSize()) {
      return -1;
    }
    long original = tell();
    if (fromOffset == 0) {
      fromOffset = length();
    }
    long count = bufferSize(), offset = fromOffset + pattern.size();
    try {
      while (true) {
        if (offset > count) {
          offset -= count;
        } else {
          count = offset;
          offset = 0;
        }
        seek(offset);
        var buffer = readBlock(count);
        if (buffer.isEmpty()) {
          break;
        }
        // Upstream deliberately has no cross-buffer partial-match pass in rfind.
        int location = buffer.rfind(pattern);
        if (location >= 0) {
          return offset + location;
        }
        if (!before.isEmpty() && buffer.find(before) >= 0) {
          return -1;
        }
      }
      clear();
      return -1;
    } finally {
      seek(original);
    }
  }

  /**
   * Closes owned storage without saving staged metadata changes.
   * Borrowed streams remain open.
   *
   * @throws java.io.UncheckedIOException if closing owned storage fails
   */
  @Override
  public void close() {
    if (owner) {
      stream.close();
    }
  }
}
