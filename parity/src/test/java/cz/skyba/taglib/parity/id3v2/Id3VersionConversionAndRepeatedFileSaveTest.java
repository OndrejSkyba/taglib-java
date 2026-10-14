package cz.skyba.taglib.parity.id3v2;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("id3v2")
class Id3VersionConversionAndRepeatedFileSaveTest {
  @Test
  void versionConversionCallbacksAndRepeatedFileSavesMatchCpp() throws Exception {
    BatchedComparison.compare(
      "id3-version-conversion-and-repeated-saves",
      "id3-version-conversion-and-repeated-saves",
      "cz.skyba.taglib.id3.v2.Id3ReleaseProbe",
      23529L,
      "");
  }
}
