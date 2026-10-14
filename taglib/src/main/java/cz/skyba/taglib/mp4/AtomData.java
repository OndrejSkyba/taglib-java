// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.mp4;

import cz.skyba.taglib.toolkit.ByteVector;

/**
 * A mutable MP4 data-atom descriptor with type, locale and copied binary payload fields.
 */
public final class AtomData {
  public AtomDataType type;
  public int locale;
  public ByteVector data;

  /**
   * Creates a data-atom descriptor with copied bytes and locale zero.
   *
   * @param type the data encoding code
   * @param data payload bytes to copy
   */
  public AtomData(AtomDataType type, ByteVector data) {
    this.type = type;
    this.data = new ByteVector(data);
  }
}
