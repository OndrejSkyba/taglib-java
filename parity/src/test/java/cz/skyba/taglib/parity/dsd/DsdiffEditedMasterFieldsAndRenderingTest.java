package cz.skyba.taglib.parity.dsd;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("dsd")
class DsdiffEditedMasterFieldsAndRenderingTest {
  @Test
  void editedMasterFieldsUnicodeValuesAndVirtualTagHooksMatchCpp() throws Exception {
    var directory =
      cz.skyba.taglib.parity.fixtures.FixtureInputs.generate(
        "dsdiff-diin", cz.skyba.taglib.parity.support.Parity.directory("edited-master-fields"));
    var n = directory.resolve("native.txt");
    var j = directory.resolve("java.txt");
    cz.skyba.taglib.parity.support.Parity.nativeRun(n, "dsdiff-diin", directory.toString());
    cz.skyba.taglib.parity.support.Parity.javaRun(
      j, "cz.skyba.taglib.parity.dsd.probes.DsdiffDiinProbe", directory.toString(), "RELEASE");
    long rows = cz.skyba.taglib.parity.support.Parity.transcripts(n, j);
    org.junit.jupiter.api.Assertions.assertEquals(3169, rows);
    cz.skyba.taglib.parity.support.Parity.metrics("operations", rows);
  }
}
