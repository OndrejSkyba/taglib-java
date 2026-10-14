package cz.skyba.taglib.parity.fixtures;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Tag("support")
class FixtureCacheReuseAndIsolationTest {
  @TempDir
  Path directory;

  @Test
  void reusesOriginalsButKeepsTestEditsAndSavedOutputsOutOfTheCache() throws Exception {
    var cache = new FixtureCache(directory.resolve("cache"), "same-code-and-upstream");
    var generations = new AtomicInteger();
    FixtureCache.Generator generator =
      root -> {
        generations.incrementAndGet();
        Files.createDirectories(root.resolve("inputs"));
        Files.createDirectories(root.resolve("outputs"));
        Files.writeString(root.resolve("inputs/audio.bin"), "original audio");
      };
    Path first = cache.materialize("audio", directory.resolve("first"), generator);
    Files.writeString(first.resolve("inputs/audio.bin"), "changed by test");
    Files.writeString(first.resolve("outputs/saved.native"), "saved output");
    Path next = cache.materialize("audio", directory.resolve("next"), generator);
    assertEquals(1, generations.get());
    assertEquals("original audio", Files.readString(next.resolve("inputs/audio.bin")));
    assertFalse(Files.exists(next.resolve("outputs/saved.native")));
    assertFalse(
      Files.isSameFile(first.resolve("inputs/audio.bin"), next.resolve("inputs/audio.bin")));
  }

  @Test
  void changedGeneratorOrUpstreamIdentitySelectsFreshInputs() throws Exception {
    Path root = directory.resolve("cache");
    new FixtureCache(root, "old")
      .materialize(
        "audio",
        directory.resolve("old"),
        path -> Files.writeString(path.resolve("case.bin"), "old bytes"));
    Path next =
      new FixtureCache(root, "new")
        .materialize(
          "audio",
          directory.resolve("new"),
          path -> Files.writeString(path.resolve("case.bin"), "new bytes"));
    assertEquals("new bytes", Files.readString(next.resolve("case.bin")));
  }

  @Test
  void failedGenerationIsRemovedAndRetried() throws Exception {
    var cache = new FixtureCache(directory.resolve("cache"), "identity");
    assertThrows(
      IllegalStateException.class,
      () ->
        cache.materialize(
          "audio",
          directory.resolve("first"),
          path -> {
            Files.writeString(path.resolve("partial.bin"), "incomplete");
            throw new IllegalStateException("Interrupted generation");
          }));
    try (var files = Files.list(directory.resolve("cache/identity"))) {
      assertEquals(0, files.count());
    }
    Path next =
      cache.materialize(
        "audio",
        directory.resolve("next"),
        path -> Files.writeString(path.resolve("complete.bin"), "complete"));
    assertFalse(Files.exists(next.resolve("partial.bin")));
    assertEquals("complete", Files.readString(next.resolve("complete.bin")));
  }

  @Test
  void removingBuildCacheRegeneratesInputs() throws Exception {
    Path root = directory.resolve("cache");
    var cache = new FixtureCache(root, "identity");
    var generations = new AtomicInteger();
    FixtureCache.Generator generator =
      path -> {
        generations.incrementAndGet();
        Files.writeString(path.resolve("case.bin"), "bytes");
      };
    cache.materialize("audio", directory.resolve("first"), generator);
    try (var files = Files.walk(root)) {
      for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList())
        Files.delete(path);
    }
    cache.materialize("audio", directory.resolve("next"), generator);
    assertEquals(2, generations.get());
  }
}
