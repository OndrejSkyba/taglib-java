package cz.skyba.taglib.riff.info;

import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.TagString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RiffInfoFieldsEncodingAndPropertiesTest {
  @AfterEach
  void reset() {
    Tag.setStringHandler(null);
  }

  static class Access extends Tag {
    Access() {
      super();
    }

    void apply(ByteVector d) {
      parse(d);
    }
  }

  static ByteVector b(String s) {
    return new ByteVector(s);
  }

  static ByteVector field(String id, String value) {
    return new ByteVector(InfoTagTest.field(id, new TagString(value).data(TagString.Type.UTF8).append((byte) 0).data()));
  }

  @Test
  void partialDeclaredLengthIsClampedWithoutAllocation() {
    var t = new Tag(b("INFOINAM").append(new ByteVector(new byte[]{-1, -1, -1})));
    assertEquals(Map.of(b("INAM"), ""), t.fieldListMap());
    assertEquals(List.of(""), t.properties().get("TITLE"));
    assertTrue(t.render().isEmpty());
  }

  @Test
  void protectedParseMergesFieldsAndShortTailOverwritesAStoredValue() {
    var t = new Access();
    t.setTitle("old");
    t.setArtist("retained");
    t.apply(b("NOPE").append(field("INAM", "first")).append(field("ZZZZ", "opaque")).append(b("INAM")));
    assertEquals("", t.title());
    assertEquals("retained", t.artist());
    assertEquals("opaque", t.fieldText(b("ZZZZ")));
    assertEquals(3, t.fieldListMap().size());
    assertEquals(List.of(""), t.properties().get("TITLE"));
  }

  @Test
  void parseCallbackFailureRetainsPriorAndReachedAssignments() {
    var t = new Access();
    t.setTitle("old-title");
    t.setArtist("old-artist");
    t.setAlbum("old-album");
    int[] calls = {0};
    Tag.setStringHandler(new StringHandler() {
      public String parse(ByteVector d) {
        if (++calls[0] == 2) throw new IllegalArgumentException("callback");
        return super.parse(d);
      }
    });
    assertThrows(IllegalArgumentException.class, () -> t.apply(b("INFO").append(field("IART", "new-artist")).append(field("IPRD", "new-album")).append(field("INAM", "new-title"))));
    assertEquals("new-artist", t.artist());
    assertEquals("old-album", t.album());
    assertEquals("old-title", t.title());
    assertEquals(2, calls[0]);
  }

  @Test
  void handlerReplacementAndDetachedInputApplyDuringParsing() {
    int[] calls = {0, 0};
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
    var raw = b("INFO").append(field("IART", "artist")).append(field("INAM", "title"));
    var copy = new ByteVector(raw);
    var t = new Tag(raw);
    assertEquals("first", t.artist());
    assertEquals("replacement", t.title());
    assertArrayEquals(new int[]{1, 1}, calls);
    assertEquals(copy, raw);
  }

  @Test
  void renderUsesOrderedFieldsAndReplacementHandlerWithoutMutatingValues() {
    int[] calls = {0, 0};
    var shared = b("A");
    var replacement = new StringHandler() {
      public ByteVector render(String v) {
        calls[1]++;
        return b("B");
      }
    };
    Tag.setStringHandler(new StringHandler() {
      public ByteVector render(String v) {
        calls[0]++;
        Tag.setStringHandler(replacement);
        return shared;
      }
    });
    var t = new Tag();
    t.setTitle("title");
    t.setArtist("artist");
    assertEquals(b("INFO").append(field("IART", "A")).append(field("INAM", "B")), t.render());
    assertEquals(b("A"), shared);
    assertEquals("title", t.title());
    assertArrayEquals(new int[]{1, 1}, calls);
  }

  @Test
  void commonHooksRemainVirtualWhileStoredFieldProjectionAndRenderAreNonvirtual() {
    class Derived extends Tag {
      int calls;

      public void setTitle(String s) {
        calls++;
        super.setTitle(s);
      }

      public String title() {
        return "virtual";
      }
    }
    var t = new Derived();
    t.setTitle("stored");
    assertEquals(1, t.calls);
    assertEquals("virtual", t.title());
    assertEquals("stored", t.properties().first("TITLE"));
    assertEquals(b("INFO").append(field("INAM", "stored")), t.render());
  }

  @Test
  void finalNativeHelpersAreNotAdvertisedAsOverrideHooks() {
    for (var m : Tag.class.getDeclaredMethods())
      if (Set.of("fieldListMap", "fieldText", "setFieldText", "removeField", "render", "parse").contains(m.getName()))
        assertTrue(Modifier.isFinal(m.getModifiers()), m.toString());
  }
}
