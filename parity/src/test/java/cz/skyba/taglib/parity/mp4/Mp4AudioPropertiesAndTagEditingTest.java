package cz.skyba.taglib.parity.mp4;

import cz.skyba.taglib.parity.fixtures.FixtureInputs;
import cz.skyba.taglib.parity.support.Parity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("parity")
@Tag("mp4")
class Mp4AudioPropertiesAndTagEditingTest {
  @Test
  @Tag("mp4-properties")
  void audioPropertiesMatchForTruncatedHeadersCodecsAndTimingBoundaries() throws Exception {
    Path directory = Parity.directory("mp4-properties");
    FixtureInputs.generate("mp4-properties", directory);
    long fixtureCount = inputCount(directory);
    Path nativeOutput = directory.resolve("native.txt"), javaOutput = directory.resolve("java.txt");
    Parity.nativeRun(nativeOutput, "mp4-properties", directory.toString());
    Parity.javaRun(
      javaOutput, "cz.skyba.taglib.parity.mp4.probes.Mp4PropertiesProbe", directory.toString());
    long rows = Parity.transcripts(nativeOutput, javaOutput);
    assertEquals(
      fixtureCount * 44L, rows, "Every fixture/read mode/property must execute");
    Parity.uniqueRows(nativeOutput);
    Parity.metrics("fixtures", fixtureCount);
    Parity.metrics("operations", rows);
  }

  @Test
  @Tag("mp4-mutable")
  void tagItemsAtomOffsetsCallbacksAndSavedFilesMatchCpp() throws Exception {
    Path directory = Parity.directory("mp4-mutable");
    FixtureInputs.generate("mp4-mutable", directory);
    long fixtureCount = inputCount(directory);
    long rows = 0;
    for (String variant : List.of("api", "extra")) {
      Path n = directory.resolve("native-" + variant + ".txt"),
        j = directory.resolve("java-" + variant + ".txt");
      Parity.nativeRun(
        n,
        variant.equals("api") ? "mp4-mutable" : "mp4-mutable-subclasses-and-callbacks",
        directory.toString());
      Parity.javaRun(
        j,
        variant.equals("api")
          ? "cz.skyba.taglib.parity.Mp4MutableProbe"
          : "cz.skyba.taglib.parity.Mp4MutableCallbacksAndSavingProbe",
        directory.toString());
      rows += Parity.transcripts(n, j);
      Parity.uniqueRows(n);
    }
    long files = 0;
    try (var paths = Files.list(directory.resolve("outputs"))) {
      for (Path n : paths.filter(path -> path.toString().endsWith(".native")).sorted().toList()) {
        Parity.bytes(
          n, n.resolveSibling(n.getFileName().toString().replaceFirst("\\.native$", ".java")));
        files++;
      }
    }
    assertTrue(files > 0, "No saved-file comparisons executed");
    Parity.metrics("fixtures", fixtureCount);
    Parity.metrics("operations", rows);
    Parity.metrics("savedFiles", files);
  }

  private static long inputCount(Path directory) throws Exception {
    try (var files = Files.list(directory.resolve("inputs"))) {
      return files.filter(Files::isRegularFile).count();
    }
  }
}
