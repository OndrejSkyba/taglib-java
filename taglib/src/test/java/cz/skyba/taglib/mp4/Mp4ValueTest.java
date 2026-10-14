package cz.skyba.taglib.mp4;

import cz.skyba.taglib.AudioFile;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.TagString;
import cz.skyba.taglib.toolkit.Variant;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Mp4ValueTest {
  static List<String> strings(String... v) {
    return Mp4MutableTest.strings(v);
  }

  static CoverArt art(CoverArt.Format f, String s) {
    return new CoverArt(f, new ByteVector(s));
  }

  @Test
  void testSimple() {
    var a = art(CoverArt.Format.PNG, "foo");
    var b = new CoverArt(a);
    var c = new CoverArt(a);
    for (var x : List.of(a, b, c)) {
      assertEquals(CoverArt.Format.PNG, x.format());
      assertEquals(new ByteVector("foo"), x.data());
    }
    a.data().clear();
    assertEquals(new ByteVector("foo"), a.data());
  }

  @Test
  void testCoverArtValues() {
    var l = List.of(art(CoverArt.Format.PNG, "foo"), art(CoverArt.Format.JPEG, "bar"));
    var i = Item.coverArt(l);
    var l2 = i.toCoverArtList();
    assertEquals(l, l2);
    l2.clear();
    assertEquals(2, i.toCoverArtList().size());
    assertEquals(CoverArt.Format.PNG, l.get(0).format());
    assertEquals(new ByteVector("foo"), l.get(0).data());
    assertEquals(CoverArt.Format.JPEG, l.get(1).format());
    assertEquals(new ByteVector("bar"), l.get(1).data());
  }

  @Test
  void testItemOperations() {
    var e = new Item();
    var equal = List.of(new Item(1), Item.fromByte('A'), Item.fromUnsignedInt(2), new Item(3L), new Item(true), new Item(4, 5), Item.textValues(strings("abc", "de")), new Item(List.of(new ByteVector("f"), new ByteVector("gh"))), Item.coverArt(List.of(art(CoverArt.Format.PNG, "foo"), art(CoverArt.Format.JPEG, "bar"))));
    var other = List.of(new Item(-1), Item.fromByte('Z'), Item.fromUnsignedInt(0), new Item(-7L), new Item(false), new Item(-4, -5), Item.textValues(strings("abc")), new Item(new ArrayList<ByteVector>()), Item.coverArt(List.of(art(CoverArt.Format.JPEG, "bar"))));
    var types = List.of(Item.Type.INT, Item.Type.BYTE, Item.Type.UNSIGNED_INT, Item.Type.LONG, Item.Type.BOOL, Item.Type.INT_PAIR, Item.Type.STRING_LIST, Item.Type.BYTE_VECTOR_LIST, Item.Type.COVER_ART_LIST);
    for (int n = 0; n < equal.size(); n++) {
      var i = equal.get(n);
      assertEquals(i, new Item(i));
      assertNotEquals(i, other.get(n));
      assertTrue(i.isValid());
      assertEquals(types.get(n), i.type());
      if (n + 1 < equal.size()) assertNotEquals(other.get(n), equal.get(n + 1));
    }
    assertNotEquals(other.getLast(), e);
    assertFalse(e.isValid());
    assertEquals(Item.Type.VOID, e.type());
    assertEquals(e, e);
    assertNotEquals(e, new Item());
    assertNotEquals(e, null);
    assertEquals(1, equal.get(0).toInt());
    assertEquals('A', equal.get(1).toByte());
    assertEquals(2, equal.get(2).toUnsignedInt());
    assertEquals(3, equal.get(3).toLong());
    assertTrue(equal.get(4).toBoolean());
    assertEquals(4, equal.get(5).toIntPair().first());
    assertEquals(strings("abc", "de"), equal.get(6).toStringList());
    assertEquals(List.of(new ByteVector("f"), new ByteVector("gh")), equal.get(7).toByteVectorList());
    assertEquals(CoverArt.Format.PNG, equal.get(8).toCoverArtList().getFirst().format());
    var s1 = other.get(6);
    var s3 = equal.get(6);
    assertEquals(strings("abc"), s1.toStringList());
    assertEquals(strings("abc", "de"), s3.toStringList());
    assertEquals(AtomDataType.TypeUndefined, s1.atomDataType());
    var copy = new Item(s1);
    s1.setAtomDataType(AtomDataType.TypeUTF8);
    assertEquals(AtomDataType.TypeUTF8, s1.atomDataType());
    assertEquals(AtomDataType.TypeUndefined, copy.atomDataType());
    copy.setAtomDataType(AtomDataType.TypeInteger);
    assertEquals(AtomDataType.TypeUTF8, s1.atomDataType());
    var textCopy = new Item(s3);
    assertEquals(strings("abc", "de"), textCopy.toStringList());
    assertEquals(Map.of("key1", new Item(1), "key2", new Item(4, 5)), Map.of("key1", equal.get(0), "key2", equal.get(5)));
    assertNotEquals(Map.of("key1", new Item(1), "key2", new Item(4, 5)), Map.of("key1", new Item(1), "key2", new Item(-4, -5)));
  }

  static Mp4File stem(ByteVectorStream s) throws IOException {
    var f = new Mp4File(s);
    assertFalse(f.hasMp4Tag());
    assertTrue(f.tag().complexProperties("STEM").isEmpty());
    assertTrue(f.tag().setComplexProperties("STEM", List.of(Map.of("manifest", new Variant(new ByteVector("{some text data}"))))));
    f.save();
    return f;
  }

  @Test
  void testCreate() throws Exception {
    var s = Mp4MutableTest.stream("no-tags.m4a");
    stem(s);
    var f = new Mp4File(s);
    assertTrue(f.hasMp4Tag());
    var stems = f.tag().complexProperties("STEM");
    assertEquals(1, stems.size());
    assertTrue(stems.getFirst().containsKey("manifest"));
    assertEquals(new Variant(new ByteVector("{some text data}")), stems.getFirst().get("manifest"));
  }

  @Test
  void testUpdate() throws Exception {
    var s = Mp4MutableTest.stream("no-tags.m4a");
    stem(s);
    var f = new Mp4File(s);
    String title = "Y".repeat(1024), artist = "X" + "\0".repeat(1023);
    var value = new ByteVector("Z".repeat(1024));
    assertTrue(f.setComplexProperties("PICTURE", List.of(Map.of("data", new Variant(new ByteVector("DummyData")), "pictureType", new Variant("Front Cover"), "mimeType", new Variant("image/png"), "description", new Variant("Test")))));
    assertTrue(f.tag().setComplexProperties("STEM", List.of(Map.of("manifest", new Variant(value)))));
    f.tag().setArtist(artist.substring(0, 1));
    f.tag().setTitle(title);
    f.save();
    f = new Mp4File(s);
    assertTrue(f.hasMp4Tag());
    assertEquals(List.of(Map.of("manifest", new Variant(value))), f.tag().complexProperties("STEM"));
    assertEquals(List.of(Map.of("data", new Variant(new ByteVector("DummyData")), "mimeType", new Variant("image/png"))), f.tag().complexProperties("PICTURE"));
    assertEquals(title, f.tag().title());
    assertEquals("X", f.tag().artist());
  }

  @Test
  void testRemove() throws Exception {
    var s = Mp4MutableTest.stream("no-tags.m4a");
    stem(s);
    var f = new Mp4File(s);
    assertTrue(f.hasMp4Tag());
    assertTrue(f.tag().setComplexProperties("STEM", List.of()));
    f.save();
    f = new Mp4File(s);
    assertFalse(f.hasMp4Tag());
    assertFalse(f.tag().complexPropertyKeys().contains("STEM"));
  }

  @Test
  void chapterAndStemCopiesRetainValuesAndDetachedExports() {
    var a = new Chapter("a", 1);
    var b = new Chapter(a);
    var holder = new ChapterHolder();
    holder.setChapters(List.of(a));
    assertNotSame(a, b);
    assertEquals(new Chapter("a", 1), b);
    assertEquals(List.of(b), holder.chapters());
    var returned = holder.chapters();
    assertThrows(UnsupportedOperationException.class, () -> returned.set(0, new Chapter("b", 2)));
    assertEquals(List.of(b), holder.chapters());
    var snapshot = new QuickTimeChapters(List.of(b));
    assertEquals(List.of(new Chapter("a", 1)), snapshot.chapters());
    var stem = new Stem(new ByteVector("bytes"));
    var copy = new Stem(stem);
    stem.data()[0] = 0;
    copy.byteVector().clear();
    assertArrayEquals("bytes".getBytes(), copy.data());
    assertArrayEquals(new byte[0], new Stem().data());
  }

  @Test
  void missingItemAndReadOnlyMapValueCopy() {
    var t = new Tag();
    assertFalse(t.item("absent").isValid());
    assertFalse(t.contains("absent"));
    assertTrue(t.itemMap().isEmpty());
    assertTrue(t.properties().isEmpty());
    t.setItem("tmpo", new Item(7));
    var map = t.itemMap();
    var item = map.get("tmpo");
    item.setAtomDataType(AtomDataType.TypeInteger);
    assertEquals(AtomDataType.TypeInteger, t.item("tmpo").atomDataType());
    item = new Item(9);
    assertEquals(7, t.item("tmpo").toInt());
    assertThrows(UnsupportedOperationException.class, () -> map.put("x", new Item()));
    t.removeItem("tmpo");
    assertFalse(map.containsKey("tmpo"));
  }

  @Test
  void unsafeTypedGettersHaveBoundedJavaOutcomes() {
    for (var i : List.of(new Item(), Item.textValues(strings("a")), Item.fromUnsignedInt(4)))
      assertThrows(IllegalStateException.class, i::toInt);
    assertEquals(new ArrayList<ByteVector>(), new Item(7).toByteVectorList());
    assertThrows(IllegalStateException.class, () -> new Tag().save());
  }

  @Test
  void emptyNumericPropertiesRetainListTypeAndSaveExplicitZeroValues() throws Exception {
    var empty = Item.textValues(List.of());
    var copy = new Item(empty);
    assertEquals(Item.Type.STRING_LIST, copy.type());
    assertFalse(copy.toBoolean());
    assertEquals(0, copy.toInt());
    assertEquals(0, copy.toByte());
    assertEquals(0L, copy.toUnsignedInt());
    assertEquals(0L, copy.toLong());
    assertEquals(new Item.IntPair(0, 0), copy.toIntPair());
    var stream = Mp4MutableTest.stream("no-tags.m4a");
    var file = new Mp4File(stream);
    var keys = List.of("TRACKNUMBER", "DISCNUMBER", "BPM", "COMPILATION", "TVSEASON", "TVEPISODE", "MOVEMENTNUMBER", "MOVEMENTCOUNT");
    var input = PropertyMap.builder();
    for (var key : keys) input.put(key, List.of());
    input.put("ARTIST", List.of());
    input.put("TITLE", "");
    assertTrue(file.replaceProperties(input.build()).rejected().isEmpty());
    assertEquals(0, file.tag().track());
    for (var key : keys) {
      var name = new TagString(ItemFactory.instance().nameForPropertyKey(key)).toString();
      assertEquals(Item.Type.STRING_LIST, file.tag().item(name).type());
      assertEquals(List.of("0"), file.properties().get(key));
    }
    assertEquals(List.of(), file.properties().get("ARTIST"));
    file.save();
    var reopened = new Mp4File(stream);
    for (var key : keys) assertEquals(List.of("0"), reopened.properties().get(key));
    assertFalse(reopened.properties().containsKey("ARTIST"));
    assertEquals(List.of(""), reopened.properties().get("TITLE"));
  }

  @Test
  void unsafeChapterTimesAreRejectedBeforeOriginalWrites() throws Exception {
    for (boolean qt : List.of(false, true)) {
      var s = Mp4MutableTest.stream("no-tags.m4a");
      var f = new Mp4File(s);
      byte[] before = s.data().toByteArray();
      f.tag().setTitle("pending");
      if (qt) f.setQtChapters(List.of(new Chapter("large", Long.MAX_VALUE)));
      else f.setNeroChapters(List.of(new Chapter("large", Long.MAX_VALUE)));
      assertThrows(RuntimeException.class, f::save);
      assertArrayEquals(before, s.data().toByteArray());
      assertEquals("pending", f.tag().title());
      assertEquals(List.of(new Chapter("large", Long.MAX_VALUE)), qt ? f.qtChapters() : f.neroChapters());
    }
  }

  @Test
  void aggregateMetadataBudgetIsCheckedBeforeRetention() {
    var entries = new ByteVector();
    for (int i = 0; i < 3; i++)
      entries.append(Mp4MutableTest.atom("©nam", Mp4MutableTest.atom("data", ByteVector.fromUnsignedInt(1).append(ByteVector.fromUnsignedInt(0)).append(new ByteVector(40)))));
    var all = Mp4MutableTest.atom("moov", Mp4MutableTest.atom("udta", Mp4MutableTest.atom("meta", ByteVector.fromUnsignedInt(0).append(Mp4MutableTest.atom("ilst", entries)))));
    var s = new ByteVectorStream(all);
    assertThrows(IllegalArgumentException.class, () -> new Mp4File(s, new ReadOptions(false, ReadStyle.AVERAGE, 100)));
    assertTrue(s.isOpen());
    assertEquals(all, s.data());
  }

  @Test
  void supportRestoresCallerCursorAndFileRefKeepsActualOwner() throws Exception {
    var s = Mp4MutableTest.stream("no-tags.m4a");
    s.seek(7);
    assertTrue(Mp4File.isSupported(s));
    assertEquals(7, s.tell());
    try (var ref = new AudioFile(s)) {
      assertInstanceOf(Mp4File.class, ref.file());
      var tag = ref.tag();
      ref.tag().setTitle("shared");
      assertSame(tag, ref.tag());
      assertDoesNotThrow(ref::save);
      assertEquals("shared", new Mp4File(s).tag().title());
    }
    assertTrue(s.isOpen());
  }

  @Test
  void unsignedPropertyTextRetainsHighBitValuesAcrossSaveAndReopen() throws Exception {
    // Deliberate compatibility difference: native String::number(int) exports negative text.
    var stream = Mp4MutableTest.stream("no-tags.m4a");
    var expected = PropertyMap.builder()
      .put("TVSEASON", "4294967295")
      .put("TVEPISODE", "2147483648")
      .build();
    try (var file = new Mp4File(stream)) {
      assertTrue(file.replaceProperties(expected).rejected().isEmpty());
      assertEquals(expected, file.properties());
      assertEquals(4294967295L, file.tag().item("tvsn").toUnsignedInt());
      assertEquals(2147483648L, file.tag().item("tves").toUnsignedInt());
      file.save();
    }
    try (var reopened = new Mp4File(stream)) {
      assertEquals(expected, reopened.properties());
      assertEquals(4294967295L, reopened.tag().item("tvsn").toUnsignedInt());
      assertEquals(2147483648L, reopened.tag().item("tves").toUnsignedInt());
    }
  }

  @Test
  void legacyStructuredSnapshotsKeepValueEqualityAndLiveTagsKeepIdentity() {
    var a = new Tag(List.of(new Chapter("one", 1)), new Stem(new ByteVector("manifest")));
    var b = new Tag(List.of(new Chapter("one", 1)), new Stem(new ByteVector("manifest")));
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertNotEquals(new Tag(), new Tag());
    assertNotEquals(a, new Tag(List.of()));
  }

}
