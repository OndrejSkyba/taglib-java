// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.mpeg;

import cz.skyba.taglib.*;
import cz.skyba.taglib.ape.Footer;
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
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * An editable MPEG audio file with live metadata and format-specific audio properties.
 * Path construction owns storage; stream construction borrows it. Metadata edits require
 * {@link #save()} unless an operation explicitly writes immediately. Closing does not save.
 */
public class MpegFile extends FileBase {
  private static final int ID3v1 = 1, ID3v2 = 2, APE = 4;

  /**
   * Tag families supported by this format's save and removal operations.
   */
  public enum TagType {
    ID3V1(ID3v1), ID3V2(ID3v2), APE(MpegFile.APE);

    private final int mask;

    TagType(int mask) {
      this.mask = mask;
    }
  }

  private static int tagMask(Set<TagType> types) {
    int mask = 0;
    for (var type : Objects.requireNonNull(types)) {
      mask |= type.mask;
    }
    return mask;
  }

  private final IOStream source;
  private final boolean owned;
  private final int metadataLimit;
  private final FrameFactory factory;
  private final TagUnion tags = new TagUnion();
  private long modernLocation = -1, modernSize, legacyLocation = -1, apeLocation = -1, apeSize;
  private Properties details;

  /**
   * Opens a MPEG audio file and owns the file handle. Uses default read options.
   *
   * @param path the file to open
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if parsing rejects the format or its metadata
   */
  public MpegFile(Path path) {
    this(path, ReadOptions.DEFAULT);
  }

  /**
   * Opens a MPEG audio file and owns the file handle.
   *
   * @param path    the file to open
   * @param options audio property selection and metadata allocation limit
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if parsing rejects the format or its metadata
   */
  public MpegFile(Path path, ReadOptions options) {
    this(path, options, null);
  }

  /**
   * Opens a MPEG audio file and owns the file handle.
   * The frame factory controls ID3v2 parsing; null selects the shared default factory.
   *
   * @param path    the file to open
   * @param options audio property selection and metadata allocation limit
   * @param factory the frame decoder factory, or {@code null} for the default
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if parsing rejects the format or its metadata
   */
  public MpegFile(Path path, ReadOptions options, FrameFactory factory) {
    Objects.requireNonNull(options);
    this(new FileStream(path), true, options, factory);
  }

  /**
   * Opens a MPEG audio file over a borrowed stream that remains open when this file closes. Uses default read options.
   *
   * @param stream the open, seekable audio stream
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if parsing rejects the format or its metadata
   * @throws IllegalStateException        if the supplied stream is closed
   */
  public MpegFile(IOStream stream) {
    this(stream, ReadOptions.DEFAULT);
  }

  /**
   * Opens a MPEG audio file over a borrowed stream that remains open when this file closes.
   *
   * @param stream  the open, seekable audio stream
   * @param options audio property selection and metadata allocation limit
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if parsing rejects the format or its metadata
   * @throws IllegalStateException        if the supplied stream is closed
   */
  public MpegFile(IOStream stream, ReadOptions options) {
    this(stream, false, options, null);
  }

  /**
   * Opens a MPEG audio file over a borrowed stream that remains open when this file closes.
   * The frame factory controls ID3v2 parsing; null selects the shared default factory.
   *
   * @param stream  the open, seekable audio stream
   * @param options audio property selection and metadata allocation limit
   * @param factory the frame decoder factory, or {@code null} for the default
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if parsing rejects the format or its metadata
   * @throws IllegalStateException        if the supplied stream is closed
   */
  public MpegFile(IOStream stream, ReadOptions options, FrameFactory factory) {
    this(stream, false, options, factory);
  }

  private MpegFile(IOStream stream, boolean owned, ReadOptions options, FrameFactory factory) {
    super(stream);
    source = stream;
    this.owned = owned;
    metadataLimit = Objects.requireNonNull(options).maxMetadataBytes();
    this.factory = factory == null ? FrameFactory.instance() : factory;
    try {
      read(options);
    } catch (RuntimeException | Error failure) {
      if (owned) {
        FailureSupport.closeOnFailure(stream, failure);
      }
      throw failure;
    }
  }

  @Override
  public TagUnion tag() {
    return tags;
  }

  @Override
  public PropertyMap properties() {
    return tags.properties();
  }

  @Override
  public void removeUnsupportedProperties(List<String> values) {
    tags.removeUnsupportedProperties(values);
  }

  /**
   * Replaces properties on an existing ID3v1 tag and creates or updates ID3v2 in memory.
   * Reports acceptance and rejection from the ID3v2 mapping; this does not save.
   */
  @Override
  public PropertyUpdateResult replaceProperties(PropertyMap values) {
    var legacy = id3v1Tag();
    if (legacy != null) {
      legacy.replaceProperties(values);
    }
    return getOrCreateId3v2Tag().replaceProperties(values);
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
  public final Properties detailedProperties() {
    return details;
  }

  @Override
  public ByteVector readBlock(long length) {
    if (length > metadataLimit) {
      throw new MetadataLimitException("MPEG metadata exceeds the configured read limit");
    }
    return super.readBlock(length);
  }

  /**
   * Returns the live ID3v2 tag without creating one.
   *
   * @return the live tag object, or {@code null} if no object exists
   */
  public final Tag id3v2Tag() {
    return (Tag) tags.tag(0);
  }

  /**
   * Returns the live ID3v2 tag, creating an empty tag in memory if absent. Creation does not write storage.
   *
   * @return the live tag object
   */
  public final Tag getOrCreateId3v2Tag() {
    if (tags.tag(0) == null) {
      tags.set(0, new Tag(null, 0, factory));
    }
    return (Tag) tags.tag(0);
  }

  /**
   * Returns the live APE tag without creating one.
   *
   * @return the live tag object, or {@code null} if no object exists
   */
  public final cz.skyba.taglib.ape.Tag apeTag() {
    return (cz.skyba.taglib.ape.Tag) tags.tag(1);
  }

  /**
   * Returns the live APE tag, creating an empty tag in memory if absent. Creation does not write storage.
   *
   * @return the live tag object
   */
  public final cz.skyba.taglib.ape.Tag getOrCreateApeTag() {
    if (tags.tag(1) == null) {
      tags.set(1, new cz.skyba.taglib.ape.Tag());
    }
    return (cz.skyba.taglib.ape.Tag) tags.tag(1);
  }

  /**
   * Returns the live ID3v1 tag without creating one.
   *
   * @return the live tag object, or {@code null} if no object exists
   */
  public final cz.skyba.taglib.id3.v1.Tag id3v1Tag() {
    return (cz.skyba.taglib.id3.v1.Tag) tags.tag(2);
  }

  /**
   * Returns the live ID3v1 tag, creating an empty tag in memory if absent. Creation does not write storage.
   *
   * @return the live tag object
   */
  public final cz.skyba.taglib.id3.v1.Tag getOrCreateId3v1Tag() {
    if (tags.tag(2) == null) {
      tags.set(2, new cz.skyba.taglib.id3.v1.Tag());
    }
    return (cz.skyba.taglib.id3.v1.Tag) tags.tag(2);
  }

  public final boolean hasId3v2Tag() {
    return modernLocation >= 0;
  }

  public final boolean hasId3v1Tag() {
    return legacyLocation >= 0;
  }

  public final boolean hasApeTag() {
    return apeLocation >= 0;
  }

  private void read(ReadOptions options) {
    modernLocation = findID3v2(options.readStyle());
    if (modernLocation >= 0) {
      tags.set(0, new Tag(this, modernLocation, factory));
      modernSize = id3v2Tag().header().completeTagSize();
    }
    legacyLocation = TagUtils.findID3v1(this);
    if (legacyLocation >= 0) {
      tags.set(2, new cz.skyba.taglib.id3.v1.Tag(this, legacyLocation));
    }
    apeLocation = TagUtils.findAPE(this, legacyLocation);
    if (apeLocation >= 0) {
      tags.set(1, new cz.skyba.taglib.ape.Tag(this, apeLocation));
      apeSize = apeTag().footer().completeTagSize();
      apeLocation = Math.subtractExact(Math.addExact(apeLocation, Footer.size()), apeSize);
    }
    if (options.readAudioProperties()) {
      details = new Properties(this, options.readStyle());
    }
    getOrCreateId3v2Tag();
    getOrCreateId3v1Tag();
  }

  private static boolean sync(int first, int second) {
    return first == 255 && second != 255 && (second & 224) == 224;
  }

  private long findID3v2(ReadStyle style) {
    seek(0);
    if (readBlock(3).equals(cz.skyba.taglib.id3.v2.Header.fileIdentifier())) {
      return 0;
    }
    if (style == ReadStyle.FAST || new Header(this, 0, true).isValid()) {
      return -1;
    }
    long position = 0;
    int previous = 0, before = 0;
    while (true) {
      seek(position);
      var data = readBlock(bufferSize());
      if (data.isEmpty()) {
        return -1;
      }
      for (int i = 0; i < data.size(); i++) {
        int current = data.get(i) & 255;
        if (sync(previous, current) && new Header(this, Math.addExact(position, i - 1L), true).isValid()) {
          return -1;
        }
        if (before == 'I' && previous == 'D' && current == '3') {
          return Math.addExact(position, i - 2L);
        }
        before = previous;
        previous = current;
      }
      position = Math.addExact(position, bufferSize());
    }
  }

  /**
   * Searches for a valid MPEG frame header at or after a byte offset. The shared cursor moves
   * as the search and header validation read storage.
   *
   * @param position the absolute byte offset to start from
   * @return the frame header's starting byte offset, or {@code -1} if none is found
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public final long nextFrameOffset(long position) {
    int previous = 0;
    while (true) {
      seek(position);
      var data = readBlock(bufferSize());
      if (data.isEmpty()) {
        return -1;
      }
      for (int i = 0; i < data.size(); i++) {
        int current = data.get(i) & 255;
        long offset = Math.addExact(position, i - 1L);
        if (sync(previous, current) && new Header(this, offset, true).isValid()) {
          return offset;
        }
        previous = current;
      }
      position = Math.addExact(position, bufferSize());
    }
  }

  /**
   * Searches backward for a valid MPEG frame and returns its end offset. The shared cursor
   * moves as the search and header validation read storage.
   *
   * @param position the byte boundary before which to search
   * @return the byte offset immediately after the frame, or {@code -1} if none is found
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public final long previousFrameOffset(long position) {
    int next = 0;
    while (position > 0) {
      long count = Math.min(position, bufferSize());
      position -= count;
      seek(position);
      var data = readBlock(count);
      for (int i = data.size() - 1; i >= 0; i--) {
        int current = data.get(i) & 255;
        long offset = Math.addExact(position, i);
        if (sync(current, next)) {
          var header = new Header(this, offset, true);
          if (header.isValid()) {
            return Math.addExact(offset, header.frameLength());
          }
        }
        next = current;
      }
    }
    return -1;
  }

  /**
   * Finds the first valid MPEG frame after any leading ID3v2 tag. The shared cursor moves.
   *
   * @return the frame header's starting byte offset, or {@code -1} if none is found
   */
  public final long firstFrameOffset() {
    return nextFrameOffset(hasId3v2Tag() ? Math.addExact(modernLocation, id3v2Tag().header().completeTagSize()) : 0);
  }

  /**
   * Finds the end offset of the last valid MPEG frame before trailing APE or ID3v1 tags.
   * The shared cursor moves.
   *
   * @return the byte offset immediately after the frame, or {@code -1} if none is found
   */
  public final long lastFrameOffset() {
    return previousFrameOffset(hasApeTag() ? apeLocation - 1 : hasId3v1Tag() ? legacyLocation - 1 : length());
  }

  /**
   * Checks for a recognizable MPEG audio header without validating the complete file.
   * The input cursor is restored after inspection.
   *
   * @param stream the stream to inspect
   * @return whether a format header is recognized
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public static boolean isSupported(IOStream stream) {
    var read = TagUtils.readHeaderWithOffset(stream, bufferSize(), true);
    var bytes = read.data();
    if (bytes.isEmpty()) {
      return false;
    }
    long original = stream.tell();
    var adapter = new FileBase(stream) {
      public TagBase tag() {
        return null;
      }

      public AudioProperties audioProperties() {
        return null;
      }

      public void save() {
        throw new UnsupportedOperationException("MPEG header probe does not support saving");
      }
    };
    try {
      for (int i = 0; i < bytes.size() - 1; i++) {
        if (sync(bytes.get(i) & 255, bytes.get(i + 1) & 255)
          && new Header(adapter, Math.addExact(read.offset(), i), true).isValid()) {
          return true;
        }
      }
      return false;
    } finally {
      stream.seek(original);
    }
  }

  /**
   * Removes selected metadata from storage immediately; no save call is required.
   * A storage failure can leave partial changes. All supported tag families are selected.
   *
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if storage is read-only or cached extents cannot be removed
   * @throws IllegalStateException        if the file is closed
   */
  public final void strip() {
    strip(Set.of(TagType.values()), true);
  }

  /**
   * Removes selected metadata from storage immediately; no save call is required.
   * A storage failure can leave partial changes. This file discards its references to physically removed tag objects.
   *
   * @param types tag families to remove; an empty set selects none
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if storage is read-only or cached extents cannot be removed
   * @throws IllegalStateException        if the file is closed
   */
  public final void strip(Set<TagType> types) {
    strip(types, true);
  }

  /**
   * Removes selected metadata from storage immediately; no save call is required.
   * A storage failure can leave partial changes.
   * When discarding is disabled, live tag objects remain and a later save can write them back.
   * When enabled, this file drops its references to physically removed tags; references held
   * by the caller remain valid. Tags without an on-disk representation are unaffected.
   *
   * @param types       tag families to remove; an empty set selects none
   * @param discardTags whether this file discards its live objects for physically removed tags
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if storage is read-only or cached extents cannot be removed
   * @throws IllegalStateException        if the file is closed
   */
  public final void strip(Set<TagType> types, boolean discardTags) {
    stripMask(tagMask(types), discardTags);
  }

  private void stripMask(int mask, boolean discardTags) {
    requireWritable();
    if (!validStripPlan(mask)) {
      throw new TagLibException("Cannot strip MPEG tags with the cached layout: " + name());
    }
    if ((mask & ID3v2) != 0 && modernLocation >= 0) {
      removeBlock(modernLocation, modernSize);
      if (apeLocation >= 0) {
        apeLocation -= modernSize;
      }
      if (legacyLocation >= 0) {
        legacyLocation -= modernSize;
      }
      modernLocation = -1;
      modernSize = 0;
      if (discardTags) {
        tags.set(0, null);
      }
    }
    if ((mask & ID3v1) != 0 && legacyLocation >= 0) {
      truncate(legacyLocation);
      legacyLocation = -1;
      if (discardTags) {
        tags.set(2, null);
      }
    }
    if ((mask & APE) != 0 && apeLocation >= 0) {
      removeBlock(apeLocation, apeSize);
      if (legacyLocation >= 0) {
        legacyLocation -= apeSize;
      }
      apeLocation = -1;
      apeSize = 0;
      if (discardTags) {
        tags.set(1, null);
      }
    }
  }

  /**
   * Writes staged metadata to storage in place. An I/O failure can leave partial changes.
   * Saves all tag families, removes unselected tags, uses ID3v2.4 and copies missing common
   * fields between selected ID3 tags.
   *
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if storage is read-only or the cached layout cannot be saved
   * @throws IllegalStateException        if the file is closed
   */
  @Override
  public void save() {
    save(MpegSaveOptions.DEFAULT);
  }

  /**
   * Writes staged metadata to storage in place using the supplied options.
   * Selected empty tags are removed from storage.
   * Unselected tag families are preserved with {@link StripTags#STRIP_NONE} and removed
   * with {@link StripTags#STRIP_OTHERS}. An I/O failure can leave partial changes.
   *
   * @param options tag selection, preservation policy and ID3v2 output settings
   * @throws NullPointerException         if {@code options} is null
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if storage is read-only or the cached layout cannot be saved
   * @throws IllegalStateException        if the file is closed
   */
  public final void save(MpegSaveOptions options) {
    requireWritable();
    int mask = tagMask(options.tags());
    var strip = options.unselectedTags();
    var version = options.id3v2Version();
    var duplicate = options.duplicateTags();
    if (duplicate == DuplicateTags.DUPLICATE) {
      if ((mask & ID3v2) != 0 && id3v1Tag() != null && (strip != StripTags.STRIP_OTHERS || (mask & ID3v1) != 0)) {
        TagBase.copyFields(id3v1Tag(), getOrCreateId3v2Tag(), false);
      }
      if ((mask & ID3v1) != 0 && tags.tag(0) != null && (strip != StripTags.STRIP_OTHERS || (mask & ID3v2) != 0)) {
        TagBase.copyFields(id3v2Tag(), getOrCreateId3v1Tag(), false);
      }
    }
    if (strip == StripTags.STRIP_OTHERS) {
      stripMask(~mask, false);
    }
    if ((mask & ID3v2) != 0) {
      var modernTag = id3v2Tag();
      if (modernTag != null && !modernTag.isEmpty()) {
        if (modernLocation < 0) {
          modernLocation = 0;
        }
        var data = id3v2Tag().render(version);
        if (!validSavePlan(mask, data.size())) {
          throw new TagLibException("MPEG ID3v2 tag exceeds the writable stream range: " + name());
        }
        insert(data, modernLocation, modernSize);
        if (apeLocation >= 0) {
          apeLocation += data.size() - modernSize;
        }
        if (legacyLocation >= 0) {
          legacyLocation += data.size() - modernSize;
        }
        modernSize = data.size();
      } else {
        stripMask(ID3v2, false);
      }
    }
    if (!validSavePlan(mask & ~ID3v2, 0)) {
      throw new TagLibException("MPEG tail tags exceed the writable stream range: " + name());
    }
    if ((mask & ID3v1) != 0) {
      var legacyTag = id3v1Tag();
      if (legacyTag != null && !legacyTag.isEmpty()) {
        if (legacyLocation >= 0) {
          seek(legacyLocation);
        } else {
          seek(0, IOStream.Position.END);
          legacyLocation = tell();
        }
        writeBlock(id3v1Tag().render());
      } else {
        stripMask(ID3v1, false);
      }
    }
    if (!validSavePlan(mask & APE, 0)) {
      throw new TagLibException("MPEG APE tag exceeds the writable stream range: " + name());
    }
    if ((mask & APE) != 0) {
      var apeTag = apeTag();
      if (apeTag != null && !apeTag.isEmpty()) {
        if (apeLocation < 0) {
          apeLocation = legacyLocation >= 0 ? legacyLocation : length();
        }
        var data = apeTag().render();
        insert(data, apeLocation, apeSize);
        if (legacyLocation >= 0) {
          legacyLocation += data.size() - apeSize;
        }
        apeSize = data.size();
      } else {
        stripMask(APE, false);
      }
    }
  }

  /**
   * Layout-only simulations keep payload rendering and user I/O hooks in native phase order.
   */
  private final class Extents {
    long size = length(), modern = modernLocation, modernLength = modernSize, legacy = legacyLocation, ape = apeLocation, apeLength =
      apeSize;

    void insert(long at, long old, long rendered) {
      long end = Math.addExact(at, old), writeEnd = Math.addExact(at, rendered), delta = Math.subtractExact(rendered, old);
      if (at < 0 || old < 0 || rendered > Integer.MAX_VALUE || source instanceof ByteVectorStream && delta > 0 && end > size) {
        throw new IllegalArgumentException("Unrepresentable MPEG edit span");
      }
      size = delta >= 0
        ? Math.max(Math.addExact(size, delta), writeEnd)
        : Math.max(size > end ? Math.addExact(size, delta) : writeEnd, writeEnd);
      check();
    }

    void remove(long at, long old) {
      insert(at, old, 0);
    }

    void check() {
      if (size < 0 || source instanceof ByteVectorStream && size > Integer.MAX_VALUE) {
        throw new IllegalArgumentException("Unrepresentable MPEG stream size");
      }
    }

    void strip(int mask) {
      if ((mask & ID3v2) != 0 && modern >= 0) {
        remove(modern, modernLength);
        if (ape >= 0) {
          ape = Math.subtractExact(ape, modernLength);
        }
        if (legacy >= 0) {
          legacy = Math.subtractExact(legacy, modernLength);
        }
        modern = -1;
        modernLength = 0;
      }
      if ((mask & ID3v1) != 0 && legacy >= 0) {
        size = legacy;
        legacy = -1;
        check();
      }
      if ((mask & APE) != 0 && ape >= 0) {
        remove(ape, apeLength);
        if (legacy >= 0) {
          legacy = Math.subtractExact(legacy, apeLength);
        }
        ape = -1;
        apeLength = 0;
      }
    }
  }

  private boolean validStripPlan(int mask) {
    try {
      var plan = new Extents();
      plan.strip(mask);
      return true;
    } catch (ArithmeticException | IllegalArgumentException invalid) {
      return false;
    }
  }

  private boolean validSavePlan(int mask, long renderedModern) {
    try {
      var plan = new Extents();
      if ((mask & ID3v2) != 0) {
        if (renderedModern > 0) {
          plan.insert(plan.modern, plan.modernLength, renderedModern);
          long delta = renderedModern - plan.modernLength;
          if (plan.ape >= 0) {
            plan.ape = Math.addExact(plan.ape, delta);
          }
          if (plan.legacy >= 0) {
            plan.legacy = Math.addExact(plan.legacy, delta);
          }
        } else {
          plan.strip(ID3v2);
        }
      }
      if ((mask & ID3v1) != 0) {
        var legacyTag = id3v1Tag();
        if (legacyTag != null && !legacyTag.isEmpty()) {
          if (plan.legacy < 0) {
            plan.legacy = plan.size;
          }
          plan.size = Math.max(plan.size, Math.addExact(plan.legacy, 128));
          plan.check();
        } else {
          plan.strip(ID3v1);
        }
      }
      if ((mask & APE) != 0) {
        var apeTag = apeTag();
        if (apeTag != null && !apeTag.isEmpty()) {
          long rendered = 2L * Footer.size();
          for (var item : apeTag().itemListMap().values()) {
            rendered = Math.addExact(rendered, item.render().size());
          }
          if (plan.ape < 0) {
            plan.ape = plan.legacy >= 0 ? plan.legacy : plan.size;
          }
          plan.insert(plan.ape, plan.apeLength, rendered);
        } else {
          plan.strip(APE);
        }
      }
      return true;
    } catch (ArithmeticException | IllegalArgumentException invalid) {
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
