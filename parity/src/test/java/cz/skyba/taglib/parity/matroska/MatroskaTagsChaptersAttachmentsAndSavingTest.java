package cz.skyba.taglib.parity.matroska;

import cz.skyba.taglib.parity.fixtures.FixtureInputs;
import cz.skyba.taglib.parity.support.BatchedComparison;
import cz.skyba.taglib.parity.support.Parity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("parity")
@Tag("matroska")
class MatroskaTagsChaptersAttachmentsAndSavingTest {
  @Test
  void tagTargetsChaptersAttachmentsCallbacksAndSavedFilesMatchCpp() throws Exception {
    var directory =
      FixtureInputs.generate("matroska-mutable", Parity.directory("matroska-mutable"));
    long rows = 0;
    for (String[] pair :
      new String[][]{
        {"matroska-mutable", "cz.skyba.taglib.parity.MatroskaMutableProbe"},
        {
          "matroska-mutable-subclasses-and-callbacks",
          "cz.skyba.taglib.parity.MatroskaMutableCallbacksAndSavingProbe"
        }
      }) {
      var n = directory.resolve(pair[0] + ".native.txt");
      var j = directory.resolve(pair[0] + ".java.txt");
      Parity.nativeRun(n, pair[0], directory.toString());
      Parity.javaRun(j, pair[1], directory.toString());
      rows += Parity.transcripts(n, j);
      Parity.uniqueRows(n);
    }
    assertEquals(15565L, rows);
    Parity.metrics("operations", rows);
    Parity.metrics("savedFiles", BatchedComparison.savedFiles(directory));
  }
}
