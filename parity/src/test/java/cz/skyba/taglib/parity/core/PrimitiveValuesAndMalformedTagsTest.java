package cz.skyba.taglib.parity.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exact assertions against independently executed pinned native consumers.
 */
@org.junit.jupiter.api.Tag("parity")
@org.junit.jupiter.api.Tag("core")
class PrimitiveValuesAndMalformedTagsTest {
  @TestFactory
  List<DynamicTest> resolversPrimitiveConversionsAndMalformedTagsMatchCpp() throws Exception {
    Path root = cz.skyba.taglib.parity.support.Parity.directory("primitive-values-and-malformed-tags");
    JsonNode cases;
    try (var input =
           getClass().getResourceAsStream("/cases/primitive-values-and-malformed-tags.json")) {
      cases = new ObjectMapper().readTree(input);
    }
    assertTrue(cases.isArray() && !cases.isEmpty(), "No native comparisons recorded");
    var tests = new ArrayList<DynamicTest>();
    for (JsonNode row : cases) {
      String id = row.get("id").asText();
      tests.add(
        DynamicTest.dynamicTest(
          id,
          () -> {
            Path target = root.resolve("cases").resolve(id);
            Files.createDirectories(target);
            String mode = row.get("mode").asText(),
              operation = row.get("operation").asText(),
              input = row.get("input").asText();
            var nativeProcess =
              new ProcessBuilder(
                cz.skyba.taglib.parity.support.Parity.nativeProbe(
                    "primitive-values-and-malformed-tags")
                  .toString(),
                operation,
                input)
                .redirectOutput(target.resolve("native.txt").toFile())
                .redirectError(target.resolve("native.stderr").toFile())
                .start();
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            var javaProcess =
              new ProcessBuilder(
                java,
                "-cp",
                System.getProperty(
                  "surefire.test.class.path", System.getProperty("java.class.path")),
                "cz.skyba.taglib.parity.core.probes.PrimitiveValuesAndMalformedTagsProbe",
                mode,
                operation,
                input)
                .redirectOutput(target.resolve("java.txt").toFile())
                .redirectError(target.resolve("java.stderr").toFile())
                .start();
            try {
              assertTrue(nativeProcess.waitFor(10, TimeUnit.SECONDS), id + " native timeout");
              assertTrue(javaProcess.waitFor(10, TimeUnit.SECONDS), id + " Java timeout");
            } finally {
              if (nativeProcess.isAlive()) nativeProcess.destroyForcibly();
              if (javaProcess.isAlive()) javaProcess.destroyForcibly();
            }
            int nativeExit = nativeProcess.exitValue(), javaExit = javaProcess.exitValue();
            String expected = Files.readString(target.resolve("native.txt")),
              actual = Files.readString(target.resolve("java.txt"));
            boolean adaptation = row.hasNonNull("adaptation");
            if (adaptation) cz.skyba.taglib.parity.support.Parity.metrics("adaptedComparisons", 1);
            boolean cleanExit = nativeExit == 0 && javaExit == 0;
            boolean expectedJava =
              !adaptation || actual.equals(row.get("javaExpected").asText());
            var result = new java.util.LinkedHashMap<String, Object>();
            result.put("id", id);
            result.put("nativeExit", nativeExit);
            result.put("javaExit", javaExit);
            result.put("native", expected);
            result.put("java", actual);
            result.put(
              "status",
              cleanExit
                ? adaptation
                ? !expected.equals(actual) && expectedJava ? "adapted" : "failed"
                : expected.equals(actual) ? "passed" : "failed"
                : "error");
            new ObjectMapper()
              .writerWithDefaultPrettyPrinter()
              .writeValue(target.resolve("result.json").toFile(), result);
            assertEquals(
              0,
              nativeExit,
              id + " native process: " + Files.readString(target.resolve("native.stderr")));
            assertEquals(
              0,
              javaExit,
              id + " Java process: " + Files.readString(target.resolve("java.stderr")));
            if (adaptation) {
              assertNotEquals(
                expected, actual, id + " must retain the recorded native/Java adaptation");
              assertEquals(
                row.get("javaExpected").asText(), actual, id + " Java adaptation outcome");
            } else {
              assertEquals(expected, actual, id + " native contract");
            }
          }));
    }
    return tests;
  }
}
