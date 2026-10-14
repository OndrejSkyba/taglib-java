package cz.skyba.taglib.parity.ape;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("ape")
class ApeItemsFootersAndTagRenderingTest {
  @Test
  void itemsFootersAndTagRenderingMatchCpp() throws Exception {
    BatchedComparison.compare(
      null, "ape-parity", "cz.skyba.taglib.parity.ape.probes.ApeTagValuesProbe", 0L, null);
  }
}
