// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.dsf;

import cz.skyba.taglib.*;
import cz.skyba.taglib.id3.v2.FrameFactory;
import cz.skyba.taglib.id3.v2.Tag;
import cz.skyba.taglib.internal.FailureSupport;
import cz.skyba.taglib.internal.MetadataLimitException;
import cz.skyba.taglib.internal.TagUtils;
import cz.skyba.taglib.toolkit.*;

import java.nio.file.Path;
import java.util.Objects;

import static java.lang.System.Logger.Level.DEBUG;

/**
 * An editable DSF file with live metadata and format-specific audio properties.
 * Path construction owns storage; stream construction borrows it. Metadata edits require
 * {@link #save()} unless an operation explicitly writes immediately. Closing does not save.
 * Audio properties are parsed even when optional property reading is disabled.
 */
public class DsfFile extends FileBase {
  private static final System.Logger LOG = System.getLogger(DsfFile.class.getName());

  private final IOStream source;
  private final boolean owned;
  private final int metadataLimit;
  private long fileSize, metadataOffset;
  private Properties details;
  private Tag tag;

  /**
   * Opens a DSF file and owns the file handle. Uses default read options.
   * Audio property parsing is required for this format.
   *
   * @param path the file to open
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if parsing rejects the format or its metadata
   */
  public DsfFile(Path path) {
    this(path, ReadOptions.DEFAULT);
  }

  /**
   * Opens a DSF file and owns the file handle.
   * Audio property parsing is required for this format.
   *
   * @param path    the file to open
   * @param options audio property selection and metadata allocation limit
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if parsing rejects the format or its metadata
   */
  public DsfFile(Path path, ReadOptions options) {
    this(path, options, null);
  }

  /**
   * Opens a DSF file and owns the file handle.
   * The frame factory controls ID3v2 parsing; null selects the shared default factory.
   * Audio property parsing is required for this format.
   *
   * @param path    the file to open
   * @param options audio property selection and metadata allocation limit
   * @param factory the frame decoder factory, or {@code null} for the default
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if parsing rejects the format or its metadata
   */
  public DsfFile(Path path, ReadOptions options, FrameFactory factory) {
    Objects.requireNonNull(options);
    this(new FileStream(path), true, options, factory);
  }

  /**
   * Opens a DSF file over a borrowed stream that remains open when this file closes. Uses default read options.
   * Audio property parsing is required for this format.
   *
   * @param source the open, seekable audio stream
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if parsing rejects the format or its metadata
   * @throws IllegalStateException        if the supplied stream is closed
   */
  public DsfFile(IOStream source) {
    this(source, ReadOptions.DEFAULT);
  }

  /**
   * Opens a DSF file over a borrowed stream that remains open when this file closes.
   * Audio property parsing is required for this format.
   *
   * @param source  the open, seekable audio stream
   * @param options audio property selection and metadata allocation limit
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if parsing rejects the format or its metadata
   * @throws IllegalStateException        if the supplied stream is closed
   */
  public DsfFile(IOStream source, ReadOptions options) {
    this(source, false, options, null);
  }

  /**
   * Opens a DSF file over a borrowed stream that remains open when this file closes.
   * The frame factory controls ID3v2 parsing; null selects the shared default factory.
   * Audio property parsing is required for this format.
   *
   * @param source  the open, seekable audio stream
   * @param options audio property selection and metadata allocation limit
   * @param factory the frame decoder factory, or {@code null} for the default
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if parsing rejects the format or its metadata
   * @throws IllegalStateException        if the supplied stream is closed
   */
  public DsfFile(IOStream source, ReadOptions options, FrameFactory factory) {
    this(source, false, options, factory);
  }

  private DsfFile(IOStream source, boolean owned, ReadOptions options, FrameFactory factory) {
    super(source);
    this.source = source;
    this.owned = owned;
    metadataLimit = Objects.requireNonNull(options).maxMetadataBytes();
    try {
      read(options.readStyle(), factory == null ? FrameFactory.instance() : factory);
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
  public ByteVector readBlock(long length) {
    if (length > metadataLimit) {
      throw new MetadataLimitException("DSF metadata exceeds the configured read limit");
    }
    return super.readBlock(length);
  }

  /**
   * Checks for a recognizable DSF header without validating the complete file.
   * The input cursor is restored after inspection.
   *
   * @param source the stream to inspect
   * @return whether a format header is recognized
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public static boolean isSupported(IOStream source) {
    return TagUtils.readHeader(source, 4, false).startsWith(new ByteVector("DSD "));
  }

  private InvalidAudioFileException invalid(String message) {
    LOG.log(DEBUG, message);
    return new InvalidAudioFileException("DSF " + message + ": " + name());
  }

  private void read(ReadStyle style, FrameFactory factory) {
    if (!readBlock(4).equals(new ByteVector("DSD "))) {
      throw invalid("Not a DSF file.");
    }
    if (readBlock(8).toUnsignedLong(false) != 28) {
      throw invalid("File is corrupted, wrong DSD header size");
    }
    fileSize = readBlock(8).toUnsignedLong(false);
    if (Long.compareUnsigned(fileSize, length()) > 0) {
      throw invalid("File is corrupted wrong length");
    }
    metadataOffset = readBlock(8).toUnsignedLong(false);
    if (Long.compareUnsigned(metadataOffset, fileSize) > 0) {
      throw invalid("Invalid metadata offset.");
    }
    if (!readBlock(4).equals(new ByteVector("fmt "))) {
      throw invalid("Missing 'fmt ' chunk.");
    }
    long formatSize = readBlock(8).toUnsignedLong(false);
    if (formatSize != 52) {
      throw invalid("File is corrupted, wrong FMT header size");
    }
    // Pinned DSF ignores readProperties, and reads 52 bytes after the fmt header.
    details = new Properties(readBlock(formatSize), style);
    // A newly created tag deliberately has no File parent, even after subsequent saves.
    tag = new Tag(metadataOffset == 0 ? null : this, metadataOffset, factory);
  }

  @Override
  public void save() {
    save(Tag.Version.V4);
  }

  /**
   * Writes staged metadata to storage in place. An I/O failure can leave partial changes.
   *
   * @param version the ID3v2 output version
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if storage is read-only or the cached layout cannot be saved
   * @throws IllegalStateException        if the file is closed
   */
  public final void save(Tag.Version version) {
    requireWritable();
    Objects.requireNonNull(version);
    boolean empty = tag.isEmpty();
    var data = empty ? new ByteVector() : tag.render(version);
    long offset = metadataOffset != 0 ? metadataOffset : fileSize;
    final long size;
    try {
      size = empty ? offset : Math.addExact(offset, data.size());
    } catch (ArithmeticException unrepresentable) {
      throw new TagLibException("DSF tag extent exceeds the writable stream range: " + name());
    }
    long oldTagSize = fileSize - offset;
    if (!validSaveExtents(empty, offset, size, oldTagSize, data.size())) {
      throw new TagLibException("DSF tag extent is not representable: " + name());
    }
    if (fileSize != size) {
      insert(ByteVector.fromUnsignedLong(size, false), 12, 8);
      fileSize = size;
    }
    long pointer = empty ? 0 : offset;
    if (metadataOffset != pointer) {
      insert(ByteVector.fromUnsignedLong(pointer, false), 20, 8);
      metadataOffset = pointer;
    }
    if (empty) {
      truncate(size);
    } else {
      insert(data, offset, oldTagSize);
    }
  }

  private boolean validSaveExtents(boolean empty, long offset, long size, long oldTagSize, int renderedSize) {
    try {
      long current = length();
      if (size < 0 || offset < 0 || oldTagSize < 0) {
        return false;
      }
      if (fileSize != size) {
        current = Math.max(current, 20);
      }
      if (metadataOffset != (empty ? 0 : offset)) {
        current = Math.max(current, 28);
      }
      if (empty) {
        current = size;
      } else {
        long end = Math.addExact(offset, oldTagSize), difference = renderedSize - oldTagSize;
        if (source instanceof ByteVectorStream && difference > 0 && end > current) {
          return false;
        }
        current = difference >= 0 ? Math.max(Math.addExact(current, difference), size)
          : Math.max(current > end ? Math.addExact(current, difference) : size, size);
      }
      return !(source instanceof ByteVectorStream) || current <= Integer.MAX_VALUE;
    } catch (ArithmeticException unrepresentable) {
      return false;
    }
  }

  @Override
  public void close() {
    if (owned) {
      source.close();
    }
  }
}
