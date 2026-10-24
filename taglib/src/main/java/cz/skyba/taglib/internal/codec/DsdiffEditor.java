// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.codec;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.internal.Binary;
import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.TextBytes;
import cz.skyba.taglib.toolkit.ByteVector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

final class DsdiffEditor {
  private static final class Chunk {
    final String id;
    long offset, size;
    int padding;

    Chunk(DsdCodec.Chunk c) {
      this(c.id(), c.offset(), c.size(), c.padding());
    }

    Chunk(String id, long offset, long size, int padding) {
      this.id = id;
      this.offset = offset;
      this.size = size;
      this.padding = padding;
    }

    long span() {
      return 12 + size + padding;
    }
  }

  private static final class ContainerState {
    final String id;
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

    ContainerState(String id) {
      this.id = id;
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
        children.get(i).offset = previous.offset + previous.span();
      }
    }
  }

  long size;
  final List<Chunk> roots = new ArrayList<>();
  final ContainerState properties = new ContainerState("PROP");
  final ContainerState diinContainer = new ContainerState("DIIN");
  final List<ContainerState> containers = List.of(properties, diinContainer);

  private enum Carrier {ROOT, PROP}

  private record Placement(String id, Carrier carrier) {
    Placement withId(String value) {
      return new Placement(value, carrier);
    }

    Placement withCarrier(Carrier value) {
      return new Placement(id, value);
    }
  }

  private Placement placement = new Placement("ID3 ", Carrier.ROOT);
  private OptionalInt duplicate = OptionalInt.empty();
  PropertyMap diin;

  DsdiffEditor(DsdCodec.Dff source) {
    size = source.declaredSize();
    for (var c : source.roots()) {
      roots.add(new Chunk(c));
    }
    for (var c : source.properties()) {
      properties.children.add(new Chunk(c));
    }
    for (var c : source.diin()) {
      diinContainer.children.add(new Chunk(c));
    }
    boolean hasId3 = false;
    for (Chunk c : roots) {
      if (c.id.equals("PROP")) {
        properties.attach(c);
      }
      if (c.id.equals("DIIN")) {
        diinContainer.attach(c);
      }
      if (c.id.equals("ID3 ") || c.id.equals("id3 ")) {
        placement = placement.withId(c.id);
        hasId3 = true;
        placement = placement.withCarrier(Carrier.ROOT);
      }
    }
    for (int i = 0; i < properties.children.size(); i++) {
      Chunk c = properties.children.get(i);
      if (c.id.equals("ID3 ") || c.id.equals("id3 ")) {
        if (hasId3) {
          duplicate = OptionalInt.of(i);
        } else {
          hasId3 = true;
          placement = placement.withCarrier(Carrier.PROP);
          placement = placement.withId(c.id);
        }
      }
    }
  }

  PropertyMap diinProperties(BinaryFile file) throws IOException {
    if (diin == null) {
      var values = new ArrayList<DsdCodec.Chunk>();
      for (Chunk c : diinContainer.children) {
        values.add(new DsdCodec.Chunk(c.id, c.offset, c.size, c.padding));
      }
      diin = DsdCodec.diinProperties(file, values, ReadOptions.DEFAULT);
    }
    return diin;
  }

  void save(BinaryFile f, byte[] tag, PropertyMap values) throws IOException {
    if (placement.carrier == Carrier.PROP) {
      setChild(f, properties, placement.id, tag);
    } else {
      setRoot(f, placement.id, tag);
    }
    setChild(f, diinContainer, "DITI", diinBytes(values.first("TITLE")));
    setChild(f, diinContainer, "DIAR", diinBytes(values.first("ARTIST")));
    diin = values;
    if (duplicate.isPresent()) {
      removeChild(f, properties, duplicate.getAsInt());
      duplicate = OptionalInt.empty();
    }
  }

  static byte[] diinBytes(String value) {
    if (value.isEmpty()) {
      return new byte[0];
    }
    byte[] raw = TextBytes.encode(value, ISO_8859_1);
    int count = 0;
    while (count < raw.length && raw[count] != 0) {
      count++;
    }
    byte[] out = new byte[4 + count];
    Binary.put32be(out, 0, value.length());
    System.arraycopy(raw, 0, out, 4, count);
    return out;
  }

  void number(BinaryFile f, long at, long value) {
    byte[] bytes = new byte[8];
    Binary.put64be(bytes, 0, value);
    f.stream().insert(new ByteVector(bytes), at, 8);
  }

  void render(BinaryFile f, Chunk c, byte[] data, long replace, int leading) throws IOException {
    byte[] body = new byte[leading + 12 + data.length + (data.length & 1)];
    System.arraycopy(Binary.bytes(c.id), 0, body, leading, 4);
    Binary.put64be(body, leading + 4, data.length);
    System.arraycopy(data, 0, body, leading + 12, data.length);
    f.stream().insert(new ByteVector(body), c.offset - 12, replace);
  }

  static int index(List<Chunk> list, String id) {
    for (int i = 0; i < list.size(); i++) {
      if (list.get(i).id.equals(id)) {
        return i;
      }
    }
    return -1;
  }

  void updateRoots(int start) {
    for (int i = start; i < roots.size(); i++) {
      if (i > 0) {
        roots.get(i).offset = roots.get(i - 1).offset + roots.get(i - 1).span();
      }
    }
    for (ContainerState container : containers) {
      if (container.parentIndex(roots) >= start && !container.children.isEmpty()) {
        container.updateOffsets(roots.get(container.parentIndex(roots)).offset + 12, 0);
      }
    }
  }

  void setRoot(BinaryFile f, String id, byte[] data) throws IOException {
    if (roots.isEmpty()) {
      return;
    }
    int i = index(roots, id);
    if (i >= 0) {
      Chunk c = roots.get(i);
      if (data.length == 0) {
        size -= c.span();
        number(f, 4, size);
        f.stream().removeBlock(c.offset - 12, c.span());
        roots.remove(i);
        for (ContainerState container : containers) {
          container.rootRemoved(c, i);
        }
        updateRoots(i);
        return;
      }
      size += ((data.length + 1L) & ~1L) - c.size - c.padding;
      number(f, 4, size);
      render(f, c, data, c.span(), 0);
      c.size = data.length;
      c.padding = data.length & 1;
      updateRoots(i + 1);
      return;
    }
    Chunk last = roots.getLast();
    long offset = last.offset + last.size + last.padding;
    int leading = (int) (offset & 1);
    size += leading + ((data.length + 1L) & ~1L) + 12;
    number(f, 4, size);
    Chunk c = new Chunk(id, offset + 12, data.length, data.length & 1);
    render(f, c, data, Math.max(0, f.stream().length() - offset), leading);
    roots.add(c);
  }

  void removeChild(BinaryFile f, ContainerState state, int i) {
    var list = state.children;
    Chunk c = list.get(i), container = roots.get(state.parentIndex(roots));
    size -= c.span();
    number(f, 4, size);
    container.size -= c.span();
    number(f, container.offset - 8, container.size);
    f.stream().removeBlock(c.offset - 12, c.span());
    list.remove(i);
    state.updateOffsets(c.offset, i);
    updateRoots(state.parentIndex(roots) + 1);
  }

  void setChild(BinaryFile f, ContainerState state, String id, byte[] data) throws IOException {
    var list = state.children;
    int i = index(list, id);
    if (i >= 0) {
      if (data.length == 0) {
        removeChild(f, state, i);
        return;
      }
      Chunk c = list.get(i), container = roots.get(state.parentIndex(roots));
      long delta = ((data.length + 1L) & ~1L) - c.size - c.padding;
      size += delta;
      number(f, 4, size);
      container.size += delta;
      number(f, container.offset - 8, container.size);
      render(f, c, data, c.span(), 0);
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
      offset = last.offset + last.size + last.padding;
    } else if (state == diinContainer) {
      if (state.parentIndex(roots) < 0) {
        setRoot(f, state.id, new byte[0]);
        if (!roots.isEmpty() && roots.getLast().id.equals("DIIN")) {
          state.attach(roots.getLast());
        }
      }
      if (state.parentIndex(roots) >= 0) {
        offset = roots.get(state.parentIndex(roots)).offset;
      }
    }
    if (offset == 0) {
      return;
    }
    Chunk container = roots.get(state.parentIndex(roots));
    int leading = (int) (offset & 1);
    long delta = leading + ((data.length + 1L) & ~1L) + 12;
    size += delta;
    number(f, 4, size);
    container.size += delta;
    number(f, container.offset - 8, container.size);
    long next =
      state.parentIndex(roots) + 1 < roots.size() ? roots.get(state.parentIndex(roots) + 1).offset - 12 : f.stream().length();
    Chunk c = new Chunk(id, offset + 12, data.length, data.length & 1);
    render(f, c, data, Math.max(0, next - offset), leading);
    updateRoots(state.parentIndex(roots) + 1);
    list.add(c);
  }
}
