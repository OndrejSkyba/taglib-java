package cz.skyba.taglib.toolkit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ByteVectorBulkSetTest {
  @Test
  void replacesOnlyTheRequestedRangeAndCopiesTheInput() {
    var vector = new ByteVector(new byte[]{1, 2, 3, 4});
    byte[] replacement = {5, 6};

    vector.set(1, replacement);
    replacement[0] = 9;

    assertArrayEquals(new byte[]{1, 5, 6, 4}, vector.toByteArray());
    vector.set(vector.size(), new byte[0]);
    assertArrayEquals(new byte[]{1, 5, 6, 4}, vector.toByteArray());
  }

  @Test
  void rejectsInvalidRangesWithoutChangingAnyBytes() {
    var vector = new ByteVector(new byte[]{1, 2, 3});

    for (int index : new int[]{-1, 2, Integer.MAX_VALUE}) {
      assertThrows(IndexOutOfBoundsException.class, () -> vector.set(index, new byte[]{4, 5}));
      assertArrayEquals(new byte[]{1, 2, 3}, vector.toByteArray());
    }
    assertThrows(IndexOutOfBoundsException.class, () -> vector.set(4, new byte[0]));
  }
}
