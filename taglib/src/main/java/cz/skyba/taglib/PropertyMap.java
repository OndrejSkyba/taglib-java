// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;

import cz.skyba.taglib.internal.Ascii;

import java.util.*;

/**
 * An immutable text property map with ordered values and opaque metadata identifiers.
 * ASCII letters in keys are uppercased; other characters are unchanged. Empty value lists
 * remain present until explicitly removed. Use {@link Builder#put(String, List)} to replace
 * values and {@link Builder#add(String, List)} to append them. Builders and constructor inputs
 * are copied, so subsequent edits do not change a snapshot.
 */
public final class PropertyMap {
  private final Map<String, List<String>> values;
  private final List<String> unsupported;
  public static final PropertyMap EMPTY = new PropertyMap(Map.of());

  /**
   * Copies and ASCII-normalizes source keys in sorted order, merging values whose keys normalize
   * identically.
   */
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

  /**
   * Returns the immutable map of normalized keys to immutable, ordered value lists.
   *
   * @return the text property map
   */
  public Map<String, List<String>> asMap() {
    return values;
  }

  /**
   * Returns the ordered values for a key using ASCII case-insensitive lookup.
   *
   * @param key the property key
   * @return an immutable value list, or an empty list if the key is absent
   */
  public List<String> get(String key) {
    return values.getOrDefault(normalize(key), List.of());
  }

  /**
   * Returns the first value for a key using ASCII case-insensitive lookup.
   *
   * @param key the property key
   * @return the first value, or an empty string if the key is absent or has no values
   */
  public String first(String key) {
    var list = get(key);
    return list.isEmpty() ? "" : list.getFirst();
  }

  /**
   * Tests whether the text map has no keys. Opaque metadata identifiers are not counted.
   *
   * @return whether there are no text property keys
   */
  public boolean isEmpty() {
    return values.isEmpty();
  }

  /**
   * Returns the number of text property keys, including keys with empty value lists.
   *
   * @return the key count
   */
  public int size() {
    return values.size();
  }

  /**
   * Tests whether a key is present, including a key with an empty value list.
   *
   * @param key the property key, matched using ASCII case-insensitive lookup
   * @return whether the key is present
   */
  public boolean containsKey(String key) {
    return values.containsKey(normalize(key));
  }

  /**
   * Compares complete ordered value lists, ignoring unsupported-data identifiers.
   */
  public boolean containsAll(PropertyMap other) {
    return values.entrySet().containsAll(other.values.entrySet());
  }

  /**
   * Returns stored values, or an immutable copy of the fallback when the key is absent.
   * The fallback is inspected only when needed; an existing empty list is a stored value.
   */
  public List<String> getOrDefault(String key, List<String> defaultValue) {
    var stored = values.get(normalize(key));
    return stored != null ? stored : List.copyOf(defaultValue);
  }

  /**
   * Returns identifiers for opaque metadata not represented by the text map.
   * These identifiers can be passed to a tag or file's opaque-metadata removal operation.
   *
   * @return an immutable identifier list
   */
  public List<String> unsupportedData() {
    return unsupported;
  }

  /**
   * Creates an independent builder initialized with these values and opaque metadata identifiers.
   *
   * @return a builder whose edits do not change this snapshot
   */
  public Builder toBuilder() {
    return new Builder(this);
  }

  /**
   * Creates an empty property-map builder.
   *
   * @return a new empty builder
   */
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

  /**
   * Builds property snapshots with ASCII-uppercase keys and ordered text values.
   * Each input value list is copied. A built snapshot is independent of subsequent builder
   * changes. Methods return this builder unless they return a completed snapshot.
   */
  public static final class Builder {
    private final Map<String, List<String>> values = new LinkedHashMap<>();
    private final List<String> unsupported = new ArrayList<>();

    private Builder(PropertyMap source) {
      source.values.forEach((k, v) -> values.put(k, new ArrayList<>(v)));
      unsupported.addAll(source.unsupported);
    }

    /**
     * Replaces all values for the normalized key.
     *
     * @param key   the key or opaque metadata identifier
     * @param value the text value
     * @return this builder
     */
    public Builder put(String key, String value) {
      return put(key, List.of(value));
    }

    /**
     * Replaces all values for the normalized key. An empty list retains the key with no values.
     *
     * @param key   the key or opaque metadata identifier
     * @param value text values to copy in order
     * @return this builder
     */
    public Builder put(String key, List<String> value) {
      values.put(normalize(key), new ArrayList<>(List.copyOf(value)));
      return this;
    }

    /**
     * Appends values to the normalized key, creating it if absent.
     *
     * @param key   the key or opaque metadata identifier
     * @param value the text value
     * @return this builder
     */
    public Builder add(String key, String value) {
      values
        .computeIfAbsent(normalize(key), _ -> new ArrayList<>())
        .add(Objects.requireNonNull(value));
      return this;
    }

    /**
     * Removes a normalized key and all its values.
     *
     * @param key the key or opaque metadata identifier
     * @return this builder
     */
    public Builder remove(String key) {
      values.remove(normalize(key));
      return this;
    }

    /**
     * Replaces values for each key in the source map; other keys and opaque identifiers are unchanged.
     *
     * @param map the source property map
     * @return this builder
     */
    public Builder putAll(PropertyMap map) {
      map.asMap().forEach(this::put);
      return this;
    }

    /**
     * Appends values to the normalized key, creating it if absent.
     *
     * @param key   the key or opaque metadata identifier
     * @param value text values to copy in order
     * @return this builder
     */
    public Builder add(String key, List<String> value) {
      var copy = List.copyOf(value);
      values.computeIfAbsent(normalize(key), _ -> new ArrayList<>()).addAll(copy);
      return this;
    }

    /**
     * Removes every text key present in the source map, regardless of its values.
     *
     * @param other the source property map
     * @return this builder
     */
    public Builder removeAll(PropertyMap other) {
      other.values.keySet().forEach(values::remove);
      return this;
    }

    /**
     * Appends the source's values and opaque metadata identifiers without replacing existing values.
     *
     * @param other the source property map
     * @return this builder
     */
    public Builder merge(PropertyMap other) {
      other.values.forEach(this::add);
      unsupported.addAll(other.unsupported);
      return this;
    }

    /**
     * Appends an opaque metadata identifier without removing any metadata.
     *
     * @param key the key or opaque metadata identifier
     * @return this builder
     */
    public Builder addUnsupportedData(String key) {
      unsupported.add(Objects.requireNonNull(key));
      return this;
    }

    /**
     * Removes text keys whose value lists are empty, leaving opaque metadata identifiers unchanged.
     *
     * @return this builder
     */
    public Builder removeEmpty() {
      values.entrySet().removeIf(entry -> entry.getValue().isEmpty());
      return this;
    }

    /**
     * Clears opaque metadata identifiers in this builder. This does not remove metadata from a file or tag.
     *
     * @return this builder
     */
    public Builder clearUnsupportedData() {
      unsupported.clear();
      return this;
    }

    /**
     * Removes all text keys and opaque metadata identifiers from this builder.
     *
     * @return this builder
     */
    public Builder clear() {
      values.clear();
      unsupported.clear();
      return this;
    }

    /**
     * Creates an immutable snapshot independent of subsequent builder changes.
     *
     * @return the property snapshot
     */
    public PropertyMap build() {
      return new PropertyMap(values, unsupported);
    }
  }
}
