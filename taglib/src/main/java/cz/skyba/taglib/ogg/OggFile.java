// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.ogg;

import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.internal.xiph.OggPacketWrite;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.IOStream;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/// A base for Ogg logical-stream packet inspection and staged packet replacement.
/// Path construction owns storage; stream construction borrows it. Packet indexing is lazy
/// and is invalidated by external writes. Saving repaginates modified groups and can reset
/// granule positions and end-of-stream flags on replaced pages. Replacing a packet spanning
/// interleaved logical streams replaces its physical span, so this API is not a general
/// multiplexed-stream editor. Materialized packet and page groups must fit a Java byte array.
public abstract class OggFile extends FileBase {
  private static final long UNLIMITED = 0xffff_ffffL;
  private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;
  private static final ByteVector CAPTURE = new ByteVector("OggS");

  private final List<Page> pages = new ArrayList<>();
  private final TreeMap<Integer, ByteVector> dirtyPackets = new TreeMap<>();
  private PageHeader firstHeader, lastHeader;
  private long currentPageOffset = -1, serial;
  private boolean serialSet;
  private boolean packetLimitExceeded;

  // Page/Header take an IOStream, while FileBase intentionally keeps its owned/borrowed stream
  // private. This view shares exactly the FileBase cursor without taking ownership or reopening.
  private final IOStream pageStream = ownerStream();

  /// Initializes an Ogg format implementation with owned path storage.
  /// The subclass supplies codec tags and audio properties.
  ///
  /// @param path the input file
  protected OggFile(Path path) {
    super(path);
  }

  /// Initializes an Ogg format implementation over a borrowed stream.
  /// The subclass supplies codec tags and audio properties.
  ///
  /// @param stream the open, seekable input stream
  protected OggFile(IOStream stream) {
    super(stream);
  }

  final IOStream pageStream() {
    return pageStream;
  }

  /// Returns an independent copy of a packet from the selected logical stream.
  /// Reading can move the shared cursor and populate the page index.
  ///
  /// @param index the nonnegative packet index
  /// @return packet bytes, or an empty vector for an absent packet
  /// @throws IllegalArgumentException     if the packet index is negative
  /// @throws java.io.UncheckedIOException if storage access fails
  public final ByteVector packet(int index) {
    return packet(index, UNLIMITED);
  }

  /// Returns an independent copy of a packet from the selected logical stream.
  /// Reading can move the shared cursor and populate the page index.
  ///
  /// @param index   the nonnegative packet index
  /// @param maxSize the maximum packet bytes to materialize
  /// @return packet bytes, or an empty vector for an absent packet or budget rejection
  /// @throws IllegalArgumentException     if the packet index is negative
  /// @throws java.io.UncheckedIOException if storage access fails
  protected final ByteVector packet(int index, long maxSize) {
    packetLimitExceeded = false;
    checkIndex(index);
    if (maxSize < 0 || maxSize > UNLIMITED) {
      throw new IllegalArgumentException("Packet maximum must fit an unsigned 32-bit value");
    }
    ByteVector dirty = dirtyPackets.get(index);
    if (dirty != null) {
      packetLimitExceeded = dirty.size() > maxSize;
      return packetLimitExceeded ? new ByteVector() : new ByteVector(dirty);
    }
    if (!readPages(index, maxSize)) {
      return new ByteVector();
    }
    int first = firstPageFor(index);
    var parts = new ArrayList<ByteVector>();
    Page page = pages.get(first);
    ByteVector part = page.packets().get(index - page.firstPacketIndex());
    parts.add(part);
    long size = part.size();
    while (nextPacketIndex(page) <= index) {
      page = pages.get(++first);
      part = page.packets().getFirst();
      if (part.size() > maxSize - size) {
        packetLimitExceeded = true;
        return new ByteVector();
      }
      size += part.size();
      checkArraySize(size);
      parts.add(part);
    }
    // Allocate once instead of repeatedly copying a packet continued over many pages.
    return new ByteVector(join(parts, size));
  }

  /// Reports whether the last bounded packet read was rejected by its byte budget.
  /// This distinguishes rejection from an absent or genuinely empty packet.
  ///
  /// @return whether the read budget was exceeded
  protected final boolean packetLimitExceeded() {
    return packetLimitExceeded;
  }

  /// Stages an independent copy of replacement packet bytes. Unknown packet indexes are ignored.
  /// Call [#save()] to write the replacement.
  ///
  /// @param index  the nonnegative packet index
  /// @param packet replacement bytes
  /// @throws IllegalArgumentException     if the index is negative
  /// @throws java.io.UncheckedIOException if locating the packet fails
  public final void setPacket(int index, ByteVector packet) {
    checkIndex(index);
    Objects.requireNonNull(packet);
    if (readPages(index, UNLIMITED)) {
      dirtyPackets.put(index, new ByteVector(packet));
    }
  }

  /// First physical page header, independently of selected logical stream, or null.
  public final PageHeader firstPageHeader() {
    if (firstHeader == null) {
      long offset = find(CAPTURE);
      if (offset < 0) {
        return null;
      }
      firstHeader = new PageHeader(pageStream, offset);
    }
    return firstHeader.isValid() ? firstHeader : null;
  }

  /// Last physical capture's header, or null if that capture does not hold a valid header.
  public final PageHeader lastPageHeader() {
    if (lastHeader == null) {
      long offset = rfind(CAPTURE);
      if (offset < 0) {
        return null;
      }
      lastHeader = new PageHeader(pageStream, offset);
    }
    return lastHeader.isValid() ? lastHeader : null;
  }

  /// Writes dirty packets in index order and clears them.
  @Override
  public void save() {
    requireWritable();
    for (var entry : dirtyPackets.entrySet()) {
      writePacket(entry.getKey(), entry.getValue());
    }
    dirtyPackets.clear();
  }

  /// Selects the first BOS page whose first packet starts with magic. Call before requesting
  /// packets. Failure retains the normal fallback to the first logical stream.
  protected final boolean selectStream(ByteVector magic) {
    Objects.requireNonNull(magic);
    long offset = find(CAPTURE);
    if (offset < 0) {
      return false;
    }
    while (true) {
      Page page = new Page(pageStream, offset);
      if (!page.header().isValid() || !page.header().firstPageOfStream()) {
        return false;
      }
      List<ByteVector> packets = page.packets();
      if (!packets.isEmpty() && packets.getFirst().startsWith(magic)) {
        serial = page.header().streamSerialNumber();
        serialSet = true;
        return true;
      }
      offset = Math.addExact(offset, page.size());
    }
  }

  private boolean readPages(int index, long maxSize) {
    boolean limited = maxSize != UNLIMITED;
    long packetSize = 0;
    if (limited) {
      for (Page page : pages) {
        long partSize = packetPartSize(page, index);
        if (partSize < 0 || partSize > maxSize - packetSize) {
          packetLimitExceeded = partSize > maxSize - packetSize;
          return false;
        }
        packetSize += partSize;
      }
    }
    while (true) {
      if (!pages.isEmpty()) {
        Page last = pages.getLast();
        if (nextPacketIndex(last) > index) {
          return true;
        }
        if (last.header().lastPageOfStream()) {
          return false;
        }
      }
      if (currentPageOffset < 0) {
        currentPageOffset = find(CAPTURE);
        if (currentPageOffset < 0) {
          return false;
        }
      }
      Page next;
      do {
        next = new Page(pageStream, currentPageOffset);
        if (!next.header().isValid()) {
          return false;
        }
        currentPageOffset = Math.addExact(currentPageOffset, next.size());
        if (!serialSet) {
          serial = next.header().streamSerialNumber();
          serialSet = true;
        }
      } while (next.header().streamSerialNumber() != serial);
      next.setFirstPacketIndex(pages.isEmpty() ? 0 : nextPacketIndex(pages.getLast()));
      if (limited) {
        long partSize = packetPartSize(next, index);
        // Deliberately keep the advanced physical cursor when a newly read page exceeds
        // the limit. The native implementation discards this page without rewinding.
        if (partSize < 0 || partSize > maxSize - packetSize) {
          packetLimitExceeded = partSize > maxSize - packetSize;
          return false;
        }
        packetSize += partSize;
      }
      pages.add(next);
    }
  }

  private static long packetPartSize(Page page, int index) {
    if (page.containsPacket(index) == Page.DoesNotContainPacket) {
      return 0;
    }
    List<ByteVector> packets = page.packets();
    int partIndex = index - page.firstPacketIndex();
    return partIndex < packets.size() ? packets.get(partIndex).size() : -1;
  }

  private int firstPageFor(int index) {
    int first = 0;
    while (pages.get(first).containsPacket(index) == Page.DoesNotContainPacket) {
      first++;
    }
    return first;
  }

  /// Internal subclass view of an already indexed complete packet's physical pages.
  protected final List<Page> cachedPacketPages(int index) {
    if (pages.isEmpty() || nextPacketIndex(pages.getLast()) <= index) {
      return List.of();
    }
    int first = firstPageFor(index), last = first;
    while (nextPacketIndex(pages.get(last)) <= index) {
      last++;
    }
    return List.copyOf(pages.subList(first, last + 1));
  }

  private static byte[] join(List<ByteVector> parts, long size) {
    byte[] data = new byte[Math.toIntExact(size)];
    int position = 0;
    for (ByteVector part : parts) {
      System.arraycopy(part.data(), 0, data, position, part.size());
      position += part.size();
    }
    return data;
  }

  private static int nextPacketIndex(Page page) {
    return Math.addExact(page.firstPacketIndex(), page.packetCount())
      - (page.header().lastPacketCompleted() ? 0 : 1);
  }

  private void writePacket(int index, ByteVector packet) {
    if (!readPages(index, UNLIMITED)) {
      return;
    }
    int pageIndex = firstPageFor(index);
    Page first = pages.get(pageIndex), last = first;
    while (nextPacketIndex(last) <= index) {
      last = pages.get(++pageIndex);
    }
    List<Page> replacement =
      OggPacketWrite.paginate(first, last, index - first.firstPacketIndex(), packet);
    List<ByteVector> rendered = replacement.stream().map(Page::render).toList();
    long size = 0;
    for (ByteVector value : rendered) {
      size += value.size();
      checkArraySize(size);
    }
    byte[] data = join(rendered, size);
    long offset = first.fileOffset();
    long originalLength = Math.subtractExact(Math.addExact(last.fileOffset(), last.size()), offset);
    insert(new ByteVector(data), offset, originalLength);
    int delta = replacement.getLast().pageSequenceNumber() - last.pageSequenceNumber();
    if (delta != 0) {
      long nextOffset = Math.addExact(offset, data.length);
      while (true) {
        Page page = new Page(pageStream, nextOffset);
        if (!page.header().isValid()) {
          break;
        }
        page.setPageSequenceNumber(page.pageSequenceNumber() + delta);
        ByteVector pageData = page.render();
        seek(nextOffset + 18);
        writeBlock(pageData.mid(18, 8));
        if (page.header().lastPageOfStream()) {
          break;
        }
        nextOffset = Math.addExact(nextOffset, page.size());
      }
    }
    pages.clear();
    currentPageOffset = -1;
    // The separate first/last header caches and logical-stream selection are retained natively.
  }

  private static void checkIndex(int index) {
    if (index < 0) {
      throw new IllegalArgumentException("Negative packet index");
    }
  }

  private static void checkArraySize(long size) {
    if (size > MAX_ARRAY_SIZE) {
      throw new IllegalStateException("Ogg packet/page group exceeds Java array size");
    }
  }
}
