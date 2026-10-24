// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.xm;

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

/// Mutable XM tracker audio properties.
/// Values describe the decoded stream and do not own a file handle. Unknown numeric values use zero.
public final class Properties {
  public static final int LinearFreqTable = 1;
  private int lengthInPatterns;
  private int channels;
  private int version;
  private int restartPosition;
  private int patternCount;
  private int instrumentCount;
  private long sampleCount;
  private int flags;
  private int tempo;
  private int bpmSpeed;

  public Properties(ReadStyle style) {
    Objects.requireNonNull(style);
  }

  /// Reads XM tracker audio properties from a file, closing its temporary handle.
  /// Values are independent of the source handle. Uses the average read-style hint.
  ///
  /// @param path the audio file to inspect
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(Path path) {
    this(path, ReadStyle.AVERAGE);
  }

  /// Reads XM tracker audio properties from a file, closing its temporary handle.
  /// Values are independent of the source handle.
  ///
  /// @param path  the audio file to inspect
  /// @param style the audio property read hint
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(Path path, ReadStyle style) {
    this(style);
    try (var file = new BinaryFile(path)) {
      assign((TrackerCodec.XmDetails) TrackerCodec.readProperties(file, Format.XM, style));
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata: " + path, failure);
    }
  }

  /// Reads XM tracker audio properties from a borrowed stream, leaving it open.
  /// Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
  ///
  /// @param stream the open, seekable input stream
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(IOStream stream) {
    this(stream, ReadStyle.AVERAGE);
  }

  /// Reads XM tracker audio properties from a borrowed stream, leaving it open.
  /// Reading can move the source cursor. Values are independent of the source handle.
  ///
  /// @param stream the open, seekable input stream
  /// @param style  the audio property read hint
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(IOStream stream, ReadStyle style) {
    this(style);
    try (var file = new BinaryFile(stream)) {
      assign((TrackerCodec.XmDetails) TrackerCodec.readProperties(file, Format.XM, style));
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata", failure);
    }
  }

  private void assign(TrackerCodec.XmDetails values) {
    lengthInPatterns = values.lengthInPatterns();
    channels = values.channels();
    version = values.version();
    restartPosition = values.restartPosition();
    patternCount = values.patternCount();
    instrumentCount = values.instrumentCount();
    sampleCount = values.sampleCount();
    flags = values.flags();
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

  public AudioProperties common() {
    return new AudioProperties(0, 0, 0, channels, 0, false);
  }

  public int lengthInPatterns() {
    return lengthInPatterns;
  }

  public int channels() {
    return channels;
  }

  public int version() {
    return version;
  }

  public int restartPosition() {
    return restartPosition;
  }

  public int patternCount() {
    return patternCount;
  }

  public int instrumentCount() {
    return instrumentCount;
  }

  public long sampleCount() {
    return sampleCount;
  }

  public int flags() {
    return flags;
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

  public void setVersion(int value) {
    version = value & 0xffff;
  }

  public void setRestartPosition(int value) {
    restartPosition = value & 0xffff;
  }

  public void setPatternCount(int value) {
    patternCount = value & 0xffff;
  }

  public void setInstrumentCount(int value) {
    instrumentCount = value & 0xffff;
  }

  public void setSampleCount(long value) {
    sampleCount = value & 0xffff_ffffL;
  }

  public void setFlags(int value) {
    flags = value & 0xffff;
  }

  public void setTempo(int value) {
    tempo = value & 0xffff;
  }

  public void setBpmSpeed(int value) {
    bpmSpeed = value & 0xffff;
  }
}
