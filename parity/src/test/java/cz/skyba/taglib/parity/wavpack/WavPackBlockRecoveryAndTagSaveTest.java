package cz.skyba.taglib.parity.wavpack;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("wavpack")
class WavPackBlockRecoveryAndTagSaveTest {
  @Test
  void blockRecoveryLosslessPropertiesAndTagSavesMatchCpp() throws Exception {
    BatchedComparison.compare(
      "wavpack-files",
      "wavpack-file",
      "cz.skyba.taglib.parity.wavpack.probes.WavpackFileProbe",
      94004L,
      "");
  }
}
