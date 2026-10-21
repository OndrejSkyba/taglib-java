// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.toolkit.ByteVector;

import java.util.*;

/// Native stable pointer-list/map storage with read-only live container views.
final class FrameCollection {
  private final NativeFrames frames = new NativeFrames();
  private final List<Frame> view = Collections.unmodifiableList(frames);
  private final NavigableMap<ByteVector, NativeFrames> buckets = new TreeMap<>();
  private final Map<ByteVector, List<Frame>> bucketViews = new TreeMap<>();
  private final Map<ByteVector, List<Frame>> mapView = Collections.unmodifiableMap(new LiveBucketMap());

  private final class LiveBucketMap extends AbstractMap<ByteVector, List<Frame>> {
    @Override
    public List<Frame> get(Object key) {
      return key instanceof ByteVector ? bucketViews.get(key) : null;
    }

    @Override
    public boolean containsKey(Object key) {
      return buckets.containsKey(key);
    }

    @Override
    public Set<Entry<ByteVector, List<Frame>>> entrySet() {
      return new BucketEntries();
    }
  }

  private final class BucketEntries extends AbstractSet<Map.Entry<ByteVector, List<Frame>>> {
    @Override
    public int size() {
      return buckets.size();
    }

    @Override
    public Iterator<Map.Entry<ByteVector, List<Frame>>> iterator() {
      return new BucketIterator();
    }
  }

  /// A native map iterator remains valid when a frame hook inserts another bucket.
  private final class BucketIterator implements Iterator<Map.Entry<ByteVector, List<Frame>>> {
    private final ByteVector first = buckets.isEmpty() ? null : buckets.firstKey();
    private ByteVector previous;

    private ByteVector nextKey() {
      return previous == null ? first : buckets.higherKey(previous);
    }

    public boolean hasNext() {
      return nextKey() != null;
    }

    public Map.Entry<ByteVector, List<Frame>> next() {
      ByteVector key = nextKey();
      if (key == null) {
        throw new NoSuchElementException();
      }
      previous = key;
      return new AbstractMap.SimpleImmutableEntry<>(new ByteVector(key), bucketViews.get(key));
    }
  }

  List<Frame> list() {
    return view;
  }

  Map<ByteVector, List<Frame>> map() {
    return mapView;
  }

  List<Frame> bucket(ByteVector id) {
    return bucketViews.getOrDefault(Objects.requireNonNull(id), List.of());
  }

  private NativeFrames mutableBucket(ByteVector id) {
    var existing = buckets.get(id);
    if (existing != null) return existing;
    ByteVector key = new ByteVector(id);
    var list = new NativeFrames();
    buckets.put(key, list);
    bucketViews.put(key, Collections.unmodifiableList(list));
    return list;
  }

  void add(Frame frame) {
    Objects.requireNonNull(frame);
    frames.append(frame);
    mutableBucket(frame.frameID()).append(frame);
  }

  void parseEmbedded(ByteVector data, int start, Header tagHeader, Frame owner) {
    long size = (data.size() - (long) start) & 0xffff_ffffL, offset = 0;
    if (size < owner.headerSize()) {
      return;
    }
    while (offset < size - owner.headerSize()) {
      long absolute = start + offset;
      Frame child = absolute >= data.size()
        ? FrameFactory.createEmbeddedFrame(new ByteVector(), 0, tagHeader)
        : FrameFactory.createEmbeddedFrame(data, (int) absolute, tagHeader);
      if (child == null || child.size() == 0 || child instanceof UnknownFrame) {
        return;
      }
      offset = (offset + child.size() + owner.headerSize()) & 0xffff_ffffL;
      add(child);
    }
  }

  void remove(Frame frame, boolean checked) {
    Objects.requireNonNull(frame);
    var mapped = mutableBucket(frame.frameID());
    if (!checked && (!frames.contains(frame) || !mapped.contains(frame))) {
      throw new IllegalStateException("Native removal requires membership in both the frame list and current-ID bucket");
    }
    frames.erase(frame);
    mapped.erase(frame);
  }

  void removeAll(ByteVector id, boolean checked) {
    // Explicit mutation retains native bucket bookkeeping; reads never create buckets.
    for (Frame frame : new ArrayList<>(mutableBucket(id))) {
      remove(frame, checked);
    }
  }

  /// Linked nodes preserve native iterators on append and removal of other entries.
  private static final class NativeFrames extends AbstractList<Frame> {
    private static final class Node {
      final Frame frame;
      Node previous, next, sameNext;
      boolean retained = true;

      Node(Frame frame) {
        this.frame = frame;
      }
    }

    private final Node end = new Node(null);
    private int count;
    private IdentityHashMap<Frame, Node> firstByFrame, lastByFrame;

    private void index(Node node) {
      Node previous = lastByFrame.put(node.frame, node);
      if (previous == null) firstByFrame.put(node.frame, node);
      else previous.sameNext = node;
    }

    private void ensureIndex() {
      if (firstByFrame != null) return;
      firstByFrame = new IdentityHashMap<>();
      lastByFrame = new IdentityHashMap<>();
      for (Node node = end.next; node != end; node = node.next) index(node);
    }

    NativeFrames() {
      end.previous = end;
      end.next = end;
    }

    public int size() {
      return count;
    }

    public Frame get(int index) {
      Objects.checkIndex(index, count);
      Node node = end.next;
      for (int i = 0; i < index; i++) {
        node = node.next;
      }
      return node.frame;
    }

    @Override
    public int indexOf(Object frame) {
      int index = 0;
      for (Node node = end.next; node != end; node = node.next, index++) {
        if (node.frame == frame) {
          return index;
        }
      }
      return -1;
    }

    @Override
    public int lastIndexOf(Object frame) {
      int index = count - 1;
      for (Node node = end.previous; node != end; node = node.previous, index--) {
        if (node.frame == frame) {
          return index;
        }
      }
      return -1;
    }

    @Override
    public boolean contains(Object frame) {
      ensureIndex();
      return frame instanceof Frame typed && firstByFrame.containsKey(typed);
    }

    void append(Frame frame) {
      var node = new Node(frame);
      node.previous = end.previous;
      node.next = end;
      end.previous.next = node;
      end.previous = node;
      count++;
      if (firstByFrame != null) index(node);
    }

    void erase(Frame frame) {
      ensureIndex();
      Node node = firstByFrame.get(frame);
      if (node == null) return;
      node.previous.next = node.next;
      node.next.previous = node.previous;
      node.retained = false;
      count--;
      if (node.sameNext == null) {
        firstByFrame.remove(frame);
        lastByFrame.remove(frame);
      } else {
        firstByFrame.put(frame, node.sameNext);
      }
    }

    @Override
    public Iterator<Frame> iterator() {
      return new Iterator<>() {
        final Node first = end.next;
        Node previous;

        private Node nextNode() {
          if (previous != null && !previous.retained) {
            throw new IllegalStateException("Native iterator's current frame entry was removed");
          }
          Node node = previous == null ? first : previous.next;
          if (!node.retained) {
            throw new IllegalStateException("Native iterator's frame entry was removed");
          }
          return node;
        }

        public boolean hasNext() {
          return nextNode() != end;
        }

        public Frame next() {
          Node node = nextNode();
          if (node == end) {
            throw new NoSuchElementException();
          }
          previous = node;
          return node.frame;
        }
      };
    }
  }
}
