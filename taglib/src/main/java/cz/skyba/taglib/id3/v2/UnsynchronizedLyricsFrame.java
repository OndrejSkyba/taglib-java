// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.toolkit.ByteVector;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

/**
 * A mutable ID3v2 frame containing USLT language, description and lyrics.
 * Editing a frame does not write a file; use the owning file's save operation to persist changes.
 */
public class UnsynchronizedLyricsFrame extends Frame {
  private final LanguageText fields = new LanguageText();

  /**
   * Creates a frame containing USLT language, description and lyrics. The initial text encoding is ISO-8859-1.
   */
  public UnsynchronizedLyricsFrame() {
    this(ISO_8859_1);
  }

  /**
   * Creates a frame containing USLT language, description and lyrics.
   *
   * @param encoding the requested text encoding
   */
  public UnsynchronizedLyricsFrame(Charset encoding) {
    super(new ByteVector("USLT"));
    fields.encoding = Id3Text.requireEncoding(encoding);
  }

  /**
   * Decodes USLT language, description and lyrics from serialized frame bytes, including their frame header.
   *
   * @param data serialized bytes including the frame header
   */
  public UnsynchronizedLyricsFrame(ByteVector data) {
    super(data);
    parseLyrics(fieldData(data));
  }

  UnsynchronizedLyricsFrame(ByteVector data, Header header) {
    super(header);
    parseLyrics(fieldData(data));
  }

  /**
   * Returns an independent copy of the stored language-code bytes.
   *
   * @return copied language bytes
   */
  public final ByteVector language() {
    return new ByteVector(fields.language);
  }

  public final String description() {
    return fields.description;
  }

  public final String text() {
    return fields.text;
  }

  /**
   * Copies up to the first three language-code bytes into the live frame.
   *
   * @param value language bytes to copy
   */
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
    return PropertyMap.builder().put(key.isEmpty() || key.equals("LYRICS") ? "LYRICS" : "LYRICS:" + key, fields.text).build();
  }

  /**
   * Returns the first live matching frame from a tag.
   *
   * @param tag   the tag to search
   * @param value the owner or description to match exactly
   * @return the matching frame, or {@code null} if absent
   */
  public static UnsynchronizedLyricsFrame findByDescription(Tag tag, String value) {
    return findByDescription(tag.frameList(new ByteVector("USLT")), value);
  }

  /**
   * Returns the first live matching frame from the supplied frame sequence.
   *
   * @param frames      the frames to search in iteration order
   * @param description the description to match exactly
   * @return the matching frame, or {@code null} if absent
   */
  public static UnsynchronizedLyricsFrame findByDescription(Iterable<? extends Frame> frames, String description) {
    for (Frame frame : frames) {
      if (frame instanceof UnsynchronizedLyricsFrame lyrics && lyrics.description().equals(description)) {
        return lyrics;
      }
    }
    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void parseFields(ByteVector data) {
    parseLyrics(data);
  }

  private void parseLyrics(ByteVector data) {
    fields.parse(data, "An unsynchronized lyrics frame must contain at least 5 bytes.");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected ByteVector renderFields() {
    Charset type = checkTextEncoding(List.of(fields.description, fields.text), fields.encoding);
    return fields.render(type);
  }
}
