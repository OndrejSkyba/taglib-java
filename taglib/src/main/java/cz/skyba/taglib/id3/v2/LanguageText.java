// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.toolkit.ByteVector;

import java.nio.charset.Charset;

import static java.lang.System.Logger.Level.DEBUG;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

/// Language-specific ID3 comment or lyrics text; parsing retains completed fields after later failures.
final class LanguageText {
  private static final System.Logger LOG = System.getLogger(LanguageText.class.getName());

  Charset encoding = ISO_8859_1;
  ByteVector language = new ByteVector();
  String description = "", text = "";

  void parse(ByteVector data, String shortFrameMessage) {
    if (data.size() < 5) {
      LOG.log(DEBUG, shortFrameMessage);
      return;
    }
    encoding = Id3Text.encoding(data.at(0));
    language = data.mid(1, 3);
    int alignment = ISO_8859_1.equals(encoding) || UTF_8.equals(encoding) ? 1 : 2;
    var parts = data.mid(4).split(Frame.textDelimiter(encoding), alignment, 2);
    if (parts.size() == 2) {
      description = Id3Text.decode(parts.getFirst(), encoding);
      text = Id3Text.decode(parts.getLast(), encoding);
    }
  }

  ByteVector render(Charset type) {
    return new ByteVector(Id3Text.encodingByte(type))
      .append(language.size() == 3 ? language : new ByteVector("XXX"))
      .append(ByteVector.fromText(description, type))
      .append(Frame.textDelimiter(type))
      .append(ByteVector.fromText(text, type));
  }
}
