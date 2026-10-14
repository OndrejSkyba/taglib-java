package cz.skyba.taglib.parity.ogg;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("ogg")
class OggCodecAudioPropertiesTest {
  @Test
  void vorbisOpusSpeexAndFlacPropertiesMatchCpp() throws Exception {
    BatchedComparison.compare(
      "ogg-properties",
      "ogg-properties",
      "cz.skyba.taglib.parity.ogg.probes.OggPropertiesProbe",
      9016L,
      "");
  }
}
