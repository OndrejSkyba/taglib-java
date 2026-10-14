package cz.skyba.taglib;

import cz.skyba.taglib.toolkit.Variant;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class CommonTagPropertiesAndDuplicationTest {
  @Test
  void importsIgnoreOpaqueIdentifiersWhilePreservingUnconsumedTextValues() {
    var input = PropertyMap.builder()
      .put("TITLE", List.of("first", "second"))
      .put("EMPTY", List.of())
      .put("PRIVATE", "retained")
      .put("", "invalid key")
      .addUnsupportedData("opaque/read-marker")
      .build();
    for (TagBase tag : List.of(new MemoryTag(), new cz.skyba.taglib.mod.Tag(),
      new cz.skyba.taglib.dsdiff.diin.Tag())) {
      var remainder = tag.replaceProperties(input).rejected();
      assertEquals("first", tag.title());
      assertEquals(Map.of("TITLE", List.of("second"), "PRIVATE", List.of("retained"),
        "", List.of("invalid key")), remainder.asMap());
      assertTrue(remainder.unsupportedData().isEmpty());
    }
    assertTrue(input.containsKey("EMPTY"));
    assertEquals(List.of("opaque/read-marker"), input.unsupportedData());
  }

  @Test
  void propertyImportConsumesOnlyOneValueAndKeepsInvalidNumbers() {
    var tag = new MemoryTag();
    tag.setArtist("old");
    tag.setTrack(8);
    var remainder =
      tag.replaceProperties(
        PropertyMap.builder()
          .put("TITLE", List.of("first", "second"))
          .put("DATE", "2026-10")
          .put("TRACKNUMBER", "-2")
          .put("PRIVATE", "value")
          .put("EMPTY", List.of())
          .addUnsupportedData("opaque")
          .build()).rejected();
    assertEquals("first", tag.title());
    assertEquals("", tag.artist());
    assertEquals(0, tag.year());
    assertEquals(0xffff_fffeL, tag.track());
    assertEquals(
      Map.of("TITLE", List.of("second"), "DATE", List.of("2026-10"), "PRIVATE", List.of("value")),
      remainder.asMap());
    assertTrue(remainder.unsupportedData().isEmpty());
    assertEquals("-2", tag.properties().first("TRACKNUMBER"));
  }

  @Test
  void propertyExportPreservesNativeVirtualGetterCallOrder() {
    var calls = new ArrayList<String>();
    var tag = new MemoryTag() {
      int titleReads, yearReads;

      @Override
      public String title() {
        calls.add("title");
        return ++titleReads == 1 ? "present" : "exported";
      }

      @Override
      public long year() {
        calls.add("year");
        return ++yearReads == 1 ? 1 : 0xffff_ffffL;
      }
    };
    var properties = tag.properties();
    assertEquals("exported", properties.first("TITLE"));
    assertEquals("-1", properties.first("DATE"));
    assertEquals(List.of("title", "title", "year", "year"), calls);
  }

  @Test
  void duplicateHonorsOverwriteAndMissingFields() {
    var source = new MemoryTag();
    source.setTitle("new");
    source.setArtist("artist");
    source.setTrack(7);
    var target = new MemoryTag();
    target.setTitle("kept");
    target.setYear(2026);
    TagBase.copyFields(source, target, false);
    assertEquals("kept", target.title());
    assertEquals("artist", target.artist());
    assertEquals(2026, target.year());
    assertEquals(7, target.track());
    TagBase.copyFields(source, target);
    assertEquals(source.properties(), target.properties());
  }

  @Test
  void unionCommonFallbackDiffersFromPropertyMapAndMutationsReachEveryChild() {
    var first = new MemoryTag();
    first.setTitle("first");
    var second = new MemoryTag();
    second.setArtist("second");
    second.setYear(2026);
    var third = new MemoryTag();
    third.setTrack(4);
    var union = new TagUnion(first, second, third);
    assertEquals("second", union.artist());
    assertEquals(2026, union.year());
    assertEquals(4, union.track());
    assertEquals(first.properties(), union.properties());
    union.replaceProperties(PropertyMap.builder().put("TITLE", "all").put("DATE", "2025").build());
    for (var tag : List.of(first, second, third)) {
      assertEquals("all", tag.title());
      assertEquals(2025, tag.year());
      assertEquals(0, tag.track());
      assertEquals("", tag.artist());
    }
    assertFalse(union.setComplexProperties("PICTURE", List.of()));
    union.set(0, null);
    union.set(1, null);
    union.set(2, null);
    assertTrue(union.isEmpty());
    assertTrue(union.properties().isEmpty());
    assertEquals("", union.title());
  }

  @Test
  void complexPropertyUnionUsesFirstNonemptyAndBroadcastDoesNotShortCircuit() {
    var first = new ComplexTag(false);
    var second = new ComplexTag(true);
    var third = new ComplexTag(false);
    var union = new TagUnion(first, second, third);
    assertEquals(List.of("CUSTOM"), union.complexPropertyKeys());
    assertEquals(List.of(Map.of("data", new Variant("x"))), union.complexProperties("CUSTOM"));
    assertTrue(union.setComplexProperties("CUSTOM", List.of()));
    assertEquals(1, first.calls);
    assertEquals(1, second.calls);
    assertEquals(1, third.calls);
  }

  static class MemoryTag extends TagBase {
    String title = "", artist = "", album = "", comment = "", genre = "";
    long year, track;

    public String title() {
      return title;
    }

    public String artist() {
      return artist;
    }

    public String album() {
      return album;
    }

    public String comment() {
      return comment;
    }

    public String genre() {
      return genre;
    }

    public long year() {
      return year;
    }

    public long track() {
      return track;
    }

    public void setTitle(String value) {
      title = value;
    }

    public void setArtist(String value) {
      artist = value;
    }

    public void setAlbum(String value) {
      album = value;
    }

    public void setComment(String value) {
      comment = value;
    }

    public void setGenre(String value) {
      genre = value;
    }

    public void setYear(long value) {
      year = value & 0xffff_ffffL;
    }

    public void setTrack(long value) {
      track = value & 0xffff_ffffL;
    }
  }

  static final class ComplexTag extends MemoryTag {
    final boolean accepts;
    int calls;

    ComplexTag(boolean accepts) {
      this.accepts = accepts;
    }

    @Override
    public List<String> complexPropertyKeys() {
      return accepts ? List.of("CUSTOM") : List.of();
    }

    @Override
    public List<Map<String, Variant>> complexProperties(String key) {
      return accepts ? List.of(Map.of("data", new Variant("x"))) : List.of();
    }

    @Override
    public boolean setComplexProperties(String key, List<Map<String, Variant>> values) {
      calls++;
      return accepts;
    }
  }
}
