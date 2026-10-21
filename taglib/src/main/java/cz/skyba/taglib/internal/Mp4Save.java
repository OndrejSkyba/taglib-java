// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal;

import cz.skyba.taglib.mp4.Atom;
import cz.skyba.taglib.mp4.Atoms;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.IOStream;

import java.util.List;

/// Shared source-ordered MP4 parent-size and chunk-offset updates.
public final class Mp4Save {
  private Mp4Save() {
  }

  public static ByteVector atom(String name, ByteVector data) {
    if (data.size() > Integer.MAX_VALUE - 8) {
      throw new IllegalArgumentException("MP4 atom exceeds Java array bound");
    }
    return ByteVector.fromUnsignedInt(data.size() + 8L).append(new ByteVector(name)).append(data);
  }

  public static void parents(IOStream file, List<Atom> path, long delta, int ignore) {
    for (int i = 0; i < path.size() - ignore; i++) {
      Atom atom = path.get(i);
      file.seek(atom.offset());
      long size = file.readBlock(4).toUnsignedInt();
      if (size == 1) {
        file.seek(4, IOStream.Position.CURRENT);
        long extended = file.readBlock(8).toLong();
        file.seek(atom.offset() + 8);
        file.writeBlock(ByteVector.fromLong(Math.addExact(extended, delta)));
      } else {
        file.seek(atom.offset());
        file.writeBlock(ByteVector.fromUnsignedInt(size + delta));
      }
    }
  }

  public static void offsets(IOStream file, Atoms atoms, long delta, long offset) {
    Atom movie = atoms.find("moov");
    if (movie != null) {
      table(file, movie.findall("stco", true), delta, offset, 4);
      table(file, movie.findall("co64", true), delta, offset, 8);
    }
    Atom fragment = atoms.find("moof");
    if (fragment != null) {
      for (Atom atom : fragment.findall("tfhd", true)) {
        if (atom.offset() > offset) {
          atom.addToOffset(delta);
        }
        if (atom.length() < 24) {
          continue;
        }
        file.seek(atom.offset() + 9);
        ByteVector data = file.readBlock(atom.length() - 9);
        if ((data.toUnsignedInt(0, 3, true) & 1) != 0) {
          long value = data.toLong(7);
          if (value > offset) {
            value = Math.addExact(value, delta);
          }
          file.seek(atom.offset() + 16);
          file.writeBlock(ByteVector.fromLong(value));
        }
      }
    }
  }

  private static void table(IOStream file, List<Atom> tables, long delta, long offset, int width) {
    for (Atom atom : tables) {
      if (atom.offset() > offset) {
        atom.addToOffset(delta);
      }
      if (atom.length() < 12 + width) {
        continue;
      }
      file.seek(atom.offset() + 12);
      ByteVector data = file.readBlock(atom.length() - 12);
      long count = data.toUnsignedInt();
      file.seek(atom.offset() + 16);
      for (int pos = 4; count-- > 0 && pos <= data.size() - width; pos += width) {
        long value = width == 4 ? data.toUnsignedInt(pos, true) : data.toLong(pos);
        if (value > offset) {
          value = Math.addExact(value, delta);
        }
        file.writeBlock(width == 4 ? ByteVector.fromUnsignedInt(value) : ByteVector.fromLong(value));
      }
    }
  }
}
