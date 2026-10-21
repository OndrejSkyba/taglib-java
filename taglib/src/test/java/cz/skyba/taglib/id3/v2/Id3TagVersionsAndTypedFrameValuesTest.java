/* Independent assertions from upstream tests/test_id3v2.cpp; LGPL-2.1-only OR MPL-2.0. */
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.AudioFixtures;
import cz.skyba.taglib.mpeg.MpegFile;
import cz.skyba.taglib.mpeg.MpegSaveOptions;
import cz.skyba.taglib.toolkit.ByteVector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static java.nio.charset.StandardCharsets.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Full fixed assertions for the remaining registered upstream owner/property tests.
 */
class Id3TagVersionsAndTypedFrameValuesTest {
  @TempDir
  Path directory;

  static Path fixture(String name) {
    return AudioFixtures.fixture(name);
  }

  Path copy(String name) throws Exception {
    return Files.copy(fixture(name), directory.resolve(name));
  }

  static ByteVector b(String value) {
    return new ByteVector(value);
  }

  static ByteVector hex(String value) {
    return new ByteVector(HexFormat.of().parseHex(value));
  }

  static List<Frame> frames(Tag tag, String id) {
    return tag.frameList(b(id));
  }

  static boolean contains(Tag tag, String id) {
    return tag.frameListMap().containsKey(b(id));
  }

  static TextIdentificationFrame text(String id, String value) {
    var frame = new TextIdentificationFrame(b(id), ISO_8859_1);
    frame.setText(value);
    return frame;
  }

  static TextIdentificationFrame text(String id, List<String> values) {
    var frame = new TextIdentificationFrame(b(id), ISO_8859_1);
    frame.setText(values);
    return frame;
  }

  static class PublicFrame extends Frame {
    PublicFrame() {
      super(b("XXXX").resize(10));
    }

    String stringField(ByteVector data) {
      return readStringField(data, ISO_8859_1);
    }

    @Override
    public String toString() {
      return "";
    }

    @Override
    protected void parseFields(ByteVector data) {
    }

    @Override
    protected ByteVector renderFields() {
      return new ByteVector();
    }
  }

  @Test
  void testUnsynchDecode() throws IOException {
    try (var file = new MpegFile(fixture("unsynch.id3"), ReadOptions.TAGS_ONLY)) {
      assertNotNull(file.tag());
      assertEquals("My babe just cares for me", file.tag().title());
    }
  }

  @Test
  void testUnsynchDecodeID3v24Frame() throws IOException {
    try (var file = new MpegFile(fixture("unsynch24.id3"), ReadOptions.TAGS_ONLY)) {
      assertNotNull(file.tag());
      assertEquals("Hi", file.tag().title());
    }
  }

  @Test
  void testDowngradeUTF8ForID3v23_1() throws Exception {
    var frame = new TextIdentificationFrame(b("TPE1"), UTF_8);
    frame.setText(List.of("Foo"));
    try (var file = new MpegFile(copy("xing.mp3"))) {
      file.getOrCreateId3v2Tag().addFrame(frame);
      file.save(new MpegSaveOptions(Set.of(MpegFile.TagType.ID3V2), MpegFile.StripTags.STRIP_OTHERS, Tag.Version.V3, MpegFile.DuplicateTags.DUPLICATE));
      assertTrue(file.hasId3v2Tag());
      var data = frame.render();
      assertEquals(19, data.size());
      var reread = new TextIdentificationFrame(data);
      assertEquals(List.of("Foo"), reread.fieldList());
      assertEquals(UTF_16, reread.textEncoding());
    }
  }

  @Test
  void testDowngradeUTF8ForID3v23_2() throws Exception {
    var frame = new UnsynchronizedLyricsFrame(UTF_8);
    frame.setText("Foo");
    try (var file = new MpegFile(copy("xing.mp3"))) {
      file.getOrCreateId3v2Tag().addFrame(frame);
      file.save(new MpegSaveOptions(Set.of(MpegFile.TagType.ID3V2), MpegFile.StripTags.STRIP_OTHERS, Tag.Version.V3, MpegFile.DuplicateTags.DUPLICATE));
      assertTrue(file.hasId3v2Tag());
      var data = frame.render();
      assertEquals(26, data.size());
      var reread = new UnsynchronizedLyricsFrame(data);
      assertEquals("Foo", reread.text());
      assertEquals(UTF_16, reread.textEncoding());
    }
  }

  @Test
  void testUTF16BEDelimiter() {
    var frame = new TextIdentificationFrame(b("TPE1"), UTF_16BE);
    frame.setText(List.of("Foo", "Bar"));
    var data = frame.render();
    assertEquals(25, data.size());
    assertEquals(hex("545045310000000f0000020046006f006f0000004200610072"), data);
    frame.setData(data);
    assertEquals("Foo Bar", frame.toString());
  }

  @Test
  void testUTF16Delimiter() {
    var frame = new TextIdentificationFrame(b("TPE1"), UTF_16);
    frame.setText(List.of("Foo", "Bar"));
    var data = frame.render();
    assertEquals(29, data.size());
    assertEquals(hex("5450453100000013000001fffe46006f006f000000fffe420061007200"), data);
    frame.setData(data);
    assertEquals("Foo Bar", frame.toString());
    for (String other : List.of("5450453100000013000001feff0046006f006f0000feff004200610072", "5450453100000011000001fffe46006f006f000000420061007200", "5450453100000011000001feff0046006f006f0000004200610072")) {
      frame.setData(hex(other));
      assertEquals("Foo Bar", frame.toString());
    }
  }

  @Test
  void testReadStringField() {
    assertEquals("abc", new PublicFrame().stringField(hex("61626300")));
  }

  @Test
  void testDontRender22() {
    var header = new Header();
    header.setMajorVersion(2);
    var frame = assertInstanceOf(UnknownFrame.class, FrameFactory.instance().createFrame(hex("464f4f000008004a504701640000"), header));
    var tag = new Tag();
    tag.addFrame(frame);
    assertEquals(1034, tag.render().size());
  }

  @Test
  void testPOPMFromFile() throws Exception {
    Path path = copy("xing.mp3");
    var frame = new PopularimeterFrame();
    frame.setEmail("email@example.com");
    frame.setRating(200);
    frame.setCounter(3);
    try (var file = new MpegFile(path)) {
      file.id3v2Tag().addFrame(frame);
      file.save();
    }
    try (var file = new MpegFile(path)) {
      var read = assertInstanceOf(PopularimeterFrame.class, frames(file.id3v2Tag(), "POPM").getFirst());
      assertEquals("email@example.com", read.email());
      assertEquals(200, read.rating());
      assertEquals(3, read.counter());
    }
  }

  @Test
  void testBrokenFrame1() throws IOException {
    try (var file = new MpegFile(fixture("broken-tenc.id3"), ReadOptions.TAGS_ONLY)) {
      assertNotNull(file.tag());
      assertFalse(contains(file.id3v2Tag(), "TENC"));
    }
  }

  @Test
  void testItunes24FrameSize() throws IOException {
    try (var file = new MpegFile(fixture("005411.id3"), ReadOptions.TAGS_ONLY)) {
      assertNotNull(file.tag());
      assertTrue(contains(file.id3v2Tag(), "TIT2"));
      assertEquals("Sunshine Superman", frames(file.id3v2Tag(), "TIT2").getFirst().toString());
    }
  }

  @Test
  void testSaveUTF16Comment() throws Exception {
    var factory = FrameFactory.instance();
    var encoding = factory.defaultTextEncoding();
    Path path = copy("xing.mp3");
    try {
      factory.setDefaultTextEncoding(UTF_16);
      try (var file = new MpegFile(path)) {
        file.strip();
        file.tag().setComment("Test comment!");
        file.save();
      }
      try (var file = new MpegFile(path)) {
        assertEquals("Test comment!", file.tag().comment());
      }
    } finally {
      factory.setDefaultTextEncoding(encoding);
    }
  }

  static void genre(String data, int version, List<String> fields, String value) {
    var header = new Header();
    header.setMajorVersion(version);
    var frame = assertInstanceOf(TextIdentificationFrame.class, FrameFactory.instance().createFrame(hex(data), header));
    assertEquals(fields, frame.fieldList());
    var tag = new Tag();
    tag.addFrame(frame);
    assertEquals(value, tag.genre());
  }

  @Test
  void testUpdateGenre23_1() {
    genre("54434f4e00000010000000283232294465617468204d6574616c", 3, List.of("Death Metal"), "Death Metal");
  }

  @Test
  void testUpdateGenre23_2() {
    genre("54434f4e0000000d0000002834294575726f646973636f", 3, List.of("4", "Eurodisco"), "Disco / Eurodisco");
  }

  @Test
  void testUpdateGenre23_3() {
    genre("54434f4e00000015000000283929283133382956696b696e67204d6574616c", 3, List.of("9", "138", "Viking Metal"), "Metal / Black Metal / Viking Metal");
  }

  @Test
  void testUpdateGenre24() {
    genre("54434f4e0000000d0000003134004575726f646973636f", 4, List.of("14", "Eurodisco"), "R&B / Eurodisco");
  }

  @Test
  void testUpdateDate22() throws IOException {
    try (var file = new MpegFile(fixture("id3v22-tda.mp3"), ReadOptions.TAGS_ONLY)) {
      assertNotNull(file.tag());
      assertEquals(2010, file.tag().year());
    }
  }

  @Test
  void testDowngradeTo23() throws Exception {
    Path path = copy("xing.mp3");
    try (var file = new MpegFile(path)) {
      var tag = file.id3v2Tag();
      tag.addFrame(text("TDOR", "2011-03-16"));
      tag.addFrame(text("TDRC", "2012-04-17T12:01"));
      tag.addFrame(text("TMCL", List.of("Guitar", "Artist 1", "Drums", "Artist 2")));
      tag.addFrame(text("TIPL", List.of("Producer", "Artist 3", "Engineer", "Artist 4")));
      tag.addFrame(text("TCON", List.of("51", "Noise", "Power Noise")));
      for (String id : List.of("TDRL", "TDTG", "TMOO", "TPRO", "TSOA", "TSOT", "TSST", "TSOP"))
        tag.addFrame(new TextIdentificationFrame(b(id), ISO_8859_1));
      file.save(new MpegSaveOptions(Set.of(MpegFile.TagType.values()), MpegFile.StripTags.STRIP_OTHERS, Tag.Version.V3, MpegFile.DuplicateTags.DUPLICATE));
    }
    try (var file = new MpegFile(path)) {
      var tag = file.id3v2Tag();
      assertEquals(List.of("2011"), assertInstanceOf(TextIdentificationFrame.class, frames(tag, "TDOR").getFirst()).fieldList());
      assertEquals(List.of("2012-04-17T12:01"), assertInstanceOf(TextIdentificationFrame.class, frames(tag, "TDRC").getFirst()).fieldList());
      assertEquals(List.of("Producer", "Artist 3", "Engineer", "Artist 4"), assertInstanceOf(TextIdentificationFrame.class, frames(tag, "TIPL").getFirst()).fieldList());
      assertEquals(List.of("Guitar", "Artist 1", "Drums", "Artist 2"), assertInstanceOf(TextIdentificationFrame.class, frames(tag, "TMCL").getFirst()).fieldList());
      assertEquals(List.of("51", "39", "Power Noise"), assertInstanceOf(TextIdentificationFrame.class, frames(tag, "TCON").getFirst()).fieldList());
      for (String id : List.of("TDRL", "TDTG", "TMOO", "TPRO", "TSST")) assertFalse(contains(tag, id));
    }
    var expected = hex("4944330300000000094854534f410000000100000054534f540000000100000054534f5000000001000000544f5259000000050000003230313154594552000000050000003230313254444154000000050000003137303454494d45000000050000003132303149504c5300000043000000477569746172004172746973742031004472756d730041727469737420320050726f647563657200417274697374203300456e67696e65657200417274697374203454434f4e000000140000002835312928333929506f776572204e6f697365");
    assertEquals(expected, new ByteVector(Files.readAllBytes(path)).mid(0, expected.size()));
    try (var file = new MpegFile(copy("rare_frames.mp3"))) {
      file.save(new MpegSaveOptions(Set.of(MpegFile.TagType.values()), MpegFile.StripTags.STRIP_OTHERS, Tag.Version.V3, MpegFile.DuplicateTags.DUPLICATE));
      file.seek(file.find(b("TCON")) + 11);
      assertEquals(b("(13)"), file.readBlock(4));
    }
  }

  @Test
  void testCompressedFrameWithBrokenLength() throws IOException {
    try (var file = new MpegFile(fixture("compressed_id3_frame_invalid.mp3"), ReadOptions.TAGS_ONLY)) {
      assertTrue(contains(file.id3v2Tag(), "APIC"));
      var frame = assertInstanceOf(AttachedPictureFrame.class, frames(file.id3v2Tag(), "APIC").getFirst());
      assertEquals("image/bmp", frame.mimeType());
      assertEquals(AttachedPictureFrame.Type.Other, frame.type());
      assertEquals("", frame.description());
      assertEquals(142, frame.picture().size());
    }
  }

  @Test
  void testW000() throws IOException {
    try (var file = new MpegFile(fixture("w000.mp3"), ReadOptions.TAGS_ONLY)) {
      assertTrue(contains(file.id3v2Tag(), "W000"));
      var frame = assertInstanceOf(UrlLinkFrame.class, frames(file.id3v2Tag(), "W000").getFirst());
      assertEquals("lukas.lalinsky@example.com____", frame.url());
    }
  }

  @Test
  void testPropertyInterface() throws Exception {
    try (var file = new MpegFile(copy("rare_frames.mp3"))) {
      var p = file.id3v2Tag().properties();
      assertEquals(6, p.size());
      assertEquals(List.of("userTextData1", "userTextData2"), p.get("USERTEXTDESCRIPTION1"));
      assertEquals(List.of("userTextData1", "userTextData2"), p.get("QuodLibet::USERTEXTDESCRIPTION2"));
      assertEquals(List.of("Pop"), p.get("GENRE"));
      assertEquals(List.of("http://a.user.url"), p.get("URL:USERURL"));
      assertEquals(List.of("http://a.user.url/with/empty/description"), p.get("URL"));
      assertEquals(List.of("A COMMENT"), p.get("COMMENT"));
      assertEquals(List.of("UFID/supermihi@web.de"), p.unsupportedData());
    }
  }

  @Test
  void testPropertyInterface2() {
    var tag = new Tag();
    var lyrics = new UnsynchronizedLyricsFrame();
    lyrics.setDescription("test");
    lyrics.setText("la-la-la test");
    tag.addFrame(lyrics);
    var plainLyrics = new UnsynchronizedLyricsFrame();
    plainLyrics.setDescription("");
    plainLyrics.setText("la-la-la nodescription");
    tag.addFrame(plainLyrics);
    var picture = new AttachedPictureFrame();
    picture.setDescription("test picture");
    tag.addFrame(picture);
    tag.addFrame(text("TIPL", "single value is invalid for TIPL"));
    tag.addFrame(text("TMCL", List.of("VIOLIN", "a violinist", "PIANO", "a pianist")));
    var mb = new UniqueFileIdentifierFrame("http://musicbrainz.org", b("152454b9-19ba-49f3-9fc9-8fc26545cf41"));
    tag.addFrame(mb);
    var opaque = new UniqueFileIdentifierFrame("http://example.com", b("123"));
    tag.addFrame(opaque);
    var album = new UserTextIdentificationFrame();
    album.setDescription("MusicBrainz Album Id");
    album.setText("95c454a5-d7e0-4d8f-9900-db04aca98ab3");
    tag.addFrame(album);
    tag.addFrame(new UnknownFrame(b("WXYZ")));
    var comment = new CommentsFrame();
    comment.setDescription("iTunNORM");
    comment.setText("00002C3B");
    comment.setLanguage(b("eng"));
    tag.addFrame(comment);
    var p = tag.properties();
    assertEquals(List.of("APIC", "TIPL", "UFID/http://example.com", "UNKNOWN/WXYZ"), p.unsupportedData());
    assertEquals(List.of("a violinist"), p.get("PERFORMER:VIOLIN"));
    assertEquals(List.of("a pianist"), p.get("PERFORMER:PIANO"));
    assertEquals(List.of("la-la-la nodescription"), p.get("LYRICS"));
    assertEquals(List.of("la-la-la test"), p.get("LYRICS:TEST"));
    assertEquals(List.of("152454b9-19ba-49f3-9fc9-8fc26545cf41"), p.get("MUSICBRAINZ_TRACKID"));
    assertEquals(List.of("95c454a5-d7e0-4d8f-9900-db04aca98ab3"), p.get("MUSICBRAINZ_ALBUMID"));
    assertEquals(List.of("00002C3B"), p.get("COMMENT:ITUNNORM"));
    tag.removeUnsupportedProperties(p.unsupportedData());
    for (String id : List.of("APIC", "TIPL", "WXYZ")) assertTrue(frames(tag, id).isEmpty());
    assertNull(UniqueFileIdentifierFrame.findByOwner(tag, "http://example.com"));
    assertSame(lyrics, UnsynchronizedLyricsFrame.findByDescription(tag, "test"));
    assertSame(mb, UniqueFileIdentifierFrame.findByOwner(tag, "http://musicbrainz.org"));
    assertSame(album, UserTextIdentificationFrame.find(tag, "MusicBrainz Album Id"));
    assertNull(UserTextIdentificationFrame.find(tag, "non existing"));
    assertSame(comment, CommentsFrame.findByDescription(tag, "iTunNORM"));
    assertNull(CommentsFrame.findByDescription(tag, "non existing"));
    tag.addFrame(text("TIPL", List.of("arranger", "an arranger", "ENGINEER", "an engineer", "producer", "a producer", "DJ-mix", "a DJ mixer", "mix", "a mixer")));
    p = tag.properties();
    assertTrue(p.unsupportedData().isEmpty());
    String[] keys = {"ARRANGER", "ENGINEER", "PRODUCER", "DJMIXER", "MIXER"};
    String[] values = {"an arranger", "an engineer", "a producer", "a DJ mixer", "a mixer"};
    for (int i = 0; i < keys.length; i++) assertEquals(List.of(values[i]), p.get(keys[i]));
  }

  @Test
  void testPropertiesMovement() {
    var tag = new Tag();
    var name = text("MVNM", "Movement Name");
    var index = text("MVIN", "2/3");
    tag.addFrame(name);
    tag.addFrame(index);
    var p = tag.properties();
    assertEquals(List.of("Movement Name"), p.get("MOVEMENTNAME"));
    assertEquals(List.of("2/3"), p.get("MOVEMENTNUMBER"));
    var nameData = hex("4d564e4d0000000e0000004d6f76656d656e74204e616d65");
    var indexData = hex("4d56494e00000004000000322f33");
    assertEquals(nameData, name.render());
    assertEquals(indexData, index.render());
    var header = new Header();
    var parsedName = assertInstanceOf(TextIdentificationFrame.class, FrameFactory.instance().createFrame(nameData, header));
    var parsedIndex = assertInstanceOf(TextIdentificationFrame.class, FrameFactory.instance().createFrame(indexData, header));
    assertEquals("Movement Name", parsedName.toString());
    assertEquals("2/3", parsedIndex.toString());
    tag.addFrame(parsedName);
    tag.addFrame(parsedIndex);
  }

  @Test
  void testPropertyGrouping() {
    var tag = new Tag();
    var frame = text("GRP1", "Grouping");
    tag.addFrame(frame);
    assertEquals(List.of("Grouping"), tag.properties().get("GROUPING"));
    var data = hex("475250310000000900000047726f7570696e67");
    assertEquals(data, frame.render());
    var parsed = assertInstanceOf(TextIdentificationFrame.class, FrameFactory.instance().createFrame(data, new Header()));
    assertEquals("Grouping", parsed.toString());
    tag.addFrame(parsed);
  }

  @Test
  void testDeleteFrame() throws Exception {
    Path path = copy("rare_frames.mp3");
    try (var file = new MpegFile(path)) {
      var tag = file.id3v2Tag();
      assertEquals(1, frames(tag, "TCON").size());
      tag.removeFrame(frames(tag, "TCON").getFirst());
      file.save(new MpegSaveOptions(Set.of(MpegFile.TagType.ID3V2), MpegFile.StripTags.STRIP_OTHERS, Tag.Version.V4, MpegFile.DuplicateTags.DUPLICATE));
    }
    try (var file = new MpegFile(path)) {
      assertTrue(frames(file.id3v2Tag(), "TCON").isEmpty());
    }
  }

  @Test
  void testSaveAndStripID3v1ShouldNotAddFrameFromID3v1ToId3v2() throws Exception {
    Path path = copy("xing.mp3");
    try (var file = new MpegFile(path)) {
      file.tag().setArtist("Artist");
      file.save(new MpegSaveOptions(Set.of(MpegFile.TagType.ID3V1, MpegFile.TagType.ID3V2), MpegFile.StripTags.STRIP_OTHERS, Tag.Version.V4, MpegFile.DuplicateTags.DUPLICATE));
    }
    try (var file = new MpegFile(path)) {
      file.id3v2Tag().removeFrames(b("TPE1"));
      file.save(new MpegSaveOptions(Set.of(MpegFile.TagType.ID3V2), MpegFile.StripTags.STRIP_OTHERS, Tag.Version.V4, MpegFile.DuplicateTags.DUPLICATE));
    }
    try (var file = new MpegFile(path)) {
      assertFalse(contains(file.id3v2Tag(), "TPE1"));
    }
  }

  @Test
  void testShrinkPadding() throws Exception {
    Path path = copy("xing.mp3");
    try (var file = new MpegFile(path)) {
      file.id3v2Tag().setTitle("X".repeat(64 * 1024));
      file.save(new MpegSaveOptions(Set.of(MpegFile.TagType.ID3V2), MpegFile.StripTags.STRIP_OTHERS, Tag.Version.V4, MpegFile.DuplicateTags.DUPLICATE));
    }
    try (var file = new MpegFile(path)) {
      assertTrue(file.hasId3v2Tag());
      assertEquals(74789, file.length());
      file.id3v2Tag().setTitle("ABCDEFGHIJ");
      file.save(new MpegSaveOptions(Set.of(MpegFile.TagType.ID3V2), MpegFile.StripTags.STRIP_OTHERS, Tag.Version.V4, MpegFile.DuplicateTags.DUPLICATE));
    }
    try (var file = new MpegFile(path)) {
      assertTrue(file.hasId3v2Tag());
      assertEquals(9263, file.length());
    }
  }

  @Test
  void testEmptyFrame() throws Exception {
    Path path = copy("xing.mp3");
    try (var file = new MpegFile(path)) {
      file.getOrCreateId3v2Tag().addFrame(new UrlLinkFrame(hex("574f414600000001000000")));
      file.id3v2Tag().addFrame(text("TIT2", "Title"));
      file.save();
    }
    try (var file = new MpegFile(path)) {
      assertTrue(file.hasId3v2Tag());
      assertEquals("Title", file.id3v2Tag().title());
      assertTrue(frames(file.id3v2Tag(), "WOAF").isEmpty());
    }
  }

  @Test
  void testDuplicateTags() throws Exception {
    Path path = copy("duplicate_id3v2.mp3");
    ByteVector audio;
    // Native suite's earlier encoding test enables the sticky LATIN1 override.
    // An isolated factory reproduces that setting without depending on JUnit order.
    var factory = new FrameFactory();
    factory.setDefaultTextEncoding(ISO_8859_1);
    try (var file = new MpegFile(path, ReadOptions.DEFAULT, factory)) {
      file.seek(file.id3v2Tag().header().completeTagSize());
      audio = file.readBlock(2089);
      assertTrue(file.hasId3v2Tag());
      assertEquals(8049, file.id3v2Tag().header().completeTagSize());
      assertEquals(44100, file.audioProperties().sampleRate());
      file.id3v2Tag().setArtist("Artist A");
      file.save(new MpegSaveOptions(Set.of(MpegFile.TagType.ID3V2), MpegFile.StripTags.STRIP_OTHERS, Tag.Version.V4, MpegFile.DuplicateTags.DUPLICATE));
    }
    try (var file = new MpegFile(path, ReadOptions.DEFAULT, factory)) {
      assertTrue(file.hasId3v2Tag());
      assertEquals(3594, file.length());
      assertEquals(1505, file.id3v2Tag().header().completeTagSize());
      assertEquals("Artist A", file.id3v2Tag().artist());
      assertEquals(44100, file.audioProperties().sampleRate());
      file.seek(file.id3v2Tag().header().completeTagSize());
      assertEquals(audio, file.readBlock(2089));
    }
  }

  @Test
  void testParseTOCFrameWithManyChildren() throws IOException {
    try (var file = new MpegFile(fixture("toc_many_children.mp3"))) {
      assertTrue(file.isValid());
      var tag = file.id3v2Tag();
      assertEquals(130, tag.frameList().size());
      for (int i = 0; i < tag.frameList().size(); i++) {
        var frame = tag.frameList().get(i);
        if (i == 0) {
          assertEquals(b("CTOC"), frame.frameID());
          var toc = assertInstanceOf(TableOfContentsFrame.class, frame);
          assertEquals(b("toc"), toc.elementID());
          assertFalse(toc.isTopLevel());
          assertFalse(toc.isOrdered());
          assertEquals(129, toc.entryCount());
          assertEquals(1, toc.embeddedFrameList().size());
          assertEquals(List.of("toplevel toc"), assertInstanceOf(TextIdentificationFrame.class, toc.embeddedFrameList().getFirst()).fieldList());
        } else {
          assertEquals(b("CHAP"), frame.frameID());
          var chapter = assertInstanceOf(ChapterFrame.class, frame);
          assertEquals(b("chapter" + (i - 1)), chapter.elementID());
          assertEquals(100 * i, chapter.startTime());
          assertEquals(100 * i, chapter.endTime());
          assertEquals(1, chapter.embeddedFrameList().size());
          assertEquals("Marker " + i, assertInstanceOf(TextIdentificationFrame.class, chapter.embeddedFrameList().getFirst()).fieldList().getFirst());
        }
      }
      assertNull(ChapterFrame.findByElementID(tag, b("chap2")));
      assertNotNull(ChapterFrame.findByElementID(tag, b("chapter2")));
      assertNull(TableOfContentsFrame.findTopLevel(tag));
      assertNull(TableOfContentsFrame.findByElementID(tag, b("ctoc")));
      var toc = TableOfContentsFrame.findByElementID(tag, b("toc"));
      assertNotNull(toc);
      assertEquals(1, toc.embeddedFrameList().size());
      toc.removeEmbeddedFrames(b("TIT2"));
      assertTrue(toc.embeddedFrameList().isEmpty());
    }
  }

  @Test
  void testInvalidID3v2Version() {
    assertEquals(0, new Header(hex("494433ff0005144f0032")).tagSize());
    assertEquals(0, new Header(hex("49443304ff05144f0032")).tagSize());
  }

  @Test
  void testParseAPIC() {
    var frame = new AttachedPictureFrame(hex("41504943000000070000006d0001640000"));
    assertEquals("m", frame.mimeType());
    assertEquals(AttachedPictureFrame.Type.FileIcon, frame.type());
    assertEquals("d", frame.description());
  }

  @Test
  void testParseAPIC_UTF16_BOM() {
    var frame = new AttachedPictureFrame(hex("4150494300000026000001696d6167652f6a7065670000feff0063006f007600650072002e006a007000670000ffd8ff"));
    assertEquals("image/jpeg", frame.mimeType());
    assertEquals(AttachedPictureFrame.Type.Other, frame.type());
    assertEquals("cover.jpg", frame.description());
    assertEquals(hex("ffd8ff"), frame.picture());
  }

  @Test
  void testParseAPICv22() {
    var header = new Header();
    header.setMajorVersion(2);
    var frame = assertInstanceOf(AttachedPictureFrame.class, FrameFactory.instance().createFrame(hex("504943000008004a504701640000"), header));
    assertEquals("image/jpeg", frame.mimeType());
    assertEquals(AttachedPictureFrame.Type.FileIcon, frame.type());
    assertEquals("d", frame.description());
  }

  @Test
  void testParseUniqueFileIdentifierFrame() {
    var frame = new UniqueFileIdentifierFrame(hex("554649440000000900006f776e657200000102"));
    assertEquals("owner", frame.owner());
    assertEquals(hex("000102"), frame.identifier());
  }

  @Test
  void testParseEmptyUniqueFileIdentifierFrame() {
    var frame = new UniqueFileIdentifierFrame(hex("5546494400000001000000"));
    assertEquals("", frame.owner());
    assertEquals(new ByteVector(), frame.identifier());
  }

  @Test
  void testRenderUniqueFileIdentifierFrame() {
    assertEquals(hex("554649440000000900006f776e657200010203"), new UniqueFileIdentifierFrame("owner", hex("010203")).render());
  }

  @Test
  void testParseUrlLinkFrame() {
    assertEquals("http://example.com", new UrlLinkFrame(hex("574f4146000000120000687474703a2f2f6578616d706c652e636f6d")).url());
  }

  @Test
  void testRenderUrlLinkFrame() {
    var frame = new UrlLinkFrame(b("WOAF"));
    frame.setUrl("http://example.com");
    assertEquals(hex("574f4146000000120000687474703a2f2f6578616d706c652e636f6d"), frame.render());
  }

  @Test
  void testParseUserUrlLinkFrame() {
    var frame = new UserUrlLinkFrame(hex("5758585800000017000000666f6f00687474703a2f2f6578616d706c652e636f6d"));
    assertEquals("foo", frame.description());
    assertEquals("http://example.com", frame.url());
  }

  @Test
  void testRenderUserUrlLinkFrame() {
    var frame = new UserUrlLinkFrame();
    frame.setDescription("foo");
    frame.setUrl("http://example.com");
    assertEquals(hex("5758585800000017000000666f6f00687474703a2f2f6578616d706c652e636f6d"), frame.render());
  }

  @Test
  void testParseCommentsFrame() {
    var frame = new CommentsFrame(hex("434f4d4d000000140000036465754465736372697074696f6e0054657874"));
    assertEquals(UTF_8, frame.textEncoding());
    assertEquals(b("deu"), frame.language());
    assertEquals("Description", frame.description());
    assertEquals("Text", frame.text());
  }

  @Test
  void testRenderCommentsFrame() {
    var frame = new CommentsFrame();
    frame.setTextEncoding(UTF_16);
    frame.setLanguage(b("eng"));
    frame.setDescription("Description");
    frame.setText("Text");
    assertEquals(hex("434f4d4d00000028000001656e67fffe4400650073006300720069007000740069006f006e000000fffe5400650078007400"), frame.render());
  }

  @Test
  void testParsePodcastFrame() {
    assertInstanceOf(PodcastFrame.class, FrameFactory.instance().createFrame(hex("5043535400000004000000000000"), new Header()));
  }

  @Test
  void testRenderPodcastFrame() {
    assertEquals(hex("5043535400000004000000000000"), new PodcastFrame().render());
  }

  @Test
  void testParseUserTextIdentificationFrame() {
    var empty = new UserTextIdentificationFrame(hex("54585858000000060000000054657874"));
    assertEquals("", empty.description());
    assertEquals("Text", empty.fieldList().get(1));
    var frame = new UserTextIdentificationFrame(hex("54585858000000110000004465736372697074696f6e0054657874"));
    assertEquals("Description", frame.description());
    assertEquals("Text", frame.fieldList().get(1));
  }
}
