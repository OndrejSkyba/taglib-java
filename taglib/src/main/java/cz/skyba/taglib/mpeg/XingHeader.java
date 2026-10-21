// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.mpeg;

import cz.skyba.taglib.toolkit.ByteVector;

import java.util.Objects;

/// A parsed Xing, Info or VBRI header with unsigned 32-bit totals represented as Java longs.
public final class XingHeader {
  public enum HeaderType {Invalid, Xing, VBRI}

  private final HeaderType type;
  private final long frames, size;

  /// Decodes header fields from the supplied bytes.
  ///
  /// @param data encoded header bytes
  public XingHeader(ByteVector data) {
    Objects.requireNonNull(data);
    int offset = data.find(new ByteVector("Xing"));
    if (offset < 0) {
      offset = data.find(new ByteVector("Info"));
    }
    if (offset >= 0) {
      if (offset > data.size() - 16 || (data.get(offset + 7) & 3) != 3) {
        type = HeaderType.Invalid;
        frames = size = 0;
      } else {
        frames = data.toUnsignedInt(offset + 8, true);
        size = data.toUnsignedInt(offset + 12, true);
        type = HeaderType.Xing;
      }
    } else {
      offset = data.find(new ByteVector("VBRI"));
      if (offset < 0 || offset > data.size() - 32) {
        type = HeaderType.Invalid;
        frames = size = 0;
      } else {
        frames = data.toUnsignedInt(offset + 14, true);
        size = data.toUnsignedInt(offset + 10, true);
        type = HeaderType.VBRI;
      }
    }
  }

  public boolean isValid() {
    return type != HeaderType.Invalid && frames > 0 && size > 0;
  }

  public long totalFrames() {
    return frames;
  }

  public long totalSize() {
    return size;
  }

  public HeaderType type() {
    return type;
  }
}
