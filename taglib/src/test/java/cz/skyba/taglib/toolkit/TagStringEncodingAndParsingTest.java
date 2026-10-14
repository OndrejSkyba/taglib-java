package cz.skyba.taglib.toolkit;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TagStringEncodingAndParsingTest {
  static ByteVector b(String h) {
    return new ByteVector(HexFormat.of().parseHex(h));
  }

  static TagString s(String s) {
    return new TagString(s);
  }

  @Test
  void testString() {
    var x = s("taglib string");
    assertEquals(new ByteVector("taglib string"), x.data(TagString.Type.LATIN1));
    assertArrayEquals("taglib string".getBytes(StandardCharsets.ISO_8859_1), x.to8Bit());
    assertEquals(s("taglib string"), x);
    for (String other : List.of("taglib STRING", "taglib", "taglib string taglib")) assertNotEquals(s(other), x);
    assertEquals(s("taglib string"), new TagString(x.toString()));
    for (String other : List.of("taglib STRING", "taglib", "taglib string taglib"))
      assertNotEquals(s(other), new TagString(x.toString()));
    x.clear();
    assertTrue(x.isEmpty());
    var unicode = new TagString(b("4a6f73c3a9204361726c6f73"), TagString.Type.UTF8);
    assertArrayEquals(b("4a6f73e9204361726c6f73").toByteArray(), unicode.to8Bit());
    var latin = new TagString(b("4a6f73e9204361726c6f73"));
    assertArrayEquals(b("4a6f73c3a9204361726c6f73").toByteArray(), latin.to8Bit(true));
    assertEquals(s("1"), s("1"));
    assertEquals(s("一"), new TagString('一'));
    assertEquals(unicode, TagString.from8Bit(unicode.to8Bit(true), TagString.Type.UTF8));
    var japanese = s("日本語");
    assertEquals('本', japanese.toString().charAt(1));
    for (var t : List.of(TagString.Type.UTF16BE, TagString.Type.UTF16LE, TagString.Type.UTF16BE, TagString.Type.UTF16LE))
      assertEquals('本', new TagString(japanese.data(t), t).at(1));
    for (String v : List.of("  foo  ", "foo    ", "    foo", "foo", "f o o", " f o o "))
      assertEquals(s(v.contains("f o o") ? "f o o" : "foo"), s(v).stripWhiteSpace());
    assertEquals(new ByteVector("foo"), s("foo").data());
    assertEquals(new ByteVector("f"), s("f").data());
  }

  @Test
  void testUTF16Encode() {
    var a = s("foo");
    assertNotEquals(a.data(TagString.Type.UTF16BE), a.data(TagString.Type.UTF16LE));
    assertEquals(b("0066006f006f"), a.data(TagString.Type.UTF16BE));
    assertEquals(b("66006f006f00"), a.data(TagString.Type.UTF16LE));
    assertEquals(b("fffe66006f006f00"), a.data(TagString.Type.UTF16));
  }

  @Test
  void testUTF16Decode() {
    assertEquals(s("foo"), new TagString(b("0066006f006f"), TagString.Type.UTF16BE));
    assertEquals(s("foo"), new TagString(b("66006f006f00"), TagString.Type.UTF16LE));
    assertEquals(s("foo"), new TagString(b("fffe66006f006f00"), TagString.Type.UTF16));
  }

  @Test
  void testUTF16DecodeInvalidBOM() {
    for (String h : List.of("20", "2020", "20200066006f006f"))
      assertEquals(s(""), new TagString(b(h), TagString.Type.UTF16));
  }

  @Test
  void testUTF16DecodeEmptyWithBOM() {
    for (String h : List.of("fffe", "feff")) assertEquals(s(""), new TagString(b(h), TagString.Type.UTF16));
  }

  @Test
  void testSurrogatePair() {
    var a = b("fffe42d8b7dfce914b5c");
    var b = b("f0a0aeb7e9878ee5b18b");
    assertEquals(b, new TagString(a, TagString.Type.UTF16).data(TagString.Type.UTF8));
    assertEquals(a, new TagString(b, TagString.Type.UTF8).data(TagString.Type.UTF16));
    for (String h : List.of("feffd8013042", "feff3042dc01", "feffdc01d801"))
      assertTrue(new TagString(b(h), TagString.Type.UTF16).data(TagString.Type.UTF8).isEmpty());
  }

  @Test
  void testAppendCharDetach() {
    var a = s("a");
    var b = new TagString(a);
    a.append(new TagString('b'));
    assertEquals(s("ab"), a);
    assertEquals(s("a"), b);
  }

  @Test
  void testAppendStringDetach() {
    var a = s("a");
    var b = new TagString(a);
    a.append(s("b"));
    assertEquals(s("ab"), a);
    assertEquals(s("a"), b);
  }

  @Test
  void testRfind() {
    for (int i = 0; i <= 7; i++) assertEquals(i < 3 ? -1 : 3, s("foo.bar").rfind(s("."), i));
    assertEquals(3, s("foo.bar").rfind(s(".")));
  }

  @Test
  void testToInt() {
    String[] inputs = {"123", "-123", "abc", "1x", "", "-"};
    int[] values = {123, -123, 0, 1, 0, 0};
    for (int i = 0; i < inputs.length; i++) {
      var r = s(inputs[i]).extractInt();
      assertEquals(values[i], r.value());
      assertEquals(i < 2, r.ok());
    }
    for (var r : Map.of("123", 123, "-123", -123, "123aa", 123, "-123aa", -123, "0000", 0, "0001", 1).entrySet())
      assertEquals(r.getValue(), s(r.getKey()).toInt());
    assertFalse(s("2147483648").extractInt().ok());
    assertFalse(s("-2147483649").extractInt().ok());
  }

  @Test
  void testFromInt() {
    assertEquals(s("0"), TagString.number(0));
    assertEquals(s("12345678"), TagString.number(12345678));
    assertEquals(s("-12345678"), TagString.number(-12345678));
  }

  @Test
  void testSubstr() {
    assertEquals(s("01"), s("0123456").substr(0, 2));
    assertEquals(s("12"), s("0123456").substr(1, 2));
    assertEquals(s("123456"), s("0123456").substr(1, 200));
    assertEquals(s("0123456"), s("0123456").substr(0, 7));
    assertEquals(s("0123456"), s("0123456").substr(0, 200));
  }

  @Test
  void testNewline() {
    var cr = s("abc\rxyz");
    var lf = s("abc\nxyz");
    var both = s("abc\r\nxyz");
    assertEquals(7, cr.size());
    assertEquals(7, lf.size());
    assertEquals(8, both.size());
    assertEquals('\r', cr.at(3));
    assertEquals('\n', lf.at(3));
    assertEquals('\r', both.at(3));
    assertEquals('\n', both.at(4));
  }

  @Test
  void testUpper() {
    var a = s("tagLIB 012 strING");
    var b = a.upper();
    assertEquals(s("tagLIB 012 strING"), a);
    assertEquals(s("TAGLIB 012 STRING"), b);
  }

  @Test
  void testEncodeNonLatin1() {
    var j = s("日本語");
    assertEquals(b("e52c9e"), j.data(TagString.Type.LATIN1));
    assertEquals(b("e697a5e69cace8aa9e"), j.data(TagString.Type.UTF8));
    assertEquals(b("fffee5652c679e8a"), j.data(TagString.Type.UTF16));
    assertEquals(b("e5652c679e8a"), j.data(TagString.Type.UTF16LE));
    assertEquals(b("65e5672c8a9e"), j.data(TagString.Type.UTF16BE));
    assertArrayEquals(b("e52c9e").toByteArray(), j.to8Bit(false));
    assertArrayEquals(b("e697a5e69cace8aa9e").toByteArray(), j.to8Bit(true));
  }

  @Test
  void testEncodeEmpty() {
    var e = s("");
    assertTrue(e.data(TagString.Type.LATIN1).isEmpty());
    assertTrue(e.data(TagString.Type.UTF8).isEmpty());
    assertEquals(b("fffe"), e.data(TagString.Type.UTF16));
    assertTrue(e.data(TagString.Type.UTF16LE).isEmpty());
    assertTrue(e.data(TagString.Type.UTF16BE).isEmpty());
    assertEquals(0, e.to8Bit(false).length);
    assertEquals(0, e.to8Bit(true).length);
  }

  @Test
  void testEncodeNonBMP() {
    assertEquals(b("f09f8590f0a083b5f09f8880"), new TagString(b("fffe3cd850dd40d8f5dc3cd800de"), TagString.Type.UTF16).data(TagString.Type.UTF8));
  }

  @Test
  void testIterator() {
    var a = s("taglib string");
    var b = new TagString(a);
    assertEquals('t', a.iterator().next());
    assertEquals('t', b.iterator().next());
    b.set(4, 'I');
    assertEquals('i', a.at(4));
    assertEquals('I', b.at(4));
  }

  @Test
  void testInvalidUTF8() {
    assertEquals(s("/"), new TagString(b("2f"), TagString.Type.UTF8));
    for (String h : List.of("c0af", "e080af", "f08080af", "f880808080", "fc8080808080", "c2", "e080", "f08080", "f8808080", "fc80808080", "eda080edb080", "edb080eda080"))
      assertTrue(new TagString(b(h), TagString.Type.UTF8).isEmpty());
    assertTrue(TagString.from8Bit(new byte[]{(byte) 0x80}, TagString.Type.UTF8).isEmpty());
  }

  @Test
  void testEmpty() {
    var e = s("");
    var n = s("A");
    var m = s("");
    assertEquals(e, s(""));
    assertEquals(e, new TagString(""));
    assertNotEquals(e, new TagString('\0'));
    assertEquals(e, new TagString(b("0000"), TagString.Type.UTF16LE));
    assertEquals(e, new TagString(new ByteVector()));
    assertEquals(0, e.to8Bit().length);
    assertEquals("", e.toString());
    assertFalse(e.iterator().hasNext());
    assertFalse(m.iterator().hasNext());
    assertEquals(0, e.find(m));
    assertEquals(-1, e.find(n));
    assertEquals(0, n.find(e));
    assertEquals(0, e.rfind(m));
    assertEquals(-1, e.rfind(n));
    assertEquals(1, n.rfind(e));
    assertEquals(List.of(""), e.split());
    assertTrue(e.startsWith(m));
    assertFalse(e.startsWith(n));
    assertTrue(n.startsWith(e));
    assertEquals(e, e.substr(0));
    assertEquals(e, m.append(e));
    assertEquals(n, m.append(n));
    m.clear();
    assertEquals(e, m);
    assertEquals(n, new TagString(n).append(e));
    assertEquals(e, e.upper());
    assertEquals(0, e.size());
    assertEquals(0, e.length());
    assertTrue(e.isEmpty());
    assertEquals(new ByteVector(), e.data(TagString.Type.LATIN1));
    assertEquals(new ByteVector(), e.data(TagString.Type.UTF16LE));
    assertFalse(e.extractInt().ok());
    assertEquals(e, e.stripWhiteSpace());
    assertTrue(e.isLatin1());
    assertTrue(e.isAscii());
    assertEquals(e, m);
    assertNotEquals(e, n);
    assertEquals(e, s(""));
    assertNotEquals(e, s(" "));
    assertEquals(e, m.append(e));
    assertEquals(n, m.append(n));
    m.clear();
    assertEquals(e, m);
    assertTrue(e.compareTo(n) < 0);
    assertEquals(e, new TagString(e).append(m));
    assertEquals(n, new TagString(e).append(n));
    assertEquals(e, new TagString(e).append(s("")));
  }

  @Test
  void nativeTerminatorIndexIsReadableAndZeroWritable() {
    for (String value : List.of("", "abc")) {
      var t = s(value);
      assertEquals('\0', t.at(t.size()));
      t.set(t.size(), '\0');
      assertEquals(value, t.toString());
      assertThrows(IndexOutOfBoundsException.class, () -> t.set(t.size(), 'X'));
      assertEquals(value, t.toString());
    }
  }

  @Test
  void copiesPreserveEmbeddedNulsOnAppend() {
    var unique = s("a\0b");
    unique.append(s("c"));
    assertEquals("a\0bc", unique.toString());
    var a = s("a\0b");
    var b = new TagString(a);
    a.append(s("x"));
    assertEquals("a\0bx", a.toString());
    assertEquals("a\0b", b.toString());
    b.append(s("y"));
    assertEquals("a\0by", b.toString());
  }

  @Test
  void mutationsDoNotDependOnOtherCopiesOrTheirState() {
    for (String input : List.of("", "plain", "a\0b", "\0tail", "tail\0", "a\0b\0c", "𝄞\0雪")) {
      var source = s(input);
      var copy = new TagString(source);
      var untouched = new TagString(source);
      source.append(s("!"));
      assertEquals(input + "!", source.toString());
      assertEquals(input, copy.toString());
      source.clear();
      copy.append(s("?"));
      assertEquals(input + "?", copy.toString());
      assertEquals(input, untouched.toString());
      assertTrue(source.isEmpty());
    }
  }

  @Test
  void settingCharactersBeforeAndAfterEmbeddedNulPreservesIndependentCopies() {
    var source = s("a\0b\0c");
    var copy = new TagString(source);
    source.set(4, 'z');
    assertEquals("a\0b\0z", source.toString());
    assertEquals("a\0b\0c", copy.toString());
    copy.set(0, 'x');
    copy.set(1, 'y');
    assertEquals("xyb\0c", copy.toString());
    assertEquals("a\0b\0z", source.toString());
    source.set(source.size(), '\0');
    assertEquals("a\0b\0z", source.toString());
    assertThrows(IndexOutOfBoundsException.class, () -> source.set(source.size(), 'x'));
    assertEquals("a\0b\0z", source.toString());
  }

  @Test
  void selfAppendPreservesEmbeddedNulsEvenWithAnotherCopy() {
    var source = s("a\0b");
    var copy = new TagString(source);
    assertSame(source, source.append(source));
    assertEquals("a\0ba\0b", source.toString());
    assertEquals("a\0b", copy.toString());
  }

  @Test
  void copiedStringsKeepEncodingAndByteConstructorTerminatorRules() {
    var source = s("a\0b");
    var copy = new TagString(source);
    copy.set(2, 'c');
    for (var encoding : TagString.Type.values()) {
      assertEquals(s("a\0c").data(encoding), copy.data(encoding));
      assertEquals(s("a"), new TagString(copy.data(encoding), encoding));
    }
    assertEquals(s("a\0c"), TagString.from8Bit(copy.to8Bit(true), TagString.Type.UTF8));
    assertEquals("a\0b", source.toString());
  }
}
