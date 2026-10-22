package cz.skyba.taglib;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class PictureTest {
  @TempDir
  Path directory;

  @ParameterizedTest
  @CsvSource({
    "ffd8ffdb, image/jpeg",
    "89504e470d0a1a0a, image/png",
    "474946383761, image/gif",
    "474946383961, image/gif",
    "424d00000000, image/bmp",
    "49492a0000000000, image/tiff",
    "4d4d002a00000000, image/tiff",
    "524946460000000057454250, image/webp"
  })
  void readsImageBytesAndDetectsMimeTypeRegardlessOfFilename(String hex, String mimeType) throws Exception {
    byte[] data = HexFormat.of().parseHex(hex);
    Path image = Files.write(directory.resolve("image.data"), data);

    var picture = Picture.from(PictureType.BACK_COVER, image);

    assertEquals(PictureType.BACK_COVER.code(), picture.type());
    assertEquals(mimeType, picture.mimeType());
    assertEquals("", picture.description());
    assertArrayEquals(data, picture.data());
    assertEquals(0, picture.width());
    assertEquals(0, picture.height());
  }

  @Test
  void rejectsUnknownImageContentsAndPreservesIoFailures() throws Exception {
    Path image = Files.writeString(directory.resolve("not-an-image.jpg"), "Not an image");
    assertThrows(IllegalArgumentException.class, () -> Picture.from(PictureType.FRONT_COVER, image));
    Files.write(image, new byte[0]);
    assertThrows(IllegalArgumentException.class, () -> Picture.from(PictureType.FRONT_COVER, image));

    var failure = assertThrows(UncheckedIOException.class,
      () -> Picture.from(PictureType.FRONT_COVER, directory.resolve("missing.jpg")));
    assertInstanceOf(NoSuchFileException.class, failure.getCause());
  }

  @Test
  void namedRoleConstructorKeepsTheExistingByteOwnershipContract() {
    byte[] data = {1, 2, 3};
    var picture = new Picture(PictureType.FRONT_COVER, "image/png", "Cover", data);
    data[0] = 9;
    picture.data()[1] = 9;
    assertEquals(new Picture(3, "image/png", "Cover", new byte[]{1, 2, 3}), picture);
  }
}
