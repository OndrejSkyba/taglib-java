// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.asf;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.PropertyUpdateResult;
import cz.skyba.taglib.TagBase;
import cz.skyba.taglib.internal.Ascii;
import cz.skyba.taglib.internal.TextNumbers;
import cz.skyba.taglib.internal.WriteGuard;
import cz.skyba.taglib.internal.codec.AsfCodec;
import cz.skyba.taglib.toolkit.Variant;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;

/// A mutable ASF tag with live attribute values.
/// Attribute-list containers are copied when installed, while attribute values remain live.
/// Edits require saving through the owning file or [#save(Path)].
public class Tag extends TagBase {
  private static final Variant EMPTY_VARIANT = new Variant();

  private String title = "", artist = "", copyright = "", comment = "", rating = "";
  private boolean preserveBasic;
  private final TreeMap<String, List<Attribute>> attributes = new TreeMap<>() {
    @Override
    public List<Attribute> put(String key, List<Attribute> values) {
      return super.put(Objects.requireNonNull(key), copyAttributeList(values));
    }
  };

  private static List<Attribute> copyAttributeList(List<Attribute> source) {
    return new ArrayList<>(List.copyOf(source));
  }

  public Tag() {
  }

  private static String upper(String key) {
    return Ascii.upper(key);
  }

  /// Creates a tag from live named attributes. Saving to a path preserves basic Content Description
  /// fields that were not edited through this view.
  ///
  /// @param values attribute values to retain
  public Tag(List<Attribute> values) {
    preserveBasic = true;
    for (var a : values) {
      addAttribute(a.name(), a);
    }
  }

  /// Reads a mutable tag from an ASF file and closes the temporary file handle.
  ///
  /// @param path the input file
  /// @return the detached tag view
  /// @throws UncheckedIOException if storage access fails
  public static Tag read(Path path) {
    try (var file = new AsfFile(path)) {
      return file.tag();
    }
  }

  /// Writes this tag to a path in place, preserving unedited basic fields for attribute-only views.
  /// A failure can leave partial storage changes.
  ///
  /// @param path the ASF file to update
  /// @throws UncheckedIOException if storage access fails
  public void save(Path path) {
    try {
      WriteGuard.run(path, () -> {
        if (preserveBasic) {
          AsfCodec.writeAttributes(path, attributes());
        } else {
          AsfCodec.writeTag(path, this);
        }
      });
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot write audio metadata: " + path, failure);
    }
  }

  /// Live map whose lists and attribute values can be edited directly.
  public final Map<String, List<Attribute>> attributeListMap() {
    return attributes;
  }

  /// Returns a detached list of named live views. Editing a value updates this tag.
  public final List<Attribute> attributes() {
    var out = new ArrayList<Attribute>();
    attributes.forEach((k, v) -> v.forEach(a -> out.add(a.namedView(k))));
    return List.copyOf(out);
  }

  /// Tests whether an exact attribute-name bucket is present, including an empty bucket.
  ///
  /// @param key the stored attribute name
  /// @return whether the bucket exists
  public final boolean contains(String key) {
    return attributes.containsKey(key);
  }

  /// Removes the exact named attribute bucket in memory.
  ///
  /// @param key the stored attribute name
  public final void removeItem(String key) {
    attributes.remove(key);
  }

  /// Returns live attribute values in a detached list. An absent name returns an empty list without adding it.
  public final List<Attribute> attribute(String name) {
    var values = attributes.get(Objects.requireNonNull(name));
    return values == null ? new ArrayList<>() : new ArrayList<>(values);
  }

  /// Stores the supplied attribute by reference. Subsequent edits to it update this tag.
  public final void setAttribute(String name, Attribute value) {
    attributes.put(name, List.of(value));
  }

  /// Copies the list structure while retaining its live attribute values.
  public final void setAttribute(String name, List<Attribute> values) {
    attributes.put(name, values);
  }

  /// Appends a live attribute reference to the named bucket. Changes remain in memory.
  ///
  /// @param name  the attribute name
  /// @param value the live attribute to retain
  public final void addAttribute(String name, Attribute value) {
    Objects.requireNonNull(value);
    attributes.computeIfAbsent(Objects.requireNonNull(name), _ -> new ArrayList<>()).add(value);
  }

  public String title() {
    return title;
  }

  public void setTitle(String value) {
    preserveBasic = false;
    title = Objects.requireNonNull(value);
  }

  public String artist() {
    return artist;
  }

  public void setArtist(String value) {
    preserveBasic = false;
    artist = Objects.requireNonNull(value);
  }

  public String copyright() {
    return copyright;
  }

  public void setCopyright(String value) {
    preserveBasic = false;
    copyright = Objects.requireNonNull(value);
  }

  public String comment() {
    return comment;
  }

  public void setComment(String value) {
    preserveBasic = false;
    comment = Objects.requireNonNull(value);
  }

  public String rating() {
    return rating;
  }

  public void setRating(String value) {
    preserveBasic = false;
    rating = Objects.requireNonNull(value);
  }

  private String joined(String key) {
    return joinTagValues(attributes.getOrDefault(key, List.of()).stream().map(Attribute::toString).toList());
  }

  public String album() {
    return joined("WM/AlbumTitle");
  }

  public void setAlbum(String value) {
    setAttribute("WM/AlbumTitle", new Attribute(value));
  }

  public String genre() {
    return joined("WM/Genre");
  }

  public void setGenre(String value) {
    setAttribute("WM/Genre", new Attribute(value));
  }

  private Attribute first(String key) {
    var values = attributes.get(key);
    if (values.isEmpty()) {
      throw new IllegalStateException("Native ASF getter indexes an empty attribute list");
    }
    return values.getFirst();
  }

  public long year() {
    return contains("WM/Year") ? Integer.toUnsignedLong(TextNumbers.parseInt(first("WM/Year").toString())) : 0;
  }

  public void setYear(long value) {
    setAttribute("WM/Year", new Attribute(Integer.toString((int) value)));
  }

  /// Reads WM/TrackNumber as a DWORD or decimal text, falling back to WM/Track when absent.
  public long track() {
    if (contains("WM/TrackNumber")) {
      var a = first("WM/TrackNumber");
      return a.type() == Attribute.DWordType ? a.toUnsignedInt() : Integer.toUnsignedLong(TextNumbers.parseInt(a.toString()));
    }
    return contains("WM/Track") ? first("WM/Track").toUnsignedInt() : 0;
  }

  public void setTrack(long value) {
    setAttribute("WM/TrackNumber", new Attribute(Integer.toString((int) value)));
  }

  public boolean isEmpty() {
    return super.isEmpty() && copyright().isEmpty() && rating().isEmpty() && attributes.isEmpty();
  }

  /// Projects mapped ASF attributes and overlays basic text fields, reporting unmapped attribute
  /// buckets as unsupported.
  public PropertyMap properties() {
    var projected = AsfCodec.propertiesForAttributes(attributes());
    var result = PropertyMap.builder().putAll(projected);
    var unsupported = new TreeSet<>(projected.unsupportedData());
    if (!title.isEmpty()) {
      result.put("TITLE", title);
    }
    if (!artist.isEmpty()) {
      result.put("ARTIST", artist);
    }
    if (!copyright.isEmpty()) {
      result.put("COPYRIGHT", copyright);
    }
    if (!comment.isEmpty()) {
      result.put("COMMENT", comment);
    }
    for (var e : attributes.entrySet()) {
      if (e.getValue().isEmpty() && !AsfCodec.isMappedAttribute(e.getKey())) {
        unsupported.add(e.getKey());
      }
    }
    unsupported.forEach(result::addUnsupportedData);
    return result.build();
  }

  private void eraseMapped(String name) {
    String normalized = upper(name);
    attributes.keySet().removeIf(key -> upper(key).equals(normalized));
  }

  /// Replaces text properties in memory and reports accepted and rejected values.
  public PropertyUpdateResult replaceProperties(PropertyMap values) {
    var rejected = PropertyMap.builder();
    for (String key : properties().asMap().keySet()) {
      if (!values.containsKey(key) || values.get(key).isEmpty()) {
        switch (key) {
          case "TITLE" -> title = "";
          case "ARTIST" -> artist = "";
          case "COMMENT" -> comment = "";
          case "COPYRIGHT" -> copyright = "";
          default -> {
            String name = AsfCodec.attributeNameForProperty(key);
            if (name != null) {
              eraseMapped(name);
            }
          }
        }
      }
    }
    preserveBasic = false;
    for (var e : values.asMap().entrySet()) {
      String name = AsfCodec.attributeNameForProperty(e.getKey());
      if (name != null) {
        eraseMapped(name);
        try {
          for (var a : AsfCodec.attributesForProperty(e.getKey(), e.getValue())) {
            addAttribute(name, a);
          }
        } catch (IOException failure) {
          throw new UncheckedIOException("Cannot map ASF metadata properties", failure);
        }
      } else {
        switch (e.getKey()) {
          case "TITLE" -> title = String.join(" ", e.getValue());
          case "ARTIST" -> artist = String.join(" ", e.getValue());
          case "COMMENT" -> comment = String.join(" ", e.getValue());
          case "COPYRIGHT" -> copyright = String.join(" ", e.getValue());
          default -> rejected.put(e.getKey(), e.getValue());
        }
      }
    }
    return PropertyUpdateResult.fromRejections(values, rejected.build());
  }

  public void removeUnsupportedProperties(List<String> names) {
    names.forEach(attributes::remove);
  }

  @Override
  public boolean supportsPictureEditing() {
    return true;
  }

  public List<String> complexPropertyKeys() {
    return contains("WM/Picture") ? List.of("PICTURE") : List.of();
  }

  /// Returns the values in the requested complex-property category.
  public List<Map<String, Variant>> complexProperties(String key) {
    if (!upper(key).equals("PICTURE")) {
      return List.of();
    }
    var result = new ArrayList<Map<String, Variant>>();
    for (var a : attributes.getOrDefault("WM/Picture", List.of())) {
      var p = a.toPicture();
      var v = new TreeMap<String, Variant>();
      v.put("data", new Variant(p.picture()));
      v.put("mimeType", new Variant(p.mimeType()));
      v.put("description", new Variant(p.description()));
      v.put("pictureType", new Variant(Picture.typeToString(p.type())));
      result.add(Collections.unmodifiableMap(v));
    }
    return List.copyOf(result);
  }

  /// Replaces the requested complex-property values and reports whether they are supported.
  public boolean setComplexProperties(String key, List<Map<String, Variant>> values) {
    if (!upper(key).equals("PICTURE")) {
      return false;
    }
    removeItem("WM/Picture");
    for (var v : values) {
      addAttribute("WM/Picture", new Attribute(pictureFromProperties(v)));
    }
    return true;
  }

  private static Picture pictureFromProperties(Map<String, Variant> values) {
    var picture = new Picture();
    picture.setPicture(values.getOrDefault("data", EMPTY_VARIANT).toByteVector());
    picture.setMimeType(values.getOrDefault("mimeType", EMPTY_VARIANT).toText());
    picture.setDescription(values.getOrDefault("description", EMPTY_VARIANT).toText());
    picture.setType(Picture.typeFromString(values.getOrDefault("pictureType", EMPTY_VARIANT).toText()));
    return picture;
  }

}
