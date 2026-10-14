// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.shorten;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.internal.FailureSupport;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.FileStream;
import cz.skyba.taglib.toolkit.IOStream;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A Shorten file reader with audio properties and an empty metadata view.
 * Path construction owns storage; stream construction borrows it. Metadata saving is unsupported.
 * Audio properties are parsed even when optional property reading is disabled.
 */
public class ShortenFile extends FileBase {
  private final IOStream source;
  private final boolean owned;
  private final Tag tag;
  private final Properties details;

  /**
   * Opens a Shorten file and owns the file handle. Uses default read options.
   * Audio property parsing is required for this format.
   *
   * @param path the file to open
   * @throws java.io.UncheckedIOException    if storage access fails
   * @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
   */
  public ShortenFile(Path path) {
    this(path, ReadOptions.DEFAULT);
  }

  /**
   * Opens a Shorten file and owns the file handle.
   * Audio property parsing is required for this format.
   *
   * @param path    the file to open
   * @param options audio property selection and metadata allocation limit
   * @throws java.io.UncheckedIOException    if storage access fails
   * @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
   */
  public ShortenFile(Path path, ReadOptions options) {
    Objects.requireNonNull(options);
    this(new FileStream(path), true, options);
  }

  /**
   * Opens a Shorten file over a borrowed stream that remains open when this file closes. Uses default read options.
   * Audio property parsing is required for this format.
   *
   * @param source the open, seekable audio stream
   * @throws java.io.UncheckedIOException    if storage access fails
   * @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
   * @throws IllegalStateException           if the supplied stream is closed
   */
  public ShortenFile(IOStream source) {
    this(source, ReadOptions.DEFAULT);
  }

  /**
   * Opens a Shorten file over a borrowed stream that remains open when this file closes.
   * Audio property parsing is required for this format.
   *
   * @param source  the open, seekable audio stream
   * @param options audio property selection and metadata allocation limit
   * @throws java.io.UncheckedIOException    if storage access fails
   * @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
   * @throws IllegalStateException           if the supplied stream is closed
   */
  public ShortenFile(IOStream source, ReadOptions options) {
    this(source, false, options);
  }

  private ShortenFile(IOStream source, boolean owned, ReadOptions options) {
    super(source);
    this.source = source;
    this.owned = owned;
    Objects.requireNonNull(options);
    try {
      details = new Properties(source, options);
      tag = new Tag();
    } catch (RuntimeException | Error failure) {
      if (owned) {
        FailureSupport.closeOnFailure(source, failure);
      }
      throw failure;
    }
  }

  @Override
  public Tag tag() {
    return tag;
  }

  @Override
  public AudioProperties audioProperties() {
    return details == null ? null : details.common();
  }

  /**
   * Returns the format-specific parsed audio properties.
   *
   * @return the detailed properties, or {@code null} if properties were not read or are unavailable
   */
  public Properties detailedProperties() {
    return details;
  }

  @Override
  public void save() {
    if (!isOpen()) {
      throw new IllegalStateException("Audio file is closed: " + name());
    }
    throw new UnsupportedOperationException("Shorten metadata writing is not supported");
  }

  @Override
  public void close() {
    if (owned) {
      source.close();
    }
  }

  /**
   * Checks for a recognizable Shorten header without validating the complete file.
   * The input cursor is restored after inspection.
   *
   * @param source the stream to inspect
   * @return whether a format header is recognized
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public static boolean isSupported(IOStream source) {
    if (source == null || !source.isOpen()) {
      return false;
    }
    long original = source.tell();
    try {
      source.seek(0);
      return source.readBlock(4).startsWith(new ByteVector("ajkg"));
    } finally {
      source.seek(original);
    }
  }
}
