// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.riff.aiff;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.codec.RiffCodec;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.IOStream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;

/// Parsed AIFF/AIFC audio properties.
/// Values describe the decoded stream and do not own a file handle. Unknown numeric values use zero.
public final class Properties {
  private final RiffCodec.AiffDetails details;

  /// Reads AIFF/AIFC audio properties from the supplied parsed file or encoded property data.
  /// Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
  ///
  /// @param file the live file to inspect without taking ownership
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(AiffFile file) {
    this(file, ReadStyle.AVERAGE);
  }

  /// Reads AIFF/AIFC audio properties from the supplied parsed file or encoded property data.
  /// Reading can move the source cursor. Values are independent of the source handle.
  ///
  /// @param file  the live file to inspect without taking ownership
  /// @param style the audio property read hint
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(AiffFile file, ReadStyle style) {
    Objects.requireNonNull(style);
    details = Objects.requireNonNull(file).readProperties();
  }

  /// Reads AIFF/AIFC audio properties from a file, closing its temporary handle.
  /// Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
  ///
  /// @param path the audio file to inspect
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(Path path) {
    this(path, ReadStyle.AVERAGE);
  }

  /// Reads AIFF/AIFC audio properties from a file, closing its temporary handle.
  /// Reading can move the source cursor. Values are independent of the source handle.
  ///
  /// @param path  the audio file to inspect
  /// @param style the audio property read hint
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(Path path, ReadStyle style) {
    Objects.requireNonNull(style);
    try (var file = new BinaryFile(path)) {
      details = RiffCodec.aiffDetails(file, style);
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata: " + path, failure);
    }
  }

  /// Reads AIFF/AIFC audio properties from a borrowed stream, leaving it open.
  /// Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
  ///
  /// @param stream the open, seekable input stream
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(IOStream stream) {
    this(stream, ReadStyle.AVERAGE);
  }

  /// Reads AIFF/AIFC audio properties from a borrowed stream, leaving it open.
  /// Reading can move the source cursor. Values are independent of the source handle.
  ///
  /// @param stream the open, seekable input stream
  /// @param style  the audio property read hint
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(IOStream stream, ReadStyle style) {
    Objects.requireNonNull(style);
    try (var file = new BinaryFile(stream)) {
      details = RiffCodec.aiffDetails(file, style);
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

  public long sampleFrames() {
    return details.sampleFrames();
  }

  public boolean isAiffC() {
    return details.compressionType().length != 0;
  }

  public ByteVector compressionType() {
    return new ByteVector(details.compressionType());
  }

  public String compressionName() {
    return details.compressionName();
  }

  public AudioProperties common() {
    return details.common();
  }
}
