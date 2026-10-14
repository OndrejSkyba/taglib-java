package cz.skyba.taglib.ogg;

import cz.skyba.taglib.*;
import cz.skyba.taglib.flac.Picture;
import cz.skyba.taglib.ogg.flac.OggFlacFile;
import cz.skyba.taglib.ogg.opus.OpusFile;
import cz.skyba.taglib.ogg.speex.SpeexFile;
import cz.skyba.taglib.ogg.vorbis.VorbisFile;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.FileStream;
import cz.skyba.taglib.toolkit.IOStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static cz.skyba.taglib.UpstreamAudioFixtures.copy;
import static cz.skyba.taglib.UpstreamAudioFixtures.fixture;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent installed codec-owner and complete upstream assertion counterparts.
 */
class OggCodecFileTest {
  @TempDir
  Path directory;

  static OggFile open(Path path) throws IOException {
    return switch (path.toString().substring(path.toString().lastIndexOf('.') + 1)) {
      case "opus" -> new OpusFile(path);
      case "spx" -> new SpeexFile(path);
      case "oga" -> new OggFlacFile(path);
      default -> new VorbisFile(path);
    };
  }

  static OggFile open(IOStream stream, String ext, boolean audio) throws IOException {
    return switch (ext) {
      case "opus" ->
        new OpusFile(stream, new ReadOptions(audio, ReadStyle.AVERAGE, ReadOptions.DEFAULT.maxMetadataBytes()));
      case "spx" ->
        new SpeexFile(stream, new ReadOptions(audio, ReadStyle.AVERAGE, ReadOptions.DEFAULT.maxMetadataBytes()));
      case "oga" ->
        new OggFlacFile(stream, new ReadOptions(audio, ReadStyle.AVERAGE, ReadOptions.DEFAULT.maxMetadataBytes()));
      default ->
        new VorbisFile(stream, new ReadOptions(audio, ReadStyle.AVERAGE, ReadOptions.DEFAULT.maxMetadataBytes()));
    };
  }

  static XiphComment comment(OggFile f) {
    return (XiphComment) f.tag();
  }

  static String longText() {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_";
    return alphabet.repeat(131072 / alphabet.length() + 1).substring(0, 131072);
  }

  static void splitState(OggFile f, long length, int sequence, int p0, int p1, int p2, int p3, String title, int duration) {
    assertTrue(f.isValid());
    assertEquals(length, f.length());
    assertEquals(sequence, f.lastPageHeader().pageSequenceNumber());
    assertEquals(p0, f.packet(0).size());
    assertEquals(p1, f.packet(1).size());
    assertEquals(p2, f.packet(2).size());
    assertEquals(p3, f.packet(3).size());
    assertEquals(title, f.tag().title());
    assertNotNull(f.audioProperties());
    assertEquals(duration, f.audioProperties().lengthInMilliseconds());
  }

  @Test
  void opusAudioProperties() throws IOException {
    try (var f = new OpusFile(fixture("correctness_gain_silent_output.opus"))) {
      var p = f.detailedProperties();
      assertNotNull(p);
      assertEquals(7, p.lengthInSeconds());
      assertEquals(7737, p.lengthInMilliseconds());
      assertEquals(36, p.bitrate());
      assertEquals(1, p.channels());
      assertEquals(48000, p.sampleRate());
      assertEquals(48000, p.inputSampleRate());
      assertEquals(1, p.opusVersion());
      assertEquals(-17920, p.outputGain());
      assertSame(p.common(), f.audioProperties());
    }
  }

  @Test
  void speexAudioProperties() throws IOException {
    try (var f = new SpeexFile(fixture("empty.spx"))) {
      var p = f.detailedProperties();
      assertNotNull(p);
      assertEquals(3, p.lengthInSeconds());
      assertEquals(3685, p.lengthInMilliseconds());
      assertEquals(53, p.bitrate());
      assertEquals(-1, p.bitrateNominal());
      assertEquals(2, p.channels());
      assertEquals(44100, p.sampleRate());
      assertSame(p.common(), f.audioProperties());
    }
  }

  private static void opusComments(OpusFile f, boolean changed) {
    assertEquals(List.of("Xiph.Org Opus testvectormaker"), f.tag().fieldListMap().get("ENCODER"));
    assertTrue(f.tag().fieldListMap().containsKey("TESTDESCRIPTION"));
    if (changed) assertEquals(List.of("Your Tester"), f.tag().fieldListMap().get("ARTIST"));
    else assertFalse(f.tag().fieldListMap().containsKey("ARTIST"));
    assertEquals("libopus 0.9.11-66-g64c2dd7", f.tag().vendorID());
  }

  @Test
  void opusReadComments() throws IOException {
    try (var f = new OpusFile(fixture("correctness_gain_silent_output.opus"))) {
      opusComments(f, false);
    }
  }

  @Test
  void opusWriteComments() throws Exception {
    var path = copy(directory, "correctness_gain_silent_output.opus");
    try (var f = new OpusFile(path)) {
      f.tag().setArtist("Your Tester");
      f.save();
    }
    try (var f = new OpusFile(path)) {
      opusComments(f, true);
    }
  }

  @Test
  void opusSplitPackets() throws Exception {
    var p = copy(directory, "correctness_gain_silent_output.opus");
    var text = longText();
    try (var f = open(p)) {
      f.tag().setTitle(text);
      f.save();
    }
    try (var f = open(p)) {
      splitState(f, 167534, 27, 19, 131380, 5, 5, text, 7737);
      f.tag().setTitle("ABCDE");
      f.save();
    }
    try (var f = open(p)) {
      splitState(f, 35521, 11, 19, 313, 5, 5, "ABCDE", 7737);
    }
  }

  @Test
  void speexSplitPackets() throws Exception {
    var p = copy(directory, "empty.spx");
    var text = longText();
    try (var f = open(p)) {
      f.tag().setTitle(text);
      f.save();
    }
    try (var f = open(p)) {
      splitState(f, 156330, 23, 80, 131116, 93, 93, text, 3685);
      f.tag().setTitle("ABCDE");
      f.save();
    }
    try (var f = open(p)) {
      splitState(f, 24317, 7, 80, 49, 93, 93, "ABCDE", 3685);
    }
  }

  @Test
  void oggFlacSplitPackets() throws Exception {
    var p = copy(directory, "empty_flac.oga");
    var text = longText();
    try (var f = open(p)) {
      f.tag().setTitle(text);
      f.save();
    }
    try (var f = open(p)) {
      splitState(f, 141141, 21, 51, 131126, 22, 8196, text, 3705);
      f.tag().setTitle("ABCDE");
      f.save();
    }
    try (var f = open(p)) {
      splitState(f, 9128, 5, 51, 59, 22, 8196, "ABCDE", 3705);
    }
  }

  @Test
  void oggFlacFramingBit() throws Exception {
    var p = copy(directory, "empty_flac.oga");
    try (var f = open(p)) {
      f.tag().setArtist("The Artist");
      f.save();
    }
    try (var f = open(p)) {
      assertEquals("The Artist", f.tag().artist());
      f.seek(0, IOStream.Position.END);
      assertEquals(9134, f.tell());
    }
  }

  @Test
  void oggFlacFuzzedFileHasBoundedJavaInvalidOutcome() throws IOException {
    assertThrows(TagLibException.class,
      () -> new OggFlacFile(fixture("segfault.oga")));
  }

  @Test
  void xiphClearComment() throws Exception {
    var p = copy(directory, "empty.ogg");
    try (var f = new VorbisFile(p)) {
      f.tag().addField("COMMENT", "Comment1");
      f.save();
    }
    try (var f = new VorbisFile(p)) {
      f.tag().setComment("");
      assertEquals("", f.tag().comment());
    }
  }

  @Test
  void xiphRemoveFields() throws IOException {
    try (var f = new VorbisFile(fixture("empty.ogg"))) {
      var c = f.tag();
      c.addField("title", "Title1");
      c.addField("Title", "Title1", false);
      c.addField("titlE", "Title2", false);
      c.addField("TITLE", "Title3", false);
      c.addField("artist", "Artist1");
      c.addField("ARTIST", "Artist2", false);
      assertEquals("Title1 / Title1 / Title2 / Title3", c.title());
      assertEquals("Artist1 / Artist2", c.artist());
      c.removeFields("title", "Title1");
      assertEquals("Title2 / Title3", c.title());
      assertEquals("Artist1 / Artist2", c.artist());
      c.removeFields("Artist");
      assertEquals("Title2 / Title3", c.title());
      assertTrue(c.artist().isEmpty());
      c.removeAllFields();
      assertTrue(c.title().isEmpty());
      assertTrue(c.artist().isEmpty());
      assertEquals("Xiph.Org libVorbis I 20050304", c.vendorID());
    }
  }

  @Test
  void xiphPicture() throws Exception {
    var path = copy(directory, "empty.ogg");
    try (var f = new VorbisFile(path)) {
      var p = new Picture();
      p.setType(Picture.Type.BackCover);
      p.setWidth(5);
      p.setHeight(6);
      p.setColorDepth(16);
      p.setNumColors(7);
      p.setMimeType("image/jpeg");
      p.setDescription("new image");
      p.setData(new ByteVector("JPEG data").toByteArray());
      f.tag().addPicture(p);
      f.save();
    }
    try (var f = new VorbisFile(path)) {
      var pictures = f.tag().pictureList();
      assertEquals(1, pictures.size());
      var p = pictures.getFirst();
      assertEquals(5, p.width());
      assertEquals(6, p.height());
      assertEquals(16, p.colorDepth());
      assertEquals(7, p.numColors());
      assertEquals("image/jpeg", p.mimeType());
      assertEquals("new image", p.description());
      assertArrayEquals(new ByteVector("JPEG data").toByteArray(), p.data());
    }
  }

  @Test
  void xiphLowercaseFields() throws Exception {
    var p = copy(directory, "lowercase-fields.ogg");
    try (var f = new VorbisFile(p)) {
      assertEquals("TEST TITLE", f.tag().title());
      assertEquals("TEST ARTIST", f.tag().artist());
      assertEquals(1, f.tag().pictureList().size());
      f.save();
    }
    try (var f = new VorbisFile(p)) {
      assertTrue(f.find(new ByteVector("METADATA_BLOCK_PICTURE")) > 0);
    }
  }

  static void fields(TagBase t, boolean second) {
    t.setArtist(second ? "ttest artist" : "test artist");
    t.setTitle(second ? "ytest title" : "test title");
    t.setGenre(second ? "uTest!" : "Test!");
    t.setAlbum(second ? "ialbummmm" : "albummmm");
    t.setComment(second ? "another comment" : "a comment");
    t.setTrack(second ? 7 : 5);
    t.setYear(second ? 2080 : 2020);
  }

  static void assertFields(TagBase t, boolean second) {
    assertEquals(second ? "ttest artist" : "test artist", t.artist());
    assertEquals(second ? "ytest title" : "test title", t.title());
    assertEquals(second ? "uTest!" : "Test!", t.genre());
    assertEquals(second ? "ialbummmm" : "albummmm", t.album());
    assertEquals(second ? "another comment" : "a comment", t.comment());
    assertEquals(second ? 7 : 5, t.track());
    assertEquals(second ? 2080 : 2020, t.year());
  }

  static void assertOwner(AudioFile f, String codec) {
    assertTrue(f.isOpen());
    Class<?> expected = switch (codec) {
      case "vorbis" -> VorbisFile.class;
      case "opus" -> OpusFile.class;
      case "speex" -> SpeexFile.class;
      case "flac" -> OggFlacFile.class;
      default -> throw new IllegalArgumentException(codec);
    };
    assertEquals(expected, f.file().getClass().getSuperclass());
    assertSame(f.tag(), f.file().tag());
  }

  void fileRefRoundTrip(String name, String codec) throws Exception {
    var p = copy(directory, name);
    try (var f = new AudioFile(p)) {
      assertOwner(f, codec);
      fields(f.tag(), false);
      assertDoesNotThrow(f::save);
    }
    try (var f = new AudioFile(p)) {
      assertOwner(f, codec);
      assertFields(f.tag(), false);
      fields(f.tag(), true);
      assertDoesNotThrow(f::save);
    }
    try (var f = new AudioFile(p)) {
      assertTrue(f.isOpen());
      assertFields(f.tag(), true);
    }
    try (var s = new FileStream(p)) {
      try (var f = new AudioFile(s)) {
        assertOwner(f, codec);
        assertFields(f.tag(), true);
        fields(f.tag(), false);
        assertDoesNotThrow(f::save);
      }
      assertTrue(s.isOpen());
    }
    byte[] content;
    try (var s = new FileStream(p); var f = new AudioFile(s)) {
      assertOwner(f, codec);
      assertFields(f.tag(), false);
      s.seek(0);
      content = s.readBlock(s.length()).toByteArray();
    }
    var s = new ByteVectorStream(content);
    try (var f = new AudioFile(s)) {
      assertOwner(f, codec);
      assertFields(f.tag(), false);
      fields(f.tag(), true);
      assertDoesNotThrow(f::save);
      content = s.data().toByteArray();
    }
    var reopened = new ByteVectorStream(content);
    try (var f = new AudioFile(reopened)) {
      assertOwner(f, codec);
      assertFields(f.tag(), true);
    }
  }

  @Test
  void fileRefVorbis() throws Exception {
    fileRefRoundTrip("empty.ogg", "vorbis");
  }

  @Test
  void fileRefSpeex() throws Exception {
    fileRefRoundTrip("empty.spx", "speex");
  }

  @Test
  void fileRefOgaFlac() throws Exception {
    fileRefRoundTrip("empty_flac.oga", "flac");
  }

  @Test
  void fileRefOgaVorbis() throws Exception {
    fileRefRoundTrip("empty_vorbis.oga", "vorbis");
  }

  @Test
  void fileRefOpus() throws Exception {
    fileRefRoundTrip("correctness_gain_silent_output.opus", "opus");
  }

  @ParameterizedTest
  @CsvSource({"empty.ogg,vorbis", "empty_vorbis.oga,vorbis", "lowercase-fields.ogg,vorbis", "test.ogg,vorbis", "empty_flac.oga,flac", "empty.spx,speex", "correctness_gain_silent_output.opus,opus"})
  void fileRefContentDetection(String name, String codec) throws Exception {
    var p = directory.resolve("unrecognized.bin");
    Files.copy(fixture(name), p);
    try (var f = new AudioFile(p)) {
      assertOwner(f, codec);
    }
    try (var s = new FileStream(p); var f = new AudioFile(s)) {
      assertOwner(f, codec);
    }
    var s = new ByteVectorStream(Files.readAllBytes(p));
    try (var f = new AudioFile(s)) {
      assertOwner(f, codec);
    }
  }

  @Test
  void audioFileRejectsCorruptOggFlac() throws Exception {
    var p = directory.resolve("corrupt.bin");
    Files.copy(fixture("segfault.oga"), p);
    assertThrows(TagLibException.class, () -> new AudioFile(p));
    try (var s = new FileStream(p)) {
      assertThrows(TagLibException.class, () -> new AudioFile(s));
      assertTrue(s.isOpen());
    }
    var s = new ByteVectorStream(Files.readAllBytes(p));
    assertThrows(TagLibException.class, () -> new AudioFile(s));
    assertTrue(s.isOpen());
  }

  @ParameterizedTest
  @CsvSource({"empty.ogg,ogg", "empty.spx,spx", "empty_flac.oga,oga", "correctness_gain_silent_output.opus,opus"})
  void disabledPropertiesStillShareMutableChildAndPackets(String name, String ext) throws Exception {
    var s = new ByteVectorStream(Files.readAllBytes(fixture(name)));
    try (var f = open(s, ext, false)) {
      assertTrue(f.isValid());
      assertNull(f.audioProperties());
      var t = f.tag();
      var p0 = f.packet(0);
      t.setTitle("live");
      f.save();
      assertSame(t, f.tag());
      assertEquals(p0, f.packet(0));
      assertEquals("live", f.properties().get("TITLE").getFirst());
    }
    assertTrue(s.isOpen());
    try (var f = open(s, ext, false)) {
      assertEquals("live", f.tag().title());
    }
  }

  @Test
  void subclassPropertiesUseStoredCommentWhileComplexUsesVirtualTag() throws Exception {
    class Derived extends VorbisFile {
      final XiphComment alternate = new XiphComment();

      Derived(IOStream s) throws IOException {
        super(s, ReadOptions.TAGS_ONLY);
      }

      @Override
      public XiphComment tag() {
        return alternate;
      }
    }
    var s = new ByteVectorStream(Files.readAllBytes(fixture("empty.ogg")));
    try (var f = new Derived(s)) {
      f.tag().setTitle("virtual");
      f.replaceProperties(PropertyMap.builder().put("TITLE", "stored").build());
      assertEquals("stored", f.properties().get("TITLE").getFirst());
      assertEquals("virtual", f.tag().title());
      var pic = new Picture();
      pic.setData(new byte[]{1});
      f.tag().addPicture(pic);
      assertEquals(1, f.complexProperties("PICTURE").size());
      f.save();
    }
    try (var f = new VorbisFile(s, ReadOptions.TAGS_ONLY)) {
      assertEquals("stored", f.tag().title());
      assertTrue(f.tag().pictureList().isEmpty());
    }
  }

  @Test
  void metadataBudgetRejectsLargeCommentBeforeMaterializingIt() throws Exception {
    var s = new ByteVectorStream(Files.readAllBytes(fixture("empty.ogg")));
    try (var f = new VorbisFile(s, ReadOptions.TAGS_ONLY)) {
      f.tag().setTitle("x".repeat(8192));
      f.save();
    }
    assertThrows(TagLibException.class, () -> new VorbisFile(s, new ReadOptions(false, ReadStyle.AVERAGE, 1024)));
    assertTrue(s.isOpen());
  }

  @Test
  void inPlaceSaveRetainsChildAndPreservesSymlink() throws Exception {
    var path = copy(directory, "empty.ogg");
    var link = directory.resolve("link.ogg");
    Files.createSymbolicLink(link, path.getFileName());
    try (var f = new VorbisFile(link)) {
      var tag = f.tag();
      tag.setTitle("saved");
      f.save();
      assertSame(tag, f.tag());
      f.save();
    }
    assertTrue(Files.isSymbolicLink(link));
    try (var f = new VorbisFile(path)) {
      assertEquals("saved", f.tag().title());
    }
  }

  static byte[] page(int sequence, int flags, byte[]... packets) {
    var laces = new ByteArrayOutputStream();
    var body = new ByteArrayOutputStream();
    for (var p : packets) {
      for (int n = 0; n < p.length / 255; n++) laces.write(255);
      laces.write(p.length % 255);
      body.writeBytes(p);
    }
    var b = ByteBuffer.allocate(27 + laces.size() + body.size()).order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[]{'O', 'g', 'g', 'S', 0, (byte) flags}).putLong(0).putInt(1).putInt(sequence).putInt(0).put((byte) laces.size()).put(laces.toByteArray()).put(body.toByteArray());
    return b.array();
  }

  static byte[] block(int type, byte[] payload) {
    return new ByteVector(new byte[]{(byte) type, (byte) (payload.length >>> 16), (byte) (payload.length >>> 8), (byte) payload.length}).append(new ByteVector(payload)).toByteArray();
  }

  static byte[] mapping(int major, int minor, int type) {
    return new ByteVector(new byte[]{127, 'F', 'L', 'A', 'C', (byte) major, (byte) minor, 0, 1, 'f', 'L', 'a', 'C'}).append(new ByteVector(block(type, new byte[34]))).toByteArray();
  }

  static byte[] join(byte[]... values) {
    var b = new ByteArrayOutputStream();
    for (var v : values) b.writeBytes(v);
    return b.toByteArray();
  }

  @ParameterizedTest
  @CsvSource({"1,0,true", "1,9,true", "2,0,true", "2,9,false"})
  void mappingVersionUsesNativeConjunction(int major, int minor, boolean valid) throws IOException {
    var data = join(page(0, 2, mapping(major, minor, 0)), page(1, 4, block(132, new byte[8])));
    if (!valid) {
      assertThrows(TagLibException.class, () -> new OggFlacFile(new ByteVectorStream(data), ReadOptions.TAGS_ONLY));
    } else {
      try (var f = new OggFlacFile(new ByteVectorStream(data), ReadOptions.TAGS_ONLY)) {
        assertTrue(f.isValid());
        assertNotNull(f.tag());
      }
    }
  }

  @Test
  void historicalMappingAndPhysicalCommentPresence() throws IOException {
    var data = join(page(0, 2, new byte[]{'f', 'L', 'a', 'C'}), page(1, 0, block(0, new byte[34])), page(2, 4, block(132, new byte[8])));
    try (var f = new OggFlacFile(new ByteVectorStream(data), ReadOptions.TAGS_ONLY)) {
      assertTrue(f.isValid());
      assertTrue(f.hasXiphComment());
      assertEquals(data.length - 42, f.streamLength());
      var t = f.tag();
      t.setTitle("old mapping");
      f.save();
      assertSame(t, f.tag());
      assertEquals(132, f.packet(2).at(0) & 255);
    }
  }

  @Test
  void missingCommentIsValidButCannotBeSaved() throws IOException {
    var data = page(0, 6, mapping(1, 0, 128));
    var stream = new ByteVectorStream(data);
    try (var f = new OggFlacFile(stream)) {
      assertTrue(f.isValid());
      assertFalse(f.hasXiphComment());
      assertNotNull(f.tag());
      assertNotNull(f.detailedProperties());
      assertEquals(data.length - 34, f.streamLength());
      f.tag().setTitle("staged");
      assertThrows(TagLibException.class, f::save);
      assertFalse(f.hasXiphComment());
      assertEquals("staged", f.tag().title());
      assertArrayEquals(data, stream.data().toByteArray());
    }
  }

  @Test
  void missingLastMarkerUsesFrameSyncAndSetsCommentLastBit() throws IOException {
    var data = join(page(0, 2, mapping(1, 0, 0)), page(1, 0, block(4, new byte[8])), page(2, 4, new byte[]{(byte) 255, (byte) 248, 0, 0, 1}));
    try (var f = new OggFlacFile(new ByteVectorStream(data), ReadOptions.TAGS_ONLY)) {
      assertTrue(f.isValid());
      assertEquals(data.length - 42, f.streamLength());
      f.save();
      assertEquals(132, f.packet(1).at(0) & 255);
    }
  }

  @ParameterizedTest
  @CsvSource({"1023,true", "1024,true", "1025,false"})
  void nativeMetadataBlockCountBoundary(int count, boolean valid) throws IOException {
    var out = new ByteArrayOutputStream();
    out.writeBytes(page(0, 2, mapping(1, 0, 0)));
    for (int i = 1; i < count - 1; i++) out.writeBytes(page(i, 0, block(1, new byte[0])));
    out.writeBytes(page(count - 1, 4, block(132, new byte[8])));
    if (!valid) {
      assertThrows(TagLibException.class, () -> new OggFlacFile(new ByteVectorStream(out.toByteArray()), ReadOptions.TAGS_ONLY));
    } else {
      try (var f = new OggFlacFile(new ByteVectorStream(out.toByteArray()), ReadOptions.TAGS_ONLY)) {
        assertTrue(f.isValid());
        assertNotNull(f.tag());
      }
    }
  }

  @Test
  void flacAggregateBudgetBoundsManySmallBlocks() {
    var out = new ByteArrayOutputStream();
    out.writeBytes(page(0, 2, mapping(1, 0, 0)));
    for (int i = 1; i < 300; i++) out.writeBytes(page(i, 0, block(1, new byte[0])));
    out.writeBytes(page(300, 4, block(132, new byte[8])));
    assertThrows(TagLibException.class, () -> new OggFlacFile(new ByteVectorStream(out.toByteArray()), new ReadOptions(false, ReadStyle.AVERAGE, 1024)));
  }

  @Test
  void oversizedFlacPictureIsRemovedBeforeRetry() throws Exception {
    var stream = new ByteVectorStream(Files.readAllBytes(fixture("empty_flac.oga")));
    try (var f = new OggFlacFile(stream, ReadOptions.TAGS_ONLY)) {
      var t = f.tag();
      var pic = new Picture();
      pic.setData(new byte[3 * (1 << 22)]);
      t.addPicture(pic);
      t.setTitle("retained");
      f.save();
      assertSame(t, f.tag());
      assertTrue(t.pictureList().isEmpty());
      assertEquals("retained", t.title());
    }
    try (var f = new OggFlacFile(stream, ReadOptions.TAGS_ONLY)) {
      assertEquals("retained", f.tag().title());
      assertTrue(f.tag().pictureList().isEmpty());
    }
  }

  @Test
  void oversizedFlacTextFailsAfterRemovingPicturesWithUnchangedBytes() throws Exception {
    byte[] data = Files.readAllBytes(fixture("empty_flac.oga"));
    var stream = new ByteVectorStream(data);
    try (var f = new OggFlacFile(stream, ReadOptions.TAGS_ONLY)) {
      var pic = new Picture();
      pic.setData(new byte[]{1});
      f.tag().addPicture(pic);
      var title = "x".repeat(1 << 24);
      f.tag().setTitle(title);
      assertThrows(TagLibException.class, f::save);
      assertTrue(f.tag().pictureList().isEmpty());
      assertEquals(title, f.tag().title());
      assertArrayEquals(data, stream.data().toByteArray());
    }
  }

  @Test
  void failedSavePreservesOriginalAndAllowsCorrectedSave() throws Exception {
    var path = copy(directory, "empty.ogg");
    byte[] original = Files.readAllBytes(path);
    try (var f = new VorbisFile(path)) {
      var t = f.tag();
      var pic = new Picture() {
        @Override
        public byte[] render() {
          throw new IllegalStateException("ordinary picture callback");
        }
      };
      t.addPicture(pic);
      assertThrows(IllegalStateException.class, f::save);
      assertSame(t, f.tag());
      assertTrue(f.isValid());
      assertArrayEquals(original, Files.readAllBytes(path));
      t.removeAllPictures();
      f.save();
    }
    try (var f = new VorbisFile(path)) {
      assertTrue(f.isValid());
    }
  }

  @Test
  void truncatedMemoryGrowthHasBoundedUnchangedByteOutcome() throws Exception {
    byte[] data = join(page(0, 2, new byte[]{1, 'v', 'o', 'r', 'b', 'i', 's'}), page(1, 4, new byte[]{3, 'v', 'o', 'r', 'b', 'i', 's', 0, 0, 0, 0, 0, 0, 0, 0}));
    // Increase only the declared final payload length. Native growth is undefined; Java-only.
    data[data.length - 16] = (byte) 30;
    var stream = new ByteVectorStream(data);
    try (var f = new VorbisFile(stream, ReadOptions.TAGS_ONLY)) {
      assertTrue(f.isValid());
      f.tag().setTitle("x".repeat(8192));
      assertThrows(TagLibException.class, f::save);
      assertEquals(8192, f.tag().title().length());
      assertArrayEquals(data, stream.data().toByteArray());
    }
  }

  @Test
  void vorbisSimple() throws Exception {
    var p = copy(directory, "empty.ogg");
    try (var f = open(p)) {
      f.tag().setArtist("The Artist");
      f.save();
    }
    try (var f = open(p)) {
      assertEquals("The Artist", f.tag().artist());
    }
  }

  @Test
  void vorbisSplitPackets1() throws Exception {
    var p = copy(directory, "empty.ogg");
    String text = longText();
    try (var f = open(p)) {
      f.tag().setTitle(text);
      f.save();
    }
    try (var f = open(p)) {
      assertTrue(f.isValid());
      assertEquals(136383, f.length());
      assertEquals(19, f.lastPageHeader().pageSequenceNumber());
      assertEquals(30, f.packet(0).size());
      assertEquals(131127, f.packet(1).size());
      assertEquals(3832, f.packet(2).size());
      assertEquals(text, f.tag().title());
      assertNotNull(f.audioProperties());
      assertEquals(3685, f.audioProperties().lengthInMilliseconds());
      f.tag().setTitle("ABCDE");
      f.save();
    }
    try (var f = open(p)) {
      assertTrue(f.isValid());
      assertEquals(4370, f.length());
      assertEquals(3, f.lastPageHeader().pageSequenceNumber());
      assertEquals(30, f.packet(0).size());
      assertEquals(60, f.packet(1).size());
      assertEquals(3832, f.packet(2).size());
      assertEquals("ABCDE", f.tag().title());
      assertNotNull(f.audioProperties());
      assertEquals(3685, f.audioProperties().lengthInMilliseconds());
    }
  }

  @Test
  void vorbisSplitPackets2() throws Exception {
    var p = copy(directory, "empty.ogg");
    String text = longText().substring(0, 60890);
    try (var f = open(p)) {
      f.tag().setTitle(text);
      f.save();
    }
    try (var f = open(p)) {
      assertTrue(f.isValid());
      assertEquals(text, f.tag().title());
      f.tag().setTitle("ABCDE");
      f.save();
    }
    try (var f = open(p)) {
      assertTrue(f.isValid());
      assertEquals("ABCDE", f.tag().title());
    }
  }

  @Test
  void vorbisDictInterface1() throws Exception {
    var p = copy(directory, "empty.ogg");
    try (var f = open(p)) {
      assertEquals(0, f.tag().properties().asMap().size());
      f.tag().replaceProperties(PropertyMap.builder().put("ARTIST", List.of("value 1", "value 2")).build());
      var map = f.tag().properties();
      assertEquals(1, map.asMap().size());
      assertEquals(2, map.get("ARTIST").size());
      assertEquals("value 1", map.get("ARTIST").getFirst());
    }
  }

  @Test
  void vorbisDictInterface2() throws Exception {
    var p = copy(directory, "test.ogg");
    try (var f = open(p)) {
      var tags = f.tag().properties();
      assertEquals(2, tags.get("UNUSUALTAG").size());
      assertEquals("usual value", tags.get("UNUSUALTAG").get(0));
      assertEquals("another value", tags.get("UNUSUALTAG").get(1));
      assertEquals("öäüoΣø", tags.get("UNICODETAG").getFirst());
      f.tag().replaceProperties(tags.toBuilder().put("UNICODETAG", "νεω ναλυε").remove("UNUSUALTAG").build());
      assertEquals("νεω ναλυε", f.tag().properties().get("UNICODETAG").getFirst());
      assertFalse(f.tag().properties().containsKey("UNUSUALTAG"));
    }
  }

  @Test
  void vorbisAudioProperties() throws IOException {
    try (var f = new VorbisFile(fixture("empty.ogg"))) {
      var p = f.detailedProperties();
      assertNotNull(p);
      assertEquals(3, p.lengthInSeconds());
      assertEquals(3685, p.lengthInMilliseconds());
      assertEquals(1, p.bitrate());
      assertEquals(2, p.channels());
      assertEquals(44100, p.sampleRate());
      assertEquals(0, p.vorbisVersion());
      assertEquals(0, p.bitrateMaximum());
      assertEquals(112000, p.bitrateNominal());
      assertEquals(0, p.bitrateMinimum());
    }
  }

  @Test
  void vorbisMultiplexed() throws Exception {
    var p = copy(directory, "multiplex.ogg");
    try (var f = open(p)) {
      assertTrue(f.isValid());
      assertNotNull(f.tag());
      assertEquals("Paper Lights", f.tag().title());
      assertNotNull(f.audioProperties());
      assertEquals(2, f.audioProperties().channels());
      assertEquals(48000, f.audioProperties().sampleRate());
      f.tag().setTitle("Changed Title");
      f.save();
    }
    try (var f = open(p)) {
      assertTrue(f.isValid());
      assertEquals("Changed Title", f.tag().title());
      f.tag().setTitle("Paper Lights");
      f.save();
    }
    try (var f = open(p)) {
      assertTrue(f.isValid());
      assertEquals("Paper Lights", f.tag().title());
    }
    assertArrayEquals(Files.readAllBytes(fixture("multiplex.ogg")), Files.readAllBytes(p));
  }

  @Test
  void vorbisPageChecksum() throws Exception {
    var p = copy(directory, "empty.ogg");
    try (var f = open(p)) {
      f.tag().setArtist("The Artist");
      f.save();
      f.seek(0x50);
      assertEquals(0x3d3bd92dL, f.readBlock(4).toUnsignedInt(0, true));
    }
    try (var f = open(p)) {
      f.tag().setArtist("The Artist 2");
      f.save();
      f.seek(0x50);
      assertEquals(0xd985291cL, f.readBlock(4).toUnsignedInt(0, true));
    }
  }

  @Test
  void vorbisPageGranulePosition() throws Exception {
    var p = copy(directory, "empty.ogg");
    try (var f = open(p)) {
      f.tag().setComment("A".repeat(70000));
      f.save();
      f.seek(0x3a);
      assertEquals(new ByteVector(new byte[]{'O', 'g', 'g', 'S', 0, 0}), f.readBlock(6));
      assertEquals(-1, f.readBlock(8).toLong());
    }
    try (var f = open(p)) {
      f.tag().setComment("A small comment");
      f.save();
      f.seek(0x3a);
      assertEquals(new ByteVector(new byte[]{'O', 'g', 'g', 'S', 0, 0}), f.readBlock(6));
      assertEquals(0, f.readBlock(8).toLong());
    }
  }

  @Test
  void pageAndHeaderConstructorsBorrowTheOwnerCursor() throws Exception {
    var data = Files.readAllBytes(fixture("empty.ogg"));
    var s = new ByteVectorStream(data);
    try (var f = new VorbisFile(s, ReadOptions.TAGS_ONLY)) {
      f.seek(7);
      var p = new Page(f, 0);
      assertTrue(p.header().isValid());
      assertEquals(0, p.fileOffset());
      assertEquals(1, p.packetCount());
      assertEquals(30, p.packets().getFirst().size());
      assertEquals(58, f.tell());
      assertEquals(58, p.size());
      var h = new PageHeader(f, 0);
      assertTrue(h.isValid());
      assertEquals(28, f.tell());
      assertFalse(new PageHeader(f).isValid());
      assertFalse(new PageHeader((OggFile) null, 0).isValid());
    }
    assertTrue(s.isOpen());
  }
}
