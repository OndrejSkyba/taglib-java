package cz.skyba.taglib.toolkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class StreamBoundsTest {
  @TempDir
  Path directory;

  private IOStream stream(boolean memory) throws IOException {
    if (memory) {
      return new ByteVectorStream(new ByteVector("abcdef"));
    }
    var path = directory.resolve("bytes");
    Files.writeString(path, "abcdef");
    return new FileStream(path);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void largeReadsReturnOnlyRemainingBytes(boolean memory) throws Exception {
    try (var stream = stream(memory)) {
      for (long count : new long[]{Integer.MAX_VALUE, 0x1_0000_0000L, 0x1_0000_0002L, Long.MAX_VALUE}) {
        stream.seek(2);
        var bytes = stream.readBlock(count);
        assertEquals(new ByteVector("cdef"), bytes);
        assertEquals(6, stream.tell());
        assertTrue(stream.readBlock(count).isEmpty());
        assertEquals(6, stream.tell());
        bytes.clear();
        stream.seek(2);
        assertEquals(new ByteVector("cdef"), stream.readBlock(4));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void positionsBeyondEndStayAtEofWithoutWrapping(boolean memory) throws Exception {
    try (var stream = stream(memory)) {
      for (long position : new long[]{6, 7, Integer.MAX_VALUE, 0x1_0000_0000L, 0x1_0000_0001L, Long.MAX_VALUE}) {
        stream.seek(position);
        for (long count : new long[]{0, 1, Long.MAX_VALUE}) {
          assertTrue(stream.readBlock(count).isEmpty());
          assertEquals(position, stream.tell());
        }
        assertEquals(6, stream.length());
        assertEquals(position, stream.tell());
      }
      stream.seek(0);
      assertEquals(new ByteVector("abcdef"), stream.readBlock(6));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void negativeReadsFailWithoutChangingDataOrPosition(boolean memory) throws Exception {
    try (var stream = stream(memory)) {
      stream.seek(2);
      for (long count : new long[]{-1, Long.MIN_VALUE}) {
        assertThrows(IllegalArgumentException.class, () -> stream.readBlock(count));
        assertEquals(2, stream.tell());
      }
      assertTrue(stream.readBlock(0).isEmpty());
      assertEquals(2, stream.tell());
      assertEquals(new ByteVector("cdef"), stream.readBlock(4));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void seeksBeforeBeginningFailWithoutMovingCursor(boolean memory) throws Exception {
    try (var stream = stream(memory)) {
      stream.seek(2);
      for (var origin : IOStream.Position.values()) {
        assertThrows(IllegalArgumentException.class, () -> stream.seek(-7, origin));
        assertThrows(IllegalArgumentException.class, () -> stream.seek(Long.MIN_VALUE, origin));
        assertEquals(2, stream.tell());
      }
      assertEquals(new ByteVector("cdef"), stream.readBlock(4));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void backwardRelativeSeeksRemainValid(boolean memory) throws Exception {
    try (var stream = stream(memory)) {
      stream.seek(-2, IOStream.Position.END);
      assertEquals(4, stream.tell());
      stream.seek(-1, IOStream.Position.CURRENT);
      assertEquals(3, stream.tell());
      assertEquals(new ByteVector("def"), stream.readBlock(3));
      stream.seek(-6, IOStream.Position.CURRENT);
      assertEquals(0, stream.tell());
      assertEquals(new ByteVector("a"), stream.readBlock(1));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void overflowingSeeksFailWithoutMovingCursor(boolean memory) throws Exception {
    try (var stream = stream(memory)) {
      stream.seek(Long.MAX_VALUE);
      assertThrows(ArithmeticException.class, () -> stream.seek(1, IOStream.Position.CURRENT));
      assertEquals(Long.MAX_VALUE, stream.tell());
      assertThrows(ArithmeticException.class, () -> stream.seek(Long.MAX_VALUE, IOStream.Position.END));
      assertEquals(Long.MAX_VALUE, stream.tell());
      assertTrue(stream.readBlock(1).isEmpty());
      stream.seek(-Long.MAX_VALUE, IOStream.Position.CURRENT);
      assertEquals(new ByteVector("abcdef"), stream.readBlock(6));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void negativeTruncationFailsWithoutChangingDataOrPosition(boolean memory) throws Exception {
    try (var stream = stream(memory)) {
      stream.seek(2);
      for (long length : new long[]{-1, Long.MIN_VALUE}) {
        assertThrows(IllegalArgumentException.class, () -> stream.truncate(length));
        assertEquals(6, stream.length());
        assertEquals(2, stream.tell());
      }
      stream.seek(0);
      assertEquals(new ByteVector("abcdef"), stream.readBlock(6));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void truncationAndExtensionPreservePosition(boolean memory) throws Exception {
    try (var stream = stream(memory)) {
      stream.seek(4);
      stream.truncate(0);
      assertEquals(4, stream.tell());
      assertTrue(stream.readBlock(1).isEmpty());
      stream.truncate(7);
      assertEquals(4, stream.tell());
      stream.seek(6);
      stream.writeBlock(new ByteVector("!"));
      stream.seek(0);
      assertArrayEquals(new byte[]{0, 0, 0, 0, 0, 0, '!'}, stream.readBlock(7).toByteArray());
    }
  }

  @Test
  void memoryCapacityFailuresDoNotChangeDataOrPosition() {
    var stream = new ByteVectorStream(new ByteVector("abcdef"));
    stream.seek(2);
    for (long length : new long[]{(long) Integer.MAX_VALUE + 1, 0x1_0000_0000L, Long.MAX_VALUE}) {
      assertThrows(ArithmeticException.class, () -> stream.truncate(length));
      assertEquals(new ByteVector("abcdef"), stream.data());
      assertEquals(2, stream.tell());
    }
    stream.seek(0x1_0000_0000L);
    assertThrows(ArithmeticException.class, () -> stream.writeBlock(new ByteVector("x")));
    assertEquals(new ByteVector("abcdef"), stream.data());
    assertEquals(0x1_0000_0000L, stream.tell());
  }
}
