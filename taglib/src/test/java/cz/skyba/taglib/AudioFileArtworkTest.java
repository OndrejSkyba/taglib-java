package cz.skyba.taglib;

import cz.skyba.taglib.id3.v2.GeneralEncapsulatedObjectFrame;
import cz.skyba.taglib.mpeg.MpegFile;
import cz.skyba.taglib.toolkit.FileStream;
import cz.skyba.taglib.toolkit.Variant;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class AudioFileArtworkTest {
  @TempDir
  Path directory;

  private static Picture picture() {
    return new Picture(3, "image/jpeg", "cover", ComplexPropertyAccessTest.PIXEL.data(), 1, 1, 8, 1);
  }

  @TestFactory
  Stream<DynamicTest> artworkReplacementAndRemovalRoundTripThroughSupportedFormats() {
    return Stream.of("lame_cbr.mp3", "no-tags.flac", "test.ogg",
        "correctness_gain_silent_output.opus", "empty.spx", "empty_flac.oga", "no-tags.m4a",
        "silence-1.wma", "empty.wav", "noise.aif", "mac-399.ape", "click.wv", "click.mpc",
        "tagged.tta", "empty10ms.dsf", "empty10ms.dff", "no-tags.mka")
      .map(name -> DynamicTest.dynamicTest(name, () -> {
        Path path = Files.copy(UpstreamAudioFixtures.fixture(name), directory.resolve(name));
        byte[] original = Files.readAllBytes(path);
        var picture = picture();
        List<Picture> snapshot;
        try (var file = new AudioFile(path, ReadOptions.TAGS_ONLY)) {
          assertTrue(file.supportsPictureEditing());
          file.replacePictures(List.of(picture));
          snapshot = file.pictures();
          assertEquals(1, snapshot.size());
          assertArrayEquals(picture.data(), snapshot.getFirst().data());
          assertThrows(UnsupportedOperationException.class, snapshot::clear);
          assertArrayEquals(original, Files.readAllBytes(path));
          file.save();
        }
        try (var file = new AudioFile(path, ReadOptions.TAGS_ONLY)) {
          assertArrayEquals(picture.data(), file.pictures().getFirst().data());
          if (name.endsWith(".flac")) {
            assertEquals(picture, file.pictures().getFirst());
          }
          file.replacePictures(List.of());
          assertTrue(file.pictures().isEmpty());
          assertEquals(1, snapshot.size());
          assertTrue(file.supportsPictureEditing());
          file.save();
        }
        assertTrue(TagLib.read(path, ReadOptions.TAGS_ONLY).pictures().isEmpty());
      }));
  }

  @Test
  void picturesInLaterTagsAreVisibleWithoutChangingKeyPrecedence() throws Exception {
    Path path = Files.copy(UpstreamAudioFixtures.fixture("lame_cbr.mp3"), directory.resolve("mixed.mp3"));
    var picture = picture();
    try (var owner = new AudioFile(new MpegFile(path, ReadOptions.TAGS_ONLY))) {
      var file = (MpegFile) owner.file();
      file.getOrCreateId3v2Tag().addFrame(new GeneralEncapsulatedObjectFrame());
      assertTrue(file.getOrCreateApeTag().setComplexProperties("PICTURE", List.of(Map.of(
        "pictureType", new Variant("Front Cover"), "description", new Variant(picture.description()),
        "data", Variant.fromByteArray(picture.data())))));
      assertEquals(List.of("GENERALOBJECT"), owner.complexPropertyKeys());
      assertEquals(1, owner.complexProperties("PICTURE").size());
      assertArrayEquals(picture.data(), owner.pictures().getFirst().data());
      owner.save();
    }
    var snapshot = TagLib.read(path, ReadOptions.TAGS_ONLY);
    assertEquals(1, snapshot.pictures().size());
    assertArrayEquals(picture.data(), snapshot.pictures().getFirst().data());
  }

  @Test
  void capabilityIsIndependentOfPresenceAndStorageWritability() throws Exception {
    Path path = UpstreamAudioFixtures.fixture("no-tags.flac");
    byte[] original = Files.readAllBytes(path);
    try (var stream = new FileStream(path, true); var file = new AudioFile(stream, ReadOptions.TAGS_ONLY)) {
      assertTrue(file.complexPropertyKeys().isEmpty());
      assertTrue(file.pictures().isEmpty());
      assertTrue(file.supportsPictureEditing());
      file.replacePictures(List.of(picture()));
      assertThrows(TagLibException.class, file::save);
      assertArrayEquals(original, Files.readAllBytes(path));
    }
  }

  @Test
  void unsupportedFormatsRejectReplacementAndRemoval() {
    for (var name : List.of("test.mod", "test.s3m", "test.it", "test.xm", "2sec-silence.shn")) {
      try (var file = new AudioFile(UpstreamAudioFixtures.fixture(name), ReadOptions.TAGS_ONLY)) {
        assertFalse(file.supportsPictureEditing());
        assertTrue(file.pictures().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> file.replacePictures(List.of(picture())));
        assertThrows(UnsupportedOperationException.class, () -> file.replacePictures(List.of()));
      }
    }
  }

  @Test
  void invalidReplacementDoesNotDiscardExistingPicturesAndClosedOwnerRejectsAccess() throws Exception {
    Path path = Files.copy(UpstreamAudioFixtures.fixture("no-tags.flac"), directory.resolve("null.flac"));
    var picture = picture();
    var file = new AudioFile(path, ReadOptions.TAGS_ONLY);
    try (file) {
      file.replacePictures(List.of(picture));
      assertThrows(NullPointerException.class, () -> file.replacePictures(null));
      assertThrows(NullPointerException.class, () -> file.replacePictures(Arrays.asList(picture, null)));
      assertEquals(List.of(picture), file.pictures());
      byte[] returned = file.pictures().getFirst().data();
      returned[0] = 0;
      assertArrayEquals(picture.data(), file.pictures().getFirst().data());
    }
    assertThrows(IllegalStateException.class, file::pictures);
    assertThrows(IllegalStateException.class, file::supportsPictureEditing);
    assertThrows(IllegalStateException.class, () -> file.replacePictures(List.of()));
  }
}
