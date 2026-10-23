// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;

import cz.skyba.taglib.internal.Ascii;

import java.util.*;

/// An immutable audio text property map with ordered values and opaque metadata identifiers.
/// ASCII letters in keys are uppercased; other characters are unchanged. Empty value lists
/// remain present until explicitly removed. Construction copies mutable inputs, so later edits
/// to a builder or source collection do not change a snapshot.
public final class PropertyMap {
  private final Map<String, List<String>> values;
  private final List<String> unsupported;
  public static final PropertyMap EMPTY = new PropertyMap(Map.of());

  /// Copies and ASCII-normalizes source keys in sorted order, merging values whose keys normalize
  /// identically.
  public PropertyMap(Map<String, ? extends List<String>> source) {
    var copy = new TreeMap<String, List<String>>();
    var rejected = new ArrayList<String>();
    // SimplePropertyMap iterates in String order, including before case folding.
    new TreeMap<>(source)
      .forEach(
        (k, v) -> {
          var key = normalize(k);
          if (key.isEmpty()) {
            rejected.add(key);
          } else {
            copy.computeIfAbsent(key, _ -> new ArrayList<>()).addAll(List.copyOf(v));
          }
        });
    values = freeze(copy);
    unsupported = List.copyOf(rejected);
  }

  private PropertyMap(Map<String, List<String>> source, List<String> unsupported) {
    values = freeze(source);
    this.unsupported = List.copyOf(unsupported);
  }

  private static Map<String, List<String>> freeze(Map<String, List<String>> source) {
    var copy = new TreeMap<String, List<String>>();
    source.forEach((key, value) -> copy.put(key, List.copyOf(value)));
    return Collections.unmodifiableMap(copy);
  }

  private static String normalize(String key) {
    return Ascii.upper(key);
  }

  /// Returns the immutable map of normalized keys to immutable, ordered value lists.
  ///
  /// @return the text property map
  public Map<String, List<String>> asMap() {
    return values;
  }

  /// Returns a property's values.
  ///
  /// @param key the property key, matched using ASCII case-insensitive lookup
  /// @return an immutable, ordered value list, or an empty list if absent
  /// @throws NullPointerException if the key is null
  public List<String> get(String key) {
    return values.getOrDefault(normalize(key), List.of());
  }

  /// Returns a property's values.
  ///
  /// @param key the property key
  /// @return an immutable, ordered value list, or an empty list if absent
  /// @throws NullPointerException if the key is null
  public List<String> get(PropertyKey key) {
    return get(key.key());
  }

  /// Returns a property's first value.
  ///
  /// @param key the property key, matched using ASCII case-insensitive lookup
  /// @return the first value, or an empty string if the property is absent or has no values
  /// @throws NullPointerException if the key is null
  public String first(String key) {
    var list = get(key);
    return list.isEmpty() ? "" : list.getFirst();
  }

  /// Returns a property's first value.
  ///
  /// @param key the property key
  /// @return the first value, or an empty string if the property is absent or has no values
  /// @throws NullPointerException if the key is null
  public String first(PropertyKey key) {
    return first(key.key());
  }

  /// Tests whether the text map has no keys. Opaque metadata identifiers are not counted.
  ///
  /// @return whether there are no text property keys
  public boolean isEmpty() {
    return values.isEmpty();
  }

  /// Returns the number of text property keys, including keys with empty value lists.
  ///
  /// @return the key count
  public int size() {
    return values.size();
  }

  /// Tests whether a property is present.
  ///
  /// @param key the property key, matched using ASCII case-insensitive lookup
  /// @return whether the property is present, including one with an empty value list
  /// @throws NullPointerException if the key is null
  public boolean containsKey(String key) {
    return values.containsKey(normalize(key));
  }

  /// Tests whether a property is present.
  ///
  /// @param key the property key
  /// @return whether the property is present, including one with an empty value list
  /// @throws NullPointerException if the key is null
  public boolean containsKey(PropertyKey key) {
    return containsKey(key.key());
  }

  /// Compares complete ordered value lists, ignoring unsupported-data identifiers.
  public boolean containsAll(PropertyMap other) {
    return values.entrySet().containsAll(other.values.entrySet());
  }

  /// Returns a property's values with a fallback for an absent property.
  ///
  /// @param key the property key, matched using ASCII case-insensitive lookup
  /// @param defaultValue fallback values, used only if absent; may be null if the property is present
  /// @return the stored immutable value list, even if empty, or an immutable copy of the fallback
  /// @throws NullPointerException if the key is null, or a required fallback is null or contains null
  public List<String> getOrDefault(String key, List<String> defaultValue) {
    var stored = values.get(normalize(key));
    return stored != null ? stored : List.copyOf(defaultValue);
  }

  /// Returns a property's values with a fallback for an absent property.
  ///
  /// @param key the property key
  /// @param defaultValue fallback values, used only if absent; may be null if the property is present
  /// @return the stored immutable value list, even if empty, or an immutable copy of the fallback
  /// @throws NullPointerException if the key is null, or a required fallback is null or contains null
  public List<String> getOrDefault(PropertyKey key, List<String> defaultValue) {
    return getOrDefault(key.key(), defaultValue);
  }

  /// Returns identifiers for opaque metadata not represented by the text map.
  /// These identifiers can be passed to a tag or file's opaque-metadata removal operation.
  ///
  /// @return an immutable identifier list
  public List<String> unsupportedData() {
    return unsupported;
  }

  /// Creates an independent builder initialized with these values and opaque metadata identifiers.
  ///
  /// @return a builder whose edits do not change this snapshot
  public Builder toBuilder() {
    return new Builder(this);
  }

  public static Builder builder() {
    return new Builder(EMPTY);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof PropertyMap p
      && values.equals(p.values)
      && unsupported.equals(p.unsupported);
  }

  @Override
  public int hashCode() {
    return Objects.hash(values, unsupported);
  }

  @Override
  public String toString() {
    var result = new StringBuilder();
    values.forEach(
      (key, list) -> result.append(key).append('=').append(String.join(", ", list)).append('\n'));
    if (!unsupported.isEmpty()) {
      result.append("Unsupported Data: ").append(String.join(", ", unsupported)).append('\n');
    }
    return result.toString();
  }

  /// Builds property snapshots with ASCII-uppercase keys and ordered text values.
  /// Each input value list is copied. A built snapshot is independent of subsequent builder
  /// changes.
  public static final class Builder {
    private final Map<String, List<String>> values = new LinkedHashMap<>();
    private final List<String> unsupported = new ArrayList<>();

    private Builder(PropertyMap source) {
      source.values.forEach((k, v) -> values.put(k, new ArrayList<>(v)));
      unsupported.addAll(source.unsupported);
    }

    /// Replaces a property's values with a single value.
    ///
    /// @param key the property key, normalized to ASCII uppercase
    /// @param value the text value
    /// @return this builder
    /// @throws NullPointerException if the key or value is null
    public Builder put(String key, String value) {
      return put(key, List.of(value));
    }

    /// Replaces a property's values with a single value.
    ///
    /// @param key the property key
    /// @param value the text value
    /// @return this builder
    /// @throws NullPointerException if the key or value is null
    public Builder put(PropertyKey key, String value) {
      return put(key.key(), value);
    }

    /// Replaces a property's values.
    ///
    /// @param key the property key, normalized to ASCII uppercase
    /// @param value text values to copy in order; an empty list retains the key with no values
    /// @return this builder
    /// @throws NullPointerException if the key, list, or any value is null
    public Builder put(String key, List<String> value) {
      values.put(normalize(key), new ArrayList<>(List.copyOf(value)));
      return this;
    }

    /// Replaces a property's values.
    ///
    /// @param key the property key
    /// @param value text values to copy in order; an empty list retains the key with no values
    /// @return this builder
    /// @throws NullPointerException if the key, list, or any value is null
    public Builder put(PropertyKey key, List<String> value) {
      return put(key.key(), value);
    }

    /// Appends a value to a property, creating it if absent.
    ///
    /// @param key the property key, normalized to ASCII uppercase
    /// @param value the text value
    /// @return this builder
    /// @throws NullPointerException if the key or value is null
    public Builder add(String key, String value) {
      values
        .computeIfAbsent(normalize(key), _ -> new ArrayList<>())
        .add(Objects.requireNonNull(value));
      return this;
    }

    /// Appends a value to a property, creating it if absent.
    ///
    /// @param key the property key
    /// @param value the text value
    /// @return this builder
    /// @throws NullPointerException if the key or value is null
    public Builder add(PropertyKey key, String value) {
      return add(key.key(), value);
    }

    /// Removes a property and all its values.
    ///
    /// @param key the property key, normalized to ASCII uppercase
    /// @return this builder
    /// @throws NullPointerException if the key is null
    public Builder remove(String key) {
      values.remove(normalize(key));
      return this;
    }

    /// Removes a property and all its values.
    ///
    /// @param key the property key
    /// @return this builder
    /// @throws NullPointerException if the key is null
    public Builder remove(PropertyKey key) {
      return remove(key.key());
    }

    /// Replaces values for each key in the source map; other keys and opaque identifiers are unchanged.
    ///
    /// @param map the source property map
    /// @return this builder
    public Builder putAll(PropertyMap map) {
      map.asMap().forEach(this::put);
      return this;
    }

    /// Appends values to a property, creating it if absent.
    ///
    /// @param key the property key, normalized to ASCII uppercase
    /// @param value text values to copy in order
    /// @return this builder
    /// @throws NullPointerException if the key, list, or any value is null
    public Builder add(String key, List<String> value) {
      var copy = List.copyOf(value);
      values.computeIfAbsent(normalize(key), _ -> new ArrayList<>()).addAll(copy);
      return this;
    }

    /// Appends values to a property, creating it if absent.
    ///
    /// @param key the property key
    /// @param value text values to copy in order
    /// @return this builder
    /// @throws NullPointerException if the key, list, or any value is null
    public Builder add(PropertyKey key, List<String> value) {
      return add(key.key(), value);
    }

    /// Removes every text key present in the source map, regardless of its values.
    ///
    /// @param other the source property map
    /// @return this builder
    public Builder removeAll(PropertyMap other) {
      other.values.keySet().forEach(values::remove);
      return this;
    }

    /// Appends the source's values and opaque metadata identifiers without replacing existing values.
    ///
    /// @param other the source property map
    /// @return this builder
    public Builder merge(PropertyMap other) {
      other.values.forEach(this::add);
      unsupported.addAll(other.unsupported);
      return this;
    }

    /// Appends an opaque metadata identifier without removing any metadata.
    ///
    /// @param key the key or opaque metadata identifier
    /// @return this builder
    public Builder addUnsupportedData(String key) {
      unsupported.add(Objects.requireNonNull(key));
      return this;
    }

    /// Removes text keys whose value lists are empty, leaving opaque metadata identifiers unchanged.
    ///
    /// @return this builder
    public Builder removeEmpty() {
      values.entrySet().removeIf(entry -> entry.getValue().isEmpty());
      return this;
    }

    /// Clears opaque metadata identifiers in this builder. This does not remove metadata from a file or tag.
    ///
    /// @return this builder
    public Builder clearUnsupportedData() {
      unsupported.clear();
      return this;
    }

    /// Removes all text keys and opaque metadata identifiers from this builder.
    ///
    /// @return this builder
    public Builder clear() {
      values.clear();
      unsupported.clear();
      return this;
    }

    /// Creates an immutable snapshot independent of subsequent builder changes.
    ///
    /// @return the property snapshot
    public PropertyMap build() {
      return new PropertyMap(values, unsupported);
    }
  }
}
