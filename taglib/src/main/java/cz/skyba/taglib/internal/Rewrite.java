// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal;

import cz.skyba.taglib.toolkit.ByteVector;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/// File edits validated as a complete plan before storage is modified.
public final class Rewrite {
  private Rewrite() {
  }

  private static final int BUFFER_SIZE = 64 * 1024;

  public record Edit(long offset, long removeLength, byte[] insertion) {
    public Edit {
      if (offset < 0 || removeLength < 0) {
        throw new IllegalArgumentException();
      }
      insertion = insertion.clone();
    }
  }

  public static void replace(
    BinaryFile file, long offset, long removeLength, byte[] insertion)
    throws IOException {
    replace(file, List.of(new Edit(offset, removeLength, insertion)));
  }

  /// Runs the same edit plan against a borrowed native IOStream without copying its audio.
  public static void replace(BinaryFile file, List<Edit> edits)
    throws IOException {
    if (file.path() != null) {
      replace(file.path(), edits);
      return;
    }
    var stream = file.stream();
    if (!stream.isOpen() || stream.readOnly()) {
      throw new IOException("Stream is not writable");
    }
    long size = file.size();
    var sorted = validateAndSort(edits, size);
    validateMaximumSize(sorted, size);
    for (var edit : sorted.reversed()) {
      if (edit.insertion().length == 0) {
        stream.removeBlock(edit.offset(), edit.removeLength());
      } else {
        stream.insert(
          new ByteVector(edit.insertion()),
          edit.offset(),
          edit.removeLength());
      }
    }
  }

  /// Executes native save order, translating each original offset after earlier edits.
  public static void replaceNative(BinaryFile file, List<Edit> edits)
    throws IOException {
    if (file.path() != null) {
      replace(file.path(), edits);
      return;
    }
    var stream = file.stream();
    if (!stream.isOpen() || stream.readOnly()) {
      throw new IOException("Stream is not writable");
    }
    var sorted = validateAndSort(edits, file.size());
    validateMaximumSize(sorted, file.size());
    var deltas = new OffsetDeltas(sorted);
    for (var edit : edits) {
      long offset = Math.addExact(edit.offset(), deltas.before(edit.offset()));
      if (edit.insertion().length == 0) {
        stream.removeBlock(offset, edit.removeLength());
      } else {
        stream.insert(
          new ByteVector(edit.insertion()), offset, edit.removeLength());
      }
      deltas.add(edit.offset(), edit.insertion().length - edit.removeLength());
    }
  }

  private static final class OffsetDeltas {
    private final long[] offsets, sums;

    OffsetDeltas(List<Edit> sorted) {
      long[] values = new long[sorted.size()];
      int count = 0;
      for (Edit edit : sorted) {
        if (count == 0 || values[count - 1] != edit.offset()) values[count++] = edit.offset();
      }
      offsets = Arrays.copyOf(values, count);
      sums = new long[count + 1];
    }

    long before(long offset) {
      int index = Arrays.binarySearch(offsets, offset);
      long result = 0;
      for (int i = index; i > 0; i -= i & -i) result = Math.addExact(result, sums[i]);
      return result;
    }

    void add(long offset, long delta) {
      for (int i = Arrays.binarySearch(offsets, offset) + 1; i < sums.length; i += i & -i) {
        sums[i] = Math.addExact(sums[i], delta);
      }
    }
  }

  public static void replace(
    Path path, long offset, long removeLength, byte[] insertion)
    throws IOException {
    replace(path, List.of(new Edit(offset, removeLength, insertion)));
  }

  public static void replace(Path path, List<Edit> edits) throws IOException {
    Path real = path.toRealPath();
    WriteGuard.check(real);
    var before = Files.readAttributes(real, BasicFileAttributes.class);
    var sorted = validateAndSort(edits, before.size());
    // The descending in-place pass may temporarily grow before another edit shrinks the file.
    validateMaximumSize(sorted, before.size());
    try (var in = FileChannel.open(real, StandardOpenOption.READ)) {
      var effective = new ArrayList<Edit>();
      for (var edit : sorted) {
        Edit trimmed = trim(in, edit);
        if (trimmed.removeLength() != 0 || trimmed.insertion().length != 0) {
          effective.add(trimmed);
        }
      }
      sorted = effective;
    }
    checkUnchanged(real, before);
    if (sorted.isEmpty()) {
      return;
    }
    inPlace(real, sorted, before);
  }

  /// Remove identical prefixes/suffixes, making no-op saves and padded updates metadata-only I/O.
  private static Edit trim(FileChannel in, Edit edit) throws IOException {
    byte[] bytes = edit.insertion();
    int shared = (int) Math.min(edit.removeLength(), bytes.length);
    var buffer = ByteBuffer.allocate(Math.min(BUFFER_SIZE, shared));
    int prefix = 0;
    while (prefix < shared) {
      int count = Math.min(buffer.capacity(), shared - prefix);
      read(in, buffer, edit.offset() + prefix, count);
      int i = 0;
      while (i < count && buffer.array()[i] == bytes[prefix + i]) {
        i++;
      }
      prefix += i;
      if (i != count) {
        break;
      }
    }
    int suffix = 0;
    while (suffix < shared - prefix) {
      int count = Math.min(buffer.capacity(), shared - prefix - suffix);
      read(in, buffer, edit.offset() + edit.removeLength() - suffix - count, count);
      int i = 0;
      while (i < count && buffer.array()[count - 1 - i] == bytes[bytes.length - suffix - 1 - i]) {
        i++;
      }
      suffix += i;
      if (i != count) {
        break;
      }
    }
    if (prefix == 0 && suffix == 0) {
      return edit;
    }
    return new Edit(
      edit.offset() + prefix,
      edit.removeLength() - prefix - suffix,
      Arrays.copyOfRange(bytes, prefix, bytes.length - suffix));
  }

  private static ArrayList<Edit> validateAndSort(List<Edit> edits, long fileSize)
    throws IOException {
    var sorted = new ArrayList<>(edits);
    sorted.sort(Comparator.comparingLong(Edit::offset));
    long end = 0;
    for (var edit : sorted) {
      if (edit.offset() < end
        || edit.offset() > fileSize
        || edit.removeLength() > fileSize - edit.offset()) {
        throw new IOException("Invalid or overlapping metadata edit");
      }
      end = edit.offset() + edit.removeLength();
    }
    return sorted;
  }

  private static void validateMaximumSize(List<Edit> edits, long fileSize) throws IOException {
    long maximumSize = fileSize;
    for (var edit : edits) {
      try {
        maximumSize =
          Math.addExact(maximumSize, Math.max(0, edit.insertion().length - edit.removeLength()));
      } catch (ArithmeticException e) {
        throw new IOException("Metadata edit would exceed the maximum file size", e);
      }
    }
  }

  private static void inPlace(Path path, List<Edit> edits, BasicFileAttributes before)
    throws IOException {
    // Validate all plans and source stamps before the first write. The public API documents that
    // subsequent device errors cannot be rolled back, just as for native TagLib's FileStream.
    try (var channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      checkUnchanged(path, before);
      WriteGuard.check(path);
      ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
      long size = before.size();
      // Original offsets remain valid when applying disjoint edits from the end toward the start.
      for (var edit : edits.reversed()) {
        long tail = edit.offset() + edit.removeLength();
        long delta = edit.insertion().length - edit.removeLength();
        if (delta > 0) {
          long remaining = size - tail;
          while (remaining > 0) {
            int count = (int) Math.min(buffer.capacity(), remaining);
            long from = tail + remaining - count;
            read(channel, buffer, from, count);
            write(channel, buffer, from + delta);
            remaining -= count;
          }
        } else if (delta < 0) {
          long from = tail;
          while (from < size) {
            int count = (int) Math.min(buffer.capacity(), size - from);
            read(channel, buffer, from, count);
            write(channel, buffer, from + delta);
            from += count;
          }
        }
        write(channel, ByteBuffer.wrap(edit.insertion()), edit.offset());
        size += delta;
        if (delta < 0) {
          channel.truncate(size);
        }
      }
    }
  }

  private static void checkUnchanged(Path path, BasicFileAttributes before) throws IOException {
    var now = Files.readAttributes(path, BasicFileAttributes.class);
    if (now.size() != before.size()
      || !now.lastModifiedTime().equals(before.lastModifiedTime())
      || !Objects.equals(now.fileKey(), before.fileKey())) {
      throw new IOException("File changed during metadata update");
    }
  }

  private static void read(FileChannel channel, ByteBuffer buffer, long at, int length)
    throws IOException {
    buffer.clear().limit(length);
    while (buffer.hasRemaining()) {
      int count = channel.read(buffer, at + buffer.position());
      if (count < 0) {
        throw new EOFException();
      }
      if (count == 0) {
        throw new IOException("No progress reading metadata update");
      }
    }
    buffer.flip();
  }

  private static void write(FileChannel channel, ByteBuffer buffer, long at) throws IOException {
    while (buffer.hasRemaining()) {
      int count = channel.write(buffer, at + buffer.position());
      if (count == 0) {
        throw new IOException("No progress writing metadata update");
      }
    }
  }
}
