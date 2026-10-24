// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.matroska;

import cz.skyba.taglib.internal.MatroskaWire;
import cz.skyba.taglib.internal.MetadataLimitException;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.IOStream;

import java.util.*;

/// A mutable EBML element with explicit rendering, storage writes and size-change notifications.
/// Element IDs retain their low 32 bits. Listeners are notified in registration order.
/// Call [#render()] before [#write(IOStream)] to include pending changes in the written data.
public abstract class Element {
  private long id, offset, size, appendOffset, voidOffset, voidSize;
  private ByteVector data = new ByteVector();
  private boolean needsRender = true, last = true, trailing;
  private WriteStyle style = WriteStyle.Compact;
  private final ArrayList<Element> listeners = new ArrayList<>();
  private static final ThreadLocal<Set<Element>> EMITTING = ThreadLocal.withInitial(
    () -> Collections.newSetFromMap(new IdentityHashMap<>()));

  /// Creates an element descriptor with the low 32 bits of its identifier.
  ///
  /// @param id the element ID
  public Element(long id) {
    setID(id);
  }

  public final long size() {
    return size;
  }

  public final long offset() {
    return offset;
  }

  public final long id() {
    return id;
  }

  public final void setOffset(long value) {
    offset = value;
  }

  /// Adjusts the stored byte offset without writing storage.
  ///
  /// @param delta the signed byte adjustment
  /// @throws ArithmeticException if the offset overflows
  public final void adjustOffset(long delta) {
    offset = Math.addExact(offset, delta);
  }

  public final void setSize(long value) {
    size = value;
  }

  public final void setID(long value) {
    id = value & 0xffff_ffffL;
  }

  /// Copies serialized element bytes into the stored serialized data.
  ///
  /// @param value the encoded bytes
  public final void setData(ByteVector value) {
    data = new ByteVector(value);
  }

  /// Returns an independent copy of the stored serialized element bytes.
  ///
  /// @return copied encoded bytes
  public final ByteVector data() {
    return new ByteVector(data);
  }

  /// Returns the stored serialized byte count.
  ///
  /// @return the byte count
  public final int dataSize() {
    return data.size();
  }

  public final void setNeedsRender(boolean value) {
    needsRender = value;
  }

  public final boolean needsRender() {
    return needsRender;
  }

  /// Removes all registered size listeners. An active notification uses its existing listener snapshot.
  public final void clearSizeListeners() {
    listeners.clear();
  }

  /// Appends a listener in notification order without copying it.
  ///
  /// @param value the listener to retain
  public final void addSizeListener(Element value) {
    listeners.add(Objects.requireNonNull(value));
  }

  /// Appends listeners in list order without copying them.
  ///
  /// @param values the listeners to retain
  public final void addSizeListeners(List<? extends Element> values) {
    values.forEach(this::addSizeListener);
  }

  public final void setWriteStyle(WriteStyle value) {
    style = Objects.requireNonNull(value);
  }

  public final WriteStyle writeStyle() {
    return style;
  }

  public final void setIsLastElement(boolean value) {
    last = value;
  }

  public final void setAppendOffset(long value) {
    appendOffset = value;
  }

  public final void setIsTrailingInSegment(boolean value) {
    trailing = value;
  }

  public final boolean isTrailingInSegment() {
    return trailing;
  }

  public final boolean wasMoved() {
    return voidOffset != 0 || voidSize != 0;
  }

  protected final long sizeRenderedOrWritten() {
    return data.isEmpty() ? size : data.size();
  }

  /// Builds the serialized bytes for this element without writing storage.
  ///
  /// @return the rendered element bytes
  protected abstract ByteVector renderInternal();

  /// Renders pending bytes and notifies listeners when the encoded size changes.
  /// The pending-render flag and some layout state are updated before listener callbacks;
  /// rejection can leave partial in-memory changes. No storage is written.
  ///
  /// @return true if no rendering is needed or all listeners accept the change; false on listener rejection
  public boolean render() {
    if (!needsRender) {
      return true;
    }
    long before = sizeRenderedOrWritten();
    ByteVector rendered = Objects.requireNonNull(renderInternal());
    needsRender = false;
    if (rendered.size() != before) {
      if (style == WriteStyle.AvoidInsert && !last && rendered.size() > before && before > 0) {
        voidOffset = offset;
        voidSize = before;
        offset = appendOffset;
        if (!emitSizeChanged(rendered.size())) {
          return false;
        }
        appendOffset = Math.addExact(appendOffset, rendered.size());
      } else if (!emitSizeChanged(rendered.size() - before)) {
        return false;
      }
    }
    setData(rendered);
    return true;
  }

  /// Notifies a snapshot of registered listeners in order. Notification stops at the first rejection.
  ///
  /// @param delta the signed byte-size change
  /// @return whether every listener accepted the change
  /// @throws IllegalStateException if notification is cyclic or exceeds the nesting limit
  public final boolean emitSizeChanged(long delta) {
    Set<Element> active = EMITTING.get();
    if (active.size() >= 64 || !active.add(this)) {
      throw new IllegalStateException("Cyclic or excessive Matroska size notification");
    }
    try {
      for (Element listener : List.copyOf(listeners)) {
        if (!listener.sizeChanged(this, delta)) {
          return false;
        }
      }
      return true;
    } finally {
      active.remove(this);
      if (active.isEmpty()) {
        EMITTING.remove();
      }
    }
  }

  /// Handles another element's size change by adjusting this offset when the caller precedes
  /// this element and has a different ID. Subclasses can extend or reject the update.
  ///
  /// @param caller the element whose size changed
  /// @param delta  the signed byte adjustment
  /// @return whether the change is accepted
  public boolean sizeChanged(Element caller, long delta) {
    if (caller.offset() <= offset && caller.id() != id) {
      adjustOffset(delta);
    }
    return true;
  }

  // Preflight the complete memory edit plan before any physical insertion.
  final void validateAllocation(int limit) {
    if (voidSize > limit || voidSize > Integer.MAX_VALUE - 8L) {
      throw new MetadataLimitException("Matroska retained Void exceeds allocation limit");
    }
  }

  final long validatedMemoryLength(long available) {
    if (voidSize > 0) {
      available = validatedInsertionLength(available, voidOffset, voidSize, MatroskaWire.voidLength(voidSize));
    }
    return validatedInsertionLength(available, offset, voidSize > 0 ? 0 : size, data.size());
  }

  private static long validatedInsertionLength(long available, long offset, long replace, long size) {
    if (size > replace && (offset < 0 || offset > available || replace > available - offset)) {
      throw new IllegalArgumentException(
        "Unsafe cached Matroska memory insertion span: offset=" + offset + ", replace=" + replace + ", size=" + size
          + ", length="
          + available);
    }
    long end = Math.addExact(offset, size);
    if (size < replace) {
      available = Math.addExact(offset, replace) < available ? Math.addExact(available, size - replace) : end;
    } else if (size > replace) {
      available = Math.addExact(available, size - replace);
    }
    return Math.max(available, end);
  }

  /// Writes stored rendered bytes immediately at the stored offset, filling any abandoned span
  /// with an EBML Void element. The stored size is updated and the target remains open.
  /// This operation does not render pending changes; call [#render()] first.
  ///
  /// @param file the borrowed storage target
  /// @throws java.io.UncheckedIOException if storage access fails
  public void write(MatroskaFile file) {
    write(file.metadataStream());
  }

  /// Writes stored rendered bytes immediately at the stored offset, filling any abandoned span
  /// with an EBML Void element. The stored size is updated and the target remains open.
  /// This operation does not render pending changes; call [#render()] first.
  ///
  /// @param file the borrowed storage target
  /// @throws java.io.UncheckedIOException if storage access fails
  public void write(IOStream file) {
    if (voidSize > 0) {
      file.insert(new ByteVector(MatroskaWire.voidElement(voidSize)), voidOffset, voidSize);
      voidOffset = 0;
      size = 0;
      voidSize = 0;
    }
    file.insert(data, offset, size);
    size = data.size();
  }
}
