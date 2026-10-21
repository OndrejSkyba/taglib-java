// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.matroska;

import cz.skyba.taglib.internal.WriteGuard;
import cz.skyba.taglib.internal.codec.MatroskaCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// A mutable attachment collection. Incoming values and exported collection values are copied.
/// MatroskaFile-bound edits are staged until the owning file is saved. Path save operations write directly.
public class Attachments {
  private final CopyingValues<AttachedFile> values = new CopyingValues<>(AttachedFile::new);
  private final ArrayList<AttachedFile> files = values.stored();
  private Runnable changed = () -> {
  };

  public Attachments() {
  }

  /// Creates a collection with copied input values.
  ///
  /// @param values the values to copy
  public Attachments(List<AttachedFile> values) {
    this.values.addAll(values);
  }

  Attachments(List<AttachedFile> values, Runnable changed) {
    this(values);
    this.changed = changed;
  }

  /// Appends a copy of the supplied value in memory and marks a bound container changed.
  ///
  /// @param value the value to copy
  public final void addAttachedFile(AttachedFile value) {
    values.add(value);
    changed.run();
  }

  /// Removes the first stored value with the matching UID in memory.
  /// The container is marked changed only when a match exists.
  ///
  /// @param uid the UID bit pattern to match
  public final void removeAttachedFile(long uid) {
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
  public final List<AttachedFile> attachedFileList() {
    return values.snapshot();
  }

  List<AttachedFile> attachedFiles() {
    changed.run();
    return files;
  }

  List<AttachedFile> storedFiles() {
    return files;
  }

  /// Reads attachment values from a file and closes temporary handles.
  ///
  /// @param path the Matroska file to inspect
  /// @return the decoded collection, or null if no attachment view exists
  /// @throws UncheckedIOException if storage access fails
  public static Attachments read(Path path) {
    try (var f = new MatroskaFile(path)) {
      return f.attachments();
    }
  }

  /// Writes this attachment collection to the supplied path in place.
  /// An empty collection requests removal. A failure can leave partial changes.
  ///
  /// @param path the Matroska file to update
  /// @throws UncheckedIOException if storage access fails
  public final void save(Path path) {
    try {
      var values = attachedFileList();
      WriteGuard.run(path, () -> MatroskaCodec.writeAttachments(path, values));
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot write audio metadata: " + path, failure);
    }
  }
}
