package cz.skyba.taglib.matroska;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.TagLib;
import cz.skyba.taglib.internal.MatroskaWire;
import cz.skyba.taglib.internal.MetadataLimitException;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.IOStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MatroskaBufferedReadTest {
  @TempDir
  Path directory;
  private static final long INFO = 0x1549a966L, TRACKS = 0x1654ae6bL, TAGS = 0x1254c367L;

  private static byte[] element(long id, byte[] bytes) {
    return MatroskaWire.render(id, bytes);
  }

  private static byte[] join(byte[]... bytes) {
    return MatroskaWire.join(bytes);
  }

  private static byte[] text(long id, String text) {
    return element(id, text.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] tags(String title) {
    return element(TAGS, element(0x7373, element(0x67c8, join(text(0x45a3, "TITLE"), text(0x4487, title)))));
  }

  private static byte[] seek(long id, long at) {
    return element(0x4dbb, join(element(0x53ab, MatroskaWire.id(id)), element(0x53ac, MatroskaWire.uint(at))));
  }

  private static byte[] fixture() {
    byte[] info = element(INFO, join(text(0x7ba9, "segment title"), element(0x2ad7b1, MatroskaWire.uint(1000000)),
      element(0x4489, ByteVector.fromFloat64BE(2000).data())));
    byte[] tracks = element(TRACKS, element(0xae, join(text(0x86, "A_PCM/INT/LIT"),
      element(0xe1, join(element(0xb5, ByteVector.fromFloat64BE(48000).data()),
        element(0x9f, MatroskaWire.uint(2)), element(0x6264, MatroskaWire.uint(16)))))));
    byte[] cluster = element(0x1f43b675L, new byte[6000]);
    byte[] head = new byte[0];
    for (int i = 0; i < 5; i++) {
      head = element(0x114d9b74L, join(seek(INFO, head.length), seek(TRACKS, head.length + info.length),
        seek(TAGS, head.length + info.length + tracks.length + cluster.length)));
    }
    return join(element(0x1a45dfa3L, join(text(0x4282, "matroska"), element(0x4287, new byte[]{4}))),
      element(0x18538067L, join(head, info, tracks, cluster, tags("selected title"), tags("unreferenced title"))));
  }

  private static final class Memory extends ByteVectorStream {
    Memory(byte[] bytes) {
      super(bytes);
    }

    @Override
    public String name() {
      return "input.mka";
    }
  }

  @Test
  void bufferedOwnedReadsMatchBorrowedSnapshotsAcrossSeekWindows() throws Exception {
    byte[] bytes = fixture();
    Path path = directory.resolve("input.mka");
    Files.write(path, bytes);
    for (var options : List.of(ReadOptions.DEFAULT, ReadOptions.TAGS_ONLY)) {
      try (var memory = new Memory(bytes)) {
        assertEquals(TagLib.read(memory, options), TagLib.read(path, options));
      }
    }
    assertEquals("selected title", TagLib.read(path).tag().title());
    assertEquals(48000, TagLib.read(path).audioProperties().sampleRate());
  }

  @Test
  void constructionCacheDoesNotSurviveLaterSaves() throws Exception {
    Path path = directory.resolve("input.mka");
    Files.write(path, fixture());
    try (var file = new MatroskaFile(path)) {
      file.replaceProperties(PropertyMap.builder().put("TITLE", "updated title").build());
      file.save();
      assertEquals("updated title", TagLib.read(path).tag().title());
      file.replaceProperties(PropertyMap.builder().put("TITLE", "second update").build());
      file.save();
      assertEquals("second update", TagLib.read(path).tag().title());
    }
  }

  @Test
  void aggregateMetadataLimitPropagatesForOwnedAndBorrowedFiles() throws Exception {
    byte[] bytes = join(element(0x1a45dfa3L, new byte[0]), element(0x18538067L, tags("x".repeat(2048))));
    var options = new ReadOptions(false, ReadStyle.AVERAGE, 1024);
    Path path = directory.resolve("large-tags.mka");
    Files.write(path, bytes);
    var ownedFailure = assertThrows(MetadataLimitException.class, () -> new MatroskaFile(path, options));
    assertEquals("Aggregate Matroska metadata exceeds allocation limit", ownedFailure.getMessage());
    try (var memory = new Memory(bytes)) {
      var borrowedFailure = assertThrows(MetadataLimitException.class, () -> new MatroskaFile(memory, options));
      assertEquals(ownedFailure.getMessage(), borrowedFailure.getMessage());
      assertTrue(memory.isOpen());
    }
  }

  @Test
  void individualMetadataLimitPropagatesFromHeaderParsing() throws Exception {
    byte[] bytes = join(element(0x1a45dfa3L, text(0x4282, "x".repeat(2048))), element(0x18538067L, new byte[0]));
    try (var memory = new Memory(bytes)) {
      var failure = assertThrows(MetadataLimitException.class,
        () -> new MatroskaFile(memory, new ReadOptions(true, ReadStyle.AVERAGE, 1024)));
      assertEquals("EBML metadata exceeds allocation limit", failure.getMessage());
      assertTrue(memory.isOpen());
    }
  }

  @Test
  void malformedInputThrowsWithoutClosingBorrowedStream() throws Exception {
    try (var memory = new Memory(new byte[]{0})) {
      assertThrows(java.io.UncheckedIOException.class, () -> new MatroskaFile(memory));
      assertTrue(memory.isOpen());
    }
  }

  private static final ThreadLocal<List<String>> READS = ThreadLocal.withInitial(ArrayList::new);

  private static final class CustomFile extends MatroskaFile {
    CustomFile(Path path) throws Exception {
      super(path);
    }

    CustomFile(IOStream stream) throws Exception {
      super(stream);
    }

    @Override
    public ByteVector readBlock(long count) {
      READS.get().add(tell() + ":" + count);
      return super.readBlock(count);
    }
  }

  @Test
  void overriddenOwnerReadersRetainBorrowedCallbackSequence() throws Exception {
    Path path = directory.resolve("input.mka");
    byte[] bytes = fixture();
    Files.write(path, bytes);
    try {
      READS.get().clear();
      try (var file = new CustomFile(path)) {
        assertTrue(file.isValid());
      }
      var owned = List.copyOf(READS.get());
      READS.get().clear();
      try (var memory = new Memory(bytes); var file = new CustomFile(memory)) {
        assertTrue(file.isValid());
      }
      assertEquals(owned, READS.get());
    } finally {
      READS.remove();
    }
  }
}
