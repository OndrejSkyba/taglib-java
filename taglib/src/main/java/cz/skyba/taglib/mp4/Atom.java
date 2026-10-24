// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.mp4;

import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.internal.FileOwnerStream;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.IOStream;

import java.nio.charset.StandardCharsets;
import java.util.*;

/// A mutable descriptor in a parsed MP4 atom tree.
/// Parsing starts at the borrowed source's current cursor and moves it past the atom. Invalid
/// extents are represented by a zero length. Descriptor mutations affect the inspection tree
/// in memory and do not rewrite storage.
public class Atom {
  private static final Set<String> CONTAINERS = Set.of("moov", "udta", "mdia", "meta", "ilst", "stbl", "minf", "moof", "traf", "trak",
    "stsd", "stem");
  private static final Set<String> META_CHILDREN = Set.of("hdlr", "ilst", "mhdr", "ctry", "lang");
  private long offset, length;
  private ByteVector name = new ByteVector();
  private final List<Atom> children = new ArrayList<>();

  static final class AtomCounter {
    private final int[] exposed;
    private int value;

    AtomCounter() {
      exposed = null;
    }

    AtomCounter(int[] exposed) {
      this.exposed = Objects.requireNonNull(exposed);
      if (exposed.length == 0) {
        throw new IllegalArgumentException("Missing atom counter");
      }
      value = exposed[0];
    }

    boolean incrementExceeds(int limit) {
      boolean exceeds = ((Integer.toUnsignedLong(value) + 1) & 0xffff_ffffL) > limit;
      value++;
      if (exposed != null) {
        exposed[0] = value;
      }
      return exceeds;
    }
  }

  /// Parses an atom and recognized child containers at the current source cursor.
  /// The source is borrowed and remains open; its cursor advances.
  ///
  /// @param file the source to inspect
  /// @throws java.io.UncheckedIOException if storage access fails
  public Atom(FileBase file) {
    this(FileOwnerStream.properties(file));
  }

  /// Parses an atom and recognized child containers at the current source cursor.
  /// The source is borrowed and remains open; its cursor advances.
  ///
  /// @param stream the source to inspect
  /// @throws java.io.UncheckedIOException if storage access fails
  public Atom(IOStream stream) {
    this(stream, 0, new AtomCounter());
  }

  /// Parses an atom and recognized child containers at the current source cursor.
  /// The source is borrowed and remains open; its cursor advances.
  ///
  /// @param file  the source to inspect
  /// @param depth the starting container nesting depth
  /// @throws java.io.UncheckedIOException if storage access fails
  protected Atom(FileBase file, int depth) {
    this(FileOwnerStream.properties(file), depth, new AtomCounter());
  }

  /// Parses an atom and recognized child containers at the current source cursor.
  /// The source is borrowed and remains open; its cursor advances.
  ///
  /// @param file      the source to inspect
  /// @param depth     the starting container nesting depth
  /// @param atomCount a nonempty shared array whose first entry tracks the parsed atom count
  /// @throws java.io.UncheckedIOException if storage access fails
  protected Atom(FileBase file, int depth, int[] atomCount) {
    this(FileOwnerStream.properties(file), depth, new AtomCounter(atomCount));
  }

  /// Parses an atom and recognized child containers at the current source cursor.
  /// The source is borrowed and remains open; its cursor advances.
  ///
  /// @param stream    the source to inspect
  /// @param depth     the starting container nesting depth
  /// @param atomCount a nonempty shared array whose first entry tracks the parsed atom count
  /// @throws java.io.UncheckedIOException if storage access fails
  protected Atom(IOStream stream, int depth, int[] atomCount) {
    this(stream, depth, new AtomCounter(atomCount));
  }

  Atom(IOStream file, int depth, AtomCounter atomCount) {
    Objects.requireNonNull(file);
    Objects.requireNonNull(atomCount);
    offset = file.tell();
    if (atomCount.incrementExceeds(50_000)) {
      file.seek(0, IOStream.Position.END);
      return;
    }
    ByteVector header = file.readBlock(8);
    if (header.size() != 8) {
      file.seek(0, IOStream.Position.END);
      return;
    }
    length = header.toUnsignedInt();
    if (length == 0) {
      length = file.length() - offset;
    } else if (length == 1) {
      length = file.readBlock(8).toLong();
    }
    if (offset < 0 || length < 8 || length > file.length() - offset) {
      length = 0;
      file.seek(0, IOStream.Position.END);
      return;
    }
    name = header.mid(4, 4);
    String key = new String(name.data(), StandardCharsets.ISO_8859_1);
    if (key.equals("stem")) {
      file.seek(offset + length);
      return;
    }
    if (CONTAINERS.contains(key)) {
      if (key.equals("meta")) {
        long pos = file.tell();
        ByteVector next = file.readBlock(8).mid(4, 4);
        file.seek(pos + (META_CHILDREN.contains(new String(next.data(), StandardCharsets.ISO_8859_1))
          ? 0
          : 4));
      } else if (key.equals("stsd")) {
        file.seek(8, IOStream.Position.CURRENT);
      }
      if (depth > 64) {
        return;
      }
      while (file.tell() < offset + length) {
        if (children.size() >= 50_000) {
          children.clear();
          length = 0;
          file.seek(0, IOStream.Position.END);
          return;
        }
        Atom child = new Atom(file, depth + 1, atomCount);
        children.add(child);
        if (child.length == 0) {
          return;
        }
      }
      return;
    }
    file.seek(offset + length);
  }

  public final long offset() {
    return offset;
  }

  public final long length() {
    return length;
  }

  public final ByteVector name() {
    return new ByteVector(name);
  }

  final boolean nameEquals(ByteVector expected) {
    return name.equals(expected);
  }

  /// Returns an unmodifiable live view of descriptor references.
  ///
  /// @return the descriptor view
  public final List<Atom> children() {
    return Collections.unmodifiableList(children);
  }

  /// Adjusts the byte offset without writing storage.
  ///
  /// @param delta the signed byte adjustment
  public final void addToOffset(long delta) {
    offset = Math.addExact(offset, delta);
  }

  /// Prepends a live child descriptor in memory without changing storage.
  ///
  /// @param atom the child descriptor to retain
  public final void prependChild(Atom atom) {
    children.addFirst(Objects.requireNonNull(atom));
  }

  /// Removes a child descriptor reference in memory without changing storage.
  ///
  /// @param atom the child descriptor to remove
  /// @return whether the reference was present
  public final boolean removeChild(Atom atom) {
    return children.remove(atom);
  }

  /// Finds the first descendant matching a child-name path. An empty path returns this atom.
  ///
  /// @param names zero to four child names, in nesting order
  /// @return the matched descriptor, or null if absent
  /// @throws IllegalArgumentException if more than four names are supplied
  public final Atom find(String... names) {
    if (names.length > 4) {
      throw new IllegalArgumentException("At most four atom names");
    }
    Atom current = this;
    for (String part : names) {
      if (part == null) {
        break;
      }
      Atom next = null;
      ByteVector expected = null;
      for (Atom child : current.children) {
        if (expected == null) {
          expected = new ByteVector(part);
        }
        if (child.name.equals(expected)) {
          next = child;
          break;
        }
      }
      if (next == null) {
        return null;
      }
      current = next;
    }
    return current;
  }

  /// Appends this atom and a matching descendant chain to the supplied list.
  /// A failed lookup leaves the matched prefix appended.
  ///
  /// @param path  the destination list to append to
  /// @param names child names in nesting order
  /// @return whether the complete path was found
  /// @throws IllegalArgumentException if more than three child names are supplied
  public final boolean path(List<Atom> path, String... names) {
    if (names.length > 3) {
      throw new IllegalArgumentException("At most three child atom names");
    }
    path.add(this);
    Atom current = this;
    for (String part : names) {
      if (part == null) {
        break;
      }
      Atom next = current.find(part);
      if (next == null) {
        return false;
      }
      path.add(next);
      current = next;
    }
    return true;
  }

  /// Finds child descriptors with the supplied atom name. Only immediate children are searched.
  ///
  /// @param name the atom name to match
  /// @return a detached list containing matched descriptor references
  public final List<Atom> findall(String name) {
    return findall(name, false);
  }

  /// Finds child descriptors with the supplied atom name.
  ///
  /// @param name      the atom name to match
  /// @param recursive whether to search all descendant containers
  /// @return a detached list containing matched descriptor references
  /// @throws IllegalStateException if a recursive search encounters a cyclic, shared or excessively large atom tree
  public final List<Atom> findall(String name, boolean recursive) {
    var found = new ArrayList<Atom>();
    ByteVector expected = new ByteVector(name);
    if (!recursive) {
      for (Atom child : children) {
        if (child.name.equals(expected)) {
          found.add(child);
        }
      }
      return found;
    }
    var pending = new ArrayDeque<Iterator<Atom>>();
    var seen = new IdentityHashMap<Atom, Boolean>();
    seen.put(this, Boolean.TRUE);
    pending.push(children.iterator());
    while (!pending.isEmpty()) {
      Iterator<Atom> level = pending.peek();
      if (!level.hasNext()) {
        pending.pop();
        continue;
      }
      Atom child = level.next();
      if (child.name.equals(expected)) {
        found.add(child);
      }
      if (seen.put(child, Boolean.TRUE) != null) {
        throw new IllegalStateException("Cyclic or multiply owned MP4 atom tree");
      }
      if (seen.size() > 50_000) {
        throw new IllegalStateException("MP4 atom count exceeds bound");
      }
      pending.push(child.children.iterator());
    }
    return found;
  }
}
