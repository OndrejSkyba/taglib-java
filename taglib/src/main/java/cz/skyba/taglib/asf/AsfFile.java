// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.asf;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.FailureSupport;
import cz.skyba.taglib.internal.TagUtils;
import cz.skyba.taglib.internal.codec.AsfCodec;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.FileStream;
import cz.skyba.taglib.toolkit.IOStream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Objects;

/**
 * An editable ASF file with live metadata and format-specific audio properties.
 * Path construction owns storage; stream construction borrows it. Metadata edits require
 * {@link #save()} unless an operation explicitly writes immediately. Closing does not save.
 */
public class AsfFile extends FileBase {
  private static final ByteVector FILE_IDENTIFIER =
    new ByteVector(HexFormat.of().parseHex("3026b2758e66cf11a6d900aa0062ce6c"));

  private final IOStream source;
  private final boolean owned;
  private final AsfCodec codec = new AsfCodec();
  private Tag tag;
  private Properties properties;

  /**
   * Opens a ASF file and owns the file handle. Uses default read options.
   *
   * @param path the file to open
   * @throws UncheckedIOException            if storage access fails
   * @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
   */
  public AsfFile(Path path) {
    this(path, ReadOptions.DEFAULT);
  }

  /**
   * Opens a ASF file and owns the file handle.
   *
   * @param path    the file to open
   * @param options audio property selection and metadata allocation limit
   * @throws UncheckedIOException            if storage access fails
   * @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
   */
  public AsfFile(Path path, ReadOptions options) {
    Objects.requireNonNull(options);
    this(new FileStream(path), true, options);
  }

  /**
   * Opens a ASF file over a borrowed stream that remains open when this file closes. Uses default read options.
   *
   * @param stream the open, seekable audio stream
   * @throws UncheckedIOException            if storage access fails
   * @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
   * @throws IllegalStateException           if the supplied stream is closed
   */
  public AsfFile(IOStream stream) {
    this(stream, ReadOptions.DEFAULT);
  }

  /**
   * Opens a ASF file over a borrowed stream that remains open when this file closes.
   *
   * @param stream  the open, seekable audio stream
   * @param options audio property selection and metadata allocation limit
   * @throws UncheckedIOException            if storage access fails
   * @throws cz.skyba.taglib.TagLibException if parsing rejects the format or its metadata
   * @throws IllegalStateException           if the supplied stream is closed
   */
  public AsfFile(IOStream stream, ReadOptions options) {
    this(stream, false, options);
  }

  private AsfFile(IOStream stream, boolean owned, ReadOptions options) {
    super(stream);
    Objects.requireNonNull(options);
    source = stream;
    this.owned = owned;
    try {
      read(options);
    } catch (RuntimeException | Error failure) {
      if (owned) {
        FailureSupport.closeOnFailure(stream, failure);
      }
      throw failure;
    }
  }

  private void read(ReadOptions options) {
    try {
      try (var file = new BinaryFile(source)) {
        codec.readNative(file, options);
      }
      tag = codec.liveTag();
      var p = codec.liveProperties();
      if (tag != null) {
        properties = p == null ? new Properties() : new Properties(p);
      }
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read ASF file: " + name(), failure);
    }
  }

  @Override
  public Tag tag() {
    return tag;
  }

  @Override
  public AudioProperties audioProperties() {
    return properties == null ? null : properties.common();
  }

  /**
   * Returns the format-specific parsed audio properties.
   *
   * @return the detailed properties, or {@code null} if properties were not read or are unavailable
   */
  public Properties detailedProperties() {
    return properties;
  }

  @Override
  public void save() {
    requireWritable();
    try (var file = new BinaryFile(source)) {
      codec.saveLive(file, tag);
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot save ASF file: " + name(), failure);
    }
  }

  /**
   * Checks for a recognizable ASF header without validating the complete file.
   * The input cursor is restored after inspection.
   *
   * @param stream the stream to inspect
   * @return whether a format header is recognized
   * @throws UncheckedIOException if storage access fails
   */
  public static boolean isSupported(IOStream stream) {
    return TagUtils.readHeader(stream, 16, false).equals(FILE_IDENTIFIER);
  }

  @Override
  public void close() {
    if (owned) {
      source.close();
    }
  }
}
