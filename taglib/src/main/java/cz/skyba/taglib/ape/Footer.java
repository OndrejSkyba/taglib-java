// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.ape;

import cz.skyba.taglib.toolkit.ByteVector;

import java.nio.charset.StandardCharsets;

/**
 * Mutable APE footer and header fields. Numeric fields retain their low 32 bits.
 * Parsed values determine the item count, stored tag size and presence of header/footer blocks.
 */
public class Footer {
  private long version, itemCount, tagSize;
  private boolean footerPresent = true, headerPresent, isHeader;

  public Footer() {
  }

  /**
   * Decodes an APE footer or header from bytes.
   *
   * @param data encoded footer or header bytes
   */
  public Footer(ByteVector data) {
    setData(data);
  }

  public final long version() {
    return version;
  }

  public final boolean headerPresent() {
    return headerPresent;
  }

  public final boolean footerPresent() {
    return footerPresent;
  }

  public final boolean isHeader() {
    return isHeader;
  }

  public final void setHeaderPresent(boolean present) {
    headerPresent = present;
  }

  public final long itemCount() {
    return itemCount;
  }

  public final void setItemCount(long count) {
    itemCount = count & 0xffff_ffffL;
  }

  public final long tagSize() {
    return tagSize;
  }

  public final void setTagSize(long size) {
    tagSize = size & 0xffff_ffffL;
  }

  /**
   * Returns the stored tag byte size plus the separate header length when a header is present.
   *
   * @return the complete tag length
   */
  public final long completeTagSize() {
    return (tagSize + (headerPresent ? size() : 0)) & 0xffff_ffffL;
  }

  public static int size() {
    return 32;
  }

  public static ByteVector fileIdentifier() {
    return new ByteVector("APETAGEX".getBytes(StandardCharsets.US_ASCII));
  }

  /**
   * Updates footer fields from encoded APE bytes.
   *
   * @param data encoded footer or header bytes
   */
  public final void setData(ByteVector data) {
    parse(data);
  }

  /**
   * Updates footer fields from encoded APE bytes.
   *
   * @param data encoded footer or header bytes
   */
  protected final void parse(ByteVector data) {
    if (data.size() < size()) {
      return;
    }
    version = data.toUnsignedInt(8, false);
    tagSize = data.toUnsignedInt(12, false);
    itemCount = data.toUnsignedInt(16, false);
    long flags = data.toUnsignedInt(20, false);
    headerPresent = (flags & 0x8000_0000L) != 0;
    footerPresent = (flags & 0x4000_0000L) == 0;
    isHeader = (flags & 0x2000_0000L) != 0;
  }

  /**
   * Serializes an APE header or footer using the stored numeric fields and flags.
   *
   * @return encoded block bytes
   */
  public final ByteVector renderFooter() {
    return render(false);
  }

  /**
   * Serializes an APE header or footer using the stored numeric fields and flags.
   *
   * @return encoded block bytes
   */
  public final ByteVector renderHeader() {
    return headerPresent ? render(true) : new ByteVector();
  }

  /**
   * Serializes an APE header or footer using the stored numeric fields and flags.
   *
   * @param header whether to emit a header instead of a footer
   * @return encoded block bytes
   */
  protected final ByteVector render(boolean header) {
    return fileIdentifier()
      .append(ByteVector.fromUnsignedInt(2000, false))
      .append(ByteVector.fromUnsignedInt(tagSize, false))
      .append(ByteVector.fromUnsignedInt(itemCount, false))
      .append(
        ByteVector.fromUnsignedInt(
          (headerPresent ? 0x8000_0000L : 0) | (header ? 0x2000_0000L : 0), false))
      .append(new ByteVector(new byte[8]));
  }
}
