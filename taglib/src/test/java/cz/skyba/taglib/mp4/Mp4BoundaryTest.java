package cz.skyba.taglib.mp4;

import cz.skyba.taglib.*;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Mp4BoundaryTest {
  @TempDir
  Path directory;

  static class ExposedAtom extends Atom {
    ExposedAtom(FileBase f, int depth) {
      super(f, depth);
    }

    ExposedAtom(FileBase f, int depth, int[] count) {
      super(f, depth, count);
    }
  }

  @Test
  void atomPathsKeepIdentityAndPartialChildPaths() throws Exception {
    var f = new Mp4File(Mp4MutableTest.stream("no-tags.m4a"), ReadOptions.TAGS_ONLY);
    var a = new Atoms(f);
    var movie = a.find("moov");
    assertSame(movie, a.path("moov").getFirst());
    assertTrue(a.path("moov", "absent").isEmpty());
    var path = new ArrayList<Atom>();
    assertFalse(movie.path(path, "trak", "absent"));
    assertEquals(2, path.size());
    assertSame(movie, path.getFirst());
    assertSame(movie.find("trak"), path.getLast());
    var stco = movie.findall("stco", true).getFirst();
    assertSame(stco, a.find("moov", "trak", "mdia", "minf").find("stbl", "stco"));
    long offset = stco.offset();
    stco.addToOffset(7);
    assertEquals(offset + 7, stco.offset());
    assertEquals(new ByteVector("stco"), stco.name());
    stco.name().clear();
    assertEquals(new ByteVector("stco"), stco.name());
    assertThrows(UnsupportedOperationException.class, () -> movie.children().clear());
    f.seek(0);
    var child = new Atom(f);
    movie.prependChild(child);
    assertSame(child, movie.children().getFirst());
    assertTrue(movie.removeChild(child));
    assertFalse(movie.removeChild(child));
  }

  @Test
  void protectedAtomDepthAndSharedCounterMatchNativeCutoff() throws Exception {
    var f = new Mp4File(Mp4MutableTest.stream("no-tags.m4a"), ReadOptions.TAGS_ONLY);
    f.seek(0);
    var a = new ExposedAtom(f, 65);
    assertEquals(new ByteVector("ftyp"), a.name());
    assertEquals(24, a.length());
    var count = new int[]{50000};
    f.seek(0);
    a = new ExposedAtom(f, 0, count);
    assertEquals(0, a.length());
    assertEquals(50001, count[0]);
    assertEquals(f.length(), f.tell());
  }

  @Test
  void userCreatedAtomCyclesHaveBoundedJavaOutcomes() throws Exception {
    var f = new Mp4File(Mp4MutableTest.stream("no-tags.m4a"), ReadOptions.TAGS_ONLY);
    var a = new Atoms(f);
    var movie = a.find("moov");
    movie.prependChild(movie);
    assertThrows(IllegalStateException.class, a::checkRootLevelAtoms);
    assertThrows(IllegalStateException.class, () -> movie.findall("trak", true));
  }

  @Test
  void neroTruncatesCountsAndUtf8BytesAndKeepsSignedTimes() throws Exception {
    var s = Mp4MutableTest.stream("no-tags.m4a");
    var f = new Mp4File(s, ReadOptions.TAGS_ONLY);
    var v = new ArrayList<Chapter>();
    for (int i = 0; i < 260; i++) v.add(new Chapter("x".repeat(300), i == 0 ? -1 : i));
    f.setNeroChapters(v);
    f.save();
    var chapters = new Mp4File(s, ReadOptions.TAGS_ONLY).neroChapters();
    assertEquals(255, chapters.size());
    assertEquals(-1, chapters.getFirst().startTime());
    assertEquals("x".repeat(255), chapters.getFirst().title());
    assertEquals(254, chapters.getLast().startTime());
  }

  @Test
  void neroTimeOverflowRejectsBeforeWritingPendingTagChanges() throws Exception {
    for (long time : new long[]{Long.MAX_VALUE, Long.MIN_VALUE}) {
      var stream = Mp4MutableTest.stream("no-tags.m4a");
      byte[] before = stream.data().toByteArray();
      try (var file = new Mp4File(stream, ReadOptions.TAGS_ONLY)) {
        file.tag().setTitle("pending");
        file.setNeroChapters(List.of(new Chapter("overflow", time)));
        assertThrows(ArithmeticException.class, file::save);
        assertArrayEquals(before, stream.data().toByteArray());
        assertEquals("pending", file.tag().title());
        assertEquals(time, file.neroChapters().getFirst().startTime());
      }
    }
  }

  @Test
  void factoryVirtualHooksAndCachesDriveOwnerOperations() throws Exception {
    class Factory extends ItemFactory {
      int parse, render, from, to, handlers, properties;

      @Override
      public ParsedItem parseItem(Atom a, ByteVector b) {
        parse++;
        return super.parseItem(a, b);
      }

      @Override
      public ByteVector renderItem(String n, Item i) {
        render++;
        return super.renderItem(n, i);
      }

      @Override
      public PropertyItem itemFromProperty(String n, List<String> v) {
        from++;
        return super.itemFromProperty(n, v);
      }

      @Override
      public PropertyValue itemToProperty(ByteVector n, Item i) {
        to++;
        return super.itemToProperty(n, i);
      }

      @Override
      protected Map<ByteVector, ItemHandlerType> nameHandlerMap() {
        handlers++;
        return super.nameHandlerMap();
      }

      @Override
      protected Map<ByteVector, String> namePropertyMap() {
        properties++;
        return super.namePropertyMap();
      }
    }
    var factory = new Factory();
    var s = Mp4MutableTest.stream("has-tags.m4a");
    var f = new Mp4File(s, ReadOptions.DEFAULT, factory);
    assertTrue(factory.parse > 0);
    f.properties();
    assertTrue(factory.to > 0);
    f.replaceProperties(PropertyMap.builder().put("TITLE", "hook").build());
    assertTrue(factory.from > 0);
    f.save();
    assertTrue(factory.render > 0);
    assertEquals(1, factory.handlers);
    assertEquals(1, factory.properties);
    assertEquals("hook", new Mp4File(s, ReadOptions.DEFAULT, factory).tag().title());
  }

  @Test
  void quickTimeSaveExplainsMissingMovieDurationAndRetainsPendingChapters() throws Exception {
    var stream = Mp4MutableTest.stream("no-tags.m4a");
    try (var file = new Mp4File(stream, ReadOptions.TAGS_ONLY)) {
      var header = new Atoms(file).find("moov", "mvhd");
      file.seek(header.offset() + 24);
      stream.writeBlock(ByteVector.fromUnsignedInt(0));
      var pending = List.of(new Chapter("Pending", 0));
      file.setQtChapters(pending);
      var failure = assertThrows(TagLibException.class, file::save);
      assertEquals("MP4 QuickTime chapters require a positive movie duration", failure.getMessage());
      assertEquals(pending, file.qtChapters());
    }
  }

  @Test
  void failedChapterSaveRetainsPendingChangesForRetry() throws Exception {
    class ReadOnlyStream extends ByteVectorStream {
      boolean locked = true;

      ReadOnlyStream(byte[] bytes) {
        super(bytes);
      }

      @Override
      public boolean readOnly() {
        return locked;
      }
    }
    var pending = List.of(new Chapter("Pending", 0));
    byte[] before = Files.readAllBytes(UpstreamAudioFixtures.fixture("no-tags.m4a"));
    for (boolean qt : new boolean[]{false, true}) {
      var stream = new ReadOnlyStream(before);
      try (var file = new Mp4File(stream, ReadOptions.TAGS_ONLY)) {
        Mp4MutableTest.set(file, qt, pending);
        assertThrows(TagLibException.class, file::save);
        assertEquals(pending, Mp4MutableTest.get(file, qt));
        assertArrayEquals(before, stream.data().toByteArray());
        stream.locked = false;
        file.save();
      }
      try (var reopened = new Mp4File(stream, ReadOptions.TAGS_ONLY)) {
        assertEquals(pending, Mp4MutableTest.get(reopened, qt));
      }
    }
  }

  @Test
  void pathSaveUsesSameOwnerAndPreservesOriginalOnCallbackFailure() throws Exception {
    Path path = directory.resolve("input.m4a");
    Files.copy(UpstreamAudioFixtures.fixture("no-tags.m4a"), path);
    byte[] before = Files.readAllBytes(path);
    try (var f = new Mp4File(path, ReadOptions.TAGS_ONLY)) {
      var tag = f.tag();
      f.tag().setTitle("saved");
      f.save();
      assertSame(tag, f.tag());
      assertEquals("saved", new Mp4File(path).tag().title());
    }
    Files.write(path, before);
    class Reject extends ItemFactory {
      @Override
      public ByteVector renderItem(String n, Item i) {
        throw new IllegalStateException("callback");
      }
    }
    try (var f = new Mp4File(path, new ReadOptions(false, ReadStyle.AVERAGE, 100000), new Reject())) {
      f.tag().setTitle("pending");
      assertThrows(IllegalStateException.class, f::save);
      assertArrayEquals(before, Files.readAllBytes(path));
      assertTrue(f.isValid());
      assertEquals("pending", f.tag().title());
    }
  }

  @Test
  void borrowedFileCloseLeavesStreamOpenAndReadonlyRetainsEdits() throws Exception {
    class ReadOnly extends ByteVectorStream {
      ReadOnly(byte[] v) {
        super(v);
      }

      @Override
      public boolean readOnly() {
        return true;
      }
    }
    var bytes = Files.readAllBytes(UpstreamAudioFixtures.fixture("no-tags.m4a"));
    var s = new ReadOnly(bytes);
    try (var f = new Mp4File(s, ReadOptions.TAGS_ONLY)) {
      assertNull(f.audioProperties());
      assertNull(f.detailedProperties());
      var tag = f.tag();
      tag.setTitle("pending");
      assertThrows(TagLibException.class, f::save);
      assertThrows(TagLibException.class, f::strip);
      assertSame(tag, f.tag());
      assertEquals("pending", tag.title());
      assertArrayEquals(bytes, s.data().toByteArray());
    }
    assertTrue(s.isOpen());
  }
}
