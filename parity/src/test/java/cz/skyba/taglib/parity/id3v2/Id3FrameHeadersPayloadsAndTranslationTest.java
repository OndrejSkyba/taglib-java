package cz.skyba.taglib.parity.id3v2;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("id3v2")
class Id3FrameHeadersPayloadsAndTranslationTest {
  @Test
  void frameHeadersPayloadBoundsEncodingsAndPropertyTranslationMatchCpp() throws Exception {
    BatchedComparison.compare(
      "id3-frame-headers-payloads-and-translation",
      "id3-frame-headers-payloads-and-translation",
      "cz.skyba.taglib.parity.id3v2.probes.Id3FrameBaseProbe",
      713347L,
      "cases.tsv");
  }
}
