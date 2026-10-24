// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.matroska;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.codec.MatroskaCodec;
import cz.skyba.taglib.toolkit.IOStream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;

/// Parsed Matroska audio and container properties.
/// Keep a borrowed source stream open until the first call to [#bitrate()] or [#common()]. Path-based
/// values retain no file handle. Duration is milliseconds and bitrate is kilobits per second.
public final class Properties {
  private final MatroskaCodec.Details details;
  private final IOStream borrowed;
  private int cachedBitrate;

  static Properties live(MatroskaCodec.Details details, IOStream source) {
    return new Properties(details, source);
  }

  private Properties(MatroskaCodec.Details details, IOStream source) {
    this.details = details;
    borrowed = source;
    cachedBitrate = -1;
  }

  public Properties() {
    this(ReadStyle.AVERAGE);
  }

  public Properties(ReadStyle style) {
    Objects.requireNonNull(style);
    details = new MatroskaCodec.Details(AudioProperties.EMPTY, "", 0, "", "");
    borrowed = null;
    cachedBitrate = 0;
  }

  /// Reads properties from a path, closing its temporary handle.
  ///
  /// @param path the input file
  /// @throws UncheckedIOException if storage access fails
  public Properties(Path path) {
    this(path, ReadStyle.AVERAGE);
  }

  /// Reads properties from a path, closing its temporary handle.
  ///
  /// @param path  the input file
  /// @param style the read-style hint
  /// @throws UncheckedIOException if storage access fails
  public Properties(Path path, ReadStyle style) {
    Objects.requireNonNull(style);
    try (var file = new BinaryFile(path)) {
      details = MatroskaCodec.readProperties(file, style);
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata: " + path, failure);
    }
    borrowed = null;
    cachedBitrate = details.common().bitrate();
  }

  /// Reads properties from a borrowed stream, leaving it open for lazy bitrate calculation.
  ///
  /// @param stream the open, seekable source
  /// @throws UncheckedIOException if storage access fails
  public Properties(IOStream stream) {
    this(stream, ReadStyle.AVERAGE);
  }

  /// Reads properties from a borrowed stream, leaving it open for lazy bitrate calculation.
  ///
  /// @param stream the open, seekable source
  /// @param style  the read-style hint
  /// @throws UncheckedIOException if storage access fails
  public Properties(IOStream stream, ReadStyle style) {
    Objects.requireNonNull(style);
    try (var file = new BinaryFile(stream)) {
      details = MatroskaCodec.readProperties(file, style);
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata", failure);
    }
    borrowed = stream;
    cachedBitrate = -1;
  }

  public long lengthInMilliseconds() {
    return details.common().lengthInMilliseconds();
  }

  public long lengthInSeconds() {
    return lengthInMilliseconds() / 1000;
  }

  /// {@return the bitrate in kilobits per second}
  public int bitrate() {
    if (cachedBitrate == -1) {
      long length = lengthInMilliseconds(), bytes = borrowed.length();
      // Native signed filesize multiplication is undefined outside this range.
      cachedBitrate = length != 0 && bytes >= 0 && bytes <= Long.MAX_VALUE / 8
        ? (int) (bytes * 8 / length) : 0;
    }
    return cachedBitrate;
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

  public String docType() {
    return details.docType();
  }

  public int docTypeVersion() {
    return details.docTypeVersion();
  }

  public String codecName() {
    return details.codecName();
  }

  public String title() {
    return details.title();
  }

  /// Creates an immutable snapshot of the common audio properties.
  ///
  /// @return common properties
  /// @throws UncheckedIOException if querying borrowed storage fails
  public AudioProperties common() {
    return new AudioProperties(lengthInMilliseconds(), bitrate(), sampleRate(), channels(), bitsPerSample(),
      details.common().lossless());
  }
}
