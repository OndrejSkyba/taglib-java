package cz.skyba.taglib.toolkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ByteStringAndCollectionBehaviorTest {
  @TempDir
  Path dir;

  @Test
  void nativeViewCopiesAndDefaultListSeparatorsAreReachable() {
    var source = new ByteVector("abc");
    var slice = new ByteVector(source, 1, 2);
    source.set(1, (byte) 'x');
    assertEquals(new ByteVector("bc"), slice);
    assertTrue(new ByteVector(source, 3, 0).isEmpty());
    assertThrows(IndexOutOfBoundsException.class, () -> new ByteVector(source, 2, 2));
    var parts = List.of(new ByteVector("a"), new ByteVector(), new ByteVector("b"));
    assertEquals(new ByteVector("a  b"), ByteVector.join(parts));
    assertEquals(new ByteVector("a  b"), ByteVector.join(new ByteVector("a::b:").split(new ByteVector(":"), 1)));
  }

  @Test
  void variantCopiesEachByteValueWithoutRetainingCallerStorage() {
    var bytes = new ByteVector("abc");
    var source = new ArrayList<>(List.of(bytes, bytes));
    var value = Variant.fromByteVectorList(source);
    var copy = value.toByteVectorList();
    source.clear();
    bytes.set(0, (byte) 'x');
    copy.getFirst().set(1, (byte) 'y');
    assertEquals(List.of(new ByteVector("ayc"), new ByteVector("abc")), copy);
    assertEquals(List.of(new ByteVector("abc"), new ByteVector("abc")), value.toByteVectorList());
    value.extractByteVectorList().value().getFirst().clear();
    assertEquals(List.of(new ByteVector("abc"), new ByteVector("abc")), value.toByteVectorList());
  }

  @Test
  void memoryStreamExtendsEqualReplacementBeyondEnd() {
    for (long replace : new long[]{2, 3}) {
      var beyond = new ByteVectorStream(new ByteVector("abc"));
      beyond.insert(new ByteVector("XY"), 5, replace);
      assertArrayEquals(new byte[]{'a', 'b', 'c', 0, 0, 'X', 'Y'}, beyond.data().toByteArray());
      assertEquals(7, beyond.tell());
    }
  }

  @Test
  void nativeReverseSearchOffsetAndAlignment() {
    var v = new ByteVector("OggS*OggS*OggS");
    assertEquals(10, v.rfind(new ByteVector("OggS"), 0, 1));
    assertEquals(5, v.rfind(new ByteVector("OggS"), 7, 1));
    assertEquals(10, v.rfind(new ByteVector("OggS"), 12, 1));
    assertEquals(5, v.find(new ByteVector("OggS"), 1, 2));
    assertEquals(-1, v.find(new ByteVector(), 0, 1));
    assertEquals(-1, v.find(new ByteVector("OggS"), 0, 0));
    assertFalse(v.startsWith(new ByteVector()));
    assertEquals(
      6, new ByteVector("blah blah").containsAt(new ByteVector("blah"), 6, 1, 3) ? 6 : -1);
  }

  @Test
  void nativeReplacementSplitPartialMatchAndDetachedCopies() {
    var value = new ByteVector("ababa");
    var copy = new ByteVector(value);
    assertEquals("XXa", value.replace(new ByteVector("ab"), new ByteVector("X")).toString());
    assertEquals("ababa", copy.toString());
    assertEquals(3, new ByteVector("123ab").endsWithPartialMatch(new ByteVector("abc")));
    assertEquals(-1, new ByteVector("ab").endsWithPartialMatch(new ByteVector("abc")));
    var list = new ByteVector("a::b:").split(new ByteVector(":"));
    assertEquals(List.of(new ByteVector("a"), new ByteVector(), new ByteVector("b")), list);
    assertEquals("a||b", ByteVector.join(list, new ByteVector("|")).toString());
    assertTrue(new ByteVector().split(new ByteVector(":")).isEmpty());
  }

  @Test
  void nativeIntegerBoundsFloatsAndBase64() {
    var v = new ByteVector(new byte[]{0, (byte) 255, 1});
    assertEquals(65281, v.toUnsignedInt(true));
    assertEquals(130816, v.toUnsignedInt(false));
    assertEquals(255, v.toShort(1, true) >> 8 & 255);
    assertEquals(0, v.toUnsignedInt(99, true));
    assertEquals(0.0, v.toFloat64LE(0));
    assertEquals(-13.75, ByteVector.fromFloat64LE(-13.75).toFloat64LE(0));
    assertEquals("hello", ByteVector.fromBase64(new ByteVector("aGVsbG8=")).toString());
    assertTrue(ByteVector.fromBase64(new ByteVector("aGVsbG8")).isEmpty());
    assertTrue(ByteVector.fromBase64(new ByteVector("aA==\n")).isEmpty());
    assertEquals("h", ByteVector.fromBase64(new ByteVector("aA=X")).toString());
  }

  @Test
  void upstreamMemoryStreamTestsAndCursorSideEffects() {
    var source = new ByteVector("abcd");
    var stream = new ByteVectorStream(source);
    source.set(0, (byte) 'z');
    assertEquals("a", stream.readBlock(1).toString());
    assertEquals("bc", stream.readBlock(2).toString());
    assertEquals("d", stream.readBlock(3).toString());
    assertTrue(stream.readBlock(3).isEmpty());
    stream.insert(new ByteVector("xx"), 1, 1);
    assertEquals("axxcd", stream.data().toString());
    assertEquals(3, stream.tell());
    stream.insert(new ByteVector("yy"), 0, 2);
    stream.insert(new ByteVector("foa"), 3, 2);
    stream.insert(new ByteVector("123"), 3, 0);
    assertEquals("yyx123foa", stream.data().toString());
    stream.removeBlock(1, 2);
    assertEquals("y123foa", stream.data().toString());
    assertEquals(stream.length(), stream.tell());
    stream.seek(-2, IOStream.Position.END);
    assertEquals("oa", stream.readBlock(99).toString());
    stream.seek(10);
    stream.writeBlock(new ByteVector("!"));
    assertEquals(11, stream.length());
    assertEquals(0, stream.data().at(9));
    stream.truncate(2);
    assertEquals(11, stream.tell());
  }

  @Test
  void fileStreamWritesInPlaceAndReadOnlyWritesDoNotChangeFile() throws Exception {
    Path path = dir.resolve("bytes");
    Files.writeString(path, "abcd");
    Object identity =
      Files.readAttributes(path, BasicFileAttributes.class).fileKey();
    try (var stream = new FileStream(path)) {
      assertTrue(stream.isOpen());
      assertFalse(stream.readOnly());
      stream.insert(new ByteVector("xx"), 1, 1);
      assertEquals(5, stream.tell());
      assertEquals("axxcd", Files.readString(path));
      stream.insert(new ByteVector("z"), 1, 2);
      assertEquals(4, stream.tell());
      assertEquals("azcd", Files.readString(path));
      stream.truncate(8);
      assertEquals(4, stream.tell());
      assertEquals(8, stream.length());
    }
    assertEquals(
      identity,
      Files.readAttributes(path, BasicFileAttributes.class).fileKey());
    byte[] before = Files.readAllBytes(path);
    try (var stream = new FileStream(path, true)) {
      stream.writeBlock(new ByteVector("no"));
      assertTrue(stream.readOnly());
    }
    assertArrayEquals(before, Files.readAllBytes(path));
    assertThrows(java.io.UncheckedIOException.class, () -> new FileStream(dir.resolve("absent")));
  }

  @Test
  void variantExtractsExactTypesAndDoesNotCoerceNumbers() {
    assertTrue(new Variant().isEmpty());
    assertFalse(new Variant("").isEmpty());
    assertEquals(-4, new Variant(-4).toInt());
    assertEquals(new Variant.Extraction<>(0L, false), new Variant(5).extractUnsignedInt());
    assertEquals(4294967295L, Variant.fromUnsignedInt(-1).toUnsignedInt());
    assertEquals("18446744073709551615", Variant.fromUnsignedLong(-1).render());
    assertEquals("", new Variant(5).toText());
    assertNotEquals(new Variant(5), Variant.fromUnsignedInt(5));
    assertNotEquals(new Variant(0.0), new Variant(-0.0));
    assertEquals(new Variant(Double.NaN), new Variant(Double.NaN));
  }

  @Test
  void variantDoubleRenderingUsesNativeDefaultStreamPrecision() {
    assertEquals("1.23456", new Variant(1.234565).render());
    assertEquals("2.34567", new Variant(2.345675).render());
    assertEquals("1.23456e-05", new Variant(0.00001234565).render());
    assertEquals("-1.23456", new Variant(-1.234565).render());
    assertEquals("1.23456", new Variant(1.234555).render());
    assertEquals("1e+06", new Variant(999999.5).render());
    assertEquals("-0", new Variant(-0.0).render());
    assertEquals("nan", new Variant(Double.NaN).render());
    assertEquals("-nan", new Variant(Double.longBitsToDouble(0xfff8000000000000L)).render());
  }

  @Test
  void fileStreamExposesItsNativeBufferSizeToSubclassesAndPackagePeers() {
    assertEquals(1024, FileStream.bufferSize());
  }

  @Test
  void variantKeepsBinaryAndNestedValuesImmutable() {
    var bytes = new ByteVector(new byte[]{1, 0, (byte) 255});
    var value = new Variant(bytes);
    bytes.set(0, (byte) 7);
    value.toByteVector().set(0, (byte) 9);
    assertEquals(1, value.toByteVector().at(0));
    // Native diagnostic streaming converts ByteVector to String, stopping at NUL.
    assertEquals("\"\\x01\"", value.render());
    var map =
      Variant.fromMap(
        Map.of("z", new Variant(true), "a", Variant.fromList(List.of(new Variant("x")))));
    assertEquals("{\"a\": [\"x\"], \"z\": true}", map.render());
    assertThrows(UnsupportedOperationException.class, () -> map.toMap().clear());
    assertEquals("1.23", new Variant(1.23).render());
  }

  @Test
  void mutablePropertyMapKeepsOperatorIndexAndValueCopyContracts() {
    var map = new MutablePropertyMap();
    map.getOrCreate("title").add("one");
    assertTrue(map.containsKey("TITLE"));
    var copy = map.values("title");
    copy.add("outside");
    assertEquals(List.of("one"), map.values("title"));
    map.add("TITLE", List.of("two"));
    map.merge(map);
    assertEquals(List.of("one", "two", "one", "two"), map.values("title"));
    var snapshot = map.snapshot();
    map.put("title", List.of("changed"));
    assertEquals(List.of("one", "two", "one", "two"), snapshot.get("title"));
    map.addUnsupportedData("raw");
    var identifiers = map.unsupportedData();
    map.getOrCreate("empty");
    map.removeEmpty();
    assertFalse(map.containsKey("empty"));
    assertEquals(List.of("raw"), identifiers);
    assertEquals(List.of("changed"), map.values("title"));
    map.clear();
    assertTrue(map.isEmpty());
    assertTrue(map.unsupportedData().isEmpty());
    assertTrue(identifiers.isEmpty());
  }

  @Test
  void selfMergeAppendsValuesAndIdentifiersWithoutInvalidatingLiveLists() {
    var map = new MutablePropertyMap();
    map.put("TITLE", List.of("one", "two"));
    map.getOrCreate("EMPTY");
    map.addUnsupportedData("opaque");
    var values = map.getOrCreate("TITLE");
    var identifiers = map.unsupportedData();
    var before = map.snapshot();
    map.merge(map);
    assertSame(values, map.getOrCreate("TITLE"));
    assertEquals(List.of("one", "two", "one", "two"), values);
    assertEquals(List.of("opaque", "opaque"), identifiers);
    assertTrue(map.containsKey("EMPTY"));
    assertEquals(List.of("one", "two"), before.get("TITLE"));
    assertEquals(List.of("opaque"), before.unsupportedData());
  }

  @Test
  void clearingIdentifiersPreservesEmptyListsLiveValuesAndDetachedSnapshots() {
    var map = new MutablePropertyMap();
    map.put("TITLE", List.of("kept"));
    map.put("BLANK", List.of(""));
    map.getOrCreate("EMPTY");
    map.addUnsupportedData("opaque");
    var values = map.getOrCreate("TITLE");
    var identifiers = map.unsupportedData();
    var before = map.snapshot();
    map.clearUnsupportedData();
    assertEquals(before.asMap(), map.snapshot().asMap());
    assertTrue(map.containsKey("EMPTY"));
    assertTrue(identifiers.isEmpty());
    map.removeEmpty();
    assertFalse(map.containsKey("EMPTY"));
    assertSame(values, map.getOrCreate("TITLE"));
    assertEquals(List.of("kept"), values);
    assertEquals(List.of(""), map.values("BLANK"));
    assertTrue(identifiers.isEmpty());
    var cleaned = map.snapshot();
    map.clearUnsupportedData();
    assertEquals(cleaned, map.snapshot());
    assertTrue(before.containsKey("EMPTY"));
    assertEquals(List.of("opaque"), before.unsupportedData());
  }

  @Test
  void versionSegmentsAreMaskedLikeNative() {
    assertEquals(new VersionNumber(1, 2, 3), new VersionNumber(257, 258, 259));
    assertEquals(0x010203, new VersionNumber(1, 2, 3).combinedVersion());
    assertTrue(new VersionNumber(2, 0).compareTo(new VersionNumber(1, 255, 255)) > 0);
  }
}
