package cz.skyba.taglib.parity.mpeg;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("mpeg")
class MpegAudioPropertiesBoundaryTest {
  @Test
  void frameCountsBitratesSampleRatesAndTaggedStreamsMatchCpp() throws Exception {
    BatchedComparison.compare(
      "mpeg-properties",
      "mpeg-properties",
      "cz.skyba.taglib.parity.mpeg.probes.MpegPropertiesProbe",
      29720L,
      "");
  }
}
