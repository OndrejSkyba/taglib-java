// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.ebml;

import cz.skyba.taglib.internal.BinaryFile;

import java.io.IOException;
import java.util.Objects;

/// Binary payload held as a file range until an attachment consumer requests its data.
public final class DeferredBinaryElement {
  private final long dataOffset;
  private final long dataSize;
  private byte[] value;

  /// Records an already validated element range without reading its payload.
  public DeferredBinaryElement(long dataOffset, long dataSize) {
    if (dataOffset < 0 || dataSize < 0) {
      throw new IllegalArgumentException("Negative EBML range");
    }
    this.dataOffset = dataOffset;
    this.dataSize = dataSize;
  }

  /// Newly rendered binary elements already own their data and are never deferred.
  public DeferredBinaryElement(byte[] value) {
    this.value = Objects.requireNonNull(value).clone();
    dataOffset = 0;
    dataSize = value.length;
  }

  public boolean isDeferred() {
    return value == null;
  }

  public long getDataOffset() {
    return dataOffset;
  }

  public long getDataSize() {
    return dataSize;
  }

  /// Caches a bounded payload; callers validate the aggregate allocation before loading a list.
  public byte[] load(BinaryFile file, int maxBytes) throws IOException {
    if (value == null) {
      if (dataSize > maxBytes) {
        throw new IOException("EBML attachment exceeds allocation limit");
      }
      value = file.read(dataOffset, (int) dataSize);
    }
    return value.clone();
  }
}
