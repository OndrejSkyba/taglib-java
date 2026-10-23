// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// An immutable request to edit text properties and artwork in a file.
/// Create one through [TagLib#edit(Path)]. Construction does not open the audio file;
/// image paths supplied to the builder are read immediately.
/// [#save()] applies the request to the file's current metadata. Requests hold no
/// open resources and may be saved again. An empty request does not open the file.
///
/// Saving text edits reads the current text property map and replaces the complete
/// map using the same replacement and default save behavior as
/// [TagLib#write(Path, PropertyMap)]. Unchanged property values are resubmitted too.
/// Tag versions, physical metadata layout and value normalization follow that API's
/// format-specific behavior. Artwork is preserved unless explicitly edited; opaque
/// metadata is preserved according to the replacement API.
///
/// The read, merge and save share one editing session and write lock. Rejected property
/// values prevent saving. An I/O failure can leave partial changes because saving updates
/// storage in place.
public final class MetadataEdit {
  private final Path path;
  private final PropertyMap updates;
  private final List<PictureEdit> pictureEdits;

  private MetadataEdit(Builder builder) {
    path = builder.path;
    updates = builder.updates.build();
    pictureEdits = List.copyOf(builder.pictureEdits);
  }

  /// Applies the request to the current properties and saves through the replacement API.
  /// Rejected property values prevent saving. Storage is updated in place.
  ///
  /// @throws MetadataEditException if property values or picture roles cannot be stored
  /// @throws java.io.UncheckedIOException if storage access or property encoding fails
  /// @throws UnsupportedOperationException if the format does not support saving
  /// @throws TagLibException if opening or reading the file is rejected
  public void save() {
    TagLib.applyEdits(path, updates, pictureEdits);
  }

  /// Builds edits without opening the audio file. Keys use the same ASCII case-insensitive
  /// matching as [PropertyMap]. The last operation on a key or picture role wins.
  /// Picture operations are applied in call order. Builders are mutable
  /// and must be used by one thread at a time; built requests are independent snapshots.
  public static final class Builder {
    private final Path path;
    private final PropertyMap.Builder updates = PropertyMap.builder();
    private final List<PictureEdit> pictureEdits = new ArrayList<>();

    Builder(Path path) {
      this.path = Objects.requireNonNull(path);
    }

    /// Replaces a property's values. An empty string is submitted as a value, subject to
    /// format constraints; use [#remove(String)] to remove the property.
    ///
    /// @param key the nonempty property key, including custom keys
    /// @param values one or more non-null text values, copied in order
    /// @return this builder
    /// @throws IllegalArgumentException if the key is empty or contains NUL,
    ///   no values are supplied, or a value contains NUL
    /// @throws NullPointerException if the key, value array, or any value is null
    public Builder set(String key, String... values) {
      return set(key, List.of(values));
    }

    /// Replaces a property's values. An empty string is submitted as a value, subject to
    /// format constraints; use [#remove(PropertyKey)] to remove the property.
    ///
    /// @param key the property key
    /// @param values one or more non-null text values, copied in order
    /// @return this builder
    /// @throws IllegalArgumentException if no values are supplied, or a value contains NUL
    /// @throws NullPointerException if the key, value array, or any value is null
    public Builder set(PropertyKey key, String... values) {
      return set(key.key(), values);
    }

    /// Replaces a property's values. An empty string is submitted as a value, subject to
    /// format constraints; use [#remove(String)] to remove the property.
    ///
    /// @param key the nonempty property key, including custom keys
    /// @param values one or more non-null text values, copied in order
    /// @return this builder
    /// @throws IllegalArgumentException if the key is empty or contains NUL,
    ///   the list is empty, or a value contains NUL
    /// @throws NullPointerException if the key, list, or any value is null
    public Builder set(String key, List<String> values) {
      validateKey(key);
      var copy = List.copyOf(values);
      if (copy.isEmpty()) {
        throw new IllegalArgumentException("set requires at least one value; use remove(key) to delete a property");
      }
      if (copy.stream().anyMatch(value -> value.indexOf('\0') >= 0)) {
        throw new IllegalArgumentException("Metadata values must not contain NUL characters");
      }
      updates.put(key, copy);
      return this;
    }

    /// Replaces a property's values. An empty string is submitted as a value, subject to
    /// format constraints; use [#remove(PropertyKey)] to remove the property.
    ///
    /// @param key the property key
    /// @param values one or more non-null text values, copied in order
    /// @return this builder
    /// @throws IllegalArgumentException if the list is empty, or a value contains NUL
    /// @throws NullPointerException if the key, list, or any value is null
    public Builder set(PropertyKey key, List<String> values) {
      return set(key.key(), values);
    }

    /// Removes a property when the edit is saved.
    ///
    /// @param key the nonempty property key, including custom keys
    /// @return this builder
    /// @throws IllegalArgumentException if the key is empty or contains NUL
    /// @throws NullPointerException if the key is null
    public Builder remove(String key) {
      validateKey(key);
      updates.put(key, List.of());
      return this;
    }

    /// Removes a property when the edit is saved.
    ///
    /// @param key the property key
    /// @return this builder
    /// @throws NullPointerException if the key is null
    public Builder remove(PropertyKey key) {
      return remove(key.key());
    }

    /// Replaces all pictures with the supplied picture's role, preserving other roles.
    /// Saving rejects roles that the format cannot retain; see [PictureType].
    ///
    /// @throws NullPointerException if the picture is null
    public Builder setPicture(Picture picture) {
      Objects.requireNonNull(picture);
      pictureEdits.add(pictures -> {
        pictures.removeIf(current -> current.type() == picture.type());
        pictures.add(picture);
      });
      return this;
    }

    /// Reads the image immediately and stages it with an empty description.
    /// Replaces pictures of this role while preserving other roles.
    ///
    /// @throws java.io.UncheckedIOException if the image cannot be read
    /// @throws IllegalArgumentException if the image type is not recognized
    /// @throws NullPointerException if the role or path is null
    public Builder setPicture(PictureType type, Path image) {
      return setPicture(Picture.from(type, image));
    }

    /// Removes all pictures with this role, preserving other roles.
    ///
    /// @throws NullPointerException if the role is null
    public Builder removePictures(PictureType type) {
      Objects.requireNonNull(type);
      pictureEdits.add(pictures -> pictures.removeIf(picture -> picture.type() == type.code()));
      return this;
    }

    /// Replaces all artwork. An empty argument list removes all pictures.
    /// Later picture operations modify this replacement; earlier ones are discarded.
    /// Saving rejects roles that the format cannot retain; see [PictureType].
    ///
    /// @throws NullPointerException if the array or any picture is null
    public Builder replacePictures(Picture... pictures) {
      return replacePictures(List.of(pictures));
    }

    /// Replaces all artwork with a copy of the list. An empty list removes all pictures.
    /// Later picture operations modify this replacement; earlier ones are discarded.
    /// Saving rejects roles that the format cannot retain; see [PictureType].
    ///
    /// @throws NullPointerException if the list or any picture is null
    public Builder replacePictures(List<Picture> pictures) {
      var copy = List.copyOf(pictures);
      pictureEdits.clear();
      pictureEdits.add(current -> {
        current.clear();
        current.addAll(copy);
      });
      return this;
    }

    /// Copies the staged changes into an immutable request without accessing the file.
    ///
    /// @return an independent edit request
    public MetadataEdit build() {
      return new MetadataEdit(this);
    }

    /// Builds and saves the request; equivalent to `build().save()`.
    ///
    /// @see MetadataEdit#save()
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
