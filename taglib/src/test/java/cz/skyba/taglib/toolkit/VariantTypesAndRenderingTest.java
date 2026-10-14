package cz.skyba.taglib.toolkit;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class VariantTypesAndRenderingTest {
  @Test
  void testVariantTypes() {
    var sl = List.of("el0", "el");
    var bytes = new ByteVector("data");
    var bvl = List.of(new ByteVector("first"), new ByteVector("second"));
    var vl = List.of(new Variant("1st"), new Variant("2nd"));
    var vm = Map.of("key1", new Variant("value1"), "key2", new Variant("value2"));
    var a = List.of(new Variant(), new Variant(true), new Variant(-4), Variant.fromUnsignedInt(5), new Variant(-6L), Variant.fromUnsignedLong(7), new Variant(1.23), new Variant("test"), new Variant("charp"), Variant.fromStringList(sl), new Variant(bytes), Variant.fromByteVectorList(bvl), Variant.fromList(vl), Variant.fromMap(vm));
    var types = List.of(Variant.Type.VOID, Variant.Type.BOOL, Variant.Type.INT, Variant.Type.UNSIGNED_INT, Variant.Type.LONG, Variant.Type.UNSIGNED_LONG, Variant.Type.DOUBLE, Variant.Type.STRING, Variant.Type.STRING, Variant.Type.STRING_LIST, Variant.Type.BYTE_VECTOR, Variant.Type.BYTE_VECTOR_LIST, Variant.Type.VARIANT_LIST, Variant.Type.VARIANT_MAP);
    for (int i = 0; i < a.size(); i++) {
      assertEquals(types.get(i), a.get(i).type());
      assertEquals(i == 0, a.get(i).isEmpty());
    }
    assertTrue(a.get(1).toBoolean());
    assertEquals(new Variant.Extraction<>(true, true), a.get(1).extractBoolean());
    assertEquals(-4, a.get(2).toInt());
    assertEquals(new Variant.Extraction<>(-4, true), a.get(2).extractInt());
    assertEquals(5, a.get(3).toUnsignedInt());
    assertEquals(new Variant.Extraction<>(5L, true), a.get(3).extractUnsignedInt());
    assertEquals(-6, a.get(4).toLong());
    assertEquals(new Variant.Extraction<>(-6L, true), a.get(4).extractLong());
    assertEquals(7, a.get(5).toUnsignedLong());
    assertEquals(new Variant.Extraction<>(7L, true), a.get(5).extractUnsignedLong());
    assertEquals(1.23, a.get(6).toDouble());
    assertEquals(new Variant.Extraction<>(1.23, true), a.get(6).extractDouble());
    for (int i = 7; i <= 8; i++) {
      assertEquals(i == 7 ? "test" : "charp", a.get(i).toText());
      assertEquals(new Variant.Extraction<>(i == 7 ? "test" : "charp", true), a.get(i).extractString());
    }
    assertEquals(sl, a.get(9).toStringList());
    assertEquals(new Variant.Extraction<>(sl, true), a.get(9).extractStringList());
    assertEquals(bytes, a.get(10).toByteVector());
    assertEquals(new Variant.Extraction<>(bytes, true), a.get(10).extractByteVector());
    assertEquals(bvl, a.get(11).toByteVectorList());
    assertEquals(new Variant.Extraction<>(bvl, true), a.get(11).extractByteVectorList());
    assertEquals(vl, a.get(12).toList());
    assertEquals(new Variant.Extraction<>(vl, true), a.get(12).extractList());
    assertEquals(vm, a.get(13).toMap());
    assertEquals(new Variant.Extraction<>(new TreeMap<>(vm), true), a.get(13).extractMap());
    assertEquals(0, a.get(1).toInt());
    assertFalse(a.get(1).extractInt().ok());
    assertEquals(0, a.get(2).toUnsignedInt());
    assertFalse(a.get(2).extractUnsignedInt().ok());
    assertEquals(0, a.get(3).toLong());
    assertFalse(a.get(3).extractLong().ok());
    assertEquals(0, a.get(4).toUnsignedLong());
    assertFalse(a.get(4).extractUnsignedLong().ok());
    assertEquals(0, a.get(5).toDouble());
    assertFalse(a.get(5).extractDouble().ok());
    assertEquals("", a.get(6).toText());
    assertFalse(a.get(6).extractString().ok());
    assertEquals(List.of(), a.get(7).toStringList());
    assertFalse(a.get(7).extractStringList().ok());
    assertEquals(new ByteVector(), a.get(9).toByteVector());
    assertFalse(a.get(9).extractByteVector().ok());
    assertTrue(a.get(10).toByteVectorList().isEmpty());
    assertFalse(a.get(10).extractByteVectorList().ok());
    assertEquals(List.of(), a.get(11).toList());
    assertFalse(a.get(11).extractList().ok());
    assertEquals(Map.of(), a.get(12).toMap());
    assertFalse(a.get(12).extractMap().ok());
    assertFalse(a.get(13).toBoolean());
    assertFalse(a.get(13).extractBoolean().ok());
    var u = a.get(3);
    assertEquals(u, u);
    assertEquals(Variant.fromUnsignedInt(5), u);
    assertNotEquals(Variant.fromUnsignedInt(6), u);
    assertNotEquals(new Variant(5), u);
    assertNotEquals(a.get(2), u);
    var copy = new Variant(u);
    assertNotSame(u, copy);
    assertEquals(u, copy);
    assertEquals(5, u.toUnsignedInt());
    assertEquals(5, copy.toUnsignedInt());
  }

  @Test
  void booleanExtractionDistinguishesFalseFromAnotherType() {
    var value = new Variant(false);
    assertFalse(value.toBoolean());
    assertEquals(new Variant.Extraction<>(false, true), value.extractBoolean());
    var number = new Variant(1);
    assertFalse(number.toBoolean());
    assertEquals(new Variant.Extraction<>(false, false), number.extractBoolean());
  }

  @Test
  @SuppressWarnings("EqualsWithItself")
    // Directly checks the previously broken NaN reflexivity.
  void floatingPointValuesHaveJavaEqualityAndWorkAsCollectionKeys() {
    var nan = new Variant(Double.NaN);
    var anotherNan = new Variant(Double.longBitsToDouble(0xfff8000000000001L));
    assertTrue(nan.equals(nan));
    assertEquals(nan, anotherNan);
    assertEquals(nan.hashCode(), anotherNan.hashCode());
    var values = new HashSet<>(List.of(nan, anotherNan, new Variant(0.0), new Variant(-0.0)));
    assertEquals(3, values.size());
    assertTrue(values.contains(new Variant(Double.NaN)));
    assertNotEquals(new Variant(0.0), new Variant(-0.0));
    var nested = Variant.fromMap(Map.of("values", Variant.fromList(List.of(nan))));
    var equivalent = Variant.fromMap(Map.of("values", Variant.fromList(List.of(anotherNan))));
    assertEquals(nested, equivalent);
    assertEquals(nested.hashCode(), equivalent.hashCode());
    var map = new HashMap<Variant, String>();
    map.put(nested, "found");
    assertEquals("found", map.get(equivalent));
    assertNotEquals(Variant.fromList(List.of(new Variant(0.0))),
      Variant.fromList(List.of(new Variant(-0.0))));
  }

  @Test
  void diagnosticsAndExactStringExtractionAreSeparate() {
    assertEquals("42", new Variant(42).toString());
    assertEquals("", new Variant(42).toText());
    assertEquals(new Variant.Extraction<>("", false), new Variant(42).extractString());
    assertEquals("text", new Variant("text").toText());
    assertEquals("text", new Variant("text").toString());
    assertEquals("null", new Variant().toString());
    assertEquals("true", new Variant(true).toString());
    assertEquals("NaN", new Variant(Double.NaN).toString());
    assertEquals("-0.0", new Variant(-0.0).toString());
    assertEquals("1.23456789", new Variant(1.23456789).toString());
    assertEquals("18446744073709551615", Variant.fromUnsignedLong(-1).toString());
    assertEquals("0x0100ff", Variant.fromByteArray(new byte[]{1, 0, (byte) 255}).toString());
    assertEquals("[1, text]", Variant.fromList(List.of(new Variant(1), new Variant("text"))).toString());
    assertEquals("{value=42}", Variant.fromMap(Map.of("value", new Variant(42))).toString());
  }

  @Test
  void testVariantToOStream() {
    var m = Map.of("strlist", Variant.fromStringList(List.of("first", "second")), "varlist", Variant.fromList(List.of(new Variant(), Variant.fromUnsignedInt(1), new Variant(-10L), new Variant(4.32), new Variant(false))), "data", new Variant(new ByteVector(new byte[]{(byte) 0xa9, 1, 127})));
    assertEquals("{\"data\": \"\\xa9\\x01\\x7f\", \"strlist\": [\"first\", \"second\"], \"varlist\": [null, 1, -10, 4.32, false]}", Variant.fromMap(m).render());
  }
}
