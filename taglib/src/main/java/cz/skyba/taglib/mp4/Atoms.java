// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.mp4;

import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.internal.FileOwnerStream;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.IOStream;

import java.util.*;

/**
 * A parsed tree of MP4 root atoms and recognized containers.
 * Descriptors retain their identities. Validation can prune invalid trailing roots after a
 * valid movie atom. The tree reflects the source at parsing time and is not refreshed by
 * external storage writes.
 */
public final class Atoms {
  private final List<Atom> atoms = new ArrayList<>();

  /**
   * Scans root atoms from byte offset zero using a borrowed source.
   * The source remains open and its cursor advances.
   *
   * @param file the source to inspect
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public Atoms(FileBase file) {
    this(FileOwnerStream.properties(file));
  }

  /**
   * Scans root atoms from byte offset zero using a borrowed source.
   * The source remains open and its cursor advances.
   *
   * @param file the source to inspect
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public Atoms(IOStream file) {
    file.seek(0, IOStream.Position.END);
    long end = file.tell();
    file.seek(0);
    var count = new Atom.AtomCounter();
    while (file.tell() <= end - 8) {
      Atom atom = new Atom(file, 0, count);
      atoms.add(atom);
      if (atom.length() == 0) {
        break;
      }
      if (atoms.size() > 50_000) {
        atoms.clear();
        break;
      }
    }
  }

  /**
   * Returns an unmodifiable live view of descriptor references.
   *
   * @return the descriptor view
   */
  public List<Atom> atoms() {
    return Collections.unmodifiableList(atoms);
  }

  /**
   * Returns the first root-and-child descriptor matching a name path.
   *
   * @param names one to four atom names, beginning with the root name
   * @return the matched descriptor, or null if absent
   * @throws IllegalArgumentException if the name count is outside one to four
   */
  public Atom find(String... names) {
    if (names.length == 0 || names.length > 4) {
      throw new IllegalArgumentException("One to four atom names required");
    }
    ByteVector expected = null;
    for (Atom atom : atoms) {
      if (expected == null) {
        expected = new ByteVector(names[0]);
      }
      if (atom.nameEquals(expected)) {
        return atom.find(Arrays.copyOfRange(names, 1, names.length));
      }
    }
    return null;
  }

  /**
   * Returns a descriptor chain matching a root-and-child name path.
   *
   * @param names one to four atom names, beginning with the root name
   * @return a detached list of matched references, or an empty list if absent
   * @throws IllegalArgumentException if the name count is outside one to four
   */
  public List<Atom> path(String... names) {
    if (names.length == 0 || names.length > 4) {
      throw new IllegalArgumentException("One to four atom names required");
    }
    var result = new ArrayList<Atom>();
    ByteVector expected = null;
    for (Atom atom : atoms) {
      if (expected == null) {
        expected = new ByteVector(names[0]);
      }
      if (atom.nameEquals(expected)) {
        if (!atom.path(result, Arrays.copyOfRange(names, 1, names.length))) {
          result.clear();
        }
        break;
      }
    }
    return result;
  }

  private static boolean valid(Atom root) {
    var pending = new ArrayDeque<Atom>();
    var seen = new IdentityHashMap<Atom, Boolean>();
    pending.push(root);
    while (!pending.isEmpty()) {
      Atom atom = pending.pop();
      if (seen.put(atom, Boolean.TRUE) != null) {
        throw new IllegalStateException("Cyclic or multiply owned MP4 atom tree");
      }
      if (seen.size() > 50_000) {
        throw new IllegalStateException("MP4 atom count exceeds bound");
      }
      if (atom.length() == 0) {
        return false;
      }
      for (Atom child : atom.children()) {
        pending.push(child);
      }
    }
    return true;
  }

  /**
   * Validates atom descriptor trees and prunes invalid trailing roots after a valid movie atom.
   * An invalid root before the movie, or an invalid movie-fragment root, rejects the tree.
   * This check does not itself require a movie atom to be present.
   *
   * @return whether the remaining roots pass validation
   * @throws IllegalStateException if descriptor ownership is cyclic, duplicated or exceeds the count limit
   */
  public boolean checkRootLevelAtoms() {
    boolean movie = false;
    for (int i = 0; i < atoms.size(); i++) {
      Atom atom = atoms.get(i);
      boolean invalid = !valid(atom);
      if (!movie && !invalid && atom.nameEquals(new ByteVector("moov"))) {
        movie = true;
      }
      if (invalid) {
        if (!movie || atom.nameEquals(new ByteVector("moof"))) {
          return false;
        }
        atoms.subList(i, atoms.size()).clear();
        return true;
      }
    }
    return true;
  }
}
