// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.toolkit.ByteVector;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

/// A replaceable decoder for ID3v2 fields marked as ISO-8859-1.
/// Register an instance through [Tag#setLatin1StringHandler(Latin1StringHandler)].
/// The default decoder ends text at the first decoded NUL character.
public class Latin1StringHandler {
  /// Decodes a text field marked as ISO-8859-1. Custom handlers can interpret the bytes differently.
  ///
  /// @param data field bytes to decode
  /// @return decoded text; a custom implementation must not return null
  public String parse(ByteVector data) {
    return data.decodeTerminatedText(ISO_8859_1);
  }
}
