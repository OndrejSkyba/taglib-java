package cz.skyba.taglib.parity.ogg;

import cz.skyba.taglib.TagLib;
import cz.skyba.taglib.flac.Picture;
import cz.skyba.taglib.ogg.OggFile;
import cz.skyba.taglib.ogg.XiphComment;
import cz.skyba.taglib.ogg.flac.OggFlacFile;
import cz.skyba.taglib.ogg.opus.OpusFile;
import cz.skyba.taglib.ogg.speex.SpeexFile;
import cz.skyba.taglib.ogg.vorbis.VorbisFile;
import cz.skyba.taglib.parity.Snapshots;
import cz.skyba.taglib.parity.support.Parity;
import cz.skyba.taglib.toolkit.ByteVector;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("parity")
@Tag("ogg")
final class XiphWrappedPictureTest {
  @ParameterizedTest
  @CsvSource({
    "empty.ogg,METADATA_BLOCK_PICTURE", "empty.ogg,COVERART",
    "correctness_gain_silent_output.opus,METADATA_BLOCK_PICTURE", "correctness_gain_silent_output.opus,COVERART",
    "empty.spx,METADATA_BLOCK_PICTURE", "empty.spx,COVERART",
    "empty_flac.oga,METADATA_BLOCK_PICTURE", "empty_flac.oga,COVERART"
  })
  void wrappedArtworkSurvivesEditsAndMatchesUnwrappedCpp(String fixture, String key) throws Exception {
    Path directory = Parity.directory("xiph-wrapped-picture/" + fixture + "/" + key);
    Path javaFile = seed(directory.resolve("java-" + fixture), fixture, key, true);
    Path nativeFile = seed(directory.resolve("native-" + fixture), fixture, key, false);
    Path nativeWrapped = Files.copy(javaFile, directory.resolve("wrapped-native-" + fixture));
    Path raw = directory.resolve("wrapped-native.json");
    Parity.nativeRun(raw, "file-reference-reads-and-mutations", "mutate", nativeWrapped.toString(), "common-title");
    // Pinned C++ drops wrapped artwork; accepting its whitespace is a Java compatibility fix.
    var dropped = Snapshots.JSON.readTree(raw.toFile());
    assertTrue(dropped.path("saved").asBoolean());
    assertTrue(dropped.path("post").path("pictures").isEmpty());
    var original = TagLib.read(javaFile);
    assertEquals(1, original.pictures().size());
    var pictures = original.pictures();

    for (String scenario : List.of("common-title", "unchanged")) {
      Path output = directory.resolve(scenario + ".json");
      Parity.nativeRun(output, "file-reference-reads-and-mutations", "mutate", nativeFile.toString(), scenario);
      var expected = Snapshots.JSON.readTree(output.toFile());
      assertTrue(expected.path("saved").asBoolean());
      if (scenario.equals("common-title")) {
        try (var file = open(javaFile)) {
          file.tag().setTitle("Common title");
          file.save();
        }
      } else {
        TagLib.write(javaFile, TagLib.read(javaFile).properties());
      }
      Parity.bytes(nativeFile, javaFile);
      assertNotEquals(-1, Files.mismatch(nativeWrapped, javaFile));
      var reopened = TagLib.read(javaFile);
      assertEquals("Common title", reopened.tag().title());
      assertEquals(pictures, reopened.pictures());
      assertEquals(original.audioProperties(), reopened.audioProperties());
      var actual = Snapshots.of(reopened);
      actual.set("unsupportedData", Snapshots.JSON.valueToTree(reopened.properties().unsupportedData()));
      assertEquals(List.of(), Snapshots.differences(expected.path("post"), actual));
    }
    Parity.metrics("savedFiles", 2);
  }

  private static Path seed(Path path, String fixture, String key, boolean wrapped) throws Exception {
    Files.copy(Parity.ROOT.resolve("taglib/src/test/resources/audio").resolve(fixture), path);
    var picture = new Picture();
    picture.setType(Picture.Type.FrontCover);
    picture.setMimeType("image/jpeg");
    picture.setDescription("Cover é音");
    picture.setData(new byte[200]);
    var bytes = key.equals("COVERART") ? new ByteVector(picture.data()) : new ByteVector(picture.render());
    String encoded = bytes.toBase64().toString();
    if (wrapped) {
      var lines = new StringBuilder();
      for (int i = 0; i < encoded.length(); i += 76) {
        lines.append(encoded, i, Math.min(i + 76, encoded.length())).append("\r\n");
      }
      encoded = lines.toString();
    }
    try (var file = open(path)) {
      var comment = (XiphComment) file.tag();
      comment.removeAllPictures();
      comment.addField(key, encoded);
      file.save();
    }
    return path;
  }

  private static OggFile open(Path path) {
    String name = path.getFileName().toString();
    if (name.endsWith(".opus")) return new OpusFile(path);
    if (name.endsWith(".spx")) return new SpeexFile(path);
    if (name.endsWith(".oga")) return new OggFlacFile(path);
    return new VorbisFile(path);
  }
}
