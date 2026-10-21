// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.mod;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.PropertyUpdateResult;
import cz.skyba.taglib.TagBase;
import cz.skyba.taglib.toolkit.MutablePropertyMap;

import java.util.Objects;
import java.util.function.Consumer;

/// Live tracker tag. Only title, comment and trackerName retain assigned values.
public class Tag extends TagBase {
  private String title = "";
  private String comment = "";
  private String trackerName = "";

  @Override
  public String title() {
    return title;
  }

  @Override
  public String artist() {
    return "";
  }

  @Override
  public String album() {
    return "";
  }

  @Override
  public String comment() {
    return comment;
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

  public String trackerName() {
    return trackerName;
  }

  @Override
  public void setTitle(String value) {
    title = Objects.requireNonNull(value);
  }

  @Override
  public void setArtist(String value) {
  }

  @Override
  public void setAlbum(String value) {
  }

  @Override
  public void setComment(String value) {
    comment = Objects.requireNonNull(value);
  }

  @Override
  public void setGenre(String value) {
  }

  @Override
  public void setYear(long value) {
  }

  @Override
  public void setTrack(long value) {
  }

  public void setTrackerName(String value) {
    trackerName = Objects.requireNonNull(value);
  }

  /// Exports title and comment, including an empty single value, and nonempty tracker-name text.
  ///
  /// @return the common tracker properties
  @Override
  public PropertyMap properties() {
    var values = PropertyMap.builder().put("TITLE", title).put("COMMENT", comment);
    if (!trackerName.isEmpty()) {
      values.put("TRACKERNAME", trackerName);
    }
    return values.build();
  }

  @Override
  public PropertyUpdateResult replaceProperties(PropertyMap original) {
    var values = new MutablePropertyMap(original);
    values.removeEmpty();
    values.clearUnsupportedData();
    take(values, "TITLE", this::setTitle);
    take(values, "COMMENT", this::setComment);
    take(values, "TRACKERNAME", this::setTrackerName);
    return PropertyUpdateResult.fromRejections(original, values.snapshot());
  }

  private static void take(
    MutablePropertyMap values, String key,
    Consumer<String> setter
  ) {
    if (!values.containsKey(key)) {
      setter.accept("");
      return;
    }
    var list = values.getOrCreate(key);
    setter.accept(list.getFirst());
    list.removeFirst();
    if (list.isEmpty()) {
      values.remove(key);
    }
  }
}
