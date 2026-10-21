// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;

import java.util.Arrays;
import java.util.Objects;

/// An immutable artwork snapshot with copied binary data.
/// Unknown image dimensions, bit depth and indexed color count use zero. Picture type codes
/// follow the ID3v2/FLAC artwork roles, including 0 for other, 3 for front cover and 4 for back cover.
///
/// @param type          the picture role code
/// @param mimeType      the content MIME type
/// @param description   descriptive text
/// @param data          image bytes, copied on construction
/// @param width         image width in pixels, or zero if unknown
/// @param height        image height in pixels, or zero if unknown
/// @param colorDepth    bits per pixel, or zero if unknown
/// @param indexedColors indexed color count, or zero if unknown
public record Picture(
  int type,
  String mimeType,
  String description,
  byte[] data,
  int width,
  int height,
  int colorDepth,
  int indexedColors) {
  /// Creates an artwork snapshot and copies the image bytes.
  ///
  /// @throws NullPointerException if MIME type, description or image bytes are null
  public Picture {
    Objects.requireNonNull(mimeType);
    Objects.requireNonNull(description);
    data = Objects.requireNonNull(data).clone();
  }

  /// Creates an artwork snapshot and copies the image bytes. Image characteristics are set to zero.
  ///
  /// @throws NullPointerException if MIME type, description or image bytes are null
  public Picture(int type, String mimeType, String description, byte[] data) {
    this(type, mimeType, description, data, 0, 0, 0, 0);
  }

  /// Returns an independent copy of the image bytes.
  ///
  /// @return copied image data
  @Override
  public byte[] data() {
    return data.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Picture p
      && type == p.type
      && mimeType.equals(p.mimeType)
      && description.equals(p.description)
      && Arrays.equals(data, p.data)
      && width == p.width
      && height == p.height
      && colorDepth == p.colorDepth
      && indexedColors == p.indexedColors;
  }

  @Override
  public int hashCode() {
    return 31 * Objects.hash(type, mimeType, description, width, height, colorDepth, indexedColors)
      + Arrays.hashCode(data);
  }
}
