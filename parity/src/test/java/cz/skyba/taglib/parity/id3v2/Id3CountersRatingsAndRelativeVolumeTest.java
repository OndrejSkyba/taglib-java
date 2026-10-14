package cz.skyba.taglib.parity.id3v2;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("id3v2")
class Id3CountersRatingsAndRelativeVolumeTest {
  @Test
  void ownershipCountersRatingsChannelsAndPeakValuesMatchCpp() throws Exception {
    BatchedComparison.compare(
      "id3-counters-ratings-and-relative-volume",
      "id3-counters-ratings-and-relative-volume",
      "cz.skyba.taglib.id3.v2.Id3NumericProbe",
      140837L,
      "cases.tsv");
  }

  @Test
  void finiteVolumeConversionsMatchCppAndUnsafeConversionsKeepJavaState() throws Exception {
    var directory =
      cz.skyba.taglib.parity.support.Parity.directory("relative-volume-conversion-boundaries");
    java.util.Set<String> defined =
      java.util.Set.of("427fffff", "c2800000", "c28000ff", "00000000", "80000000", "00000001");
    long compared = 0;
    for (String bits :
      java.util.List.of(
        "42800000",
        "427fffff",
        "c2800000",
        "c28000ff",
        "c2800100",
        "7f800000",
        "ff800000",
        "7fc00000",
        "ffc00001",
        "7f7fffff",
        "ff7fffff",
        "00000000",
        "80000000",
        "00000001")) {
      var actual = directory.resolve(bits + ".java.txt");
      cz.skyba.taglib.parity.support.Parity.javaRun(
        actual, "cz.skyba.taglib.parity.id3v2.probes.Id3VolumeBoundaryProbe", bits);
      if (defined.contains(bits)) {
        var expected = directory.resolve(bits + ".native.txt");
        cz.skyba.taglib.parity.support.Parity.nativeRun(expected, "id3-volume-boundary", bits);
        cz.skyba.taglib.parity.support.Parity.transcripts(expected, actual);
        compared++;
      } else
        org.junit.jupiter.api.Assertions.assertEquals(
          "REJECTED; state unchanged\n", java.nio.file.Files.readString(actual));
    }
    cz.skyba.taglib.parity.support.Parity.metrics("operations", compared);
    cz.skyba.taglib.parity.support.Parity.metrics("adaptationAssertions", 14 - compared);
  }
}
