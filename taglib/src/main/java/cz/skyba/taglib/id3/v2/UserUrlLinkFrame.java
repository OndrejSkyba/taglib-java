// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.internal.TextBytes;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.Debug;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A mutable ID3v2 frame containing WXXX description and URL.
 * Editing a frame does not write a file; use the owning file's save operation to persist changes.
 */
public class UserUrlLinkFrame extends UrlLinkFrame {
  private Charset encoding = ISO_8859_1;
  private String description = "";

  /**
   * Creates a frame containing WXXX description and URL. The initial text encoding is ISO-8859-1.
   */
  public UserUrlLinkFrame() {
    this(ISO_8859_1);
  }

  /**
   * Creates a frame containing WXXX description and URL.
   *
   * @param encoding the requested text encoding
   */
  public UserUrlLinkFrame(Charset encoding) {
    super(new ByteVector("WXXX"));
    this.encoding = Id3Text.requireEncoding(encoding);
  }

  /**
   * Decodes WXXX description and URL from serialized frame bytes, including their frame header.
   *
   * @param data serialized bytes including the frame header
   */
  public UserUrlLinkFrame(ByteVector data) {
    super(data);
    parseUserUrl(fieldData(data), true);
  }

  /**
   * Decodes WXXX description and URL from serialized frame bytes, including their frame header.
   * The supplied parsed frame header is retained as a live reference.
   *
   * @param data   serialized bytes including the frame header
   * @param header the live parsed frame header
   */
  protected UserUrlLinkFrame(ByteVector data, Header header) {
    super(data, header);
    parseUserUrl(fieldData(data), true);
  }

  public final String description() {
    return description;
  }

  public final void setDescription(String value) {
    description = Objects.requireNonNull(value);
  }

  public final Charset textEncoding() {
    return encoding;
  }

  public final void setTextEncoding(Charset value) {
    encoding = Id3Text.requireEncoding(value);
  }

  @Override
  public String toString() {
    return "[" + Id3Text.detachedLeft(description) + "] " + url();
  }

  @Override
  public PropertyMap asProperties() {
    String key = Id3Text.upper(description);
    return PropertyMap.builder().put(key.isEmpty() || key.equals("URL") ? "URL" : "URL:" + key, url()).build();
  }

  /**
   * Returns the first live matching frame from a tag.
   *
   * @param tag   the tag to search
   * @param value the owner or description to match exactly
   * @return the matching frame, or {@code null} if absent
   */
  public static UserUrlLinkFrame find(Tag tag, String value) {
    return find(tag.frameList(new ByteVector("WXXX")), value);
  }

  /**
   * Returns the first live matching frame from the supplied frame sequence.
   *
   * @param frames      the frames to search in iteration order
   * @param description the description to match exactly
   * @return the matching frame, or {@code null} if absent
   */
  public static UserUrlLinkFrame find(Iterable<? extends Frame> frames, String description) {
    for (Frame frame : frames) {
      if (frame instanceof UserUrlLinkFrame url && url.description().equals(description)) {
        return url;
      }
    }
    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void parseFields(ByteVector data) {
    parseUserUrl(data, false);
  }

  private void parseUserUrl(ByteVector data, boolean constructing) {
    if (data.size() < 2) {
      Debug.debug("A user URL link frame must contain at least 2 bytes.");
      return;
    }
    encoding = Id3Text.encoding(data.at(0));
    int position = 1;
    int end = data.find(textDelimiter(encoding), position,
      ISO_8859_1.equals(encoding) || UTF_8.equals(encoding) ? 1 : 2);
    if (end < position) {
      return;
    }
    // Unlike COMM/USLT/TXXX, native WXXX uses String directly for LATIN1 descriptions.
    description = TextBytes.decodeTerminated(data.data(), position, end - position, encoding);
    position = end + textDelimiter(encoding).size();
    String value = Id3Text.fixedLatin1(data.mid(position));
    if (constructing) {
      setUrlBase(value);
    } else {
      setUrl(value);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected ByteVector renderFields() {
    Charset type = checkTextEncoding(List.of(description), encoding);
    return new ByteVector(Id3Text.encodingByte(type)).append(ByteVector.fromText(description, type))
      .append(textDelimiter(type)).append(ByteVector.fromText(url(), ISO_8859_1));
  }
}
