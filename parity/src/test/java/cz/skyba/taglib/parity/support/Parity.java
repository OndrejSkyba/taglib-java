package cz.skyba.taglib.parity.support;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared process and file comparisons; probe processes retain their original isolation.
 */
public final class Parity {
  public static final ObjectMapper JSON = new ObjectMapper();
  public static final Path ROOT = root();
  public static final Path BUILD = ROOT.resolve("parity/target");
  private static Path work;

  private Parity() {
  }

  private static Path root() {
    Path path = Path.of("").toAbsolutePath();
    while (path != null && !Files.isDirectory(path.resolve("parity/native")))
      path = path.getParent();
    if (path == null) throw new IllegalStateException("Cannot find parity/native");
    return path;
  }

  public static synchronized Path workspace() throws IOException {
    if (work == null) {
      Files.createDirectories(BUILD.resolve("runs"));
      work = Files.createTempDirectory(BUILD.resolve("runs"), "run-");
      for (String key :
        List.of(
          "core",
          "matroska.compact",
          "dsdiff.source",
          "id3.frame",
          "xiph.encoding",
          "ogg.source",
          "regression.contract"))
        System.setProperty(key + ".evidence", work.resolve(key.replace('.', '-')).toString());
      for (var entry :
        Map.of(
            "container",
            "container-properties-and-structured-edits",
            "matroska.attachment",
            "matroska-attachment-parsing-and-saving",
            "matroska.compact",
            "matroska-compact-saving-and-seek-indexes",
            "dsdiff.source",
            "dsdiff-chunks-and-saving",
            "id3.frame",
            "id3-frame-rendering-and-saving")
          .entrySet())
        System.setProperty(entry.getKey() + ".oracle", nativeProbe(entry.getValue()).toString());
    }
    return work;
  }

  public static Path directory(String name) throws IOException {
    return Files.createDirectories(workspace().resolve(name));
  }

  public static Properties upstream() throws IOException {
    Properties properties = new Properties();
    try (var input = Files.newInputStream(ROOT.resolve("parity/target/native-build/upstream.properties"))) {
      properties.load(input);
    }
    return properties;
  }

  public static String defaultUpstreamVersion() {
    try (var lines = Files.lines(ROOT.resolve("parity/native/upstream.env"))) {
      return lines.filter(line -> line.startsWith("TAGLIB_VERSION="))
        .findFirst().orElseThrow().substring("TAGLIB_VERSION=".length());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static boolean defaultUpstream() throws IOException {
    return upstream().getProperty("selection", "default").equals("default");
  }

  public static Path corpus() throws IOException {
    return Path.of(upstream().getProperty("source")).resolve("tests/data");
  }

  public static Path nativeProbe(String name) {
    return ROOT.resolve("parity/target/native-build/bin").resolve(name);
  }

  public static void nativeRun(Path output, String probe, String... arguments) throws Exception {
    run(output, new ArrayList<>(List.of(nativeProbe(probe).toString())), arguments);
  }

  public static void javaRun(Path output, String probe, String... arguments) throws Exception {
    String java = Path.of(System.getProperty("java.home"), "bin/java").toString();
    String classpath =
      System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    run(output, new ArrayList<>(List.of(java, "-Djava.io.tmpdir=" + BUILD, "-cp", classpath, probe)), arguments);
  }

  private static void run(Path output, List<String> command, String... arguments) throws Exception {
    command.addAll(Arrays.asList(arguments));
    Path stderr = output.resolveSibling(output.getFileName() + ".stderr");
    Process process =
      new ProcessBuilder(command)
        .redirectOutput(output.toFile())
        .redirectError(stderr.toFile())
        .start();
    try {
      assertTrue(process.waitFor(180, TimeUnit.SECONDS), "Timeout: " + command);
      assertEquals(
        0, process.exitValue(), () -> "Process failed: " + command + "\n" + read(stderr));
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException e) {
      return e.toString();
    }
  }

  public static long transcripts(Path nativeOutput, Path javaOutput) throws IOException {
    long mismatch = Files.mismatch(nativeOutput, javaOutput);
    if (mismatch != -1) {
      try (var expected = Files.newBufferedReader(nativeOutput);
           var actual = Files.newBufferedReader(javaOutput)) {
        for (long line = 1; ; line++) {
          String n = expected.readLine(), j = actual.readLine();
          if (!Objects.equals(n, j))
            fail("Transcript line " + line + " differs\nC++: " + n + "\nJava: " + j);
          if (n == null) break;
        }
      }
    }
    try (var lines = Files.lines(nativeOutput)) {
      long count = lines.count();
      assertTrue(count > 0, "Empty comparison transcript");
      return count;
    }
  }

  public static void bytes(Path nativeOutput, Path javaOutput) throws IOException {
    assertEquals(
      -1,
      Files.mismatch(nativeOutput, javaOutput),
      "Saved bytes differ: " + nativeOutput.getFileName());
  }

  public static void uniqueRows(Path transcript) throws IOException {
    Set<String> ids = new HashSet<>();
    try (var lines = Files.lines(transcript)) {
      lines.forEach(
        line ->
          assertTrue(
            ids.add(line.split("\\t", 2)[0]),
            "Duplicate operation: " + line.split("\\t", 2)[0]));
    }
  }

  public static void metrics(String key, long value) {
    Results.metric(key, value);
  }

  public static synchronized void cleanup() throws IOException {
    if (work == null) return;
    try (var files = Files.walk(work)) {
      for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
    }
    work = null;
  }
}
