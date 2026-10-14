package cz.skyba.taglib.parity.dsd;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("dsd")
class DsfMetadataOffsetsAndTagSaveTest {
  @Test
  void metadataOffsetsId3TagsAndCompleteSavesMatchCpp() throws Exception {
    BatchedComparison.compare(
      "dsf-files", "dsf-file", "cz.skyba.taglib.parity.dsd.probes.DsfFileProbe", 30213L, "");
    checkUnsafeInputRejections();
  }

  private void checkUnsafeInputRejections() throws Exception {
    var directory = cz.skyba.taglib.parity.support.Parity.directory("DsfMetadataOffsetsAndTagSaveTest");
    var output = directory.resolve("rejected-inputs.java.txt");
    cz.skyba.taglib.parity.support.Parity.javaRun(
      output,
      "cz.skyba.taglib.parity.dsd.probes.DsfFileBoundaryProbe",
      directory.resolve("missing.dsf").toString());
    org.junit.jupiter.api.Assertions.assertEquals(
      "missing-open\tUncheckedIOException\ninvalid-open\tTagLibException\n",
      java.nio.file.Files.readString(output));
  }
}
