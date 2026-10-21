// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;

import cz.skyba.taglib.internal.TextNumbers;
import cz.skyba.taglib.toolkit.MutablePropertyMap;
import cz.skyba.taglib.toolkit.Variant;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/// Base for mutable audio metadata views and format-specific property mappings.
/// File-bound edits are staged until the owning file is saved. Text properties use ordered
/// value lists; the default common-field mapping consumes the first value and reports
/// remaining values as rejected. Formats can provide richer mappings. Numeric year and
/// track values use unsigned 32-bit values represented as Java `long`s.
public abstract class TagBase {
  public abstract String title();

  public abstract String artist();

  public abstract String album();

  public abstract String comment();

  public abstract String genre();

  /// Returns the year as an unsigned 32-bit value represented in a Java `long`.
  ///
  /// @return the year, or zero if unspecified
  public abstract long year();

  public abstract long track();

  public abstract void setTitle(String value);

  public abstract void setArtist(String value);

  public abstract void setAlbum(String value);

  public abstract void setComment(String value);

  public abstract void setGenre(String value);

  public abstract void setYear(long value);

  public abstract void setTrack(long value);

  public boolean isEmpty() {
    return title().isEmpty()
      && artist().isEmpty()
      && album().isEmpty()
      && comment().isEmpty()
      && genre().isEmpty()
      && year() == 0
      && track() == 0;
  }

  /// Returns an immutable snapshot of the text properties exposed by this tag.
  /// The default mapping includes nonempty common fields and nonzero numeric fields.
  ///
  /// @return the exposed text properties
  public PropertyMap properties() {
    var result = PropertyMap.builder();
    if (!title().isEmpty()) {
      result.put("TITLE", title());
    }
    if (!artist().isEmpty()) {
      result.put("ARTIST", artist());
    }
    if (!album().isEmpty()) {
      result.put("ALBUM", album());
    }
    if (!comment().isEmpty()) {
      result.put("COMMENT", comment());
    }
    if (!genre().isEmpty()) {
      result.put("GENRE", genre());
    }
    // Upstream String::number accepts int even though getters return uint.
    if (year() != 0) {
      result.put("DATE", Integer.toString((int) year()));
    }
    if (track() != 0) {
      result.put("TRACKNUMBER", Integer.toString((int) track()));
    }
    return result.build();
  }

  private static void setText(
    MutablePropertyMap values, String key, Consumer<String> setter) {
    if (values.containsKey(key)) {
      var list = values.getOrCreate(key);
      setter.accept(list.getFirst());
      list.removeFirst();
      if (list.isEmpty()) {
        values.remove(key);
      }
    } else {
      setter.accept("");
    }
  }

  private static void setNumber(
    MutablePropertyMap values, String key, LongConsumer setter) {
    if (!values.containsKey(key)) {
      setter.accept(0);
      return;
    }
    var list = values.getOrCreate(key);
    var number = TextNumbers.extractInt(list.getFirst());
    setter.accept(number.ok() ? Integer.toUnsignedLong(number.value()) : 0);
    if (number.ok()) {
      list.removeFirst();
      if (list.isEmpty()) {
        values.remove(key);
      }
    }
  }

  /// Replaces the supported text properties in memory. Omitted keys are cleared; rejected values
  /// are reported without preventing other values from being accepted. This does not save.
  /// Unsupported-data identifiers do not request removal of opaque metadata; use
  /// [#removeUnsupportedProperties(List)] for that operation.
  public PropertyUpdateResult replaceProperties(PropertyMap properties) {
    var values = new MutablePropertyMap(properties);
    values.removeEmpty();
    values.clearUnsupportedData();
    setText(values, "TITLE", this::setTitle);
    setText(values, "ARTIST", this::setArtist);
    setText(values, "ALBUM", this::setAlbum);
    setText(values, "COMMENT", this::setComment);
    setText(values, "GENRE", this::setGenre);
    setNumber(values, "DATE", this::setYear);
    setNumber(values, "TRACKNUMBER", this::setTrack);
    return PropertyUpdateResult.fromRejections(properties, values.snapshot());
  }

  /// Removes opaque metadata identified by [PropertyMap#unsupportedData()] from the live tag.
  /// The identifiers are format-specific. The base implementation has no opaque metadata
  /// to remove. File-bound changes require saving the owner.
  ///
  /// @param unsupported identifiers supplied by this tag
  public void removeUnsupportedProperties(List<String> unsupported) {
  }

  /// Lists complex metadata categories present in this tag, independently of editing support.
  /// The base implementation returns an empty list.
  ///
  /// @return category names, such as `PICTURE`
  public List<String> complexPropertyKeys() {
    return List.of();
  }

  /// Reports whether this tag accepts picture replacement, even when it has no pictures.
  /// Subclasses that accept pictures should override this method.
  public boolean supportsPictureEditing() {
    return false;
  }

  /// Returns structured values for a complex metadata category.
  /// Categories and map fields are format-specific. An absent or unsupported category returns
  /// an empty list. The base implementation returns an empty list for every key.
  ///
  /// @param key the category name
  /// @return the exposed structured values
  public List<Map<String, Variant>> complexProperties(String key) {
    return List.of();
  }

  /// Replaces a complex metadata category in memory using format-specific fields.
  /// An empty list requests removal. The base implementation accepts no categories.
  /// File-bound changes require saving the owner.
  ///
  /// @param key   the category name
  /// @param value replacement structured values
  /// @return `true` if accepted; `false` if the category is unsupported
  public boolean setComplexProperties(String key, List<Map<String, Variant>> value) {
    return false;
  }

  /// Copies common title, artist, album, comment, genre, year and track values between tags.
  /// Existing target values are overwritten.
  /// Changes to a file-bound target are staged until its owner is saved.
  ///
  /// @param source the tag to read
  /// @param target the tag to change
  public static void copyFields(TagBase source, TagBase target) {
    copyFields(source, target, true);
  }

  /// Copies common title, artist, album, comment, genre, year and track values between tags.
  /// If overwrite is disabled, only empty text fields and zero numeric fields are replaced.
  /// Changes to a file-bound target are staged until its owner is saved.
  ///
  /// @param source    the tag to read
  /// @param target    the tag to change
  /// @param overwrite whether populated target fields may be replaced
  public static void copyFields(TagBase source, TagBase target, boolean overwrite) {
    if (overwrite || target.title().isEmpty()) {
      target.setTitle(source.title());
    }
    if (overwrite || target.artist().isEmpty()) {
      target.setArtist(source.artist());
    }
    if (overwrite || target.album().isEmpty()) {
      target.setAlbum(source.album());
    }
    if (overwrite || target.comment().isEmpty()) {
      target.setComment(source.comment());
    }
    if (overwrite || target.genre().isEmpty()) {
      target.setGenre(source.genre());
    }
    if (overwrite || target.year() == 0) {
      target.setYear(source.year());
    }
    if (overwrite || target.track() == 0) {
      target.setTrack(source.track());
    }
  }

  /// Joins multiple values for a common-field getter using the tag display separator.
  protected static String joinTagValues(List<String> values) {
    return String.join(" / ", values);
  }
}
