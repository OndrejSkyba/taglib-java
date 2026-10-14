package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.MutablePropertyMap;
import cz.skyba.taglib.toolkit.TagString;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FrameBaseTest {
  static ByteVector bytes(String hex) {
    return new ByteVector(HexFormat.of().parseHex(hex));
  }

  static class Raw extends Frame {
    ByteVector fields;
    int parses, renders;

    Raw(ByteVector data, long version) {
      super(new Header(data, version));
      parseFields(fieldData(data));
    }

    Raw(ByteVector data) {
      super(data);
      fields = new ByteVector();
    }

    public String toString() {
      return "custom";
    }

    protected void parseFields(ByteVector value) {
      fields = new ByteVector(value);
      parses++;
    }

    protected ByteVector renderFields() {
      renders++;
      return new ByteVector(fields);
    }

    ByteVector extract(ByteVector data) {
      return fieldData(data);
    }

    void replace(Header value) {
      setHeader(value);
    }

    String string(ByteVector data, TagString.Type type, int[] pos) {
      return readStringField(data, type, pos);
    }

    TagString.Type encoding(List<String> fields, TagString.Type type) {
      return checkTextEncoding(fields, type);
    }

    PropertyMap properties() {
      return asProperties();
    }

    static void split(MutablePropertyMap a, MutablePropertyMap b, MutablePropertyMap c, MutablePropertyMap d) {
      splitProperties(a, b, c, d);
    }
  }

  static class UnknownChild extends UnknownFrame {
    UnknownChild(ByteVector data) {
      super(data);
    }

    PropertyMap properties() {
      return asProperties();
    }
  }

  @Test
  void constructorDoesNotCallSubclassParserBeforeItsStateExists() {
    var raw = new Raw(new ByteVector("X001"));
    assertEquals(0, raw.parses);
    assertEquals(new ByteVector("X001"), raw.frameID());
    assertEquals(0, raw.size());
  }

  @Test
  void shortHeaderRetainsIdSizeAndFlagsButChangesVersion() {
    var h = new Frame.Header(bytes("4142313200000003ff7f"));
    h.setData(new ByteVector("AB"), 2);
    assertEquals(2, h.version());
    assertEquals(new ByteVector("AB12"), h.frameID());
    assertEquals(3, h.frameSize());
    assertTrue(h.tagAlterPreservation());
    assertTrue(h.dataLengthIndicator());
    h.setData(new ByteVector("XYZ"), 2);
    assertEquals(new ByteVector("XYZ"), h.frameID());
    assertEquals(0, h.frameSize());
    assertTrue(h.dataLengthIndicator());
  }

  @Test
  void v3ReparseLeavesPreviouslySetV4OnlyFlags() {
    var h = new Frame.Header(bytes("58303031000000030003"));
    h.setData(bytes("58303031000000050000"), 3);
    assertTrue(h.unsynchronisation());
    assertTrue(h.dataLengthIndicator());
    assertFalse(h.compression());
    assertEquals(5, h.frameSize());
  }

  @Test
  void allV3FlagsUseNativeBits() {
    var h = new Frame.Header(bytes("5830303100000003e0e0"), 3);
    assertTrue(h.tagAlterPreservation());
    assertTrue(h.fileAlterPreservation());
    assertTrue(h.readOnly());
    assertTrue(h.groupingIdentity());
    assertTrue(h.compression());
    assertTrue(h.encryption());
    assertFalse(h.unsynchronisation());
    assertFalse(h.dataLengthIndicator());
  }

  @Test
  void headerRendersZeroFlagsWithoutClearingStoredFlags() {
    var h = new Frame.Header(bytes("5830303100000003704f"));
    assertEquals(bytes("58303031000000030000"), h.render());
    assertTrue(h.tagAlterPreservation());
    assertTrue(h.encryption());
    assertTrue(h.unsynchronisation());
  }

  @Test
  void v2HeaderReportsSixBytesButRendersNativeFourByteSizeAndTwoFlags() {
    var h = new Frame.Header(bytes("414243010203"), 2);
    assertEquals(6, h.size());
    assertEquals(0x010203, h.frameSize());
    assertEquals(bytes("414243000404030000"), h.render());
  }

  @Test
  void settersNarrowUintAndCopyIds() {
    var h = new Frame.Header(new ByteVector());
    var id = new ByteVector("ABCDE");
    h.setFrameID(id);
    id.set(0, (byte) 'X');
    h.frameID().set(0, (byte) 'Y');
    assertEquals(new ByteVector("ABCD"), h.frameID());
    h.setFrameSize(-1);
    h.setVersion(0x1_0000_0003L);
    assertEquals(0xffff_ffffL, h.frameSize());
    assertEquals(3, h.version());
    assertEquals(bytes("41424344ffffffff0000"), h.render());
  }

  @Test
  void iTunesSizeFallbackRequiresAValidNextFrameAtOrdinarySize() {
    var data = bytes("58303031000001000000").append(new ByteVector(256)).append(new ByteVector("NEXT"));
    assertEquals(256, new Frame.Header(data).frameSize());
    data.set(266, (byte) '?');
    assertEquals(128, new Frame.Header(data).frameSize());
    data.set(138, (byte) 'N');
    data.set(139, (byte) 'E');
    data.set(140, (byte) 'X');
    data.set(141, (byte) 'T');
    data.set(266, (byte) 'N');
    assertEquals(128, new Frame.Header(data).frameSize());
  }

  @Test
  void v3UsesOrdinaryIntegerWhileUnknownVersionsUseV4Rules() {
    var data = bytes("58303031000001000000");
    assertEquals(256, new Frame.Header(data, 3).frameSize());
    assertEquals(128, new Frame.Header(data, 5).frameSize());
    assertEquals(10, new Frame.Header(data, -1).size());
  }

  @Test
  void renderUsesVirtualFieldsAndUpdatesSameLiveHeader() {
    var raw = new Raw(bytes("58303031000000030000616263"), 4);
    var live = raw.header();
    raw.fields = new ByteVector("longer");
    assertEquals(3, raw.size());
    assertEquals(bytes("583030310000000600006c6f6e676572"), raw.render());
    assertSame(live, raw.header());
    assertEquals(6, raw.size());
    assertEquals(1, raw.renders);
    raw.setText("ignored");
    assertEquals("custom", raw.toString());
    assertEquals(List.of("custom"), raw.toStringList());
    var detached = raw.toStringList();
    detached.set(0, "changed");
    detached.add("added");
    assertEquals(List.of("changed", "added"), detached);
    assertEquals(List.of("custom"), raw.toStringList());
  }

  @Test
  void setDataReusesHeaderAndResetsParsingVersionToFour() {
    var raw = new Raw(bytes("58303031000001000000"), 3);
    var live = raw.header();
    raw.setData(bytes("4e455854000001000000"));
    assertSame(live, raw.header());
    assertEquals(4, live.version());
    assertEquals(128, live.frameSize());
    assertEquals(2, raw.parses);
    assertEquals(new ByteVector("NEXT"), raw.frameID());
  }

  @Test
  void nullHeaderCanBeRestoredAndReplacementIsLive() {
    var raw = new Raw(new ByteVector("X001"), 4);
    raw.replace(null);
    assertEquals(new ByteVector(), raw.frameID());
    assertEquals(0, raw.size());
    raw.setData(new ByteVector("NEW1"));
    assertEquals(new ByteVector("NEW1"), raw.frameID());
    var old = raw.header();
    var h = new Frame.Header(new ByteVector("NEW2"));
    raw.replace(h);
    h.setFrameSize(77);
    assertSame(h, raw.header());
    assertEquals(77, raw.size());
    assertEquals(new ByteVector("NEW1"), old.frameID());
  }

  @Test
  void fieldExtractionClampsAvailableUnsynchronisedData() {
    var data = bytes("583030310000000a0002616263");
    var raw = new Raw(data, 4);
    assertEquals(new ByteVector("abc"), raw.fields);
    assertEquals(data, bytes("583030310000000a0002616263"));
  }

  @Test
  void dataLengthIndicatorConsumesFourBytesAndUsesItsDeclaredLength() {
    var raw = new Raw(bytes("583030310000000a0001000000026162635441494c"), 4);
    assertEquals(new ByteVector("ab"), raw.fields);
    var shortData = new Raw(bytes("583030310000000400010001"), 4);
    assertTrue(shortData.fields.isEmpty());
  }

  @Test
  void compressedDataUsesSourceLimitsAndTruncatesToDeclaredOutput() {
    // zlib stream for abcdef; declaration 3 truncates the decompressed bytes.
    var raw = new Raw(bytes("5830303100000012000800000003789c4b4c4a4e494d0300081e0256"), 4);
    assertEquals(new ByteVector("abc"), raw.fields);
    raw.setData(bytes("5830303100000012000800000006789c4b4c4a4e494d0300081e0256"));
    assertEquals(new ByteVector("abcdef"), raw.fields);
    raw.setData(bytes("5830303100000012000800200000789c4b4c4a4e494d0300081e0256"));
    assertTrue(raw.fields.isEmpty()); // Output declaration exceeds 64:1 input ratio.
    raw.setData(bytes("5830303100000012000804000001789c4b4c4a4e494d0300081e0256"));
    assertTrue(raw.fields.isEmpty()); // More than 64 MiB.
  }

  @Test
  void encryptionKeepsCompressedBytesAndInvalidCompressionReturnsEmpty() {
    var encrypted = new Raw(bytes("583030310000000a000c0000000362726f6b656e"), 4);
    assertEquals(new ByteVector("bro"), encrypted.fields);
    var invalid = new Raw(bytes("583030310000000a00080000000362726f6b656e"), 4);
    assertTrue(invalid.fields.isEmpty());
  }

  @Test
  void stringFieldRequiresDelimiterAndRetainsPositionWhenAbsent() {
    var raw = new Raw(new ByteVector("X001"), 4);
    int[] pos = {0};
    assertEquals("a", raw.string(bytes("61006200"), TagString.Type.LATIN1, pos));
    assertEquals(2, pos[0]);
    assertEquals("b", raw.string(bytes("61006200"), TagString.Type.LATIN1, pos));
    assertEquals(4, pos[0]);
    pos[0] = 1;
    assertEquals("", raw.string(new ByteVector("abc"), TagString.Type.LATIN1, pos));
    assertEquals(1, pos[0]);
    assertThrows(IllegalArgumentException.class, () -> raw.string(new ByteVector(), TagString.Type.UTF8, new int[0]));
  }

  @Test
  void utf16DelimiterSearchAlignsFromRequestedPosition() {
    var raw = new Raw(new ByteVector("X001"), 4);
    int[] pos = {1};
    assertEquals("A", raw.string(bytes("99fffe41000000"), TagString.Type.UTF16, pos));
    assertEquals(7, pos[0]);
    assertEquals(bytes("0000"), Frame.textDelimiter(TagString.Type.UTF16LE));
  }

  @Test
  void encodingFallbackDependsOnHeaderVersionAndAllFields() {
    var raw = new Raw(new ByteVector("X001"), 4);
    assertEquals(TagString.Type.UTF8, raw.encoding(List.of("latin", "Ž"), TagString.Type.LATIN1));
    assertEquals(TagString.Type.LATIN1, raw.encoding(List.of("café"), TagString.Type.LATIN1));
    raw.header().setVersion(3);
    assertEquals(TagString.Type.UTF16, raw.encoding(List.of("Ž"), TagString.Type.LATIN1));
    assertEquals(TagString.Type.UTF16, raw.encoding(List.of(), TagString.Type.UTF8));
    assertEquals(TagString.Type.UTF16, raw.encoding(List.of(), TagString.Type.UTF16BE));
    assertEquals(TagString.Type.UTF16LE, raw.encoding(List.of(), TagString.Type.UTF16LE));
  }

  @Test
  void propertyMappingsKeepNativeDeprecatedAndUnmappedIdsDistinct() {
    assertEquals("DATE", Frame.frameIDToKey(new ByteVector("TDAT")));
    assertEquals("", Frame.frameIDToKey(new ByteVector("TORY")));
    assertEquals("TAGGINGDATE", Frame.frameIDToKey(new ByteVector("TDTG")));
    assertEquals(new ByteVector("TDRC"), Frame.keyToFrameID("date"));
    assertEquals(new ByteVector("WFED"), Frame.keyToFrameID("podcasturl"));
    assertEquals(new ByteVector(), Frame.keyToFrameID("títle"));
  }

  @Test
  void splitKeepsEmptyEntriesAndClearsStaleOutputIdentifiers() {
    var original = new MutablePropertyMap();
    original.add("DJMIXER", List.of());
    original.add("DJ-MIX", List.of("other"));
    original.add("PERFORMER:guitar", List.of("person"));
    original.addUnsupportedData("discarded");
    var single = new MutablePropertyMap();
    var tipl = new MutablePropertyMap();
    var tmcl = new MutablePropertyMap();
    single.addUnsupportedData("retained");
    tipl.add("OLD", List.of("old"));
    tipl.addUnsupportedData("retained");
    tmcl.addUnsupportedData("retained");
    Raw.split(original, single, tipl, tmcl);
    assertTrue(single.unsupportedData().isEmpty());
    assertTrue(tipl.unsupportedData().isEmpty());
    assertTrue(tmcl.unsupportedData().isEmpty());
    assertFalse(tipl.containsKey("OLD"));
    assertTrue(tipl.containsKey("DJMIXER"));
    assertTrue(tipl.values("DJMIXER").isEmpty());
    assertEquals(List.of("other"), single.values("DJ-MIX"));
    assertEquals(List.of("person"), tmcl.values("PERFORMER:GUITAR"));
  }

  @Test
  void unknownFrameRetainsFieldsWithDetachedReturnedBytesAndUnsupportedPrefix() {
    var input = bytes("58595a31000000030000616263");
    var unknown = new UnknownChild(input);
    input.set(10, (byte) 'X');
    unknown.data().set(0, (byte) 'Y');
    assertEquals(new ByteVector("abc"), unknown.data());
    assertEquals("", unknown.toString());
    assertEquals(List.of("UNKNOWN/XYZ1"), unknown.properties().unsupportedData());
    assertEquals(bytes("58595a31000000030000616263"), unknown.render());
    unknown.setData(bytes("414243440000000100007a"));
    assertEquals(new ByteVector("z"), unknown.data());
    assertEquals(List.of("UNKNOWN/ABCD"), unknown.properties().unsupportedData());
  }

  @Test
  void unknownConstructionDoesNotDispatchDerivedParser() {
    class Child extends UnknownFrame {
      boolean ready;
      int calls;

      Child() {
        super(bytes("58595a3100000001000061"));
        ready = true;
      }

      protected void parseFields(ByteVector v) {
        assertTrue(ready);
        calls++;
        super.parseFields(v);
      }
    }
    var child = new Child();
    assertEquals(0, child.calls);
    assertEquals(new ByteVector("a"), child.data());
    child.setData(bytes("58595a3100000001000062"));
    assertEquals(1, child.calls);
  }

  @Test
  void customVirtualTextListAndPropertiesHooksAreUsableThroughFrame() {
    class Custom extends Raw {
      Custom() {
        super(new ByteVector("X001"), 4);
      }

      public void setText(String v) {
        fields = new TagString(v).data(TagString.Type.UTF8);
      }

      public List<String> toStringList() {
        return List.of("one", "two");
      }

      protected PropertyMap asProperties() {
        return PropertyMap.builder().put("TITLE", "virtual").build();
      }
    }
    var custom = new Custom();
    Frame frame = custom;
    frame.setText("text");
    assertEquals(bytes("5830303100000004000074657874"), frame.render());
    assertEquals(List.of("one", "two"), frame.toStringList());
    assertEquals("virtual", custom.properties().first("TITLE"));
    assertEquals(List.of("X001"), new Raw(new ByteVector("X001"), 4).properties().unsupportedData());
  }
}
