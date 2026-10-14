package cz.skyba.taglib.parity.id3v1;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("id3v1")
class Id3v1AndRiffInfoReparsingTest {
  @Test
  void reparsingCallbacksBorrowedStreamsAndRepeatedSavesMatchCpp() throws Exception {
    BatchedComparison.compare(
      "id3v1-riff-info-reparsing",
      "id3v1-riff-info-reparsing",
      "cz.skyba.taglib.parity.id3v1.probes.Id3v1AndRiffInfoReparsingProbe",
      14948L,
      "");
  }
}
