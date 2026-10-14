package cz.skyba.taglib.mpc;

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

class MpcFileTest {
  @TempDir
  Path directory;

  static ByteVector sv7() {
    return new ByteVector("MP+").append(new ByteVector(new byte[]{7}))
      .append(ByteVector.fromUnsignedInt(2, false)).append(new ByteVector(48));
  }

  static ByteVector leading() {
    return new ByteVector("ID3").append(new ByteVector(new byte[]{4, 0, 0, 0, 0, 0, 5})).append(new ByteVector("abcde"));
  }

  static ByteVector tagged() {
    var tag = new cz.skyba.taglib.ape.Tag();
    tag.setTitle("primary");
    tag.setData("OPAQUE", new ByteVector("kept"));
    var legacy = new cz.skyba.taglib.id3.v1.Tag();
    legacy.setTitle("legacy");
    legacy.setArtist("fallback");
    return leading().append(sv7()).append(tag.render()).append(legacy.render());
  }

  static ByteVector packet(String kind, byte[] body) {
    return new ByteVector(kind).append(new ByteVector(new byte[]{(byte) (3 + body.length)})).append(new ByteVector(body));
  }

  static ByteVector sh() {
    return packet("SH", new byte[]{0, 0, 0, 0, 8, 100, 0, 0, 0x10});
  }

  static ByteVector rg() {
    return packet("RG", new byte[]{1, (byte) 0xff, (byte) 0xff, (byte) 0x80, 0, 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xf6, 0});
  }

  @Test
  void removeTagsStagesPrefixRemovalAndPreservesLiveTailChildren() throws IOException {
    var source = new ByteVectorStream(tagged());
    try (var file = new MpcFile(source)) {
      var primary = file.apeTag();
      var legacy = file.id3v1Tag();
      assertEquals("primary", file.tag().title());
      byte[] beforeRemoval = source.data().toByteArray();
      file.removeTags(Set.of(MpcFile.TagType.ID3V2));
      assertArrayEquals(beforeRemoval, source.data().toByteArray());
      assertSame(primary, file.apeTag());
      assertSame(legacy, file.id3v1Tag());
      file.save();
      assertEquals("MP+", new String(source.data().mid(0, 3).data()));
      assertTrue(file.hasApeTag());
      assertTrue(file.hasId3v1Tag());
      byte[] once = source.data().toByteArray();
      file.removeTags(Set.of(MpcFile.TagType.ID3V2));
      file.save();
      assertArrayEquals(once, source.data().data());
      file.removeTags(Set.of(MpcFile.TagType.APE));
      assertNull(file.apeTag());
      assertEquals("legacy", file.tag().title());
      file.save();
      file.removeTags();
      file.save();
      assertArrayEquals(sv7().data(), source.data().data());
    }
  }

  @Test
  void prefixRemovalParticipatesInCompletePreflightBeforeMutation() throws IOException {
    var footer = new ByteVector("APETAGEX").append(ByteVector.fromUnsignedInt(2000, false))
      .append(ByteVector.fromUnsignedInt(33, false)).append(ByteVector.fromUnsignedInt(0, false))
      .append(ByteVector.fromUnsignedInt(0x80000000L, false)).append(new ByteVector(8));
    var bytes = leading().append(sv7().mid(0, 17)).append(footer);
    var source = new ByteVectorStream(bytes);
    try (var file = new MpcFile(source, ReadOptions.TAGS_ONLY)) {
      file.apeTag().setTitle("grow an invalid retained span");
      file.removeTags(Set.of(MpcFile.TagType.ID3V2));
      long cursor = file.tell();
      var before = file.apeTag().footer().renderFooter();
      assertThrows(TagLibException.class, file::save);
      assertArrayEquals(bytes.data(), source.data().data());
      assertEquals(cursor, file.tell());
      assertEquals(before, file.apeTag().footer().renderFooter());
    }
  }

  @Test
  void directPropertiesUseCurrentPositionAndOneReadForShortFixedHeader() throws IOException {
    var source = new ByteVectorStream(new ByteVector("X").append(sv7()));
    try (var file = new MpcFile(source, ReadOptions.TAGS_ONLY)) {
      file.seek(1);
      var p = new Properties(file, 56, ReadStyle.FAST);
      assertEquals(7, p.mpcVersion());
      assertEquals(2, p.totalFrames());
      assertEquals(1728, p.sampleFrames());
      assertEquals(39, p.lengthInMilliseconds());
      assertEquals(11, p.bitrate());
      assertEquals(44100, p.sampleRate());
      assertEquals(2, p.channels());
      assertEquals(57, file.tell());
    }
  }

  @Test
  void callerReadVectorsAreNotMutatedWhenAppendingFixedHeader() throws IOException {
    var magic = new ByteVector("MP+").append(new ByteVector(new byte[]{7}));
    var source = new ByteVectorStream(sv7()) {
      @Override
      public ByteVector readBlock(long count) {
        if (tell() == 0 && count == 4) {
          seek(4);
          return magic;
        }
        return super.readBlock(count);
      }
    };
    try (var file = new MpcFile(source, ReadOptions.TAGS_ONLY)) {
      file.seek(0);
      assertEquals(7, new Properties(file, 56).mpcVersion());
      assertEquals(4, magic.size());
    }
  }

  @Test
  void earlyUnsupportedVersionRetainsVersionWithoutCommonFields() throws IOException {
    var bytes = sv7();
    bytes.set(3, (byte) 6);
    try (var file = new MpcFile(new ByteVectorStream(bytes))) {
      assertTrue(file.isValid());
      assertEquals(6, file.detailedProperties().mpcVersion());
      assertEquals(0, file.detailedProperties().sampleRate());
      assertEquals(0, file.detailedProperties().sampleFrames());
    }
  }

  @Test
  void emptyOldHeaderRetainsNativeUint32SubtractionAndDefaults() throws IOException {
    try (var file = new MpcFile(new ByteVectorStream(new ByteVector()))) {
      var p = file.detailedProperties();
      assertNotNull(p);
      assertEquals(0, p.mpcVersion());
      assertEquals(44100, p.sampleRate());
      assertEquals(2, p.channels());
      assertEquals(0xfffffdc0L, p.sampleFrames());
      assertEquals(0, file.tell());
    }
  }

  @Test
  void signedNegativeStreamLengthKeepsNativeNegativeCalculatedBitrate() throws IOException {
    try (var file = new MpcFile(new ByteVectorStream(sv7()), ReadOptions.TAGS_ONLY)) {
      file.seek(0);
      var p = new Properties(file, -128);
      assertEquals(-25, p.bitrate());
      assertEquals(39, p.lengthInMilliseconds());
    }
  }

  @Test
  void lowerTruncationBoundaryRetainsIntMinAndRejectsUndefinedConversion() throws IOException {
    try (var file = new MpcFile(new ByteVectorStream(sv7()), ReadOptions.TAGS_ONLY)) {
      for (long length : new long[]{-10518287262L, -10518287260L, -10518287258L}) {
        file.seek(0);
        assertEquals(Integer.MIN_VALUE, new Properties(file, length).bitrate());
      }
      file.seek(0);
      assertThrows(IllegalArgumentException.class, () -> new Properties(file, -10518287263L));
    }
  }

  @Test
  void sv8StopsAfterFirstShOrRgAndKeepsSignedReplayGain() throws IOException {
    try (var file = new MpcFile(new ByteVectorStream(new ByteVector("MPCK").append(sh()).append(rg())))) {
      var p = file.detailedProperties();
      assertEquals(8, p.mpcVersion());
      assertEquals(100, p.sampleFrames());
      assertEquals(2, p.channels());
      assertEquals(0, p.trackGain());
      assertEquals(16, file.tell());
    }
    try (var file = new MpcFile(new ByteVectorStream(new ByteVector("MPCK").append(rg()).append(sh())))) {
      var p = file.detailedProperties();
      assertEquals(0, p.mpcVersion());
      assertEquals(0, p.channels());
      assertEquals(-1, p.trackGain());
      assertEquals(-32768, p.trackPeak());
      assertEquals(32767, p.albumGain());
      assertEquals(-10, p.albumPeak());
    }
  }

  @Test
  void sv8UnknownPacketConsumesAndThenSkipsItsPayloadAgain() throws IOException {
    var bytes = new ByteVector("MPCK").append(packet("XX", new byte[]{1, 2})).append(new ByteVector("XX")).append(sh());
    try (var file = new MpcFile(new ByteVectorStream(bytes))) {
      assertEquals(8, file.detailedProperties().mpcVersion());
      assertEquals(100, file.detailedProperties().sampleFrames());
    }
  }

  @Test
  void sv8CorruptHeaderRetainsAssignedVersionAndSamples() throws IOException {
    var bytes = new ByteVector("MPCK").append(packet("SH", new byte[]{0, 0, 0, 0, (byte) 255, (byte) 0x81}));
    try (var file = new MpcFile(new ByteVectorStream(bytes))) {
      assertEquals(-1, file.detailedProperties().mpcVersion());
      assertEquals(1, file.detailedProperties().sampleFrames());
      assertEquals(0, file.detailedProperties().channels());
    }
  }

  @Test
  void supportSkipsId3RestoresCursorAndHandlesThreeByteMpPrefix() throws Exception {
    var source = new ByteVectorStream(leading().append(sv7()));
    source.seek(7);
    assertTrue(MpcFile.isSupported(source));
    assertEquals(7, source.tell());
    assertTrue(MpcFile.isSupported(new ByteVectorStream(new ByteVector("MP+"))));
    assertFalse(MpcFile.isSupported(null));
    try (var ref = new AudioFile(new ByteVectorStream(source.data()) {
      @Override
      public String name() {
        return "sample.unknown";
      }
    })) {
      assertInstanceOf(MpcFile.class, ref.file());
    }
  }

  @Test
  void fileRefRetainsSingleConstructorAndDisabledProperties() throws Exception {
    var source = new ByteVectorStream(sv7()) {
      int reads;

      @Override
      public String name() {
        return "sample.mpc";
      }

      @Override
      public ByteVector readBlock(long count) {
        return tell() == 4 && count == 52 && ++reads > 1 ? new ByteVector() : super.readBlock(count);
      }
    };
    try (var ref = new AudioFile(source)) {
      var f = assertInstanceOf(MpcFile.class, ref.file());
      assertEquals(1728, f.detailedProperties().sampleFrames());
      ref.tag().setTitle("live");
      assertEquals("live", f.apeTag().title());
    }
    try (var ref = new AudioFile(new ByteVectorStream(sv7()) {
      @Override
      public String name() {
        return "sample.mpc";
      }
    }, ReadOptions.TAGS_ONLY)) {
      assertInstanceOf(MpcFile.class, ref.file());
      assertNull(ref.audioProperties());
    }
  }

  @Test
  void propertyImportRetainsOpaqueAndBroadcastIsSeparate() throws IOException {
    var source = new ByteVectorStream(tagged());
    try (var file = new MpcFile(source)) {
      file.tag().setTitle("broadcast");
      assertEquals("broadcast", file.id3v1Tag().title());
      var rejected = file.replaceProperties(PropertyMap.builder().put("TITLE", List.of("one", "two")).put("ID3", "invalid").build()).rejected();
      assertEquals(List.of("invalid"), rejected.get("ID3"));
      assertEquals("one", file.id3v1Tag().title());
      assertEquals("one / two", file.apeTag().title());
      assertEquals(List.of("OPAQUE"), file.properties().unsupportedData());
      file.save();
      assertArrayEquals(leading().append(sv7()).data(), Arrays.copyOf(source.data().data(), 71));
    }
  }

  @Test
  void readOnlySaveDoesNotRemovePrefixOrRenderFooter() throws IOException {
    var source = new ByteVectorStream(tagged()) {
      @Override
      public boolean readOnly() {
        return true;
      }
    };
    try (var file = new MpcFile(source)) {
      file.removeTags(Set.of(MpcFile.TagType.ID3V2));
      file.apeTag().setTitle("staged");
      long cursor = file.tell();
      var footer = file.apeTag().footer().renderFooter();
      assertThrows(TagLibException.class, file::save);
      assertEquals(cursor, file.tell());
      assertEquals(footer, file.apeTag().footer().renderFooter());
      assertArrayEquals(tagged().data(), source.data().data());
    }
  }

  @Test
  void ownedBorrowedAndUnopenedFileHandlesMatch() throws Exception {
    var path = directory.resolve("sample.mpc");
    Files.write(path, sv7().data());
    var owned = new MpcFile(path);
    owned.close();
    assertFalse(owned.isOpen());
    try (var source = new FileStream(path)) {
      new MpcFile(source).close();
      assertTrue(source.isOpen());
    }
    assertThrows(java.io.UncheckedIOException.class, () -> new MpcFile(directory.resolve("missing.mpc")));
  }
}
