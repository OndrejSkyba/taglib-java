// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.codec;

import cz.skyba.taglib.*;
import cz.skyba.taglib.internal.*;
import cz.skyba.taglib.internal.ape.ApeTag;
import cz.skyba.taglib.internal.id3.Id3v1;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.FileStream;
import cz.skyba.taglib.toolkit.IOStream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static cz.skyba.taglib.internal.Binary.*;

/// APE-tagged Monkey's Audio, WavPack and Musepack containers.
public final class ApeCodec implements Codec {
  private static final int[] WAVPACK_RATES = {
    6000, 8000, 9600, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 64000, 88200, 96000,
    192000, 0
  };
  private static final int[] MUSEPACK_RATES = {44100, 48000, 37800, 32000, 0, 0, 0, 0};

  private final Format format;

  public ApeCodec(Format format) {
    this.format = format;
  }

  public Format format() {
    return format;
  }

  @Override
  public PropertyMap unsupportedProperties(PropertyMap properties) {
    return new cz.skyba.taglib.ape.Tag().replaceProperties(properties).rejected();
  }

  @Override
  public AudioMetadata read(Path path, ReadOptions options) throws IOException {
    try (var f = new BinaryFile(path)) {
      return read(f, options);
    }
  }

  @Override
  public AudioMetadata read(BinaryFile f, ReadOptions options) throws IOException {
    ApeTag.Data ape = ApeTag.read(f, options);
    PropertyMap legacy =
      f.size() >= 128 && f.ascii(f.size() - 128, 3).equals("TAG")
        ? Id3v1.read(f.read(f.size() - 128, 128))
        : PropertyMap.EMPTY;
    PropertyMap props = ape.items().isEmpty() ? legacy : ape.properties();
    long begin = audioStart(f, format);
    long end = ape.offset();
    AudioProperties audio = AudioProperties.EMPTY;
    if (options.readAudioProperties()) {
      audio =
        switch (format) {
          case APE -> monkey(f, begin, end - begin).common();
          case WAVPACK -> wavpack(f, end).common();
          case MPC -> musepack(f, begin, end - begin).common();
          default -> AudioProperties.EMPTY;
        };
    }
    return new AudioMetadata(
      format, props, audio, ape.pictures(), MpegCodec.mergeTags(ape.basic(), new Tag(legacy)));
  }

  @Override
  public void write(Path path, PropertyMap properties) throws IOException {
    write(path, properties, null);
  }

  @Override
  public AudioMetadata readNative(BinaryFile f, ReadOptions options) throws IOException {
    AudioMetadata result = read(f, options);
    if (f.stream() == null) {
      return result;
    }
    var stream = f.stream();
    long begin = audioStart(f, format);
    long v1 = NativeTagIO.id3v1(stream);
    long footer = NativeTagIO.ape(stream, v1);
    if (!options.readAudioProperties()) {
      return result;
    }
    long end = footer >= 0 ? ApeTag.read(f, options).offset() : v1 >= 0 ? v1 : f.size();
    switch (format) {
      case APE -> {
        byte[] header = NativeTagIO.read(stream, begin, 6);
        long at = begin;
        if (header.length < 6 || !ascii(header, 0, 4).equals("MAC ")) {
          at = findMarker(f, "MAC ", begin, f.size(), false);
          if (at < 0) {
            break;
          }
          header = NativeTagIO.read(stream, at, 6);
        }
        if (header.length < 6 || !ascii(header, 0, 4).equals("MAC ")) {
          break;
        }
        if (u16le(header, 4) >= 3980) {
          byte[] descriptor = NativeTagIO.read(stream, at + 8, 44);
          if (descriptor.length == 44) {
            NativeTagIO.read(stream, at + 52 + ((u32le(descriptor, 0) - 52) & 0xffffffffL), 24);
          }
        } else {
          byte[] old = NativeTagIO.read(stream, at + 6, 26);
          if (old.length == 26 && u32le(old, 18) != 0) {
            NativeTagIO.read(stream, at + 48, 28);
          }
        }
      }
      case MPC -> nativeMpcProperties(stream, begin, end - begin);
      case WAVPACK -> nativeWavpackProperties(f, end);
      default -> {
      }
    }
    return result;
  }

  private static void nativeMpcProperties(
    IOStream stream, long begin, long length) throws IOException {
    byte[] magic = NativeTagIO.read(stream, begin, 4);
    if (magic.length != 4 || !ascii(magic, 0, 4).equals("MPCK")) {
      stream.readBlock(52);
      return;
    }
    while (true) {
      byte[] type = stream.readBlock(2).toByteArray();
      long size = 0;
      int sizeLength = 0;
      while (true) {
        byte[] next = stream.readBlock(1).toByteArray();
        if (next.length == 0) {
          return;
        }
        size = (size << 7) | (next[0] & 127);
        sizeLength++;
        if (next[0] >= 0) {
          break;
        }
      }
      long headerSize = 2L + sizeLength, offset = stream.tell();
      if (Long.compareUnsigned(size, headerSize) < 0 || offset < 0 || offset > length) {
        return;
      }
      long dataSize = size - headerSize;
      if (Long.compareUnsigned(dataSize, length - offset) > 0
        || Long.compareUnsigned(dataSize, 0xffffffffL) > 0) {
        return;
      }
      byte[] data = stream.readBlock(dataSize).toByteArray();
      if (data.length != dataSize) {
        return;
      }
      String name = type.length == 2 ? ascii(type, 0, 2) : "";
      if (name.equals("SH") || name.equals("RG") || name.equals("SE")) {
        return;
      }
      stream.seek(dataSize, IOStream.Position.CURRENT);
    }
  }

  private static void nativeWavpackProperties(BinaryFile f, long length) throws IOException {
    var stream = f.stream();
    long offset = 0, samples = 0;
    while (true) {
      byte[] h = NativeTagIO.read(stream, offset, 32);
      if (h.length < 32 || !ascii(h, 0, 4).equals("wvpk")) {
        break;
      }
      long size = u32le(h, 4), flags = u32le(h, 24), blockSamples = u32le(h, 20);
      if (size < 24 || size > 1048576) {
        break;
      }
      if (blockSamples == 0) {
        offset += size + 8;
        continue;
      }
      if (WAVPACK_RATES[(int) (flags >>> 23) & 15] == 0 || (flags & 0x80000000L) != 0) {
        if (stream.readBlock(size - 24).size() < size - 24) {
          break;
        }
      }
      if ((flags & 0x800) != 0) {
        int version = (short) u16le(h, 8);
        if (version < 0x402 || version > 0x410) {
          break;
        }
        samples = u32le(h, 12);
      }
      if ((flags & 0x1000) != 0) {
        break;
      }
      offset += size + 8;
    }
    if (samples == 0xffffffffL) {
      wavpackFinalIndex(f, length);
    }
  }

  @Override
  public void writeNative(
    BinaryFile f,
    long constructorOffset,
    PropertyMap properties,
    List<Picture> pictures
  )
    throws IOException {
    if (f.stream() == null) {
      write(f, properties, pictures);
      return;
    }
    var stream = f.stream();
    long cursor = stream.tell();
    var ape = ApeTag.read(f, ReadOptions.DEFAULT);
    var span = NativeTagIO.apeSpan(f, ape);
    byte[] data = ApeTag.render(properties, ape, pictures);
    boolean hasV1 = f.size() >= 128 && f.ascii(f.size() - 128, 3).equals("TAG");
    PropertyMap legacyPlan = NativeSaveState.id3v1Properties();
    boolean retainedV1 = hasV1 || legacyPlan != null;
    if (legacyPlan == null) {
      legacyPlan = Id3v1.nativeProperties(properties);
    }
    byte[] legacy = retainedV1 && !legacyPlan.isEmpty() ? Id3v1.render(legacyPlan) : new byte[0];
    stream.seek(cursor);
    if (legacy.length != 0) {
      NativeTagIO.write(stream, f.size() - (hasV1 ? 128 : 0), legacy);
    } else if (hasV1) {
      stream.truncate(f.size() - 128);
    }
    if (data.length != 0) {
      stream.insert(new ByteVector(data), span.offset(), span.length());
    } else if (span.length() != 0) {
      stream.removeBlock(span.offset(), span.length());
    }
  }

  @Override
  public void write(
    BinaryFile f, PropertyMap properties, List<Picture> pictures)
    throws IOException {
    if (f.stream() != null) {
      writeNative(f, f.initialPosition(), properties, pictures);
    } else {
      try (var stream = new FileStream(f.path());
           var borrowed = new BinaryFile(stream)) {
        if (!stream.isOpen() || stream.readOnly()) {
          throw new IOException("APE stream is not writable");
        }
        WriteGuard.check(f.requirePath().toRealPath());
        writeNative(borrowed, 0, properties, pictures);
      }
    }
  }

  private static AudioProperties calculated(
    long samples, int rate, int channels, int bits, boolean lossless, long length) {
    double ms = rate > 0 ? samples * 1000.0 / rate : 0;
    long duration = ms > 0 && ms + .5 <= Integer.MAX_VALUE ? Math.round(ms) : 0;
    int bitrate =
      ms > 0
        && ms + .5 <= Integer.MAX_VALUE
        && length * 8.0 / ms >= 0
        && length * 8.0 / ms + .5 <= Integer.MAX_VALUE
        ? (int) (length * 8.0 / ms + .5)
        : 0;
    return new AudioProperties(duration, bitrate, rate, channels, bits, lossless);
  }

  public record Details(
    AudioProperties common,
    int version,
    long sampleFrames,
    long totalFrames,
    int trackGain,
    int trackPeak,
    int albumGain,
    int albumPeak,
    boolean dsd) {
  }

  /// Reads detailed properties using the same tag boundaries as the format's native File.
  public static Details readProperties(BinaryFile file, Format format) throws IOException {
    long begin = audioStart(file, format);
    long end = ApeTag.read(file, ReadOptions.DEFAULT).offset();
    return switch (format) {
      case APE -> monkey(file, begin, end - begin);
      case WAVPACK -> wavpack(file, end);
      case MPC -> musepack(file, begin, end - begin);
      default -> throw new IllegalArgumentException("Detailed properties not implemented: " + format);
    };
  }

  private static long audioStart(BinaryFile file, Format format) throws IOException {
    if (format == Format.WAVPACK || file.size() < 3 || !file.ascii(0, 3).equals("ID3")) {
      return 0;
    }
    // These native File types inspect only Header; they do not invoke the ID3 frame factory.
    if (file.size() < 10) {
      return 10;
    }
    byte[] header = file.read(0, 10);
    if ((header[3] & 255) == 255 || (header[4] & 255) == 255) {
      return 10;
    }
    for (int i = 6; i < 10; i++) {
      if (header[i] < 0) {
        return 10;
      }
    }
    return 10L + ((header[5] & 16) != 0 ? 10 : 0) + synchsafe(header, 6);
  }

  private static Details simple(AudioProperties common, int version, long samples) {
    return new Details(common, version, samples, 0, 0, 0, 0, 0, false);
  }

  private static Details monkey(BinaryFile f, long at, long length) throws IOException {
    int version, rate = 0, channels = 0, bits = 0;
    long samples = 0;
    if (at < 0 || at > f.size() - 6 || !f.ascii(at, 4).equals("MAC ")) {
      at = findMarker(f, "MAC ", Math.max(0, at), f.size(), false);
    }
    if (at < 0 || at > f.size() - 6) {
      return simple(AudioProperties.EMPTY, 0, 0);
    }
    version = u16le(f.read(at + 4, 2), 0);
    if (version >= 3980) {
      if (at <= f.size() - 52) {
        long descriptor = f.u32le(at + 8);
        // Native unsigned subtraction places undersized descriptors beyond the file.
        long header = at + 52 + ((descriptor - 52) & 0xffffffffL);
        if (header <= f.size() - 24) {
          byte[] h = f.read(header, 24);
          channels = (short) u16le(h, 18);
          rate = (int) u32le(h, 20);
          bits = (short) u16le(h, 16);
          long frames = u32le(h, 12);
          if (frames > 0) {
            samples = ((frames - 1) * u32le(h, 4) + u32le(h, 8)) & 0xffffffffL;
          }
        }
      }
    } else if (at <= f.size() - 32) {
      byte[] h = f.read(at + 6, 26);
      long frames = u32le(h, 18);
      if (frames > 0) {
        int compression = (short) u16le(h, 0);
        int blocks =
          version >= 3950
            ? 73728 * 4
            : version >= 3900 || (version >= 3800 && compression == 4000) ? 73728 : 9216;
        channels = (short) u16le(h, 4);
        rate = (int) u32le(h, 6);
        samples = ((frames - 1) * blocks + u32le(h, 22)) & 0xffffffffL;
        if (at <= f.size() - 76 && f.ascii(at + 48, 8).equals("WAVEfmt ")) {
          bits = (short) u16le(f.read(at + 74, 2), 0);
        }
      }
    }
    // APE uses the strict unrounded upper bound from apeproperties.cpp.
    double ms = samples > 0 && rate > 0 ? samples * 1000.0 / rate : 0;
    long duration = ms > 0 && ms < Integer.MAX_VALUE ? (int) (ms + .5) : 0;
    double kbps = ms > 0 ? length * 8.0 / ms : -1;
    int bitrate =
      ms > 0 && ms < Integer.MAX_VALUE && kbps >= 0 && kbps < Integer.MAX_VALUE
        ? (int) (kbps + .5)
        : 0;
    return simple(
      new AudioProperties(duration, bitrate, rate, channels, bits, true), version, samples);
  }

  /// Bounded forward find and native 1024-byte rfind windows; end is exclusive.
  private static long findMarker(BinaryFile f, String marker, long start, long end, boolean reverse)
    throws IOException {
    byte[] needle = marker.getBytes(StandardCharsets.ISO_8859_1);
    if (start < 0 || end > f.size() || end - start < needle.length) {
      return -1;
    }
    long cursor = reverse ? end : start;
    while (reverse ? cursor - start >= needle.length : end - cursor >= needle.length) {
      long lo = reverse ? Math.max(start, cursor - 1024) : cursor;
      long hi = reverse ? cursor : Math.min(end, cursor + 65536);
      byte[] block = f.read(lo, (int) (hi - lo));
      for (int i = reverse ? block.length - needle.length : 0;
           reverse ? i >= 0 : i <= block.length - needle.length;
           i += reverse ? -1 : 1) {
        boolean match = true;
        for (int j = 0; j < needle.length; j++) {
          if (block[i + j] != needle[j]) {
            match = false;
            break;
          }
        }
        if (match) {
          return lo + i;
        }
      }
      if (reverse ? lo == start : hi == end) {
        break;
      }
      cursor = reverse ? lo : hi - needle.length + 1;
    }
    return -1;
  }

  private static Details wavpack(BinaryFile f, long length) throws IOException {
    int version = 0, rate = 0, channels = 0, bits = 0;
    long samples = 0, at = 0;
    boolean lossless = false, dsd = false;
    while (at <= f.size() - 32) {
      byte[] h = f.read(at, 32);
      if (!ascii(h, 0, 4).equals("wvpk")) {
        break;
      }
      long size = u32le(h, 4), flags = u32le(h, 24), blockSamples = u32le(h, 20);
      if (size < 24 || size > 1048576) {
        break;
      }
      if (blockSamples == 0) {
        at += size + 8;
        continue;
      }
      int sr = WAVPACK_RATES[(int) (flags >>> 23) & 15];
      if (sr == 0 || (flags & 0x80000000L) != 0) {
        int payloadSize = (int) size - 24;
        if (payloadSize > f.size() - at - 32) {
          break;
        }
        byte[] block = f.read(at + 32, payloadSize);
        if (sr == 0) {
          sr = wavpackMetadata(block, 0x27);
        }
        if (sr != 0 && (flags & 0x80000000L) != 0) {
          sr <<= wavpackMetadata(block, 0x0e);
        }
      }
      if ((flags & 0x800) != 0) {
        version = (short) u16le(h, 8);
        if (version < 0x402 || version > 0x410) {
          break;
        }
        bits = (int) (((flags & 3) + 1) * 8 - ((flags >>> 13) & 31));
        rate = sr;
        lossless = (flags & 8) == 0;
        dsd = (flags & 0x80000000L) != 0;
        samples = u32le(h, 12);
      }
      channels += (flags & 4) != 0 ? 1 : 2;
      if ((flags & 0x1000) != 0) {
        break;
      }
      at += size + 8;
    }
    if (samples == 0xffffffffL) {
      samples = wavpackFinalIndex(f, length);
    }
    return new Details(
      calculated(samples, rate, channels, bits, lossless, length),
      version,
      samples,
      0,
      0,
      0,
      0,
      0,
      dsd);
  }

  public static int wavpackMetadata(byte[] b, int wanted) {
    int p = 0;
    while (p + 1 < b.length) {
      int id = b[p++] & 255, n = (b[p++] & 255) << 1;
      if ((id & 128) != 0) {
        if (p + 2 > b.length) {
          return 0;
        }
        n += (b[p++] & 255) << 9;
        n += (b[p++] & 255) << 17;
      }
      if (n > b.length - p) {
        return 0;
      }
      if (wanted == 0x27 && (id & 63) == wanted && n == 4) {
        int rate = (b[p] & 255) | ((b[p + 1] & 255) << 8) | ((b[p + 2] & 255) << 16);
        if ((id & 64) == 0) {
          rate |= (b[p + 3] & 127) << 24;
        }
        return rate;
      }
      if (wanted == 0x0e && (id & 63) == wanted && n > 0 && (b[p] & 255) <= 31) {
        return b[p] & 255;
      }
      p += n;
    }
    return 0;
  }

  private static long wavpackFinalIndex(BinaryFile f, long length) throws IOException {
    long offset = length;
    while (offset >= 32) {
      offset = findMarker(f, "wvpk", 0, Math.min(offset, f.size()), true);
      if (offset < 0 || offset > f.size() - 32) {
        return 0;
      }
      byte[] h = f.read(offset, 32);
      long size = u32le(h, 4), blockSamples = u32le(h, 20), flags = u32le(h, 24);
      int version = (short) u16le(h, 8);
      if (version < 0x402
        || version > 0x410
        || (size & 1) != 0
        || size < 24
        || size >= 1048576
        || blockSamples > 131072) {
        continue;
      }
      if (blockSamples != 0 && (flags & 0x1000) != 0) {
        return (u32le(h, 16) + blockSamples) & 0xffffffffL;
      }
    }
    return 0;
  }

  private static Details musepack(BinaryFile f, long at, long length) throws IOException {
    int version = 0, rate = 0, channels = 0, bitrate = 0;
    int trackGain = 0, trackPeak = 0, albumGain = 0, albumPeak = 0;
    long samples = 0, totalFrames = 0, durationSamples = 0;
    int available = at < 0 || at > f.size() ? 0 : (int) Math.min(56, f.size() - at);
    byte[] h = Arrays.copyOf(f.read(Math.clamp(at, 0L, f.size()), available), 56);
    if (ascii(h, 0, 4).equals("MPCK")) {
      long p = at + 4;
      // Upstream stops after either SH or RG, not after collecting both packets.
      while (p <= f.size() - 3) {
        String type = f.ascii(p, 2);
        Varint packet = varint(f, p + 2, f.size());
        if (!packet.terminated()) {
          break;
        }
        long payload = packet.next(), headerSize = payload - p, size = packet.value();
        if (Long.compareUnsigned(size, headerSize) < 0 || payload > length) {
          break;
        }
        long n = size - headerSize;
        if (Long.compareUnsigned(n, length - payload) > 0
          || Long.compareUnsigned(n, 0xffffffffL) > 0
          || n > f.size() - payload) {
          break;
        }
        if (type.equals("SH")) {
          if (n <= 5) {
            break;
          }
          version = (byte) f.u8(payload + 4);
          Varint count = varint(f, payload + 5, payload + n);
          samples = count.value();
          if (count.next() > payload + n - 3) {
            break;
          }
          Varint silence = varint(f, count.next(), payload + n);
          if (silence.next() > payload + n - 2) {
            break;
          }
          int flags = u16be(f.read(silence.next(), 2), 0);
          rate = MUSEPACK_RATES[(flags >>> 13) & 7];
          channels = ((flags >>> 4) & 15) + 1;
          durationSamples = samples - silence.value();
          break;
        }
        if (type.equals("RG")) {
          if (n > 9 && f.u8(payload) == 1) {
            byte[] replayGain = f.read(payload + 1, 8);
            trackGain = (short) u16be(replayGain, 0);
            trackPeak = (short) u16be(replayGain, 2);
            albumGain = (short) u16be(replayGain, 4);
            albumPeak = (short) u16be(replayGain, 6);
          }
          break;
        }
        if (type.equals("SE")) {
          break;
        }
        // Upstream readBlock already consumed the payload, then seeks by dataSize again.
        p = payload + 2 * n;
      }
    } else if (ascii(h, 0, 3).equals("MP+")) {
      if (available < 4) {
        return simple(AudioProperties.EMPTY, 0, 0);
      }
      version = h[3] & 15;
      if (version < 7) {
        return simple(AudioProperties.EMPTY, version, 0);
      }
      totalFrames = u32le(h, 4);
      long flags = u32le(h, 8), gapless = u32le(h, 5);
      rate = MUSEPACK_RATES[(int) (flags >>> 16) & 3];
      channels = 2;
      trackGain = mpcGain((short) u16le(h, 14));
      trackPeak = mpcPeak(u16le(h, 12));
      albumGain = mpcGain((short) u16le(h, 18));
      albumPeak = mpcPeak(u16le(h, 16));
      samples =
        (totalFrames * 1152 - ((gapless & 0x80000000L) != 0 ? (gapless >>> 20) & 2047 : 576))
          & 0xffffffffL;
      durationSamples = samples;
    } else {
      long header = u32le(h, 0);
      bitrate = (int) ((header >>> 23) & 511);
      version = (int) ((header >>> 11) & 1023);
      rate = 44100;
      channels = 2;
      totalFrames = version >= 5 ? u32le(h, 4) : u16le(h, 6);
      samples = (totalFrames * 1152 - 576) & 0xffffffffL;
      durationSamples = samples;
    }
    double unsignedSamples =
      durationSamples >= 0 ? durationSamples : (durationSamples & Long.MAX_VALUE) + 0x1.0p63;
    double ms = rate > 0 ? unsignedSamples * 1000.0 / rate : 0;
    long duration = ms > 0 && ms + .5 <= Integer.MAX_VALUE ? (int) (ms + .5) : 0;
    double kbps = ms > 0 ? length * 8.0 / ms : -1;
    if (bitrate == 0
      && ms > 0
      && ms + .5 <= Integer.MAX_VALUE
      && kbps >= 0
      && kbps + .5 <= Integer.MAX_VALUE) {
      bitrate = (int) (kbps + .5);
    }
    return new Details(
      new AudioProperties(duration, bitrate, rate, channels, 0, false),
      version,
      samples,
      totalFrames,
      trackGain,
      trackPeak,
      albumGain,
      albumPeak,
      false);
  }

  private static int mpcGain(int gain) {
    if (gain == 0) {
      return 0;
    }
    int result = (int) ((64.82 - gain / 100.0) * 256 + .5);
    return result >= 65536 || result < 0 ? 0 : result;
  }

  private static int mpcPeak(int peak) {
    return peak == 0 ? 0 : (int) (Math.log10(peak) * 20 * 256 + .5);
  }

  private record Varint(long value, long next, boolean terminated) {
  }

  /// Native unsigned-long accumulation, streamed so long continuation runs do not allocate.
  private static Varint varint(BinaryFile f, long at, long end) throws IOException {
    long value = 0;
    while (at < end) {
      byte[] block = f.read(at, (int) Math.min(4096, end - at));
      for (byte element : block) {
        int b = element & 255;
        value = (value << 7) | (b & 127);
        at++;
        if ((b & 128) == 0) {
          return new Varint(value, at, true);
        }
      }
    }
    return new Varint(value, at, false);
  }
}
