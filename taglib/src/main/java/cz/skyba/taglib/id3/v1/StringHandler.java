// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v1;

import cz.skyba.taglib.internal.Ascii;
import cz.skyba.taglib.internal.TextBytes;
import cz.skyba.taglib.toolkit.ByteVector;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

/// A replaceable decoder and encoder for ID3v1 text. The default encoding is ISO-8859-1.
public class StringHandler {
  /// Decodes a text field using ISO-8859-1 and ends at the first decoded NUL. Leading and trailing ASCII whitespace is removed.
  ///
  /// @param data encoded field bytes
  /// @return decoded text
  public String parse(ByteVector data) {
    return Ascii.stripWhiteSpace(data.decodeTerminatedText(ISO_8859_1));
  }

  /// Encodes text using ISO-8859-1. Non-Latin-1 text produces empty bytes.
  ///
  /// @param value the text to encode
  /// @return encoded bytes
  public ByteVector render(String value) {
    return TextBytes.isLatin1(value) ? ByteVector.fromText(value, ISO_8859_1) : new ByteVector();
  }
}
