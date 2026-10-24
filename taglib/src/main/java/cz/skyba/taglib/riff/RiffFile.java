// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.riff;

import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.InvalidAudioFileException;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.internal.FailureSupport;
import cz.skyba.taglib.internal.MetadataLimitException;
import cz.skyba.taglib.toolkit.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Objects;

import static java.lang.System.Logger.Level.DEBUG;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

/// A RIFF-family file with configurable byte order.
/// Protected chunk edits write storage immediately and update the file layout.
public abstract class RiffFile extends FileBase {
  private static final System.Logger LOG = System.getLogger(RiffFile.class.getName());

  /// The byte order used by RIFF-family integer fields.
  protected enum Endianness {BigEndian, LittleEndian}

  private static final int MAX_CHUNKS = 50_000;

  private static final class Chunk {
    final ByteVector name;
    long offset, size;
    int padding;

    Chunk(ByteVector name, long offset, long size, int padding) {
      this.name = new ByteVector(name);
      this.offset = offset;
      this.size = size;
      this.padding = padding;
    }
  }

  private final ArrayList<Chunk> chunks = new ArrayList<>();
  private final IOStream source;
  private final boolean owned, bigEndian;
  private final int metadataLimit;
  private long size, sizeOffset, ds64Offset, dataSize64;
  private boolean longForm;

  /// Initializes a RIFF-family implementation and reads its chunk layout.
  /// Path storage is owned; supplied streams are borrowed unless ownership is explicitly requested.
  ///
  /// @param path       the input file
  /// @param endianness the container byte order
  /// @throws java.io.UncheckedIOException if storage access fails
  protected RiffFile(Path path, Endianness endianness) {
    Objects.requireNonNull(endianness);
    this(new FileStream(path), true, endianness, ReadOptions.DEFAULT);
  }

  /// Initializes a RIFF-family implementation and reads its chunk layout.
  /// Path storage is owned; supplied streams are borrowed unless ownership is explicitly requested.
  ///
  /// @param source     the open stream
  /// @param endianness the container byte order
  /// @throws java.io.UncheckedIOException if storage access fails
  protected RiffFile(IOStream source, Endianness endianness) {
    this(source, false, endianness, ReadOptions.DEFAULT);
  }

  /// Initializes a RIFF-family implementation and reads its chunk layout.
  /// Path storage is owned; supplied streams are borrowed unless ownership is explicitly requested.
  ///
  /// @param source     the open stream
  /// @param owned      whether ownership of the supplied stream transfers
  /// @param endianness the container byte order
  /// @param options    read options and metadata allocation limit
  /// @throws java.io.UncheckedIOException if storage access fails
  protected RiffFile(IOStream source, boolean owned, Endianness endianness, ReadOptions options) {
    super(source);
    this.source = source;
    this.owned = owned;
    bigEndian = Objects.requireNonNull(endianness) == Endianness.BigEndian;
    metadataLimit = Objects.requireNonNull(options).maxMetadataBytes();
    try {
      readChunks();
    } catch (RuntimeException | Error failure) {
      if (owned) {
        FailureSupport.closeOnFailure(source, failure);
      }
      throw failure;
    }
  }

  @Override
  public ByteVector readBlock(long count) {
    if (count > metadataLimit) {
      throw new MetadataLimitException("RIFF metadata exceeds configured read limit");
    }
    return super.readBlock(count);
  }

  protected final long riffSize() {
    return size;
  }

  protected final int chunkCount() {
    return chunks.size();
  }

  private Chunk chunk(int index) {
    if (index < 0 || index >= chunks.size()) {
      LOG.log(DEBUG, () -> "Chunk index " + index + " is out of range.");
      return null;
    }
    return chunks.get(index);
  }

  /// Returns a chunk payload byte offset.
  ///
  /// @param index the chunk index
  /// @return the chunk field
  protected final long chunkOffset(int index) {
    var c = chunk(index);
    return c == null ? 0 : c.offset;
  }

  /// Returns a 32-bit payload size.
  ///
  /// @param index the chunk index
  /// @return the chunk field
  protected final long chunkDataSize(int index) {
    return Math.min(chunkDataSize64(index), 0xffff_ffffL);
  }

  /// Returns the full payload byte length.
  ///
  /// @param index the chunk index
  /// @return the chunk field
  protected final long chunkDataSize64(int index) {
    var c = chunk(index);
    return c == null ? 0 : c.size;
  }

  /// Returns the chunk padding byte count.
  ///
  /// @param index the chunk index
  /// @return the chunk field
  protected final int chunkPadding(int index) {
    var c = chunk(index);
    return c == null ? 0 : c.padding;
  }

  /// Returns copied chunk-identifier bytes.
  ///
  /// @param index the chunk index
  /// @return the chunk field
  protected final ByteVector chunkName(int index) {
    var c = chunk(index);
    return c == null ? new ByteVector() : new ByteVector(c.name);
  }

  /// Reads chunk payload bytes through the shared cursor.
  ///
  /// @param index the chunk index
  /// @return payload bytes
  /// @throws java.io.UncheckedIOException if storage access fails
  /// @throws cz.skyba.taglib.MetadataReadException if the chunk exceeds the configured metadata limit
  protected final ByteVector chunkData(int index) {
    var c = chunk(index);
    if (c == null) {
      return new ByteVector();
    }
    long count = Math.min(c.size, 0xffff_ffffL);
    // Guard before seeking or allocating, including a short-read custom stream.
    if (count > metadataLimit) {
      throw new MetadataLimitException("RIFF chunk exceeds Java metadata representation");
    }
    seek(c.offset);
    return readBlock(count);
  }

  /// Writes a replacement chunk payload immediately, updating the file layout and container size.
  ///
  /// @param index the chunk index
  /// @param data  replacement payload bytes
  /// @throws java.io.UncheckedIOException if storage access fails
  protected final void setChunkData(int index, ByteVector data) {
    Objects.requireNonNull(data);
    var c = chunk(index);
    if (c == null) {
      return;
    }
    long originalSize = Math.addExact(c.size, c.padding);
    writeChunk(c.name, data, Math.subtractExact(c.offset, 8), Math.addExact(originalSize, 8));
    c.size = data.size();
    c.padding = data.size() % 2;
    // Upstream narrows the displacement to int before adjusting following chunks.
    int difference = (int) (c.size + c.padding - originalSize);
    for (int i = index + 1; i < chunks.size(); i++) {
      chunks.get(i).offset = Math.addExact(chunks.get(i).offset, difference);
    }
    updateGlobalSize();
  }

  /// Writes a replacement chunk payload immediately, updating the file layout and container size.
  ///
  /// @param name the four-byte chunk identifier
  /// @param data replacement payload bytes
  /// @throws java.io.UncheckedIOException if storage access fails
  protected final void setChunkData(ByteVector name, ByteVector data) {
    setChunkData(name, data, false);
  }

  /// Writes a replacement chunk payload immediately, updating the file layout and container size.
  ///
  /// @param name         the four-byte chunk identifier
  /// @param data         replacement payload bytes
  /// @param alwaysCreate whether to append a new chunk even if a matching name exists
  /// @throws java.io.UncheckedIOException if storage access fails
  protected final void setChunkData(ByteVector name, ByteVector data, boolean alwaysCreate) {
    Objects.requireNonNull(name);
    Objects.requireNonNull(data);
    if (chunks.isEmpty()) {
      LOG.log(DEBUG, "Cannot set chunk data: no valid chunks are available.");
      return;
    }
    if (alwaysCreate && !name.equals(new ByteVector("LIST"))) {
      LOG.log(DEBUG, "Only LIST chunks support unconditional creation.");
      return;
    }
    if (!alwaysCreate) {
      for (int i = 0; i < chunks.size(); i++) {
        if (chunks.get(i).name.equals(name)) {
          setChunkData(i, data);
          return;
        }
      }
    }
    var last = chunks.getLast();
    long offset = Math.addExact(Math.addExact(last.offset, last.size), last.padding);
    // Preflight the complete append, including its possible preceding pad edit.
    long aligned = (offset & 1) == 0 ? offset : last.padding == 1 ? offset - 1 : Math.addExact(offset, 1);
    long combined = Math.addExact(Math.addExact(name.size(), 4L), data.size() + data.size() % 2L);
    validateInsert(aligned, 0, combined, (offset & 1) == 0 ? 0 : last.padding == 1 ? -1 : 1);
    if ((offset & 1) != 0) {
      if (last.padding == 1) {
        last.padding = 0;
        offset--;
        removeBlock(offset, 1);
      } else {
        insert(new ByteVector(new byte[]{0}), offset, 0);
        last.padding = 1;
        offset++;
      }
    }
    writeChunk(name, data, offset, 0);
    chunks.add(new Chunk(name, Math.addExact(offset, 8), data.size(), data.size() % 2));
    updateGlobalSize();
  }

  /// Removes a chunk from storage immediately and updates the file layout.
  ///
  /// @param index the chunk index
  /// @throws java.io.UncheckedIOException if storage access fails
  protected final void removeChunk(int index) {
    var c = chunk(index);
    if (c == null) {
      return;
    }
    long count = Math.addExact(Math.addExact(c.size, c.padding), 8);
    removeBlock(Math.subtractExact(c.offset, 8), count);
    chunks.remove(index);
    for (int i = index; i < chunks.size(); i++) {
      chunks.get(i).offset = Math.subtractExact(chunks.get(i).offset, count);
    }
    updateGlobalSize();
  }

  /// Removes a chunk from storage immediately and updates the file layout.
  ///
  /// @param name the four-byte chunk identifier
  /// @throws java.io.UncheckedIOException if storage access fails
  protected final void removeChunk(ByteVector name) {
    Objects.requireNonNull(name);
    for (int i = chunks.size() - 1; i >= 0; i--) {
      if (chunks.get(i).name.equals(name)) {
        removeChunk(i);
      }
    }
  }

  /// Checks whether the chunk descriptors still fit the available storage.
  ///
  /// @return whether the file layout is writable
  protected final boolean cachedLayoutWritable() {
    if (chunks.isEmpty() || !(source instanceof ByteVectorStream)) {
      return true;
    }
    long length = length();
    if (sizeOffset < 0 || sizeOffset > length - 4) {
      return false;
    }
    for (var c : chunks) {
      if (c.offset < 8 || c.offset > length || c.size > length - c.offset
        || c.padding > length - c.offset - c.size) {
        return false;
      }
    }
    return true;
  }

  private void validateInsert(long offset, long replace, long bytes, long precedingChange) {
    if (offset < 0 || replace < 0) {
      throw new IllegalStateException("Unrepresentable RIFF edit span");
    }
    long current = Math.addExact(length(), precedingChange);
    long end = Math.addExact(offset, replace);
    long output = Math.addExact(offset, bytes);
    if (source instanceof ByteVectorStream) {
      if (bytes > replace && end > current || Math.max(output, Math.addExact(current, bytes - replace)) > Integer.MAX_VALUE) {
        throw new IllegalStateException("Unsafe RIFF memory edit");
      }
    }
  }

  private void writeChunk(ByteVector name, ByteVector data, long offset, long replace) {
    long count = Math.addExact(Math.addExact(name.size(), 4L), data.size() + data.size() % 2L);
    if (count > Integer.MAX_VALUE) {
      throw new IllegalStateException("RIFF render exceeds Java representation");
    }
    validateInsert(offset, replace, count, 0);
    var combined = new ByteVector(name);
    combined.append(ByteVector.fromUnsignedInt(data.size(), bigEndian));
    combined.append(data);
    if ((data.size() & 1) != 0) {
      combined.resize(combined.size() + 1);
    }
    insert(combined, offset, replace);
  }

  private void updateGlobalSize() {
    if (chunks.isEmpty()) {
      return;
    }
    var first = chunks.getFirst();
    var last = chunks.getLast();
    long total = Math.addExact(Math.subtractExact(Math.addExact(Math.addExact(last.offset, last.size), last.padding), first.offset),
      12);
    size = longForm ? 0xffff_ffffL : total & 0xffff_ffffL;
    insert(ByteVector.fromUnsignedInt(size, bigEndian), sizeOffset, 4);
    if (longForm && ds64Offset > 0) {
      insert(ByteVector.fromUnsignedLong(total, bigEndian), ds64Offset, 8);
    }
  }

  private static boolean validName(ByteVector name) {
    if (name.size() != 4) {
      return false;
    }
    for (byte b : name.data()) {
      if ((b & 255) < 32 || (b & 255) > 127) {
        return false;
      }
    }
    return true;
  }

  private void readChunks() {
    long offset = tell();
    if (!bigEndian) {
      seek(offset);
      var magic = readBlock(4);
      longForm = magic.equals(new ByteVector("RF64")) || magic.equals(new ByteVector("BW64"));
    }
    offset = Math.addExact(offset, 4);
    sizeOffset = offset;
    seek(offset);
    size = readBlock(4).toUnsignedInt(bigEndian);
    offset = Math.addExact(offset, 8);
    long fileLength = length();
    while (offset >= 0 && offset <= fileLength && fileLength - offset >= 8) {
      if (chunks.size() >= MAX_CHUNKS) {
        LOG.log(DEBUG, "Maximum chunk count exceeded.");
        throw new InvalidAudioFileException("RIFF maximum chunk count exceeded: " + name());
      }
      seek(offset);
      var name = readBlock(4);
      long declared = readBlock(4).toUnsignedInt(bigEndian);
      if (!validName(name)) {
        LOG.log(DEBUG, () -> "Chunk '" + name.decodeTerminatedText(ISO_8859_1) + "' has an invalid ID.");
        break;
      }
      if (longForm && name.equals(new ByteVector("ds64")) && chunks.isEmpty() && declared >= 28) {
        seek(offset + 8);
        var ds64 = readBlock(28);
        if (ds64.size() == 28) {
          ds64Offset = offset + 8;
          dataSize64 = ds64.toUnsignedLong(8, bigEndian);
        }
      }
      long available = fileLength - offset - 8, chunkSize = declared;
      if (longForm && name.equals(new ByteVector("data")) && declared == 0xffff_ffffL && dataSize64 != 0) {
        chunkSize = Long.compareUnsigned(dataSize64, available) > 0 ? available : dataSize64;
      }
      if (chunkSize > available) {
        LOG.log(DEBUG, () -> "Chunk '" + name.decodeTerminatedText(ISO_8859_1) + "' is truncated; clamping size to available bytes.");
        chunkSize = length() - offset - 8;
      }
      var c = new Chunk(name, offset + 8, chunkSize, 0);
      offset = Math.addExact(c.offset, c.size);
      if ((offset & 1) != 0) {
        seek(offset);
        var one = readBlock(1);
        if (one.size() == 1 && (one.data()[0] == 0 || validName(readBlock(4)))) {
          c.padding = 1;
          offset++;
        }
      }
      chunks.add(c);
    }
  }

  @Override
  public void close() {
    if (owned) source.close();
  }
}
