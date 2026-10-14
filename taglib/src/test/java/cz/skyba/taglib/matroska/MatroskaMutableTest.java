package cz.skyba.taglib.matroska;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.internal.MatroskaWire;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.Variant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static cz.skyba.taglib.UpstreamAudioFixtures.fixture;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent full assertions for the eighteen mutable/read-owner TestMatroska registrations.
 */
class MatroskaMutableTest {
  @TempDir
  Path directory;

  Path copy(String name) throws Exception {
    return Files.copy(fixture(name), directory.resolve(name));
  }

  static MatroskaFile accurate(Path p) throws Exception {
    var f = new MatroskaFile(p, new ReadOptions(true, ReadStyle.ACCURATE, ReadOptions.DEFAULT.maxMetadataBytes()));
    assertTrue(f.isValid());
    return f;
  }

  static MatroskaFile accurate(ByteVectorStream s) throws Exception {
    s.seek(0);
    var f = new MatroskaFile(s, new ReadOptions(true, ReadStyle.ACCURATE, ReadOptions.DEFAULT.maxMetadataBytes()));
    assertTrue(f.isValid());
    return f;
  }

  static ByteVector bytes(String s) {
    return new ByteVector(s);
  }

  static SimpleTag text(String n, String v, int target, String language, boolean def, long track, long edition, long chapter, long attachment) {
    return new SimpleTag(n, new SimpleTag.Text(v), target, language, def, track, edition, chapter, attachment);
  }

  static Map<String, Variant> map(Object... pairs) {
    var m = new TreeMap<String, Variant>();
    for (int i = 0; i < pairs.length; i += 2) {
      Object v = pairs[i + 1];
      m.put((String) pairs[i], v instanceof Variant x ? x : v instanceof String x ? new Variant(x) : v instanceof Boolean x ? new Variant(x) : v instanceof Integer x ? new Variant(x) : v instanceof ByteVector x ? new Variant(x) : Variant.fromUnsignedLong((Long) v));
    }
    return Collections.unmodifiableMap(m);
  }

  static Variant ul(String s) {
    return Variant.fromUnsignedLong(Long.parseUnsignedLong(s));
  }

  static Map<String, Variant> duration() {
    return map("defaultLanguage", true, "language", "und", "name", "DURATION", "value", "00:00:00.120000000", "trackUid", ul("9584013959154292683"));
  }

  static Map<String, Variant> attachment(String data, String name, String mime, String description, long uid) {
    return map("data", bytes(data), "fileName", name, "mimeType", mime, "description", description, "uid", uid);
  }

  static void sameOriginal(Path p, String name) throws Exception {
    assertArrayEquals(Files.readAllBytes(fixture(name)), Files.readAllBytes(p));
  }

  static void assertSimple(SimpleTag expected, SimpleTag actual) {
    assertEquals(expected.name(), actual.name());
    assertEquals(expected.type(), actual.type());
    assertEquals(expected.toString(), actual.toString());
    assertEquals(expected.toByteVector(), actual.toByteVector());
    assertEquals(expected.targetTypeValue(), actual.targetTypeValue());
    assertEquals(expected.language(), actual.language());
    assertEquals(expected.defaultLanguageFlag(), actual.defaultLanguageFlag());
    assertEquals(expected.trackUid(), actual.trackUid());
    assertEquals(expected.editionUid(), actual.editionUid());
    assertEquals(expected.chapterUid(), actual.chapterUid());
    assertEquals(expected.attachmentUid(), actual.attachmentUid());
  }

  static void assertTags(List<SimpleTag> expected, List<SimpleTag> actual) {
    assertEquals(expected.size(), actual.size());
    for (int i = 0; i < expected.size(); i++) assertSimple(expected.get(i), actual.get(i));
  }

  @Test
  void testSimpleTagsAndAttachments() throws Exception {
    Path p = copy("no-tags.mka");
    try (var f = new MatroskaFile(p)) {
      assertTrue(f.isValid());
      assertNull(f.existingTag());
      assertNull(f.attachments());
      var t = f.tag();
      assertTrue(t.isEmpty());
      t.addSimpleTag(new SimpleTag("Test Name 2", "Test Value 2", SimpleTag.TargetTypeValue.Album, "", true, 0x72ac, 0xed17, 0xca97, 0xa7ac));
      t.insertSimpleTag(0, new SimpleTag("Test Name 1", "Test Value 1", SimpleTag.TargetTypeValue.Track, "en"));
      t.insertSimpleTag(1, new SimpleTag("Test Name 3", "Test Value 3"));
      t.removeSimpleTag(1);
      t.setTitle("Test title");
      t.setArtist("Test artist");
      t.setYear(1969);
      f.getOrCreateAttachments().addAttachedFile(new AttachedFile(bytes("JPEG data"), "cover.jpg", "image/jpeg", 5081000385627515072L, "Cover"));
      f.save();
    }
    try (var f = accurate(p)) {
      var t = f.existingTag();
      assertNotNull(t);
      assertFalse(t.isEmpty());
      assertEquals("Test title", t.title());
      assertEquals("Test artist", t.artist());
      assertEquals(1969, t.year());
      assertEquals("", t.album());
      assertEquals("", t.comment());
      assertEquals("", t.genre());
      assertEquals(0, t.track());
      assertTags(simpleExpected(), t.simpleTagsList());
      var a = f.attachments().attachedFileList();
      assertEquals(List.of(new AttachedFile(bytes("JPEG data"), "cover.jpg", "image/jpeg", 5081000385627515072L, "Cover")), a);
      var expected = PropertyMap.builder().put("ARTIST", "Test artist").put("DATE", "1969").put("TEST NAME 1", "Test Value 1").put("TITLE", "Test title").build();
      assertEquals(expected, f.properties());
      assertEquals(expected, t.properties());
      assertEquals(List.of("Test Name 2", "PICTURE"), f.complexPropertyKeys());
      assertEquals(List.of(attachment("JPEG data", "cover.jpg", "image/jpeg", "Cover", 5081000385627515072L)), f.complexProperties("PICTURE"));
      assertEquals(List.of(map("defaultLanguage", true, "language", "und", "name", "Test Name 2", "value", "Test Value 2", "targetTypeValue", 50, "trackUid", 0x72acL, "editionUid", 0xed17L, "chapterUid", 0xca97L, "attachmentUid", 0xa7acL)), f.complexProperties("Test Name 2"));
      t.clearSimpleTags();
      f.attachments().clear();
      f.save();
    }
    try (var f = accurate(p)) {
      assertNull(f.existingTag());
      assertNull(f.attachments());
    }
    sameOriginal(p, "no-tags.mka");
  }

  @Test
  void testAddRemoveTagsAttachments() throws Exception {
    Path p = copy("no-tags.mka");
    try (var f = new MatroskaFile(p)) {
      f.tag().setComment("C");
      f.save();
    }
    try (var f = accurate(p)) {
      assertEquals("C", f.tag().comment());
      f.tag().setComment("");
      f.save();
    }
    try (var f = accurate(p)) {
      assertNull(f.existingTag());
      f.getOrCreateAttachments().addAttachedFile(new AttachedFile(new ByteVector(), "", ""));
      f.save();
    }
    try (var f = accurate(p)) {
      var a = f.attachments().attachedFileList();
      assertEquals(1, a.size());
      assertNotEquals(0, a.getFirst().uid());
      f.attachments().removeAttachedFile(a.getFirst().uid());
      f.save();
    }
    try (var f = accurate(p)) {
      assertNull(f.existingTag());
      assertNull(f.attachments());
    }
  }

  @Test
  void testTagsWebm() throws Exception {
    Path p = copy("no-tags.webm");
    try (var f = new MatroskaFile(p)) {
      assertTrue(f.isValid());
      assertNull(f.existingTag());
      assertNull(f.attachments());
      f.replaceProperties(PropertyMap.builder().put("ARTIST", List.of("First artist", "second artist")).put("", List.of("Invalid", "Ignored")).build());
      f.existingTag().addSimpleTag(new SimpleTag("", bytes("Not valid")));
      f.save();
    }
    try (var f = accurate(p)) {
      assertNull(f.attachments());
      assertEquals("First artist", f.tag().artist());
      assertEquals(List.of("First artist", "second artist"), f.properties().get("ARTIST"));
      f.tag().setAlbum("Album");
      f.tag().setTrack(5);
      f.save();
    }
    try (var f = accurate(p)) {
      assertEquals("First artist", f.tag().artist());
      assertEquals("Album", f.tag().album());
      assertEquals(5, f.tag().track());
      f.tag().setArtist("");
      f.tag().removeSimpleTag("TITLE", SimpleTag.TargetTypeValue.Album);
      f.tag().setTrack(0);
      f.save();
    }
    try (var f = new MatroskaFile(p, new ReadOptions(false, ReadStyle.ACCURATE, ReadOptions.DEFAULT.maxMetadataBytes()))) {
      assertNull(f.existingTag());
      assertNull(f.attachments());
    }
    sameOriginal(p, "no-tags.webm");
  }

  @Test
  void testRepeatedSave() throws Exception {
    Path p = copy("no-tags.mka");
    String text = "01234 56789 ABCDE FGHIJ 01234 56789 ABCDE FGHIJ 01234 56789";
    try (var f = new MatroskaFile(p)) {
      f.save();
      for (var v : List.of(text.substring(0, 23), text.substring(0, 5), text)) {
        f.tag().setTitle(v);
        f.save();
      }
    }
    try (var f = accurate(p)) {
      assertEquals(text, f.tag().title());
    }
  }

  @Test
  void testPropertyInterface() throws Exception {
    Path p = copy("tags-before-cues.mkv");
    var initial = initialProps();
    var next = newProps();
    var binary = map("defaultLanguage", false, "language", "en", "name", "BINARY", "data", new ByteVector(new byte[]{1, 2, 3, 4, 5, 6}), "targetTypeValue", 70);
    try (var f = new MatroskaFile(p)) {
      assertTrue(f.isValid());
      assertNull(f.attachments());
      var t = f.existingTag();
      assertNotNull(t);
      assertEquals("handbrake", t.title());
      assertEquals("Actors", t.artist());
      assertEquals("", t.album());
      assertEquals("", t.comment());
      assertEquals("Genre", t.genre());
      assertEquals(0, t.track());
      assertTags(initialExpected(), t.simpleTagsList());
      assertEquals(initial, f.properties());
      assertEquals(initial, t.properties());
      assertEquals(List.of("DURATION"), f.complexPropertyKeys());
      assertEquals(List.of(duration()), f.complexProperties("DURATION"));
      assertTrue(f.setComplexProperties("DURATION", List.of()));
      assertTrue(f.setComplexProperties("BINARY", List.of(binary)));
      assertTrue(f.replaceProperties(next).rejected().isEmpty());
      f.save();
    }
    try (var f = accurate(p)) {
      assertNotNull(f.existingTag());
      assertNull(f.attachments());
      assertEquals(next, f.properties());
      assertEquals(next, f.tag().properties());
      assertEquals(List.of("BINARY"), f.complexPropertyKeys());
      assertEquals(List.of(binary), f.complexProperties("BINARY"));
      assertEquals(expectedNames(), f.tag().simpleTagsList().stream().map(SimpleTag::name).toList());
      assertTrue(f.setComplexProperties("DURATION", List.of(duration())));
      assertTrue(f.setComplexProperties("BINARY", List.of()));
      assertTrue(f.replaceProperties(initial).rejected().isEmpty());
      f.save();
    }
    try (var f = accurate(p)) {
      assertNotNull(f.existingTag());
      assertNull(f.attachments());
    }
    sameOriginal(p, "tags-before-cues.mkv");
  }

  @Test
  void testComplexProperties() throws Exception {
    Path p = copy("no-tags.mka");
    var picture = attachment("JPEG data", "folder.jpg", "image/jpeg", "Cover", 123);
    var font = attachment("TTF data", "file.ttf", "font/ttf", "Subtitle font", 456);
    var scoped = map("defaultLanguage", true, "language", "und", "name", "DURATION", "trackUid", 8315232342706310039L, "value", "00:00:00.120000000");
    var target = map("defaultLanguage", false, "language", "en", "name", "PART_NUMBER", "targetTypeValue", 20, "value", "2");
    var binary = map("defaultLanguage", false, "language", "und", "name", "THUMBNAIL", "targetTypeValue", 30, "data", bytes("JPEG data"));
    try (var f = new MatroskaFile(p)) {
      assertTrue(f.isValid());
      assertNull(f.existingTag());
      assertNull(f.attachments());
      assertTrue(f.complexPropertyKeys().isEmpty());
      assertTrue(f.complexProperties("PICTURE").isEmpty());
      assertTrue(f.setComplexProperties("PICTURE", List.of(picture)));
      assertTrue(f.setComplexProperties("file.ttf", List.of(font)));
      f.save();
    }
    try (var f = accurate(p)) {
      assertNull(f.existingTag());
      assertEquals(List.of(new AttachedFile(bytes("JPEG data"), "folder.jpg", "image/jpeg", 123, "Cover"), new AttachedFile(bytes("TTF data"), "file.ttf", "font/ttf", 456, "Subtitle font")), f.attachments().attachedFileList());
      assertEquals(List.of("PICTURE", "file.ttf"), f.complexPropertyKeys());
      assertEquals(List.of(picture), f.complexProperties("PICTURE"));
      assertEquals(List.of(font), f.complexProperties("file.ttf"));
      assertTrue(f.setComplexProperties("DURATION", List.of(scoped)));
      f.save();
    }
    try (var f = accurate(p)) {
      assertNotNull(f.attachments());
      assertTags(List.of(text("DURATION", "00:00:00.120000000", 0, "und", true, 8315232342706310039L, 0, 0, 0)), f.tag().simpleTagsList());
      assertEquals(List.of("DURATION", "PICTURE", "file.ttf"), f.complexPropertyKeys());
      assertEquals(List.of(scoped), f.complexProperties("DURATION"));
      assertTrue(f.setComplexProperties("PICTURE", List.of()));
      assertTrue(f.setComplexProperties("file.ttf", List.of()));
      assertTrue(f.setComplexProperties("PART_NUMBER", List.of(target)));
      assertTrue(f.setComplexProperties("THUMBNAIL", List.of(binary)));
      f.save();
    }
    try (var f = accurate(p)) {
      assertNull(f.attachments());
      assertTags(List.of(text("DURATION", "00:00:00.120000000", 0, "und", true, 8315232342706310039L, 0, 0, 0), text("PART_NUMBER", "2", 20, "en", false, 0, 0, 0, 0), new SimpleTag("THUMBNAIL", new ByteVector("JPEG data"), SimpleTag.TargetTypeValue.Track, "und", false)), f.tag().simpleTagsList());
      assertEquals(List.of("DURATION", "PART_NUMBER", "THUMBNAIL"), f.complexPropertyKeys());
      assertEquals(List.of(scoped), f.complexProperties("DURATION"));
      assertEquals(List.of(target), f.complexProperties("PART_NUMBER"));
      assertEquals(List.of(binary), f.complexProperties("THUMBNAIL"));
      for (var k : List.of("PART_NUMBER", "THUMBNAIL", "DURATION")) assertTrue(f.setComplexProperties(k, List.of()));
      f.save();
    }
    try (var f = accurate(p)) {
      assertNull(f.existingTag());
      assertNull(f.attachments());
      assertTrue(f.setComplexProperties("font/ttf", List.of(map("data", bytes("TTF data"), "description", "Subtitle font", "fileName", "file.ttf"))));
      assertTrue(f.setComplexProperties("file.otf", List.of(map("data", bytes("OTF data"), "mimeType", "font/otf", "description", "OpenType"))));
      f.save();
    }
    try (var f = accurate(p)) {
      assertNull(f.existingTag());
      assertNotNull(f.attachments());
      assertEquals(List.of("file.ttf", "file.otf"), f.complexPropertyKeys());
      for (String k : List.of("file.ttf", "file.otf")) {
        var v = f.complexProperties(k);
        assertEquals(1, v.size());
        var m = v.getFirst();
        assertNotEquals(0, m.get("uid").toUnsignedLong());
        assertEquals(attachment(k.equals("file.ttf") ? "TTF data" : "OTF data", k, k.equals("file.ttf") ? "font/ttf" : "font/otf", k.equals("file.ttf") ? "Subtitle font" : "OpenType", m.get("uid").toUnsignedLong()), m);
        assertTrue(f.setComplexProperties(k, List.of()));
      }
      f.save();
    }
    sameOriginal(p, "no-tags.mka");
  }

  @Test
  void testOpenInvalid() throws Exception {
    assertThrows(java.io.UncheckedIOException.class, () -> new MatroskaFile(fixture("garbage.mp3")));
    var data = Files.readAllBytes(fixture("no-tags.mka"));
    assertTrue(accurate(new ByteVectorStream(data)).isValid());
    var s = new ByteVectorStream(Arrays.copyOf(data, 4000));
    assertThrows(java.io.UncheckedIOException.class,
      () -> new MatroskaFile(s, new ReadOptions(true, ReadStyle.ACCURATE, ReadOptions.DEFAULT.maxMetadataBytes())));
  }

  @Test
  void testSegmentSizeChange() throws Exception {
    Path p = copy("optimized.mkv");
    byte[] data = new byte[20000];
    Arrays.fill(data, (byte) 'x');
    try (var f = new MatroskaFile(p)) {
      assertTrue(f.isValid());
      assertNotNull(f.existingTag());
      assertNull(f.attachments());
      f.getOrCreateAttachments().addAttachedFile(new AttachedFile(data, "cover.jpg", "image/jpeg", 5081000385627515072L, "Cover"));
      f.save();
    }
    try (var f = accurate(p)) {
      assertEquals(initialProps(), f.tag().properties());
      assertArrayEquals(data, f.attachments().attachedFileList().getFirst().data());
      f.attachments().clear();
      f.save();
    }
    try (var f = accurate(p)) {
      assertNotNull(f.existingTag());
      assertNull(f.attachments());
    }
  }

  static ChapterEdition edition1() {
    return new ChapterEdition(List.of(new Chapter(0, 40000, List.of(new Chapter.Display("Chapter 1", "eng")), 1, false), new Chapter(40000, 80000, List.of(new Chapter.Display("Chapter 2", "eng"), new Chapter.Display("Kapitel 2", "deu")), 2), new Chapter(80000, 120000, List.of(new Chapter.Display("Chapter 3", "und")), 3, true)), true, false);
  }

  static Map<String, Variant> edition1Map() {
    return map("isDefault", true, "chapters", Variant.fromList(List.of(Variant.fromMap(map("timeStart", 0L, "timeEnd", 40000L, "uid", 1L, "displays", Variant.fromList(List.of(Variant.fromMap(map("string", "Chapter 1", "language", "eng")))))), Variant.fromMap(map("timeStart", 40000L, "timeEnd", 80000L, "uid", 2L, "displays", Variant.fromList(List.of(Variant.fromMap(map("string", "Chapter 2", "language", "eng")), Variant.fromMap(map("string", "Kapitel 2", "language", "deu")))))), Variant.fromMap(map("timeStart", 80000L, "timeEnd", 120000L, "uid", 3L, "isHidden", true, "displays", Variant.fromList(List.of(Variant.fromMap(map("string", "Chapter 3", "language", "und")))))))));
  }

  static Map<String, Variant> edition2Map() {
    return map("isOrdered", true, "uid", 321L, "chapters", Variant.fromList(List.of(Variant.fromMap(map("timeStart", 10000L, "uid", 1234567890L, "displays", Variant.fromList(List.of(Variant.fromMap(map("string", "Chapter A")))))))));
  }

  @Test
  void testChapters() throws Exception {
    Path p = copy("tags-before-cues.mkv");
    var e1 = edition1();
    var e2 = new ChapterEdition(List.of(new Chapter(10000, 0, List.of(new Chapter.Display("Chapter A", "")), 1234567890L)), false, true, 321);
    try (var f = new MatroskaFile(p)) {
      assertTrue(f.isValid());
      assertNotNull(f.existingTag());
      assertNull(f.attachments());
      assertNull(f.chapters());
      assertEquals(List.of("DURATION"), f.complexPropertyKeys());
      assertTrue(f.complexProperties("CHAPTERS").isEmpty());
      f.getOrCreateChapters().addChapterEdition(e1);
      f.save();
    }
    try (var f = new MatroskaFile(p)) {
      assertTrue(f.isValid());
      assertNotNull(f.existingTag());
      assertNull(f.attachments());
      assertEquals(List.of("DURATION", "CHAPTERS"), f.complexPropertyKeys());
      assertEquals(List.of(edition1Map()), f.complexProperties("CHAPTERS"));
      assertEquals(List.of(e1), f.chapters().chapterEditionList());
      assertTrue(f.setComplexProperties("CHAPTERS", List.of(edition2Map())));
      f.save();
    }
    try (var f = accurate(p)) {
      assertNotNull(f.existingTag());
      assertNull(f.attachments());
      assertEquals(List.of("DURATION", "CHAPTERS"), f.complexPropertyKeys());
      assertEquals(List.of(edition2Map()), f.complexProperties("CHAPTERS"));
      assertEquals(List.of(e2), f.chapters().chapterEditionList());
      var saved = f.chapters().chapterEditionList().getFirst();
      f.chapters().removeChapterEdition(321);
      f.chapters().addChapterEdition(e1);
      f.chapters().addChapterEdition(saved);
      f.getOrCreateAttachments().addAttachedFile(new AttachedFile(bytes("PNG data"), "folder.png", "image/png", 1763187649L, "Cover"));
      f.save();
    }
    try (var f = accurate(p)) {
      assertNotNull(f.existingTag());
      assertNotNull(f.attachments());
      assertNotNull(f.chapters());
      assertEquals(List.of("DURATION", "PICTURE", "CHAPTERS"), f.complexPropertyKeys());
      assertEquals(List.of(edition1Map(), edition2Map()), f.complexProperties("CHAPTERS"));
      f.attachments().clear();
      f.chapters().clear();
      f.save();
    }
    try (var f = accurate(p)) {
      assertNotNull(f.existingTag());
      assertNull(f.attachments());
    }
    sameOriginal(p, "tags-before-cues.mkv");
  }

  static void largeTags(MatroskaFile f) {
    f.tag().clearSimpleTags();
    f.tag().addSimpleTag(new SimpleTag("TITLE", LONG_TITLE, SimpleTag.TargetTypeValue.Track));
    f.tag().addSimpleTag(new SimpleTag("ARTIST", LONG_ARTIST, SimpleTag.TargetTypeValue.Track));
  }

  static int index(byte[] b, long id, int from) {
    byte[] n = MatroskaWire.id(id);
    outer:
    for (int i = from; i <= b.length - n.length; i++) {
      for (int j = 0; j < n.length; j++) if (b[i + j] != n[j]) continue outer;
      return i;
    }
    return -1;
  }

  static long end(byte[] b, int start, int idWidth) {
    int at = start + idWidth, first = b[at] & 255, width = Integer.numberOfLeadingZeros(first) - 23;
    long size = first & ((1 << (8 - width)) - 1);
    for (int i = 1; i < width; i++) size = (size << 8) | (b[at + i] & 255);
    return at + width + size;
  }

  @Test
  void testSaveTypesBeforeCues() throws Exception {
    byte[] original = Files.readAllBytes(fixture("tags-before-cues.mkv"));
    int before = index(original, 0x1f43b675, 0);
    assertTrue(before > 0);
    for (var style : WriteStyle.values()) {
      Path p = directory.resolve(style + ".mkv");
      Files.write(p, original);
      try (var f = new MatroskaFile(p)) {
        assertTrue(f.isValid());
        largeTags(f);
        f.save(style);
      }
      try (var f = accurate(p)) {
        assertNotNull(f.existingTag());
        assertEquals(LONG_TITLE, f.tag().title());
        assertEquals(LONG_ARTIST, f.tag().artist());
      }
      byte[] after = Files.readAllBytes(p);
      int cluster = index(after, 0x1f43b675, 0);
      assertTrue(cluster > 0);
      if (style == WriteStyle.AvoidInsert) {
        assertEquals(before, cluster);
        int cues = index(after, 0x1c53bb6b, cluster);
        assertTrue(index(after, 0x1254c367, cues + 4) > cues);
      } else assertTrue(cluster > before);
    }
  }

  @Test
  void testSaveTypesNoTrailingVoid() throws Exception {
    Path p = copy("tags-before-cues.mkv");
    try (var f = new MatroskaFile(p)) {
      assertTrue(f.isValid());
      largeTags(f);
      f.save(WriteStyle.AvoidInsert);
    }
    long large = Files.size(p);
    try (var f = new MatroskaFile(p)) {
      assertTrue(f.isValid());
      f.tag().clearSimpleTags();
      f.tag().addSimpleTag(new SimpleTag("TITLE", "X", SimpleTag.TargetTypeValue.Track));
      f.save(WriteStyle.AvoidInsert);
    }
    try (var f = accurate(p)) {
      assertNotNull(f.existingTag());
    }
    byte[] b = Files.readAllBytes(p);
    assertTrue(b.length < large);
    int cues = index(b, 0x1c53bb6b, index(b, 0x1f43b675, 0));
    int tags = index(b, 0x1254c367, cues + 4);
    assertTrue(tags > cues);
    assertEquals(b.length, end(b, tags, 4));
  }

  @Test
  void testSaveTypesReclaimVoid() throws Exception {
    Path p = copy("tags-before-cues.mkv");
    long original = Files.size(p);
    try (var f = new MatroskaFile(p)) {
      assertTrue(f.isValid());
      largeTags(f);
      f.save(WriteStyle.AvoidInsert);
    }
    long large = Files.size(p);
    assertTrue(large > original);
    try (var f = new MatroskaFile(p)) {
      assertTrue(f.isValid());
      f.tag().clearSimpleTags();
      f.tag().addSimpleTag(new SimpleTag("TITLE", "X", SimpleTag.TargetTypeValue.Track));
      f.save(WriteStyle.Compact);
    }
    long compact = Files.size(p);
    assertTrue(compact < large);
    Path reference = directory.resolve("reference.mkv");
    Files.copy(fixture("tags-before-cues.mkv"), reference);
    try (var f = new MatroskaFile(reference)) {
      assertTrue(f.isValid());
      f.tag().clearSimpleTags();
      f.tag().addSimpleTag(new SimpleTag("TITLE", "X", SimpleTag.TargetTypeValue.Track));
      f.save(WriteStyle.Compact);
    }
    assertTrue(Files.size(reference) <= compact);
    try (var f = accurate(p)) {
      assertNotNull(f.existingTag());
      assertEquals("X", f.tag().title());
    }
  }

  @Test
  void testSaveTypes() throws Exception {
    for (var style : WriteStyle.values()) {
      Path p = directory.resolve(style + ".mka");
      Files.copy(fixture("no-tags.mka"), p);
      long[] sizes = new long[4];
      for (int round = 0; round < 4; round++) {
        try (var f = new MatroskaFile(p)) {
          assertTrue(f.isValid());
          f.tag().clearSimpleTags();
          f.getOrCreateAttachments().clear();
          f.getOrCreateChapters().clear();
          roundData(f, round);
          f.save(style);
        }
        sizes[round] = Files.size(p);
        try (var f = accurate(p)) {
          assertNotNull(f.existingTag());
          assertEquals(ROUND_TITLES[round], f.tag().title());
          assertNotNull(f.attachments());
          assertEquals((round + 1) * 111L, f.attachments().attachedFileList().getFirst().uid());
          assertNotNull(f.chapters());
          assertEquals(1, f.chapters().chapterEditionList().size());
          var ch = f.chapters().chapterEditionList().getFirst().chapterList();
          assertEquals(new int[]{2, 1, 1, 3}[round], ch.size());
          assertEquals(round == 1 ? 1000 : 40000, ch.getFirst().timeEnd());
        }
      }
      if (style == WriteStyle.Compact) {
        assertTrue(sizes[1] < sizes[0]);
        assertNotEquals(sizes[1], sizes[2]);
        assertTrue(sizes[2] < sizes[0]);
      } else if (style == WriteStyle.AvoidInsert) {
        assertTrue(sizes[1] <= sizes[0]);
        assertTrue(sizes[2] <= sizes[0]);
      } else {
        assertEquals(sizes[0], sizes[1]);
        assertEquals(sizes[0], sizes[2]);
      }
      assertTrue(sizes[3] > sizes[0]);
    }
  }

  static void roundData(MatroskaFile f, int round) throws Exception {
    f.tag().addSimpleTag(new SimpleTag("TITLE", ROUND_TITLES[round], SimpleTag.TargetTypeValue.Track));
    if (round == 0)
      f.tag().addSimpleTag(new SimpleTag("ARTIST", "A Very Long Artist Name That Also Takes Up Lots Of Space", SimpleTag.TargetTypeValue.Track));
    byte[] data = new byte[][]{new byte[200], new byte[20], new byte[80], new byte[500]}[round];
    Arrays.fill(data, (byte) 'x');
    f.attachments().addAttachedFile(new AttachedFile(data, round == 1 ? "img.png" : "cover.jpg", round == 1 ? "image/png" : "image/jpeg", (round + 1) * 111L, round == 1 ? "Img" : "Cover"));
    var ch = new ArrayList<Chapter>();
    switch (round) {
      case 0 -> {
        ch.add(new Chapter(0, 40000, List.of(new Chapter.Display("Chapter One Long Name", "eng")), 1));
        ch.add(new Chapter(40000, 80000, List.of(new Chapter.Display("Chapter Two Long Name", "eng")), 2));
      }
      case 1 -> ch.add(new Chapter(0, 1000, List.of(new Chapter.Display("A", "und")), 1));
      case 2 -> ch.add(new Chapter(0, 40000, List.of(new Chapter.Display("Chapter Medium", "eng")), 1));
      case 3 -> {
        ch.add(new Chapter(0, 40000, List.of(new Chapter.Display("Chapter One Extremely Long Name Here", "eng"), new Chapter.Display("Kapitel Eins Sehr Langer Name", "deu")), 1));
        ch.add(new Chapter(40000, 80000, List.of(new Chapter.Display("Chapter Two Extremely Long Name Here", "eng"), new Chapter.Display("Kapitel Zwei Sehr Langer Name", "deu")), 2));
        ch.add(new Chapter(80000, 120000, List.of(new Chapter.Display("Chapter Three Extra Large", "eng")), 3, true));
      }
    }
    f.chapters().addChapterEdition(new ChapterEdition(ch, round != 1, round == 3));
  }

  @Test
  void testFastReadStyleLargeSegment() throws Exception {
    Path p = copy("tags-before-cues.mkv");
    byte[] b = Files.readAllBytes(p);
    assertEquals(3412, b.length);
    byte[] extra = MatroskaWire.render(0xec, new byte[1024 * 1024]);
    byte[] out = new byte[b.length + extra.length];
    System.arraycopy(b, 0, out, 0, b.length);
    System.arraycopy(extra, 0, out, b.length, extra.length);
    System.arraycopy(MatroskaWire.size(out.length - 0x34, 8), 0, out, 0x2c, 8);
    Files.write(p, out);
    for (var style : ReadStyle.values())
      try (var f = new MatroskaFile(p, new ReadOptions(true, style, ReadOptions.DEFAULT.maxMetadataBytes()))) {
        assertTrue(f.isValid());
        assertNotNull(f.existingTag());
        assertEquals("handbrake", f.tag().title());
      }
  }

  static class Counting extends ByteVectorStream {
    long bytesRead;

    Counting(byte[] b) {
      super(b);
    }

    @Override
    public ByteVector readBlock(long size) {
      var b = super.readBlock(size);
      bytesRead += b.size();
      return b;
    }
  }

  @Test
  void testAttachedFileDataReadOnDemand() throws Exception {
    Path p = copy("no-tags.mka");
    for (int size : new int[]{64 * 1024, 512 * 1024}) {
      byte[] payload = new byte[size];
      Arrays.fill(payload, (byte) 'x');
      try (var f = new MatroskaFile(p)) {
        assertTrue(f.isValid());
        f.getOrCreateAttachments().clear();
        f.attachments().addAttachedFile(new AttachedFile(payload, "cover.jpg", "image/jpeg", 0xa7ac, "Cover"));
        f.save();
      }
      var s = new Counting(Files.readAllBytes(p));
      try (var f = new MatroskaFile(s, new ReadOptions(true, ReadStyle.FAST, ReadOptions.DEFAULT.maxMetadataBytes()))) {
        assertTrue(f.isValid());
        assertTrue(s.bytesRead < 4096, "Header reads: " + s.bytesRead);
        long before = s.bytesRead;
        var a = f.attachments().attachedFileList();
        assertEquals(1, a.size());
        assertEquals(new AttachedFile(payload, "cover.jpg", "image/jpeg", 0xa7ac, "Cover"), a.getFirst());
        assertTrue(s.bytesRead >= before + size);
      }
    }
  }

  @Test
  void testSaveUnrequestedAttachedFileData() throws Exception {
    Path p = copy("no-tags.mka");
    byte[] payload = new byte[64 * 1024];
    Arrays.fill(payload, (byte) 'x');
    try (var f = new MatroskaFile(p)) {
      assertTrue(f.isValid());
      f.getOrCreateAttachments().addAttachedFile(new AttachedFile(payload, "cover.jpg", "image/jpeg", 0xa7ac, "Cover"));
      f.save();
    }
    try (var f = new MatroskaFile(p, new ReadOptions(true, ReadStyle.FAST, ReadOptions.DEFAULT.maxMetadataBytes()))) {
      assertTrue(f.isValid());
      f.tag().setTitle("Test title");
      f.save();
    }
    try (var f = accurate(p)) {
      assertEquals("Test title", f.tag().title());
      var a = f.attachments().attachedFileList();
      assertEquals(1, a.size());
      assertEquals("cover.jpg", a.getFirst().fileName());
      assertArrayEquals(payload, a.getFirst().data());
    }
  }

  @Test
  void testSegmentTitleWithoutAudioProperties() throws Exception {
    for (boolean read : new boolean[]{true, false})
      try (var f = new MatroskaFile(fixture("optimized.mkv"), new ReadOptions(read, ReadStyle.AVERAGE, ReadOptions.DEFAULT.maxMetadataBytes()))) {
        assertTrue(f.isValid());
        assertEquals(read, f.audioProperties() != null);
        assertEquals("handbrake", f.tag().title());
      }
    try (var f = new MatroskaFile(fixture("no-tags.mka"), ReadOptions.TAGS_ONLY)) {
      assertTrue(f.isValid());
      assertEquals("", f.tag().title());
    }
  }

  @Test
  void testUnknownSizeSegment() throws Exception {
    Path p = copy("no-tags.mka");
    byte[] b = Files.readAllBytes(p);
    assertEquals(12390, b.length);
    Arrays.fill(b, 0x2d, 0x34, (byte) 255);
    b[0x1482] = 0x7f;
    b[0x1483] = (byte) 255;
    Files.write(p, b);
    try (var f = accurate(p)) {
      var prop = f.detailedProperties();
      assertEquals(444, prop.lengthInMilliseconds());
      assertEquals(223, prop.bitrate());
      assertEquals(2, prop.channels());
      assertEquals(44100, prop.sampleRate());
      assertEquals("matroska", prop.docType());
      assertEquals(4, prop.docTypeVersion());
      assertEquals("A_MPEG/L3", prop.codecName());
      assertEquals("", prop.title());
      assertNull(f.existingTag());
      assertNull(f.attachments());
      assertTrue(f.tag().isEmpty());
      f.tag().setTitle("Unknown size");
      f.save();
    }
    try (var f = accurate(p)) {
      assertNotNull(f.existingTag());
      assertEquals("Unknown size", f.tag().title());
    }
  }

  // Generated literal expectations follow, reviewed against the pinned source assertions.
  static List<SimpleTag> simpleExpected() {
    return List.of(text("Test Name 1", "Test Value 1", 30, "en", true, 0L, 0L, 0L, 0L),
      text("TITLE", "Test title", 30, "und", true, 0L, 0L, 0L, 0L),
      text("ARTIST", "Test artist", 30, "und", true, 0L, 0L, 0L, 0L),
      text("DATE_RECORDED", "1969", 30, "und", true, 0L, 0L, 0L, 0L),
      text("Test Name 2", "Test Value 2", 50, "und", true, 29356L, 60695L, 51863L, 42924L));
  }

  static List<SimpleTag> initialExpected() {
    return List.of(text("DURATION", "00:00:00.120000000", 0, "und", true, Long.parseUnsignedLong("9584013959154292683"), 0L, 0L, 0L),
      text("ARTIST", "Actors", 30, "und", true, 0L, 0L, 0L, 0L),
      text("DESCRIPTION", "Description", 30, "und", true, 0L, 0L, 0L, 0L),
      text("DIRECTOR", "Director", 30, "und", true, 0L, 0L, 0L, 0L),
      text("ENCODER", "Lavf59.27.100", 30, "und", true, 0L, 0L, 0L, 0L),
      text("GENRE", "Genre", 30, "und", true, 0L, 0L, 0L, 0L),
      text("SUMMARY", "Comment", 30, "und", true, 0L, 0L, 0L, 0L),
      text("SYNOPSIS", "Plot", 30, "und", true, 0L, 0L, 0L, 0L),
      text("DATE_RELEASED", "2023", 50, "und", true, 0L, 0L, 0L, 0L));
  }

  static PropertyMap initialProps() {
    return PropertyMap.builder().put("ARTIST", List.of("Actors")).put("RELEASEDATE", List.of("2023")).put("DESCRIPTION", List.of("Description")).put("DIRECTOR", List.of("Director")).put("ENCODEDBY", List.of("Lavf59.27.100")).put("GENRE", List.of("Genre")).put("SUMMARY", List.of("Comment")).put("SYNOPSIS", List.of("Plot")).build();
  }

  static PropertyMap newProps() {
    return PropertyMap.builder().put("ALBUM", List.of("Album")).put("ALBUMARTIST", List.of("Album Artist")).put("ALBUMARTISTSORT", List.of("Album Artist Sort")).put("ALBUMSORT", List.of("Album Sort")).put("ARTIST", List.of("Artist")).put("ARTISTS", List.of("Artists")).put("ARTISTSORT", List.of("Artist Sort")).put("ASIN", List.of("ASIN")).put("BARCODE", List.of("Barcode")).put("CATALOGNUMBER", List.of("Catalog Number 1", "Catalog Number 2")).put("COMMENT", List.of("Comment")).put("DATE", List.of("2021-01-10")).put("DISCNUMBER", List.of("3")).put("DISCTOTAL", List.of("5")).put("DJMIXER", List.of("Mixed by")).put("ENCODEDBY", List.of("Encoded by")).put("ENCODING", List.of("Encoder settings")).put("ENCODINGTIME", List.of("Date encoded")).put("GENRE", List.of("Genre")).put("INITIALKEY", List.of("Initial key")).put("ISRC", List.of("UKAAA0500001")).put("LABEL", List.of("Label 1", "Label 2")).put("MEDIA", List.of("Media")).put("MUSICBRAINZ_ALBUMARTISTID", List.of("MusicBrainz_AlbumartistID")).put("MUSICBRAINZ_ALBUMID", List.of("MusicBrainz_AlbumID")).put("MUSICBRAINZ_ARTISTID", List.of("MusicBrainz_ArtistID")).put("MUSICBRAINZ_RELEASEGROUPID", List.of("MusicBrainz_ReleasegroupID")).put("MUSICBRAINZ_RELEASETRACKID", List.of("MusicBrainz_ReleasetrackID")).put("MUSICBRAINZ_TRACKID", List.of("MusicBrainz_TrackID")).put("OWNER", List.of("Purchase owner")).put("ORIGINALDATE", List.of("2021-01-09")).put("RELEASECOUNTRY", List.of("Release Country")).put("RELEASEDATE", List.of("2021-01-10")).put("RELEASESTATUS", List.of("Release Status")).put("RELEASETYPE", List.of("Release Type")).put("REMIXER", List.of("Remixed by")).put("SCRIPT", List.of("Script")).put("TAGGINGDATE", List.of("2021-01-08")).put("TITLE", List.of("Title")).put("TRACKNUMBER", List.of("2")).put("TRACKTOTAL", List.of("4")).build();
  }

  static List<String> expectedNames() {
    return List.of("BINARY", "TITLE", "ARTIST", "ARTISTSORT", "TITLESORT", "PART_NUMBER", "TOTAL_PARTS", "MUSICBRAINZ_ALBUMARTISTID", "MUSICBRAINZ_ALBUMID", "MUSICBRAINZ_RELEASEGROUPID", "DATE_RELEASED", "ARTIST", "ARTISTS", "ARTISTSORT", "ASIN", "BARCODE", "CATALOG_NUMBER", "CATALOG_NUMBER", "COMMENT", "DATE_RECORDED", "MIXED_BY", "ENCODER", "ENCODER_SETTINGS", "DATE_ENCODED", "GENRE", "INITIAL_KEY", "ISRC", "LABEL_CODE", "LABEL_CODE", "ORIGINAL_MEDIA_TYPE", "MUSICBRAINZ_ARTISTID", "MUSICBRAINZ_RELEASETRACKID", "MUSICBRAINZ_TRACKID", "ORIGINALDATE", "PURCHASE_OWNER", "RELEASECOUNTRY", "RELEASESTATUS", "RELEASETYPE", "REMIXED_BY", "SCRIPT", "DATE_TAGGED", "TITLE", "PART_NUMBER", "TOTAL_PARTS");
  }

  static final String LONG_TITLE = "An Extremely Long Title Value That Is Definitely Larger Than The Original Tags Element In The File Because It Contains Many Characters To Ensure That The AvoidInsert Move-To-End Behavior Triggers Here";
  static final String LONG_ARTIST = "An Extremely Long Artist Name Value That Is Also Larger Than The Original Tags Element And Together With The Title Tag Makes The Rendered Output Exceed The Original Tags Size So The AvoidInsert Triggers";
  static final String[] ROUND_TITLES = {"A Very Long Title That Takes Up A Lot Of Space In The File 1234567890", "Short", "Medium Title 12345678901234", "An Extremely Long Title That Is Even Larger Than The Previous Large Title With Extra Content To Ensure Growth 0123456789ABCDEF"};
}
