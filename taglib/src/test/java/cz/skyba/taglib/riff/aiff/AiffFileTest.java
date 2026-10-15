package cz.skyba.taglib.riff.aiff;

import cz.skyba.taglib.*;
import cz.skyba.taglib.id3.v2.*;
import cz.skyba.taglib.id3.v2.Tag;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class AiffFileTest {
  @TempDir
  Path directory;

  static Path fixture(String name) {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("taglib/src"))) root = root.getParent();
    assertNotNull(root);
    var path = UpstreamAudioFixtures.upstreamSource(root).resolve("tests/data/" + name);
    assertTrue(Files.isRegularFile(path));
    return path;
  }

  Path copy(String name) throws Exception {
    var target = directory.resolve(name);
    Files.copy(fixture(name), target);
    return target;
  }

  static ByteVector chunk(String id, ByteVector data) {
    return new ByteVector(id).append(ByteVector.fromUnsignedInt(data.size(), true)).append(data).append(new ByteVector(data.size() % 2));
  }

  static ByteVector form(ByteVector... chunks) {
    var body = new ByteVector("AIFF");
    for (var c : chunks) body.append(c);
    return new ByteVector("FORM").append(ByteVector.fromUnsignedInt(body.size(), true)).append(body);
  }

  static ByteVector title(String value) {
    var tag = new Tag();
    tag.setTitle(value);
    return tag.render();
  }

  @Test
  void testAiffProperties() throws IOException {
    try (var f = new AiffFile(fixture("empty.aiff"))) {
      var p = f.detailedProperties();
      assertNotNull(p);
      assertSame(p.common(), f.audioProperties());
      assertEquals(0, p.lengthInSeconds());
      assertEquals(67, p.lengthInMilliseconds());
      assertEquals(706, p.bitrate());
      assertEquals(44100, p.sampleRate());
      assertEquals(1, p.channels());
      assertEquals(16, p.bitsPerSample());
      assertEquals(2941, p.sampleFrames());
      assertFalse(p.isAiffC());
    }
  }

  @Test
  void testAiffCProperties() throws IOException {
    try (var f = new AiffFile(fixture("alaw.aifc"))) {
      var p = f.detailedProperties();
      assertNotNull(p);
      assertEquals(0, p.lengthInSeconds());
      assertEquals(37, p.lengthInMilliseconds());
      assertEquals(355, p.bitrate());
      assertEquals(44100, p.sampleRate());
      assertEquals(1, p.channels());
      assertEquals(16, p.bitsPerSample());
      assertEquals(1622, p.sampleFrames());
      assertTrue(p.isAiffC());
      assertEquals(new ByteVector("ALAW"), p.compressionType());
      assertEquals("SGI CCITT G.711 A-law", p.compressionName());
    }
  }

  @Test
  void testSaveID3v2() throws Exception {
    var path = copy("empty.aiff");
    try (var f = new AiffFile(path)) {
      assertFalse(f.hasId3v2Tag());
      f.tag().setTitle("TitleXXX");
      f.save();
      assertTrue(f.hasId3v2Tag());
    }
    try (var f = new AiffFile(path)) {
      assertTrue(f.hasId3v2Tag());
      assertEquals("TitleXXX", f.tag().title());
      f.tag().setTitle("");
      f.save();
      assertFalse(f.hasId3v2Tag());
    }
    try (var f = new AiffFile(path)) {
      assertFalse(f.hasId3v2Tag());
    }
  }

  @Test
  void testSaveID3v23() throws Exception {
    var path = copy("empty.aiff");
    String text = "X".repeat(254);
    try (var f = new AiffFile(path)) {
      assertFalse(f.hasId3v2Tag());
      f.tag().setTitle(text);
      f.tag().setArtist("Artist A");
      f.save(Tag.Version.V3);
      assertTrue(f.hasId3v2Tag());
    }
    try (var f = new AiffFile(path)) {
      assertEquals(3, f.tag().header().majorVersion());
      assertEquals("Artist A", f.tag().artist());
      assertEquals(text, f.tag().title());
    }
  }

  @Test
  void testDuplicateID3v2() throws Exception {
    try (var f = new AiffFile(copy("duplicate_id3v2.aiff"))) {
      assertTrue(f.hasId3v2Tag());
      assertEquals("Title1", f.tag().title());
      f.save();
      assertEquals(7030, f.length());
      assertEquals(-1, f.find(new ByteVector("Title2")));
    }
  }

  @Test
  void testFuzzedFile1() throws IOException {
    try (var f = new AiffFile(fixture("segfault.aif"))) {
      assertTrue(f.isValid());
    }
  }

  @Test
  void testFuzzedFile2() throws IOException {
    try (var f = new AiffFile(fixture("excessive_alloc.aif"))) {
      assertTrue(f.isValid());
    }
  }

  @Test
  void fileRefUsesTheSameLiveOwnerAndRetainsOpaqueFramesPicturesAndMedia() throws Exception {
    var path = copy("empty.aiff");
    byte[] original = Files.readAllBytes(path);
    try (var ref = new AudioFile(path)) {
      var f = assertInstanceOf(AiffFile.class, ref.file());
      var tag = f.tag();
      var list = tag.frameList();
      tag.addFrame(new UnknownFrame(new ByteVector("ZZZZ").append(ByteVector.fromUnsignedInt(3, true)).append(new ByteVector(2)).append(new ByteVector("raw"))));
      var picture = new AttachedPictureFrame();
      picture.setMimeType("image/png");
      picture.setPicture(new ByteVector("picture"));
      tag.addFrame(picture);
      ref.tag().setTitle("shared title");
      assertEquals("shared title", tag.title());
      assertSame(tag, ref.tag());
      assertDoesNotThrow(ref::save);
      assertSame(tag, f.tag());
      assertSame(list, tag.frameList());
      assertDoesNotThrow(ref::save);
      assertEquals(1, tag.frameList(new ByteVector("ZZZZ")).size());
    }
    try (var f = new AiffFile(path)) {
      assertEquals("shared title", f.tag().title());
      assertEquals(1, f.tag().frameList(new ByteVector("ZZZZ")).size());
      assertEquals(new ByteVector("picture"), ((AttachedPictureFrame) f.tag().frameList(new ByteVector("APIC")).getFirst()).picture());
    }
    assertArrayEquals(Arrays.copyOfRange(original, 12, original.length), Arrays.copyOfRange(Files.readAllBytes(path), 12, original.length));
  }

  @Test
  void customFactoryIsRetainedAndNullUsesDefault() throws IOException {
    int[] calls = {0};
    var factory = new FrameFactory() {
      @Override
      public Frame createFrame(ByteVector data, Header header) {
        calls[0]++;
        return super.createFrame(data, header);
      }
    };
    var stream = new ByteVectorStream(form(chunk("COMM", new ByteVector(18)), chunk("id3 ", title("factory"))));
    try (var f = new AiffFile(stream, new ReadOptions(false, ReadStyle.FAST, ReadOptions.DEFAULT.maxMetadataBytes()), factory)) {
      assertEquals(1, calls[0]);
      assertEquals("factory", f.tag().title());
      assertNull(f.audioProperties());
      var tag = f.tag();
      f.save(Tag.Version.V3);
      assertSame(tag, f.tag());
    }
    assertTrue(stream.isOpen());
    stream.seek(0);
    try (var f = new AiffFile(stream, new ReadOptions(false, ReadStyle.ACCURATE, ReadOptions.DEFAULT.maxMetadataBytes()), null)) {
      assertEquals("factory", f.tag().title());
    }
  }

  @Test
  void constructorAndQuickSupportHaveDifferentAcceptanceAndRestoreProbeCursor() throws IOException {
    var stream = new ByteVectorStream(form(chunk("JUNK", new ByteVector("abc"))));
    stream.seek(7);
    assertTrue(AiffFile.isSupported(stream));
    assertEquals(7, stream.tell());
    stream.seek(0);
    stream.data().set(0, (byte) 'X');
    assertFalse(AiffFile.isSupported(stream));
    assertEquals(0, stream.tell());
    try (var f = new AiffFile(stream, ReadOptions.TAGS_ONLY)) {
      assertTrue(f.isValid());
      assertNotNull(f.tag());
    }
    assertFalse(AiffFile.isSupported(null));
  }

  @Test
  void lastTagRemovalLeavesNativeNoInsertionAnchorButPresenceStillChanges() throws IOException {
    var stream = new ByteVectorStream(form(chunk("ID3 ", title("first"))));
    try (var f = new AiffFile(stream, ReadOptions.TAGS_ONLY)) {
      var tag = f.tag();
      f.save();
      assertTrue(f.hasId3v2Tag());
      assertSame(tag, f.tag());
      assertEquals(12, stream.length());
      f.save();
      assertEquals(12, stream.length());
    }
  }

  @Test
  void lowerAndUpperDuplicatesAreRemovedBeforeRenderingAndFailureRetainsReachedState() throws IOException {
    var bytes = form(chunk("JUNK", new ByteVector("abc")), chunk("id3 ", title("first")), chunk("ID3 ", title("second")));
    var stream = new ByteVectorStream(bytes);
    try (var f = new AiffFile(stream, ReadOptions.TAGS_ONLY)) {
      assertEquals("first", f.tag().title());
      f.tag().addFrame(new TextIdentificationFrame(new ByteVector("TXXX"), UTF_8) {
        @Override
        public ByteVector renderFields() {
          throw new IllegalStateException("render failure");
        }
      });
      assertThrows(IllegalStateException.class, f::save);
      assertFalse(f.hasId3v2Tag());
      assertEquals(form(chunk("JUNK", new ByteVector("abc"))).size(), stream.length());
    }
  }

  @Test
  void externalMemoryTruncationRejectsBeforeChangingBytesOrPresence() throws Exception {
    var stream = new ByteVectorStream(Files.readAllBytes(fixture("duplicate_id3v2.aiff")));
    try (var f = new AiffFile(stream)) {
      stream.truncate(12);
      var before = stream.data().toByteArray();
      var tag = f.tag();
      assertThrows(TagLibException.class, f::save);
      assertArrayEquals(before, stream.data().toByteArray());
      assertTrue(f.hasId3v2Tag());
      assertSame(tag, f.tag());
      assertEquals("Title1", tag.title());
    }
  }

  @Test
  void metadataLimitRejectsBeforeOversizedChunkRead() {
    var stream = new ByteVectorStream(form(chunk("COMM", new ByteVector(2048)), chunk("SSND", new ByteVector(8)))) {
      @Override
      public ByteVector readBlock(long count) {
        assertTrue(count <= 1024);
        return super.readBlock(count);
      }
    };
    assertThrows(TagLibException.class, () -> new AiffFile(stream, new ReadOptions(true, ReadStyle.AVERAGE, 1024)));
  }

  @Test
  void emptyBorrowedFileRetainsNativeNoChunkSaveAndRenderSideEffects() throws IOException {
    var stream = new ByteVectorStream(new byte[0]);
    try (var f = new AiffFile(stream, ReadOptions.TAGS_ONLY)) {
      assertTrue(f.isValid());
      f.save();
      f.tag().setTitle("live");
      f.save(Tag.Version.V3);
      assertTrue(f.hasId3v2Tag());
      assertEquals(3, f.tag().header().majorVersion());
      assertEquals(0, stream.length());
      f.save();
      assertEquals(4, f.tag().header().majorVersion());
    }
  }

  @Test
  void unknownExtensionRequiresCompleteSupportedHeaderBeforeConstructingOwner() throws Exception {
    for (ByteVector bytes : List.of(new ByteVector("FORM123"), form(chunk("JUNK", new ByteVector("audio"))))) {
      if (bytes.size() >= 12) bytes.set(8, (byte) 'X');
      var stream = new ByteVectorStream(bytes) {
        @Override
        public String name() {
          return "sample.unknown";
        }
      };
      stream.seek(7);
      assertThrows(TagLibException.class, () -> new AudioFile(stream));
      assertEquals(7, stream.tell());
      assertTrue(stream.isOpen());
    }
  }

  @Test
  void readOnlyAndUnopenedFilesHaveNativeSaveResults() throws IOException {
    var stream = new ByteVectorStream(form(chunk("JUNK", new ByteVector("audio")))) {
      @Override
      public boolean readOnly() {
        return true;
      }
    };
    try (var f = new AiffFile(stream, ReadOptions.TAGS_ONLY)) {
      var before = stream.data().toByteArray();
      f.tag().setTitle("change");
      assertThrows(TagLibException.class, f::save);
      assertArrayEquals(before, stream.data().toByteArray());
      assertFalse(f.hasId3v2Tag());
    }
    assertThrows(java.io.UncheckedIOException.class, () -> new AiffFile(directory.resolve("missing.aiff")));
  }

  @Test
  void propertyForwardingUsesOwnedTagWhileSaveGuardUsesVirtualTag() throws IOException {
    var stream = new ByteVectorStream(form(chunk("JUNK", new ByteVector("audio")), chunk("ID3 ", title("original"))));
    var alternate = new Tag();
    class AlternateTagFile extends AiffFile {
      AlternateTagFile() throws IOException {
        super(stream, ReadOptions.TAGS_ONLY);
      }

      @Override
      public Tag tag() {
        return alternate;
      }

      Tag ownedTag() {
        return super.tag();
      }
    }
    try (var f = new AlternateTagFile()) {
      assertEquals("original", f.properties().get("TITLE").getFirst());
      f.replaceProperties(PropertyMap.builder().put("TITLE", "owned").build());
      assertEquals("owned", f.ownedTag().title());
      assertTrue(alternate.isEmpty());
      f.ownedTag().addFrame(new UnknownFrame(new ByteVector(new byte[]{'Z', 'Z', 'Z', 'Z', 0, 0, 0, 3, 0, 0, 'r', 'a', 'w'})));
      assertEquals(List.of("UNKNOWN/ZZZZ"), f.properties().unsupportedData());
      f.removeUnsupportedProperties(List.of("UNKNOWN/ZZZZ"));
      assertTrue(f.properties().unsupportedData().isEmpty());
      f.save();
      assertFalse(f.hasId3v2Tag());
      assertEquals("owned", f.ownedTag().title());
      alternate.setTitle("guard");
      f.save();
      assertTrue(f.hasId3v2Tag());
      try (var reopened = new AiffFile(new ByteVectorStream(stream.data()), ReadOptions.TAGS_ONLY)) {
        assertEquals("owned", reopened.tag().title());
      }
    }
  }
}
