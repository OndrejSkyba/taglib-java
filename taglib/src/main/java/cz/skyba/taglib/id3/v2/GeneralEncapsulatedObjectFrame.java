// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.toolkit.ByteVector;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static java.lang.System.Logger.Level.DEBUG;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

/**
 * A mutable ID3v2 frame containing GEOB MIME type, filename, description and binary object.
 * Editing a frame does not write a file; use the owning file's save operation to persist changes.
 */
public class GeneralEncapsulatedObjectFrame extends Frame {
  private static final System.Logger LOG = System.getLogger(GeneralEncapsulatedObjectFrame.class.getName());

  private Charset encoding = ISO_8859_1;
  private String mimeType = "", fileName = "", description = "";
  private ByteVector object = new ByteVector();

  /**
   * Creates a frame containing GEOB MIME type, filename, description and binary object.
   */
  public GeneralEncapsulatedObjectFrame() {
    super(new ByteVector("GEOB"));
  }

  /**
   * Decodes GEOB MIME type, filename, description and binary object from serialized frame bytes, including their frame header.
   *
   * @param data serialized bytes including the frame header
   */
  public GeneralEncapsulatedObjectFrame(ByteVector data) {
    super(data);
    header().setData(data);
    parseObject(fieldData(data));
  }

  GeneralEncapsulatedObjectFrame(ByteVector data, Header header) {
    super(header);
    parseObject(fieldData(data));
  }

  public final Charset textEncoding() {
    return encoding;
  }

  public final void setTextEncoding(Charset value) {
    encoding = Id3Text.requireEncoding(value);
  }

  public final String mimeType() {
    return mimeType;
  }

  public final void setMimeType(String value) {
    mimeType = Objects.requireNonNull(value);
  }

  public final String fileName() {
    return fileName;
  }

  public final void setFileName(String value) {
    fileName = Objects.requireNonNull(value);
  }

  public final String description() {
    return description;
  }

  public final void setDescription(String value) {
    description = Objects.requireNonNull(value);
  }

  /**
   * Returns an independent copy of the binary payload.
   *
   * @return payload bytes
   */
  public final ByteVector object() {
    return new ByteVector(object);
  }

  public final void setObject(ByteVector value) {
    object = new ByteVector(value);
  }

  @Override
  public String toString() {
    String value = "[" + Id3Text.detachedLeft(mimeType) + "]";
    if (!fileName.isEmpty()) {
      value += " " + fileName;
    }
    if (!description.isEmpty()) {
      value += " \"" + Id3Text.detachedLeft(description) + "\"";
    }
    return value;
  }

  @Override
  public List<String> toStringList() {
    return new ArrayList<>(List.of(description, fileName, mimeType));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void parseFields(ByteVector data) {
    parseObject(data);
  }

  private void parseObject(ByteVector data) {
    if (data.size() < 4) {
      LOG.log(DEBUG, "An object frame must contain at least 4 bytes.");
      return;
    }
    encoding = Id3Text.encoding(data.at(0));
    StringField field = stringField(data, ISO_8859_1, 1);
    mimeType = field.value();
    field = stringField(data, encoding, field.nextPosition());
    fileName = field.value();
    field = stringField(data, encoding, field.nextPosition());
    description = field.value();
    object = data.mid(field.nextPosition());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected ByteVector renderFields() {
    Charset rendered = checkTextEncoding(List.of(fileName, description), encoding);
    return new ByteVector(Id3Text.encodingByte(rendered)).append(ByteVector.fromText(mimeType, ISO_8859_1))
      .append(textDelimiter(ISO_8859_1)).append(ByteVector.fromText(fileName, rendered))
      .append(textDelimiter(rendered)).append(ByteVector.fromText(description, rendered))
      .append(textDelimiter(rendered)).append(object);
  }
}
