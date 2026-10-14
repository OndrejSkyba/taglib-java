package cz.skyba.taglib.ogg;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.TagBase;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OggPropertiesTest {
  @TempDir
  Path directory;

  private static byte[] page(int sequence, int flags, long granule, byte[] packet) {
    return ByteBuffer.allocate(28 + packet.length).order(ByteOrder.LITTLE_ENDIAN)
      .put(new byte[]{'O', 'g', 'g', 'S', 0, (byte) flags}).putLong(granule)
      .putInt(1).putInt(sequence).putInt(0).put((byte) 1).put((byte) packet.length)
      .put(packet).array();
  }

  private static byte[] opus() {
    return ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).put("OpusHead".getBytes())
      .put((byte) 1).put((byte) 2).putShort((short) 312).putInt(44100)
      .putShort((short) -17920).put((byte) 0).array();
  }

  private static byte[] vorbis() {
    return ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN).put("\u0001vorbis".getBytes())
      .putInt(0).put((byte) 2).putInt(44100).putInt(-1).putInt(112000)
      .putInt(-2).putShort((short) 0).array();
  }

  private static byte[] speex() {
    var b = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN);
    b.put("Speex   ".getBytes()).putInt(28, 1).putInt(36, 44100).putInt(48, 2).putInt(52, -1);
    return b.array();
  }

  private static ByteVectorStream stream(String ext, byte[] header, long start, long end) {
    var comment = new ByteVector(ext.equals("opus") ? "OpusTags" : ext.equals("ogg") ? "\u0003vorbis" : "")
      .append(new ByteVector(8));
    var data = new ByteVector(page(0, 2, start, header)).append(new ByteVector(page(1, 0, 0, comment.toByteArray())));
    if (ext.equals("ogg")) data.append(new ByteVector(page(2, 0, 0, new byte[]{5, 'v', 'o', 'r', 'b', 'i', 's'})));
    data.append(new ByteVector(page(ext.equals("ogg") ? 3 : 2, 4, end, new byte[64])));
    return new ByteVectorStream(data);
  }

  @Test
  void opusDecodesUnsignedFieldsSignedGainAndFixedOutputClock() throws Exception {
    var p = new cz.skyba.taglib.ogg.opus.Properties(stream("opus", opus(), 0, 48312));
    assertEquals(1, p.opusVersion());
    assertEquals(2, p.channels());
    assertEquals(48000, p.sampleRate());
    assertEquals(44100, p.inputSampleRate());
    assertEquals(-17920, p.outputGain());
    assertEquals(1000, p.lengthInMilliseconds());
    assertEquals(1, p.lengthInSeconds());
    assertEquals(1, p.bitrate());
  }

  @Test
  void opusPartialNumericFieldsUseAvailableBytesWithoutSignExtension() throws Exception {
    var id = opus();
    id[10] = (byte) 128;
    var p = new cz.skyba.taglib.ogg.opus.Properties(stream("opus", Arrays.copyOf(id, 11), 0, 48000));
    assertEquals(997, p.lengthInMilliseconds());
    assertEquals(0, p.inputSampleRate());
    assertEquals(0, p.outputGain());
    id[12] = (byte) 255;
    p = new cz.skyba.taglib.ogg.opus.Properties(stream("opus", Arrays.copyOf(id, 13), 0, 48312));
    assertEquals(255, p.inputSampleRate());
    id[16] = (byte) 255;
    p = new cz.skyba.taglib.ogg.opus.Properties(stream("opus", Arrays.copyOf(id, 17), 0, 48312));
    assertEquals(255, p.outputGain());
    id[17] = (byte) 255;
    p = new cz.skyba.taglib.ogg.opus.Properties(stream("opus", Arrays.copyOf(id, 18), 0, 48312));
    assertEquals(-1, p.outputGain());
  }

  @Test
  void vorbisRetainsAllSignedBitrateFieldsAndNominalFallback() throws Exception {
    var p = new cz.skyba.taglib.ogg.vorbis.Properties(stream("ogg", vorbis(), 0, 0));
    assertEquals(0, p.vorbisVersion());
    assertEquals(-1, p.bitrateMaximum());
    assertEquals(112000, p.bitrateNominal());
    assertEquals(-2, p.bitrateMinimum());
    assertEquals(112, p.bitrate());
    assertEquals(0, p.lengthInMilliseconds());
    var id = vorbis();
    ByteBuffer.wrap(id).order(ByteOrder.LITTLE_ENDIAN).putInt(12, -1).putInt(20, 500);
    p = new cz.skyba.taglib.ogg.vorbis.Properties(stream("ogg", id, 0, 44100));
    assertEquals(-1, p.sampleRate());
    assertEquals(1, p.bitrate());
    assertEquals(0, p.lengthInMilliseconds());
  }

  @Test
  void shortVorbisAndSpeexHeadersLeaveEveryPropertyAtItsDefault() throws Exception {
    var v = new cz.skyba.taglib.ogg.vorbis.Properties(stream("ogg", Arrays.copyOf(vorbis(), 27), 0, 44100));
    assertEquals(AudioProperties.EMPTY, v.common());
    assertEquals(0, v.bitrateNominal());
    assertEquals(0, v.vorbisVersion());
    var s = new cz.skyba.taglib.ogg.speex.Properties(stream("spx", Arrays.copyOf(speex(), 63), 0, 44100));
    assertEquals(AudioProperties.EMPTY, s.common());
    assertEquals(0, s.bitrateNominal());
    assertEquals(0, s.speexVersion());
  }

  @Test
  void speexKeepsVersionAndNominalSignedness() throws Exception {
    var p = new cz.skyba.taglib.ogg.speex.Properties(stream("spx", speex(), 0, 44100));
    assertEquals(1, p.speexVersion());
    assertEquals(-1, p.bitrateNominal());
    assertEquals(44100, p.sampleRate());
    assertEquals(2, p.channels());
    assertEquals(1000, p.lengthInMilliseconds());
    assertEquals(1, p.bitrate());
  }

  @Test
  void extremeGranulesRetainFieldsWithoutOverflowingDuration() throws Exception {
    var p = new cz.skyba.taglib.ogg.opus.Properties(stream("opus", opus(), 0, Long.MAX_VALUE));
    assertEquals(0, p.lengthInMilliseconds());
    assertEquals(0, p.bitrate());
    assertEquals(44100, p.inputSampleRate());
    // This decreasing extreme would overflow native signed subtraction; Java keeps zero duration.
    p = new cz.skyba.taglib.ogg.opus.Properties(stream("opus", opus(), Long.MAX_VALUE, 0));
    assertEquals(0, p.lengthInMilliseconds());
    assertEquals(0, p.bitrate());
    p = new cz.skyba.taglib.ogg.opus.Properties(stream("opus", opus(), 0, 312));
    assertEquals(0, p.lengthInMilliseconds());
  }

  @Test
  void snapshotsAreDetachedAndBorrowedStreamsRemainOpenForEveryReadStyle() throws Exception {
    var data = stream("opus", opus(), 0, 48312).data().toByteArray();
    var path = Files.write(directory.resolve("properties.bin"), data);
    var defaultProperties = new cz.skyba.taglib.ogg.opus.Properties(path);
    for (var style : ReadStyle.values()) {
      var s = new ByteVectorStream(data);
      s.seek(7);
      var p = new cz.skyba.taglib.ogg.opus.Properties(s, style);
      assertEquals(defaultProperties.common(), p.common());
      assertTrue(s.isOpen());
      s.data().clear();
      assertEquals(44100, p.inputSampleRate());
      assertEquals(-17920, p.outputGain());
      assertEquals(1000, p.lengthInMilliseconds());
    }
    Files.delete(path);
    assertEquals(1000, defaultProperties.lengthInMilliseconds());
  }

  @Test
  void existingPacketIndexAcceptsRawIdentificationWithoutMetadataValidation() throws Exception {
    var s = stream("opus", opus(), 0, 48312);
    var f = new OggFile(s) {
      public TagBase tag() {
        return null;
      }

      public AudioProperties audioProperties() {
        return null;
      }
    };
    f.packet(1);
    var p = new cz.skyba.taglib.ogg.opus.Properties(f);
    assertEquals(1000, p.lengthInMilliseconds());
    f.close();
    assertTrue(s.isOpen());
    assertEquals(-17920, p.outputGain());
    var empty = new OggFile(new ByteVectorStream(new byte[0])) {
      public TagBase tag() {
        return null;
      }

      public AudioProperties audioProperties() {
        return null;
      }
    };
    p = new cz.skyba.taglib.ogg.opus.Properties(empty, ReadStyle.FAST);
    assertEquals(48000, p.sampleRate());
    assertEquals(0, p.channels());
    assertEquals(0, p.opusVersion());
    assertEquals(0, p.outputGain());
  }
}
