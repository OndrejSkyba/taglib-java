package cz.skyba.taglib.parity.asf;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("asf")
class AsfTypedAttributesAndRepeatedSaveTest {
  @Test
  void typedAttributesLanguageStreamIdsAndRepeatedSavesMatchCpp() throws Exception {
    BatchedComparison.compare(
      "asf-mutable", "asf-mutable", "cz.skyba.taglib.parity.asf.probes.AsfMutableProbe", 161179L, "");
  }
}
