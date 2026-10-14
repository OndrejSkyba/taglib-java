package cz.skyba.taglib.internal.codec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MatroskaUidTest {
  @Test
  void attachmentUidUsesNativeMinimumUnsignedWidth() {
    assertArrayEquals(new byte[]{1}, MatroskaCodec.attachmentUid(1));
    long[] values = {0, 1, 255, 256, 0xffff, 0x10000, 0xffff_ffffL,
      0x1_0000_0000L, 0x00ff_ffff_ffff_ffffL, Long.MAX_VALUE, Long.MIN_VALUE, -1};
    int[] widths = {1, 1, 1, 2, 2, 3, 4, 5, 7, 8, 8, 8};
    for (int i = 0; i < values.length; i++) {
      byte[] encoded = MatroskaCodec.attachmentUid(values[i]);
      assertEquals(widths[i], encoded.length);
      long decoded = 0;
      for (byte value : encoded) decoded = (decoded << 8) | (value & 255);
      assertEquals(values[i], decoded);
    }
  }
}
