// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.internal.Ascii;
import cz.skyba.taglib.toolkit.ByteVector;

import java.nio.charset.Charset;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.*;

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

  static Charset encoding(byte value) {
    return switch (value & 255) {
      case 1 -> UTF_16;
      case 2 -> UTF_16BE;
      case 3 -> UTF_8;
      case 4 -> UTF_16LE;
      default -> ISO_8859_1;
    };
  }

  static byte encodingByte(Charset charset) {
    Objects.requireNonNull(charset);
    if (ISO_8859_1.equals(charset)) return 0;
    if (UTF_16.equals(charset)) return 1;
    if (UTF_16BE.equals(charset)) return 2;
    if (UTF_8.equals(charset)) return 3;
    if (UTF_16LE.equals(charset)) return 4;
    throw new IllegalArgumentException("Unsupported ID3 text charset: " + charset);
  }

  static Charset requireEncoding(Charset charset) {
    encodingByte(charset);
    return charset;
  }

  static String decode(ByteVector data, Charset charset) {
    return ISO_8859_1.equals(charset) ? Objects.requireNonNull(handler.parse(new ByteVector(data)))
      : data.decodeTerminatedText(charset);
  }

  static String fixedLatin1(ByteVector data) {
    return data.decodeTerminatedText(ISO_8859_1);
  }

  static String upper(String text) {
    return Ascii.upper(text);
  }

  // Native String's copy-on-write detach uses c_str(), truncating a copied left operand.
  static String detachedLeft(String text) {
    int end = text.indexOf('\0');
    return end < 0 ? text : text.substring(0, end);
  }

  static PropertyMap unsupported(ByteVector id) {
    return PropertyMap.builder().addUnsupportedData(fixedLatin1(id)).build();
  }
}
