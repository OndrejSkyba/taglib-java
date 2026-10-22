package cz.skyba.taglib.matroska;

import cz.skyba.taglib.toolkit.ByteVector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MatroskaValueTest {
  @Test
  void simpleTagCopiesAndStoredValuesDetachBinaryExports() {
    var a = new SimpleTag("NAME", new ByteVector(new byte[]{0, -1}), SimpleTag.TargetTypeValue.Collection, "jpn", false, -1, 2, 3, 4);
    var copy = new SimpleTag(a);
    var t = new Tag();
    t.addSimpleTag(a);
    assertNotSame(a, copy);
    assertEquals(a, copy);
    copy.toByteVector().clear();
    assertEquals("NAME", a.name());
    assertEquals("NAME", copy.name());
    assertEquals(a, t.simpleTagsList().getFirst());
    var view = t.simpleTagsList().getFirst();
    view.toByteVector().clear();
    assertEquals(a, t.simpleTagsList().getFirst());
    assertThrows(UnsupportedOperationException.class, () -> t.simpleTagsList().clear());
    assertEquals("", a.toString());
    assertEquals(new ByteVector(new byte[]{0, -1}), a.toByteVector());
    assertEquals(SimpleTag.ValueType.BinaryType, a.type());
    assertEquals(-1, a.trackUid());
  }

  @Test
  void nestedChapterCopiesRetainOriginalDisplays() {
    var d = new Chapter.Display("First", "eng");
    var displays = new ArrayList<>(List.of(d));
    var c = new Chapter(0, -1, displays, -1, true);
    var copy = new Chapter(c);
    displays.clear();
    assertEquals(c, copy);
    assertEquals("First", c.displayList().getFirst().string());
    var chapters = new ArrayList<>(List.of(c));
    var e = new ChapterEdition(chapters, true, true, -1);
    chapters.clear();
    assertEquals(0, e.chapterList().getFirst().timeStart());
    var ch = new Chapters();
    ch.addChapterEdition(e);
    assertEquals(-1, ch.chapterEditionList().getFirst().uid());
    var view = ch.chapterEditionList().getFirst().chapterList().getFirst();
    assertThrows(UnsupportedOperationException.class, () -> view.displayList().clear());
    assertEquals(0, ch.chapterEditionList().getFirst().chapterList().getFirst().timeStart());
  }

  @Test
  void attachmentCopiesAndViewsOwnPayloads() {
    byte[] data = {0, -1};
    var a = new AttachedFile(data, "font.ttf", "font/ttf", -1, "Desc");
    data[0] = 8;
    var t = new Attachments();
    t.addAttachedFile(a);
    var copy = new AttachedFile(a);
    copy.data()[0] = 9;
    assertEquals(a, copy);
    var stored = t.attachedFileList().getFirst();
    byte[] bytes = stored.data();
    bytes[0] = 7;
    assertArrayEquals(new byte[]{0, -1}, stored.data());
    assertEquals(-1, stored.uid());
    assertEquals("font.ttf", t.attachedFileList().getFirst().fileName());
    assertThrows(UnsupportedOperationException.class, () -> t.attachedFileList().clear());

    byte[] loaded = {3, 4};
    a.setLoadedData(loaded);
    loaded[0] = 0;
    assertArrayEquals(new byte[]{3, 4}, a.data());
    assertArrayEquals(new byte[]{0, -1}, copy.data());

    var vector = new ByteVector(new byte[]{5, 6});
    var fromVector = new AttachedFile(vector, "cover.jpg", "image/jpeg", 7, "Cover");
    vector.set(0, (byte) 0);
    assertArrayEquals(new byte[]{5, 6}, fromVector.data());
  }

  @Test
  void removalUsesFirstMatchingUidAndCompleteScope() {
    var chapters = new Chapters();
    chapters.addChapterEdition(new ChapterEdition(List.of(), true, false, 7));
    chapters.addChapterEdition(new ChapterEdition(List.of(), false, true, 7));
    chapters.removeChapterEdition(7);
    assertTrue(chapters.chapterEditionList().getFirst().isOrdered());
    var attachments = new Attachments(List.of(new AttachedFile(new byte[0], "first", "mime", 7), new AttachedFile(new byte[0], "second", "mime", 7)));
    attachments.removeAttachedFile(7);
    assertEquals("second", attachments.attachedFileList().getFirst().fileName());
    var t = new Tag();
    t.addSimpleTag(new SimpleTag("N", "first", SimpleTag.TargetTypeValue.Track, "", true, 1, 2, 3, 4));
    t.addSimpleTag(new SimpleTag("N", "second", SimpleTag.TargetTypeValue.Track, "", true, 1, 2, 3, 4));
    t.removeSimpleTag("N", SimpleTag.TargetTypeValue.Track, 1, 2, 3, 4);
    assertEquals("second", t.simpleTagsList().getFirst().toString());
    t.removeSimpleTag(-1);
    assertEquals(1, t.simpleTagsList().size());
    t.insertSimpleTag(-1, new SimpleTag("LAST", "last"));
    assertEquals("LAST", t.simpleTagsList().getLast().name());
  }

  @Test
  void commonUnsignedNumbersRetainNativeSignedText() {
    long[] values = {0, 1, 2147483647L, 2147483648L, 4294967295L};
    String[] texts = {"", "1", "2147483647", "-2147483648", "-1"};
    long[] getters = {0, 1, 2147483647L, 0, 0};
    for (int i = 0; i < values.length; i++) {
      var tag = new Tag();
      tag.setYear(values[i]);
      tag.setTrack(values[i]);
      assertEquals(getters[i], tag.year());
      assertEquals(getters[i], tag.track());
      assertEquals(texts[i].isEmpty() ? List.of() : List.of(texts[i]), tag.properties().get("DATE"));
      assertEquals(texts[i].isEmpty() ? List.of() : List.of(texts[i]), tag.properties().get("TRACKNUMBER"));
      if (values[i] == 0) assertTrue(tag.isEmpty());
    }
  }
}
