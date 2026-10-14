package cz.skyba.taglib.parity.mpeg;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("mpeg")
class MpegTagCarrierEditingAndSaveTest {
  @Test
  void id3ApeTagCarriersOffsetsAndSavedBytesMatchCpp() throws Exception {
    BatchedComparison.compare(
      "mpeg-files", "mpeg-file", "cz.skyba.taglib.parity.mpeg.probes.MpegFileProbe", 58414L, "");
    checkUnsafeInputRejections();
  }

  private void checkUnsafeInputRejections() throws Exception {
    var directory = cz.skyba.taglib.parity.support.Parity.directory("MpegTagCarrierEditingAndSaveTest");
    var output = directory.resolve("rejected-inputs.java.txt");
    cz.skyba.taglib.parity.support.Parity.javaRun(
      output, "cz.skyba.taglib.parity.mpeg.probes.MpegFileBoundaryProbe");
    org.junit.jupiter.api.Assertions.assertEquals(
      "rejected\tfalse\tunchanged", java.nio.file.Files.readAllLines(output).get(1));
  }
}
