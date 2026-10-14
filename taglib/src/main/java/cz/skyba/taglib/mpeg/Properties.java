// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.mpeg;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.codec.MpegCodec;
import cz.skyba.taglib.toolkit.IOStream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Parsed MPEG audio audio property fields.
 * Common projections report duration in milliseconds, sample rate in hertz and bitrate in
 * kilobits per second. Unknown numeric fields use zero. Parsed values do not own a file handle.
 */
public final class Properties {
  private static final Header.Version[] VERSION_VALUES = Header.Version.values();
  private static final Header.ChannelMode[] CHANNELMODE_VALUES = Header.ChannelMode.values();
  private static final Header.ChannelConfiguration[] CHANNELCONFIGURATION_VALUES = Header.ChannelConfiguration.values();

  private final MpegCodec.Details details;

  /**
   * Reads MPEG audio audio properties from the supplied parsed file or encoded property data.
   * Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
   *
   * @param file the live file to inspect without taking ownership
   * @throws UncheckedIOException if storage access or property decoding fails
   */
  public Properties(MpegFile file) {
    this(file, ReadStyle.AVERAGE);
  }

  /**
   * Reads MPEG audio audio properties from the supplied parsed file or encoded property data.
   * Reading can move the source cursor. Values are independent of the source handle.
   *
   * @param file  the live file to inspect without taking ownership
   * @param style the audio property read hint
   * @throws UncheckedIOException if storage access or property decoding fails
   */
  public Properties(MpegFile file, ReadStyle style) {
    details = MpegCodec.readProperties(Objects.requireNonNull(file), Objects.requireNonNull(style));
  }

  /**
   * Reads MPEG audio audio properties from a file, closing its temporary handle.
   * Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
   *
   * @param path the audio file to inspect
   * @throws UncheckedIOException if storage access or property decoding fails
   */
  public Properties(Path path) {
    this(path, ReadStyle.AVERAGE);
  }

  /**
   * Reads MPEG audio audio properties from a file, closing its temporary handle.
   * Reading can move the source cursor. Values are independent of the source handle.
   *
   * @param path  the audio file to inspect
   * @param style the audio property read hint
   * @throws UncheckedIOException if storage access or property decoding fails
   */
  public Properties(Path path, ReadStyle style) {
    Objects.requireNonNull(style);
    try (var file = new BinaryFile(path)) {
      details = MpegCodec.readProperties(file, style);
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata: " + path, failure);
    }
  }

  /**
   * Reads MPEG audio audio properties from a borrowed stream, leaving it open.
   * Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
   *
   * @param stream the open, seekable input stream
   * @throws UncheckedIOException if storage access or property decoding fails
   */
  public Properties(IOStream stream) {
    this(stream, ReadStyle.AVERAGE);
  }

  /**
   * Reads MPEG audio audio properties from a borrowed stream, leaving it open.
   * Reading can move the source cursor. Values are independent of the source handle.
   *
   * @param stream the open, seekable input stream
   * @param style  the audio property read hint
   * @throws UncheckedIOException if storage access or property decoding fails
   */
  public Properties(IOStream stream, ReadStyle style) {
    Objects.requireNonNull(style);
    try (var file = new BinaryFile(stream)) {
      details = MpegCodec.readProperties(file, style);
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

  /**
   * Returns null when the first frame has no valid Xing/Info/VBRI totals.
   */
  public XingHeader xingHeader() {
    return details.xingHeader();
  }

  public Header.Version version() {
    return VERSION_VALUES[details.header().version()];
  }

  public int layer() {
    return details.header().layer();
  }

  public boolean protectionEnabled() {
    return details.header().protectionEnabled();
  }

  public Header.ChannelMode channelMode() {
    return CHANNELMODE_VALUES[details.header().channelMode()];
  }

  public Header.ChannelConfiguration channelConfiguration() {
    return CHANNELCONFIGURATION_VALUES[details.header().channelConfiguration()];
  }

  public boolean isADTS() {
    return details.header().isADTS();
  }

  public boolean isCopyrighted() {
    return details.header().copyrighted();
  }

  public boolean isOriginal() {
    return details.header().original();
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
