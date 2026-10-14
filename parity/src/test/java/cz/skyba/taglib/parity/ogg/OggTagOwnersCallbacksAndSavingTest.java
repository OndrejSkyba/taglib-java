package cz.skyba.taglib.parity.ogg;

import cz.skyba.taglib.parity.fixtures.FixtureInputs;
import cz.skyba.taglib.parity.support.BatchedComparison;
import cz.skyba.taglib.parity.support.Parity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("parity")
@Tag("ogg")
class OggTagOwnersCallbacksAndSavingTest {
  @Test
  void vorbisOpusSpeexAndOggFlacOwnersCallbacksAndSavedBytesMatchCpp() throws Exception {
    var directory = FixtureInputs.generate("ogg-tag-owners", Parity.directory("ogg-tag-owners"));
    long rows = 0;
    for (String[] pair :
      new String[][]{
        {"ogg-tag-owners", "cz.skyba.taglib.parity.OggCodecTagOwnerProbe"},
        {"ogg-subclasses-and-callbacks", "cz.skyba.taglib.parity.OggCodecSubclassesAndCallbacksProbe"}
      }) {
      var n = directory.resolve(pair[0] + ".native.txt");
      var j = directory.resolve(pair[0] + ".java.txt");
      Parity.nativeRun(n, pair[0], directory.toString());
      Parity.javaRun(j, pair[1], directory.toString());
      rows += Parity.transcripts(n, j);
      Parity.uniqueRows(n);
    }
    assertEquals(109054L, rows);
    Parity.metrics("operations", rows);
    Parity.metrics("savedFiles", BatchedComparison.savedFiles(directory));
  }
}
