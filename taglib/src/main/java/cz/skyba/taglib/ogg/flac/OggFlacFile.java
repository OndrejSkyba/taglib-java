// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.ogg.flac;

import cz.skyba.taglib.Format;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.flac.Properties;
import cz.skyba.taglib.internal.OggCommentFile;
import cz.skyba.taglib.toolkit.IOStream;

import java.nio.file.Path;

/// An editable Ogg FLAC file with live metadata and format-specific audio properties.
/// Path construction owns storage; stream construction borrows it. Metadata edits require
/// [#save()] unless an operation explicitly writes immediately. Closing does not save.
public class OggFlacFile extends OggCommentFile {
  /// Opens a Ogg FLAC file and owns the file handle. Uses default read options.
  ///
  /// @param path the file to open
  /// @throws java.io.UncheckedIOException    if storage access fails
  /// @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
  public OggFlacFile(Path path) {
    this(path, ReadOptions.DEFAULT);
  }

  /// Opens a Ogg FLAC file and owns the file handle.
  ///
  /// @param path    the file to open
  /// @param options audio property selection and metadata allocation limit
  /// @throws java.io.UncheckedIOException    if storage access fails
  /// @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
  public OggFlacFile(Path path, ReadOptions options) {
    super(openPath(path, options), true, options, Format.OGG_FLAC);
  }

  /// Opens a Ogg FLAC file over a borrowed stream that remains open when this file closes. Uses default read options.
  ///
  /// @param stream the open, seekable audio stream
  /// @throws java.io.UncheckedIOException    if storage access fails
  /// @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
  /// @throws IllegalStateException           if the supplied stream is closed
  public OggFlacFile(IOStream stream) {
    this(stream, ReadOptions.DEFAULT);
  }

  /// Opens a Ogg FLAC file over a borrowed stream that remains open when this file closes.
  ///
  /// @param stream  the open, seekable audio stream
  /// @param options audio property selection and metadata allocation limit
  /// @throws java.io.UncheckedIOException    if storage access fails
  /// @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
  /// @throws IllegalStateException           if the supplied stream is closed
  public OggFlacFile(IOStream stream, ReadOptions options) {
    super(stream, false, options, Format.OGG_FLAC);
  }

  /// @return the detailed properties, or `null` if properties were not read or are unavailable
  public Properties detailedProperties() {
    return (Properties) codecProperties();
  }

  /// Checks for a recognizable Ogg FLAC header without validating the complete file.
  /// The input cursor is restored after inspection.
  ///
  /// @param stream the stream to inspect
  /// @return whether a format header is recognized
  /// @throws java.io.UncheckedIOException if storage access fails
  public static boolean isSupported(IOStream stream) {
    return supported(stream, "fLaC");
  }

  public final long streamLength() {
    return flacStreamLength();
  }

  public final boolean hasXiphComment() {
    return flacHasXiphComment();
  }
}
