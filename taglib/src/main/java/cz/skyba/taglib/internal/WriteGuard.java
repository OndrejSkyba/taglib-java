// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/// Java 25 scoped state binds a source snapshot to nested codec rewrites, without thread-local
/// leaks.
public final class WriteGuard {
  private WriteGuard() {
  }

  @FunctionalInterface
  public interface Operation {
    void run() throws IOException;
  }

  @FunctionalInterface
  public interface IOOperation<T> {
    T run() throws IOException;
  }

  private record Stamp(
    Path path, long size, Object key, FileTime modified) {
  }

  private static final ScopedValue<Stamp> SOURCE = ScopedValue.newInstance();
  private static final ReentrantLock[] LOCKS = locks();

  private static ReentrantLock[] locks() {
    var result = new ReentrantLock[256];
    Arrays.setAll(result, _ -> new ReentrantLock());
    return result;
  }

  public static void run(Path path, Operation action) throws IOException {
    call(path, () -> {
      action.run();
      return null;
    });
  }

  public static <T> T call(Path path, IOOperation<T> action) throws IOException {
    Path real = path.toRealPath();
    ReentrantLock lock = LOCKS[(real.hashCode() & Integer.MAX_VALUE) % LOCKS.length];
    lock.lock();
    try {
      var attributes = Files.readAttributes(real, BasicFileAttributes.class);
      var stamp = new Stamp(real, attributes.size(), attributes.fileKey(), attributes.lastModifiedTime());
      return ScopedValue.where(SOURCE, stamp).call(action::run);
    } finally {
      lock.unlock();
    }
  }

  public static void check(Path real) throws IOException {
    if (!SOURCE.isBound()) {
      return;
    }
    var stamp = SOURCE.get();
    if (!real.equals(stamp.path())) {
      throw new IOException("Rewrite path changed during save");
    }
    var attributes = Files.readAttributes(real, BasicFileAttributes.class);
    if (attributes.size() != stamp.size()
      || !Objects.equals(attributes.fileKey(), stamp.key())
      || !attributes.lastModifiedTime().equals(stamp.modified())) {
      throw new IOException("Source changed while parsing metadata; refusing stale write");
    }
  }
}
