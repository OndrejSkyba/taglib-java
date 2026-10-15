package cz.skyba.taglib;

import cz.skyba.taglib.id3.v2.SynchData;
import cz.skyba.taglib.internal.TextBytes;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.FileStream;
import cz.skyba.taglib.toolkit.IOStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.jupiter.api.Assertions.*;

class FileBaseDefaultsAndOwnershipTest {
  @TempDir
  Path directory;

  static class Plain extends FileBase {
    Plain(Path path) {
      super(path);
    }

    Plain(IOStream stream) {
      super(stream);
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

    void resize(long n) {
      truncate(n);
    }
  }

  Path copy() throws Exception {
    return Files.copy(FileReferenceResolversAndSavingTest.fixture("empty.ogg"), directory.resolve("empty.ogg"));
  }

  @Test
  void testFindInSmallFile() throws Exception {
    var p = copy();
    try (var f = new Plain(p)) {
      f.seek(0);
      f.writeBlock(new ByteVector("0123456239"));
      f.resize(10);
    }
    try (var f = new Plain(p)) {
      assertEquals(10, f.length());
      assertEquals(2, f.find(new ByteVector("23")));
      assertEquals(2, f.find(new ByteVector("23"), 2));
      assertEquals(7, f.find(new ByteVector("23"), 3));
      f.seek(0);
      var v = f.readBlock(f.length());
      assertEquals(10, v.size());
      for (int i : new int[]{0, 2, 3}) assertEquals(v.find(new ByteVector("23"), i), f.find(new ByteVector("23"), i));
    }
  }

  @Test
  void testRFindInSmallFile() throws Exception {
    var p = copy();
    try (var f = new Plain(p)) {
      f.seek(0);
      f.writeBlock(new ByteVector("0123456239"));
      f.resize(10);
    }
    try (var f = new Plain(p)) {
      assertEquals(10, f.length());
      assertEquals(7, f.rfind(new ByteVector("23")));
      assertEquals(7, f.rfind(new ByteVector("23"), 7));
      assertEquals(2, f.rfind(new ByteVector("23"), 6));
      f.seek(0);
      var v = f.readBlock(f.length());
      assertEquals(10, v.size());
      for (int i : new int[]{0, 7, 6}) assertEquals(v.rfind(new ByteVector("23"), i), f.rfind(new ByteVector("23"), i));
    }
  }

  @Test
  void testSeek() throws Exception {
    try (var f = new Plain(copy())) {
      assertEquals(0, f.tell());
      assertEquals(4328, f.length());
      f.seek(100, IOStream.Position.BEGINNING);
      assertEquals(100, f.tell());
      f.seek(100, IOStream.Position.CURRENT);
      assertEquals(200, f.tell());
      assertThrows(IllegalArgumentException.class, () -> f.seek(-300, IOStream.Position.CURRENT));
      assertEquals(200, f.tell());
      f.seek(-100, IOStream.Position.END);
      assertEquals(4228, f.tell());
      f.seek(-100, IOStream.Position.CURRENT);
      assertEquals(4128, f.tell());
      f.seek(300, IOStream.Position.CURRENT);
      assertEquals(4428, f.tell());
    }
  }

  @Test
  void testTruncate() throws Exception {
    var p = copy();
    try (var f = new Plain(p)) {
      assertEquals(4328, f.length());
      f.resize(2000);
      assertEquals(2000, f.length());
    }
    try (var f = new Plain(p)) {
      assertEquals(2000, f.length());
    }
  }

  @Test
  void descriptorAdapterOwnsChannelAtCurrentOffset() throws Exception {
    var p = directory.resolve("channel");
    Files.writeString(p, "abcdef");
    var channel = FileChannel.open(p, StandardOpenOption.READ, StandardOpenOption.WRITE);
    channel.position(2);
    try (var stream = new FileStream(channel)) {
      assertEquals("", stream.name());
      assertEquals(2, stream.tell());
      assertEquals("cd", stream.readBlock(2).toString());
      stream.insert(new ByteVector("X"), 1, 2);
      assertEquals("aXdef", Files.readString(p));
    }
    assertFalse(channel.isOpen());
  }

  @Test
  void smallEofReadsRequireClearButLengthSeeksClearIt() throws Exception {
    var p = directory.resolve("eof");
    Files.writeString(p, "a");
    try (var stream = new FileStream(p)) {
      assertEquals("a", stream.readBlock(2).toString());
      Files.writeString(p, "ab");
      assertTrue(stream.readBlock(1).isEmpty());
      stream.clear();
      assertEquals("b", stream.readBlock(1).toString());
      assertTrue(stream.readBlock(1).isEmpty());
      Files.writeString(p, "abc");
      assertEquals(3, stream.length());
      assertEquals("c", stream.readBlock(1).toString());
    }
  }

  @Test
  void completedDefaultsHaveLiteralResults() {
    assertEquals(2, new AudioProperties(2064, 0, 0, 0, 0, false).lengthInSeconds());
    var bytes = new byte[]{'a', 0, 'b', 'c'};
    assertEquals("610062", new ByteVector(bytes, 3).toHex().toString());
    assertEquals("610062", ByteVector.fromCString("a\0bc", 3).toHex().toString());
    assertEquals("61", ByteVector.fromCString("a\0bc", 0xffff_ffffL).toHex().toString());
    assertEquals(new ByteVector("bc"), new ByteVector().setData(bytes, 4).mid(2));
    assertEquals("a", new ByteVector().setData("a\0bc").toString());
    assertTrue(new ByteVector("blah blah").containsAt(new ByteVector("blah"), 6, 1));
    assertEquals(1, new ByteVector(".OggS....").rfind((byte) 'O', 4));
    assertEquals(0x010203, new ByteVector(new byte[]{1, 2, 3}).toUnsignedInt(0, 3));
    assertArrayEquals(new byte[]{(byte) 0xe9}, TextBytes.encode("é", ISO_8859_1));
    assertEquals("é", TextBytes.decode(new byte[]{(byte) 0xe9}, ISO_8859_1));
    var stream = new ByteVectorStream(new ByteVector("abc"));
    stream.removeBlock(1, 0);
    assertEquals("abc", stream.data().toString());
    var file = new Plain(stream);
    file.insert(new ByteVector("X"), 1);
    assertEquals("aXbc", stream.data().toString());
    file.removeBlock(0, 0);
    file.removeBlock(1, 0);
    assertEquals("aXbc", stream.data().toString());
  }

  static void concurrent(Runnable work) throws Exception {
    var ready = new CountDownLatch(8);
    var go = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(8)) {
      var futures = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 8; i++)
        futures.add(executor.submit(() -> {
          ready.countDown();
          go.await();
          for (int n = 0; n < 25; n++) work.run();
          return true;
        }));
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      go.countDown();
      int success = 0;
      for (var future : futures) if (future.get(30, TimeUnit.SECONDS)) success++;
      assertEquals(8, success);
    }
  }

  static ByteVector ipls() {
    var fields = new ByteVector((byte) 0);
    for (String s : List.of("Guitar", "Artist 1", "Drums", "Artist 2", "Producer", "Artist 3", "Engineer", "Artist 4")) {
      if (fields.size() > 1) fields.append((byte) 0);
      fields.append(new ByteVector(s));
    }
    var frame = new ByteVector("IPLS").append(ByteVector.fromUnsignedInt(fields.size())).append(new ByteVector(2)).append(fields);
    return new ByteVector("ID3").append((byte) 3).append(new ByteVector(2)).append(SynchData.fromUnsignedInt(frame.size())).append(frame);
  }

  @Test
  void testConcurrentLazyInitialization() throws Exception {
    // Upstream runs this suite in a separate executable. Isolate all library
    // classes here so earlier const-map lookups cannot alter its initial state.
    var location = TagLib.class.getProtectionDomain().getCodeSource().getLocation();
    try (var loader = new URLClassLoader(new URL[]{location}, ClassLoader.getPlatformClassLoader())) {
      Class<?> byteType = loader.loadClass("cz.skyba.taglib.toolkit.ByteVector");
      Class<?> streamType = loader.loadClass("cz.skyba.taglib.toolkit.ByteVectorStream");
      Class<?> ioType = loader.loadClass("cz.skyba.taglib.toolkit.IOStream");
      Class<?> optionsType = loader.loadClass("cz.skyba.taglib.ReadOptions");
      Object tagsOnly = optionsType.getField("TAGS_ONLY").get(null);
      Class<?> mpegType = loader.loadClass("cz.skyba.taglib.mpeg.MpegFile");
      Class<?> textType = loader.loadClass("cz.skyba.taglib.id3.v2.TextIdentificationFrame");
      byte[] raw = ipls().toByteArray();
      concurrent(() -> {
        try {
          assertEquals(5, ((Map<?, ?>) textType.getMethod("involvedPeopleMap").invoke(null)).size());
        } catch (ReflectiveOperationException e) {
          throw new AssertionError(e);
        }
      });
      concurrent(() -> {
        try {
          Object data = byteType.getConstructor(byte[].class).newInstance((Object) raw);
          Object stream = streamType.getConstructor(byteType).newInstance(data);
          try (var file = (AutoCloseable) mpegType.getConstructor(ioType, optionsType).newInstance(stream, tagsOnly)) {
            assertTrue((Boolean) mpegType.getMethod("hasId3v2Tag").invoke(file));
            Object tag = mpegType.getMethod("id3v2Tag").invoke(file);
            Object id = byteType.getConstructor(String.class).newInstance("TMCL");
            assertEquals(1, ((List<?>) tag.getClass().getMethod("frameList", byteType).invoke(tag, id)).size());
          }
        } catch (Exception e) {
          throw new AssertionError(e);
        }
      });
      byte[] mp4 = Files.readAllBytes(FileReferenceResolversAndSavingTest.fixture("has-tags.m4a"));
      assertTrue(mp4.length > 0);
      Class<?> mp4Type = loader.loadClass("cz.skyba.taglib.mp4.Mp4File");
      concurrent(() -> {
        try {
          Object data = byteType.getConstructor(byte[].class).newInstance((Object) mp4);
          Object stream = streamType.getConstructor(byteType).newInstance(data);
          try (var file = (AutoCloseable) mp4Type.getConstructor(ioType).newInstance(stream)) {
            assertTrue((Boolean) mp4Type.getMethod("isValid").invoke(file));
            Object tag = mp4Type.getMethod("tag").invoke(file);
            Object props = tag.getClass().getMethod("properties").invoke(tag);
            assertFalse((Boolean) props.getClass().getMethod("isEmpty").invoke(props));
          }
        } catch (Exception e) {
          throw new AssertionError(e);
        }
      });
      Class<?> propertyType = loader.loadClass("cz.skyba.taglib.PropertyMap");
      Object builder = propertyType.getMethod("builder").invoke(null);
      builder.getClass().getMethod("put", String.class, String.class).invoke(builder, "TITLE", "Title");
      builder.getClass().getMethod("put", String.class, String.class).invoke(builder, "ARTIST", "Artist");
      Object properties = builder.getClass().getMethod("build").invoke(builder);
      for (String name : List.of("cz.skyba.taglib.riff.info.Tag", "cz.skyba.taglib.asf.Tag")) {
        Class<?> type = loader.loadClass(name);
        concurrent(() -> {
          try {
            Object tag = type.getConstructor().newInstance();
            Object update = type.getMethod("replaceProperties", propertyType).invoke(tag, properties);
            Object rejected = update.getClass().getMethod("rejected").invoke(update);
            assertTrue((Boolean) propertyType.getMethod("isEmpty").invoke(rejected));
          } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
          }
        });
      }
      Object data = byteType.getConstructor(byte[].class).newInstance((Object) raw);
      Object stream = streamType.getConstructor(byteType).newInstance(data);
      try (var file = (AutoCloseable) mpegType.getConstructor(ioType, optionsType).newInstance(stream, tagsOnly)) {
        assertTrue((Boolean) mpegType.getMethod("hasId3v2Tag").invoke(file));
        Object tag = mpegType.getMethod("id3v2Tag").invoke(file);
        Object id = byteType.getConstructor(String.class).newInstance("TMCL");
        assertEquals(1, ((List<?>) tag.getClass().getMethod("frameList", byteType).invoke(tag, id)).size());
      }
    }
  }
}
