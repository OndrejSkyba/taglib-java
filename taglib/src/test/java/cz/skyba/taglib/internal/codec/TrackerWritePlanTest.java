package cz.skyba.taglib.internal.codec;

import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TrackerWritePlanTest {
  @Test
  void overlaysDoNotModifyTheSourceReadBuffer() {
    var source = new ByteVector(new byte[]{1, 2, 3});
    var plan = new TrackerWritePlan((at, count) -> source, source.size(), 0, Integer.MAX_VALUE);
    plan.write(new ByteVector((byte) 9));
    plan.seek(0);
    var result = plan.read(3);
    assertArrayEquals(new byte[]{9, 2, 3}, result.data());
    result.set(1, (byte) 8);
    assertArrayEquals(new byte[]{1, 2, 3}, source.data());
  }

  @Test
  void readsBeyondEndDoNotWrapToEarlierWrites() {
    var stream = new ByteVectorStream(new byte[]{1, 2, 3, 4});
    var plan = new TrackerWritePlan((at, count) -> {
      stream.seek(at);
      return stream.readBlock(count);
    }, 4, 0, Integer.MAX_VALUE);
    plan.seek(1);
    plan.write(new ByteVector((byte) 9));
    plan.seek(0x1_0000_0000L);
    assertArrayEquals(new byte[0], plan.read(4).toByteArray());
    assertEquals(0x1_0000_0000L, plan.tell());
    plan.seek(0);
    assertArrayEquals(new byte[]{1, 9, 3, 4}, plan.read(0x1_0000_0000L).toByteArray());
    assertEquals(4, plan.tell());
    assertArrayEquals(new byte[]{1, 2, 3, 4}, stream.data().toByteArray());
  }

  @Test
  void truncateAndExtendEraseTheRemovedBytesAndReplayExactFinalPosition() {
    var stream = new ByteVectorStream(new byte[]{1, 2, 3, 4});
    var plan = new TrackerWritePlan((at, count) -> {
      stream.seek(at);
      return stream.readBlock(count);
    }, 4, 0, Integer.MAX_VALUE);
    plan.truncate(2);
    plan.seek(4);
    plan.write(new ByteVector((byte) 9));
    plan.seek(0);
    assertArrayEquals(new byte[]{1, 2, 0, 0, 9}, plan.read(5).toByteArray());
    plan.apply((at, bytes) -> {
      stream.seek(at);
      stream.writeBlock(bytes);
    }, stream::truncate, stream::seek);
    assertArrayEquals(new byte[]{1, 2, 0, 0, 9}, stream.data().toByteArray());
    assertEquals(5, stream.tell());
  }

  @Test
  void shortSourceReadsRetainTheirRequestAndDoNotFabricateUnwrittenBytes() {
    long[] request = {-1};
    var plan = new TrackerWritePlan((at, count) -> {
      request[0] = count;
      return new ByteVector((byte) 7);
    }, 100, 0, Long.MAX_VALUE);
    assertArrayEquals(new byte[]{7}, plan.read(4).toByteArray());
    assertEquals(4, request[0]);
    assertEquals(1, plan.tell());
  }
}
