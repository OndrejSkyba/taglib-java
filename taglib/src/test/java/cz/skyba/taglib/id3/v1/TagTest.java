package cz.skyba.taglib.id3.v1;

import cz.skyba.taglib.*;
import cz.skyba.taglib.internal.TextBytes;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.IOStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class TagTest {
  @AfterEach
  void resetHandler() {
    Tag.setStringHandler(null);
  }

  @Test
  void upstreamStripWhiteSpace() throws Exception {
    var tag = new Tag();
    tag.setArtist("Artist     ");
    var stream =
      new ByteVectorStream(tag.render()) {
        @Override
        public String name() {
          return "strip-space.mp3";
        }
      };
    try (var reference = new AudioFile(stream, ReadOptions.TAGS_ONLY)) {
      assertTrue(reference.isOpen());
      reference.tag().setArtist("Artist     ");
      assertDoesNotThrow(reference::save);
    }
    var file = new MemoryFile(stream);
    assertTrue(stream.data().mid((int) stream.length() - 128, 3).equals(Tag.fileIdentifier()));
    assertEquals("Artist", new Tag(file, stream.length() - 128).artist());
    assertEquals(stream.length(), stream.tell());
    assertTrue(stream.isOpen());
  }

  @Test
  void upstreamGenres() {
    assertEquals("Darkwave", Genres.genre(50));
    assertEquals(100, Genres.genreIndex("Humour"));
    assertTrue(Genres.genreList().contains("Heavy Metal"));
    assertEquals(79, Genres.genreMap().get("Hard Rock"));
    var list = Genres.genreList();
    list.clear();
    var map = Genres.genreMap();
    map.clear();
    assertEquals(192, Genres.genreList().size());
    assertEquals(192, Genres.genreMap().size());
  }

  @Test
  void upstreamRenamedGenres() {
    assertEquals("Bebop", Genres.genre(85));
    assertEquals(85, Genres.genreIndex("Bebop"));
    assertEquals(85, Genres.genreIndex("Bebob"));
    var tag = new Tag();
    tag.setGenre("Hardcore");
    assertEquals("Hardcore Techno", tag.genre());
    assertEquals(129, tag.genreNumber());
    assertEquals(255, Genres.genreIndex("hardcore"));
  }

  @Test
  void hiddenRawYearAndReservedGenreSurviveRendering() {
    byte[] source = footer();
    System.arraycopy("oops".getBytes(StandardCharsets.US_ASCII), 0, source, 93, 4);
    source[127] = (byte) 222;
    var tag = new Tag(new ByteVector(source));
    assertTrue(tag.isEmpty());
    assertTrue(tag.properties().isEmpty());
    assertEquals(0, tag.year());
    assertEquals("", tag.genre());
    assertEquals(222, tag.genreNumber());
    assertArrayEquals(source, tag.render().toByteArray());
    tag.setYear(0xffff_ffffL);
    assertEquals(0xffff_ffffL, tag.year());
    assertEquals("-1", tag.properties().first("DATE"));
    assertArrayEquals(new byte[]{'-', '1', 0, 0}, tag.render().mid(93, 4).toByteArray());
    tag.setGenreNumber(256);
    assertEquals(255, tag.genreNumber());
    tag.setTrack(256);
    assertEquals(0, tag.track());
    tag.setTrack(255);
    assertEquals(255, tag.track());
  }

  @Test
  void renderTruncatesEncodedBytesAndRejectsTheWholeNonLatin1Field() {
    var tag = new Tag();
    tag.setTitle("a".repeat(31));
    tag.setArtist("b".repeat(30) + "日本語");
    tag.setComment("c".repeat(30));
    tag.setYear(123456);
    tag.setTrack(1);
    byte[] expected = footer();
    Arrays.fill(expected, 3, 33, (byte) 'a');
    Arrays.fill(expected, 97, 125, (byte) 'c');
    System.arraycopy(new byte[]{'1', '2', '3', '4'}, 0, expected, 93, 4);
    expected[126] = 1;
    assertArrayEquals(expected, tag.render().toByteArray());
    assertEquals("a".repeat(31), tag.title());
    assertEquals("c".repeat(30), tag.comment());
  }

  @Test
  void v10CommentBypassesHandlerWhileV11UsesItAndResetChangesRendering() {
    Tag.setStringHandler(
      new StringHandler() {
        @Override
        public String parse(ByteVector data) {
          return "[" + super.parse(data) + "]";
        }

        @Override
        public ByteVector render(String value) {
          return ByteVector.fromText("!" + value, UTF_8);
        }
      });
    byte[] source = footer();
    source[97] = ' ';
    source[98] = 'x';
    source[99] = ' ';
    var v10 = new Tag(new ByteVector(source));
    assertEquals("[]", v10.title());
    assertEquals(" x ", v10.comment());
    source[126] = 7;
    var v11 = new Tag(new ByteVector(source));
    assertEquals("[x]", v11.comment());
    assertEquals(7, v11.track());
    assertEquals("![x]", TextBytes.decodeTerminated(v11.render().mid(97, 28).data(), ISO_8859_1));
    Tag.setStringHandler(null);
    assertEquals("[x]", TextBytes.decodeTerminated(v11.render().mid(97, 28).data(), ISO_8859_1));
    assertEquals("[x]", v11.comment());
  }

  @Test
  void fileConstructorReadsAtOffsetAndInvalidReadsRetainDefaults() {
    byte[] input = new byte[135];
    System.arraycopy(footer(), 0, input, 7, 128);
    var memory = new ByteVectorStream(input);
    var file = new MemoryFile(memory);
    assertTrue(new Tag(file, 7).isEmpty());
    assertEquals(135, memory.tell());
    assertTrue(new Tag(file, 8).isEmpty());
    assertEquals(135, memory.tell());
    file.invalidate();
    memory.seek(4);
    assertTrue(new Tag(file, 7).isEmpty());
    assertEquals(4, memory.tell());
    assertTrue(new Tag((FileBase) null, 7).isEmpty());
    assertTrue(new Tag(new ByteVector(new byte[128])).isEmpty());
  }

  @Test
  void sharedPropertyImportKeepsNativeRejectedValuesAndNumericRules() {
    var tag = new Tag();
    var rejected =
      tag.replaceProperties(
        PropertyMap.builder()
          .put("TITLE", List.of("first", "second"))
          .put("DATE", "oops")
          .put("TRACKNUMBER", "-1")
          .put("CUSTOM", "value")
          .build()).rejected();
    assertEquals("first", tag.title());
    assertEquals(0, tag.year());
    assertEquals(0, tag.track());
    assertEquals(
      PropertyMap.builder()
        .put("TITLE", "second")
        .put("DATE", "oops")
        .put("CUSTOM", "value")
        .build(),
      rejected);
  }

  private static byte[] footer() {
    byte[] value = new byte[128];
    value[0] = 'T';
    value[1] = 'A';
    value[2] = 'G';
    value[127] = (byte) 255;
    return value;
  }

  private static final class MemoryFile extends FileBase {
    MemoryFile(IOStream stream) {
      super(stream);
    }

    void invalidate() {
      setValid(false);
    }

    @Override
    public TagBase tag() {
      return null;
    }

    @Override
    public AudioProperties audioProperties() {
      return null;
    }

    @Override
    public void save() {
      throw new cz.skyba.taglib.TagLibException("Test save rejected");
    }
  }
}
