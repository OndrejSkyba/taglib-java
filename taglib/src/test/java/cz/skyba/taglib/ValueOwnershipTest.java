package cz.skyba.taglib;

import cz.skyba.taglib.asf.AsfFile;
import cz.skyba.taglib.asf.Attribute;
import cz.skyba.taglib.asf.Picture;
import cz.skyba.taglib.mp4.*;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ValueOwnershipTest {
  @Test
  void itemCopiesRetainMetadataAndDetachMutableExports() {
    var input = new ByteVector("payload");
    var original = new Item(List.of(input));
    original.setAtomDataType(AtomDataType.TypeUTF8);
    var copy = new Item(original);
    original.setAtomDataType(AtomDataType.TypeInteger);
    assertEquals(AtomDataType.TypeUTF8, copy.atomDataType());
    copy.setAtomDataType(AtomDataType.TypeImplicit);
    assertEquals(AtomDataType.TypeInteger, original.atomDataType());
    input.clear();
    copy.toByteVectorList().getFirst().clear();
    assertEquals(new ByteVector("payload"), original.toByteVectorList().getFirst());
    assertEquals(new ByteVector("payload"), copy.toByteVectorList().getFirst());
  }

  @Test
  void ownerItemReferencesRemainLiveAndSaveTheirEdits() throws Exception {
    var stream = new ByteVectorStream(Files.readAllBytes(UpstreamAudioFixtures.fixture("no-tags.m4a")));
    String key = "----:org.Test:LIVE";
    var item = new Item(List.of(new ByteVector("payload")));
    var type = new AtomDataType(253);
    try (var file = new Mp4File(stream, ReadOptions.TAGS_ONLY)) {
      file.tag().setItem(key, item);
      var map = file.tag().itemMap();
      assertSame(item, file.tag().item(key));
      assertSame(item, map.get(key));
      assertSame(item, map.entrySet().iterator().next().getValue());
      assertThrows(UnsupportedOperationException.class,
        () -> map.entrySet().iterator().next().setValue(new Item()));
      var copy = new Item(item);
      copy.setAtomDataType(AtomDataType.TypeInteger);
      assertEquals(AtomDataType.TypeUndefined, item.atomDataType());
      map.get(key).setAtomDataType(type);
      file.save();
    }
    try (var file = new Mp4File(stream, ReadOptions.TAGS_ONLY)) {
      assertEquals(type, file.tag().item(key).atomDataType());
      assertEquals(new ByteVector("payload"), file.tag().item(key).toByteVectorList().getFirst());
      var parsed = file.tag().item(key);
      assertSame(parsed, file.tag().itemMap().get(key));
      parsed.setAtomDataType(new AtomDataType(254));
      file.save();
    }
    try (var file = new Mp4File(stream, ReadOptions.TAGS_ONLY)) {
      assertEquals(new AtomDataType(254), file.tag().item(key).atomDataType());
    }
  }

  @Test
  void independentItemCopiesSaveTheirOwnAtomTypes() throws Exception {
    var stream = new ByteVectorStream(Files.readAllBytes(UpstreamAudioFixtures.fixture("no-tags.m4a")));
    var original = new Item(List.of(new ByteVector("payload")));
    original.setAtomDataType(new AtomDataType(253));
    var copy = new Item(original);
    copy.setAtomDataType(new AtomDataType(254));
    String originalKey = "----:org.Test:ORIGINAL", copyKey = "----:org.Test:COPY";
    try (var file = new Mp4File(stream, ReadOptions.TAGS_ONLY)) {
      file.tag().setItem(originalKey, original);
      file.tag().setItem(copyKey, copy);
      file.save();
    }
    try (var file = new Mp4File(stream, ReadOptions.TAGS_ONLY)) {
      assertEquals(new AtomDataType(253), file.tag().item(originalKey).atomDataType());
      assertEquals(new AtomDataType(254), file.tag().item(copyKey).atomDataType());
      for (String key : List.of(originalKey, copyKey)) {
        assertEquals(new ByteVector("payload"), file.tag().item(key).toByteVectorList().getFirst());
      }
    }
  }

  @Test
  void pictureAndAttributeCopiesKeepTheirPayloadsAfterReplacement() {
    var input = new ByteVector("payload");
    var picture = new Picture();
    picture.setPicture(input);
    var pictureCopy = new Picture(picture);
    var attribute = new Attribute(picture);
    var attributeCopy = new Attribute(attribute);
    input.clear();
    picture.setPicture(new ByteVector("replacement"));
    assertEquals(new ByteVector("payload"), pictureCopy.picture());
    assertEquals(new ByteVector("payload"), attribute.toPicture().picture());
    attribute.toPicture().setPicture(new ByteVector("owned"));
    attribute.toPicture().picture().clear();
    assertEquals(new ByteVector("owned"), attribute.toPicture().picture());
    assertEquals(new ByteVector("payload"), attributeCopy.toPicture().picture());
  }

  @Test
  void namedAttributeViewsKeepLiveScopeAndPictureEdits() throws Exception {
    var stream = new ByteVectorStream(Files.readAllBytes(UpstreamAudioFixtures.fixture("silence-1.wma")));
    var picture = new Picture();
    picture.setMimeType("image/png");
    picture.setDescription("original");
    picture.setPicture(new ByteVector("payload"));
    var value = new Attribute(picture);
    var copy = new Attribute(value);
    try (var file = new AsfFile(stream, ReadOptions.TAGS_ONLY)) {
      file.tag().setAttribute("WM/Picture", value);
      assertSame(value, file.tag().attribute("WM/Picture").getFirst());
      assertSame(value, file.tag().attributeListMap().get("WM/Picture").getFirst());
      var named = file.tag().attributes().stream()
        .filter(attribute -> attribute.name().equals("WM/Picture")).findFirst().orElseThrow();
      named.setStream(43);
      named.setLanguage(56);
      named.toPicture().setDescription("edited");
      assertEquals(43, value.stream());
      assertEquals(0, copy.stream());
      assertEquals("original", copy.toPicture().description());
      file.save();
    }
    stream.seek(0);
    try (var file = new AsfFile(stream, ReadOptions.TAGS_ONLY)) {
      var saved = file.tag().attribute("WM/Picture").getFirst();
      assertEquals(43, saved.stream());
      assertEquals(56, saved.language());
      assertEquals("edited", saved.toPicture().description());
      assertEquals(new ByteVector("payload"), saved.toPicture().picture());
      assertSame(saved, file.tag().attributeListMap().get("WM/Picture").getFirst());
      var named = file.tag().attributes().stream()
        .filter(attribute -> attribute.name().equals("WM/Picture")).findFirst().orElseThrow();
      named.setStream(44);
      named.toPicture().setDescription("edited after reopening");
      assertEquals(44, saved.stream());
      file.save();
    }
    stream.seek(0);
    try (var file = new AsfFile(stream, ReadOptions.TAGS_ONLY)) {
      var saved = file.tag().attribute("WM/Picture").getFirst();
      assertEquals(44, saved.stream());
      assertEquals("edited after reopening", saved.toPicture().description());
    }
  }

  @Test
  void pictureConstructorAndAttributeCopiesSaveIndependentValues() throws Exception {
    var stream = new ByteVectorStream(Files.readAllBytes(UpstreamAudioFixtures.fixture("silence-1.wma")));
    var picture = new Picture();
    picture.setType(Picture.Type.FrontCover);
    picture.setMimeType("image/png");
    picture.setDescription("original");
    picture.setPicture(new ByteVector("original payload"));
    var original = new Attribute(picture);
    var copy = new Attribute(original);
    picture.setDescription("external change");
    picture.setPicture(new ByteVector("external payload"));
    original.setStream(1);
    original.setLanguage(2);
    copy.setStream(3);
    copy.setLanguage(4);
    copy.toPicture().setType(Picture.Type.BackCover);
    copy.toPicture().setMimeType("image/jpeg");
    copy.toPicture().setDescription("copy");
    copy.toPicture().setPicture(new ByteVector("copy payload"));
    try (var file = new AsfFile(stream, ReadOptions.TAGS_ONLY)) {
      file.tag().setAttribute("WM/Picture", List.of(original, copy));
      file.save();
    }
    stream.seek(0);
    try (var file = new AsfFile(stream, ReadOptions.TAGS_ONLY)) {
      var values = file.tag().attribute("WM/Picture");
      assertEquals(2, values.size());
      var savedOriginal = values.stream().filter(value -> value.stream() == 1).findFirst().orElseThrow();
      var savedCopy = values.stream().filter(value -> value.stream() == 3).findFirst().orElseThrow();
      assertEquals(2, savedOriginal.language());
      assertEquals(4, savedCopy.language());
      assertEquals(Picture.Type.FrontCover, savedOriginal.toPicture().type());
      assertEquals(Picture.Type.BackCover, savedCopy.toPicture().type());
      assertEquals("image/png", savedOriginal.toPicture().mimeType());
      assertEquals("image/jpeg", savedCopy.toPicture().mimeType());
      assertEquals("original", savedOriginal.toPicture().description());
      assertEquals("copy", savedCopy.toPicture().description());
      assertEquals(new ByteVector("original payload"), savedOriginal.toPicture().picture());
      assertEquals(new ByteVector("copy payload"), savedCopy.toPicture().picture());
    }
  }

  @Test
  void namedCopiesDetachWhileViewsKeepDistinctNames() {
    var value = new Attribute(7);
    var namedCopy = value.named("detached");
    namedCopy.setStream(43);
    assertEquals(0, value.stream());
    var tag = new cz.skyba.taglib.asf.Tag();
    tag.setAttribute("A", value);
    tag.setAttribute("B", value);
    var named = tag.attributes();
    assertEquals(List.of("A", "B"), named.stream().map(Attribute::name).toList());
    named.getFirst().setLanguage(56);
    assertEquals(56, tag.attribute("A").getFirst().language());
    assertEquals(56, tag.attribute("B").getFirst().language());
    assertEquals("", value.name());
  }

  @Test
  void chapterSnapshotsCopyListsAndRetainImmutableValues() {
    var chapter = new Chapter("Title", 123);
    var input = new ArrayList<>(List.of(chapter));
    var holder = new ChapterHolder();
    holder.setChapters(input);
    var snapshot = holder.chapters();
    var quickTime = new QuickTimeChapters(input);
    input.clear();
    assertSame(chapter, snapshot.getFirst());
    assertSame(chapter, quickTime.chapters().getFirst());
    assertThrows(UnsupportedOperationException.class, snapshot::clear);
    holder.setChapters(List.of());
    assertEquals(List.of(chapter), snapshot);
    assertEquals(List.of(chapter), quickTime.chapters());
  }
}
