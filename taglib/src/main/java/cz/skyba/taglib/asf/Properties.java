// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.asf;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.codec.AsfCodec;
import cz.skyba.taglib.toolkit.IOStream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Mutable ASF audio property fields.
 * Common projections report duration in milliseconds, sample rate in hertz and bitrate in
 * kilobits per second. Unknown numeric fields use zero. Parsed values do not own a file handle.
 */
public final class Properties {
  public enum Codec {Unknown, WMA1, WMA2, WMA9Pro, WMA9Lossless}

  private int length, bitrate, sampleRate, channels, bitsPerSample;
  private Codec codec = Codec.Unknown;
  private String codecName = "", codecDescription = "";
  private boolean encrypted;

  /**
   * Creates zero-valued mutable audio property fields.
   */
  public Properties() {
  }

  /**
   * Internal decoded-state adapter; avoids rereading the owning AsfFile's stream.
   */
  Properties(AsfCodec.Details value) {
    set(Objects.requireNonNull(value));
  }

  /**
   * Reads ASF audio properties from a file, closing its temporary handle.
   * Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
   *
   * @param path the audio file to inspect
   * @throws UncheckedIOException if storage access or property decoding fails
   */
  public Properties(Path path) {
    this(path, ReadStyle.AVERAGE);
  }

  /**
   * Reads ASF audio properties from a file, closing its temporary handle.
   * Reading can move the source cursor. Values are independent of the source handle.
   *
   * @param path  the audio file to inspect
   * @param style the audio property read hint
   * @throws UncheckedIOException if storage access or property decoding fails
   */
  public Properties(Path path, ReadStyle style) {
    Objects.requireNonNull(style);
    try (var file = new BinaryFile(path)) {
      set(AsfCodec.readProperties(file, style));
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata: " + path, failure);
    }
  }

  /**
   * Reads ASF audio properties from a borrowed stream, leaving it open.
   * Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
   *
   * @param stream the open, seekable input stream
   * @throws UncheckedIOException if storage access or property decoding fails
   */
  public Properties(IOStream stream) {
    this(stream, ReadStyle.AVERAGE);
  }

  /**
   * Reads ASF audio properties from a borrowed stream, leaving it open.
   * Reading can move the source cursor. Values are independent of the source handle.
   *
   * @param stream the open, seekable input stream
   * @param style  the audio property read hint
   * @throws UncheckedIOException if storage access or property decoding fails
   */
  public Properties(IOStream stream, ReadStyle style) {
    Objects.requireNonNull(style);
    try (var file = new BinaryFile(stream)) {
      set(AsfCodec.readProperties(file, style));
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata", failure);
    }
  }

  private void set(AsfCodec.Details value) {
    var p = value.common();
    length = (int) p.lengthInMilliseconds();
    bitrate = p.bitrate();
    sampleRate = p.sampleRate();
    channels = p.channels();
    bitsPerSample = p.bitsPerSample();
    setCodec(value.codecId());
    codecName = value.codecName();
    codecDescription = value.codecDescription();
    encrypted = value.encrypted();
  }

  public long lengthInMilliseconds() {
    return length;
  }

  public long lengthInSeconds() {
    return length / 1000;
  }

  public int bitrate() {
    return bitrate;
  }

  public int sampleRate() {
    return sampleRate;
  }

  public int channels() {
    return channels;
  }

  public int bitsPerSample() {
    return bitsPerSample;
  }

  public Codec codec() {
    return codec;
  }

  public String codecName() {
    return codecName;
  }

  public String codecDescription() {
    return codecDescription;
  }

  public boolean isEncrypted() {
    return encrypted;
  }

  public void setLengthInMilliseconds(int value) {
    length = value;
  }

  public void setBitrate(int value) {
    bitrate = value;
  }

  public void setSampleRate(int value) {
    sampleRate = value;
  }

  public void setChannels(int value) {
    channels = value;
  }

  public void setBitsPerSample(int value) {
    bitsPerSample = value;
  }

  /**
   * Selects a codec using its WAVE format number, not the Codec enum ordinal.
   *
   * @param value the WAVE codec code
   */
  public void setCodec(int value) {
    codec = switch (value) {
      case 0x160 -> Codec.WMA1;
      case 0x161 -> Codec.WMA2;
      case 0x162 -> Codec.WMA9Pro;
      case 0x163 -> Codec.WMA9Lossless;
      default -> Codec.Unknown;
    };
  }

  public void setCodecName(String value) {
    codecName = Objects.requireNonNull(value);
  }

  public void setCodecDescription(String value) {
    codecDescription = Objects.requireNonNull(value);
  }

  public void setEncrypted(boolean value) {
    encrypted = value;
  }

  /**
   * Creates the immutable common audio-property projection.
   * Unknown or unsupported fields use zero.
   *
   * @return common audio properties
   */
  public AudioProperties common() {
    return new AudioProperties(length, bitrate, sampleRate, channels, bitsPerSample, codec == Codec.WMA9Lossless);
  }
}
