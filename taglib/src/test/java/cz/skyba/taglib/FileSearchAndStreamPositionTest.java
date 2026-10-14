package cz.skyba.taglib;

import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.IOStream;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

final class FileSearchAndStreamPositionTest {
  @Test
  void searchRetainsNativeBufferAndBeforePrecedenceWhileRestoringCursor() {
    byte[] bytes = new byte[3100];
    Arrays.fill(bytes, (byte) 'x');
    System.arraycopy(new byte[]{'A', 'B', 'C', 'D'}, 0, bytes, 1022, 4);
    var stream = new TrackingMemory(bytes);
    var file = new MemoryFile(stream);
    file.seek(33);
    assertEquals(1022, file.find(new ByteVector("ABCD")));
    assertEquals(1022, file.rfind(new ByteVector("ABCD")));
    // A partial "before" at the preceding boundary wins before the next full search.
    assertEquals(-1, file.find(new ByteVector("ABCD"), 0, new ByteVector("AB")));
    assertEquals(1022, file.rfind(new ByteVector("ABCD"), 0, new ByteVector("AB")));
    assertEquals(33, file.tell());
    assertEquals(-1, file.find(new ByteVector(1025)));
    assertEquals(33, file.tell());
    file.close();
    assertFalse(stream.closed);
  }

  @Test
  void delegatedMutationValidityAndBorrowedStreamLifetime() {
    var stream = new TrackingMemory(new byte[]{1, 2, 3});
    var file = new MemoryFile(stream);
    assertTrue(file.isValid());
    file.invalid();
    assertFalse(file.isValid());
    file.replaceProperties(PropertyMap.builder().put("TITLE", "value").build());
    assertEquals("value", file.tag().title());
    assertEquals("value", file.properties().first("TITLE"));
    file.insert(new ByteVector(new byte[]{8, 9}), 1, 1);
    assertArrayEquals(new byte[]{1, 8, 9, 3}, stream.data().toByteArray());
    file.removeBlock(1, 2);
    assertArrayEquals(new byte[]{1, 3}, stream.data().toByteArray());
    file.close();
    assertFalse(stream.closed);
  }

  private static final class MemoryFile extends FileBase {
    final CommonTagPropertiesAndDuplicationTest.MemoryTag tag = new CommonTagPropertiesAndDuplicationTest.MemoryTag();

    MemoryFile(IOStream stream) {
      super(stream);
    }

    @Override
    public TagBase tag() {
      return tag;
    }

    @Override
    public AudioProperties audioProperties() {
      return null;
    }

    @Override
    public void save() {
      throw new cz.skyba.taglib.TagLibException("Test save rejected");
    }

    void invalid() {
      setValid(false);
    }
  }

  private static final class TrackingMemory extends ByteVectorStream {
    boolean closed;

    TrackingMemory(byte[] bytes) {
      super(bytes);
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}
