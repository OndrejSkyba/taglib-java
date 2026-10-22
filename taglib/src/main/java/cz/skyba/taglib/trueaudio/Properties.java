// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.trueaudio;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.codec.TrueAudioCodec;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.IOStream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;

/// Parsed TrueAudio audio properties.
/// Values describe the decoded stream and do not own a file handle. Unknown numeric values use zero.
public final class Properties {
  private final TrueAudioCodec.Details details;

  /// Reads TrueAudio audio properties from the supplied parsed file or encoded property data.
  ///
  /// @param data         the encoded audio property bytes
  /// @param streamLength the byte length used to calculate bitrate
  public Properties(ByteVector data, long streamLength) {
    this(data, streamLength, ReadStyle.AVERAGE);
  }

  /// Reads TrueAudio audio properties from the supplied parsed file or encoded property data.
  ///
  /// @param data         the encoded audio property bytes
  /// @param streamLength the byte length used to calculate bitrate
  /// @param style        the audio property read hint
  public Properties(ByteVector data, long streamLength, ReadStyle style) {
    Objects.requireNonNull(style);
    details = TrueAudioCodec.properties(data.data(), streamLength);
  }

  /// Reads TrueAudio audio properties from a file, closing its temporary handle.
  /// Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
  ///
  /// @param path the audio file to inspect
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(Path path) {
    this(path, ReadStyle.AVERAGE);
  }

  /// Reads TrueAudio audio properties from a file, closing its temporary handle.
  /// Reading can move the source cursor. Values are independent of the source handle.
  ///
  /// @param path  the audio file to inspect
  /// @param style the audio property read hint
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(Path path, ReadStyle style) {
    Objects.requireNonNull(style);
    try (var file = new BinaryFile(path)) {
      details = TrueAudioCodec.readProperties(file, style);
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata: " + path, failure);
    }
  }

  /// Reads TrueAudio audio properties from a borrowed stream, leaving it open.
  /// Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
  ///
  /// @param stream the open, seekable input stream
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(IOStream stream) {
    this(stream, ReadStyle.AVERAGE);
  }

  /// Reads TrueAudio audio properties from a borrowed stream, leaving it open.
  /// Reading can move the source cursor. Values are independent of the source handle.
  ///
  /// @param stream the open, seekable input stream
  /// @param style  the audio property read hint
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(IOStream stream, ReadStyle style) {
    Objects.requireNonNull(style);
    try (var file = new BinaryFile(stream)) {
      details = TrueAudioCodec.readProperties(file, style);
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

  public int ttaVersion() {
    return details.version();
  }

  public long sampleFrames() {
    return details.sampleFrames();
  }

  public AudioProperties common() {
    return details.common();
  }
}
