// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.ogg.vorbis;

import cz.skyba.taglib.Format;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.internal.OggCommentFile;
import cz.skyba.taglib.toolkit.IOStream;

import java.nio.file.Path;

/**
 * An editable Ogg Vorbis file with live metadata and format-specific audio properties.
 * Path construction owns storage; stream construction borrows it. Metadata edits require
 * {@link #save()} unless an operation explicitly writes immediately. Closing does not save.
 */
public class VorbisFile extends OggCommentFile {
  /**
   * Opens a Ogg Vorbis file and owns the file handle. Uses default read options.
   *
   * @param path the file to open
   * @throws java.io.UncheckedIOException    if storage access fails
   * @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
   */
  public VorbisFile(Path path) {
    this(path, ReadOptions.DEFAULT);
  }

  /**
   * Opens a Ogg Vorbis file and owns the file handle.
   *
   * @param path    the file to open
   * @param options audio property selection and metadata allocation limit
   * @throws java.io.UncheckedIOException    if storage access fails
   * @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
   */
  public VorbisFile(Path path, ReadOptions options) {
    super(openPath(path, options), true, options, Format.OGG_VORBIS);
  }

  /**
   * Opens a Ogg Vorbis file over a borrowed stream that remains open when this file closes. Uses default read options.
   *
   * @param stream the open, seekable audio stream
   * @throws java.io.UncheckedIOException    if storage access fails
   * @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
   * @throws IllegalStateException           if the supplied stream is closed
   */
  public VorbisFile(IOStream stream) {
    this(stream, ReadOptions.DEFAULT);
  }

  /**
   * Opens a Ogg Vorbis file over a borrowed stream that remains open when this file closes.
   *
   * @param stream  the open, seekable audio stream
   * @param options audio property selection and metadata allocation limit
   * @throws java.io.UncheckedIOException    if storage access fails
   * @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
   * @throws IllegalStateException           if the supplied stream is closed
   */
  public VorbisFile(IOStream stream, ReadOptions options) {
    super(stream, false, options, Format.OGG_VORBIS);
  }

  /**
   * Returns the format-specific parsed audio properties.
   *
   * @return the detailed properties, or {@code null} if properties were not read or are unavailable
   */
  public Properties detailedProperties() {
    return (Properties) codecProperties();
  }

  /**
   * Checks for a recognizable Ogg Vorbis header without validating the complete file.
   * The input cursor is restored after inspection.
   *
   * @param stream the stream to inspect
   * @return whether a format header is recognized
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public static boolean isSupported(IOStream stream) {
    return supported(stream, "\u0001vorbis");
  }
}
