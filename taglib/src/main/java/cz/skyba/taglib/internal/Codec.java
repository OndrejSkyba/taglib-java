// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal;

import cz.skyba.taglib.AudioMetadata;
import cz.skyba.taglib.Picture;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.ReadOptions;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface Codec {
  default PropertyMap unsupportedProperties(PropertyMap properties) {
    return PropertyMap.EMPTY;
  }

  AudioMetadata read(Path path, ReadOptions options) throws IOException;

  AudioMetadata read(BinaryFile file, ReadOptions options) throws IOException;

  /// Native constructor entry point, including effects of a borrowed stream's current cursor.
  default AudioMetadata readNative(BinaryFile file, ReadOptions options) throws IOException {
    return read(file, options);
  }

  default void writeNative(
    BinaryFile file,
    long constructorOffset,
    PropertyMap properties,
    List<Picture> pictures
  )
    throws IOException {
    if (pictures == null) {
      write(file, properties);
    } else {
      write(file, properties, pictures);
    }
  }

  default void write(Path path, PropertyMap properties) throws IOException {
    try (var file = new BinaryFile(path)) {
      write(file, properties);
    }
  }

  default void write(
    Path path, PropertyMap properties, List<Picture> pictures)
    throws IOException {
    try (var file = new BinaryFile(path)) {
      write(file, properties, pictures);
    }
  }

  default void write(BinaryFile file, PropertyMap properties)
    throws IOException {
    write(file, properties, null);
  }

  default void write(
    BinaryFile file,
    PropertyMap properties,
    List<Picture> pictures
  )
    throws IOException {
    throw new UnsupportedOperationException(
      "Artwork editing is not implemented for " + getClass().getSimpleName());
  }
}
