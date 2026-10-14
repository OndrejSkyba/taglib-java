package cz.skyba.taglib.parity.aiff;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("aiff")
class AiffSampleRatesAndAudioPropertiesTest {
  @Test
  void extendedFloatsPartialChunksAndAifcPropertiesMatchCpp() throws Exception {
    BatchedComparison.compare(
      "aiff-properties",
      "aiff-properties",
      "cz.skyba.taglib.parity.aiff.probes.AiffPropertiesProbe",
      3264L,
      "");
  }
}
