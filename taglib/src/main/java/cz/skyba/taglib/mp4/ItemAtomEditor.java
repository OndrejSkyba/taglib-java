// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.mp4;

import cz.skyba.taglib.internal.Mp4Save;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.IOStream;

import java.util.List;

/// In-place item atom replacement, retained padding, and parent/offset updates.
final class ItemAtomEditor {
  private final IOStream file;
  private final Atoms atoms;

  ItemAtomEditor(IOStream file, Atoms atoms) {
    this.file = file;
    this.atoms = atoms;
  }

  static ByteVector padding(ByteVector data, int length) {
    if (length == -1) {
      length = (int) (((data.size() + 1023L) & ~1023L) - data.size());
    }
    return Mp4Save.atom("free", new ByteVector(length, (byte) 1));
  }


  void saveNew(ByteVector data) {
    List<Atom> path = atoms.path("moov", "udta");
    if (path.size() != 2) {
      path = atoms.path("moov");
      data = Mp4Save.atom("udta", data);
    }
    if (path.isEmpty()) {
      throw new IllegalStateException("MP4 tag has no movie owner");
    }
    long offset = path.getLast().offset() + 8;
    file.insert(data, offset, 0);
    Mp4Save.parents(file, path, data.size(), 0);
    Mp4Save.offsets(file, atoms, data.size(), offset);
    file.seek(offset);
    path.getLast().prependChild(new Atom(file));
  }

  void saveExisting(ByteVector data, List<Atom> path) {
    Atom target = path.getLast(), parent = path.get(path.size() - 2);
    long offset = target.offset(), length = target.length();
    int index = parent.children().indexOf(target);
    if (index < 0) {
      throw new IllegalStateException("Detached MP4 atom");
    }
    if (index > 0) {
      Atom previous = parent.children().get(index - 1);
      if (previous.name().equals(new ByteVector("free"))) {
        offset = previous.offset();
        length = Math.addExact(length, previous.length());
      }
    }
    if (index + 1 < parent.children().size()) {
      Atom next = parent.children().get(index + 1);
      if (next.name().equals(new ByteVector("free"))) {
        length = Math.addExact(length, next.length());
      }
    }
    long delta = data.size() - length;
    if (!data.isEmpty()) {
      if (delta > 0 || delta < 0 && delta > -8) {
        data.append(padding(data, -1));
        delta = data.size() - length;
      } else if (delta < 0) {
        if (-delta - 8 > Integer.MAX_VALUE) {
          throw new IllegalArgumentException("MP4 padding exceeds Java array bound");
        }
        data.append(padding(data, (int) (-delta - 8)));
        delta = 0;
      }
      file.insert(data, offset, length);
      if (delta != 0) {
        Mp4Save.parents(file, path, delta, 1);
        Mp4Save.offsets(file, atoms, delta, offset);
      }
    } else {
      // Native saveExisting removes the target's parent container for an empty payload.
      Atom outer = path.get(path.size() - 3);
      if (outer.removeChild(parent)) {
        offset = parent.offset();
        delta = -parent.length();
        file.removeBlock(offset, parent.length());
        if (delta != 0) {
          Mp4Save.parents(file, path, delta, 2);
          Mp4Save.offsets(file, atoms, delta, offset);
        }
      }
    }
  }
}
