package cz.skyba.taglib.wavpack;

import cz.skyba.taglib.*;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.FileStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WavpackFileTest {
  @TempDir
  Path directory;

  static ByteVector block(long frames, long flags) {
    return new ByteVector("wvpk").append(ByteVector.fromUnsignedInt(24, false))
      .append(ByteVector.fromUnsignedShort(0x410, false)).append(new ByteVector(2))
      .append(ByteVector.fromUnsignedInt(frames, false)).append(ByteVector.fromUnsignedInt(0, false))
      .append(ByteVector.fromUnsignedInt(100, false)).append(ByteVector.fromUnsignedInt(flags, false))
      .append(new ByteVector(4));
  }

  static ByteVector audio() {
    return block(200, 0x1801 | (9L << 23));
  }

  static ByteVector tagged() {
    var ape = new cz.skyba.taglib.ape.Tag();
    ape.setTitle("primary");
    ape.setData("OPAQUE", new ByteVector("kept"));
    var v1 = new cz.skyba.taglib.id3.v1.Tag();
    v1.setTitle("legacy");
    v1.setArtist("fallback");
    return audio().append(ape.render()).append(v1.render());
  }

  @Test
  void propertiesBeginAtAbsoluteZeroAndRespectDisabledReads() throws IOException {
    var source = new ByteVectorStream(audio());
    source.seek(7);
    try (var file = new WavPackFile(source, new ReadOptions(false, ReadStyle.ACCURATE, ReadOptions.DEFAULT.maxMetadataBytes()))) {
      assertNull(file.audioProperties());
      assertNull(file.detailedProperties());
      file.seek(7);
      var p = new Properties(file, 32, ReadStyle.FAST);
      assertEquals(0x410, p.version());
      assertEquals(200, p.sampleFrames());
      assertEquals(44100, p.sampleRate());
      assertEquals(2, p.channels());
      assertEquals(16, p.bitsPerSample());
      assertTrue(p.isLossless());
      assertFalse(p.isDsd());
      assertEquals(5, p.lengthInMilliseconds());
      assertEquals(56, p.bitrate());
      assertEquals(32, file.tell());
    }
  }

  @Test
  void shortHeaderIsReadOnceAndNeverInvalidatesTheFile() throws IOException {
    var source = new ByteVectorStream(audio()) {
      @Override
      public ByteVector readBlock(long count) {
        return count == 32 ? super.readBlock(31) : super.readBlock(count);
      }
    };
    try (var file = new WavPackFile(source)) {
      assertTrue(file.isValid());
      assertEquals(0, file.detailedProperties().version());
      assertEquals(0, file.detailedProperties().channels());
      assertEquals(31, file.tell());
    }
  }

  @Test
  void invalidInitialVersionSurvivesWithoutAssigningRemainingFields() throws IOException {
    var bytes = audio();
    bytes.set(8, (byte) 1);
    bytes.set(9, (byte) 4);
    try (var file = new WavPackFile(new ByteVectorStream(bytes))) {
      assertTrue(file.isValid());
      var p = file.detailedProperties();
      assertEquals(0x401, p.version());
      assertEquals(0, p.sampleFrames());
      assertEquals(0, p.channels());
      assertFalse(p.isLossless());
    }
  }

  @Test
  void completeInitialFieldsSurviveLaterShortBlockAndChannelsAccumulate() throws IOException {
    var bytes = block(200, 0x801 | (9L << 23)).append(block(200, 0x1005 | (9L << 23)));
    try (var file = new WavPackFile(new ByteVectorStream(bytes))) {
      assertEquals(3, file.detailedProperties().channels());
    }
    try (var file = new WavPackFile(new ByteVectorStream(bytes.mid(0, 49)))) {
      assertEquals(2, file.detailedProperties().channels());
      assertEquals(200, file.detailedProperties().sampleFrames());
    }
  }

  @Test
  void unknownFramesUseReverseFinalIndexAndUnsignedAddition() throws IOException {
    var finalBlock = audio();
    System.arraycopy(ByteVector.fromUnsignedInt(0xfffffffEL, false).data(), 0, finalBlock.data(), 16, 4);
    try (var file = new WavPackFile(new ByteVectorStream(block(0xffffffffL, 0x1801 | (9L << 23)).append(finalBlock)))) {
      assertEquals(98, file.detailedProperties().sampleFrames());
      assertEquals(64, file.tell());
    }
  }

  @Test
  void metadataMustBeCompleteBeforeInitialVersionAssignment() throws IOException {
    var bytes = block(200, 0x1801 | (15L << 23));
    bytes.set(4, (byte) 32);
    bytes.append(new ByteVector(new byte[]{0x27, 2, (byte) 0xe8, 3, 0, 0}));
    try (var file = new WavPackFile(new ByteVectorStream(bytes))) {
      assertEquals(0, file.detailedProperties().version());
      assertEquals(0, file.detailedProperties().channels());
      assertEquals(38, file.tell());
    }
  }

  @Test
  void dsdShiftOverflowRetainsSignedSampleRateAndZeroDuration() throws IOException {
    var bytes = block(200, 0x80001800L | (9L << 23));
    bytes.set(4, (byte) 28);
    bytes.append(new ByteVector(new byte[]{0x0e, 1, 30, 0}));
    try (var file = new WavPackFile(new ByteVectorStream(bytes))) {
      assertEquals(0, file.detailedProperties().sampleRate());
      assertEquals(0, file.detailedProperties().lengthInMilliseconds());
      assertTrue(file.detailedProperties().isDsd());
      assertEquals(200, file.detailedProperties().sampleFrames());
    }
  }

  @Test
  void supportDoesNotSkipLeadingId3OrScanDisplacedMagicAndRestoresCursor() throws IOException {
    for (var bytes : List.of(new ByteVector("ID3\004\000\000\000\000\000\000").append(audio()), new ByteVector("X").append(audio()))) {
      var source = new ByteVectorStream(bytes);
      source.seek(7);
      assertFalse(WavPackFile.isSupported(source));
      assertEquals(7, source.tell());
      try (var file = new WavPackFile(source)) {
        assertTrue(file.isValid());
        assertEquals(0, file.detailedProperties().version());
      }
    }
    var source = new ByteVectorStream(audio());
    source.seek(7);
    assertTrue(WavPackFile.isSupported(source));
    assertEquals(7, source.tell());
    assertFalse(WavPackFile.isSupported(null));
  }

  @Test
  void childrenStayLiveAndPhysicalPresenceChangesOnlyOnSave() throws IOException {
    var source = new ByteVectorStream(tagged());
    try (var file = new WavPackFile(source)) {
      assertEquals("primary", file.tag().title());
      assertEquals("fallback", file.tag().artist());
      byte[] beforeRemoval = source.data().toByteArray();
      file.removeTags(Set.of(WavPackFile.TagType.APE));
      assertArrayEquals(beforeRemoval, source.data().toByteArray());
      assertNull(file.apeTag());
      assertTrue(file.hasApeTag());
      assertEquals("legacy", file.tag().title());
      file.save();
      assertFalse(file.hasApeTag());
      assertTrue(file.hasId3v1Tag());
      file.removeTags();
      assertNotNull(file.apeTag());
      assertNull(file.id3v1Tag());
      assertTrue(file.hasId3v1Tag());
      file.save();
      assertArrayEquals(audio().data(), source.data().data());
      file.apeTag().setTitle("created");
      file.getOrCreateId3v1Tag().setTitle("legacy");
      file.save();
      assertTrue(file.hasApeTag());
      assertTrue(file.hasId3v1Tag());
      byte[] once = source.data().toByteArray();
      file.save();
      assertArrayEquals(once, source.data().data());
    }
  }

  @Test
  void propertyImportPreservesOpaqueAndLegacyFirstValue() throws IOException {
    var source = new ByteVectorStream(tagged());
    try (var file = new WavPackFile(source)) {
      var rejected = file.replaceProperties(PropertyMap.builder().put("TITLE", List.of("one", "two")).put("ID3", "invalid").build()).rejected();
      assertEquals(List.of("invalid"), rejected.get("ID3"));
      assertEquals("one", file.id3v1Tag().title());
      assertEquals("one / two", file.apeTag().title());
      assertEquals(List.of("OPAQUE"), file.properties().unsupportedData());
      file.save();
      assertArrayEquals(audio().data(), Arrays.copyOf(source.data().data(), 32));
      try (var reopened = new WavPackFile(source)) {
        assertEquals("kept", new String(reopened.apeTag().itemListMap().get("OPAQUE").binaryData().data()));
      }
    }
  }

  @Test
  void fileRefRetainsOneConstructorAndDisabledNullProperties() throws Exception {
    var source = new ByteVectorStream(audio()) {
      int headers;

      @Override
      public String name() {
        return "sample.wv";
      }

      @Override
      public ByteVector readBlock(long count) {
        return tell() == 0 && count == 32 && ++headers > 1 ? new ByteVector() : super.readBlock(count);
      }
    };
    try (var ref = new AudioFile(source)) {
      var file = assertInstanceOf(WavPackFile.class, ref.file());
      assertEquals(0x410, file.detailedProperties().version());
      ref.tag().setTitle("live");
      assertEquals("live", file.apeTag().title());
      assertDoesNotThrow(ref::save);
    }
    try (var ref = new AudioFile(new ByteVectorStream(audio()) {
      @Override
      public String name() {
        return "sample.wv";
      }
    }, ReadOptions.TAGS_ONLY)) {
      assertInstanceOf(WavPackFile.class, ref.file());
      assertNull(ref.audioProperties());
    }
  }

  @Test
  void ownedAndBorrowedStreamsAndUnopenedHandlesMatch() throws Exception {
    var path = directory.resolve("sample.wv");
    Files.write(path, audio().data());
    var owned = new WavPackFile(path);
    owned.close();
    assertFalse(owned.isOpen());
    try (var stream = new FileStream(path)) {
      new WavPackFile(stream).close();
      assertTrue(stream.isOpen());
    }
    assertThrows(java.io.UncheckedIOException.class, () -> new WavPackFile(directory.resolve("missing.wv")));
  }

  @Test
  void undefinedNativeMemoryGrowthRejectsWithoutByteCursorOrFooterChanges() throws IOException {
    for (long flags : new long[]{0x80000000L, 0xffffffffL}) {
      var footer = new ByteVector("APETAGEX").append(ByteVector.fromUnsignedInt(2000, false))
        .append(ByteVector.fromUnsignedInt(33, false)).append(ByteVector.fromUnsignedInt(0, false))
        .append(ByteVector.fromUnsignedInt(flags, false)).append(new ByteVector(8));
      var bytes = audio().append(footer);
      var source = new ByteVectorStream(bytes);
      try (var file = new WavPackFile(source)) {
        assertFalse(file.hasApeTag());
        assertNotNull(file.apeTag());
        file.apeTag().setTitle("requires growing the invalid retained span");
        long cursor = file.tell();
        var before = file.apeTag().footer().renderFooter();
        assertThrows(TagLibException.class, file::save);
        assertEquals(cursor, file.tell());
        assertArrayEquals(bytes.data(), source.data().data());
        assertEquals(before, file.apeTag().footer().renderFooter());
      }
    }
  }

  @Test
  void readOnlySaveDoesNotRenderLiveFooter() throws IOException {
    var source = new ByteVectorStream(audio()) {
      @Override
      public boolean readOnly() {
        return true;
      }
    };
    try (var file = new WavPackFile(source)) {
      file.apeTag().setTitle("staged");
      var footer = file.apeTag().footer().renderFooter();
      long cursor = file.tell();
      assertThrows(TagLibException.class, file::save);
      assertEquals(cursor, file.tell());
      assertEquals(footer, file.apeTag().footer().renderFooter());
      assertArrayEquals(audio().data(), source.data().data());
    }
  }

  @Test
  void explicitMetadataLimitRejectsBeforePayloadAllocation() {
    var tag = new cz.skyba.taglib.ape.Tag();
    tag.setData("OPAQUE", new ByteVector(1500));
    var bytes = audio().append(tag.render());
    assertThrows(TagLibException.class, () -> new WavPackFile(new ByteVectorStream(bytes),
      new ReadOptions(true, ReadStyle.AVERAGE, 1024)));
  }
}
