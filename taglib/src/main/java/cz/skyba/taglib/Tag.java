// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;


import cz.skyba.taglib.toolkit.TagString;

/**
 * An immutable common-field snapshot, separate from the complete text property map.
 * Year and track values retain the low 32 bits of their inputs as unsigned Java {@code long}s.
 *
 * @param title   title text
 * @param artist  artist text
 * @param album   album text
 * @param comment comment text
 * @param genre   genre text
 * @param year    unsigned 32-bit year value, with zero representing an unspecified year
 * @param track   unsigned 32-bit track value, with zero representing an unspecified track
 */
public record Tag(
  String title, String artist, String album, String comment, String genre, long year, long track) {
  /**
   * Creates a common-field snapshot, retaining the low 32 bits of the year and track values.
   */
  public Tag {
    year &= 0xffff_ffffL;
    track &= 0xffff_ffffL;
  }

  /**
   * Creates a common-field snapshot using MPEG text and numeric mapping rules.
   *
   * @param p source properties
   */
  public Tag(PropertyMap p) {
    this(p, Format.MPEG);
  }

  /**
   * Creates a common-field snapshot using the selected format's mapping and display rules.
   * Multiple text values and numeric fields are projected according to the format. The input
   * property map is unchanged.
   *
   * @param p      source properties
   * @param format the format whose common-field mappings to use
   */
  public Tag(PropertyMap p, Format format) {
    this(
      text(p, "TITLE", format),
      text(p, "ARTIST", format),
      text(p, "ALBUM", format),
      comment(p, format),
      text(p, "GENRE", format),
      number(p.first("DATE").isEmpty() && isXiphFormat(format) ? p.first("YEAR") : p.first("DATE"), true, format),
      number(
        p.first("TRACKNUMBER").isEmpty() && isXiphFormat(format)
          ? p.first("TRACKNUM")
          : p.first("TRACKNUMBER"),
        false, format));
  }

  private static boolean isXiphFormat(Format f) {
    return f == Format.FLAC
      || f == Format.OGG_FLAC
      || f == Format.OGG_VORBIS
      || f == Format.OGG_OPUS
      || f == Format.OGG_SPEEX;
  }

  private static String text(PropertyMap p, String key, Format format) {
    return format == Format.MATROSKA
      ? p.first(key)
      : String.join(format == Format.MP4 ? ", " : " / ", p.get(key));
  }

  private static String comment(PropertyMap p, Format format) {
    if (isXiphFormat(format)) {
      String key = p.get("DESCRIPTION").isEmpty() ? "COMMENT" : "DESCRIPTION";
      return text(p, key, format);
    }
    return format == Format.APE || format == Format.WAVPACK || format == Format.MPC || format == Format.MP4
      ? text(p, "COMMENT", format)
      : p.first("COMMENT");
  }

  private static long number(String value, boolean year, Format format) {
    if (format == Format.MATROSKA) {
      int dash = value.indexOf('-');
      if (dash >= 0) {
        value = value.substring(0, dash);
      }
    } else if (year && !isXiphFormat(format) && format != Format.ASF && format != Format.MP4) {
      value = value.substring(0, Math.min(4, value.length()));
    }
    return Integer.toUnsignedLong(new TagString(value).toInt());
  }
}
