// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.ogg.vorbis;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.Format;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.codec.OggCodec;
import cz.skyba.taglib.ogg.OggFile;
import cz.skyba.taglib.toolkit.IOStream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/// Parsed Ogg Vorbis audio properties.
/// Values describe the decoded stream and do not own a file handle. Unknown numeric values use zero.
/// The read-style hint does not change the calculation for this format.
public final class Properties {
  private final OggCodec.Details details;

  /// Reads Ogg Vorbis audio properties from a file, closing its temporary handle.
  /// Values are independent of the source handle. Uses the average read-style hint.
  ///
  /// @param path the audio file to inspect
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(Path path) {
    this(path, ReadStyle.AVERAGE);
  }

  /// Reads Ogg Vorbis audio properties from a file, closing its temporary handle.
  /// Values are independent of the source handle.
  ///
  /// @param path  the audio file to inspect
  /// @param style the audio property read hint
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(Path path, ReadStyle style) {
    try (var file = new BinaryFile(path)) {
      details = OggCodec.readProperties(file, Format.OGG_VORBIS, style);
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata: " + path, failure);
    }
  }

  /// Reads Ogg Vorbis audio properties from a borrowed stream, leaving it open.
  /// Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
  ///
  /// @param stream the open, seekable input stream
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(IOStream stream) {
    this(stream, ReadStyle.AVERAGE);
  }

  /// Reads Ogg Vorbis audio properties from a borrowed stream, leaving it open.
  /// Reading can move the source cursor. Values are independent of the source handle.
  ///
  /// @param stream the open, seekable input stream
  /// @param style  the audio property read hint
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(IOStream stream, ReadStyle style) {
    try (var file = new BinaryFile(stream)) {
      details = OggCodec.readProperties(file, Format.OGG_VORBIS, style);
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata", failure);
    }
  }

  /// Reads Ogg Vorbis audio properties from the supplied parsed file.
  /// Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
  ///
  /// @param file the live file to inspect without taking ownership
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(OggFile file) {
    this(file, ReadStyle.AVERAGE);
  }

  /// Reads Ogg Vorbis audio properties from the supplied parsed file.
  /// Reading can move the source cursor. Values are independent of the source handle.
  ///
  /// @param file  the live file to inspect without taking ownership
  /// @param style the audio property read hint
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(OggFile file, ReadStyle style) {
    try {
      details = OggCodec.readProperties(file, Format.OGG_VORBIS, style);
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

  public int vorbisVersion() {
    return details.version();
  }

  public int bitrateMaximum() {
    return details.bitrateMaximum();
  }

  public int bitrateNominal() {
    return details.bitrateNominal();
  }

  public int bitrateMinimum() {
    return details.bitrateMinimum();
  }

  public AudioProperties common() {
    return details.common();
  }
}
