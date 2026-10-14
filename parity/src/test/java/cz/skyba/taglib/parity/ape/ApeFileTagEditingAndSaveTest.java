package cz.skyba.taglib.parity.ape;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("ape")
class ApeFileTagEditingAndSaveTest {
  @Test
  void legacyAndApeTagsAudioPropertiesAndCompleteSavesMatchCpp() throws Exception {
    BatchedComparison.compare(
      "ape-files", "ape-file", "cz.skyba.taglib.parity.ape.probes.ApeFileProbe", 87320L, "");
  }
}
