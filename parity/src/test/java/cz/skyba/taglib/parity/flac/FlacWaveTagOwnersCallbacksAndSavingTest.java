package cz.skyba.taglib.parity.flac;

import cz.skyba.taglib.parity.fixtures.FixtureInputs;
import cz.skyba.taglib.parity.support.BatchedComparison;
import cz.skyba.taglib.parity.support.Parity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("parity")
@Tag("flac")
class FlacWaveTagOwnersCallbacksAndSavingTest {
  @Test
  void flacAndWaveTagOwnersConstructorsCallbacksAndSavedBytesMatchCpp() throws Exception {
    var directory =
      FixtureInputs.generate("flac-wave-tag-owners", Parity.directory("flac-wave-tag-owners"));
    long rows = 0;
    for (String[] pair :
      new String[][]{
        {"flac-wave-tag-owners", "cz.skyba.taglib.parity.FlacWaveTagOwnerProbe"},
        {
          "flac-wave-constructors-and-callbacks",
          "cz.skyba.taglib.parity.FlacWaveConstructorsAndCallbacksProbe"
        }
      }) {
      var n = directory.resolve(pair[0] + ".native.txt");
      var j = directory.resolve(pair[0] + ".java.txt");
      Parity.nativeRun(n, pair[0], directory.toString());
      Parity.javaRun(j, pair[1], directory.toString());
      rows += Parity.transcripts(n, j);
      Parity.uniqueRows(n);
    }
    assertEquals(119002L, rows);
    Parity.metrics("operations", rows);
    Parity.metrics("savedFiles", BatchedComparison.savedFiles(directory));
  }
}
