// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.mpc;

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

/// Parsed Musepack audio properties.
/// Values describe the decoded stream and do not own a file handle. Unknown numeric values use zero.
public final class Properties {
  private final ApeCodec.Details details;

  /// Reads Musepack audio properties from the supplied parsed file or encoded property data.
  /// Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
  ///
  /// @param file         the live file to inspect without taking ownership
  /// @param streamLength the byte length used to calculate bitrate
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(MpcFile file, long streamLength) {
    this(file, streamLength, ReadStyle.AVERAGE);
  }

  /// Reads Musepack audio properties from the supplied parsed file or encoded property data.
  /// Reading can move the source cursor. Values are independent of the source handle.
  ///
  /// @param file         the live file to inspect without taking ownership
  /// @param streamLength the byte length used to calculate bitrate
  /// @param style        the audio property read hint
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(MpcFile file, long streamLength, ReadStyle style) {
    Objects.requireNonNull(file);
    Objects.requireNonNull(style);
    details = read(file, streamLength);
  }

  private static final int[] RATES = {44100, 48000, 37800, 32000, 0, 0, 0, 0};

  private static ApeCodec.Details read(MpcFile file, long streamLength) {
    var magic = file.readBlock(4);
    return magic.equals(new ByteVector("MPCK"))
      ? readSV8(file, streamLength) : readSV7(new ByteVector(magic).append(file.readBlock(52)), streamLength);
  }

  private record Size(long value, int bytes, boolean eof) {
  }

  private static Size readSize(MpcFile file) {
    long value = 0;
    int count = 0;
    int b;
    do {
      var data = file.readBlock(1);
      if (data.isEmpty()) {
        return new Size(value, count, true);
      }
      b = data.at(0) & 255;
      value = (value << 7) | (b & 127);
      count++;
    } while ((b & 128) != 0);
    return new Size(value, count, false);
  }

  private record VectorSize(long value, int next) {
  }

  private static VectorSize readSize(ByteVector data, int pos) {
    long value = 0;
    int b;
    do {
      b = data.at(pos++) & 255;
      value = (value << 7) | (b & 127);
    } while ((b & 128) != 0 && pos < data.size());
    return new VectorSize(value, pos);
  }

  private static ApeCodec.Details readSV8(MpcFile file, long streamLength) {
    int version = 0, duration = 0, bitrate = 0, rate = 0, channels = 0;
    int trackGain = 0, trackPeak = 0, albumGain = 0, albumPeak = 0;
    long samples = 0;
    while (true) {
      var type = file.readBlock(2);
      var packet = readSize(file);
      if (packet.eof()) {
        break;
      }
      long headerSize = (2L + packet.bytes()) & 0xffff_ffffL;
      long offset = file.tell();
      if (Long.compareUnsigned(packet.value(), headerSize) < 0 || offset < 0 || offset > streamLength) {
        break;
      }
      long dataSize = packet.value() - headerSize;
      if (Long.compareUnsigned(dataSize, streamLength - offset) > 0
        || Long.compareUnsigned(dataSize, 0xffff_ffffL) > 0) {
        break;
      }
      var data = file.readBlock(dataSize);
      if (data.size() != dataSize) {
        break;
      }
      if (type.equals(new ByteVector("SH"))) {
        if (dataSize <= 5) {
          break;
        }
        version = data.at(4);
        var count = readSize(data, 5);
        samples = count.value();
        if (count.next() > dataSize - 3) {
          break;
        }
        var silence = readSize(data, count.next());
        if (silence.next() > dataSize - 2) {
          break;
        }
        int flags = data.toUnsignedShort(silence.next(), true);
        rate = RATES[(flags >>> 13) & 7];
        channels = ((flags >>> 4) & 15) + 1;
        long frameCount = samples - silence.value();
        if (frameCount != 0 && rate > 0) {
          double milliseconds = unsignedDouble(frameCount) * 1000.0 / rate;
          if (milliseconds > 0 && milliseconds + .5 <= Integer.MAX_VALUE) {
            duration = (int) (milliseconds + .5);
            double estimate = streamLength * 8.0 / milliseconds;
            if (estimate >= 0 && estimate + .5 <= Integer.MAX_VALUE) {
              bitrate = (int) (estimate + .5);
            }
          }
        }
        break; // Native stops after either SH or RG, not after collecting both.
      }
      if (type.equals(new ByteVector("RG"))) {
        if (dataSize > 9 && data.at(0) == 1) {
          trackGain = data.toShort(1, true);
          trackPeak = data.toShort(3, true);
          albumGain = data.toShort(5, true);
          albumPeak = data.toShort(7, true);
        }
        break;
      }
      if (type.equals(new ByteVector("SE"))) {
        break;
      }
      // Native consumed the data with readBlock and then skips dataSize again.
      file.seek(dataSize, IOStream.Position.CURRENT);
    }
    return details(duration, bitrate, rate, channels, version, samples, 0,
      trackGain, trackPeak, albumGain, albumPeak);
  }

  private static ApeCodec.Details readSV7(ByteVector data, long streamLength) {
    int version, rate, channels, bitrate = 0;
    int trackGain = 0, trackPeak = 0, albumGain = 0, albumPeak = 0;
    long frames, samples;
    if (data.startsWith(new ByteVector("MP+"))) {
      if (data.size() < 4) {
        return details(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
      }
      version = data.at(3) & 15;
      if (version < 7) {
        return details(0, 0, 0, 0, version, 0, 0, 0, 0, 0, 0);
      }
      frames = data.toUnsignedInt(4, false);
      long flags = data.toUnsignedInt(8, false), gapless = data.toUnsignedInt(5, false);
      rate = RATES[(int) (flags >>> 16) & 3];
      channels = 2;
      trackGain = gain(data.toShort(14, false));
      trackPeak = peak(data.toUnsignedShort(12, false));
      albumGain = gain(data.toShort(18, false));
      albumPeak = peak(data.toUnsignedShort(16, false));
      samples = (frames * 1152 - ((gapless & 0x80000000L) != 0 ? (gapless >>> 20) & 2047 : 576)) & 0xffff_ffffL;
    } else {
      long header = data.toUnsignedInt(0, false);
      bitrate = (int) (header >>> 23) & 511;
      version = (int) (header >>> 11) & 1023;
      rate = 44100;
      channels = 2;
      frames = version >= 5 ? data.toUnsignedInt(4, false) : data.toUnsignedShort(6, false);
      samples = (frames * 1152 - 576) & 0xffff_ffffL;
    }
    int duration = 0;
    if (samples > 0) {
      double milliseconds = samples * 1000.0 / rate;
      if (milliseconds + .5 <= Integer.MAX_VALUE) {
        duration = (int) (milliseconds + .5);
        if (bitrate == 0) {
          double estimate = streamLength * 8.0 / milliseconds;
          if (estimate + .5 <= Integer.MAX_VALUE) {
            if (estimate + .5 <= (double) Integer.MIN_VALUE - 1.0) {
              throw new IllegalArgumentException("Undefined native out-of-range negative bitrate conversion");
            }
            bitrate = (int) (estimate + .5);
          }
        }
      }
    }
    return details(duration, bitrate, rate, channels, version, samples, frames, trackGain, trackPeak, albumGain, albumPeak);
  }

  private static double unsignedDouble(long value) {
    return value >= 0 ? value : (value & Long.MAX_VALUE) + 0x1.0p63;
  }

  private static int gain(int value) {
    if (value == 0) {
      return 0;
    }
    int result = (int) ((64.82 - value / 100.0) * 256 + .5);
    return result < 0 || result >= 65536 ? 0 : result;
  }

  private static int peak(int value) {
    return value == 0 ? 0 : (int) (Math.log10(value) * 20 * 256 + .5);
  }

  private static ApeCodec.Details details(
    int duration, int bitrate, int rate, int channels, int version,
    long samples, long frames, int trackGain, int trackPeak, int albumGain, int albumPeak
  ) {
    return new ApeCodec.Details(new AudioProperties(duration, bitrate, rate, channels, 0, false), version,
      samples, frames, trackGain, trackPeak, albumGain, albumPeak, false);
  }

  /// Reads Musepack audio properties from a file, closing its temporary handle.
  /// Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
  ///
  /// @param path the audio file to inspect
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(Path path) {
    this(path, ReadStyle.AVERAGE);
  }

  /// Reads Musepack audio properties from a file, closing its temporary handle.
  /// Reading can move the source cursor. Values are independent of the source handle.
  ///
  /// @param path  the audio file to inspect
  /// @param style the audio property read hint
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(Path path, ReadStyle style) {
    Objects.requireNonNull(style);
    try (var file = new BinaryFile(path)) {
      details = ApeCodec.readProperties(file, Format.MPC);
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata: " + path, failure);
    }
  }

  /// Reads Musepack audio properties from a borrowed stream, leaving it open.
  /// Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
  ///
  /// @param stream the open, seekable input stream
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(IOStream stream) {
    this(stream, ReadStyle.AVERAGE);
  }

  /// Reads Musepack audio properties from a borrowed stream, leaving it open.
  /// Reading can move the source cursor. Values are independent of the source handle.
  ///
  /// @param stream the open, seekable input stream
  /// @param style  the audio property read hint
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(IOStream stream, ReadStyle style) {
    Objects.requireNonNull(style);
    try (var file = new BinaryFile(stream)) {
      details = ApeCodec.readProperties(file, Format.MPC);
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

  public int mpcVersion() {
    return details.version();
  }

  /// unsigned-long count; use Long.toUnsignedString for out-of-signed-range SV8 values.
  public long sampleFrames() {
    return details.sampleFrames();
  }

  public long totalFrames() {
    return details.totalFrames();
  }

  public int trackGain() {
    return details.trackGain();
  }

  public int trackPeak() {
    return details.trackPeak();
  }

  public int albumGain() {
    return details.albumGain();
  }

  public int albumPeak() {
    return details.albumPeak();
  }

  public AudioProperties common() {
    return details.common();
  }
}
