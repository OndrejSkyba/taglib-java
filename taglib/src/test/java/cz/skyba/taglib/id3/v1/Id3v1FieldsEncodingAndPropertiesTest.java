package cz.skyba.taglib.id3.v1;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.TagBase;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class Id3v1FieldsEncodingAndPropertiesTest {
  @AfterEach
  void reset() {
    Tag.setStringHandler(null);
  }

  static class Access extends Tag {
    Access() {
      super();
    }

    Access(FileBase f) {
      super(f, 7);
    }

    void apply(ByteVector d) {
      parse(d);
    }

    void reread() {
      read();
    }
  }

  static ByteVector footer(int track) {
    var d = new ByteVector("TAG");
    for (String s : List.of("title", "artist", "album")) d.append(new ByteVector(s).resize(30));
    return d.append(new ByteVector("2026")).append(new ByteVector(" comment ").resize(28)).append((byte) 0).append((byte) track).append((byte) 11);
  }

  static class Bare extends FileBase {
    Bare(ByteVectorStream s) {
      super(s);
    }

    public TagBase tag() {
      return null;
    }

    public AudioProperties audioProperties() {
      return null;
    }

    public void save() {
      throw new cz.skyba.taglib.TagLibException("Test save rejected");
    }
  }

  @Test
  void reparsingVersion10RetainsTrackAndBypassesCommentHandler() {
    var t = new Access();
    t.apply(footer(7));
    Tag.setStringHandler(new StringHandler() {
      public String parse(ByteVector d) {
        return "callback";
      }
    });
    t.apply(footer(0));
    assertEquals(7, t.track());
    assertEquals("callback", t.title());
    assertEquals(" comment ", t.comment());
    assertEquals(11, t.genreNumber());
  }

  @Test
  void protectedParseDoesNotRequireSignatureButConstructorDoes() {
    var raw = footer(7);
    raw.set(0, (byte) 'X');
    var t = new Access();
    t.apply(raw);
    assertEquals("title", t.title());
    assertEquals(7, t.track());
    assertTrue(new Tag(raw).isEmpty());
  }

  @Test
  void shortProtectedInputsRejectBeforeCallbacksOrMutation() {
    var t = new Access();
    t.apply(footer(7));
    var original = t.render();
    var calls = new int[1];
    Tag.setStringHandler(new StringHandler() {
      public String parse(ByteVector d) {
        calls[0]++;
        return "callback";
      }
    });
    for (int n = 0; n < 128; n++) {
      int size = n;
      assertThrows(IllegalArgumentException.class, () -> t.apply(footer(0).mid(0, size)));
      assertEquals(original, t.render());
    }
    assertEquals(0, calls[0]);
    assertEquals(7, t.track());
  }

  @Test
  void failedReadKeepsDecodedState() {
    var stream = new ByteVectorStream(new ByteVector(7).append(footer(7)));
    var t = new Access(new Bare(stream));
    stream.data();
    stream.seek(7);
    stream.writeBlock(new ByteVector("XXX"));
    t.reread();
    assertEquals("title", t.title());
    assertEquals(7, t.track());
    assertEquals(135, stream.tell());
    assertTrue(stream.isOpen());
  }

  @Test
  void eachThrowingCallbackKeepsOnlyReachedFieldAssignments() {
    for (int fail = 1; fail <= 5; fail++) {
      var t = new Access();
      t.setTitle("old-title");
      t.setArtist("old-artist");
      t.setAlbum("old-album");
      t.setYear(2020);
      t.setComment("old-comment");
      t.setTrack(55);
      t.setGenreNumber(222);
      int at = fail;
      int[] calls = {0};
      Tag.setStringHandler(new StringHandler() {
        public String parse(ByteVector d) {
          if (++calls[0] == at) throw new IllegalArgumentException("callback");
          return super.parse(d);
        }
      });
      assertThrows(IllegalArgumentException.class, () -> t.apply(footer(7)));
      assertEquals(fail, calls[0]);
      assertEquals(fail > 1 ? "title" : "old-title", t.title());
      assertEquals(fail > 2 ? "artist" : "old-artist", t.artist());
      assertEquals(fail > 3 ? "album" : "old-album", t.album());
      assertEquals(fail > 4 ? 2026 : 2020, t.year());
      assertEquals("old-comment", t.comment());
      assertEquals(55, t.track());
      assertEquals(222, t.genreNumber());
    }
  }

  @Test
  void parseHandlerReplacementAppliesToSubsequentFieldsAndInputIsDetached() {
    var calls = new int[2];
    var replacement = new StringHandler() {
      public String parse(ByteVector d) {
        calls[1]++;
        return "replacement";
      }
    };
    Tag.setStringHandler(new StringHandler() {
      public String parse(ByteVector d) {
        calls[0]++;
        d.clear();
        Tag.setStringHandler(replacement);
        return "first";
      }
    });
    var raw = footer(7);
    var copy = new ByteVector(raw);
    var t = new Access();
    t.apply(raw);
    assertEquals("first", t.title());
    assertEquals("replacement", t.artist());
    assertEquals("replacement", t.comment());
    assertArrayEquals(new int[]{1, 4}, calls);
    assertEquals(copy, raw);
  }

  @Test
  void renderUsesCurrentHandlerForEachStoredFieldAndDoesNotResizeItsValue() {
    var calls = new int[2];
    var shared = new ByteVector(40, (byte) 'A');
    var replacement = new StringHandler() {
      public ByteVector render(String v) {
        calls[1]++;
        return new ByteVector("B");
      }
    };
    Tag.setStringHandler(new StringHandler() {
      public ByteVector render(String v) {
        calls[0]++;
        Tag.setStringHandler(replacement);
        return shared;
      }
    });
    var tag = new Tag();
    tag.setTitle("unchanged");
    var rendered = tag.render();
    assertEquals(new ByteVector(30, (byte) 'A'), rendered.mid(3, 30));
    assertEquals((byte) 'B', rendered.at(33));
    assertEquals(40, shared.size());
    assertEquals("unchanged", tag.title());
    assertArrayEquals(new int[]{1, 4}, calls);
    assertEquals(128, rendered.size());
  }

  @Test
  void nonvirtualNativeOperationsAreFinalWhileCommonAndHandlerHooksRemainVirtual() throws Exception {
    for (var m : Tag.class.getDeclaredMethods())
      if (Set.of("render", "read", "parse", "genreNumber", "setGenreNumber").contains(m.getName()))
        assertTrue(Modifier.isFinal(m.getModifiers()), m.toString());
    assertFalse(Modifier.isFinal(Tag.class.getMethod("title").getModifiers()));
    assertFalse(Modifier.isFinal(StringHandler.class.getMethod("parse", ByteVector.class).getModifiers()));
  }
}
