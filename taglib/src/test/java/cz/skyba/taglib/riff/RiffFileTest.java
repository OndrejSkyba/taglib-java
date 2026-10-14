package cz.skyba.taglib.riff;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.TagBase;
import cz.skyba.taglib.TagLibException;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.IOStream;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class RiffFileTest {
  static class Owner extends RiffFile {
    Owner(IOStream stream, boolean big) throws IOException {
      super(stream, big ? Endianness.BigEndian : Endianness.LittleEndian);
    }

    @Override
    public TagBase tag() {
      return null;
    }

    @Override
    public AudioProperties audioProperties() {
      return null;
    }

    @Override
    public void save() {
      throw new cz.skyba.taglib.TagLibException("Test save rejected");
    }
  }

  static ByteVector chunk(String name, ByteVector data, boolean big) {
    return new ByteVector(name).append(ByteVector.fromUnsignedInt(data.size(), big)).append(data).append(new ByteVector(data.size() % 2));
  }

  static ByteVector form(ByteVector chunks) {
    return new ByteVector("FORM").append(ByteVector.fromUnsignedInt(chunks.size() + 4, true)).append(new ByteVector("AIFF")).append(chunks);
  }

  @Test
  void immediateReplacementRelocatesFollowingChunksAndRetainsUnknownTail() throws IOException {
    var original = form(chunk("JUNK", new ByteVector("abc"), true).append(chunk("COMM", new ByteVector("format"), true))).append(new ByteVector("\u0001tail"));
    var stream = new ByteVectorStream(original);
    try (var f = new Owner(stream, true)) {
      assertEquals(2, f.chunkCount());
      assertEquals(32, f.chunkOffset(1));
      f.setChunkData(0, new ByteVector("abcde"));
      assertEquals(34, f.chunkOffset(1));
      assertEquals(5, f.chunkDataSize64(0));
      assertEquals(1, f.chunkPadding(0));
      assertEquals(new ByteVector("format"), f.chunkData(1));
      assertEquals(40, f.tell());
      assertEquals(form(chunk("JUNK", new ByteVector("abcde"), true).append(chunk("COMM", new ByteVector("format"), true))).append(new ByteVector("\u0001tail")), stream.data());
      f.removeChunk(new ByteVector("JUNK"));
      assertEquals(20, f.chunkOffset(0));
      assertEquals(18, f.riffSize());
    }
  }

  @Test
  void emptyChunkTableAndRemovingLastChunkRetainGlobalSize() throws IOException {
    var bytes = new ByteVector("FORM").append(ByteVector.fromUnsignedInt(99, true)).append(new ByteVector("AIFF"));
    var stream = new ByteVectorStream(bytes);
    try (var f = new Owner(stream, true)) {
      assertEquals(0, f.chunkCount());
      f.setChunkData(new ByteVector("JUNK"), new ByteVector("x"));
      assertEquals(bytes, stream.data());
      assertEquals(99, f.riffSize());
    }
    stream = new ByteVectorStream(form(chunk("JUNK", new ByteVector("ab"), true)));
    try (var f = new Owner(stream, true)) {
      assertEquals(14, f.riffSize());
      f.removeChunk(0);
      assertEquals(12, stream.length());
      assertEquals(14, f.riffSize());
      assertEquals(14, stream.data().toUnsignedInt(4, true));
    }
  }

  @Test
  void duplicateListAppendAndNamedRemovalUseNativeOrderAndAlignment() throws IOException {
    var original = new ByteVector("FORM").append(ByteVector.fromUnsignedInt(13, true)).append(new ByteVector("AIFF"))
      .append(new ByteVector("JUNK")).append(ByteVector.fromUnsignedInt(1, true)).append(new ByteVector("x"));
    var stream = new ByteVectorStream(original);
    try (var f = new Owner(stream, true)) {
      assertEquals(0, f.chunkPadding(0));
      f.setChunkData(new ByteVector("LIST"), new ByteVector("a"), true);
      assertEquals(1, f.chunkPadding(0));
      assertEquals(30, f.chunkOffset(1));
      assertEquals(32, stream.length());
      f.setChunkData(new ByteVector("LIST"), new ByteVector("b"), true);
      assertEquals(3, f.chunkCount());
      var before = stream.data().toByteArray();
      long cursor = f.tell();
      f.setChunkData(new ByteVector("JUNK"), new ByteVector("bad"), true);
      assertArrayEquals(before, stream.data().toByteArray());
      assertEquals(cursor, f.tell());
      f.removeChunk(new ByteVector("LIST"));
      assertEquals(1, f.chunkCount());
      assertEquals(22, stream.length());
      assertEquals(14, f.riffSize());
    }
  }

  @Test
  void littleEndianLongFormKeepsSentinelsAndUpdatesDs64RiffExtent() throws IOException {
    var ds64 = ByteVector.fromUnsignedLong(100, false).append(ByteVector.fromUnsignedLong(-1, false)).append(ByteVector.fromUnsignedLong(7, false)).append(ByteVector.fromUnsignedInt(0, false));
    var bytes = new ByteVector("RF64").append(ByteVector.fromUnsignedInt(0xffff_ffffL, false)).append(new ByteVector("WAVE"))
      .append(chunk("ds64", ds64, false)).append(new ByteVector("data")).append(ByteVector.fromUnsignedInt(0xffff_ffffL, false)).append(new ByteVector("audio"));
    var stream = new ByteVectorStream(bytes);
    try (var f = new Owner(stream, false)) {
      assertEquals(5, f.chunkDataSize64(1));
      f.setChunkData(new ByteVector("JUNK"), new ByteVector("x"));
      assertEquals(0xffff_ffffL, f.riffSize());
      assertEquals(0xffff_ffffL, stream.data().toUnsignedInt(4, false));
      assertEquals(stream.length() - 8, stream.data().toUnsignedLong(20, false));
      assertEquals(-1, stream.data().toUnsignedLong(28, false));
      assertEquals(0xffff_ffffL, stream.data().toUnsignedInt(52, false));
    }
  }

  @Test
  void chunkCountHasNativeBoundAndReadsBeginAtCurrentCursor() throws IOException {
    var bytes = new ByteVector("prefix!").append(form(chunk("JUNK", new ByteVector("audio"), true)));
    var stream = new ByteVectorStream(bytes);
    stream.seek(7);
    try (var f = new Owner(stream, true)) {
      assertEquals(1, f.chunkCount());
      assertEquals(27, f.chunkOffset(0));
    }
    byte[] raw = new byte[50_001 * 8];
    for (int n = 0; n < 50_001; n++) System.arraycopy(new byte[]{'J', 'U', 'N', 'K'}, 0, raw, n * 8, 4);
    var chunks = new ByteVector(raw);
    assertThrows(TagLibException.class, () -> new Owner(new ByteVectorStream(form(chunks)), true));
  }

  @Test
  void invalidIndexesDoNotChangeBytesOrCursorAndMemoryGrowthIsPreflighted() throws IOException {
    var stream = new ByteVectorStream(form(chunk("JUNK", new ByteVector("abc"), true)));
    try (var f = new Owner(stream, true)) {
      byte[] before = stream.data().toByteArray();
      long cursor = f.tell();
      assertEquals(0, f.chunkOffset(-1));
      assertEquals(0, f.chunkDataSize64(100));
      assertTrue(f.chunkName(100).isEmpty());
      f.removeChunk(100);
      f.setChunkData(100, new ByteVector("x"));
      assertTrue(f.chunkData(100).isEmpty());
      assertArrayEquals(before, stream.data().toByteArray());
      assertEquals(cursor, f.tell());
      stream.truncate(12);
      before = stream.data().toByteArray();
      assertThrows(IllegalStateException.class, () -> f.setChunkData(0, new ByteVector(32)));
      assertArrayEquals(before, stream.data().toByteArray());
      assertEquals(3, f.chunkDataSize64(0));
    }
  }
}
