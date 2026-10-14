// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.internal.Ascii;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.TagString;

import java.util.Objects;

/**
 * Shared native decoding; the public Tag facade will own handler registration.
 */
final class Id3Text {
  private static final Latin1StringHandler DEFAULT = new Latin1StringHandler();
  private static volatile Latin1StringHandler handler = DEFAULT;

  private Id3Text() {
  }

  static Latin1StringHandler handler() {
    return handler;
  }

  static void setHandler(Latin1StringHandler value) {
    handler = value == null ? DEFAULT : value;
  }

  static TagString.Type encoding(byte value) {
    return switch (value & 255) {
      case 1 -> TagString.Type.UTF16;
      case 2 -> TagString.Type.UTF16BE;
      case 3 -> TagString.Type.UTF8;
      case 4 -> TagString.Type.UTF16LE;
      default -> TagString.Type.LATIN1;
    };
  }

  static String decode(ByteVector data, TagString.Type type) {
    return type == TagString.Type.LATIN1 ? Objects.requireNonNull(handler.parse(new ByteVector(data)))
      : new TagString(data, type).toString();
  }

  static String fixedLatin1(ByteVector data) {
    return new TagString(data).toString();
  }

  static ByteVector encode(String text, TagString.Type type) {
    return new TagString(text).data(type);
  }

  static String upper(String text) {
    return Ascii.upper(text);
  }

  /**
   * Native String's copy-on-write detach uses c_str(), truncating a copied left operand.
   */
  static String detachedLeft(String text) {
    int end = text.indexOf('\0');
    return end < 0 ? text : text.substring(0, end);
  }

  static PropertyMap unsupported(ByteVector id) {
    return PropertyMap.builder().addUnsupportedData(fixedLatin1(id)).build();
  }
}
