package cz.skyba.taglib.dsdiff;

import cz.skyba.taglib.*;
import cz.skyba.taglib.id3.v2.*;
import cz.skyba.taglib.id3.v2.Tag;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.FileStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class DsdiffFileTest {
  @TempDir
  Path directory;

  static ByteVector chunk(String id, ByteVector data) {
    var bytes = new ByteVector(id).append(ByteVector.fromUnsignedLong(data.size(), true)).append(data);
    if ((data.size() & 1) != 0) bytes.append((byte) 0);
    return bytes;
  }

  static ByteVector form(ByteVector chunks) {
    return new ByteVector("FRM8").append(ByteVector.fromUnsignedLong(4L + chunks.size(), true)).append(new ByteVector("DSD ")).append(chunks);
  }

  static ByteVector audio() {
    return form(chunk("FVER", new ByteVector(4)).append(chunk("PROP", new ByteVector("SND ")
        .append(chunk("FS  ", ByteVector.fromUnsignedInt(2822400, true)))
        .append(chunk("CHNL", ByteVector.fromShort((short) 2, true)))))
      .append(chunk("DSD ", new ByteVector(128))));
  }

  static Path fixture() {
    return AudioFixtures.fixture("empty10ms.dff");
  }

  Path copy(String name) throws Exception {
    Path path = directory.resolve(name + ".dff");
    Files.copy(fixture(), path);
    return path;
  }

  @Test
  void nativeProperties() throws IOException {
    try (var f = new DsdiffFile(fixture())) {
      var p = f.detailedProperties();
      assertNotNull(p);
      assertEquals(0, p.lengthInSeconds());
      assertEquals(10, p.lengthInMilliseconds());
      assertEquals(5644, p.bitrate());
      assertEquals(2, p.channels());
      assertEquals(2822400, p.sampleRate());
      assertEquals(1, p.bitsPerSample());
      assertEquals(28224, p.sampleCount());
    }
  }

  @Test
  void nativeTags() throws Exception {
    var path = copy("tags");
    try (var f = new DsdiffFile(path)) {
      assertEquals("", f.tag().artist());
      f.tag().setArtist("The Artist");
      f.save();
    }
    try (var f = new DsdiffFile(path)) {
      assertEquals("The Artist", f.tag().artist());
    }
  }

  @Test
  void nativeSaveId3() throws Exception {
    var path = copy("id3");
    try (var f = new DsdiffFile(path)) {
      assertFalse(f.hasId3v2Tag());
      f.tag().setTitle("TitleXXX");
      f.save();
      assertTrue(f.hasId3v2Tag());
    }
    try (var f = new DsdiffFile(path)) {
      assertTrue(f.hasId3v2Tag());
      assertEquals("TitleXXX", f.tag().title());
      f.tag().setTitle("");
      f.save();
      assertFalse(f.hasId3v2Tag());
    }
    try (var f = new DsdiffFile(path)) {
      assertFalse(f.hasId3v2Tag());
      f.getOrCreateId3v2Tag().setTitle("TitleXXX");
      f.save();
      assertTrue(f.hasId3v2Tag());
    }
    try (var f = new DsdiffFile(path)) {
      assertTrue(f.hasId3v2Tag());
      assertEquals("TitleXXX", f.tag().title());
    }
  }

  @Test
  void nativeSaveVersion3() throws Exception {
    var path = copy("v3");
    String title = "X".repeat(254);
    try (var f = new DsdiffFile(path)) {
      assertFalse(f.hasId3v2Tag());
      f.tag().setTitle(title);
      f.tag().setArtist("Artist A");
      f.save(new DsdiffSaveOptions(Set.of(DsdiffFile.TagType.values()), DsdiffFile.StripTags.STRIP_OTHERS, Tag.Version.V3));
      assertTrue(f.hasId3v2Tag());
    }
    try (var f = new DsdiffFile(path)) {
      assertEquals(3, f.id3v2Tag().header().majorVersion());
      assertEquals("Artist A", f.tag().artist());
      assertEquals(title, f.tag().title());
    }
  }

  @Test
  void nativeStripAllThreeScenarios() throws Exception {
    for (int scenario = 0; scenario < 3; scenario++) {
      var path = copy("strip" + scenario);
      try (var f = new DsdiffFile(path)) {
        if (scenario == 0) {
          f.getOrCreateId3v2Tag().setArtist("X");
          f.getOrCreateDiinTag().setArtist("Y");
        } else {
          if (scenario == 1) {
            f.getOrCreateId3v2Tag();
            f.getOrCreateDiinTag();
          }
          f.tag().setArtist("X");
        }
        f.save();
      }
      try (var f = new DsdiffFile(path)) {
        assertTrue(f.hasId3v2Tag());
        assertTrue(f.hasDiinTag());
        f.strip(scenario == 0 ? Set.of(DsdiffFile.TagType.values()) : scenario == 1 ? Set.of(DsdiffFile.TagType.ID3V2) : Set.of(DsdiffFile.TagType.DIIN));
      }
      try (var f = new DsdiffFile(path)) {
        assertEquals(scenario == 2, f.hasId3v2Tag());
        assertEquals(scenario == 1, f.hasDiinTag());
      }
    }
  }

  @Test
  void nativeRepeatedSaveAndExactRestoration() throws Exception {
    var path = copy("repeat");
    try (var f = new DsdiffFile(path)) {
      assertEquals(7186, f.length());
      assertEquals("", f.tag().title());
      f.tag().setTitle("NEW TITLE");
      f.save();
      assertEquals("NEW TITLE", f.tag().title());
      f.tag().setTitle("NEW TITLE 2");
      f.save();
      assertEquals("NEW TITLE 2", f.tag().title());
      assertEquals(8292, f.length());
      f.save();
      assertEquals(8292, f.length());
    }
    try (var f = new DsdiffFile(path)) {
      assertEquals("NEW TITLE 2", f.tag().title());
      f.strip();
      assertEquals(7186, f.length());
    }
    assertArrayEquals(Files.readAllBytes(fixture()), Files.readAllBytes(path));
  }

  @Test
  void nativeId3InPropFullMapAndExactRestoration() throws Exception {
    var path = copy("prop");
    try (var s = new FileStream(path)) {
      s.insert(new ByteVector(new byte[]{0x28}), 0x0b, 1);
      s.insert(new ByteVector(new byte[]{0x6c}), 0x2b, 1);
      s.insert(new ByteVector(new byte[]{'I', 'D', '3', ' ', 0, 0, 0, 0, 0, 0, 0, 0x16, 'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0x0c, 'T', 'I', 'T', '2', 0, 0, 0, 2, 0, 0, 0, 'X'}), 0x76, 0);
    }
    var props = PropertyMap.builder().put("ALBUM", "Album").put("ALBUMARTIST", "Album Artist").put("ARTIST", "Artist").put("COMMENT", "Comment").put("DATE", "2023-10-20").put("DISCNUMBER", "1/2").put("GENRE", "Genre").put("TITLE", "Title").put("TRACKNUMBER", "2/3").build();
    try (var f = new DsdiffFile(path)) {
      assertTrue(f.hasId3v2Tag());
      assertEquals("X", f.tag().title());
      assertTrue(f.replaceProperties(props).rejected().isEmpty());
      f.getOrCreateDiinTag().setArtist("DIINArtist");
      f.save();
    }
    try (var f = new DsdiffFile(path)) {
      var tag = f.id3v2Tag();
      assertNotNull(tag);
      assertEquals("Title", tag.title());
      assertEquals("Artist", tag.artist());
      assertEquals("Album", tag.album());
      assertEquals(props, f.properties());
      assertEquals("DIINArtist", f.diinTag().artist());
      f.strip();
    }
    assertArrayEquals(Files.readAllBytes(fixture()), Files.readAllBytes(path));
  }

  @Test
  void emptyModernSaveCreatesThenRemovesAChunkWithFalseCachedPresence() throws IOException {
    var s = new ByteVectorStream(audio());
    long original = s.length();
    try (var f = new DsdiffFile(s)) {
      assertNotNull(f.id3v2Tag());
      assertNotNull(f.diinTag());
      assertFalse(f.hasId3v2Tag());
      f.save();
      assertFalse(f.hasId3v2Tag());
      assertEquals(original + 12, s.length());
      try (var reopened = new DsdiffFile(new ByteVectorStream(s.data()))) {
        assertTrue(reopened.hasId3v2Tag());
      }
      f.save();
      assertEquals(original, s.length());
      f.save();
      assertEquals(original + 12, s.length());
    }
  }

  @Test
  void clearingDiinPayloadsRetainsItsPhysicalContainer() throws IOException {
    var s = new ByteVectorStream(audio());
    try (var f = new DsdiffFile(s)) {
      f.diinTag().setTitle("DIIN only");
      f.save(new DsdiffSaveOptions(Set.of(DsdiffFile.TagType.DIIN), DsdiffFile.StripTags.STRIP_OTHERS, Tag.Version.V4));
      assertTrue(f.hasDiinTag());
      assertFalse(f.hasId3v2Tag());
      f.diinTag().setTitle("");
      f.save(new DsdiffSaveOptions(Set.of(DsdiffFile.TagType.DIIN), DsdiffFile.StripTags.STRIP_OTHERS, Tag.Version.V4));
      assertTrue(f.hasDiinTag());
      try (var reopened = new DsdiffFile(new ByteVectorStream(s.data()))) {
        assertTrue(reopened.hasDiinTag());
        assertTrue(reopened.tag().isEmpty());
      }
      f.strip(Set.of(DsdiffFile.TagType.DIIN));
      assertFalse(f.hasDiinTag());
    }
  }

  @Test
  void physicalStripIsImmediateAndReplacesOnlySelectedChildren() throws IOException {
    var s = new ByteVectorStream(audio());
    try (var f = new DsdiffFile(s)) {
      f.tag().setTitle("both");
      f.save();
      var modern = f.id3v2Tag();
      var diin = f.diinTag();
      var union = f.tag();
      var p = f.detailedProperties();
      f.strip(Set.of(DsdiffFile.TagType.ID3V2));
      assertNotSame(modern, f.id3v2Tag());
      assertSame(diin, f.diinTag());
      assertSame(union, f.tag());
      assertSame(p, f.detailedProperties());
      try (var reopened = new DsdiffFile(new ByteVectorStream(s.data()))) {
        assertFalse(reopened.hasId3v2Tag());
        assertTrue(reopened.hasDiinTag());
        assertEquals("both", reopened.tag().title());
      }
    }
  }

  @Test
  void rawInvalidIdsThrowOnOpen() throws IOException {
    var modern = new Tag();
    modern.setTitle("earlier");
    var data = audio().append(chunk("ID3 ", modern.render())).append(new ByteVector(new byte[]{0, 'B', 'A', 'D'})).append(new ByteVector(8));
    var stream = new ByteVectorStream(data);
    assertThrows(TagLibException.class, () -> new DsdiffFile(stream));
    assertTrue(stream.isOpen());
  }

  @Test
  void unsupportedFramesRemainLiveUntilExplicitRemovalThroughFile() throws IOException {
    var s = new ByteVectorStream(audio());
    try (var f = new DsdiffFile(s)) {
      var opaque = new UnknownFrame(new ByteVector(new byte[]{'Z', 'Z', 'Z', 'Z', 0, 0, 0, 3, 0, 0, 'x', 0, 'y'}));
      f.id3v2Tag().addFrame(opaque);
      f.diinTag().setTitle("DIIN independent");
      f.replaceProperties(PropertyMap.builder().put("TITLE", "modern").build());
      assertSame(opaque, f.id3v2Tag().frameList(new ByteVector("ZZZZ")).getFirst());
      assertEquals("DIIN independent", f.diinTag().title());
      f.save();
      assertTrue(s.data().find(new ByteVector("ZZZZ")) >= 0);
      f.removeUnsupportedProperties(List.of("UNKNOWN/ZZZZ"));
      assertTrue(f.id3v2Tag().frameList(new ByteVector("ZZZZ")).isEmpty());
      assertEquals("DIIN independent", f.diinTag().title());
      f.save();
    }
  }

  @Test
  void noPropertyReadStillBuildsMetadataChildrenAndRetainsStyleIndependentFields() throws IOException {
    for (var style : ReadStyle.values())
      try (var f = new DsdiffFile(new ByteVectorStream(audio()), new ReadOptions(false, style, ReadOptions.DEFAULT.maxMetadataBytes()))) {
        assertTrue(f.isValid());
        assertNull(f.detailedProperties());
        assertNotNull(f.id3v2Tag());
        assertNotNull(f.diinTag());
      }
  }

  @Test
  void unknownDiinAndMediaBytesSurviveStrip() throws IOException {
    var data = audio().append(chunk("DIIN", chunk("ZZZZ", new ByteVector("opaque"))));
    var s = new ByteVectorStream(data);
    try (var f = new DsdiffFile(s)) {
      assertTrue(f.hasDiinTag());
      f.strip(Set.of(DsdiffFile.TagType.DIIN));
      assertFalse(f.hasDiinTag());
      assertEquals(data, s.data());
      try (var reopened = new DsdiffFile(new ByteVectorStream(s.data()))) {
        assertTrue(reopened.hasDiinTag());
      }
    }
  }

  @Test
  void factoryRemainsAttachedToReplacementModernChild() throws IOException {
    var c = new FrameFactory() {
    };
    c.setDefaultTextEncoding(UTF_8);
    try (var f = new DsdiffFile(new ByteVectorStream(audio()), ReadOptions.DEFAULT, c)) {
      f.strip(Set.of(DsdiffFile.TagType.ID3V2));
      f.id3v2Tag().setTitle("factory");
      assertEquals(UTF_8, ((TextIdentificationFrame) f.id3v2Tag().frameList().getFirst()).textEncoding());
    }
  }

  @Test
  void fileRefRetainsActualLiveFileAndBorrowedStream() throws Exception {
    class Named extends ByteVectorStream {
      Named() {
        super(audio());
      }

      public String name() {
        return "sample.dff";
      }
    }
    var s = new Named();
    try (var ref = new AudioFile(s)) {
      var f = assertInstanceOf(DsdiffFile.class, ref.file());
      assertSame(f.tag(), ref.tag());
      assertSame(f.audioProperties(), ref.audioProperties());
      f.id3v2Tag().setTitle("live");
      assertEquals("live", ref.tag().title());
      assertDoesNotThrow(ref::save);
      assertTrue(f.hasId3v2Tag());
    }
    assertTrue(s.isOpen());
  }

  @Test
  void readonlyGuardPrecedesRenderButStripStillMutatesMemory() throws IOException {
    class Readonly extends ByteVectorStream {
      Readonly(ByteVector d) {
        super(d);
      }

      public boolean readOnly() {
        return true;
      }
    }
    var modern = new Tag();
    modern.setTitle("original");
    var s = new Readonly(audio().append(chunk("ID3 ", modern.render())));
    var before = new ByteVector(s.data());
    try (var f = new DsdiffFile(s)) {
      f.id3v2Tag().setTitle("edit");
      assertThrows(TagLibException.class, () -> f.save(new DsdiffSaveOptions(Set.of(DsdiffFile.TagType.values()), DsdiffFile.StripTags.STRIP_OTHERS, Tag.Version.V3)));
      assertEquals(4, f.id3v2Tag().header().majorVersion());
      assertEquals(before, s.data());
      f.strip(Set.of(DsdiffFile.TagType.ID3V2));
      assertFalse(f.hasId3v2Tag());
      assertNotEquals(before, s.data());
    }
  }

  @Test
  void rendererThrowsAfterExcludedDiinWasPhysicallyStripped() throws IOException {
    class Throwing extends Frame {
      Throwing() {
        super(new ByteVector("X001"));
      }

      public String toString() {
        return "";
      }

      protected void parseFields(ByteVector d) {
      }

      protected ByteVector renderFields() {
        throw new IllegalStateException("hook");
      }
    }
    var s = new ByteVectorStream(audio());
    try (var f = new DsdiffFile(s)) {
      f.tag().setTitle("both");
      f.save();
      f.id3v2Tag().addFrame(new Throwing());
      var before = new ByteVector(s.data());
      assertThrows(IllegalStateException.class, () -> f.save(new DsdiffSaveOptions(Set.of(DsdiffFile.TagType.ID3V2), DsdiffFile.StripTags.STRIP_OTHERS, Tag.Version.V4)));
      assertFalse(f.hasDiinTag());
      assertNotEquals(before, s.data());
      try (var reopened = new DsdiffFile(new ByteVectorStream(s.data()))) {
        assertFalse(reopened.hasDiinTag());
        assertTrue(reopened.hasId3v2Tag());
      }
    }
  }

  @Test
  void propertyImportUsesPrivateUnionEvenWhenTagGetterIsOverridden() throws IOException {
    class Custom extends DsdiffFile {
      Custom() throws IOException {
        super(new ByteVectorStream(audio()));
      }

      public TagUnion tag() {
        return new TagUnion();
      }
    }
    try (var f = new Custom()) {
      f.replaceProperties(PropertyMap.builder().put("TITLE", "stored").build());
      assertEquals(List.of("stored"), f.properties().get("TITLE"));
      assertEquals("", f.tag().title());
    }
  }

  @Test
  void unknownExtensionRequiresBothMarkersBeforeConstructingAFile() throws Exception {
    class Named extends ByteVectorStream {
      Named(ByteVector d) {
        super(d);
      }

      public String name() {
        return "sample.unknown";
      }
    }
    for (var data : List.of(new ByteVector("FRM8"), new ByteVector(audio()))) {
      if (data.size() > 12) System.arraycopy(new ByteVector("JUNK").data(), 0, data.data(), 12, 4);
      var s = new Named(data);
      assertThrows(TagLibException.class, () -> new AudioFile(s));
      assertEquals(0, s.tell());
      assertTrue(s.isOpen());
    }
  }

  @Test
  void recordBudgetBoundsAnIndexOfEmptyChunks() {
    var chunks = chunk("PROP", new ByteVector("SND "));
    for (int i = 0; i < 20; i++) chunks.append(chunk("ZERO", new ByteVector()));
    assertThrows(TagLibException.class, () -> new DsdiffFile(new ByteVectorStream(form(chunks)), new ReadOptions(true, ReadStyle.AVERAGE, 1024)));
  }

  @Test
  void nativeUndefinedFirstRootRemovalIsRejectedBeforeWriting() throws IOException {
    var s = new ByteVectorStream(form(chunk("ID3 ", new ByteVector()).append(chunk("PROP", new ByteVector("SND "))).append(chunk("DSD ", new ByteVector(2)))));
    var before = new ByteVector(s.data());
    try (var f = new DsdiffFile(s)) {
      assertTrue(f.isValid());
      assertThrows(IllegalStateException.class, () -> f.strip(Set.of(DsdiffFile.TagType.ID3V2)));
      assertEquals(before, s.data());
      assertTrue(f.hasId3v2Tag());
    }
  }

  @Test
  void nativeUndefinedStaleParentSaveIsRejectedBeforeWriting() throws IOException {
    var s = new ByteVectorStream(audio().append(chunk("DIIN", new ByteVector())));
    try (var f = new DsdiffFile(s)) {
      f.strip(Set.of(DsdiffFile.TagType.DIIN));
      f.diinTag().setTitle("x");
      var before = new ByteVector(s.data());
      assertThrows(TagLibException.class, () -> f.save(new DsdiffSaveOptions(Set.of(DsdiffFile.TagType.DIIN), DsdiffFile.StripTags.STRIP_NONE, Tag.Version.V4)));
      assertEquals(before, s.data());
    }
  }

  @Test
  void growthAfterExternalTruncationRejectsCompletePlanBeforeHeaderWrite() throws IOException {
    var modern = new Tag();
    modern.setTitle("original");
    var s = new ByteVectorStream(audio().append(chunk("ID3 ", modern.render())));
    try (var f = new DsdiffFile(s)) {
      s.truncate(0);
      f.id3v2Tag().setTitle("x".repeat(5000));
      assertThrows(TagLibException.class, () -> f.save(new DsdiffSaveOptions(Set.of(DsdiffFile.TagType.ID3V2), DsdiffFile.StripTags.STRIP_NONE, Tag.Version.V4)));
      assertEquals(0, s.length());
    }
  }
}
