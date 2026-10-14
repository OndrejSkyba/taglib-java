package cz.skyba.taglib.parity.ogg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cz.skyba.taglib.parity.support.FileRefAdapter;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.parity.Snapshots;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.Variant;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Native Ogg FLAC clears oversized artwork before save can succeed or fail.
 */
@org.junit.jupiter.api.Tag("parity")
@org.junit.jupiter.api.Tag("ogg")
final class OggFlacPictureSizeLimitTest {
  @TempDir
  Path directory;

  @TestFactory
  List<DynamicTest> oversizedCommentMutatesActualPictures() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("parity/native")))
      root = root.getParent();
    assertNotNull(root);
    Path probe = root.resolve("parity/target/native-build/bin/ogg-flac-picture-size-limits");
    assertTrue(Files.isExecutable(probe), "Required native Ogg picture-limit control");
    Path source = Snapshots.upstreamSource(root).resolve("tests/data/empty_flac.oga");
    assertTrue(Files.isRegularFile(source), "Pinned Ogg FLAC fixture");
    byte[] bytes = Files.readAllBytes(source);
    var tests = new ArrayList<DynamicTest>();
    for (int pictureSize : List.of(11 * 1024 * 1024, 13 * 1024 * 1024))
      for (int origin : List.of(-1, 0, 7))
        tests.add(
          DynamicTest.dynamicTest(
            "picture-" + pictureSize + "/origin-" + origin,
            () -> compare(probe, bytes, pictureSize, 0, origin)));
    for (int origin : List.of(-1, 0, 7))
      tests.add(
        DynamicTest.dynamicTest(
          "oversized-pictures-and-properties/origin-" + origin,
          () -> compare(probe, bytes, 13 * 1024 * 1024, 17 * 1024 * 1024, origin)));
    return tests;
  }

  private void compare(Path probe, byte[] source, int pictureSize, int customSize, int origin)
    throws Exception {
    Path nativePath = Files.write(directory.resolve("native-limit.oga"), source);
    var process =
      new ProcessBuilder(
        probe.toString(),
        nativePath.toString(),
        Integer.toString(pictureSize),
        Integer.toString(customSize),
        Integer.toString(origin))
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start();
    byte[] response = process.getInputStream().readAllBytes();
    assertEquals(0, process.waitFor(), "Required native picture-limit operation");
    JsonNode expected = Snapshots.JSON.readTree(response);
    Path path = Files.write(directory.resolve("java-limit.oga"), source);
    ByteVectorStream stream =
      origin < 0
        ? null
        : new ByteVectorStream(source) {
        @Override
        public String name() {
          return path.toString();
        }
      };
    if (stream != null) stream.seek(origin);
    try (var ref = stream == null ? new FileRefAdapter(path, false) : new FileRefAdapter(stream, false)) {
      assertFalse(ref.isNull());
      if (stream != null)
        assertEquals(expected.path("cursorAfterConstruction").asLong(), stream.tell());
      var properties = PropertyMap.builder().put("TITLE", "Limit");
      if (customSize != 0) properties.put("CUSTOM", "x".repeat(customSize));
      assertTrue(ref.replaceProperties(properties.build()).rejected().isEmpty());
      byte[] picture = new byte[pictureSize];
      Arrays.fill(picture, (byte) 'p');
      boolean supported =
        ref.setComplexProperties(
          "PICTURE",
          List.of(
            Map.of(
              "data",
              new Variant(new ByteVector(picture)),
              "mimeType",
              new Variant("image/png"),
              "description",
              new Variant("Limit picture"),
              "pictureType",
              new Variant("Front Cover"),
              "width",
              new Variant(1),
              "height",
              new Variant(2),
              "colorDepth",
              new Variant(8),
              "numColors",
              new Variant(0))));
      assertEquals(expected.path("pictureSupported").asBoolean(), supported);
      assertEquals(List.of(), Snapshots.differences(expected.path("before"), state(ref)));
      if (stream != null) assertEquals(expected.path("cursorBeforeSave").asLong(), stream.tell());
      boolean first = ref.save();
      assertEquals(expected.path("savedFirst").asBoolean(), first, "First save outcome");
      assertEquals(customSize == 0, first, "Oversized properties keep the native failure branch");
      if (stream != null) assertEquals(expected.path("cursorAfterFirst").asLong(), stream.tell());
      assertEquals(
        List.of(),
        Snapshots.differences(expected.path("afterFirst"), state(ref)),
        "Live state after first save");
      assertEquals(
        pictureSize < 12 * 1024 * 1024 ? 1 : 0,
        ref.complexProperties("PICTURE").size(),
        "Native size fallback changes the actual tag before returning");
      boolean second = ref.save();
      assertEquals(expected.path("savedSecond").asBoolean(), second, "Second save outcome");
      if (stream != null) assertEquals(expected.path("cursorAfterSecond").asLong(), stream.tell());
      assertEquals(
        List.of(),
        Snapshots.differences(expected.path("afterSecond"), state(ref)),
        "Live state after repeated save");
    }
    byte[] actual = stream == null ? Files.readAllBytes(path) : stream.data().toByteArray();
    assertArrayEquals(
      Files.readAllBytes(nativePath), actual, "Complete physical output after both saves");
    if (customSize != 0)
      assertArrayEquals(source, actual, "Failed native render never reaches file writes");
    if (stream != null) {
      assertTrue(stream.isOpen());
      assertTrue(expected.path("borrowedOpen").asBoolean());
      Files.write(path, actual);
    }
    try (var ref = new FileRefAdapter(path, false)) {
      assertEquals(
        List.of(),
        Snapshots.differences(expected.path("reopened"), state(ref)),
        "Reopened picture state");
    }
  }

  private static ObjectNode state(FileRefAdapter ref) {
    if (ref.isNull()) return Snapshots.invalid();
    var result = Snapshots.JSON.createObjectNode().put("valid", true);
    var properties = result.putObject("properties");
    for (var entry : ref.properties().asMap().entrySet()) {
      var values = properties.putArray(entry.getKey());
      for (String value : entry.getValue()) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        values.addObject().put("size", bytes.length).put("fnv1a64", Snapshots.fnv(bytes));
      }
    }
    assertEquals(List.of(), ref.properties().unsupportedData());
    var t = ref.tag();
    result
      .putObject("common")
      .put("title", t.title())
      .put("artist", t.artist())
      .put("album", t.album())
      .put("comment", t.comment())
      .put("genre", t.genre())
      .put("year", t.year())
      .put("track", t.track());
    result.put("isEmpty", t.isEmpty());
    result.set("complexKeys", Snapshots.JSON.valueToTree(ref.complexPropertyKeys()));
    var pictures = result.putArray("pictures");
    for (var p : ref.complexProperties("PICTURE")) {
      byte[] bytes = p.getOrDefault("data", new Variant()).toByteVector().toByteArray();
      pictures
        .addObject()
        .put("size", bytes.length)
        .put("fnv1a64", Snapshots.fnv(bytes))
        .put("mimeType", p.getOrDefault("mimeType", new Variant()).toText())
        .put("description", p.getOrDefault("description", new Variant()).toText())
        .put("pictureType", p.getOrDefault("pictureType", new Variant()).toText())
        .put("width", p.getOrDefault("width", new Variant()).toInt())
        .put("height", p.getOrDefault("height", new Variant()).toInt())
        .put("colorDepth", p.getOrDefault("colorDepth", new Variant()).toInt())
        .put("numColors", p.getOrDefault("numColors", new Variant()).toInt());
    }
    return result;
  }
}
