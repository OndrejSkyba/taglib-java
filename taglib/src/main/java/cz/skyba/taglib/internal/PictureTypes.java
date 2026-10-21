// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal;

/// Shared picture-type codes and names used by ID3, FLAC and ASF metadata.
public final class PictureTypes {
  private static final String[] NAMES = {
    "Other", "File Icon", "Other File Icon", "Front Cover", "Back Cover", "Leaflet Page",
    "Media", "Lead Artist", "Artist", "Conductor", "Band", "Composer", "Lyricist",
    "Recording Location", "During Recording", "During Performance", "Movie Screen Capture",
    "Coloured Fish", "Illustration", "Band Logo", "Publisher Logo"
  };

  private PictureTypes() {
  }

  /// Returns the name for a code, using Other for an unknown code.
  public static String nameForCode(int code) {
    return NAMES[code >= 0 && code < NAMES.length ? code : 0];
  }

  /// Returns the code for an exact name, using Other for an unknown name.
  public static int codeForName(String name) {
    for (int code = 0; code < NAMES.length; code++) {
      if (NAMES[code].equals(name)) {
        return code;
      }
    }
    return 0;
  }
}
