// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.Debug;
import cz.skyba.taglib.toolkit.TagString;

import java.util.Objects;

/**
 * Mutable ID3v2 tag header fields for parsing and serialization.
 */
public class Header {
  private long majorVersion = 4, revisionNumber, tagSize;
  private boolean unsynchronisation, extendedHeader, experimentalIndicator, footerPresent;

  public Header() {
  }

  /**
   * Decodes ID3v2 tag header fields from the supplied bytes.
   *
   * @param data encoded ID3v2 tag header bytes
   */
  public Header(ByteVector data) {
    parseData(data);
  }

  public long majorVersion() {
    return majorVersion;
  }

  public void setMajorVersion(long value) {
    majorVersion = value & 0xffff_ffffL;
  }

  public long revisionNumber() {
    return revisionNumber;
  }

  public boolean unsynchronisation() {
    return unsynchronisation;
  }

  public boolean extendedHeader() {
    return extendedHeader;
  }

  public boolean experimentalIndicator() {
    return experimentalIndicator;
  }

  public boolean footerPresent() {
    return footerPresent;
  }

  public long tagSize() {
    return tagSize;
  }

  /**
   * Returns the payload size plus header and, when flagged, footer length.
   *
   * @return the complete byte length of the tag
   */
  public long completeTagSize() {
    return (tagSize + size() + (footerPresent ? Footer.size() : 0)) & 0xffff_ffffL;
  }

  public void setTagSize(long value) {
    tagSize = value & 0xffff_ffffL;
  }

  public static int size() {
    return 10;
  }

  public static ByteVector fileIdentifier() {
    return new ByteVector("ID3");
  }

  /**
   * Decodes ID3v2 tag header fields from the supplied bytes.
   *
   * @param data encoded ID3v2 tag header bytes
   */
  public void setData(ByteVector data) {
    parseData(data);
  }

  /**
   * Serializes a ten-byte header with revision zero. Clears the live extended-header, footer
   * and unsynchronization flags; the experimental flag is retained.
   *
   * @return serialized tag-header bytes
   */
  public ByteVector render() {
    extendedHeader = false;
    footerPresent = false;
    unsynchronisation = false;
    return fileIdentifier().append(new ByteVector(new byte[]{
      (byte) majorVersion, 0, (byte) (experimentalIndicator ? 32 : 0)
    })).append(SynchData.fromUnsignedInt(tagSize));
  }

  /**
   * Decodes available header fields into this object.
   *
   * @param data encoded header bytes
   */
  protected void parse(ByteVector data) {
    parseData(data);
  }

  private void parseData(ByteVector data) {
    Objects.requireNonNull(data);
    if (data.size() < size()) {
      return;
    }
    for (int i = 6; i < 10; i++) {
      if ((data.at(i) & 255) >= 128) {
        tagSize = 0;
        Debug.debug(new TagString("TagLib::ID3v2::Header::parse() - One of the size bytes in the ID3v2 header was greater than the allowed 128."));
        return;
      }
    }
    if ((data.at(3) & 255) == 255 || (data.at(4) & 255) == 255) {
      tagSize = 0;
      Debug.debug(new TagString("TagLib::ID3v2::Header::parse() - The version or revision in the ID3v2 header was 0xFF."));
      return;
    }
    // Pinned Linux char is signed before promotion to native unsigned int.
    majorVersion = Integer.toUnsignedLong(data.at(3));
    revisionNumber = Integer.toUnsignedLong(data.at(4));
    int flags = data.at(5) & 255;
    unsynchronisation = (flags & 128) != 0;
    extendedHeader = (flags & 64) != 0;
    experimentalIndicator = (flags & 32) != 0;
    footerPresent = (flags & 16) != 0;
    tagSize = SynchData.toUnsignedInt(data.mid(6, 4));
  }
}
