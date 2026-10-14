package cz.skyba.taglib.parity.id3v1;

import cz.skyba.taglib.parity.fixtures.FixtureInputs;
import cz.skyba.taglib.parity.support.Parity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

@Tag("parity")
@Tag("id3v1")
class Id3v1EncodingFieldsAndHandlerSavingTest {
  @Test
  void fixedWidthFieldsYearParsingAndHandlerConfiguredSavesMatchCpp() throws Exception {
    var directory =
      FixtureInputs.generate("id3v1", Parity.directory("id3v1-fields-and-handlers"));
    var n = directory.resolve("native.txt");
    var j = directory.resolve("java.txt");
    Parity.nativeRun(n, "id3v1", directory.toString());
    Parity.javaRun(j, "cz.skyba.taglib.parity.id3v1.probes.Id3v1Probe", directory.toString());
    long rows = Parity.transcripts(n, j), files = 0;
    for (String file :
      List.of(
        "rare_frames.mp3",
        "tagged.tta",
        "tagged.wv",
        "mac-399-tagged.ape",
        "click.mpc",
        "no-tags.flac"))
      for (String operation : List.of("save-only", "common", "import"))
        for (String handler : List.of("utf8", "counting")) {
          var work =
            Files.createDirectories(directory.resolve(file + "-" + operation + "-" + handler));
          var np = work.resolve("native-" + file);
          var jp = work.resolve("java-" + file);
          byte[] original = Files.readAllBytes(Parity.corpus().resolve(file));
          if (original.length >= 128
            && new String(
            original,
            original.length - 128,
            3,
            java.nio.charset.StandardCharsets.ISO_8859_1)
            .equals("TAG")) original = Arrays.copyOf(original, original.length - 128);
          original =
            cz.skyba.taglib.parity.fixtures.Bytes.join(
              original, Files.readAllBytes(directory.resolve("inputs/oops-v11.bin")));
          Files.write(np, original);
          Files.write(jp, original);
          n = work.resolve("native.txt");
          j = work.resolve("java.txt");
          Parity.nativeRun(n, "id3v1", np.toString(), operation, handler);
          Parity.javaRun(
            j, "cz.skyba.taglib.parity.id3v1.probes.Id3v1Probe", jp.toString(), operation, handler);
          rows += Parity.transcripts(n, j);
          Parity.bytes(np, jp);
          files++;
        }
    Parity.metrics("operations", rows);
    Parity.metrics("savedFiles", files);
  }
}
