package cz.skyba.taglib.asf;

import cz.skyba.taglib.*;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent assertions ported from all 15 pinned TestASF registrations plus value-state regressions.
 */
class AsfMutableTest {
  @TempDir
  Path directory;

  static Path fixture(String name) {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("taglib/src"))) root = root.getParent();
    assertNotNull(root);
    return UpstreamAudioFixtures.upstreamSource(root).resolve("tests/data/" + name);
  }

  Path copy(String name) throws Exception {
    Path p = directory.resolve(name);
    Files.copy(fixture(name), p);
    return p;
  }

  @Test
  void nativeAudioProperties() throws IOException {
    try (var f = new AsfFile(fixture("silence-1.wma"))) {
      var p = f.detailedProperties();
      assertNotNull(p);
      assertEquals(3, p.lengthInSeconds());
      assertEquals(3712, p.lengthInMilliseconds());
      assertEquals(64, p.bitrate());
      assertEquals(2, p.channels());
      assertEquals(48000, p.sampleRate());
      assertEquals(16, p.bitsPerSample());
      assertEquals(Properties.Codec.WMA2, p.codec());
      assertEquals("Windows Media Audio 9.1", p.codecName());
      assertEquals("64 kbps, 48 kHz, stereo 2-pass CBR", p.codecDescription());
      assertFalse(p.isEncrypted());
    }
  }

  @Test
  void nativeLosslessProperties() throws IOException {
    try (var f = new AsfFile(fixture("lossless.wma"))) {
      var p = f.detailedProperties();
      assertNotNull(p);
      assertEquals(3, p.lengthInSeconds());
      assertEquals(3549, p.lengthInMilliseconds());
      assertEquals(1152, p.bitrate());
      assertEquals(2, p.channels());
      assertEquals(44100, p.sampleRate());
      assertEquals(16, p.bitsPerSample());
      assertEquals(Properties.Codec.WMA9Lossless, p.codec());
      assertEquals("Windows Media Audio 9.2 Lossless", p.codecName());
      assertEquals("VBR Quality 100, 44 kHz, 2 channel 16 bit 1-pass VBR", p.codecDescription());
      assertFalse(p.isEncrypted());
    }
  }

  @Test
  void nativeRead() throws IOException {
    try (var f = new AsfFile(fixture("silence-1.wma"))) {
      assertEquals("test", f.tag().title());
    }
  }

  @Test
  void nativeSaveMultipleValues() throws Exception {
    Path p = copy("silence-1.wma");
    try (var f = new AsfFile(p)) {
      f.tag().setAttribute("WM/AlbumTitle", List.of(new Attribute("Foo"), new Attribute("Bar")));
      f.save();
    }
    try (var f = new AsfFile(p)) {
      assertEquals(2, f.tag().attributeListMap().get("WM/AlbumTitle").size());
    }
  }

  @Test
  void nativeSaveStream() throws Exception {
    Path p = copy("silence-1.wma");
    try (var f = new AsfFile(p)) {
      var a = new Attribute("Foo");
      a.setStream(43);
      f.tag().setAttribute("WM/AlbumTitle", a);
      f.save();
    }
    try (var f = new AsfFile(p)) {
      assertEquals(43, f.tag().attribute("WM/AlbumTitle").getFirst().stream());
    }
  }

  @Test
  void nativeSaveLanguage() throws Exception {
    Path p = copy("silence-1.wma");
    try (var f = new AsfFile(p)) {
      var a = new Attribute("Foo");
      a.setStream(32);
      a.setLanguage(56);
      f.tag().setAttribute("WM/AlbumTitle", a);
      f.save();
    }
    try (var f = new AsfFile(p)) {
      var a = f.tag().attribute("WM/AlbumTitle").getFirst();
      assertEquals(32, a.stream());
      assertEquals(56, a.language());
    }
  }

  @Test
  void nativeDWordTrackNumber() throws Exception {
    Path p = copy("silence-1.wma");
    try (var f = new AsfFile(p)) {
      assertFalse(f.tag().contains("WM/TrackNumber"));
      f.tag().setAttribute("WM/TrackNumber", new Attribute(123));
      f.save();
    }
    try (var f = new AsfFile(p)) {
      assertTrue(f.tag().contains("WM/TrackNumber"));
      assertEquals(Attribute.DWordType, f.tag().attribute("WM/TrackNumber").getFirst().type());
      assertEquals(123, f.tag().track());
      f.tag().setTrack(234);
      f.save();
    }
    try (var f = new AsfFile(p)) {
      assertTrue(f.tag().contains("WM/TrackNumber"));
      assertEquals(Attribute.UnicodeType, f.tag().attribute("WM/TrackNumber").getFirst().type());
      assertEquals(234, f.tag().track());
    }
  }

  @Test
  void nativeSaveLargeValue() throws Exception {
    Path p = copy("silence-1.wma");
    var bytes = new ByteVector(70000, (byte) 'x');
    try (var f = new AsfFile(p)) {
      f.tag().setAttribute("WM/Blob", new Attribute(bytes));
      f.save();
    }
    try (var f = new AsfFile(p)) {
      assertEquals(bytes, f.tag().attribute("WM/Blob").getFirst().toByteVector());
    }
  }

  static Picture front() {
    var p = new Picture();
    p.setMimeType("image/jpeg");
    p.setType(Picture.Type.FrontCover);
    p.setDescription("description");
    p.setPicture(new ByteVector("data"));
    return p;
  }

  static Picture back() {
    var p = new Picture();
    p.setMimeType("image/png");
    p.setType(Picture.Type.BackCover);
    p.setDescription("back cover");
    p.setPicture(new ByteVector("PNG data"));
    return p;
  }

  static void assertPicture(Picture p, boolean front) {
    assertTrue(p.isValid());
    assertEquals(front ? "image/jpeg" : "image/png", p.mimeType());
    assertEquals(front ? Picture.Type.FrontCover : Picture.Type.BackCover, p.type());
    assertEquals(front ? "description" : "back cover", p.description());
    assertEquals(new ByteVector(front ? "data" : "PNG data"), p.picture());
  }

  @Test
  void nativeSavePicture() throws Exception {
    Path p = copy("silence-1.wma");
    try (var f = new AsfFile(p)) {
      f.tag().setAttribute("WM/Picture", new Attribute(front()));
      f.save();
    }
    try (var f = new AsfFile(p)) {
      var values = f.tag().attribute("WM/Picture");
      assertEquals(1, values.size());
      assertPicture(values.getFirst().toPicture(), true);
    }
  }

  @Test
  void nativeSaveMultiplePictures() throws Exception {
    Path p = copy("silence-1.wma");
    try (var f = new AsfFile(p)) {
      f.tag().setAttribute("WM/Picture", List.of(new Attribute(front()), new Attribute(back())));
      f.save();
    }
    try (var f = new AsfFile(p)) {
      var values = f.tag().attribute("WM/Picture");
      assertEquals(2, values.size());
      assertPicture(values.get(1).toPicture(), true);
      assertPicture(values.get(0).toPicture(), false);
    }
  }

  @Test
  void nativeProperties() throws IOException {
    try (var f = new AsfFile(fixture("silence-1.wma"))) {
      var tags = f.properties().toBuilder().put("TRACKNUMBER", "2").put("DISCNUMBER", "3").put("BPM", "123").put("ARTIST", "Foo Bar").build();
      f.replaceProperties(tags);
      assertEquals("Foo Bar", f.tag().artist());
      assertEquals(List.of("Foo Bar"), f.properties().get("ARTIST"));
      for (String[] e : new String[][]{{"WM/BeatsPerMinute", "BPM", "123"}, {"WM/TrackNumber", "TRACKNUMBER", "2"}, {"WM/PartOfSet", "DISCNUMBER", "3"}}) {
        assertTrue(f.tag().contains(e[0]));
        assertEquals(1, f.tag().attributeListMap().get(e[0]).size());
        assertEquals(e[2], f.tag().attribute(e[0]).getFirst().toString());
        assertEquals(List.of(e[2]), f.properties().get(e[1]));
      }
    }
  }

  static PropertyMap allSupported() {
    var p = PropertyMap.builder();
    p.put("ACOUSTID_ID", "Acoustid ID");
    p.put("ACOUSTID_FINGERPRINT", "Acoustid Fingerprint");
    p.put("ALBUM", "Album");
    p.put("ALBUMARTIST", "Album Artist");
    p.put("ALBUMARTISTSORT", "Album Artist Sort");
    p.put("ALBUMSORT", "Album Sort");
    p.put("ARTIST", "Artist");
    p.put("ARTISTS", "Artists");
    p.put("ARTISTSORT", "Artist Sort");
    p.put("ARTISTWEBPAGE", "Artist Webpage");
    p.put("ASIN", "ASIN");
    p.put("BARCODE", "Barcode");
    p.put("BPM", "123");
    p.put("CATALOGNUMBER", "Catalog Number");
    p.put("COMMENT", "Comment");
    p.put("COMPOSER", "Composer");
    p.put("CONDUCTOR", "Conductor");
    p.put("COPYRIGHT", "2021 Copyright");
    p.put("DATE", "2021-01-03 12:29:23");
    p.put("DISCNUMBER", "3/5");
    p.put("DISCSUBTITLE", "Disc Subtitle");
    p.put("ENCODEDBY", "Encoded by");
    p.put("ENCODING", "Encoding");
    p.put("ENCODINGTIME", "131452413620000000");
    p.put("FILEWEBPAGE", "File Webpage");
    p.put("GENRE", "Genre");
    p.put("WORK", "Grouping");
    p.put("INITIALKEY", "Initial Key");
    p.put("ISRC", "UKAAA0500001");
    p.put("LABEL", "Label");
    p.put("LANGUAGE", "eng");
    p.put("LYRICIST", "Lyricist");
    p.put("LYRICS", "Lyrics");
    p.put("MEDIA", "Media");
    p.put("MOOD", "Mood");
    p.put("MUSICBRAINZ_ALBUMARTISTID", "MusicBrainz_AlbumartistID");
    p.put("MUSICBRAINZ_ALBUMID", "MusicBrainz_AlbumID");
    p.put("MUSICBRAINZ_ARTISTID", "MusicBrainz_ArtistID");
    p.put("MUSICBRAINZ_RELEASEGROUPID", "MusicBrainz_ReleasegroupID");
    p.put("MUSICBRAINZ_RELEASETRACKID", "MusicBrainz_ReleasetrackID");
    p.put("MUSICBRAINZ_TRACKID", "MusicBrainz_TrackID");
    p.put("MUSICBRAINZ_WORKID", "MusicBrainz_WorkID");
    p.put("MUSICIP_PUID", "MusicIP PUID");
    p.put("ORIGINALALBUM", "Original Album");
    p.put("ORIGINALARTIST", "Original Artist");
    p.put("ORIGINALFILENAME", "Original Filename");
    p.put("ORIGINALLYRICIST", "Original Lyricist");
    p.put("ORIGINALDATE", "2021-01-03 13:52:19");
    p.put("PRODUCER", "Producer");
    p.put("RELEASECOUNTRY", "Release Country");
    p.put("RELEASESTATUS", "Release Status");
    p.put("RELEASETYPE", "Release Type");
    p.put("REMIXER", "Remixer");
    p.put("SCRIPT", "Script");
    p.put("SUBTITLE", "Subtitle");
    p.put("TITLE", "Title");
    p.put("TITLESORT", "Title Sort");
    p.put("TRACKNUMBER", "2/4");
    p.put("REPLAYGAIN_TRACK_GAIN", "-4.25 dB");
    p.put("REPLAYGAIN_TRACK_PEAK", "0.985412");
    p.put("REPLAYGAIN_ALBUM_GAIN", "-3.80 dB");
    p.put("REPLAYGAIN_ALBUM_PEAK", "0.998201");
    p.put("MEDIACLASSPRIMARYID", "D1607DBC-E323-4BE2-86A1-48A42A28441E");
    p.put("MEDIACLASSSECONDARYID", "F24FF731-96FC-4D0F-A2F5-5A3483682B1A");
    p.put("COLLECTIONGROUPID", "A81F04B3-2B1B-4B52-8700-6F8091DF3B41");
    p.put("COLLECTIONID", "3F2504E0-4F89-11D3-9A0C-0305E82C3301");
    p.put("CONTENTID", "6B29FC40-CA47-1067-B31D-00DD010662DA");
    p.put("CONTENTDISTRIBUTOR", "Universal Music Group");
    p.put("PARENTALRATING", "Explicit");
    p.put("PERIOD", "Baroque");
    p.put("PROMOTIONURL", "https://www.artistwebsite.com/store/album-special-edition");
    p.put("TOOLNAME", "Windows Media Encoder");
    p.put("TOOLVERSION", "9.00.00.2980");
    p.put("PROVIDER", "AMG");
    p.put("UNIQUEFILEIDENTIFIER", "AMGI_0000000000012345");
    p.put("WMFSDKVERSION", "12.0.19041.1");
    p.put("WMFSDKNEEDED", "9.0.0.4503");
    p.put("DEVICECONFORMANCETEMPLATE", "L1");
    p.put("MEDIAFOUNDATIONVERSION", "2.0");
    p.put("ISVBR", "1");
    p.put("PEAKVALUE", "32104");
    p.put("AVERAGELEVEL", "2450");
    return p.build();
  }

  @Test
  void nativePropertiesAllSupported() throws Exception {
    Path p = copy("silence-1.wma");
    try (var f = new AsfFile(p)) {
      f.tag().setTitle("");
      f.tag().attributeListMap().clear();
      f.save();
    }
    try (var f = new AsfFile(p)) {
      assertTrue(f.properties().isEmpty());
      f.replaceProperties(allSupported());
      f.save();
    }
    try (var f = new AsfFile(p)) {
      assertEquals(allSupported(), f.properties());
      assertNotNull(f.tag());
      var expected = new TreeMap<String, Integer>();
      expected.put("ASIN", Attribute.UnicodeType);
      expected.put("Acoustid/Fingerprint", Attribute.UnicodeType);
      expected.put("Acoustid/Id", Attribute.UnicodeType);
      expected.put("AverageLevel", Attribute.DWordType);
      expected.put("DeviceConformanceTemplate", Attribute.UnicodeType);
      expected.put("IsVBR", Attribute.BoolType);
      expected.put("MediaFoundationVersion", Attribute.UnicodeType);
      expected.put("MusicBrainz/Album Artist Id", Attribute.UnicodeType);
      expected.put("MusicBrainz/Album Id", Attribute.UnicodeType);
      expected.put("MusicBrainz/Album Release Country", Attribute.UnicodeType);
      expected.put("MusicBrainz/Album Status", Attribute.UnicodeType);
      expected.put("MusicBrainz/Album Type", Attribute.UnicodeType);
      expected.put("MusicBrainz/Artist Id", Attribute.UnicodeType);
      expected.put("MusicBrainz/Release Group Id", Attribute.UnicodeType);
      expected.put("MusicBrainz/Release Track Id", Attribute.UnicodeType);
      expected.put("MusicBrainz/Track Id", Attribute.UnicodeType);
      expected.put("MusicBrainz/Work Id", Attribute.UnicodeType);
      expected.put("MusicIP/PUID", Attribute.UnicodeType);
      expected.put("PeakValue", Attribute.DWordType);
      expected.put("WM/ARTISTS", Attribute.UnicodeType);
      expected.put("WM/AlbumArtist", Attribute.UnicodeType);
      expected.put("WM/AlbumArtistSortOrder", Attribute.UnicodeType);
      expected.put("WM/AlbumSortOrder", Attribute.UnicodeType);
      expected.put("WM/AlbumTitle", Attribute.UnicodeType);
      expected.put("WM/ArtistSortOrder", Attribute.UnicodeType);
      expected.put("WM/AudioFileURL", Attribute.UnicodeType);
      expected.put("WM/AuthorURL", Attribute.UnicodeType);
      expected.put("WM/Barcode", Attribute.UnicodeType);
      expected.put("WM/BeatsPerMinute", Attribute.UnicodeType);
      expected.put("WM/CatalogNo", Attribute.UnicodeType);
      expected.put("WM/Composer", Attribute.UnicodeType);
      expected.put("WM/Conductor", Attribute.UnicodeType);
      expected.put("WM/ContentDistributor", Attribute.UnicodeType);
      expected.put("WM/ContentGroupDescription", Attribute.UnicodeType);
      expected.put("WM/EncodedBy", Attribute.UnicodeType);
      expected.put("WM/EncodingSettings", Attribute.UnicodeType);
      expected.put("WM/EncodingTime", Attribute.QWordType);
      expected.put("WM/Genre", Attribute.UnicodeType);
      expected.put("WM/ISRC", Attribute.UnicodeType);
      expected.put("WM/InitialKey", Attribute.UnicodeType);
      expected.put("WM/Language", Attribute.UnicodeType);
      expected.put("WM/Lyrics", Attribute.UnicodeType);
      expected.put("WM/Media", Attribute.UnicodeType);
      expected.put("WM/MediaClassPrimaryID", Attribute.GuidType);
      expected.put("WM/MediaClassSecondaryID", Attribute.GuidType);
      expected.put("WM/ModifiedBy", Attribute.UnicodeType);
      expected.put("WM/Mood", Attribute.UnicodeType);
      expected.put("WM/OriginalAlbumTitle", Attribute.UnicodeType);
      expected.put("WM/OriginalArtist", Attribute.UnicodeType);
      expected.put("WM/OriginalFilename", Attribute.UnicodeType);
      expected.put("WM/OriginalLyricist", Attribute.UnicodeType);
      expected.put("WM/OriginalReleaseYear", Attribute.UnicodeType);
      expected.put("WM/ParentalRating", Attribute.UnicodeType);
      expected.put("WM/PartOfSet", Attribute.UnicodeType);
      expected.put("WM/Period", Attribute.UnicodeType);
      expected.put("WM/Producer", Attribute.UnicodeType);
      expected.put("WM/PromotionURL", Attribute.UnicodeType);
      expected.put("WM/Provider", Attribute.UnicodeType);
      expected.put("WM/Publisher", Attribute.UnicodeType);
      expected.put("WM/Script", Attribute.UnicodeType);
      expected.put("WM/SetSubTitle", Attribute.UnicodeType);
      expected.put("WM/SubTitle", Attribute.UnicodeType);
      expected.put("WM/TitleSortOrder", Attribute.UnicodeType);
      expected.put("WM/ToolName", Attribute.UnicodeType);
      expected.put("WM/ToolVersion", Attribute.UnicodeType);
      expected.put("WM/TrackNumber", Attribute.UnicodeType);
      expected.put("WM/UniqueFileIdentifier", Attribute.UnicodeType);
      expected.put("WM/WMCollectionGroupID", Attribute.GuidType);
      expected.put("WM/WMCollectionID", Attribute.GuidType);
      expected.put("WM/WMContentID", Attribute.GuidType);
      expected.put("WM/Writer", Attribute.UnicodeType);
      expected.put("WM/Year", Attribute.UnicodeType);
      expected.put("WMFSDKNeeded", Attribute.UnicodeType);
      expected.put("WMFSDKVersion", Attribute.UnicodeType);
      expected.put("replaygain_album_gain", Attribute.UnicodeType);
      expected.put("replaygain_album_peak", Attribute.UnicodeType);
      expected.put("replaygain_track_gain", Attribute.UnicodeType);
      expected.put("replaygain_track_peak", Attribute.UnicodeType);
      var actual = new TreeMap<String, Integer>();
      f.tag().attributeListMap().forEach((k, v) -> {
        if (!v.isEmpty()) actual.put(k, v.getFirst().type());
      });
      assertEquals(expected, actual);
    }
  }

  @Test
  void nativePropertiesRealFile() throws IOException {
    try (var f = new AsfFile(fixture("real_example.wma"))) {
      var tags = f.properties();
      assertEquals(List.of("Wake Up, Get Up, Get Out There"), tags.get("TITLE"));
      assertEquals(List.of("Shoji Meguro"), tags.get("ARTIST"));
      assertEquals(List.of("Persona 5: Sounds of Rebellion"), tags.get("ALBUM"));
      assertEquals(List.of("-8.27 dB"), tags.get("REPLAYGAIN_TRACK_GAIN"));
      assertEquals(List.of("1.000000"), tags.get("REPLAYGAIN_TRACK_PEAK"));
      assertEquals(List.of("131452413620000000"), tags.get("ENCODINGTIME"));
      assertEquals(List.of("32673"), tags.get("PEAKVALUE"));
      assertEquals(List.of("8315"), tags.get("AVERAGELEVEL"));
      assertEquals(List.of("0"), tags.get("ISVBR"));
      assertEquals(List.of("12.0.15063.332"), tags.get("WMFSDKVERSION"));
      assertEquals(List.of("0.0.0.0000"), tags.get("WMFSDKNEEDED"));
      assertEquals(List.of("User Feedback"), tags.get("PROVIDER"));
      assertEquals(List.of("L1"), tags.get("DEVICECONFORMANCETEMPLATE"));
      assertEquals(List.of("2.112"), tags.get("MEDIAFOUNDATIONVERSION"));
      assertEquals(List.of("1"), tags.get("TRACKNUMBER"));
      assertEquals(List.of("Alternative"), tags.get("GENRE"));
      assertEquals(List.of("Shoji Meguro"), tags.get("ALBUMARTIST"));
      assertEquals(List.of("00000000-0000-0000-0000-000000000000"), tags.get("COLLECTIONGROUPID"));
      assertEquals(List.of("00000000-0000-0000-0000-000000000000"), tags.get("COLLECTIONID"));
      assertEquals(List.of("00000000-0000-0000-0000-000000000000"), tags.get("CONTENTID"));
      assertEquals(List.of("BC7D60D1-23E3-E24B-86A1-48A42A28441E"), tags.get("MEDIACLASSPRIMARYID"));
      assertEquals(List.of("00000000-0000-0000-0000-000000000000"), tags.get("MEDIACLASSSECONDARYID"));
      assertEquals(List.of(";"), tags.get("UNIQUEFILEIDENTIFIER"));
    }
  }

  @Test
  void nativeCaseInsensitiveAttributeNames() throws Exception {
    Path p = copy("silence-1.wma");
    try (var f = new AsfFile(p)) {
      f.tag().setAttribute("REPLAYGAIN_TRACK_GAIN", new Attribute("-6.00 dB"));
      f.save();
    }
    try (var f = new AsfFile(p)) {
      assertEquals(List.of("-6.00 dB"), f.properties().get("REPLAYGAIN_TRACK_GAIN"));
      f.replaceProperties(f.properties().toBuilder().put("REPLAYGAIN_TRACK_GAIN", "-7.00 dB").build());
      assertFalse(f.tag().contains("REPLAYGAIN_TRACK_GAIN"));
      assertEquals("-7.00 dB", f.tag().attribute("replaygain_track_gain").getFirst().toString());
      assertEquals(List.of("-7.00 dB"), f.properties().get("REPLAYGAIN_TRACK_GAIN"));
    }
  }

  @Test
  void nativeRepeatedSave() throws Exception {
    Path p = copy("silence-1.wma");
    try (var f = new AsfFile(p)) {
      f.tag().setTitle("x".repeat(128 * 1024));
      f.save();
      assertEquals(297578, f.length());
      f.tag().setTitle("x".repeat(16 * 1024));
      f.save();
      assertEquals(68202, f.length());
    }
  }

  @Test
  void copiedValuesHaveIndependentScopeAndPictureMetadata() {
    var p = front();
    var a = new Attribute(p);
    var copy = new Attribute(a);
    copy.setLanguage(-1);
    copy.setStream(Integer.MIN_VALUE);
    assertEquals(0, a.language());
    assertEquals(0, a.stream());
    assertEquals(-1, copy.language());
    assertEquals(Integer.MIN_VALUE, copy.stream());
    p.setDescription("changed");
    assertEquals("description", a.toPicture().description());
    a.toPicture().setMimeType("image/test");
    assertEquals("image/jpeg", p.mimeType());
    assertEquals("image/jpeg", copy.toPicture().mimeType());
    var exported = a.toByteVector();
    exported.clear();
    assertFalse(a.toByteVector().isEmpty());
    var tag = new Tag();
    tag.setAttribute("WM/Picture", a);
    var list = tag.attribute("WM/Picture");
    assertSame(a, list.getFirst());
    list.getFirst().setStream(43);
    list.clear();
    assertEquals(43, tag.attribute("WM/Picture").getFirst().stream());
    assertEquals(1, tag.attribute("WM/Picture").size());
  }

  @Test
  void nativeCrossTypeConversionsAndQwordSizeQuirk() {
    var a = new Attribute(-1L);
    assertEquals(5, a.dataSize());
    assertEquals(-1L, a.toUnsignedLong());
    assertEquals(0xffffffffL, a.toUnsignedInt());
    assertEquals(65535, a.toUnsignedShort());
    assertTrue(a.toBoolean());
    assertEquals("", a.toString());
    assertTrue(a.toByteVector().isEmpty());
    assertFalse(a.toPicture().isValid());
    assertEquals(8, a.data().length);
    var b = new Attribute("123");
    assertEquals(0, b.toUnsignedInt());
    assertFalse(b.toBoolean());
    assertTrue(b.toByteVector().isEmpty());
    assertEquals(3, Attribute.fromGuid(new ByteVector("abc")).dataSize());
  }

  @Test
  void booleanConversionsUseTheFullNumericValue() {
    for (var value : List.of(
      new Attribute(true), new Attribute((short) 1), new Attribute((short) -1),
      new Attribute(1), new Attribute(Integer.MIN_VALUE),
      new Attribute(1L << 32), new Attribute(Long.MIN_VALUE), new Attribute(-1L))) {
      assertTrue(value.toBoolean());
    }
    for (var value : List.of(
      new Attribute(false), new Attribute((short) 0), new Attribute(0), new Attribute(0L),
      new Attribute("text"), new Attribute(new ByteVector("binary")))) {
      assertFalse(value.toBoolean());
    }
  }

  @Test
  void parsedBooleanPayloadsRetainCanonicalLittleEndianData() {
    record BooleanCase(byte[] bytes, boolean value) {
    }
    for (var test : List.of(
      new BooleanCase(new byte[]{0, 0}, false),
      new BooleanCase(new byte[]{1, 0}, true),
      new BooleanCase(new byte[]{2, 0}, true),
      new BooleanCase(new byte[]{0, (byte) 0x80}, true),
      new BooleanCase(new byte[]{(byte) 0xff, (byte) 0xff}, true))) {
      var value = new Attribute("flag", Attribute.BoolType, 0, 0, test.bytes());
      assertEquals(test.value(), value.toBoolean());
      assertArrayEquals(test.value() ? new byte[]{1, 0} : new byte[]{0, 0}, value.data());
    }
  }

  @Test
  void booleanAttributesRoundTripWithAndWithoutScope() throws Exception {
    Path path = copy("silence-1.wma");
    for (int stream : new int[]{0, 43}) {
      for (boolean enabled : new boolean[]{false, true}) {
        var value = new Attribute(enabled);
        value.setStream(stream);
        int language = stream == 0 ? 0 : 56;
        value.setLanguage(language);
        try (var file = new AsfFile(path, ReadOptions.TAGS_ONLY)) {
          file.tag().setAttribute("JavaBoolean", value);
          file.save();
        }
        try (var file = new AsfFile(path, ReadOptions.TAGS_ONLY)) {
          var saved = file.tag().attribute("JavaBoolean").getFirst();
          assertEquals(Attribute.BoolType, saved.type());
          assertEquals(enabled, saved.toBoolean());
          assertEquals(stream, saved.stream());
          assertEquals(language, saved.language());
          assertArrayEquals(enabled ? new byte[]{1, 0} : new byte[]{0, 0}, saved.data());
        }
      }
    }
  }

  @Test
  void missingAttributeLookupDoesNotChangeTheNextSave() throws Exception {
    Path queried = copy("silence-1.wma");
    Path control = directory.resolve("control.wma");
    Files.copy(queried, control);
    try (var file = new AsfFile(queried); var reference = new AsfFile(control)) {
      var properties = file.properties();
      int count = file.tag().attributeListMap().size();
      var absent = file.tag().attribute("ABSENT-LOOKUP");
      absent.add(new Attribute("detached"));
      assertFalse(file.tag().contains("ABSENT-LOOKUP"));
      assertEquals(count, file.tag().attributeListMap().size());
      assertEquals(properties, file.properties());
      file.save();
      reference.save();
    }
    assertArrayEquals(Files.readAllBytes(control), Files.readAllBytes(queried));
  }

  @Test
  void missingAttributeLookupLeavesMetadataUnchanged() {
    var tag = new Tag();
    assertTrue(tag.isEmpty());
    assertTrue(tag.attribute("unknown").isEmpty());
    assertFalse(tag.contains("unknown"));
    assertTrue(tag.isEmpty());
    assertTrue(tag.attributeListMap().isEmpty());
    assertTrue(tag.properties().unsupportedData().isEmpty());
    tag.setAttribute("unknown", List.of());
    assertTrue(tag.contains("unknown"));
    assertEquals(List.of("unknown"), tag.properties().unsupportedData());
    tag.removeUnsupportedProperties(List.of("unknown"));
    assertTrue(tag.isEmpty());
  }

  @Test
  void pictureFailureRetainsAssignedPrefix() {
    var p = new Picture(new ByteVector(new byte[]{3, 1, 0, 0, 0, 65, 0, 0, 0}));
    assertFalse(p.isValid());
    assertEquals(Picture.Type.FrontCover, p.type());
    assertEquals("A", p.mimeType());
    assertEquals("", p.description());
    assertTrue(p.picture().isEmpty());
    assertTrue(p.render().isEmpty());
    assertEquals(11, p.dataSize());
  }

  @Test
  void actualFileRefAndBorrowedOwnerShareTheLiveTag() throws Exception {
    byte[] data = Files.readAllBytes(fixture("silence-1.wma"));
    var stream = new ByteVectorStream(data) {
      public String name() {
        return "live.wma";
      }
    };
    try (var ref = new AudioFile(stream, new ReadOptions(false, ReadStyle.FAST, ReadOptions.DEFAULT.maxMetadataBytes()))) {
      assertInstanceOf(AsfFile.class, ref.file());
      var f = (AsfFile) ref.file();
      assertSame(f.tag(), ref.tag());
      assertNotNull(f.detailedProperties());
      f.tag().setRating("custom rating");
      f.tag().setCopyright("copyright");
      f.tag().setAttribute("unknown", new Attribute(0xffffffff));
      assertDoesNotThrow(ref::save);
      assertEquals("custom rating", f.tag().rating());
      assertEquals("copyright", f.tag().copyright());
    }
    assertTrue(stream.isOpen());
    stream.seek(0);
    try (var f = new AsfFile(stream, ReadOptions.TAGS_ONLY)) {
      assertEquals("custom rating", f.tag().rating());
      assertEquals("copyright", f.tag().copyright());
      assertEquals(0xffffffffL, f.tag().attribute("unknown").getFirst().toUnsignedInt());
    }
  }

  @Test
  void emptyScalarListsRejectWithoutMutation() {
    for (String key : List.of("WM/Year", "WM/TrackNumber", "WM/Track")) {
      var tag = new Tag();
      tag.setAttribute(key, List.of());
      assertThrows(IllegalStateException.class, () -> {
        if (key.equals("WM/Year")) tag.year();
        else tag.track();
      });
      assertEquals(Set.of(key), tag.attributeListMap().keySet());
      assertTrue(tag.attributeListMap().get(key).isEmpty());
    }
  }

  @Test
  void explicitPictureReparseRetainsPartialNativeState() {
    var picture = front();
    var copy = new Picture(picture);
    picture.parse(new ByteVector(new byte[]{3, 0, 0, 0, 0, 'x', 0, 0, 0}));
    assertFalse(picture.isValid());
    assertEquals("x", picture.mimeType());
    assertEquals("description", picture.description());
    assertEquals(new ByteVector("data"), picture.picture());
    assertTrue(copy.isValid());
    assertEquals("image/jpeg", copy.mimeType());
    picture.parse(new ByteVector());
    assertEquals("x", picture.mimeType());
    assertTrue(picture.render().isEmpty());
  }

  @Test
  void attributeReparseKeepsOtherValueSlotsAndExistingScopes() throws IOException {
    var stream = new ByteVectorStream(Files.readAllBytes(fixture("silence-1.wma")));
    try (var file = new AsfFile(stream, ReadOptions.TAGS_ONLY)) {
      stream.data().clear();
      var value = new Attribute("previous");
      value.setStream(-7);
      value.setLanguage(-9);
      var copy = new Attribute(value);
      // Declared numeric size is deliberately zero: native reads the scalar width regardless.
      stream.data().append(new ByteVector(new byte[]{2, 0, 'N', 0, 3, 0, 0, 0, 1, 0, 0, (byte) 128}));
      file.seek(0);
      assertEquals("N", value.parse(file));
      assertEquals(12, file.tell());
      assertEquals(0x80000001L, value.toUnsignedInt());
      assertEquals(0, copy.toUnsignedInt());
      assertEquals(Attribute.UnicodeType, copy.type());
      assertEquals("previous", value.toString());
      assertEquals(-7, value.stream());
      assertEquals(-9, value.language());
      // Unknown types leave type/numeric/text slots unchanged after consuming only the prefix.
      stream.data().clear();
      stream.data().append(new ByteVector(new byte[]{23, 0, 43, 0, 2, 0, 7, 0, 1, 0, 0, 0, 'X', 0, 99}));
      file.seek(0);
      assertEquals("", value.parse(file, 2));
      assertEquals(14, file.tell());
      assertEquals(Attribute.DWordType, value.type());
      assertEquals(0x80000001L, value.toUnsignedInt());
      assertEquals(-7, copy.stream());
      assertEquals(-9, copy.language());
    }
  }

  @Test
  void attributeParseTrimsAllTrailingUtf16NullsAndZerosShortScalars() throws IOException {
    var raw = new ByteVector(new byte[]{6, 0, 'N', 0, 0, 0, 0, 0, 0, 0, 6, 0, 'V', 0, 0, 0, 0, 0});
    var stream = new ByteVectorStream(Files.readAllBytes(fixture("silence-1.wma")));
    try (var file = new AsfFile(stream, ReadOptions.TAGS_ONLY)) {
      stream.data().clear().append(raw);
      file.seek(0);
      var value = new Attribute();
      assertEquals("N", value.parse(file));
      assertEquals("V", value.toString());
      assertEquals(18, file.tell());
      stream.data().clear();
      stream.data().append(new ByteVector(new byte[]{0, 0, 3, 0, 4, 0, 1, 2, 3}));
      file.seek(0);
      assertEquals("", value.parse(file));
      assertEquals(0, value.toUnsignedInt());
      assertEquals("V", value.toString());
      assertEquals(9, file.tell());
    }
  }

  @Test
  void attributeParseDetachesStreamReturnedBinaryValues() throws IOException {
    var supplied = new ByteVector("raw");
    class Stream extends ByteVectorStream {
      boolean armed;

      Stream() throws IOException {
        super(Files.readAllBytes(fixture("silence-1.wma")));
      }

      public ByteVector readBlock(long length) {
        return armed && length == 3 ? supplied : super.readBlock(length);
      }
    }
    var stream = new Stream();
    try (var file = new AsfFile(stream, ReadOptions.TAGS_ONLY)) {
      stream.data().clear().append(new ByteVector(new byte[]{0, 0, 1, 0, 3, 0}));
      file.seek(0);
      stream.armed = true;
      var value = new Attribute();
      assertEquals("", value.parse(file));
      supplied.clear();
      assertEquals(new ByteVector("raw"), value.toByteVector());
      assertEquals(Attribute.BytesType, value.type());
    }
  }
}
