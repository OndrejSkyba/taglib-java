// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.mp4;

import cz.skyba.taglib.toolkit.ByteVector;

import java.util.Arrays;
import java.util.Objects;

/**
 * An immutable MP4 Stem manifest stored as raw bytes without JSON validation.
 */
public final class Stem {
  private final byte[] data;

  /**
   * Creates an empty manifest.
   */
  public Stem() {
    this(new byte[0]);
  }

  /**
   * Creates an immutable manifest value. Input bytes are copied.
   *
   * @param data the bytes to copy
   */
  public Stem(byte[] data) {
    this.data = Objects.requireNonNull(data).clone();
  }

  /**
   * Creates an immutable manifest value. Input bytes are copied.
   *
   * @param data the bytes to copy
   */
  public Stem(ByteVector data) {
    this.data = data.toByteArray();
  }

  /**
   * Creates an immutable manifest value. Immutable payload storage can be shared.
   *
   * @param other the value to copy
   */
  public Stem(Stem other) {
    data = other.data;
  }

  /**
   * Returns an independent copy of the stored bytes.
   *
   * @return copied bytes
   */
  public byte[] data() {
    return data.clone();
  }

  /**
   * Returns an independent copy of the stored bytes.
   *
   * @return copied bytes
   */
  public ByteVector byteVector() {
    return new ByteVector(data);
  }

  @Override
  public boolean equals(Object value) {
    return value instanceof Stem other && Arrays.equals(data, other.data);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(data);
  }
}
