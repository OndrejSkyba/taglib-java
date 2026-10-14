package cz.skyba.taglib.parity.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Development-only native comparison of original fixtures; never supplies a guessed expectation.
 */
public final class NativeSavedFileComparison {
  private NativeSavedFileComparison() {
  }

  public static void compareTitle(byte[] original, Path javaFile, String title, boolean retain)
    throws Exception {
    compare(original, javaFile, "title", title, retain ? "retain" : "replace");
  }

  public static void compare(byte[] original, Path javaFile, String... operation) throws Exception {
    compareOutcome(original, javaFile, false, operation);
  }

  public static void compareRejected(byte[] original, Path javaFile, String... operation)
    throws Exception {
    compareOutcome(original, javaFile, true, operation);
  }

  private static void compareOutcome(
    byte[] original, Path javaFile, boolean rejected, String... operation) throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("parity/native")))
      root = root.getParent();
    assertNotNull(root);
    Path tool =
      Path.of(
        System.getProperty(
          "regression.contract.oracle",
          root.resolve("parity/target/native-build/bin/tag-edits-and-complete-file-saving")
            .toString()));
    if (Boolean.getBoolean("taglib.requireNative"))
      assertTrue(
        Files.isExecutable(tool), "Build regression-contract-probe before required checks");
    assumeTrue(Files.isExecutable(tool));
    var command = new ArrayList<String>();
    command.add(tool.toString());
    command.add(operation[0]);
    Path copy = javaFile.resolveSibling("native-" + javaFile.getFileName());
    Files.write(copy, original);
    command.add(copy.toString());
    command.addAll(Arrays.asList(operation).subList(1, operation.length));
    var process =
      new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    String output =
      new String(
        process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(rejected ? 2 : 0, process.waitFor(), copy.toString());
    assertEquals(rejected ? "" : "saved=1\n", output);
    byte[] expected = Files.readAllBytes(copy), actual = Files.readAllBytes(javaFile);
    String hash =
      HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(original))
        .substring(0, 16);
    Path evidence =
      Path.of(
          System.getProperty(
            "regression.contract.evidence",
            root.resolve("parity/target/regression-contract").toString()))
        .resolve(javaFile.getFileName() + "-" + hash);
    Files.createDirectories(evidence);
    Files.write(evidence.resolve("input.bin"), original);
    Files.write(evidence.resolve("native.bin"), expected);
    Files.write(evidence.resolve("java.bin"), actual);
    Files.writeString(
      evidence.resolve("operation.txt"), String.join(" ", operation) + "\n" + output);
    assertArrayEquals(expected, actual, javaFile.toString());
  }
}
