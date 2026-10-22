// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.codec;

import cz.skyba.taglib.*;
import cz.skyba.taglib.internal.*;
import cz.skyba.taglib.internal.id3.Id3v2;
import cz.skyba.taglib.riff.wav.Properties;
import cz.skyba.taglib.toolkit.ByteVector;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

/// RIFF WAVE/RF64/BW64 and IFF AIFF/AIFC, with bounded metadata reads.
public final class RiffCodec implements Codec {
  private static final Set<String> LOSSLESS_AIFF_COMPRESSION =
    Set.of("NONE", "sowt", "twos", "fl32", "fl64", "FL32", "FL64", "in24", "in32");
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
  private static final Set<String> INFO_SUPPORTED_PROPERTY_KEYS = Set.copyOf(INFO_KEYS.values());

  private record Chunk(String id, long offset, long size, int padding) {
    long start() {
      return offset - 8;
    }

    long span() {
      return size + padding + 8;
    }
  }

  private record Layout(
    boolean aiff, boolean longForm, long ds64, long sizeOffset, List<Chunk> chunks) {
  }

  public static Set<String> infoSupportedPropertyKeys() {
    return INFO_SUPPORTED_PROPERTY_KEYS;
  }

  public record WavDetails(
    Properties properties,
    String iXMLData,
    byte[] BEXTData,
    boolean hasiXMLData,
    boolean hasBEXTData,
    boolean hasID3v2Tag,
    boolean hasInfoTag) {
    public WavDetails {
      BEXTData = BEXTData.clone();
    }

    @Override
    public byte[] BEXTData() {
      return BEXTData.clone();
    }
  }

  public record AiffDetails(AudioProperties common, long sampleFrames,
                            byte[] compressionType, String compressionName) {
    public AiffDetails {
      compressionType = compressionType.clone();
    }

    @Override
    public byte[] compressionType() {
      return compressionType.clone();
    }
  }

  /// Reuses native constructor layout/tag reads before retaining the AIFF properties.
  public static AiffDetails aiffDetails(BinaryFile file, ReadStyle style) throws IOException {
    var codec = new RiffCodec();
    var layout = layout(file, file.initialPosition(), true);
    return codec.readResult(file, new ReadOptions(true, style, ReadOptions.DEFAULT.maxMetadataBytes()), layout).aiffDetails;
  }

  private record ReadResult(AudioMetadata snapshot, AiffDetails aiffDetails) {
  }

  /// Parsed WAV payloads; like upstream the last duplicate broadcast chunk wins.
  public static WavDetails wavDetails(Path path, ReadOptions options) throws IOException {
    try (var file = new BinaryFile(path)) {
      return wavDetails(file, options);
    }
  }

  public static WavDetails wavDetails(BinaryFile file, ReadOptions options) throws IOException {

    return wavDetails(file, options, layout(file));
  }

  private static WavDetails wavDetails(BinaryFile file, ReadOptions options, Layout layout)
    throws IOException {
    if (layout.aiff) {
      throw new IOException("Expected WAV file");
    }
    String xml = "";
    byte[] bext = new byte[0], fmt = new byte[0];
    boolean hasXml = false, hasBext = false, hasId3 = false, hasInfo = false;
    long stream = 0, fact = 0;
    for (Chunk chunk : layout.chunks) {
      switch (chunk.id) {
        case "iXML" -> {
          xml = new String(data(file, chunk, options.maxMetadataBytes()), StandardCharsets.UTF_8);
          hasXml = true;
        }
        case "bext" -> {
          bext = data(file, chunk, options.maxMetadataBytes());
          hasBext = true;
        }
        case "ID3 ", "id3 " -> hasId3 = true;
        case "LIST" -> {
          if (info(file, chunk)) {
            hasInfo = true;
          }
        }
        case "fmt " -> {
          if (fmt.length == 0) {
            fmt = file.read(chunk.offset, (int) Math.min(chunk.size, 256));
          }
        }
        case "data" -> {
          if (stream == 0) {
            stream = chunk.size + chunk.padding;
          }
        }
        case "fact" -> {
          if (fact == 0 && chunk.size >= 4) {
            fact = file.u32le(chunk.offset);
          }
        }
        default -> {
        }
      }
    }
    return new WavDetails(
      Properties.fromChunks(fmt, stream, fact),
      xml,
      bext,
      hasXml,
      hasBext,
      hasId3,
      hasInfo);
  }

  private static Layout layout(BinaryFile file) throws IOException {
    String magic = file.ascii(0, 4), kind = file.ascii(8, 4);
    // Like TagLib's AIFF reader, tolerate a damaged FORM subtype and let bounded chunks decide what
    // is readable.
    boolean aiff = magic.equals("FORM");
    boolean longForm = magic.equals("RF64") || magic.equals("BW64");
    if (!aiff && (!(magic.equals("RIFF") || longForm) || !kind.equals("WAVE"))) {
      throw new IOException("Invalid RIFF/IFF audio signature");
    }
    return layout(file, 0, aiff);
  }

  private static Layout layout(BinaryFile file, long start, boolean aiff) throws IOException {
    String magic = aiff ? "" : new String(file.readNative(start, 4), StandardCharsets.ISO_8859_1);
    boolean longForm = magic.equals("RF64") || magic.equals("BW64");
    file.readNative(start + 4, 4);
    long pos = start + 12, dataSize = 0, ds64 = 0;
    var chunks = new ArrayList<Chunk>();
    while (pos <= file.size() - 8) {
      if (chunks.size() == 50_000) {
        throw new IOException("Too many RIFF chunks");
      }
      byte[] header = file.read(pos, 8);
      String id = Binary.ascii(header, 0, 4);
      if (!validId(id)) {
        break;
      }
      long size = aiff ? Binary.u32be(header, 4) : Binary.u32le(header, 4);
      if (longForm
        && chunks.isEmpty()
        && id.equals("ds64")
        && size >= 28
        && file.size() - pos >= 36) {
        ds64 = pos + 8;
        dataSize = file.u64le(ds64 + 8);
      }
      if (longForm && id.equals("data") && size == 0xffffffffL && dataSize != 0) {
        size = dataSize < 0 ? file.size() - pos - 8 : dataSize;
      }
      size = Math.min(size, file.size() - pos - 8);
      long end = pos + 8 + size;
      int padding = 0;
      if ((end & 1) != 0) {
        byte[] one = file.readNative(end, 1);
        if (one.length == 1
          && (one[0] == 0
          || validId(new String(file.readNative(end + 1, 4), StandardCharsets.ISO_8859_1)))) {
          padding = 1;
        }
      }
      chunks.add(new Chunk(id, pos + 8, size, padding));
      pos = end + padding;
    }
    return new Layout(aiff, longForm, ds64, start + 4, chunks);
  }

  public record NativeTags(Id3v2.TagData id3, cz.skyba.taglib.riff.info.Tag info, byte[] id3Bytes) {
  }

  public static NativeTags nativeTags(
    BinaryFile file, long constructorOffset, boolean aiff, ReadOptions options)
    throws IOException {
    Layout parsed = layout(file, constructorOffset, aiff);
    Id3v2.TagData id3 = Id3v2.read(new byte[0], options);
    var info = new cz.skyba.taglib.riff.info.Tag();
    boolean foundId3 = false, foundInfo = false;
    byte[] original = new byte[0];
    for (Chunk value : parsed.chunks) {
      if (!foundId3 && (value.id.equals("ID3 ") || value.id.equals("id3 "))) {
        original = data(file, value, options.maxMetadataBytes());
        id3 = Id3v2.read(original, options);
        foundId3 = true;
      } else if (!aiff && !foundInfo && info(file, value)) {
        info = new cz.skyba.taglib.riff.info.Tag(new ByteVector(
          data(file, value, options.maxMetadataBytes())));
        foundInfo = true;
      }
    }
    return new NativeTags(id3, info, original);
  }

  private static boolean validId(String id) {
    return id.length() == 4 && id.chars().allMatch(c -> c >= 32 && c <= 127);
  }

  private static byte[] data(BinaryFile file, Chunk chunk, int limit) throws IOException {
    if (chunk.size > limit) {
      throw new IOException("RIFF metadata exceeds configured limit");
    }
    return file.read(chunk.offset, (int) chunk.size);
  }

  private static boolean info(BinaryFile file, Chunk chunk) throws IOException {
    return chunk.id.equals("LIST") && chunk.size >= 4 && file.ascii(chunk.offset, 4).equals("INFO");
  }

  private static Map<String, byte[]> infoFields(byte[] data) {
    var fields = new TreeMap<String, byte[]>();
    int pos = 4;
    while (pos <= data.length - 8) {
      String id = Binary.ascii(data, pos, 4);
      long length = Binary.u32le(data, pos + 4);
      if (length > data.length - pos - 8) {
        break;
      }
      if (validId(id)) {
        fields.put(id, Arrays.copyOfRange(data, pos + 8, pos + 8 + (int) length));
      }
      pos += 8 + (int) length + (int) (length & 1);
    }
    return fields;
  }

  @Override
  public AudioMetadata read(Path path, ReadOptions options) throws IOException {
    try (var file = new BinaryFile(path)) {
      return read(file, options);
    }
  }

  @Override
  public AudioMetadata read(BinaryFile file, ReadOptions options) throws IOException {
    return read(file, options, layout(file));
  }

  @Override
  public AudioMetadata readNative(BinaryFile file, ReadOptions options) throws IOException {
    boolean aiff = nativeAiff(file);
    return read(file, options, layout(file, file.initialPosition(), aiff));
  }

  public static boolean nativeAiff(BinaryFile file) throws IOException {
    var stream = file.stream();
    long cursor = stream == null ? 0 : stream.tell();
    try {
      String name = stream == null ? "" : stream.name().toLowerCase(Locale.ROOT);
      return name.endsWith(".aif") || name.endsWith(".aiff") || name.endsWith(".aifc")
        || name.endsWith(".afc") || (!name.endsWith(".wav")
        && new String(file.readNative(0, 4), StandardCharsets.ISO_8859_1).equals("FORM"));
    } finally {
      if (stream != null) {
        stream.seek(cursor);
      }
    }
  }

  private AudioMetadata read(BinaryFile file, ReadOptions options, Layout layout) throws IOException {
    return readResult(file, options, layout).snapshot;
  }

  private ReadResult readResult(BinaryFile file, ReadOptions options, Layout layout) throws IOException {
    PropertyMap id3 = PropertyMap.builder().build(), info = PropertyMap.builder().build();
    Tag id3Basic = new Tag(id3);
    boolean id3Empty = true;
    List<Picture> pictures = List.of();
    boolean hasId3 = false, hasInfo = false;
    long totalMetadata = 0;
    for (Chunk chunk : layout.chunks) {
      if ((chunk.id.equals("ID3 ") || chunk.id.equals("id3 ")) && !hasId3) {
        totalMetadata += chunk.size;
        if (totalMetadata > options.maxMetadataBytes()) {
          throw new IOException("RIFF metadata exceeds configured limit");
        }
        var tag = Id3v2.read(data(file, chunk, options.maxMetadataBytes()), options);
        if (file.stream() != null) {
          NativeTagIO.id3v2(file.stream(), chunk.offset);
        }
        id3 = tag.properties();
        pictures = tag.pictures();
        hasId3 = true;
        id3Basic = tag.basic();
        id3Empty = tag.empty();
      } else if (!layout.aiff && chunk.id.equals("LIST")) {
        byte[] listData = data(file, chunk, options.maxMetadataBytes());
        if (hasInfo || listData.length < 4 || !Binary.ascii(listData, 0, 4).equals("INFO")) {
          continue;
        }
        totalMetadata += chunk.size;
        if (totalMetadata > options.maxMetadataBytes()) {
          throw new IOException("RIFF metadata exceeds configured limit");
        }
        info = new cz.skyba.taglib.riff.info.Tag(new ByteVector(listData)).properties();
        hasInfo = true;
      } else if (!layout.aiff && (chunk.id.equals("iXML") || chunk.id.equals("bext"))) {
        data(file, chunk, options.maxMetadataBytes());
      }
    }
    Format format = layout.aiff ? Format.AIFF : Format.WAV;
    AudioProperties common = AudioProperties.EMPTY;
    AiffDetails details = null;
    if (options.readAudioProperties()) {
      if (layout.aiff) {
        details = aiffAudio(file, layout);
        common = details.common();
      } else {
        common = audio(file, layout);
      }
    }
    return new ReadResult(new AudioMetadata(
      format,
      id3Empty ? info : id3,
      common,
      pictures,
      FlacCodec.fallback(id3Basic, new Tag(info, format))), details);
  }

  private static AudioProperties audio(BinaryFile file, Layout layout) throws IOException {
    byte[] format = null;
    long streamSize = 0, fact = 0;
    for (Chunk chunk : layout.chunks) {
      if (format == null && chunk.id.equals(layout.aiff ? "COMM" : "fmt ")) {
        format = file.read(chunk.offset, (int) chunk.size);
      } else if (streamSize == 0 && chunk.id.equals(layout.aiff ? "SSND" : "data")) {
        streamSize = chunk.size + chunk.padding;
      } else if (!layout.aiff && fact == 0 && chunk.id.equals("fact")) {
        byte[] bytes = file.read(chunk.offset, (int) chunk.size);
        fact = bytes.length >= 4 ? Binary.u32le(bytes, 0) : 0;
      }
    }
    if (format == null || streamSize == 0 || format.length < (layout.aiff ? 18 : 16)) {
      return AudioProperties.EMPTY;
    }
    return Properties.fromChunks(format, streamSize, fact).common();
  }

  private static AiffDetails aiffAudio(BinaryFile file, Layout layout) throws IOException {
    byte[] format = new byte[0];
    long streamSize = 0;
    for (Chunk chunk : layout.chunks) {
      if (chunk.id.equals("COMM") && format.length == 0) {
        format = file.read(chunk.offset, Math.toIntExact(chunk.size));
      } else if (chunk.id.equals("SSND") && streamSize == 0) {
        streamSize = (chunk.size + chunk.padding) & 0xffff_ffffL;
      }
    }
    return aiffFromChunks(format, streamSize);
  }

  /// Shared native AIFF arithmetic for the live chunk owner and snapshot adapter.
  public static AiffDetails aiffFromChunks(byte[] format, long streamSize) {
    if (format.length < 18 || streamSize == 0) {
      return new AiffDetails(AudioProperties.EMPTY, 0, new byte[0], "");
    }
    var data = new ByteVector(format);
    int channels = data.toShort(0), bits = data.toShort(6), rate = 0;
    long samples = data.toUnsignedInt(2, true), milliseconds = 0;
    int bitrate = 0;
    var sampleRate = data.toFloat80BE(8).toBigDecimal();
    var maximum = BigDecimal.valueOf(Integer.MAX_VALUE);
    var half = new BigDecimal("0.5");
    if (sampleRate.compareTo(BigDecimal.ONE) >= 0 && sampleRate.compareTo(maximum) < 0) {
      rate = extended(sampleRate.add(half)).intValue();
    }
    if (samples > 0 && rate > 0) {
      // Native promotes this quotient to Linux's 64-bit-significand long double.
      var duration = extended(BigDecimal.valueOf(samples * 1000)
        .divide(sampleRate, new MathContext(100, RoundingMode.HALF_EVEN)));
      if (duration.signum() > 0 && duration.compareTo(maximum) < 0) {
        milliseconds = extended(duration.add(half)).intValue();
        double bps = streamSize * 8.0 / duration.doubleValue();
        if (bps >= 0 && bps < Integer.MAX_VALUE) {
          bitrate = (int) (bps + 0.5);
        }
      }
    }
    byte[] type = format.length >= 23 ? data.mid(18, 4).data() : new byte[0];
    String name = format.length >= 23
      ? TextBytes.decodeTerminated(format, 23, Math.min(format[22] & 255, format.length - 23), ISO_8859_1) : "";
    // Lossless is the existing Java common-property extension, separate from isAiffC.
    String compression = format.length >= 22 ? Binary.ascii(format, 18, 4) : "NONE";
    boolean lossless = LOSSLESS_AIFF_COMPRESSION.contains(compression);
    return new AiffDetails(new AudioProperties(milliseconds, bitrate, rate, channels, bits, lossless),
      samples, type, name);
  }

  /// Round a bounded positive audio calculation to the pinned Linux long-double precision.
  private static BigDecimal extended(BigDecimal value) {
    if (value.signum() == 0) {
      return BigDecimal.ZERO;
    }
    BigInteger numerator = value.unscaledValue(), denominator = BigInteger.ONE;
    if (value.scale() >= 0) {
      denominator = BigInteger.TEN.pow(value.scale());
    } else {
      numerator = numerator.multiply(BigInteger.TEN.pow(-value.scale()));
    }
    int exponent = numerator.bitLength() - denominator.bitLength();
    if (exponent >= 0 ? numerator.compareTo(denominator.shiftLeft(exponent)) < 0
      : numerator.shiftLeft(-exponent).compareTo(denominator) < 0) {
      exponent--;
    }
    int shift = 63 - exponent;
    var scaledNumerator = shift >= 0 ? numerator.shiftLeft(shift) : numerator;
    var scaledDenominator = shift >= 0 ? denominator : denominator.shiftLeft(-shift);
    var quotient = scaledNumerator.divideAndRemainder(scaledDenominator);
    int comparison = quotient[1].shiftLeft(1).compareTo(scaledDenominator);
    var significand = comparison > 0 || comparison == 0 && quotient[0].testBit(0)
      ? quotient[0].add(BigInteger.ONE) : quotient[0];
    int power = exponent - 63;
    if (power >= 0) {
      return new BigDecimal(significand.shiftLeft(power));
    }
    return new BigDecimal(significand.multiply(BigInteger.valueOf(5).pow(-power)), -power);
  }

  @Override
  public void write(Path path, PropertyMap properties) throws IOException {
    write(path, properties, null);
  }

  @Override
  public void write(Path path, PropertyMap properties, List<Picture> pictures)
    throws IOException {
    write(path, properties, pictures, null, null, true);
  }

  @Override
  public void write(
    BinaryFile file, PropertyMap properties, List<Picture> pictures)
    throws IOException {
    write(file, properties, pictures, null, null, true);
  }

  /// Null payloads preserve their values; rewriteTags=false edits only WAV broadcast chunks.
  public void write(
    Path path,
    PropertyMap properties,
    List<Picture> pictures,
    String xml,
    byte[] bext,
    boolean rewriteTags
  )
    throws IOException {
    try (var file = new BinaryFile(path)) {
      write(file, properties, pictures, xml, bext, rewriteTags);
    }
  }

  public void write(
    BinaryFile file,
    PropertyMap properties,
    List<Picture> pictures,
    String xml,
    byte[] bext,
    boolean rewriteTags
  )
    throws IOException {
    Layout layout = layout(file);
    write(file, properties, pictures, xml, bext, rewriteTags, layout);
  }

  @Override
  public void writeNative(
    BinaryFile file,
    long constructorOffset,
    PropertyMap properties,
    List<Picture> pictures
  )
    throws IOException {
    write(
      file,
      properties,
      pictures,
      null,
      null,
      true,
      layout(file, constructorOffset, nativeAiff(file)));
  }

  private void write(
    BinaryFile file,
    PropertyMap properties,
    List<Picture> pictures,
    String xml,
    byte[] bext,
    boolean rewriteTags,
    Layout layout
  )
    throws IOException {
    var edits = new ArrayList<Rewrite.Edit>();
    // Native setChunkData cannot add a chunk when there is no recognized anchor.
    if (layout.chunks.isEmpty()) {
      return;
    }
    if (rewriteTags
      && layout.chunks.stream()
      .allMatch(chunk -> chunk.id.equals("ID3 ") || chunk.id.equals("id3 "))) {
      if (file.stream() != null) {
        removeOnlyTagChunks(file, layout);
        return;
      }
      // Native removes uppercase ID3 chunks backwards, then lowercase chunks backwards.
      // Removing the final chunk leaves no insertion anchor and does not update RIFF size.
      for (Chunk chunk : layout.chunks) {
        edits.add(new Rewrite.Edit(chunk.start(), chunk.span(), new byte[0]));
      }
      if (layout.chunks.size() > 1) {
        Chunk finalRemoved =
          layout.chunks.stream()
            .filter(chunk -> chunk.id.equals("id3 "))
            .findFirst()
            .orElse(layout.chunks.getFirst());
        byte[] size = new byte[4];
        if (layout.aiff) {
          Binary.put32be(size, 0, finalRemoved.size + finalRemoved.padding + 12);
        } else {
          Binary.put32le(size, 0, finalRemoved.size + finalRemoved.padding + 12);
        }
        edits.add(new Rewrite.Edit(layout.sizeOffset, 4, size));
      }
    } else {
      byte[] originalId3 = new byte[0];
      var fields = new TreeMap<String, byte[]>();
      boolean foundId3 = false, foundInfo = false;
      for (Chunk chunk : layout.chunks) {
        if (rewriteTags && (chunk.id.equals("ID3 ") || chunk.id.equals("id3 "))) {
          if (!foundId3) {
            originalId3 = data(file, chunk, ReadOptions.DEFAULT.maxMetadataBytes());
          }
          foundId3 = true;
          edits.add(new Rewrite.Edit(chunk.start(), chunk.span(), new byte[0]));
        } else if (rewriteTags && !layout.aiff && info(file, chunk)) {
          if (!foundInfo) {
            fields.putAll(infoFields(data(file, chunk, ReadOptions.DEFAULT.maxMetadataBytes())));
          }
          foundInfo = true;
          edits.add(new Rewrite.Edit(chunk.start(), chunk.span(), new byte[0]));
        } else if (!layout.aiff && (chunk.id.equals("iXML") || chunk.id.equals("bext"))) {
          edits.add(new Rewrite.Edit(chunk.start(), chunk.span(), new byte[0]));
        }
      }
      if (!layout.aiff) {
        WavDetails original = wavDetails(file, ReadOptions.DEFAULT, layout);
        if (xml == null) {
          xml = original.iXMLData;
        }
        if (bext == null) {
          bext = original.BEXTData;
        }
      } else if (xml != null || bext != null) {
        throw new IOException("Broadcast payload API requires WAV");
      }
      long appendOffset =
        layout.chunks.isEmpty()
          ? 12
          : layout.chunks.getLast().offset
          + layout.chunks.getLast().size
          + layout.chunks.getLast().padding;
      long relocatedAppendOffset =
        appendOffset - edits.stream().mapToLong(Rewrite.Edit::removeLength).sum();
      var appended = new ByteArrayOutputStream();
      // A final odd-sized chunk may omit its pad at EOF. Once another chunk follows,
      // restore alignment without counting that pad as encoded media or tag bytes.
      if (!layout.aiff) {
        if (bext.length != 0) {
          appended.writeBytes(chunk("bext", bext, false));
        }
        if (!xml.isEmpty()) {
          appended.writeBytes(chunk("iXML", xml.getBytes(StandardCharsets.UTF_8), false));
        }
      }
      byte[] id3 =
        rewriteTags
          ? Id3v2.renderNative(properties, originalId3, pictures, file.size())
          : new byte[0];
      if (id3.length > 0) {
        appended.writeBytes(chunk("ID3 ", id3, layout.aiff));
      }
      if (rewriteTags && !layout.aiff) {
        var infoTag = NativeSaveState.riffInfoTag();
        if (infoTag == null) {
          var payload = new ByteArrayOutputStream();
          payload.writeBytes(Binary.bytes("INFO"));
          for (var field : fields.entrySet()) {
            payload.writeBytes(chunk(field.getKey(), field.getValue(), false));
          }
          infoTag = new cz.skyba.taglib.riff.info.Tag(new ByteVector(payload.toByteArray()));
          if (!NativeSaveState.nativeSession()) {
            infoTag.replaceProperties(properties);
          }
        }
        if (!infoTag.isEmpty()) {
          // Upstream writes a zero-length LIST when fields exist but render is empty.
          appended.writeBytes(chunk("LIST", infoTag.render().data(), false));
        }
      }
      byte[] appendedBytes = appended.toByteArray();
      if (appendedBytes.length > 0 && (relocatedAppendOffset & 1) != 0) {
        byte[] padded = new byte[appendedBytes.length + 1];
        System.arraycopy(appendedBytes, 0, padded, 1, appendedBytes.length);
        appendedBytes = padded;
      }
      if (appendedBytes.length != 0) {
        edits.add(new Rewrite.Edit(appendOffset, 0, appendedBytes));
      }
      if (edits.isEmpty()) {
        file.seekNative(file.initialPosition());
        return;
      }
      // Native derives RIFF size from its final recognized chunk, excluding an invalid tail.
      long newSize =
        appendOffset
          + edits.stream()
          .mapToLong(edit -> edit.insertion().length - edit.removeLength())
          .sum();
      byte[] size = new byte[4];
      if (layout.aiff) {
        Binary.put32be(size, 0, newSize - layout.sizeOffset - 4);
      } else {
        Binary.put32le(size, 0, layout.longForm ? 0xffffffffL : newSize - layout.sizeOffset - 4);
      }
      edits.add(new Rewrite.Edit(layout.sizeOffset, 4, size));
      if (layout.longForm && layout.ds64 > 0) {
        byte[] longSize = new byte[8];
        Binary.put64le(longSize, 0, newSize - layout.sizeOffset - 4);
        edits.add(new Rewrite.Edit(layout.ds64, 8, longSize));
      }
    }

    Rewrite.replace(file, edits);
    if (file.stream() != null
      && edits.stream().anyMatch(edit -> edit.offset() == layout.sizeOffset)) {
      file.seekNative(layout.longForm && layout.ds64 > 0 ? layout.ds64 + 8 : layout.sizeOffset + 4);
    }
  }

  /// removeChunk(name) removes backwards, updating the size until the final anchor disappears.
  private static void removeOnlyTagChunks(BinaryFile file, Layout layout) {
    var remaining = new ArrayList<>(layout.chunks);
    for (String id : List.of("ID3 ", "id3 ")) {
      for (int i = remaining.size() - 1; i >= 0; i--) {
        Chunk removed = remaining.get(i);
        if (!removed.id.equals(id)) {
          continue;
        }
        file.stream().removeBlock(removed.start(), removed.span());
        remaining.remove(i);
        for (int j = i; j < remaining.size(); j++) {
          Chunk moved = remaining.get(j);
          remaining.set(
            j, new Chunk(moved.id, moved.offset - removed.span(), moved.size, moved.padding));
        }
        if (!remaining.isEmpty()) {
          Chunk first = remaining.getFirst(), last = remaining.getLast();
          long total = last.offset + last.size + last.padding - first.offset + 12;
          byte[] size = new byte[4];
          if (layout.aiff) {
            Binary.put32be(size, 0, total);
          } else {
            Binary.put32le(size, 0, layout.longForm ? 0xffffffffL : total);
          }
          file.stream().insert(new ByteVector(size), layout.sizeOffset, 4);
          if (layout.longForm && layout.ds64 > 0) {
            byte[] longSize = new byte[8];
            Binary.put64le(longSize, 0, total);
            file.stream().insert(new ByteVector(longSize), layout.ds64, 8);
          }
        }
      }
    }
  }

  private static byte[] chunk(String id, byte[] payload, boolean bigEndian) {
    byte[] result = new byte[8 + payload.length + (payload.length & 1)];
    System.arraycopy(Binary.bytes(id), 0, result, 0, 4);
    if (bigEndian) {
      Binary.put32be(result, 4, payload.length);
    } else {
      Binary.put32le(result, 4, payload.length);
    }
    System.arraycopy(payload, 0, result, 8, payload.length);
    return result;
  }
}
