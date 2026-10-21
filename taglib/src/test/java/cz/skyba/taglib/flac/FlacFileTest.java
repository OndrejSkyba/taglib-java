package cz.skyba.taglib.flac;

import cz.skyba.taglib.*;
import cz.skyba.taglib.internal.TextBytes;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.Variant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cz.skyba.taglib.AudioFixtures.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent assertions corresponding to all 32 pinned TestFLAC registrations.
 */
class FlacFileTest {
  @TempDir
  Path dir;

  Path copy(String name) throws Exception {
    return AudioFixtures.copy(dir, name);
  }

  static Picture newPicture() {
    var p = new Picture();
    p.setType(Picture.Type.BackCover);
    p.setWidth(5);
    p.setHeight(6);
    p.setColorDepth(16);
    p.setNumColors(7);
    p.setMimeType("image/jpeg");
    p.setDescription("new image");
    p.setData(new ByteVector("JPEG data").toByteArray());
    return p;
  }

  static void pixel(Picture p) {
    assertEquals(Picture.Type.FrontCover, p.type());
    assertEquals(1, p.width());
    assertEquals(1, p.height());
    assertEquals(24, p.colorDepth());
    assertEquals(0, p.numColors());
    assertEquals("image/png", p.mimeType());
    assertEquals("A pixel.", p.description());
    assertEquals(150, p.data().length);
  }

  static void added(Picture p) {
    assertEquals(Picture.Type.BackCover, p.type());
    assertEquals(5, p.width());
    assertEquals(6, p.height());
    assertEquals(16, p.colorDepth());
    assertEquals(7, p.numColors());
    assertEquals("image/jpeg", p.mimeType());
    assertEquals("new image", p.description());
    assertArrayEquals(new ByteVector("JPEG data").toByteArray(), p.data());
  }

  @Test
  void testSignature() throws IOException {
    try (var f = new FlacFile(fixture("no-tags.flac"))) {
      assertEquals("a1b141f766e9849ac3db1030a20a3c77", new ByteVector(f.detailedProperties().signature()).toHex().toString());
    }
  }

  @Test
  void testMultipleCommentBlocks() throws Exception {
    var path = copy("multiple-vc.flac");
    try (var f = new FlacFile(path)) {
      assertEquals("Artist 1", f.tag().artist());
      f.tag().setArtist("The Artist");
      f.save();
    }
    try (var f = new FlacFile(path)) {
      assertEquals("The Artist", f.tag().artist());
      assertEquals(69, f.find(new ByteVector("Artist")));
      assertEquals(-1, f.find(new ByteVector("Artist"), 70));
    }
  }

  @Test
  void testReadPicture() throws Exception {
    try (var f = new FlacFile(copy("silence-44-s.flac"))) {
      assertEquals(1, f.pictureList().size());
      pixel(f.pictureList().getFirst());
    }
  }

  @Test
  void testAddPicture() throws Exception {
    var path = copy("silence-44-s.flac");
    try (var f = new FlacFile(path)) {
      assertEquals(1, f.pictureList().size());
      f.addPicture(newPicture());
      f.save();
    }
    try (var f = new FlacFile(path)) {
      assertEquals(2, f.pictureList().size());
      pixel(f.pictureList().get(0));
      added(f.pictureList().get(1));
    }
  }

  @Test
  void testReplacePicture() throws Exception {
    var path = copy("silence-44-s.flac");
    try (var f = new FlacFile(path)) {
      assertEquals(1, f.pictureList().size());
      f.removePictures();
      f.addPicture(newPicture());
      f.save();
    }
    try (var f = new FlacFile(path)) {
      assertEquals(1, f.pictureList().size());
      added(f.pictureList().getFirst());
    }
  }

  @Test
  void testRemoveAllPictures() throws Exception {
    var path = copy("silence-44-s.flac");
    try (var f = new FlacFile(path)) {
      assertEquals(1, f.pictureList().size());
      f.removePictures();
      f.save();
    }
    try (var f = new FlacFile(path)) {
      assertEquals(0, f.pictureList().size());
    }
  }

  @Test
  void testRepeatedSave1() throws Exception {
    var path = copy("silence-44-s.flac");
    try (var f = new FlacFile(path)) {
      assertEquals("Silence", f.tag().title());
      f.tag().setTitle("NEW TITLE");
      f.save();
      assertEquals("NEW TITLE", f.tag().title());
      f.tag().setTitle("NEW TITLE 2");
      f.save();
      assertEquals("NEW TITLE 2", f.tag().title());
    }
    try (var f = new FlacFile(path)) {
      assertEquals("NEW TITLE 2", f.tag().title());
    }
  }

  @Test
  void testRepeatedSave2() throws Exception {
    try (var f = new FlacFile(copy("no-tags.flac"))) {
      f.getOrCreateId3v2Tag().setTitle("0123456789");
      f.save();
      assertEquals(5735, f.length());
      f.save();
      assertEquals(5735, f.length());
      assertTrue(f.find(new ByteVector("fLaC")) >= 0);
    }
  }

  @Test
  void testRepeatedSave3() throws Exception {
    try (var f = new FlacFile(copy("no-tags.flac"))) {
      f.xiphComment().setTitle("x".repeat(8192));
      f.save();
      assertEquals(12862, f.length());
      f.save();
      assertEquals(12862, f.length());
    }
  }

  @Test
  void testSaveMultipleValues() throws Exception {
    var path = copy("silence-44-s.flac");
    try (var f = new FlacFile(path)) {
      f.getOrCreateXiphComment().addField("ARTIST", "artist 1", true);
      f.getOrCreateXiphComment().addField("ARTIST", "artist 2", false);
      f.save();
    }
    try (var f = new FlacFile(path)) {
      assertEquals(List.of("artist 1", "artist 2"), f.xiphComment().fieldListMap().get("ARTIST"));
    }
  }

  @Test
  void testDict() throws Exception {
    var path = copy("silence-44-s.flac");
    var map = PropertyMap.builder().put("ARTIST", List.of("artøst 1", "artöst 2")).build();
    try (var f = new FlacFile(path)) {
      f.replaceProperties(map);
      f.save();
    }
    try (var f = new FlacFile(path)) {
      assertEquals(map, f.properties());
    }
  }

  @Test
  void testProperties() throws Exception {
    var map = PropertyMap.builder();
    String[][] fields = {{"ALBUM", "Album"}, {"ALBUMARTIST", "Album Artist"}, {"ALBUMARTISTSORT", "Album Artist Sort"}, {"ALBUMSORT", "Album Sort"}, {"ARTIST", "Artist"}, {"ARTISTS", "Artists"}, {"ARTISTSORT", "Artist Sort"}, {"ASIN", "ASIN"}, {"BARCODE", "Barcode"}, {"COMMENT", "Comment"}, {"DATE", "2021-01-10"}, {"DISCNUMBER", "3"}, {"DISCTOTAL", "5"}, {"GENRE", "Genre"}, {"ISRC", "UKAAA0500001"}, {"MEDIA", "Media"}, {"MUSICBRAINZ_ALBUMARTISTID", "MusicBrainz_AlbumartistID"}, {"MUSICBRAINZ_ALBUMID", "MusicBrainz_AlbumID"}, {"MUSICBRAINZ_ARTISTID", "MusicBrainz_ArtistID"}, {"MUSICBRAINZ_RELEASEGROUPID", "MusicBrainz_ReleasegroupID"}, {"MUSICBRAINZ_RELEASETRACKID", "MusicBrainz_ReleasetrackID"}, {"MUSICBRAINZ_TRACKID", "MusicBrainz_TrackID"}, {"ORIGINALDATE", "2021-01-09"}, {"RELEASECOUNTRY", "Release Country"}, {"RELEASESTATUS", "Release Status"}, {"RELEASETYPE", "Release Type"}, {"SCRIPT", "Script"}, {"TITLE", "Title"}, {"TRACKNUMBER", "2"}, {"TRACKTOTAL", "4"}};
    for (var v : fields) map.put(v[0], v[1]);
    map.put("CATALOGNUMBER", List.of("Catalog Number 1", "Catalog Number 2")).put("LABEL", List.of("Label 1", "Label 2"));
    var expected = map.build();
    var path = copy("no-tags.flac");
    try (var f = new FlacFile(path)) {
      assertTrue(f.properties().isEmpty());
      f.replaceProperties(expected);
      f.save();
    }
    try (var f = new FlacFile(path)) {
      assertEquals(expected, f.properties());
    }
  }

  @Test
  void testInvalid() throws Exception {
    try (var f = new FlacFile(copy("silence-44-s.flac"))) {
      var rejected = f.replaceProperties(PropertyMap.builder().put("HÄÖ", "bla").build()).rejected();
      assertEquals(1, rejected.asMap().size());
      assertEquals(0, f.properties().asMap().size());
    }
  }

  @Test
  void testAudioProperties() throws IOException {
    try (var f = new FlacFile(fixture("sinewave.flac"))) {
      var p = f.detailedProperties();
      assertNotNull(p);
      assertEquals(3, p.lengthInSeconds());
      assertEquals(3550, p.lengthInMilliseconds());
      assertEquals(145, p.bitrate());
      assertEquals(44100, p.sampleRate());
      assertEquals(2, p.channels());
      assertEquals(16, p.bitsPerSample());
      assertEquals(156556, p.sampleFrames());
      assertEquals("cfe3d9dabadeab2cbf2ca235274b7f76", new ByteVector(p.signature()).toHex().toString());
    }
  }

  @Test
  void testZeroSizedPadding1() throws Exception {
    try (var f = new FlacFile(copy("zero-sized-padding.flac"))) {
      assertTrue(f.isValid());
    }
  }

  @Test
  void testZeroSizedPadding2() throws Exception {
    var path = copy("silence-44-s.flac");
    try (var f = new FlacFile(path)) {
      f.xiphComment().setTitle("ABC");
      f.save();
    }
    try (var f = new FlacFile(path)) {
      f.xiphComment().setTitle("X".repeat(3067));
      f.save();
    }
    try (var f = new FlacFile(path)) {
      assertTrue(f.isValid());
    }
  }

  @Test
  void testShrinkPadding() throws Exception {
    var path = copy("no-tags.flac");
    try (var f = new FlacFile(path)) {
      f.xiphComment().setTitle("x".repeat(128 * 1024));
      f.save();
      assertTrue(f.length() > 128 * 1024);
    }
    try (var f = new FlacFile(path)) {
      f.xiphComment().setTitle("0123456789");
      f.save();
      assertTrue(f.length() < 8192);
    }
  }

  @Test
  void testSaveID3v1() throws Exception {
    try (var f = new FlacFile(copy("no-tags.flac"))) {
      assertFalse(f.hasId3v1Tag());
      assertEquals(4692, f.length());
      f.seek(256);
      var audio = f.readBlock(4436);
      f.getOrCreateId3v1Tag().setTitle("01234 56789 ABCDE FGHIJ");
      f.save();
      assertTrue(f.hasId3v1Tag());
      assertEquals(4820, f.length());
      f.seek(256);
      assertEquals(audio, f.readBlock(4436));
    }
  }

  @Test
  void testUpdateID3v2() throws Exception {
    var path = copy("no-tags.flac");
    try (var f = new FlacFile(path)) {
      f.getOrCreateId3v2Tag().setTitle("0123456789");
      f.save();
    }
    try (var f = new FlacFile(path)) {
      f.id3v2Tag().setTitle("ABCDEFGHIJ");
      f.save();
    }
    try (var f = new FlacFile(path)) {
      assertEquals("ABCDEFGHIJ", f.id3v2Tag().title());
    }
  }

  @Test
  void testEmptyID3v2() throws Exception {
    var path = copy("no-tags.flac");
    try (var f = new FlacFile(path)) {
      f.getOrCreateId3v2Tag();
      f.save();
    }
    try (var f = new FlacFile(path)) {
      assertFalse(f.hasId3v2Tag());
    }
  }

  @Test
  void testRemoveTagsStagesStorageChanges() throws Exception {
    var path = copy("silence-44-s.flac");
    try (var f = new FlacFile(path)) {
      f.getOrCreateXiphComment().setTitle("XiphComment Title");
      f.getOrCreateId3v1Tag().setTitle("ID3v1 Title");
      f.getOrCreateId3v2Tag().setTitle("ID3v2 Title");
      f.save();
    }
    for (var mask : List.of(Set.of(FlacFile.TagType.ID3V2), Set.of(FlacFile.TagType.ID3V1), Set.of(FlacFile.TagType.XIPH_COMMENT)))
      try (var f = new FlacFile(path)) {
        assertTrue(f.hasXiphComment());
        assertEquals("XiphComment Title", f.xiphComment().title());
        assertEquals(mask.equals(Set.of(FlacFile.TagType.ID3V2)), f.hasId3v2Tag());
        assertEquals(!mask.equals(Set.of(FlacFile.TagType.XIPH_COMMENT)), f.hasId3v1Tag());
        if (f.hasId3v1Tag()) assertEquals("ID3v1 Title", f.id3v1Tag().title());
        if (f.hasId3v2Tag()) assertEquals("ID3v2 Title", f.id3v2Tag().title());
        byte[] beforeRemoval = Files.readAllBytes(path);
        f.removeTags(mask);
        assertArrayEquals(beforeRemoval, Files.readAllBytes(path));
        f.save();
      }
    try (var f = new FlacFile(path)) {
      assertTrue(f.hasXiphComment());
      assertFalse(f.hasId3v1Tag());
      assertFalse(f.hasId3v2Tag());
      assertTrue(f.xiphComment().isEmpty());
      assertEquals("reference libFLAC 1.1.0 20030126", f.xiphComment().vendorID());
    }
  }

  @Test
  void testRemoveXiphField() throws Exception {
    var path = copy("silence-44-s.flac");
    try (var f = new FlacFile(path)) {
      f.getOrCreateXiphComment().setTitle("XiphComment Title");
      f.getOrCreateId3v2Tag().setTitle("ID3v2 Title");
      f.save();
    }
    try (var f = new FlacFile(path)) {
      assertEquals("XiphComment Title", f.xiphComment().title());
      f.xiphComment().removeFields("TITLE");
      f.save();
    }
    try (var f = new FlacFile(path)) {
      assertEquals("", f.xiphComment().title());
    }
  }

  @Test
  void testEmptySeekTable() throws Exception {
    var path = copy("empty-seektable.flac");
    try (var f = new FlacFile(path)) {
      assertTrue(f.isValid());
      f.getOrCreateXiphComment().setTitle("XiphComment Title");
      f.save();
    }
    try (var f = new FlacFile(path)) {
      assertTrue(f.isValid());
      f.seek(42);
      assertEquals(new ByteVector(new byte[]{3, 0, 0, 0}), f.readBlock(4));
    }
  }

  // The exact pinned PNG and expected prefix are added below from the reviewed assertion constants.
  static final ByteVector PNG = new ByteVector(HexFormat.of().parseHex("89504e470d0a1a0a0000000d49484452000000030000000208060000009d74661a000000017352474200aece1ce90000000467414d410000b18f0bfc6105000000097048597300000ec300000ec301c76fa8640000000c49444154185763c001181800001a000182924d600000000049454e44ae426082"));
  static final ByteVector PREFIX = new ByteVector(HexFormat.of().parseHex("664c6143000000221200120000000e0000100ac442f000027ac0a1b141f766e9849ac3db1030a20a3c770400001700000000010000000b0000005449544c453d5469746c65060000a90000000300000009696d6167652f706e6700000009626c616e6b2e706e670000000300000002000000200000000000000077"));

  @Test
  void testPictureStoredAfterComment() throws Exception {
    var path = copy("no-tags.flac");
    try (var f = new FlacFile(path)) {
      assertFalse(f.hasId3v1Tag());
      assertFalse(f.hasId3v2Tag());
      assertFalse(f.hasXiphComment());
      assertTrue(f.pictureList().isEmpty());
      var p = new Picture();
      p.setData(PNG.toByteArray());
      p.setType(Picture.Type.FrontCover);
      p.setMimeType("image/png");
      p.setDescription("blank.png");
      p.setWidth(3);
      p.setHeight(2);
      p.setColorDepth(32);
      p.setNumColors(0);
      f.addPicture(p);
      f.getOrCreateXiphComment().setTitle("Title");
      f.save();
    }
    try (var f = new FlacFile(path)) {
      assertFalse(f.hasId3v1Tag());
      assertFalse(f.hasId3v2Tag());
      assertTrue(f.hasXiphComment());
      assertEquals(1, f.pictureList().size());
      var p = f.pictureList().getFirst();
      assertArrayEquals(PNG.toByteArray(), p.data());
      assertEquals(Picture.Type.FrontCover, p.type());
      assertEquals("image/png", p.mimeType());
      assertEquals("blank.png", p.description());
      assertEquals(3, p.width());
      assertEquals(2, p.height());
      assertEquals(32, p.colorDepth());
      assertEquals(0, p.numColors());
      assertEquals("Title", f.xiphComment().title());
    }
    assertTrue(new ByteVector(Files.readAllBytes(path)).startsWith(new ByteVector(PREFIX).append(PNG)));
  }

  void readApp(String id, ByteVector payload, boolean wrapped) throws IOException {
    var app = wrapped ? wrap(id, payload) : new ByteVector(id).append(payload);
    try (var f = new FlacFile(new ByteVectorStream(flacApp(app)), ReadOptions.TAGS_ONLY)) {
      assertTrue(f.isValid());
      if (id.equals("iXML")) {
        assertTrue(f.hasiXMLData());
        assertFalse(f.hasBEXTData());
        assertEquals(TextBytes.decodeTerminated(payload.data(), UTF_8), f.iXMLData());
      } else {
        assertTrue(f.hasBEXTData());
        assertFalse(f.hasiXMLData());
        assertArrayEquals(payload.toByteArray(), f.BEXTData());
      }
    }
  }

  @Test
  void testReadiXMLDirect() throws IOException {
    readApp("iXML", new ByteVector("<BWFXML><IXML_VERSION>1.0</IXML_VERSION></BWFXML>"), false);
  }

  @Test
  void testReadiXMLRiffWrapped() throws IOException {
    readApp("iXML", new ByteVector("<BWFXML><SCENE>1</SCENE></BWFXML>"), true);
  }

  @Test
  void testReadBEXTDirect() throws Exception {
    readApp("bext", new ByteVector("test bext data"), false);
  }

  @Test
  void testReadBEXTRiffWrapped() throws Exception {
    readApp("bext", new ByteVector("test bext data"), true);
  }

  @Test
  void testWriteiXMLAndBEXT() throws Exception {
    var path = copy("silence-44-s.flac");
    String xml = "<BWFXML><IXML_VERSION>1.6</IXML_VERSION></BWFXML>";
    var bext = new ByteVector("bext payload bytes");
    try (var f = new FlacFile(path)) {
      assertFalse(f.hasiXMLData());
      assertFalse(f.hasBEXTData());
      f.setiXMLData(xml);
      f.setBEXTData(bext);
      f.save();
    }
    try (var f = new FlacFile(path)) {
      assertTrue(f.hasiXMLData());
      assertTrue(f.hasBEXTData());
      assertEquals(xml, f.iXMLData());
      assertArrayEquals(bext.toByteArray(), f.BEXTData());
    }
    var bytes = new ByteVector(Files.readAllBytes(path));
    assertTrue(bytes.find(wrap("iXML", new ByteVector(xml))) >= 0);
    assertTrue(bytes.find(wrap("bext", bext)) >= 0);
  }

  @Test
  void testWriteEmptyClearsiXMLAndBEXT() throws Exception {
    var path = copy("silence-44-s.flac");
    try (var f = new FlacFile(path)) {
      f.setiXMLData("<BWFXML/>");
      f.setBEXTData(new ByteVector("bext"));
      f.save();
    }
    try (var f = new FlacFile(path)) {
      assertTrue(f.hasiXMLData());
      assertTrue(f.hasBEXTData());
      f.setiXMLData("");
      f.setBEXTData(new ByteVector());
      f.save();
    }
    try (var f = new FlacFile(path)) {
      assertFalse(f.hasiXMLData());
      assertFalse(f.hasBEXTData());
      assertTrue(f.iXMLData().isEmpty());
      assertEquals(0, f.BEXTData().length);
    }
  }

  @Test
  void testHasiXMLAndBEXTReflectFileState() throws Exception {
    var path = copy("silence-44-s.flac");
    try (var f = new FlacFile(path)) {
      assertFalse(f.hasiXMLData());
      assertFalse(f.hasBEXTData());
      f.setiXMLData("<BWFXML/>");
      f.setBEXTData(new ByteVector("bext"));
      assertFalse(f.hasiXMLData());
      assertFalse(f.hasBEXTData());
      f.save();
      assertTrue(f.hasiXMLData());
      assertTrue(f.hasBEXTData());
    }
    try (var f = new FlacFile(path)) {
      assertTrue(f.hasiXMLData());
      assertTrue(f.hasBEXTData());
      f.setiXMLData("");
      f.setBEXTData(new ByteVector());
      assertTrue(f.hasiXMLData());
      assertTrue(f.hasBEXTData());
      f.save();
      assertFalse(f.hasiXMLData());
      assertFalse(f.hasBEXTData());
    }
    for (String id : List.of("bext", "iXML"))
      try (var f = new FlacFile(new ByteVectorStream(flacApp(new ByteVector(id))), ReadOptions.TAGS_ONLY)) {
        assertTrue(id.equals("bext") ? f.hasBEXTData() : f.hasiXMLData());
      }
  }

  @Test
  void testRoundTripPreservesUnknownApplicationBlock() throws IOException {
    var stream = new ByteVectorStream(flacApp(new ByteVector("SMEDopaque sequoia metadata payload")));
    try (var f = new FlacFile(stream, ReadOptions.TAGS_ONLY)) {
      assertTrue(f.isValid());
      assertFalse(f.hasiXMLData());
      f.setiXMLData("<BWFXML/>");
      f.save();
    }
    assertTrue(stream.data().find(new ByteVector("SMED")) >= 0);
    assertTrue(stream.data().find(new ByteVector("opaque sequoia metadata payload")) >= 0);
    try (var f = new FlacFile(new ByteVectorStream(stream.data()), ReadOptions.TAGS_ONLY)) {
      assertTrue(f.isValid());
      assertTrue(f.hasiXMLData());
      assertEquals("<BWFXML/>", f.iXMLData());
    }
  }

  @Test
  void fileRefUsesActualOwnerAndKeepsChildrenThroughSaves() throws Exception {
    try (var ref = new AudioFile(copy("no-tags.flac"))) {
      var f = assertInstanceOf(FlacFile.class, ref.file());
      var x = f.xiphComment();
      var union = ref.tag();
      assertSame(f.tag(), union);
      x.setTitle("live");
      assertEquals("live", ref.tag().title());
      assertFalse(f.hasXiphComment());
      assertDoesNotThrow(ref::save);
      assertDoesNotThrow(ref::save);
      assertSame(x, f.xiphComment());
      assertSame(union, ref.tag());
    }
  }

  @Test
  void physicalPicturesAndEmbeddedCommentPicturesHaveDistinctComplexSurfaces() throws IOException {
    var stream = new ByteVectorStream(flacApp(new ByteVector("SMEDopaque")));
    try (var f = new FlacFile(stream, ReadOptions.TAGS_ONLY)) {
      var physical = newPicture();
      var embedded = newPicture();
      embedded.setDescription("embedded");
      f.addPicture(physical);
      f.xiphComment().addPicture(embedded);
      assertEquals(List.of("PICTURE"), f.complexPropertyKeys());
      assertEquals("new image", f.complexProperties("picture").getFirst().get("description").toText());
      assertTrue(f.setComplexProperties("PICTURE", List.of(Map.of("width", new Variant("wrong type")))));
      assertEquals(0, f.pictureList().getFirst().width());
      assertSame(embedded, f.xiphComment().pictureList().getFirst());
      f.save();
    }
  }

  @Test
  void shortenedMemoryRejectsBeforeBytesAndChildStateChange() throws Exception {
    var stream = new ByteVectorStream(Files.readAllBytes(fixture("no-tags.flac")));
    try (var f = new FlacFile(stream)) {
      var x = f.xiphComment();
      x.setTitle("retained");
      stream.truncate(8);
      var bytes = stream.data().toByteArray();
      assertThrows(TagLibException.class, f::save);
      assertArrayEquals(bytes, stream.data().toByteArray());
      assertFalse(f.hasXiphComment());
      assertSame(x, f.xiphComment());
      assertEquals("retained", x.title());
    }
  }

  @Test
  void boundedAggregateMetadataRejectsBeforeAllocatingPayload() {
    var bytes = new ByteVector("fLaC").append(flacBlock(0, new ByteVector(34))).append(flacBlock(130, new ByteVector("SMED")));
    var stream = new ByteVectorStream(bytes);
    assertThrows(IllegalArgumentException.class, () -> new FlacFile(stream, new ReadOptions(false, ReadStyle.AVERAGE, 36)));
    assertTrue(stream.isOpen());
  }

  @Test
  void invalidMissingCommentThrowsOnOpen() throws IOException {
    assertThrows(TagLibException.class,
      () -> new FlacFile(new ByteVectorStream(new ByteVector("fLaC")), ReadOptions.TAGS_ONLY));
  }

  @Test
  void repeatedInPlaceSaveKeepsLiveChildren() throws Exception {
    var path = copy("no-tags.flac");
    try (var f = new FlacFile(path)) {
      var x = f.xiphComment();
      x.setTitle("first");
      f.save();
      assertSame(x, f.xiphComment());
      x.setTitle("in-place");
      f.save();
    }
    try (var f = new FlacFile(path)) {
      assertEquals("in-place", f.tag().title());
    }
  }

  @Test
  void invalidPictureUtf8UsesNativeEmptyStringsInsteadOfReplacementCharacters() {
    var raw = ByteVector.fromUnsignedInt(3, true).append(ByteVector.fromUnsignedInt(1, true)).append(new ByteVector(new byte[]{(byte) 255})).append(ByteVector.fromUnsignedInt(1, true)).append(new ByteVector(new byte[]{(byte) 255})).append(new ByteVector(20));
    var p = new Picture();
    assertTrue(p.parse(raw.toByteArray()));
    assertEquals("", p.mimeType());
    assertEquals("", p.description());
    assertEquals(32, p.render().length);
  }

  @Test
  void failedRenderingPreservesOriginalAndAllowsCorrectedSave() throws Exception {
    var path = copy("no-tags.flac");
    var original = Files.readAllBytes(path);
    try (var f = new FlacFile(path)) {
      f.addPicture(new Picture() {
        @Override
        public byte[] render() {
          throw new IllegalStateException("ordinary callback failure");
        }
      });
      assertThrows(IllegalStateException.class, f::save);
      assertArrayEquals(original, Files.readAllBytes(path));
      assertTrue(f.isValid());
      f.removePictures();
      f.save();
    }
  }

  @Test
  void nullFactoryUsesDefaultForNewId3Frames() throws Exception {
    var path = copy("no-tags.flac");
    try (var f = new FlacFile(path, ReadOptions.TAGS_ONLY, null)) {
      assertTrue(f.isValid());
      assertNotNull(f.getOrCreateId3v2Tag());
      f.id3v2Tag().setTitle("default factory");
      assertFalse(f.hasId3v2Tag());
      f.save();
    }
    try (var f = new FlacFile(path)) {
      assertEquals("default factory", f.id3v2Tag().title());
    }
  }

  @Test
  void inPlaceSaveFollowsSymlinkAndRetainsTheLink() throws Exception {
    var target = copy("no-tags.flac");
    var link = dir.resolve("alias.flac");
    Files.createSymbolicLink(link, target.getFileName());
    try (var f = new FlacFile(link)) {
      f.xiphComment().setTitle("through link");
      f.save();
      assertTrue(Files.isSymbolicLink(link));
    }
    try (var f = new FlacFile(target)) {
      assertEquals("through link", f.tag().title());
    }
  }

}
