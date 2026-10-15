package cz.skyba.taglib.riff.info;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.internal.TextBytes;
import cz.skyba.taglib.toolkit.ByteVector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.jupiter.api.Assertions.*;

class InfoTagTest {
  @AfterEach
  void resetHandler() {
    Tag.setStringHandler(null);
  }

  static ByteVector id(String value) {
    return new ByteVector(value.getBytes(StandardCharsets.ISO_8859_1));
  }

  static byte[] field(String id, byte[] bytes) {
    var buffer = ByteBuffer.allocate(8 + bytes.length + (bytes.length & 1)).order(ByteOrder.LITTLE_ENDIAN);
    buffer.put(id.getBytes(StandardCharsets.ISO_8859_1)).putInt(bytes.length).put(bytes);
    return buffer.array();
  }

  static ByteVector data(byte[]... fields) {
    var buffer = new ByteArrayOutputStream();
    buffer.writeBytes(new byte[]{'I', 'N', 'F', 'O'});
    for (byte[] field : fields) buffer.writeBytes(field);
    return new ByteVector(buffer.toByteArray());
  }

  @Test
  void upstreamTitleAndRawMapAssertions() {
    var tag = new Tag();
    assertEquals("", tag.title());
    tag.setTitle("Test title 1");
    tag.setFieldText(id("TEST"), "Dummy Text");
    assertEquals("Test title 1", tag.title());
    var map = tag.fieldListMap();
    assertEquals("Test title 1", map.get(id("INAM")));
    assertEquals("Dummy Text", map.get(id("TEST")));
  }

  @Test
  void upstreamNumericFieldAssertions() {
    var tag = new Tag();
    assertEquals(0, tag.track());
    tag.setTrack(1234);
    assertEquals(1234, tag.track());
    assertEquals("1234", tag.fieldText(id("IPRT")));
    assertEquals(0, tag.year());
    tag.setYear(1234);
    assertEquals(1234, tag.year());
    assertEquals("1234", tag.fieldText(id("ICRD")));
  }

  @Test
  void unknownOnlyTagIsNonemptyAndUsesNativeEmptyUnsupportedIdentifier() {
    var tag = new Tag(data(field("ZZZZ", new byte[]{'v', 0}), field("YYYY", new byte[]{'w', 0})));
    assertFalse(tag.isEmpty());
    assertTrue(tag.properties().isEmpty());
    assertEquals(List.of("", ""), tag.properties().unsupportedData());
    tag.removeUnsupportedProperties(tag.properties().unsupportedData());
    assertEquals(2, tag.fieldListMap().size()); // Native empty identifier does not erase a raw field ID.
    tag.removeUnsupportedProperties(List.of("ZZZZ"));
    assertEquals("w", tag.fieldText(id("YYYY")));
    assertEquals(1, tag.fieldListMap().size());
  }

  @Test
  void duplicateEmptyAndInvalidUtf8FieldsFollowDecodedNativeMap() {
    var tag = new Tag(data(field("INAM", new byte[]{'a', 0}), field("INAM", new byte[]{'b', 0}),
      field("IART", new byte[]{0}), field("ZZZZ", new byte[]{(byte) 0xc3, 0})));
    assertEquals("b", tag.title());
    assertEquals(List.of(""), tag.properties().get("ARTIST"));
    assertEquals("", tag.fieldText(id("ZZZZ")));
    assertFalse(tag.isEmpty());
    assertArrayEquals(data(field("INAM", new byte[]{'b', 0})).toByteArray(), tag.render().toByteArray());
  }

  @Test
  void settersValidateIdentifiersAndCopyMapKeys() {
    var tag = new Tag();
    var key = id("ZZZZ");
    tag.setFieldText(key, "opaque");
    key.set(0, (byte) 'A');
    var copy = tag.fieldListMap();
    copy.keySet().iterator().next().set(0, (byte) 'B');
    copy.clear();
    assertEquals("opaque", tag.fieldText(id("ZZZZ")));
    for (String invalid : List.of("ABC", "ABCDE", "\u001fABC", "\u0080ABC")) tag.setFieldText(id(invalid), "bad");
    assertEquals(1, tag.fieldListMap().size());
    tag.setFieldText(id("ZZZZ"), "");
    assertTrue(tag.isEmpty());
  }

  @Test
  void propertyReplacementRetainsUnknownAndPreservesEmptyFirstValues() {
    var tag = new Tag();
    tag.setTitle("old");
    tag.setFieldText(id("ZZZZ"), "unknown");
    var rejected = tag.replaceProperties(PropertyMap.builder().put("TITLE", List.of("", "second"))
      .put("ARTIST", List.of()).put("CUSTOM", "x").build()).rejected();
    assertEquals(List.of(""), tag.properties().get("TITLE"));
    assertEquals("unknown", tag.fieldText(id("ZZZZ")));
    assertEquals(PropertyMap.builder().put("ARTIST", List.of()).put("CUSTOM", "x").build(), rejected);
    assertFalse(tag.isEmpty());
    tag.setTitle("");
    assertEquals(1, tag.fieldListMap().size());
  }

  @Test
  void unsignedNumbersUseNativeSignedNumberRendering() {
    var tag = new Tag();
    tag.setYear(0xffff_ffffL);
    tag.setTrack(0xffff_ffffL);
    assertEquals("-1", tag.fieldText(id("ICRD")));
    assertEquals(0xffff_ffffL, tag.year());
    assertEquals(0xffff_ffffL, tag.track());
    tag.setYear(0);
    tag.setTrack(0);
    assertTrue(tag.isEmpty());
  }

  @Test
  void handlerControlsParsingAndRenderingAndNullRestoresUtf8() {
    Tag.setStringHandler(new StringHandler() {
      @Override
      public String parse(ByteVector data) {
        return TextBytes.decodeTerminated(data.data(), ISO_8859_1);
      }

      @Override
      public ByteVector render(String value) {
        return ByteVector.fromText(value, ISO_8859_1);
      }
    });
    var tag = new Tag(data(field("INAM", new byte[]{(byte) 0xe9, 0})));
    assertEquals("é", tag.title());
    assertArrayEquals(data(field("INAM", new byte[]{(byte) 0xe9, 0})).toByteArray(), tag.render().toByteArray());
    Tag.setStringHandler(null);
    assertArrayEquals(data(field("INAM", new byte[]{(byte) 0xc3, (byte) 0xa9, 0})).toByteArray(), tag.render().toByteArray());
  }

  @Test
  void invalidLengthsStopParsingWithoutAllocationOrLosingEarlierFields() {
    var tag = new Tag(data(field("INAM", new byte[]{'x', 0}), new byte[]{'Z', 'Z', 'Z', 'Z', -1, -1, -1, -1}));
    assertEquals("x", tag.title());
    assertEquals(1, tag.fieldListMap().size());
    assertTrue(new Tag(new ByteVector(new byte[]{'I', 'N', 'F', 'O', 1, 2, 3})).isEmpty());
  }

  @Test
  void partialValidHeadersRetainNativeEmptyFields() {
    for (int tail = 4; tail < 8; tail++) {
      var raw = id("INFOINAM").append(new ByteVector(tail - 4));
      var tag = new Tag(raw);
      assertFalse(tag.isEmpty());
      assertEquals(Map.of(id("INAM"), ""), tag.fieldListMap());
      assertEquals(List.of(""), tag.properties().get("TITLE"));
      assertTrue(tag.render().isEmpty());
    }
  }
}
