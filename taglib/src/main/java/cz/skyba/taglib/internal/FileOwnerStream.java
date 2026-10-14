// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal;

import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.IOStream;

/**
 * Borrowed read view that dispatches through the owner's virtual methods and never closes it.
 * FileBase supplies its writable subclass to format collaborators.
 */
public class FileOwnerStream extends IOStream {
  protected final FileBase owner;

  protected FileOwnerStream(FileBase owner) {
    this.owner = owner;
  }

  public static IOStream properties(FileBase owner) {
    return new FileOwnerStream(owner);
  }

  public final FileBase owner() {
    return owner;
  }

  @Override
  public String name() {
    return owner.name();
  }

  @Override
  public ByteVector readBlock(long count) {
    return owner.readBlock(count);
  }

  @Override
  public void writeBlock(ByteVector value) {
    throw new UnsupportedOperationException("Read-only file view");
  }

  @Override
  public void insert(ByteVector value, long at, long replace) {
    throw new UnsupportedOperationException("Read-only file view");
  }

  @Override
  public void removeBlock(long at, long count) {
    throw new UnsupportedOperationException("Read-only file view");
  }

  @Override
  public boolean readOnly() {
    return true;
  }

  @Override
  public boolean isOpen() {
    return owner.isOpen();
  }

  @Override
  public void seek(long offset, Position origin) {
    owner.seek(offset, origin);
  }

  @Override
  public void clear() {
    owner.clear();
  }

  @Override
  public long tell() {
    return owner.tell();
  }

  @Override
  public long length() {
    return owner.length();
  }

  @Override
  public void truncate(long length) {
    throw new UnsupportedOperationException("Read-only file view");
  }
}
