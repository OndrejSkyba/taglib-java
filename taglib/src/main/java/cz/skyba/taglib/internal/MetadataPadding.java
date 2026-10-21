// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal;

/// TagLib's bounded padding reserve for formats whose metadata can grow before audio data.
public final class MetadataPadding {
  private MetadataPadding() {
  }

  /// Retain usable capacity; cap reserves to one percent of the file, between minimum and 1 MiB.
  public static int bytes(long available, long fileSize, int minimum) {
    long threshold = Math.min(Math.max(fileSize / 100, minimum), 1024 * 1024);
    return available <= 0 || available > threshold ? minimum : (int) available;
  }
}
