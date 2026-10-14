// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.mp4;

import cz.skyba.taglib.*;
import cz.skyba.taglib.internal.FailureSupport;
import cz.skyba.taglib.internal.MetadataLimitException;
import cz.skyba.taglib.internal.OverflowChecks;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.FileStream;
import cz.skyba.taglib.toolkit.IOStream;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * An editable MP4 file with live metadata and format-specific audio properties.
 * Path construction owns storage; stream construction borrows it. Metadata edits require
 * {@link #save()} unless an operation explicitly writes immediately. Closing does not save.
 */
public class Mp4File extends FileBase {
  /**
   * Tag families supported by this format's removal operations.
   */
  public enum TagType {MP4}

  private final IOStream source;
  private final boolean owned;
  private final int limit;
  private final Tag tag;
  private final Atoms atoms;
  private Properties details;
  private NeroChapterList nero;
  private QtChapterList qt;

  /**
   * Opens a MP4 file and owns the file handle. Uses default read options.
   *
   * @param path the file to open
   * @throws java.io.UncheckedIOException    if storage access fails
   * @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
   */
  public Mp4File(Path path) {
    this(path, ReadOptions.DEFAULT, null);
  }

  /**
   * Opens a MP4 file and owns the file handle.
   *
   * @param path    the file to open
   * @param options audio property selection and metadata allocation limit
   * @throws java.io.UncheckedIOException    if storage access fails
   * @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
   */
  public Mp4File(Path path, ReadOptions options) {
    this(path, options, null);
  }

  /**
   * Opens a MP4 file and owns the file handle.
   * The item factory controls MP4 item parsing; null selects the shared default factory.
   *
   * @param path    the file to open
   * @param options audio property selection and metadata allocation limit
   * @param factory the item decoder factory, or {@code null} for the default
   * @throws java.io.UncheckedIOException    if storage access fails
   * @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
   */
  public Mp4File(Path path, ReadOptions options, ItemFactory factory) {
    this(open(path, options), true, options, factory);
  }

  /**
   * Opens a MP4 file over a borrowed stream that remains open when this file closes. Uses default read options.
   *
   * @param stream the open, seekable audio stream
   * @throws java.io.UncheckedIOException    if storage access fails
   * @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
   * @throws IllegalStateException           if the supplied stream is closed
   */
  public Mp4File(IOStream stream) {
    this(stream, ReadOptions.DEFAULT, null);
  }

  /**
   * Opens a MP4 file over a borrowed stream that remains open when this file closes.
   *
   * @param stream  the open, seekable audio stream
   * @param options audio property selection and metadata allocation limit
   * @throws java.io.UncheckedIOException    if storage access fails
   * @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
   * @throws IllegalStateException           if the supplied stream is closed
   */
  public Mp4File(IOStream stream, ReadOptions options) {
    this(stream, options, null);
  }

  /**
   * Opens a MP4 file over a borrowed stream that remains open when this file closes.
   * The item factory controls MP4 item parsing; null selects the shared default factory.
   *
   * @param stream  the open, seekable audio stream
   * @param options audio property selection and metadata allocation limit
   * @param factory the item decoder factory, or {@code null} for the default
   * @throws java.io.UncheckedIOException    if storage access fails
   * @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
   * @throws IllegalStateException           if the supplied stream is closed
   */
  public Mp4File(IOStream stream, ReadOptions options, ItemFactory factory) {
    this(stream, false, options, factory);
  }

  private static IOStream open(Path path, ReadOptions options) {
    Objects.requireNonNull(options);
    return new FileStream(path);
  }

  private Mp4File(IOStream stream, boolean owned, ReadOptions options, ItemFactory factory) {
    super(stream);
    source = stream;
    this.owned = owned;
    limit = Objects.requireNonNull(options).maxMetadataBytes();
    try {
      atoms = new Atoms(ownerStream());
      if (!atoms.checkRootLevelAtoms() || atoms.find("moov") == null) {
        throw new InvalidAudioFileException("MP4 requires valid root atoms and a moov atom: " + name());
      }
      tag = new Tag(this, atoms, factory);
      if (options.readAudioProperties()) {
        details = new Properties(this, atoms);
      }
    } catch (RuntimeException | Error failure) {
      if (owned) {
        FailureSupport.closeOnFailure(stream, failure);
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
  public PropertyMap properties() {
    return Objects.requireNonNull(tag, "MP4 tag unavailable").properties();
  }

  @Override
  public PropertyUpdateResult replaceProperties(PropertyMap values) {
    return Objects.requireNonNull(tag, "MP4 tag unavailable").replaceProperties(values);
  }

  @Override
  public void removeUnsupportedProperties(List<String> values) {
    Objects.requireNonNull(tag, "MP4 tag unavailable").removeUnsupportedProperties(values);
  }

  public final boolean hasMp4Tag() {
    return atoms != null && atoms.find("moov", "udta", "meta", "ilst") != null;
  }

  /**
   * Returns Nero chapter snapshots, loading them on first access.
   * Pending replacements are returned without re-reading storage.
   *
   * @return the chapter list
   * @throws java.io.UncheckedIOException if loading storage fails
   */
  public final List<Chapter> neroChapters() {
    if (nero == null) {
      nero = new NeroChapterList();
      nero.read(this);
    }
    return nero.chapters();
  }

  /**
   * Stages complete Nero chapter replacement.
   * An empty list requests removal. Call {@link #save()} to write the chapter metadata.
   *
   * @param values the replacement chapter list
   */
  public final void setNeroChapters(List<Chapter> values) {
    if (nero == null) {
      nero = new NeroChapterList();
      nero.setModified(true);
    }
    if (nero.isModified() || !nero.chapters().equals(values)) {
      nero.setChapters(values);
      nero.setModified(true);
    }
  }

  /**
   * Returns QuickTime chapter snapshots, loading them on first access.
   * Pending replacements are returned without re-reading storage.
   *
   * @return the chapter list
   * @throws java.io.UncheckedIOException if loading storage fails
   */
  public final List<Chapter> qtChapters() {
    if (qt == null) {
      qt = new QtChapterList();
      qt.read(this);
    }
    return qt.chapters();
  }

  /**
   * Stages complete QuickTime chapter replacement.
   * An empty list requests removal. Call {@link #save()} to write the chapter metadata.
   *
   * @param values the replacement chapter list
   */
  public final void setQtChapters(List<Chapter> values) {
    if (qt == null) {
      qt = new QtChapterList();
      qt.setModified(true);
    }
    if (qt.isModified() || !qt.chapters().equals(values)) {
      qt.setChapters(values);
      qt.setModified(true);
    }
  }

  IOStream metadataStream() {
    return ownerStream();
  }

  @Override
  public void save() {
    requireWritable();
    if (nero != null && nero.isModified()) {
      for (Chapter chapter : nero.chapters().stream().limit(255).toList()) {
        OverflowChecks.checkMultiplication(chapter.startTime(), 10000);
      }
    }
    if (qt != null && qt.isModified()) {
      QtChapterList.validateTimes(qt.chapters());
    }
    tag.save();
    if (nero != null && nero.isModified()) {
      nero.write(this);
    }
    if (qt != null && qt.isModified()) {
      qt.write(this);
    }
  }

  /**
   * Removes all supported tag types from storage immediately, without calling save().
   */
  public final void strip() {
    strip(Set.of(TagType.values()));
  }

  /**
   * Removes selected MP4 metadata from storage immediately, without calling save().
   */
  public final void strip(Set<TagType> types) {
    types = Set.copyOf(types);
    requireWritable();
    if (types.contains(TagType.MP4)) {
      tag.strip();
    }
  }

  final void checkMetadataBytes(long count) {
    if (count > limit) {
      throw new MetadataLimitException("MP4 retained metadata exceeds configured read limit");
    }
  }

  @Override
  public ByteVector readBlock(long count) {
    if (count < 0 || count > limit) {
      throw new MetadataLimitException("MP4 metadata exceeds configured read limit");
    }
    return super.readBlock(count);
  }

  /**
   * Replaces a byte span, rejecting growth across an externally shortened cached memory span before
   * writing.
   */
  @Override
  protected void insert(ByteVector data, long start, long replace) {
    // Native ByteVectorStream growth with an externally shortened cached span is undefined.
    if (source instanceof ByteVectorStream && data.size() > replace && (start < 0 || start > length() || replace > length() - start)) {
      throw new IllegalArgumentException("Unsafe cached MP4 memory insertion span");
    }
    super.insert(data, start, replace);
  }

  @Override
  public void close() {
    if (owned) {
      source.close();
    }
  }

  /**
   * Checks for a recognizable MP4 header without validating the complete file.
   * The input cursor is restored after inspection.
   *
   * @param stream the stream to inspect
   * @return whether a format header is recognized
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public static boolean isSupported(IOStream stream) {
    long pos = stream.tell();
    try {
      stream.seek(0);
      return stream.readBlock(8).containsAt(new ByteVector("ftyp"), 4);
    } finally {
      stream.seek(pos);
    }
  }
}
