// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.matroska;

import cz.skyba.taglib.internal.WriteGuard;
import cz.skyba.taglib.internal.codec.MatroskaCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// A mutable collection of Matroska chapter editions.
/// Values are copied on insertion and retrieval. When bound to a [MatroskaFile], edits are staged
/// until that file is saved. [#save(Path)] writes directly to the supplied path.
public class Chapters {
  private final CopyingValues<ChapterEdition> values = new CopyingValues<>(ChapterEdition::new);
  private final ArrayList<ChapterEdition> editions = values.stored();
  private Runnable changed = () -> {
  };
  private final boolean snapshotValue;

  public Chapters() {
    snapshotValue = false;
  }

  /// Creates a collection with copied input values.
  ///
  /// @param values the values to copy
  public Chapters(List<ChapterEdition> values) {
    snapshotValue = true;
    this.values.addAll(values);
  }

  Chapters(List<ChapterEdition> values, Runnable changed) {
    snapshotValue = false;
    this.values.addAll(values);
    this.changed = changed;
  }

  /// Appends a copy of the supplied value in memory and marks a bound container changed.
  ///
  /// @param value the value to copy
  public final void addChapterEdition(ChapterEdition value) {
    values.add(value);
    changed.run();
  }

  /// Removes the first stored value with the matching UID in memory.
  /// The container is marked changed only when a match exists.
  ///
  /// @param uid the UID bit pattern to match
  public final void removeChapterEdition(long uid) {
    if (values.removeFirst(value -> value.uid() == uid)) {
      changed.run();
    }
  }

  /// Clears the collection in memory and marks a bound container changed.
  public final void clear() {
    this.values.clear();
    changed.run();
  }

  /// Returns an immutable list snapshot with copied values.
  ///
  /// @return detached collection values
  public final List<ChapterEdition> chapterEditionList() {
    return values.snapshot();
  }

  /// Reads chapter edition values from a file and closes temporary handles.
  ///
  /// @param path the Matroska file to inspect
  /// @return the decoded collection
  /// @throws UncheckedIOException if storage access fails
  public static Chapters read(Path path) {
    try {
      return new Chapters(MatroskaCodec.readChapters(path));
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata" + ": " + path, failure);
    }
  }

  /// Writes this chapter edition collection to the supplied path in place.
  /// An empty collection requests removal. A failure can leave partial changes.
  ///
  /// @param path the Matroska file to update
  /// @throws UncheckedIOException if storage access fails
  public final void save(Path path) {
    try {
      WriteGuard.run(path, () -> MatroskaCodec.writeChapters(path, chapterEditionList()));
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot write audio metadata: " + path, failure);
    }
  }

  @Override
  public boolean equals(Object other) {
    return this == other || snapshotValue && other instanceof Chapters c && c.snapshotValue && editions.equals(c.editions);
  }

  @Override
  public int hashCode() {
    return snapshotValue ? editions.hashCode() : System.identityHashCode(this);
  }
}
