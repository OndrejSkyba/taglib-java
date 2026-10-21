// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.toolkit.ByteVector;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

/// A mutable ID3v2 frame for language-specific comments (COMM).
/// Editing a frame does not write a file; use the owning file's save operation to persist changes.
public class CommentsFrame extends Frame {
  private final LanguageText fields = new LanguageText();

  /// Creates a frame containing COMM language, description and comment text. The initial text encoding is ISO-8859-1.
  public CommentsFrame() {
    this(ISO_8859_1);
  }

  public CommentsFrame(Charset encoding) {
    super(new ByteVector("COMM"));
    fields.encoding = Id3Text.requireEncoding(encoding);
  }

  /// Decodes COMM language, description and comment text from serialized frame bytes, including their frame header.
  ///
  /// @param data serialized bytes including the frame header
  public CommentsFrame(ByteVector data) {
    super(data);
    parseComment(fieldData(data));
  }

  CommentsFrame(ByteVector data, Header header) {
    super(header);
    parseComment(fieldData(data));
  }

  /// Returns an independent copy of the stored language-code bytes.
  ///
  /// @return copied language bytes
  public final ByteVector language() {
    return new ByteVector(fields.language);
  }

  public final String description() {
    return fields.description;
  }

  public final String text() {
    return fields.text;
  }

  /// Copies up to the first three language-code bytes into the live frame.
  ///
  /// @param value language bytes to copy
  public final void setLanguage(ByteVector value) {
    fields.language = value.mid(0, 3);
  }

  public final void setDescription(String value) {
    fields.description = Objects.requireNonNull(value);
  }

  @Override
  public void setText(String value) {
    fields.text = Objects.requireNonNull(value);
  }

  @Override
  public String toString() {
    return fields.text;
  }

  public final Charset textEncoding() {
    return fields.encoding;
  }

  public final void setTextEncoding(Charset value) {
    fields.encoding = Id3Text.requireEncoding(value);
  }

  @Override
  public PropertyMap asProperties() {
    String key = Id3Text.upper(fields.description);
    return PropertyMap.builder().put(key.isEmpty() || key.equals("COMMENT") ? "COMMENT" : "COMMENT:" + key, fields.text).build();
  }

  /// Returns the first live matching frame from a tag.
  ///
  /// @param tag   the tag to search
  /// @param value the owner or description to match exactly
  /// @return the matching frame, or `null` if absent
  public static CommentsFrame findByDescription(Tag tag, String value) {
    return findByDescription(tag.frameList(new ByteVector("COMM")), value);
  }

  /// Returns the first live matching frame from the supplied frame sequence.
  ///
  /// @param frames      the frames to search in iteration order
  /// @param description the description to match exactly
  /// @return the matching frame, or `null` if absent
  public static CommentsFrame findByDescription(Iterable<? extends Frame> frames, String description) {
    for (Frame frame : frames) {
      if (frame instanceof CommentsFrame comment && comment.description().equals(description)) {
        return comment;
      }
    }
    return null;
  }

  @Override
  protected void parseFields(ByteVector data) {
    parseComment(data);
  }

  private void parseComment(ByteVector data) {
    fields.parse(data, "A comment frame must contain at least 5 bytes.");
  }

  @Override
  protected ByteVector renderFields() {
    Charset type = checkTextEncoding(List.of(fields.description), fields.encoding);
    type = checkTextEncoding(List.of(fields.text), type);
    return fields.render(type);
  }
}
