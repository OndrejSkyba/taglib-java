package cz.skyba.taglib.mpeg;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.TagBase;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class HeaderTest {
  private static ByteVector bytes(String hex) {
    return new ByteVector(HexFormat.of().parseHex(hex));
  }

  private static class MemoryFile extends FileBase {
    MemoryFile(ByteVectorStream stream) {
      super(stream);
    }

    void invalidate() {
      setValid(false);
    }

    public TagBase tag() {
      return null;
    }

    public AudioProperties audioProperties() {
      return null;
    }

    public void save() {
      throw new cz.skyba.taglib.TagLibException("Test save rejected");
    }
  }

  private static Header read(String hex) {
    return new Header(new MemoryFile(new ByteVectorStream(bytes(hex))), 0, false);
  }

  @Test
  void mpegFlagsAndFrameArithmetic() {
    var header = read("fffa920c");
    assertTrue(header.isValid());
    assertEquals(Header.Version.Version1, header.version());
    assertEquals(3, header.layer());
    assertTrue(header.protectionEnabled());
    assertEquals(128, header.bitrate());
    assertEquals(44100, header.sampleRate());
    assertTrue(header.isPadded());
    assertEquals(Header.ChannelMode.Stereo, header.channelMode());
    assertEquals(Header.ChannelConfiguration.Custom, header.channelConfiguration());
    assertTrue(header.isCopyrighted());
    assertTrue(header.isOriginal());
    assertEquals(418, header.frameLength());
    assertEquals(1152, header.samplesPerFrame());
    assertFalse(header.isADTS());
    var layerOne = read("fffed200");
    assertTrue(layerOne.isValid());
    assertEquals(1, layerOne.layer());
    assertEquals(384, layerOne.samplesPerFrame());
    // Native integer division precedes Layer I's four-byte padding addition.
    assertEquals(456, layerOne.frameLength());
  }

  @Test
  void nextHeaderCheckRetainsFieldsAndNativeCursorEffects() {
    var frame = bytes("fffb9000").resize(417).append(bytes("fffa00c0"));
    var stream = new ByteVectorStream(new ByteVector("prefix!").append(frame));
    var file = new MemoryFile(stream);
    var checked = new Header(file, 7);
    assertTrue(checked.isValid()); // Native mask ignores protection, bitrate and channel mode.
    assertEquals(428, stream.tell());
    stream.data().set(426, (byte) 0x04); // The next header's sample-rate bits now differ.
    var rejected = new Header(file, 7, true);
    assertFalse(rejected.isValid());
    assertEquals(128, rejected.bitrate());
    assertEquals(417, rejected.frameLength());
    assertEquals(428, stream.tell());
    var unchecked = new Header(file, 7, false);
    assertTrue(unchecked.isValid());
    assertEquals(11, stream.tell());
    file.close();
    assertTrue(stream.isOpen());
  }

  @Test
  void invalidHeadersRetainOnlyFieldsReachedBeforeFailure() {
    var free = read("ffe2f00c");
    assertFalse(free.isValid());
    assertEquals(Header.Version.Version2_5, free.version());
    assertEquals(3, free.layer());
    assertTrue(free.protectionEnabled());
    assertEquals(0, free.sampleRate());
    assertFalse(free.isCopyrighted());
    var reservedRate = read("fff2ac0c");
    assertFalse(reservedRate.isValid());
    assertEquals(Header.Version.Version2, reservedRate.version());
    assertEquals(96, reservedRate.bitrate());
    assertEquals(0, reservedRate.samplesPerFrame());
    assertFalse(reservedRate.isOriginal());
    for (String value : new String[]{"", "ff", "fffb90", "ffff9000", "00009000", "ffea9000"}) {
      var invalid = read(value);
      assertFalse(invalid.isValid());
      assertEquals(Header.Version.Version1, invalid.version());
      assertEquals(0, invalid.layer());
    }
  }

  @Test
  void adtsHasItsOwnVersionChannelAndFlagRules() {
    var header = read("fff151e401e0");
    assertTrue(header.isValid());
    assertTrue(header.isADTS());
    assertEquals(Header.Version.Version4, header.version());
    assertEquals(0, header.layer());
    assertFalse(header.protectionEnabled());
    assertEquals(44100, header.sampleRate());
    assertEquals(Header.ChannelConfiguration.FrontCenterLeftRightSideLeftRightBackLeftRightLFE,
      header.channelConfiguration());
    assertEquals(Header.ChannelMode.Stereo, header.channelMode());
    assertTrue(header.isOriginal());
    assertTrue(header.isCopyrighted());
    assertFalse(header.isPadded());
    assertEquals(15, header.frameLength());
    assertEquals(5, header.bitrate());
    assertEquals(1024, header.samplesPerFrame());
    assertEquals(Header.Version.Version2, read("fff9504000e0").version());
    assertEquals(Header.ChannelMode.SingleChannel, read("fff9504000e0").channelMode());
  }

  @Test
  void shortAndReservedAdtsCanBeValidWithoutLengthCheck() {
    var stream = new ByteVectorStream(bytes("fff15080"));
    var file = new MemoryFile(stream);
    var header = new Header(file, 0, false);
    assertTrue(header.isValid());
    assertEquals(1024, header.samplesPerFrame());
    assertEquals(0, header.frameLength());
    assertEquals(4, stream.tell());
    var checked = new Header(file, 0);
    assertFalse(checked.isValid());
    assertEquals(44100, checked.sampleRate());
    assertEquals(4, stream.tell());
    var reserved = read("fff13c8000e0");
    assertTrue(reserved.isValid());
    assertEquals(0, reserved.sampleRate());
    assertEquals(7, reserved.frameLength());
    assertEquals(0, reserved.bitrate());
  }

  @Test
  void invalidFileStillReadsAndCopiesRetainTheSnapshot() {
    var stream = new ByteVectorStream(bytes("fffb9000"));
    var file = new MemoryFile(stream);
    file.invalidate();
    var header = new Header(file, 0, false);
    assertTrue(header.isValid());
    var copy = new Header(header);
    stream.data().setData(new byte[4]);
    assertEquals(128, header.bitrate());
    assertEquals(header.frameLength(), copy.frameLength());
    assertFalse(new Header(file, 0, false).isValid());
  }

  @Test
  void xingTotalsRemainUnsignedAndZeroTotalsKeepTheirType() {
    var data = bytes("58696e6700000003ffffffff80000000");
    var header = new XingHeader(data);
    assertTrue(header.isValid());
    assertEquals(XingHeader.HeaderType.Xing, header.type());
    assertEquals(0xffff_ffffL, header.totalFrames());
    assertEquals(0x8000_0000L, header.totalSize());
    data.set(8, (byte) 0);
    assertEquals(0xffff_ffffL, header.totalFrames());
    var zero = new XingHeader(bytes("496e666f00000003000000000000007b"));
    assertFalse(zero.isValid());
    assertEquals(XingHeader.HeaderType.Xing, zero.type());
    assertEquals(123, zero.totalSize());
  }

  @Test
  void markerPrecedenceAndTruncationDoNotFallBack() {
    var vbri = bytes("564252490001000000000000007b0000002a").resize(32);
    var parsed = new XingHeader(vbri);
    assertTrue(parsed.isValid());
    assertEquals(XingHeader.HeaderType.VBRI, parsed.type());
    assertEquals(123, parsed.totalSize());
    assertEquals(42, parsed.totalFrames());
    assertFalse(new XingHeader(vbri.mid(0, 31)).isValid());
    var info = bytes("496e666f00000003000000070000000b");
    var xing = bytes("58696e67000000030000000d00000011");
    var preferred = new XingHeader(new ByteVector(vbri).append(info).append(xing));
    assertEquals(13, preferred.totalFrames());
    assertEquals(17, preferred.totalSize());
    xing.set(7, (byte) 1);
    var rejected = new XingHeader(new ByteVector(vbri).append(info).append(xing));
    assertFalse(rejected.isValid());
    assertEquals(XingHeader.HeaderType.Invalid, rejected.type());
    assertEquals(0, rejected.totalFrames());
    assertFalse(new XingHeader(bytes("58696e67000000030000000d000000")).isValid());
  }
}
