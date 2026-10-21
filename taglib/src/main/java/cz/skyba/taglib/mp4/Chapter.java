// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.mp4;

import java.util.Objects;

/// An immutable MP4 chapter title and signed start time in milliseconds.
public final class Chapter {
  private final String title;
  private final long startTime;

  /// Creates an immutable chapter value.
  ///
  /// @param title     the chapter title
  /// @param startTime the signed chapter start time in milliseconds
  public Chapter(String title, long startTime) {
    this.title = Objects.requireNonNull(title);
    this.startTime = startTime;
  }

  /// Creates an immutable chapter value. Immutable payload storage can be shared.
  ///
  /// @param other the value to copy
  public Chapter(Chapter other) {
    this(other.title, other.startTime);
  }

  public String title() {
    return title;
  }

  public long startTime() {
    return startTime;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Chapter c && title.equals(c.title) && startTime == c.startTime;
  }

  @Override
  public int hashCode() {
    return Objects.hash(title, startTime);
  }

  @Override
  public String toString() {
    return "Chapter[title=" + title + ", startTime=" + startTime + "]";
  }
}
