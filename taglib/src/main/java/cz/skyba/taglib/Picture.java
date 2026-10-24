// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/// An immutable artwork snapshot with copied binary data.
/// Unknown image dimensions, bit depth and indexed color count use zero. Picture type codes
/// follow the ID3v2/FLAC artwork roles described by [PictureType].
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
  /// @param type the picture role code
  /// @param mimeType the content MIME type
  /// @param description descriptive text
  /// @param data image bytes, copied on construction
  /// @param width image width in pixels, or zero if unknown
  /// @param height image height in pixels, or zero if unknown
  /// @param colorDepth bits per pixel, or zero if unknown
  /// @param indexedColors indexed color count, or zero if unknown
  /// @throws NullPointerException if MIME type, description or image bytes are null
  public Picture {
    Objects.requireNonNull(mimeType);
    Objects.requireNonNull(description);
    data = Objects.requireNonNull(data).clone();
  }

  /// Creates an artwork snapshot and copies the image bytes. Image characteristics are set to zero.
  ///
  /// @param type the picture role code
  /// @param mimeType the content MIME type
  /// @param description descriptive text
  /// @param data image bytes, copied on construction
  /// @throws NullPointerException if MIME type, description or image bytes are null
  public Picture(int type, String mimeType, String description, byte[] data) {
    this(type, mimeType, description, data, 0, 0, 0, 0);
  }

  /// Creates an artwork snapshot with a named role and unknown image characteristics.
  ///
  /// @param type the named artwork role
  /// @param mimeType the content MIME type
  /// @param description descriptive text
  /// @param data image bytes, copied on construction
  /// @throws NullPointerException if the role, MIME type, description or image bytes are null
  public Picture(PictureType type, String mimeType, String description, byte[] data) {
    this(Objects.requireNonNull(type).code(), mimeType, description, data);
  }

  /// Reads an image immediately and detects its MIME type from its contents.
  /// The description is empty and image characteristics are left unknown.
  /// Recognizes JPEG, PNG, GIF, BMP, TIFF and WebP signatures without decoding the image.
  ///
  /// @param type the artwork role
  /// @param path the image file to read
  /// @return the artwork snapshot
  /// @throws UncheckedIOException if the image cannot be read
  /// @throws IllegalArgumentException if the image type is not recognized
  /// @throws NullPointerException if the role or path is null
  public static Picture from(PictureType type, Path path) {
    Objects.requireNonNull(type);
    Objects.requireNonNull(path);
    try {
      byte[] data = Files.readAllBytes(path);
      return new Picture(type, imageMimeType(data), "", data);
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read picture: " + path, failure);
    }
  }

  private static String imageMimeType(byte[] data) throws IOException {
    if (data.length >= 3 && data[0] == (byte) 0xff && data[1] == (byte) 0xd8 && data[2] == (byte) 0xff) {
      return "image/jpeg";
    }
    if (data.length >= 2 && data[0] == 'B' && data[1] == 'M') {
      return "image/bmp";
    }
    if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
      && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
      return "image/webp";
    }
    String mimeType = URLConnection.guessContentTypeFromStream(new ByteArrayInputStream(data));
    if (mimeType == null || !mimeType.startsWith("image/")) {
      throw new IllegalArgumentException("Unrecognized image type");
    }
    return mimeType;
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
