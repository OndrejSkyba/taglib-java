// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.matroska;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.PropertyUpdateResult;
import cz.skyba.taglib.TagBase;
import cz.skyba.taglib.internal.Ascii;
import cz.skyba.taglib.internal.TextNumbers;
import cz.skyba.taglib.internal.WriteGuard;
import cz.skyba.taglib.internal.codec.MatroskaCodec;
import cz.skyba.taglib.toolkit.Variant;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;

/// A mutable collection of Matroska [SimpleTag] values.
/// Values are copied on insertion and retrieval. When bound to a [MatroskaFile], edits are staged
/// until that file is saved. [#save(Path)] writes directly to the supplied path.
public class Tag extends TagBase {
  private static final Variant EMPTY_VARIANT = new Variant();

  private final CopyingValues<SimpleTag> values = new CopyingValues<>(SimpleTag::new);
  private final ArrayList<SimpleTag> tags = values.stored();
  private String segmentTitle = "";
  private Runnable changed = () -> {
  };
  private final boolean snapshotValue;

  public Tag() {
    snapshotValue = false;
  }

  /// Creates a collection with copied input values.
  ///
  /// @param values the values to copy
  public Tag(List<SimpleTag> values) {
    snapshotValue = true;
    this.values.addAll(values);
  }

  Tag(List<SimpleTag> values, Runnable changed) {
    snapshotValue = false;
    this.values.addAll(values);
    this.changed = changed;
  }

  void setSegmentTitle(String title) {
    segmentTitle = title;
  }

  /// Reads SimpleTag values from a file and closes temporary handles.
  ///
  /// @param path the Matroska file to inspect
  /// @return the decoded collection
  /// @throws UncheckedIOException if storage access fails
  public static Tag read(Path path) {
    try {
      return new Tag(MatroskaCodec.readSimpleTags(path));
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata" + ": " + path, failure);
    }
  }

  /// Writes this SimpleTag collection to the supplied path in place.
  /// An empty collection requests removal. A failure can leave partial changes.
  ///
  /// @param path the Matroska file to update
  /// @throws UncheckedIOException if storage access fails
  public final void save(Path path) {
    try {
      WriteGuard.run(path, () -> MatroskaCodec.writeSimpleTags(path, simpleTagsList()));
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot write audio metadata: " + path, failure);
    }
  }

  /// Returns an immutable snapshot of copied SimpleTag values.
  ///
  /// @return detached tags
  public final List<SimpleTag> simpleTags() {
    return simpleTagsList();
  }

  /// Returns an immutable list snapshot with copied values.
  ///
  /// @return detached collection values
  public final List<SimpleTag> simpleTagsList() {
    return values.snapshot();
  }

  /// Appends a copy of the supplied value in memory and marks a bound container changed.
  ///
  /// @param tag the value to copy
  public final void addSimpleTag(SimpleTag tag) {
    values.add(tag);
    changed.run();
  }

  /// Appends copied tags in source order and marks a bound container changed.
  ///
  /// @param values the tags to copy
  public final void addSimpleTags(List<SimpleTag> values) {
    this.values.addAll(values);
    changed.run();
  }

  /// Inserts a copied tag at an unsigned 32-bit position, or appends when the position is beyond
  /// the list. The change remains in memory.
  ///
  /// @param index the insertion position using its low 32 bits
  /// @param tag   the value to copy
  public final void insertSimpleTag(long index, SimpleTag tag) {
    long n = unsignedIndex(index);
    values.insert(n < tags.size() ? (int) n : tags.size(), tag);
    changed.run();
  }

  /// Removes the tag at the unsigned 32-bit index, ignoring an out-of-range index. The edit remains in memory.
  ///
  /// @param index the position using its low 32 bits
  public final void removeSimpleTag(long index) {
    long n = unsignedIndex(index);
    if (n < tags.size()) {
      tags.remove((int) n);
      changed.run();
    }
  }

  /// Removes the first tag matching the name, target level and all supplied scope UIDs exactly. The edit remains in memory.
  ///
  /// @param name   the exact tag name
  /// @param target the exact target level
  public final void removeSimpleTag(String name, SimpleTag.TargetTypeValue target) {
    removeSimpleTag(name, target, 0);
  }

  /// Removes the first tag matching the name, target level and all supplied scope UIDs exactly. The edit remains in memory.
  ///
  /// @param name   the exact tag name
  /// @param target the exact target level
  /// @param track  the exact track UID; omitted scope parameters default to zero
  public final void removeSimpleTag(String name, SimpleTag.TargetTypeValue target, long track) {
    removeSimpleTag(name, target, track, 0, 0, 0);
  }

  /// Removes the first tag matching the name, target level and all supplied scope UIDs exactly. The edit remains in memory.
  ///
  /// @param name    the exact tag name
  /// @param target  the exact target level
  /// @param track   the exact track UID; omitted scope parameters default to zero
  /// @param edition the exact edition UID
  public final void removeSimpleTag(String name, SimpleTag.TargetTypeValue target, long track, long edition) {
    removeSimpleTag(name, target, track, edition, 0, 0);
  }

  /// Removes the first tag matching the name, target level and all supplied scope UIDs exactly. The edit remains in memory.
  ///
  /// @param name    the exact tag name
  /// @param target  the exact target level
  /// @param track   the exact track UID; omitted scope parameters default to zero
  /// @param edition the exact edition UID
  /// @param chapter the exact chapter UID
  public final void removeSimpleTag(String name, SimpleTag.TargetTypeValue target, long track, long edition, long chapter) {
    removeSimpleTag(name, target, track, edition, chapter, 0);
  }

  /// Removes the first tag matching the name, target level and all supplied scope UIDs exactly. The edit remains in memory.
  ///
  /// @param name       the exact tag name
  /// @param target     the exact target level
  /// @param track      the exact track UID; omitted scope parameters default to zero
  /// @param edition    the exact edition UID
  /// @param chapter    the exact chapter UID
  /// @param attachment the exact attachment UID
  public final void removeSimpleTag(
    String name,
    SimpleTag.TargetTypeValue target,
    long track,
    long edition,
    long chapter,
    long attachment
  ) {
    for (int i = 0; i < tags.size(); i++) {
      var t = tags.get(i);
      if (matchesScope(t, name, target, track, edition, chapter, attachment)) {
        tags.remove(i);
        changed.run();
        return;
      }
    }
  }

  private static long unsignedIndex(long index) {
    return index & 0xffff_ffffL;
  }

  private static boolean matchesScope(
    SimpleTag tag, String name, SimpleTag.TargetTypeValue target,
    long track, long edition, long chapter, long attachment
  ) {
    return tag.name().equals(name) && tag.targetType() == target.value() && tag.trackUid() == track
      && tag.editionUid() == edition && tag.chapterUid() == chapter && tag.attachmentUid() == attachment;
  }

  /// Removes all SimpleTag values in memory and marks a bound container changed.
  public final void clearSimpleTags() {
    this.values.clear();
    changed.run();
  }

  private void remove(Predicate<SimpleTag> predicate) {
    boolean result = tags.removeIf(predicate);
    if (result) {
      changed.run();
    }
  }

  private static boolean unscoped(SimpleTag t) {
    return t.trackUid() == 0 && t.editionUid() == 0 && t.chapterUid() == 0 && t.attachmentUid() == 0;
  }

  private static boolean exposed(SimpleTag t) {
    String key = MatroskaCodec.propertyKeyForSimpleTag(t.name(), t.targetType());
    return t.type() == SimpleTag.ValueType.StringType && unscoped(t) && key != null && !key.isEmpty();
  }

  private String common(String key) {
    return MatroskaCodec.commonProperty(tags, key);
  }

  private void setTag(String key, String value) {
    var replacement = MatroskaCodec.simpleTagForProperty(key, value);
    if (replacement == null) {
      return;
    }
    remove(t -> t.name().equals(replacement.name()) && t.targetType() == replacement.targetType());
    if (!value.isEmpty()) {
      tags.add(replacement);
    }
    changed.run();
  }

  /// Returns the mapped TITLE value, falling back to the segment title when the tag value is empty.
  @Override
  public String title() {
    String value = common("TITLE");
    return value.isEmpty() ? segmentTitle : value;
  }

  @Override
  public String artist() {
    return common("ARTIST");
  }

  @Override
  public String album() {
    return common("ALBUM");
  }

  @Override
  public String comment() {
    return common("COMMENT");
  }

  @Override
  public String genre() {
    return common("GENRE");
  }

  private long number(String key) {
    String value = common(key);
    if (value.isEmpty()) {
      return 0;
    }
    return Integer.toUnsignedLong(TextNumbers.parseInt(value.split("-", -1)[0]));
  }

  @Override
  public long year() {
    return number("DATE");
  }

  @Override
  public long track() {
    return number("TRACKNUMBER");
  }

  @Override
  public void setTitle(String value) {
    setTag("TITLE", value);
  }

  @Override
  public void setArtist(String value) {
    setTag("ARTIST", value);
  }

  @Override
  public void setAlbum(String value) {
    setTag("ALBUM", value);
  }

  @Override
  public void setComment(String value) {
    setTag("COMMENT", value);
  }

  @Override
  public void setGenre(String value) {
    setTag("GENRE", value);
  }

  @Override
  public void setYear(long value) {
    value &= 0xffff_ffffL;
    setTag("DATE", value == 0 ? "" : Integer.toString((int) value));
  }

  @Override
  public void setTrack(long value) {
    value &= 0xffff_ffffL;
    setTag("TRACKNUMBER", value == 0 ? "" : Integer.toString((int) value));
  }

  @Override
  public boolean isEmpty() {
    return tags.isEmpty();
  }

  /// Exports unscoped text tags using their names and target levels; unmapped text names are
  /// reported as unsupported.
  @Override
  public PropertyMap properties() {
    var result = PropertyMap.builder();
    for (var t : tags) {
      if (t.type() == SimpleTag.ValueType.StringType && unscoped(t)) {
        String key = MatroskaCodec.propertyKeyForSimpleTag(t.name(), t.targetType());
        if (key == null || key.isEmpty()) {
          result.addUnsupportedData(t.name());
        } else {
          result.add(key, t.toString());
        }
      }
    }
    return result.build();
  }

  @Override
  public PropertyUpdateResult replaceProperties(PropertyMap values) {
    remove(Tag::exposed);
    var rejected = PropertyMap.builder();
    for (var e : values.asMap().entrySet()) {
      for (var v : e.getValue()) {
        var tag = MatroskaCodec.simpleTagForProperty(e.getKey(), v);
        if (tag == null) {
          rejected.put(e.getKey(), e.getValue());
        } else {
          tags.add(tag);
          changed.run();
        }
      }
    }
    changed.run();
    return PropertyUpdateResult.fromRejections(values, rejected.build());
  }

  @Override
  public void removeUnsupportedProperties(List<String> names) {
    remove(t -> names.contains(t.name()));
  }

  @Override
  public List<String> complexPropertyKeys() {
    var keys = new LinkedHashSet<String>();
    for (var t : tags) {
      if (!exposed(t)) {
        keys.add(t.name());
      }
    }
    return List.copyOf(keys);
  }

  @Override
  public List<Map<String, Variant>> complexProperties(String key) {
    var result = new ArrayList<Map<String, Variant>>();
    if (Ascii.upper(key).equals("PICTURE")) {
      return List.of();
    }
    for (var t : tags) {
      if (t.name().equals(key) && !exposed(t)) {
        var p = new TreeMap<String, Variant>();
        p.put(t.type() == SimpleTag.ValueType.StringType ? "value" : "data",
          t.type() == SimpleTag.ValueType.StringType ? new Variant(t.toString()) : new Variant(t.toByteVector()));
        p.put("name", new Variant(t.name()));
        if (t.targetType() != 0) {
          p.put("targetTypeValue", new Variant(t.targetType()));
        }
        if (t.trackUid() != 0) {
          p.put("trackUid", Variant.fromUnsignedLong(t.trackUid()));
        }
        if (t.editionUid() != 0) {
          p.put("editionUid", Variant.fromUnsignedLong(t.editionUid()));
        }
        if (t.chapterUid() != 0) {
          p.put("chapterUid", Variant.fromUnsignedLong(t.chapterUid()));
        }
        if (t.attachmentUid() != 0) {
          p.put("attachmentUid", Variant.fromUnsignedLong(t.attachmentUid()));
        }
        p.put("language", new Variant(t.language()));
        p.put("defaultLanguage", new Variant(t.defaultLanguage()));
        result.add(Collections.unmodifiableMap(p));
      }
    }
    return List.copyOf(result);
  }

  private static int targetTypeInt(Variant target) {
    return switch (target.type()) {
      case UNSIGNED_INT -> (int) target.toUnsignedInt();
      case LONG -> (int) target.toLong();
      case UNSIGNED_LONG -> (int) target.toUnsignedLong();
      default -> target.toInt();
    };
  }

  private static SimpleTag.Value simpleTagValue(Map<String, Variant> values) {
    return values.containsKey("data")
      ? new SimpleTag.Binary(v(values, "data").toByteVector().data())
      : new SimpleTag.Text(v(values, "value").toText());
  }

  private static Variant v(Map<String, Variant> p, String key) {
    return p.getOrDefault(key, EMPTY_VARIANT);
  }

  @Override
  public boolean setComplexProperties(String key, List<Map<String, Variant>> values) {
    if (Ascii.upper(key).equals("PICTURE")) {
      return false;
    }
    remove(t -> t.name().equals(key) && !exposed(t));
    boolean result = false;
    for (var p : values) {
      if (v(p, "name").toText().equals(key) && (p.containsKey("data") || p.containsKey("value"))) {
        var target = p.getOrDefault("targetTypeValue", new Variant(0));
        int type = targetTypeInt(target);
        tags.add(new SimpleTag(key, simpleTagValue(p), type, v(p, "language").toText(),
          p.getOrDefault("defaultLanguage", new Variant(true)).toBoolean(), v(p, "trackUid").toUnsignedLong(),
          v(p, "editionUid").toUnsignedLong(), v(p, "chapterUid").toUnsignedLong(), v(p, "attachmentUid").toUnsignedLong()));
        changed.run();
        result = true;
      }
    }
    return result;
  }

  @Override
  public boolean equals(Object other) {
    return this == other || snapshotValue && other instanceof Tag t && t.snapshotValue && tags.equals(t.tags);
  }

  @Override
  public int hashCode() {
    return snapshotValue ? tags.hashCode() : System.identityHashCode(this);
  }
}
