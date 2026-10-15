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

  /**
   * Removes ASCII space, tab, newline, form feed and carriage return at either end.
   * Vertical tabs and other Unicode whitespace are retained.
   */
  public static String stripWhiteSpace(String value) {
    int begin = 0, end = value.length();
    while (begin < end && "\t\n\f\r ".indexOf(value.charAt(begin)) >= 0) {
      begin++;
    }
    while (end > begin && "\t\n\f\r ".indexOf(value.charAt(end - 1)) >= 0) {
      end--;
    }
    return value.substring(begin, end);
  }
}
