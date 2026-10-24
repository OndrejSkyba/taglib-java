// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.wavpack;

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

/// An editable WavPack file with live metadata and format-specific audio properties.
/// Path construction owns storage; stream construction borrows it. Metadata edits require
/// [#save()] unless an operation explicitly writes immediately. Closing does not save.
public class WavPackFile extends ApeTaggedFile {
  /// Tag families supported by this format's removal operations.
  public enum TagType {ID3V1, APE}

  private Properties details;

  /// Opens a WavPack file and owns the file handle. Uses default read options.
  ///
  /// @param path the file to open
  /// @throws java.io.UncheckedIOException    if storage access fails
  /// @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
  public WavPackFile(Path path) {
    this(path, ReadOptions.DEFAULT);
  }

  /// Opens a WavPack file and owns the file handle.
  ///
  /// @param path    the file to open
  /// @param options audio property selection and metadata allocation limit
  /// @throws java.io.UncheckedIOException    if storage access fails
  /// @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
  public WavPackFile(Path path, ReadOptions options) {
    Objects.requireNonNull(options);
    this(new FileStream(path), true, options);
  }

  /// Opens a WavPack file over a borrowed stream that remains open when this file closes. Uses default read options.
  ///
  /// @param source the open, seekable audio stream
  /// @throws java.io.UncheckedIOException    if storage access fails
  /// @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
  /// @throws IllegalStateException           if the supplied stream is closed
  public WavPackFile(IOStream source) {
    this(source, ReadOptions.DEFAULT);
  }

  /// Opens a WavPack file over a borrowed stream that remains open when this file closes.
  ///
  /// @param source  the open, seekable audio stream
  /// @param options audio property selection and metadata allocation limit
  /// @throws java.io.UncheckedIOException    if storage access fails
  /// @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
  /// @throws IllegalStateException           if the supplied stream is closed
  public WavPackFile(IOStream source, ReadOptions options) {
    this(source, false, options);
  }

  private WavPackFile(IOStream source, boolean owned, ReadOptions options) {
    super(source, owned, options);
    try {
      long streamLength = readApeTags();
      if (options.readAudioProperties()) {
        details = new Properties(this, streamLength);
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

  /// Checks for a recognizable WavPack header without validating the complete file.
  /// The input cursor is restored after inspection.
  ///
  /// @param source the stream to inspect
  /// @return whether a format header is recognized
  /// @throws java.io.UncheckedIOException if storage access fails
  public static boolean isSupported(IOStream source) {
    return TagUtils.readHeader(source, 4, false).equals(new ByteVector("wvpk"));
  }
}
