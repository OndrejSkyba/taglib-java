// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal;

/// Owned-resource cleanup, preserving the primary diagnostic.
public final class FailureSupport {
  private FailureSupport() {
  }

  public static void closeOnFailure(AutoCloseable resource, Throwable failure) {
    try {
      resource.close();
    } catch (Throwable cleanup) {
      if (cleanup != failure) {
        failure.addSuppressed(cleanup);
      }
    }
  }
}
