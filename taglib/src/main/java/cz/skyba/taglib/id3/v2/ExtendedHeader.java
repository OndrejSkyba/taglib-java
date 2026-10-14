// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.toolkit.ByteVector;

/**
 * Mutable ID3v2 extended-header size fields for parsing and serialization.
 */
public class ExtendedHeader {
  private long size;

  public ExtendedHeader() {
  }

  public long size() {
    return size;
  }

  /**
   * Decodes ID3v2 extended-header size fields from the supplied bytes.
   *
   * @param data encoded ID3v2 extended-header size bytes
   */
  public void setData(ByteVector data) {
    parseData(data);
  }

  /**
   * Decodes available header fields into this object.
   *
   * @param data encoded header bytes
   */
  protected void parse(ByteVector data) {
    parseData(data);
  }

  private void parseData(ByteVector data) {
    size = SynchData.toUnsignedInt(data.mid(0, 4));
  }
}
