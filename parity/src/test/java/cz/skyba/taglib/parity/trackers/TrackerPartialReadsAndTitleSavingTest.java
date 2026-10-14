package cz.skyba.taglib.parity.trackers;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("trackers")
class TrackerPartialReadsAndTitleSavingTest {
  @Test
  void partialOwnersTitlesInstrumentsAndSavedBytesMatchCpp() throws Exception {
    BatchedComparison.compare(
      "tracker-files",
      "tracker-file",
      "cz.skyba.taglib.parity.trackers.probes.TrackerFileProbe",
      31328L,
      "");
  }
}
