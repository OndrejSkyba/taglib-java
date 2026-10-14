package cz.skyba.taglib.parity.support;

import cz.skyba.taglib.parity.fixtures.FixtureInputs;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One JUnit scenario can compare many observations without launching a process per assertion.
 */
public final class BatchedComparison {
  private BatchedComparison() {
  }

  public static void compare(
    String fixtures, String nativeProbe, String javaProbe, long expectedRows, String argumentFile)
    throws Exception {
    Path directory =
      fixtures == null
        ? Parity.directory(nativeProbe)
        : FixtureInputs.generate(fixtures, Parity.directory(fixtures));
    String[] args =
      argumentFile == null
        ? new String[0]
        : new String[]{directory.resolve(argumentFile).toString()};
    Path n = directory.resolve("native.txt"), j = directory.resolve("java.txt");
    Parity.nativeRun(n, nativeProbe, args);
    Parity.javaRun(j, javaProbe, args);
    long rows = Parity.transcripts(n, j);
    if (expectedRows > 0)
      assertEquals(expectedRows, rows, "Every fixture and operation must execute");
    long files = savedFiles(directory);
    Parity.metrics("operations", rows);
    Parity.metrics("savedFiles", files);
  }

  public static long savedFiles(Path directory) throws Exception {
    long files = 0;
    try (var paths = Files.walk(directory)) {
      for (Path n :
        paths
          .filter(
            path ->
              Files.isRegularFile(path)
                && path.getFileName().toString().endsWith(".native"))
          .sorted()
          .toList()) {
        Parity.bytes(
          n, n.resolveSibling(n.getFileName().toString().replaceFirst("\\.native$", ".java")));
        files++;
      }
    }
    return files;
  }
}
