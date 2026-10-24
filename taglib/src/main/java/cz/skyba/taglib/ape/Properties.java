// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.ape;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.Format;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.codec.ApeCodec;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.IOStream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;

/// Parsed Monkey's Audio audio properties.
/// Values describe the decoded stream and do not own a file handle. Unknown numeric values use zero.
public final class Properties {
  private final ApeCodec.Details details;

  /// Reads Monkey's Audio audio properties from the supplied parsed file.
  /// Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
  ///
  /// @param file         the live file to inspect without taking ownership
  /// @param streamLength the byte length used to calculate bitrate
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(ApeFile file, long streamLength) {
    this(file, streamLength, ReadStyle.AVERAGE);
  }

  /// Reads Monkey's Audio audio properties from the supplied parsed file.
  /// Reading can move the source cursor. Values are independent of the source handle.
  ///
  /// @param file         the live file to inspect without taking ownership
  /// @param streamLength the byte length used to calculate bitrate
  /// @param style        the audio property read hint
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(ApeFile file, long streamLength, ReadStyle style) {
    Objects.requireNonNull(file);
    Objects.requireNonNull(style);
    details = read(file, streamLength);
  }

  private static ApeCodec.Details read(ApeFile file, long streamLength) {
    long offset = file.tell();
    var header = file.readBlock(6);
    if (header.size() < 6 || !header.startsWith(new ByteVector("MAC "))) {
      offset = file.find(new ByteVector("MAC "), offset);
      if (offset < 0) {
        return details(AudioProperties.EMPTY, 0, 0);
      }
      file.seek(offset);
      header = file.readBlock(6);
    }
    if (header.size() < 6 || !header.startsWith(new ByteVector("MAC "))) {
      return details(AudioProperties.EMPTY, 0, 0);
    }
    int version = header.toUnsignedShort(4, false), channels = 0, rate = 0, bits = 0;
    long samples = 0;
    if (version >= 3980) {
      file.seek(2, IOStream.Position.CURRENT);
      var descriptor = file.readBlock(44);
      if (descriptor.size() >= 44) {
        long extra = (descriptor.toUnsignedInt(0, false) - 52) & 0xffff_ffffL;
        if (extra > 0) {
          file.seek(extra, IOStream.Position.CURRENT);
        }
        var data = file.readBlock(24);
        if (data.size() >= 24) {
          channels = data.toShort(18, false);
          rate = (int) data.toUnsignedInt(20, false);
          bits = data.toShort(16, false);
          long frames = data.toUnsignedInt(12, false);
          if (frames > 0) {
            samples = ((frames - 1) * data.toUnsignedInt(4, false) + data.toUnsignedInt(8, false)) & 0xffff_ffffL;
          }
        }
      }
    } else {
      var data = file.readBlock(26);
      if (data.size() >= 26 && data.toUnsignedInt(18, false) != 0) {
        int compression = data.toShort(0, false);
        int blocks = version >= 3950 ? 73728 * 4
          : version >= 3900 || (version >= 3800 && compression == 4000) ? 73728 : 9216;
        channels = data.toShort(4, false);
        rate = (int) data.toUnsignedInt(6, false);
        samples = ((data.toUnsignedInt(18, false) - 1) * blocks + data.toUnsignedInt(22, false)) & 0xffff_ffffL;
        file.seek(16, IOStream.Position.CURRENT);
        var fmt = file.readBlock(28);
        if (fmt.size() >= 28 && fmt.startsWith(new ByteVector("WAVEfmt "))) {
          bits = fmt.toShort(26, false);
        }
      }
    }
    double ms = samples > 0 && rate > 0 ? samples * 1000.0 / rate : 0;
    long duration = ms > 0 && ms < Integer.MAX_VALUE ? (int) (ms + .5) : 0;
    double kbps = ms > 0 ? streamLength * 8.0 / ms : -1;
    int bitrate = ms > 0 && ms < Integer.MAX_VALUE && kbps >= 0 && kbps < Integer.MAX_VALUE
      ? (int) (kbps + .5) : 0;
    return details(new AudioProperties(duration, bitrate, rate, channels, bits, true), version, samples);
  }

  private static ApeCodec.Details details(AudioProperties common, int version, long samples) {
    return new ApeCodec.Details(common, version, samples, 0, 0, 0, 0, 0, false);
  }

  /// Reads Monkey's Audio audio properties from a file, closing its temporary handle.
  /// Values are independent of the source handle. Uses the average read-style hint.
  ///
  /// @param path the audio file to inspect
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(Path path) {
    this(path, ReadStyle.AVERAGE);
  }

  /// Reads Monkey's Audio audio properties from a file, closing its temporary handle.
  /// Values are independent of the source handle.
  ///
  /// @param path  the audio file to inspect
  /// @param style the audio property read hint
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(Path path, ReadStyle style) {
    Objects.requireNonNull(style);
    try (var file = new BinaryFile(path)) {
      details = ApeCodec.readProperties(file, Format.APE);
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata: " + path, failure);
    }
  }

  /// Reads Monkey's Audio audio properties from a borrowed stream, leaving it open.
  /// Reading can move the source cursor. Values are independent of the source handle. Uses the average read-style hint.
  ///
  /// @param stream the open, seekable input stream
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(IOStream stream) {
    this(stream, ReadStyle.AVERAGE);
  }

  /// Reads Monkey's Audio audio properties from a borrowed stream, leaving it open.
  /// Reading can move the source cursor. Values are independent of the source handle.
  ///
  /// @param stream the open, seekable input stream
  /// @param style  the audio property read hint
  /// @throws UncheckedIOException if storage access or property decoding fails
  public Properties(IOStream stream, ReadStyle style) {
    Objects.requireNonNull(style);
    try (var file = new BinaryFile(stream)) {
      details = ApeCodec.readProperties(file, Format.APE);
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

  public int bitsPerSample() {
    return details.common().bitsPerSample();
  }

  public int version() {
    return details.version();
  }

  /// {@return the unsigned 32-bit sample-frame count}
  public long sampleFrames() {
    return details.sampleFrames();
  }

  public AudioProperties common() {
    return details.common();
  }
}
