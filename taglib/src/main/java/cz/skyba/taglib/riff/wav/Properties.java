// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.riff.wav;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.internal.Binary;

import java.util.Objects;

/// Parsed WAV audio properties.
/// Values describe the decoded stream and do not own a file handle. Unknown numeric values use zero.
public final class Properties {
  private final AudioProperties common;
  private final long sampleFrames;
  private final int format;

  /// Reads WAV audio properties from the supplied parsed file.
  /// Reading can move the source cursor. Values are independent of the source handle.
  ///
  /// @param file  the live file to inspect without taking ownership
  /// @param style the audio property read hint
  /// @throws java.io.UncheckedIOException if storage access or property decoding fails
  public Properties(WavFile file, ReadStyle style) {
    Objects.requireNonNull(style);
    Properties source = Objects.requireNonNull(file).readProperties();
    common = source.common;
    sampleFrames = source.sampleFrames;
    format = source.format;
  }

  private Properties(AudioProperties common, long frames, int format) {
    this.common = common;
    sampleFrames = frames;
    this.format = format;
  }

  /// Creates properties from selected fmt, data-length and sample-count information.
  ///
  /// @param data         encoded fmt chunk bytes
  /// @param streamLength the data stream byte length
  /// @param totalSamples the sample count when supplied by a fact or extended-size chunk
  /// @return parsed audio properties
  public static Properties fromChunks(byte[] data, long streamLength, long totalSamples) {
    Objects.requireNonNull(data);
    if (data.length < 16 || streamLength == 0) {
      return empty(0);
    }
    int encoding = (short) Binary.u16le(data, 0);
    if ((encoding & 65535) == 65534) {
      if (data.length != 40) {
        return empty(encoding);
      }
      encoding = (short) Binary.u16le(data, 24);
    }
    if (encoding != 1 && encoding != 3 && totalSamples == 0) {
      return empty(encoding);
    }
    int channels = (short) Binary.u16le(data, 2),
      rate = (int) Binary.u32le(data, 4),
      bits = (short) Binary.u16le(data, 14);
    long frames =
      encoding != 1 && (encoding != 3 || totalSamples != 0)
        ? totalSamples
        : channels > 0 && bits > 0
        ? Math.min(0xffffffffL, streamLength / (channels * ((bits + 7) / 8)))
        : 0;
    int length = 0, bitrate = 0;
    if (frames > 0 && rate > 0) {
      double duration = frames * 1000.0 / rate;
      if (duration > 0 && duration + 0.5 <= Integer.MAX_VALUE) {
        length = (int) (duration + 0.5);
        bitrate = bounded(streamLength * 8.0 / duration);
      }
    } else {
      long byteRate = Binary.u32le(data, 8);
      if (byteRate > 0) {
        length = bounded(streamLength * 1000.0 / byteRate);
        bitrate = bounded(byteRate * 8.0 / 1000);
      }
    }
    return new Properties(
      new AudioProperties(length, bitrate, rate, channels, bits, encoding == 1 || encoding == 3),
      frames,
      encoding);
  }

  private static int bounded(double value) {
    return value >= 0 && value + 0.5 <= Integer.MAX_VALUE ? (int) (value + 0.5) : 0;
  }

  private static Properties empty(int format) {
    return new Properties(AudioProperties.EMPTY, 0, format);
  }

  public long lengthInMilliseconds() {
    return common.lengthInMilliseconds();
  }

  public long lengthInSeconds() {
    return common.lengthInSeconds();
  }

  public int bitrate() {
    return common.bitrate();
  }

  public int sampleRate() {
    return common.sampleRate();
  }

  public int channels() {
    return common.channels();
  }

  public int bitsPerSample() {
    return common.bitsPerSample();
  }

  public long sampleFrames() {
    return sampleFrames;
  }

  public int format() {
    return format;
  }

  public AudioProperties common() {
    return common;
  }
}
