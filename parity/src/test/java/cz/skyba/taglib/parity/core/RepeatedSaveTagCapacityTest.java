package cz.skyba.taglib.parity.core;

import cz.skyba.taglib.parity.support.FileRefAdapter;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.parity.Snapshots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Repeated writes observe the same native in-memory tag and container state.
 */
@org.junit.jupiter.api.Tag("parity")
@org.junit.jupiter.api.Tag("core")
final class RepeatedSaveTagCapacityTest {
  @TempDir
  Path directory;

  @Test
  void nativeTagCapacityAfterGrowthSurvivesACommonSetterAndSecondSave() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("parity/native")))
      root = root.getParent();
    assertNotNull(root);
    Path tool = root.resolve("parity/target/native-build/bin/file-reference-reads-and-mutations");
    Path corpus = Snapshots.upstreamSource(root).resolve("tests/data");
    if (Boolean.getBoolean("taglib.requireNative")) {
      assertTrue(Files.isExecutable(tool));
      assertTrue(Files.isDirectory(corpus));
    }
    assumeTrue(Files.isExecutable(tool) && Files.isDirectory(corpus));
    var cases = new ArrayList<Executable>();
    for (String name :
      List.of("empty.wav", "empty.aiff", "empty10ms.dsf", "empty10ms.dff", "real_example.wma")) {
      Path source = corpus.resolve(name);
      cases.add(() -> compare(tool, source));
    }
    assertAll(cases);
  }

  @Test
  void nativeHiddenId3v1YearAndGenreSurviveDefaultAndUnrelatedCommonSave() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("parity/native")))
      root = root.getParent();
    assertNotNull(root);
    Path tool = root.resolve("parity/target/native-build/bin/file-reference-reads-and-mutations");
    Path corpus = Snapshots.upstreamSource(root).resolve("tests/data");
    if (Boolean.getBoolean("taglib.requireNative")) {
      assertTrue(Files.isExecutable(tool));
      assertTrue(Files.isDirectory(corpus));
    }
    assumeTrue(Files.isExecutable(tool) && Files.isDirectory(corpus));
    for (String name : List.of("tagged.tta", "no-tags.flac")) {
      Path source = corpus.resolve(name);
      // Append the released TTA footer to untagged FLAC to exercise its optional native child.
      byte[] bytes = Files.readAllBytes(source);
      int footer = bytes.length - 128;
      if (footer < 0
        || bytes[footer] != 'T'
        || bytes[footer + 1] != 'A'
        || bytes[footer + 2] != 'G') {
        byte[] tag =
          Arrays.copyOfRange(
            Files.readAllBytes(corpus.resolve("tagged.tta")),
            (int) Files.size(corpus.resolve("tagged.tta")) - 128,
            (int) Files.size(corpus.resolve("tagged.tta")));
        byte[] joined = Arrays.copyOf(bytes, bytes.length + tag.length);
        System.arraycopy(tag, 0, joined, bytes.length, tag.length);
        bytes = joined;
        footer = bytes.length - 128;
      }
      assertEquals('T', bytes[footer]);
      assertEquals('A', bytes[footer + 1]);
      assertEquals('G', bytes[footer + 2]);
      for (String year : List.of("abcd", "0000"))
        for (String scenario : List.of("save-only", "common-title")) {
          System.arraycopy(
            year.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),
            0,
            bytes,
            footer + 93,
            4);
          bytes[footer + 127] = (byte) 200;
          Path nativeCopy =
            Files.write(directory.resolve(year + '-' + scenario + '-' + name), bytes);
          var process =
            new ProcessBuilder(
              tool.toString(), "mutate-stream", nativeCopy.toString(), scenario, "0")
              .redirectError(ProcessBuilder.Redirect.INHERIT)
              .start();
          byte[] output = process.getInputStream().readAllBytes();
          assertEquals(0, process.waitFor());
          var expected = Snapshots.JSON.readTree(output);
          assertTrue(expected.path("opened").asBoolean());
          assertTrue(expected.path("saved").asBoolean());
          var memory =
            new cz.skyba.taglib.toolkit.ByteVectorStream(bytes) {
              @Override
              public String name() {
                return name;
              }
            };
          try (var file = new FileRefAdapter(memory, false)) {
            assertFalse(file.isNull());
            if (scenario.equals("common-title")) file.tag().setTitle("Common title");
            assertTrue(file.save());
            assertEquals(
              expected.path("borrowedStreamCursorAfterSave").asLong(),
              memory.tell(),
              name + '-' + year + '-' + scenario);
          }
          assertArrayEquals(
            Files.readAllBytes(nativeCopy),
            memory.data().toByteArray(),
            name + '-' + year + '-' + scenario);
        }
    }
  }

  @Test
  void nativeEmptyInfoValuesRetainAnEmptyListChunk() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("parity/native")))
      root = root.getParent();
    assertNotNull(root);
    Path tool = root.resolve("parity/target/native-build/bin/file-reference-reads-and-mutations");
    Path corpus = Snapshots.upstreamSource(root).resolve("tests/data");
    if (Boolean.getBoolean("taglib.requireNative")) {
      assertTrue(Files.isExecutable(tool));
      assertTrue(Files.isDirectory(corpus));
    }
    assumeTrue(Files.isExecutable(tool) && Files.isDirectory(corpus));
    for (String name : List.of("empty.wav", "pcm_with_fact_chunk.wav")) {
      byte[] bytes = Files.readAllBytes(corpus.resolve(name));
      Path nativeCopy = Files.write(directory.resolve("empty-values-" + name), bytes);
      var process =
        new ProcessBuilder(
          tool.toString(), "mutate-stream", nativeCopy.toString(), "empty-values", "0")
          .start();
      byte[] output = process.getInputStream().readAllBytes();
      assertEquals(0, process.waitFor());
      var expected = Snapshots.JSON.readTree(output);
      var memory =
        new cz.skyba.taglib.toolkit.ByteVectorStream(bytes) {
          @Override
          public String name() {
            return name;
          }
        };
      try (var file = new FileRefAdapter(memory, false)) {
        var requested =
          PropertyMap.builder()
            .put("TITLE", "")
            .put("ARTIST", List.of())
            .put("COMMENT", "")
            .put("DATE", List.of())
            .put("TRACKNUMBER", List.of())
            .put("CUSTOM", List.of())
            .build();
        assertEquals(PropertyMap.EMPTY, file.replaceProperties(requested).rejected());
        assertTrue(file.save());
        assertEquals(expected.path("borrowedStreamCursorAfterSave").asLong(), memory.tell(), name);
      }
      assertArrayEquals(Files.readAllBytes(nativeCopy), memory.data().toByteArray(), name);
    }
  }

  private void compare(Path tool, Path source) throws Exception {
    String name = source.getFileName().toString();
    byte[] bytes = Files.readAllBytes(source);
    Path nativeCopy = Files.write(directory.resolve(name), bytes);
    var process =
      new ProcessBuilder(
        tool.toString(), "mutate-stream-twice", nativeCopy.toString(), "grow-shrink", "0")
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start();
    byte[] output = process.getInputStream().readAllBytes();
    assertEquals(0, process.waitFor(), name);
    var expected = Snapshots.JSON.readTree(output);
    assertTrue(expected.path("opened").asBoolean(), name);
    assertTrue(expected.path("saved").asBoolean(), name);
    assertEquals(2, expected.path("methodCalls").path("save").asInt());
    var memory =
      new cz.skyba.taglib.toolkit.ByteVectorStream(bytes) {
        @Override
        public String name() {
          return name;
        }
      };
    try (var file = new FileRefAdapter(memory, false)) {
      assertFalse(file.isNull(), name);
      assertEquals(
        PropertyMap.EMPTY,
        file.replaceProperties(PropertyMap.builder().put("TITLE", "x".repeat(8192)).build()).rejected(),
        name);
      assertTrue(file.save(), name);
      file.tag().setTitle("x");
      assertTrue(file.save(), name);
      assertEquals(expected.path("borrowedStreamCursorAfterSave").asLong(), memory.tell(), name);
    }
    assertArrayEquals(Files.readAllBytes(nativeCopy), memory.data().toByteArray(), name);
  }
}
