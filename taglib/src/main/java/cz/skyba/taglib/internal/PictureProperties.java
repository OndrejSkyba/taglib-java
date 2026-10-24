// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal;

import cz.skyba.taglib.flac.Picture;
import cz.skyba.taglib.toolkit.Variant;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/// FLAC-style picture metadata represented as Variant dictionaries.
public final class PictureProperties {
  private static final Variant EMPTY_VARIANT = new Variant();

  private PictureProperties() {
  }

  public static Map<String, Variant> export(Picture picture) {
    var values = new TreeMap<String, Variant>();
    values.put("data", Variant.fromByteArray(picture.data()));
    values.put("mimeType", new Variant(picture.mimeType()));
    values.put("description", new Variant(picture.description()));
    values.put("pictureType", new Variant(Picture.typeToString(picture.type())));
    values.put("width", new Variant(picture.width()));
    values.put("height", new Variant(picture.height()));
    values.put("numColors", new Variant(picture.numColors()));
    values.put("colorDepth", new Variant(picture.colorDepth()));
    return Collections.unmodifiableMap(values);
  }

  private static Variant value(Map<String, Variant> values, String name) {
    return values.getOrDefault(name, EMPTY_VARIANT);
  }

  public static Picture create(Map<String, Variant> values) {
    var picture = new Picture();
    picture.setData(value(values, "data").toByteVector().data());
    picture.setMimeType(value(values, "mimeType").toText());
    picture.setDescription(value(values, "description").toText());
    picture.setType(Picture.typeFromString(value(values, "pictureType").toText()));
    picture.setWidth(value(values, "width").toInt());
    picture.setHeight(value(values, "height").toInt());
    picture.setNumColors(value(values, "numColors").toInt());
    picture.setColorDepth(value(values, "colorDepth").toInt());
    return picture;
  }
}
