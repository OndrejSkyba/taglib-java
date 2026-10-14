package cz.skyba.taglib.parity.toolkit;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("toolkit")
class ExtendedFloatEncodingAndDecodingTest {
  @Test
  void allGeneratedExponentMantissaAndOffsetConversionsMatchCpp() throws Exception {
    BatchedComparison.compare(
      null, "toolkit-float80", "cz.skyba.taglib.parity.toolkit.probes.ToolkitFloat80Probe", 0L, null);
  }
}
