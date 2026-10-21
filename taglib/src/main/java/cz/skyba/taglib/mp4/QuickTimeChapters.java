// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.mp4;

import cz.skyba.taglib.internal.WriteGuard;
import cz.skyba.taglib.internal.editor.Mp4QuickTime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/// An immutable snapshot of an audio track's QuickTime chapter reference.
/// Chapter times are milliseconds. Nero chapters and other metadata are separate.
///
/// @param chapters the chapter values, copied into an immutable list
public record QuickTimeChapters(List<Chapter> chapters) {
  /// Creates a chapter snapshot and copies the chapter list.
  public QuickTimeChapters {
    chapters = List.copyOf(chapters);
  }

  /// Reads the first audio track's supported chapter reference and closes its temporary file handle.
  /// Chapter tracks requiring only 64-bit chunk offsets are not read. An initial dummy chapter
  /// is hidden, and a damaged later sample can leave a readable prefix.
  ///
  /// @param path the MP4 file to inspect
  /// @return the chapter snapshot
  /// @throws UncheckedIOException if storage access fails
  public static QuickTimeChapters read(Path path) {
    try {
      return new QuickTimeChapters(Mp4QuickTime.read(path));
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata" + ": " + path, failure);
    }
  }

  /// Writes the complete QuickTime chapter replacement in place, preserving separate Nero chapters.
  /// An empty list requests removal. A failure can leave partial changes.
  ///
  /// @param path the MP4 file to update
  /// @throws UncheckedIOException            if storage access fails
  /// @throws cz.skyba.taglib.TagLibException if chapter data cannot be represented
  public void save(Path path) {
    try {
      WriteGuard.run(path, () -> Mp4QuickTime.write(path, chapters));
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot write audio metadata: " + path, failure);
    }
  }
}
