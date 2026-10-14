package cz.skyba.taglib.ape;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.TagBase;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.IOStream;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApeTagItemsAndPropertyValuesTest {
  @Test
  void testIsEmpty() {
    var t = new Tag();
    assertTrue(t.isEmpty());
    t.addValue("COMPOSER", "Mike Oldfield");
    assertFalse(t.isEmpty());
  }

  @Test
  void testIsEmpty2() {
    var t = new Tag();
    assertTrue(t.isEmpty());
    t.setArtist("Mike Oldfield");
    assertFalse(t.isEmpty());
  }

  @Test
  void testPropertyInterface1() {
    var t = new Tag();
    assertTrue(t.properties().isEmpty());
    t.replaceProperties(PropertyMap.builder().put("ARTIST", List.of("artist 1", "artist 2")).put("TRACKNUMBER", "17").build());
    assertEquals("17", t.itemListMap().get("TRACK").values().getFirst());
    assertEquals(2, t.itemListMap().get("ARTIST").values().size());
    assertEquals("artist 1 / artist 2", t.artist());
    assertEquals(17, t.track());
    var item = t.itemListMap().get("TRACK");
    assertEquals(Item.ItemType.Text, item.type());
    assertFalse(item.isEmpty());
    assertEquals(9 + 5 + 2, item.size());
  }

  @Test
  void testPropertyInterface2() {
    var t = new Tag();
    t.setItem("TRACK", new Item("TRACK", List.of("17")));
    var b = new Item();
    b.setType(Item.ItemType.Binary);
    var first = new ByteVector("first");
    b.setBinaryData(first);
    t.setItem("TESTBINARY", b);
    var p = t.properties();
    assertEquals(1, p.unsupportedData().size());
    assertTrue(p.containsKey("TRACKNUMBER"));
    assertFalse(p.containsKey("TRACK"));
    assertTrue(t.itemListMap().containsKey("TESTBINARY"));
    assertEquals(first, t.itemListMap().get("TESTBINARY").binaryData());
    var second = new ByteVector("second");
    t.setData("TESTBINARY", second);
    var item = t.itemListMap().get("TESTBINARY");
    assertEquals(Item.ItemType.Binary, item.type());
    assertFalse(item.isEmpty());
    assertEquals(9 + 10 + second.size(), item.size());
    assertEquals(second, item.binaryData());
    t.removeUnsupportedProperties(p.unsupportedData());
    assertFalse(t.itemListMap().containsKey("TESTBINARY"));
    t.setItem("TRACKNUMBER", new Item("TRACKNUMBER", List.of("29")));
    p = t.properties();
    assertEquals(2, p.get("TRACKNUMBER").size());
    assertEquals("17", p.get("TRACKNUMBER").get(0));
    assertEquals("29", p.get("TRACKNUMBER").get(1));
  }

  @Test
  void testInvalidKeys() {
    var p = PropertyMap.builder().put("A", "invalid key: one character").put("MP+", "invalid key: forbidden string").put("\u1234\u3456", "invalid key: Unicode").put("A B~C", "valid key: space and tilde").put("ARTIST", "valid key: normal one").build();
    var t = new Tag();
    var rejected = t.replaceProperties(p).rejected();
    assertEquals(3, rejected.size());
    for (String key : List.of("A", "MP+", "\u1234\u3456")) assertTrue(rejected.containsKey(key));
    assertEquals(2, t.itemListMap().size());
    t.addValue("VALID KEY", "Test Value 1");
    t.addValue("INVALID KEY \u007f", "Test Value 2");
    t.addValue("INVALID KEY \u1234\u3456", "Test Value 3");
    assertEquals(3, t.itemListMap().size());
  }

  @Test
  void testTextBinary() {
    var item = new Item("DUMMY", List.of("Test Text"));
    assertEquals("Test Text", item.toString());
    assertEquals(new ByteVector(), item.binaryData());
    var data = new ByteVector("Test Data");
    item.setBinaryData(data);
    assertTrue(item.values().isEmpty());
    assertEquals("", item.toString());
    assertEquals(data, item.binaryData());
    item.setValue("Test Text 2");
    assertEquals("Test Text 2", item.toString());
    assertEquals(new ByteVector(), item.binaryData());
  }

  static class FooterAccess extends Footer {
    void apply(ByteVector d) {
      parse(d);
    }

    ByteVector raw(boolean header) {
      return render(header);
    }
  }

  static class TagAccess extends Tag {
    TagAccess() {
      super();
    }

    TagAccess(FileBase f, long o) {
      super(f, o);
    }

    void again() {
      read();
    }

    void apply(ByteVector d) {
      parse(d);
    }
  }

  static class Bare extends FileBase {
    Bare(IOStream s) {
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
  void protectedFooterHelpersKeepPartialStateAndBaseDispatch() throws Exception {
    var f = new FooterAccess();
    f.setTagSize(99);
    f.setItemCount(7);
    for (int i = 0; i < 32; i++) {
      f.apply(new ByteVector(i));
      assertEquals(99, f.tagSize());
      assertEquals(7, f.itemCount());
    }
    assertEquals(32, f.raw(true).size());
    assertTrue(f.renderHeader().isEmpty());
    var raw = f.renderFooter();
    raw.set(0, (byte) 'X');
    f.apply(raw);
    assertEquals(2000, f.version());
    assertEquals(99, f.tagSize());
    assertEquals(7, f.itemCount());
    assertEquals(raw.mid(8), f.renderFooter().mid(8));
    assertTrue(Modifier.isFinal(Footer.class.getDeclaredMethod("parse", ByteVector.class).getModifiers()));
  }

  @Test
  void protectedTagReadRetainsPriorFieldsAndBorrowsFile() {
    var first = new Tag();
    first.setTitle("first");
    var raw = first.render();
    var stream = new ByteVectorStream(raw);
    var t = new TagAccess(new Bare(stream), raw.size() - 32);
    assertEquals("first", t.title());
    var second = new Tag();
    second.setArtist("next");
    var next = second.render();
    assertEquals(raw.size(), next.size());
    stream.seek(0);
    stream.writeBlock(next);
    t.again();
    assertEquals("first", t.title());
    assertEquals("next", t.artist());
    assertTrue(stream.isOpen());
  }

  @Test
  void inactiveItemStorageFollowsImplementationRatherThanHeaderProse() {
    var item = new Item();
    var raw = new ByteVector("inactive");
    item.setBinaryData(raw);
    item.setType(Item.ItemType.Text);
    assertEquals(raw, item.binaryData());
    item.setType(Item.ItemType.Locator);
    assertEquals(raw, item.binaryData());
  }
}
