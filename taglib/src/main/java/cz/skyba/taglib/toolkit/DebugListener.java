// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.toolkit;

/**
 * Receives TagLib diagnostic messages. Installing a listener does not enable diagnostics;
 * see {@link Debug#setMode(Debug.Mode)}.
 */
public abstract class DebugListener {
  protected DebugListener() {
  }

  /**
   * Receives the complete message, including any prefix and trailing newline.
   */
  public abstract void printMessage(TagString message);
}
