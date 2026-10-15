// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.riff.aiff;

import cz.skyba.taglib.*;
import cz.skyba.taglib.id3.v2.FrameFactory;
import cz.skyba.taglib.id3.v2.Tag;
import cz.skyba.taglib.internal.FailureSupport;
import cz.skyba.taglib.internal.TagUtils;
import cz.skyba.taglib.internal.codec.RiffCodec;
import cz.skyba.taglib.riff.RiffFile;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.Debug;
import cz.skyba.taglib.toolkit.FileStream;
import cz.skyba.taglib.toolkit.IOStream;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * An editable AIFF/AIFC file with live metadata and format-specific audio properties.
 * Path construction owns storage; stream construction borrows it. Metadata edits require
 * {@link #save()} unless an operation explicitly writes immediately. Closing does not save.
 */
public class AiffFile extends RiffFile {
  private static final ByteVector ID3 = new ByteVector("ID3 "), LOWER_ID3 = new ByteVector("id3 ");
  private Tag tag;
  private Properties details;
  private boolean hasID3;

  /**
   * Opens a AIFF/AIFC file and owns the file handle. Uses default read options.
   *
   * @param path the file to open
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if parsing rejects the format or its metadata
   */
  public AiffFile(Path path) {
    this(path, ReadOptions.DEFAULT);
  }

  /**
   * Opens a AIFF/AIFC file and owns the file handle.
   *
   * @param path    the file to open
   * @param options audio property selection and metadata allocation limit
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if parsing rejects the format or its metadata
   */
  public AiffFile(Path path, ReadOptions options) {
    this(path, options, null);
  }

  /**
   * Opens a AIFF/AIFC file and owns the file handle.
   * The frame factory controls ID3v2 parsing; null selects the shared default factory.
   *
   * @param path    the file to open
   * @param options audio property selection and metadata allocation limit
   * @param factory the frame decoder factory, or {@code null} for the default
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if parsing rejects the format or its metadata
   */
  public AiffFile(Path path, ReadOptions options, FrameFactory factory) {
    Objects.requireNonNull(options);
    this(new FileStream(path), true, options, factory);
  }

  /**
   * Opens a AIFF/AIFC file over a borrowed stream that remains open when this file closes. Uses default read options.
   *
   * @param stream the open, seekable audio stream
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if parsing rejects the format or its metadata
   * @throws IllegalStateException        if the supplied stream is closed
   */
  public AiffFile(IOStream stream) {
    this(stream, ReadOptions.DEFAULT);
  }

  /**
   * Opens a AIFF/AIFC file over a borrowed stream that remains open when this file closes.
   *
   * @param stream  the open, seekable audio stream
   * @param options audio property selection and metadata allocation limit
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if parsing rejects the format or its metadata
   * @throws IllegalStateException        if the supplied stream is closed
   */
  public AiffFile(IOStream stream, ReadOptions options) {
    this(stream, false, options, null);
  }

  /**
   * Opens a AIFF/AIFC file over a borrowed stream that remains open when this file closes.
   * The frame factory controls ID3v2 parsing; null selects the shared default factory.
   *
   * @param stream  the open, seekable audio stream
   * @param options audio property selection and metadata allocation limit
   * @param factory the frame decoder factory, or {@code null} for the default
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if parsing rejects the format or its metadata
   * @throws IllegalStateException        if the supplied stream is closed
   */
  public AiffFile(IOStream stream, ReadOptions options, FrameFactory factory) {
    this(stream, false, options, factory);
  }

  private AiffFile(IOStream stream, boolean owned, ReadOptions options, FrameFactory factory) {
    super(stream, owned, Endianness.BigEndian, options);
    try {
      read(options, factory);
    } catch (RuntimeException | Error failure) {
      if (owned) {
        FailureSupport.closeOnFailure(stream, failure);
      }
      throw failure;
    }
  }

  private void read(ReadOptions options, FrameFactory factory) {
    var selectedFactory = factory == null ? FrameFactory.instance() : factory;
    for (int i = 0; i < chunkCount(); i++) {
      if (chunkName(i).equals(ID3) || chunkName(i).equals(LOWER_ID3)) {
        if (tag == null) {
          tag = new Tag(this, chunkOffset(i), selectedFactory);
          hasID3 = true;
        } else {
          Debug.debug("RIFF::AIFF::File::read() - Duplicate ID3v2 tag found.");
        }
      }
    }
    if (tag == null) {
      tag = new Tag(null, 0, selectedFactory);
    }
    // The File constructor ignores the requested read style, as upstream does.
    if (options.readAudioProperties()) {
      details = new Properties(this, ReadStyle.AVERAGE);
    }
  }

  @Override
  public Tag tag() {
    return tag;
  }

  @Override
  public PropertyMap properties() {
    return tag.properties();
  }

  @Override
  public PropertyUpdateResult replaceProperties(PropertyMap values) {
    return tag.replaceProperties(values);
  }

  @Override
  public void removeUnsupportedProperties(List<String> values) {
    tag.removeUnsupportedProperties(values);
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

  public boolean hasId3v2Tag() {
    return hasID3;
  }

  /**
   * Checks for a recognizable AIFF/AIFC header without validating the complete file.
   * The input cursor is restored after inspection.
   *
   * @param stream the stream to inspect
   * @return whether a format header is recognized
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public static boolean isSupported(IOStream stream) {
    var id = TagUtils.readHeader(stream, 12, false);
    return id.startsWith(new ByteVector("FORM")) && (id.containsAt(new ByteVector("AIFF"), 8) || id.containsAt(new ByteVector("AIFC"),
      8));
  }

  RiffCodec.AiffDetails readProperties() {
    ByteVector data = new ByteVector();
    long streamLength = 0;
    for (int i = 0; i < chunkCount(); i++) {
      var name = chunkName(i);
      if (name.equals(new ByteVector("COMM"))) {
        if (data.isEmpty()) {
          data = chunkData(i);
        } else {
          Debug.debug("RIFF::AIFF::Properties::read() - Duplicate 'COMM' chunk found.");
        }
      } else if (name.equals(new ByteVector("SSND"))) {
        if (streamLength == 0) {
          streamLength = (chunkDataSize(i) + chunkPadding(i)) & 0xffff_ffffL;
        } else {
          Debug.debug("RIFF::AIFF::Properties::read() - Duplicate 'SSND' chunk found.");
        }
      }
    }
    if (data.size() < 18) {
      Debug.debug("RIFF::AIFF::Properties::read() - 'COMM' chunk not found or too short.");
    } else if (streamLength == 0) {
      Debug.debug("RIFF::AIFF::Properties::read() - 'SSND' chunk not found.");
    }
    return RiffCodec.aiffFromChunks(data.toByteArray(), streamLength);
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
    if (!cachedLayoutWritable()) {
      throw new TagLibException("AIFF cached chunk layout is no longer writable: " + name());
    }
    if (hasID3) {
      removeChunk(ID3);
      removeChunk(LOWER_ID3);
      hasID3 = false;
    }
    if (tag() != null && !tag().isEmpty()) {
      setChunkData(ID3, tag.render(version));
      hasID3 = true;
    }
  }
}
