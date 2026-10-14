// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.riff.wav;

import cz.skyba.taglib.FileBase.StripTags;
import cz.skyba.taglib.id3.v2.Tag;
import cz.skyba.taglib.riff.wav.WavFile.TagType;

import java.util.Objects;
import java.util.Set;

/**
 * Immutable options for saving WAV metadata in place.
 * Selecting a tag family controls which in-memory tag is written. The unselected-tag
 * policy determines whether other supported tag families remain in storage.
 * The selection covers ID3v2 and INFO tags; BEXT and iXML edits are saved regardless of it.
 *
 * @param tags           tag families to save; an empty set selects none
 * @param unselectedTags whether unselected tag families are removed or preserved
 * @param id3v2Version   the ID3v2 output version
 */
public record WavSaveOptions(Set<TagType> tags, StripTags unselectedTags, Tag.Version id3v2Version) {
  /**
   * All supported tag families are selected, unselected tags are removed, and ID3v2 output
   * uses version 4.
   */
  public static final WavSaveOptions DEFAULT =
    new WavSaveOptions(Set.of(TagType.values()), StripTags.STRIP_OTHERS, Tag.Version.V4);

  /**
   * Creates save options. Constructing options does not modify a file.
   *
   * @throws NullPointerException if the tag set, a tag element, a policy or the ID3v2 version is null
   */
  public WavSaveOptions {
    tags = Set.copyOf(tags);
    Objects.requireNonNull(unselectedTags);
    Objects.requireNonNull(id3v2Version);
  }
}
