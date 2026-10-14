package cz.skyba.taglib.mp4;

import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.UpstreamAudioFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Mp4JavaValuesTest {
  @TempDir
  Path directory;

  @Test
  void textAndPropertyValuesAreDetachedFromCallerLists() {
    var source = new ArrayList<>(List.of("first", "žluťoučký", ""));
    var item = Item.textValues(source);
    var property = new ItemFactory.PropertyValue("TITLE", source);
    source.clear();
    item.toStringList().clear();
    property.values().clear();

    assertEquals(List.of("first", "žluťoučký", ""), item.toStringList());
    assertEquals(item.toStringList(), property.values());
    assertEquals(item, new Item(item));
    assertEquals(item.hashCode(), new Item(item).hashCode());
    assertThrows(NullPointerException.class, () -> Item.textValues(Arrays.asList("first", null)));

    var factory = ItemFactory.instance();
    var mapped = factory.itemFromProperty("TITLE", item.toStringList());
    assertEquals(item, mapped.item());
    assertEquals(property, factory.itemToProperty(mapped.name(), mapped.item()));
    assertEquals(new Item.IntPair(2, 10), factory.itemFromProperty("TRACKNUMBER", List.of("2/10")).item().toIntPair());
    assertEquals(0, Item.textValues(List.of()).toInt());
    assertTrue(new Item(1).toStringList().isEmpty());
  }

  @Test
  void propertyAndFreeFormDelimitersRetainEmptyFields() {
    var factory = ItemFactory.instance();
    assertEquals(new Item.IntPair(2, 0), factory.itemFromProperty("TRACKNUMBER", List.of("2/")).item().toIntPair());
    assertEquals(new Item.IntPair(0, 10), factory.itemFromProperty("TRACKNUMBER", List.of("/10")).item().toIntPair());
    assertEquals(new Item.IntPair(2, 0), factory.itemFromProperty("TRACKNUMBER", List.of("2//10")).item().toIntPair());
    var text = Item.textValues(List.of("value"));
    assertFalse(factory.renderItem("----:org.example:", text).isEmpty());
    assertFalse(factory.renderItem("----::field", text).isEmpty());
    assertTrue(factory.renderItem("----:org.example:field:extra", text).isEmpty());
  }

  @Test
  void binaryAndArtworkValuesCopyInputAndOutputArrays() {
    byte[] bytes = {0, 1, (byte) 255};
    var source = new ArrayList<>(List.of(bytes));
    var item = Item.binaryValues(source);
    var picture = new CoverArt(CoverArt.Format.PNG, bytes);
    var covers = new ArrayList<>(List.of(picture));
    var artwork = Item.coverArt(covers);
    bytes[0] = 42;
    source.clear();
    covers.clear();
    var exported = item.toByteArrays();
    exported.getFirst()[1] = 42;
    exported.clear();
    picture.toByteArray()[1] = 42;
    artwork.toCoverArtList().clear();

    byte[] expected = {0, 1, (byte) 255};
    assertArrayEquals(expected, item.toByteArrays().getFirst());
    assertArrayEquals(expected, picture.toByteArray());
    assertEquals(List.of(picture), artwork.toCoverArtList());
    assertEquals(item, Item.binaryValues(List.of(expected)));
    assertEquals(item.hashCode(), Item.binaryValues(List.of(expected)).hashCode());
    assertThrows(NullPointerException.class, () -> Item.binaryValues(Arrays.asList(expected, null)));
    assertThrows(NullPointerException.class, () -> Item.coverArt(Arrays.asList(picture, null)));
    assertTrue(new Item(1).toByteArrays().isEmpty());
    assertTrue(new Item(1).toCoverArtList().isEmpty());
  }

  @Test
  void standardJavaValuesSurviveSavingAndReopening() throws Exception {
    Path path = UpstreamAudioFixtures.copy(directory, "no-tags.m4a");
    var text = List.of("first", "žluťoučký 🎵");
    byte[] binary = {0, 1, (byte) 255, 0};
    byte[] image = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    String binaryKey = "----:org.example:binary";
    try (var file = new Mp4File(path, ReadOptions.TAGS_ONLY)) {
      file.tag().setItem("©nam", Item.textValues(text));
      file.tag().setItem(binaryKey, Item.binaryValues(List.of(binary, new byte[0])));
      file.tag().setItem("covr", Item.coverArt(List.of(new CoverArt(CoverArt.Format.PNG, image))));
      file.save();
    }
    try (var file = new Mp4File(path, ReadOptions.TAGS_ONLY)) {
      assertEquals(text, file.tag().item("©nam").toStringList());
      assertEquals(text, file.properties().get("TITLE"));
      var saved = file.tag().item(binaryKey).toByteArrays();
      assertEquals(2, saved.size());
      assertArrayEquals(binary, saved.getFirst());
      assertArrayEquals(new byte[0], saved.get(1));
      var covers = file.tag().item("covr").toCoverArtList();
      assertEquals(1, covers.size());
      assertEquals(CoverArt.Format.PNG, covers.getFirst().format());
      assertArrayEquals(image, covers.getFirst().toByteArray());
    }
  }
}
