// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.it;

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

/// Mutable IT tracker audio properties.
/// Values describe the decoded stream and do not own a file handle. Unknown numeric values use zero.
public final class Properties {
  public static final int Stereo = 1;
  public static final int Vol0MixOptimizations = 2;
  public static final int UseInstruments = 4;
  public static final int LinearSlides = 8;
  public static final int OldEffects = 16;
  public static final int LinkEffects = 32;
  public static final int UseMidiPitchController = 64;
  public static final int RequestEmbeddedMidiConf = 128;
  public static final int MessageAttached = 1;
  public static final int MidiConfEmbedded = 8;
  private int channels;
  private int lengthInPatterns;
  private int instrumentCount;
  private int sampleCount;
  private int patternCount;
  private int version;
  private int compatibleVersion;
  private int flags;
  private int special;
  private int globalVolume;
  private int mixVolume;
  private int tempo;
  private int bpmSpeed;
  private int panningSeparation;
  private int pitchWheelDepth;

  public Properties(ReadStyle style) {
    Objects.requireNonNull(style);
  }

  /// Reads IT tracker audio properties from a file, closing its temporary handle.
  /// Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
  ///
  /// @param path the audio file to inspect
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(Path path) {
    this(path, ReadStyle.AVERAGE);
  }

  /// Reads IT tracker audio properties from a file, closing its temporary handle.
  /// Reading can move the source cursor. Values are independent of the source handle.
  ///
  /// @param path  the audio file to inspect
  /// @param style the audio property read hint
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(Path path, ReadStyle style) {
    this(style);
    try (var file = new BinaryFile(path)) {
      assign((TrackerCodec.ItDetails) TrackerCodec.readProperties(file, Format.IT, style));
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata: " + path, failure);
    }
  }

  /// Reads IT tracker audio properties from a borrowed stream, leaving it open.
  /// Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
  ///
  /// @param stream the open, seekable input stream
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(IOStream stream) {
    this(stream, ReadStyle.AVERAGE);
  }

  /// Reads IT tracker audio properties from a borrowed stream, leaving it open.
  /// Reading can move the source cursor. Values are independent of the source handle.
  ///
  /// @param stream the open, seekable input stream
  /// @param style  the audio property read hint
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(IOStream stream, ReadStyle style) {
    this(style);
    try (var file = new BinaryFile(stream)) {
      assign((TrackerCodec.ItDetails) TrackerCodec.readProperties(file, Format.IT, style));
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata", failure);
    }
  }

  private void assign(TrackerCodec.ItDetails values) {
    channels = values.channels();
    lengthInPatterns = values.lengthInPatterns();
    instrumentCount = values.instrumentCount();
    sampleCount = values.sampleCount();
    patternCount = values.patternCount();
    version = values.version();
    compatibleVersion = values.compatibleVersion();
    flags = values.flags();
    special = values.special();
    globalVolume = values.globalVolume();
    mixVolume = values.mixVolume();
    tempo = values.tempo();
    bpmSpeed = values.bpmSpeed();
    panningSeparation = values.panningSeparation();
    pitchWheelDepth = values.pitchWheelDepth();
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

  public boolean stereo() {
    return (flags & Stereo) != 0;
  }

  public int channels() {
    return channels;
  }

  public int lengthInPatterns() {
    return lengthInPatterns;
  }

  public int instrumentCount() {
    return instrumentCount;
  }

  public int sampleCount() {
    return sampleCount;
  }

  public int patternCount() {
    return patternCount;
  }

  public int version() {
    return version;
  }

  public int compatibleVersion() {
    return compatibleVersion;
  }

  public int flags() {
    return flags;
  }

  public int special() {
    return special;
  }

  public int globalVolume() {
    return globalVolume;
  }

  public int mixVolume() {
    return mixVolume;
  }

  public int tempo() {
    return tempo;
  }

  public int bpmSpeed() {
    return bpmSpeed;
  }

  public int panningSeparation() {
    return panningSeparation;
  }

  public int pitchWheelDepth() {
    return pitchWheelDepth;
  }

  // Unsigned assignments narrow modulo the native width, as C++ parameter conversion does.
  public void setChannels(int value) {
    channels = value;
  }

  public void setLengthInPatterns(int value) {
    lengthInPatterns = value & 0xffff;
  }

  public void setInstrumentCount(int value) {
    instrumentCount = value & 0xffff;
  }

  public void setSampleCount(int value) {
    sampleCount = value & 0xffff;
  }

  public void setPatternCount(int value) {
    patternCount = value & 0xffff;
  }

  public void setVersion(int value) {
    version = value & 0xffff;
  }

  public void setCompatibleVersion(int value) {
    compatibleVersion = value & 0xffff;
  }

  public void setFlags(int value) {
    flags = value & 0xffff;
  }

  public void setSpecial(int value) {
    special = value & 0xffff;
  }

  public void setGlobalVolume(int value) {
    globalVolume = value & 0xff;
  }

  public void setMixVolume(int value) {
    mixVolume = value & 0xff;
  }

  public void setTempo(int value) {
    tempo = value & 0xff;
  }

  public void setBpmSpeed(int value) {
    bpmSpeed = value & 0xff;
  }

  public void setPanningSeparation(int value) {
    panningSeparation = value & 0xff;
  }

  public void setPitchWheelDepth(int value) {
    pitchWheelDepth = value & 0xff;
  }
}
