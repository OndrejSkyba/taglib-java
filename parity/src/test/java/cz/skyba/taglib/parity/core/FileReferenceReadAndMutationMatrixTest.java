package cz.skyba.taglib.parity.core;

import cz.skyba.taglib.parity.FileReferenceReadAndMutationMatrix;
import cz.skyba.taglib.parity.Snapshots;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Mandatory with taglib.requireNative; optional native provisioning during ordinary unit work.
 */
@org.junit.jupiter.api.Tag("parity")
@org.junit.jupiter.api.Tag("core")
@org.junit.jupiter.api.Tag("corpus-mutations")
final class FileReferenceReadAndMutationMatrixTest {
  @Test
  void allFormatsReadModesMutationsAndSavedBytesMatchCpp() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("parity/native")))
      root = root.getParent();
    assertNotNull(root);
    Path oracle =
      Path.of(
        System.getProperty(
          "core.oracle",
          root.resolve("parity/target/native-build/bin/file-reference-reads-and-mutations")
            .toString()));
    Path corpus = Snapshots.upstreamSource(root).resolve("tests/data");
    if (Boolean.getBoolean("taglib.requireNative")) {
      assertTrue(Files.isExecutable(oracle), "Required core native oracle: ./mvnw -Pparity verify");
      assertTrue(Files.isDirectory(corpus), "Required pinned native fixture corpus");
    }
    assumeTrue(
      Files.isExecutable(oracle) && Files.isDirectory(corpus),
      "Provision with ./mvnw -Pparity verify");
    var report =
      FileReferenceReadAndMutationMatrix.run(
        oracle,
        corpus,
        Path.of(
          System.getProperty("core.evidence", root.resolve("parity/target/matrix").toString())),
        false);
    cz.skyba.taglib.parity.support.Parity.metrics(
      "operations",
      report.path("summary").path("readOperations").asLong()
        + report.path("summary").path("sessionReadOperations").asLong()
        + report.path("summary").path("mutationOperations").asLong()
        + report.path("summary").path("sessionMutationOperations").asLong());
    cz.skyba.taglib.parity.support.Parity.metrics(
      "savedFiles", report.path("summary").path("completeOutputComparisons").asLong());
    if (cz.skyba.taglib.parity.support.Parity.defaultUpstream()) {
      assertEquals(492, report.path("summary").path("readOperations").asInt());
      assertEquals(1476, report.path("summary").path("sessionReadOperations").asInt());
      assertEquals(116, report.path("summary").path("nativeValidFiles").asInt());
      assertEquals(1856, report.path("summary").path("mutationOperations").asInt());
      assertEquals(7308, report.path("summary").path("sessionMutationOperations").asInt());
      assertEquals(22, report.path("summary").path("observedFormats").asInt());
      assertEquals(9128, report.path("summary").path("completeOutputComparisons").asInt());
      assertEquals(36, report.path("summary").path("javaSafetyOperations").asInt());
    } else {
      long files;
      try (var paths = Files.walk(corpus)) {
        files = paths.filter(Files::isRegularFile).count();
      }
      assertEquals(files * 4, report.path("summary").path("readOperations").asLong());
      assertEquals(files * 12, report.path("summary").path("sessionReadOperations").asLong());
      long writes =
        report.path("summary").path("mutationOperations").asLong()
          + report.path("summary").path("sessionMutationOperations").asLong();
      assertEquals(
        writes,
        report.path("summary").path("completeOutputComparisons").asLong()
          + report.path("summary").path("javaSafetyOperations").asLong());
    }
    assertEquals(0, report.path("summary").path("nativeFaultsExecuted").asInt());
    assertEquals("ordinary-port-v1", report.path("summary").path("qualificationPolicy").asText());
    assertTrue(report.path("summary").path("definedComparisonsVerified").asBoolean());
    assertTrue(report.path("summary").path("verified").asBoolean());
    assertEquals(0, report.path("summary").path("completeOutputByteDifferences").asInt());
    assertEquals(
      0,
      report.path("summary").path("differences").asInt(),
      "Native core results differ; see the comparison failure details");
  }
}
