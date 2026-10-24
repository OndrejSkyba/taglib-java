// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.matroska;

import java.util.List;
import java.util.Objects;

/// An immutable Matroska chapter edition. UIDs retain all 64 bits.
public final class ChapterEdition {
  private record State(List<Chapter> chapters, boolean isDefault, boolean ordered, long uid) {
  }

  private final State state;

  /// Creates a chapter edition with copied chapters.
  ///
  /// @param uid       the unsigned 64-bit UID bit pattern, or zero if unspecified
  /// @param chapters  chapter values to copy
  /// @param isDefault whether this is the default edition
  /// @param ordered   whether playback follows the edition's chapter order
  public ChapterEdition(List<Chapter> chapters, boolean isDefault, boolean ordered, long uid) {
    state = new State(chapters.stream().map(Chapter::new).toList(), isDefault, ordered, uid);
  }

  /// Creates a chapter edition with copied chapters.
  ///
  /// @param chapters  chapter values to copy
  /// @param isDefault whether this is the default edition
  /// @param ordered   whether playback follows the edition's chapter order
  public ChapterEdition(List<Chapter> chapters, boolean isDefault, boolean ordered) {
    this(chapters, isDefault, ordered, 0);
  }

  /// Creates a chapter edition with copied chapters.
  ///
  /// @param chapters  chapter values to copy
  /// @param isDefault whether this is the default edition
  public ChapterEdition(List<Chapter> chapters, boolean isDefault) {
    this(chapters, isDefault, false, 0);
  }

  /// Creates a copy of the chapter edition value.
  ///
  /// @param other the value to copy
  public ChapterEdition(ChapterEdition other) {
    state = Objects.requireNonNull(other).state;
  }

  /// Returns independent copies of the stored values in an immutable list.
  ///
  /// @return the copied values
  public List<Chapter> chapterList() {
    return state.chapters.stream().map(Chapter::new).toList();
  }

  public boolean isDefault() {
    return state.isDefault;
  }

  public boolean isOrdered() {
    return state.ordered;
  }

  public long uid() {
    return state.uid;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ChapterEdition e && state.equals(e.state);
  }

  @Override
  public int hashCode() {
    return state.hashCode();
  }
}
