// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;

/// Picture roles defined by ID3v2 and FLAC. Formats may support only some roles.
/// MP4 and Matroska expose artwork as [#OTHER]. APE, Musepack and WavPack
/// support only [#FRONT_COVER] and [#BACK_COVER].
public enum PictureType {
  OTHER(0),
  FILE_ICON(1),
  OTHER_FILE_ICON(2),
  FRONT_COVER(3),
  BACK_COVER(4),
  LEAFLET_PAGE(5),
  MEDIA(6),
  LEAD_ARTIST(7),
  ARTIST(8),
  CONDUCTOR(9),
  BAND(10),
  COMPOSER(11),
  LYRICIST(12),
  RECORDING_LOCATION(13),
  DURING_RECORDING(14),
  DURING_PERFORMANCE(15),
  MOVIE_SCREEN_CAPTURE(16),
  COLOURED_FISH(17),
  ILLUSTRATION(18),
  BAND_LOGO(19),
  PUBLISHER_LOGO(20);

  private final int code;

  PictureType(int code) {
    this.code = code;
  }

  /// {@return the numeric role used by [Picture#type()]}
  public int code() {
    return code;
  }
}
