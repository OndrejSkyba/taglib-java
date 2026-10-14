package cz.skyba.taglib.dsdiff.diin;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.TagBase;
import cz.skyba.taglib.TagUnion;
import cz.skyba.taglib.toolkit.MutablePropertyMap;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiinTagTest {
  @Test
  void emptyExportContainsBothSingleEmptyValues() {
    var tag = new Tag();
    assertTrue(tag.isEmpty());
    assertEquals(PropertyMap.builder().put("TITLE", "").put("ARTIST", "").build(), tag.properties());
    assertTrue(new TagUnion(tag, null, null).properties().isEmpty());
  }

  @Test
  void unsupportedCommonFieldsNeverBecomeStoredProperties() {
    TagBase tag = new Tag();
    tag.setTitle("title");
    tag.setArtist("artist");
    tag.setAlbum("album");
    tag.setComment("comment");
    tag.setGenre("genre");
    tag.setYear(0xffff_ffffL);
    tag.setTrack(0x8000_0000L);
    assertEquals("title", tag.title());
    assertEquals("artist", tag.artist());
    assertEquals("", tag.album());
    assertEquals("", tag.comment());
    assertEquals("", tag.genre());
    assertEquals(0, tag.year());
    assertEquals(0, tag.track());
    assertEquals(2, tag.properties().size());
  }

  @Test
  void propertyImportConsumesOneValueAndPreservesRejectedOrderClearingUnsupportedData() {
    var input = PropertyMap.builder().put("TITLE", List.of("", "next", "last"))
      .put("ARTIST", List.of("first", "second")).put("ALBUM", List.of("a", "b"))
      .put("EMPTY", List.of()).build();
    var mutable = new MutablePropertyMap(input);
    mutable.addUnsupportedData("opaque");
    mutable.addUnsupportedData("opaque");
    input = mutable.snapshot();
    var tag = new Tag();
    var rejected = tag.replaceProperties(input).rejected();
    assertEquals("", tag.title());
    assertEquals("first", tag.artist());
    assertEquals(List.of("next", "last"), rejected.get("TITLE"));
    assertEquals(List.of("second"), rejected.get("ARTIST"));
    assertEquals(List.of("a", "b"), rejected.get("ALBUM"));
    assertFalse(rejected.containsKey("EMPTY"));
    assertTrue(rejected.unsupportedData().isEmpty());
    assertEquals(List.of("opaque", "opaque"), input.unsupportedData());
    assertEquals(List.of("", "next", "last"), input.get("TITLE"));
  }

  @Test
  void absentAndZeroValuePropertiesClearStoredFields() {
    var tag = new Tag();
    tag.setTitle("old");
    tag.setArtist("old artist");
    var rejected = tag.replaceProperties(PropertyMap.builder().put("TITLE", List.of()).put("ALBUM", "a").build()).rejected();
    assertTrue(tag.isEmpty());
    assertEquals(PropertyMap.builder().put("ALBUM", "a").build(), rejected);
    tag.setTitle("new");
    tag.replaceProperties(PropertyMap.EMPTY);
    assertTrue(tag.isEmpty());
  }

  @Test
  void storedUtf16PreservesNulsSurrogatesAndSnapshotOwnership() {
    var tag = new Tag();
    String title = "a\0b\ud800\udfff", artist = "日本語\ud83c\udfb5";
    tag.setTitle(title);
    tag.setArtist(artist);
    var snapshot = tag.properties();
    tag.setTitle("later");
    assertEquals(title, snapshot.first("TITLE"));
    assertEquals(artist, tag.artist());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.get("TITLE").add("changed"));
  }

  @Test
  void nativePropertyImportExportBypassesSubclassAccessors() {
    class Custom extends Tag {
      int setterCalls;

      @Override
      public String title() {
        return "custom getter";
      }

      @Override
      public void setTitle(String value) {
        setterCalls++;
        super.setTitle("custom:" + value);
      }
    }
    var tag = new Custom();
    tag.setTitle("direct");
    assertEquals(1, tag.setterCalls);
    assertEquals("custom:direct", tag.properties().first("TITLE"));
    tag.replaceProperties(PropertyMap.builder().put("TITLE", "imported").build());
    assertEquals(1, tag.setterCalls);
    assertEquals("custom getter", tag.title());
    assertEquals("imported", tag.properties().first("TITLE"));
  }

  @Test
  void nativeCommonDuplicateAndUnionUseVirtualAccessorsAndExistingChildren() {
    var source = new Tag();
    source.setTitle("source");
    source.setArtist("artist");
    var target = new Tag();
    target.setTitle("kept");
    TagBase.copyFields(source, target, false);
    assertEquals("kept", target.title());
    assertEquals("artist", target.artist());
    TagBase.copyFields(source, target);
    assertEquals("source", target.title());
    var first = new Tag();
    var union = new TagUnion(first, target, null);
    assertEquals("source", union.title());
    union.setTitle("broadcast");
    assertEquals("broadcast", first.title());
    assertEquals("broadcast", target.title());
  }

  @Test
  void unsupportedAndComplexRemovalLeaveTitleArtistUnchanged() {
    var tag = new Tag();
    tag.setTitle("title");
    tag.setArtist("artist");
    var before = tag.properties();
    tag.removeUnsupportedProperties(List.of("TITLE", "ARTIST", "opaque"));
    assertTrue(tag.complexPropertyKeys().isEmpty());
    assertTrue(tag.complexProperties("PICTURE").isEmpty());
    assertFalse(tag.setComplexProperties("PICTURE", List.of()));
    assertEquals(before, tag.properties());
  }
}
