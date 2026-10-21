// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.toolkit.ByteVector;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

/// A mutable ID3v2 frame for popularity information (POPM).
/// Editing a frame does not write a file; use the owning file's save operation to persist changes.
/// The rating is stored as a Java integer and encoded using its low eight bits.
public class PopularimeterFrame extends Frame {
  private String email = "";
  private int rating;
  private long counter;

  public PopularimeterFrame() {
    super(new ByteVector("POPM"));
  }

  /// Decodes POPM email, rating and play counter from serialized frame bytes, including their frame header.
  ///
  /// @param data serialized bytes including the frame header
  public PopularimeterFrame(ByteVector data) {
    super(data);
    header().setData(data);
    parsePopularimeter(fieldData(data));
  }

  PopularimeterFrame(ByteVector data, Header header) {
    super(header);
    parsePopularimeter(fieldData(data));
  }

  public final String email() {
    return email;
  }

  public final void setEmail(String value) {
    email = Objects.requireNonNull(value);
  }

  public final int rating() {
    return rating;
  }

  public final void setRating(int value) {
    rating = value;
  }

  public final long counter() {
    return counter;
  }

  public final void setCounter(long value) {
    counter = value & 0xffff_ffffL;
  }

  @Override
  public String toString() {
    return Id3Text.detachedLeft(email) + " rating=" + rating + " counter=" + (int) counter;
  }

  @Override
  public List<String> toStringList() {
    // Native String::number takes int, even when given this unsigned counter.
    return new ArrayList<>(List.of(email, Integer.toString(rating), Integer.toString((int) counter)));
  }

  @Override
  protected void parseFields(ByteVector data) {
    parsePopularimeter(data);
  }

  private void parsePopularimeter(ByteVector data) {
    StringField field = stringField(data, ISO_8859_1, 0);
    email = field.value();
    int position = field.nextPosition();
    rating = 0;
    counter = 0;
    if (position < data.size()) {
      rating = data.at(position++) & 255;
      if (position < data.size()) {
        counter = data.toUnsignedInt(position, true);
      }
    }
  }

  @Override
  protected ByteVector renderFields() {
    return ByteVector.fromText(email, ISO_8859_1).append((byte) 0)
      .append((byte) rating).append(ByteVector.fromUnsignedInt(counter));
  }
}
