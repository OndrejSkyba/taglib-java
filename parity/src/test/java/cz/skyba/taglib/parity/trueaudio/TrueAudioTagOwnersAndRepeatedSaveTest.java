package cz.skyba.taglib.parity.trueaudio;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("trueaudio")
class TrueAudioTagOwnersAndRepeatedSaveTest {
  @Test
  void id3CarriersStreamCursorsAndCompleteSavesMatchCpp() throws Exception {
    BatchedComparison.compare(
      "trueaudio-files",
      "trueaudio-file",
      "cz.skyba.taglib.parity.trueaudio.probes.TrueAudioFileProbe",
      54483L,
      "");
    checkUnsafeInputRejections();
  }

  private void checkUnsafeInputRejections() throws Exception {
    var directory =
      cz.skyba.taglib.parity.support.Parity.directory("TrueAudioTagOwnersAndRepeatedSaveTest");
    var output = directory.resolve("rejected-inputs.java.txt");
    cz.skyba.taglib.parity.support.Parity.javaRun(
      output, "cz.skyba.taglib.parity.trueaudio.probes.TrueAudioFileBoundaryProbe");
    org.junit.jupiter.api.Assertions.assertEquals(
      "memory-grow\tfalse\tunchanged\n",
      java.nio.file.Files.readString(output));
  }
}
