package cz.skyba.taglib.ogg;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.flac.Picture;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.Variant;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct comment assertions; Ogg codec-owner persistence is compared in the parity module.
 */
class XiphCommentTest {
  @Test
  void testYear() {
    var c = new XiphComment();
    assertEquals(0, c.year());
    c.addField("YEAR", "2009");
    assertEquals(2009, c.year());
    c.addField("DATE", "2008");
    assertEquals(2008, c.year());
  }

  @Test
  void testSetYear() {
    var c = new XiphComment();
    c.addField("YEAR", "2009");
    c.addField("DATE", "2008");
    c.setYear(1995);
    assertFalse(c.fieldListMap().containsKey("YEAR"));
    assertEquals("1995", c.fieldListMap().get("DATE").getFirst());
  }

  @Test
  void testTrack() {
    var c = new XiphComment();
    assertEquals(0, c.track());
    c.addField("TRACKNUM", "7");
    assertEquals(7, c.track());
    c.addField("TRACKNUMBER", "8");
    assertEquals(8, c.track());
  }

  @Test
  void testSetTrack() {
    var c = new XiphComment();
    c.addField("TRACKNUM", "7");
    c.addField("TRACKNUMBER", "8");
    c.setTrack(3);
    assertFalse(c.fieldListMap().containsKey("TRACKNUM"));
    assertEquals("3", c.fieldListMap().get("TRACKNUMBER").getFirst());
  }

  @Test
  void testInvalidKeys1() {
    var b = PropertyMap.builder();
    for (String k : List.of("", "A=B", "A~B", "A\u007fB", "A\u3456B")) b.put(k, "invalid");
    var c = new XiphComment();
    assertEquals(5, c.replaceProperties(b.build()).rejected().asMap().size());
    assertTrue(c.properties().isEmpty());
  }

  @Test
  void testInvalidKeys2() {
    var c = new XiphComment();
    for (String k : List.of("", "A=B", "A~B", "A\u007fB", "A\u3456B")) c.addField(k, "invalid");
    assertEquals(0, c.fieldCount());
  }

  @Test
  void clearCommentAndRememberSelectedDescriptionField() {
    var c = new XiphComment();
    c.addField("COMMENT", "Comment1");
    var reopened = new XiphComment(c.render());
    reopened.setComment("");
    assertEquals("", reopened.comment());
    c.addField("DESCRIPTION", "description");
    assertEquals("description", c.comment());
    c.removeFields("DESCRIPTION");
    c.setComment("sticky");
    assertEquals(List.of("sticky"), c.fieldListMap().get("DESCRIPTION"));
  }

  @Test
  void removeFieldsRetainsVendorAndDuplicates() {
    var c = new XiphComment(new ByteVector().append(ByteVector.fromUnsignedInt(6, false)).append(new ByteVector("vendor")).append(ByteVector.fromUnsignedInt(0, false)));
    c.addField("title", "Title1");
    c.addField("Title", "Title1", false);
    c.addField("titlE", "Title2", false);
    c.addField("TITLE", "Title3", false);
    c.addField("artist", "Artist1");
    c.addField("ARTIST", "Artist2", false);
    assertEquals("Title1 / Title1 / Title2 / Title3", c.title());
    assertEquals("Artist1 / Artist2", c.artist());
    c.removeFields("title", "Title1");
    assertEquals("Title2 / Title3", c.title());
    assertEquals("Artist1 / Artist2", c.artist());
    c.removeFields("Artist");
    assertEquals("Title2 / Title3", c.title());
    assertTrue(c.artist().isEmpty());
    c.removeAllFields();
    assertTrue(c.title().isEmpty());
    assertTrue(c.artist().isEmpty());
    assertEquals("vendor", c.vendorID());
  }

  @Test
  void pictureRoundtripRetainsEveryFieldAndPicturesDoNotMakeTagNonempty() {
    var c = new XiphComment();
    var p = new Picture();
    p.setType(Picture.Type.BackCover);
    p.setWidth(5);
    p.setHeight(6);
    p.setColorDepth(16);
    p.setNumColors(7);
    p.setMimeType("image/jpeg");
    p.setDescription("new image");
    p.setData(new ByteVector("JPEG data").toByteArray());
    c.addPicture(p);
    assertTrue(c.isEmpty());
    assertEquals(1, c.fieldCount());
    var q = new XiphComment(c.render()).pictureList().getFirst();
    assertEquals(Picture.Type.BackCover, q.type());
    assertEquals(5, q.width());
    assertEquals(6, q.height());
    assertEquals(16, q.colorDepth());
    assertEquals(7, q.numColors());
    assertEquals("image/jpeg", q.mimeType());
    assertEquals("new image", q.description());
    assertArrayEquals(p.data(), q.data());
    assertSame(p, c.pictureList().getFirst());
    c.removePicture(p);
    assertTrue(c.pictureList().isEmpty());
  }

  @Test
  void legacyCoverArtRetainsBinaryDataAndDefaultPictureFields() {
    byte[] image = {0, 1, (byte) 255, 0, 2};
    var comment = new XiphComment();
    comment.addField("COVERART", new ByteVector(image).toBase64().toString());
    var parsed = new XiphComment(comment.render());
    assertEquals(1, parsed.pictureList().size());
    var picture = parsed.pictureList().getFirst();
    assertArrayEquals(image, picture.data());
    assertEquals("image/", picture.mimeType());
    assertEquals(Picture.Type.Other, picture.type());
    assertArrayEquals(image, new XiphComment(parsed.render()).pictureList().getFirst().data());
  }

  @Test
  void fieldMapIsLiveAndMissingLookupDoesNotInsertAnEntry() {
    var c = new XiphComment();
    var map = c.fieldListMap();
    var before = c.render();
    assertNull(map.get("ABSENT"));
    assertEquals(List.of("fallback"), map.getOrDefault("ABSENT", List.of("fallback")));
    assertTrue(map.isEmpty());
    assertTrue(c.properties().isEmpty());
    assertEquals(before, c.render());
    assertEquals(0, c.fieldCount());
    assertTrue(c.isEmpty());
    c.addField("title", "first");
    assertEquals(List.of("first"), map.get("TITLE"));
    var values = map.get("TITLE");
    c.addField("title", "second", false);
    assertEquals(List.of("first", "second"), values);
    assertThrows(UnsupportedOperationException.class, () -> map.put("X", List.of("x")));
    assertThrows(UnsupportedOperationException.class, () -> map.get("TITLE").add("x"));
  }

  @Test
  void parseRetainsPriorFieldsAndBoundedPartialUpdates() {
    class Exposed extends XiphComment {
      void read(ByteVector b) {
        parse(b);
      }
    }
    var c = new Exposed();
    c.setTitle("retained");
    c.read(ByteVector.fromUnsignedInt(3, false).append(new ByteVector("new")).append(ByteVector.fromUnsignedInt(50001, false)));
    assertEquals("new", c.vendorID());
    assertEquals("retained", c.title());
  }

  @Test
  void complexPicturesUseExactVariantTypes() {
    var c = new XiphComment();
    assertFalse(c.setComplexProperties("other", List.of()));
    assertTrue(c.setComplexProperties("picture", List.of(Map.of("data", new Variant(new ByteVector("bytes")), "width", new Variant("wrong")))));
    assertEquals(0, c.pictureList().getFirst().width());
    assertEquals(new ByteVector("bytes"), c.complexProperties("PICTURE").getFirst().get("data").toByteVector());
  }
}
