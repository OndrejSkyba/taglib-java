// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.dsf;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.codec.DsdCodec;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.IOStream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;

/// Parsed DSF audio properties.
/// Values describe the decoded stream and do not own a file handle. Unknown numeric values use zero.
public final class Properties {
  private final DsdCodec.DsfDetails details;

  /// Reads DSF audio properties from encoded property data.
  ///
  /// @param data  the encoded audio property bytes
  /// @param style the audio property read hint
  public Properties(ByteVector data, ReadStyle style) {
    Objects.requireNonNull(style);
    details = DsdCodec.dsfProperties(data.data());
  }

  /// Reads DSF audio properties from a file, closing its temporary handle.
  /// Values are independent of the source handle. Uses the average read-style hint.
  ///
  /// @param path the audio file to inspect
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(Path path) {
    this(path, ReadStyle.AVERAGE);
  }

  /// Reads DSF audio properties from a file, closing its temporary handle.
  /// Values are independent of the source handle.
  ///
  /// @param path  the audio file to inspect
  /// @param style the audio property read hint
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(Path path, ReadStyle style) {
    Objects.requireNonNull(style);
    try (var file = new BinaryFile(path)) {
      details = DsdCodec.readDsfProperties(file, style);
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata: " + path, failure);
    }
  }

  /// Reads DSF audio properties from a borrowed stream, leaving it open.
  /// Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
  ///
  /// @param stream the open, seekable input stream
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(IOStream stream) {
    this(stream, ReadStyle.AVERAGE);
  }

  /// Reads DSF audio properties from a borrowed stream, leaving it open.
  /// Reading can move the source cursor. Values are independent of the source handle.
  ///
  /// @param stream the open, seekable input stream
  /// @param style  the audio property read hint
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(IOStream stream, ReadStyle style) {
    Objects.requireNonNull(style);
    try (var file = new BinaryFile(stream)) {
      details = DsdCodec.readDsfProperties(file, style);
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

  public int formatVersion() {
    return details.formatVersion();
  }

  public int formatID() {
    return details.formatID();
  }

  public int channelType() {
    return details.channelType();
  }

  public long sampleCount() {
    return details.sampleCount();
  }

  public int blockSizePerChannel() {
    return details.blockSizePerChannel();
  }

  public AudioProperties common() {
    return details.common();
  }
}
