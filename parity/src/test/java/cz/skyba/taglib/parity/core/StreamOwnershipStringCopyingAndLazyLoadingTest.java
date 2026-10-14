package cz.skyba.taglib.parity.core;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("core")
class StreamOwnershipStringCopyingAndLazyLoadingTest {
  @Test
  void defaultValuesOwnedDescriptorsEofAndLazyInitializationMatchCpp() throws Exception {
    var directory = cz.skyba.taglib.parity.support.Parity.directory("stream-ownership-and-lazy-loading");
    var n = directory.resolve("native.txt");
    var j = directory.resolve("java.txt");
    cz.skyba.taglib.parity.support.Parity.nativeRun(
      n,
      "stream-ownership-copying-and-lazy-loading",
      directory.toString(),
      cz.skyba.taglib.parity.support.Parity.corpus().toString());
    cz.skyba.taglib.parity.support.Parity.javaRun(
      j,
      "cz.skyba.taglib.parity.core.probes.StreamOwnershipStringCopyingAndLazyLoadingProbe",
      directory.toString(),
      cz.skyba.taglib.parity.support.Parity.corpus().toString(),
      "RELEASE");
    long rows = cz.skyba.taglib.parity.support.Parity.transcripts(n, j);
    org.junit.jupiter.api.Assertions.assertEquals(90, rows);
    cz.skyba.taglib.parity.support.Parity.metrics("operations", rows);
  }
}
