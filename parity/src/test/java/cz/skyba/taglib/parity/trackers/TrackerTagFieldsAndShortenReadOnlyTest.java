package cz.skyba.taglib.parity.trackers;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("trackers")
class TrackerTagFieldsAndShortenReadOnlyTest {
  @Test
  void trackerTagFieldsAndShortenReadOnlyBehaviorMatchCpp() throws Exception {
    BatchedComparison.compare(
      "tracker-support",
      "tracker-support",
      "cz.skyba.taglib.parity.trackers.probes.TrackerSupportProbe",
      4925L, // Rejected Shorten opens have no save operation (128 former rows).
      "");
  }
}
