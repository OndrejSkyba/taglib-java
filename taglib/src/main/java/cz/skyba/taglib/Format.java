// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;

import cz.skyba.taglib.internal.codec.AsfCodec;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/// Audio containers supported by built-in format detection and metadata snapshots.
public enum Format {
  MPEG,
  FLAC,
  OGG_VORBIS,
  OGG_OPUS,
  OGG_SPEEX,
  OGG_FLAC,
  MP4,
  ASF,
  WAV,
  AIFF,
  APE,
  WAVPACK,
  MPC,
  TRUEAUDIO,
  DSF,
  DSDIFF,
  MOD,
  S3M,
  IT,
  XM,
  MATROSKA,
  SHORTEN;

  private static final Set<String> PROPERTY_KEYS = Arrays.stream(PropertyKey.values())
    .map(PropertyKey::key).collect(Collectors.toUnmodifiableSet());

  private static final Set<PictureType> ALL_PICTURE_TYPES = Set.copyOf(EnumSet.allOf(PictureType.class));

  /// Returns the known writable text property keys for this format.
  /// For formats supporting custom properties, this set is not exhaustive.
  ///
  /// @return an immutable set of uppercase property keys
  /// @see #supportsCustomProperties()
  public Set<String> supportedPropertyKeys() {
    return switch (this) {
      case ASF -> AsfCodec.supportedPropertyKeys();
      case MOD, S3M, IT -> Set.of(PropertyKey.TITLE.key(), PropertyKey.COMMENT.key());
      case XM -> Set.of(PropertyKey.TITLE.key(), PropertyKey.COMMENT.key(), PropertyKey.TRACKER_NAME.key());
      case SHORTEN -> Set.of();
      default -> PROPERTY_KEYS;
    };
  }

  /// Returns whether this format can write the property.
  ///
  /// @param key the property key
  /// @return whether the property is supported
  /// @throws NullPointerException if the key is null
  public boolean supportsProperty(PropertyKey key) {
    return supportedPropertyKeys().contains(key.key());
  }

  /// Returns whether this format accepts custom text property names.
  ///
  /// @return whether custom text property names are supported
  public boolean supportsCustomProperties() {
    return switch (this) {
      case ASF, MOD, S3M, IT, XM, SHORTEN -> false;
      default -> true;
    };
  }

  /// Returns the named artwork roles this format can preserve when saving.
  ///
  /// @return an immutable set, empty when artwork editing is unsupported
  public Set<PictureType> supportedPictureTypes() {
    return switch (this) {
      case MP4, MATROSKA -> Set.of(PictureType.OTHER);
      case APE, MPC, WAVPACK -> Set.of(PictureType.FRONT_COVER, PictureType.BACK_COVER);
      case MOD, S3M, IT, XM, SHORTEN -> Set.of();
      default -> ALL_PICTURE_TYPES;
    };
  }

  /// Returns whether this format can preserve the artwork role when saving.
  ///
  /// @param type the artwork role
  /// @return whether the role is supported
  /// @throws NullPointerException if the role is null
  public boolean supportsPictureType(PictureType type) {
    return supportsPictureType(type.code());
  }

  // Unrestricted containers also retain the existing API's support for unknown numeric roles.
  boolean supportsPictureType(int code) {
    return switch (this) {
      case MP4, MATROSKA, APE, MPC, WAVPACK, MOD, S3M, IT, XM, SHORTEN ->
        supportedPictureTypes().stream().anyMatch(type -> type.code() == code);
      default -> true;
    };
  }
}
