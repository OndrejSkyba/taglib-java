// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.toolkit.ByteVector;

/// A mutable ID3v2 frame containing unmapped frame bytes.
/// Editing a frame does not write a file; use the owning file's save operation to persist changes.
public class UnknownFrame extends Frame {
  private ByteVector data;

  /// Decodes unmapped frame bytes from serialized frame bytes, including their frame header.
  ///
  /// @param data serialized bytes including the frame header
  public UnknownFrame(ByteVector data) {
    super(data);
    // Native construction dispatches UnknownFrame's own field parser, not a derived override.
    this.data = fieldData(data);
  }

  UnknownFrame(ByteVector data, Header header) {
    super(header);
    this.data = fieldData(data);
  }

  @Override
  public String toString() {
    return "";
  }

  public final ByteVector data() {
    return new ByteVector(data);
  }

  @Override
  protected void parseFields(ByteVector fields) {
    data = new ByteVector(fields);
  }

  @Override
  protected ByteVector renderFields() {
    return new ByteVector(data);
  }
}
