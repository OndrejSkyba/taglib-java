package cz.skyba.taglib.matroska;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CollectionNotificationTest {
  @Test
  void attachmentCopiesAndNotificationsRemainAtOwnerBoundaries() {
    var notifications = new AtomicInteger();
    var source = new AttachedFile(new byte[]{1, 2}, "one", "binary", 9);
    var owner = new Attachments(List.of(source), notifications::incrementAndGet);
    source = new AttachedFile(new byte[]{3}, "other", "binary", 10);
    assertEquals("one", owner.attachedFileList().getFirst().fileName());
    assertEquals(0, notifications.get());
    owner.removeAttachedFile(99);
    assertEquals(0, notifications.get());
    var snapshot = owner.attachedFileList();
    owner.attachedFiles().clear();
    assertEquals(1, notifications.get());
    assertEquals("one", snapshot.getFirst().fileName());
    owner.clear();
    assertEquals(2, notifications.get());
  }

  @Test
  void chapterInsertionCopiesWrappersAndMissingRemovalDoesNotNotify() {
    var notifications = new AtomicInteger();
    var owner = new Chapters(List.of(), notifications::incrementAndGet);
    var edition = new ChapterEdition(List.of(), true, false, 17);
    owner.addChapterEdition(edition);
    edition = new ChapterEdition(List.of(), false, true, 18);
    assertEquals(17, owner.chapterEditionList().getFirst().uid());
    owner.removeChapterEdition(18);
    assertEquals(1, notifications.get());
    owner.removeChapterEdition(17);
    owner.clear();
    assertEquals(3, notifications.get());
  }

  @Test
  void simpleTagBatchesNotifyOnceIncludingEmptyBatches() {
    var notifications = new AtomicInteger();
    var owner = new Tag(List.of(), notifications::incrementAndGet);
    owner.addSimpleTags(List.of());
    assertEquals(1, notifications.get());
    var tag = new SimpleTag("CUSTOM", new SimpleTag.Text("value"), 30, "eng", true, 0, 0, 0, 0);
    owner.addSimpleTags(List.of(tag, tag));
    assertEquals(2, notifications.get());
    owner.removeSimpleTag(99);
    assertEquals(2, notifications.get());
    var snapshot = owner.simpleTagsList();
    owner.clearSimpleTags();
    assertEquals(3, notifications.get());
    assertEquals(2, snapshot.size());
    assertEquals("value", snapshot.getFirst().toString());
  }
}
