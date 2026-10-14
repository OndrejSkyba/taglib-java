// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;

import java.util.Objects;

/**
 * Audio property selection, read style and a metadata allocation limit.
 * DSF, Shorten and tracker formats parse audio properties regardless of
 * {@code readAudioProperties}. The allocation limit bounds format metadata reads; it does
 * not limit audio payload length or guarantee a total heap limit.
 *
 * @param readAudioProperties whether audio properties should be read where optional
 * @param readStyle           the format-specific speed and accuracy hint
 * @param maxMetadataBytes    the metadata allocation limit in bytes, at least 1,024
 */
public record ReadOptions(boolean readAudioProperties, ReadStyle readStyle, int maxMetadataBytes) {
  public static final ReadOptions DEFAULT =
    new ReadOptions(true, ReadStyle.AVERAGE, Integer.MAX_VALUE);
  public static final ReadOptions TAGS_ONLY =
    new ReadOptions(false, ReadStyle.AVERAGE, Integer.MAX_VALUE);

  /**
   * Creates read options with an explicit metadata allocation limit.
   *
   * @throws NullPointerException     if {@code readStyle} is null
   * @throws IllegalArgumentException if {@code maxMetadataBytes} is less than 1,024
   */
  public ReadOptions {
    Objects.requireNonNull(readStyle);
    if (maxMetadataBytes < 1024) {
      throw new IllegalArgumentException("maxMetadataBytes must be at least 1024");
    }
  }
}
