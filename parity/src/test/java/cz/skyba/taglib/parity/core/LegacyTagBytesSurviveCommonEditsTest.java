package cz.skyba.taglib.parity.core;

import cz.skyba.taglib.parity.support.FileRefAdapter;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.parity.Snapshots;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Native private fields can change bytes while every exported property still agrees.
 */
@org.junit.jupiter.api.Tag("parity")
@org.junit.jupiter.api.Tag("core")
final class LegacyTagBytesSurviveCommonEditsTest {
  @TempDir
  Path directory;

  @Test
  void actualApeLegacyChildReceivesRawPropertiesAndCommonBroadcast() throws Exception {
    Path root = root(), corpus = corpus(root);
    for (String file : List.of("tagged.wv", "mac-399-tagged.ape", "click.mpc")) {
      byte[] bytes = Files.readAllBytes(corpus.resolve(file));
      if (!hasFooter(bytes)) {
        byte[] source = Files.readAllBytes(corpus.resolve("tagged.tta"));
        byte[] joined = Arrays.copyOf(bytes, bytes.length + 128);
        System.arraycopy(source, source.length - 128, joined, bytes.length, 128);
        bytes = joined;
      }
      for (String operation : List.of("aliases", "common-2147483648", "save-only", "common-title"))
        compare(root, file, bytes, operation);
    }
  }

  @Test
  void v10CommentWhitespaceSurvivesNativeDefaultMpegSave() throws Exception {
    Path root = root(), corpus = corpus(root);
    byte[] bytes = Files.readAllBytes(corpus.resolve("rare_frames.mp3"));
    for (String operation : List.of("save-only", "remove-unsupported"))
      compare(root, "rare_frames.mp3", bytes, operation);
  }

  private void compare(Path root, String name, byte[] bytes, String operation) throws Exception {
    Path output = Files.write(directory.resolve(operation + '-' + name), bytes);
    var process =
      new ProcessBuilder(
        root.resolve("parity/target/native-build/bin/file-reference-reads-and-mutations").toString(),
        "mutate-stream",
        output.toString(),
        operation,
        "0")
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start();
    byte[] response = process.getInputStream().readAllBytes();
    assertEquals(0, process.waitFor());
    var expected = Snapshots.JSON.readTree(response);
    assertTrue(expected.path("opened").asBoolean(), name);
    var memory =
      new ByteVectorStream(bytes) {
        @Override
        public String name() {
          return name;
        }
      };
    try (var file = new FileRefAdapter(memory, false)) {
      assertFalse(file.isNull(), name);
      if (operation.equals("aliases")) file.replaceProperties(aliases());
      else if (operation.equals("common-title")) file.tag().setTitle("Common title");
      else if (operation.equals("remove-unsupported"))
        file.removeUnsupportedProperties(file.properties().unsupportedData());
      else if (operation.equals("common-2147483648")) {
        var tag = file.tag();
        tag.setTitle("Common Žluť");
        tag.setArtist("Common artist");
        tag.setAlbum("Common album");
        tag.setComment("Common comment");
        tag.setGenre("Jazz");
        tag.setYear(2147483648L);
        tag.setTrack(2147483648L);
      }
      assertEquals(
        expected.path("beforeSave").path("properties"),
        Snapshots.JSON.valueToTree(file.properties().asMap()),
        name + '-' + operation);
      assertEquals(
        expected.path("beforeSaveIsEmpty").asBoolean(),
        file.tag().isEmpty(),
        name + '-' + operation);
      assertEquals(expected.path("saved").asBoolean(), file.save(), name + '-' + operation);
      assertEquals(
        expected.path("borrowedStreamCursorAfterSave").asLong(),
        memory.tell(),
        name + '-' + operation);
    }
    assertArrayEquals(
      Files.readAllBytes(output), memory.data().toByteArray(), name + '-' + operation);
    assertTrue(memory.isOpen());
  }

  private static PropertyMap aliases() {
    return PropertyMap.builder()
      .put("title", "Alias title")
      .put("Album Artist", "Spaced")
      .put("albumartist", "Joined")
      .put("year", "1999")
      .put("date", "2026-10-03")
      .put("track", "2")
      .put("tracknumber", "7/11")
      .put("DESCRIPTION", "Description alias")
      .put("COMMENT", "Comment alias")
      .build();
  }

  private static boolean hasFooter(byte[] bytes) {
    return bytes.length >= 128
      && bytes[bytes.length - 128] == 'T'
      && bytes[bytes.length - 127] == 'A'
      && bytes[bytes.length - 126] == 'G';
  }

  private static Path corpus(Path root) {
    return Snapshots.upstreamSource(root).resolve("tests/data");
  }

  private static Path root() {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("parity/native")))
      root = root.getParent();
    assertNotNull(root);
    boolean present =
      Files.isExecutable(root.resolve("parity/target/native-build/bin/file-reference-reads-and-mutations"))
        && Files.isDirectory(corpus(root));
    if (Boolean.getBoolean("taglib.requireNative"))
      assertTrue(present, "Required native core probe/corpus");
    assumeTrue(present, "Build ./mvnw -Pparity verify");
    return root;
  }
}
