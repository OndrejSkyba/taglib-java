// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.matroska;

import java.util.List;
import java.util.Objects;

/// An immutable Matroska chapter description. Timestamps use unsigned nanoseconds; UIDs retain all 64 bits.
public final class Chapter {
  /// An immutable chapter display title and language identifier.
  public static final class Display {
    private record State(String string, String language) {
    }

    private final State state;

    /// Creates an immutable chapter display value.
    ///
    /// @param string   the displayed title
    /// @param language the language identifier
    public Display(String string, String language) {
      state = new State(Objects.requireNonNull(string), Objects.requireNonNull(language));
    }

    /// Creates an immutable chapter display value.
    ///
    /// @param other the display to copy
    public Display(Display other) {
      state = Objects.requireNonNull(other).state;
    }

    public String string() {
      return state.string;
    }

    public String language() {
      return state.language;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Display d && state.equals(d.state);
    }

    @Override
    public int hashCode() {
      return state.hashCode();
    }
  }

  private record State(long start, long end, List<Display> displays, long uid, boolean hidden) {
  }

  private final State state;

  /// Creates a Chapter value, copying mutable byte and collection inputs.
  ///
  /// @param uid      the unsigned 64-bit UID bit pattern, or zero if unspecified
  /// @param start    the unsigned starting timestamp in nanoseconds
  /// @param end      the unsigned ending timestamp in nanoseconds, or zero if unspecified
  /// @param displays chapter title and language values to copy
  /// @param hidden   whether the chapter is hidden
  public Chapter(long start, long end, List<Display> displays, long uid, boolean hidden) {
    state = new State(start, end, displays.stream().map(Display::new).toList(), uid, hidden);
  }

  /// Creates a Chapter value, copying mutable byte and collection inputs.
  ///
  /// @param uid      the unsigned 64-bit UID bit pattern, or zero if unspecified
  /// @param start    the unsigned starting timestamp in nanoseconds
  /// @param end      the unsigned ending timestamp in nanoseconds, or zero if unspecified
  /// @param displays chapter title and language values to copy
  public Chapter(long start, long end, List<Display> displays, long uid) {
    this(start, end, displays, uid, false);
  }

  /// Creates a Chapter value, copying mutable byte and collection inputs.
  ///
  /// @param start    the unsigned starting timestamp in nanoseconds
  /// @param end      the unsigned ending timestamp in nanoseconds, or zero if unspecified
  /// @param displays chapter title and language values to copy
  public Chapter(long start, long end, List<Display> displays) {
    this(start, end, displays, 0, false);
  }

  /// Creates a copied value with shared immutable state.
  ///
  /// @param other the value to copy
  public Chapter(Chapter other) {
    state = Objects.requireNonNull(other).state;
  }

  public long timeStart() {
    return state.start;
  }

  public long timeEnd() {
    return state.end;
  }

  public long uid() {
    return state.uid;
  }

  public boolean isHidden() {
    return state.hidden;
  }

  /// Returns independent copies of the stored values in an immutable list.
  ///
  /// @return the copied values
  public List<Display> displayList() {
    return state.displays.stream().map(Display::new).toList();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Chapter c && state.equals(c.state);
  }

  @Override
  public int hashCode() {
    return state.hashCode();
  }
}
