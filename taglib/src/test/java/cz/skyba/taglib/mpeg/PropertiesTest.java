package cz.skyba.taglib.mpeg;

import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class PropertiesTest {
  @TempDir
  Path directory;

  private static ByteVector bytes(String hex) {
    return new ByteVector(HexFormat.of().parseHex(hex));
  }

  private static ByteVector frames(ByteVector frame, int count) {
    var result = new ByteVector();
    for (int i = 0; i < count; i++) result.append(frame);
    return result;
  }

  @Test
  void cbrDetailsAndCommonProjectionShareFirstFrameState() throws Exception {
    var stream = new ByteVectorStream(frames(bytes("fffa900c").resize(417), 3));
    var p = new Properties(stream);
    assertEquals(78, p.lengthInMilliseconds());
    assertEquals(0, p.lengthInSeconds());
    assertEquals(128, p.bitrate());
    assertEquals(44100, p.sampleRate());
    assertEquals(2, p.channels());
    assertEquals(Header.Version.Version1, p.version());
    assertEquals(3, p.layer());
    assertTrue(p.protectionEnabled());
    assertEquals(Header.ChannelMode.Stereo, p.channelMode());
    assertEquals(Header.ChannelConfiguration.Custom, p.channelConfiguration());
    assertTrue(p.isCopyrighted());
    assertTrue(p.isOriginal());
    assertFalse(p.isADTS());
    assertNull(p.xingHeader());
    assertEquals(838, stream.tell());
    assertEquals(p.lengthInMilliseconds(), p.common().lengthInMilliseconds());
  }

  @Test
  void adtsFastSuppressesEstimationButRetainsChannelAndFlagFields() throws Exception {
    var data = frames(bytes("fff151e401e0").resize(15), 3);
    var fastStream = new ByteVectorStream(data);
    var fast = new Properties(fastStream, ReadStyle.FAST);
    assertEquals(0, fast.bitrate());
    assertEquals(0, fast.lengthInMilliseconds());
    assertEquals(15, fastStream.tell());
    assertTrue(fast.isADTS());
    assertEquals(Header.Version.Version4, fast.version());
    assertEquals(8, fast.channels());
    assertEquals(Header.ChannelConfiguration.FrontCenterLeftRightSideLeftRightBackLeftRightLFE,
      fast.channelConfiguration());
    assertTrue(fast.isCopyrighted());
    assertTrue(fast.isOriginal());
    assertFalse(fast.protectionEnabled());
    for (var style : new ReadStyle[]{ReadStyle.AVERAGE, ReadStyle.ACCURATE}) {
      var stream = new ByteVectorStream(data);
      var p = new Properties(stream, style);
      assertEquals(5, p.bitrate());
      assertEquals(72, p.lengthInMilliseconds());
      assertEquals(36, stream.tell());
    }
  }

  @Test
  void averageStopsAtStableIntegerMeanWhileAccurateReachesTheTail() throws Exception {
    // Fifteen 100-byte frames establish the stable mean; the larger tail changes Accurate.
    var small = bytes("fff150800c80").resize(100);
    var large = bytes("fff150802580").resize(300);
    var data = frames(small, 15).append(frames(large, 15));
    var average = new Properties(new ByteVectorStream(data), ReadStyle.AVERAGE);
    var accurate = new Properties(new ByteVectorStream(data), ReadStyle.ACCURATE);
    assertEquals(34, average.bitrate());
    assertEquals(1412, average.lengthInMilliseconds()); // round(6000 * 8 / 34).
    assertEquals(67, accurate.bitrate());
    assertEquals(716, accurate.lengthInMilliseconds());
  }

  @Test
  void invalidPreferredVbrMarkerDoesNotFallBackToInfo() throws Exception {
    var frame = bytes("fffb9000").append(new ByteVector("Info"))
      .append(new ByteVector(ByteBuffer.allocate(12).putInt(3).putInt(7).putInt(11).array()))
      .append(new ByteVector("Xing"))
      .append(new ByteVector(ByteBuffer.allocate(12).putInt(1).putInt(7).putInt(11).array()))
      .resize(417);
    var p = new Properties(new ByteVectorStream(frame.append(bytes("fffb9000").resize(417))));
    assertNull(p.xingHeader());
    assertEquals(128, p.bitrate());
    assertEquals(52, p.lengthInMilliseconds());
  }

  @Test
  void validUnsignedXingTotalsRemainVisibleWhenDurationIsOutOfNativeIntRange() throws Exception {
    var frame = bytes("fffb9000").append(new ByteVector("Xing"))
      .append(bytes("00000003ffffffff80000000")).resize(417);
    var p = new Properties(new ByteVectorStream(frame.append(bytes("fffb9000"))));
    assertNotNull(p.xingHeader());
    assertEquals(0xffff_ffffL, p.xingHeader().totalFrames());
    assertEquals(0x8000_0000L, p.xingHeader().totalSize());
    assertEquals(0, p.lengthInMilliseconds());
    assertEquals(0, p.bitrate());
    assertEquals(44100, p.sampleRate());
  }

  @Test
  void noCoherentFramesRetainsNativePropertyDefaults() throws Exception {
    for (String hex : new String[]{"", "fffb9000", "ffff9000", "fff15080"}) {
      var p = new Properties(new ByteVectorStream(bytes(hex)));
      assertEquals(Header.Version.Version1, p.version());
      assertEquals(0, p.layer());
      assertEquals(0, p.channels());
      assertEquals(0, p.sampleRate());
      assertFalse(p.isADTS());
      assertNull(p.xingHeader());
    }
    var reserved = new Properties(new ByteVectorStream(frames(bytes("fff13ce400e0").resize(7), 3)));
    assertTrue(reserved.isADTS());
    assertEquals(0, reserved.sampleRate());
    assertEquals(3, reserved.channels());
    assertEquals(0, reserved.bitrate());
  }

  @Test
  void specializedPathAndBorrowedStreamProduceDetachedSnapshots() throws Exception {
    var data = frames(bytes("fffb9000").resize(417), 3);
    var path = Files.write(directory.resolve("audio.unknown"), data.toByteArray());
    var fromPath = new Properties(path);
    var stream = new ByteVectorStream(data);
    stream.seek(7);
    var borrowed = new Properties(stream);
    assertEquals(fromPath.common(), borrowed.common());
    assertTrue(stream.isOpen());
    stream.data().clear();
    Files.delete(path);
    assertEquals(78, borrowed.lengthInMilliseconds());
    assertEquals(128, fromPath.bitrate());
  }
}
