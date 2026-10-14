package cz.skyba.taglib.internal.id3;

import cz.skyba.taglib.Picture;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.Tag;
import cz.skyba.taglib.internal.ape.ApeTag;
import cz.skyba.taglib.internal.codec.MpegCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

class Id3Test {
  @TempDir
  Path directory;

  @Test
  void nativeSetPropertiesRetainsLogicalEmptyListsBeforeRender() throws Exception {
    var requested = PropertyMap.builder().put("ARTIST", List.of()).put("CUSTOM", List.of())
      .put("TITLE", "").build();
    var state = Id3v2.updateProperties(new Id3v2.Parsed(4, List.of()), requested);
    var before = Id3v2.read(state).properties();
    assertEquals(List.of(), before.get("ARTIST"));
    assertEquals(List.of(""), before.get("TITLE"));
    assertFalse(before.asMap().containsKey("CUSTOM"));
    assertTrue(before.unsupportedData().isEmpty());
  }

  @Test
  void nativeNonemptyObsoleteFrameRendersPaddedEmptyTag() throws Exception {
    byte[] source = tag(2, frame(2, "TDA", 0, new byte[]{0, '0', '1', '0', '2'}));
    byte[] saved = Id3v2.renderNative(PropertyMap.EMPTY, source, null);
    assertTrue(saved.length >= 10);
    assertEquals(4, saved[3]);
    assertTrue(Id3v2.read(saved, ReadOptions.DEFAULT).empty());
    assertEquals(0, Id3v2.renderNative(PropertyMap.EMPTY, null, null).length);
  }

  @Test
  void mpegDefaultDuplicationRunsBeforeObsoleteFramesAreRenderedAway() throws Exception {
    byte[] source = Arrays.copyOf(tag(2, frame(2, "TDA", 0, new byte[]{0, '0', '1', '0', '2'})), 512);
    source[8] = 3;
    source[9] = 118; // 502-byte declared capacity.
    var properties = PropertyMap.builder().put("DATE", "0").put("TRACKNUMBER", "0").build();
    var saved = Id3v2.renderMpeg(properties, source, null, new Tag(PropertyMap.EMPTY), 4096);
    assertEquals(512, saved.bytes().length);
    assertTrue(Id3v2.read(saved.bytes(), ReadOptions.DEFAULT).empty());
    assertEquals(new Tag(PropertyMap.EMPTY), saved.basic());
  }

  @Test
  void nativeGenericSaveDoesNotCreatePaddingOnlyId3Tags() throws Exception {
    assertArrayEquals(new byte[0], Id3v2.renderNative(PropertyMap.EMPTY, null, null));
    byte[] source = tag(3, frame(3, "TIT2", 0, new byte[]{0, 'x'}));
    assertArrayEquals(new byte[0], Id3v2.renderNative(PropertyMap.EMPTY, source, null));
  }

  @Test
  void genericNativeSaveConvertsLegacyTagsAndRetainsEmptyValueFrames() throws Exception {
    for (int version : new int[]{2, 3, 4}) {
      byte[] source = tag(version, frame(version, version == 2 ? "TT2" : "TIT2", 0,
        new byte[]{0, 'O', 'l', 'd'}));
      var properties = PropertyMap.builder().put("ARTIST", List.of()).put("TITLE", "")
        .put("CUSTOM", List.of()).put("DATE", List.of("2026-10-03", "2147483648")).build();
      byte[] rewritten = Id3v2.renderNative(properties, source, null);
      assertEquals(4, rewritten[3]);
      var result = Id3v2.read(rewritten, ReadOptions.DEFAULT).properties();
      assertEquals(List.of(), result.get("ARTIST"));
      assertEquals(List.of(), result.get("TITLE"));
      assertEquals(List.of(""), result.get("CUSTOM"));
      assertEquals(List.of("2026-10-03", "2147483648"), result.get("DATE"));
      assertTrue(result.unsupportedData().isEmpty());
    }
  }

  @Test
  void nativeTimestampProjectionChangesOnlyTheFirstTAndSaveKeepsLiteralSpaces() throws Exception {
    var source = tag(4, frame(4, "TDRC", 0,
      "\3dateTtimeTzone".getBytes(StandardCharsets.UTF_8)));
    assertEquals("date timeTzone", Id3v2.read(source, ReadOptions.DEFAULT).properties().first("DATE"));
    var properties = PropertyMap.builder().put("DATE", "2003-01-30 03:28:45 UTC").build();
    assertEquals("2003-01-30 03:28:45 U C", Id3v2.read(
      Id3v2.renderNative(properties, null, null), ReadOptions.DEFAULT).properties().first("DATE"));
  }

  @Test
  void id3v1RejectsAnEntireNonLatin1FieldIncludingCharactersAfterItsTruncationBoundary() {
    for (String value : List.of("Žlutý", "x".repeat(30) + "水", "Music 🎵")) {
      byte[] encoded = Id3v1.render(PropertyMap.builder().put("TITLE", value).put("ARTIST", "Björk").build());
      assertArrayEquals(new byte[30], Arrays.copyOfRange(encoded, 3, 33));
      assertEquals("Björk", Id3v1.read(encoded).first("ARTIST"));
    }
  }

  @Test
  void mappedCommentsAndLyricsUseNativeUnspecifiedLanguageCode() throws Exception {
    byte[] encoded = Id3v2.render(
      PropertyMap.builder().put("COMMENT", "Notes").put("LYRICS", "Verse").build(), null);
    for (var frame : Id3v2.parseForEditing(encoded).frames())
      if (frame.id().equals("COMM") || frame.id().equals("USLT"))
        assertArrayEquals(new byte[]{'X', 'X', 'X'}, Arrays.copyOfRange(frame.data(), 1, 4));
  }

  @Test
  void unsupportedFrameProjectionIncludesTypedOpaqueUnknownAndEncryptedIdentifiersInOrder()
    throws Exception {
    byte[] source = tag(4,
      frame(4, "APIC", 0, new byte[]{0, 'x', 0, 3, 0, 1}),
      frame(4, "POPM", 0, new byte[]{'x', 0, 7, 0, 0, 0, 1}),
      frame(4, "UFID", 0, new byte[]{'x', 0, 1, 2}),
      frame(4, "W000", 0, new byte[]{'x'}),
      frame(4, "XZZZ", 0, new byte[]{1, 2}),
      frame(4, "TIT2", 4, new byte[]{0, 1, 2}),
      frame(4, "PRIV", 0, new byte[]{'x', 0, 1}),
      frame(4, "PRIV", 0, new byte[]{'y', 0, 2}));
    assertEquals(List.of("APIC", "POPM", "UFID/x", "W000", "UNKNOWN/XZZZ",
        "UNKNOWN/TIT2", "PRIV", "PRIV"),
      Id3v2.read(source, ReadOptions.DEFAULT).properties().unsupportedData());
    assertEquals(List.of("UNKNOWN/TDA"),
      Id3v2.read(tag(2, frame(2, "TDA", 0, new byte[]{0, '1', '2', '3', '4'})),
        ReadOptions.DEFAULT).properties().unsupportedData());
  }

  @Test
  void emptyUserTextDescriptionUsesNativeInsertRatherThanSimpleMapConstruction() throws Exception {
    var properties =
      Id3v2.read(tag(4, frame(4, "TXXX", 0, new byte[]{3, 0})), ReadOptions.DEFAULT)
        .properties();
    assertTrue(properties.containsKey(""));
    assertEquals(List.of(""), properties.get(""));
    assertTrue(properties.unsupportedData().isEmpty());
  }

  @Test
  void legacyFrameFactoryMigrationsMatchRelease232() throws Exception {
    String[][] cases = {
      {"TOA", "ORIGINALALBUM"},
      {"TOT", "ORIGINALALBUM"},
      {"TXT", "ORIGINALLYRICIST"},
      {"TOR", "ORIGINALDATE"},
      {"TRD", "DATE"},
      {"TYE", "DATE"},
      {"TCT", "PODCASTCATEGORY"},
      {"TDR", "RELEASEDATE"},
      {"TDS", "PODCASTDESC"},
      {"TID", "PODCASTID"},
      {"WFD", "PODCASTURL"},
      {"MVN", "MOVEMENTNAME"},
      {"MVI", "MOVEMENTNUMBER"},
      {"GP1", "GROUPING"}
    };
    for (String[] item : cases) {
      byte[] source = tag(2, frame(2, item[0], 0, new byte[]{0, '2', '0', '2', '6'}));
      var properties = Id3v2.read(source, ReadOptions.DEFAULT).properties();
      assertEquals(List.of("2026"), properties.get(item[1]), item[0]);
      assertEquals(
        properties,
        Id3v2.read(Id3v2.render(properties, source), ReadOptions.DEFAULT).properties(),
        item[0]);
    }
    assertEquals(
      "2026",
      Id3v2.read(
          tag(4, frame(4, "TRDC", 0, new byte[]{0, '2', '0', '2', '6'})),
          ReadOptions.DEFAULT)
        .properties()
        .first("DATE"));
    assertEquals(
      "TDOR",
      Id3v2.parseForEditing(tag(3, frame(3, "TORY", 0, new byte[]{0, '2', '0', '2', '6'})))
        .frames()
        .getFirst()
        .id());
    for (int version : new int[]{2, 3, 4}) {
      // Native uses a ten-byte lower bound even for the shorter v2.2 frame header.
      byte[] source = tag(version, frame(version, version == 2 ? "PCS" : "PCST", 0, new byte[4]), new byte[10]);
      var podcast = Id3v2.read(source, ReadOptions.DEFAULT).properties();
      assertTrue(podcast.containsKey("PODCAST"));
      assertEquals(List.of(), podcast.get("PODCAST"));
      assertEquals(
        podcast, Id3v2.read(Id3v2.render(podcast, source), ReadOptions.DEFAULT).properties());
    }
  }

  @Test
  void itunesLegacyTextHeadersAndNonAsciiDescriptionsMatchNative() throws Exception {
    for (String id : List.of("TT2\0", "TT2 ")) {
      var props =
        Id3v2.read(tag(3, frame(3, id, 0, new byte[]{0, 'T'})), ReadOptions.DEFAULT)
          .properties();
      assertEquals("T", props.first("TITLE"));
    }
    byte[] source =
      tag(
        4,
        frame(4, "TXXX", 0, "\3éx\0value".getBytes(StandardCharsets.UTF_8)),
        frame(4, "COMM", 0, "\3engéx\0comment".getBytes(StandardCharsets.UTF_8)));
    var props = Id3v2.read(source, ReadOptions.DEFAULT).properties();
    assertEquals("value", props.first("éX"));
    assertEquals("comment", props.first("COMMENT:éX"));
    assertFalse(props.containsKey("ÉX"));
    assertEquals(props, Id3v2.read(Id3v2.render(props, source), ReadOptions.DEFAULT).properties());
  }

  @Test
  void allPropertyFamiliesRoundTrip() throws Exception {
    var properties =
      PropertyMap.builder()
        .put("TITLE", "水の音 – žluťoučký")
        .put("ARTIST", List.of("One", "Two"))
        .put("DATE", "2026-10-03 14:32")
        .put("COMMENT", "Notes")
        .put("COMMENT:SESSION", "Studio")
        .put("LYRICS", "Line one\nLine two")
        .put("URL", "https://example.test/")
        .put("URL:LICENSE", "https://example.test/license")
        .put("MUSICBRAINZ_TRACKID", "12345678")
        .put("MUSICBRAINZ_ALBUMID", "album-id")
        .put("PRODUCER", List.of("A", "B"))
        .put("PERFORMER:PIANO", "C")
        .put("GENRE", List.of("Rock", "Jazz"))
        .put("CUSTOM", "unmapped")
        .build();
    byte[] encoded = Id3v2.render(properties, null);
    assertEquals(properties, Id3v2.read(encoded, ReadOptions.DEFAULT).properties());
  }

  @Test
  void utf16LittleEndianBomIsInheritedAcrossValues() throws Exception {
    byte[] data = {1, (byte) 255, (byte) 254, 'A', 0, 0, 0, 'B', 0};
    assertEquals(
      List.of("A", "B"),
      Id3v2.read(tag(3, frame(3, "TPE1", 0, data)), ReadOptions.DEFAULT)
        .properties()
        .get("ARTIST"));
  }

  @Test
  void version22AndVersion23RetainVersionAndPreserveOpaqueData() throws Exception {
    for (int version : new int[]{2, 3}) {
      byte[] unknown = frame(version, version == 2 ? "XYZ" : "XZZZ", 0, new byte[]{1, 2, 3, 4, 5});
      byte[] source =
        tag(
          version,
          unknown,
          frame(version, version == 2 ? "TT2" : "TIT2", 0, new byte[]{0, 'O', 'l', 'd'}));
      var properties =
        PropertyMap.builder()
          .put("TITLE", "Nový")
          .put("ARTIST", List.of("First", "Second"))
          .build();
      byte[] changed = Id3v2.render(properties, source);
      assertEquals(version, changed[3]);
      assertEquals(properties.toBuilder().addUnsupportedData("UNKNOWN/" + (version == 2 ? "XYZ" : "XZZZ")).build(),
        Id3v2.read(changed, ReadOptions.DEFAULT).properties());
      assertTrue(indexOf(changed, unknown) >= 0);
    }
  }

  @Test
  void picturesAndOpaqueEncryptedFramesSurviveUpdates() throws Exception {
    byte[] picture =
      new byte[]{
        3, 'i', 'm', 'a', 'g', 'e', '/', 'p', 'n', 'g', 0, 3, 'c', 'o', 'v', 'e', 'r', 0, 1, 2, 3,
        4
      };
    byte[] encrypted = frame(4, "TPE1", 4, new byte[]{9, 7, 5, 3});
    byte[] source = tag(4, frame(4, "APIC", 0, picture), encrypted);
    byte[] changed = Id3v2.render(PropertyMap.builder().put("TITLE", "New").build(), source);
    var decoded = Id3v2.read(changed, ReadOptions.DEFAULT);
    assertEquals(1, decoded.pictures().size());
    assertEquals("cover", decoded.pictures().getFirst().description());
    assertArrayEquals(new byte[]{1, 2, 3, 4}, decoded.pictures().getFirst().data());
    assertTrue(indexOf(changed, encrypted) >= 0);
  }

  @Test
  void compressedAndUnsynchronizedFramesAreDecoded() throws Exception {
    byte[] plain = {0, 'a', (byte) 255, (byte) 224};
    byte[] unsync = {0, 'a', (byte) 255, 0, (byte) 224};
    var decoded = Id3v2.read(tag(4, frame(4, "TIT2", 2, unsync)), ReadOptions.DEFAULT);
    assertEquals("aÿà", decoded.properties().first("TITLE"));
    var deflater = new Deflater();
    deflater.setInput(plain);
    deflater.finish();
    byte[] compressed = new byte[128];
    int count = deflater.deflate(compressed);
    deflater.end();
    byte[] data = new byte[count + 4];
    data[3] = (byte) plain.length;
    System.arraycopy(compressed, 0, data, 4, count);
    assertEquals(
      "aÿà",
      Id3v2.read(tag(3, frame(3, "TIT2", 128, data)), ReadOptions.DEFAULT)
        .properties()
        .first("TITLE"));
  }

  @Test
  void genreAndDateCompatibilityMatchTaglib() throws Exception {
    var parsed =
      Id3v2.read(
        tag(
          3,
          frame(3, "TYER", 0, new byte[]{0, '2', '0', '2', '6'}),
          frame(3, "TDAT", 0, new byte[]{0, '0', '3', '1', '0'}),
          frame(3, "TIME", 0, new byte[]{0, '1', '4', '3', '2'}),
          frame(3, "TCON", 0, "\0(17)(8)Fusion".getBytes(StandardCharsets.ISO_8859_1))),
        ReadOptions.DEFAULT);
    assertEquals("2026-10-03 14:32", parsed.properties().first("DATE"));
    assertEquals(List.of("Rock", "Jazz", "Fusion"), parsed.properties().get("GENRE"));
  }

  @Test
  void artworkReplacementAndRemovalPreserveOtherFramesAcrossVersions() throws Exception {
    for (int version : new int[]{2, 3, 4}) {
      byte[] source =
        tag(version, frame(version, version == 2 ? "XYZ" : "XZZZ", 0, new byte[]{8, 7, 6}), new byte[10]);
      var pictures = List.of(new Picture(3, "image/png", "Přední obal", new byte[]{1, 2, 3, 4}));
      var properties =
        PropertyMap.builder().put("URL", "https://example.test/").put("TITLE", "Cover").build();
      byte[] changed = Id3v2.render(properties, source, pictures);
      var read = Id3v2.read(changed, ReadOptions.DEFAULT);
      assertEquals(properties.toBuilder()
        .addUnsupportedData("UNKNOWN/" + (version == 2 ? "XYZ" : "XZZZ"))
        .addUnsupportedData("APIC").build(), read.properties());
      assertEquals("Přední obal", read.pictures().getFirst().description());
      assertArrayEquals(pictures.getFirst().data(), read.pictures().getFirst().data());
      assertTrue(
        Id3v2.read(Id3v2.render(properties, changed, List.of()), ReadOptions.DEFAULT)
          .pictures()
          .isEmpty());
    }
  }

  @Test
  void nativeTenByteAdmissionBoundLeavesShortFinalV22FrameUnparsed() throws Exception {
    byte[] shortFrame = frame(2, "TT2", 0, new byte[]{0, 'x'});
    assertTrue(Id3v2.read(tag(2, shortFrame), ReadOptions.DEFAULT).empty());
    assertEquals("x", Id3v2.read(tag(2, shortFrame, new byte[3]), ReadOptions.DEFAULT).basic().title());
  }

  @Test
  void obsoleteAndTagAlterFramesAreRemovedWithoutDroppingUnrelatedOpaqueData() throws Exception {
    var properties = PropertyMap.builder().put("DATE", "2026").build();
    for (int version : new int[]{2, 3, 4}) {
      byte[] retained = frame(version, version == 2 ? "XYZ" : "XZZZ", 0, new byte[]{9, 7, 5});
      byte[] obsolete =
        frame(
          version,
          version == 2 ? "RVA" : version == 3 ? "EQUA" : "XBAD",
          version == 4 ? 0x4000 : 0,
          new byte[]{3, 1, 4});
      byte[] date =
        version == 4
          ? new byte[0]
          : frame(
          version, version == 2 ? "TDA" : "TDAT", 0, new byte[]{0, '0', '3', '1', '0'});
      byte[] changed = Id3v2.render(properties, tag(version, retained, obsolete, date));
      assertTrue(indexOf(changed, retained) >= 0);
      assertEquals(-1, indexOf(changed, obsolete));
      if (date.length != 0) assertEquals(-1, indexOf(changed, date));
      assertEquals("2026", Id3v2.read(changed, ReadOptions.DEFAULT).properties().first("DATE"));
    }
  }

  @Test
  void unchangedUserTextCommentIsNotPromotedToCommonCommentDuringArtworkEdits() throws Exception {
    byte[] userComment =
      frame(4, "TXXX", 0, "\u0003COMMENT\0User text comment".getBytes(StandardCharsets.UTF_8));
    byte[] source = tag(4, userComment);
    var before = Id3v2.read(source, ReadOptions.DEFAULT);
    assertEquals("User text comment", before.properties().first("COMMENT"));
    assertEquals("", before.basic().comment());
    byte[] artworkEdit =
      Id3v2.render(
        before.properties(),
        source,
        List.of(new Picture(3, "image/png", "Cover", new byte[]{1, 3, 5})));
    var after = Id3v2.read(artworkEdit, ReadOptions.DEFAULT);
    assertEquals(before.properties().toBuilder().addUnsupportedData("APIC").build(), after.properties());
    assertEquals("", after.basic().comment());
    assertTrue(indexOf(artworkEdit, userComment) >= 0);
    byte[] changedComment =
      Id3v2.render(PropertyMap.builder().put("COMMENT", "Changed comment").build(), artworkEdit);
    assertEquals(
      "Changed comment", Id3v2.read(changedComment, ReadOptions.DEFAULT).basic().comment());
    assertEquals(-1, indexOf(changedComment, userComment));
  }

  @Test
  void multipleSpecialValuesUseUserTextWithoutInventingCommonComment() throws Exception {
    var properties =
      PropertyMap.builder()
        .put("COMMENT", List.of("One", "Two"))
        .put("LYRICS", List.of("Verse", "Refrain"))
        .put("URL", List.of("https://one.test", "https://two.test"))
        .put("ARTISTWEBPAGE", List.of("https://a.test", "https://b.test"))
        .put("MUSICBRAINZ_TRACKID", List.of("id-one", "id-two"))
        .build();
    var read = Id3v2.read(Id3v2.render(properties, null), ReadOptions.DEFAULT);
    assertEquals(properties, read.properties());
    assertEquals("", read.basic().comment());
  }

  @Test
  void mpegDefaultSaveDuplicatesUserTextCommentThroughUntruncatedLegacyTag() throws Exception {
    String comment = "A complete comment longer than the twenty-eight-byte legacy field";
    byte[] source =
      tag(4, frame(4, "TXXX", 0, ("\u0003COMMENT\0" + comment).getBytes(StandardCharsets.UTF_8)));
    Path path = directory.resolve("user-comment.mp3");
    Files.write(path, source);
    var codec = new MpegCodec();
    var first = codec.read(path, ReadOptions.TAGS_ONLY);
    codec.write(
      path,
      first.properties(),
      List.of(new Picture(3, "image/png", "Cover", new byte[]{1, 3, 5})));
    var replaced = codec.read(path, ReadOptions.TAGS_ONLY);
    assertEquals(List.of(comment, comment), replaced.properties().get("COMMENT"));
    assertEquals(comment, replaced.tag().comment());
    byte[] bytes = Files.readAllBytes(path);
    assertEquals(
      comment.substring(0, 28),
      Id3v1.read(Arrays.copyOfRange(bytes, bytes.length - 128, bytes.length)).first("COMMENT"));
    codec.write(path, replaced.properties(), List.of());
    var cleared = codec.read(path, ReadOptions.TAGS_ONLY);
    assertEquals(List.of(comment, comment, comment), cleared.properties().get("COMMENT"));
    assertEquals(comment, cleared.tag().comment());
    assertTrue(cleared.pictures().isEmpty());
  }

  @Test
  void utf16EmptyCommentDescriptionTakesPriorityOverEarlierNamedComment() throws Exception {
    byte[] named =
      frame(3, "COMM", 0, "\0engSESSION\0Named comment".getBytes(StandardCharsets.ISO_8859_1));
    byte[] plain =
      frame(
        3,
        "COMM",
        0,
        new byte[]{
          1,
          'e',
          'n',
          'g',
          (byte) 255,
          (byte) 254,
          0,
          0,
          (byte) 255,
          (byte) 254,
          'M',
          0,
          'a',
          0,
          'i',
          0,
          'n',
          0
        });
    var read = Id3v2.read(tag(3, named, plain), ReadOptions.DEFAULT);
    assertEquals("Named comment", read.properties().first("COMMENT:SESSION"));
    assertEquals("Main", read.properties().first("COMMENT"));
    assertEquals("Main", read.basic().comment());
  }

  @Test
  void malformedFrameLengthsDoNotAllocateOrReadOutsideTag() throws Exception {
    byte[] huge = frame(3, "TIT2", 0, new byte[]{0, 'A'});
    Arrays.fill(huge, 4, 8, (byte) 255);
    assertTrue(Id3v2.read(tag(3, huge), ReadOptions.DEFAULT).properties().isEmpty());
    byte[] invalid = tag(4);
    invalid[6] = (byte) 128;
    assertTrue(Id3v2.read(invalid, ReadOptions.DEFAULT).empty(), "Native invalid header keeps its default empty tag");
    assertThrows(IOException.class, () -> Id3v2.parseForEditing(invalid));
  }

  @Test
  void mpegReadsXingAndWritesWithoutChangingAudio() throws Exception {
    byte[] audio = new byte[417 * 4];
    for (int i = 0; i < 4; i++) {
      int o = i * 417;
      audio[o] = (byte) 255;
      audio[o + 1] = (byte) 251;
      audio[o + 2] = (byte) 144;
    }
    System.arraycopy("Xing".getBytes(StandardCharsets.ISO_8859_1), 0, audio, 36, 4);
    audio[43] = 3;
    audio[47] = 4;
    audio[50] = 6;
    audio[51] = (byte) 132;
    Path path = directory.resolve("audio.mp3");
    Files.write(path, audio);
    var codec = new MpegCodec();
    var parsed = codec.read(path, ReadOptions.DEFAULT);
    assertEquals(44100, parsed.audioProperties().sampleRate());
    assertEquals(2, parsed.audioProperties().channels());
    assertEquals(104, parsed.audioProperties().lengthInMilliseconds());
    var properties = PropertyMap.builder().put("TITLE", "New metadata").build();
    codec.write(path, properties);
    assertEquals(properties, codec.read(path, ReadOptions.DEFAULT).properties());
    byte[] changed = Files.readAllBytes(path);
    int offset = Id3v2.tagSize(changed);
    assertArrayEquals(audio, Arrays.copyOfRange(changed, offset, changed.length - 128));
    assertEquals(
      properties, Id3v1.read(Arrays.copyOfRange(changed, changed.length - 128, changed.length)));
    codec.write(path, PropertyMap.EMPTY);
    assertArrayEquals(audio, Files.readAllBytes(path));
    assertTrue(codec.read(path, ReadOptions.DEFAULT).properties().isEmpty());
  }

  @Test
  void clearingMpegId3LeavesApeTagAvailableAsPrimaryProperties() throws Exception {
    var apeProperties = PropertyMap.builder().put("TITLE", "APE title").build();
    byte[] ape =
      ApeTag.render(
        apeProperties, new ApeTag.Data(0, 0, PropertyMap.EMPTY, List.of(), List.of()));
    var bytes = new ByteArrayOutputStream();
    bytes.writeBytes(Id3v2.render(PropertyMap.builder().put("TITLE", "ID3 title").build(), null));
    bytes.writeBytes(ape);
    bytes.writeBytes(Id3v1.render(PropertyMap.builder().put("TITLE", "Legacy title").build()));
    Path path = directory.resolve("ape-fallback.mp3");
    Files.write(path, bytes.toByteArray());
    var codec = new MpegCodec();
    codec.write(path, PropertyMap.EMPTY);
    assertArrayEquals(ape, Files.readAllBytes(path));
    assertEquals(apeProperties, codec.read(path, ReadOptions.TAGS_ONLY).properties());
  }

  @Test
  void commonTagFallsBackPerFieldWhilePropertiesUsePrimaryTag() throws Exception {
    byte[] modern =
      Id3v2.render(
        PropertyMap.builder()
          .put("TITLE", "Modern title")
          .put("COMMENT:SESSION", "Studio notes")
          .build(),
        null);
    byte[] legacy = Id3v1.render(PropertyMap.builder().put("ARTIST", "Legacy artist").build());
    Path path = directory.resolve("mixed.mp3");
    var bytes = new ByteArrayOutputStream();
    bytes.writeBytes(modern);
    bytes.writeBytes(legacy);
    Files.write(path, bytes.toByteArray());
    var read = new MpegCodec().read(path, ReadOptions.TAGS_ONLY);
    assertFalse(read.properties().containsKey("ARTIST"));
    assertEquals("Legacy artist", read.tag().artist());
    assertEquals("Studio notes", read.tag().comment());
    byte[] opaque = tag(4, frame(4, "PRIV", 0, new byte[]{'a', 0, 9}));
    bytes.reset();
    bytes.writeBytes(opaque);
    bytes.writeBytes(legacy);
    Files.write(path, bytes.toByteArray());
    read = new MpegCodec().read(path, ReadOptions.TAGS_ONLY);
    assertTrue(read.properties().isEmpty());
    assertEquals("Legacy artist", read.tag().artist());
  }

  @Test
  void id3v1TrackAndTrimming() {
    var properties =
      PropertyMap.builder()
        .put("TITLE", "Title")
        .put("ARTIST", "Artist")
        .put("ALBUM", "Album")
        .put("DATE", "1999")
        .put("COMMENT", "Comment")
        .put("TRACKNUMBER", "7")
        .put("GENRE", "Rock")
        .build();
    assertEquals(properties, Id3v1.read(Id3v1.render(properties)));
  }

  private static byte[] tag(int version, byte[]... frames) {
    var body = new ByteArrayOutputStream();
    for (byte[] frame : frames) body.writeBytes(frame);
    byte[] bytes = new byte[10 + body.size()];
    bytes[0] = 'I';
    bytes[1] = 'D';
    bytes[2] = '3';
    bytes[3] = (byte) version;
    sync(bytes, 6, body.size());
    System.arraycopy(body.toByteArray(), 0, bytes, 10, body.size());
    return bytes;
  }

  private static byte[] frame(int version, String id, int flags, byte[] data) {
    int header = version == 2 ? 6 : 10;
    byte[] bytes = new byte[header + data.length];
    System.arraycopy(id.getBytes(StandardCharsets.ISO_8859_1), 0, bytes, 0, id.length());
    if (version == 4) sync(bytes, 4, data.length);
    else {
      int size = version == 2 ? 3 : 4;
      for (int i = 0; i < size; i++)
        bytes[id.length() + size - 1 - i] = (byte) (data.length >>> (8 * i));
    }
    if (version != 2) {
      bytes[8] = (byte) (flags >>> 8);
      bytes[9] = (byte) flags;
    }
    System.arraycopy(data, 0, bytes, header, data.length);
    return bytes;
  }

  private static void sync(byte[] data, int offset, int value) {
    for (int i = 3; i >= 0; i--) {
      data[offset + i] = (byte) (value & 127);
      value >>>= 7;
    }
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    outer:
    for (int i = 0; i <= haystack.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
      return i;
    }
    return -1;
  }
}
