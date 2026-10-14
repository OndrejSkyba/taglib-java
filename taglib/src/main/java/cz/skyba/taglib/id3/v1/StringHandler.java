// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v1;

import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.TagString;

/**
 * A replaceable decoder and encoder for ID3v1 text. The default encoding is ISO-8859-1.
 */
public class StringHandler {
  /**
   * Decodes a text field using ISO-8859-1 and ends at the first decoded NUL. Leading and trailing ASCII whitespace is removed.
   *
   * @param data encoded field bytes
   * @return decoded text
   */
  public String parse(ByteVector data) {
    return new TagString(data, TagString.Type.LATIN1).stripWhiteSpace().toString();
  }

  /**
   * Encodes text using ISO-8859-1. Non-Latin-1 text produces empty bytes.
   *
   * @param value the text to encode
   * @return encoded bytes
   */
  public ByteVector render(String value) {
    var text = new TagString(value);
    return text.isLatin1() ? text.data(TagString.Type.LATIN1) : new ByteVector();
  }
}
