// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.Debug;
import cz.skyba.taglib.toolkit.TagString;

/**
 * Shared COMM/USLT wire fields; parsing retains each completed field before later failures.
 */
final class LanguageText {
  TagString.Type encoding = TagString.Type.LATIN1;
  ByteVector language = new ByteVector();
  String description = "", text = "";

  void parse(ByteVector data, String shortFrameMessage) {
    if (data.size() < 5) {
      Debug.debug(new TagString(shortFrameMessage));
      return;
    }
    encoding = Id3Text.encoding(data.at(0));
    language = data.mid(1, 3);
    int alignment = encoding == TagString.Type.LATIN1 || encoding == TagString.Type.UTF8 ? 1 : 2;
    var parts = data.mid(4).split(Frame.textDelimiter(encoding), alignment, 2);
    if (parts.size() == 2) {
      description = Id3Text.decode(parts.getFirst(), encoding);
      text = Id3Text.decode(parts.getLast(), encoding);
    }
  }

  ByteVector render(TagString.Type type) {
    return new ByteVector((byte) type.ordinal())
      .append(language.size() == 3 ? language : new ByteVector("XXX"))
      .append(Id3Text.encode(description, type))
      .append(Frame.textDelimiter(type))
      .append(Id3Text.encode(text, type));
  }
}
