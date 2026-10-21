// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.dsdiff.diin;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.PropertyUpdateResult;
import cz.skyba.taglib.TagBase;
import cz.skyba.taglib.toolkit.MutablePropertyMap;

import java.util.Objects;

import static java.lang.System.Logger.Level.DEBUG;

/// Mutable DSDIFF Edited Master information. Only title and artist retain values.
public class Tag extends TagBase {
  private static final System.Logger LOG = System.getLogger(Tag.class.getName());

  private String title = "", artist = "";

  @Override
  public String title() {
    return title;
  }

  @Override
  public String artist() {
    return artist;
  }

  @Override
  public String album() {
    return "";
  }

  @Override
  public String comment() {
    return "";
  }

  @Override
  public String genre() {
    return "";
  }

  @Override
  public long year() {
    return 0;
  }

  @Override
  public long track() {
    return 0;
  }

  @Override
  public void setTitle(String value) {
    title = Objects.requireNonNull(value);
  }

  @Override
  public void setArtist(String value) {
    artist = Objects.requireNonNull(value);
  }

  @Override
  public void setAlbum(String value) {
    ignored("album");
  }

  @Override
  public void setComment(String value) {
    ignored("comment");
  }

  @Override
  public void setGenre(String value) {
    ignored("genre");
  }

  @Override
  public void setYear(long value) {
    ignored("year");
  }

  @Override
  public void setTrack(long value) {
    ignored("track");
  }

  private static void ignored(String field) {
    LOG.log(DEBUG, () -> "Ignoring unsupported " + field + " field.");
  }

  /// Exports title and artist, including fields whose single value is empty.
  ///
  /// @return the DIIN text fields
  @Override
  public PropertyMap properties() {
    return PropertyMap.builder().put("TITLE", title).put("ARTIST", artist).build();
  }

  /// Replaces stored title and artist fields directly, without invoking common-field setter hooks.
  /// Changes are in memory and require saving the owner.
  ///
  /// @param original the complete replacement property map
  /// @return accepted and rejected text values
  @Override
  public PropertyUpdateResult replaceProperties(PropertyMap original) {
    var values = new MutablePropertyMap(original);
    values.removeEmpty();
    values.clearUnsupportedData();
    title = take(values, "TITLE");
    artist = take(values, "ARTIST");
    return PropertyUpdateResult.fromRejections(original, values.snapshot());
  }

  private static String take(MutablePropertyMap values, String key) {
    if (!values.containsKey(key)) {
      return "";
    }
    var list = values.getOrCreate(key);
    String first = list.removeFirst();
    if (list.isEmpty()) {
      values.remove(key);
    }
    return first;
  }
}
