package cz.skyba.taglib.toolkit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolkitListAndMapOperationsTest {
  @Test
  void testSplitSingleChar() {
    var l = new ByteVector("a b").split(new ByteVector(" "));
    assertEquals(2, l.size());
    assertEquals(new ByteVector("a"), l.get(0));
    assertEquals(new ByteVector("b"), l.get(1));
  }

  @Test
  void testSplitSingleChar_2() {
    var l = new ByteVector("a").split(new ByteVector(" "));
    assertEquals(1, l.size());
    assertEquals(new ByteVector("a"), l.get(0));
  }

  @Test
  void testInitialData() {
    assertEquals(new ByteVector("abcd"), new ByteVectorStream(new ByteVector("abcd")).data());
  }

  @Test
  void testWriteBlock() {
    var s = new ByteVectorStream(new ByteVector("abcd"));
    s.seek(1);
    s.writeBlock(new ByteVector("xx"));
    assertEquals(new ByteVector("axxd"), s.data());
  }

  @Test
  void testWriteBlockResize() {
    var s = new ByteVectorStream(new ByteVector("abcd"));
    s.seek(3);
    s.writeBlock(new ByteVector("xx"));
    assertEquals(new ByteVector("abcxx"), s.data());
    s.seek(5);
    s.writeBlock(new ByteVector("yy"));
    assertEquals(new ByteVector("abcxxyy"), s.data());
  }

  @Test
  void testReadBlock() {
    var s = new ByteVectorStream(new ByteVector("abcd"));
    assertEquals(new ByteVector("a"), s.readBlock(1));
    assertEquals(new ByteVector("bc"), s.readBlock(2));
    assertEquals(new ByteVector("d"), s.readBlock(3));
    assertEquals(new ByteVector(), s.readBlock(3));
  }

  @Test
  void testRemoveBlock() {
    var s = new ByteVectorStream(new ByteVector("abcd"));
    s.removeBlock(1, 1);
    assertEquals(new ByteVector("acd"), s.data());
    s.removeBlock(0, 2);
    assertEquals(new ByteVector("d"), s.data());
    s.removeBlock(0, 2);
    assertEquals(new ByteVector(), s.data());
  }

  @Test
  void testInsert() {
    var s = new ByteVectorStream(new ByteVector("abcd"));
    s.insert(new ByteVector("xx"), 1, 1);
    assertEquals(new ByteVector("axxcd"), s.data());
    s.insert(new ByteVector("yy"), 0, 2);
    assertEquals(new ByteVector("yyxcd"), s.data());
    s.insert(new ByteVector("foa"), 3, 2);
    assertEquals(new ByteVector("yyxfoa"), s.data());
    s.insert(new ByteVector("123"), 3, 0);
    assertEquals(new ByteVector("yyx123foa"), s.data());
  }

  @Test
  void testSeekEnd() {
    var s = new ByteVectorStream(new ByteVector("abcdefghijklmnopqrstuvwxyz"));
    assertEquals(26, s.length());
    s.seek(-4, IOStream.Position.END);
    assertEquals(new ByteVector("w"), s.readBlock(1));
    s.seek(-25, IOStream.Position.END);
    assertEquals(new ByteVector("b"), s.readBlock(1));
  }

  @Test
  void testInvalidKeys() {
    var a = new MutablePropertyMap();
    assertTrue(a.isEmpty());
    a.getOrCreate("ÄÖÜ").add("test");
    assertEquals(1, a.size());
    var b = new MutablePropertyMap();
    b.getOrCreate("ÄÖÜ").add("test");
    assertEquals(a, b);
    assertTrue(a.containsAll(b));
    b.put("ARTIST", List.of("Test Artist"));
    assertNotEquals(a, b);
    assertTrue(b.containsAll(a));
    b.getOrCreate("ÄÖÜ").add("test 2");
    assertFalse(b.containsAll(a));
  }

  @Test
  void testGetSet() {
    var p = new MutablePropertyMap();
    p.put("Title", List.of("Test Title"));
    var artists = List.of("Artist 1", "Artist 2");
    p.add("Artist", artists);
    for (String k : List.of("TITLE", "Title")) {
      assertEquals(List.of("Test Title"), p.values(k));
      assertEquals(List.of("Test Title"), p.getOrCreate(k));
    }
    assertTrue(p.containsKey("title"));
    assertEquals(List.of("Test Title"), p.find("TITLE").orElseThrow());
    assertEquals(2, p.size());
    assertFalse(p.isEmpty());
    p.clear();
    assertTrue(p.isEmpty());
    for (String k : List.of("TITLE", "Title")) assertEquals(List.of(), p.values(k));
    assertEquals(artists, p.getOrDefault("Title", artists));
    assertFalse(p.containsKey("title"));
    assertTrue(p.find("TITLE").isEmpty());
    assertEquals(0, p.size());
    assertTrue(p.isEmpty());
    for (String k : List.of("TITLE", "Title")) assertEquals(List.of(), p.getOrCreate(k));
    assertTrue(p.containsKey("title"));
    assertTrue(p.find("TITLE").isPresent());
    assertEquals(1, p.size());
    assertFalse(p.isEmpty());
  }

  @Test
  void testVersionNumber() {
    var a = new VersionNumber(2, 1, 0);
    var b = new VersionNumber(2, 1, 1);
    var c = new VersionNumber(2, 2, 0);
    var d = new VersionNumber(3, 0, 0);
    assertEquals(0x020100, a.combinedVersion());
    assertEquals(2, a.majorVersion());
    assertEquals(1, a.minorVersion());
    assertEquals(0, a.patchVersion());
    assertEquals(new VersionNumber(2, 1), a);
    assertFalse(a.equals(b));
    assertNotEquals(a, b);
    assertFalse(!a.equals(a));
    assertTrue(a.compareTo(b) < 0);
    assertFalse(c.compareTo(a) < 0);
    assertTrue(c.compareTo(b) > 0);
    assertFalse(a.compareTo(b) > 0);
    assertTrue(a.compareTo(a) <= 0);
    assertTrue(a.compareTo(d) <= 0);
    assertFalse(d.compareTo(c) <= 0);
    assertTrue(a.compareTo(a) >= 0);
    assertTrue(c.compareTo(a) >= 0);
    assertFalse(a.compareTo(d) >= 0);
    assertEquals("2.1.0", a.toString());
  }
}
