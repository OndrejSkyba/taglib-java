package cz.skyba.taglib.trueaudio;

import cz.skyba.taglib.*;
import cz.skyba.taglib.id3.v2.*;
import cz.skyba.taglib.id3.v2.Tag;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.FileStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class TrueAudioFileTest {
  @TempDir
  Path directory;

  @AfterEach
  void reset() {
    Tag.setLatin1StringHandler(null);
  }

  static ByteVector audio() {
    return new ByteVector("TTA1").append(ByteVector.fromShort((short) 1, false)).append(ByteVector.fromShort((short) 2, false)).append(ByteVector.fromShort((short) 16, false)).append(ByteVector.fromUnsignedInt(44100, false)).append(ByteVector.fromUnsignedInt(44100, false)).append(new ByteVector("opaque audio"));
  }

  static ByteVector tagged() {
    var t = new Tag();
    t.setTitle("modern");
    var v1 = new cz.skyba.taglib.id3.v1.Tag();
    v1.setTitle("legacy");
    return t.render().append(audio()).append(v1.render());
  }

  static Path fixture(String name) {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("taglib/src"))) root = root.getParent();
    assertNotNull(root);
    Path p = UpstreamAudioFixtures.upstreamSource(root).resolve("tests/data/" + name);
    assertTrue(Files.isRegularFile(p));
    return p;
  }

  static void nativeProperties(TrueAudioFile f) {
    var p = f.detailedProperties();
    assertNotNull(p);
    assertEquals(3, p.lengthInSeconds());
    assertEquals(3685, p.lengthInMilliseconds());
    assertEquals(173, p.bitrate());
    assertEquals(2, p.channels());
    assertEquals(44100, p.sampleRate());
    assertEquals(16, p.bitsPerSample());
    assertEquals(162496, p.sampleFrames());
    assertEquals(1, p.ttaVersion());
  }

  @Test
  void nativeReadPropertiesWithoutID3v2() throws IOException {
    try (var f = new TrueAudioFile(fixture("empty.tta"))) {
      nativeProperties(f);
    }
  }

  @Test
  void nativeReadPropertiesWithTags() throws IOException {
    try (var f = new TrueAudioFile(fixture("tagged.tta"))) {
      nativeProperties(f);
    }
  }

  @Test
  void nativeStripAndProperties() throws Exception {
    Path copy = directory.resolve("strip.tta");
    Files.copy(fixture("empty.tta"), copy);
    try (var f = new TrueAudioFile(copy)) {
      f.getOrCreateId3v2Tag().setTitle("ID3v2");
      f.getOrCreateId3v1Tag().setTitle("ID3v1");
      f.save();
    }
    try (var f = new TrueAudioFile(copy)) {
      assertTrue(f.hasId3v1Tag());
      assertTrue(f.hasId3v2Tag());
      assertEquals("ID3v2", f.properties().get("TITLE").getFirst());
      f.removeTags(Set.of(TrueAudioFile.TagType.ID3V2));
      assertEquals("ID3v1", f.properties().get("TITLE").getFirst());
      f.removeTags(Set.of(TrueAudioFile.TagType.ID3V1));
      assertTrue(f.properties().isEmpty());
      f.save();
    }
    try (var f = new TrueAudioFile(copy)) {
      assertFalse(f.hasId3v1Tag());
      assertFalse(f.hasId3v2Tag());
      assertTrue(f.properties().get("TITLE").isEmpty());
      assertTrue(f.properties().isEmpty());
    }
  }

  @Test
  void nativeRepeatedSave() throws Exception {
    Path copy = directory.resolve("repeat.tta");
    Files.copy(fixture("empty.tta"), copy);
    try (var f = new TrueAudioFile(copy)) {
      assertFalse(f.hasId3v2Tag());
      assertFalse(f.hasId3v1Tag());
      f.getOrCreateId3v2Tag().setTitle("01234 56789 ABCDE FGHIJ");
      f.save();
      f.id3v2Tag().setTitle("0");
      f.save();
      f.getOrCreateId3v1Tag().setTitle("01234 56789 ABCDE FGHIJ");
      f.id3v2Tag().setTitle("01234 56789 ABCDE FGHIJ 01234 56789 ABCDE FGHIJ 01234 56789");
      f.save();
    }
    try (var f = new TrueAudioFile(copy)) {
      assertTrue(f.hasId3v2Tag());
      assertTrue(f.hasId3v1Tag());
    }
  }

  @Test
  void constructorFindersUseAbsoluteOffsetsAndRespectReadFlag() throws IOException {
    for (var style : ReadStyle.values())
      for (boolean enabled : new boolean[]{false, true}) {
        var s = new ByteVectorStream(tagged());
        s.seek(7);
        try (var f = new TrueAudioFile(s, new ReadOptions(enabled, style, ReadOptions.DEFAULT.maxMetadataBytes()))) {
          assertTrue(f.isValid());
          assertEquals("modern", f.id3v2Tag().title());
          assertEquals("legacy", f.id3v1Tag().title());
          assertEquals(enabled, f.detailedProperties() != null);
          if (enabled) {
            assertEquals(1000, f.detailedProperties().lengthInMilliseconds());
            assertEquals(18, f.tell() - f.id3v2Tag().header().completeTagSize());
          }
        }
      }
  }

  @Test
  void emptyAndForeignFilesRemainValid() throws IOException {
    for (var d : List.of(new ByteVector(), new ByteVector("foreign"))) {
      try (var f = new TrueAudioFile(new ByteVectorStream(d))) {
        assertTrue(f.isValid());
        assertNotNull(f.tag());
        assertNotNull(f.id3v2Tag());
        assertNotNull(f.detailedProperties());
        assertEquals(0, f.detailedProperties().sampleRate());
        f.save();
      }
    }
  }

  @Test
  void removeTagsChangesChildrenBeforeStorageAndRecreatesOnlyRequiredChild() throws IOException {
    var s = new ByteVectorStream(tagged());
    try (var f = new TrueAudioFile(s)) {
      var union = f.tag();
      var modern = f.id3v2Tag();
      var legacy = f.id3v1Tag();
      byte[] beforeRemoval = s.data().toByteArray();
      f.removeTags(Set.of());
      assertSame(modern, f.id3v2Tag());
      assertSame(legacy, f.id3v1Tag());
      f.removeTags(Set.of(TrueAudioFile.TagType.ID3V2));
      assertNull(f.id3v2Tag());
      assertSame(legacy, f.id3v1Tag());
      assertEquals("legacy", f.tag().title());
      assertTrue(f.hasId3v2Tag());
      f.removeTags(Set.of(TrueAudioFile.TagType.ID3V1));
      assertNull(f.id3v1Tag());
      assertNotNull(f.id3v2Tag());
      assertTrue(f.id3v2Tag().isEmpty());
      assertSame(union, f.tag());
      assertTrue(f.hasId3v1Tag());
      assertArrayEquals(beforeRemoval, s.data().toByteArray());
      f.save();
      assertFalse(f.hasId3v1Tag());
      assertFalse(f.hasId3v2Tag());
      assertEquals(audio(), s.data());
    }
  }

  @Test
  void liveFrameAndUnionIdentitySurviveGrowthShrinkAndTailShift() throws IOException {
    var s = new ByteVectorStream(tagged());
    try (var f = new TrueAudioFile(s)) {
      var union = f.tag();
      var t = f.id3v2Tag();
      var h = t.header();
      var p = f.detailedProperties();
      var frame = t.frameList().getFirst();
      frame.setText("x".repeat(5000));
      f.save();
      frame.setText("s");
      f.save();
      assertSame(union, f.tag());
      assertSame(t, f.id3v2Tag());
      assertSame(h, t.header());
      assertSame(frame, t.frameList().getFirst());
      assertSame(p, f.detailedProperties());
      s.seek(0);
      try (var reopen = new TrueAudioFile(s)) {
        assertEquals("s", reopen.tag().title());
        assertEquals("legacy", reopen.id3v1Tag().title());
      }
    }
  }

  @Test
  void propertiesUpdateExistingLegacyAndCreateModern() throws IOException {
    var legacy = new cz.skyba.taglib.id3.v1.Tag();
    legacy.setTitle("legacy");
    var s = new ByteVectorStream(audio().append(legacy.render()));
    try (var f = new TrueAudioFile(s)) {
      assertNull(f.id3v2Tag());
      f.replaceProperties(PropertyMap.builder().put("TITLE", List.of("first", "second")).put("ALBUMARTIST", "other").build());
      assertEquals("first", f.id3v1Tag().title());
      assertEquals(List.of("first", "second"), f.properties().get("TITLE"));
      f.save();
    }
  }

  @Test
  void commonSettersBroadcastToPresentChildren() throws IOException {
    try (var f = new TrueAudioFile(new ByteVectorStream(tagged()))) {
      f.tag().setArtist("both");
      assertEquals("both", f.id3v2Tag().artist());
      assertEquals("both", f.id3v1Tag().artist());
      f.removeTags(Set.of(TrueAudioFile.TagType.ID3V2));
      f.tag().setTitle("legacy only");
      assertNull(f.id3v2Tag());
      assertEquals("legacy only", f.id3v1Tag().title());
    }
  }

  @Test
  void legacyOnlySaveDoesNotInventPhysicalModernTag() throws IOException {
    var s = new ByteVectorStream(audio());
    try (var f = new TrueAudioFile(s)) {
      f.getOrCreateId3v1Tag().setTitle("legacy");
      f.removeTags(Set.of(TrueAudioFile.TagType.ID3V2));
      f.save();
      assertFalse(f.hasId3v2Tag());
      assertTrue(f.hasId3v1Tag());
      assertEquals(audio(), s.data().mid(0, audio().size()));
    }
  }

  static class Factory extends FrameFactory {
    int raw, aggregate;

    Factory() {
      setDefaultTextEncoding(UTF_8);
    }

    public Frame createFrame(ByteVector d, Header h) {
      raw++;
      return super.createFrame(d, h);
    }

    public void rebuildAggregateFrames(Tag t) {
      aggregate++;
      super.rebuildAggregateFrames(t);
    }
  }

  @Test
  void customFactoryReachesReadAndRecreatedChildren() throws IOException {
    var c = new Factory();
    try (var f = new TrueAudioFile(new ByteVectorStream(tagged()), new ReadOptions(false, ReadStyle.FAST, ReadOptions.DEFAULT.maxMetadataBytes()), c)) {
      assertEquals(1, c.raw);
      assertEquals(1, c.aggregate);
      f.removeTags(Set.of(TrueAudioFile.TagType.values()));
      f.id3v2Tag().setTitle("new");
      assertEquals(UTF_8, ((TextIdentificationFrame) f.id3v2Tag().frameList().getFirst()).textEncoding());
    }
  }

  @Test
  void nullFactoryUsesDefaultForNewId3Frames() throws IOException {
    var s = new ByteVectorStream(audio());
    try (var f = new TrueAudioFile(s, ReadOptions.TAGS_ONLY, null)) {
      assertTrue(f.isValid());
      assertTrue(f.id3v2Tag().isEmpty());
      f.save();
      f.id3v2Tag().setTitle("");
      assertEquals(audio(), s.data());
      f.id3v2Tag().setTitle("new");
      f.save();
    }
    try (var f = new TrueAudioFile(s)) {
      assertEquals("new", f.id3v2Tag().title());
    }
  }

  @Test
  void globalDecoderReachesExistingFrames() throws IOException {
    Tag.setLatin1StringHandler(new Latin1StringHandler() {
      public String parse(ByteVector d) {
        return "decoded";
      }
    });
    try (var f = new TrueAudioFile(new ByteVectorStream(tagged()))) {
      assertEquals("decoded", f.id3v2Tag().title());
    }
  }

  @Test
  void unknownFramesSurviveUntilExplicitRemoval() throws IOException {
    var s = new ByteVectorStream(audio());
    try (var f = new TrueAudioFile(s)) {
      var opaque = new UnknownFrame(new ByteVector(new byte[]{'Z', 'Z', 'Z', 'Z', 0, 0, 0, 1, 0, 0, 'x'}));
      f.id3v2Tag().addFrame(opaque);
      f.replaceProperties(PropertyMap.builder().put("TITLE", "known").build());
      assertSame(opaque, f.id3v2Tag().frameList(new ByteVector("ZZZZ")).getFirst());
      f.save();
      f.removeUnsupportedProperties(List.of("UNKNOWN/ZZZZ"));
      assertTrue(f.id3v2Tag().frameList(new ByteVector("ZZZZ")).isEmpty());
    }
  }

  @Test
  void fileRefUsesActualOwnerAndBorrowsStream() throws Exception {
    class Named extends ByteVectorStream {
      Named() {
        super(tagged());
      }

      public String name() {
        return "sample.tta";
      }
    }
    var s = new Named();
    try (var ref = new AudioFile(s, ReadOptions.TAGS_ONLY)) {
      var f = assertInstanceOf(TrueAudioFile.class, ref.file());
      assertSame(f.tag(), ref.tag());
      assertNull(ref.audioProperties());
      f.id3v2Tag().frameList().getFirst().setText("live");
      assertEquals("live", ref.tag().title());
      assertDoesNotThrow(ref::save);
    }
    assertTrue(s.isOpen());
  }

  @Test
  void readonlySaveLeavesBytesAndRenderStateUntouched() throws IOException {
    class Readonly extends ByteVectorStream {
      Readonly() {
        super(tagged());
      }

      public boolean readOnly() {
        return true;
      }
    }
    var s = new Readonly();
    var before = new ByteVector(s.data());
    try (var f = new TrueAudioFile(s)) {
      f.id3v2Tag().setTitle("changed");
      long oldSize = f.id3v2Tag().header().tagSize();
      assertThrows(TagLibException.class, f::save);
      assertEquals(oldSize, f.id3v2Tag().header().tagSize());
      assertEquals(before, s.data());
    }
  }

  @Test
  void throwingRenderMarksNewLocationBeforeHookButChangesNoBytes() throws IOException {
    class ThrowFrame extends Frame {
      ThrowFrame() {
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
    try (var f = new TrueAudioFile(s)) {
      assertFalse(f.hasId3v2Tag());
      f.id3v2Tag().addFrame(new ThrowFrame());
      assertThrows(IllegalStateException.class, f::save);
      assertTrue(f.hasId3v2Tag());
      assertEquals(audio(), s.data());
    }
  }

  @Test
  void truncatedId3MemoryGrowthRejectsBeforeWrite() throws Exception {
    var original = new ByteVector("ID3");
    var s = new ByteVectorStream(original);
    try (var f = new TrueAudioFile(s)) {
      f.id3v2Tag().setTitle("new");
      assertThrows(TagLibException.class, f::save);
      assertEquals(original, s.data());
      assertTrue(f.hasId3v2Tag());
    }
  }

  @Test
  void truncatedId3PathGrowthRetainsDefinedNativeAcceptance() throws Exception {
    Path p = directory.resolve("short.tta");
    Files.writeString(p, "ID3");
    try (var f = new TrueAudioFile(p)) {
      f.id3v2Tag().setTitle("new");
      f.save();
    }
    try (var f = new TrueAudioFile(p)) {
      assertEquals("new", f.tag().title());
    }
  }

  @Test
  void unknownExtensionDetectorSkipsLeadingId3AndRestoresInitialCursor() throws Exception {
    class Named extends ByteVectorStream {
      Named() {
        super(tagged());
      }

      public String name() {
        return "unknown.audio";
      }
    }
    var s = new Named();
    s.seek(7);
    try (var ref = new AudioFile(s, ReadOptions.TAGS_ONLY)) {
      var f = assertInstanceOf(TrueAudioFile.class, ref.file());
      assertEquals("modern", f.id3v2Tag().title());
      assertEquals("legacy", f.id3v1Tag().title());
      assertSame(f.tag(), ref.tag());
    }
  }

  @Test
  void customIoMutationDuringInsertReachesLaterLegacyBranch() throws IOException {
    class Callback extends ByteVectorStream {
      TrueAudioFile file;

      Callback() {
        super(audio());
      }

      public void insert(ByteVector d, long start, long replace) {
        super.insert(d, start, replace);
        file.getOrCreateId3v1Tag().setTitle("from IO callback");
      }
    }
    var s = new Callback();
    try (var f = new TrueAudioFile(s)) {
      s.file = f;
      f.id3v2Tag().setTitle("modern");
      f.save();
      assertTrue(f.hasId3v1Tag());
      s.seek(0);
      try (var reopen = new TrueAudioFile(s)) {
        assertEquals("from IO callback", reopen.id3v1Tag().title());
      }
    }
  }

  @Test
  void legacyAppendInvokesPublicTruncateAndPreservesNativeFailureSideEffects() throws IOException {
    class Failing extends ByteVectorStream {
      Failing() {
        super(audio());
      }

      public void truncate(long length) {
        throw new IllegalStateException("growth hook");
      }
    }
    var s = new Failing();
    try (var f = new TrueAudioFile(s)) {
      f.getOrCreateId3v1Tag().setTitle("legacy");
      assertThrows(IllegalStateException.class, f::save);
      assertTrue(f.hasId3v1Tag());
      assertFalse(f.hasId3v2Tag());
      assertEquals(audio(), s.data());
    }
  }

  static class CustomFrame extends Frame {
    long value;

    CustomFrame(long value) {
      super(new ByteVector("CUST"));
      this.value = value;
    }

    CustomFrame(ByteVector d, Frame.Header h) {
      super(h);
      value = fieldData(d).toUnsignedInt();
    }

    public String toString() {
      return Long.toString(value);
    }

    protected PropertyMap asProperties() {
      return PropertyMap.builder().put("CUSTOM", toString()).build();
    }

    protected void parseFields(ByteVector d) {
      value = d.toUnsignedInt();
    }

    protected ByteVector renderFields() {
      return ByteVector.fromUnsignedInt(value);
    }
  }

  static class CustomFactory extends FrameFactory {
    protected Frame createFrame(ByteVector d, Frame.Header h, Header t) {
      return h.frameID().equals(new ByteVector("CUST")) ? new CustomFrame(d, h) : super.createFrame(d, h, t);
    }

    public Frame createFrameForProperty(String k, List<String> v) {
      return k.equals("CUSTOM") ? new CustomFrame(v.isEmpty() ? 0 : Long.parseLong(v.getFirst())) : super.createFrameForProperty(k, v);
    }
  }

  @Test
  void nativeCustomFrameFactory() throws Exception {
    Path copy = directory.resolve("custom.tta");
    Files.copy(fixture("empty.tta"), copy);
    var factory = new CustomFactory();
    try (var f = new TrueAudioFile(copy)) {
      assertTrue(f.isValid());
      var t = f.getOrCreateId3v2Tag();
      for (var x : List.copyOf(t.frameList())) t.removeFrame(x);
      t.setArtist("An artist");
      t.setTitle("A title");
      f.save();
    }
    try (var f = new TrueAudioFile(copy)) {
      assertTrue(f.isValid());
      assertTrue(f.hasId3v2Tag());
      f.getOrCreateId3v2Tag().addFrame(new CustomFrame(1234567890));
      f.save();
    }
    try (var f = new TrueAudioFile(copy)) {
      assertTrue(f.isValid());
      assertTrue(f.hasId3v2Tag());
      var frames = f.getOrCreateId3v2Tag().frameList(new ByteVector("CUST"));
      assertFalse(frames.isEmpty());
      assertFalse(frames.getFirst() instanceof CustomFrame);
    }
    try (var f = new TrueAudioFile(copy, ReadOptions.DEFAULT, factory)) {
      assertTrue(f.isValid());
      assertTrue(f.hasId3v2Tag());
      var t = f.getOrCreateId3v2Tag();
      var frames = t.frameList(new ByteVector("CUST"));
      assertFalse(frames.isEmpty());
      assertEquals(1234567890, assertInstanceOf(CustomFrame.class, frames.getFirst()).value);
      assertEquals(List.of("1234567890"), t.properties().get("CUSTOM"));
      assertEquals(List.of("An artist"), t.properties().get("ARTIST"));
      assertEquals(List.of("A title"), t.properties().get("TITLE"));
      f.removeTags();
      f.save();
    }
    try (var f = new TrueAudioFile(copy, ReadOptions.DEFAULT, factory)) {
      assertTrue(f.isValid());
      assertFalse(f.hasId3v2Tag());
      var t = f.getOrCreateId3v2Tag();
      assertTrue(t.properties().isEmpty());
      t.replaceProperties(PropertyMap.builder().put("CUSTOM", "305419896").build());
      f.save();
    }
    try (var f = new TrueAudioFile(copy, ReadOptions.DEFAULT, factory)) {
      assertTrue(f.isValid());
      assertTrue(f.hasId3v2Tag());
      var t = f.getOrCreateId3v2Tag();
      assertEquals(List.of("305419896"), t.properties().get("CUSTOM"));
      var frames = t.frameList(new ByteVector("CUST"));
      assertFalse(frames.isEmpty());
      assertEquals(0x12345678, assertInstanceOf(CustomFrame.class, frames.getFirst()).value);
    }
  }

  static void common(TagBase t, boolean second) {
    t.setArtist(second ? "ttest artist" : "test artist");
    t.setTitle(second ? "ytest title" : "test title");
    t.setGenre(second ? "uTest!" : "Test!");
    t.setAlbum(second ? "ialbummmm" : "albummmm");
    t.setComment(second ? "another comment" : "a comment");
    t.setTrack(second ? 7 : 5);
    t.setYear(second ? 2080 : 2020);
  }

  static void assertCommon(AudioFile f, boolean second) {
    assertTrue(f.isOpen());
    var t = f.tag();
    assertEquals(second ? "ttest artist" : "test artist", t.artist());
    assertEquals(second ? "ytest title" : "test title", t.title());
    assertEquals(second ? "uTest!" : "Test!", t.genre());
    assertEquals(second ? "ialbummmm" : "albummmm", t.album());
    assertEquals(second ? "another comment" : "a comment", t.comment());
    assertEquals(second ? 7 : 5, t.track());
    assertEquals(second ? 2080 : 2020, t.year());
  }

  @Test
  void nativeFileRefTrueAudio() throws Exception {
    Path copy = directory.resolve("ref.tta");
    Files.copy(fixture("empty.tta"), copy);
    try (var f = new AudioFile(copy)) {
      assertInstanceOf(TrueAudioFile.class, f.file());
      assertTrue(f.isOpen());
      common(f.tag(), false);
      assertDoesNotThrow(f::save);
    }
    try (var f = new AudioFile(copy)) {
      assertCommon(f, false);
      common(f.tag(), true);
      assertDoesNotThrow(f::save);
    }
    try (var f = new AudioFile(copy)) {
      assertCommon(f, true);
    }
    try (var stream = new FileStream(copy); var f = new AudioFile(stream)) {
      assertInstanceOf(TrueAudioFile.class, f.file());
      assertCommon(f, true);
      common(f.tag(), false);
      assertDoesNotThrow(f::save);
    }
    ByteVector content;
    try (var stream = new FileStream(copy); var f = new AudioFile(stream)) {
      assertInstanceOf(TrueAudioFile.class, f.file());
      assertCommon(f, false);
      stream.seek(0);
      content = stream.readBlock(stream.length());
    }
    var stream = new ByteVectorStream(content);
    try (var f = new AudioFile(stream)) {
      assertInstanceOf(TrueAudioFile.class, f.file());
      assertCommon(f, false);
      common(f.tag(), true);
      assertDoesNotThrow(f::save);
      content = new ByteVector(stream.data());
    }
    try (var f = new AudioFile(new ByteVectorStream(content))) {
      assertInstanceOf(TrueAudioFile.class, f.file());
      assertCommon(f, true);
    }
  }

}
