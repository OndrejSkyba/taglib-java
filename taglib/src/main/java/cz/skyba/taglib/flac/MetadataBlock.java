// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.flac;

/**
 * A FLAC metadata payload. Rendering excludes the four-byte container block header.
 */
public abstract class MetadataBlock {
  /**
   * The defined FLAC metadata block codes.
   */
  public enum BlockType {
    StreamInfo, Padding, Application, SeekTable, VorbisComment, CueSheet, Picture;

    public int code() {
      return ordinal();
    }
  }

  protected MetadataBlock() {
  }

  public abstract int code();

  /**
   * Serializes this block's payload without its container header.
   *
   * @return payload bytes
   */
  public abstract byte[] render();
}
