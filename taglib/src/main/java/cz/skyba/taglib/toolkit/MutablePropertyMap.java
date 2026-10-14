// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.toolkit;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.internal.Ascii;

import java.util.*;

/**
 * Mutable text properties with ASCII-uppercase keys and ordered values.
 * {@link #getOrCreate(String)} and {@link #find(String)} expose live lists. Detached lookup
 * methods copy lists, and {@link #snapshot()} creates an immutable property map. Opaque
 * metadata identifiers are maintained separately from text keys.
 */
public final class MutablePropertyMap implements Iterable<Map.Entry<String, List<String>>> {
  private final NavigableMap<String, List<String>> values = new TreeMap<>();
  private final List<String> unsupported = new ArrayList<>();

  /**
   * Creates an empty map.
   */
  public MutablePropertyMap() {
  }

  /**
   * Creates an independent copy of text values and opaque metadata identifiers.
   *
   * @param other the source map
   */
  public MutablePropertyMap(MutablePropertyMap other) {
    other.values.forEach((k, v) -> values.put(k, new ArrayList<>(v)));
    unsupported.addAll(other.unsupported);
  }

  /**
   * Copies source keys in sorted order, normalizes ASCII case and appends values for
   * keys that normalize identically. An empty source key becomes an opaque identifier.
   *
   * @param source the source map
   */
  public MutablePropertyMap(Map<String, ? extends List<String>> source) {
    new TreeMap<>(source)
      .forEach(
        (key, value) -> {
          if (key.isEmpty()) {
            unsupported.add("");
          } else {
            add(key, value);
          }
        });
  }

  /**
   * Creates an independent copy of text values and opaque metadata identifiers.
   *
   * @param snapshot the source map
   */
  public MutablePropertyMap(PropertyMap snapshot) {
    snapshot.asMap().forEach(this::add);
    unsupported.addAll(snapshot.unsupportedData());
  }

  private static String key(String value) {
    return Ascii.upper(value);
  }

  /**
   * Appends values to a normalized key, copying the input list.
   *
   * @param key   the property key
   * @param value values to append
   * @return this map
   */
  public MutablePropertyMap add(String key, List<String> value) {
    var copy = List.copyOf(value);
    values.computeIfAbsent(key(key), _ -> new ArrayList<>()).addAll(copy);
    return this;
  }

  /**
   * Replaces values for a normalized key, copying the input list. Empty lists retain the key.
   *
   * @param key   the property key
   * @param value replacement values
   * @return this map
   */
  public MutablePropertyMap put(String key, List<String> value) {
    values.put(key(key), new ArrayList<>(List.copyOf(value)));
    return this;
  }

  /**
   * Tests whether a normalized key is present, including keys with empty value lists.
   *
   * @param key the key to test
   * @return whether the key is present
   */
  public boolean containsKey(String key) {
    return values.containsKey(key(key));
  }

  /**
   * Tests whether every source key has an identical ordered value list in this map.
   * Opaque metadata identifiers are not compared.
   *
   * @param other the source map
   * @return whether every source entry matches
   */
  public boolean containsAll(MutablePropertyMap other) {
    return values.entrySet().containsAll(other.values.entrySet());
  }

  /**
   * Returns a live editable value list, adding an empty list when the key is absent.
   *
   * @param key the property key
   * @return the live value list
   */
  public List<String> getOrCreate(String key) {
    return values.computeIfAbsent(key(key), _ -> new ArrayList<>());
  }

  /**
   * Returns an independent value list without inserting an absent key.
   *
   * @param key the property key
   * @return copied values, or an empty list if absent
   */
  public List<String> values(String key) {
    return getOrDefault(key, List.of());
  }

  /**
   * Returns an independent copy of stored values or the supplied fallback.
   * An existing empty list is a stored value and does not select the fallback.
   *
   * @param key          the property key
   * @param defaultValue the fallback values for an absent key
   * @return a detached editable list
   */
  public List<String> getOrDefault(String key, List<String> defaultValue) {
    return new ArrayList<>(values.getOrDefault(key(key), defaultValue));
  }

  /**
   * Looks up a normalized key without inserting it.
   *
   * @param key the property key
   * @return the live value list if present, or an empty optional
   */
  public Optional<List<String>> find(String key) {
    return Optional.ofNullable(values.get(key(key)));
  }

  /**
   * Removes a normalized key and its values.
   *
   * @param key the key to remove
   * @return this map
   */
  public MutablePropertyMap remove(String key) {
    values.remove(key(key));
    return this;
  }

  /**
   * Removes every text key present in another map, regardless of its values.
   *
   * @param other the map supplying keys
   * @return this map
   */
  public MutablePropertyMap removeAll(MutablePropertyMap other) {
    values.keySet().removeAll(other.values.keySet());
    return this;
  }

  /**
   * Appends another map's text values and opaque metadata identifiers.
   *
   * @param other the map to append
   * @return this map
   */
  public MutablePropertyMap merge(MutablePropertyMap other) {
    // Snapshot handles merge(self) without iterator invalidation or value aliasing.
    var source = other == this ? new MutablePropertyMap(other) : other;
    source.values.forEach(this::add);
    unsupported.addAll(source.unsupported);
    return this;
  }

  /**
   * Appends an opaque metadata identifier without editing a file or tag.
   *
   * @param key the identifier to append
   */
  public void addUnsupportedData(String key) {
    unsupported.add(Objects.requireNonNull(key));
  }

  /**
   * Returns an unmodifiable live view of opaque metadata identifiers.
   *
   * @return the identifier view
   */
  public List<String> unsupportedData() {
    return Collections.unmodifiableList(unsupported);
  }

  /**
   * Removes keys with empty value lists, retaining unsupported-data identifiers.
   */
  public void removeEmpty() {
    values.entrySet().removeIf(e -> e.getValue().isEmpty());
  }

  /**
   * Clears unsupported-data identifiers, retaining all property values, including empty lists.
   * Clearing identifiers does not remove the opaque metadata they describe from a tag or file.
   */
  public void clearUnsupportedData() {
    unsupported.clear();
  }

  /**
   * Removes all property values and unsupported-data identifiers.
   */
  public void clear() {
    values.clear();
    unsupported.clear();
  }

  public int size() {
    return values.size();
  }

  public boolean isEmpty() {
    return values.isEmpty();
  }

  /**
   * Creates an immutable snapshot of text values and opaque metadata identifiers.
   * Subsequent changes to this map or its live lists do not change the snapshot.
   *
   * @return the property snapshot
   */
  public PropertyMap snapshot() {
    var result = PropertyMap.builder();
    values.forEach(result::add);
    unsupported.forEach(result::addUnsupportedData);
    return result.build();
  }

  /**
   * Iterates normalized keys and live value lists. Entries cannot be replaced through
   * {@code setValue}, but iterator removal removes the current map entry.
   *
   * @return the live-entry iterator
   */
  @Override
  public Iterator<Map.Entry<String, List<String>>> iterator() {
    // Keys remain canonical; values and iterator removal retain mutable-map semantics.
    var source = values.entrySet().iterator();
    return new Iterator<>() {
      public boolean hasNext() {
        return source.hasNext();
      }

      public Map.Entry<String, List<String>> next() {
        var entry = source.next();
        return new AbstractMap.SimpleImmutableEntry<>(entry.getKey(), entry.getValue());
      }

      public void remove() {
        source.remove();
      }
    };
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof MutablePropertyMap p
      && values.equals(p.values)
      && unsupported.equals(p.unsupported);
  }

  @Override
  public int hashCode() {
    return Objects.hash(values, unsupported);
  }

  @Override
  public String toString() {
    return snapshot().toString();
  }
}
