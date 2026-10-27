package cz.skyba.taglib.parity.core;

import cz.skyba.taglib.Picture;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.TagLib;
import cz.skyba.taglib.parity.Snapshots;
import cz.skyba.taglib.parity.support.FileRefAdapter;
import cz.skyba.taglib.parity.support.Parity;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("parity")
@Tag("core")
final class HighLevelStreamWriteTest {
  private static final PropertyMap UNICODE = PropertyMap.builder()
    .put("TITLE", "Žluťoučký 🐦")
    .put("ARTIST", List.of("東京", "é"))
    .put("COMMENT", "Line one\nLine two")
    .put("CUSTOM", List.of("Ελληνικά", "العربية"))
    .build();

  @ParameterizedTest
  @CsvSource({
    "empty.wav,false", "empty.wav,true",
    "noise.aif,false", "noise.aif,true",
    "sinewave.flac,false", "sinewave.flac,true",
    "lame_cbr.mp3,false", "lame_cbr.mp3,true"
  })
  void highLevelWritesMatchRewoundCpp(String fixture, boolean afterRead) throws Exception {
    Path directory = Parity.directory("high-level-stream-write/" + fixture + "/" + afterRead);
    Path nativeFile = Files.write(directory.resolve(fixture), fixtureBytes(fixture));
    try (var stream = stream(fixture)) {
      var properties = UNICODE;
      for (String scenario : List.of("unicode", "pictures-replace", "unchanged", "pictures-remove")) {
        if (afterRead) {
          TagLib.read(stream);
        } else {
          stream.seek(stream.length());
        }
        assertTrue(stream.tell() > 0);
        Path output = directory.resolve(scenario + ".json");
        // C++ FileRef retains the cursor; rewind is the Java convenience API's contract.
        Parity.nativeRun(output, "file-reference-reads-and-mutations",
          "mutate-stream", nativeFile.toString(), scenario, "0");
        var expected = Snapshots.JSON.readTree(output.toFile());
        assertTrue(expected.path("opened").asBoolean());
        assertTrue(expected.path("saved").asBoolean());
        assertTrue(expected.path("rejected").path("properties").isEmpty());
        var result = switch (scenario) {
          case "pictures-replace" -> TagLib.write(stream, properties, pictures());
          case "pictures-remove" -> TagLib.write(stream, properties, List.of());
          default -> TagLib.write(stream, properties);
        };
        assertTrue(result.rejected().isEmpty());
        assertTrue(stream.isOpen());
        assertArrayEquals(Files.readAllBytes(nativeFile), stream.data().toByteArray(), scenario);
        var reopened = TagLib.read(stream);
        var actual = Snapshots.of(reopened);
        actual.set("unsupportedData", Snapshots.JSON.valueToTree(reopened.properties().unsupportedData()));
        assertEquals(List.of(), Snapshots.differences(expected.path("post"), actual), scenario);
        properties = reopened.properties();
      }
    }
    Parity.metrics("savedFiles", 4);
  }

  @ParameterizedTest
  @ValueSource(strings = {"empty.wav", "noise.aif"})
  void lowLevelEofBehaviorStillMatchesCpp(String fixture) throws Exception {
    Path directory = Parity.directory("high-level-stream-write/" + fixture + "/low-level-eof");
    byte[] original = fixtureBytes(fixture);
    Path nativeFile = Files.write(directory.resolve(fixture), original);
    Path output = directory.resolve("native.json");
    Parity.nativeRun(output, "file-reference-reads-and-mutations",
      "mutate-stream", nativeFile.toString(), "unicode", Integer.toString(original.length));
    var expected = Snapshots.JSON.readTree(output.toFile());
    try (var stream = stream(fixture)) {
      stream.seek(stream.length());
      try (var ref = new FileRefAdapter(stream, false)) {
        assertEquals(expected.path("opened").asBoolean(), !ref.isNull());
        assertFalse(ref.isNull());
        ref.replaceProperties(UNICODE);
        assertEquals(expected.path("saved").asBoolean(), ref.save());
      }
      assertTrue(stream.isOpen());
      assertArrayEquals(Files.readAllBytes(nativeFile), stream.data().toByteArray());
      assertArrayEquals(original, stream.data().toByteArray());
    }
    Parity.metrics("savedFiles", 1);
  }

  private static byte[] fixtureBytes(String fixture) throws Exception {
    return Files.readAllBytes(Parity.ROOT.resolve("taglib/src/test/resources/audio").resolve(fixture));
  }

  private static ByteVectorStream stream(String fixture) throws Exception {
    return new ByteVectorStream(fixtureBytes(fixture)) {
      @Override
      public String name() {
        return fixture;
      }
    };
  }

  private static List<Picture> pictures() {
    byte[] image = HexFormat.of().parseHex(
      "89504e470d0a1a0a0000000d4948445200000001000000010804000000b51c0c02"
        + "0000000b4944415478da6364f80f00010501012718e3660000000049454e44ae426082");
    return List.of(
      new Picture(3, "image/png", "Front Žluť", image, 1, 1, 8, 0),
      new Picture(4, "image/png", "Back 東京", image, 1, 1, 8, 0));
  }
}
