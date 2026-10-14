package cz.skyba.taglib.parity.fixtures;

import cz.skyba.taglib.parity.support.Parity;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;

/**
 * Keeps generated originals; tests always receive ordinary, independent file copies.
 */
final class FixtureCache {
  @FunctionalInterface
  interface Generator {
    void generate(Path directory) throws Exception;
  }

  private static final Map<String, FixtureCache> shared = new HashMap<>();
  private static byte[] generatorIdentity;
  private final Path root;
  private final String identity;

  FixtureCache(Path root, String identity) {
    this.root = root;
    this.identity = identity;
  }

  static synchronized Path copy(String name, Path destination, Generator generator)
    throws Exception {
    FixtureCache cache = shared.get(name);
    if (cache == null) {
      cache = new FixtureCache(Parity.BUILD.resolve("fixture-cache"), fingerprint(name));
      shared.put(name, cache);
    }
    return cache.materialize(name, destination, generator);
  }

  private static String fingerprint(String name) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    if (generatorIdentity == null) generatorIdentity = generatorIdentity();
    digest.update(generatorIdentity);
    Properties upstream = syntheticOnly(name) ? new Properties() : Parity.upstream();
    digest.update(inputsIdentity(name, upstream).getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(digest.digest());
  }

  private static boolean syntheticOnly(String name) {
    return Set.of(
        "id3-headers",
        "id3-text",
        "id3-picture",
        "id3-owner",
        "id3v1",
        "id3-counters-ratings-and-relative-volume",
        "id3-lyrics-events-and-timed-text",
        "dsdiff-diin")
      .contains(name);
  }

  static String inputsIdentity(String name, Properties upstream) throws Exception {
    if (syntheticOnly(name)) return "synthetic";
    if (name.equals("id3-frame-headers-payloads-and-translation")) {
      var pairs = Id3FrameHeaderFixtures.translations(Path.of(upstream.getProperty("source")));
      return "translations:"
        + String.join(
        "\n", pairs.stream().map(pair -> pair.getKey() + "\t" + pair.getValue()).toList());
    }
    String corpus = upstream.getProperty("corpusSha256");
    if (corpus == null)
      throw new IllegalStateException("Missing upstream corpus hash; rebuild native probes");
    return "corpus:" + corpus;
  }

  private static byte[] generatorIdentity() throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    Path classes =
      Path.of(FixtureCache.class.getProtectionDomain().getCodeSource().getLocation().toURI())
        .resolve("cz/skyba/taglib/parity/fixtures");
    try (var files = Files.walk(classes)) {
      for (Path file :
        files
          .filter(
            path ->
              path.toString().endsWith(".class")
                && !path.getFileName().toString().endsWith("Test.class"))
          .sorted()
          .toList()) {
        digest.update(classes.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
        digest.update(Files.readAllBytes(file));
      }
    }
    return digest.digest();
  }

  synchronized Path materialize(String name, Path destination, Generator generator)
    throws Exception {
    if (!name.matches("[a-z0-9-]+"))
      throw new IllegalArgumentException("Invalid fixture set: " + name);
    Files.createDirectories(root);
    Path cached = root.resolve(identity).resolve(name);
    // Maven invocations can also share this cache without going through parity/run.
    try (var channel =
           FileChannel.open(
             root.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
         var lock = channel.lock()) {
      if (!Files.isDirectory(cached)) {
        Files.createDirectories(cached.getParent());
        Path staging = Files.createTempDirectory(cached.getParent(), name + ".tmp-");
        try {
          generator.generate(staging);
          Files.move(staging, cached, StandardCopyOption.ATOMIC_MOVE);
          System.out.println("Fixtures generated: " + name);
        } finally {
          if (Files.exists(staging)) deleteTree(staging);
        }
      } else {
        System.out.println("Fixtures reused: " + name);
      }
      copyTree(cached, destination);
    }
    return destination;
  }

  private static void copyTree(Path source, Path destination) throws Exception {
    try (var files = Files.walk(source)) {
      for (Path file : files.sorted().toList()) {
        Path target = destination.resolve(source.relativize(file));
        if (Files.isDirectory(file)) Files.createDirectories(target);
        else Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }

  private static void deleteTree(Path directory) throws Exception {
    try (var files = Files.walk(directory)) {
      for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
    }
  }
}
