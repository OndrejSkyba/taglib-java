// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.ogg;

import cz.skyba.taglib.internal.Binary;
import cz.skyba.taglib.toolkit.*;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static java.lang.System.Logger.Level.DEBUG;

/// A mutable Ogg page header for parsing and serialization.
/// Parsed size and validity describe the last read header; setters do not recalculate them.
/// Rendering creates a header with a zero checksum for the page renderer to fill.
public final class PageHeader {
  private static final System.Logger LOG = System.getLogger(PageHeader.class.getName());

  private boolean valid, firstContinued, lastCompleted, firstPage, lastPage;
  private long granule, serial;
  private int sequence = -1, size, dataSize;
  private List<Integer> packetSizes = List.of();

  /// Creates an unparsed header with default fields. This overload does not read storage.
  public PageHeader() {
  }

  /// Creates an unparsed header with default fields. This overload does not read storage.
  ///
  /// @param file the source file, unused without an offset
  public PageHeader(OggFile file) {
    this(file, -1);
  }

  /// Reads an Ogg page header at the specified byte offset. Negative offsets leave an unparsed header. The source is borrowed and its cursor can move.
  ///
  /// @param file   the source file, unused without an offset
  /// @param offset the page-header byte offset
  /// @throws java.io.UncheckedIOException if storage access fails
  public PageHeader(OggFile file, long offset) {
    this(file == null ? null : file.pageStream(), offset);
  }

  /// Reads an Ogg page header at the specified byte offset. Negative offsets leave an unparsed header. The source is borrowed and its cursor can move.
  ///
  /// @param stream the borrowed source, or null for an unparsed header
  /// @param offset the page-header byte offset
  /// @throws java.io.UncheckedIOException if storage access fails
  public PageHeader(IOStream stream, long offset) {
    if (stream != null && offset >= 0) {
      read(stream, offset);
    }
  }

  /// Reads an Ogg page header at the specified byte offset. Negative offsets leave an unparsed header. The temporary file handle is closed after reading.
  ///
  /// @param path   the input file
  /// @param offset the page-header byte offset
  /// @throws java.io.UncheckedIOException if storage access fails
  public PageHeader(Path path, long offset) {
    try (var stream = new FileStream(Objects.requireNonNull(path), true)) {
      if (offset >= 0) {
        read(stream, offset);
      }
    }
  }

  /// Parses an encoded header from byte offset zero of copied input data.
  ///
  /// @param data encoded page-header bytes
  /// @throws java.io.UncheckedIOException if storage access fails
  public PageHeader(byte[] data) {
    this(new ByteVectorStream(data), 0);
  }

  public boolean isValid() {
    return valid;
  }

  public List<Integer> packetSizes() {
    return packetSizes;
  }

  /// Copies packet fragment sizes used to render page lacing. Parsed size and validity are unchanged.
  ///
  /// @param sizes nonnegative fragment byte counts
  /// @throws IllegalArgumentException if a count is negative
  public void setPacketSizes(List<Integer> sizes) {
    var copy = List.copyOf(sizes);
    if (copy.stream().anyMatch(n -> n < 0)) {
      throw new IllegalArgumentException("Negative packet size");
    }
    packetSizes = copy;
  }

  public boolean firstPacketContinued() {
    return firstContinued;
  }

  public void setFirstPacketContinued(boolean value) {
    firstContinued = value;
  }

  public boolean lastPacketCompleted() {
    return lastCompleted;
  }

  public void setLastPacketCompleted(boolean value) {
    lastCompleted = value;
  }

  public boolean firstPageOfStream() {
    return firstPage;
  }

  public void setFirstPageOfStream(boolean value) {
    firstPage = value;
  }

  public boolean lastPageOfStream() {
    return lastPage;
  }

  public void setLastPageOfStream(boolean value) {
    lastPage = value;
  }

  public long absoluteGranularPosition() {
    return granule;
  }

  public void setAbsoluteGranularPosition(long value) {
    granule = value;
  }

  public long streamSerialNumber() {
    return serial;
  }

  public void setStreamSerialNumber(long value) {
    serial = value & 0xffffffffL;
  }

  public int pageSequenceNumber() {
    return sequence;
  }

  public void setPageSequenceNumber(int value) {
    sequence = value;
  }

  public int size() {
    return size;
  }

  public int dataSize() {
    return dataSize;
  }

  /// Encodes packet lacing and header flags; the returned header leaves the CRC field zero for the
  /// page renderer.
  public ByteVector render() {
    var laces = new ByteArrayOutputStream();
    for (int i = 0; i < packetSizes.size(); i++) {
      int length = packetSizes.get(i);
      for (int n = 0; n < length / 255; n++) {
        laces.write(255);
      }
      if (i + 1 < packetSizes.size() || lastCompleted) {
        laces.write(length % 255);
      }
    }
    byte[] values = laces.toByteArray(), result = new byte[Math.addExact(27, values.length)];
    System.arraycopy(Binary.bytes("OggS"), 0, result, 0, 4);
    // Upstream render derives BOS from sequence, not the stored first-page flag.
    result[5] = (byte) ((firstContinued ? 1 : 0) | (sequence == 0 ? 2 : 0) | (lastPage ? 4 : 0));
    Binary.put64le(result, 6, granule);
    Binary.put32le(result, 14, serial);
    Binary.put32le(result, 18, sequence);
    result[26] = (byte) values.length;
    System.arraycopy(values, 0, result, 27, values.length);
    return new ByteVector(result);
  }

  private void read(IOStream stream, long offset) {
    stream.seek(offset);
    byte[] data = stream.readBlock(27).toByteArray();
    if (data.length != 27 || !Binary.ascii(data, 0, 4).equals("OggS")) {
      LOG.log(DEBUG, "Ogg page header is incomplete or has an invalid capture pattern.");
      return;
    }
    firstContinued = (data[5] & 1) != 0;
    firstPage = (data[5] & 2) != 0;
    lastPage = (data[5] & 4) != 0;
    granule = Binary.u64le(data, 6);
    serial = Binary.u32le(data, 14);
    sequence = (int) Binary.u32le(data, 18);
    int count = data[26] & 255;
    byte[] laces = stream.readBlock(count).toByteArray();
    if (count < 1 || laces.length != count) {
      return;
    }
    size = 27 + count;
    var sizes = new ArrayList<Integer>();
    int packet = 0;
    for (byte lace : laces) {
      int amount = lace & 255;
      dataSize += amount;
      packet += amount;
      if (amount < 255) {
        sizes.add(packet);
        packet = 0;
      }
    }
    if (packet > 0) {
      sizes.add(packet);
    }
    lastCompleted = packet == 0;
    packetSizes = List.copyOf(sizes);
    valid = true;
  }
}
