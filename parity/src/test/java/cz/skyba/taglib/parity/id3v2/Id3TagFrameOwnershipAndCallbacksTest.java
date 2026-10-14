package cz.skyba.taglib.parity.id3v2;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("id3v2")
class Id3TagFrameOwnershipAndCallbacksTest {
  @Test
  void frameOwnershipFactoryCallbacksAndPropertyMapsMatchCpp() throws Exception {
    BatchedComparison.compare(
      "id3-owner", "id3-owner", "cz.skyba.taglib.id3.v2.Id3OwnerProbe", 31125L, "cases.tsv");
    checkUnsafeInputRejections();
  }

  private void checkUnsafeInputRejections() throws Exception {
    var directory =
      cz.skyba.taglib.parity.support.Parity.directory("Id3TagFrameOwnershipAndCallbacksTest");
    var output = directory.resolve("rejected-inputs.java.txt");
    cz.skyba.taglib.parity.support.Parity.javaRun(
      output, "cz.skyba.taglib.parity.id3v2.probes.Id3OwnerRemovalProbe");
    org.junit.jupiter.api.Assertions.assertEquals(
      "tag-missing\tIllegalStateException\n"
        + "tag-stale\tIllegalStateException\n"
        + "chapter-missing\tIllegalStateException\n"
        + "chapter-stale\tIllegalStateException\n",
      java.nio.file.Files.readString(output));
  }
}
