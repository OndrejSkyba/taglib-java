// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;

/// An immutable snapshot of common audio properties. Unknown numeric values use zero.
///
/// @param lengthInMilliseconds duration in milliseconds
/// @param bitrate              average bitrate in kilobits per second
/// @param sampleRate           samples per second
/// @param channels             audio channel count
/// @param bitsPerSample        decoded sample bit depth
/// @param lossless             whether the audio coding is lossless
public record AudioProperties(
  long lengthInMilliseconds,
  int bitrate,
  int sampleRate,
  int channels,
  int bitsPerSample,
  boolean lossless) {
  public static final AudioProperties EMPTY = new AudioProperties(0, 0, 0, 0, 0, false);

  /// {@return the duration in whole seconds, truncating fractional seconds}
  public long lengthInSeconds() {
    return lengthInMilliseconds / 1000;
  }
}
