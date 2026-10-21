// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.mod;

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

/// Mutable MOD tracker audio properties.
/// Values describe the decoded stream and do not own a file handle. Unknown numeric values use zero.
public final class Properties {
  private int channels;
  private long instrumentCount;
  private int lengthInPatterns;

  public Properties(ReadStyle style) {
    Objects.requireNonNull(style);
  }

  /// Reads MOD tracker audio properties from a file, closing its temporary handle.
  /// Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
  ///
  /// @param path the audio file to inspect
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(Path path) {
    this(path, ReadStyle.AVERAGE);
  }

  /// Reads MOD tracker audio properties from a file, closing its temporary handle.
  /// Reading can move the source cursor. Values are independent of the source handle.
  ///
  /// @param path  the audio file to inspect
  /// @param style the audio property read hint
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(Path path, ReadStyle style) {
    this(style);
    try (var file = new BinaryFile(path)) {
      assign((TrackerCodec.ModDetails) TrackerCodec.readProperties(file, Format.MOD, style));
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata: " + path, failure);
    }
  }

  /// Reads MOD tracker audio properties from a borrowed stream, leaving it open.
  /// Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
  ///
  /// @param stream the open, seekable input stream
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(IOStream stream) {
    this(stream, ReadStyle.AVERAGE);
  }

  /// Reads MOD tracker audio properties from a borrowed stream, leaving it open.
  /// Reading can move the source cursor. Values are independent of the source handle.
  ///
  /// @param stream the open, seekable input stream
  /// @param style  the audio property read hint
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(IOStream stream, ReadStyle style) {
    this(style);
    try (var file = new BinaryFile(stream)) {
      assign((TrackerCodec.ModDetails) TrackerCodec.readProperties(file, Format.MOD, style));
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata", failure);
    }
  }

  private void assign(TrackerCodec.ModDetails values) {
    channels = values.channels();
    instrumentCount = values.instrumentCount();
    lengthInPatterns = values.lengthInPatterns();
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

  public int channels() {
    return channels;
  }

  public long instrumentCount() {
    return instrumentCount;
  }

  public int lengthInPatterns() {
    return lengthInPatterns;
  }

  // Unsigned assignments narrow modulo the native width, as C++ parameter conversion does.
  public void setChannels(int value) {
    channels = value;
  }

  public void setInstrumentCount(long value) {
    instrumentCount = value & 0xffff_ffffL;
  }

  public void setLengthInPatterns(int value) {
    lengthInPatterns = value & 0xff;
  }
}
