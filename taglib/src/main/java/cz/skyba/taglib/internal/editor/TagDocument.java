// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.editor;

import cz.skyba.taglib.*;
import cz.skyba.taglib.internal.Binary;
import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.MetadataPadding;
import cz.skyba.taglib.internal.Rewrite;
import cz.skyba.taglib.internal.ape.ApeTag;
import cz.skyba.taglib.internal.codec.MpegCodec;
import cz.skyba.taglib.internal.id3.Id3v1;
import cz.skyba.taglib.internal.id3.Id3v2;
import cz.skyba.taglib.internal.xiph.OggPages;
import cz.skyba.taglib.internal.xiph.XiphComment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Physical tag editor. Each callback changes one original frame, field, or metadata block.
 */
public final class TagDocument extends AbstractDocument {
  private static final int LIMIT = ReadOptions.DEFAULT.maxMetadataBytes();
  private static final Pattern LEGACY_INTEGER = Pattern.compile("[+-]?[0-9]+");
  private static final List<String> LEGACY_GENRES = legacyGenres();

  private enum Destination {
    NONE, ID3, APE, XIPH
  }

  private Destination destination = Destination.NONE;
  private static final Map<String, String> INFO_KEYS =
    Map.ofEntries(
      Map.entry("IPRD", "ALBUM"),
      Map.entry("IENG", "ARRANGER"),
      Map.entry("IART", "ARTIST"),
      Map.entry("IBSU", "ARTISTWEBPAGE"),
      Map.entry("IBPM", "BPM"),
      Map.entry("ICMT", "COMMENT"),
      Map.entry("IMUS", "COMPOSER"),
      Map.entry("ICOP", "COPYRIGHT"),
      Map.entry("ICRD", "DATE"),
      Map.entry("PRT1", "DISCSUBTITLE"),
      Map.entry("ITCH", "ENCODEDBY"),
      Map.entry("ISFT", "ENCODING"),
      Map.entry("IDIT", "ENCODINGTIME"),
      Map.entry("IGNR", "GENRE"),
      Map.entry("ISRC", "ISRC"),
      Map.entry("IPUB", "LABEL"),
      Map.entry("ILNG", "LANGUAGE"),
      Map.entry("IWRI", "LYRICIST"),
      Map.entry("IMED", "MEDIA"),
      Map.entry("ISTR", "PERFORMER"),
      Map.entry("ICNT", "RELEASECOUNTRY"),
      Map.entry("IEDT", "REMIXER"),
      Map.entry("INAM", "TITLE"),
      Map.entry("IPRT", "TRACKNUMBER"));

  @FunctionalInterface
  private interface Bytes {
    byte[] get() throws IOException;
  }

  private static final class Part {
    final long offset, length;
    Bytes renderer;
    boolean dirty;

    Part(long offset, long length) {
      this.offset = offset;
      this.length = length;
    }
  }

  private final List<Part> parts = new ArrayList<>();
  private boolean originalId3;

  @FunctionalInterface
  private interface Validation {
    void validate() throws IOException;
  }

  private final List<Validation> validators = new ArrayList<>();
  private final List<Id3BlockEditor> id3Blocks = new ArrayList<>();
  private Id3BlockEditor primaryId3;
  private XiphBlockEditor primaryXiph;
  private ApeBlockEditor primaryApe;

  private final Format format;
  private final long fileSize;

  private sealed interface CarrierState {
  }

  private record PlainCarrier() implements CarrierState {
  }

  private static final class FlacCarrier implements CarrierState {
    final List<FlacBlock> blocks = new ArrayList<>();
    Part part;
  }

  private static final class RiffCarrier implements CarrierState {
    final boolean aiff, longForm;
    long ds64;

    RiffCarrier(boolean aiff, boolean longForm) {
      this.aiff = aiff;
      this.longForm = longForm;
    }
  }

  private static final class OggCarrier implements CarrierState {
    OggPages.Packet packet;
    byte[] prefix;
    boolean framing, flac;
  }

  private CarrierState carrier = new PlainCarrier();
  private long retainedMetadata;

  public TagDocument(Path path) throws IOException {
    super(path);
    try (var file = new BinaryFile(path)) {
      format = TagLib.read(path, ReadOptions.TAGS_ONLY).format();
      fileSize = file.size();
      switch (format) {
        case MPEG, TRUEAUDIO -> loadTagged(file, true);
        case APE, MPC, WAVPACK -> loadTagged(file, false);
        case FLAC -> loadFlac(file);
        case WAV, AIFF -> loadRiff(file);
        case OGG_VORBIS, OGG_OPUS, OGG_SPEEX, OGG_FLAC -> loadOgg(file);
        default -> readOnlyReason = "Physical tag editing is not supported for this container.";
      }
    } catch (FileSystemException e) {
      throw e;
    } catch (IOException e) {
      throw new MetadataReadException(
        "Could not read metadata from this audio file. Its tags or file structure may be"
          + " damaged.",
        e);
    }
  }

  private byte[] read(BinaryFile file, long at, long size) throws IOException {
    retainedMetadata += size;
    if (size < 0 || size > LIMIT || retainedMetadata > LIMIT) {
      throw new IOException("Metadata exceeds editor byte limit");
    }
    return file.read(at, (int) size);
  }

  private Part part(long at, long length) {
    var result = new Part(at, length);
    parts.add(result);
    return result;
  }

  private Id3BlockEditor id3(byte[] data, Part part, int precedence, boolean projected)
    throws IOException {
    if (data.length > 0) {
      originalId3 = true;
    }
    var result = new Id3BlockEditor(this, fileSize, data, () -> part.dirty = true, precedence, projected);
    part.renderer = result::render;
    id3Blocks.add(result);
    return result;
  }

  private long id3Prefix(BinaryFile file, int precedence) throws IOException {
    int length = 0;
    long offset = 0;
    if (format == Format.MPEG) {
      var location = MpegCodec.initialTag(file, ReadOptions.DEFAULT);
      offset = location.offset();
      length = location.size();
    } else if (file.size() >= 10 && file.ascii(0, 3).equals("ID3")) {
      length = Id3v2.tagSize(file.read(0, 10));
    }
    primaryId3 = id3(read(file, offset, length), part(offset, length), precedence, true);
    return offset + length;
  }

  private void loadTagged(BinaryFile file, boolean id3Primary) throws IOException {
    defaultBlock = id3Primary ? "ID3v2" : "APE";
    destination = id3Primary ? Destination.ID3 : Destination.APE;
    if (id3Primary) {
      id3Prefix(file, 300);
    }
    loadLegacy(file);
    var ape = ApeTag.read(file, ReadOptions.DEFAULT);
    retainedMetadata += ape.length();
    if (retainedMetadata > LIMIT) {
      throw new IOException("Metadata exceeds editor byte limit");
    }
    if (ape.length() > 0 || !id3Primary) {
      var p = part(ape.offset(), ape.length());
      var block = new ApeBlockEditor(this, ape.items(), () -> p.dirty = true, id3Primary ? 200 : 300);
      p.renderer = block::render;
      if (!id3Primary) {
        primaryApe = block;
      }
    }
  }

  private void loadLegacy(BinaryFile file) throws IOException {
    if (file.size() < 128 || !file.ascii(file.size() - 128, 3).equals("TAG")) {
      return;
    }
    var p = part(file.size() - 128, 128);
    var values = new LinkedHashMap<>(Id3v1.read(read(file, p.offset, 128)).asMap());
    for (var entry : List.copyOf(values.entrySet())) {
      String key = entry.getKey();
      addEntry(
        "ID3v1",
        key,
        key,
        "text",
        entry.getValue(),
        (_, next) -> {
          validateLegacy(key, next);
          values.put(key, List.copyOf(next));
          p.dirty = true;
        },
        () -> {
          values.remove(key);
          p.dirty = true;
        },
        false,
        "")
        .constraints(legacySpec(key).constraints())
        .precedence(100)
        .projected(true);
    }
    validators.add(
      () -> {
        for (var e : values.entrySet()) {
          validateLegacy(e.getKey(), e.getValue());
        }
      });
    p.renderer =
      () -> {
        for (var e : values.entrySet()) {
          validateLegacy(e.getKey(), e.getValue());
        }
        return values.isEmpty() ? new byte[0] : Id3v1.render(new PropertyMap(values));
      };
  }

  private record LegacyFieldSpec(Integer maxLength, Integer maxInteger, List<String> choices) {
    Map<String, Object> constraints() {
      var result = new LinkedHashMap<String, Object>();
      result.put("latin1", true);
      if (maxLength != null) {
        result.put("maxLength", maxLength);
      }
      if (maxInteger != null) {
        result.put("minInteger", 1);
        result.put("maxInteger", maxInteger);
      }
      if (choices != null) {
        result.put("choices", choices);
      }
      return result;
    }
  }

  private static List<String> legacyGenres() {
    var result = new ArrayList<String>();
    for (int i = 0; !Id3v1.genre(i).isEmpty(); i++) {
      result.add(Id3v1.genre(i));
    }
    return List.copyOf(result);
  }

  private static LegacyFieldSpec legacySpec(String key) {
    return switch (key) {
      case "TITLE", "ARTIST", "ALBUM" -> new LegacyFieldSpec(30, null, null);
      case "COMMENT" -> new LegacyFieldSpec(28, null, null);
      case "TRACKNUMBER" -> new LegacyFieldSpec(null, 255, null);
      case "DATE" -> new LegacyFieldSpec(null, 9999, null);
      case "GENRE" -> new LegacyFieldSpec(null, null, LEGACY_GENRES);
      default -> new LegacyFieldSpec(null, null, null);
    };
  }

  private static void validateLegacy(String key, List<String> values) {
    EditorText.single(values);
    String value = values.getFirst();
    var limits = legacySpec(key);
    if (value.chars().anyMatch(c -> c > 255)) {
      throw new MetadataEditException(
        "ID3v1 cannot represent this text. Use the ID3v2 field instead.");
    }
    if (limits.maxLength() != null && value.length() > limits.maxLength()) {
      throw new MetadataEditException(
        "The ID3v1 field is limited to " + limits.maxLength() + " characters.");
    }
    if (limits.maxInteger() != null) {
      if (!LEGACY_INTEGER.matcher(value).matches()) {
        throw new MetadataEditException("Enter a whole number for this tag.");
      }
      try {
        long n = Long.parseLong(value);
        if (n < 1 || n > limits.maxInteger()) {
          throw new MetadataEditException(
            "ID3v1 requires an integer between 1 and " + limits.maxInteger() + ".");
        }
      } catch (NumberFormatException e) {
        throw new MetadataEditException("Value is outside the supported integer range.");
      }
    }
    if (limits.choices() != null && !limits.choices().contains(value)) {
      throw new MetadataEditException(
        "ID3v1 requires a predefined genre. Use the ID3v2 field for custom genres.");
    }
  }

  private static final class FlacBlock {
    int type;
    byte[] raw;
    Bytes renderer;
    boolean removed;

    FlacBlock(int type, byte[] raw) {
      this.type = type;
      this.raw = raw;
    }
  }

  private void loadFlac(BinaryFile file) throws IOException {
    defaultBlock = "Vorbis";
    destination = Destination.XIPH;
    long marker = id3Prefix(file, 200);
    loadLegacy(file);
    if (!file.ascii(marker, 4).equals("fLaC")) {
      throw new IOException("Invalid FLAC signature");
    }
    long pos = marker + 4;
    var flacState = new FlacCarrier();
    carrier = flacState;
    boolean last = false;
    int count = 0;
    while (!last) {
      if (++count > 1024) {
        throw new IOException("Too many FLAC blocks");
      }
      byte[] header = file.read(pos, 4);
      int type = header[0] & 127,
        size = (header[1] & 255) << 16 | (header[2] & 255) << 8 | (header[3] & 255);
      last = (header[0] & 128) != 0;
      flacState.blocks.add(new FlacBlock(type, read(file, pos + 4, size)));
      pos += 4L + size;
    }
    flacState.part = part(marker + 4, pos - marker - 4);
    flacState.part.renderer = this::renderFlac;
    for (var block : flacState.blocks) {
      if (block.type == 4) {
        var comments = new XiphBlockEditor(this, block.raw, false, () -> flacState.part.dirty = true, 300);
        block.renderer = comments::render;
        if (primaryXiph == null) {
          primaryXiph = comments;
        }
      } else if (block.type == 6) {
        Removal remove =
          () -> {
            block.removed = true;
            flacState.part.dirty = true;
          };
        try {
          var pic = XiphComment.parsePicture(block.raw);
          picture("FLAC", "PICTURE", pic, pic.description(), remove).projected(false);
        } catch (IOException e) {
          addEntry(
            "FLAC",
            "PICTURE",
            "",
            "binary",
            List.of(),
            null,
            remove,
            false,
            "Unrecognized picture bytes")
            .projected(false);
        }
      } else if (block.type == 2
        && block.raw.length >= 12
        && Binary.ascii(block.raw, 0, 4).equals("riff")) {
        String key = Binary.ascii(block.raw, 4, 4);
        if (key.equals("iXML") || key.equals("bext")) {
          addEntry(
            "FLAC",
            key,
            "",
            key.equals("iXML") ? "structured" : "binary",
            List.of(),
            null,
            () -> {
              block.removed = true;
              flacState.part.dirty = true;
            },
            false,
            "")
            .projected(false);
        }
      }
    }
    if (primaryXiph == null) {
      var block = new FlacBlock(4, new byte[0]);
      primaryXiph = new XiphBlockEditor(this, new byte[0], false, () -> flacState.part.dirty = true, 300);
      block.renderer = primaryXiph::render;
      flacState.blocks.add(block);
    }
  }

  private byte[] renderFlac() throws IOException {
    var flacState = (FlacCarrier) carrier;
    var kept = flacState.blocks.stream().filter(b -> !b.removed && b.type != 1).toList();
    var out = new ByteArrayOutputStream();
    for (var block : kept) {
      byte[] payload = block.renderer == null ? block.raw : block.renderer.get();
      if (payload.length > 0xffffff) {
        throw new IOException("FLAC block exceeds 24-bit size");
      }
      if ((long) out.size() + 4 + payload.length > LIMIT) {
        throw new IOException("Metadata exceeds editor byte limit");
      }
      out.write(block.type);
      out.write(payload.length >>> 16);
      out.write(payload.length >>> 8);
      out.write(payload.length);
      out.writeBytes(payload);
    }
    int padding = MetadataPadding.bytes(flacState.part.length - out.size() - 4L, fileSize, 4096);
    if ((long) out.size() + 4 + padding > LIMIT) {
      throw new IOException("Metadata exceeds editor byte limit");
    }
    out.write(0x81); // One final PADDING block holds the reusable metadata capacity.
    out.write(padding >>> 16);
    out.write(padding >>> 8);
    out.write(padding);
    out.writeBytes(new byte[padding]);
    return out.toByteArray();
  }

  private void loadOgg(BinaryFile file) throws IOException {
    var oggState = new OggCarrier();
    carrier = oggState;
    defaultBlock = "Vorbis";
    destination = Destination.XIPH;
    var headers = OggPages.headers(file, LIMIT);
    int skip = 0;
    switch (format) {
      case OGG_VORBIS -> {
        oggState.packet = headers.packets().get(1);
        skip = 7;
        oggState.framing = true;
        if (!OggPages.starts(oggState.packet.data(), 0, "\u0003vorbis")) {
          throw new IOException("Invalid Vorbis comment packet");
        }
      }
      case OGG_OPUS -> {
        oggState.packet = headers.packets().get(1);
        skip = 8;
        if (!OggPages.starts(oggState.packet.data(), 0, "OpusTags")) {
          throw new IOException("Invalid Opus comment packet");
        }
      }
      case OGG_SPEEX -> {
        oggState.packet = headers.packets().get(1);
        oggState.framing = true;
      }
      case OGG_FLAC -> {
        oggState.flac = true;
        for (int i = 0; i < headers.packets().size(); i++) {
          var packet = headers.packets().get(i);
          int off = i == 0 && OggPages.starts(packet.data(), 0, "\u007fFLAC") ? 13 : 0;
          if (packet.data().length >= off + 4 && (packet.data()[off] & 127) == 4) {
            oggState.packet = packet;
            skip = off + 4;
            break;
          }
        }
        if (oggState.packet == null) {
          readOnlyReason =
            "Ogg FLAC without an existing comment packet is not supported by the physical"
              + " editor.";
          return;
        }
      }
      default -> throw new IOException("Unsupported Ogg mapping");
    }
    byte[] raw = oggState.packet.data();
    if (skip > raw.length) {
      throw new IOException("Truncated Ogg comment packet");
    }
    oggState.prefix = Arrays.copyOf(raw, skip);
    var p = part(0, 0);
    primaryXiph =
      new XiphBlockEditor(this,
        Arrays.copyOfRange(raw, skip, raw.length), oggState.framing, () -> p.dirty = true, 300);
    p.renderer = primaryXiph::render;
  }

  private void loadRiff(BinaryFile file) throws IOException {
    defaultBlock = "ID3v2";
    destination = Destination.ID3;
    String signature = file.ascii(0, 4);
    var riffState = new RiffCarrier(format == Format.AIFF, signature.equals("RF64") || signature.equals("BW64"));
    carrier = riffState;
    long pos = 12, dataSize = 0;
    int count = 0;
    while (pos < file.size()) {
      if (++count > 100000) {
        throw new IOException("Too many RIFF chunks");
      }
      if (pos > file.size() - 8) {
        throw new IOException("Trailing RIFF bytes cannot be edited safely");
      }
      byte[] h = file.read(pos, 8);
      String id = Binary.ascii(h, 0, 4);
      if (!id.chars().allMatch(c -> c >= 32 && c <= 126)) {
        throw new IOException("Invalid RIFF chunk name");
      }
      long size = riffState.aiff ? Binary.u32be(h, 4) : Binary.u32le(h, 4);
      if (riffState.longForm && id.equals("ds64") && size >= 28) {
        riffState.ds64 = pos + 8;
        dataSize = file.u64le(riffState.ds64 + 8);
      }
      if (riffState.longForm && id.equals("data") && size == 0xffffffffL) {
        if (dataSize <= 0) {
          throw new IOException("Invalid RF64 data size");
        }
        size = dataSize;
      }
      if (size > file.size() - pos - 8) {
        throw new IOException("Truncated RIFF chunk cannot be edited safely");
      }
      long span = 8 + size + (size & 1);
      if (span > file.size() - pos) {
        if (size % 2 == 1 && pos + 8 + size == file.size()) {
          span--;
        } else {
          throw new IOException("Truncated RIFF padding");
        }
      }
      if (id.equals("ID3 ") || id.equals("id3 ")) {
        originalId3 = true;
        var p = part(pos, span);
        var block = id3(read(file, pos + 8, size), p, 300, primaryId3 == null);
        p.renderer =
          () -> {
            byte[] tag = block.render();
            return tag.length == 0 ? new byte[0] : chunk(id, tag, riffState.aiff);
          };
        if (primaryId3 == null) {
          primaryId3 = block;
        }
      } else if (!riffState.aiff && id.equals("LIST") && size >= 4 && file.ascii(pos + 8, 4).equals("INFO")) {
        var p = part(pos, span);
        var fields = readInfo(read(file, pos + 8, size), p);
        p.renderer = () -> renderInfo(fields);
      } else if (!riffState.aiff && (id.equals("iXML") || id.equals("bext"))) {
        var p = part(pos, span);
        p.renderer = () -> new byte[0];
        addEntry(
          "RIFF",
          id,
          "",
          id.equals("iXML") ? "structured" : "binary",
          List.of(),
          null,
          () -> p.dirty = true,
          false,
          "")
          .projected(false);
      }
      pos += span;
    }
    if (primaryId3 == null) {
      var p = part(file.size(), 0);
      primaryId3 = id3(new byte[0], p, 300, true);
      p.renderer =
        () -> {
          byte[] tag = primaryId3.render();
          if (tag.length == 0) {
            return new byte[0];
          }
          byte[] encoded = chunk("ID3 ", tag, riffState.aiff);
          if ((fileSize & 1) == 0) {
            return encoded;
          }
          // A legal final odd chunk can omit its pad byte until another chunk follows it.
          byte[] aligned = new byte[encoded.length + 1];
          System.arraycopy(encoded, 0, aligned, 1, encoded.length);
          return aligned;
        };
    }
  }

  private static final class InfoField {
    String id;
    byte[] value;
    boolean removed;

    InfoField(String id, byte[] value) {
      this.id = id;
      this.value = value;
    }
  }

  private List<InfoField> readInfo(byte[] raw, Part p) throws IOException {
    var fields = new ArrayList<InfoField>();
    int at = 4;
    while (at < raw.length) {
      if (at > raw.length - 8) {
        throw new IOException("Truncated RIFF INFO field");
      }
      String id = Binary.ascii(raw, at, 4);
      long size = Binary.u32le(raw, at + 4);
      if (size > raw.length - at - 8) {
        throw new IOException("Truncated RIFF INFO text");
      }
      var field = new InfoField(id, Arrays.copyOfRange(raw, at + 8, at + 8 + (int) size));
      fields.add(field);
      int n = 0;
      while (n < field.value.length && field.value[n] != 0) {
        n++;
      }
      String text = new String(field.value, 0, n, StandardCharsets.UTF_8);
      addEntry(
        "RIFF INFO",
        id,
        INFO_KEYS.getOrDefault(id, ""),
        "text",
        List.of(text),
        (_, next) -> {
          EditorText.single(next);
          field.value = (next.getFirst() + "\u0000").getBytes(StandardCharsets.UTF_8);
          p.dirty = true;
        },
        () -> {
          field.removed = true;
          p.dirty = true;
        },
        false,
        "")
        .precedence(100);
      at += 8 + (int) size + (int) (size & 1);
    }
    return fields;
  }

  private byte[] renderInfo(List<InfoField> fields) {
    var out = new ByteArrayOutputStream();
    out.writeBytes(Binary.bytes("INFO"));
    for (var field : fields) {
      if (!field.removed) {
        out.writeBytes(chunk(field.id, field.value, false));
      }
    }
    return out.size() == 4 ? new byte[0] : chunk("LIST", out.toByteArray(), false);
  }

  private static byte[] chunk(String id, byte[] payload, boolean bigEndian) {
    byte[] out = new byte[8 + payload.length + (payload.length & 1)];
    System.arraycopy(Binary.bytes(id), 0, out, 0, 4);
    if (bigEndian) {
      Binary.put32be(out, 4, payload.length);
    } else {
      Binary.put32le(out, 4, payload.length);
    }
    System.arraycopy(payload, 0, out, 8, payload.length);
    return out;
  }

  @Override
  public boolean hasId3v2Tag() {
    return originalId3;
  }

  @Override
  protected void add(String key, List<String> values, boolean custom) throws IOException {
    switch (destination) {
      case XIPH -> {
        if (primaryXiph != null) {
          primaryXiph.add(key, values, custom);
          return;
        }
      }
      case ID3 -> {
        if (primaryId3 != null) {
          primaryId3.add(key, values, custom);
          return;
        }
      }
      case APE -> {
        if (primaryApe != null) {
          primaryApe.add(key, values, custom);
          return;
        }
      }
      case NONE -> {
      }
    }
    throw new MetadataEditException("Adding tags is not supported for this file");
  }

  @Override
  protected void replacePicturesChanges(List<Picture> pictures) throws IOException {
    requireWritable();
    pictures = List.copyOf(pictures);
    for (var picture : pictures) {
      if (picture.type() < 0
        || picture.type() > 20
        || picture.mimeType().indexOf('\0') >= 0
        || picture.description().indexOf('\0') >= 0) {
        throw new MetadataEditException("Artwork contains an invalid type or text character");
      }
      if (primaryId3 != null
        && primaryXiph == null
        && picture.mimeType().chars().anyMatch(c -> c > 255)) {
        throw new MetadataEditException("ID3 artwork MIME types require Latin-1 text");
      }
    }
    if (primaryApe != null) {
      var types = new HashSet<Integer>();
      for (var picture : pictures) {
        if (!types.add(picture.type() == 4 ? 4 : 3)) {
          throw new MetadataEditException("APE supports one front and one back picture");
        }
      }
    }
    for (var block : id3Blocks) {
      block.checkArtworkReplacement();
    }
    if (carrier instanceof FlacCarrier flacState) {
      for (var block : flacState.blocks) {
        if (block.type == 6) {
          block.removed = true;
        }
      }
      Objects.requireNonNull(primaryXiph).pictures(List.of());
      for (var pic : pictures) {
        flacState.blocks.add(new FlacBlock(6, XiphComment.renderPicture(pic)));
      }
      flacState.part.dirty = true;
    } else if (primaryXiph != null) {
      primaryXiph.pictures(pictures);
    } else if (primaryId3 != null) {
      primaryId3.pictures(pictures);
    } else if (primaryApe != null) {
      primaryApe.pictures(pictures);
    } else {
      throw new MetadataEditException("Artwork editing is not supported for this container");
    }
  }

  @Override
  protected void saveChanges() throws IOException {
    if (carrier instanceof OggCarrier oggState && oggState.packet != null) {
      if (parts.stream().noneMatch(p -> p.dirty)) {
        return;
      }
      byte[] comment = primaryXiph.render();
      if (oggState.flac) {
        if (comment.length > 0xffffff) {
          throw new IOException("Ogg FLAC comment exceeds block limit");
        }
        int at = oggState.prefix.length - 4;
        oggState.prefix[at + 1] = (byte) (comment.length >>> 16);
        oggState.prefix[at + 2] = (byte) (comment.length >>> 8);
        oggState.prefix[at + 3] = (byte) comment.length;
      }
      var out = new ByteArrayOutputStream();
      out.writeBytes(oggState.prefix);
      out.writeBytes(comment);
      if (out.size() > LIMIT) {
        throw new IOException("Metadata exceeds editor byte limit");
      }
      OggPages.replacePacket(path, oggState.packet, out.toByteArray());
      return;
    }
    var edits = new ArrayList<Rewrite.Edit>();
    long delta = 0, total = 0;
    for (var p : parts) {
      if (p.dirty) {
        byte[] data = p.renderer.get();
        total += data.length;
        if (total > LIMIT) {
          throw new IOException("Metadata exceeds editor byte limit");
        }
        edits.add(new Rewrite.Edit(p.offset, p.length, data));
        delta += data.length - p.length;
      }
    }
    if (edits.isEmpty()) {
      return;
    }
    for (var validator : validators) {
      validator.validate();
    }
    if (carrier instanceof RiffCarrier riffState) {
      long size = fileSize + delta - 8;
      if (!riffState.longForm && size > 0xffffffffL) {
        throw new IOException("RIFF size exceeds 32-bit limit");
      }
      byte[] bytes = new byte[4];
      if (riffState.aiff) {
        Binary.put32be(bytes, 0, size);
      } else {
        Binary.put32le(bytes, 0, riffState.longForm ? 0xffffffffL : size);
      }
      edits.add(new Rewrite.Edit(4, 4, bytes));
      if (riffState.longForm) {
        if (riffState.ds64 == 0) {
          throw new IOException("Missing RF64 ds64 chunk");
        }
        byte[] extended = new byte[8];
        Binary.put64le(extended, 0, size);
        edits.add(new Rewrite.Edit(riffState.ds64, 8, extended));
      }
    }
    Rewrite.replace(path, edits);
  }


}
