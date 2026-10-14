// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;

import cz.skyba.taglib.toolkit.Variant;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * A view over three optional live tags in priority order.
 * Common text and numeric getters select the first populated field. Common setters update
 * every non-null child. Property and complex-property reads use the documented precedence
 * for each operation. Child references are retained without copying or taking ownership.
 */
public final class TagUnion extends TagBase {
  private final TagBase[] tags;

  /**
   * Creates a union with three empty child slots.
   */
  public TagUnion() {
    this(null, null, null);
  }

  /**
   * Creates a union over live child tags in decreasing priority order.
   *
   * @param first  the highest-priority tag, or {@code null}
   * @param second the middle-priority tag, or {@code null}
   * @param third  the lowest-priority tag, or {@code null}
   */
  public TagUnion(TagBase first, TagBase second, TagBase third) {
    tags = new TagBase[]{first, second, third};
  }

  /**
   * Returns a live child reference from a priority slot.
   *
   * @param index the zero-based slot, from 0 to 2
   * @return the child tag, or {@code null} if the slot is empty
   * @throws IndexOutOfBoundsException if the slot is outside 0 to 2
   */
  public TagBase tag(int index) {
    return tags[index];
  }

  /**
   * Replaces a child reference without copying or closing the tag.
   *
   * @param index the zero-based priority slot, from 0 to 2
   * @param tag   the new live tag, or {@code null} to clear the slot
   * @throws IndexOutOfBoundsException if the slot is outside 0 to 2
   */
  public void set(int index, TagBase tag) {
    tags[index] = tag;
  }

  private String stringValue(Function<TagBase, String> getter) {
    for (var tag : tags) {
      if (tag != null) {
        String value = getter.apply(tag);
        if (!value.isEmpty()) {
          return value;
        }
      }
    }
    return "";
  }

  private long numberValue(ToLongFunction<TagBase> getter) {
    for (var tag : tags) {
      if (tag != null) {
        long value = getter.applyAsLong(tag);
        if (value > 0) {
          return value;
        }
      }
    }
    return 0;
  }

  private void each(Consumer<TagBase> operation) {
    for (var tag : tags) {
      if (tag != null) {
        operation.accept(tag);
      }
    }
  }

  @Override
  public String title() {
    return stringValue(TagBase::title);
  }

  @Override
  public String artist() {
    return stringValue(TagBase::artist);
  }

  @Override
  public String album() {
    return stringValue(TagBase::album);
  }

  @Override
  public String comment() {
    return stringValue(TagBase::comment);
  }

  @Override
  public String genre() {
    return stringValue(TagBase::genre);
  }

  @Override
  public long year() {
    return numberValue(TagBase::year);
  }

  @Override
  public long track() {
    return numberValue(TagBase::track);
  }

  @Override
  public void setTitle(String value) {
    each(t -> t.setTitle(value));
  }

  @Override
  public void setArtist(String value) {
    each(t -> t.setArtist(value));
  }

  @Override
  public void setAlbum(String value) {
    each(t -> t.setAlbum(value));
  }

  @Override
  public void setComment(String value) {
    each(t -> t.setComment(value));
  }

  @Override
  public void setGenre(String value) {
    each(t -> t.setGenre(value));
  }

  @Override
  public void setYear(long value) {
    each(t -> t.setYear(value));
  }

  @Override
  public void setTrack(long value) {
    each(t -> t.setTrack(value));
  }

  @Override
  public boolean isEmpty() {
    return Arrays.stream(tags).allMatch(t -> t == null || t.isEmpty());
  }

  /**
   * Returns properties from the first non-null, nonempty tag in precedence order.
   */
  @Override
  public PropertyMap properties() {
    for (var tag : tags) {
      if (tag != null && !tag.isEmpty()) {
        return tag.properties();
      }
    }
    return PropertyMap.EMPTY;
  }

  @Override
  public void removeUnsupportedProperties(List<String> unsupported) {
    each(t -> t.removeUnsupportedProperties(unsupported));
  }

  @Override
  public boolean supportsPictureEditing() {
    for (var tag : tags) {
      if (tag != null && tag.supportsPictureEditing()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the first nonempty complex-property key list in tag precedence order.
   */
  @Override
  public List<String> complexPropertyKeys() {
    for (var tag : tags) {
      if (tag != null) {
        var keys = tag.complexPropertyKeys();
        if (!keys.isEmpty()) {
          return keys;
        }
      }
    }
    return List.of();
  }

  /**
   * Returns the first nonempty value list for the key in tag precedence order.
   */
  @Override
  public List<Map<String, Variant>> complexProperties(String key) {
    for (var tag : tags) {
      if (tag != null) {
        var result = tag.complexProperties(key);
        if (!result.isEmpty()) {
          return result;
        }
      }
    }
    return List.of();
  }

  /**
   * Broadcasts the values to every non-null child tag and succeeds if any child accepts them.
   */
  @Override
  public boolean setComplexProperties(String key, List<Map<String, Variant>> value) {
    boolean accepted = false;
    for (var tag : tags)
      if (tag != null && tag.setComplexProperties(key, value)) {
        accepted = true;
      }
    return accepted;
  }
}
