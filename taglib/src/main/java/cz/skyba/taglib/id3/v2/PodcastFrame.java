// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.toolkit.ByteVector;

import java.util.List;

/**
 * A mutable ID3v2 frame containing PCST podcast marker.
 * Editing a frame does not write a file; use the owning file's save operation to persist changes.
 */
public class PodcastFrame extends Frame {
  private ByteVector fields = new ByteVector(4);

  /**
   * Creates a frame containing PCST podcast marker.
   */
  public PodcastFrame() {
    super(new ByteVector("PCST"));
  }

  PodcastFrame(ByteVector data, Header header) {
    super(header);
    fields = fieldData(data);
  }

  @Override
  public String toString() {
    return "";
  }

  @Override
  public PropertyMap asProperties() {
    var result = PropertyMap.builder();
    result.add("PODCAST", List.of());
    return result.build();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void parseFields(ByteVector data) {
    fields = new ByteVector(data);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected ByteVector renderFields() {
    return new ByteVector(fields);
  }
}
