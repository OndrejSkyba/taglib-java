package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.TagBase;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.TagString;
import cz.skyba.taglib.toolkit.Variant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TagOwnerTest {
  static ByteVector bytes(String hex) {
    return new ByteVector(HexFormat.of().parseHex(hex));
  }

  static ByteVector wire(String id, ByteVector fields) {
    return new ByteVector(id).append(SynchData.fromUnsignedInt(fields.size())).append(new ByteVector(2)).append(fields);
  }

  static TextIdentificationFrame title(String text) {
    var f = new TextIdentificationFrame(new ByteVector("TIT2"), TagString.Type.LATIN1);
    f.setText(text);
    return f;
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

  static class Access extends Tag {
    Access() {
      super();
    }

    Access(FileBase f, long o, FrameFactory c) {
      super(f, o, c);
    }

    void append(ByteVector d) {
      parse(d);
    }
  }

  static class Factory extends FrameFactory {
    int raw, typed, update, aggregate, property;

    public Frame createFrame(ByteVector d, Header h) {
      raw++;
      return super.createFrame(d, h);
    }

    protected Frame createFrame(ByteVector d, Frame.Header h, Header t) {
      typed++;
      return super.createFrame(d, h, t);
    }

    protected boolean updateFrame(Frame.Header h) {
      update++;
      return super.updateFrame(h);
    }

    public void rebuildAggregateFrames(Tag t) {
      aggregate++;
      super.rebuildAggregateFrames(t);
    }

    public Frame createFrameForProperty(String k, List<String> v) {
      property++;
      return super.createFrameForProperty(k, v);
    }
  }

  @AfterEach
  void reset() {
    Tag.setLatin1StringHandler(null);
  }

  @Test
  void missingLookupsPreservePropertiesAndRenderedFrames() {
    var tag = new Tag();
    tag.addFrame(title("retained title"));
    var properties = tag.properties();
    var rendered = tag.render();
    int buckets = tag.frameListMap().size();
    for (String id : List.of("APIC", "GEOB", "TPE1", "ZZZZ")) {
      var key = new ByteVector(id);
      assertTrue(tag.frameList(key).isEmpty());
      assertNull(tag.frameListMap().get(key));
      assertEquals(List.of(), tag.frameListMap().getOrDefault(key, List.of()));
      assertFalse(tag.frameListMap().containsKey(key));
    }
    assertEquals(buckets, tag.frameListMap().size());
    assertTrue(tag.complexPropertyKeys().isEmpty());
    assertEquals(properties, tag.properties());
    assertEquals(rendered, tag.render());
  }

  @Test
  void frameContainersRemainLiveAndMissingLookupsDoNotInsertBuckets() {
    var t = new Tag();
    var map = t.frameListMap();
    var list = t.frameList();
    var id = new ByteVector("TIT2");
    var absent = t.frameList(id);
    assertTrue(absent.isEmpty());
    assertFalse(map.containsKey(id));
    assertNull(map.get(id));
    assertEquals(List.of(), map.getOrDefault(id, List.of()));
    assertTrue(map.isEmpty());
    id.clear();
    var f = title("a");
    t.addFrame(f);
    var bucket = t.frameList(new ByteVector("TIT2"));
    assertSame(bucket, t.frameList(new ByteVector("TIT2")));
    assertTrue(absent.isEmpty());
    assertSame(f, list.getFirst());
    assertSame(f, bucket.getFirst());
    assertSame(map, t.frameListMap());
    assertThrows(UnsupportedOperationException.class, () -> list.clear());
    assertThrows(UnsupportedOperationException.class, () -> map.clear());
    map.keySet().iterator().next().clear();
    assertTrue(map.containsKey(new ByteVector("TIT2")));
    t.removeFrame(f);
    assertTrue(list.isEmpty());
    assertTrue(bucket.isEmpty());
    assertEquals("a", f.toString());
  }

  @Test
  void liveHeaderIdMutationRetainsOriginalBucketAndInvalidRemovalRejects() {
    var t = new Tag();
    var f = title("a");
    t.addFrame(f);
    f.header().setFrameID(new ByteVector("TPE1"));
    assertEquals("a", t.title());
    assertEquals("", t.artist());
    assertSame(f, t.frameList(new ByteVector("TIT2")).getFirst());
    assertThrows(IllegalStateException.class, () -> t.removeFrame(f));
    assertSame(f, t.frameList().getFirst());
  }

  @Test
  void absentRemovalHasExplicitBoundaryAndCheckedTocRemovalIsHarmless() {
    var f = title("a");
    assertThrows(IllegalStateException.class, () -> new Tag().removeFrame(f));
    var c = new ChapterFrame(new ByteVector("c"), 0, 0, 0, 0);
    assertThrows(IllegalStateException.class, () -> c.removeEmbeddedFrame(f));
    var toc = new TableOfContentsFrame(new ByteVector("toc"), new ArrayList<ByteVector>());
    toc.removeEmbeddedFrame(f);
    assertTrue(toc.embeddedFrameListMap().containsKey(new ByteVector("TIT2")));
  }

  @Test
  void constructorAndParseUseCustomFactoryAndKeepTheSameHeader() {
    var f = title("a");
    var h = new Header();
    var body = f.render().append(new ByteVector(11));
    h.setTagSize(body.size());
    var s = new ByteVectorStream(new ByteVector("pre").append(h.render()).append(body).append(new ByteVector("end")));
    var c = new Factory();
    var t = new Access(new Bare(s), 3, c);
    assertEquals(1, c.raw);
    assertEquals(1, c.typed);
    assertEquals(1, c.update);
    assertEquals(1, c.aggregate);
    var header = t.header();
    var first = t.frameList().getFirst();
    t.append(body);
    assertEquals(2, c.raw);
    assertSame(first, t.frameList().getFirst());
    assertEquals(2, t.frameList().size());
    t.render();
    assertSame(header, t.header());
  }

  @Test
  void incompleteHeaderSizedBodyStillParsesAccordingToUnsignedNativeLoop() {
    var c = new Factory();
    var h = new Header();
    h.setTagSize(7);
    var d = new ByteVector("TIT2").append(new ByteVector(3));
    var s = new ByteVectorStream(h.render().append(d));
    var t = new Access(new Bare(s), 0, c);
    assertEquals(1, c.raw);
    assertEquals(1, c.typed);
    assertEquals(1, c.aggregate);
    assertTrue(t.isEmpty());
  }

  @Test
  void invalidFactoryFrameReturnsBeforeAggregate() {
    var c = new Factory();
    var h = new Header();
    var d = new ByteVector("bad!").append(new ByteVector(20));
    h.setTagSize(d.size());
    var t = new Access(new Bare(new ByteVectorStream(h.render().append(d))), 0, c);
    assertEquals(1, c.raw);
    assertEquals(0, c.aggregate);
    assertTrue(t.isEmpty());
  }

  @Test
  void commentSelectsFirstEmptyDescriptionAndCommonSettersUseVirtualText() {
    var t = new Tag();
    var a = new CommentsFrame();
    a.setDescription("named");
    a.setText("a");
    var b = new CommentsFrame();
    b.setText("b");
    t.addFrame(a);
    t.addFrame(b);
    assertEquals("b", t.comment());
    t.setComment("new");
    assertEquals("a", a.text());
    assertEquals("new", b.text());
    t.setComment("");
    assertTrue(t.frameList(new ByteVector("COMM")).isEmpty());
  }

  @Test
  void unsignedSettersUseNativeSignedNumberTextAndYearPrefix() {
    var t = new Tag();
    t.setTrack(0xffffffffL);
    assertEquals(0xffffffffL, t.track());
    assertEquals("-1", t.frameList(new ByteVector("TRCK")).getFirst().toString());
    t.setYear(0x1_0000_0000L);
    assertEquals(0, t.year());
    t.setYear(0xffffffffL);
    assertEquals(0xffffffffL, t.year());
  }

  @Test
  void propertyReplacementKeepsMatchingIdentityAndOpaqueUnsupportedData() {
    var c = new Factory();
    var t = new Access(null, 0, c);
    var f = title("same");
    var opaque = new UnknownFrame(wire("ZZZZ", new ByteVector("data")));
    t.addFrame(f);
    t.addFrame(opaque);
    var input = PropertyMap.builder().put("TITLE", "same").put("ARTIST", "artist").build();
    assertTrue(t.replaceProperties(input).rejected().isEmpty());
    assertSame(f, t.frameList(new ByteVector("TIT2")).getFirst());
    assertSame(opaque, t.frameList(new ByteVector("ZZZZ")).getFirst());
    assertEquals(1, c.property);
  }

  @Test
  void duplicateMatchingPropertyFramesConsumePendingKeyOnce() {
    var t = new Tag();
    var a = title("same");
    var b = title("same");
    t.addFrame(a);
    t.addFrame(b);
    t.replaceProperties(PropertyMap.builder().put("TITLE", "same").build());
    assertEquals(List.of(a), t.frameList());
  }

  @Test
  void missingFrameReadsDoNotChangeComplexKeys() {
    var t = new Tag();
    assertTrue(t.complexProperties("PICTURE").isEmpty());
    assertTrue(t.frameListMap().isEmpty());
    var bucket = t.frameList(new ByteVector("APIC"));
    assertTrue(t.complexPropertyKeys().isEmpty());
    assertTrue(bucket.isEmpty());
    assertTrue(t.frameListMap().isEmpty());
    t.addFrame(new AttachedPictureFrame());
    assertEquals(List.of("PICTURE"), t.complexPropertyKeys());
    t.setComplexProperties("picture", List.of());
    assertEquals(List.of("PICTURE"), t.complexPropertyKeys());
  }

  @Test
  void complexValuesUseExactVariantTypesAndDefaultLatin1() {
    var t = new Tag();
    t.setComplexProperties("GENERALOBJECT", List.of(Map.of("data", new Variant(3), "description", new Variant(false), "fileName", new Variant("f"))));
    var g = (GeneralEncapsulatedObjectFrame) t.frameList().getFirst();
    assertTrue(g.object().isEmpty());
    assertEquals("", g.description());
    assertEquals("f", g.fileName());
    assertEquals(TagString.Type.LATIN1, g.textEncoding());
    var values = t.complexProperties("generalobject");
    values.getFirst().put("fileName", new Variant("changed"));
    assertEquals("f", g.fileName());
    assertFalse(t.setComplexProperties("none", List.of()));
  }

  @Test
  void descriptionRemovalIgnoresSeparatorAndDoesNotHandleChapterDescriptions() {
    var t = new Tag();
    var c = new CommentsFrame();
    c.setDescription("X");
    t.addFrame(c);
    var chapter = new ChapterFrame(new ByteVector("ch"), 0, 0, 0, 0);
    t.addFrame(chapter);
    t.removeUnsupportedProperties(List.of("COMM!X", "CHAP/ch"));
    assertEquals(List.of(chapter), t.frameList());
  }

  @Test
  void renderUses1024MinimumAndRetainsAllowedSmallPadding() {
    var t = new Tag();
    t.setTitle("a");
    var h = t.header();
    var f = t.frameList().getFirst();
    long size = f.render().size();
    h.setTagSize(size + 200);
    assertEquals(size + 210, t.render().size());
    h.setTagSize(size + 1025);
    assertEquals(size + 1034, t.render().size());
  }

  @Test
  void downgradeCreatesTemporaryDateFramesAndKeepsOriginalOwnership() {
    var t = new Tag();
    var d = new TextIdentificationFrame(new ByteVector("TDRC"), TagString.Type.UTF8);
    d.setText("2026-10-05T12:34");
    t.addFrame(d);
    var out = t.render(Tag.Version.V3);
    assertEquals(List.of(d), t.frameList());
    assertEquals(4, d.header().version());
    assertTrue(out.find(new ByteVector("TYER")) >= 0);
    assertTrue(out.find(new ByteVector("TDAT")) >= 0);
    assertTrue(out.find(new ByteVector("TIME")) >= 0);
    assertEquals(3, t.header().majorVersion());
  }

  @Test
  void renderingOmittedFramesStillChangesTheirHeaderVersion() {
    var t = new Tag();
    var unknown = new UnknownFrame(new ByteVector("ABC"));
    t.addFrame(unknown);
    t.render(Tag.Version.V3);
    assertEquals(3, unknown.header().version());
    assertSame(unknown, t.frameList().getFirst());
  }

  @Test
  void publicHandlerRegistrationUsesDetachedBytesAndNullRestoresDefault() {
    var original = Tag.latin1StringHandler();
    Tag.setLatin1StringHandler(new Latin1StringHandler() {
      public String parse(ByteVector d) {
        d.clear();
        return "custom";
      }
    });
    var input = wire("TIT2", bytes("00616263"));
    assertEquals("custom", new TextIdentificationFrame(input).toString());
    assertEquals(bytes("00616263"), input.mid(10));
    Tag.setLatin1StringHandler(null);
    assertSame(original, Tag.latin1StringHandler());
    assertEquals("abc", new TextIdentificationFrame(input).toString());
  }

  @Test
  void tagFindersUseOriginalBucketsAfterHeaderMutation() {
    var t = new Tag();
    var f = new CommentsFrame();
    f.setDescription("X");
    t.addFrame(f);
    f.header().setFrameID(new ByteVector("ZZZZ"));
    assertSame(f, CommentsFrame.findByDescription(t, "X"));
    assertNull(CommentsFrame.findByDescription(t, "x"));
  }

  @Test
  void factoryDefaultsAreSeparateFromPropertyCreationEncoding() {
    var c = new Factory();
    assertFalse(c.isUsingDefaultTextEncoding());
    assertEquals(TagString.Type.LATIN1, c.defaultTextEncoding());
    c.setDefaultTextEncoding(TagString.Type.UTF16);
    var raw = wire("TIT2", bytes("00616263"));
    var f = (TextIdentificationFrame) c.createFrame(raw, new Header());
    assertEquals(TagString.Type.UTF16, f.textEncoding());
    var property = (TextIdentificationFrame) c.createFrameForProperty("TITLE", List.of("abc"));
    assertEquals(TagString.Type.UTF8, property.textEncoding());
    assertEquals(bytes("00616263"), raw.mid(10));
  }

  @Test
  void prepareUnsynchronizationChangesPrivateInputAndRetainsDeclaredSize() {
    var c = new Factory();
    var raw = wire("TIT2", bytes("0061ff0062"));
    raw.set(9, (byte) 2);
    var original = new ByteVector(raw);
    var p = c.prepareFrameHeader(raw, new Header());
    assertTrue(p.supported());
    assertEquals(5, p.header().frameSize());
    assertEquals(bytes("0061ff62"), raw.mid(10));
    assertEquals(bytes("0061ff0062"), original.mid(10));
  }

  @Test
  void aggregateDateUsesFixedUnicodeDecodingAndKeepsDiscardedFrames() {
    var t = new Tag();
    t.header().setMajorVersion(3);
    var y = new TextIdentificationFrame(new ByteVector("TDRC"), TagString.Type.LATIN1);
    y.setText("2026");
    var date = new UnknownFrame(wire("TDAT", bytes("0031303035")));
    var time = new UnknownFrame(wire("TIME", bytes("0031323334")));
    t.addFrame(y);
    t.addFrame(date);
    t.addFrame(time);
    Tag.setLatin1StringHandler(new Latin1StringHandler() {
      public String parse(ByteVector d) {
        return "wrong";
      }
    });
    new Factory().rebuildAggregateFrames(t);
    assertEquals("2026-05-10T12:34", y.toString());
    assertEquals(List.of(y, date, time), t.frameList());
  }

  @Test
  void propertyHookCanInsertBucketsAndAppendToCurrentAndFutureLists() {
    var t = new Tag();
    var visited = new ArrayList<String>();
    class Hook extends Frame {
      final String name;
      final boolean add;

      Hook(String name, boolean add) {
        super(new ByteVector(name));
        this.name = name;
        this.add = add;
      }

      public String toString() {
        return "";
      }

      protected void parseFields(ByteVector d) {
      }

      protected ByteVector renderFields() {
        return new ByteVector("x");
      }

      protected PropertyMap asProperties() {
        visited.add(name);
        if (add) {
          t.frameList(new ByteVector("A000"));
          t.frameList(new ByteVector("ZZZZ"));
          t.addFrame(new Hook("X001", false));
          t.addFrame(new Hook("Z999", false));
        }
        return PropertyMap.EMPTY;
      }
    }
    t.addFrame(new Hook("X001", true));
    t.addFrame(new Hook("Y001", false));
    t.replaceProperties(PropertyMap.EMPTY);
    assertEquals(List.of("X001", "X001", "Y001", "Z999"), visited);
    assertEquals(4, t.frameList().size());
    assertEquals(3, t.frameListMap().size());
  }

  @Test
  void liveListIteratorSurvivesRemovalOfEarlierEntryAndSeesAppend() {
    var t = new Tag();
    var a = title("a");
    var b = title("b");
    var c = title("c");
    t.addFrame(a);
    t.addFrame(b);
    var iterator = t.frameList().iterator();
    assertSame(a, iterator.next());
    assertSame(b, iterator.next());
    t.removeFrame(a);
    t.addFrame(c);
    assertSame(c, iterator.next());
    assertFalse(iterator.hasNext());
  }

  @Test
  void nullFactoryKeepsDefinedExistingFrameAndEmptyOperations() {
    var t = new Tag(null, 0, null);
    assertTrue(t.isEmpty());
    assertEquals("", t.title());
    assertEquals(1034, t.render().size());
    t.setTitle("");
    t.setComment("");
    var f = title("existing");
    t.addFrame(f);
    t.setTitle("changed");
    assertSame(f, t.frameList().getFirst());
    assertEquals("changed", t.title());
    assertTrue(t.replaceProperties(PropertyMap.builder().put("TITLE", "changed").build()).rejected().isEmpty());
    assertTrue(t.render().size() > 0);
  }

  @Test
  void nullFactoryRejectsOnlyTheFactoryDereference() {
    var t = new Tag(null, 0, null);
    assertThrows(IllegalStateException.class, () -> t.setTitle("new"));
    assertTrue(t.isEmpty());
    assertThrows(IllegalStateException.class, () -> t.setComment("new"));
    assertThrows(IllegalStateException.class, () -> t.replaceProperties(PropertyMap.builder().put("TITLE", "new").build()));
    assertTrue(t.isEmpty());
    assertTrue(t.replaceProperties(PropertyMap.builder().put("ARRANGER", "a").build()).rejected().isEmpty());
    assertEquals(List.of("a"), t.properties().get("ARRANGER"));
  }

}
