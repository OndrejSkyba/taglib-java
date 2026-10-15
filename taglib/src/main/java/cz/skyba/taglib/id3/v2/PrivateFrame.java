// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.toolkit.ByteVector;

import java.util.Objects;

import static java.lang.System.Logger.Level.DEBUG;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

/**
 * A mutable ID3v2 frame containing PRIV owner and private binary payload.
 * Editing a frame does not write a file; use the owning file's save operation to persist changes.
 */
public class PrivateFrame extends Frame {
  private static final System.Logger LOG = System.getLogger(PrivateFrame.class.getName());

  private String owner = "";
  private ByteVector data = new ByteVector();

  /**
   * Creates a frame containing PRIV owner and private binary payload.
   */
  public PrivateFrame() {
    super(new ByteVector("PRIV"));
  }

  /**
   * Decodes PRIV owner and private binary payload from serialized frame bytes, including their frame header.
   *
   * @param data serialized bytes including the frame header
   */
  public PrivateFrame(ByteVector data) {
    super(data);
    parsePrivate(fieldData(data));
  }

  PrivateFrame(ByteVector data, Header header) {
    super(header);
    parsePrivate(fieldData(data));
  }

  public final String owner() {
    return owner;
  }

  public final ByteVector data() {
    return new ByteVector(data);
  }

  public final void setOwner(String value) {
    owner = Objects.requireNonNull(value);
  }

  public final void setPrivateData(ByteVector value) {
    data = new ByteVector(value);
  }

  @Override
  public String toString() {
    return owner;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void parseFields(ByteVector data) {
    parsePrivate(data);
  }

  private void parsePrivate(ByteVector value) {
    if (value.size() < 2) {
      LOG.log(DEBUG, "A private frame must contain at least 2 bytes.");
      return;
    }
    int end = value.find(textDelimiter(ISO_8859_1));
    // Native -1 becomes UINT_MAX in mid(), retaining the whole owner and payload.
    owner = Id3Text.fixedLatin1(value.mid(0, end < 0 ? Integer.MAX_VALUE : end));
    data = value.mid(end + 1);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected ByteVector renderFields() {
    return ByteVector.fromText(owner, ISO_8859_1).append((byte) 0).append(data);
  }
}
