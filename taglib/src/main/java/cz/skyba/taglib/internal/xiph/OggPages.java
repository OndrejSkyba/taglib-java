// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.xiph;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.Format;
import cz.skyba.taglib.TagBase;
import cz.skyba.taglib.internal.Binary;
import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.Rewrite;
import cz.skyba.taglib.internal.WriteGuard;
import cz.skyba.taglib.ogg.OggFile;
import cz.skyba.taglib.ogg.PageHeader;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.FileStream;
import cz.skyba.taglib.toolkit.IOStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Ogg packet assembly and native contiguous-span packet replacement/pagination.
 */
public final class OggPages {
  private OggPages() {
  }

  private static final int[] CRC_TABLE = crcTable();

  private static int[] crcTable() {
    var result = new int[256];
    for (int i = 0; i < result.length; i++) {
      int value = i << 24;
      for (int bit = 0; bit < 8; bit++) {
        value = (value << 1) ^ (value < 0 ? 0x04c11db7 : 0);
      }
      result[i] = value;
    }
    return result;
  }

  public record Page(long offset, byte[] header, int[] laces, int payloadSize) {
    public long serial() {
      return Binary.u32le(header, 14);
    }

    public long sequence() {
      return Binary.u32le(header, 18);
    }

    public long granule() {
      return Binary.u64le(header, 6);
    }

    public int flags() {
      return header[5] & 255;
    }

    public int size() {
      return header.length + payloadSize;
    }

    public long payloadOffset() {
      return offset + header.length;
    }
  }

  public record Packet(byte[] data, Page first, int firstSegment, Page last, int lastSegment) {
  }

  public record Headers(Format format, long serial, Page first, List<Packet> packets) {
  }

  private static Page decodedPage(long offset, byte[] bytes, int count, int dataSize) {
    int[] laces = new int[count];
    for (int i = 0; i < laces.length; i++) {
      laces[i] = bytes[27 + i] & 255;
    }
    return new Page(offset, bytes, laces, dataSize);
  }

  public static Page readPage(BinaryFile file, long offset) throws IOException {
    var header =
      file.stream() == null
        ? new PageHeader(file.path(), offset)
        : new PageHeader(file.stream(), offset);
    if (!header.isValid()) {
      throw new IOException("Invalid Ogg page at " + offset);
    }
    byte[] bytes = file.readNative(offset, header.size());
    return decodedPage(offset, bytes, bytes.length - 27, header.dataSize());
  }

  /**
   * Uses the same native packet index as the public Ogg base, without extra format validation.
   */
  public static final class Reader implements AutoCloseable {
    private final BinaryFile source;
    private final IOStream input;
    private final boolean ownsInput;
    private NativeFile file;

    public Reader(BinaryFile source) {
      this(source, true);
    }

    public Reader(BinaryFile source, boolean readOnly) {
      this.source = source;
      ownsInput = source.stream() == null;
      input = ownsInput ? new FileStream(source.path(), readOnly) : source.stream();
      file = new NativeFile(input);
    }

    public void reset() {
      file.close();
      file = new NativeFile(input);
    }

    public void selectVorbis() {
      file.select(new ByteVector("\u0001vorbis"));
    }

    public byte[] packet(int index, long maximum) {
      return file.bounded(index, maximum).toByteArray();
    }

    public byte[] packet(int index) {
      return file.packet(index).toByteArray();
    }

    public long tell() {
      return file.tell();
    }

    public void seek(long offset) {
      file.seek(offset);
    }

    public Page first() throws IOException {
      return physical(file.firstPageHeader(), false);
    }

    public Page last() throws IOException {
      return physical(file.lastPageHeader(), true);
    }

    private Page physical(PageHeader header, boolean last) throws IOException {
      if (header == null) {
        return null;
      }
      long cursor = input.tell();
      // Native header lookup uses the first/last physical capture, independent of CRC or serial.
      long offset = last ? file.rfind(new ByteVector("OggS")) : file.find(new ByteVector("OggS"));
      try {
        return page(offset, header.size(), header.dataSize());
      } finally {
        input.seek(cursor);
      }
    }

    private Page page(long offset, int headerSize, int dataSize) throws IOException {
      byte[] bytes = source.readNative(offset, headerSize);
      return decodedPage(offset, bytes, Math.max(0, bytes.length - 27), dataSize);
    }

    public Packet location(int index, byte[] data) throws IOException {
      var pages = file.locations(index);
      if (pages.isEmpty()) {
        return new Packet(data, null, 0, null, 0);
      }
      var first = pages.getFirst();
      var last = pages.getLast();
      long cursor = input.tell();
      try {
        Page begin = page(first.fileOffset(), first.header().size(), first.header().dataSize());
        Page end = page(last.fileOffset(), last.header().size(), last.header().dataSize());
        int firstSegment = segment(begin, index - first.firstPacketIndex(), false);
        int lastSegment = segment(end, index - last.firstPacketIndex(), true);
        return new Packet(data, begin, firstSegment, end, lastSegment);
      } finally {
        input.seek(cursor);
      }
    }

    public void replace(int index, byte[] data) throws IOException {
      file.setPacket(index, new ByteVector(data));
      file.save();
    }

    private static int segment(Page page, int packet, boolean end) {
      int current = 0;
      for (int i = 0; i < page.laces().length; i++) {
        if (current == packet && !end) {
          return i;
        }
        if (page.laces()[i] < 255) {
          if (current == packet) {
            return i;
          }
          current++;
        }
      }
      return Math.max(0, page.laces().length - 1);
    }

    @Override
    public void close() {
      file.close();
      if (ownsInput) {
        input.close();
      }
    }
  }

  private static final class NativeFile extends OggFile {
    NativeFile(IOStream input) {
      super(input);
    }

    @Override
    public TagBase tag() {
      return null;
    }

    @Override
    public AudioProperties audioProperties() {
      return null;
    }

    void select(ByteVector magic) {
      selectStream(magic);
    }

    ByteVector bounded(int index, long maximum) {
      return packet(index, maximum);
    }

    List<cz.skyba.taglib.ogg.Page> locations(int index) {
      return cachedPacketPages(index);
    }
  }

  public static Format detect(BinaryFile file) throws IOException {
    byte[] prefix = file.readNative(0, (int) Math.min(file.size(), 1024));
    String header = Binary.ascii(prefix, 0, prefix.length);
    if (header.contains("OggS")) {
      if (header.contains("\u0001vorbis")) {
        return Format.OGG_VORBIS;
      }
      if (header.contains("fLaC")) {
        return Format.OGG_FLAC;
      }
      if (header.contains("Speex   ")) {
        return Format.OGG_SPEEX;
      }
      if (header.contains("OpusHead")) {
        return Format.OGG_OPUS;
      }
    }
    throw new IOException("Missing Ogg audio identification");
  }

  public static Headers headers(BinaryFile file, int limit) throws IOException {
    long cursor = file.stream() == null ? 0 : file.stream().tell();
    Format format = detect(file);
    file.seekNative(cursor);
    try (var reader = new Reader(file)) {
      return headers(reader, format, limit);
    }
  }

  public static Headers headers(Reader reader, Format format, int limit) throws IOException {
    var packets = new ArrayList<Packet>();
    long maximum = format == Format.OGG_FLAC ? Math.min(limit, (1L << 24) + 17) : limit;
    if (format != Format.OGG_FLAC) {
      if (format == Format.OGG_VORBIS) {
        reader.selectVorbis();
      }
      byte[] id = format == Format.OGG_VORBIS ? null : reader.packet(0, maximum);
      if (format == Format.OGG_OPUS && !starts(id, 0, "OpusHead")
        || format == Format.OGG_SPEEX && !starts(id, 0, "Speex   ")) {
        throw new IOException("Invalid Ogg identification header");
      }
      byte[] comment = reader.packet(1, maximum);
      if (format == Format.OGG_VORBIS && !starts(comment, 0, "\u0003vorbis")
        || format == Format.OGG_OPUS && !starts(comment, 0, "OpusTags")) {
        throw new IOException("Invalid Ogg comment header");
      }
      long cursor = reader.tell();
      if (id == null) {
        id = reader.packet(0, maximum);
      }
      packets.add(reader.location(0, id));
      packets.add(reader.location(1, comment));
      Page first = reader.first();
      reader.seek(cursor);
      long serial = packets.getFirst().first() == null ? 0 : packets.getFirst().first().serial();
      return new Headers(format, serial, first, List.copyOf(packets));
    }
    int count = 1;
    boolean last = false, historical = false;
    for (int i = 0; i < count; i++) {
      if (i > (historical ? 1 : 0) && i - (historical ? 1 : 0) >= 1024) {
        throw new IOException("Too many Ogg FLAC metadata blocks");
      }
      byte[] data = reader.packet(i, maximum);
      if (i == 0
        && !starts(data, 0, "fLaC")
        && (data.length < 13
        || !starts(data, 0, "\u007fFLAC")
        || (data[5] != 1 && data[6] != 0)
        || !starts(data, 9, "fLaC"))) {
        throw new IOException("Invalid Ogg FLAC mapping header");
      }
      Packet packet = reader.location(i, data);
      packets.add(packet);
      int offset = i == 0 && starts(data, 0, "\u007fFLAC") ? 13 : 0;
      if (i == 0 && starts(data, 0, "fLaC")) {
        historical = true;
      } else {
        if (data.length < offset + 4) {
          throw new IOException("Truncated Ogg FLAC metadata");
        }
        if (i == (historical ? 1 : 0) && (data[offset] & 127) != 0) {
          throw new IOException("Missing initial Ogg FLAC STREAMINFO");
        }
        if (i > 0 && (data[0] & 255) == 255 && (data[1] & 255) == 248) {
          packets.removeLast();
          break;
        }
        last = (data[offset] & 128) != 0;
      }
      if (!last) {
        count++;
      }
    }
    long cursor = reader.tell();
    Page first = reader.first();
    reader.seek(cursor);
    long serial = packets.getFirst().first() == null ? 0 : packets.getFirst().first().serial();
    return new Headers(format, serial, first, List.copyOf(packets));
  }

  public static boolean starts(byte[] data, int offset, String value) {
    return offset >= 0
      && data.length - offset >= value.length()
      && Binary.ascii(data, offset, value.length()).equals(value);
  }

  public static int crc(byte[] bytes) {
    int crc = 0;
    for (int i = 0; i < bytes.length; i++) {
      int value = i >= 22 && i < 26 ? 0 : bytes[i] & 255;
      crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ value) & 255];
    }
    return crc;
  }

  public static boolean validCrc(byte[] bytes) {
    return bytes.length >= 27 && Binary.u32le(bytes, 22) == Integer.toUnsignedLong(crc(bytes));
  }

  public static byte[] page(
    long serial, long sequence, long granule, int flags, int[] laces, byte[] payload) {
    byte[] result = new byte[27 + laces.length + payload.length];
    System.arraycopy(Binary.bytes("OggS"), 0, result, 0, 4);
    result[5] = (byte) flags;
    for (int i = 0; i < 8; i++) {
      result[6 + i] = (byte) (granule >>> (8 * i));
    }
    Binary.put32le(result, 14, serial);
    Binary.put32le(result, 18, sequence);
    result[26] = (byte) laces.length;
    for (int i = 0; i < laces.length; i++) {
      result[27 + i] = (byte) laces[i];
    }
    System.arraycopy(payload, 0, result, 27 + laces.length, payload.length);
    Binary.put32le(result, 22, Integer.toUnsignedLong(crc(result)));
    return result;
  }

  public static void replacePacket(
    BinaryFile file, Packet packet, byte[] replacement) throws IOException {
    if (file.path() != null) {
      replacePacket(file.path(), packet, replacement);
      return;
    }
    var stream = file.stream();
    if (stream.readOnly() || !stream.isOpen()) {
      throw new IOException("Stream is not writable");
    }
    Replacement rendered = renderPacket(stream, packet, replacement);
    var insertion = new ByteArrayOutputStream();
    for (byte[] page : rendered.pages) {
      insertion.writeBytes(page);
    }
    long originalLength = packet.last.offset + packet.last.size() - packet.first.offset;
    Rewrite.replace(file, packet.first.offset, originalLength, insertion.toByteArray());
    if (rendered.delta != 0) {
      long offset = packet.first.offset + insertion.size();
      while (offset < stream.length()) {
        var page = new cz.skyba.taglib.ogg.Page(stream, offset);
        if (!page.header().isValid()) {
          break;
        }
        page.setPageSequenceNumber(page.pageSequenceNumber() + rendered.delta);
        byte[] header = page.render().mid(18, 8).toByteArray();
        stream.seek(offset + 18);
        stream.writeBlock(new ByteVector(header));
        if (page.header().lastPageOfStream()) {
          break;
        }
        offset += page.size();
      }
    }
  }

  public static void replacePacket(
    Path path, Packet packet, byte[] replacement) throws IOException {
    Path absolute = path.toRealPath();
    WriteGuard.check(absolute);
    var before = Files.readAttributes(absolute, BasicFileAttributes.class);
    Replacement rendered = renderPacket(absolute, packet, replacement);
    long originalLength = packet.last.offset + packet.last.size() - packet.first.offset;
    var insertion = new ByteArrayOutputStream();
    for (byte[] page : rendered.pages) {
      insertion.writeBytes(page);
    }
    checkUnchanged(absolute, before);
    WriteGuard.check(absolute);
    // Native replaces the entire physical span, including pages of interleaved logical streams.
    Rewrite.replace(
      absolute, packet.first.offset, originalLength, insertion.toByteArray());
    if (rendered.delta != 0) {
      renumberTail(absolute, packet.first.offset + insertion.size(), rendered.delta);
    }
  }

  private static void checkUnchanged(Path path, BasicFileAttributes before) throws IOException {
    var after = Files.readAttributes(path, BasicFileAttributes.class);
    if (before.size() != after.size()
      || !before.lastModifiedTime().equals(after.lastModifiedTime())
      || !Objects.equals(before.fileKey(), after.fileKey())) {
      throw new IOException("File changed during Ogg metadata update");
    }
  }

  /**
   * Native renumbering covers every serial and stops at the first EOS or invalid header.
   */
  private static void renumberTail(Path path, long offset, int delta) throws IOException {
    try (var input = new FileStream(path, true);
         var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
      while (offset < input.length()) {
        var page = new cz.skyba.taglib.ogg.Page(input, offset);
        if (!page.header().isValid()) {
          return;
        }
        page.setPageSequenceNumber(page.pageSequenceNumber() + delta);
        byte[] rendered = page.render().toByteArray();
        ByteBuffer header = ByteBuffer.wrap(rendered, 18, 8);
        long at = offset + 18;
        while (header.hasRemaining()) {
          int written = channel.write(header, at);
          if (written == 0) {
            throw new IOException("No progress writing Ogg page header");
          }
          at += written;
        }
        if (page.header().lastPageOfStream()) {
          return;
        }
        offset += page.size();
      }
    }
  }

  private record Replacement(List<byte[]> pages, int delta) {
  }

  private static Replacement renderPacket(Path path, Packet packet, byte[] replacement) {
    try (var input = new FileStream(path, true)) {
      return renderPacket(input, packet, replacement);
    }
  }

  private static Replacement renderPacket(
    IOStream input, Packet packet, byte[] replacement) {
    var first = new cz.skyba.taglib.ogg.Page(input, packet.first.offset);
    var last =
      packet.first.offset == packet.last.offset
        ? first
        : new cz.skyba.taglib.ogg.Page(input, packet.last.offset);
    int localIndex = 0;
    for (int i = 0; i < packet.firstSegment; i++) {
      if (packet.first.laces[i] < 255) {
        localIndex++;
      }
    }
    var pages = OggPacketWrite.paginate(first, last, localIndex, new ByteVector(replacement));
    int delta = pages.getLast().pageSequenceNumber() - last.pageSequenceNumber();
    return new Replacement(pages.stream().map(page -> page.render().toByteArray()).toList(), delta);
  }
}
