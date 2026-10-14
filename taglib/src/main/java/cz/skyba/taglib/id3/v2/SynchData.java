// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.toolkit.ByteVector;

import java.util.Arrays;
import java.util.Objects;

/**
 * Synchsafe integer encoding and ID3 unsynchronization decoding.
 */
public final class SynchData {
  private SynchData() {
  }

  /**
   * Decodes up to four synchsafe bytes; a high bit selects ordinary big-endian decoding with
   * right-side zero padding.
   */
  public static long toUnsignedInt(ByteVector data) {
    Objects.requireNonNull(data);
    int length = Math.min(data.size(), 4);
    long result = 0;
    for (int i = 0; i < length; i++) {
      int value = data.at(i) & 255;
      if ((value & 128) != 0) {
        // Native short malformed data is padded on the right before BE conversion.
        result = 0;
        for (int j = 0; j < 4; j++) {
          result = (result << 8) | (j < length ? data.at(j) & 255 : 0);
        }
        return result;
      }
      result |= (long) value << ((length - i - 1) * 7);
    }
    return result;
  }

  /**
   * Encodes the low 28 bits as four synchsafe bytes.
   */
  public static ByteVector fromUnsignedInt(long value) {
    byte[] data = new byte[4];
    for (int i = 0; i < 4; i++) {
      data[i] = (byte) ((value >>> ((3 - i) * 7)) & 127);
    }
    return new ByteVector(data);
  }

  /**
   * Removes ID3 unsynchronisation padding: a zero byte immediately following 0xff.
   */
  public static ByteVector decode(ByteVector data) {
    Objects.requireNonNull(data);
    byte[] result = new byte[data.size()];
    int written = 0;
    for (int i = 0; i < data.size(); i++) {
      byte value = data.at(i);
      result[written++] = value;
      if (value == (byte) 255 && i + 1 < data.size() && data.at(i + 1) == 0) {
        i++;
      }
    }
    return new ByteVector(written == result.length ? result : Arrays.copyOf(result, written));
  }
}
