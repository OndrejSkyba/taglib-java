package cz.skyba.taglib.parity.id3v2;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("id3v2")
class Id3HeadersFootersAndSynchSafeNumbersTest {
  @Test
  void headersFootersFlagsAndSynchSafeIntegersMatchCpp() throws Exception {
    BatchedComparison.compare(
      "id3-headers",
      "id3-header",
      "cz.skyba.taglib.parity.id3v2.probes.Id3HeaderProbe",
      214428L,
      "cases.tsv");
  }
}
