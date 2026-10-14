package cz.skyba.taglib.toolkit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TagStringByteEncodingTest {
  @Test
  void byteConstructorsRespectEncodingNullsAndInvalidSequences() {
    var nul = new ByteVector(new byte[]{'a', 0, 'b'});
    assertEquals("a", new TagString(nul).toString());
    assertEquals("a\0b", TagString.from8Bit(nul.data(), TagString.Type.LATIN1).toString());
    assertTrue(
      new TagString(new ByteVector(new byte[]{(byte) 0xc0, (byte) 0x80}), TagString.Type.UTF8)
        .isEmpty());
    assertTrue(new TagString(new ByteVector(new byte[]{0, 65}), TagString.Type.UTF16).isEmpty());
    assertEquals(
      "A",
      new TagString(
        new ByteVector(new byte[]{(byte) 255, (byte) 254, 65, 0, 99}),
        TagString.Type.UTF16)
        .toString());
    assertEquals(
      "é😀",
      new TagString(new TagString("é😀").data(TagString.Type.UTF8), TagString.Type.UTF8)
        .toString());
    assertEquals("fffe4100", new TagString("A").data(TagString.Type.UTF16).toHex().toString());
    assertEquals("00", new TagString("Ā").data(TagString.Type.LATIN1).toHex().toString());
    assertTrue(new TagString("\ud800").data(TagString.Type.UTF8).isEmpty());
  }

  @Test
  void stringSplittingRetainsEmptyFieldsAndUsesLiteralDelimiters() {
    var input = new TagString("a::b::");
    var separator = new TagString("::");
    assertEquals(List.of("a", "b", ""), input.split(separator));
    assertEquals(List.of("", "a", "", "b", ""), new TagString(" a  b ").split());
    assertEquals(List.of("a", "b", ""), new TagString("a.*b.*").split(new TagString(".*")));
    assertThrows(IllegalArgumentException.class, () -> input.split(new TagString()));
    assertEquals("éX", new TagString("éx").upper().toString());
    assertEquals(
      "\u000b x \u000b", new TagString("\t\u000b x \u000b\n").stripWhiteSpace().toString());
  }

  @Test
  void splitResultsAreDetachedMutableListsOfJavaStrings() {
    var input = new TagString("A\0B C");
    var parts = input.split();
    input.clear();
    assertEquals(List.of("A\0B", "C"), parts);
    parts.set(0, "changed");
    parts.add("another");
    assertTrue(input.isEmpty());
    assertEquals(List.of("changed", "C", "another"), parts);
  }

  @Test
  void numericParsingMatchesNativeStrictStatusAndPartialResults() {
    assertEquals(new Variant.Extraction<>(12, false), new TagString("12foo").extractInt());
    assertEquals(new Variant.Extraction<>(12, true), new TagString(" +12").extractInt());
    assertEquals(new Variant.Extraction<>(12, false), new TagString("12 ").extractInt());
    assertEquals(
      new Variant.Extraction<>(Integer.MAX_VALUE, false),
      new TagString("2147483647").extractInt());
    assertEquals(
      new Variant.Extraction<>(Integer.MIN_VALUE, false),
      new TagString("2147483648").extractInt());
    assertEquals(
      new Variant.Extraction<>(Long.MAX_VALUE, false),
      new TagString("9223372036854775808").extractLong(10));
    assertEquals(new Variant.Extraction<>(-1L, true), new TagString("-1").extractUnsignedLong(10));
    assertEquals(
      new Variant.Extraction<>(1L, true),
      new TagString("-18446744073709551615").extractUnsignedLong(10));
    assertEquals(new Variant.Extraction<>(8L, true), new TagString("010").extractLong(0));
    assertEquals(new Variant.Extraction<>(255L, true), new TagString("0xff").extractLong(0));
    assertEquals(new Variant.Extraction<>(7, true), new TagString("7\0junk").extractInt());
  }
}
