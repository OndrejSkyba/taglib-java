// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.riff.info;

import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.TagString;

/**
 * A replaceable decoder and encoder for RIFF INFO text. The default encoding is UTF-8.
 */
public class StringHandler {
  /**
   * Decodes a text field using UTF-8 and ends at the first decoded NUL.
   *
   * @param data encoded field bytes
   * @return decoded text
   */
  public String parse(ByteVector data) {
    return new TagString(data, TagString.Type.UTF8).toString();
  }

  /**
   * Encodes text using UTF-8. Invalid UTF-16 input produces empty bytes.
   *
   * @param value the text to encode
   * @return encoded bytes
   */
  public ByteVector render(String value) {
    return new TagString(value).data(TagString.Type.UTF8);
  }
}
