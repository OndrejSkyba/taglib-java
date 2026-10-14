// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.wavpack;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.Format;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.codec.ApeCodec;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.IOStream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Parsed WavPack audio property fields.
 * Common projections report duration in milliseconds, sample rate in hertz and bitrate in
 * kilobits per second. Unknown numeric fields use zero. Parsed values do not own a file handle.
 */
public final class Properties {
  private static final int[] RATES = {
    6000, 8000, 9600, 11025, 12000, 16000, 22050, 24000,
    32000, 44100, 48000, 64000, 88200, 96000, 192000, 0
  };

  private final ApeCodec.Details details;

  /**
   * Reads WavPack audio properties from the supplied parsed file or encoded property data.
   * Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
   *
   * @param file         the live file to inspect without taking ownership
   * @param streamLength the byte length used to calculate bitrate
   * @throws UncheckedIOException if storage access or property decoding fails
   */
  public Properties(WavPackFile file, long streamLength) {
    this(file, streamLength, ReadStyle.AVERAGE);
  }

  /**
   * Reads WavPack audio properties from the supplied parsed file or encoded property data.
   * Reading can move the source cursor. Values are independent of the source handle.
   *
   * @param file         the live file to inspect without taking ownership
   * @param streamLength the byte length used to calculate bitrate
   * @param style        the audio property read hint
   * @throws UncheckedIOException if storage access or property decoding fails
   */
  public Properties(WavPackFile file, long streamLength, ReadStyle style) {
    Objects.requireNonNull(file);
    Objects.requireNonNull(style);
    details = read(file, streamLength);
  }

  private static ApeCodec.Details read(WavPackFile file, long streamLength) {
    int version = 0, rate = 0, channels = 0, bits = 0;
    long samples = 0, offset = 0;
    boolean lossless = false, dsd = false;
    var magic = new ByteVector("wvpk");
    while (true) {
      file.seek(offset);
      var data = file.readBlock(32);
      if (data.size() < 32 || !data.startsWith(magic)) {
        break;
      }
      long blockSize = data.toUnsignedInt(4, false), flags = data.toUnsignedInt(24, false);
      if (blockSize < 24 || blockSize > 1048576) {
        break;
      }
      if (data.toUnsignedInt(20, false) == 0) {
        offset += blockSize + 8;
        continue;
      }
      int sampleRate = RATES[(int) (flags >>> 23) & 15];
      if (sampleRate == 0 || (flags & 0x80000000L) != 0) {
        var block = file.readBlock(blockSize - 24);
        if (block.size() < blockSize - 24) {
          break;
        }
        byte[] bytes = block.toByteArray();
        if (sampleRate == 0) {
          sampleRate = ApeCodec.wavpackMetadata(bytes, 0x27);
        }
        if (sampleRate != 0 && (flags & 0x80000000L) != 0) {
          sampleRate <<= ApeCodec.wavpackMetadata(bytes, 0x0e);
        }
      }
      if ((flags & 0x800) != 0) {
        version = data.toShort(8, false);
        if (version < 0x402 || version > 0x410) {
          break;
        }
        bits = (int) (((flags & 3) + 1) * 8 - ((flags >>> 13) & 31));
        rate = sampleRate;
        lossless = (flags & 8) == 0;
        dsd = (flags & 0x80000000L) != 0;
        samples = data.toUnsignedInt(12, false);
      }
      channels += (flags & 4) != 0 ? 1 : 2;
      if ((flags & 0x1000) != 0) {
        break;
      }
      offset += blockSize + 8;
    }
    if (samples == 0xffff_ffffL) {
      samples = finalIndex(file, streamLength);
    }
    double milliseconds = samples > 0 && rate > 0 ? samples * 1000.0 / rate : 0;
    int duration = 0, bitrate = 0;
    if (milliseconds > 0 && milliseconds + .5 <= Integer.MAX_VALUE) {
      duration = (int) (milliseconds + .5);
      double estimate = streamLength * 8.0 / milliseconds;
      if (estimate >= 0 && estimate + .5 <= Integer.MAX_VALUE) {
        bitrate = (int) (estimate + .5);
      }
    }
    return new ApeCodec.Details(new AudioProperties(duration, bitrate, rate, channels, bits, lossless),
      version, samples, 0, 0, 0, 0, 0, dsd);
  }

  private static long finalIndex(WavPackFile file, long streamLength) {
    long offset = streamLength;
    while (offset >= 32) {
      offset = file.rfind(new ByteVector("wvpk"), offset - 4);
      if (offset == -1) {
        return 0;
      }
      file.seek(offset);
      var data = file.readBlock(32);
      if (data.size() < 32) {
        return 0;
      }
      long size = data.toUnsignedInt(4, false), blockSamples = data.toUnsignedInt(20, false);
      int version = data.toShort(8, false);
      if (version < 0x402 || version > 0x410 || (size & 1) != 0
        || size < 24 || size >= 1048576 || blockSamples > 131072) {
        continue;
      }
      if (blockSamples != 0 && (data.toUnsignedInt(24, false) & 0x1000) != 0) {
        return (data.toUnsignedInt(16, false) + blockSamples) & 0xffff_ffffL;
      }
    }
    return 0;
  }

  /**
   * Reads WavPack audio properties from a file, closing its temporary handle.
   * Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
   *
   * @param path the audio file to inspect
   * @throws UncheckedIOException if storage access or property decoding fails
   */
  public Properties(Path path) {
    this(path, ReadStyle.AVERAGE);
  }

  /**
   * Reads WavPack audio properties from a file, closing its temporary handle.
   * Reading can move the source cursor. Values are independent of the source handle.
   *
   * @param path  the audio file to inspect
   * @param style the audio property read hint
   * @throws UncheckedIOException if storage access or property decoding fails
   */
  public Properties(Path path, ReadStyle style) {
    Objects.requireNonNull(style);
    try (var file = new BinaryFile(path)) {
      details = ApeCodec.readProperties(file, Format.WAVPACK);
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata: " + path, failure);
    }
  }

  /**
   * Reads WavPack audio properties from a borrowed stream, leaving it open.
   * Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
   *
   * @param stream the open, seekable input stream
   * @throws UncheckedIOException if storage access or property decoding fails
   */
  public Properties(IOStream stream) {
    this(stream, ReadStyle.AVERAGE);
  }

  /**
   * Reads WavPack audio properties from a borrowed stream, leaving it open.
   * Reading can move the source cursor. Values are independent of the source handle.
   *
   * @param stream the open, seekable input stream
   * @param style  the audio property read hint
   * @throws UncheckedIOException if storage access or property decoding fails
   */
  public Properties(IOStream stream, ReadStyle style) {
    Objects.requireNonNull(style);
    try (var file = new BinaryFile(stream)) {
      details = ApeCodec.readProperties(file, Format.WAVPACK);
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata", failure);
    }
  }

  public long lengthInMilliseconds() {
    return details.common().lengthInMilliseconds();
  }

  public long lengthInSeconds() {
    return details.common().lengthInSeconds();
  }

  public int bitrate() {
    return details.common().bitrate();
  }

  public int sampleRate() {
    return details.common().sampleRate();
  }

  public int channels() {
    return details.common().channels();
  }

  public int bitsPerSample() {
    return details.common().bitsPerSample();
  }

  public boolean isLossless() {
    return details.common().lossless();
  }

  public boolean isDsd() {
    return details.dsd();
  }

  public int version() {
    return details.version();
  }

  /**
   * Unsigned 32-bit sample-frame count, represented without sign loss.
   */
  public long sampleFrames() {
    return details.sampleFrames();
  }

  /**
   * Creates the immutable common audio-property projection.
   * Unknown or unsupported fields use zero.
   *
   * @return common audio properties
   */
  public AudioProperties common() {
    return details.common();
  }
}
