// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.shorten;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.PropertyUpdateResult;
import cz.skyba.taglib.TagBase;

import java.util.Objects;

/**
 * An empty metadata view for Shorten audio. Common-field setters have no effect and text properties are rejected.
 */
public class Tag extends TagBase {
  @Override
  public String title() {
    return "";
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
  }

  @Override
  public void setArtist(String value) {
  }

  @Override
  public void setAlbum(String value) {
  }

  @Override
  public void setComment(String value) {
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

  @Override
  public PropertyMap properties() {
    return PropertyMap.EMPTY;
  }

  @Override
  public PropertyUpdateResult replaceProperties(PropertyMap values) {
    return PropertyUpdateResult.fromRejections(values, Objects.requireNonNull(values));
  }
}
