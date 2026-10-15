// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.dsdiff.diin;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.PropertyUpdateResult;
import cz.skyba.taglib.TagBase;
import cz.skyba.taglib.toolkit.Debug;
import cz.skyba.taglib.toolkit.MutablePropertyMap;

import java.util.Objects;

/**
 * Mutable DSDIFF Edited Master information. Only title and artist retain values.
 */
public class Tag extends TagBase {
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
    ignored("setAlbum");
  }

  @Override
  public void setComment(String value) {
    ignored("setComment");
  }

  @Override
  public void setGenre(String value) {
    ignored("setGenre");
  }

  @Override
  public void setYear(long value) {
    ignored("setYear");
  }

  @Override
  public void setTrack(long value) {
    ignored("setTrack");
  }

  private static void ignored(String operation) {
    Debug.debug("DSDIFF::DIIN::Tag::" + operation + "() -- Ignoring unsupported tag.");
  }

  /**
   * Exports title and artist, including fields whose single value is empty.
   *
   * @return the DIIN text fields
   */
  @Override
  public PropertyMap properties() {
    return PropertyMap.builder().put("TITLE", title).put("ARTIST", artist).build();
  }

  /**
   * Replaces stored title and artist fields directly, without invoking common-field setter hooks.
   * Changes are in memory and require saving the owner.
   *
   * @param original the complete replacement property map
   * @return accepted and rejected text values
   */
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
