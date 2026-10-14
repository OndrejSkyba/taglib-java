package cz.skyba.taglib;

import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class TrackerPropertiesTest {
  @TempDir
  Path temporary;

  private static void u16(byte[] data, int offset, int value) {
    ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putShort(offset, (short) value);
  }

  private static void u32(byte[] data, int offset, long value) {
    ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, (int) value);
  }

  private static byte[] join(byte[]... chunks) {
    var out = new ByteArrayOutputStream();
    for (var chunk : chunks) out.writeBytes(chunk);
    return out.toByteArray();
  }

  private static byte[] xmHeader(int length, int channels, int patterns, int instruments) {
    byte[] data = new byte[80];
    u16(data, 58, 0x104);
    u32(data, 60, 20);
    u16(data, 64, length);
    u16(data, 68, channels);
    u16(data, 70, patterns);
    u16(data, 72, instruments);
    return data;
  }

  @Test
  void explicitStyleConstructorsHaveNativeZeroDefaultsAndConstants() {
    for (var style : ReadStyle.values()) {
      var mod = new cz.skyba.taglib.mod.Properties(style);
      assertEquals(AudioProperties.EMPTY, mod.common());
      assertEquals(0, mod.instrumentCount());
      assertEquals(0, mod.lengthInPatterns());
      var s3m = new cz.skyba.taglib.s3m.Properties(style);
      assertEquals(AudioProperties.EMPTY, s3m.common());
      assertFalse(s3m.stereo());
      assertEquals(0, s3m.tempo());
      var it = new cz.skyba.taglib.it.Properties(style);
      assertEquals(AudioProperties.EMPTY, it.common());
      assertFalse(it.stereo());
      assertEquals(0, it.special());
      var xm = new cz.skyba.taglib.xm.Properties(style);
      assertEquals(AudioProperties.EMPTY, xm.common());
      assertEquals(0, xm.sampleCount());
      assertEquals(0, xm.version());
    }
    assertEquals(128, cz.skyba.taglib.s3m.Properties.CustomData);
    assertEquals(32, cz.skyba.taglib.s3m.Properties.EnableFilter);
    assertEquals(64, cz.skyba.taglib.it.Properties.UseMidiPitchController);
    assertEquals(128, cz.skyba.taglib.it.Properties.RequestEmbeddedMidiConf);
    assertEquals(1, cz.skyba.taglib.it.Properties.MessageAttached);
    assertEquals(8, cz.skyba.taglib.it.Properties.MidiConfEmbedded);
    assertEquals(1, cz.skyba.taglib.xm.Properties.LinearFreqTable);
  }

  @Test
  void assignmentNarrowsUnsignedWidthsWithoutNormalizingSignedChannels() {
    var mod = new cz.skyba.taglib.mod.Properties(ReadStyle.ACCURATE);
    mod.setChannels(-19);
    mod.setInstrumentCount(-1);
    mod.setLengthInPatterns(0x101);
    assertEquals(-19, mod.channels());
    assertEquals(0xffff_ffffL, mod.instrumentCount());
    assertEquals(1, mod.lengthInPatterns());
    assertEquals(-19, mod.common().channels());
    var s3m = new cz.skyba.taglib.s3m.Properties(ReadStyle.AVERAGE);
    s3m.setPatternCount(-1);
    s3m.setGlobalVolume(0x180);
    assertEquals(65535, s3m.patternCount());
    assertEquals(128, s3m.globalVolume());
    var xm = new cz.skyba.taglib.xm.Properties(ReadStyle.FAST);
    xm.setSampleCount(0x1_0000_0001L);
    xm.setTempo(-1);
    assertEquals(1, xm.sampleCount());
    assertEquals(65535, xm.tempo());
    assertEquals(0, xm.lengthInMilliseconds());
    assertEquals(0, xm.bitrate());
    assertEquals(0, xm.sampleRate());
  }

  @Test
  void s3mStereoIsIndependentWhileItStereoComesFromFlags() {
    var s3m = new cz.skyba.taglib.s3m.Properties(ReadStyle.AVERAGE);
    s3m.setStereo(true);
    s3m.setFlags(0);
    assertTrue(s3m.stereo());
    s3m.setStereo(false);
    s3m.setFlags(128);
    assertFalse(s3m.stereo());
    var it = new cz.skyba.taglib.it.Properties(ReadStyle.AVERAGE);
    it.setFlags(cz.skyba.taglib.it.Properties.LinearSlides);
    assertFalse(it.stereo());
    it.setFlags(0x10001);
    assertTrue(it.stereo());
    it.setSpecial(cz.skyba.taglib.it.Properties.MidiConfEmbedded);
    assertEquals(8, it.special());
    assertTrue(it.stereo());
  }

  @Test
  void modRetainsSelectedInstrumentSlotsAndUnsignedSongLength() throws Exception {
    for (var signature : new String[]{"M.K.", "none"}) {
      byte[] data = new byte[1084];
      System.arraycopy(signature.getBytes(StandardCharsets.US_ASCII), 0, data, 1080, 4);
      int count = signature.equals("M.K.") ? 31 : 15;
      data[20 + count * 30] = (byte) 255;
      var stream = new ByteVectorStream(data);
      var p = new cz.skyba.taglib.mod.Properties(stream);
      assertEquals(count, p.instrumentCount());
      assertEquals(255, p.lengthInPatterns());
      assertEquals(4, p.channels());
      stream.data().clear();
      assertEquals(count, p.instrumentCount());
      assertTrue(stream.isOpen());
    }
  }

  @Test
  void s3mRetainsOrdersHeaderFlagsAndMasterVolumeSelection() throws Exception {
    byte[] data = new byte[101];
    data[28] = 26;
    data[29] = 16;
    System.arraycopy("SCRM".getBytes(StandardCharsets.US_ASCII), 0, data, 44, 4);
    u16(data, 32, 5);
    u16(data, 36, 65535);
    u16(data, 38, 65535);
    u16(data, 40, 0xabcd);
    u16(data, 42, 65535);
    data[48] = (byte) 255;
    data[49] = (byte) 128;
    data[50] = (byte) 255;
    data[51] = (byte) 255;
    Arrays.fill(data, 64, 96, (byte) 255);
    data[64] = (byte) 128;
    System.arraycopy(new byte[]{0, (byte) 254, 1, (byte) 255, 2}, 0, data, 96, 5);
    var p = new cz.skyba.taglib.s3m.Properties(new ByteVectorStream(data));
    assertEquals(2, p.lengthInPatterns());
    assertEquals(1, p.channels());
    assertTrue(p.stereo());
    assertEquals(127, p.masterVolume());
    assertEquals(255, p.globalVolume());
    assertEquals(255, p.tempo());
    assertEquals(128, p.bpmSpeed());
    assertEquals(65535, p.patternCount());
    assertEquals(65535, p.flags());
    assertEquals(0xabcd, p.trackerVersion());
    assertEquals(65535, p.fileFormatVersion());
    assertEquals(0, p.sampleCount());
  }

  @Test
  void itRetainsSignedVolumeChannelRuleAndIndependentSpecialFlags() throws Exception {
    byte[] data = new byte[198];
    System.arraycopy("IMPM".getBytes(StandardCharsets.US_ASCII), 0, data, 0, 4);
    u16(data, 32, 6);
    u16(data, 38, 65535);
    u16(data, 40, 65535);
    u16(data, 42, 65535);
    u16(data, 44, 9);
    u16(data, 46, 8);
    Arrays.fill(data, 64, 128, (byte) 128);
    data[64] = 0;
    data[128] = (byte) 255;
    data[65] = 0;
    data[129] = 127;
    data[66] = (byte) 128;
    data[130] = 127;
    data[67] = 127;
    data[131] = 1;
    data[48] = (byte) 255;
    data[49] = 48;
    data[50] = 6;
    data[51] = 125;
    data[52] = (byte) 255;
    data[53] = (byte) 128;
    System.arraycopy(new byte[]{(byte) 254, 3, (byte) 254, 4, (byte) 255, 8}, 0, data, 192, 6);
    var p = new cz.skyba.taglib.it.Properties(new ByteVectorStream(data));
    assertEquals(2, p.channels());
    assertEquals(2, p.lengthInPatterns());
    assertTrue(p.stereo());
    assertEquals(8, p.special());
    assertEquals(65535, p.patternCount());
    assertEquals(65535, p.version());
    assertEquals(65535, p.compatibleVersion());
    assertEquals(255, p.globalVolume());
    assertEquals(48, p.mixVolume());
    assertEquals(6, p.bpmSpeed());
    assertEquals(125, p.tempo());
    assertEquals(255, p.panningSeparation());
    assertEquals(128, p.pitchWheelDepth());
  }

  @Test
  void s3mTitleAndMarkerUseTheInitialCursorWhilePropertyHeadersAreAbsolute() throws Exception {
    byte[] data = new byte[96 + 6656 * 2];
    data[28] = 26;
    data[29] = 16;
    data[35] = 26;
    data[36] = 16;
    System.arraycopy("SCRM".getBytes(StandardCharsets.US_ASCII), 0, data, 44, 4);
    Arrays.fill(data, 64, 96, (byte) 255);
    var stream = new ByteVectorStream(data);
    stream.seek(7);
    var p = new cz.skyba.taglib.s3m.Properties(stream);
    assertEquals(6656, p.sampleCount());
    assertEquals(16, p.patternCount());
    assertEquals(0, p.channels());
    assertEquals(76, stream.tell());
    assertTrue(stream.isOpen());
  }

  @Test
  void xmAvailableLowByteIsRetainedAndNativeFileRefRemainsValid() throws Exception {
    byte[] data = new byte[69];
    u32(data, 60, 9);
    data[68] = 7;
    for (var style : ReadStyle.values()) {
      var stream = new ByteVectorStream(data);
      stream.seek(7);
      var p = new cz.skyba.taglib.xm.Properties(stream, style);
      assertEquals(7, p.channels());
      assertEquals(69, stream.tell());
      stream.data().clear();
      assertEquals(7, p.common().channels());
      assertTrue(stream.isOpen());
      Path path = temporary.resolve("partial-" + style + ".xm");
      Files.write(path, data);
      assertEquals(7, TagLib.read(path).audioProperties().channels());
      try (var ref = new AudioFile(path, new ReadOptions(false, style, ReadOptions.DEFAULT.maxMetadataBytes()))) {
        assertTrue(ref.isOpen());
        assertEquals(7, ref.audioProperties().channels());
      }
      var detached = new cz.skyba.taglib.xm.Properties(path, style);
      Files.delete(path);
      assertEquals(7, detached.channels());
    }
  }

  @Test
  void xmAggregatesSampleCountsAndRetainsPartialSampleLengths() throws Exception {
    byte[] one = new byte[33];
    u32(one, 0, 33);
    u16(one, 27, 1);
    u32(one, 29, 0);
    byte[] two = new byte[33];
    u32(two, 0, 33);
    u16(two, 27, 2);
    u32(two, 29, 1);
    byte[] data = join(xmHeader(1, 8, 0, 2), one, new byte[40], two, new byte[]{5, 5}, new byte[10]);
    var stream = new ByteVectorStream(data);
    var p = new cz.skyba.taglib.xm.Properties(stream);
    assertEquals(2, p.instrumentCount());
    assertEquals(3, p.sampleCount());
    assertEquals(260, p.version());
    assertEquals(data.length, stream.tell());
    assertEquals("\n".repeat(4), TagLib.read(new ByteVectorStream(data) {
      public String name() {
        return "count.xm";
      }
    }).properties().first("COMMENT"));
  }

  @Test
  void xmPartialPatternDataSizeControlsTheFinalCursor() throws Exception {
    byte[] pattern = new byte[8];
    u32(pattern, 0, 8);
    pattern[7] = 3;
    byte[] data = join(xmHeader(1, 2, 1, 0), pattern, new byte[3]);
    var stream = new ByteVectorStream(data);
    var p = new cz.skyba.taglib.xm.Properties(stream);
    assertEquals(1, p.patternCount());
    assertEquals(data.length, stream.tell());
  }

  @Test
  void missingModIdentifierRejectsTheInputAfterTheNativeSignatureRead() throws Exception {
    byte[] data = new byte[471];
    data[470] = (byte) 255;
    var stream = new ByteVectorStream(data);
    assertThrows(java.io.UncheckedIOException.class, () -> new cz.skyba.taglib.mod.Properties(stream));
    assertEquals(1080, stream.tell());
    assertTrue(stream.isOpen());
  }

  @Test
  void xmIgnoresUnavailablePaddingAndAudioAfterRequiredFields() throws Exception {
    byte[] header = xmHeader(1, 8, 0, 0);
    u32(header, 60, 0xffff_ffffL);
    var stream = new ByteVectorStream(header);
    var p = new cz.skyba.taglib.xm.Properties(stream);
    assertEquals(8, p.channels());
    assertEquals(60L + 0xffff_ffffL, stream.tell());
    byte[] instrument = new byte[33];
    u32(instrument, 0, 33);
    u16(instrument, 27, 1);
    u32(instrument, 29, 40);
    byte[] sample = new byte[40];
    u32(sample, 0, 0xffff_ffffL);
    byte[] data = join(xmHeader(1, 8, 0, 1), instrument, sample);
    stream = new ByteVectorStream(data);
    p = new cz.skyba.taglib.xm.Properties(stream);
    assertEquals(1, p.sampleCount());
    assertEquals(data.length + 0xffff_ffffL, stream.tell());
  }
}
