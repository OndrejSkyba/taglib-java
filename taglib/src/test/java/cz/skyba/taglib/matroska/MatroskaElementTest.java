package cz.skyba.taglib.matroska;

import cz.skyba.taglib.AudioFile;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.internal.MatroskaWire;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static cz.skyba.taglib.AudioFixtures.fixture;
import static org.junit.jupiter.api.Assertions.*;

class MatroskaElementTest {
  @TempDir
  Path directory;

  static class E extends Element {
    ByteVector rendered = new ByteVector();

    E(long id) {
      super(id);
    }

    @Override
    protected ByteVector renderInternal() {
      return rendered;
    }
  }

  static byte[] input() throws Exception {
    return Files.readAllBytes(fixture("no-tags.mka"));
  }

  @Test
  void listenerFailureRetainsNativePartialRenderState() {
    var e = new E(1);
    e.setOffset(4);
    e.setSize(3);
    e.rendered = new ByteVector("abcdef");
    var before = new E(2);
    before.setOffset(7);
    e.addSizeListener(before);
    e.addSizeListener(new E(3) {
      @Override
      public boolean sizeChanged(Element c, long d) {
        return false;
      }
    });
    assertFalse(e.render());
    assertFalse(e.needsRender());
    assertTrue(e.data().isEmpty());
    assertEquals(10, before.offset());
    assertEquals(3, e.size());
    assertTrue(e.render());
    assertTrue(e.data().isEmpty());
  }

  @Test
  void listenersSeeMoveDestinationAndBoundCycles() {
    var e = new E(-1);
    e.setOffset(4);
    e.setSize(3);
    e.setAppendOffset(20);
    e.setIsLastElement(false);
    e.setWriteStyle(WriteStyle.AvoidInsert);
    e.rendered = new ByteVector("abcdef");
    var listener = new E(2);
    listener.setOffset(8);
    e.addSizeListener(listener);
    assertTrue(e.render());
    assertEquals(20, e.offset());
    assertEquals(8, listener.offset());
    assertTrue(e.wasMoved());
    assertEquals(0xffffffffL, e.id());
    var cyclic = new E(3) {
      @Override
      public boolean sizeChanged(Element caller, long delta) {
        return emitSizeChanged(delta);
      }
    };
    cyclic.addSizeListener(cyclic);
    assertThrows(IllegalStateException.class, () -> cyclic.emitSizeChanged(1));
    var huge = new E(4);
    huge.setOffset(Long.MAX_VALUE);
    assertThrows(ArithmeticException.class, () -> huge.adjustOffset(1));
  }

  @Test
  void wholeCachedMemoryPlanRejectsBeforeAnyInsert() throws Exception {
    class Count extends ByteVectorStream {
      int inserts;

      Count(byte[] bytes) {
        super(bytes);
      }

      @Override
      public void insert(ByteVector data, long offset, long replace) {
        inserts++;
        super.insert(data, offset, replace);
      }
    }
    var s = new Count(input());
    try (var f = new MatroskaFile(s, ReadOptions.TAGS_ONLY)) {
      f.getOrCreateChapters().addChapterEdition(new ChapterEdition(List.of(new Chapter(0, 1, List.of(), 1)), true, false, 2));
      f.tag().setTitle("First");
      f.save();
      s.data().resize(10);
      byte[] before = s.data().toByteArray();
      s.inserts = 0;
      f.tag().setTitle("A very long title".repeat(200));
      assertThrows(IllegalArgumentException.class, f::save);
      assertArrayEquals(before, s.data().toByteArray());
      assertEquals(0, s.inserts);
      assertEquals("A very long title".repeat(200), f.tag().title());
    }
  }

  @Test
  void fileRefUsesActualOwnerAndLazyBitrate() throws Exception {
    var s = new ByteVectorStream(input()) {
      @Override
      public String name() {
        return "fixture.mka";
      }
    };
    try (var ref = new AudioFile(s)) {
      assertInstanceOf(MatroskaFile.class, ref.file());
      s.data().resize(s.data().size() + 1000);
      assertEquals(s.data().size() * 8 / 444, ref.audioProperties().bitrate());
      ref.tag().setTitle("Live");
      assertSame(ref.tag(), ref.file().tag());
      assertDoesNotThrow(ref::save);
    }
    assertTrue(s.isOpen());
  }

  @Test
  void deferredBudgetPrecedesMutationAndRetainsState() throws Exception {
    var s = new ByteVectorStream(input());
    try (var f = new MatroskaFile(s, ReadOptions.TAGS_ONLY)) {
      f.getOrCreateAttachments().addAttachedFile(new AttachedFile(new byte[4096], "font.ttf", "font/ttf", 1));
      f.save();
    }
    s.seek(0);
    try (var f = new MatroskaFile(s, new ReadOptions(false, ReadStyle.AVERAGE, 1024))) {
      byte[] before = s.data().toByteArray();
      f.tag().setTitle("Edited");
      assertThrows(java.io.UncheckedIOException.class, f::save);
      assertArrayEquals(before, s.data().toByteArray());
      assertEquals("Edited", f.tag().title());
    }
  }

  @Test
  void ownedSaveUsesSameGraphAndBorrowedCloseKeepsStream() throws Exception {
    Path p = directory.resolve("stage.mka");
    Files.write(p, input());
    try (var f = new MatroskaFile(p, ReadOptions.TAGS_ONLY)) {
      var tag = f.tag();
      tag.setTitle("Saved");
      f.save();
      assertSame(tag, f.tag());
      assertEquals("Saved", f.tag().title());
    }
    try (var reopened = new MatroskaFile(p, ReadOptions.TAGS_ONLY)) {
      assertEquals("Saved", reopened.tag().title());
    }
    var s = new ByteVectorStream(input());
    try (var f = new MatroskaFile(s, ReadOptions.TAGS_ONLY)) {
      assertTrue(f.isValid());
    }
    assertTrue(s.isOpen());
  }

  @Test
  void sparseFilePaddingRejectsBeforeWriting() throws Exception {
    long padding = 40L * 1024 * 1024;
    byte[] header = MatroskaWire.render(0x1a45dfa3, new byte[0]);
    byte[] seek = MatroskaWire.render(0x114d9b74, new byte[0]);
    byte[] voidHeader = MatroskaWire.join(new byte[]{(byte) 0xec}, MatroskaWire.size(padding, 8));
    byte[] segmentHeader = MatroskaWire.join(MatroskaWire.id(0x18538067), MatroskaWire.size(seek.length + voidHeader.length + padding, 8));
    Path p = directory.resolve("sparse.mka");
    try (var sparse = new RandomAccessFile(p.toFile(), "rw")) {
      sparse.write(header);
      sparse.write(segmentHeader);
      sparse.write(seek);
      sparse.write(voidHeader);
      sparse.setLength(sparse.getFilePointer() + padding);
    }
    long length = Files.size(p);
    byte[] prefix;
    try (var in = Files.newInputStream(p)) {
      prefix = in.readNBytes(100);
    }
    try (var f = new MatroskaFile(p, new ReadOptions(false, ReadStyle.AVERAGE, 1024))) {
      assertTrue(f.isValid());
      f.tag().setTitle("Edited");
      assertThrows(IllegalArgumentException.class, f::save);
      assertEquals("Edited", f.tag().title());
    }
    assertEquals(length, Files.size(p));
    try (var in = Files.newInputStream(p)) {
      assertArrayEquals(prefix, in.readNBytes(100));
    }
  }

  @Test
  void missingFileThrowsOnOpen() throws Exception {
    assertThrows(java.io.UncheckedIOException.class, () -> new MatroskaFile(directory.resolve("missing.mka")));
  }
}
