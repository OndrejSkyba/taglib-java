// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.toolkit.ByteVector;

import java.util.Objects;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

/// A mutable ID3v2 frame containing URL-link text.
/// Editing a frame does not write a file; use the owning file's save operation to persist changes.
public class UrlLinkFrame extends Frame {
  private String url = "";

  /// Decodes URL-link text from serialized frame bytes, including their frame header.
  ///
  /// @param data serialized bytes including the frame header
  public UrlLinkFrame(ByteVector data) {
    super(data);
    parseUrl(fieldData(data));
  }

  /// Decodes URL-link text from serialized frame bytes, including their frame header.
  /// The supplied parsed frame header is retained as a live reference.
  ///
  /// @param data   serialized bytes including the frame header
  /// @param header the live parsed frame header
  protected UrlLinkFrame(ByteVector data, Header header) {
    super(header);
    parseUrl(fieldData(data));
  }

  public String url() {
    return url;
  }

  public void setUrl(String value) {
    setUrlBase(value);
  }

  protected final void setUrlBase(String value) {
    url = Objects.requireNonNull(value);
  }

  @Override
  public void setText(String value) {
    setUrl(value);
  }

  @Override
  public String toString() {
    return url();
  }

  @Override
  public PropertyMap asProperties() {
    String key = frameIDToKey(frameID());
    return key.isEmpty() ? Id3Text.unsupported(frameID()) : PropertyMap.builder().put(key, url()).build();
  }

  @Override
  protected void parseFields(ByteVector data) {
    parseUrl(data);
  }

  private void parseUrl(ByteVector data) {
    url = Id3Text.fixedLatin1(data);
  }

  @Override
  protected ByteVector renderFields() {
    return ByteVector.fromText(url, ISO_8859_1);
  }
}
