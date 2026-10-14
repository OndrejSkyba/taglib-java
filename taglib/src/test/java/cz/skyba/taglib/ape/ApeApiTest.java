package cz.skyba.taglib.ape;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.Variant;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApeApiTest {
  @Test
  void malformedUtf16KeyFollowsNativeConvertedByteValidation() {
    assertTrue(Tag.checkKey("\ud800\ud800"));
    assertFalse(Tag.checkKey("\ud800"));
    assertFalse(Tag.checkKey("éé"));
    var tag = new Tag();
    tag.setItem("\ud800\ud800", new Item("physical", List.of("value")));
    assertEquals(1, tag.itemListMap().size());
  }

  @Test
  void renderingTextExposesPayloadAndTypeChangesRetainInactiveStorage() {
    var item = new Item("Title", List.of("one", "two"));
    assertTrue(item.binaryData().isEmpty());
    item.render();
    assertArrayEquals(
      new byte[]{'o', 'n', 'e', 0, 't', 'w', 'o'}, item.binaryData().toByteArray());
    item.setType(Item.ItemType.Binary);
    assertEquals(List.of("one", "two"), item.values());
    assertEquals("", item.toString());
    assertFalse(item.isEmpty());
    item.appendValue("three");
    assertTrue(item.binaryData().isEmpty());
    assertEquals(List.of("one", "two", "three"), item.values());
  }

  @Test
  void emptyItemStillCountsInFooterButDoesNotRenderAnItem() {
    var tag = new Tag();
    tag.setItem("EMPTY", new Item("EMPTY", List.of("")));
    assertFalse(tag.isEmpty());
    assertEquals(64, tag.render().size());
    assertEquals(1, tag.footer().itemCount());
    assertEquals(32, tag.footer().tagSize());
    assertTrue(tag.footer().headerPresent());
    assertEquals(0, tag.footer().version()); // rendering does not mutate parsed version
    assertEquals(2000, new Footer(tag.footer().renderFooter()).version());
  }

  @Test
  void mapKeyAndPhysicalItemKeyRemainIndependentAndCopied() {
    var tag = new Tag();
    var view = tag.itemListMap();
    var item = new Item("Physical", List.of("value"));
    tag.setItem("Logical", item);
    assertEquals(1, view.size());
    item.setValue("changed");
    assertEquals("Physical", tag.itemListMap().get("LOGICAL").key());
    assertEquals(List.of("value"), tag.properties().get("LOGICAL"));
    tag.itemListMap().get("LOGICAL").setValue("snapshot");
    assertEquals(List.of("value"), tag.properties().get("LOGICAL"));
    tag.removeItem("Logical");
    assertTrue(view.isEmpty());
  }

  @Test
  void propertyImportMergesAliasesRejectsInvalidAndRetainsBinary() {
    var tag = new Tag();
    tag.setData("BINARY", new ByteVector("opaque"));
    tag.setTitle("old");
    var rejected =
      tag.replaceProperties(
        PropertyMap.builder()
          .put("DATE", "2026")
          .put("YEAR", "2000")
          .put("TITLE", List.of("", "new", ""))
          .put("ID3", "invalid")
          .build()).rejected();
    assertEquals(List.of("invalid"), rejected.get("ID3"));
    assertEquals(List.of("2000", "2026"), tag.properties().get("DATE"));
    assertEquals("new", tag.title());
    assertEquals(List.of("BINARY"), tag.properties().unsupportedData());
  }

  @Test
  void pictureSelectionUsesLastExplicitFrontAndFirstBack() {
    var tag = new Tag();
    var pictures = new ArrayList<Map<String, Variant>>();
    for (String type : List.of("Other", "Front Cover", "Front Cover", "Back Cover", "Back Cover"))
      pictures.add(
        Map.of(
          "pictureType",
          new Variant(type),
          "data",
          new Variant(new ByteVector((byte) (pictures.size() + 1)))));
    assertTrue(tag.setComplexProperties("picture", pictures));
    var result = tag.complexProperties("PICTURE");
    assertArrayEquals(new byte[]{3}, result.get(0).get("data").toByteVector().toByteArray());
    assertArrayEquals(new byte[]{4}, result.get(1).get("data").toByteVector().toByteArray());
    assertFalse(result.get(0).containsKey("description"));
    assertFalse(tag.setComplexProperties("OTHER", List.of()));
  }

  @Test
  void footerParsingDoesNotValidateIdentifierAndAlwaysRendersVersionTwo() {
    var bytes =
      new ByteVector("NOTMAGIC")
        .append(ByteVector.fromUnsignedInt(1000, false))
        .append(ByteVector.fromUnsignedInt(0xfffffff0L, false))
        .append(ByteVector.fromUnsignedInt(5, false))
        .append(ByteVector.fromUnsignedInt(0xffffffffL, false))
        .append(new ByteVector(8));
    var footer = new Footer(bytes);
    assertEquals(1000, footer.version());
    assertTrue(footer.headerPresent());
    assertFalse(footer.footerPresent());
    assertTrue(footer.isHeader());
    assertEquals(16, footer.completeTagSize());
    var rendered = new Footer(footer.renderFooter());
    assertEquals(2000, rendered.version());
    assertTrue(rendered.footerPresent());
    assertFalse(rendered.isHeader());
    footer.setData(new ByteVector("short"));
    assertEquals(1000, footer.version());
  }
}
