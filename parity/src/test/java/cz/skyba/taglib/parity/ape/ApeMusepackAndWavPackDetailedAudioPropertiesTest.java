package cz.skyba.taglib.parity.ape;

import cz.skyba.taglib.parity.support.Parity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("ape")
@Tag("mpc")
@Tag("wavpack")
class ApeMusepackAndWavPackDetailedAudioPropertiesTest {
  @Test
  void detailedAudioPropertyValuesMatchCppForSyntheticAndUpstreamFiles() throws Exception {
    var directory = Parity.directory("ape-musepack-wavpack-audio-properties");
    var n = directory.resolve("native.txt");
    var j = directory.resolve("java.txt");
    Parity.javaRun(
      j,
      "cz.skyba.taglib.parity.id3v2.probes.Id3AudioPropertiesProbe",
      directory.toString(),
      Parity.corpus().toString());
    Parity.nativeRun(n, "ape-musepack-wavpack-audio-properties", directory.toString());
    Parity.metrics("operations", Parity.transcripts(n, j));
  }
}
