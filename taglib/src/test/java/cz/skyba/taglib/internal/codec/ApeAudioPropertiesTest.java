package cz.skyba.taglib.internal.codec;

import cz.skyba.taglib.Format;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApeAudioPropertiesTest {
  @TempDir
  Path directory;
  private final Map<String, byte[]> fixtures = ApeAudioFixtures.cases();

  private cz.skyba.taglib.ape.Properties ape(String name) throws Exception {
    return new cz.skyba.taglib.ape.Properties(new ByteVectorStream(fixtures.get(name + ".ape")));
  }

  private cz.skyba.taglib.wavpack.Properties wv(String name) throws Exception {
    return new cz.skyba.taglib.wavpack.Properties(new ByteVectorStream(fixtures.get(name + ".wv")));
  }

  private cz.skyba.taglib.mpc.Properties mpc(String name) throws Exception {
    return new cz.skyba.taglib.mpc.Properties(new ByteVectorStream(fixtures.get(name + ".mpc")));
  }

  @Test
  void apeFindsDisplacedHeaderAndPreservesVersionOnPartialHeaders() throws Exception {
    var base = ape("ape-current");
    var displaced = ape("ape-garbage-prefix");
    assertEquals(4000, base.version());
    assertEquals(222184, base.sampleFrames());
    assertEquals(24, base.bitsPerSample());
    assertEquals(base.sampleFrames(), displaced.sampleFrames());
    assertEquals(base.lengthInMilliseconds(), displaced.lengthInMilliseconds());
    assertTrue(displaced.bitrate() > base.bitrate());
    assertEquals(base.common(), ape("ape-id3-prefix").common());
    assertEquals(4000, ape("ape-short").version());
    assertEquals(0, ape("ape-short").channels());
    assertEquals(4000, ape("ape-small-descriptor").version());
    assertEquals(0, ape("ape-small-descriptor").sampleFrames());
  }

  @Test
  void apeOldVersionBlocksAndUnsignedCountsMatchNativeArithmetic() throws Exception {
    assertEquals(3 * 9216 + 1000, ape("ape-old-3770").sampleFrames());
    assertEquals(3 * 73728 + 1000, ape("ape-old-3800").sampleFrames());
    assertEquals(3 * 73728 + 1000, ape("ape-old-3900").sampleFrames());
    assertEquals(3 * 294912 + 1000, ape("ape-old-3950").sampleFrames());
    assertEquals(24, ape("ape-old-3950").bitsPerSample());
    assertEquals(0, ape("ape-empty-old").channels());
    assertEquals(2, ape("ape-empty-current").channels());
    assertEquals(-1, ape("ape-signed-fields").channels());
    assertEquals(-2, ape("ape-signed-fields").bitsPerSample());
    assertEquals(
      ((0xffffffffL - 1) * 73728 + 1000) & 0xffffffffL, ape("ape-overflow-count").sampleFrames());
  }

  @Test
  void wavpackFindsCustomRatesAndDsdShiftWithoutTruncationOrMasking() throws Exception {
    assertEquals(34554432, wv("wv-custom-four-byte").sampleRate());
    assertEquals(1000000, wv("wv-custom-three-byte").sampleRate());
    assertEquals(0, wv("wv-custom-invalid-width").sampleRate());
    assertTrue(wv("wv-dsd").isDsd());
    assertEquals(44100 * 8, wv("wv-dsd").sampleRate());
    assertEquals(44100, wv("wv-dsd-invalid-shift").sampleRate());
    assertEquals(44100 * 4, wv("wv-dsd-later-valid-shift").sampleRate());
    assertEquals(44100 * 2, wv("wv-dsd-standard-ignores-custom").sampleRate());
  }

  @Test
  void wavpackCountsChannelsAndRecoversOnlyValidatedFinalBlocks() throws Exception {
    assertEquals(3, wv("wv-multichannel").channels());
    assertEquals(0x407, wv("wv-multichannel").version());
    assertTrue(wv("wv-stereo").isLossless());
    assertFalse(wv("wv-hybrid").isLossless());
    assertEquals(wv("wv-stereo").sampleFrames(), wv("wv-many-empty-blocks").sampleFrames());
    assertEquals(1200, wv("wv-unknown-count").sampleFrames());
    assertEquals(1200, wv("wv-spurious-tail").sampleFrames());
    assertEquals(16, wv("wv-wrapped-final-count").sampleFrames());
    assertEquals(0x411, wv("wv-invalid-version").version());
    assertEquals(0, wv("wv-invalid-version").sampleRate());
  }

  @Test
  void musepackSv7ReplayGainAndUnsignedFrameOverflow() throws Exception {
    var p = mpc("mpc-sv7");
    assertEquals(7, p.mpcVersion());
    assertEquals(100, p.totalFrames());
    assertEquals(114624, p.sampleFrames());
    assertEquals(17426, p.trackGain());
    assertEquals(23119, p.trackPeak());
    assertEquals(16269, p.albumGain());
    assertEquals(21578, p.albumPeak());
    assertEquals((0xffffffffL * 1152 - 576) & 0xffffffffL, mpc("mpc-sv7-overflow").sampleFrames());
    assertEquals(0xfffffdc0L, mpc("mpc-sv7-zero").sampleFrames());
    assertEquals(100, mpc("mpc-unrecognized-id3-version").totalFrames());
    assertEquals(100, mpc("mpc-invalid-id3-size").totalFrames());
    assertEquals(100, mpc("mpc-id3v3-footer").totalFrames());
    assertEquals(7, mpc("mpc-truncated-sv7").mpcVersion());
    assertEquals(192, mpc("mpc-sv5").bitrate());
  }

  @Test
  void musepackSv8ExposesRawSampleCountAndReleasePacketSelection() throws Exception {
    var p = mpc("mpc-sv8-sh-first");
    assertEquals(8, p.mpcVersion());
    assertEquals(123456, p.sampleFrames());
    assertEquals(3251, p.lengthInMilliseconds());
    assertEquals(37800, p.sampleRate());
    assertEquals(6, p.channels());
    assertEquals(0, p.trackGain());
    var rg = mpc("mpc-sv8-rg-first");
    assertEquals(0, rg.mpcVersion());
    assertEquals((short) 0xabcd, rg.trackGain());
    assertEquals((short) 0xfedc, rg.trackPeak());
    assertEquals((short) 0x8001, rg.albumPeak());
    assertEquals(0, mpc("mpc-sv8-short-rg").trackGain());
    assertEquals(123456, mpc("mpc-sv8-unknown-packet").sampleFrames());
    assertEquals(16383, mpc("mpc-sv8-truncated-sh").sampleFrames());
    assertEquals(-1L, mpc("mpc-sv8-unsigned-count").sampleFrames());
    assertEquals(-120, mpc("mpc-sv8-signed-version").mpcVersion());
    assertEquals(0, mpc("mpc-sv8-id3-short").mpcVersion());
    assertEquals(123456, mpc("mpc-sv8-id3-padded").sampleFrames());
  }

  @Test
  void publicPropertiesAndCodecShareParsingForEveryFixtureAndReadStyle() throws Exception {
    for (var entry : fixtures.entrySet()) {
      Path path = directory.resolve(entry.getKey());
      Files.write(path, entry.getValue());
      Format format =
        entry.getKey().endsWith(".ape")
          ? Format.APE
          : entry.getKey().endsWith(".wv") ? Format.WAVPACK : Format.MPC;
      for (ReadStyle style : ReadStyle.values()) {
        var detailed =
          switch (format) {
            case APE -> new cz.skyba.taglib.ape.Properties(path, style).common();
            case WAVPACK -> new cz.skyba.taglib.wavpack.Properties(path, style).common();
            default -> new cz.skyba.taglib.mpc.Properties(path, style).common();
          };
        assertEquals(
          detailed,
          new ApeCodec(format)
            .read(path, new ReadOptions(true, style, 64 * 1024 * 1024))
            .audioProperties(),
          entry.getKey());
      }
    }
  }
}
