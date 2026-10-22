package cz.skyba.taglib;

import cz.skyba.taglib.matroska.AttachedFile;
import cz.skyba.taglib.matroska.MatroskaFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TagLibPictureEditTest {
  @TempDir
  Path directory;

  private static Picture picture(PictureType type, int marker) {
    byte[] jpeg = ComplexPropertyAccessTest.PIXEL.data();
    byte[] data = Arrays.copyOf(jpeg, jpeg.length + 1);
    data[jpeg.length] = (byte) marker;
    return new Picture(type, "image/jpeg", "Cover " + marker, data);
  }

  @ParameterizedTest
  @ValueSource(strings = {"lame_cbr.mp3", "no-tags.flac", "test.ogg",
    "correctness_gain_silent_output.opus", "empty.spx", "empty_flac.oga",
    "silence-1.wma", "empty.wav", "noise.aif", "mac-399.ape", "click.wv",
    "click.mpc", "tagged.tta", "empty10ms.dsf", "empty10ms.dff"})
  void setAndRemoveByRolePreserveOtherArtworkAndText(String fixture) throws Exception {
    Path path = AudioFixtures.copy(directory, fixture);
    TagLib.write(path, PropertyMap.builder().put("TITLE", "Keep title").build(),
      List.of(picture(PictureType.FRONT_COVER, 1), picture(PictureType.BACK_COVER, 2)));
    var before = TagLib.read(path);
    var back = before.pictures().stream().filter(p -> p.type() == PictureType.BACK_COVER.code()).findFirst().orElseThrow();
    var front = picture(PictureType.FRONT_COVER, 3);

    TagLib.edit(path).setPicture(front).save();

    var after = TagLib.read(path);
    assertEquals(before.properties(), after.properties());
    assertEquals(2, after.pictures().size());
    assertTrue(after.pictures().contains(back));
    assertArrayEquals(front.data(), after.pictures().stream()
      .filter(p -> p.type() == PictureType.FRONT_COVER.code()).findFirst().orElseThrow().data());

    TagLib.edit(path).removePictures(PictureType.FRONT_COVER).save();
    assertEquals(List.of(back), TagLib.read(path).pictures());
    TagLib.edit(path).removePictures(PictureType.BACK_COVER).save();
    assertTrue(TagLib.read(path).pictures().isEmpty());
    assertEquals(before.properties().asMap(), TagLib.read(path).properties().asMap());
  }

  @Test
  void setAndRemoveAffectAllPicturesOfTheSelectedRole() throws Exception {
    Path path = AudioFixtures.copy(directory, "no-tags.flac");
    var back = picture(PictureType.BACK_COVER, 1);
    TagLib.edit(path).replacePictures(picture(PictureType.FRONT_COVER, 2), back,
      picture(PictureType.FRONT_COVER, 3)).save();
    var front = picture(PictureType.FRONT_COVER, 4);

    TagLib.edit(path).removePictures(PictureType.FRONT_COVER).setPicture(front).save();
    assertEquals(List.of(back, front), TagLib.read(path).pictures());

    TagLib.edit(path).replacePictures(front, back, picture(PictureType.FRONT_COVER, 5))
      .removePictures(PictureType.FRONT_COVER).save();
    assertEquals(List.of(back), TagLib.read(path).pictures());

    TagLib.edit(path).replacePictures(front, picture(PictureType.FRONT_COVER, 5), back)
      .setPicture(front).save();
    assertEquals(List.of(back, front), TagLib.read(path).pictures());
  }

  @Test
  void replacementDiscardsEarlierPictureEditsAndLaterOperationsModifyIt() throws Exception {
    Path path = AudioFixtures.copy(directory, "no-tags.flac");
    var front = picture(PictureType.FRONT_COVER, 1);
    var back = picture(PictureType.BACK_COVER, 2);
    TagLib.edit(path).set("TITLE", "New title").setPicture(picture(PictureType.ARTIST, 3))
      .removePictures(PictureType.BACK_COVER).replacePictures(back, front)
      .removePictures(PictureType.FRONT_COVER).setPicture(picture(PictureType.BACK_COVER, 4))
      .setPicture(back).save();

    assertEquals(List.of(back), TagLib.read(path).pictures());
    assertEquals("New title", TagLib.read(path).tag().title());

    TagLib.edit(path).replacePictures().save();
    assertTrue(TagLib.read(path).pictures().isEmpty());
    assertEquals("New title", TagLib.read(path).tag().title());
  }

  @Test
  void builtRequestsCopyInputsAndMergeWithArtworkPresentAtEachSave() throws Exception {
    Path path = directory.resolve("later.flac");
    var front = picture(PictureType.FRONT_COVER, 1);
    var back = picture(PictureType.BACK_COVER, 2);
    var pictures = new ArrayList<>(List.of(back));
    var builder = TagLib.edit(path).replacePictures(pictures).setPicture(front);
    var replacement = builder.build();
    pictures.clear();
    builder.replacePictures();
    assertFalse(Files.exists(path));
    Files.copy(AudioFixtures.fixture("no-tags.flac"), path);

    replacement.save();
    assertEquals(List.of(back, front), TagLib.read(path).pictures());

    var request = TagLib.edit(path).setPicture(front).build();
    var artist = picture(PictureType.ARTIST, 3);
    TagLib.edit(path).replacePictures(artist).save();
    request.save();
    assertEquals(List.of(artist, front), TagLib.read(path).pictures());
    TagLib.edit(path).replacePictures(back).save();
    request.save();
    assertEquals(List.of(back, front), TagLib.read(path).pictures());
  }

  @Test
  void pathConvenienceReadsImageImmediatelyAndSavesWithTextEdits() throws Exception {
    Path path = AudioFixtures.copy(directory, "no-tags.flac");
    byte[] jpeg = ComplexPropertyAccessTest.PIXEL.data();
    Path image = Files.write(directory.resolve("cover.data"), jpeg);
    var request = TagLib.edit(path).set("TITLE", "New title")
      .setPicture(PictureType.FRONT_COVER, image).build();
    Files.delete(image);
    assertTrue(TagLib.read(path).pictures().isEmpty());

    request.save();

    var after = TagLib.read(path);
    assertEquals("New title", after.tag().title());
    assertEquals(List.of(new Picture(PictureType.FRONT_COVER, "image/jpeg", "", jpeg)), after.pictures());
  }

  @ParameterizedTest
  @ValueSource(strings = {"has-tags.m4a", "no-tags.mka"})
  void wholeListReplacementWorksInFormatsWithoutPictureRoles(String fixture) throws Exception {
    Path path = AudioFixtures.copy(directory, fixture);
    var picture = picture(PictureType.OTHER, 1);
    TagLib.edit(path).replacePictures(picture).save();
    assertEquals(1, TagLib.read(path).pictures().size());
    assertArrayEquals(picture.data(), TagLib.read(path).pictures().getFirst().data());
    var replacement = picture(PictureType.OTHER, 2);
    var request = TagLib.edit(path).setPicture(replacement).build();
    request.save();
    request.save();
    assertEquals(1, TagLib.read(path).pictures().size());
    assertArrayEquals(replacement.data(), TagLib.read(path).pictures().getFirst().data());
    TagLib.edit(path).removePictures(PictureType.OTHER).save();
    assertTrue(TagLib.read(path).pictures().isEmpty());
    TagLib.edit(path).replacePictures(picture).save();
    TagLib.edit(path).replacePictures(List.of()).save();
    assertTrue(TagLib.read(path).pictures().isEmpty());
  }

  @Test
  void removingAnAbsentRolePreservesMatroskaAttachments() throws Exception {
    Path path = AudioFixtures.copy(directory, "no-tags.mka");
    var attachments = List.of(
      new AttachedFile(picture(PictureType.OTHER, 1).data(), "cover.jpg", "image/jpeg", 12345L, "Cover"),
      new AttachedFile(picture(PictureType.OTHER, 2).data(), "small_cover.jpg", "image/jpeg", 67890L, "Thumbnail"),
      new AttachedFile(new byte[]{1, 2, 3}, "notes.txt", "text/plain", 24680L, "Notes"));
    try (var file = new MatroskaFile(path, ReadOptions.TAGS_ONLY)) {
      attachments.forEach(file.getOrCreateAttachments()::addAttachedFile);
      file.save();
    }

    TagLib.edit(path).set("TITLE", "Updated title").removePictures(PictureType.BACK_COVER).save();

    try (var file = new MatroskaFile(path, ReadOptions.TAGS_ONLY)) {
      assertEquals(attachments, file.attachments().attachedFileList());
      assertEquals("Updated title", file.tag().title());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"test.mod", "test.it"})
  void unsupportedArtworkPreventsSavingTextEdits(String fixture) throws Exception {
    Path path = AudioFixtures.copy(directory, fixture);
    byte[] before = Files.readAllBytes(path);
    assertThrows(UnsupportedOperationException.class, () -> TagLib.edit(path)
      .set("TITLE", "Do not save").setPicture(picture(PictureType.FRONT_COVER, 1)).save());
    assertArrayEquals(before, Files.readAllBytes(path));
    assertThrows(UnsupportedOperationException.class,
      () -> TagLib.edit(path).removePictures(PictureType.FRONT_COVER).save());
    assertArrayEquals(before, Files.readAllBytes(path));
  }

  @Test
  void shortenRejectsPictureOnlyEditsWithoutChangingStorage() throws Exception {
    Path path = AudioFixtures.copy(directory, "2sec-silence.shn");
    byte[] before = Files.readAllBytes(path);
    assertThrows(UnsupportedOperationException.class,
      () -> TagLib.edit(path).setPicture(picture(PictureType.FRONT_COVER, 1)).save());
    assertThrows(UnsupportedOperationException.class,
      () -> TagLib.edit(path).removePictures(PictureType.FRONT_COVER).save());
    assertArrayEquals(before, Files.readAllBytes(path));
  }

  @ParameterizedTest
  @ValueSource(strings = {"has-tags.m4a", "no-tags.mka", "mac-399.ape", "click.wv", "click.mpc"})
  void unsupportedRolesPreventSavingOtherEdits(String fixture) throws Exception {
    Path path = AudioFixtures.copy(directory, fixture);
    byte[] before = Files.readAllBytes(path);
    assertThrows(MetadataEditException.class, () -> TagLib.edit(path).set("TITLE", "Do not save")
      .setPicture(picture(PictureType.ARTIST, 1)).save());
    assertArrayEquals(before, Files.readAllBytes(path));
  }

  @Test
  void rejectedTextPreventsSavingPictureEdits() throws Exception {
    Path path = AudioFixtures.copy(directory, "has-tags.m4a");
    byte[] before = Files.readAllBytes(path);
    assertThrows(MetadataEditException.class, () -> TagLib.edit(path).set("1INVALID", "Value")
      .replacePictures(picture(PictureType.OTHER, 1)).save());
    assertArrayEquals(before, Files.readAllBytes(path));
  }

  @Test
  void invalidInputsDoNotDiscardPreviouslyStagedArtwork() throws Exception {
    Path path = AudioFixtures.copy(directory, "no-tags.flac");
    var picture = picture(PictureType.FRONT_COVER, 1);
    var builder = TagLib.edit(path).replacePictures(picture);
    assertThrows(NullPointerException.class, () -> builder.setPicture(null));
    assertThrows(NullPointerException.class, () -> builder.removePictures(null));
    assertThrows(NullPointerException.class, () -> builder.replacePictures((Picture[]) null));
    assertThrows(NullPointerException.class, () -> builder.replacePictures(Arrays.asList(picture, null)));
    builder.save();
    assertEquals(List.of(picture), TagLib.read(path).pictures());
  }
}
