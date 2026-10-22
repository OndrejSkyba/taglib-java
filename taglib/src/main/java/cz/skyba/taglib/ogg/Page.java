// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.ogg;

import cz.skyba.taglib.internal.Binary;
import cz.skyba.taglib.internal.ByteVectorOutput;
import cz.skyba.taglib.internal.xiph.OggPages;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.FileStream;
import cz.skyba.taglib.toolkit.IOStream;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// An Ogg page with a live header and packet fragments.
/// Stream-backed instances borrow storage and read packets on demand. Path-backed instances
/// reopen the path for later reads without retaining a handle. Constructed packet pages own
/// copied byte values. Rendering recomputes the page checksum without writing storage.
public class Page {
  public static final int DoesNotContainPacket = 0, CompletePacket = 1, BeginsWithPacket = 2, EndsWithPacket = 4;

  /// Controls whether packet groups stay on one page or are divided into multiple pages.
  public enum PaginationStrategy {SinglePagePerGroup, Repaginate}

  private final Path path;
  private final IOStream stream;
  private final long offset;
  private final PageHeader header;
  private final List<ByteVector> storedPackets;
  private int firstPacketIndex = -1;

  /// Reads a page header from borrowed storage and defers packet reads.
  ///
  /// @param file       the borrowed Ogg file
  /// @param pageOffset the absolute page byte offset
  /// @throws java.io.UncheckedIOException if storage access fails
  public Page(OggFile file, long pageOffset) {
    this(file == null ? null : file.pageStream(), pageOffset);
  }

  /// Reads a page header from borrowed storage and defers packet reads.
  ///
  /// @param stream     the borrowed stream
  /// @param pageOffset the absolute page byte offset
  /// @throws java.io.UncheckedIOException if storage access fails
  public Page(IOStream stream, long pageOffset) {
    this.stream = stream;
    path = null;
    offset = pageOffset;
    header = new PageHeader(stream, pageOffset);
    storedPackets = List.of();
  }

  /// Reads a page header from a path and reopens that path for subsequent packet reads.
  /// No file handle remains open.
  ///
  /// @param path       the input file
  /// @param pageOffset the absolute page byte offset
  /// @throws java.io.UncheckedIOException if storage access fails
  public Page(Path path, long pageOffset) {
    this.path = Objects.requireNonNull(path);
    stream = null;
    offset = pageOffset;
    header = new PageHeader(path, pageOffset);
    storedPackets = List.of();
  }

  /// Creates a page with independently copied packet fragments.
  ///
  /// @param packets   the packet fragments to copy
  /// @param serial    the logical stream serial number, using its low 32 bits
  /// @param sequence  the page sequence number
  /// @param continued whether the first packet continues from a preceding page
  /// @param completed whether the final packet ends on this page
  /// @param last      whether this is the last page of the logical stream
  protected Page(List<ByteVector> packets, long serial, int sequence, boolean continued, boolean completed, boolean last) {
    this(packets, serial, sequence, continued, completed, last, false);
  }

  private Page(List<ByteVector> packets, long serial, int sequence, boolean continued, boolean completed, boolean last, boolean owned) {
    path = null;
    stream = null;
    offset = -1;
    header = new PageHeader();
    header.setFirstPageOfStream(sequence == 0 && !continued);
    header.setLastPageOfStream(last);
    header.setFirstPacketContinued(continued);
    header.setLastPacketCompleted(completed);
    header.setStreamSerialNumber(serial);
    header.setPageSequenceNumber(sequence);
    storedPackets = owned ? List.copyOf(packets) : packets.stream().map(ByteVector::new).toList();
    header.setPacketSizes(storedPackets.stream().map(ByteVector::size).toList());
    if (!completed && packets.size() <= 1) {
      header.setAbsoluteGranularPosition(-1);
    }
  }

  /// Creates a page with independently copied packet fragments.
  ///
  /// @param packets  the packet fragments to copy
  /// @param serial   the logical stream serial number, using its low 32 bits
  /// @param sequence the page sequence number
  protected Page(List<ByteVector> packets, long serial, int sequence) {
    this(packets, serial, sequence, false, true, false);
  }

  /// Creates a page with independently copied packet fragments.
  ///
  /// @param packets   the packet fragments to copy
  /// @param serial    the logical stream serial number, using its low 32 bits
  /// @param sequence  the page sequence number
  /// @param continued whether the first packet continues from a preceding page
  protected Page(List<ByteVector> packets, long serial, int sequence, boolean continued) {
    this(packets, serial, sequence, continued, true, false);
  }

  /// Creates a page with independently copied packet fragments.
  ///
  /// @param packets   the packet fragments to copy
  /// @param serial    the logical stream serial number, using its low 32 bits
  /// @param sequence  the page sequence number
  /// @param continued whether the first packet continues from a preceding page
  /// @param completed whether the final packet ends on this page
  protected Page(List<ByteVector> packets, long serial, int sequence, boolean continued, boolean completed) {
    this(packets, serial, sequence, continued, completed, false);
  }

  public final long fileOffset() {
    return offset;
  }

  public final PageHeader header() {
    return header;
  }

  public final int pageSequenceNumber() {
    return header.pageSequenceNumber();
  }

  public final void setPageSequenceNumber(int value) {
    header.setPageSequenceNumber(value);
  }

  public final int firstPacketIndex() {
    return firstPacketIndex;
  }

  public final void setFirstPacketIndex(int value) {
    firstPacketIndex = value;
  }

  public final int packetCount() {
    return header.packetSizes().size();
  }

  public final int size() {
    return header.size() + header.dataSize();
  }

  /// Returns packet-containment flags for the requested packet index.
  public final int containsPacket(int index) {
    int count = packetCount(), last = firstPacketIndex + count - 1;
    if (index < firstPacketIndex || index > last) {
      return DoesNotContainPacket;
    }
    int flags = (index == firstPacketIndex ? BeginsWithPacket : 0) | (index == last ? EndsWithPacket : 0);
    if (count == 1 && !header.firstPacketContinued() && header.lastPacketCompleted()
      || count > 1 && (((flags & BeginsWithPacket) != 0 && !header.firstPacketContinued())
      || ((flags & EndsWithPacket) != 0 && header.lastPacketCompleted())
      || ((flags & (BeginsWithPacket | EndsWithPacket)) == 0))) {
      flags |= CompletePacket;
    }
    return flags;
  }

  /// Returns independent packet-fragment byte values, reading borrowed storage when needed.
  /// The source cursor can move during a read.
  ///
  /// @return copied packet fragments
  /// @throws java.io.UncheckedIOException if storage access fails
  public final List<ByteVector> packets() {
    if (!storedPackets.isEmpty()) {
      return storedPackets.stream().map(ByteVector::new).toList();
    }
    if (!header.isValid()) {
      return List.of();
    }
    if (path != null) {
      try (var input = new FileStream(path, true)) {
        return packets(input);
      }
    }
    return stream == null ? List.of() : packets(stream);
  }

  private List<ByteVector> packets(IOStream input) {
    input.seek(offset + header.size());
    var result = new ArrayList<ByteVector>();
    for (int length : header.packetSizes()) {
      result.add(input.readBlock(length));
    }
    return List.copyOf(result);
  }

  /// Serializes the page header and packet payload, then computes the Ogg CRC over the complete
  /// page.
  public final ByteVector render() {
    var output = new ByteVectorOutput();
    output.append(header.render());
    if (!storedPackets.isEmpty()) {
      for (ByteVector packet : storedPackets) {
        output.append(packet);
      }
    } else if (path != null) {
      try (var input = new FileStream(path, true)) {
        output.append(payload(input));
      }
    } else if (stream != null) {
      output.append(payload(stream));
    }
    ByteVector data = output.toByteVector();
    Binary.put32le(data.data(), 22, OggPages.crc(data.data()));
    return data;
  }

  private byte[] payload(IOStream input) {
    input.seek(offset + header.size());
    return input.readBlock(header.dataSize()).data();
  }

  /// Divides supplied packet bytes into new pages using the selected grouping policy.
  /// The input packets are unchanged and output pages retain independent packet storage.
  ///
  /// @param packets   the packet bytes
  /// @param strategy  the page grouping policy
  /// @param serial    the logical stream serial number
  /// @param firstPage the first output page sequence number
  /// @return the generated pages
  public static List<Page> paginate(List<ByteVector> packets, PaginationStrategy strategy, long serial, int firstPage) {
    return paginate(packets, strategy, serial, firstPage, false, true, false);
  }

  /// Divides supplied packet bytes into new pages using the selected grouping policy.
  /// The input packets are unchanged and output pages retain independent packet storage.
  ///
  /// @param packets   the packet bytes
  /// @param strategy  the page grouping policy
  /// @param serial    the logical stream serial number
  /// @param firstPage the first output page sequence number
  /// @param continued whether the first packet continues from an earlier page
  /// @return the generated pages
  public static List<Page> paginate(
    List<ByteVector> packets,
    PaginationStrategy strategy,
    long serial,
    int firstPage,
    boolean continued
  ) {
    return paginate(packets, strategy, serial, firstPage, continued, true, false);
  }

  /// Divides supplied packet bytes into new pages using the selected grouping policy.
  /// The input packets are unchanged and output pages retain independent packet storage.
  ///
  /// @param packets   the packet bytes
  /// @param strategy  the page grouping policy
  /// @param serial    the logical stream serial number
  /// @param firstPage the first output page sequence number
  /// @param continued whether the first packet continues from an earlier page
  /// @param completed whether the final packet is complete
  /// @return the generated pages
  public static List<Page> paginate(
    List<ByteVector> packets,
    PaginationStrategy strategy,
    long serial,
    int firstPage,
    boolean continued,
    boolean completed
  ) {
    return paginate(packets, strategy, serial, firstPage, continued, completed, false);
  }

  /// Divides supplied packet bytes into new pages using the selected grouping policy.
  /// The input packets are unchanged and output pages retain independent packet storage.
  ///
  /// @param packets              the packet bytes
  /// @param strategy             the page grouping policy
  /// @param serial               the logical stream serial number
  /// @param firstPage            the first output page sequence number
  /// @param firstPacketContinued whether the first packet continues from an earlier page
  /// @param lastPacketCompleted  whether the final packet is complete
  /// @param containsLastPacket   whether the group contains the stream's final packet
  /// @return the generated pages
  public static List<Page> paginate(
    List<ByteVector> packets, PaginationStrategy strategy, long serial, int firstPage,
    boolean firstPacketContinued, boolean lastPacketCompleted, boolean containsLastPacket
  ) {
    Objects.requireNonNull(strategy);
    List<ByteVector> source = List.copyOf(packets);
    long tableSize = 0;
    for (ByteVector packet : source) {
      tableSize += packet.size() / 255 + 1L;
    }
    if (tableSize > 255) {
      strategy = PaginationStrategy.Repaginate;
    }
    var result = new ArrayList<Page>();
    if (strategy == PaginationStrategy.SinglePagePerGroup) {
      result.add(new Page(source, serial, firstPage, firstPacketContinued, lastPacketCompleted, containsLastPacket));
    } else {
      int sequence = firstPage;
      for (int i = 0; i < source.size(); i++) {
        ByteVector packet = source.get(i);
        boolean continued = firstPacketContinued && i == 0, lastPacket = i == source.size() - 1;
        // Deliberately preserve upstream's omission of empty packets when repaginating.
        for (int position = 0; position < packet.size(); ) {
          int count = Math.min(32 * 255, packet.size() - position);
          boolean lastSplit = count == packet.size() - position;
          result.add(new Page(List.of(packet.mid(position, count)), serial, sequence++, continued,
            lastSplit && (!lastPacket || lastPacketCompleted), lastSplit && containsLastPacket && lastPacket, true));
          continued = true;
          position += count;
        }
      }
    }
    return List.copyOf(result);
  }
}
