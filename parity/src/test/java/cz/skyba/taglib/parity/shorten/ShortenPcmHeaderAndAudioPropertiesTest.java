package cz.skyba.taglib.parity.shorten;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("shorten")
class ShortenPcmHeaderAndAudioPropertiesTest {
  @Test
  void partialPcmHeadersAndArithmeticBoundariesMatchCpp() throws Exception {
    BatchedComparison.compare(
      "shorten-properties",
      "shorten-properties",
      "cz.skyba.taglib.parity.shorten.probes.ShortenPropertiesProbe",
      4336L,
      "");
  }
}
