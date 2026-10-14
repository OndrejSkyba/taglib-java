package cz.skyba.taglib.ape;

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

class ApeFileTest {
  @TempDir
  Path directory;

  static ByteVector audio() {
    var descriptor = new ByteVector(44);
    descriptor.set(0, (byte) 52);
    var header = new ByteVector(24);
    header.set(4, (byte) 100); // blocks per frame
    header.set(8, (byte) 100); // final frame
    header.set(12, (byte) 2); // total frames
    header.set(16, (byte) 16);
    header.set(18, (byte) 2);
    header.set(20, (byte) 0x44);
    header.set(21, (byte) 0xac); // 44100 Hz
    return new ByteVector("MAC ").append(ByteVector.fromUnsignedShort(3990, false))
      .append(new ByteVector(2)).append(descriptor).append(header).append(new ByteVector(52));
  }

  static ByteVector tagged() {
    var ape = new Tag();
    ape.setTitle("APE title");
    ape.setData("OPAQUE", new ByteVector("bytes"));
    var legacy = new cz.skyba.taglib.id3.v1.Tag();
    legacy.setTitle("legacy");
    legacy.setArtist("fallback");
    return audio().append(ape.render()).append(legacy.render());
  }

  @Test
  void physicalPresenceAndCreatedHandlesStayDistinctUntilSave() throws IOException {
    var source = new ByteVectorStream(audio());
    try (var file = new ApeFile(source, ReadOptions.TAGS_ONLY)) {
      assertNotNull(file.apeTag());
      assertNull(file.id3v1Tag());
      assertNull(file.detailedProperties());
      assertNull(file.audioProperties());
      file.getOrCreateId3v1Tag().setTitle("legacy");
      assertFalse(file.hasApeTag());
      assertFalse(file.hasId3v1Tag());
      file.apeTag().setTitle("primary");
      file.save();
      assertTrue(file.hasApeTag());
      assertTrue(file.hasId3v1Tag());
      assertEquals("primary", file.tag().title());
      assertEquals("legacy", file.id3v1Tag().title());
      byte[] once = source.data().toByteArray();
      file.save();
      assertArrayEquals(once, source.data().toByteArray());
    }
  }

  @Test
  void removeTagsStagesRemovalAndSavePreservesAudio() throws IOException {
    var original = tagged();
    var source = new ByteVectorStream(original);
    try (var file = new ApeFile(source)) {
      assertEquals("APE title", file.tag().title());
      assertEquals("fallback", file.tag().artist());
      assertEquals(List.of("OPAQUE"), file.properties().unsupportedData());
      byte[] beforeRemoval = source.data().toByteArray();
      file.removeTags(Set.of(ApeFile.TagType.APE));
      assertArrayEquals(beforeRemoval, source.data().toByteArray());
      assertNull(file.apeTag());
      assertNotNull(file.id3v1Tag());
      assertTrue(file.hasApeTag());
      assertEquals("legacy", file.tag().title());
      file.save();
      assertFalse(file.hasApeTag());
      assertTrue(file.hasId3v1Tag());
      assertArrayEquals(audio().toByteArray(), Arrays.copyOf(source.data().toByteArray(), audio().size()));
      file.removeTags();
      assertNotNull(file.apeTag());
      assertTrue(file.apeTag().isEmpty());
      assertNull(file.id3v1Tag());
      assertTrue(file.hasId3v1Tag());
      file.save();
      assertFalse(file.hasId3v1Tag());
      assertArrayEquals(audio().toByteArray(), source.data().toByteArray());
    }
  }

  @Test
  void commonBroadcastAndPropertyImportHaveDifferentChildSemantics() throws IOException {
    var source = new ByteVectorStream(tagged());
    try (var file = new ApeFile(source)) {
      file.tag().setTitle("broadcast");
      assertEquals("broadcast", file.apeTag().title());
      assertEquals("broadcast", file.id3v1Tag().title());
      var rejected = file.replaceProperties(PropertyMap.builder().put("TITLE", List.of("one", "two"))
        .put("ALBUMARTIST", "other").put("ID3", "invalid").build()).rejected();
      assertEquals(List.of("invalid"), rejected.get("ID3"));
      assertEquals("one / two", file.apeTag().title());
      assertEquals("one", file.id3v1Tag().title());
      assertEquals(List.of("OPAQUE"), file.properties().unsupportedData());
      file.save();
      try (var reopened = new ApeFile(source)) {
        assertEquals("one / two", reopened.tag().title());
        assertEquals("bytes", new String(reopened.apeTag().itemListMap().get("OPAQUE").binaryData().toByteArray()));
      }
    }
  }

  @Test
  void noDescriptorDoesNotInvalidateNativeFileAndPartialVersionSurvives() throws IOException {
    try (var noDescriptor = new ApeFile(new ByteVectorStream(new byte[80]))) {
      assertTrue(noDescriptor.isValid());
      assertEquals(0, noDescriptor.detailedProperties().version());
    }
    var source = new ByteVectorStream(audio().mid(0, 51));
    try (var partial = new ApeFile(source)) {
      assertTrue(partial.isValid());
      assertEquals(3990, partial.detailedProperties().version());
      assertEquals(0, partial.detailedProperties().channels());
      assertEquals(51, source.tell());
    }
  }

  @Test
  void undersizedDescriptorDoesNotWrapBackIntoAudioHeader() throws Exception {
    var bytes = audio();
    bytes.set(8, (byte) 51);
    Path path = directory.resolve("descriptor.ape");
    Files.write(path, bytes.toByteArray());
    for (var source : List.of(new ByteVectorStream(bytes), new FileStream(path))) {
      try (source; var file = new ApeFile(source)) {
        assertTrue(file.isValid());
        assertEquals(3990, file.detailedProperties().version());
        assertEquals(0, file.detailedProperties().sampleFrames());
        assertEquals(0, file.detailedProperties().sampleRate());
        assertEquals(0, file.detailedProperties().channels());
        assertEquals(0, file.detailedProperties().lengthInMilliseconds());
        assertEquals(52L + 0xffff_ffffL, source.tell());
      }
    }
  }

  @Test
  void directPropertyConstructorReadsOnceAtCurrentPositionAndKeepsPartialFields() throws IOException {
    var source = new ByteVectorStream(audio());
    try (var file = new ApeFile(source, ReadOptions.TAGS_ONLY)) {
      file.seek(0);
      var properties = new Properties(file, 128, ReadStyle.FAST);
      assertEquals(3990, properties.version());
      assertEquals(200, properties.sampleFrames());
      assertEquals(44100, properties.sampleRate());
      assertEquals(2, properties.channels());
      assertEquals(16, properties.bitsPerSample());
      assertEquals(5, properties.lengthInMilliseconds());
      assertEquals(76, file.tell());
    }
  }

  @Test
  void supportSkipsOnlyId3HeaderAndRestoresCursor() throws IOException {
    var header = new ByteVector("ID3").append(new ByteVector(new byte[]{4, 0, 0, 0, 0, 0, 5}));
    var source = new ByteVectorStream(header.append(new ByteVector(5)).append(audio()));
    source.seek(7);
    assertTrue(ApeFile.isSupported(source));
    assertEquals(7, source.tell());
    try (var file = new ApeFile(source)) {
      assertEquals(3990, file.detailedProperties().version());
      assertEquals(91, source.tell());
    }
    assertFalse(ApeFile.isSupported(null));
  }

  @Test
  void tagProbeDoesNotMistakeApeMagicForLegacyTag() throws IOException {
    var bytes = new ByteVector(160);
    System.arraycopy(new ByteVector("APETAGEX").toByteArray(), 0, bytes.data(), 29, 8);
    try (var file = new ApeFile(new ByteVectorStream(bytes), ReadOptions.TAGS_ONLY)) {
      assertFalse(file.hasId3v1Tag());
      assertNull(file.id3v1Tag());
    }
  }

  @Test
  void pathOwnershipBorrowedLifetimeAndUnopenedHandles() throws Exception {
    var path = directory.resolve("sample.ape");
    Files.write(path, audio().toByteArray());
    var owned = new ApeFile(path);
    assertTrue(owned.isOpen());
    owned.close();
    assertFalse(owned.isOpen());
    try (var stream = new FileStream(path)) {
      var borrowed = new ApeFile(stream);
      borrowed.close();
      assertTrue(stream.isOpen());
    }
    assertThrows(java.io.UncheckedIOException.class, () -> new ApeFile(directory.resolve("missing.ape")));
  }

  @Test
  void readOnlySaveDoesNotRenderOrMutateFooter() throws IOException {
    var stream = new ByteVectorStream(audio()) {
      @Override
      public boolean readOnly() {
        return true;
      }
    };
    try (var file = new ApeFile(stream)) {
      file.tag().setTitle("staged");
      assertFalse(file.apeTag().footer().headerPresent());
      long cursor = file.tell();
      assertThrows(TagLibException.class, file::save);
      assertEquals(cursor, file.tell());
      assertFalse(file.apeTag().footer().headerPresent());
      assertArrayEquals(audio().toByteArray(), stream.data().toByteArray());
    }
  }

  @Test
  void fileRefRetainsOneActualConstructorIncludingDisabledProperties() throws Exception {
    var source = new ByteVectorStream(audio()) {
      int descriptors;

      @Override
      public String name() {
        return "sample.ape";
      }

      @Override
      public ByteVector readBlock(long count) {
        if (tell() == 8 && count == 44 && ++descriptors > 1) return new ByteVector();
        return super.readBlock(count);
      }
    };
    try (var ref = new AudioFile(source)) {
      assertTrue(ref.isOpen());
      var file = assertInstanceOf(ApeFile.class, ref.file());
      assertEquals(2, file.detailedProperties().channels());
      assertEquals(76, ref.file().tell());
      ref.tag().setTitle("live");
      assertEquals("live", file.apeTag().title());
      assertDoesNotThrow(ref::save);
    }
    try (var ref = new AudioFile(new ByteVectorStream(audio()) {
      @Override
      public String name() {
        return "sample.ape";
      }
    }, new ReadOptions(false, ReadStyle.ACCURATE, ReadOptions.DEFAULT.maxMetadataBytes()))) {
      assertTrue(ref.isOpen());
      assertInstanceOf(ApeFile.class, ref.file());
      assertNull(ref.audioProperties());
    }
  }

  @Test
  void explicitLimitRejectsApePayloadBeforeAllocation() throws IOException {
    var ape = new Tag();
    ape.setData("OPAQUE", new ByteVector(1500));
    var source = new ByteVectorStream(audio().append(ape.render()));
    assertThrows(TagLibException.class, () -> new ApeFile(source,
      new ReadOptions(true, ReadStyle.AVERAGE, 1024)));
    try (var file = new ApeFile(new ByteVectorStream(source.data()))) {
      assertEquals(1, file.apeTag().itemListMap().size());
    }
  }
}
