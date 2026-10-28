package cz.skyba.taglib.parity.asf;

import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.asf.AsfFile;
import cz.skyba.taglib.asf.Attribute;
import cz.skyba.taglib.asf.Picture;
import cz.skyba.taglib.parity.support.Parity;
import cz.skyba.taglib.toolkit.ByteVector;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@Tag("parity")
@Tag("asf")
final class AsfScopedPictureCopyTest {
  @ParameterizedTest
  @CsvSource({"0,0", "43,0", "43,56"})
  void scopedPictureEditsMatchCpp(int stream, int language) throws Exception {
    Path directory = Parity.directory("asf-scoped-picture/" + stream + "/" + language);
    Path fixture = Parity.ROOT.resolve("taglib/src/test/resources/audio/silence-1.wma");
    Path nativeFile = Files.copy(fixture, directory.resolve("native.wma"));
    Path javaFile = Files.copy(fixture, directory.resolve("java.wma"));
    Parity.nativeRun(directory.resolve("save.txt"), "container-properties-and-structured-edits",
      "asf-scoped-picture", nativeFile.toString(), Integer.toString(stream), Integer.toString(language));

    var picture = new Picture();
    picture.setType(Picture.Type.FrontCover);
    picture.setMimeType("image/jpeg");
    picture.setDescription("Front é音");
    picture.setPicture(new ByteVector("data"));
    var original = new Attribute(picture).named("WM/Picture");
    // The Java adapter adds independent copy semantics to C++'s copy-and-set operation.
    var scoped = original.withScope(stream, language);
    assertTrue(scoped.toPicture().isValid());
    scoped.toPicture().setDescription("Scoped é音");
    scoped.toPicture().setPicture(new ByteVector("new image"));
    assertEquals("Front é音", original.toPicture().description());
    assertEquals(new ByteVector("data"), original.toPicture().picture());
    try (var file = new AsfFile(javaFile, ReadOptions.TAGS_ONLY)) {
      file.tag().setAttribute("WM/Picture", scoped);
      file.save();
    }

    Parity.bytes(nativeFile, javaFile);
    Path expected = directory.resolve("native.txt"), actual = directory.resolve("java.txt");
    Parity.nativeRun(expected, "container-properties-and-structured-edits", "asf-attributes", nativeFile.toString());
    Parity.nativeRun(actual, "container-properties-and-structured-edits", "asf-attributes", javaFile.toString());
    Parity.transcripts(expected, actual);
    try (var file = new AsfFile(javaFile, ReadOptions.TAGS_ONLY)) {
      var saved = file.tag().attribute("WM/Picture").getFirst();
      assertEquals(stream, saved.stream());
      assertEquals(language, saved.language());
      assertTrue(saved.toPicture().isValid());
      assertEquals(Picture.Type.FrontCover, saved.toPicture().type());
      assertEquals("image/jpeg", saved.toPicture().mimeType());
      assertEquals("Scoped é音", saved.toPicture().description());
      assertEquals(new ByteVector("new image"), saved.toPicture().picture());
    }
    Parity.metrics("savedFiles", 1);
  }
}
