package cz.skyba.taglib;

import cz.skyba.taglib.toolkit.FileStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DefaultWritePolicyTest {
  @TempDir
  Path directory;

  @Test
  void defaultPublicSavesKeepFileIdentityAndUpdateHardLinks() throws Exception {
    for (int operation = 0; operation < 4; operation++) {
      Path path = wave("default-" + operation);
      Path alias = Files.createLink(directory.resolve("alias-" + operation + ".wav"), path);
      var identity = Files.readAttributes(path, BasicFileAttributes.class).fileKey();
      var properties =
        TagLib.read(path, ReadOptions.TAGS_ONLY).properties().toBuilder()
          .put("TITLE", "Updated title")
          .build();
      switch (operation) {
        case 0 -> TagLib.write(path, properties);
        case 1 -> TagLib.write(
          path,
          properties,
          List.of(new Picture(3, "image/png", "cover", new byte[]{1, 2, 3})));
        case 2 -> assertEquals(PropertyMap.EMPTY, TagLib.write(path, properties).rejected());
        case 3 -> editedDocument(path).save();
        default -> throw new AssertionError();
      }
      assertEquals(identity, Files.readAttributes(path, BasicFileAttributes.class).fileKey());
      assertTrue(Files.isSameFile(path, alias));
      assertEquals(
        "Updated title", TagLib.read(alias, ReadOptions.TAGS_ONLY).properties().first("TITLE"));
      assertArrayEquals(Files.readAllBytes(path), Files.readAllBytes(alias));
    }
  }

  @Test
  void pathWritesRejectNullPicturesWithoutChangingTheFile() throws Exception {
    Path path = wave("invalid-path-pictures");
    byte[] original = Files.readAllBytes(path);
    var properties = PropertyMap.builder().put("TITLE", "Must not be saved").build();

    assertThrows(NullPointerException.class, () -> TagLib.write(path, properties, null));
    assertArrayEquals(original, Files.readAllBytes(path));
    assertThrows(NullPointerException.class,
      () -> TagLib.write(path, properties, Collections.singletonList(null)));
    assertArrayEquals(original, Files.readAllBytes(path));
  }

  @Test
  void streamWritesRejectNullPicturesWithoutChangingOrClosingTheStream() throws Exception {
    Path path = wave("invalid-stream-pictures");
    byte[] original = Files.readAllBytes(path);
    var properties = PropertyMap.builder().put("TITLE", "Must not be saved").build();
    try (var stream = new FileStream(path)) {
      stream.seek(7);
      assertThrows(NullPointerException.class, () -> TagLib.write(stream, properties, null));
      assertEquals(7, stream.tell());
      assertArrayEquals(original, Files.readAllBytes(path));
      assertThrows(NullPointerException.class,
        () -> TagLib.write(stream, properties, Collections.singletonList(null)));
      assertEquals(7, stream.tell());
      assertArrayEquals(original, Files.readAllBytes(path));
      assertTrue(stream.isOpen());
    }
  }

  @Test
  void pathWritesReplacePreserveAndRemovePictures() throws Exception {
    Path path = wave("path-pictures");
    var picture = new Picture(3, "image/png", "cover", new byte[]{1, 2, 3});
    var properties = PropertyMap.builder().put("TITLE", "With artwork").build();
    TagLib.write(path, properties, List.of(picture));
    assertEquals(List.of(picture), TagLib.read(path, ReadOptions.TAGS_ONLY).pictures());

    properties = properties.toBuilder().put("TITLE", "Preserved artwork").build();
    TagLib.write(path, properties);
    var preserved = TagLib.read(path, ReadOptions.TAGS_ONLY);
    assertEquals(properties.asMap(), preserved.properties().asMap());
    assertEquals(List.of(picture), preserved.pictures());

    properties = properties.toBuilder().put("TITLE", "Removed artwork").build();
    TagLib.write(path, properties, List.of());
    var removed = TagLib.read(path, ReadOptions.TAGS_ONLY);
    assertEquals(properties.asMap(), removed.properties().asMap());
    assertTrue(removed.pictures().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"empty.wav", "noise.aif", "sinewave.flac", "lame_cbr.mp3"})
  void streamWritesReplacePreserveAndRemovePictures(String fixture) throws Exception {
    Path path = AudioFixtures.copy(directory, fixture);
    var picture = new Picture(3, "image/png", "cover", new byte[]{1, 2, 3});
    var properties = PropertyMap.builder().put("TITLE", "With artwork").build();
    try (var stream = new FileStream(path)) {
      stream.seek(stream.length());
      TagLib.write(stream, properties, List.of(picture));
      assertEquals(List.of(picture), TagLib.read(stream, ReadOptions.TAGS_ONLY).pictures());

      properties = properties.toBuilder().put("TITLE", "Preserved artwork").build();
      TagLib.write(stream, properties);
      var preserved = TagLib.read(stream, ReadOptions.TAGS_ONLY);
      assertEquals(properties.asMap(), preserved.properties().asMap());
      assertEquals(List.of(picture), preserved.pictures());

      properties = properties.toBuilder().put("TITLE", "Removed artwork").build();
      TagLib.write(stream, properties, List.of());
      var removed = TagLib.read(stream, ReadOptions.TAGS_ONLY);
      assertEquals(properties.asMap(), removed.properties().asMap());
      assertTrue(removed.pictures().isEmpty());
      assertTrue(stream.isOpen());
    }
  }

  private MetadataDocument editedDocument(Path path) throws Exception {
    var document = MetadataDocument.open(path);
    var title =
      document.entries().stream()
        .filter(entry -> entry.standardKey().equals("TITLE"))
        .findFirst()
        .orElseThrow();
    document.apply(
      List.of(
        new MetadataChange.Update(title.id(), title.key(), List.of("Updated title"))));
    return document;
  }

  private Path wave(String name) throws Exception {
    byte[] audio = new byte[4096];
    new Random(19).nextBytes(audio);
    var bytes = ByteBuffer.allocate(44 + audio.length).order(ByteOrder.LITTLE_ENDIAN);
    bytes.put("RIFF".getBytes(StandardCharsets.US_ASCII));
    bytes.putInt(bytes.capacity() - 8);
    bytes.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
    bytes.putInt(16).putShort((short) 1).putShort((short) 1);
    bytes.putInt(44100).putInt(88200).putShort((short) 2).putShort((short) 16);
    bytes.put("data".getBytes(StandardCharsets.US_ASCII));
    bytes.putInt(audio.length).put(audio);
    Path path = directory.resolve(name + ".wav");
    Files.write(path, bytes.array());
    TagLib.write(path, PropertyMap.builder().put("TITLE", "Original title").build());
    return path;
  }
}
