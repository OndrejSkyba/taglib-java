// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * An immutable request to edit common text properties in a file.
 * Create one through {@link TagLib#edit(Path)}. Construction performs no file I/O;
 * {@link #save()} applies the request to the file's current metadata. Requests hold no
 * open resources and may be saved again. An empty request does not open the file.
 *
 * <p>Saving reads the current text property map, applies the edits and replaces the complete
 * map using the same replacement and default save behavior as
 * {@link TagLib#write(Path, PropertyMap)}. Unchanged property values are resubmitted too.
 * Tag versions, physical metadata layout and value normalization follow that API's
 * format-specific behavior, including its preservation of artwork and opaque metadata.
 *
 * <p>The read, merge and save share one editing session and write lock. Rejected property
 * values prevent saving. An I/O failure can leave partial changes because saving updates
 * storage in place.
 */
public final class MetadataEdit {
  private final Path path;
  private final PropertyMap updates;

  private MetadataEdit(Builder builder) {
    path = builder.path;
    updates = builder.updates.build();
  }

  /**
   * Applies the request to the current properties and saves through the replacement API.
   * Rejected property values prevent saving. Storage is updated in place.
   *
   * @throws MetadataEditException if the replacement API rejects property values
   * @throws java.io.UncheckedIOException if storage access or property encoding fails
   * @throws UnsupportedOperationException if the format does not support saving
   * @throws TagLibException if opening or reading the file is rejected
   */
  public void save() {
    TagLib.applyEdits(path, updates);
  }

  /**
   * Builds text edits without opening the file. Keys use the same ASCII case-insensitive
   * matching as {@link PropertyMap}. The last operation on a key wins. Builders are mutable
   * and must be used by one thread at a time; built requests are independent snapshots.
   */
  public static final class Builder {
    private final Path path;
    private final PropertyMap.Builder updates = PropertyMap.builder();

    Builder(Path path) {
      this.path = Objects.requireNonNull(path);
    }

    /**
     * Replaces a property's values. An empty string is submitted as a value, subject to
     * format constraints; use {@link #clear(String)} to remove the property.
     *
     * @param key the nonempty common property key, including custom keys
     * @param values one or more non-null text values, copied in order
     * @return this builder
     * @throws IllegalArgumentException if the key is empty, no values are supplied, or text contains NUL
     * @throws NullPointerException if a key, value, or the value array is null
     */
    public Builder set(String key, String... values) {
      return set(key, List.of(values));
    }

    /**
     * Replaces a property's values with a copy of a nonempty list.
     *
     * @param key the nonempty common property key, including custom keys
     * @param values one or more non-null text values, copied in order
     * @return this builder
     * @throws IllegalArgumentException if the key or list is empty, or text contains NUL
     * @throws NullPointerException if a key, value, or the list is null
     */
    public Builder set(String key, List<String> values) {
      validateKey(key);
      var copy = List.copyOf(values);
      if (copy.isEmpty()) {
        throw new IllegalArgumentException("set requires at least one value; use clear to remove a property");
      }
      if (copy.stream().anyMatch(value -> value.indexOf('\0') >= 0)) {
        throw new IllegalArgumentException("Metadata values must not contain NUL characters");
      }
      updates.put(key, copy);
      return this;
    }

    /**
     * Removes a property from the text property map submitted to the replacement API.
     *
     * @param key the nonempty common property key
     * @return this builder
     * @throws IllegalArgumentException if the key is empty or contains NUL
     * @throws NullPointerException if the key is null
     */
    public Builder clear(String key) {
      validateKey(key);
      updates.put(key, List.of());
      return this;
    }

    /**
     * Copies the staged changes into an immutable request without accessing the file.
     *
     * @return an independent edit request
     */
    public MetadataEdit build() {
      return new MetadataEdit(this);
    }

    /**
     * Builds and saves the request; equivalent to {@code build().save()}.
     *
     * @see MetadataEdit#save()
     */
    public void save() {
      build().save();
    }

    private static void validateKey(String key) {
      Objects.requireNonNull(key);
      if (key.isEmpty() || key.indexOf('\0') >= 0) {
        throw new IllegalArgumentException("A nonempty metadata key without NUL characters is required");
      }
    }
  }
}
