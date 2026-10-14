package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.TagString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NumericFramesTest {
  static ByteVector bytes(String hex) {
    return new ByteVector(HexFormat.of().parseHex(hex));
  }

  static ByteVector wire(String id, String hex) {
    return PictureObjectFramesTest.wire(id, hex);
  }

  @AfterEach
  void restoreDecoder() {
    Id3Text.setHandler(null);
  }

  @Test
  void ownershipZeroStateAndEmptyReparseKeepNativeDefaultsAndOldState() {
    var o = new OwnershipFrame(new ByteVector());
    assertEquals(TagString.Type.LATIN1, o.textEncoding());
    assertEquals(List.of("", "", ""), o.toStringList());
    o.setPricePaid("old-price");
    o.setDatePurchased("old-date");
    o.setSeller("old-seller");
    o.setTextEncoding(TagString.Type.UTF8);
    o.setData(wire("OWNE", ""));
    assertEquals(TagString.Type.UTF8, o.textEncoding());
    assertEquals(List.of("old-price", "old-date", "old-seller"), o.toStringList());
  }

  @Test
  void ownershipShortDateChangesPriceAndEncodingOnly() {
    var o = new OwnershipFrame();
    o.setDatePurchased("old-date");
    o.setSeller("old-seller");
    o.setData(wire("OWNE", "03700073686f7274"));
    assertEquals(TagString.Type.UTF8, o.textEncoding());
    assertEquals(List.of("p", "old-date", "old-seller"), o.toStringList());
  }

  @Test
  void ownershipMissingPriceDelimiterKeepsPositionForDateAndSeller() {
    var o = new OwnershipFrame(wire("OWNE", "00616263646566676873656c6c6572"));
    assertEquals("", o.pricePaid());
    assertEquals("abcdefgh", o.datePurchased());
    assertEquals("seller", o.seller());
  }

  @Test
  void ownershipDateFixedBytesAndSellerDecodeWithoutRequiredTerminator() {
    var o = new OwnershipFrame(wire("OWNE", "007000323000303130303573656c6c6572006166746572"));
    assertEquals("20", o.datePurchased());
    assertEquals("seller", o.seller());
  }

  @Test
  void ownershipDateBypassesCallbackWhilePriceAndSellerUseIt() {
    var calls = new ArrayList<String>();
    Id3Text.setHandler(new Latin1StringHandler() {
      public String parse(ByteVector data) {
        var s = new TagString(data).toString();
        calls.add(s);
        data.clear();
        return "decoded:" + s;
      }
    });
    var data = wire("OWNE", "007000323032363130303573656c6c6572");
    var original = new ByteVector(data);
    var o = new OwnershipFrame(data);
    assertEquals(List.of("p", "seller"), calls);
    assertEquals(List.of("decoded:p", "20261005", "decoded:seller"), o.toStringList());
    assertEquals(original, data);
  }

  @Test
  void ownershipSecondCallbackFailureRetainsNewPriceDateAndOldSeller() {
    int[] calls = {0};
    Id3Text.setHandler(new Latin1StringHandler() {
      public String parse(ByteVector data) {
        if (++calls[0] == 2) throw new IllegalArgumentException("second");
        return "decoded:" + new TagString(data);
      }
    });
    var o = new OwnershipFrame();
    o.setSeller("old-seller");
    assertThrows(IllegalArgumentException.class, () -> o.setData(wire("OWNE", "007000323032363130303573656c6c6572")));
    assertEquals(List.of("decoded:p", "20261005", "old-seller"), o.toStringList());
  }

  @Test
  void ownershipRenderDoesNotPadOrTruncateDateAndChecksOnlySellerEncoding() {
    var o = new OwnershipFrame();
    o.setPricePaid("雪");
    o.setDatePurchased("abcdefghi");
    o.setSeller("seller");
    assertEquals(wire("OWNE", "00ea0061626364656667686973656c6c6572"), o.render());
    assertEquals(TagString.Type.LATIN1, o.textEncoding());
    o.setSeller("Ž");
    var h = o.header();
    var rendered = o.render();
    assertSame(h, o.header());
    assertEquals(3, rendered.at(10));
    assertEquals(TagString.Type.LATIN1, o.textEncoding());
    o.header().setVersion(3);
    assertEquals(1, o.render().at(10));
  }

  @Test
  void ownershipDisplayTruncatesCopiedPriceDateButKeepsFinalSellerNul() {
    var o = new OwnershipFrame();
    o.setPricePaid("a\0b");
    o.setDatePurchased("c\0d");
    o.setSeller("e\0f");
    assertEquals("pricePaid=a datePurchased=c seller=e\0f", o.toString());
    assertEquals(List.of("a\0b", "c\0d", "e\0f"), o.toStringList());
    o.toStringList().clear();
    assertEquals(3, o.toStringList().size());
  }

  @Test
  void popularimeterMissingEmailDelimiterReusesWholeBodyAsNumbers() {
    var p = new PopularimeterFrame(wire("POPM", "616263646566"));
    assertEquals("", p.email());
    assertEquals(97, p.rating());
    assertEquals(0x62636465L, p.counter());
  }

  @Test
  void popularimeterCounterConsumesAvailablePrefixAndIgnoresAfterFourBytes() {
    var p = new PopularimeterFrame(wire("POPM", "650007010203"));
    assertEquals(0x010203L, p.counter());
    p.setData(wire("POPM", "6500070102030405"));
    assertEquals(0x01020304L, p.counter());
  }

  @Test
  void popularimeterEmptyReparseClearsAllThreeFields() {
    var p = new PopularimeterFrame();
    p.setEmail("old");
    p.setRating(257);
    p.setCounter(-1);
    p.setData(wire("POPM", ""));
    assertEquals(List.of("", "0", "0"), p.toStringList());
  }

  @Test
  void popularimeterRatingIntIsRetainedButRenderNarrowsWhileCounterNarrowsAtSetter() {
    var p = new PopularimeterFrame();
    p.setEmail("e");
    p.setRating(-257);
    p.setCounter(0x1_0000_0003L);
    assertEquals(-257, p.rating());
    assertEquals(3, p.counter());
    assertEquals(wire("POPM", "6500ff00000003"), p.render());
    var copy = new PopularimeterFrame(p.render());
    assertEquals(255, copy.rating());
  }

  @Test
  void popularimeterCallbackThrowDoesNotResetOldNumericFields() {
    Id3Text.setHandler(new Latin1StringHandler() {
      public String parse(ByteVector data) {
        throw new IllegalArgumentException("callback");
      }
    });
    var p = new PopularimeterFrame();
    p.setEmail("old");
    p.setRating(333);
    p.setCounter(0xffffffffL);
    assertThrows(IllegalArgumentException.class, () -> p.setData(wire("POPM", "65000701020304")));
    assertEquals(List.of("old", "333", "-1"), p.toStringList());
  }

  @Test
  void popularimeterDisplayTruncatesEmailWithoutChangingStoredField() {
    var p = new PopularimeterFrame();
    p.setEmail("a\0b");
    p.setRating(-1);
    p.setCounter(-1);
    assertEquals("a rating=-1 counter=-1", p.toString());
    assertEquals(List.of("a\0b", "-1", "-1"), p.toStringList());
    p.toStringList().set(0, "detached");
    assertEquals("a\0b", p.email());
  }

  @Test
  void relativeVolumeAbsentGettersDoNotInsertChannels() {
    var r = new RelativeVolumeFrame();
    assertEquals(0, r.volumeAdjustmentIndex());
    assertEquals(0, r.volumeAdjustment());
    assertEquals(0, r.peakVolume().bitsRepresentingPeak());
    r.peakVolume().peakVolume().append((byte) 1);
    assertTrue(r.channels().isEmpty());
    assertEquals(wire("RVA2", "00"), r.render());
  }

  @Test
  void relativeVolumeReparseRetainsAbsentChannelsAndUpdatesDuplicatesInOrder() {
    var r = new RelativeVolumeFrame();
    r.setVolumeAdjustmentIndex(7, RelativeVolumeFrame.ChannelType.Subwoofer);
    r.setData(wire("RVA2", "6964000200110002ffff08ff"));
    assertEquals(List.of(RelativeVolumeFrame.ChannelType.FrontRight, RelativeVolumeFrame.ChannelType.Subwoofer), r.channels());
    assertEquals(-1, r.volumeAdjustmentIndex(RelativeVolumeFrame.ChannelType.FrontRight));
    assertEquals(7, r.volumeAdjustmentIndex(RelativeVolumeFrame.ChannelType.Subwoofer));
    r.setData(wire("RVA2", ""));
    assertEquals("", r.identification());
    assertEquals(2, r.channels().size());
  }

  @Test
  void relativeVolumeInvalidChannelByteMapsToOtherAndSignedIndexIsExact() {
    var r = new RelativeVolumeFrame(wire("RVA2", "696400ff800000"));
    assertEquals(9, RelativeVolumeFrame.ChannelType.values().length);
    assertEquals(List.of(RelativeVolumeFrame.ChannelType.Other), r.channels());
    assertEquals(-32768, r.volumeAdjustmentIndex(RelativeVolumeFrame.ChannelType.Other));
    assertEquals(-64, r.volumeAdjustment(RelativeVolumeFrame.ChannelType.Other));
  }

  @Test
  void relativeVolumeMissingIdentificationDelimiterStartsRecordsAtZero() {
    var r = new RelativeVolumeFrame(wire("RVA2", "02110f08aa"));
    assertEquals("", r.identification());
    assertEquals(4367, r.volumeAdjustmentIndex(RelativeVolumeFrame.ChannelType.FrontRight));
    assertEquals(bytes("aa"), r.peakVolume(RelativeVolumeFrame.ChannelType.FrontRight).peakVolume());
  }

  @Test
  void relativeVolumePeakCountIsUnsignedAndShortPayloadRetainsDeclaredBits() {
    var r = new RelativeVolumeFrame(wire("RVA2", "696400030011ff0102"));
    var peak = r.peakVolume(RelativeVolumeFrame.ChannelType.FrontLeft);
    assertEquals(255, peak.bitsRepresentingPeak());
    assertEquals(bytes("0102"), peak.peakVolume());
    assertEquals(wire("RVA2", "696400030011ff0102"), r.render());
  }

  @Test
  void relativeVolumePeakBytesAndChannelListsAreDetachedAndPayloadIsNotNormalized() {
    var r = new RelativeVolumeFrame();
    var source = bytes("aabbcc");
    var p = new RelativeVolumeFrame.PeakVolume(256, source);
    r.setPeakVolume(p);
    source.clear();
    p.peakVolume().clear();
    r.peakVolume().peakVolume().clear();
    r.channels().clear();
    assertEquals(0, r.peakVolume().bitsRepresentingPeak());
    assertEquals(bytes("aabbcc"), r.peakVolume().peakVolume());
    var equal = new RelativeVolumeFrame.PeakVolume(0, bytes("aabbcc"));
    assertEquals(equal, p);
    assertEquals(equal.hashCode(), p.hashCode());
    assertEquals(new RelativeVolumeFrame.PeakVolume(0, new ByteVector()), new RelativeVolumeFrame.PeakVolume());
    assertThrows(NullPointerException.class, () -> new RelativeVolumeFrame.PeakVolume(8, null));
    assertThrows(NullPointerException.class, () -> r.setPeakVolume(null, RelativeVolumeFrame.ChannelType.FrontLeft));
    assertEquals(List.of(RelativeVolumeFrame.ChannelType.MasterVolume), r.channels());
    assertEquals(wire("RVA2", "0001000000aabbcc"), r.render());
  }

  @Test
  void relativeVolumeSetterShortNarrowingAndFractionalTruncationFollowNative() {
    var r = new RelativeVolumeFrame();
    r.setVolumeAdjustmentIndex(65535);
    assertEquals(-1, r.volumeAdjustmentIndex());
    r.setVolumeAdjustment(-1.9f / 512);
    assertEquals(-1, r.volumeAdjustmentIndex());
    r.setVolumeAdjustment(1.9f / 512);
    assertEquals(1, r.volumeAdjustmentIndex());
    r.setVolumeAdjustment(-64.00194f);
    assertEquals(-32768, r.volumeAdjustmentIndex());
  }

  @Test
  void relativeVolumeUndefinedFloatConversionsRejectWithoutChangingStateOrChannels() {
    var r = new RelativeVolumeFrame();
    r.setVolumeAdjustmentIndex(17);
    var before = r.render();
    for (float f : new float[]{64, -64.001953125f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MAX_VALUE})
      assertThrows(IllegalArgumentException.class, () -> r.setVolumeAdjustment(f, RelativeVolumeFrame.ChannelType.Subwoofer));
    assertEquals(before, r.render());
    assertEquals(List.of(RelativeVolumeFrame.ChannelType.MasterVolume), r.channels());
  }

  @Test
  void relativeVolumeIdentificationCallbackFailureKeepsAllPriorState() {
    var r = new RelativeVolumeFrame();
    r.setIdentification("old");
    r.setVolumeAdjustmentIndex(17);
    Id3Text.setHandler(new Latin1StringHandler() {
      public String parse(ByteVector data) {
        throw new IllegalArgumentException("callback");
      }
    });
    assertThrows(IllegalArgumentException.class, () -> r.setData(wire("RVA2", "69640002000f00")));
    assertEquals("old", r.identification());
    assertEquals(List.of(RelativeVolumeFrame.ChannelType.MasterVolume), r.channels());
    assertEquals(17, r.volumeAdjustmentIndex());
  }

  @Test
  void constructorsAvoidSubclassDispatchButRuntimeReparseUsesIt() {
    class O extends OwnershipFrame {
      boolean ready;
      int calls;

      O(ByteVector data) {
        super(data);
        ready = true;
      }

      protected void parseFields(ByteVector data) {
        assertTrue(ready);
        calls++;
        super.parseFields(data);
      }
    }
    class P extends PopularimeterFrame {
      boolean ready;
      int calls;

      P(ByteVector data) {
        super(data);
        ready = true;
      }

      protected void parseFields(ByteVector data) {
        assertTrue(ready);
        calls++;
        super.parseFields(data);
      }
    }
    class R extends RelativeVolumeFrame {
      boolean ready;
      int calls;

      R(ByteVector data) {
        super(data);
        ready = true;
      }

      protected void parseFields(ByteVector data) {
        assertTrue(ready);
        calls++;
        super.parseFields(data);
      }
    }
    var od = wire("OWNE", "007000323032363130303573656c6c6572");
    var o = new O(od);
    assertEquals(0, o.calls);
    o.setData(od);
    assertEquals(1, o.calls);
    var pd = wire("POPM", "65000701020304");
    var p = new P(pd);
    assertEquals(0, p.calls);
    p.setData(pd);
    assertEquals(1, p.calls);
    var rd = wire("RVA2", "69640002000f00");
    var r = new R(rd);
    assertEquals(0, r.calls);
    r.setData(rd);
    assertEquals(1, r.calls);
  }

  @Test
  void allThreeInheritedTextSettersAreNoOpsAndPropertiesRemainUnsupported() {
    for (var f : List.of(new OwnershipFrame(), new PopularimeterFrame(), new RelativeVolumeFrame())) {
      String text = f.toString();
      f.setText("ignored");
      assertEquals(text, f.toString());
      assertEquals(List.of(new TagString(f.frameID()).toString()), f.asProperties().unsupportedData());
    }
  }

  @Test
  void upstreamParseOwnershipFrame() {
    var f = new OwnershipFrame(bytes("4f574e4500000019000000474250312e393900323031323039303542656174706f7274"));
    assertEquals("GBP1.99", f.pricePaid());
    assertEquals("20120905", f.datePurchased());
    assertEquals("Beatport", f.seller());
  }

  @Test
  void upstreamRenderOwnershipFrame() {
    var f = new OwnershipFrame();
    f.setPricePaid("GBP1.99");
    f.setDatePurchased("20120905");
    f.setSeller("Beatport");
    assertEquals(bytes("4f574e4500000019000000474250312e393900323031323039303542656174706f7274"), f.render());
  }

  @Test
  void upstreamParsePOPM() {
    var f = new PopularimeterFrame(bytes("504f504d000000170000656d61696c406578616d706c652e636f6d000200000003"));
    assertEquals("email@example.com", f.email());
    assertEquals(2, f.rating());
    assertEquals(3, f.counter());
  }

  @Test
  void upstreamParsePOPMWithoutCounter() {
    var f = new PopularimeterFrame(bytes("504f504d000000130000656d61696c406578616d706c652e636f6d0002"));
    assertEquals("email@example.com", f.email());
    assertEquals(2, f.rating());
    assertEquals(0, f.counter());
  }

  @Test
  void upstreamRenderPOPM() {
    var f = new PopularimeterFrame();
    f.setEmail("email@example.com");
    f.setRating(2);
    f.setCounter(3);
    assertEquals(bytes("504f504d000000170000656d61696c406578616d706c652e636f6d000200000003"), f.render());
  }

  @Test
  void upstreamPOPMtoString() {
    var f = new PopularimeterFrame();
    f.setEmail("email@example.com");
    f.setRating(2);
    f.setCounter(3);
    assertEquals("email@example.com rating=2 counter=3", f.toString());
  }

  @Test
  void upstreamParseRelativeVolumeFrame() {
    var f = new RelativeVolumeFrame(bytes("525641320000000b00006964656e740002000f0845"));
    var t = RelativeVolumeFrame.ChannelType.FrontRight;
    assertEquals("ident", f.identification());
    assertEquals(15.0f / 512, f.volumeAdjustment(t));
    assertEquals(15, f.volumeAdjustmentIndex(t));
    assertEquals(8, f.peakVolume(t).bitsRepresentingPeak());
    assertEquals(bytes("45"), f.peakVolume(t).peakVolume());
    assertEquals(List.of(t), f.channels());
  }

  @Test
  void upstreamRenderRelativeVolumeFrame() {
    var f = new RelativeVolumeFrame();
    f.setIdentification("ident");
    var t = RelativeVolumeFrame.ChannelType.FrontRight;
    f.setVolumeAdjustment(15.0f / 512, t);
    f.setPeakVolume(new RelativeVolumeFrame.PeakVolume(8, bytes("45")), t);
    assertEquals(bytes("525641320000000b00006964656e740002000f0845"), f.render());
  }
}
