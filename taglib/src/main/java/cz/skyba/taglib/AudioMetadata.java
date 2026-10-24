// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;

import java.util.List;
import java.util.Objects;

/// An immutable metadata snapshot independent of an open file handle.
/// The artwork list is copied on construction.
///
/// @param format          the recognized audio container
/// @param properties      complete text properties and opaque metadata identifiers
/// @param audioProperties parsed audio properties, or [AudioProperties#EMPTY] if not read
/// @param pictures        embedded artwork snapshots
/// @param tag             the common-field view
public record AudioMetadata(
  Format format,
  PropertyMap properties,
  AudioProperties audioProperties,
  List<Picture> pictures,
  Tag tag) {
  /// Creates a metadata snapshot and copies the artwork list.
  /// The common-field view is derived from the properties using the selected format.
  ///
  /// @param format the recognized audio container
  /// @param properties complete text properties and opaque metadata identifiers
  /// @param audioProperties parsed audio properties, or [AudioProperties#EMPTY] if not read
  /// @param pictures embedded artwork snapshots
  /// @throws NullPointerException if a required snapshot value or picture element is null
  public AudioMetadata(
    Format format,
    PropertyMap properties,
    AudioProperties audioProperties,
    List<Picture> pictures
  ) {
    this(format, properties, audioProperties, pictures, new Tag(properties, format));
  }

  /// Creates a metadata snapshot and copies the artwork list.
  ///
  /// @param format the recognized audio container
  /// @param properties complete text properties and opaque metadata identifiers
  /// @param audioProperties parsed audio properties, or [AudioProperties#EMPTY] if not read
  /// @param pictures embedded artwork snapshots
  /// @param tag the common-field view
  /// @throws NullPointerException if a required snapshot value or picture element is null
  public AudioMetadata {
    Objects.requireNonNull(format);
    Objects.requireNonNull(properties);
    Objects.requireNonNull(audioProperties);
    Objects.requireNonNull(tag);
    pictures = List.copyOf(pictures);
  }
}
