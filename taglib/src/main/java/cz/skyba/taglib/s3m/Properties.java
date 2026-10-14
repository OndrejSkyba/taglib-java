// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.s3m;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.Format;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.codec.TrackerCodec;
import cz.skyba.taglib.toolkit.IOStream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Mutable S3M tracker audio property fields.
 * Common projections report duration in milliseconds, sample rate in hertz and bitrate in
 * kilobits per second. Unknown numeric fields use zero. Parsed values do not own a file handle.
 */
public final class Properties {
  public static final int ST2Vibrato = 1;
  public static final int ST2Tempo = 2;
  public static final int AmigaSlides = 4;
  public static final int Vol0MixOptimizations = 8;
  public static final int AmigaLimits = 16;
  public static final int EnableFilter = 32;
  public static final int CustomData = 128;
  private int lengthInPatterns;
  private int channels;
  private boolean stereo;
  private int sampleCount;
  private int patternCount;
  private int flags;
  private int trackerVersion;
  private int fileFormatVersion;
  private int globalVolume;
  private int masterVolume;
  private int tempo;
  private int bpmSpeed;

  /**
   * Creates zero-valued mutable audio property fields.
   *
   * @param style the audio property read hint
   */
  public Properties(ReadStyle style) {
    Objects.requireNonNull(style);
  }

  /**
   * Reads S3M tracker audio properties from a file, closing its temporary handle.
   * Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
   *
   * @param path the audio file to inspect
   * @throws UncheckedIOException if storage access or property decoding fails
   */
  public Properties(Path path) {
    this(path, ReadStyle.AVERAGE);
  }

  /**
   * Reads S3M tracker audio properties from a file, closing its temporary handle.
   * Reading can move the source cursor. Values are independent of the source handle.
   *
   * @param path  the audio file to inspect
   * @param style the audio property read hint
   * @throws UncheckedIOException if storage access or property decoding fails
   */
  public Properties(Path path, ReadStyle style) {
    this(style);
    try (var file = new BinaryFile(path)) {
      assign((TrackerCodec.S3mDetails) TrackerCodec.readProperties(file, Format.S3M, style));
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata: " + path, failure);
    }
  }

  /**
   * Reads S3M tracker audio properties from a borrowed stream, leaving it open.
   * Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
   *
   * @param stream the open, seekable input stream
   * @throws UncheckedIOException if storage access or property decoding fails
   */
  public Properties(IOStream stream) {
    this(stream, ReadStyle.AVERAGE);
  }

  /**
   * Reads S3M tracker audio properties from a borrowed stream, leaving it open.
   * Reading can move the source cursor. Values are independent of the source handle.
   *
   * @param stream the open, seekable input stream
   * @param style  the audio property read hint
   * @throws UncheckedIOException if storage access or property decoding fails
   */
  public Properties(IOStream stream, ReadStyle style) {
    this(style);
    try (var file = new BinaryFile(stream)) {
      assign((TrackerCodec.S3mDetails) TrackerCodec.readProperties(file, Format.S3M, style));
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata", failure);
    }
  }

  private void assign(TrackerCodec.S3mDetails values) {
    lengthInPatterns = values.lengthInPatterns();
    channels = values.channels();
    stereo = values.stereo();
    sampleCount = values.sampleCount();
    patternCount = values.patternCount();
    flags = values.flags();
    trackerVersion = values.trackerVersion();
    fileFormatVersion = values.fileFormatVersion();
    globalVolume = values.globalVolume();
    masterVolume = values.masterVolume();
    tempo = values.tempo();
    bpmSpeed = values.bpmSpeed();
  }

  public long lengthInMilliseconds() {
    return 0;
  }

  public long lengthInSeconds() {
    return 0;
  }

  public int bitrate() {
    return 0;
  }

  public int sampleRate() {
    return 0;
  }

  /**
   * Creates the immutable common audio-property projection.
   * Unknown or unsupported fields use zero.
   *
   * @return common audio properties
   */
  public AudioProperties common() {
    return new AudioProperties(0, 0, 0, channels, 0, false);
  }

  public int lengthInPatterns() {
    return lengthInPatterns;
  }

  public int channels() {
    return channels;
  }

  public boolean stereo() {
    return stereo;
  }

  public int sampleCount() {
    return sampleCount;
  }

  public int patternCount() {
    return patternCount;
  }

  public int flags() {
    return flags;
  }

  public int trackerVersion() {
    return trackerVersion;
  }

  public int fileFormatVersion() {
    return fileFormatVersion;
  }

  public int globalVolume() {
    return globalVolume;
  }

  public int masterVolume() {
    return masterVolume;
  }

  public int tempo() {
    return tempo;
  }

  public int bpmSpeed() {
    return bpmSpeed;
  }

  // Unsigned assignments narrow modulo the native width, as C++ parameter conversion does.
  public void setLengthInPatterns(int value) {
    lengthInPatterns = value & 0xffff;
  }

  public void setChannels(int value) {
    channels = value;
  }

  public void setStereo(boolean value) {
    stereo = value;
  }

  public void setSampleCount(int value) {
    sampleCount = value & 0xffff;
  }

  public void setPatternCount(int value) {
    patternCount = value & 0xffff;
  }

  public void setFlags(int value) {
    flags = value & 0xffff;
  }

  public void setTrackerVersion(int value) {
    trackerVersion = value & 0xffff;
  }

  public void setFileFormatVersion(int value) {
    fileFormatVersion = value & 0xffff;
  }

  public void setGlobalVolume(int value) {
    globalVolume = value & 0xff;
  }

  public void setMasterVolume(int value) {
    masterVolume = value & 0xff;
  }

  public void setTempo(int value) {
    tempo = value & 0xff;
  }

  public void setBpmSpeed(int value) {
    bpmSpeed = value & 0xff;
  }
}
