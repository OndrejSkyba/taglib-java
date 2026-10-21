// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.mp4;

import cz.skyba.taglib.toolkit.ByteVector;

import java.util.Objects;

/// An immutable MP4 cover-art value with copied image data.
public final class CoverArt {
  /// The image format code stored in an MP4 cover-art data atom.
  public enum Format {
    JPEG(13), PNG(14), BMP(27), GIF(12), Unknown(0);
    private final int value;

    Format(int value) {
      this.value = value;
    }

    public int value() {
      return value;
    }
  }

  private record State(Format format, ByteVector data) {
  }

  private final State state;

  /// Creates an immutable artwork value. Input bytes are copied.
  ///
  /// @param format the image encoding
  /// @param data   the bytes to copy
  public CoverArt(Format format, ByteVector data) {
    state = new State(Objects.requireNonNull(format), new ByteVector(data));
  }

  /// Creates an immutable artwork value. Input bytes are copied.
  ///
  /// @param format the image encoding
  /// @param data   the bytes to copy
  public CoverArt(Format format, byte[] data) {
    state = new State(Objects.requireNonNull(format), new ByteVector(data));
  }

  /// Creates an immutable artwork value. Immutable payload storage can be shared.
  ///
  /// @param other the value to copy
  public CoverArt(CoverArt other) {
    state = other.state;
  }

  public Format format() {
    return state.format;
  }

  /// Returns an independent copy of the stored bytes.
  ///
  /// @return copied bytes
  public ByteVector data() {
    return new ByteVector(state.data);
  }

  /// Returns an independent copy of the image bytes.
  ///
  /// @return copied image bytes
  public byte[] toByteArray() {
    return state.data.toByteArray();
  }

  @Override
  public boolean equals(Object value) {
    return value instanceof CoverArt c && state.equals(c.state);
  }

  @Override
  public int hashCode() {
    return state.hashCode();
  }
}
