package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.toolkit.ByteVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HeaderHelpersTest {
  private static ByteVector header(int version, int revision, int flags, long size) {
    return new ByteVector("xyz").append(new ByteVector(new byte[]{
      (byte) version, (byte) revision, (byte) flags
    })).append(SynchData.fromUnsignedInt(size));
  }

  @Test
  void defaultAndIdentifierHaveIndependentReturnedVectors() {
    var h = new Header();
    assertEquals(4, h.majorVersion());
    assertEquals(0, h.revisionNumber());
    assertEquals(0, h.tagSize());
    assertEquals(10, h.completeTagSize());
    assertEquals(10, Header.size());
    assertEquals(10, Footer.size());
    var id = Header.fileIdentifier();
    id.set(0, (byte) 'X');
    assertEquals(new ByteVector("ID3"), Header.fileIdentifier());
  }

  @Test
  void parsingDoesNotRequireMagicAndUsesSignedCharPromotion() {
    var h = new Header(header(128, 254, 240, 123));
    assertEquals(0xffff_ff80L, h.majorVersion());
    assertEquals(0xffff_fffeL, h.revisionNumber());
    assertTrue(h.unsynchronisation());
    assertTrue(h.extendedHeader());
    assertTrue(h.experimentalIndicator());
    assertTrue(h.footerPresent());
    assertEquals(143, h.completeTagSize());
  }

  @Test
  void shortSetDataRetainsAllPreviouslyParsedFields() {
    var h = new Header(header(3, 7, 240, 99));
    h.setData(header(4, 0, 0, 0).mid(0, 9));
    assertEquals(3, h.majorVersion());
    assertEquals(7, h.revisionNumber());
    assertEquals(99, h.tagSize());
    assertTrue(h.footerPresent());
  }

  @Test
  void invalidSizeAndVersionClearOnlySize() {
    var h = new Header(header(3, 7, 240, 99));
    var invalid = header(4, 0, 0, 8);
    invalid.set(6, (byte) 128);
    h.setData(invalid);
    assertEquals(0, h.tagSize());
    assertEquals(3, h.majorVersion());
    assertEquals(7, h.revisionNumber());
    assertTrue(h.footerPresent());
    h.setTagSize(99);
    h.setData(header(255, 0, 0, 8));
    assertEquals(0, h.tagSize());
    assertEquals(3, h.majorVersion());
    assertTrue(h.extendedHeader());
  }

  @Test
  void renderMutatesFlagsAndKeepsRevisionStateWhileWritingZeroRevision() {
    var h = new Header(header(3, 7, 240, 99));
    var rendered = h.render();
    assertArrayEquals(new byte[]{'I', 'D', '3', 3, 0, 32, 0, 0, 0, 99}, rendered.data());
    assertEquals(7, h.revisionNumber());
    assertTrue(h.experimentalIndicator());
    assertFalse(h.unsynchronisation());
    assertFalse(h.extendedHeader());
    assertFalse(h.footerPresent());
    assertEquals(109, h.completeTagSize());
    assertEquals(rendered, h.render());
  }

  @Test
  void footerRenderAlsoMutatesSuppliedHeader() {
    var h = new Header(header(4, 7, 240, 99));
    assertArrayEquals(new byte[]{'3', 'D', 'I', 4, 0, 32, 0, 0, 0, 99}, new Footer().render(h).data());
    assertFalse(h.footerPresent());
    assertFalse(h.extendedHeader());
    assertEquals(7, h.revisionNumber());
  }

  @Test
  void unsignedSettersAndCompleteSizeWrapBeforeRenderingLow28Bits() {
    var h = new Header();
    h.setTagSize(-1);
    h.setMajorVersion(0x1_0000_0003L);
    assertEquals(3, h.majorVersion());
    assertEquals(0xffff_ffffL, h.tagSize());
    assertEquals(9, h.completeTagSize());
    assertArrayEquals(new byte[]{127, 127, 127, 127}, h.render().mid(6, 4).data());
  }

  @Test
  void malformedShortSynchIntegerPadsOnTheRightAndDoesNotChangeInput() {
    var data = new ByteVector(new byte[]{(byte) 128, 1});
    assertEquals(0x8001_0000L, SynchData.toUnsignedInt(data));
    assertArrayEquals(new byte[]{(byte) 128, 1}, data.data());
    assertEquals(129, SynchData.toUnsignedInt(new ByteVector(new byte[]{1, 1})));
    assertEquals(0x8102_0304L, SynchData.toUnsignedInt(new ByteVector(new byte[]{(byte) 129, 2, 3, 4, 9})));
  }

  @Test
  void synchDecodeDropsEveryFfZeroPairIncludingTerminalPair() {
    var data = new ByteVector(new byte[]{1, (byte) 255, 0, 0, (byte) 255, 0, (byte) 255});
    var decoded = SynchData.decode(data);
    assertArrayEquals(new byte[]{1, (byte) 255, 0, (byte) 255, (byte) 255}, decoded.data());
    decoded.set(0, (byte) 9);
    assertArrayEquals(new byte[]{1, (byte) 255, 0, 0, (byte) 255, 0, (byte) 255}, data.data());
    assertEquals(new ByteVector(), SynchData.decode(new ByteVector()));
    assertEquals(new ByteVector(new byte[]{(byte) 255}), SynchData.decode(new ByteVector(new byte[]{(byte) 255, 0})));
    var twoZeros = SynchData.decode(new ByteVector(new byte[]{(byte) 255, 0, 0}));
    assertEquals(new ByteVector(new byte[]{(byte) 255, 0}), twoZeros);
    assertEquals(new ByteVector(new byte[]{(byte) 255}), SynchData.decode(twoZeros));
  }

  @Test
  void extendedHeaderRetainsNativeAvailablePrefixAndMalformedFallback() {
    var h = new ExtendedHeader();
    assertEquals(0, h.size());
    h.setData(new ByteVector(new byte[]{1, 2}));
    assertEquals(130, h.size());
    h.setData(new ByteVector(new byte[]{(byte) 128, 1}));
    assertEquals(0x8001_0000L, h.size());
    h.setData(new ByteVector());
    assertEquals(0, h.size());
  }

  @Test
  void nativeSynchTest1() {
    var v = new ByteVector(new byte[]{0, 0, 0, 127});
    assertEquals(127, SynchData.toUnsignedInt(v));
    assertEquals(v, SynchData.fromUnsignedInt(127));
  }

  @Test
  void nativeSynchTest2() {
    var v = new ByteVector(new byte[]{0, 0, 1, 0});
    assertEquals(128, SynchData.toUnsignedInt(v));
    assertEquals(v, SynchData.fromUnsignedInt(128));
  }

  @Test
  void nativeSynchTest3() {
    var v = new ByteVector(new byte[]{0, 0, 1, 1});
    assertEquals(129, SynchData.toUnsignedInt(v));
    assertEquals(v, SynchData.fromUnsignedInt(129));
  }

  @Test
  void nativeSynchToUIntBroken() {
    assertEquals(255, SynchData.toUnsignedInt(new ByteVector(new byte[]{0, 0, 0, (byte) 255})));
    assertEquals(65535, SynchData.toUnsignedInt(new ByteVector(new byte[]{0, 0, (byte) 255, (byte) 255})));
  }

  @Test
  void nativeSynchToUIntBrokenAndTooLarge() {
    assertEquals(255, SynchData.toUnsignedInt(new ByteVector(new byte[]{0, 0, 0, (byte) 255, 0})));
  }

  @Test
  void nativeSynchDecode1() {
    var v = SynchData.decode(new ByteVector(new byte[]{(byte) 255, 0, 0}));
    assertEquals(2, v.size());
    assertEquals(new ByteVector(new byte[]{(byte) 255, 0}), v);
  }

  @Test
  void nativeSynchDecode2() {
    var v = SynchData.decode(new ByteVector(new byte[]{(byte) 255, 0x44}));
    assertEquals(2, v.size());
    assertEquals(new ByteVector(new byte[]{(byte) 255, 0x44}), v);
  }

  @Test
  void nativeSynchDecode3() {
    var v = SynchData.decode(new ByteVector(new byte[]{(byte) 255, (byte) 255, 0}));
    assertEquals(2, v.size());
    assertEquals(new ByteVector(new byte[]{(byte) 255, (byte) 255}), v);
  }

  @Test
  void nativeSynchDecode4() {
    var v = SynchData.decode(new ByteVector(new byte[]{(byte) 255, (byte) 255, (byte) 255}));
    assertEquals(3, v.size());
    assertEquals(new ByteVector(new byte[]{(byte) 255, (byte) 255, (byte) 255}), v);
  }
}
