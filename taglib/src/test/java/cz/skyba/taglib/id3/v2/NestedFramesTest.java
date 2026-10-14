package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.toolkit.ByteVector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NestedFramesTest {
  @Test
  void absentEmbeddedFramesDoNotCreateBucketsOrChangeRendering() {
    var chapter = new ChapterFrame(new ByteVector("chapter"), 0, 0, 0, 0);
    var toc = new TableOfContentsFrame(new ByteVector("toc"));
    var id = new ByteVector("TIT2");
    var chapterBytes = chapter.render();
    var tocBytes = toc.render();
    assertTrue(chapter.embeddedFrameList(id).isEmpty());
    assertNull(chapter.embeddedFrameListMap().get(id));
    assertTrue(chapter.embeddedFrameListMap().isEmpty());
    assertTrue(toc.embeddedFrameList(id).isEmpty());
    assertNull(toc.embeddedFrameListMap().get(id));
    assertTrue(toc.embeddedFrameListMap().isEmpty());
    assertEquals(chapterBytes, chapter.render());
    assertEquals(tocBytes, toc.render());
  }

  @Test
  void nativeDefaultTocConstructorHasEmptyChildrenAndFlags() {
    var id = new ByteVector("toc");
    var toc = new TableOfContentsFrame(id);
    id.clear();
    assertEquals(new ByteVector("toc"), toc.elementID());
    assertFalse(toc.isTopLevel());
    assertFalse(toc.isOrdered());
    assertEquals(0, toc.entryCount());
    assertTrue(toc.childElements().isEmpty());
    assertTrue(toc.embeddedFrameList().isEmpty());
    assertEquals(b("43544f43000000060000746f63000000"), toc.render());
  }

  static ByteVector b(String s) {
    return TagOwnerTest.bytes(s);
  }

  static ByteVector wire(String id, ByteVector p) {
    return TagOwnerTest.wire(id, p);
  }

  static ByteVector chapter() {
    return b("430000000003000000050000000200000003");
  }

  static ByteVector leaf(String text) {
    return TagOwnerTest.title(text).render();
  }

  @Test
  void chapterParsingAppendsLiveChildrenAndShortReparseRetainsFields() {
    var h = new Header();
    var d = wire("CHAP", chapter().append(leaf("CH1")));
    var c = new ChapterFrame(h, d);
    var list = c.embeddedFrameList();
    var bucket = c.embeddedFrameList(new ByteVector("TIT2"));
    var first = list.getFirst();
    c.setData(d);
    assertEquals(2, list.size());
    assertSame(first, list.getFirst());
    assertEquals(2, bucket.size());
    c.setData(wire("CHAP", new ByteVector()));
    assertEquals(new ByteVector("C"), c.elementID());
    assertEquals(2, list.size());
  }

  @Test
  void tocParsingAppendsChildIdsAndEmbeddedFrames() {
    var p = b("5400010243004400").append(leaf("TC1"));
    var d = wire("CTOC", p);
    var c = new TableOfContentsFrame(new Header(), d);
    var list = c.embeddedFrameList();
    c.setData(d);
    assertEquals(4, c.entryCount());
    assertEquals(2, list.size());
    assertEquals(List.of(new ByteVector("C"), new ByteVector("D"), new ByteVector("C"), new ByteVector("D")), c.childElements());
  }

  @Test
  void chapterStripsExactlyOneTrailingNullAndTocKeepsAllBytes() {
    var c = new ChapterFrame(b("430000"), 0, 0, 0, 0);
    assertEquals(b("4300"), c.elementID());
    var t = new TableOfContentsFrame(b("5400"), new ArrayList<ByteVector>());
    assertEquals(b("5400"), t.elementID());
  }

  @Test
  void childIdentifierListsCopyAtEveryOwnerBoundary() {
    var id = new ByteVector("ch");
    var ids = new ArrayList<>(List.of(id, id));
    var toc = new TableOfContentsFrame(new ByteVector("toc"));
    toc.setChildElements(ids);
    ids.clear();
    id.clear();
    toc.childElements().getFirst().clear();
    assertEquals(List.of(new ByteVector("ch"), new ByteVector("ch")), toc.childElements());
    var added = new ByteVector("new");
    toc.addChildElement(added);
    added.clear();
    assertEquals(new ByteVector("new"), toc.childElements().getLast());
    var before = toc.render();
    assertThrows(NullPointerException.class,
      () -> toc.setChildElements(Arrays.asList(new ByteVector("other"), null)));
    assertThrows(NullPointerException.class, () -> toc.addChildElement(null));
    assertEquals(before, toc.render());
  }

  @Test
  void idsChildrenAndImportedListsAreDetachedButFramesRemainShared() {
    var id = new ByteVector("ch");
    var f = TagOwnerTest.title("a");
    var frames = new ArrayList<Frame>(List.of(f));
    var c = new ChapterFrame(id, 0, 0, 0, 0, frames);
    id.clear();
    frames.clear();
    c.elementID().clear();
    f.setText("changed");
    assertEquals(new ByteVector("ch"), c.elementID());
    assertSame(f, c.embeddedFrameList().getFirst());
    var ids = List.of(new ByteVector("ch"));
    var t = new TableOfContentsFrame(new ByteVector("toc"), ids);
    ids.getFirst().clear();
    t.childElements().getFirst().clear();
    assertEquals(new ByteVector("ch"), t.childElements().getFirst());
  }

  @Test
  void renderSetsSameEmbeddedHeaderVersionAndUnsignedFieldsNarrow() {
    var f = TagOwnerTest.title("a");
    var c = new ChapterFrame(new ByteVector("ch"), 0x1_0000_0003L, -1, 0xffffffffL, 0xffffffffL, List.of(f));
    c.header().setVersion(3);
    c.render();
    assertEquals(3, f.header().version());
    assertEquals(3, c.startTime());
    assertEquals(0xffffffffL, c.endTime());
    assertEquals("ch: start time: 3, end time: -1, sub-frames: [ TIT2 ]", c.toString());
  }

  @Test
  void tocCStringFallbackKeepsTrailingNullAndRemovesFirstExactDuplicate() {
    var t = new TableOfContentsFrame(new ByteVector("t"), List.of(b("4300"), new ByteVector("D"), new ByteVector("D")));
    t.removeChildElement(new ByteVector("C"));
    assertEquals(3, t.entryCount());
    assertEquals(b("4300"), t.childElements().getFirst());
    t.removeChildElement(new ByteVector("D"));
    assertEquals(2, t.entryCount());
    t.removeChildElement(new ByteVector("none"));
    assertEquals(2, t.entryCount());
  }

  @Test
  void tocEntryCountRendersLowByteButIncludesEveryChild() {
    var ids = new ArrayList<ByteVector>();
    for (int i = 0; i < 257; i++) ids.add(new ByteVector("c"));
    var t = new TableOfContentsFrame(new ByteVector("t"), ids);
    var r = t.render();
    assertEquals(257, t.entryCount());
    assertEquals(1, r.at(13));
    assertEquals(10 + 4 + 514, r.size());
  }

  @Test
  void unknownAndEmptyEmbeddedFramesStopBeforeLaterKnownChildren() {
    for (ByteVector stop : List.of(wire("ZZZZ", new ByteVector("opaque")), wire("TIT2", new ByteVector()))) {
      var c = new ChapterFrame(new Header(), wire("CHAP", chapter().append(stop).append(leaf("later"))));
      assertTrue(c.embeddedFrameList().isEmpty());
    }
  }

  @Test
  void nativeFindersUseOriginalBucketAndFirstTopLevel() {
    var t = new Tag();
    var c = new ChapterFrame(new ByteVector("c"), 0, 0, 0, 0);
    t.addFrame(c);
    c.header().setFrameID(new ByteVector("ZZZZ"));
    assertSame(c, ChapterFrame.findByElementID(t, new ByteVector("c")));
    var a = new TableOfContentsFrame(new ByteVector("a"), new ArrayList<ByteVector>());
    var z = new TableOfContentsFrame(new ByteVector("z"), new ArrayList<ByteVector>());
    a.setIsTopLevel(true);
    z.setIsTopLevel(true);
    t.addFrame(a);
    t.addFrame(z);
    assertSame(a, TableOfContentsFrame.findTopLevel(t));
    assertSame(z, TableOfContentsFrame.findByElementID(t, new ByteVector("z")));
  }

  @Test
  void completeUpstreamParseChapterFrame() {
    var h = new Header();
    var f1 = new ChapterFrame(h, wire("CHAP", chapter()));
    assertEquals(new ByteVector("C"), f1.elementID());
    assertEquals(3, f1.startTime());
    assertEquals(5, f1.endTime());
    assertEquals(2, f1.startOffset());
    assertEquals(3, f1.endOffset());
    assertTrue(f1.embeddedFrameList().isEmpty());
    var f2 = new ChapterFrame(h, wire("CHAP", chapter().append(leaf("CH1"))));
    assertEquals(new ByteVector("C"), f2.elementID());
    assertEquals(3, f2.startTime());
    assertEquals(5, f2.endTime());
    assertEquals(2, f2.startOffset());
    assertEquals(3, f2.endOffset());
    assertEquals(1, f2.embeddedFrameList().size());
    assertEquals(1, f2.embeddedFrameList(new ByteVector("TIT2")).size());
    assertEquals("CH1", f2.embeddedFrameList().getFirst().toString());
  }

  @Test
  void completeUpstreamRenderChapterFrame() {
    var expected = wire("CHAP", chapter().append(leaf("CH1")));
    var f1 = new ChapterFrame(new Header(), new ByteVector("CHAP"));
    f1.setElementID(b("4300"));
    f1.setStartTime(3);
    f1.setEndTime(5);
    f1.setStartOffset(2);
    f1.setEndOffset(3);
    f1.addEmbeddedFrame(TagOwnerTest.title("CH1"));
    assertEquals(expected, f1.render());
    f1.setElementID(new ByteVector("C"));
    assertEquals(expected, f1.render());
    for (ByteVector id : List.of(b("4300"), b("4300"), new ByteVector("C"))) {
      var f = new ChapterFrame(id, 3, 5, 2, 3, List.of(TagOwnerTest.title("CH1")));
      assertEquals(expected, f.render());
      assertFalse(f.toString().isEmpty());
    }
    var f5 = new ChapterFrame(new ByteVector("C"), 3, 5, 2, 3);
    f5.addEmbeddedFrame(TagOwnerTest.title("CH1"));
    assertEquals(expected, f5.render());
  }

  @Test
  void completeUpstreamParseTableOfContentsFrame() {
    var f = new TableOfContentsFrame(new Header(), wire("CTOC", b("5400010243004400").append(leaf("TC1"))));
    assertEquals(new ByteVector("T"), f.elementID());
    assertFalse(f.isTopLevel());
    assertTrue(f.isOrdered());
    assertEquals(2, f.entryCount());
    assertEquals(new ByteVector("C"), f.childElements().get(0));
    assertEquals(new ByteVector("D"), f.childElements().get(1));
    assertEquals(1, f.embeddedFrameList().size());
    assertEquals(1, f.embeddedFrameList(new ByteVector("TIT2")).size());
    assertEquals("TC1", f.embeddedFrameList().getFirst().toString());
    f.removeChildElement(new ByteVector("E"));
    assertEquals(2, f.entryCount());
    f.removeChildElement(new ByteVector("C"));
    assertEquals(1, f.entryCount());
    assertEquals(new ByteVector("D"), f.childElements().getFirst());
    f.removeEmbeddedFrame(f.embeddedFrameList().getFirst());
    assertTrue(f.embeddedFrameList(new ByteVector("TIT2")).isEmpty());
  }

  @Test
  void completeUpstreamRenderTableOfContentsFrame() {
    var f = new TableOfContentsFrame(new Header(), new ByteVector("CTOC"));
    f.setElementID(new ByteVector("T"));
    f.setIsTopLevel(false);
    f.setIsOrdered(true);
    f.addChildElement(new ByteVector("C"));
    f.addChildElement(new ByteVector("D"));
    f.addEmbeddedFrame(TagOwnerTest.title("TC1"));
    assertEquals(wire("CTOC", b("5400010243004400").append(leaf("TC1"))), f.render());
  }
}
