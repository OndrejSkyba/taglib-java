package cz.skyba.taglib.parity.trackers;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("trackers")
class TrackerAudioPropertiesAndHeadersTest {
  @Test
  void modS3mItAndXmPropertiesAndPartialHeadersMatchCpp() throws Exception {
    BatchedComparison.compare(
      "tracker-properties",
      "tracker-properties",
      "cz.skyba.taglib.parity.trackers.probes.TrackerPropertiesProbe",
      12523L,
      "");
  }
}
