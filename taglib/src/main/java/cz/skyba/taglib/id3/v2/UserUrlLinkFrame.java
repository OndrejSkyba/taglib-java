// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.Debug;
import cz.skyba.taglib.toolkit.TagString;

import java.util.List;
import java.util.Objects;

/**
 * A mutable ID3v2 frame containing WXXX description and URL.
 * Editing a frame does not write a file; use the owning file's save operation to persist changes.
 */
public class UserUrlLinkFrame extends UrlLinkFrame {
  private TagString.Type encoding = TagString.Type.LATIN1;
  private String description = "";

  /**
   * Creates a frame containing WXXX description and URL. The initial text encoding is ISO-8859-1.
   */
  public UserUrlLinkFrame() {
    this(TagString.Type.LATIN1);
  }

  /**
   * Creates a frame containing WXXX description and URL.
   *
   * @param encoding the requested text encoding
   */
  public UserUrlLinkFrame(TagString.Type encoding) {
    super(new ByteVector("WXXX"));
    this.encoding = Objects.requireNonNull(encoding);
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

  public final TagString.Type textEncoding() {
    return encoding;
  }

  public final void setTextEncoding(TagString.Type value) {
    encoding = Objects.requireNonNull(value);
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
      Debug.debug(new TagString("A user URL link frame must contain at least 2 bytes."));
      return;
    }
    encoding = Id3Text.encoding(data.at(0));
    int position = 1;
    int end = data.find(textDelimiter(encoding), position,
      encoding == TagString.Type.LATIN1 || encoding == TagString.Type.UTF8 ? 1 : 2);
    if (end < position) {
      return;
    }
    // Unlike COMM/USLT/TXXX, native WXXX uses String directly for LATIN1 descriptions.
    description = new TagString(data.mid(position, end - position), encoding).toString();
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
    TagString.Type type = checkTextEncoding(List.of(description), encoding);
    return new ByteVector((byte) type.ordinal()).append(Id3Text.encode(description, type))
      .append(textDelimiter(type)).append(Id3Text.encode(url(), TagString.Type.LATIN1));
  }
}
