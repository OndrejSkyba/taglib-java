// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.TagString;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A mutable ID3v2 frame containing OWNE price, purchase date and seller.
 * Editing a frame does not write a file; use the owning file's save operation to persist changes.
 */
public class OwnershipFrame extends Frame {
  private TagString.Type encoding = TagString.Type.LATIN1;
  private String pricePaid = "", datePurchased = "", seller = "";

  /**
   * Creates a frame containing OWNE price, purchase date and seller. The initial text encoding is ISO-8859-1.
   */
  public OwnershipFrame() {
    this(TagString.Type.LATIN1);
  }

  /**
   * Creates a frame containing OWNE price, purchase date and seller.
   *
   * @param encoding the requested text encoding
   */
  public OwnershipFrame(TagString.Type encoding) {
    super(new ByteVector("OWNE"));
    this.encoding = Objects.requireNonNull(encoding);
  }

  /**
   * Decodes OWNE price, purchase date and seller from serialized frame bytes, including their frame header.
   *
   * @param data serialized bytes including the frame header
   */
  public OwnershipFrame(ByteVector data) {
    super(data);
    header().setData(data);
    parseOwnership(fieldData(data));
  }

  OwnershipFrame(ByteVector data, Header header) {
    super(header);
    parseOwnership(fieldData(data));
  }

  public final String pricePaid() {
    return pricePaid;
  }

  public final void setPricePaid(String value) {
    pricePaid = Objects.requireNonNull(value);
  }

  public final String datePurchased() {
    return datePurchased;
  }

  public final void setDatePurchased(String value) {
    datePurchased = Objects.requireNonNull(value);
  }

  public final String seller() {
    return seller;
  }

  public final void setSeller(String value) {
    seller = Objects.requireNonNull(value);
  }

  public final TagString.Type textEncoding() {
    return encoding;
  }

  public final void setTextEncoding(TagString.Type value) {
    encoding = Objects.requireNonNull(value);
  }

  @Override
  public String toString() {
    return "pricePaid=" + Id3Text.detachedLeft(pricePaid) + " datePurchased="
      + Id3Text.detachedLeft(datePurchased) + " seller=" + seller;
  }

  @Override
  public List<String> toStringList() {
    return new ArrayList<>(List.of(pricePaid, datePurchased, seller));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void parseFields(ByteVector data) {
    parseOwnership(data);
  }

  private void parseOwnership(ByteVector data) {
    if (data.isEmpty()) {
      return;
    }
    encoding = Id3Text.encoding(data.at(0));
    StringField field = stringField(data, TagString.Type.LATIN1, 1);
    pricePaid = field.value();
    int position = field.nextPosition();
    if (data.size() - position < 8) {
      return;
    }
    datePurchased = Id3Text.fixedLatin1(data.mid(position, 8));
    seller = Id3Text.decode(data.mid(position + 8), encoding);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected ByteVector renderFields() {
    TagString.Type rendered = checkTextEncoding(List.of(seller), encoding);
    return new ByteVector((byte) rendered.ordinal()).append(Id3Text.encode(pricePaid, TagString.Type.LATIN1))
      .append((byte) 0).append(Id3Text.encode(datePurchased, TagString.Type.LATIN1))
      .append(Id3Text.encode(seller, rendered));
  }
}
