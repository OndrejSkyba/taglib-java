package cz.skyba.taglib;

import cz.skyba.taglib.ape.ApeFile;
import cz.skyba.taglib.mpeg.MpegFile;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Complete native registration assertions through the actual mutable legacy owners.
 */
class Id3v1RiffInfoAndApeOwnerMutationTest {
  @TempDir
  Path directory;

  static Path fixture(String name) {
    return AudioFixtures.fixture(name);
  }

  @Test
  void testStripWhiteSpace() throws Exception {
    Path path = Files.copy(fixture("xing.mp3"), directory.resolve("xing.mp3"));
    try (var f = new MpegFile(path)) {
      f.getOrCreateId3v1Tag().setArtist("Artist     ");
      f.save();
    }
    try (var f = new MpegFile(path)) {
      assertNotNull(f.id3v1Tag());
      assertEquals("Artist", f.id3v1Tag().artist());
    }
  }

  static void fullPropertyContract(TagBase tag) {
    tag.setTitle("Test Title");
    tag.setArtist("Test Artist");
    tag.setAlbum("Test Album");
    tag.setYear(2015);
    tag.setTrack(10);
    PropertyMap prop = tag.properties();
    assertEquals("Test Title", prop.get("TITLE").getFirst());
    assertEquals("Test Artist", prop.get("ARTIST").getFirst());
    assertEquals("Test Album", prop.get("ALBUM").getFirst());
    assertEquals("2015", prop.get("DATE").getFirst());
    assertEquals("10", prop.get("TRACKNUMBER").getFirst());
    tag.replaceProperties(prop.toBuilder().put("TITLE", "Test Title 2").put("ARTIST", "Test Artist 2").put("TRACKNUMBER", "5").build());
    assertEquals("Test Title 2", tag.title());
    assertEquals("Test Artist 2", tag.artist());
    assertEquals(5, tag.track());
    assertFalse(tag.isEmpty());
    PropertyMap props = tag.properties();
    assertEquals(List.of("Test Artist 2"), props.asMap().get("ARTIST"));
    assertFalse(props.asMap().containsKey("COMMENT"));
    props = props.toBuilder().put("ARTIST", "Test Artist 3").build();
    assertEquals(List.of("Test Artist 3"), props.get("ARTIST"));
    props = props.toBuilder().remove("ARTIST").remove("ALBUM").remove("TITLE").build();
    assertEquals("DATE=2015\nTRACKNUMBER=5\n", props.toString());
    tag.replaceProperties(PropertyMap.EMPTY);
    assertTrue(tag.isEmpty());
    assertTrue(tag.properties().isEmpty());
    assertEquals("", tag.title());
    assertEquals("", tag.artist());
    assertEquals("", tag.album());
    assertEquals("", tag.comment());
    assertEquals("", tag.genre());
    assertEquals(0, tag.track());
    assertTrue(tag.isEmpty());
    assertTrue(tag.properties().isEmpty());
  }

  @Test
  void testGetSetId3v1() {
    fullPropertyContract(new cz.skyba.taglib.id3.v1.Tag());
  }

  @Test
  void testGetSetInfo() {
    fullPropertyContract(new cz.skyba.taglib.riff.info.Tag());
  }

  @Test
  void testID3v1Collision() throws Exception {
    // Saving an APE tag to an empty file must not create an ID3v1 footer.
    Path path = Files.write(directory.resolve("no-tags.mpc"), new byte[0]);
    try (var f = new ApeFile(path)) {
      f.getOrCreateApeTag().setArtist("Filltointersect    ");
      f.apeTag().setTitle("Filltointersect    ");
      f.save();
    }
    try (var f = new ApeFile(path)) {
      assertFalse(f.hasId3v1Tag());
    }
  }

  @Test
  void realApeCollisionRetainsAbsenceOfLegacyFooter() throws Exception {
    Path path = Files.copy(fixture("mac-399.ape"), directory.resolve("mac-399.ape"));
    try (var f = new ApeFile(path)) {
      assertTrue(f.isValid());
      f.getOrCreateApeTag().setArtist("Filltointersect    ");
      f.apeTag().setTitle("Filltointersect    ");
      f.save();
    }
    try (var f = new ApeFile(path)) {
      assertFalse(f.hasId3v1Tag());
      assertEquals("Filltointersect    ", f.apeTag().title());
    }
  }

  @Test
  void testApeId3v1Mp3() throws Exception {
    Path path = fixture("ape-id3v1.mp3");
    assertTrue(Files.isReadable(path));
    var stream = new ByteVectorStream(new ByteVector(Files.readAllBytes(path)));
    try (var ref = new AudioFile(stream)) {
      assertTrue(ref.isOpen());
      assertInstanceOf(MpegFile.class, ref.file());
    }
  }
}
