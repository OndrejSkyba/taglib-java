// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal;

import java.io.IOException;
import java.util.List;

/// Explicit AudioFile save request, scoped to one render; ordinary readers never hide source data.
public final class UnsupportedDataRemoval {
  private static final ScopedValue<List<String>> REQUEST = ScopedValue.newInstance();

  private UnsupportedDataRemoval() {
  }

  public static List<String> requested() {
    return REQUEST.isBound() ? REQUEST.get() : List.of();
  }

  public static boolean contains(String name) {
    return requested().contains(name);
  }

  public static void run(List<String> requested, WriteGuard.Operation action) throws IOException {
    ScopedValue.where(REQUEST, List.copyOf(requested)).call(() -> {
      action.run();
      return null;
    });
  }
}
