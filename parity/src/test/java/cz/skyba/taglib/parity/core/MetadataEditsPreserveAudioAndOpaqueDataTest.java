package cz.skyba.taglib.parity.core;

import cz.skyba.taglib.parity.Harness;
import cz.skyba.taglib.parity.support.Parity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("parity")
@Tag("core")
@Tag("preservation")
class MetadataEditsPreserveAudioAndOpaqueDataTest {
  @Test
  void metadataAndArtworkSavesMatchCppAndPreserveEncodedAudio() throws Exception {
    var directory = Parity.directory("media-preservation");
    var report =
      Harness.verify(
        new String[]{
          "verify",
          Parity.nativeProbe("oracle").toString(),
          Parity.corpus().toString(),
          directory.toString()
        });
    var summary = report.get("summary");
    assertTrue(summary.get("strictPassed").asBoolean(), summary.toPrettyString());
    Parity.metrics("fixtures", summary.get("totalFixtures").asLong());
    Parity.metrics("savedFiles", summary.get("completeOutputComparisons").asLong());
  }
}
