// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;

/// Common text property keys for [PropertyMap] and [MetadataEdit.Builder].
/// Each key is equivalent to its [#key()] string in read and edit operations.
/// Support depends on the format; see [Format#supportedPropertyKeys()].
public enum PropertyKey {
  TITLE("TITLE"),
  ARTIST("ARTIST"),
  ARTISTS("ARTISTS"),
  ALBUM("ALBUM"),
  ALBUM_ARTIST("ALBUMARTIST"),
  COMMENT("COMMENT"),
  GENRE("GENRE"),
  /// Release date text, such as a year or an ISO date.
  DATE("DATE"),
  ORIGINAL_DATE("ORIGINALDATE"),
  /// Track number, optionally including the total as `number/total` where supported.
  TRACK_NUMBER("TRACKNUMBER"),
  /// Total number of tracks, separate from [#TRACK_NUMBER].
  TRACK_TOTAL("TRACKTOTAL"),
  /// Disc number, optionally including the total as `number/total` where supported.
  DISC_NUMBER("DISCNUMBER"),
  /// Total number of discs, separate from [#DISC_NUMBER].
  DISC_TOTAL("DISCTOTAL"),
  COMPOSER("COMPOSER"),
  CONDUCTOR("CONDUCTOR"),
  LYRICIST("LYRICIST"),
  LYRICS("LYRICS"),
  BPM("BPM"),
  GROUPING("GROUPING"),
  WORK("WORK"),
  COMPILATION("COMPILATION"),
  COPYRIGHT("COPYRIGHT"),
  LABEL("LABEL"),
  ISRC("ISRC"),
  CATALOG_NUMBER("CATALOGNUMBER"),
  BARCODE("BARCODE"),
  TITLE_SORT("TITLESORT"),
  ARTIST_SORT("ARTISTSORT"),
  ALBUM_SORT("ALBUMSORT"),
  ALBUM_ARTIST_SORT("ALBUMARTISTSORT"),
  COMPOSER_SORT("COMPOSERSORT"),
  /// MusicBrainz recording identifier; use [#MUSICBRAINZ_RELEASE_TRACK_ID] for a track on a particular release.
  MUSICBRAINZ_TRACK_ID("MUSICBRAINZ_TRACKID"),
  MUSICBRAINZ_ARTIST_ID("MUSICBRAINZ_ARTISTID"),
  MUSICBRAINZ_ALBUM_ID("MUSICBRAINZ_ALBUMID"),
  MUSICBRAINZ_ALBUM_ARTIST_ID("MUSICBRAINZ_ALBUMARTISTID"),
  MUSICBRAINZ_RELEASE_GROUP_ID("MUSICBRAINZ_RELEASEGROUPID"),
  MUSICBRAINZ_RELEASE_TRACK_ID("MUSICBRAINZ_RELEASETRACKID"),
  MUSICBRAINZ_WORK_ID("MUSICBRAINZ_WORKID"),
  REPLAYGAIN_TRACK_GAIN("REPLAYGAIN_TRACK_GAIN"),
  REPLAYGAIN_TRACK_PEAK("REPLAYGAIN_TRACK_PEAK"),
  REPLAYGAIN_ALBUM_GAIN("REPLAYGAIN_ALBUM_GAIN"),
  REPLAYGAIN_ALBUM_PEAK("REPLAYGAIN_ALBUM_PEAK"),
  /// Name of the software used to create a tracker module.
  TRACKER_NAME("TRACKERNAME");

  private final String key;

  PropertyKey(String key) {
    this.key = key;
  }

  /// Returns the uppercase property name used in string-based property operations.
  ///
  /// @return the uppercase property name
  public String key() {
    return key;
  }
}
