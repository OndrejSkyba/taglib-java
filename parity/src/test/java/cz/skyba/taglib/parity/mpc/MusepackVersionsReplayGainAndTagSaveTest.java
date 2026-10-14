package cz.skyba.taglib.parity.mpc;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("mpc")
class MusepackVersionsReplayGainAndTagSaveTest {
  @Test
  void sv5Sv7Sv8ReplayGainTagEditingAndSavedBytesMatchCpp() throws Exception {
    BatchedComparison.compare(
      "mpc-files", "mpc-file", "cz.skyba.taglib.parity.mpc.probes.MpcFileProbe", 113687L, "");
  }
}
