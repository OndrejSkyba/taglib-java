package cz.skyba.taglib.internal;

import cz.skyba.taglib.toolkit.ByteVector;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.*;
import static org.junit.jupiter.api.Assertions.*;

class TextConversionTest {
  private static byte[] bytes(String hex) {
    return HexFormat.of().parseHex(hex);
  }

  @Test
  void utf16ByteOrderAndBomRules() {
    assertArrayEquals(bytes("0066006f006f"), TextBytes.encode("foo", UTF_16BE));
    assertArrayEquals(bytes("66006f006f00"), TextBytes.encode("foo", UTF_16LE));
    assertArrayEquals(bytes("fffe66006f006f00"), TextBytes.encode("foo", UTF_16));
    assertEquals("foo", TextBytes.decode(bytes("0066006f006f"), UTF_16BE));
    assertEquals("foo", TextBytes.decode(bytes("66006f006f00"), UTF_16LE));
    assertEquals("foo", TextBytes.decode(bytes("fffe66006f006f00"), UTF_16));
    assertEquals("foo", TextBytes.decode(bytes("feff0066006f006f"), UTF_16));
    for (String hex : List.of("20", "2020", "20200066006f006f", "fffe", "feff")) {
      assertEquals("", TextBytes.decode(bytes(hex), UTF_16));
    }
  }

  @Test
  void supplementaryCharactersAndMalformedSurrogates() {
    byte[] utf16 = bytes("fffe42d8b7dfce914b5c");
    byte[] utf8 = bytes("f0a0aeb7e9878ee5b18b");
    assertArrayEquals(utf8, TextBytes.encode(TextBytes.decode(utf16, UTF_16), UTF_8));
    assertArrayEquals(utf16, TextBytes.encode(TextBytes.decode(utf8, UTF_8), UTF_16));
    assertArrayEquals(bytes("f09f8590f0a083b5f09f8880"),
      TextBytes.encode(TextBytes.decode(bytes("fffe3cd850dd40d8f5dc3cd800de"), UTF_16), UTF_8));
    for (String hex : List.of("feffd8013042", "feff3042dc01", "feffdc01d801")) {
      assertArrayEquals(new byte[0], TextBytes.encode(TextBytes.decode(bytes(hex), UTF_16), UTF_8));
    }
  }

  @Test
  void malformedUtf8ProducesEmptyText() {
    assertEquals("/", TextBytes.decode(bytes("2f"), UTF_8));
    for (String hex : List.of("c0af", "e080af", "f08080af", "f880808080", "fc8080808080", "c2",
      "e080", "f08080", "f8808080", "fc80808080", "eda080edb080", "edb080eda080", "80")) {
      assertEquals("", TextBytes.decode(bytes(hex), UTF_8), hex);
    }
  }

  @Test
  void encodingPreservesCodeUnitsRatherThanReplacingUnrepresentableCharacters() {
    assertArrayEquals(bytes("e52c9e"), TextBytes.encode("日本語", ISO_8859_1));
    assertArrayEquals(bytes("e697a5e69cace8aa9e"), TextBytes.encode("日本語", UTF_8));
    assertArrayEquals(bytes("fffee5652c679e8a"), TextBytes.encode("日本語", UTF_16));
    assertArrayEquals(bytes("e5652c679e8a"), TextBytes.encode("日本語", UTF_16LE));
    assertArrayEquals(bytes("65e5672c8a9e"), TextBytes.encode("日本語", UTF_16BE));
    assertTrue(TextBytes.isLatin1("é\0"));
    assertFalse(TextBytes.isLatin1("Ā"));
    for (var charset : List.of(ISO_8859_1, UTF_8, UTF_16LE, UTF_16BE)) {
      assertArrayEquals(new byte[0], TextBytes.encode("", charset));
    }
    assertArrayEquals(bytes("fffe"), TextBytes.encode("", UTF_16));
  }

  @Test
  void nulTerminationIsAFieldDecodingChoice() {
    for (var charset : List.of(ISO_8859_1, UTF_8, UTF_16, UTF_16LE, UTF_16BE)) {
      byte[] encoded = TextBytes.encode("a\0b", charset);
      assertEquals("a\0b", TextBytes.decode(encoded, charset));
      assertEquals("a", new ByteVector(encoded).decodeTerminatedText(charset));
    }
    assertEquals("A", TextBytes.decode(bytes("fffe410063"), UTF_16));
  }

  @Test
  void byteVectorTextFactoryOwnsItsBytes() {
    String text = "é😀\0Z";
    var first = ByteVector.fromText(text, UTF_8);
    var second = ByteVector.fromText(text, UTF_8);
    first.set(0, (byte) 0);
    assertEquals(text, TextBytes.decode(second.data(), UTF_8));
    assertEquals("00", ByteVector.fromText("Ā", ISO_8859_1).toHex().toString());
    assertThrows(IllegalArgumentException.class, () -> ByteVector.fromText("x", US_ASCII));
  }

  @Test
  void numericParsingRetainsPartialValuesAndStrictStatus() {
    assertEquals(new TextNumbers.IntResult(12, false), TextNumbers.extractInt("12foo"));
    assertEquals(new TextNumbers.IntResult(12, true), TextNumbers.extractInt(" +12"));
    assertEquals(new TextNumbers.IntResult(12, false), TextNumbers.extractInt("12 "));
    assertEquals(new TextNumbers.IntResult(Integer.MAX_VALUE, false), TextNumbers.extractInt("2147483647"));
    assertEquals(new TextNumbers.IntResult(Integer.MIN_VALUE, false), TextNumbers.extractInt("2147483648"));
    assertFalse(TextNumbers.extractInt("-2147483649").ok());
    assertEquals(new TextNumbers.LongResult(Long.MAX_VALUE, false), TextNumbers.extractLong("9223372036854775808", 10));
    assertEquals(new TextNumbers.LongResult(-1L, true), TextNumbers.extractUnsignedLong("-1", 10));
    assertEquals(new TextNumbers.LongResult(1L, true), TextNumbers.extractUnsignedLong("-18446744073709551615", 10));
    assertEquals(new TextNumbers.LongResult(8L, true), TextNumbers.extractLong("010", 0));
    assertEquals(new TextNumbers.LongResult(255L, true), TextNumbers.extractLong("0xff", 0));
    assertEquals(new TextNumbers.IntResult(7, true), TextNumbers.extractInt("7\0junk"));
    for (var entry : Map.of("123", 123, "-123", -123, "123aa", 123, "-123aa", -123, "0000", 0, "0001", 1).entrySet()) {
      assertEquals(entry.getValue().intValue(), TextNumbers.parseInt(entry.getKey()));
    }
    for (String text : List.of("abc", "", "-")) {
      assertEquals(new TextNumbers.IntResult(0, false), TextNumbers.extractInt(text));
    }
    assertEquals(new TextNumbers.IntResult(1, false), TextNumbers.extractInt("1x"));
  }

  @Test
  void whitespaceAndCaseConversionRemainAsciiSpecific() {
    for (String text : List.of("  foo  ", "foo    ", "    foo", "foo", "f o o", " f o o ")) {
      assertEquals(text.contains("f o o") ? "f o o" : "foo", Ascii.stripWhiteSpace(text));
    }
    assertEquals("\u000b x \u000b", Ascii.stripWhiteSpace("\t\u000b x \u000b\n"));
    assertEquals("éX", Ascii.upper("éx"));
  }
}
