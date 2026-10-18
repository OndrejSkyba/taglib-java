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

  @Test
  void copiesOnlyTheSelectedSourceRange() {
    var vector = new ByteVector(new byte[]{1, 2, 3, 4});
    byte[] source = {8, 5, 6, 9};

    vector.set(1, source, 1, 2);
    source[1] = 0;

    assertArrayEquals(new byte[]{1, 5, 6, 4}, vector.toByteArray());
    vector.set(vector.size(), source, source.length, 0);
    assertArrayEquals(new byte[]{1, 5, 6, 4}, vector.toByteArray());
  }

  @Test
  void copiesOverlappingRangesInBothDirections() {
    var vector = new ByteVector(new byte[]{1, 2, 3, 4, 5});
    vector.set(1, vector.data(), 0, 4);
    assertArrayEquals(new byte[]{1, 1, 2, 3, 4}, vector.toByteArray());
    vector.set(0, vector.data(), 1, 4);
    assertArrayEquals(new byte[]{1, 2, 3, 4, 4}, vector.toByteArray());
  }

  @Test
  void rejectsInvalidSourceAndDestinationRangesBeforeCopying() {
    var vector = new ByteVector(new byte[]{1, 2, 3});
    byte[] source = {4, 5, 6};
    for (int[] range : new int[][]{
      {-1, 0, 1}, {2, 0, 2}, {0, -1, 1}, {0, 2, 2}, {0, 0, -1},
      {0, 1, Integer.MAX_VALUE}, {4, 0, 0}, {0, 4, 0}
    }) {
      assertThrows(IndexOutOfBoundsException.class,
        () -> vector.set(range[0], source, range[1], range[2]));
      assertArrayEquals(new byte[]{1, 2, 3}, vector.toByteArray());
    }
  }
}
