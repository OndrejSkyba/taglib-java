// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.shorten;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.codec.ShortenCodec;
import cz.skyba.taglib.toolkit.IOStream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Parsed Shorten audio property fields.
 * Common projections report duration in milliseconds, sample rate in hertz and bitrate in
 * kilobits per second. Unknown numeric fields use zero. Parsed values do not own a file handle.
 */
public final class Properties {
  /**
   * Input values for constructing Shorten audio properties.
   */
  public record Values(int version, int fileType, int channels, int sampleRate,
                       int bitsPerSample, long sampleFrames) {
  }

  private final ShortenCodec.Details details;

  /**
   * Reads Shorten audio properties from supplied property values.
   *
   * @param values the property values, or {@code null} for zero values
   */
  public Properties(Values values) {
    this(values, ReadStyle.AVERAGE);
  }

  /**
   * Reads Shorten audio properties from supplied property values.
   *
   * @param style  the audio property read hint
   * @param values the property values, or {@code null} for zero values
   */
  public Properties(Values values, ReadStyle style) {
    Objects.requireNonNull(style);
    details = values == null ? new ShortenCodec.Details(AudioProperties.EMPTY, 0, 0, 0)
      : ShortenCodec.properties(values.version(), values.fileType(), values.channels(),
      values.sampleRate(), values.bitsPerSample(), values.sampleFrames());
  }

  /**
   * Reads Shorten audio properties from a file, closing its temporary handle.
   * Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
   *
   * @param path the audio file to inspect
   * @throws UncheckedIOException if storage access or property decoding fails
   */
  public Properties(Path path) {
    this(path, ReadStyle.AVERAGE);
  }

  /**
   * Reads Shorten audio properties from a file, closing its temporary handle.
   * Reading can move the source cursor. Values are independent of the source handle.
   *
   * @param path  the audio file to inspect
   * @param style the audio property read hint
   * @throws UncheckedIOException if storage access or property decoding fails
   */
  public Properties(Path path, ReadStyle style) {
    Objects.requireNonNull(style);
    try (var file = new BinaryFile(path)) {
      details = ShortenCodec.readProperties(file, style);
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata: " + path, failure);
    }
  }

  /**
   * Reads Shorten audio properties from a borrowed stream, leaving it open.
   * Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
   *
   * @param stream the open, seekable input stream
   * @throws UncheckedIOException if storage access or property decoding fails
   */
  public Properties(IOStream stream) {
    this(stream, ReadStyle.AVERAGE);
  }

  /**
   * Reads Shorten audio properties from a borrowed stream, leaving it open.
   * Reading can move the source cursor. Values are independent of the source handle.
   *
   * @param stream the open, seekable input stream
   * @param style  the audio property read hint
   * @throws UncheckedIOException if storage access or property decoding fails
   */
  public Properties(IOStream stream, ReadStyle style) {
    this(stream, new ReadOptions(true, style, ReadOptions.DEFAULT.maxMetadataBytes()));
  }

  /**
   * Reads Shorten audio properties from a borrowed stream, leaving it open.
   * Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
   *
   * @param stream  the open, seekable input stream
   * @param options read options and metadata allocation limit
   * @throws UncheckedIOException if storage access or property decoding fails
   */
  public Properties(IOStream stream, ReadOptions options) {
    Objects.requireNonNull(options);
    try (var file = new BinaryFile(stream)) {
      details = ShortenCodec.readProperties(file, options);
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

  public int shortenVersion() {
    return details.version();
  }

  public int fileType() {
    return details.fileType();
  }

  /**
   * Returns the unsigned 64-bit sample-frame count as a Java long bit pattern.
   * Use {@link Long#toUnsignedString(long)} for decimal presentation.
   *
   * @return the sample count bits
   */
  public long sampleFrames() {
    return details.sampleFrames();
  }

  /**
   * Creates the immutable common audio-property projection.
   * Unknown or unsupported fields use zero.
   *
   * @return common audio properties
   */
  public AudioProperties common() {
    return details.common();
  }
}
