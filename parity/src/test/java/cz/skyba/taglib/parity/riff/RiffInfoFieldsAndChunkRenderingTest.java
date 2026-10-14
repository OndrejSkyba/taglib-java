package cz.skyba.taglib.parity.riff;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("riff")
class RiffInfoFieldsAndChunkRenderingTest {
  @Test
  void fieldsPropertiesAndSerializedChunksMatchCpp() throws Exception {
    BatchedComparison.compare(
      null, "riff-info", "cz.skyba.taglib.parity.riff.probes.RiffInfoProbe", 0L, null);
  }
}
