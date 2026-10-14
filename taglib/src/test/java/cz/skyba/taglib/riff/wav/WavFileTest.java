package cz.skyba.taglib.riff.wav;

import cz.skyba.taglib.*;
import cz.skyba.taglib.id3.v2.Tag;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.FileStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import static cz.skyba.taglib.UpstreamAudioFixtures.fixture;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent assertions corresponding to all 26 pinned TestWAV registrations.
 */
class WavFileTest {
  @TempDir
  Path dir;

  Path copy(String name) throws Exception {
    return UpstreamAudioFixtures.copy(dir, name);
  }

  static void audio(Properties p, long ms, int bitrate, int channels, int rate, int bits, long frames, int format) {
    assertNotNull(p);
    assertEquals(ms / 1000, p.lengthInSeconds());
    assertEquals(ms, p.lengthInMilliseconds());
    assertEquals(bitrate, p.bitrate());
    assertEquals(channels, p.channels());
    assertEquals(rate, p.sampleRate());
    assertEquals(bits, p.bitsPerSample());
    assertEquals(frames, p.sampleFrames());
    assertEquals(format, p.format());
  }

  @Test
  void testPCMProperties() throws IOException {
    try (var f = new WavFile(fixture("empty.wav"))) {
      audio(f.detailedProperties(), 3675, 32, 2, 1000, 16, 3675, 1);
    }
  }

  @Test
  void testALAWProperties() throws IOException {
    try (var f = new WavFile(fixture("alaw.wav"))) {
      audio(f.detailedProperties(), 3550, 128, 2, 8000, 8, 28400, 6);
    }
  }

  @Test
  void testFloatProperties() throws IOException {
    try (var f = new WavFile(fixture("float64.wav"))) {
      audio(f.detailedProperties(), 97, 5645, 2, 44100, 64, 4281, 3);
    }
  }

  @Test
  void testFloatWithoutFactChunkProperties() throws Exception {
    var bytes = new ByteVector(Files.readAllBytes(fixture("float64.wav")));
    assertEquals(new ByteVector("fact"), bytes.mid(36, 4));
    bytes.set(38, (byte) 'k');
    try (var f = new WavFile(new ByteVectorStream(bytes))) {
      audio(f.detailedProperties(), 97, 5645, 2, 44100, 64, 4281, 3);
    }
  }

  @Test
  void testZeroSizeDataChunk() throws IOException {
    try (var f = new WavFile(fixture("zero-size-chunk.wav"))) {
      assertTrue(f.isValid());
    }
  }

  void textTag(boolean id3) throws Exception {
    var path = copy("empty.wav");
    try (var f = new WavFile(path)) {
      assertTrue(f.isValid());
      assertFalse(id3 ? f.hasId3v2Tag() : f.hasInfoTag());
      var t = id3 ? f.id3v2Tag() : f.infoTag();
      t.setTitle("Title");
      t.setArtist("Artist");
      f.save();
      assertTrue(id3 ? f.hasId3v2Tag() : f.hasInfoTag());
    }
    try (var f = new WavFile(path)) {
      assertTrue(f.isValid());
      assertTrue(id3 ? f.hasId3v2Tag() : f.hasInfoTag());
      var t = id3 ? f.id3v2Tag() : f.infoTag();
      assertEquals("Title", t.title());
      assertEquals("Artist", t.artist());
      t.setTitle("");
      t.setArtist("");
      f.save();
      assertFalse(id3 ? f.hasId3v2Tag() : f.hasInfoTag());
    }
    try (var f = new WavFile(path)) {
      assertTrue(f.isValid());
      assertFalse(id3 ? f.hasId3v2Tag() : f.hasInfoTag());
      var t = id3 ? f.id3v2Tag() : f.infoTag();
      assertEquals("", t.title());
      assertEquals("", t.artist());
    }
  }

  @Test
  void testID3v2Tag() throws Exception {
    textTag(true);
  }

  @Test
  void testInfoTag() throws Exception {
    textTag(false);
  }

  @Test
  void testSaveID3v23() throws Exception {
    var path = copy("empty.wav");
    String text = "X".repeat(254);
    try (var f = new WavFile(path)) {
      assertFalse(f.hasId3v2Tag());
      f.tag().setTitle(text);
      f.tag().setArtist("Artist A");
      f.save(new WavSaveOptions(Set.of(WavFile.TagType.values()), FileBase.StripTags.STRIP_OTHERS, Tag.Version.V3));
      assertTrue(f.hasId3v2Tag());
    }
    try (var f = new WavFile(path)) {
      assertEquals(3, f.id3v2Tag().header().majorVersion());
      assertEquals("Artist A", f.tag().artist());
      assertEquals(text, f.tag().title());
    }
  }

  @Test
  void testStripTags() throws Exception {
    var path = copy("empty.wav");
    try (var f = new WavFile(path)) {
      f.id3v2Tag().setTitle("test title");
      f.infoTag().setTitle("test title");
      f.save();
    }
    try (var f = new WavFile(path)) {
      assertTrue(f.hasId3v2Tag());
      assertTrue(f.hasInfoTag());
      f.save(new WavSaveOptions(Set.of(WavFile.TagType.ID3V2), FileBase.StripTags.STRIP_OTHERS, Tag.Version.V4));
    }
    try (var f = new WavFile(path)) {
      assertTrue(f.hasId3v2Tag());
      assertFalse(f.hasInfoTag());
      f.id3v2Tag().setTitle("test title");
      f.infoTag().setTitle("test title");
      f.save();
    }
    try (var f = new WavFile(path)) {
      assertTrue(f.hasId3v2Tag());
      assertTrue(f.hasInfoTag());
      f.save(new WavSaveOptions(Set.of(WavFile.TagType.INFO), FileBase.StripTags.STRIP_OTHERS, Tag.Version.V4));
    }
    try (var f = new WavFile(path)) {
      assertFalse(f.hasId3v2Tag());
      assertTrue(f.hasInfoTag());
    }
  }

  @Test
  void testDuplicateTags() throws Exception {
    try (var f = new WavFile(copy("duplicate_tags.wav"))) {
      assertEquals(17052, f.length());
      assertTrue(f.hasId3v2Tag());
      assertEquals("Title1", f.id3v2Tag().title());
      assertTrue(f.hasInfoTag());
      assertEquals("Title1", f.infoTag().title());
      f.save();
      assertEquals(15898, f.length());
      assertEquals(-1, f.find(new ByteVector("Title2")));
    }
  }

  @Test
  void testFuzzedFile1() throws IOException {
    try (var f = new WavFile(fixture("infloop.wav"))) {
      assertTrue(f.isValid());
      var p = f.detailedProperties();
      assertEquals(1, p.channels());
      assertEquals(88, p.bitrate());
      assertEquals(8, p.bitsPerSample());
      assertEquals(11025, p.sampleRate());
      assertFalse(f.hasInfoTag());
      assertFalse(f.hasId3v2Tag());
    }
  }

  @Test
  void testFuzzedFile2() throws IOException {
    try (var f = new WavFile(fixture("segfault.wav"))) {
      assertTrue(f.isValid());
    }
  }

  @Test
  void testFileWithGarbageAppended() throws Exception {
    var path = copy("empty.wav");
    Files.write(path, new ByteVector("\r2345678").toByteArray(), StandardOpenOption.APPEND);
    var before = Files.readAllBytes(path);
    try (var f = new WavFile(path)) {
      assertTrue(f.isValid());
      f.id3v2Tag().setTitle("ID3v2 Title");
      f.infoTag().setTitle("INFO Title");
      f.save();
    }
    try (var f = new WavFile(path)) {
      f.strip();
    }
    assertArrayEquals(before, Files.readAllBytes(path));
  }

  @Test
  void testStripAndProperties() throws Exception {
    var path = copy("empty.wav");
    try (var f = new WavFile(path)) {
      f.id3v2Tag().setTitle("ID3v2");
      f.infoTag().setTitle("INFO");
      f.save();
    }
    try (var f = new WavFile(path)) {
      assertEquals("ID3v2", f.properties().get("TITLE").getFirst());
      f.strip(Set.of(WavFile.TagType.ID3V2));
      assertEquals("INFO", f.properties().get("TITLE").getFirst());
      f.strip(Set.of(WavFile.TagType.INFO));
      assertTrue(f.properties().isEmpty());
    }
  }

  @Test
  void testPCMWithFactChunk() throws IOException {
    try (var f = new WavFile(fixture("pcm_with_fact_chunk.wav"))) {
      audio(f.detailedProperties(), 3675, 32, 2, 1000, 16, 3675, 1);
    }
  }

  @Test
  void testWaveFormatExtensible() throws IOException {
    try (var f = new WavFile(fixture("uint8we.wav"))) {
      audio(f.detailedProperties(), 2937, 128, 2, 8000, 8, 23493, 1);
    }
  }

  @Test
  void testInvalidChunk() throws Exception {
    var path = copy("invalid-chunk.wav");
    try (var f = new WavFile(path)) {
      assertEquals(0, f.audioProperties().lengthInSeconds());
      assertTrue(f.hasId3v2Tag());
      f.id3v2Tag().setTitle("Title");
      f.save();
    }
    try (var f = new WavFile(path)) {
      assertFalse(f.hasId3v2Tag());
    }
  }

  @Test
  void testRF64IsSupported() throws Exception {
    var path = copy("rf64.wav");
    for (String magic : List.of("RF64", "BW64", "XX64")) {
      try (var stream = new FileStream(path)) {
        stream.writeBlock(new ByteVector(magic));
        stream.seek(7);
        assertEquals(!magic.equals("XX64"), WavFile.isSupported(stream));
        assertEquals(7, stream.tell());
      }
    }
  }

  @Test
  void testRF64Properties() throws Exception {
    var path = copy("rf64.wav");
    Files.write(path, new ByteVector("junk").append(ByteVector.fromUnsignedInt(1000, false)).append(new ByteVector(1000)).toByteArray(), StandardOpenOption.APPEND);
    try (var f = new WavFile(path)) {
      assertTrue(f.isValid());
      assertEquals(50, f.audioProperties().lengthInMilliseconds());
      assertEquals(48000, f.audioProperties().sampleRate());
      assertEquals(2, f.audioProperties().channels());
    }
  }

  void rf64(boolean repair) throws Exception {
    var path = copy("rf64.wav");
    long original = Files.size(path);
    if (repair) try (var s = new FileStream(path)) {
      s.seek(4);
      s.writeBlock(ByteVector.fromUnsignedInt(5230, false));
    }
    try (var f = new WavFile(path)) {
      assertTrue(f.isValid());
      if (repair) f.infoTag().setTitle("Title");
      else
        assertTrue(f.replaceProperties(PropertyMap.builder().put("TITLE", "Title").put("ARTIST", "Artist").build()).rejected().isEmpty());
      f.save();
    }
    try (var f = new WavFile(path)) {
      assertEquals("Title", f.properties().get("TITLE").getFirst());
      if (!repair) assertEquals("Artist", f.properties().get("ARTIST").getFirst());
      assertEquals(50, f.audioProperties().lengthInMilliseconds());
    }
    long length = Files.size(path);
    assertTrue(length > original);
    try (var s = new FileStream(path, true)) {
      s.seek(4);
      assertEquals(0xffffffffL, s.readBlock(4).toUnsignedInt(false));
      s.seek(20);
      assertEquals(length - 8, s.readBlock(8).toUnsignedLong(false));
      s.seek(28);
      assertEquals(9600, s.readBlock(8).toUnsignedLong(false));
    }
  }

  @Test
  void testRF64Save() throws Exception {
    rf64(false);
  }

  @Test
  void testRF64SaveRepairsClobberedSize() throws Exception {
    rf64(true);
  }

  @Test
  void testRIFFInfoProperties() throws Exception {
    var b = PropertyMap.builder();
    b.put("ALBUM", "Album");
    b.put("ARRANGER", "Arranger");
    b.put("ARTIST", "Artist");
    b.put("ARTISTWEBPAGE", "Artist Webpage");
    b.put("BPM", "123");
    b.put("COMMENT", "Comment");
    b.put("COMPOSER", "Composer");
    b.put("COPYRIGHT", "2023 Copyright");
    b.put("DATE", "2023");
    b.put("DISCSUBTITLE", "Disc Subtitle");
    b.put("ENCODEDBY", "Encoded by");
    b.put("ENCODING", "Encoding");
    b.put("ENCODINGTIME", "2023-11-25 15:42:39");
    b.put("GENRE", "Genre");
    b.put("ISRC", "UKAAA0500001");
    b.put("LABEL", "Label");
    b.put("LANGUAGE", "eng");
    b.put("LYRICIST", "Lyricist");
    b.put("MEDIA", "Media");
    b.put("PERFORMER", "Performer");
    b.put("RELEASECOUNTRY", "Release Country");
    b.put("REMIXER", "Remixer");
    b.put("TITLE", "Title");
    b.put("TRACKNUMBER", "2/4");
    var expected = b.build();
    var path = copy("empty.wav");
    try (var f = new WavFile(path)) {
      assertTrue(f.infoTag().isEmpty());
      assertTrue(f.infoTag().properties().isEmpty());
      f.infoTag().replaceProperties(expected);
      f.save();
    }
    try (var f = new WavFile(path)) {
      assertFalse(f.infoTag().isEmpty());
      assertEquals(expected, f.infoTag().properties());
      var fields = new TreeMap<ByteVector, String>();
      fields.put(new ByteVector("IPRD"), "Album");
      fields.put(new ByteVector("IENG"), "Arranger");
      fields.put(new ByteVector("IART"), "Artist");
      fields.put(new ByteVector("IBSU"), "Artist Webpage");
      fields.put(new ByteVector("IBPM"), "123");
      fields.put(new ByteVector("ICMT"), "Comment");
      fields.put(new ByteVector("IMUS"), "Composer");
      fields.put(new ByteVector("ICOP"), "2023 Copyright");
      fields.put(new ByteVector("ICRD"), "2023");
      fields.put(new ByteVector("PRT1"), "Disc Subtitle");
      fields.put(new ByteVector("ITCH"), "Encoded by");
      fields.put(new ByteVector("ISFT"), "Encoding");
      fields.put(new ByteVector("IDIT"), "2023-11-25 15:42:39");
      fields.put(new ByteVector("IGNR"), "Genre");
      fields.put(new ByteVector("ISRC"), "UKAAA0500001");
      fields.put(new ByteVector("IPUB"), "Label");
      fields.put(new ByteVector("ILNG"), "eng");
      fields.put(new ByteVector("IWRI"), "Lyricist");
      fields.put(new ByteVector("IMED"), "Media");
      fields.put(new ByteVector("ISTR"), "Performer");
      fields.put(new ByteVector("ICNT"), "Release Country");
      fields.put(new ByteVector("IEDT"), "Remixer");
      fields.put(new ByteVector("INAM"), "Title");
      fields.put(new ByteVector("IPRT"), "2/4");
      assertEquals(fields, f.infoTag().fieldListMap());
    }
  }

  void broadcast(boolean xml) throws Exception {
    var path = copy("empty.wav");
    String text = xml ? "<BWFXML><IXML_VERSION>1.0</IXML_VERSION></BWFXML>" : "test bext data";
    try (var f = new WavFile(path)) {
      assertTrue(f.isValid());
      assertFalse(xml ? f.hasiXMLData() : f.hasBEXTData());
      assertTrue(xml ? f.iXMLData().isEmpty() : f.BEXTData().length == 0);
      if (xml) f.setiXMLData(text);
      else f.setBEXTData(new ByteVector(text));
      f.save();
      assertTrue(xml ? f.hasiXMLData() : f.hasBEXTData());
    }
    try (var f = new WavFile(path)) {
      assertTrue(f.isValid());
      assertTrue(xml ? f.hasiXMLData() : f.hasBEXTData());
      if (xml) {
        assertEquals(text, f.iXMLData());
        f.setiXMLData("");
      } else {
        assertArrayEquals(new ByteVector(text).toByteArray(), f.BEXTData());
        f.setBEXTData(new ByteVector());
      }
      f.save();
      assertFalse(xml ? f.hasiXMLData() : f.hasBEXTData());
    }
    try (var f = new WavFile(path)) {
      assertTrue(f.isValid());
      assertFalse(xml ? f.hasiXMLData() : f.hasBEXTData());
      assertTrue(xml ? f.iXMLData().isEmpty() : f.BEXTData().length == 0);
    }
    assertArrayEquals(Files.readAllBytes(fixture("empty.wav")), Files.readAllBytes(path));
  }

  @Test
  void testBEXTTag() throws Exception {
    broadcast(false);
  }

  @Test
  void testiXMLTag() throws Exception {
    broadcast(true);
  }

  @Test
  void testBEXTTagWithOtherTags() throws Exception {
    var path = copy("empty.wav");
    try (var f = new WavFile(path)) {
      f.id3v2Tag().setTitle("ID3v2 Title");
      f.infoTag().setTitle("INFO Title");
      f.setBEXTData(new ByteVector("bext payload"));
      f.save();
    }
    try (var f = new WavFile(path)) {
      assertTrue(f.hasId3v2Tag());
      assertTrue(f.hasInfoTag());
      assertTrue(f.hasBEXTData());
      assertEquals("ID3v2 Title", f.id3v2Tag().title());
      assertEquals("INFO Title", f.infoTag().title());
      assertArrayEquals(new ByteVector("bext payload").toByteArray(), f.BEXTData());
    }
  }

  @Test
  void testiXMLTagWithOtherTags() throws Exception {
    var path = copy("empty.wav");
    try (var f = new WavFile(path)) {
      f.id3v2Tag().setTitle("ID3v2 Title");
      f.setiXMLData("<BWFXML><SCENE>1</SCENE></BWFXML>");
      f.setBEXTData(new ByteVector("bext data"));
      f.save();
    }
    try (var f = new WavFile(path)) {
      assertTrue(f.hasId3v2Tag());
      assertTrue(f.hasiXMLData());
      assertTrue(f.hasBEXTData());
      assertEquals("ID3v2 Title", f.id3v2Tag().title());
      assertEquals("<BWFXML><SCENE>1</SCENE></BWFXML>", f.iXMLData());
      assertArrayEquals(new ByteVector("bext data").toByteArray(), f.BEXTData());
      f.setiXMLData("");
      f.setBEXTData(new ByteVector());
      f.strip();
      f.save();
    }
    try (var f = new WavFile(path)) {
      assertTrue(f.isValid());
      assertFalse(f.hasId3v2Tag());
      assertFalse(f.hasiXMLData());
      assertFalse(f.hasBEXTData());
      assertTrue(f.iXMLData().isEmpty());
      assertEquals(0, f.BEXTData().length);
    }
    assertArrayEquals(Files.readAllBytes(fixture("empty.wav")), Files.readAllBytes(path));
  }

  @Test
  void liveOwnerSharesTagsButStripReplacesSelectedChild() throws Exception {
    try (var ref = new AudioFile(copy("empty.wav"))) {
      var f = assertInstanceOf(WavFile.class, ref.file());
      var union = ref.tag();
      var old = f.id3v2Tag();
      var info = f.infoTag();
      ref.tag().setTitle("live");
      assertEquals("live", old.title());
      assertEquals("live", info.title());
      assertDoesNotThrow(ref::save);
      assertSame(old, f.id3v2Tag());
      f.strip(Set.of(WavFile.TagType.ID3V2));
      assertSame(union, ref.tag());
      assertNotSame(old, f.id3v2Tag());
      assertSame(info, f.infoTag());
      assertEquals("live", old.title());
      assertEquals("live", ref.tag().title());
    }
  }

  @Test
  void disabledCachedAudioStillAllowsDirectPropertiesScan() throws Exception {
    try (var f = new WavFile(copy("empty.wav"), new ReadOptions(false, ReadStyle.ACCURATE, ReadOptions.DEFAULT.maxMetadataBytes()))) {
      assertNull(f.audioProperties());
      assertNull(f.detailedProperties());
      audio(new Properties(f, ReadStyle.FAST), 3675, 32, 2, 1000, 16, 3675, 1);
    }
  }

  @Test
  void truncatedMemoryRejectsBeforeImmediateStripAndSave() throws Exception {
    var stream = new ByteVectorStream(Files.readAllBytes(fixture("duplicate_tags.wav")));
    try (var f = new WavFile(stream)) {
      var tag = f.id3v2Tag();
      stream.truncate(12);
      var data = stream.data().toByteArray();
      assertThrows(TagLibException.class, f::save);
      assertThrows(IllegalStateException.class, f::strip);
      assertArrayEquals(data, stream.data().toByteArray());
      assertSame(tag, f.id3v2Tag());
      assertTrue(f.hasId3v2Tag());
      assertTrue(f.hasInfoTag());
    }
  }

  @Test
  void repeatedInPlaceSavePreservesChildren() throws Exception {
    var path = copy("empty.wav");
    try (var f = new WavFile(path)) {
      var t = f.id3v2Tag();
      var info = f.infoTag();
      t.setTitle("first");
      f.save();
      assertSame(t, f.id3v2Tag());
      assertSame(info, f.infoTag());
      t.setTitle("in-place");
      f.save();
    }
    try (var f = new WavFile(path)) {
      assertEquals("in-place", f.tag().title());
    }
  }
}
