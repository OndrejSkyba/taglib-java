// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.ape;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.internal.ApeTaggedFile;
import cz.skyba.taglib.internal.FailureSupport;
import cz.skyba.taglib.internal.TagUtils;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.FileStream;
import cz.skyba.taglib.toolkit.IOStream;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/// An editable Monkey's Audio file with live metadata and format-specific audio properties.
/// Path construction owns storage; stream construction borrows it. Metadata edits require
/// [#save()] unless an operation explicitly writes immediately. Closing does not save.
public class ApeFile extends ApeTaggedFile {
  /// Tag families supported by this format's removal operations.
  public enum TagType {ID3V1, APE}

  private Properties details;

  /// Opens a Monkey's Audio file and owns the file handle. Uses default read options.
  ///
  /// @param path the file to open
  /// @throws java.io.UncheckedIOException    if storage access fails
  /// @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
  public ApeFile(Path path) {
    this(path, ReadOptions.DEFAULT);
  }

  /// Opens a Monkey's Audio file and owns the file handle.
  ///
  /// @param path    the file to open
  /// @param options audio property selection and metadata allocation limit
  /// @throws java.io.UncheckedIOException    if storage access fails
  /// @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
  public ApeFile(Path path, ReadOptions options) {
    Objects.requireNonNull(options);
    this(new FileStream(path), true, options);
  }

  /// Opens a Monkey's Audio file over a borrowed stream that remains open when this file closes. Uses default read options.
  ///
  /// @param source the open, seekable audio stream
  /// @throws java.io.UncheckedIOException    if storage access fails
  /// @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
  /// @throws IllegalStateException           if the supplied stream is closed
  public ApeFile(IOStream source) {
    this(source, ReadOptions.DEFAULT);
  }

  /// Opens a Monkey's Audio file over a borrowed stream that remains open when this file closes.
  ///
  /// @param source  the open, seekable audio stream
  /// @param options audio property selection and metadata allocation limit
  /// @throws java.io.UncheckedIOException    if storage access fails
  /// @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
  /// @throws IllegalStateException           if the supplied stream is closed
  public ApeFile(IOStream source, ReadOptions options) {
    this(source, false, options);
  }

  private ApeFile(IOStream source, boolean owned, ReadOptions options) {
    super(source, owned, options);
    try {
      long id3v2Location = TagUtils.findID3v2(this), id3v2Size = 0;
      if (id3v2Location >= 0) {
        seek(id3v2Location);
        id3v2Size = TagUtils.completeID3v2Size(readBlock(10));
      }
      long streamLength = readApeTags();
      if (options.readAudioProperties()) {
        long begin = id3v2Location >= 0 ? id3v2Location + id3v2Size : 0;
        seek(begin);
        details = new Properties(this, streamLength - begin);
      }
    } catch (RuntimeException | Error failure) {
      if (owned) {
        FailureSupport.closeOnFailure(source, failure);
      }
      throw failure;
    }
  }

  @Override
  public AudioProperties audioProperties() {
    return details == null ? null : details.common();
  }

  /// @return the detailed properties, or `null` if properties were not read or are unavailable
  public Properties detailedProperties() {
    return details;
  }

  /// Stages removal of all supported tag types; storage changes only on [#save()].
  public void removeTags() {
    removeTags(Set.of(TagType.values()));
  }

  /// Stages removal of selected tag types; an empty set selects none. Call [#save()] to persist it.
  ///
  /// @param types tag types to remove; an empty set selects none
  public void removeTags(Set<TagType> types) {
    types = Set.copyOf(types);
    stripTags(types.contains(TagType.ID3V1), types.contains(TagType.APE));
  }

  /// Checks for a recognizable Monkey's Audio header without validating the complete file.
  /// The input cursor is restored after inspection.
  ///
  /// @param source the stream to inspect
  /// @return whether a format header is recognized
  /// @throws java.io.UncheckedIOException if storage access fails
  public static boolean isSupported(IOStream source) {
    return TagUtils.readHeader(source, bufferSize(), true).find(new ByteVector("MAC ")) >= 0;
  }
}
