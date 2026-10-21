// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.s3m;

import cz.skyba.taglib.Format;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.internal.codec.TrackerFile;
import cz.skyba.taglib.toolkit.IOStream;

import java.nio.file.Path;

/// An editable S3M tracker file with live metadata and format-specific audio properties.
/// Path construction owns storage; stream construction borrows it. Metadata edits require
/// [#save()] unless an operation explicitly writes immediately. Closing does not save.
/// Audio properties are parsed even when optional property reading is disabled.
public class S3mFile extends TrackerFile {
  /// Opens a S3M tracker file and owns the file handle. Uses default read options.
  /// Audio property parsing is required for this format.
  ///
  /// @param path the file to open
  /// @throws java.io.UncheckedIOException    if storage access fails
  /// @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
  public S3mFile(Path path) {
    this(path, ReadStyle.AVERAGE);
  }

  /// Opens a S3M tracker file and owns the file handle.
  /// Audio property parsing is required for this format.
  ///
  /// @param path  the file to open
  /// @param style the audio property read hint
  /// @throws java.io.UncheckedIOException    if storage access fails
  /// @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
  public S3mFile(Path path, ReadStyle style) {
    super(path, Format.S3M, style);
  }

  /// Opens a S3M tracker file over a borrowed stream that remains open when this file closes. Uses default read options.
  /// Audio property parsing is required for this format.
  ///
  /// @param stream the open, seekable audio stream
  /// @throws java.io.UncheckedIOException    if storage access fails
  /// @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
  /// @throws IllegalStateException           if the supplied stream is closed
  public S3mFile(IOStream stream) {
    this(stream, ReadStyle.AVERAGE);
  }

  /// Opens a S3M tracker file over a borrowed stream that remains open when this file closes.
  /// Audio property parsing is required for this format.
  ///
  /// @param stream the open, seekable audio stream
  /// @param style  the audio property read hint
  /// @throws java.io.UncheckedIOException    if storage access fails
  /// @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
  /// @throws IllegalStateException           if the supplied stream is closed
  public S3mFile(IOStream stream, ReadStyle style) {
    super(stream, Format.S3M, style);
  }

  /// @return the detailed properties, or `null` if properties were not read or are unavailable
  public Properties detailedProperties() {
    return (Properties) detailedState();
  }
}
