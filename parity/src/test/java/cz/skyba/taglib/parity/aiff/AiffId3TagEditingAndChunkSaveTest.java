package cz.skyba.taglib.parity.aiff;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("aiff")
class AiffId3TagEditingAndChunkSaveTest {
  @Test
  void tagEditsChunkOffsetsAndSerializedFilesMatchCpp() throws Exception {
    BatchedComparison.compare(
      "aiff-files", "aiff-file", "cz.skyba.taglib.riff.AiffFileProbe", 33906L, "");
  }
}
