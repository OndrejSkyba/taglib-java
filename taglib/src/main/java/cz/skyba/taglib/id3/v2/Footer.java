// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.toolkit.ByteVector;

import java.util.Objects;

/// Mutable ID3v2 footer fields for parsing and serialization.
public class Footer {
  public Footer() {
  }

  public static int size() {
    return 10;
  }

  /// Serializes a footer from a live tag header. Header flag normalization is the same as
  /// [Header#render()] and updates the supplied header.
  ///
  /// @param header the live header to render
  /// @return ten footer bytes
  public ByteVector render(Header header) {
    var data = Objects.requireNonNull(header).render();
    data.set(0, (byte) '3');
    data.set(1, (byte) 'D');
    data.set(2, (byte) 'I');
    return data;
  }
}
