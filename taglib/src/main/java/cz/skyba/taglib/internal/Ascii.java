// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal;

import java.util.Objects;

/**
 * ASCII transformations shared by native-compatible property and string APIs.
 */
public final class Ascii {
  private Ascii() {
  }

  public static String upper(String value) {
    char[] chars = Objects.requireNonNull(value).toCharArray();
    for (int i = 0; i < chars.length; i++) {
      if (chars[i] >= 'a' && chars[i] <= 'z') {
        chars[i] -= 'a' - 'A';
      }
    }
    return new String(chars);
  }
}
