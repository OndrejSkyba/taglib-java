package cz.skyba.taglib.internal.codec;

import cz.skyba.taglib.AudioFile;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DsdTtaPropertiesTest {
  private static ByteVector dsf(long samples, int rate, int channels) {
    return new ByteVector(ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
      .putInt(1).putInt(0).putInt(2).putInt(channels).putInt(rate).putInt(1)
      .putLong(samples).putInt(4096).putInt(0).array());
  }

  private static ByteVector tta(long frames, int rate) {
    return new ByteVector(ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN)
      .put(new byte[]{'T', 'T', 'A', '1'}).putShort((short) 1).putShort((short) 2)
      .putShort((short) 16).putInt(rate).putInt((int) frames).array());
  }

  @Test
  void dsfDetailedFieldsShareNativeUnsignedBitrateCalculation() {
    var p = new cz.skyba.taglib.dsf.Properties(dsf(2822400, 2822400, 2), ReadStyle.AVERAGE);
    assertEquals(1, p.formatVersion());
    assertEquals(0, p.formatID());
    assertEquals(2, p.channelType());
    assertEquals(2, p.channels());
    assertEquals(2822400, p.sampleRate());
    assertEquals(1, p.bitsPerSample());
    assertEquals(2822400, p.sampleCount());
    assertEquals(4096, p.blockSizePerChannel());
    assertEquals(1000, p.lengthInMilliseconds());
    assertEquals(1, p.lengthInSeconds());
    assertEquals(5645, p.bitrate());
    p = new cz.skyba.taglib.dsf.Properties(dsf(1, -1, 2), ReadStyle.FAST);
    assertEquals(-1, p.sampleRate());
    assertEquals(4294967, p.bitrate());
  }

  @Test
  void dsfDurationRetainsNativeUnsignedStorageAndSignedGetter() {
    var p = new cz.skyba.taglib.dsf.Properties(dsf(2147483648L, 1000, 2), ReadStyle.ACCURATE);
    assertEquals(Integer.MIN_VALUE, p.lengthInMilliseconds());
    assertEquals(-2147483, p.lengthInSeconds());
    p = new cz.skyba.taglib.dsf.Properties(dsf(0xffffffffL, 1000, 2), ReadStyle.AVERAGE);
    assertEquals(0, p.lengthInMilliseconds());
    p = new cz.skyba.taglib.dsf.Properties(dsf(-1, 2822400, 2), ReadStyle.AVERAGE);
    assertEquals(-1, p.sampleCount());
    assertEquals(0, p.lengthInMilliseconds());
  }

  @Test
  void dsfPartialFieldsAndDataLifetimeMatchNativeConversions() {
    var p = new cz.skyba.taglib.dsf.Properties(new ByteVector(new byte[]{(byte) 255}), ReadStyle.AVERAGE);
    assertEquals(255, p.formatVersion());
    assertEquals(0, p.sampleCount());
    assertEquals(0, p.blockSizePerChannel());
    var data = dsf(2822400, 2822400, 2);
    p = new cz.skyba.taglib.dsf.Properties(data, ReadStyle.AVERAGE);
    data.clear();
    assertEquals(2822400, p.sampleCount());
    assertEquals(4096, p.blockSizePerChannel());
  }

  @Test
  void dsdiffConstructorRetainsSuppliedBitrateAndFixedWidth() {
    var p = new cz.skyba.taglib.dsdiff.Properties(2822400, 2, 2822400, 5644, ReadStyle.AVERAGE);
    assertEquals(1000, p.lengthInMilliseconds());
    assertEquals(1, p.lengthInSeconds());
    assertEquals(2822400, p.sampleCount());
    assertEquals(2, p.channels());
    assertEquals(1, p.bitsPerSample());
    assertEquals(5644, p.bitrate());
    p = new cz.skyba.taglib.dsdiff.Properties(1000, 0, 0, -123, ReadStyle.FAST);
    assertEquals(-123, p.bitrate());
    assertEquals(0, p.sampleCount());
    assertEquals(0, p.lengthInMilliseconds());
  }

  @Test
  void dsdiffUnsignedCountAndRoundedDurationGuardRemainNative() {
    var p = new cz.skyba.taglib.dsdiff.Properties(1000, 2, -1, 7, ReadStyle.AVERAGE);
    assertEquals(-1, p.sampleCount());
    assertEquals(0, p.lengthInMilliseconds());
    assertEquals(7, p.bitrate());
    p = new cz.skyba.taglib.dsdiff.Properties(0xffffffffL, 65535, 2822400, 1, ReadStyle.ACCURATE);
    assertEquals(-1, p.sampleRate());
    assertEquals(65535, p.channels());
    assertEquals(0, p.lengthInMilliseconds());
    p = new cz.skyba.taglib.dsdiff.Properties(1000, 2, 2147483647L, 1, ReadStyle.AVERAGE);
    assertEquals(0, p.lengthInMilliseconds());
  }

  @Test
  void trueAudioFieldsAndUnsignedFrameCountAreRetained() {
    var p = new cz.skyba.taglib.trueaudio.Properties(tta(162496, 44100), 79517);
    assertEquals(1, p.ttaVersion());
    assertEquals(162496, p.sampleFrames());
    assertEquals(2, p.channels());
    assertEquals(16, p.bitsPerSample());
    assertEquals(44100, p.sampleRate());
    assertEquals(3685, p.lengthInMilliseconds());
    assertEquals(3, p.lengthInSeconds());
    assertEquals(173, p.bitrate());
    p = new cz.skyba.taglib.trueaudio.Properties(tta(0xffffffffL, 1), 79517, ReadStyle.ACCURATE);
    assertEquals(0xffffffffL, p.sampleFrames());
    assertEquals(0, p.lengthInMilliseconds());
    assertEquals(0, p.bitrate());
  }

  @Test
  void trueAudioUnsupportedVersionAndShortHeaderKeepVersionWithoutInventingFields() {
    var p = new cz.skyba.taglib.trueaudio.Properties(new ByteVector("TTA2"), 79517);
    assertEquals(2, p.ttaVersion());
    assertEquals(0, p.sampleFrames());
    assertEquals(0, p.channels());
    p = new cz.skyba.taglib.trueaudio.Properties(new ByteVector("TTA1"), 79517);
    assertEquals(1, p.ttaVersion());
    assertEquals(0, p.sampleFrames());
    assertEquals(0, p.sampleRate());
    p = new cz.skyba.taglib.trueaudio.Properties(new ByteVector(new byte[]{'T', 'T', 'A', (byte) 255}), 79517);
    assertEquals(-49, p.ttaVersion());
    p = new cz.skyba.taglib.trueaudio.Properties(new ByteVector("NOPE"), 79517);
    assertEquals(0, p.ttaVersion());
  }

  @Test
  void trueAudioSignedFieldsAndSuppliedStreamLengthAreUsedWithoutNormalization() {
    var data = tta(44100, 44100);
    data.data()[6] = (byte) 255;
    data.data()[7] = (byte) 255;
    var p = new cz.skyba.taglib.trueaudio.Properties(data, -1, ReadStyle.FAST);
    assertEquals(-1, p.channels());
    assertEquals(1000, p.lengthInMilliseconds());
    assertEquals(0, p.bitrate());
    p = new cz.skyba.taglib.trueaudio.Properties(data, 1000);
    data.clear();
    assertEquals(8, p.bitrate());
    assertEquals(44100, p.sampleFrames());
    assertEquals(1, p.ttaVersion());
  }

  @Test
  void dsdiffNativeAcceptsUnrecognizedAndShortPropMarkers() throws Exception {
    for (byte[] marker : new byte[][]{{'x', 'x', 'x', 'x'}, {}, {'x'}, {'x', 'x'}, {'x', 'x', 'x'}}) {
      byte[] fields = marker.length == 4 ? join(marker, dffChunk("FS  ", new byte[]{0, 43, 17, 0}), dffChunk("CHNL", new byte[]{0, 2})) : marker;
      var stream = new ByteVectorStream(dffDocument(fields, dffChunk("DSD ", new byte[128])));
      var p = new cz.skyba.taglib.dsdiff.Properties(stream);
      assertEquals(marker.length == 4 ? 2822400 : 0, p.sampleRate());
      assertEquals(marker.length == 4 ? 2 : 0, p.channels());
      assertEquals(1, p.bitsPerSample());
      assertTrue(stream.isOpen());
      stream.seek(0);
      try (var ref = new AudioFile(stream)) {
        assertTrue(ref.isOpen());
      }
    }
  }

  @Test
  void dsdiffEmptyScalarPayloadReadsTheNextChunkIdentifier() throws Exception {
    var fields = join(new byte[]{'S', 'N', 'D', ' '}, dffChunk("FS  ", new byte[0]), dffChunk("CHNL", new byte[]{0, 2}));
    var p = new cz.skyba.taglib.dsdiff.Properties(new ByteVectorStream(dffDocument(fields, dffChunk("DSD ", new byte[128]))));
    assertEquals(1128812108, p.sampleRate());
    assertEquals(2, p.channels());
    assertEquals(512, p.sampleCount());
    fields = join(new byte[]{'S', 'N', 'D', ' '}, dffChunk("FS  ", new byte[]{0, 43, 17, 0}), dffChunk("CHNL", new byte[0]));
    p = new cz.skyba.taglib.dsdiff.Properties(new ByteVectorStream(dffDocument(fields, dffChunk("DSD ", new byte[128]))));
    assertEquals(17491, p.channels());
    assertEquals(2822400, p.sampleRate());
    assertEquals(0, p.sampleCount());
  }

  @Test
  void dsdiffStopsAtFirstFrameInfoBeforeMalformedFollowingHeaders() throws Exception {
    var fields = join(new byte[]{'S', 'N', 'D', ' '}, dffChunk("FS  ", new byte[]{0, 43, 17, 0}), dffChunk("CHNL", new byte[]{0, 2}));
    var audio = dffChunk("DST ", join(dffChunk("FRTE", new byte[]{0, 0, 0, 75, 0, 75}), new byte[12]));
    var p = new cz.skyba.taglib.dsdiff.Properties(new ByteVectorStream(dffDocument(fields, audio)));
    assertEquals(2822400, p.sampleCount());
    assertEquals(1000, p.lengthInMilliseconds());
    audio = dffChunk("DST ", join(dffChunk("FRTE", new byte[]{0, 0, 0, 75}), dffChunk("DSTF", new byte[128])));
    p = new cz.skyba.taglib.dsdiff.Properties(new ByteVectorStream(dffDocument(fields, audio)));
    assertEquals(12102, p.sampleCount());
  }

  @Test
  void dsdiffPropertiesAccumulateAcrossRepeatedRootContainers() throws Exception {
    var first = dffChunk("PROP", join(new byte[]{'S', 'N', 'D', ' '}, dffChunk("FS  ", new byte[]{0, 43, 17, 0})));
    var second = dffChunk("PROP", join(new byte[]{'S', 'N', 'D', ' '}, dffChunk("CHNL", new byte[]{0, 2})));
    var data = dffChunk("FRM8", join(new byte[]{'D', 'S', 'D', ' '}, first, second, dffChunk("DSD ", new byte[128])));
    var p = new cz.skyba.taglib.dsdiff.Properties(new ByteVectorStream(data));
    assertEquals(2822400, p.sampleRate());
    assertEquals(2, p.channels());
    assertEquals(512, p.sampleCount());
  }

  private static byte[] dffDocument(byte[] properties, byte[] audio) {
    return dffChunk("FRM8", join(new byte[]{'D', 'S', 'D', ' '}, dffChunk("PROP", properties), audio));
  }

  private static byte[] dffChunk(String id, byte[] data) {
    return ByteBuffer.allocate(12 + data.length + (data.length & 1)).order(ByteOrder.BIG_ENDIAN)
      .put(id.getBytes(StandardCharsets.ISO_8859_1)).putLong(data.length).put(data).array();
  }

  private static byte[] join(byte[]... data) {
    var result = new ByteArrayOutputStream();
    for (var part : data) result.writeBytes(part);
    return result.toByteArray();
  }

}
