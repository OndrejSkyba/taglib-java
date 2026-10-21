package cz.skyba.taglib.mp4;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.TagLibException;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.FileStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static cz.skyba.taglib.AudioFixtures.fixture;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent pinned TestMP4, CoverArt, Item and Stem assertions on live owners.
 */
class Mp4MutableTest {
  @TempDir
  Path directory;

  static ByteVectorStream stream(String name) throws Exception {
    return new ByteVectorStream(Files.readAllBytes(fixture(name)));
  }

  static List<String> strings(String... v) {
    return List.of(v);
  }

  static ByteVector atom(String name, ByteVector data) {
    return ByteVector.fromUnsignedInt(data.size() + 8L).append(new ByteVector(name)).append(data);
  }

  static ByteVector read(Mp4File f, Atom a) {
    f.seek(a.offset());
    return f.readBlock(a.length());
  }

  static List<Chapter> chapters(String... v) {
    var result = new ArrayList<Chapter>();
    for (int i = 0; i < v.length; i++) result.add(new Chapter(v[i], i * 15000));
    return result;
  }

  static Mp4File reopen(ByteVectorStream s) {
    return new Mp4File(s);
  }

  static void properties(Mp4File f, long millis, int bitrate, int channels, int sampleRate, int bits, Properties.Codec codec, String id) {
    var p = f.detailedProperties();
    assertNotNull(p);
    assertEquals(millis / 1000, p.lengthInSeconds());
    assertEquals(millis, p.lengthInMilliseconds());
    if (bitrate < 0) assertTrue(p.bitrate() > 0);
    else assertEquals(bitrate, p.bitrate());
    assertEquals(channels, p.channels());
    assertEquals(sampleRate, p.sampleRate());
    if (bits >= 0) assertEquals(bits, p.bitsPerSample());
    assertFalse(p.isEncrypted());
    assertEquals(codec, p.codec());
    if (id != null) assertEquals(id, p.codecId());
  }

  @Test
  void missingItemLookupDoesNotChangeSavedAtoms() throws Exception {
    var queried = stream("has-tags.m4a");
    var control = stream("has-tags.m4a");
    try (var file = new Mp4File(queried); var reference = new Mp4File(control)) {
      var properties = file.properties();
      int count = file.tag().itemMap().size();
      var missing = file.tag().item("ABSENT-LOOKUP");
      assertFalse(missing.isValid());
      missing.setAtomDataType(AtomDataType.TypeUTF8);
      assertFalse(file.tag().contains("ABSENT-LOOKUP"));
      assertEquals(count, file.tag().itemMap().size());
      assertEquals(properties, file.properties());
      file.save();
      reference.save();
    }
    assertEquals(control.data(), queried.data());
  }

  @Test
  void chapterTransactionsRejectReadOnlyAndClosedFilesWithoutDiscardingChanges() throws Exception {
    Path path = fixture("no-tags.m4a");
    byte[] original = Files.readAllBytes(path);
    for (var chapters : List.of(new NeroChapterList(), new QtChapterList())) {
      try (var stream = new FileStream(path, true); var file = new Mp4File(stream)) {
        var values = List.of(new Chapter("pending", 0));
        chapters.setChapters(values);
        chapters.setModified(true);
        Runnable write = chapters instanceof NeroChapterList nero
          ? () -> nero.write(file) : () -> ((QtChapterList) chapters).write(file);
        Runnable remove = chapters instanceof NeroChapterList nero
          ? () -> nero.remove(file) : () -> ((QtChapterList) chapters).remove(file);
        assertThrows(TagLibException.class, write::run);
        assertThrows(TagLibException.class, remove::run);
        assertEquals(values, chapters.chapters());
        assertTrue(chapters.isModified());
        assertArrayEquals(original, Files.readAllBytes(path));
        stream.close();
        assertThrows(IllegalStateException.class, write::run);
        assertThrows(IllegalStateException.class, remove::run);
        assertEquals(values, chapters.chapters());
        assertTrue(chapters.isModified());
      }
    }
  }

  @Test
  void testPropertiesAAC() throws Exception {
    properties(new Mp4File(stream("has-tags.m4a")), 3708, 3, 2, 44100, 16, Properties.Codec.AAC, null);
  }

  @Test
  void testPropertiesAACWithoutBitrate() throws Exception {
    var s = stream("has-tags.m4a");
    assertTrue(s.data().size() > 1960);
    assertEquals(new ByteVector("mp4a"), s.data().mid(1890, 4));
    for (int i = 1956; i < 1960; i++) s.data().set(i, (byte) 0);
    properties(new Mp4File(s), 3708, 3, 2, 44100, 16, Properties.Codec.AAC, null);
  }

  @Test
  void testPropertiesALAC() throws Exception {
    properties(new Mp4File(stream("empty_alac.m4a")), 3705, 3, 2, 44100, 16, Properties.Codec.ALAC, null);
  }

  @Test
  void testPropertiesALACWithoutBitrate() throws Exception {
    var s = stream("empty_alac.m4a");
    assertTrue(s.data().size() > 474);
    assertEquals(new ByteVector("alac"), s.data().mid(446, 4));
    for (int i = 470; i < 474; i++) s.data().set(i, (byte) 0);
    properties(new Mp4File(s), 3705, 2, 2, 44100, 16, Properties.Codec.ALAC, null);
  }

  @Test
  void testPropertiesAACWithoutLength() throws Exception {
    var s = stream("no-tags.m4a");
    assertEquals(2898, s.data().size());
    assertEquals(new ByteVector("mdhd"), s.data().mid(1749, 4));
    for (int i = 1769; i < 1773; i++) s.data().set(i, (byte) 0);
    properties(new Mp4File(s), 3707, 3, 2, 44100, 16, Properties.Codec.AAC, null);
  }

  @Test
  void testPropertiesAC3() throws Exception {
    var f = new Mp4File(stream("ac3.m4a"));
    assertEquals(1, f.detailedProperties().lengthInSeconds());
    properties(f, f.detailedProperties().lengthInMilliseconds(), -1, 2, 48000, -1, Properties.Codec.AC3, "ac-3");
  }

  @Test
  void testPropertiesEAC3() throws Exception {
    var f = new Mp4File(stream("eac3.m4a"));
    assertEquals(1, f.detailedProperties().lengthInSeconds());
    properties(f, f.detailedProperties().lengthInMilliseconds(), 128, 2, 48000, -1, Properties.Codec.EAC3, "ec-3");
  }

  @Test
  void testPropertiesFLAC() throws Exception {
    var f = new Mp4File(stream("flac.m4a"));
    assertEquals(1, f.detailedProperties().lengthInSeconds());
    properties(f, f.detailedProperties().lengthInMilliseconds(), -1, 2, 48000, 16, Properties.Codec.FLAC, "fLaC");
  }

  @Test
  void testPropertiesFLACHighResolution() throws Exception {
    var f = new Mp4File(stream("flac96.m4a"));
    assertEquals(1, f.detailedProperties().lengthInSeconds());
    properties(f, f.detailedProperties().lengthInMilliseconds(), -1, 2, 96000, 24, Properties.Codec.FLAC, "fLaC");
  }

  @Test
  void testPropertiesOpus() throws Exception {
    var f = new Mp4File(stream("opus.m4a"));
    assertEquals(1, f.detailedProperties().lengthInSeconds());
    properties(f, f.detailedProperties().lengthInMilliseconds(), -1, 2, 48000, -1, Properties.Codec.Opus, "Opus");
  }

  @Test
  void testPropertiesM4V() throws Exception {
    properties(new Mp4File(stream("blank_video.m4v")), 975, 96, 2, 44100, 16, Properties.Codec.AAC, null);
  }

  @Test
  void testCheckValid() throws Exception {
    assertThrows(TagLibException.class, () -> new Mp4File(stream("empty.aiff")));
  }

  @Test
  void testHasTag() throws Exception {
    assertTrue(new Mp4File(stream("has-tags.m4a")).hasMp4Tag());
    var s = stream("no-tags.m4a");
    var f = new Mp4File(s);
    assertTrue(f.isValid());
    assertFalse(f.hasMp4Tag());
    f.tag().setTitle("TITLE");
    f.save();
    f = reopen(s);
    assertTrue(f.isValid());
    assertTrue(f.hasMp4Tag());
  }

  @Test
  void testIsEmpty() {
    var t = new Tag();
    assertTrue(t.isEmpty());
    t.setArtist("Foo");
    assertFalse(t.isEmpty());
    t = new Tag();
    t.setItem("foo", Item.textValues(strings("bar")));
    assertFalse(t.isEmpty());
  }

  static List<ByteVector> chunkPrefixes(Mp4File f) {
    var a = new Atoms(f).find("moov").findall("stco", true).getFirst();
    f.seek(a.offset() + 12);
    var d = f.readBlock(a.length() - 12);
    var result = new ArrayList<ByteVector>();
    for (int i = 0; i < d.toUnsignedInt(); i++) {
      f.seek(d.toUnsignedInt(4 + i * 4, true));
      result.add(f.readBlock(20));
    }
    return result;
  }

  @Test
  void testUpdateStco() throws Exception {
    var s = stream("no-tags.m4a");
    var f = new Mp4File(s);
    f.tag().setArtist("x".repeat(3000));
    var chunks = chunkPrefixes(f);
    f.save();
    assertEquals(chunks, chunkPrefixes(reopen(s)));
  }

  @Test
  void testFreeForm() throws Exception {
    var s = stream("has-tags.m4a");
    var f = new Mp4File(s);
    assertTrue(f.tag().contains("----:com.apple.iTunes:iTunNORM"));
    f.tag().setItem("----:org.kde.TagLib:Foo", Item.textValues(strings("Bar")));
    f.save();
    f = reopen(s);
    assertTrue(f.tag().contains("----:org.kde.TagLib:Foo"));
    assertEquals("Bar", f.tag().item("----:org.kde.TagLib:Foo").toStringList().getFirst());
    f.save();
  }

  @Test
  void testSaveExisingWhenIlstIsLast() throws Exception {
    var s = stream("ilst-is-last.m4a");
    var f = new Mp4File(s);
    assertEquals(strings("82,164"), f.tag().item("----:com.apple.iTunes:replaygain_track_minmax").toStringList());
    assertEquals("Pearl Jam", f.tag().artist());
    f.tag().setComment("foo");
    f.save();
    f = reopen(s);
    assertEquals(strings("82,164"), f.tag().item("----:com.apple.iTunes:replaygain_track_minmax").toStringList());
    assertEquals("Pearl Jam", f.tag().artist());
    assertEquals("foo", f.tag().comment());
  }

  @Test
  void test64BitAtom() throws Exception {
    var s = stream("64bit.mp4");
    var f = new Mp4File(s);
    assertTrue(f.tag().itemMap().get("cpil").toBoolean());
    assertEquals(77, new Atoms(f).atoms().getFirst().length());
    f.tag().setItem("pgap", new Item(true));
    f.save();
    f = reopen(s);
    assertTrue(f.tag().item("cpil").toBoolean());
    assertTrue(f.tag().item("pgap").toBoolean());
    assertEquals(77 + 25 + 974, new Atoms(f).atoms().getFirst().length());
  }

  @Test
  void testGnre() throws Exception {
    assertEquals("Ska", new Mp4File(stream("gnre.m4a")).tag().genre());
  }

  static void covers(Mp4File f, int count) {
    assertTrue(f.tag().contains("covr"));
    var v = f.tag().item("covr").toCoverArtList();
    assertEquals(count, v.size());
    assertEquals(CoverArt.Format.PNG, v.get(0).format());
    assertEquals(79, v.get(0).data().size());
    assertEquals(CoverArt.Format.JPEG, v.get(1).format());
    assertEquals(287, v.get(1).data().size());
  }

  @Test
  void testCovrRead() throws Exception {
    covers(new Mp4File(stream("has-tags.m4a")), 2);
  }

  @Test
  void testCovrRead2() throws Exception {
    covers(new Mp4File(stream("covr-junk.m4a")), 2);
  }

  @Test
  void testCovrWrite() throws Exception {
    var s = stream("has-tags.m4a");
    var f = new Mp4File(s);
    covers(f, 2);
    var v = f.tag().item("covr").toCoverArtList();
    v.add(new CoverArt(CoverArt.Format.PNG, new ByteVector("foo")));
    f.tag().setItem("covr", Item.coverArt(v));
    f.save();
    f = reopen(s);
    covers(f, 3);
    assertEquals(CoverArt.Format.PNG, f.tag().item("covr").toCoverArtList().get(2).format());
    assertEquals(3, f.tag().item("covr").toCoverArtList().get(2).data().size());
  }

  @Test
  void testProperties() throws Exception {
    var f = new Mp4File(stream("has-tags.m4a"));
    assertEquals(List.of("Test Artist"), f.properties().get("ARTIST"));
    var p = f.properties().toBuilder().put("TRACKNUMBER", "2/4").put("DISCNUMBER", "3/5").put("BPM", "123").put("ARTIST", "Foo Bar").put("COMPILATION", "1").put("REMIXEDBY", "Remixed by").build();
    f.replaceProperties(p);
    assertEquals(new Item.IntPair(2, 4), f.tag().item("trkn").toIntPair());
    assertEquals(new Item.IntPair(3, 5), f.tag().item("disk").toIntPair());
    assertEquals(123, f.tag().item("tmpo").toInt());
    assertTrue(f.tag().item("cpil").toBoolean());
    assertEquals(strings("Foo Bar"), f.tag().item("©ART").toStringList());
    assertEquals(strings("Remixed by"), f.tag().item("----:com.apple.iTunes:REMIXEDBY").toStringList());
    for (String key : List.of("TRACKNUMBER", "DISCNUMBER", "BPM", "ARTIST", "COMPILATION", "REMIXEDBY"))
      assertEquals(p.get(key), f.properties().get(key));
    p = p.toBuilder().put("COMPILATION", "0").build();
    f.replaceProperties(p);
    assertFalse(f.tag().item("cpil").toBoolean());
    assertEquals(List.of("0"), f.properties().get("COMPILATION"));
    f.replaceProperties(p.toBuilder().put("TRACKNUMBER", List.of()).put("DISCNUMBER", List.of()).put("BPM", List.of()).put("COMPILATION", List.of()).build());
  }

  @Test
  void testPropertiesAllSupported() throws Exception {
    var p = allSupported();
    var s = stream("no-tags.m4a");
    var f = new Mp4File(s);
    assertTrue(f.properties().isEmpty());
    assertTrue(f.replaceProperties(p).rejected().isEmpty());
    f.save();
    assertEquals(p, reopen(s).properties());
  }

  @Test
  void testPropertiesMovement() throws Exception {
    var f = new Mp4File(stream("has-tags.m4a"));
    var p = f.properties().toBuilder().put("WORK", "Foo").put("MOVEMENTNAME", "Bar").put("MOVEMENTNUMBER", "2").put("MOVEMENTCOUNT", "3").put("SHOWWORKMOVEMENT", "1").build();
    f.replaceProperties(p);
    assertEquals(strings("Foo"), f.tag().item("©wrk").toStringList());
    assertEquals(strings("Bar"), f.tag().item("©mvn").toStringList());
    assertEquals(2, f.tag().item("©mvi").toInt());
    assertEquals(3, f.tag().item("©mvc").toInt());
    assertTrue(f.tag().item("shwm").toBoolean());
    for (String key : List.of("WORK", "MOVEMENTNAME", "MOVEMENTNUMBER", "MOVEMENTCOUNT", "SHOWWORKMOVEMENT"))
      assertEquals(p.get(key), f.properties().get(key));
    f.replaceProperties(p.toBuilder().put("SHOWWORKMOVEMENT", "0").build());
    assertFalse(f.tag().item("shwm").toBoolean());
    assertEquals(List.of("0"), f.properties().get("SHOWWORKMOVEMENT"));
    f.replaceProperties(p.toBuilder().put("WORK", List.of()).put("MOVEMENTNAME", List.of()).put("MOVEMENTNUMBER", List.of()).put("MOVEMENTCOUNT", List.of()).put("SHOWWORKMOVEMENT", List.of()).build());
  }

  @Test
  void testFuzzedFile() throws Exception {
    assertTrue(new Mp4File(stream("infloop.m4a")).isValid());
  }

  @Test
  void testRepeatedSave() throws Exception {
    var s = stream("no-tags.m4a");
    var f = new Mp4File(s);
    f.tag().setTitle("0123456789");
    f.save();
    f.save();
    assertEquals(2862, f.find(new ByteVector("0123456789")));
    assertEquals(-1, f.find(new ByteVector("0123456789"), 2863));
  }

  @Test
  void testWithZeroLengthAtom() throws Exception {
    var f = new Mp4File(stream("zero-length-mdat.m4a"));
    assertTrue(f.isValid());
    assertEquals(1115, f.detailedProperties().lengthInMilliseconds());
    assertEquals(22050, f.detailedProperties().sampleRate());
  }

  @Test
  void testEmptyValuesRemoveItems() throws Exception {
    var t = new Mp4File(stream("has-tags.m4a")).tag();
    t.setTitle("Title");
    t.setArtist("Artist");
    t.setAlbum("Album");
    t.setComment("Comment");
    t.setGenre("Genre");
    t.setYear(2020);
    t.setTrack(1);
    assertEquals("Title", t.title());
    assertEquals("Artist", t.artist());
    assertEquals("Album", t.album());
    assertEquals("Comment", t.comment());
    assertEquals("Genre", t.genre());
    assertEquals(2020, t.year());
    assertEquals(1, t.track());
    for (String k : List.of("©nam", "©ART", "©alb", "©cmt", "©gen", "©day", "trkn")) assertTrue(t.contains(k));
    t.setTitle("");
    t.setArtist("");
    t.setAlbum("");
    t.setComment("");
    t.setGenre("");
    t.setYear(0);
    t.setTrack(0);
    assertEquals("", t.title());
    assertEquals("", t.artist());
    assertEquals("", t.album());
    assertEquals("", t.comment());
    assertEquals("", t.genre());
    assertEquals(0, t.year());
    assertEquals(0, t.track());
    for (String k : List.of("©nam", "©ART", "©alb", "©cmt", "©gen", "©day", "trkn")) assertFalse(t.contains(k));
  }

  @Test
  void testRemoveMetadata() throws Exception {
    var s = stream("no-tags.m4a");
    byte[] initial = s.data().toByteArray();
    var f = new Mp4File(s);
    assertTrue(f.isValid());
    assertFalse(f.hasMp4Tag());
    assertTrue(f.tag().isEmpty());
    f.tag().setTitle("TITLE");
    f.save();
    f = reopen(s);
    assertTrue(f.isValid());
    assertTrue(f.hasMp4Tag());
    assertFalse(f.tag().isEmpty());
    f.strip();
    f = reopen(s);
    assertTrue(f.isValid());
    assertFalse(f.hasMp4Tag());
    assertTrue(f.tag().isEmpty());
    assertArrayEquals(initial, s.data().toByteArray());
  }

  @Test
  void testNonFullMetaAtom() throws Exception {
    var f = new Mp4File(stream("non-full-meta.m4a"));
    assertTrue(f.isValid());
    assertTrue(f.hasMp4Tag());
    covers(f, 2);
    assertEquals(List.of("Test Artist!!!!"), f.properties().get("ARTIST"));
    assertEquals(List.of("FAAC 1.24"), f.properties().get("ENCODING"));
  }

  static class Custom extends ItemFactory {
    @Override
    protected Map<ByteVector, ItemHandlerType> nameHandlerMap() {
      var m = super.nameHandlerMap();
      m.put(new ByteVector("tsti"), ItemHandlerType.Int);
      m.put(new ByteVector("tstt"), ItemHandlerType.Text);
      return m;
    }

    @Override
    protected Map<ByteVector, String> namePropertyMap() {
      var m = super.namePropertyMap();
      m.put(new ByteVector("tsti"), "TESTINTEGER");
      return m;
    }
  }

  @Test
  void testItemFactory() throws Exception {
    var s = stream("no-tags.m4a");
    var f = new Mp4File(s);
    f.tag().setItem("tsti", new Item(123));
    f.tag().setItem("tstt", Item.textValues(strings("Test text")));
    f.save();
    f = reopen(s);
    assertFalse(f.tag().item("tsti").isValid());
    assertThrows(IllegalStateException.class, () -> new Item().toInt());
    assertEquals(strings("Test text"), f.tag().item("tstt").toStringList());
    f.strip();
    var factory = new Custom();
    f = new Mp4File(s, ReadOptions.DEFAULT, factory);
    assertFalse(f.hasMp4Tag());
    f.tag().setItem("tsti", new Item(123));
    f.tag().setItem("tstt", Item.textValues(strings("Test text")));
    f.tag().setItem("trkn", new Item(2, 10));
    f.tag().setItem("rate", new Item(80));
    f.tag().setItem("plID", new Item(1540934238L));
    f.tag().setItem("cnID", new Item(9876543210L));
    f.tag().setItem("rtng", Item.fromByte(2));
    f.save();
    f = new Mp4File(s, ReadOptions.DEFAULT, factory);
    assertTrue(f.hasMp4Tag());
    assertEquals(123, f.tag().item("tsti").toInt());
    assertEquals(strings("Test text"), f.tag().item("tstt").toStringList());
    assertEquals(new Item.IntPair(2, 10), f.tag().item("trkn").toIntPair());
    assertEquals(80, f.tag().item("rate").toInt());
    assertEquals(1540934238L, f.tag().item("plID").toLong());
    assertEquals(9876543210L, f.tag().item("cnID").toLong());
    assertEquals(2, f.tag().item("rtng").toByte());
    assertEquals(List.of("123"), f.properties().get("TESTINTEGER"));
    assertEquals(List.of("2/10"), f.properties().get("TRACKNUMBER"));
    f.replaceProperties(f.properties().toBuilder().put("TESTINTEGER", "456").build());
    f.save();
    f = new Mp4File(s, ReadOptions.DEFAULT, factory);
    assertEquals(456, f.tag().item("tsti").toInt());
    assertEquals(List.of("456"), f.properties().get("TESTINTEGER"));
  }

  @Test
  void testNonPrintableAtom() throws Exception {
    var s = stream("nonprintable-atom-type.m4a");
    var f = new Mp4File(s);
    assertTrue(f.isValid());
    assertEquals(1, f.audioProperties().channels());
    assertEquals(32000, f.audioProperties().sampleRate());
    f.tag().setTitle("TITLE");
    f.save();
    f = reopen(s);
    assertTrue(f.isValid());
    assertTrue(f.hasMp4Tag());
    assertEquals("TITLE", f.tag().title());
  }

  static void set(Mp4File f, boolean qt, List<Chapter> c) {
    if (qt) f.setQtChapters(c);
    else f.setNeroChapters(c);
  }

  static List<Chapter> get(Mp4File f, boolean qt) {
    return qt ? f.qtChapters() : f.neroChapters();
  }

  static void roundTrip(String fixture, boolean qt, List<Chapter> c) throws Exception {
    var s = stream(fixture);
    var f = new Mp4File(s);
    set(f, qt, c);
    f.save();
    assertEquals(c, get(reopen(s), qt));
  }

  @Test
  void testChapterListWrite() throws Exception {
    var s = stream("no-tags.m4a");
    var f = new Mp4File(s);
    assertTrue(f.neroChapters().isEmpty());
    var c = List.of(new Chapter("Introduction", 0), new Chapter("Main Content", 30000), new Chapter("Conclusion", 60000));
    f.setNeroChapters(c);
    f.save();
    f = reopen(s);
    assertEquals(c, f.neroChapters());
    f.setNeroChapters(List.of(new Chapter("Part One", 0)));
    f.save();
    assertEquals(List.of(new Chapter("Part One", 0)), reopen(s).neroChapters());
  }

  static void remove(boolean qt) throws Exception {
    var s = stream("no-tags.m4a");
    var f = new Mp4File(s);
    var c = qt ? List.of(new Chapter("Chapter 1", 0), new Chapter("Chapter 2", 10000)) : List.of(new Chapter("Chapter 1", 0));
    set(f, qt, c);
    f.save();
    f = reopen(s);
    assertEquals(c, get(f, qt));
    set(f, qt, List.of());
    f.save();
    f = reopen(s);
    assertTrue(get(f, qt).isEmpty());
    set(f, qt, List.of());
    f.save();
  }

  @Test
  void testChapterListRemove() throws Exception {
    remove(false);
  }

  static void existingTags(boolean qt) throws Exception {
    var s = stream("has-tags.m4a");
    var f = new Mp4File(s);
    String artist = f.tag().artist();
    assertTrue(f.isValid());
    assertFalse(artist.isEmpty());
    var c = List.of(new Chapter("Intro", 0), new Chapter("Verse", 10000));
    set(f, qt, c);
    f.save();
    f = reopen(s);
    assertTrue(f.isValid());
    assertEquals(c, get(f, qt));
    assertEquals(artist, f.tag().artist());
    set(f, qt, List.of());
    f.save();
    f = reopen(s);
    assertTrue(f.isValid());
    assertEquals(artist, f.tag().artist());
    assertTrue(get(f, qt).isEmpty());
  }

  @Test
  void testChapterListWithExistingTags() throws Exception {
    existingTags(false);
  }

  @Test
  void testChapterListReadEmpty() throws Exception {
    assertTrue(new Mp4File(stream("no-tags.m4a")).neroChapters().isEmpty());
  }

  @Test
  void testQTChapterListWrite() throws Exception {
    assertTrue(new Mp4File(stream("no-tags.m4a")).qtChapters().isEmpty());
    roundTrip("no-tags.m4a", true, chapters("Intro", "Verse", "Outro"));
  }

  @Test
  void testQTChapterListRemove() throws Exception {
    remove(true);
  }

  @Test
  void testQTChapterListWithExistingTags() throws Exception {
    existingTags(true);
  }

  @Test
  void testQTChapterListReadEmpty() throws Exception {
    assertTrue(new Mp4File(stream("no-tags.m4a")).qtChapters().isEmpty());
  }

  @Test
  void testQTChapterListOverwrite() throws Exception {
    var s = stream("no-tags.m4a");
    var f = new Mp4File(s);
    f.setQtChapters(List.of(new Chapter("Old1", 0), new Chapter("Old2", 5000)));
    f.save();
    assertEquals(2, reopen(s).qtChapters().size());
    f = reopen(s);
    var c = List.of(new Chapter("New1", 0), new Chapter("New2", 10000), new Chapter("New3", 20000));
    f.setQtChapters(c);
    f.save();
    assertEquals(c, reopen(s).qtChapters());
  }

  @Test
  void testQTChapterListTimestampPrecision() throws Exception {
    roundTrip("no-tags.m4a", true, List.of(new Chapter("Start", 0), new Chapter("Precise", 1500)));
  }

  @Test
  void testQTChapterListNonZeroFirstChapter() throws Exception {
    roundTrip("no-tags.m4a", true, List.of(new Chapter("One", 10000), new Chapter("Two", 20000), new Chapter("Three", 30000)));
  }

  static long mdats(Mp4File f) {
    return new Atoms(f).atoms().stream().filter(a -> a.name().equals(new ByteVector("mdat"))).count();
  }

  @Test
  void testQTChapterListNoOrphanedMdat() throws Exception {
    var s = stream("no-tags.m4a");
    long initial = mdats(reopen(s));
    for (int i = 0; i < 3; i++) {
      var f = reopen(s);
      f.setQtChapters(List.of(new Chapter("Ch1", 0), new Chapter("Ch2", 10000)));
      f.save();
      assertEquals(initial + 1, mdats(reopen(s)));
      f = reopen(s);
      f.setQtChapters(List.of());
      f.save();
      assertEquals(initial, mdats(reopen(s)));
    }
  }

  static List<ByteVector> refs(Mp4File f) {
    var audio = new Atoms(f).find("moov").findall("trak").getFirst();
    return audio.children().stream().filter(a -> a.name().equals(new ByteVector("tref"))).map(a -> read(f, a).mid(8)).toList();
  }

  @Test
  void testQTChapterListSharedTref() throws Exception {
    var s = stream("no-tags.m4a");
    var f = new Mp4File(s);
    var a = new Atoms(f);
    var moov = a.find("moov");
    var track = moov.findall("trak").getFirst();
    var tmcd = atom("tmcd", ByteVector.fromUnsignedInt(3));
    var tref = atom("tref", tmcd);
    f.insert(tref, track.offset() + track.length(), 0);
    f.seek(track.offset());
    s.writeBlock(ByteVector.fromUnsignedInt(track.length() + tref.size()));
    f.seek(moov.offset());
    s.writeBlock(ByteVector.fromUnsignedInt(moov.length() + tref.size()));
    assertEquals(List.of(tmcd), refs(reopen(s)));
    f = reopen(s);
    f.setQtChapters(List.of(new Chapter("Chapter 1", 0), new Chapter("Chapter 2", 10000)));
    f.save();
    var r = refs(reopen(s));
    assertEquals(1, r.size());
    assertEquals(tmcd, r.getFirst().mid(0, 12));
    assertEquals(new ByteVector("chap"), r.getFirst().mid(16, 4));
    assertEquals(2, reopen(s).qtChapters().size());
    f = reopen(s);
    f.setQtChapters(List.of());
    f.save();
    assertEquals(List.of(tmcd), refs(reopen(s)));
  }

  @Test
  void testQTChapterListSharedMdatPreservesAudio() throws Exception {
    var s = stream("no-tags.m4a");
    var f = new Mp4File(s);
    var first = new Atoms(f).atoms().stream().filter(a -> a.name().equals(new ByteVector("mdat"))).findFirst().orElseThrow();
    assertTrue(first.offset() >= 0);
    assertTrue(first.length() > 16);
    var bytes = read(f, first);
    f.setQtChapters(List.of(new Chapter("Chapter 1", 0), new Chapter("Chapter 2", 1000)));
    f.save();
    f = reopen(s);
    var a = new Atoms(f);
    var tracks = a.find("moov").findall("trak");
    assertTrue(tracks.size() >= 2);
    var chapterFile = new Mp4File(s, ReadOptions.TAGS_ONLY);
    var chapter = tracks.stream().filter(t -> read(chapterFile, t.find("mdia", "hdlr")).containsAt(new ByteVector("text"), 16)).findFirst().orElseThrow();
    var stco = chapter.find("mdia", "minf", "stbl", "stco");
    f.seek(stco.offset() + 16);
    s.writeBlock(ByteVector.fromUnsignedInt(first.offset() + 8));
    f = reopen(s);
    f.setQtChapters(List.of());
    f.save();
    f = reopen(s);
    var after = new Atoms(f).atoms().stream().filter(t -> t.name().equals(new ByteVector("mdat"))).findFirst().orElseThrow();
    assertEquals(first.length(), after.length());
    assertEquals(bytes, read(f, after));
  }

  @Test
  void testQTChapterListUnicodeTitles() throws Exception {
    roundTrip("no-tags.m4a", true, chapters("日本語", "Über", "Привет"));
  }

  @Test
  void testChapterListUnicodeTitles() throws Exception {
    roundTrip("no-tags.m4a", false, chapters("日本語", "Über", "Привет"));
  }

  @Test
  void testQTChapterListEmptyTitleStripped() throws Exception {
    var s = stream("no-tags.m4a");
    var f = new Mp4File(s);
    f.setQtChapters(List.of(new Chapter("", 0), new Chapter("Chapter 1", 5000), new Chapter("Chapter 2", 10000)));
    f.save();
    assertEquals(List.of(new Chapter("Chapter 1", 5000), new Chapter("Chapter 2", 10000)), reopen(s).qtChapters());
  }

  @Test
  void testQTChapterListSingleEmptyTitleNotStripped() throws Exception {
    roundTrip("no-tags.m4a", true, List.of(new Chapter("", 0)));
  }

  @Test
  void testNeroAndQTChaptersAreIndependent() throws Exception {
    var s = stream("no-tags.m4a");
    var f = new Mp4File(s);
    var nero = List.of(new Chapter("Nero 1", 0), new Chapter("Nero 2", 10000));
    var qt = List.of(new Chapter("QT 1", 0), new Chapter("QT 2", 20000));
    f.setNeroChapters(nero);
    f.setQtChapters(qt);
    f.save();
    f = reopen(s);
    assertEquals(nero, f.neroChapters());
    assertEquals(qt, f.qtChapters());
    f.setQtChapters(List.of());
    f.save();
    f = reopen(s);
    assertTrue(f.qtChapters().isEmpty());
    assertEquals(nero, f.neroChapters());
  }

  @Test
  void testNeroChaptersAloneWhenNoQT() throws Exception {
    for (boolean qt : List.of(false, true)) {
      var s = stream("no-tags.m4a");
      var f = new Mp4File(s);
      set(f, qt, List.of(new Chapter(qt ? "QT Only" : "Nero Only", 0)));
      f.save();
      f = reopen(s);
      assertEquals(1, get(f, qt).size());
      assertTrue(get(f, !qt).isEmpty());
    }
  }

  @Test
  void chapterListsLoadLazilyAndKeepStagedValues() throws Exception {
    class CountingStream extends ByteVectorStream {
      int reads;

      CountingStream(byte[] bytes) {
        super(bytes);
      }

      @Override
      public ByteVector readBlock(long length) {
        reads++;
        return super.readBlock(length);
      }
    }
    var pending = List.of(new Chapter("Pending", 0), new Chapter("Second", 1500));
    for (boolean qt : new boolean[]{false, true}) {
      for (boolean readFirst : new boolean[]{false, true}) {
        var stream = new CountingStream(Files.readAllBytes(fixture("no-tags.m4a")));
        try (var file = new Mp4File(stream, ReadOptions.TAGS_ONLY)) {
          stream.reads = 0;
          if (readFirst) {
            assertTrue(get(file, qt).isEmpty());
            assertTrue(stream.reads > 0);
            int reads = stream.reads;
            assertTrue(get(file, qt).isEmpty());
            assertEquals(reads, stream.reads);
          }
          int reads = stream.reads;
          byte[] before = stream.data().toByteArray();
          set(file, qt, pending);
          assertEquals(pending, get(file, qt));
          assertEquals(reads, stream.reads);
          assertArrayEquals(before, stream.data().toByteArray());
          file.save();
        }
        try (var reopened = new Mp4File(stream, ReadOptions.TAGS_ONLY)) {
          assertEquals(pending, get(reopened, qt));
        }
      }
    }
  }

  static PropertyMap allSupported() {
    return PropertyMap.builder()
      .put("ALBUM", "Album")
      .put("ALBUMARTIST", "Album Artist")
      .put("ALBUMARTISTSORT", "Album Artist Sort")
      .put("ALBUMSORT", "Album Sort")
      .put("ARTIST", "Artist")
      .put("ARTISTS", "Artists")
      .put("ARTISTSORT", "Artist Sort")
      .put("ASIN", "ASIN")
      .put("BARCODE", "Barcode")
      .put("BPM", "123")
      .put("CATALOGNUMBER", "Catalog Number")
      .put("COMMENT", "Comment")
      .put("COMPILATION", "1")
      .put("COMPOSER", "Composer")
      .put("COMPOSERSORT", "Composer Sort")
      .put("CONDUCTOR", "Conductor")
      .put("COPYRIGHT", "2021 Copyright")
      .put("DATE", "2021-01-03 12:29:23")
      .put("DISCNUMBER", "3/5")
      .put("DISCSUBTITLE", "Disc Subtitle")
      .put("DJMIXER", "DJ Mixer")
      .put("ENCODEDBY", "Encoded by")
      .put("ENCODING", "Encoding")
      .put("ENGINEER", "Engineer")
      .put("GAPLESSPLAYBACK", "1")
      .put("GENRE", "Genre")
      .put("GROUPING", "Grouping")
      .put("ISRC", "UKAAA0500001")
      .put("LABEL", "Label")
      .put("LANGUAGE", "eng")
      .put("LICENSE", "License")
      .put("LYRICIST", "Lyricist")
      .put("LYRICS", "Lyrics")
      .put("MEDIA", "Media")
      .put("MIXER", "Mixer")
      .put("MOOD", "Mood")
      .put("MOVEMENTCOUNT", "3")
      .put("MOVEMENTNAME", "Movement Name")
      .put("MOVEMENTNUMBER", "2")
      .put("MUSICBRAINZ_ALBUMARTISTID", "MusicBrainz_AlbumartistID")
      .put("MUSICBRAINZ_ALBUMID", "MusicBrainz_AlbumID")
      .put("MUSICBRAINZ_ARTISTID", "MusicBrainz_ArtistID")
      .put("MUSICBRAINZ_RELEASEGROUPID", "MusicBrainz_ReleasegroupID")
      .put("MUSICBRAINZ_RELEASETRACKID", "MusicBrainz_ReleasetrackID")
      .put("MUSICBRAINZ_TRACKID", "MusicBrainz_TrackID")
      .put("MUSICBRAINZ_WORKID", "MusicBrainz_WorkID")
      .put("ORIGINALDATE", "2021-01-03 13:52:19")
      .put("OWNER", "Owner")
      .put("PODCAST", "1")
      .put("PODCASTCATEGORY", "Podcast Category")
      .put("PODCASTDESC", "Podcast Description")
      .put("PODCASTID", "Podcast ID")
      .put("PODCASTURL", "Podcast URL")
      .put("PRODUCER", "Producer")
      .put("RELEASECOUNTRY", "Release Country")
      .put("RELEASESTATUS", "Release Status")
      .put("RELEASETYPE", "Release Type")
      .put("REMIXER", "Remixer")
      .put("SCRIPT", "Script")
      .put("SHOWSORT", "Show Sort")
      .put("SHOWWORKMOVEMENT", "1")
      .put("SUBTITLE", "Subtitle")
      .put("TITLE", "Title")
      .put("TITLESORT", "Title Sort")
      .put("TRACKNUMBER", "2/4")
      .put("TVEPISODE", "3")
      .put("TVEPISODEID", "TV Episode ID")
      .put("TVNETWORK", "TV Network")
      .put("TVSEASON", "2")
      .put("TVSHOW", "TV Show")
      .put("WORK", "Work")
      .build();
  }
}
