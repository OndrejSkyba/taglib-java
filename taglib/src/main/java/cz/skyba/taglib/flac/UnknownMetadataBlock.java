// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.flac;

import java.util.Objects;

/**
 * A mutable FLAC metadata payload retained by numeric block code.
 * Payload inputs and exports are copied, so edits through returned arrays do not change the block.
 */
public final class UnknownMetadataBlock extends MetadataBlock {
  private int code;
  private byte[] data;

  /**
   * Creates a metadata block with copied payload bytes.
   *
   * @param code the FLAC block code
   * @param data the payload bytes
   */
  public UnknownMetadataBlock(int code, byte[] data) {
    this.code = code;
    setData(data);
  }

  @Override
  public int code() {
    return code;
  }

  public void setCode(int value) {
    code = value;
  }

  public byte[] data() {
    return data.clone();
  }

  /**
   * Replaces the stored binary payload with an independent copy.
   *
   * @param value the bytes to copy
   */
  public void setData(byte[] value) {
    data = Objects.requireNonNull(value).clone();
  }

  @Override
  public byte[] render() {
    return data();
  }
}
