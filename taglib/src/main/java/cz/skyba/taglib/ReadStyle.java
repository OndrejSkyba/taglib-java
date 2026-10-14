// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;

/**
 * A hint for the speed and accuracy of audio property parsing.
 * {@code FAST} favors fewer reads, {@code ACCURATE} favors a more thorough analysis, and
 * {@code AVERAGE} selects the normal tradeoff. Formats may ignore this hint.
 */
public enum ReadStyle {
  FAST,
  AVERAGE,
  ACCURATE
}
