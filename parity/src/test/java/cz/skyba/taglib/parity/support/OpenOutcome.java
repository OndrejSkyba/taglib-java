package cz.skyba.taglib.parity.support;

import cz.skyba.taglib.TagLibException;

/** Compares Java opening exceptions with the native oracle's invalid-file result. */
public final class OpenOutcome {
  private OpenOutcome() { }

  @FunctionalInterface
  public interface Open<T extends AutoCloseable> {
    T open();
  }

  public static <T extends AutoCloseable> T file(Open<T> open) {
    try {
      return open.open();
    } catch (TagLibException | java.io.UncheckedIOException failure) {
      return null;
    }
  }

  public static boolean rejected(Open<?> open) throws Exception {
    AutoCloseable file;
    try {
      file = open.open();
    } catch (TagLibException | java.io.UncheckedIOException failure) {
      return true;
    }
    try (file) {
      return false;
    }
  }
}
