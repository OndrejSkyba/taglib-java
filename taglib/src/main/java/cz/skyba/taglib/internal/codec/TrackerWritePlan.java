// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.codec;

import cz.skyba.taglib.toolkit.ByteVector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.LongConsumer;

/// Staged tracker-file edits with validated extents and read access to pending changes.
final class TrackerWritePlan {
  @FunctionalInterface
  interface SourceReader {
    ByteVector read(long position, long count);
  }

  private sealed interface Change permits Write, Truncate {
  }

  private record Write(long offset, byte[] bytes) implements Change {
  }

  private record Truncate(long extent) implements Change {
  }

  private final SourceReader source;
  private final long originalSize;
  private final long maximumWriteExtent;
  private final List<Change> changes = new ArrayList<>();
  private long position;
  private long size;
  private boolean truncated;

  TrackerWritePlan(
    SourceReader source, long originalSize, long position,
    long maximumWriteExtent
  ) {
    if (originalSize < 0 || position < 0) {
      throw new IllegalArgumentException("Negative stream extent");
    }
    this.source = source;
    this.originalSize = this.size = originalSize;
    this.position = position;
    this.maximumWriteExtent = maximumWriteExtent;
  }

  long tell() {
    return position;
  }

  long length() {
    return size;
  }

  void seek(long offset) {
    if (offset < 0) {
      throw new IllegalArgumentException("Negative tracker offset");
    }
    position = offset;
  }

  void write(ByteVector value) {
    byte[] bytes = value.toByteArray();
    long end = Math.addExact(position, bytes.length);
    if (end > maximumWriteExtent) {
      throw new IllegalArgumentException("Tracker write exceeds stream range");
    }
    changes.add(new Write(position, bytes));
    size = Math.max(size, end);
    position = end;
  }

  void truncate(long extent) {
    if (extent < 0) {
      throw new IllegalArgumentException("Negative tracker extent");
    }
    if (extent > maximumWriteExtent) {
      throw new IllegalArgumentException("Tracker truncate exceeds stream range");
    }
    changes.add(new Truncate(extent));
    size = extent;
    truncated = true;
  }

  ByteVector read(long count) {
    if (count < 0) {
      throw new IllegalArgumentException("Negative block length");
    }
    if (count == 0) {
      return new ByteVector();
    }
    long at = position;
    byte[] original = source.read(position, count).data();
    long available = Math.clamp(originalSize - at, 0L, count);
    long virtualAvailable = Math.clamp(size - at, 0L, count);
    // A source short read remains short. A newly extended gap is zero-filled.
    int wanted = Math.toIntExact(original.length < available ? original.length
      : Math.max(original.length, virtualAvailable));
    if (truncated) {
      wanted = (int) Math.min(wanted, virtualAvailable);
    }
    byte[] bytes = Arrays.copyOf(original, wanted);
    overlay(bytes, at);
    position = Math.addExact(position, bytes.length);
    return new ByteVector(bytes);
  }

  private void overlay(byte[] bytes, long at) {
    for (Change change : changes) {
      switch (change) {
        case Write(long offset, byte[] written) -> {
          long from = Math.max(at, offset), to = Math.min(at + bytes.length, offset + written.length);
          if (to > from) System.arraycopy(written, (int) (from - offset), bytes, (int) (from - at), (int) (to - from));
        }
        case Truncate(long extent) ->
          Arrays.fill(bytes, Math.clamp(extent - at, 0, bytes.length), bytes.length, (byte) 0);
      }
    }
  }

  /// Replay the validated edits, without reading a stateful caller stream a second time.
  void apply(BiConsumer<Long, ByteVector> writer, LongConsumer truncater, LongConsumer seeker) {
    for (Change change : changes) {
      switch (change) {
        case Write(long offset, byte[] bytes) -> writer.accept(offset, new ByteVector(bytes));
        case Truncate(long extent) -> truncater.accept(extent);
      }
    }
    seeker.accept(position);
  }
}
