// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal;

@SuppressWarnings("ResultOfMethodCallIgnored")
public final class OverflowChecks {
  private OverflowChecks() {
  }

  public static void checkAddition(int left, int right) {
    Math.addExact(left, right);
  }

  public static void checkMultiplication(long left, long right) {
    Math.multiplyExact(left, right);
  }
}
