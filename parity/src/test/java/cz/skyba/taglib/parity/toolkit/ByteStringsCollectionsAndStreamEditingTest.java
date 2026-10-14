package cz.skyba.taglib.parity.toolkit;

import cz.skyba.taglib.parity.support.Parity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("toolkit")
class ByteStringsCollectionsAndStreamEditingTest {
  @Test
  void propertyMapsByteStringsListsVariantsAndStreamEditingMatchCpp() throws Exception {
    var directory = Parity.directory("toolkit-operations");
    long rows = 0;
    String[][] pairs = {
      {"toolkit-parity", "cz.skyba.taglib.parity.toolkit.probes.PropertyMapsAndToolkitValuesProbe"},
      {"toolkit-operations", "cz.skyba.taglib.parity.toolkit.probes.ToolkitOperationsProbe"},
      {"tag-base", "cz.skyba.taglib.parity.toolkit.probes.TagBaseProbe"}
    };
    for (String[] pair : pairs) {
      var n = directory.resolve(pair[0] + ".native.txt");
      var j = directory.resolve(pair[0] + ".java.txt");
      if (pair[0].equals("toolkit-operations")) {
        Parity.nativeRun(n, pair[0], directory.resolve("native-stream.bin").toString());
        Parity.javaRun(j, pair[1], directory.resolve("java-stream.bin").toString());
      } else {
        Parity.nativeRun(n, pair[0]);
        Parity.javaRun(j, pair[1]);
      }
      rows += Parity.transcripts(n, j);
    }
    Parity.metrics("operations", rows);
  }
}
