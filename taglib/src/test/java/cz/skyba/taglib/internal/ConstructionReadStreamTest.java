package cz.skyba.taglib.internal;

import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.FileStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ConstructionReadStreamTest {
  @TempDir
  Path directory;

  @Test
  void ownedWindowKeepsExactRangesCursorAndEof() throws Exception {
    byte[] bytes = new byte[9000];
    for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
    Path path = directory.resolve("input.mka");
    Files.write(path, bytes);
    try (var stream = ConstructionReadStream.wrap(new FileStream(path), true)) {
      stream.begin(true, 4096);
      assertTrue(stream.isBuffered());
      assertArrayEquals(new byte[]{0}, stream.readBlock(1).data());
      stream.seek(3900);
      assertArrayEquals(Arrays.copyOfRange(bytes, 3900, 4100), stream.readBlock(200).data());
      assertEquals(4100, stream.tell());
      stream.seek(8998);
      assertArrayEquals(Arrays.copyOfRange(bytes, 8998, 9000), stream.readBlock(4).data());
      assertEquals(9000, stream.tell());
      assertTrue(stream.readBlock(1).isEmpty());
      stream.clear();
      stream.seek(0);
      assertEquals(0, stream.readBlock(1).at(0));
    }
  }

  @Test
  void writesInvalidateTheWindowAndFinishRestoresLiveReads() throws Exception {
    Path path = directory.resolve("input.mka");
    Files.write(path, new byte[5000]);
    try (var stream = ConstructionReadStream.wrap(new FileStream(path), true)) {
      stream.begin(true, 4096);
      stream.readBlock(1);
      stream.seek(4);
      stream.writeBlock(new ByteVector((byte) 42));
      stream.seek(4);
      assertEquals(42, stream.readBlock(1).at(0));
      stream.seek(5);
      stream.insert(new ByteVector((byte) 43), 5, 1);
      stream.seek(5);
      assertEquals(43, stream.readBlock(1).at(0));
      stream.finish();
      assertFalse(stream.isBuffered());
      Files.write(path, new byte[]{9, 8, 7});
      stream.seek(0);
      assertArrayEquals(new byte[]{9, 8, 7}, stream.readBlock(10).data());
    }
  }

  @Test
  void borrowedAndCustomReadersKeepOriginalRequestSizes() throws Exception {
    class Counted extends ByteVectorStream {
      int requests;
      long last;

      Counted() {
        super(new byte[]{1, 2, 3});
      }

      @Override
      public ByteVector readBlock(long count) {
        requests++;
        last = count;
        return super.readBlock(count);
      }
    }
    try (var source = new Counted(); var stream = ConstructionReadStream.wrap(source, true)) {
      stream.begin(true, 4096);
      assertFalse(stream.isBuffered());
      assertArrayEquals(new byte[]{1, 2}, stream.readBlock(2).data());
      assertEquals(1, source.requests);
      assertEquals(2, source.last);
    }
    Path path = directory.resolve("input.mka");
    Files.write(path, new byte[]{1, 2, 3});
    try (var stream = ConstructionReadStream.wrap(new FileStream(path), false)) {
      stream.begin(true, 4096);
      assertFalse(stream.isBuffered());
    }
  }
}
