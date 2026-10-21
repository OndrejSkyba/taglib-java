// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal;

import cz.skyba.taglib.*;
import cz.skyba.taglib.ape.Footer;
import cz.skyba.taglib.ape.Tag;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.IOStream;

import java.util.Objects;

/// Internal implementation shared only where the pinned codec sources agree.
public abstract class ApeTaggedFile extends FileBase {
  private final IOStream source;
  private final boolean owned;
  private final int metadataLimit;
  private final TagUnion tag = new TagUnion();
  private long apeLocation = -1, apeSize, id3v1Location = -1;
  private long prefixLocation = -1, prefixSize;

  protected ApeTaggedFile(IOStream source, boolean owned, ReadOptions options) {
    super(source);
    this.source = source;
    this.owned = owned;
    metadataLimit = Objects.requireNonNull(options).maxMetadataBytes();
  }

  /// Called by the codec after its own prefix processing, never from this constructor.
  protected final long readApeTags() {
    id3v1Location = TagUtils.findID3v1(this);
    if (id3v1Location >= 0) {
      tag.set(1, new cz.skyba.taglib.id3.v1.Tag(this, id3v1Location));
    }
    apeLocation = TagUtils.findAPE(this, id3v1Location);
    if (apeLocation >= 0) {
      tag.set(0, new Tag(this, apeLocation));
      apeSize = apeTag().footer().completeTagSize();
      apeLocation += Footer.size() - apeSize;
    }
    if (id3v1Location < 0) {
      getOrCreateApeTag();
    }
    return apeLocation >= 0 ? apeLocation
      : id3v1Location >= 0 ? id3v1Location : length();
  }

  @Override
  public TagUnion tag() {
    return tag;
  }

  @Override
  public ByteVector readBlock(long length) {
    if (metadataLimit < Integer.MAX_VALUE && length > metadataLimit) {
      throw new MetadataLimitException("APE-tagged metadata exceeds the configured read limit");
    }
    return super.readBlock(length);
  }

  /// Returns the live APE tag object without creating a new object.
  ///
  /// @return the live tag object, or `null` if no object exists
  public Tag apeTag() {
    return (Tag) tag.tag(0);
  }

  /// Returns the live APE tag object, creating an empty object in memory if absent.
  ///
  /// @return the live tag
  public Tag getOrCreateApeTag() {
    if (tag.tag(0) == null) {
      tag.set(0, new Tag());
    }
    return (Tag) tag.tag(0);
  }

  /// Returns the live ID3v1 tag object without creating a new object.
  ///
  /// @return the live tag object, or `null` if no object exists
  public cz.skyba.taglib.id3.v1.Tag id3v1Tag() {
    return (cz.skyba.taglib.id3.v1.Tag) tag.tag(1);
  }

  /// Returns the live ID3v1 tag object, creating an empty object in memory if absent.
  ///
  /// @return the live tag
  public cz.skyba.taglib.id3.v1.Tag getOrCreateId3v1Tag() {
    if (tag.tag(1) == null) {
      tag.set(1, new cz.skyba.taglib.id3.v1.Tag());
    }
    return (cz.skyba.taglib.id3.v1.Tag) tag.tag(1);
  }

  public boolean hasApeTag() {
    return apeLocation >= 0;
  }

  public boolean hasId3v1Tag() {
    return id3v1Location >= 0;
  }

  protected final void stripTags(boolean legacy, boolean ape) {
    if (legacy) {
      tag.set(1, null);
    }
    if (ape) {
      tag.set(0, null);
    }
    if (id3v1Tag() == null) {
      getOrCreateApeTag();
    }
  }

  /// MPC alone removes its retained leading ID3 header span before updating tail tags.
  protected final void removePrefixOnSave(long location, long size) {
    prefixLocation = location;
    prefixSize = size;
  }

  @Override
  public PropertyUpdateResult replaceProperties(PropertyMap values) {
    if (id3v1Tag() != null) {
      id3v1Tag().replaceProperties(values);
    }
    return getOrCreateApeTag().replaceProperties(values);
  }

  @Override
  public void save() {
    requireWritable();
    // Check every extent before the first write. Serialize copies during sizing so
    // native item/footer mutations still occur at their original point below.
    if (!validSaveExtents()) {
      throw new TagLibException("APE tag extents exceed the writable stream range: " + name());
    }
    if (prefixLocation >= 0) {
      removeBlock(prefixLocation, prefixSize);
      if (apeLocation >= 0) {
        apeLocation -= prefixSize;
      }
      if (id3v1Location >= 0) {
        id3v1Location -= prefixSize;
      }
      prefixLocation = -1;
      prefixSize = 0;
    }
    if (id3v1Tag() != null && !id3v1Tag().isEmpty()) {
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
    if (apeTag() != null && !apeTag().isEmpty()) {
      if (apeLocation < 0) {
        apeLocation = id3v1Location >= 0 ? id3v1Location : length();
      }
      var data = apeTag().render();
      insert(data, apeLocation, apeSize);
      if (id3v1Location >= 0) {
        id3v1Location += data.size() - apeSize;
      }
      apeSize = data.size();
    } else if (apeLocation >= 0) {
      removeBlock(apeLocation, apeSize);
      if (id3v1Location >= 0) {
        id3v1Location -= apeSize;
      }
      apeLocation = -1;
      apeSize = 0;
    }
  }

  private boolean validSaveExtents() {
    try {
      long size = length(), v1 = id3v1Location, start = apeLocation;
      if (prefixLocation >= 0) {
        long end = Math.addExact(prefixLocation, prefixSize);
        size = size > end ? Math.subtractExact(size, prefixSize) : prefixLocation;
        if (v1 >= 0) {
          v1 = Math.subtractExact(v1, prefixSize);
        }
        if (start >= 0) {
          start = Math.subtractExact(start, prefixSize);
        }
      }
      if (id3v1Tag() != null && !id3v1Tag().isEmpty()) {
        if (v1 < 0) {
          v1 = size;
        }
        size = Math.max(size, Math.addExact(v1, 128));
      } else if (v1 >= 0) {
        size = v1;
        v1 = -1;
      }
      if (apeTag() != null && !apeTag().isEmpty()) {
        long rendered = renderedTagSize(apeTag());
        if (rendered > Integer.MAX_VALUE) {
          return false;
        }
        if (start < 0) {
          start = v1 >= 0 ? v1 : size;
        }
        long end = Math.addExact(start, apeSize);
        long writeEnd = Math.addExact(start, rendered);
        long difference = rendered - apeSize;
        // Native memory insertion requires an existing suffix when growing.
        if (source instanceof ByteVectorStream && difference > 0 && end > size) {
          return false;
        }
        size = difference >= 0 ? Math.max(Math.addExact(size, difference), writeEnd)
          : Math.max(size > end ? size + difference : writeEnd, writeEnd);
      } else if (start >= 0) {
        long end = Math.addExact(start, apeSize);
        size = size > end ? size - apeSize : start;
      }
      return size >= 0 && (!(source instanceof ByteVectorStream) || size <= Integer.MAX_VALUE);
    } catch (ArithmeticException | IllegalArgumentException unrepresentable) {
      return false;
    }
  }

  private static long renderedTagSize(Tag tag) {
    long size = 2L * Footer.size();
    // itemListMap values are detached copies: render must not mutate live items yet.
    for (var item : tag.itemListMap().values()) {
      size = Math.addExact(size, item.render().size());
    }
    return size;
  }

  @Override
  public void close() {
    if (owned) {
      source.close();
    }
  }
}
