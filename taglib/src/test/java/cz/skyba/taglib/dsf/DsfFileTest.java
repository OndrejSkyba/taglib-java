package cz.skyba.taglib.dsf;

import cz.skyba.taglib.*;
import cz.skyba.taglib.id3.v2.*;
import cz.skyba.taglib.id3.v2.Tag;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class DsfFileTest {
  @TempDir
  Path directory;

  @AfterEach
  void resetDecoder() {
    Tag.setLatin1StringHandler(null);
  }

  static ByteVector audio(int size) {
    var fmt = ByteVector.fromUnsignedInt(1, false).append(ByteVector.fromUnsignedInt(0, false)).append(ByteVector.fromUnsignedInt(2, false)).append(ByteVector.fromUnsignedInt(2, false)).append(ByteVector.fromUnsignedInt(2822400, false)).append(ByteVector.fromUnsignedInt(1, false)).append(ByteVector.fromLong(28224, false)).append(ByteVector.fromUnsignedInt(4096, false)).append(new ByteVector(4));
    return new ByteVector("DSD ").append(ByteVector.fromUnsignedLong(28, false)).append(ByteVector.fromUnsignedLong(92L + size, false)).append(new ByteVector(8)).append(new ByteVector("fmt ")).append(ByteVector.fromUnsignedLong(52, false)).append(fmt).append(new ByteVector("data")).append(ByteVector.fromUnsignedLong(12L + size, false)).append(new ByteVector(size));
  }

  static ByteVector tagged(String title) {
    var t = new Tag();
    t.setTitle(title);
    var d = audio(64);
    var bytes = t.render();
    System.arraycopy(ByteVector.fromUnsignedLong(d.size() + bytes.size(), false).data(), 0, d.data(), 12, 8);
    System.arraycopy(ByteVector.fromUnsignedLong(d.size(), false).data(), 0, d.data(), 20, 8);
    return d.append(bytes);
  }

  static Path fixture() {
    return AudioFixtures.fixture("empty10ms.dsf");
  }

  @Test
  void nativeBasic() throws IOException {
    try (var f = new DsfFile(fixture())) {
      var p = f.detailedProperties();
      assertNotNull(p);
      assertEquals(0, p.lengthInSeconds());
      assertEquals(10, p.lengthInMilliseconds());
      assertEquals(5645, p.bitrate());
      assertEquals(2, p.channels());
      assertEquals(2822400, p.sampleRate());
      assertEquals(1, p.formatVersion());
      assertEquals(0, p.formatID());
      assertEquals(2, p.channelType());
      assertEquals(1, p.bitsPerSample());
      assertEquals(28224, p.sampleCount());
      assertEquals(4096, p.blockSizePerChannel());
    }
  }

  @Test
  void nativeTags() throws Exception {
    Path copy = directory.resolve("tags.dsf");
    Files.copy(fixture(), copy);
    try (var f = new DsfFile(copy)) {
      assertTrue(f.properties().isEmpty());
      assertEquals("", f.tag().artist());
      f.tag().setArtist("The Artist");
      var p = f.properties().toBuilder().put("ALBUMARTIST", "Album Artist").build();
      f.replaceProperties(p);
      f.save();
    }
    try (var f = new DsfFile(copy)) {
      assertEquals("The Artist", f.tag().artist());
      assertEquals("The Artist", f.properties().get("ARTIST").getFirst());
      assertEquals("Album Artist", f.properties().get("ALBUMARTIST").getFirst());
      f.replaceProperties(PropertyMap.EMPTY);
      f.save();
    }
    assertArrayEquals(Files.readAllBytes(fixture()), Files.readAllBytes(copy));
  }

  @Test
  void ignoresReadPropertyFlagAndRetainsSameDetails() throws IOException {
    for (var style : ReadStyle.values()) {
      try (var f = new DsfFile(new ByteVectorStream(audio(64)), new ReadOptions(false, style, ReadOptions.DEFAULT.maxMetadataBytes()))) {
        assertNotNull(f.detailedProperties());
        assertEquals(10, f.detailedProperties().lengthInMilliseconds());
        assertEquals(92, f.tell());
        var p = f.detailedProperties();
        f.tag().setTitle("a");
        f.save();
        assertSame(p, f.detailedProperties());
      }
    }
  }

  @Test
  void supportUsesByteZeroWhileConstructorUsesCurrentCursor() throws IOException {
    var s = new ByteVectorStream(audio(64));
    s.seek(7);
    assertTrue(DsfFile.isSupported(s));
    assertEquals(7, s.tell());
    assertThrows(TagLibException.class, () -> new DsfFile(s));
    assertEquals(11, s.tell());
    assertTrue(s.isOpen());
    assertFalse(DsfFile.isSupported(null));
  }

  @Test
  void liveTagAndFrameIdentitySurviveVersionedRepeatedSaves() throws IOException {
    var s = new ByteVectorStream(tagged("original"));
    try (var f = new DsfFile(s)) {
      var t = f.tag();
      var h = t.header();
      var frame = t.frameList().getFirst();
      var list = t.frameList();
      frame.setText("changed");
      assertEquals("changed", f.tag().title());
      assertEquals(List.of("changed"), f.properties().get("TITLE"));
      f.save(Tag.Version.V3);
      assertSame(t, f.tag());
      assertSame(h, t.header());
      assertSame(frame, list.getFirst());
      assertEquals(3, h.majorVersion());
      f.save();
      assertEquals(4, h.majorVersion());
      assertSame(frame, list.getFirst());
      s.seek(0);
      try (var reopened = new DsfFile(s)) {
        assertEquals("changed", reopened.tag().title());
      }
    }
  }

  @Test
  void unknownFramesSurvivePropertyUpdatesAndExplicitRemoval() throws IOException {
    var s = new ByteVectorStream(tagged("a"));
    try (var f = new DsfFile(s)) {
      var opaque = new UnknownFrame(new ByteVector(new byte[]{'Z', 'Z', 'Z', 'Z', 0, 0, 0, 1, 0, 0, 'x'}));
      f.tag().addFrame(opaque);
      f.replaceProperties(PropertyMap.builder().put("TITLE", "b").build());
      assertSame(opaque, f.tag().frameList(new ByteVector("ZZZZ")).getFirst());
      f.save();
      assertTrue(s.data().find(new ByteVector("ZZZZ")) >= 0);
      f.removeUnsupportedProperties(List.of("UNKNOWN/ZZZZ"));
      assertTrue(f.tag().frameList(new ByteVector("ZZZZ")).isEmpty());
    }
  }

  @Test
  void trailingGarbageIsRetainedByInsertAndDiscardedByEmptySave() throws IOException {
    var original = audio(64);
    var s = new ByteVectorStream(new ByteVector(original).append(new ByteVector("trailing")));
    try (var f = new DsfFile(s)) {
      assertTrue(f.isValid());
      f.tag().setTitle("a");
      f.save();
      assertEquals(new ByteVector("trailing"), s.data().mid(s.data().size() - 8));
      for (var frame : List.copyOf(f.tag().frameList())) f.tag().removeFrame(frame);
      f.save();
      assertEquals(original, s.data());
    }
  }

  @Test
  void constructorDoesNotRequireCompleteFormatPayload() throws IOException {
    var s = new ByteVectorStream(audio(64).mid(0, 40));
    System.arraycopy(ByteVector.fromUnsignedLong(40, false).data(), 0, s.data().data(), 12, 8);
    try (var f = new DsfFile(s)) {
      assertTrue(f.isValid());
      assertNotNull(f.tag());
      assertNotNull(f.detailedProperties());
      assertEquals(0, f.detailedProperties().sampleRate());
      assertEquals(40, s.tell());
    }
  }

  @Test
  void unsignedHeaderExtentsRejectBeforeConstructingChildren() throws IOException {
    var data = audio(64);
    System.arraycopy(ByteVector.fromUnsignedLong(Long.MIN_VALUE, false).data(), 0, data.data(), 12, 8);
    var s = new ByteVectorStream(data);
    assertThrows(TagLibException.class, () -> new DsfFile(s));
    assertEquals(20, s.tell());
    assertTrue(s.isOpen());
  }

  @Test
  void absentTagKeepsNullFileParentAcrossSaves() throws IOException {
    var s = new ByteVectorStream(audio(200000));
    try (var f = new DsfFile(s)) {
      f.tag().setTitle("a");
      long rendered = f.tag().frameList().getFirst().render().size();
      f.tag().header().setTagSize(rendered + 1500);
      f.save();
      assertEquals(rendered + 1024, f.tag().header().tagSize());
      f.tag().header().setTagSize(rendered + 1500);
      f.save();
      assertEquals(rendered + 1024, f.tag().header().tagSize());
      s.seek(0);
      try (var reopened = new DsfFile(s)) {
        reopened.tag().header().setTagSize(rendered + 1500);
        reopened.save();
        assertEquals(rendered + 1500, reopened.tag().header().tagSize());
      }
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
  void suppliedFactoryDrivesReadAndLaterNewFrames() throws IOException {
    var c = new Factory();
    try (var f = new DsfFile(new ByteVectorStream(tagged("a")), new ReadOptions(false, ReadStyle.FAST, ReadOptions.DEFAULT.maxMetadataBytes()), c)) {
      assertEquals(1, c.raw);
      assertEquals(1, c.aggregate);
      assertEquals(UTF_8, ((TextIdentificationFrame) f.tag().frameList().getFirst()).textEncoding());
      f.tag().setArtist("b");
      assertEquals(UTF_8, ((TextIdentificationFrame) f.tag().frameList(new ByteVector("TPE1")).getFirst()).textEncoding());
    }
  }

  @Test
  void globalDecoderReachesExistingFileFrames() throws IOException {
    Tag.setLatin1StringHandler(new Latin1StringHandler() {
      public String parse(ByteVector d) {
        return "decoded";
      }
    });
    try (var f = new DsfFile(new ByteVectorStream(tagged("a")))) {
      assertEquals("decoded", f.tag().title());
    }
  }

  @Test
  void fileRefDispatchSharesActualLiveOwnerAndBorrowedStream() throws Exception {
    class Named extends ByteVectorStream {
      Named() {
        super(tagged("a"));
      }

      public String name() {
        return "sample.dsf";
      }
    }
    var s = new Named();
    try (var ref = new AudioFile(s, ReadOptions.TAGS_ONLY)) {
      var f = assertInstanceOf(DsfFile.class, ref.file());
      assertSame(f.tag(), ref.tag());
      assertSame(f.audioProperties(), ref.audioProperties());
      var frame = f.tag().frameList().getFirst();
      frame.setText("live");
      assertEquals("live", ref.tag().title());
      assertDoesNotThrow(ref::save);
      assertSame(frame, f.tag().frameList().getFirst());
    }
    assertTrue(s.isOpen());
  }

  @Test
  void readonlySaveLeavesBytesAndOwnerStateUntouched() throws IOException {
    class Readonly extends ByteVectorStream {
      Readonly() {
        super(tagged("a"));
      }

      public boolean readOnly() {
        return true;
      }
    }
    var s = new Readonly();
    var before = new ByteVector(s.data());
    try (var f = new DsfFile(s)) {
      f.tag().setTitle("b");
      var h = f.tag().header();
      long size = h.tagSize();
      assertThrows(TagLibException.class, () -> f.save(Tag.Version.V3));
      assertEquals(4, h.majorVersion());
      assertEquals(size, h.tagSize());
      assertEquals(before, s.data());
    }
  }

  @Test
  void configuredAllocationLimitFailsBeforeReadingTagBody() {
    var source = tagged("x".repeat(3000));
    assertThrows(TagLibException.class, () -> new DsfFile(new ByteVectorStream(source), new ReadOptions(true, ReadStyle.AVERAGE, 1024)));
  }

  @Test
  void throwingCustomRenderHookChangesNoFileBytes() throws IOException {
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
        throw new IllegalStateException("render hook");
      }
    }
    var original = audio(64);
    var stream = new ByteVectorStream(original);
    try (var f = new DsfFile(stream)) {
      var frame = new ThrowFrame();
      f.tag().addFrame(frame);
      assertThrows(IllegalStateException.class, () -> f.save(Tag.Version.V3));
      assertEquals(original, stream.data());
      assertEquals(3, frame.header().version());
    }
  }

  @Test
  void nonzeroConstructorOriginStillWritesNativeAbsoluteHeaderOffsets() throws IOException {
    var data = audio(64);
    long declared = data.size() + 7;
    System.arraycopy(ByteVector.fromUnsignedLong(declared, false).data(), 0, data.data(), 12, 8);
    var source = new ByteVectorStream(new ByteVector("prefix!").append(data));
    source.seek(7);
    try (var f = new DsfFile(source)) {
      assertTrue(f.isValid());
      f.tag().setTitle("a");
      f.save();
      assertEquals(source.length(), source.data().toUnsignedLong(12, false));
      assertEquals(declared, source.data().toUnsignedLong(20, false));
      assertEquals(new ByteVector("prefix!"), source.data().mid(0, 7));
    }
  }

}
