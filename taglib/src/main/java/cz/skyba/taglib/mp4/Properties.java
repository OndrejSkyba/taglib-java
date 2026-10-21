// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.mp4;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.codec.Mp4Codec;
import cz.skyba.taglib.toolkit.IOStream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;

/// Parsed MP4 audio properties.
/// Values describe the decoded stream and do not own a file handle. Unknown numeric values use zero.
public final class Properties {
  public enum Codec {Unknown, AAC, ALAC, AC3, EAC3, FLAC, DTS, Opus}

  private static final Codec[] CODECS = Codec.values();
  private final Mp4Codec.Details details;

  /// Reads MP4 audio properties from a file, closing its temporary handle.
  /// Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
  ///
  /// @param path the audio file to inspect
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(Path path) {
    this(path, ReadStyle.AVERAGE);
  }

  /// Reads MP4 audio properties from a file, closing its temporary handle.
  /// Reading can move the source cursor. Values are independent of the source handle.
  ///
  /// @param path  the audio file to inspect
  /// @param style the audio property read hint
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(Path path, ReadStyle style) {
    Objects.requireNonNull(style);
    try (var file = new BinaryFile(path)) {
      details = Mp4Codec.readProperties(file, style);
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata: " + path, failure);
    }
  }

  /// Reads MP4 audio properties from a borrowed stream, leaving it open.
  /// Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
  ///
  /// @param stream the open, seekable input stream
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(IOStream stream) {
    this(stream, ReadStyle.AVERAGE);
  }

  /// Reads MP4 audio properties from a borrowed stream, leaving it open.
  /// Reading can move the source cursor. Values are independent of the source handle.
  ///
  /// @param stream the open, seekable input stream
  /// @param style  the audio property read hint
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(IOStream stream, ReadStyle style) {
    Objects.requireNonNull(style);
    try (var file = new BinaryFile(stream)) {
      details = Mp4Codec.readProperties(file, style);
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata", failure);
    }
  }

  /// Reads MP4 audio properties from the supplied parsed file or encoded property data.
  /// Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
  ///
  /// @param file  the live file to inspect without taking ownership
  /// @param atoms the parsed atom tree for this file
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(Mp4File file, Atoms atoms) {
    this(file, atoms, ReadStyle.AVERAGE);
  }

  /// Reads MP4 audio properties from the supplied parsed file or encoded property data.
  /// Reading can move the source cursor. Values are independent of the source handle.
  ///
  /// @param file  the live file to inspect without taking ownership
  /// @param atoms the parsed atom tree for this file
  /// @param style the audio property read hint
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(Mp4File file, Atoms atoms, ReadStyle style) {
    Objects.requireNonNull(style);
    try {
      details = Mp4Codec.readProperties(file, atoms);
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata", failure);
    }
  }

  public long lengthInMilliseconds() {
    return common().lengthInMilliseconds();
  }

  public long lengthInSeconds() {
    return common().lengthInSeconds();
  }

  public int bitrate() {
    return common().bitrate();
  }

  public int sampleRate() {
    return common().sampleRate();
  }

  public int channels() {
    return common().channels();
  }

  public int bitsPerSample() {
    return common().bitsPerSample();
  }

  public boolean isEncrypted() {
    return details.encrypted();
  }

  public Codec codec() {
    return CODECS[details.codec()];
  }

  public String codecId() {
    return details.codecId();
  }

  public AudioProperties common() {
    return details.common();
  }
}
