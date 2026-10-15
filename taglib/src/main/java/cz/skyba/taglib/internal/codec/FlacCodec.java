// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.codec;

import cz.skyba.taglib.*;
import cz.skyba.taglib.flac.Properties;
import cz.skyba.taglib.internal.*;
import cz.skyba.taglib.internal.id3.Id3v1;
import cz.skyba.taglib.internal.id3.Id3v2;
import cz.skyba.taglib.internal.xiph.XiphComment;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.FileStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Native FLAC metadata; compressed audio, application blocks and seek tables are preserved.
 */
public final class FlacCodec implements Codec {
  @Override
  public PropertyMap unsupportedProperties(PropertyMap properties) {
    return XiphComment.unsupportedProperties(properties);
  }

  private record Block(int type, long offset, int length) {
  }

  private record RenderedBlock(int type, byte[] payload) {
  }

  private record Layout(long marker, long end, List<Block> blocks, long id3Length) {
  }

  public record NativeTags(
    PropertyMap xiph, Id3v2.TagData id3v2, PropertyMap id3v1,
    boolean hasXiphComment) {
  }

  /**
   * Constructor-owned physical tags, before TagUnion chooses its property fallback.
   */
  public static NativeTags nativeTags(BinaryFile file, ReadOptions options) throws IOException {
    Layout parsed = layout(file, options.maxMetadataBytes());
    PropertyMap xiph = PropertyMap.EMPTY;
    boolean hasXiphComment = false;
    for (Block block : parsed.blocks) {
      if (block.type == 4 && block.length != 0) {
        xiph = XiphComment.parse(file.read(block.offset, block.length)).properties();
        hasXiphComment = true;
        break;
      }
    }
    var id3v2 =
      parsed.id3Length > 0
        ? Id3v2.read(file.read(0, (int) Math.min(parsed.id3Length, file.size())), options)
        : null;
    PropertyMap id3v1 =
      file.size() >= 128 && file.ascii(file.size() - 128, 3).equals("TAG")
        ? Id3v1.read(file.read(file.size() - 128, 128))
        : null;
    return new NativeTags(xiph, id3v2, id3v1, hasXiphComment);
  }

  /**
   * Internal parsed carrier for the upstream-shaped FLAC File API.
   */
  public record Details(
    Properties properties,
    boolean hasXiphComment,
    boolean hasID3v2Tag,
    boolean hasID3v1Tag,
    String iXMLData,
    byte[] BEXTData,
    boolean hasiXMLData,
    boolean hasBEXTData,
    PropertyMap xiphProperties) {
    public Details {
      BEXTData = BEXTData.clone();
    }

    @Override
    public byte[] BEXTData() {
      return BEXTData.clone();
    }
  }

  private record Broadcast(String xml, byte[] bext, boolean hasXml, boolean hasBext) {
  }

  public static Details details(Path path, ReadOptions options) throws IOException {
    try (var file = new BinaryFile(path)) {
      Layout layout = layout(file, options.maxMetadataBytes());
      Broadcast data = broadcast(file, layout);
      boolean id3v1 =
        file.size() - layout.end >= 128 && file.ascii(file.size() - 128, 3).equals("TAG");
      Block streamInfo = layout.blocks.getFirst();
      Block comment =
        layout.blocks.stream()
          .filter(block -> block.type == 4 && block.length > 0)
          .findFirst()
          .orElse(null);
      PropertyMap xiph =
        comment == null
          ? PropertyMap.EMPTY
          : XiphComment.parse(file.read(comment.offset, comment.length)).properties();
      return new Details(
        new Properties(
          file.read(streamInfo.offset, streamInfo.length),
          file.size() - layout.end - (id3v1 ? 128 : 0),
          options.readStyle()),
        comment != null,
        layout.id3Length > 0,
        id3v1,
        data.xml,
        data.bext,
        data.hasXml,
        data.hasBext,
        xiph);
    }
  }

  private static String applicationType(byte[] payload) {
    if (payload.length < 4) {
      return "";
    }
    String id = Binary.ascii(payload, 0, 4);
    return id.equals("riff") && payload.length >= 12 ? Binary.ascii(payload, 4, 4) : id;
  }

  private static Broadcast broadcast(BinaryFile file, Layout layout) throws IOException {
    String xml = "";
    byte[] bext = new byte[0];
    boolean hasXml = false, hasBext = false;
    for (Block block : layout.blocks) {
      if (block.type != 2) {
        continue;
      }
      byte[] payload = file.read(block.offset, block.length);
      String id = applicationType(payload);
      if ((!id.equals("iXML") || hasXml) && (!id.equals("bext") || hasBext)) {
        continue;
      }
      int offset = Binary.ascii(payload, 0, 4).equals("riff") ? 12 : 4;
      int length =
        offset == 12
          ? (int) Math.min(Binary.u32le(payload, 8), payload.length - offset)
          : payload.length - offset;
      if (id.equals("iXML")) {
        xml = TextBytes.decodeTerminated(payload, offset, length, UTF_8);
        hasXml = true;
      } else {
        bext = Arrays.copyOfRange(payload, offset, offset + length);
        hasBext = true;
      }
    }
    return new Broadcast(xml, bext, hasXml, hasBext);
  }

  private static byte[] broadcastBlock(String id, byte[] value) throws IOException {
    if (value.length > 0xffffff - 12) {
      throw new IOException("FLAC application payload exceeds 24-bit block limit");
    }
    byte[] payload = new byte[value.length + 12];
    System.arraycopy(Binary.bytes("riff" + id), 0, payload, 0, 8);
    Binary.put32le(payload, 8, value.length);
    System.arraycopy(value, 0, payload, 12, value.length);
    return payload;
  }

  private static Layout layout(BinaryFile file, int limit) throws IOException {
    return layout(file, limit, false);
  }

  private static Layout layout(BinaryFile file, int limit, boolean nativeProtocol) throws IOException {
    long id3Length = 0;
    byte[] magic = nativeProtocol ? file.readNative(0, 3)
      : file.read(0, (int) Math.min(file.size(), 3));
    if (magic.length == 3 && Binary.ascii(magic, 0, 3).equals("ID3")) {
      id3Length = nativeProtocol && file.stream() != null
        ? NativeTagIO.id3v2(file.stream(), 0).length() : NativeTagIO.id3v2(file, 0).length();
    }
    if (nativeProtocol && file.stream() != null) {
      NativeTagIO.id3v1(file.stream());
    }
    long marker = findMarker(file, id3Length);
    if (marker < 0) {
      throw new IOException("Invalid FLAC signature");
    }
    long pos = marker + 4;
    var blocks = new ArrayList<Block>();
    for (int count = 0; count < 50_000; count++) {
      byte[] header = nativeProtocol ? file.readNative(pos, 4) : file.read(pos, 4);
      if (header.length != 4) {
        throw new IOException("Truncated FLAC block header");
      }
      int type = header[0] & 0x7f;
      int length = ((header[1] & 255) << 16) | ((header[2] & 255) << 8) | (header[3] & 255);
      if (count == 0 && type != 0) {
        throw new IOException("Missing FLAC STREAMINFO");
      }
      if (length == 0 && type != 1 && type != 3) {
        throw new IOException("Zero-sized FLAC metadata block");
      }
      if (pos + 4 + length > file.size()) {
        if (nativeProtocol) {
          file.readNative(pos + 4, length);
        }
        throw new IOException("Truncated FLAC metadata block");
      }
      if (pos + 4 + length - marker > limit) {
        throw new IOException("FLAC metadata exceeds configured limit");
      }
      blocks.add(new Block(type, pos + 4, length));
      if (nativeProtocol) {
        file.seekNative(pos + 4 + length);
      }
      pos += 4L + length;
      if ((header[0] & 0x80) != 0) {
        return new Layout(marker, pos, blocks, id3Length);
      }
    }
    throw new IOException("Too many FLAC metadata blocks");
  }

  /**
   * File::find scans bounded buffers and restores the incoming cursor on every outcome.
   */
  private static long findMarker(BinaryFile file, long start) throws IOException {
    long cursor = file.stream() == null ? 0 : file.stream().tell();
    byte[] pattern = Binary.bytes("fLaC");
    try {
      for (long offset = start; offset < file.size(); offset += 1021) {
        byte[] bytes = file.read(offset, (int) Math.min(1024, file.size() - offset));
        for (int at = 0; at <= bytes.length - 4; at++) {
          if (bytes[at] == pattern[0] && bytes[at + 1] == pattern[1]
            && bytes[at + 2] == pattern[2] && bytes[at + 3] == pattern[3]) {
            return offset + at;
          }
        }
      }
      return -1;
    } finally {
      if (file.stream() != null) {
        file.stream().seek(cursor);
      }
    }
  }

  @Override
  public AudioMetadata read(Path path, ReadOptions options) throws IOException {
    try (var file = new BinaryFile(path)) {
      return read(file, options);
    }
  }

  @Override
  public AudioMetadata read(BinaryFile file, ReadOptions options) throws IOException {
    Layout layout = layout(file, options.maxMetadataBytes());
    return readLayout(file, options, layout);
  }

  private static AudioMetadata readLayout(BinaryFile file, ReadOptions options, Layout layout) throws IOException {
    XiphComment comments = XiphComment.empty();
    boolean hasComments = false;
    var pictures = new ArrayList<Picture>();
    byte[] streamInfo = null;
    for (Block block : layout.blocks) {
      if (block.type == 0 && streamInfo == null) {
        streamInfo = file.read(block.offset, block.length);
      } else if (block.type == 4 && !hasComments) {
        comments = XiphComment.parse(file.read(block.offset, block.length));
        hasComments = true;
      } else if (block.type == 6) {
        try {
          pictures.add(XiphComment.parsePicture(file.read(block.offset, block.length)));
        } catch (IOException ignored) {
          /* Upstream skips malformed pictures. */
        }
      }
    }
    pictures.addAll(comments.pictures());
    PropertyMap properties = comments.properties();
    Tag commonTag = new Tag(properties, Format.FLAC);
    boolean primaryTagChosen = !properties.isEmpty();
    if (layout.id3Length > 0) {
      if (layout.id3Length > options.maxMetadataBytes()) {
        throw new IOException("ID3 metadata exceeds configured limit");
      }
      var id3 = Id3v2.read(file.read(0, (int) Math.min(layout.id3Length, file.size())), options);
      commonTag = fallback(commonTag, id3.basic());
      if (!primaryTagChosen && !id3.empty()) {
        properties = id3.properties();
        primaryTagChosen = true;
      }
    }
    long streamLength = file.size() - layout.end;
    if (streamLength >= 128 && file.ascii(file.size() - 128, 3).equals("TAG")) {
      streamLength -= 128;
      PropertyMap id3v1 = Id3v1.read(file.read(file.size() - 128, 128));
      commonTag = fallback(commonTag, new Tag(id3v1, Format.MPEG));
      if (!primaryTagChosen) {
        properties = id3v1;
      }
    }
    return new AudioMetadata(
      Format.FLAC,
      properties,
      options.readAudioProperties() ? audio(streamInfo, streamLength) : AudioProperties.EMPTY,
      pictures,
      commonTag);
  }

  @Override
  public AudioMetadata readNative(BinaryFile file, ReadOptions options) throws IOException {
    Layout layout = layout(file, options.maxMetadataBytes(), true);
    // FLAC::File::scan reads each metadata body in sequence. Properties are
    // constructed from the retained STREAMINFO bytes without further I/O.
    try {
      return readLayout(file, options, layout);
    } finally {
      file.seekNative(layout.end);
    }
  }

  static Tag fallback(Tag first, Tag second) {
    return new Tag(
      first.title().isEmpty() ? second.title() : first.title(),
      first.artist().isEmpty() ? second.artist() : first.artist(),
      first.album().isEmpty() ? second.album() : first.album(),
      first.comment().isEmpty() ? second.comment() : first.comment(),
      first.genre().isEmpty() ? second.genre() : first.genre(),
      first.year() == 0 ? second.year() : first.year(),
      first.track() == 0 ? second.track() : first.track());
  }

  /**
   * Shared with the Ogg FLAC mapping. Rounding follows upstream behavior.
   */
  public static AudioProperties audio(byte[] info, long streamLength) {
    if (info == null || info.length < 18) {
      return AudioProperties.EMPTY;
    }
    long flags = Binary.u32be(info, 10);
    int rate = (int) (flags >>> 12);
    int channels = (int) ((flags >>> 9) & 7) + 1;
    int bits = (int) ((flags >>> 4) & 31) + 1;
    long samples = ((flags & 15) << 32) | Binary.u32be(info, 14);
    double duration = rate > 0 ? samples * 1000.0 / rate : 0;
    long milliseconds = duration > 0 && duration < Integer.MAX_VALUE ? Math.round(duration) : 0;
    double bitrate =
      duration > 0 && duration < Integer.MAX_VALUE ? streamLength * 8.0 / duration : 0;
    int kbps = bitrate >= 0 && bitrate < Integer.MAX_VALUE ? (int) Math.round(bitrate) : 0;
    return new AudioProperties(milliseconds, kbps, rate, channels, bits, true);
  }

  @Override
  public void write(Path path, PropertyMap properties) throws IOException {
    write(path, properties, null);
  }

  @Override
  public void write(Path path, PropertyMap properties, List<Picture> pictures)
    throws IOException {
    write(path, properties, pictures, null, null);
  }

  @Override
  public void write(
    BinaryFile file, PropertyMap properties, List<Picture> pictures)
    throws IOException {
    write(file, properties, pictures, null, null);
  }

  @Override
  public void writeNative(
    BinaryFile file,
    long constructorOffset,
    PropertyMap properties,
    List<Picture> pictures
  )
    throws IOException {
    if (file.stream() == null) {
      write(file, properties, pictures);
      return;
    }
    writeNativeSave(file, properties, pictures, null, null);
  }

  private void writeNativeSave(
    BinaryFile file, PropertyMap properties, List<Picture> pictures,
    String xml, byte[] bext
  ) throws IOException {
    var stream = file.requireStream();
    long cursor = stream.tell();
    Layout source = layout(file, ReadOptions.DEFAULT.maxMetadataBytes());
    NativeTags tags = nativeTags(file, ReadOptions.TAGS_ONLY);
    var id3v2Plan = NativeSaveState.flacId3v2Properties();
    var id3v1Plan = NativeSaveState.flacId3v1Properties();
    if (id3v2Plan == null && tags.id3v2 != null) {
      id3v2Plan = tags.id3v2.properties();
    }
    if (id3v1Plan == null) {
      id3v1Plan = tags.id3v1;
    }
    byte[] originalTag = source.id3Length == 0 ? new byte[0]
      : file.read(0, (int) Math.min(source.id3Length, file.size()));
    byte[] legacy = new byte[0];
    if (id3v1Plan != null && !id3v1Plan.isEmpty()) {
      var fields = NativeSaveState.id3v1Fields();
      if (fields == null && tags.id3v1 != null) {
        byte[] raw = file.read(file.size() - 128, 128);
        fields = new NativeSaveState.Id3v1Fields(Id3v1.yearText(raw), raw[127] & 255);
      }
      PropertyMap legacyPlan = id3v1Plan;
      legacy = NativeSaveState.callId3v1(fields, () -> Id3v1.render(legacyPlan));
    }
    long oldModern = source.id3Length;
    stream.seek(cursor);
    writeMetadata(file, properties, pictures, xml, bext);
    long metadataDelta = stream.length() - file.size();
    // Native renders the secondary tag after replacing the FLAC blocks: its padding
    // threshold uses the new physical file length and its header includes duplicate tags.
    byte[] modern = id3v2Plan == null ? new byte[0]
      : Id3v2.renderNative(
      id3v2Plan, originalTag, null, stream.length(), source.id3Length);
    if (modern.length > 0) {
      stream.insert(new ByteVector(modern), 0, oldModern);
    } else if (oldModern > 0) {
      stream.removeBlock(0, oldModern);
    }
    long legacyOffset =
      file.size() - (tags.id3v1 == null ? 0 : 128) + metadataDelta + modern.length - oldModern;
    if (legacy.length > 0) {
      NativeTagIO.write(stream, legacyOffset, legacy);
    } else if (tags.id3v1 != null) {
      stream.truncate(legacyOffset);
    }
  }

  public void write(
    Path path,
    PropertyMap properties,
    List<Picture> pictures,
    String xml,
    byte[] bext
  )
    throws IOException {
    try (var file = new BinaryFile(path)) {
      write(file, properties, pictures, xml, bext);
    }
  }

  public void write(
    BinaryFile file,
    PropertyMap properties,
    List<Picture> pictures,
    String xml,
    byte[] bext
  )
    throws IOException {
    if (file.stream() != null) {
      writeNativeSave(file, properties, pictures, xml, bext);
    } else {
      WriteGuard.check(file.requirePath().toRealPath());
      try (var stream = new FileStream(file.path()); var borrowed = new BinaryFile(stream)) {
        if (!stream.isOpen() || stream.readOnly()) {
          throw new IOException("FLAC stream is not writable");
        }
        writeNativeSave(borrowed, properties, pictures, xml, bext);
      }
    }
  }

  private void writeMetadata(
    BinaryFile file, PropertyMap properties, List<Picture> pictures, String xml,
    byte[] bext
  ) throws IOException {
    byte[] replacement;
    long offset, oldLength;

    Layout layout = layout(file, ReadOptions.DEFAULT.maxMetadataBytes());
    Broadcast broadcast = broadcast(file, layout);
    if (xml == null) {
      xml = broadcast.xml;
    }
    if (bext == null) {
      bext = broadcast.bext;
    }
    offset = layout.marker + 4;
    oldLength = layout.end - offset;
    var blocks = new ArrayList<RenderedBlock>();
    XiphComment original = XiphComment.empty();
    boolean hadComment = false;
    for (Block block : layout.blocks) {
      if (block.type == 4 && block.length != 0) {
        original = XiphComment.parse(file.read(block.offset, block.length));
        hadComment = true;
        break;
      }
    }
    if (!hadComment) {
      // Native save duplicates missing common fields into a newly created Xiph tag,
      // even when setProperties already created its in-memory object. A zero numeric
      // getter invokes setYear/Track(0), which removes the corresponding text field.
      Tag staged = new Tag(properties, Format.FLAC);
      Tag physical = read(file, ReadOptions.TAGS_ONLY).tag();
      var secondary = NativeSaveState.flacId3v2Properties();
      if (secondary != null) {
        byte[] sourceTag = layout.id3Length == 0 ? new byte[0] : file.read(0, (int) Math.min(layout.id3Length, file.size()));
        physical =
          Id3v2.read(
              Id3v2.renderNative(secondary, sourceTag, null),
              ReadOptions.TAGS_ONLY)
            .basic();
      }
      var legacy = NativeSaveState.flacId3v1Properties();
      if (legacy != null) {
        physical = fallback(physical, new Tag(legacy, Format.MPEG));
      }
      Tag source = fallback(staged, physical);
      var update = properties.toBuilder();
      if (staged.title().isEmpty()) {
        setDuplicated(update, "TITLE", source.title());
      }
      if (staged.artist().isEmpty()) {
        setDuplicated(update, "ARTIST", source.artist());
      }
      if (staged.album().isEmpty()) {
        setDuplicated(update, "ALBUM", source.album());
      }
      if (staged.comment().isEmpty()) {
        setDuplicated(
          update,
          properties.get("DESCRIPTION").isEmpty() ? "COMMENT" : "DESCRIPTION",
          source.comment());
      }
      if (staged.genre().isEmpty()) {
        setDuplicated(update, "GENRE", source.genre());
      }
      if (staged.year() == 0) {
        update.remove("YEAR");
        setDuplicated(
          update, "DATE", source.year() == 0 ? "" : Integer.toString((int) source.year()));
      }
      if (staged.track() == 0) {
        update.remove("TRACKNUM");
        setDuplicated(
          update,
          "TRACKNUMBER",
          source.track() == 0 ? "" : Integer.toString((int) source.track()));
      }
      properties = update.build();
    }
    byte[] comment = original.render(properties, false, pictures == null ? null : List.of());
    if (comment.length > 0xffffff) {
      throw new IOException("FLAC comment exceeds 24-bit block limit");
    }
    for (Block block : layout.blocks) {
      if (block.type == 1) {
        continue;
      }
      if (block.type == 6 && pictures != null) {
        continue;
      }
      if (block.type == 6) {
        // Native scan creates typed Picture objects, whose render canonicalizes strings
        // and excludes malformed pictures rather than reusing their physical block bytes.
        try {
          byte[] payload = XiphComment.renderPicture(
            XiphComment.parsePicture(file.read(block.offset, block.length)));
          blocks.add(new RenderedBlock(6, payload));
        } catch (IOException malformedPicture) {
          // FLAC::File::scan discards pictures whose length fields cannot be parsed.
        }
        continue;
      }
      if (block.type == 2) {
        String id = applicationType(file.read(block.offset, block.length));
        if (id.equals("iXML") || id.equals("bext")) {
          continue;
        }
      }
      if (block.type == 4) {
        continue;
      }
      blocks.add(new RenderedBlock(block.type, file.read(block.offset, block.length)));
    }
    if (pictures != null) {
      for (Picture picture : pictures) {
        byte[] payload = XiphComment.renderPicture(picture);
        if (payload.length > 0xffffff) {
          throw new IOException("FLAC picture exceeds 24-bit block limit");
        }
        blocks.add(new RenderedBlock(6, payload));
      }
    }
    if (!xml.isEmpty()) {
      blocks.add(new RenderedBlock(2, broadcastBlock("iXML", TextBytes.encode(xml, UTF_8))));
    }
    if (bext.length > 0) {
      blocks.add(new RenderedBlock(2, broadcastBlock("bext", bext)));
    }
    // FLAC::File::save removes the old comment and inserts the replacement immediately
    // before the first Picture, or after every retained/new block when no picture exists.
    int commentPosition = -1;
    for (int i = 0; i < blocks.size(); i++) {
      if (blocks.get(i).type() == 6) {
        commentPosition = i;
        break;
      }
    }
    if (commentPosition < 0) {
      commentPosition = blocks.size();
    }
    blocks.add(commentPosition, new RenderedBlock(4, comment));
    long length = blocks.stream().mapToLong(block -> 4L + block.payload().length).sum();
    int padding = MetadataPadding.bytes(oldLength - length - 4, file.size(), 4096);
    if (length + 4L + padding > ReadOptions.DEFAULT.maxMetadataBytes()) {
      throw new IOException("FLAC metadata exceeds configured limit");
    }
    blocks.add(new RenderedBlock(1, new byte[padding]));
    var out = new ByteArrayOutputStream();
    for (int i = 0; i < blocks.size(); i++) {
      RenderedBlock block = blocks.get(i);
      byte[] payload = block.payload();
      out.write(block.type() | (i == blocks.size() - 1 ? 0x80 : 0));
      out.write(payload.length >>> 16);
      out.write(payload.length >>> 8);
      out.write(payload.length);
      out.writeBytes(payload);
    }
    replacement = out.toByteArray();

    Rewrite.replace(file, offset, oldLength, replacement);
  }

  private static void setDuplicated(PropertyMap.Builder properties, String key, String value) {
    if (value.isEmpty()) {
      properties.remove(key);
    } else {
      properties.put(key, value);
    }
  }
}
