// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.dsdiff;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.LongSupplier;

import static cz.skyba.taglib.dsdiff.DsdiffFile.DIIN;
import static cz.skyba.taglib.dsdiff.DsdiffFile.ID3v2;

/**
 * Physical layout transitions shared by live writes and detached preflight simulations.
 */
final class DsdiffLayout {
  static final class InvalidPlan extends RuntimeException {
    InvalidPlan() {
      super("DSDIFF layout has no representable native write plan");
    }
  }

  enum Carrier {ROOT, PROP}

  record Placement(String id, Carrier carrier) {
    Placement withId(String value) {
      return new Placement(value, carrier);
    }

    Placement withCarrier(Carrier value) {
      return new Placement(id, value);
    }

    boolean inProperties() {
      return carrier == Carrier.PROP;
    }
  }

  interface Target {
    void number(long at, long value);

    void remove(long start, long count);

    void render(Chunk chunk, byte[] data, long replace, int leading, int renderedSize);

    long length();

    void clearModern();

    void clearDiin();

    void debug(String message);
  }

  static final class Chunk {
    final String id;
    long offset, size;
    int padding;

    Chunk(String id, long offset, long size, int padding) {
      this.id = id;
      this.offset = offset;
      this.size = size;
      this.padding = padding;
    }

    long span() {
      return Math.addExact(Math.addExact(12, size), padding);
    }
  }

  static final class ContainerState {
    final String id;
    final int prefixSize;
    final List<Chunk> children = new ArrayList<>();
    private Chunk parent;
    private int detachedIndex = -1;

    void attach(Chunk root) {
      parent = root;
      detachedIndex = -1;
    }

    int parentIndex(List<Chunk> roots) {
      return parent == null ? detachedIndex : roots.indexOf(parent);
    }

    void rootRemoved(Chunk root, int index) {
      if (parent == root) {
        parent = null;
        detachedIndex = index;
      } else if (parent == null && detachedIndex > index) {
        detachedIndex--;
      }
    }

    void copyParent(ContainerState source, IdentityHashMap<Chunk, Chunk> copies) {
      parent = source.parent == null ? null : copies.get(source.parent);
      detachedIndex = source.detachedIndex;
    }

    ContainerState(String id, int prefixSize) {
      this.id = id;
      this.prefixSize = prefixSize;
    }

    void updateOffsets(long firstOffset, int start) {
      if (children.isEmpty()) {
        return;
      }
      if (start == 0) {
        children.getFirst().offset = firstOffset;
        start = 1;
      }
      for (int i = start; i < children.size(); i++) {
        Chunk previous = children.get(i - 1);
        children.get(i).offset = Math.addExact(previous.offset, previous.span());
      }
    }
  }

  long size;
  final List<Chunk> roots;
  final ContainerState properties;
  final ContainerState diinContainer;
  final List<ContainerState> containers;
  Placement placement = new Placement("ID3 ", Carrier.ROOT);
  boolean hasModern, hasDiin;
  OptionalInt duplicate = OptionalInt.empty();
  private final Target target;

  DsdiffLayout(Target target) {
    this.target = target;
    roots = new ArrayList<>();
    properties = new ContainerState("PROP", 4);
    diinContainer = new ContainerState("DIIN", 0);
    containers = List.of(properties, diinContainer);
  }

  DsdiffLayout snapshot(LongSupplier length, boolean memory) {
    // Copy the graph before calling the owner's virtual length(), as the former File copy did.
    var copies = new IdentityHashMap<Chunk, Chunk>();
    var result = new DsdiffLayout(null);
    result.size = size;
    result.placement = placement;
    result.hasModern = hasModern;
    result.hasDiin = hasDiin;
    result.duplicate = duplicate;
    for (var chunk : roots) {
      var copy = copy(chunk);
      copies.put(chunk, copy);
      result.roots.add(copy);
    }
    for (int i = 0; i < containers.size(); i++) {
      var source = containers.get(i);
      var destination = result.containers.get(i);
      destination.copyParent(source, copies);
      for (var chunk : source.children) {
        destination.children.add(copy(chunk));
      }
    }
    return new DsdiffLayout(result, new PlanningTarget(length.getAsLong(), memory));
  }

  private DsdiffLayout(DsdiffLayout source, Target target) {
    this.target = target;
    size = source.size;
    placement = source.placement;
    hasModern = source.hasModern;
    hasDiin = source.hasDiin;
    duplicate = source.duplicate;
    roots = source.roots;
    properties = source.properties;
    diinContainer = source.diinContainer;
    containers = source.containers;
  }

  private static Chunk copy(Chunk chunk) {
    return new Chunk(chunk.id, chunk.offset, chunk.size, chunk.padding);
  }

  private void renderChunk(Chunk chunk, byte[] data, long replace, int leading) {
    int renderedSize = Math.addExact(Math.addExact(leading + 12, data.length), data.length & 1);
    target.render(chunk, data, replace, leading, renderedSize);
  }

  void stripImpl(int tags) {
    if ((tags & ID3v2) != 0) {
      setRootIfPresent("ID3 ");
      setRootIfPresent("id3 ");
      removeChildIfPresent(properties, "ID3 ");
      removeChildIfPresent(properties, "id3 ");
      hasModern = false;
      target.clearModern();
      duplicate = OptionalInt.empty();
      placement = placement.withCarrier(Carrier.ROOT);
      placement = placement.withId("ID3 ");
    }
    if ((tags & DIIN) != 0) {
      removeChildIfPresent(diinContainer, "DITI");
      removeChildIfPresent(diinContainer, "DIAR");
      if (diinContainer.children.isEmpty()) {
        setRootIfPresent("DIIN");
      }
      hasDiin = false;
      target.clearDiin();
    }
  }

  private void setRootIfPresent(String id) {
    if (index(roots, id) >= 0) {
      setRoot(id, new byte[0]);
    }
  }

  private void removeChildIfPresent(ContainerState container, String id) {
    int i = index(container.children, id);
    if (i >= 0) {
      removeChild(container, i);
    }
  }

  private static int index(List<Chunk> list, String id) {
    for (int i = 0; i < list.size(); i++) {
      if (list.get(i).id.equals(id)) {
        return i;
      }
    }
    return -1;
  }

  private void updateRoots(int start) {
    // Native uses chunks[i - 1]; removing the first root with a successor is undefined.
    if (start == 0 && !roots.isEmpty()) {
      throw new InvalidPlan();
    }
    for (int i = start; i < roots.size(); i++) {
      roots.get(i).offset = Math.addExact(roots.get(i - 1).offset, roots.get(i - 1).span());
    }
    for (ContainerState container : containers) {
      if (container.parentIndex(roots) >= start && !container.children.isEmpty()) {
        container.updateOffsets(Math.addExact(roots.get(container.parentIndex(roots)).offset, 12), 0);
      }
    }
  }

  void setRoot(String id, byte[] data) {
    if (roots.isEmpty()) {
      target.debug("Cannot set root chunk '" + id + "': no valid chunks are available.");
      return;
    }
    int i = index(roots, id);
    if (i >= 0) {
      Chunk c = roots.get(i);
      if (data.length == 0) {
        size -= c.span();
        target.number(4, size);
        target.remove(c.offset - 12, c.span());
        roots.remove(i);
        for (ContainerState container : containers) {
          container.rootRemoved(c, i);
        }
        updateRoots(i);
        return;
      }
      size += ((data.length + 1L) & ~1L) - c.size - c.padding;
      target.number(4, size);
      renderChunk(c, data, c.span(), 0);
      c.size = data.length;
      c.padding = data.length & 1;
      updateRoots(i + 1);
      return;
    }
    Chunk last = roots.getLast();
    long offset = Math.addExact(last.offset, Math.addExact(last.size, last.padding));
    int leading = (int) (offset & 1);
    size += leading + ((data.length + 1L) & ~1L) + 12;
    target.number(4, size);
    Chunk c = new Chunk(id, Math.addExact(offset, 12), data.length, data.length & 1);
    renderChunk(c, data, Math.max(0, target.length() - offset), leading);
    roots.add(c);
  }

  void removeChild(ContainerState state, int i) {
    var list = state.children;
    Chunk c = list.get(i), container = roots.get(state.parentIndex(roots));
    size -= c.span();
    target.number(4, size);
    container.size -= c.span();
    target.number(container.offset - 8, container.size);
    target.remove(c.offset - 12, c.span());
    list.remove(i);
    state.updateOffsets(c.offset, i);
    updateRoots(state.parentIndex(roots) + 1);
  }

  void setChild(ContainerState state, String id, byte[] data) {
    var list = state.children;
    int i = index(list, id);
    if (i >= 0) {
      if (data.length == 0) {
        removeChild(state, i);
        return;
      }
      Chunk c = list.get(i), container = roots.get(state.parentIndex(roots));
      long delta = ((data.length + 1L) & ~1L) - c.size - c.padding;
      size += delta;
      target.number(4, size);
      container.size += delta;
      target.number(container.offset - 8, container.size);
      renderChunk(c, data, c.span(), 0);
      c.size = data.length;
      c.padding = data.length & 1;
      state.updateOffsets(0, i + 1);
      updateRoots(state.parentIndex(roots) + 1);
      return;
    }
    if (data.length == 0) {
      return;
    }
    long offset = 0;
    if (!list.isEmpty()) {
      Chunk last = list.getLast();
      offset = Math.addExact(last.offset, Math.addExact(last.size, last.padding));
    } else if (state == diinContainer) {
      if (state.parentIndex(roots) < 0) {
        setRoot(state.id, new byte[0]);
        if (!roots.isEmpty() && roots.getLast().id.equals("DIIN")) {
          state.attach(roots.getLast());
          hasDiin = true;
        }
      }
      if (state.parentIndex(roots) >= 0) {
        offset = roots.get(state.parentIndex(roots)).offset;
      }
    }
    if (offset == 0) {
      target.debug("Cannot set child chunk data: no valid chunks are available.");
      return;
    }
    Chunk container = roots.get(state.parentIndex(roots));
    int leading = (int) (offset & 1);
    long delta = leading + ((data.length + 1L) & ~1L) + 12;
    size += delta;
    target.number(4, size);
    container.size += delta;
    target.number(container.offset - 8, container.size);
    long next =
      state.parentIndex(roots) + 1 < roots.size() ? roots.get(state.parentIndex(roots) + 1).offset - 12 : target.length();
    Chunk c = new Chunk(id, Math.addExact(offset, 12), data.length, data.length & 1);
    renderChunk(c, data, Math.max(0, next - offset), leading);
    updateRoots(state.parentIndex(roots) + 1);
    list.add(c);
  }

  private static final class PlanningTarget implements Target {
    private long length;
    private final boolean memory;

    PlanningTarget(long length, boolean memory) {
      this.length = length;
      this.memory = memory;
      if (length < 0) {
        throw new InvalidPlan();
      }
    }

    private void insert(long start, long replace, long rendered) {
      if (start < 0 || replace < 0 || rendered < 0) {
        throw new InvalidPlan();
      }
      long end = Math.addExact(start, replace), writeEnd = Math.addExact(start, rendered);
      long difference = rendered - replace;
      if (memory && difference > 0 && end > length) {
        throw new InvalidPlan();
      }
      if (difference < 0) {
        remove(Math.addExact(start, rendered), -difference);
      } else if (difference > 0) {
        length = Math.addExact(length, difference);
      }
      length = Math.max(length, writeEnd);
      checkLength();
    }

    public void number(long at, long value) {
      insert(at, 8, 8);
    }

    public void remove(long start, long count) {
      if (start < 0 || count < 0) {
        throw new InvalidPlan();
      }
      long end = Math.addExact(start, count);
      length = end < length ? Math.subtractExact(length, count) : start;
      checkLength();
    }

    private void checkLength() {
      if (length < 0 || memory && length > Integer.MAX_VALUE) {
        throw new InvalidPlan();
      }
    }

    public void render(Chunk chunk, byte[] data, long replace, int leading, int renderedSize) {
      insert(chunk.offset - 12, replace, renderedSize);
    }

    public long length() {
      return length;
    }

    public void clearModern() {
    }

    public void clearDiin() {
    }

    public void debug(String message) {
    }
  }
}
