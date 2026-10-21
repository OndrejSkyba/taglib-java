// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.mp4;

import cz.skyba.taglib.internal.FileOwnerStream;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.IOStream;

/// QuickTime sample-table decoding with native cursor and unsigned arithmetic rules.
final class ChapterTables {
  private ChapterTables() {
  }

  private static final long[] EMPTY = new long[0];

  record Sizes(long defaultSize, long count, long[] sizes) {
  }

  record Table(long[] first, long[] second, long[] third) {
    int size() {
      return first.length;
    }
  }

  static Table table(IOStream file, Atom track, String name, int width) {
    Atom box = track.find("mdia", "minf", "stbl", name);
    if (box == null || box.length() < 12) return new Table(EMPTY, EMPTY, EMPTY);
    file.seek(box.offset() + 12);
    ByteVector data = file.readBlock(box.length() - 12);
    if (data.size() < 4) return new Table(EMPTY, EMPTY, EMPTY);
    int count = (int) Math.min(data.toUnsignedInt(), (data.size() - 4) / width);
    long[] first = new long[count], second = width >= 8 ? new long[count] : EMPTY,
      third = width >= 12 ? new long[count] : EMPTY;
    for (int i = 0, pos = 4; i < count; i++, pos += width) {
      first[i] = data.toUnsignedInt(pos, true);
      if (width >= 8) second[i] = data.toUnsignedInt(pos + 4, true);
      if (width >= 12) third[i] = data.toUnsignedInt(pos + 8, true);
    }
    return new Table(first, second, third);
  }

  static Sizes sizes(IOStream file, Atom track) {
    Atom box = track.find("mdia", "minf", "stbl", "stsz");
    if (box == null || box.length() < 12) return new Sizes(0, 0, EMPTY);
    file.seek(box.offset() + 12);
    ByteVector data = file.readBlock(box.length() - 12);
    if (data.size() < 8) return new Sizes(0, 0, EMPTY);
    long size = data.toUnsignedInt(), count = data.toUnsignedInt(4, true);
    int n = size == 0 ? (int) Math.min(count, (data.size() - 8) / 4) : 0;
    long[] values = new long[n];
    for (int i = 0; i < n; i++) values[i] = data.toUnsignedInt(8 + i * 4, true);
    return new Sizes(size, count, values);
  }

  static long[] sampleOffsets(IOStream file, Atom track, Sizes sizes) {
    var chunks = table(file, track, "stco", 4);
    if (chunks.size() == 0 || sizes.count == 0 || sizes.count > file.length() / 4) return EMPTY;
    if (sizes.count > Integer.MAX_VALUE / 4)
      throw new IllegalArgumentException("QuickTime sample offsets exceed Java array representation");
    var mappings = table(file, track, "stsc", 12);
    if (mappings.size() == 0) mappings = new Table(new long[]{1}, new long[]{1}, new long[]{1});
    if (file instanceof FileOwnerStream view && view.owner() instanceof Mp4File owner) {
      owner.checkMetadataBytes(Math.multiplyExact(sizes.count, 64L));
    }
    int total = 0, map = 0;
    for (int chunk = 0; chunk < chunks.size(); chunk++) {
      while (map + 1 < mappings.size() && mappings.first[map + 1] <= chunk + 1L) map++;
      total += (int) Math.min(mappings.second[map], sizes.count - total);
    }
    long[] offsets = new long[total];
    map = 0;
    int index = 0;
    for (int chunk = 0; chunk < chunks.size(); chunk++) {
      while (map + 1 < mappings.size() && mappings.first[map + 1] <= chunk + 1L) map++;
      long count = Math.min(mappings.second[map], sizes.count - index), offset = 0;
      for (long i = 0; i < count; i++) {
        offsets[index] = (chunks.first[chunk] + offset) & 0xffff_ffffL;
        long size = sizes.defaultSize;
        if (size == 0 && index < sizes.sizes.length) size = sizes.sizes[index];
        offset = (offset + size) & 0xffff_ffffL;
        index++;
      }
    }
    return offsets;
  }
}
