// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.trueaudio;

import cz.skyba.taglib.*;
import cz.skyba.taglib.id3.v2.FrameFactory;
import cz.skyba.taglib.id3.v2.Tag;
import cz.skyba.taglib.internal.FailureSupport;
import cz.skyba.taglib.internal.MetadataLimitException;
import cz.skyba.taglib.internal.TagUtils;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.FileStream;
import cz.skyba.taglib.toolkit.IOStream;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/// An editable TrueAudio file with live metadata and format-specific audio properties.
/// Path construction owns storage; stream construction borrows it. Metadata edits require
/// [#save()] unless an operation explicitly writes immediately. Closing does not save.
public class TrueAudioFile extends FileBase {
  /// Tag families supported by this format's removal operations.
  public enum TagType {ID3V1, ID3V2}

  public static final int HeaderSize = 18;
  private final IOStream source;
  private final boolean owned;
  private final int metadataLimit;
  private final FrameFactory factory;
  private final TagUnion tag = new TagUnion();
  private long id3v2Location = -1, id3v2OriginalSize, id3v1Location = -1;
  private Properties details;

  /// Opens a TrueAudio file and owns the file handle. Uses default read options.
  ///
  /// @param path the file to open
  /// @throws java.io.UncheckedIOException if storage access fails
  /// @throws TagLibException              if parsing rejects the format or its metadata
  public TrueAudioFile(Path path) {
    this(path, ReadOptions.DEFAULT);
  }

  /// Opens a TrueAudio file and owns the file handle.
  ///
  /// @param path    the file to open
  /// @param options audio property selection and metadata allocation limit
  /// @throws java.io.UncheckedIOException if storage access fails
  /// @throws TagLibException              if parsing rejects the format or its metadata
  public TrueAudioFile(Path path, ReadOptions options) {
    this(path, options, null);
  }

  /// Opens a TrueAudio file and owns the file handle.
  /// The frame factory controls ID3v2 parsing; null selects the shared default factory.
  ///
  /// @param path    the file to open
  /// @param options audio property selection and metadata allocation limit
  /// @param factory the frame decoder factory, or `null` for the default
  /// @throws java.io.UncheckedIOException if storage access fails
  /// @throws TagLibException              if parsing rejects the format or its metadata
  public TrueAudioFile(Path path, ReadOptions options, FrameFactory factory) {
    Objects.requireNonNull(options);
    this(new FileStream(path), true, options, factory);
  }

  /// Opens a TrueAudio file over a borrowed stream that remains open when this file closes. Uses default read options.
  ///
  /// @param source the open, seekable audio stream
  /// @throws java.io.UncheckedIOException if storage access fails
  /// @throws TagLibException              if parsing rejects the format or its metadata
  /// @throws IllegalStateException        if the supplied stream is closed
  public TrueAudioFile(IOStream source) {
    this(source, ReadOptions.DEFAULT);
  }

  /// Opens a TrueAudio file over a borrowed stream that remains open when this file closes.
  ///
  /// @param source  the open, seekable audio stream
  /// @param options audio property selection and metadata allocation limit
  /// @throws java.io.UncheckedIOException if storage access fails
  /// @throws TagLibException              if parsing rejects the format or its metadata
  /// @throws IllegalStateException        if the supplied stream is closed
  public TrueAudioFile(IOStream source, ReadOptions options) {
    this(source, false, options, null);
  }

  /// Opens a TrueAudio file over a borrowed stream that remains open when this file closes.
  /// The frame factory controls ID3v2 parsing; null selects the shared default factory.
  ///
  /// @param source  the open, seekable audio stream
  /// @param options audio property selection and metadata allocation limit
  /// @param factory the frame decoder factory, or `null` for the default
  /// @throws java.io.UncheckedIOException if storage access fails
  /// @throws TagLibException              if parsing rejects the format or its metadata
  /// @throws IllegalStateException        if the supplied stream is closed
  public TrueAudioFile(IOStream source, ReadOptions options, FrameFactory factory) {
    this(source, false, options, factory);
  }

  private TrueAudioFile(IOStream source, boolean owned, ReadOptions options, FrameFactory factory) {
    super(source);
    this.source = source;
    this.owned = owned;
    metadataLimit = Objects.requireNonNull(options).maxMetadataBytes();
    this.factory = factory == null ? FrameFactory.instance() : factory;
    try {
      read(options.readAudioProperties());
    } catch (RuntimeException | Error failure) {
      if (owned) {
        FailureSupport.closeOnFailure(source, failure);
      }
      throw failure;
    }
  }

  @Override
  public TagUnion tag() {
    return tag;
  }

  @Override
  public AudioProperties audioProperties() {
    return details == null ? null : details.common();
  }

  /// @return the detailed properties, or `null` if properties were not read or are unavailable
  public Properties detailedProperties() {
    return details;
  }

  @Override
  public ByteVector readBlock(long length) {
    if (length > metadataLimit) {
      throw new MetadataLimitException("TrueAudio metadata exceeds the configured read limit");
    }
    return super.readBlock(length);
  }

  /// Checks for a recognizable TrueAudio header without validating the complete file.
  /// The input cursor is restored after inspection.
  ///
  /// @param source the stream to inspect
  /// @return whether a format header is recognized
  /// @throws java.io.UncheckedIOException if storage access fails
  public static boolean isSupported(IOStream source) {
    return TagUtils.readHeader(source, 3, true).equals(new ByteVector("TTA"));
  }

  /// Returns the live ID3v2 tag without creating one.
  ///
  /// @return the live tag object, or `null` if no object exists
  public final Tag id3v2Tag() {
    return (Tag) tag.tag(0);
  }

  /// Returns the live ID3v2 tag, creating an empty tag in memory if absent. Creation does not write storage.
  ///
  /// @return the live tag object
  public final Tag getOrCreateId3v2Tag() {
    if (tag.tag(0) == null) {
      tag.set(0, new Tag(null, 0, factory));
    }
    return (Tag) tag.tag(0);
  }

  /// Returns the live ID3v1 tag without creating one.
  ///
  /// @return the live tag object, or `null` if no object exists
  public final cz.skyba.taglib.id3.v1.Tag id3v1Tag() {
    return (cz.skyba.taglib.id3.v1.Tag) tag.tag(1);
  }

  /// Returns the live ID3v1 tag, creating an empty tag in memory if absent. Creation does not write storage.
  ///
  /// @return the live tag object
  public final cz.skyba.taglib.id3.v1.Tag getOrCreateId3v1Tag() {
    if (tag.tag(1) == null) {
      tag.set(1, new cz.skyba.taglib.id3.v1.Tag());
    }
    return (cz.skyba.taglib.id3.v1.Tag) tag.tag(1);
  }

  public final boolean hasId3v1Tag() {
    return id3v1Location >= 0;
  }

  public final boolean hasId3v2Tag() {
    return id3v2Location >= 0;
  }

  /// Stages removal of all supported tag types; storage changes only on [#save()].
  public final void removeTags() {
    removeTags(Set.of(TagType.values()));
  }

  /// Stages removal of selected tag types; storage changes only on [#save()].
  ///
  /// @param types tag types to remove; an empty set selects none
  public final void removeTags(Set<TagType> types) {
    types = Set.copyOf(types);
    if (types.contains(TagType.ID3V1)) {
      tag.set(1, null);
    }
    if (types.contains(TagType.ID3V2)) {
      tag.set(0, null);
    }
    if (id3v1Tag() == null) {
      getOrCreateId3v2Tag();
    }
  }

  /// Replaces properties on an existing ID3v1 tag and creates or updates ID3v2 in memory.
  /// Reports acceptance and rejection from the ID3v2 mapping; this does not save.
  @Override
  public PropertyUpdateResult replaceProperties(PropertyMap values) {
    var legacy = id3v1Tag();
    if (legacy != null) {
      legacy.replaceProperties(values);
    }
    return getOrCreateId3v2Tag().replaceProperties(values);
  }

  private void read(boolean readProperties) {
    id3v2Location = TagUtils.findID3v2(this);
    if (id3v2Location >= 0) {
      tag.set(0, new Tag(this, id3v2Location, factory));
      id3v2OriginalSize = id3v2Tag().header().completeTagSize();
    }
    id3v1Location = TagUtils.findID3v1(this);
    if (id3v1Location >= 0) {
      tag.set(1, new cz.skyba.taglib.id3.v1.Tag(this, id3v1Location));
    }
    if (id3v1Location < 0) {
      getOrCreateId3v2Tag();
    }
    if (readProperties) {
      long streamLength = id3v1Location >= 0 ? id3v1Location : length();
      if (id3v2Location >= 0) {
        long begin = Math.addExact(id3v2Location, id3v2OriginalSize);
        seek(begin);
        streamLength = Math.subtractExact(streamLength, begin);
      } else {
        seek(0);
      }
      details = new Properties(readBlock(HeaderSize), streamLength);
    }
  }

  @Override
  public void save() {
    requireWritable();
    boolean modern = id3v2Tag() != null && !id3v2Tag().isEmpty();
    // Native marks the newly allocated location before invoking custom render hooks.
    if (modern && id3v2Location < 0) {
      id3v2Location = 0;
    }
    var data = modern ? id3v2Tag().render() : new ByteVector();
    boolean legacy = id3v1Tag() != null && !id3v1Tag().isEmpty();
    if (!validSaveExtents(modern, data.size(), legacy)) {
      throw new TagLibException("TrueAudio tag extents exceed the writable stream range: " + name());
    }
    if (modern) {
      insert(data, id3v2Location, id3v2OriginalSize);
      if (id3v1Location >= 0) {
        id3v1Location += data.size() - id3v2OriginalSize;
      }
      id3v2OriginalSize = data.size();
    } else if (id3v2Location >= 0) {
      removeBlock(id3v2Location, id3v2OriginalSize);
      if (id3v1Location >= 0) {
        id3v1Location -= id3v2OriginalSize;
      }
      id3v2Location = -1;
      id3v2OriginalSize = 0;
    }
    var legacyTag = id3v1Tag();
    if (legacyTag != null && !legacyTag.isEmpty()) {
      if (id3v1Location >= 0) {
        seek(id3v1Location);
      } else {
        seek(0, IOStream.Position.END);
        id3v1Location = tell();
      }
      writeBlock(id3v1Tag().render());
    } else if (id3v1Location >= 0) {
      truncate(id3v1Location);
      id3v1Location = -1;
    }
  }

  private boolean validSaveExtents(boolean modern, int rendered, boolean legacy) {
    try {
      long size = length(), v1 = id3v1Location;
      if (modern) {
        long end = Math.addExact(id3v2Location, id3v2OriginalSize);
        long writeEnd = Math.addExact(id3v2Location, rendered), difference = rendered - id3v2OriginalSize;
        if (source instanceof ByteVectorStream && difference > 0 && end > size) {
          return false;
        }
        size = difference >= 0 ? Math.max(Math.addExact(size, difference), writeEnd)
          : Math.max(size > end ? Math.addExact(size, difference) : writeEnd, writeEnd);
        if (v1 >= 0) {
          v1 = Math.addExact(v1, difference);
        }
      } else if (id3v2Location >= 0) {
        long end = Math.addExact(id3v2Location, id3v2OriginalSize);
        size = size > end ? Math.subtractExact(size, id3v2OriginalSize) : id3v2Location;
        if (v1 >= 0) {
          v1 = Math.subtractExact(v1, id3v2OriginalSize);
        }
      }
      if (legacy) {
        size = Math.max(size, Math.addExact(v1 >= 0 ? v1 : size, 128));
      } else if (v1 >= 0) {
        size = v1;
      }
      return size >= 0 && (!(source instanceof ByteVectorStream) || size <= Integer.MAX_VALUE);
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
