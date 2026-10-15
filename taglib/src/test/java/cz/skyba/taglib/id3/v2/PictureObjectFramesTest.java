package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.internal.TextBytes;
import cz.skyba.taglib.toolkit.ByteVector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;

import static java.nio.charset.StandardCharsets.*;
import static org.junit.jupiter.api.Assertions.*;

class PictureObjectFramesTest {
  static ByteVector bytes(String hex) {
    return new ByteVector(HexFormat.of().parseHex(hex));
  }

  static ByteVector wire(String id, String hex) {
    var body = bytes(hex);
    int n = body.size();
    return new ByteVector(id).append(new ByteVector(new byte[]{(byte) (n >>> 21 & 127), (byte) (n >>> 14 & 127), (byte) (n >>> 7 & 127), (byte) (n & 127), 0, 0})).append(body);
  }

  @AfterEach
  void restoreDecoder() {
    Id3Text.setHandler(null);
  }

  @Test
  void pictureDefaultsRenderFourFieldsDespiteFiveByteParseMinimum() {
    var p = new AttachedPictureFrame();
    assertEquals(ISO_8859_1, p.textEncoding());
    assertEquals(AttachedPictureFrame.Type.Other, p.type());
    assertEquals(wire("APIC", "00000000"), p.render());
    assertEquals(List.of("", ""), p.toStringList());
    assertEquals("[]", p.toString());
  }

  @Test
  void shortPictureBodyRetainsEveryOldField() {
    var p = new AttachedPictureFrame();
    p.setMimeType("old");
    p.setDescription("old-desc");
    p.setType(AttachedPictureFrame.Type.BackCover);
    p.setPicture(new ByteVector("old-body"));
    p.setTextEncoding(UTF_16);
    p.setData(wire("APIC", "03616263"));
    assertEquals(UTF_16, p.textEncoding());
    assertEquals("old", p.mimeType());
    assertEquals("old-desc", p.description());
    assertEquals(AttachedPictureFrame.Type.BackCover, p.type());
    assertEquals(new ByteVector("old-body"), p.picture());
  }

  @Test
  void pictureMimeAndEncodingChangeBeforeTruncatedTypeOrDescription() {
    var p = new AttachedPictureFrame();
    p.setDescription("old-desc");
    p.setType(AttachedPictureFrame.Type.BackCover);
    p.setPicture(new ByteVector("old-body"));
    p.setData(wire("APIC", "0361626300"));
    assertEquals(UTF_8, p.textEncoding());
    assertEquals("abc", p.mimeType());
    assertEquals("old-desc", p.description());
    assertEquals(AttachedPictureFrame.Type.BackCover, p.type());
    assertEquals(new ByteVector("old-body"), p.picture());
  }

  @Test
  void missingPictureMimeDelimiterLeavesPositionForTypeAndPayload() {
    var p = new AttachedPictureFrame(wire("APIC", "0061626364"));
    assertEquals("", p.mimeType());
    assertEquals(AttachedPictureFrame.Type.Other, p.type());
    assertEquals("", p.description());
    assertEquals(new ByteVector("bcd"), p.picture());
  }

  @Test
  void missingPictureDescriptionDelimiterRetainsItInPicturePayload() {
    var p = new AttachedPictureFrame(wire("APIC", "006d000364657363"));
    assertEquals("m", p.mimeType());
    assertEquals(AttachedPictureFrame.Type.FrontCover, p.type());
    assertEquals("", p.description());
    assertEquals(new ByteVector("desc"), p.picture());
  }

  @Test
  void unrecognizedPictureTypeBytesClampThroughNativeHelper() {
    var p = new AttachedPictureFrame(wire("APIC", "006d00ff6400ff"));
    assertEquals(AttachedPictureFrame.Type.Other, p.type());
    assertEquals("d", p.description());
    assertEquals(bytes("ff"), p.picture());
    assertEquals(AttachedPictureFrame.Type.FrontCover, AttachedPictureFrame.typeFromUInt(0x1_0000_0003L));
    assertEquals(21, AttachedPictureFrame.Type.values().length);
    assertEquals(AttachedPictureFrame.Type.Other, AttachedPictureFrame.typeFromUInt(31));
    assertEquals(AttachedPictureFrame.Type.Other, AttachedPictureFrame.typeFromByte((byte) 255));
    assertEquals(AttachedPictureFrame.Type.Other, AttachedPictureFrame.typeFromString("front cover"));
    assertEquals("Front Cover", AttachedPictureFrame.typeToString(AttachedPictureFrame.Type.FrontCover));
  }

  @Test
  void utf16LittleEndianPictureDescriptionAndDetachedPayload() {
    var original = wire("APIC", "046d00034100000000ff");
    var p = new AttachedPictureFrame(original);
    p.picture().clear();
    original.clear();
    assertEquals(UTF_16LE, p.textEncoding());
    assertEquals("A", p.description());
    assertEquals(bytes("00ff"), p.picture());
    p.toStringList().set(0, "detached");
    assertEquals(List.of("A", "m"), p.toStringList());
  }

  @Test
  void picturePayloadSetterCopiesAndHeaderRemainsLiveAcrossRendering() {
    var p = new AttachedPictureFrame();
    var payload = bytes("000102ff00");
    p.setPicture(payload);
    payload.clear();
    var h = p.header();
    p.setDescription("Ž");
    var rendered = p.render();
    assertSame(h, p.header());
    assertEquals(ISO_8859_1, p.textEncoding());
    var parsed = new AttachedPictureFrame(rendered);
    assertEquals("Ž", parsed.description());
    assertEquals(bytes("000102ff00"), parsed.picture());
    assertEquals(List.of("APIC"), p.asProperties().unsupportedData());
  }

  @Test
  void pictureStringConcatenationTruncatesCopiedOperandsWithoutChangingFields() {
    var p = new AttachedPictureFrame();
    p.setMimeType("a\0b");
    p.setDescription("c\0d");
    assertEquals("c [a]", p.toString());
    assertEquals(List.of("c\0d", "a\0b"), p.toStringList());
    p.setDescription("\0description");
    assertEquals(" [a]", p.toString());
  }

  @Test
  void pictureHandlerFailurePreservesEarlierChangesAndOldPayload() {
    int[] calls = {0};
    Id3Text.setHandler(new Latin1StringHandler() {
      public String parse(ByteVector data) {
        if (++calls[0] == 2) throw new IllegalArgumentException("second");
        return "decoded:" + TextBytes.decodeTerminated(data.data(), ISO_8859_1);
      }
    });
    var p = new AttachedPictureFrame();
    p.setDescription("old-desc");
    p.setPicture(new ByteVector("old-body"));
    assertThrows(IllegalArgumentException.class, () -> p.setData(wire("APIC", "006d0003646573630078")));
    assertEquals("decoded:m", p.mimeType());
    assertEquals(AttachedPictureFrame.Type.FrontCover, p.type());
    assertEquals("old-desc", p.description());
    assertEquals(new ByteVector("old-body"), p.picture());
  }

  @Test
  void legacyConstructorReplacesHeaderWhileKeepingOldDeclaredSize() {
    var data = bytes("504943000008004a504701640000");
    var old = new Frame.Header(data, 2);
    old.setTagAlterPreservation(true);
    var p = new AttachedPictureFrameV22(data, old);
    assertNotSame(old, p.header());
    assertEquals(4, p.header().version());
    assertEquals(8, p.size());
    assertFalse(p.header().tagAlterPreservation());
    assertEquals(new ByteVector("APIC"), p.frameID());
    assertEquals("image/jpeg", p.mimeType());
    assertEquals(AttachedPictureFrame.Type.FileIcon, p.type());
    assertEquals("d", p.description());
    assertEquals(bytes("00"), p.picture());
    p.render();
    assertEquals(16, p.size());
    assertEquals(8, old.frameSize());
  }

  @Test
  void legacyFixedFormatIsCaseInsensitiveAndBypassesGlobalDecoder() {
    Id3Text.setHandler(new Latin1StringHandler() {
      public String parse(ByteVector data) {
        return "callback";
      }
    });
    var data = bytes("50494300000800706e6701640000");
    var p = new AttachedPictureFrameV22(data, new Frame.Header(data, 2));
    assertEquals("image/png", p.mimeType());
    assertEquals("callback", p.description());
  }

  @Test
  void legacySubtypeStillUsesLegacyFieldsOnLaterWholeFrameReparse() {
    var data = bytes("504943000008004a504701640000");
    var p = new AttachedPictureFrameV22(data, new Frame.Header(data, 2));
    var h = p.header();
    p.setData(p.render());
    assertSame(h, p.header());
    assertEquals(4, h.version());
    assertEquals("image/ima", p.mimeType());
    assertEquals(AttachedPictureFrame.Type.Other, p.type());
    assertEquals("e/jpeg", p.description());
    assertEquals(bytes("01640000"), p.picture());
  }

  @Test
  void objectDefaultAndMissingDelimitersPreserveNativePosition() {
    var o = new GeneralEncapsulatedObjectFrame();
    assertEquals(wire("GEOB", "00000000"), o.render());
    o.setData(wire("GEOB", "00616263"));
    assertEquals("", o.mimeType());
    assertEquals("", o.fileName());
    assertEquals("", o.description());
    assertEquals(new ByteVector("abc"), o.object());
  }

  @Test
  void shortObjectPayloadRetainsRequestedEncodingAndOldFields() {
    var o = new GeneralEncapsulatedObjectFrame();
    o.setMimeType("old");
    o.setFileName("name");
    o.setDescription("desc");
    o.setObject(new ByteVector("old-body"));
    o.setTextEncoding(UTF_8);
    o.setData(wire("GEOB", "000102"));
    assertEquals(UTF_8, o.textEncoding());
    assertEquals("old", o.mimeType());
    assertEquals("name", o.fileName());
    assertEquals("desc", o.description());
    assertEquals(new ByteVector("old-body"), o.object());
  }

  @Test
  void objectSecondCallbackFailureKeepsOldFilenameDescriptionAndBody() {
    int[] calls = {0};
    Id3Text.setHandler(new Latin1StringHandler() {
      public String parse(ByteVector data) {
        if (++calls[0] == 2) throw new IllegalArgumentException("second");
        return "decoded:" + TextBytes.decodeTerminated(data.data(), ISO_8859_1);
      }
    });
    var o = new GeneralEncapsulatedObjectFrame();
    o.setFileName("old-name");
    o.setDescription("old-desc");
    o.setObject(new ByteVector("old-body"));
    assertThrows(IllegalArgumentException.class, () -> o.setData(wire("GEOB", "006d006600640078")));
    assertEquals("decoded:m", o.mimeType());
    assertEquals("old-name", o.fileName());
    assertEquals("old-desc", o.description());
    assertEquals(new ByteVector("old-body"), o.object());
  }

  @Test
  void objectThirdCallbackFailureRetainsOldDescriptionAndBody() {
    int[] calls = {0};
    Id3Text.setHandler(new Latin1StringHandler() {
      public String parse(ByteVector data) {
        if (++calls[0] == 3) throw new IllegalArgumentException("third");
        return "decoded:" + TextBytes.decodeTerminated(data.data(), ISO_8859_1);
      }
    });
    var o = new GeneralEncapsulatedObjectFrame();
    o.setDescription("old-desc");
    o.setObject(new ByteVector("old-body"));
    assertThrows(IllegalArgumentException.class, () -> o.setData(wire("GEOB", "006d006600640078")));
    assertEquals("decoded:m", o.mimeType());
    assertEquals("decoded:f", o.fileName());
    assertEquals("old-desc", o.description());
    assertEquals(new ByteVector("old-body"), o.object());
  }

  @Test
  void objectMimetypeAndLatinTextUseTheSameGlobalHandler() {
    Id3Text.setHandler(new Latin1StringHandler() {
      public String parse(ByteVector data) {
        return TextBytes.decodeTerminated(data.data(), UTF_8);
      }
    });
    var o = new GeneralEncapsulatedObjectFrame(wire("GEOB", "00c3a900c3a900c3a90000ff"));
    assertEquals("é", o.mimeType());
    assertEquals("é", o.fileName());
    assertEquals("é", o.description());
    assertEquals(bytes("00ff"), o.object());
  }

  @Test
  void objectStringKeepsUniqueFilenameButTruncatesCopiedMimeAndDescription() {
    var o = new GeneralEncapsulatedObjectFrame();
    o.setMimeType("a\0b");
    o.setFileName("c\0d");
    o.setDescription("e\0f");
    assertEquals("[a] c\0d \"e\"", o.toString());
    assertEquals(List.of("e\0f", "c\0d", "a\0b"), o.toStringList());
  }

  @Test
  void objectEncodingFallbackUsesBothStringsAndCopiesPayload() {
    var o = new GeneralEncapsulatedObjectFrame();
    o.setFileName("雪");
    o.setDescription("desc");
    var data = bytes("000102ff00");
    o.setObject(data);
    data.clear();
    o.object().clear();
    var h = o.header();
    var rendered = o.render();
    assertSame(h, o.header());
    assertEquals(ISO_8859_1, o.textEncoding());
    var parsed = new GeneralEncapsulatedObjectFrame(rendered);
    assertEquals("雪", parsed.fileName());
    assertEquals("desc", parsed.description());
    assertEquals(bytes("000102ff00"), parsed.object());
    assertEquals(List.of("GEOB"), o.asProperties().unsupportedData());
  }

  @Test
  void objectDecoderReceivesDetachedBytes() {
    Id3Text.setHandler(new Latin1StringHandler() {
      public String parse(ByteVector data) {
        var result = data.toString();
        data.clear();
        return result;
      }
    });
    var data = wire("GEOB", "006d006600640078");
    var copy = new ByteVector(data);
    var o = new GeneralEncapsulatedObjectFrame(data);
    assertEquals(copy, data);
    assertEquals("m", o.mimeType());
    assertEquals("f", o.fileName());
    assertEquals("d", o.description());
    assertEquals(new ByteVector("x"), o.object());
  }

  @Test
  void constructorsAvoidFurtherSubclassDispatchButReparseUsesIt() {
    class Picture extends AttachedPictureFrame {
      boolean ready;
      int calls;

      Picture(ByteVector data) {
        super(data);
        ready = true;
      }

      protected void parseFields(ByteVector data) {
        assertTrue(ready);
        calls++;
        super.parseFields(data);
      }
    }
    var aData = wire("APIC", "006d0003640078");
    var a = new Picture(aData);
    assertEquals(0, a.calls);
    a.setData(aData);
    assertEquals(1, a.calls);
    class ObjectFrame extends GeneralEncapsulatedObjectFrame {
      boolean ready;
      int calls;

      ObjectFrame(ByteVector data) {
        super(data);
        ready = true;
      }

      protected void parseFields(ByteVector data) {
        assertTrue(ready);
        calls++;
        super.parseFields(data);
      }
    }
    var oData = wire("GEOB", "006d006600640078");
    var o = new ObjectFrame(oData);
    assertEquals(0, o.calls);
    o.setData(oData);
    assertEquals(1, o.calls);
  }

  @Test
  void upstreamParseApic() {
    var p = new AttachedPictureFrame(bytes("41504943000000070000006d0001640000"));
    assertEquals("m", p.mimeType());
    assertEquals(AttachedPictureFrame.Type.FileIcon, p.type());
    assertEquals("d", p.description());
  }

  @Test
  void upstreamParseApicUtf16Bom() {
    var p = new AttachedPictureFrame(bytes("4150494300000026000001696d6167652f6a7065670000feff0063006f007600650072002e006a007000670000ffd8ff"));
    assertEquals("image/jpeg", p.mimeType());
    assertEquals(AttachedPictureFrame.Type.Other, p.type());
    assertEquals("cover.jpg", p.description());
    assertEquals(bytes("ffd8ff"), p.picture());
  }

  @Test
  void upstreamRenderApic() {
    var p = new AttachedPictureFrame();
    p.setTextEncoding(UTF_8);
    p.setMimeType("image/png");
    p.setType(AttachedPictureFrame.Type.BackCover);
    p.setDescription("Description");
    p.setPicture(new ByteVector("PNG data"));
    assertEquals(bytes("4150494300000020000003696d6167652f706e6700044465736372697074696f6e00504e472064617461"), p.render());
  }

  @Test
  void upstreamParseGeob() {
    var o = new GeneralEncapsulatedObjectFrame(bytes("47454f42000000080000006d006600640000"));
    assertEquals("m", o.mimeType());
    assertEquals("f", o.fileName());
    assertEquals("d", o.description());
  }

  @Test
  void upstreamRenderGeob() {
    var o = new GeneralEncapsulatedObjectFrame();
    o.setTextEncoding(ISO_8859_1);
    o.setMimeType("application/octet-stream");
    o.setFileName("test.bin");
    o.setDescription("Description");
    o.setObject(bytes("010101"));
    assertEquals(bytes("47454f42000000320000006170706c69636174696f6e2f6f637465742d73747265616d00746573742e62696e004465736372697074696f6e00010101"), o.render());
  }
}
