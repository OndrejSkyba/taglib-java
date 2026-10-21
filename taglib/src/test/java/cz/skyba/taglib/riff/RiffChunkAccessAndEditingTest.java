package cz.skyba.taglib.riff;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.TagBase;
import cz.skyba.taglib.AudioFixtures;
import cz.skyba.taglib.toolkit.ByteVector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RiffChunkAccessAndEditingTest {
  @TempDir
  Path directory;

  static class PublicRIFF extends RiffFile {
    PublicRIFF(Path p) throws IOException {
      super(p, Endianness.BigEndian);
    }

    public TagBase tag() {
      return null;
    }

    public AudioProperties audioProperties() {
      return null;
    }

    public void save() {
      throw new cz.skyba.taglib.TagLibException("Test save rejected");
    }
  }

  Path copy(String name) throws Exception {
    return AudioFixtures.copy(directory, name);
  }

  @Test
  void testPadding() throws Exception {
    var path = copy("empty.aiff");

    try (var f = new PublicRIFF(path)) {
      assertEquals(new ByteVector("TEST"), f.chunkName(2));
      assertEquals((long) (0x1728 + 8), f.chunkOffset(2));

      f.setChunkData(new ByteVector("TEST"), new ByteVector("foo"));
    }
    try (var f = new PublicRIFF(path)) {
      assertEquals(new ByteVector("TEST"), f.chunkName(2));
      assertEquals(new ByteVector("foo"), f.chunkData(2));
      assertEquals((3), f.chunkDataSize(2));
      assertEquals((long) (0x1728 + 8), f.chunkOffset(2));

      f.setChunkData(new ByteVector("SSND"), new ByteVector("abcd"));

      assertEquals(new ByteVector("SSND"), f.chunkName(1));
      assertEquals(new ByteVector("abcd"), f.chunkData(1));

      f.seek(f.chunkOffset(1));
      assertEquals(new ByteVector("abcd"), f.readBlock(4));

      assertEquals(new ByteVector("TEST"), f.chunkName(2));
      assertEquals(new ByteVector("foo"), f.chunkData(2));

      f.seek(f.chunkOffset(2));
      assertEquals(new ByteVector("foo"), f.readBlock(3));
    }
    try (var f = new PublicRIFF(path)) {
      assertEquals(new ByteVector("SSND"), f.chunkName(1));
      assertEquals(new ByteVector("abcd"), f.chunkData(1));

      assertEquals(new ByteVector("TEST"), f.chunkName(2));
      assertEquals(new ByteVector("foo"), f.chunkData(2));
    }

  }

  @Test
  void testLastChunkAtEvenPosition() throws Exception {
    var path = copy("noise.aif");

    try (var f = new PublicRIFF(path)) {
      assertEquals((long) (0xff0 + 8), f.chunkOffset(2));
      assertEquals((311), f.chunkDataSize(2));
      assertEquals(new ByteVector("SSND"), f.chunkName(2));
      assertEquals((1), f.chunkPadding(2));
      assertEquals((long) (4400), f.length());
      assertEquals((4399 - 8), f.riffSize());
      f.setChunkData(new ByteVector("TEST"), new ByteVector("abcd"));
      assertEquals((long) (4088), f.chunkOffset(2));
      assertEquals((311), f.chunkDataSize(2));
      assertEquals(new ByteVector("SSND"), f.chunkName(2));
      assertEquals((1), f.chunkPadding(2));
      assertEquals((long) (4408), f.chunkOffset(3));
      assertEquals((4), f.chunkDataSize(3));
      assertEquals(new ByteVector("TEST"), f.chunkName(3));
      assertEquals((0), f.chunkPadding(3));
      assertEquals((4412 - 8), f.riffSize());
    }
    try (var f = new PublicRIFF(path)) {
      assertEquals((long) (4088), f.chunkOffset(2));
      assertEquals((311), f.chunkDataSize(2));
      assertEquals(new ByteVector("SSND"), f.chunkName(2));
      assertEquals((1), f.chunkPadding(2));
      assertEquals((long) (4408), f.chunkOffset(3));
      assertEquals((4), f.chunkDataSize(3));
      assertEquals(new ByteVector("TEST"), f.chunkName(3));
      assertEquals((0), f.chunkPadding(3));
      assertEquals((long) (4412), f.length());
    }

  }

  @Test
  void testLastChunkAtEvenPosition2() throws Exception {
    var path = copy("noise_odd.aif");

    try (var f = new PublicRIFF(path)) {
      assertEquals((long) (0xff0 + 8), f.chunkOffset(2));
      assertEquals((311), f.chunkDataSize(2));
      assertEquals(new ByteVector("SSND"), f.chunkName(2));
      assertEquals((0), f.chunkPadding(2));
      assertEquals((long) (4399), f.length());
      assertEquals((4399 - 8), f.riffSize());
      f.setChunkData(new ByteVector("TEST"), new ByteVector("abcd"));
      assertEquals((long) (4088), f.chunkOffset(2));
      assertEquals((311), f.chunkDataSize(2));
      assertEquals(new ByteVector("SSND"), f.chunkName(2));
      assertEquals((1), f.chunkPadding(2));
      assertEquals((long) (4408), f.chunkOffset(3));
      assertEquals((4), f.chunkDataSize(3));
      assertEquals(new ByteVector("TEST"), f.chunkName(3));
      assertEquals((0), f.chunkPadding(3));
      assertEquals((4412 - 8), f.riffSize());
    }
    try (var f = new PublicRIFF(path)) {
      assertEquals((long) (4088), f.chunkOffset(2));
      assertEquals((311), f.chunkDataSize(2));
      assertEquals(new ByteVector("SSND"), f.chunkName(2));
      assertEquals((1), f.chunkPadding(2));
      assertEquals((long) (4408), f.chunkOffset(3));
      assertEquals((4), f.chunkDataSize(3));
      assertEquals(new ByteVector("TEST"), f.chunkName(3));
      assertEquals((0), f.chunkPadding(3));
      assertEquals((long) (4412), f.length());
    }

  }

  @Test
  void testLastChunkAtEvenPosition3() throws Exception {
    var path = copy("noise_odd.aif");

    try (var f = new PublicRIFF(path)) {
      assertEquals((long) (0xff0 + 8), f.chunkOffset(2));
      assertEquals((311), f.chunkDataSize(2));
      assertEquals(new ByteVector("SSND"), f.chunkName(2));
      assertEquals((0), f.chunkPadding(2));
      assertEquals((long) (4399), f.length());
      assertEquals((4399 - 8), f.riffSize());
      f.setChunkData(new ByteVector("TEST"), new ByteVector("abc"));
      assertEquals((long) (4088), f.chunkOffset(2));
      assertEquals((311), f.chunkDataSize(2));
      assertEquals(new ByteVector("SSND"), f.chunkName(2));
      assertEquals((1), f.chunkPadding(2));
      assertEquals((long) (4408), f.chunkOffset(3));
      assertEquals((3), f.chunkDataSize(3));
      assertEquals(new ByteVector("TEST"), f.chunkName(3));
      assertEquals((1), f.chunkPadding(3));
      assertEquals((4412 - 8), f.riffSize());
    }
    try (var f = new PublicRIFF(path)) {
      assertEquals((long) (4088), f.chunkOffset(2));
      assertEquals((311), f.chunkDataSize(2));
      assertEquals(new ByteVector("SSND"), f.chunkName(2));
      assertEquals((1), f.chunkPadding(2));
      assertEquals((long) (4408), f.chunkOffset(3));
      assertEquals((3), f.chunkDataSize(3));
      assertEquals(new ByteVector("TEST"), f.chunkName(3));
      assertEquals((1), f.chunkPadding(3));
      assertEquals((long) (4412), f.length());
    }

  }

  @Test
  void testChunkOffset() throws Exception {
    var path = copy("empty.aiff");

    try (var f = new PublicRIFF(path)) {

      assertEquals(5928, f.riffSize());
      assertEquals((long) (5936), f.length());
      assertEquals(new ByteVector("COMM"), f.chunkName(0));
      assertEquals((long) (0x000C + 8), f.chunkOffset(0));
      assertEquals(new ByteVector("SSND"), f.chunkName(1));
      assertEquals((long) (0x0026 + 8), f.chunkOffset(1));
      assertEquals(new ByteVector("TEST"), f.chunkName(2));
      assertEquals((long) (0x1728 + 8), f.chunkOffset(2));

      var data = new ByteVector(0x400, (byte) ' ');
      f.setChunkData(new ByteVector("SSND"), data);
      assertEquals(1070, f.riffSize());
      assertEquals((long) (1078), f.length());
      assertEquals((long) (0x000C + 8), f.chunkOffset(0));
      assertEquals((long) (0x0026 + 8), f.chunkOffset(1));
      assertEquals((long) (0x042E + 8), f.chunkOffset(2));

      f.seek(f.chunkOffset(0) - 8);
      assertEquals(new ByteVector("COMM"), f.readBlock(4));
      f.seek(f.chunkOffset(1) - 8);
      assertEquals(new ByteVector("SSND"), f.readBlock(4));
      f.seek(f.chunkOffset(2) - 8);
      assertEquals(new ByteVector("TEST"), f.readBlock(4));

      f.setChunkData(0, data);
      assertEquals(2076, f.riffSize());
      assertEquals((long) (2084), f.length());
      assertEquals((long) (0x000C + 8), f.chunkOffset(0));
      assertEquals((long) (0x0414 + 8), f.chunkOffset(1));
      assertEquals((long) (0x081C + 8), f.chunkOffset(2));

      f.seek(f.chunkOffset(0) - 8);
      assertEquals(new ByteVector("COMM"), f.readBlock(4));
      f.seek(f.chunkOffset(1) - 8);
      assertEquals(new ByteVector("SSND"), f.readBlock(4));
      f.seek(f.chunkOffset(2) - 8);
      assertEquals(new ByteVector("TEST"), f.readBlock(4));

      f.removeChunk(new ByteVector("SSND"));
      assertEquals(1044, f.riffSize());
      assertEquals((long) (1052), f.length());
      assertEquals((long) (0x000C + 8), f.chunkOffset(0));
      assertEquals((long) (0x0414 + 8), f.chunkOffset(1));

      f.seek(f.chunkOffset(0) - 8);
      assertEquals(new ByteVector("COMM"), f.readBlock(4));
      f.seek(f.chunkOffset(1) - 8);
      assertEquals(new ByteVector("TEST"), f.readBlock(4));

      f.removeChunk(0);
      assertEquals(12, f.riffSize());
      assertEquals((long) (20), f.length());
      assertEquals((long) (0x000C + 8), f.chunkOffset(0));

      f.seek(f.chunkOffset(0) - 8);
      assertEquals(new ByteVector("TEST"), f.readBlock(4));

    }

  }
}
