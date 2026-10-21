// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.mp4;

import cz.skyba.taglib.TagLibException;
import cz.skyba.taglib.toolkit.IOStream;

import java.util.ArrayList;
import java.util.List;

/// Chapter values and modification tracking shared by Nero and QuickTime chapter lists.
public class ChapterHolder {
  protected List<Chapter> chapterList = new ArrayList<>();
  protected boolean modified;

  static void requireWritable(IOStream file) {
    if (!file.isOpen()) {
      throw new IllegalStateException("Audio file is closed: " + file.name());
    }
    if (file.readOnly()) {
      throw new TagLibException("Audio file is read-only: " + file.name());
    }
  }

  /// Returns an immutable snapshot of this list's chapter values.
  ///
  /// @return chapter values
  public final List<Chapter> chapters() {
    return List.copyOf(chapterList);
  }

  /// Replaces chapter values in memory, copying the list. The modification flag is unchanged.
  ///
  /// @param chapters the replacement values
  /// @see #setModified(boolean)
  public final void setChapters(List<Chapter> chapters) {
    chapterList = new ArrayList<>(List.copyOf(chapters));
  }

  public final boolean isModified() {
    return modified;
  }

  public final void setModified(boolean value) {
    modified = value;
  }
}
