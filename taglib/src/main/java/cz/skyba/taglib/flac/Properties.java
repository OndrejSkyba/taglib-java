// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.flac;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.internal.Binary;
import cz.skyba.taglib.internal.codec.FlacCodec;

import java.util.Arrays;
import java.util.Objects;

/// Parsed FLAC audio properties.
/// Values describe the decoded stream and do not own a file handle. Unknown numeric values use zero.
public final class Properties {
  private final AudioProperties common;
  private final long sampleFrames;
  private final byte[] signature;

  /// Reads FLAC audio properties from supplied property values.
  ///
  /// @param streamLength the byte length used to calculate bitrate
  public Properties(byte[] data, long streamLength) {
    this(data, streamLength, ReadStyle.AVERAGE);
  }

  /// Reads FLAC audio properties from supplied property values.
  ///
  /// @param streamLength the byte length used to calculate bitrate
  /// @param style        the audio property read hint
  public Properties(byte[] data, long streamLength, ReadStyle style) {
    Objects.requireNonNull(data);
    Objects.requireNonNull(style);
    common = FlacCodec.audio(data, streamLength);
    sampleFrames = data.length < 18 ? 0 : ((Binary.u32be(data, 10) & 15) << 32) | Binary.u32be(data, 14);
    signature = data.length < 34 ? new byte[0] : Arrays.copyOfRange(data, 18, 34);
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

  public byte[] signature() {
    return signature.clone();
  }

  public AudioProperties common() {
    return common;
  }
}
