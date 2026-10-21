// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v1;

import cz.skyba.taglib.internal.id3.Id3v1;

import java.util.List;
import java.util.Map;

/// The ID3v1 genre-code table with exact, case-sensitive name lookup.
public final class Genres {
  private Genres() {
  }

  /// Returns the immutable genre-name table in numeric code order.
  ///
  /// @return genre names
  public static List<String> genreList() {
    return Id3v1.genreList();
  }

  /// Returns the immutable map from genre names to numeric codes.
  ///
  /// @return genre-name mappings
  public static Map<String, Integer> genreMap() {
    return Id3v1.genreMap();
  }

  /// Looks up a genre name by numeric code.
  ///
  /// @param index the genre code
  /// @return the name, or an empty string for an unknown code
  public static String genre(int index) {
    return Id3v1.genre(index);
  }

  /// Looks up a numeric genre code by name.
  ///
  /// @param name the genre name
  /// @return the code, or 255 if unknown
  public static int genreIndex(String name) {
    return Id3v1.genreIndex(name);
  }
}
