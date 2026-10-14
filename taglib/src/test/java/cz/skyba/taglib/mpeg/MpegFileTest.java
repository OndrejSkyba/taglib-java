package cz.skyba.taglib.mpeg;

import cz.skyba.taglib.*;
import cz.skyba.taglib.id3.v2.AttachedPictureFrame;
import cz.skyba.taglib.id3.v2.Tag;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent complete assertions from the pinned tests/test_mpeg.cpp and fixed source regressions.
 */
class MpegFileTest {
  @TempDir
  Path directory;

  static Path fixture(String name) {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("taglib/src"))) root = root.getParent();
    assertNotNull(root);
    Path p = UpstreamAudioFixtures.upstreamSource(root).resolve("tests/data/" + name);
    assertTrue(Files.isRegularFile(p));
    return p;
  }

  Path copy(String name) throws Exception {
    Path p = directory.resolve(name);
    Files.copy(fixture(name), p);
    return p;
  }

  static ByteVector audio() {
    var frame = new ByteVector(HexFormat.of().parseHex("fffb9000")).resize(417);
    return new ByteVector(frame).append(frame).append(frame).append(frame);
  }

  @Test
  void compressedPictureRejectsOnlyUnsafeMemoryExtent() throws Exception {
    byte[] original = Files.readAllBytes(fixture("compressed_id3_frame.mp3"));
    var unsafe = new ByteVectorStream(original);
    try (var f = new MpegFile(unsafe, ReadOptions.TAGS_ONLY)) {
      assertEquals(5112, f.id3v2Tag().header().completeTagSize());
      assertEquals(4088, unsafe.length());
      assertEquals(86414, ((AttachedPictureFrame) f.id3v2Tag().frameList(new ByteVector("APIC")).getFirst()).picture().size());
      assertThrows(TagLibException.class, f::save);
      assertArrayEquals(original, unsafe.data().toByteArray());
    }
    var filled = new ByteVectorStream(new ByteVector(original).resize(5112));
    try (var f = new MpegFile(filled, ReadOptions.TAGS_ONLY)) {
      f.save();
    }
    try (var f = new MpegFile(filled, ReadOptions.TAGS_ONLY)) {
      assertEquals(86414, ((AttachedPictureFrame) f.id3v2Tag().frameList(new ByteVector("APIC")).getFirst()).picture().size());
    }
    Path path = copy("compressed_id3_frame.mp3");
    try (var f = new MpegFile(path, ReadOptions.TAGS_ONLY)) {
      f.save();
    }
    try (var f = new MpegFile(path, ReadOptions.TAGS_ONLY)) {
      assertEquals(86414, ((AttachedPictureFrame) f.id3v2Tag().frameList(new ByteVector("APIC")).getFirst()).picture().size());
    }
  }

  static void properties(String name, long ms, int rate, int channels, int sampleRate, XingHeader.HeaderType type) throws IOException {
    try (var f = new MpegFile(fixture(name))) {
      var p = f.detailedProperties();
      assertNotNull(p);
      assertEquals(ms / 1000, p.lengthInSeconds());
      assertEquals(ms, p.lengthInMilliseconds());
      assertEquals(rate, p.bitrate());
      assertEquals(channels, p.channels());
      assertEquals(sampleRate, p.sampleRate());
      if (type == null) assertNull(p.xingHeader());
      else assertEquals(type, p.xingHeader().type());
      assertFalse(p.isADTS());
    }
  }

  @Test
  void nativeAudioPropertiesXingHeaderCBR() throws IOException {
    properties("lame_cbr.mp3", 1887164, 64, 1, 44100, XingHeader.HeaderType.Xing);
  }

  @Test
  void nativeAudioPropertiesXingHeaderVBR() throws IOException {
    properties("lame_vbr.mp3", 1887164, 70, 1, 44100, XingHeader.HeaderType.Xing);
  }

  @Test
  void nativeAudioPropertiesVBRIHeader() throws IOException {
    properties("rare_frames.mp3", 222198, 233, 2, 44100, XingHeader.HeaderType.VBRI);
  }

  @Test
  void nativeAudioPropertiesNoVBRHeaders() throws IOException {
    properties("bladeenc.mp3", 3553, 64, 1, 44100, null);
    try (var f = new MpegFile(fixture("bladeenc.mp3"))) {
      long last = f.lastFrameOffset();
      assertEquals(28213, last);
      assertEquals(209, new Header(f, last, false).frameLength());
    }
  }

  @Test
  void nativeAudioPropertiesADTS() throws IOException {
    for (var style : ReadStyle.values())
      try (var f = new MpegFile(fixture("empty1s.aac"), new ReadOptions(true, style, ReadOptions.DEFAULT.maxMetadataBytes()))) {
        var p = f.detailedProperties();
        assertNotNull(p);
        assertEquals(style == ReadStyle.FAST ? 0 : 1, p.lengthInSeconds());
        assertEquals(style == ReadStyle.FAST ? 0 : 1176, p.lengthInMilliseconds());
        assertEquals(style == ReadStyle.FAST ? 0 : 1, p.bitrate());
        assertEquals(1, p.channels());
        assertEquals(Header.ChannelConfiguration.FrontCenter, p.channelConfiguration());
        assertEquals(11025, p.sampleRate());
        assertNull(p.xingHeader());
        assertTrue(p.isADTS());
        long last = f.lastFrameOffset();
        assertEquals(136, last);
        assertEquals(11, new Header(f, last, false).frameLength());
      }
  }

  @Test
  void nativeSkipInvalidFrames1() throws Exception {
    properties("invalid-frames1.mp3", 392, 160, 2, 44100, null);
  }

  @Test
  void nativeSkipInvalidFrames2() throws Exception {
    properties("invalid-frames2.mp3", 314, 192, 2, 44100, null);
  }

  @Test
  void nativeSkipInvalidFrames3() throws Exception {
    properties("invalid-frames3.mp3", 183, 320, 2, 44100, null);
  }

  @Test
  void nativeVersion2DurationWithXingHeader() throws IOException {
    try (var f = new MpegFile(fixture("mpeg2.mp3"))) {
      assertNotNull(f.detailedProperties());
      assertEquals(5387, f.detailedProperties().lengthInSeconds());
      assertEquals(5387285, f.detailedProperties().lengthInMilliseconds());
    }
  }

  void saveVersion(Tag.Version version) throws Exception {
    Path path = copy("xing.mp3");
    String title = "X".repeat(254);
    try (var f = new MpegFile(path)) {
      assertFalse(f.hasId3v2Tag());
      f.tag().setTitle(title);
      f.tag().setArtist("Artist A");
      f.save(new MpegSaveOptions(Set.of(MpegFile.TagType.values()), MpegFile.StripTags.STRIP_OTHERS, version, MpegFile.DuplicateTags.DUPLICATE));
      assertTrue(f.hasId3v2Tag());
    }
    try (var f = new MpegFile(path)) {
      assertEquals(version.code(), f.id3v2Tag().header().majorVersion());
      assertEquals("Artist A", f.tag().artist());
      assertEquals(title, f.tag().title());
    }
  }

  @Test
  void nativeSaveID3v24() throws Exception {
    saveVersion(Tag.Version.V4);
  }

  @Test
  void nativeSaveID3v23() throws Exception {
    saveVersion(Tag.Version.V3);
  }

  @Test
  void nativeDuplicateID3v2() throws IOException {
    try (var f = new MpegFile(fixture("duplicate_id3v2.mp3"))) {
      assertTrue(f.hasId3v2Tag());
      assertEquals(44100, f.detailedProperties().sampleRate());
    }
  }

  @Test
  void nativeFuzzedFile() throws Exception {
    try (var f = new MpegFile(fixture("excessive_alloc.mp3"))) {
      assertTrue(f.isValid());
    }
  }

  @Test
  void nativeFrameOffset() throws IOException {
    String[] names = {"ape.mp3", "ape-id3v1.mp3", "ape-id3v2.mp3"};
    long[] first = {0, 0, 0x41a}, last = {0x1fd6, 0x1fd6, 0x23f0};
    for (int i = 0; i < names.length; i++)
      try (var f = new MpegFile(fixture(names[i]))) {
        assertTrue(f.isValid());
        assertEquals(first[i], f.firstFrameOffset());
        assertEquals(last[i], f.lastFrameOffset());
      }
  }

  @Test
  void nativeStripAndProperties() throws Exception {
    Path path = copy("xing.mp3");
    try (var f = new MpegFile(path)) {
      f.getOrCreateId3v2Tag().setTitle("ID3v2");
      f.getOrCreateApeTag().setTitle("APE");
      f.getOrCreateId3v1Tag().setTitle("ID3v1");
      f.save();
    }
    try (var f = new MpegFile(path)) {
      assertEquals("ID3v2", f.properties().get("TITLE").getFirst());
      f.strip(Set.of(MpegFile.TagType.ID3V2));
      assertEquals("APE", f.properties().get("TITLE").getFirst());
      f.strip(Set.of(MpegFile.TagType.APE));
      assertEquals("ID3v1", f.properties().get("TITLE").getFirst());
      f.strip(Set.of(MpegFile.TagType.ID3V1));
      assertTrue(f.properties().isEmpty());
    }
  }

  @Test
  void nativeProperties() throws Exception {
    var expected = PropertyMap.builder();
    expected.put("ALBUM", "Album");
    expected.put("ALBUMARTIST", "Album Artist");
    expected.put("ALBUMARTISTSORT", "Album Artist Sort");
    expected.put("ALBUMSORT", "Album Sort");
    expected.put("ARRANGER", "Arranger");
    expected.put("ARTIST", "Artist");
    expected.put("ARTISTSORT", "Artist Sort");
    expected.put("ARTISTWEBPAGE", "Artist Web Page");
    expected.put("ASIN", "ASIN");
    expected.put("AUDIOSOURCEWEBPAGE", "Audio Source Web Page");
    expected.put("BARCODE", "Barcode");
    expected.put("BPM", "123");
    expected.put("CATALOGNUMBER", "Catalog Number");
    expected.put("COMMENT", "Comment");
    expected.put("COMMENT:CDESC", "Comment with Description");
    expected.put("COMPILATION", "1");
    expected.put("COMPOSER", "Composer");
    expected.put("COMPOSERSORT", "Composer Sort");
    expected.put("CONDUCTOR", "Conductor");
    expected.put("WORK", "Content Group");
    expected.put("COPYRIGHT", "2021 Copyright");
    expected.put("COPYRIGHTURL", "Copyright URL");
    expected.put("DATE", "2021-01-03 12:29:23");
    expected.put("DISCNUMBER", "3/5");
    expected.put("DISCSUBTITLE", "Disc Subtitle");
    expected.put("DJMIXER", "DJ Mixer");
    expected.put("ENCODEDBY", "Encoded by");
    expected.put("ENCODING", "Encoding");
    expected.put("ENCODINGTIME", "2021-01-03 13:48:44");
    expected.put("ENGINEER", "Engineer");
    expected.put("FILETYPE", "File Type");
    expected.put("FILEWEBPAGE", "File Web Page");
    expected.put("GENRE", "Genre");
    expected.put("GROUPING", "Grouping");
    expected.put("INITIALKEY", "Dbm");
    expected.put("ISRC", "UKAAA0500001");
    expected.put("LABEL", "Label");
    expected.put("LANGUAGE", "eng");
    expected.put("LENGTH", "1234");
    expected.put("LYRICIST", "Lyricist");
    expected.put("LYRICS:LDESC", "Lyrics");
    expected.put("MEDIA", "Media");
    expected.put("MIXER", "Mixer");
    expected.put("MOOD", "Mood");
    expected.put("MOVEMENTNAME", "Movement Name");
    expected.put("MOVEMENTNUMBER", "2");
    expected.put("MUSICBRAINZ_ALBUMID", "MusicBrainz_AlbumID");
    expected.put("MUSICBRAINZ_ALBUMARTISTID", "MusicBrainz_AlbumartistID");
    expected.put("MUSICBRAINZ_ARTISTID", "MusicBrainz_ArtistID");
    expected.put("MUSICBRAINZ_RELEASEGROUPID", "MusicBrainz_ReleasegroupID");
    expected.put("MUSICBRAINZ_RELEASETRACKID", "MusicBrainz_ReleasetrackID");
    expected.put("MUSICBRAINZ_TRACKID", "MusicBrainz_TrackID");
    expected.put("MUSICBRAINZ_WORKID", "MusicBrainz_WorkID");
    expected.put("ORIGINALALBUM", "Original Album");
    expected.put("ORIGINALARTIST", "Original Artist");
    expected.put("ORIGINALDATE", "2021-01-03 13:52:19");
    expected.put("ORIGINALFILENAME", "Original Filename");
    expected.put("ORIGINALLYRICIST", "Original Lyricist");
    expected.put("OWNER", "Owner");
    expected.put("PAYMENTWEBPAGE", "Payment Web Page");
    expected.put("PERFORMER:DRUMS", "Drummer");
    expected.put("PERFORMER:GUITAR", "Guitarist");
    expected.put("PLAYLISTDELAY", "10");
    expected.put("PODCAST", List.of());
    expected.put("PODCASTCATEGORY", "Podcast Category");
    expected.put("PODCASTDESC", "Podcast Description");
    expected.put("PODCASTID", "Podcast ID");
    expected.put("PODCASTURL", "Podcast URL");
    expected.put("PRODUCEDNOTICE", "2021 Produced Notice");
    expected.put("PRODUCER", "Producer");
    expected.put("PUBLISHERWEBPAGE", "Publisher Web Page");
    expected.put("RADIOSTATION", "Radio Station");
    expected.put("RADIOSTATIONOWNER", "Radio Station Owner");
    expected.put("RELEASECOUNTRY", "Release Country");
    expected.put("RELEASESTATUS", "Release Status");
    expected.put("RELEASETYPE", "Release Type");
    expected.put("REMIXER", "Remixer");
    expected.put("SCRIPT", "Script");
    expected.put("SUBTITLE", "Subtitle");
    expected.put("TITLE", "Title");
    expected.put("TITLESORT", "Title Sort");
    expected.put("TRACKNUMBER", "2/4");
    expected.put("URL:UDESC", "URL");
    var map = expected.build();
    Path path = copy("xing.mp3");
    try (var f = new MpegFile(path)) {
      assertTrue(f.properties().isEmpty());
      f.replaceProperties(map);
      f.save();
    }
    try (var f = new MpegFile(path)) {
      assertEquals(map, f.properties());
    }
  }

  @Test
  void nativeRepeatedSave1() throws Exception {
    Path path = copy("xing.mp3");
    try (var f = new MpegFile(path)) {
      f.getOrCreateId3v2Tag().setTitle("X".repeat(4096));
      f.save();
    }
    try (var f = new MpegFile(path)) {
      f.getOrCreateId3v2Tag().setTitle("");
      f.save();
      f.getOrCreateId3v2Tag().setTitle("X".repeat(4096));
      f.save();
      assertEquals(5141, f.firstFrameOffset());
    }
  }

  @Test
  void nativeRepeatedSave2() throws Exception {
    Path path = copy("xing.mp3");
    try (var f = new MpegFile(path)) {
      f.getOrCreateId3v2Tag().setTitle("0123456789");
      f.save();
      f.save();
      assertEquals(-1, f.find(new ByteVector("ID3"), 3));
    }
  }

  @Test
  void nativeRepeatedSave3() throws Exception {
    Path path = copy("xing.mp3");
    try (var f = new MpegFile(path)) {
      assertFalse(f.hasApeTag());
      assertFalse(f.hasId3v1Tag());
      f.getOrCreateApeTag().setTitle("01234 56789 ABCDE FGHIJ");
      f.save();
      f.apeTag().setTitle("0");
      f.save();
      f.getOrCreateId3v1Tag().setTitle("01234 56789 ABCDE FGHIJ");
      f.apeTag().setTitle("01234 56789 ABCDE FGHIJ 01234 56789 ABCDE FGHIJ 01234 56789");
      f.save();
    }
    try (var f = new MpegFile(path)) {
      assertTrue(f.hasApeTag());
      assertTrue(f.hasId3v1Tag());
    }
  }

  void emptyTag(Set<MpegFile.TagType> mask) throws Exception {
    Path path = copy("xing.mp3");
    try (var f = new MpegFile(path)) {
      (mask.equals(Set.of(MpegFile.TagType.ID3V2)) ? f.getOrCreateId3v2Tag() : mask.equals(Set.of(MpegFile.TagType.ID3V1)) ? f.getOrCreateId3v1Tag() : f.getOrCreateApeTag()).setTitle("0123456789");
      f.save(new MpegSaveOptions(mask, MpegFile.StripTags.STRIP_OTHERS, Tag.Version.V4, MpegFile.DuplicateTags.DUPLICATE));
    }
    try (var f = new MpegFile(path)) {
      (mask.equals(Set.of(MpegFile.TagType.ID3V2)) ? f.getOrCreateId3v2Tag() : mask.equals(Set.of(MpegFile.TagType.ID3V1)) ? f.getOrCreateId3v1Tag() : f.getOrCreateApeTag()).setTitle("");
      f.save(new MpegSaveOptions(mask, MpegFile.StripTags.STRIP_NONE, Tag.Version.V4, MpegFile.DuplicateTags.DUPLICATE));
    }
    try (var f = new MpegFile(path)) {
      assertFalse(mask.equals(Set.of(MpegFile.TagType.ID3V2)) ? f.hasId3v2Tag() : mask.equals(Set.of(MpegFile.TagType.ID3V1)) ? f.hasId3v1Tag() : f.hasApeTag());
    }
  }

  @Test
  void nativeEmptyID3v2() throws Exception {
    emptyTag(Set.of(MpegFile.TagType.ID3V2));
  }

  @Test
  void nativeEmptyID3v1() throws Exception {
    emptyTag(Set.of(MpegFile.TagType.ID3V1));
  }

  @Test
  void nativeEmptyAPE() throws Exception {
    emptyTag(Set.of(MpegFile.TagType.APE));
  }

  @Test
  void nativeIgnoreGarbage() throws Exception {
    Path path = copy("garbage.mp3");
    try (var f = new MpegFile(path)) {
      assertTrue(f.isValid());
      assertTrue(f.hasId3v2Tag());
      assertEquals(2255, f.firstFrameOffset());
      assertEquals(6015, f.lastFrameOffset());
      assertEquals("Title A", f.id3v2Tag().title());
      f.id3v2Tag().setTitle("Title B");
      f.save();
    }
    try (var f = new MpegFile(path)) {
      assertTrue(f.isValid());
      assertTrue(f.hasId3v2Tag());
      assertEquals("Title B", f.id3v2Tag().title());
    }
  }

  @Test
  void nativeExtendedHeader() throws Exception {
    Path path = copy("extended-header.mp3");
    try (var f = new MpegFile(path)) {
      assertTrue(f.isValid());
      assertTrue(f.hasId3v2Tag());
      var t = f.id3v2Tag();
      assertNotNull(t.extendedHeader());
      assertEquals(12, t.extendedHeader().size());
      assertEquals("Druids", t.title());
      assertEquals("Excelsis", t.artist());
      assertEquals("Vo Chrieger U Drache", t.album());
      assertEquals(2013, t.year());
      assertEquals("Folk/Power Metal", t.genre());
      assertEquals(3, t.track());
      assertEquals("2013", f.properties().get("ORIGINALDATE").getFirst());
    }
  }

  @Test
  void nativeReadStyleFast() throws Exception {
    Path path = copy("lame_cbr.mp3");
    try (var f = new MpegFile(path, new ReadOptions(true, ReadStyle.FAST, ReadOptions.DEFAULT.maxMetadataBytes()))) {
      var p = f.detailedProperties();
      assertNotNull(p);
      assertEquals(1887, p.lengthInSeconds());
      assertEquals(1887164, p.lengthInMilliseconds());
      assertEquals(64, p.bitrate());
      assertEquals(1, p.channels());
      assertEquals(44100, p.sampleRate());
      assertTrue(f.isValid());
      assertTrue(f.hasId3v2Tag());
      assertEquals("", f.id3v2Tag().title());
      assertEquals("-1.020000 dB", f.properties().get("REPLAYGAIN_TRACK_GAIN").getFirst());
      assertEquals("0.920032", f.properties().get("REPLAYGAIN_TRACK_PEAK").getFirst());
      f.replaceProperties(f.properties().toBuilder().put("TITLE", "A Title").put("Artist", "An Artist").build());
      f.save();
    }
    try (var f = new MpegFile(path, new ReadOptions(true, ReadStyle.FAST, ReadOptions.DEFAULT.maxMetadataBytes()))) {
      assertTrue(f.isValid());
      assertTrue(f.hasId3v2Tag());
      assertEquals("A Title", f.id3v2Tag().title());
      assertEquals("An Artist", f.id3v2Tag().artist());
    }
    try (var f = new MpegFile(fixture("garbage.mp3"), new ReadOptions(true, ReadStyle.FAST, ReadOptions.DEFAULT.maxMetadataBytes()))) {
      assertTrue(f.isValid());
      assertFalse(f.hasId3v2Tag());
      assertEquals(2255, f.firstFrameOffset());
      assertEquals(6015, f.lastFrameOffset());
    }
  }

  @Test
  void nativeID3v22Properties() throws Exception {
    Path path = copy("itunes10.mp3");
    try (var f = new MpegFile(path)) {
      var b = PropertyMap.builder();
      b.put("ALBUM", "Album");
      b.put("ALBUMARTIST", "Album Artist");
      b.put("ALBUMARTISTSORT", "Sort Album Artist");
      b.put("ALBUMSORT", "Sort Album");
      b.put("ARTIST", "Artist");
      b.put("ARTISTSORT", "Sort Artist");
      b.put("BPM", "180");
      b.put("COMMENT", "Comments");
      b.put("COMMENT:ITUNPGAP", "1");
      b.put("COMPILATION", "1");
      b.put("COMPOSER", "Composer");
      b.put("COMPOSERSORT", "Sort Composer");
      b.put("DATE", "2011");
      b.put("DISCNUMBER", "1/2");
      b.put("GENRE", "Heavy Metal");
      b.put("LYRICS", "Lyrics");
      b.put("SUBTITLE", "Description");
      b.put("TITLE", "iTunes10MP3");
      b.put("TITLESORT", "Sort Name");
      b.put("TRACKNUMBER", "1/10");
      b.put("WORK", "Grouping");
      b.addUnsupportedData("APIC").addUnsupportedData("UNKNOWN/RVA");
      assertEquals(b.build(), f.properties());
      assertEquals(List.of("PICTURE"), f.complexPropertyKeys());
      var pictures = f.complexProperties("PICTURE");
      assertEquals(1, pictures.size());
      var picture = pictures.getFirst();
      assertEquals("image/png", picture.get("mimeType").toString());
      assertTrue(picture.get("description").toString().isEmpty());
      assertEquals("Other", picture.get("pictureType").toString());
      var data = picture.get("data").toByteVector();
      assertTrue(data.startsWith(new ByteVector(HexFormat.of().parseHex("89504e470d0a1a0a"))));
      assertEquals(2315, data.size());
    }
  }

  @Test
  void nativeSniffRequiresBothSyncBytesInsideQuickBuffer() throws Exception {
    class Named extends ByteVectorStream {
      Named(ByteVector data) {
        super(data);
      }

      public String name() {
        return "track.unknown";
      }
    }
    for (int prefix : new int[]{1022, 1023, 1024}) {
      var stream = new Named(new ByteVector().resize(prefix, (byte) '!').append(audio()));
      stream.seek(7);
      assertEquals(prefix == 1022, MpegFile.isSupported(stream));
      assertEquals(7, stream.tell());
      if (prefix == 1022) {
        try (var ref = new AudioFile(stream, ReadOptions.TAGS_ONLY)) {
          assertInstanceOf(MpegFile.class, ref.file());
        }
      } else {
        assertThrows(TagLibException.class,
          () -> new AudioFile(stream, ReadOptions.TAGS_ONLY));
        assertEquals(7, stream.tell());
      }
    }
  }

  @Test
  void searchCarryAcrossBlocksAndFastSkipsOnlyID3Discovery() throws IOException {
    for (int prefix : new int[]{1022, 1023, 1024, 2047}) {
      var original = new ByteVector().resize(prefix, (byte) '!').append(audio());
      try (var f = new MpegFile(new ByteVectorStream(original), new ReadOptions(false, ReadStyle.FAST, ReadOptions.DEFAULT.maxMetadataBytes()))) {
        assertEquals(prefix, f.firstFrameOffset());
        assertEquals(prefix + 3 * 417, f.lastFrameOffset());
        assertEquals(prefix + 417, f.nextFrameOffset(prefix + 1));
        assertEquals(prefix + 2 * 417, f.previousFrameOffset(prefix + 2 * 417));
        long cursor = f.tell();
        assertThrows(IllegalArgumentException.class, () -> f.nextFrameOffset(-1));
        assertEquals(cursor, f.tell());
      }
    }
  }

  @Test
  void lookupAndCreationAreSeparateAndDoNotWriteStorage() throws Exception {
    var modern = new Tag();
    modern.setTitle("modern");
    var legacy = new cz.skyba.taglib.id3.v1.Tag();
    legacy.setTitle("legacy");
    var ape = new cz.skyba.taglib.ape.Tag();
    ape.setTitle("ape");
    var stream = new ByteVectorStream(modern.render().append(audio()).append(ape.render()).append(legacy.render()));
    try (var file = new MpegFile(stream, ReadOptions.TAGS_ONLY)) {
      file.strip();
      byte[] beforeCreation = stream.data().toByteArray();
      assertNull(file.id3v2Tag());
      assertNull(file.id3v1Tag());
      assertNull(file.apeTag());
      assertSame(file.getOrCreateId3v2Tag(), file.getOrCreateId3v2Tag());
      assertSame(file.getOrCreateId3v1Tag(), file.getOrCreateId3v1Tag());
      assertSame(file.getOrCreateApeTag(), file.getOrCreateApeTag());
      assertNotNull(file.id3v2Tag());
      assertNotNull(file.id3v1Tag());
      assertNotNull(file.apeTag());
      assertFalse(file.hasId3v2Tag());
      assertFalse(file.hasId3v1Tag());
      assertFalse(file.hasApeTag());
      assertArrayEquals(beforeCreation, stream.data().toByteArray());
    }
  }

  @Test
  void saveSelectionIsIndependentOfLaterCallerEdits() throws Exception {
    var selected = java.util.EnumSet.of(MpegFile.TagType.ID3V2);
    var options = new MpegSaveOptions(selected, MpegFile.StripTags.STRIP_NONE,
      Tag.Version.V4, MpegFile.DuplicateTags.DO_NOT_DUPLICATE);
    selected.clear();
    selected.add(MpegFile.TagType.ID3V1);
    assertThrows(UnsupportedOperationException.class, () -> options.tags().clear());
    var stream = new ByteVectorStream(audio());
    try (var file = new MpegFile(stream, ReadOptions.TAGS_ONLY)) {
      file.getOrCreateId3v2Tag().setTitle("modern");
      file.getOrCreateId3v1Tag().setTitle("legacy");
      file.save(options);
    }
    try (var file = new MpegFile(stream, ReadOptions.TAGS_ONLY)) {
      assertTrue(file.hasId3v2Tag());
      assertFalse(file.hasId3v1Tag());
      assertEquals("modern", file.id3v2Tag().title());
      byte[] beforeEmptySelection = stream.data().toByteArray();
      file.save(new MpegSaveOptions(Set.of(), MpegFile.StripTags.STRIP_NONE,
        Tag.Version.V4, MpegFile.DuplicateTags.DO_NOT_DUPLICATE));
      assertArrayEquals(beforeEmptySelection, stream.data().toByteArray());
    }
  }

  @Test
  void stripChangesStorageImmediatelyAndPreservesCallerReferences() throws IOException {
    var modern = new Tag();
    modern.setTitle("modern");
    var legacy = new cz.skyba.taglib.id3.v1.Tag();
    legacy.setTitle("legacy");
    var ape = new cz.skyba.taglib.ape.Tag();
    ape.setTitle("ape");
    var bytes = modern.render().append(audio()).append(ape.render()).append(legacy.render());
    for (boolean discardTags : new boolean[]{false, true}) {
      var stream = new ByteVectorStream(bytes);
      try (var f = new MpegFile(stream, ReadOptions.TAGS_ONLY)) {
        var t = f.id3v2Tag();
        var v1 = f.id3v1Tag();
        var a = f.apeTag();
        f.strip(Set.of(MpegFile.TagType.values()), discardTags);
        assertSame(discardTags ? null : t, f.id3v2Tag());
        assertSame(discardTags ? null : v1, f.id3v1Tag());
        assertSame(discardTags ? null : a, f.apeTag());
        assertEquals("modern", t.title());
        assertEquals("legacy", v1.title());
        assertEquals("ape", a.title());
        assertFalse(f.hasId3v2Tag());
        assertFalse(f.hasId3v1Tag());
        assertFalse(f.hasApeTag());
        assertEquals(audio(), stream.data());
        f.save();
        assertEquals(!discardTags, f.hasId3v2Tag());
        assertEquals(!discardTags, f.hasId3v1Tag());
        assertEquals(!discardTags, f.hasApeTag());
        if (discardTags) assertEquals(audio(), stream.data());
        try (var reopened = new MpegFile(new ByteVectorStream(stream.data()), ReadOptions.TAGS_ONLY)) {
          assertEquals(discardTags ? "" : "modern", reopened.tag().title());
          assertEquals(!discardTags, reopened.hasApeTag());
        }
      }
    }
  }

  @Test
  void stripKeepsUnsavedTagsThatHaveNoPhysicalRepresentation() throws IOException {
    var stream = new ByteVectorStream(audio());
    try (var f = new MpegFile(stream, ReadOptions.TAGS_ONLY)) {
      var tag = f.getOrCreateApeTag();
      tag.setTitle("unsaved");
      f.strip(Set.of(MpegFile.TagType.APE), true);
      assertSame(tag, f.apeTag());
      assertFalse(f.hasApeTag());
      assertEquals(audio(), stream.data());
      f.save(new MpegSaveOptions(Set.of(MpegFile.TagType.APE), MpegFile.StripTags.STRIP_OTHERS, Tag.Version.V4, MpegFile.DuplicateTags.DUPLICATE));
      assertTrue(f.hasApeTag());
      try (var reopened = new MpegFile(new ByteVectorStream(stream.data()), ReadOptions.TAGS_ONLY)) {
        assertEquals("unsaved", reopened.apeTag().title());
      }
    }
  }

  @Test
  void directPropertiesUsesCurrentOwnerAndBorrowedLifetime() throws Exception {
    var stream = new ByteVectorStream(audio());
    try (var f = new MpegFile(stream, ReadOptions.TAGS_ONLY)) {
      assertNull(f.detailedProperties());
      var p = new Properties(f, ReadStyle.ACCURATE);
      assertEquals(128, p.bitrate());
      assertEquals(104, p.lengthInMilliseconds());
      assertEquals(1255, f.tell());
      f.tag().setTitle("saved");
      f.save();
      try (var ref = new AudioFile(stream, ReadOptions.TAGS_ONLY)) {
        assertInstanceOf(MpegFile.class, ref.file());
        assertSame(ref.tag(), ref.file().tag());
        assertEquals("saved", ref.tag().title());
      }
    }
    assertTrue(stream.isOpen());
  }

  @Test
  void externalTruncationRejectsProvedUnsafeMemoryGrowthBeforeWriting() throws IOException {
    var t = new Tag();
    t.setTitle("small");
    var stream = new ByteVectorStream(t.render().append(audio()));
    try (var f = new MpegFile(stream, ReadOptions.TAGS_ONLY)) {
      stream.truncate(3);
      var before = new ByteVector(stream.data());
      f.id3v2Tag().setTitle("x".repeat(5000));
      assertThrows(TagLibException.class, f::save);
      assertEquals(before, stream.data());
    }
  }
}
