// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.codec;

import cz.skyba.taglib.*;
import cz.skyba.taglib.internal.Binary;
import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.Codec;
import cz.skyba.taglib.internal.xiph.OggPages;
import cz.skyba.taglib.internal.xiph.XiphComment;
import cz.skyba.taglib.ogg.OggFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/// Ogg Vorbis, Opus, Speex and the current and historical FLAC mappings.
public final class OggCodec implements Codec {
  private final Format preferred;
  private final boolean tryVorbis;
  private OggPages.Reader nativeReader;
  private Metadata nativeMetadata;
  private boolean picturesRemoved;
  private OggPages.Headers nativeHeaders;

  /// Native Vorbis/Opus/Speex identification values alongside their common projection.
  public record Details(AudioProperties common, int version, int bitrateMaximum,
                        int bitrateNominal, int bitrateMinimum, int inputSampleRate, int outputGain,
                        int speexMode, boolean speexVbr) {
    private static final Details EMPTY = new Details(AudioProperties.EMPTY, 0, 0, 0, 0, 0, 0, 0, false);
  }

  private interface PropertySource {
    byte[] packet(int index) throws IOException;

    long length();

    Long firstGranule() throws IOException;

    Long lastGranule() throws IOException;
  }

  /// Snapshot construction reads the specialized codec directly and retains no file handle.
  public static Details readProperties(BinaryFile file, Format format, ReadStyle style)
    throws IOException {
    Objects.requireNonNull(style);
    try (var reader = new OggPages.Reader(file)) {
      // Match File(readProperties=false) before constructing a native Properties.
      // Explicit Properties still decodes a native File whose metadata is invalid.
      int maximum = ReadOptions.DEFAULT.maxMetadataBytes();
      if (format == Format.OGG_VORBIS) {
        reader.selectVorbis();
        reader.packet(1, maximum);
      } else {
        byte[] identification = reader.packet(0, maximum);
        String magic = format == Format.OGG_OPUS ? "OpusHead" : "Speex   ";
        if (OggPages.starts(identification, 0, magic)) {
          reader.packet(1, maximum);
        }
      }
      return details(file, reader, format);
    }
  }

  /// Existing-file construction reuses its packet index and exact native cursor effects.
  public static Details readProperties(OggFile file, Format format, ReadStyle style)
    throws IOException {
    Objects.requireNonNull(file);
    Objects.requireNonNull(style);
    return details(new PropertySource() {
      public byte[] packet(int index) {
        return file.packet(index).data();
      }

      public long length() {
        return file.length();
      }

      public Long firstGranule() {
        var p = file.firstPageHeader();
        return p == null ? null : p.absoluteGranularPosition();
      }

      public Long lastGranule() {
        var p = file.lastPageHeader();
        return p == null ? null : p.absoluteGranularPosition();
      }
    }, format);
  }

  public OggCodec() {
    this(null);
  }

  public OggCodec(Format preferred) {
    this(preferred, false);
  }

  public OggCodec(Format preferred, boolean tryVorbis) {
    this.preferred = preferred;
    this.tryVorbis = tryVorbis;
  }

  private static Format detected(BinaryFile file) throws IOException {
    long cursor = file.stream() == null ? 0 : file.stream().tell();
    try {
      return OggPages.detect(file);
    } finally {
      file.seekNative(cursor);
    }
  }

  private Format format(BinaryFile file) throws IOException {
    return preferred == null ? detected(file) : preferred;
  }

  /// Retains extension-selected construction and the native .oga FLAC-then-Vorbis retry.
  public Format fileRefFormat(BinaryFile file) throws IOException {
    return format(file);
  }

  public boolean fileRefTryVorbis() {
    return tryVorbis;
  }

  public boolean consumePicturesRemoved() {
    boolean result = picturesRemoved;
    picturesRemoved = false;
    return result;
  }

  @Override
  public PropertyMap unsupportedProperties(PropertyMap properties) {
    return XiphComment.unsupportedProperties(properties);
  }

  private record Metadata(
    OggPages.Packet packet, XiphComment comment, byte[] streamInfo, long overhead) {
  }

  private static Metadata metadata(OggPages.Headers headers) throws IOException {
    var packets = headers.packets();
    return switch (headers.format()) {
      case OGG_VORBIS -> {
        byte[] data = packets.get(1).data();
        if (!OggPages.starts(data, 0, "\u0003vorbis")) {
          throw new IOException("Invalid Vorbis comment header");
        }
        yield new Metadata(
          packets.get(1),
          XiphComment.parse(Arrays.copyOfRange(data, 7, data.length)),
          null,
          packets.stream().mapToLong(packet -> packet.data().length).sum());
      }
      case OGG_OPUS -> {
        byte[] data = packets.get(1).data();
        if (!OggPages.starts(data, 0, "OpusTags")) {
          throw new IOException("Invalid Opus comment header");
        }
        yield new Metadata(
          packets.get(1),
          XiphComment.parse(Arrays.copyOfRange(data, 8, data.length)),
          null,
          packets.getFirst().data().length + data.length);
      }
      case OGG_SPEEX -> new Metadata(
        packets.get(1),
        XiphComment.parse(packets.get(1).data()),
        null,
        packets.getFirst().data().length + packets.get(1).data().length);
      case OGG_FLAC -> {
        byte[] streamInfo = null;
        XiphComment comment = XiphComment.empty();
        OggPages.Packet commentPacket = null;
        long overhead = 0;
        for (int i = 0; i < packets.size(); i++) {
          byte[] data = packets.get(i).data();
          if (i == 0 && OggPages.starts(data, 0, "fLaC")) {
            continue;
          }
          int offset = i == 0 ? 13 : 0;
          if (i == 0
            && (data.length < 17
            || (data[5] != 1 && data[6] != 0)
            || !OggPages.starts(data, 9, "fLaC"))) {
            throw new IOException("Invalid Ogg FLAC mapping header");
          }
          if (data.length < offset + 4) {
            throw new IOException("Truncated Ogg FLAC block");
          }
          int type = data[offset] & 0x7f;
          int length =
            ((data[offset + 1] & 255) << 16)
              | ((data[offset + 2] & 255) << 8)
              | (data[offset + 3] & 255);
          overhead += length;
          if (streamInfo == null && type != 0) {
            throw new IOException("Missing initial STREAMINFO");
          }
          int payloadEnd = Math.min(data.length, offset + 4 + length);
          if (type == 0 && streamInfo == null) {
            streamInfo =
              Arrays.copyOfRange(data, offset + 4, payloadEnd);
          } else if (type == 4) {
            comment =
              XiphComment.parse(
                Arrays.copyOfRange(
                  data, offset + 4, payloadEnd));
            commentPacket = packets.get(i);
          }
        }
        if (streamInfo == null) {
          throw new IOException("Missing Ogg FLAC STREAMINFO");
        }
        yield new Metadata(commentPacket, comment, streamInfo, overhead);
      }
      default -> throw new IOException("Unsupported Ogg codec");
    };
  }

  @Override
  public AudioMetadata read(Path path, ReadOptions options) throws IOException {
    try (var file = new BinaryFile(path)) {
      return read(file, options);
    }
  }

  @Override
  public AudioMetadata read(BinaryFile file, ReadOptions options) throws IOException {
    Format selected = format(file);
    try (var reader = new OggPages.Reader(file)) {
      return read(file, reader, selected, options);
    } catch (IOException failure) {
      if (preferred == null) {
        throw failure;
      }
      if (tryVorbis) {
        try (var reader = new OggPages.Reader(file)) {
          return read(file, reader, Format.OGG_VORBIS, options);
        } catch (IOException ignored) {
        }
      }
      // The snapshot facade follows AudioFile's fallback after an invalid extension constructor.
      Format detected = OggPages.detect(file);
      try (var reader = new OggPages.Reader(file)) {
        return read(file, reader, detected, options);
      }
    }
  }

  private AudioMetadata read(
    BinaryFile file, OggPages.Reader reader, Format selected, ReadOptions options)
    throws IOException {
    var headers = OggPages.headers(reader, selected, options.maxMetadataBytes());
    var metadata = metadata(headers);
    AudioProperties audio = AudioProperties.EMPTY;
    if (options.readAudioProperties()) {
      audio =
        selected == Format.OGG_FLAC
          ? FlacCodec.audio(metadata.streamInfo, file.size() - metadata.overhead)
          : details(file, reader, headers.format()).common();
    }
    nativeHeaders = headers;
    nativeMetadata = metadata;
    return new AudioMetadata(
      selected, metadata.comment.properties(), audio, metadata.comment.pictures());
  }

  @Override
  public AudioMetadata readNative(BinaryFile file, ReadOptions options) throws IOException {
    nativeReader = new OggPages.Reader(file);
    try {
      return read(file, nativeReader, format(file), options);
    } catch (IOException failure) {
      if (!tryVorbis) {
        throw failure;
      }
      nativeReader.reset();
      return read(file, nativeReader, Format.OGG_VORBIS, options);
    }
  }

  private static Details details(BinaryFile file, OggPages.Reader reader, Format format)
    throws IOException {
    return details(new PropertySource() {
      public byte[] packet(int index) {
        return reader.packet(index);
      }

      public long length() {
        return file.size();
      }

      public Long firstGranule() throws IOException {
        var p = reader.first();
        return p == null ? null : p.granule();
      }

      public Long lastGranule() throws IOException {
        var p = reader.last();
        return p == null ? null : p.granule();
      }
    }, format);
  }

  /// Shares native identification parsing, signedness, partial state and granule arithmetic.
  private static Details details(PropertySource source, Format format) throws IOException {
    byte[] id = source.packet(0);
    int rate, channels, preSkip = 0, nominal = 0, maximum = 0, minimum = 0;
    int version, inputRate = 0, gain = 0, speexMode = 0;
    boolean speexVbr = false;
    switch (format) {
      case OGG_VORBIS -> {
        if (id.length < 28 || !OggPages.starts(id, 0, "\u0001vorbis")) {
          return Details.EMPTY;
        }
        version = (int) Binary.u32le(id, 7);
        channels = id[11] & 255;
        rate = (int) Binary.u32le(id, 12);
        maximum = (int) Binary.u32le(id, 16);
        nominal = (int) Binary.u32le(id, 20);
        minimum = (int) Binary.u32le(id, 24);
      }
      case OGG_OPUS -> {
        version = id.length > 8 ? id[8] & 255 : 0;
        channels = id.length > 9 ? id[9] & 255 : 0;
        rate = 48_000;
        preSkip = availableLittleEndian(id, 10, 2);
        inputRate = availableLittleEndian(id, 12, 4);
        gain = (short) availableLittleEndian(id, 16, 2);
      }
      case OGG_SPEEX -> {
        if (id.length < 64) {
          return Details.EMPTY;
        }
        version = (int) Binary.u32le(id, 28);
        rate = (int) Binary.u32le(id, 36);
        speexMode = (int) Binary.u32le(id, 40);
        channels = (int) Binary.u32le(id, 48);
        nominal = (int) Binary.u32le(id, 52);
        speexVbr = Binary.u32le(id, 60) == 1;
      }
      default -> throw new IOException("Unsupported Ogg audio properties");
    }
    Long first = source.firstGranule(), last = source.lastGranule();
    double duration = 0, bitrate = 0;
    if (first != null && last != null && rate > 0 && first >= 0 && last >= 0) {
      // Only a positive difference can produce properties. Avoid signed underflow on
      // extreme decreasing Opus granules (where native signed subtraction is undefined).
      long difference = last > first ? last - first : 0;
      long samples = difference > preSkip ? difference - preSkip : 0;
      if (samples > 0) {
        duration = samples * 1000.0 / rate;
        long length = source.length();
        for (int i = 0; i < (format == Format.OGG_VORBIS ? 3 : 2); i++) {
          length -= source.packet(i).length;
        }
        if (duration > 0 && duration < Integer.MAX_VALUE) {
          bitrate = length * 8.0 / duration;
        }
      }
    }
    long milliseconds = duration > 0 && duration < Integer.MAX_VALUE ? (int) (duration + 0.5) : 0;
    int kbps = bitrate >= 0 && bitrate < Integer.MAX_VALUE ? (int) (bitrate + 0.5) : 0;
    if (kbps == 0 && nominal > 0) {
      kbps = (int) (nominal / 1000.0 + 0.5);
    }
    return new Details(new AudioProperties(milliseconds, kbps, rate, channels, 0, false),
      version, maximum, nominal, minimum, inputRate, gain, speexMode, speexVbr);
  }

  /// Native ByteVector numeric conversions decode an available suffix, without sign extension.
  private static int availableLittleEndian(byte[] data, int offset, int width) {
    int value = 0;
    for (int i = 0; i < width && offset + i < data.length; i++) {
      value |= (data[offset + i] & 255) << (8 * i);
    }
    return value;
  }

  @Override
  public void write(Path path, PropertyMap properties) throws IOException {
    write(path, properties, null);
  }

  @Override
  public void write(
    BinaryFile file, PropertyMap properties, List<Picture> pictures)
    throws IOException {
    try (var reader = new OggPages.Reader(file, false)) {
      nativeReader = reader;
      try {
        try {
          read(file, reader, format(file), ReadOptions.TAGS_ONLY);
        } catch (IOException failure) {
          if (preferred == null) {
            throw failure;
          }
          reader.reset();
          if (tryVorbis) {
            try {
              read(file, reader, Format.OGG_VORBIS, ReadOptions.TAGS_ONLY);
            } catch (IOException ignored) {
              reader.reset();
              read(file, reader, detected(file), ReadOptions.TAGS_ONLY);
            }
          } else {
            read(file, reader, detected(file), ReadOptions.TAGS_ONLY);
          }
        }
        writeNative(file, file.initialPosition(), properties, pictures);
      } finally {
        nativeReader = null;
      }
    }
  }

  @Override
  public void writeNative(
    BinaryFile file,
    long origin,
    PropertyMap properties,
    List<Picture> pictures
  )
    throws IOException {
    if (nativeReader == null) {
      readNative(file, ReadOptions.TAGS_ONLY);
    }
    Format format = nativeHeaders.format();
    if (format == Format.OGG_FLAC && nativeMetadata.packet == null) {
      throw new IOException("Ogg FLAC stream has no comment packet");
    }
    byte[] comment =
      nativeMetadata.comment.render(
        properties, format == Format.OGG_VORBIS || format == Format.OGG_SPEEX, pictures);
    int index =
      format == Format.OGG_FLAC ? nativeHeaders.packets().indexOf(nativeMetadata.packet) : 1;
    byte[] prefix =
      switch (format) {
        case OGG_VORBIS -> Binary.bytes("\u0003vorbis");
        case OGG_OPUS -> Binary.bytes("OpusTags");
        case OGG_SPEEX -> new byte[0];
        case OGG_FLAC -> {
          if (comment.length > 0xffffff) {
            picturesRemoved = true;
            comment = nativeMetadata.comment.render(properties, false, List.of());
            if (comment.length > 0xffffff) {
              throw new IOException("Ogg FLAC comment exceeds 24-bit block size");
            }
          }
          yield new byte[]{
            (byte) (index == nativeHeaders.packets().size() - 1 ? 0x84 : 4),
            (byte) (comment.length >>> 16),
            (byte) (comment.length >>> 8),
            (byte) comment.length
          };
        }
        default -> throw new IOException("Unsupported Ogg format");
      };
    byte[] replacement = Arrays.copyOf(prefix, prefix.length + comment.length);
    System.arraycopy(comment, 0, replacement, prefix.length, comment.length);
    nativeReader.replace(index, replacement);
    nativeMetadata =
      new Metadata(
        nativeMetadata.packet,
        XiphComment.parse(comment),
        nativeMetadata.streamInfo,
        nativeMetadata.overhead);
  }
}
