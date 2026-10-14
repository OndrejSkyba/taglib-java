package cz.skyba.taglib.parity.asf;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("asf")
class AsfAudioPropertiesAndCodecListTest {
  @Test
  void fileStreamObjectsCodecListsAndNumericBoundariesMatchCpp() throws Exception {
    BatchedComparison.compare(
      "asf-properties",
      "asf-properties",
      "cz.skyba.taglib.parity.asf.probes.AsfPropertiesProbe",
      7448L,
      "");
  }
}
