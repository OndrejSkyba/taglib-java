package cz.skyba.taglib.parity.support;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One report for the JUnit run, including failures and skipped native comparisons.
 */
public final class Results implements TestExecutionListener {
  private static final ThreadLocal<ObjectNode> CURRENT = new ThreadLocal<>();
  private final Map<String, ObjectNode> active = new ConcurrentHashMap<>();
  private final List<ObjectNode> tests = Collections.synchronizedList(new ArrayList<>());
  private TestPlan plan;
  private Instant started;
  private long startNanos;
  private boolean enabled;

  static void metric(String name, long value) {
    ObjectNode test = CURRENT.get();
    if (test != null) test.withObject("/comparisons").put(name, value);
  }

  @Override
  public void testPlanExecutionStarted(TestPlan plan) {
    enabled = Boolean.getBoolean("parity.enabled");
    if (!enabled) return;
    this.plan = plan;
    started = Instant.now();
    startNanos = System.nanoTime();
    try {
      Parity.workspace();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void executionStarted(TestIdentifier id) {
    if (!enabled || !id.isTest()) return;
    ObjectNode test =
      Parity.JSON
        .createObjectNode()
        .put("suite", suite(id))
        .put("name", id.getDisplayName())
        .put("status", "running")
        .put("startedNanos", System.nanoTime());
    test.put(
      "component",
      id.getTags().stream()
        .map(tag -> tag.getName())
        .filter(tag -> !tag.equals("parity"))
        .sorted()
        .findFirst()
        .orElse("core"));
    active.put(id.getUniqueId(), test);
    CURRENT.set(test);
  }

  private String suite(TestIdentifier id) {
    TestIdentifier current = id;
    while (true) {
      if (current.getSource().orElse(null) instanceof ClassSource source)
        return source.getClassName();
      Optional<TestIdentifier> parent = plan.getParent(current);
      if (parent.isEmpty()) return id.getDisplayName();
      current = parent.get();
    }
  }

  @Override
  public void executionFinished(TestIdentifier id, TestExecutionResult result) {
    if (!enabled) return;
    ObjectNode test = active.remove(id.getUniqueId());
    if (test == null && result.getStatus() == TestExecutionResult.Status.FAILED)
      test =
        Parity.JSON
          .createObjectNode()
          .put("suite", suite(id))
          .put("name", id.getDisplayName())
          .put("startedNanos", System.nanoTime());
    if (test == null) return;
    test.put(
      "status",
      result.getStatus() == TestExecutionResult.Status.SUCCESSFUL
        ? "passed"
        : result.getStatus() == TestExecutionResult.Status.ABORTED ? "skipped" : "failed");
    test.put("durationSeconds", (System.nanoTime() - test.remove("startedNanos").asLong()) / 1e9);
    if (result.getThrowable().isPresent()) {
      String message = result.getThrowable().get().toString();
      test.put("message", message.substring(0, Math.min(message.length(), 12000)));
    }
    tests.add(test);
    CURRENT.remove();
  }

  @Override
  public void executionSkipped(TestIdentifier id, String reason) {
    if (enabled && id.isTest())
      tests.add(
        Parity.JSON
          .createObjectNode()
          .put("suite", suite(id))
          .put("name", id.getDisplayName())
          .put("status", "skipped")
          .put("message", reason));
  }

  @Override
  public void testPlanExecutionFinished(TestPlan plan) {
    if (!enabled) return;
    ObjectNode report =
      Parity.JSON
        .createObjectNode()
        .put("schemaVersion", 2)
        .put("startedAt", started.toString())
        .put("completedAt", Instant.now().toString())
        .put("durationSeconds", (System.nanoTime() - startNanos) / 1e9);
    long passed =
      tests.stream().filter(test -> test.path("status").asText().equals("passed")).count();
    long skipped =
      tests.stream().filter(test -> test.path("status").asText().equals("skipped")).count();
    report.put("status", passed == tests.size() && passed > 0 ? "passed" : "failed");
    report
      .putObject("summary")
      .put("suites", tests.stream().map(test -> test.path("suite").asText()).distinct().count())
      .put("tests", tests.size())
      .put("passed", passed)
      .put("failed", tests.size() - passed - skipped)
      .put("skipped", skipped)
      .put(
        "operations",
        tests.stream()
          .mapToLong(test -> test.path("comparisons").path("operations").asLong())
          .sum());
    report.set("tests", Parity.JSON.valueToTree(tests));
    try {
      var upstream = Parity.upstream();
      var identity = report.putObject("upstream");
      for (String field : List.of("version", "selection", "requested", "commit", "sourceSha256", "corpusSha256")) {
        String value = upstream.getProperty(field);
        if (value != null && !value.isEmpty()) identity.put(field, value);
      }
      identity.put("dirty", Boolean.parseBoolean(upstream.getProperty("dirty", "false")));
      Path output =
        Path.of(
            System.getProperty(
              "parity.output", Parity.ROOT.resolve("parity/target/results.json").toString()))
          .toAbsolutePath();
      Files.createDirectories(output.getParent());
      Path temporary = Files.createTempFile(output.getParent(), ".parity-results-", ".tmp");
      try {
        Parity.JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), report);
        Files.move(
          temporary, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } finally {
        Files.deleteIfExists(temporary);
      }
      System.out.println(
        "Comparisons "
          + report.path("status").asText()
          + ": "
          + passed
          + "/"
          + tests.size()
          + " tests. Results: "
          + output);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot write comparison results", e);
    } finally {
      try {
        Parity.cleanup();
      } catch (Exception e) {
        throw new IllegalStateException("Cannot clean comparison work", e);
      }
    }
  }
}
