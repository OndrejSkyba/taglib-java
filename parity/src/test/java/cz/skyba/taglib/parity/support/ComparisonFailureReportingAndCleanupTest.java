package cz.skyba.taglib.parity.support;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

@Tag("support")
@Tag("parity")
class ComparisonFailureReportingAndCleanupTest {
  @TempDir
  Path directory;

  @Test
  void failuresSkipsAndPassedTestsAreRecordedAndTemporaryFilesAreRemoved() throws Exception {
    String previousEnabled = System.getProperty("parity.enabled"),
      previousOutput = System.getProperty("parity.output");
    Path output = directory.resolve("results.json");
    System.setProperty("parity.enabled", "true");
    System.setProperty("parity.output", output.toString());
    Path work = Parity.workspace();
    try {
      var config =
        LauncherConfig.builder()
          .enableTestExecutionListenerAutoRegistration(false)
          .addTestExecutionListeners(new Results())
          .build();
      var launcher = LauncherFactory.create(config);
      launcher.execute(
        LauncherDiscoveryRequestBuilder.request()
          .selectors(selectClass(SampleComparisons.class))
          .build());
      var report = Parity.JSON.readTree(output.toFile());
      assertEquals("failed", report.get("status").asText());
      assertEquals(1, report.path("summary").path("passed").asInt());
      assertEquals(1, report.path("summary").path("failed").asInt());
      assertEquals(1, report.path("summary").path("skipped").asInt());
      assertFalse(Files.exists(work));
      try (var files = Files.list(directory)) {
        assertEquals(1, files.count());
      }
      assertTrue(report.toString().contains("Different native and Java values"));
    } finally {
      if (previousEnabled == null) System.clearProperty("parity.enabled");
      else System.setProperty("parity.enabled", previousEnabled);
      if (previousOutput == null) System.clearProperty("parity.output");
      else System.setProperty("parity.output", previousOutput);
      Parity.cleanup();
    }
  }

  static class SampleComparisons {
    @Test
    void equalValues() {
      assertEquals(7, 7);
    }

    @Test
    void differentValues() {
      fail("Different native and Java values");
    }

    @Test
    @Disabled("Missing native prerequisite")
    void missingPrerequisite() {
    }
  }
}
