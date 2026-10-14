package cz.skyba.taglib.parity.matroska;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("matroska")
class MatroskaTrackPropertiesBoundaryTest {
  @Test
  void ebmlTrackTimingAndAudioPropertiesMatchCpp() throws Exception {
    BatchedComparison.compare(
      "matroska-properties",
      "matroska-properties",
      "cz.skyba.taglib.parity.matroska.probes.MatroskaPropertiesProbe",
      7124L,
      "");
  }
}
