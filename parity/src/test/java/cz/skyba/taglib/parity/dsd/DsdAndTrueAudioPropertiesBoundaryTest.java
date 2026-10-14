package cz.skyba.taglib.parity.dsd;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("dsd")
class DsdAndTrueAudioPropertiesBoundaryTest {
  @Test
  void partialHeadersNumericBoundariesAndDsdChunksMatchCpp() throws Exception {
    BatchedComparison.compare(
      "dsd-tta-properties",
      "dsd-tta-properties",
      "cz.skyba.taglib.parity.dsd.probes.DsdTtaPropertiesProbe",
      4544L,
      "");
  }
}
