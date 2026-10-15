package cz.skyba.taglib;

import cz.skyba.taglib.flac.FlacFile;
import cz.skyba.taglib.id3.v2.AttachedPictureFrame;
import cz.skyba.taglib.id3.v2.GeneralEncapsulatedObjectFrame;
import cz.skyba.taglib.mp4.CoverArt;
import cz.skyba.taglib.ogg.XiphComment;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.Variant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class ComplexPropertyAccessTest {
  @TempDir
  Path directory;

  static ByteVector b(String h) {
    return new ByteVector(HexFormat.of().parseHex(h));
  }

  static final ByteVector PIXEL = b("ffd8ffe000104a46494600010101004800480000ffdb004300030202020202030202020303030304060404040404080606050609080a0a090809090a0c0f0c0a0b0e0b09090d110d0e0f101011100a0c12131210130f101010ffc9000b080001000101011100ffcc000600101005ffda0008010100003f00d2cf20ffd9");

  static Map<String, Variant> picture() {
    return new TreeMap<>(Map.of("data", new Variant(PIXEL), "mimeType", new Variant("image/jpeg"), "description", new Variant("Embedded cover"), "pictureType", new Variant("Front Cover")));
  }

  static Map<String, Variant> dimensional() {
    var p = picture();
    p.put("colorDepth", new Variant(8));
    p.put("numColors", new Variant(1));
    p.put("width", new Variant(1));
    p.put("height", new Variant(1));
    return p;
  }

  static void tagSetGetPicture(TagBase tag, Map<String, Variant> p) {
    assertTrue(tag.complexPropertyKeys().isEmpty());
    assertTrue(tag.complexProperties("PICTURE").isEmpty());
    assertTrue(tag.setComplexProperties("PICTURE", List.of(p)));
    assertEquals(List.of("PICTURE"), tag.complexPropertyKeys());
    assertEquals(p, tag.complexProperties("PICTURE").getFirst());
  }

  @Test
  void testReadMp3Picture() throws Exception {
    try (var f = new AudioFile(FileReferenceResolversAndSavingTest.fixture("compressed_id3_frame.mp3"), ReadOptions.TAGS_ONLY)) {
      assertEquals(List.of("PICTURE"), f.complexPropertyKeys());
      var pics = f.complexProperties("PICTURE");
      assertEquals(1, pics.size());
      var p = pics.getFirst();
      assertEquals(86414, p.get("data").toByteVector().size());
      assertEquals("", p.get("description").toText());
      assertEquals("image/bmp", p.get("mimeType").toText());
      assertEquals("Other", p.get("pictureType").toText());
    }
  }

  @Test
  void testReadM4aPicture() throws Exception {
    try (var f = new AudioFile(FileReferenceResolversAndSavingTest.fixture("has-tags.m4a"), ReadOptions.TAGS_ONLY)) {
      assertEquals(List.of("PICTURE"), f.complexPropertyKeys());
      var pics = f.complexProperties("PICTURE");
      assertEquals(2, pics.size());
      assertEquals(b("89504e470d0a1a0a0000000d4948445200000002000000020802000000fdd49a730000001649444154789c637c9fcac0c0c0c0c4c0c0c0c0c0000011090158ab88db6f0000000049454e44ae426082"), pics.getFirst().get("data").toByteVector());
      assertEquals("image/png", pics.getFirst().get("mimeType").toText());
      assertEquals(b("ffd8ffe000104a46494600010101006400640000ffdb004300090607080706090808080a0a090b0e170f0e0d0d0e1c14151117221e2323211e2020252a352d2527322820202e3f2f3237393c3c3c242d4246413a46353b3c39ffdb0043010a0a0a0e0c0e1b0f0f1b392620263939393939393939393939393939393939393939393939393939393939393939393939393939393939393939393939393939ffc00011080002000203012200021101031101ffc4001500010100000000000000000000000000000007ffc40014100100000000000000000000000000000000ffc4001501010100000000000000000000000000000406ffc40014110100000000000000000000000000000000ffda000c03010002110311003f008d80b819ffd9"), pics.getLast().get("data").toByteVector());
      assertEquals("image/jpeg", pics.getLast().get("mimeType").toText());
    }
  }

  @Test
  void testReadOggPicture() throws Exception {
    try (var f = new AudioFile(FileReferenceResolversAndSavingTest.fixture("lowercase-fields.ogg"), ReadOptions.TAGS_ONLY)) {
      assertEquals(List.of("PICTURE"), f.complexPropertyKeys());
      var pics = f.complexProperties("PICTURE");
      assertEquals(1, pics.size());
      var p = pics.getFirst();
      assertEquals(new ByteVector("JPEG data"), p.get("data").toByteVector());
      assertEquals("image/jpeg", p.get("mimeType").toText());
      assertEquals("Back Cover", p.get("pictureType").toText());
      assertEquals("new image", p.get("description").toText());
      assertEquals(16, p.get("colorDepth").toInt());
      assertEquals(7, p.get("numColors").toInt());
      assertEquals(5, p.get("width").toInt());
      assertEquals(6, p.get("height").toInt());
    }
  }

  @Test
  void testReadWriteFlacPicture() throws Exception {
    var p = dimensional();
    var path = Files.copy(FileReferenceResolversAndSavingTest.fixture("no-tags.flac"), directory.resolve("no-tags.flac"));
    try (var f = new FlacFile(path, ReadOptions.TAGS_ONLY)) {
      assertTrue(f.complexPropertyKeys().isEmpty());
      assertTrue(f.pictureList().isEmpty());
      assertTrue(f.setComplexProperties("PICTURE", List.of(p)));
      f.save();
    }
    try (var f = new FlacFile(path, ReadOptions.TAGS_ONLY)) {
      assertEquals(List.of("PICTURE"), f.complexPropertyKeys());
      assertEquals(p, f.complexProperties("PICTURE").getFirst());
      var pics = f.pictureList();
      assertEquals(1, pics.size());
      assertPicture(p, pics.getFirst());
      assertTrue(f.setComplexProperties("PICTURE", List.of()));
      f.save();
    }
    try (var f = new FlacFile(path, ReadOptions.TAGS_ONLY)) {
      assertTrue(f.complexPropertyKeys().isEmpty());
      assertTrue(f.pictureList().isEmpty());
    }
  }

  static void assertPicture(Map<String, Variant> p, cz.skyba.taglib.flac.Picture f) {
    assertArrayEquals(p.get("data").toByteVector().toByteArray(), f.data());
    assertEquals(p.get("mimeType").toText(), f.mimeType());
    assertEquals(cz.skyba.taglib.flac.Picture.Type.FrontCover, f.type());
    assertEquals(p.get("description").toText(), f.description());
    assertEquals(p.get("colorDepth").toInt(), f.colorDepth());
    assertEquals(p.get("numColors").toInt(), f.numColors());
    assertEquals(p.get("width").toInt(), f.width());
    assertEquals(p.get("height").toInt(), f.height());
  }

  @Test
  void testReadWriteMultipleProperties() throws Exception {
    var two = Map.of("data", new Variant(new ByteVector("PNG data")), "mimeType", new Variant("image/png"), "description", new Variant(""), "pictureType", new Variant("Back Cover"));
    var g1 = Map.of("data", new Variant(new ByteVector("First")), "mimeType", new Variant("text/plain"), "description", new Variant("Object 1"), "fileName", new Variant("test1.txt"));
    var g2 = Map.of("data", new Variant(new ByteVector("Second")), "mimeType", new Variant("text/plain"), "description", new Variant("Object 2"), "fileName", new Variant("test2.txt"));
    var path = Files.copy(FileReferenceResolversAndSavingTest.fixture("xing.mp3"), directory.resolve("xing.mp3"));
    try (var f = new AudioFile(path, ReadOptions.TAGS_ONLY)) {
      assertTrue(f.complexPropertyKeys().isEmpty());
      assertTrue(f.setComplexProperties("PICTURE", List.of(picture(), two)));
      assertTrue(f.setComplexProperties("GENERALOBJECT", List.of(g1, g2)));
      assertDoesNotThrow(f::save);
    }
    try (var f = new AudioFile(path, ReadOptions.TAGS_ONLY)) {
      assertEquals(List.of("PICTURE", "GENERALOBJECT"), f.complexPropertyKeys());
      assertEquals(List.of(picture(), two), f.complexProperties("PICTURE"));
      assertEquals(List.of(g1, g2), f.complexProperties("GENERALOBJECT"));
    }
  }

  @Test
  void testSetGetId3Geob() {
    var p = Map.of("data", new Variant(new ByteVector("Just a test")), "mimeType", new Variant("text/plain"), "description", new Variant("Embedded object"), "fileName", new Variant("test.txt"));
    var tag = new cz.skyba.taglib.id3.v2.Tag();
    assertFalse(tag.frameListMap().containsKey(new ByteVector("GEOB")));
    assertTrue(tag.complexPropertyKeys().isEmpty());
    assertTrue(tag.complexProperties("GENERALOBJECT").isEmpty());
    assertTrue(tag.setComplexProperties("GENERALOBJECT", List.of(p)));
    assertEquals(List.of("GENERALOBJECT"), tag.complexPropertyKeys());
    assertEquals(p, tag.complexProperties("GENERALOBJECT").getFirst());
    var frames = tag.frameList(new ByteVector("GEOB"));
    assertEquals(1, frames.size());
    var f = assertInstanceOf(GeneralEncapsulatedObjectFrame.class, frames.getFirst());
    assertEquals(p.get("data").toByteVector(), f.object());
    assertEquals(p.get("mimeType").toText(), f.mimeType());
    assertEquals(p.get("description").toText(), f.description());
    assertEquals(p.get("fileName").toString(), f.fileName());
  }

  @Test
  void testSetGetId3Picture() {
    var p = picture();
    var tag = new cz.skyba.taglib.id3.v2.Tag();
    assertFalse(tag.frameListMap().containsKey(new ByteVector("APIC")));
    tagSetGetPicture(tag, p);
    var frames = tag.frameList(new ByteVector("APIC"));
    assertEquals(1, frames.size());
    var f = assertInstanceOf(AttachedPictureFrame.class, frames.getFirst());
    assertEquals(p.get("data").toByteVector(), f.picture());
    assertEquals(p.get("mimeType").toText(), f.mimeType());
    assertEquals(p.get("description").toText(), f.description());
    assertEquals(AttachedPictureFrame.Type.FrontCover, f.type());
  }

  @Test
  void testSetGetApePicture() {
    var p = picture();
    p.remove("mimeType");
    var t = new cz.skyba.taglib.ape.Tag();
    assertFalse(t.itemListMap().containsKey("COVER ART (FRONT)"));
    tagSetGetPicture(t, p);
    assertEquals(ByteVector.fromText(p.get("description").toText(), UTF_8).append((byte) 0).append(p.get("data").toByteVector()), t.itemListMap().get("COVER ART (FRONT)").binaryData());
  }

  @Test
  void testSetGetAsfPicture() {
    var p = picture();
    var t = new cz.skyba.taglib.asf.Tag();
    assertFalse(t.attributeListMap().containsKey("WM/Picture"));
    tagSetGetPicture(t, p);
    var attributes = t.attribute("WM/Picture");
    assertEquals(1, attributes.size());
    var f = attributes.getFirst().toPicture();
    assertEquals(p.get("data").toByteVector(), f.picture());
    assertEquals(p.get("mimeType").toText(), f.mimeType());
    assertEquals(p.get("description").toText(), f.description());
    assertEquals(cz.skyba.taglib.asf.Picture.Type.FrontCover, f.type());
  }

  @Test
  void testSetGetMp4Picture() {
    var p = picture();
    p.remove("description");
    p.remove("pictureType");
    var t = new cz.skyba.taglib.mp4.Tag();
    assertFalse(t.itemMap().containsKey("covr"));
    tagSetGetPicture(t, p);
    var covers = t.item("covr").toCoverArtList();
    assertEquals(1, covers.size());
    assertEquals(p.get("data").toByteVector(), covers.getFirst().data());
    assertEquals(CoverArt.Format.JPEG, covers.getFirst().format());
  }

  @Test
  void testSetGetXiphPicture() {
    var p = dimensional();
    var t = new XiphComment();
    assertTrue(t.pictureList().isEmpty());
    tagSetGetPicture(t, p);
    assertEquals(1, t.pictureList().size());
    assertPicture(p, t.pictureList().getFirst());
  }

  @Test
  void testNonExistent() {
    for (TagBase t : List.of(new cz.skyba.taglib.id3.v2.Tag(), new cz.skyba.taglib.id3.v1.Tag())) {
      assertTrue(t.complexPropertyKeys().isEmpty());
      for (String key : List.of("PICTURE", "GENERALOBJECT", "NONEXISTENT"))
        assertTrue(t.complexProperties(key).isEmpty());
      assertFalse(t.setComplexProperties("NONEXISTENT", List.of(Map.of("description", new Variant("test")))));
      assertTrue(t.complexProperties("NONEXISTENT").isEmpty());
      boolean supported = t instanceof cz.skyba.taglib.id3.v2.Tag;
      assertEquals(supported, t.setComplexProperties("PICTURE", List.of(picture())));
      assertEquals(!supported, t.complexProperties("PICTURE").isEmpty());
    }
  }
}
