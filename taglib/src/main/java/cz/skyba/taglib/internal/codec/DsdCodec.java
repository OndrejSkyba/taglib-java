// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.codec;

import cz.skyba.taglib.*;
import cz.skyba.taglib.internal.*;
import cz.skyba.taglib.internal.id3.Id3v2;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.FileStream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * DSF and DSDIFF/DST audio properties and embedded ID3v2 metadata.
 */
public final class DsdCodec implements Codec {
  private final Format selectedFormat;

  public DsdCodec() {
    selectedFormat = null;
  }

  public DsdCodec(Format format) {
    if (format != Format.DSF && format != Format.DSDIFF) {
      throw new IllegalArgumentException("DSD codec format");
    }
    selectedFormat = format;
  }

  /**
   * The constructor selected by AudioFile without another probe or payload read.
   */
  public Format format() {
    return selectedFormat;
  }

  private Dsf nativeDsf;
  private Dff nativeDff;
  private DsdiffEditor nativeEditor;
  private byte[] nativeId3 = new byte[0];

  public record DsfDetails(AudioProperties common, int formatVersion, int formatID,
                           int channelType, long sampleCount, int blockSizePerChannel) {
  }

  public record DffDetails(AudioProperties common, long sampleCount) {
  }

  private record ReadResult(AudioMetadata snapshot, DsfDetails dsfDetails, DffDetails dffDetails) {
  }

  /**
   * Native DSF Properties' available-byte conversions, shared with the file decoder.
   */
  public static DsfDetails dsfProperties(byte[] data) {
    var numeric = new ByteVector(data);
    int channels = (int) numeric.toUnsignedInt(12, false), rate = (int) numeric.toUnsignedInt(16, false),
      bits = (int) numeric.toUnsignedInt(20, false);
    long samples = numeric.toLong(24, false), unsignedRate = Integer.toUnsignedLong(rate);
    double duration = unsignedRate > 0 && samples > 0 ? samples * 1000.0 / unsignedRate : 0;
    long milliseconds = duration + 0.5 < 0xffffffffL ? (int) (long) (duration + 0.5) : 0;
    long nativeProduct = (Integer.toUnsignedLong(rate) * Integer.toUnsignedLong(bits)
      * Integer.toUnsignedLong(channels)) & 0xffffffffL;
    int bitrate = (int) (nativeProduct / 1000.0 + 0.5);
    return new DsfDetails(new AudioProperties(milliseconds, bitrate, rate, channels, bits, true),
      (int) numeric.toUnsignedInt(0, false), (int) numeric.toUnsignedInt(4, false),
      (int) numeric.toUnsignedInt(8, false), samples, (int) numeric.toUnsignedInt(32, false));
  }

  /**
   * Unsigned sample count is supplied as raw 64-bit bits, matching the native constructor.
   */
  public static DffDetails dffProperties(long sampleRate, int channels, long samples, int bitrate) {
    if (sampleRate < 0 || sampleRate > 0xffff_ffffL || channels < 0 || channels > 0xffff) {
      throw new IllegalArgumentException("DSDIFF unsigned sample rate/channel range");
    }
    int rate = (int) sampleRate;
    double count = samples >= 0 ? (double) samples : (double) (samples & Long.MAX_VALUE) + 0x1.0p63;
    double milliseconds = rate > 0 && samples != 0 ? count * 1000.0 / rate + 0.5 : 0;
    long length = milliseconds > 0 && milliseconds < Integer.MAX_VALUE ? (int) milliseconds : 0;
    return new DffDetails(new AudioProperties(length, bitrate, rate, channels, 1, true), samples);
  }

  public static DsfDetails readDsfProperties(BinaryFile file, ReadStyle style) throws IOException {
    var codec = new DsdCodec();
    return codec.readResult(file, new ReadOptions(true, style, ReadOptions.DEFAULT.maxMetadataBytes()),
      file.initialPosition(), true).dsfDetails;
  }

  public static DffDetails readDffProperties(BinaryFile file, ReadStyle style) throws IOException {
    var codec = new DsdCodec();
    return codec.readResult(file, new ReadOptions(true, style, ReadOptions.DEFAULT.maxMetadataBytes()),
      file.initialPosition(), false).dffDetails;
  }

  private record Dsf(long size, long metadata, byte[] format) {
  }

  record Chunk(String id, long offset, long size, int padding) {
  }

  record Dff(
    long declaredSize, List<Chunk> roots, Chunk prop, List<Chunk> properties, List<Chunk> diin) {
  }

  private static Dsf dsf(BinaryFile file, long start) throws IOException {
    if (!new String(file.readNative(start, 4), StandardCharsets.ISO_8859_1).equals("DSD ")) {
      throw new IOException("Invalid DSF signature");
    }
    byte[] field = file.readNative(start + 4, 8);
    if (field.length != 8 || Binary.u64le(field, 0) != 28) {
      throw new IOException("Invalid DSF header size");
    }
    field = file.readNative(start + 12, 8);
    if (field.length != 8) {
      throw new IOException("Truncated DSF size");
    }
    long size = Binary.u64le(field, 0);
    if (size < 0 || size > file.size()) {
      throw new IOException("Invalid DSF size");
    }
    field = file.readNative(start + 20, 8);
    if (field.length != 8) {
      throw new IOException("Truncated DSF metadata offset");
    }
    long metadata = Binary.u64le(field, 0);
    if (metadata < 0 || metadata > size) {
      throw new IOException("Invalid DSF metadata offset");
    }
    if (!new String(file.readNative(start + 28, 4), StandardCharsets.ISO_8859_1).equals("fmt ")) {
      throw new IOException("Missing DSF fmt");
    }
    field = file.readNative(start + 32, 8);
    if (field.length != 8 || Binary.u64le(field, 0) != 52) {
      throw new IOException("Invalid DSF fmt size");
    }
    return new Dsf(size, metadata, file.readNative(start + 40, 52));
  }

  private static List<Chunk> chunks(BinaryFile file, long start, long end) throws IOException {
    var result = new ArrayList<Chunk>();
    file.seekNative(start);
    long pos = start;
    while (pos <= end - 12) {
      String id = file.ascii(pos, 4);
      byte[] sizeField = file.readNative(pos + 4, 8);
      if (!id.chars().allMatch(c -> c >= 32 && c <= 126)) {
        throw new IOException("Invalid DSDIFF chunk ID");
      }
      long size = Binary.u64be(sizeField, 0);
      if (size < 0 || size > end - pos - 12) {
        throw new IOException("Truncated DSDIFF chunk");
      }
      long next = pos + 12 + size;
      int padding = (next & 1) != 0 && next < end && file.u8(next) == 0 ? 1 : 0;
      file.seekNative(next + padding);
      result.add(new Chunk(id, pos + 12, size, padding));
      pos = next + padding;
    }
    return result;
  }

  private static Dff dff(BinaryFile file, long start) throws IOException {
    // DSDIFF reads the three outer fields before validating its child chunks.
    file.readNative(start, 4);
    byte[] declared = file.readNative(start + 4, 8);
    long declaredSize = declared.length == 8 ? Binary.u64be(declared, 0) : 0;
    file.readNative(start + 12, 4);
    List<Chunk> roots = chunks(file, start + 16, file.size());
    Chunk prop = null;
    List<Chunk> properties = new ArrayList<>(), diin = new ArrayList<>();
    for (Chunk chunk : roots) {
      if (chunk.id.equals("PROP")) {
        // Native skips four marker bytes without checking their value or parent size.
        prop = chunk;
        properties.addAll(chunks(file, chunk.offset + 4, chunk.offset + chunk.size));
      } else if (chunk.id.equals("DIIN")) {
        diin.addAll(chunks(file, chunk.offset, chunk.offset + chunk.size));
      }
    }
    if (prop == null) {
      throw new IOException("Missing DSDIFF PROP chunk");
    }
    return new Dff(declaredSize, roots, prop, properties, diin);
  }

  private static boolean id3(Chunk chunk) {
    return chunk.id.equals("ID3 ") || chunk.id.equals("id3 ");
  }

  private static byte[] readTag(BinaryFile file, long offset, long maxSize, ReadOptions options)
    throws IOException {
    if (maxSize < 10) {
      return new byte[0];
    }
    byte[] header = file.read(offset, 10);
    if (!Binary.ascii(header, 0, 3).equals("ID3")) {
      return new byte[0];
    }
    int length = Id3v2.tagSize(header);
    if (length > options.maxMetadataBytes()) {
      throw new IOException("DSD metadata exceeds configured limit");
    }
    if (length > maxSize) {
      throw new IOException("Truncated DSD ID3 tag");
    }
    return file.read(offset, length);
  }

  @Override
  public AudioMetadata read(Path path, ReadOptions options) throws IOException {
    try (var file = new BinaryFile(path)) {
      return read(file, options);
    }
  }

  @Override
  public AudioMetadata read(BinaryFile file, ReadOptions options) throws IOException {
    return read(file, options, 0, file.ascii(0, 4).equals("DSD "));
  }

  @Override
  public AudioMetadata readNative(BinaryFile file, ReadOptions options) throws IOException {
    String name = file.requireStream().name().toLowerCase(Locale.ROOT);
    boolean isDsf = selectedFormat != null ? selectedFormat == Format.DSF :
      name.endsWith(".dsf")
        || (!name.endsWith(".dff")
        && !name.endsWith(".dsdiff")
        && new String(file.readNative(0, 4), StandardCharsets.ISO_8859_1).equals("DSD "));
    return read(file, options, file.initialPosition(), isDsf);
  }

  private AudioMetadata read(BinaryFile file, ReadOptions options, long start, boolean isDsf)
    throws IOException {
    return readResult(file, options, start, isDsf).snapshot;
  }

  private ReadResult readResult(BinaryFile file, ReadOptions options, long start, boolean isDsf)
    throws IOException {
    if (isDsf) {
      Dsf layout = dsf(file, start);
      nativeDsf = layout;
      byte[] tagBytes =
        layout.metadata == 0
          ? new byte[0]
          : readTag(file, layout.metadata, layout.size - layout.metadata, options);
      nativeId3 = tagBytes;
      if (file.stream() != null && layout.metadata != 0) {
        NativeTagIO.id3v2(file.stream(), layout.metadata);
      }
      var tag = Id3v2.read(tagBytes, options);
      DsfDetails details = dsfProperties(layout.format);
      return new ReadResult(new AudioMetadata(
        Format.DSF,
        tag.properties(),
        details.common(),
        tag.pictures(),
        tag.basic()), details, null);
    }
    Dff layout = dff(file, start);
    nativeDff = layout;
    Chunk tagChunk = id3Chunk(layout);
    nativeId3 =
      tagChunk == null ? new byte[0] : readTag(file, tagChunk.offset, tagChunk.size, options);
    if (file.stream() != null && tagChunk != null) {
      NativeTagIO.id3v2(file.stream(), tagChunk.offset);
    }
    var tag = Id3v2.read(nativeId3, options);
    PropertyMap properties = tag.properties();
    // Native always reads the PROP audio fields, even when it does not construct Properties.
    DffDetails details = audioDff(file, layout);
    AudioProperties decoded = details.common();
    PropertyMap diin = diinProperties(file, layout.diin, options);
    if (tag.empty()) {
      properties = diin;
    }
    return new ReadResult(new AudioMetadata(
      Format.DSDIFF,
      properties,
      options.readAudioProperties() ? decoded : AudioProperties.EMPTY,
      tag.pictures(),
      FlacCodec.fallback(tag.basic(), new Tag(diin, Format.DSDIFF))), null, details);
  }

  private static Chunk id3Chunk(Dff layout) {
    Chunk selected = null;
    for (Chunk chunk : layout.roots) {
      if (id3(chunk)) {
        selected = chunk;
      }
    }
    if (selected == null) {
      for (Chunk chunk : layout.properties) {
        if (id3(chunk)) {
          selected = chunk;
          break;
        }
      }
    }
    return selected;
  }

  private static DffDetails audioDff(BinaryFile file, Dff layout) throws IOException {
    int rate = 0, channels = 0;
    long rawSize = 0, audioSize = 0, frames = 0;
    int frameRate = 0;
    for (Chunk chunk : layout.roots) {
      if (chunk.id.equals("DSD ")) {
        rawSize = chunk.size;
        audioSize = chunk.size;
      } else if (chunk.id.equals("DST ")) {
        audioSize = chunk.size;
        long position = chunk.offset, end = chunk.offset + chunk.size;
        file.seekNative(position);
        while (position <= end - 12) {
          String id = new String(file.readNative(position, 4), StandardCharsets.ISO_8859_1);
          long size = new ByteVector(file.readNative(position + 4, 8)).toUnsignedLong(true);
          if (id.length() != 4 || !id.chars().allMatch(c -> c >= 32 && c <= 126)) {
            throw new IOException("Invalid DSDIFF DST chunk ID");
          }
          if (size < 0 || size > end - position - 12) {
            throw new IOException("Truncated DSDIFF DST chunk");
          }
          if (id.equals("FRTE")) {
            // Native reads both fields even across a short FRTE boundary, then stops.
            frames = new ByteVector(file.readNative(position + 12, 4)).toUnsignedInt(true);
            frameRate = new ByteVector(file.readNative(position + 16, 2)).toUnsignedShort(true);
            break;
          }
          long next = position + 12 + size;
          int padding = 0;
          if ((next & 1) != 0) {
            byte[] pad = file.readNative(next, 1);
            if (pad.length == 1 && pad[0] == 0) {
              padding = 1;
            }
          }
          file.seekNative(next + padding);
          position = next + padding;
        }
      }
    }
    for (Chunk chunk : layout.properties) {
      if (chunk.id.equals("FS  ")) {
        rate = (int) new ByteVector(file.readNative(chunk.offset, 4)).toUnsignedInt(0, 4, true);
      } else if (chunk.id.equals("CHNL")) {
        channels = Short.toUnsignedInt(new ByteVector(file.readNative(chunk.offset, 2)).toShort(0, true));
      }
    }
    long unsignedRate = Integer.toUnsignedLong(rate);
    // Native chunk/count arithmetic is unsigned 64-bit, including modular products
    // and integer division before the final int bitrate conversion.
    long samples = rawSize * 8;
    if (samples != 0) {
      if (channels > 0) {
        samples = Long.divideUnsigned(samples, channels);
      }
    } else if (frameRate > 0) {
      samples = Long.divideUnsigned(frames * unsignedRate, frameRate);
    }
    int kbps = samples != 0
      ? (int) Long.divideUnsigned(Long.divideUnsigned(audioSize * 8 * unsignedRate, samples), 1000)
      : 0;
    return dffProperties(unsignedRate, channels, samples, kbps);
  }

  @Override
  public void write(Path path, PropertyMap properties) throws IOException {
    write(path, properties, null);
  }

  @Override
  public void write(
    BinaryFile file, PropertyMap properties, List<Picture> pictures)
    throws IOException {
    if (file.path() != null) {
      try (var stream = new FileStream(file.path());
           var view = new BinaryFile(stream)) {
        var concrete = new DsdCodec();
        concrete.readNative(view, ReadOptions.TAGS_ONLY);
        concrete.writeNative(view, 0, properties, pictures);
      }
    } else {
      var stream = file.requireStream();
      long cursor = stream.tell();
      stream.seek(0);
      try (var view = new BinaryFile(file.stream())) {
        var concrete = new DsdCodec();
        concrete.readNative(view, ReadOptions.TAGS_ONLY);
        stream.seek(cursor);
        concrete.writeNative(view, 0, properties, pictures);
      }
    }
  }

  @Override
  public void writeNative(
    BinaryFile file,
    long constructorOffset,
    PropertyMap properties,
    List<Picture> pictures
  )
    throws IOException {
    var stream = file.requireStream();
    long cursor = stream.tell();
    String name = stream.name().toLowerCase(Locale.ROOT);
    boolean isDsf =
      name.endsWith(".dsf")
        || (!name.endsWith(".dff")
        && !name.endsWith(".dsdiff")
        && new String(file.readNative(0, 4), StandardCharsets.ISO_8859_1).equals("DSD "));
    if (isDsf) {
      Dsf layout = nativeDsf != null ? nativeDsf : dsf(file, constructorOffset);
      long offset = layout.metadata == 0 ? layout.size : layout.metadata;
      byte[] original =
        layout.metadata == 0
          ? new byte[0]
          : readTag(file, offset, layout.size - offset, ReadOptions.DEFAULT);
      byte[] replacement =
        Id3v2.renderNative(
          properties, nativeDsf != null ? nativeId3 : original, pictures, file.size());
      long newSize = offset + replacement.length;
      file.seekNative(cursor);
      if (layout.size != newSize) {
        stream.insert(new ByteVector(littleEndianLong(newSize)), 12, 8);
      }
      long newMetadata = replacement.length == 0 ? 0 : offset;
      if (layout.metadata != newMetadata) {
        stream.insert(new ByteVector(littleEndianLong(newMetadata)), 20, 8);
      }
      if (replacement.length == 0) {
        stream.truncate(newSize);
      } else {
        stream.insert(new ByteVector(replacement), offset, layout.size - offset);
      }
      nativeDsf = new Dsf(newSize, newMetadata, layout.format);
      nativeId3 = replacement;
      return;
    }
    if (nativeEditor == null) {
      nativeEditor = new DsdiffEditor(nativeDff != null ? nativeDff : dff(file, constructorOffset));
    }
    byte[] replacement = Id3v2.renderNative(properties, nativeId3, pictures, file.size());
    PropertyMap diin = NativeSaveState.dsdiffDiinProperties();
    if (diin == null) {
      diin = nativeEditor.diinProperties(file);
    }
    file.seekNative(cursor);
    nativeEditor.save(file, replacement, diin);
    nativeId3 = replacement;
  }

  public record NativeTags(Id3v2.TagData id3, PropertyMap diin, byte[] id3Bytes) {
  }

  static PropertyMap diinProperties(BinaryFile file, List<Chunk> chunks, ReadOptions options)
    throws IOException {
    var result = PropertyMap.builder();
    for (Chunk value : chunks) {
      if ((value.id.equals("DITI") || value.id.equals("DIAR")) && value.size >= 4) {
        long count = file.u32be(value.offset);
        if (count > value.size || count > options.maxMetadataBytes()) {
          continue;
        }
        byte[] bytes = file.readNative(value.offset + 4, (int) count);
        int nul = 0;
        while (nul < bytes.length && bytes[nul] != 0) {
          nul++;
        }
        result.put(
          value.id.equals("DITI") ? "TITLE" : "ARTIST",
          new String(bytes, 0, nul, StandardCharsets.ISO_8859_1));
      }
    }
    return result.build();
  }

  private static byte[] littleEndianLong(long value) {
    byte[] bytes = new byte[8];
    Binary.put64le(bytes, 0, value);
    return bytes;
  }
}
