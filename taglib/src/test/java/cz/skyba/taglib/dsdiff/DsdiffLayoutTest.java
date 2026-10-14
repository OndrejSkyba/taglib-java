package cz.skyba.taglib.dsdiff;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DsdiffLayoutTest {
  private static final class Target implements DsdiffLayout.Target {
    final List<String> calls = new ArrayList<>();
    long length = 78;

    public void number(long at, long value) {
      calls.add("number:" + at + ":" + value);
    }

    public void remove(long at, long count) {
      calls.add("remove:" + at + ":" + count);
      length -= count;
    }

    public void render(DsdiffLayout.Chunk chunk, byte[] data, long replace, int leading, int renderedSize) {
      calls.add("render:" + chunk.id + ":" + replace + ":" + leading);
    }

    public long length() {
      return length;
    }

    public void clearModern() {
      calls.add("modern");
    }

    public void clearDiin() {
      calls.add("diin");
    }

    public void debug(String message) {
      calls.add(message);
    }
  }

  private static DsdiffLayout layout(Target target) {
    var layout = new DsdiffLayout(target);
    layout.size = 66;
    layout.roots.add(new DsdiffLayout.Chunk("FVER", 28, 4, 0));
    layout.roots.add(new DsdiffLayout.Chunk("ID3 ", 44, 2, 0));
    var prop = new DsdiffLayout.Chunk("PROP", 58, 20, 0);
    layout.roots.add(prop);
    layout.properties.attach(prop);
    layout.properties.children.add(new DsdiffLayout.Chunk("FS  ", 74, 4, 0));
    return layout;
  }

  @Test
  void preflightChangesOnlyItsOwnGraphAndNeverCallsLiveIo() {
    var target = new Target();
    var live = layout(target);
    var plan = live.snapshot(() -> target.length, true);
    plan.stripImpl(DsdiffFile.ID3v2);
    assertTrue(target.calls.isEmpty());
    assertEquals(3, live.roots.size());
    assertEquals(58, live.properties.parentIndex(live.roots) >= 0
      ? live.roots.get(live.properties.parentIndex(live.roots)).offset : -1);
    assertEquals(74, live.properties.children.getFirst().offset);
    assertEquals(2, plan.roots.size());
    assertEquals(52, plan.size);
    assertEquals(44, plan.roots.getLast().offset);
    assertEquals(56, plan.properties.children.getFirst().offset);
  }

  @Test
  void liveRemovalKeepsHeaderBeforeRemovalAndTagResetAfterPhysicalUpdates() {
    var target = new Target();
    var live = layout(target);
    live.stripImpl(DsdiffFile.ID3v2);
    assertEquals(List.of("number:4:52", "remove:32:14", "modern"), target.calls);
    assertEquals(1, live.properties.parentIndex(live.roots));
    assertEquals(56, live.properties.children.getFirst().offset);
  }

  @Test
  void snapshotCopiesBeforeTheOwnersLengthCallback() {
    var target = new Target();
    var live = layout(target);
    var plan = live.snapshot(() -> {
      live.roots.clear();
      return 78;
    }, true);
    assertTrue(live.roots.isEmpty());
    assertEquals(3, plan.roots.size());
    assertEquals(2, plan.properties.parentIndex(plan.roots));
    plan.setRoot("ID3 ", new byte[0]);
    assertEquals(44, plan.roots.getLast().offset);
    assertTrue(target.calls.isEmpty());
  }

  @Test
  void removedContainerRetainsItsFormerPositionalSlot() {
    var target = new Target();
    var live = layout(target);
    var removed = live.roots.get(1);
    live.diinContainer.attach(removed);
    live.setRoot("ID3 ", new byte[0]);
    assertEquals(1, live.diinContainer.parentIndex(live.roots));
    assertEquals("PROP", live.roots.get(live.diinContainer.parentIndex(live.roots)).id);
    var snapshot = live.snapshot(() -> target.length, true);
    assertEquals(1, snapshot.diinContainer.parentIndex(snapshot.roots));
  }
}
