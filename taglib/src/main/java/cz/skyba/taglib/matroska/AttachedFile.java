// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.matroska;

import cz.skyba.taglib.toolkit.ByteVector;

import java.util.Arrays;
import java.util.Objects;

/// A Matroska file attachment with copied data and an unsigned 64-bit UID.
public final class AttachedFile {
  private record State(byte[] data, String name, String mime, long uid, String description, long offset, long size,
                       boolean deferred) {
    State withLoadedData(byte[] value) {
      return new State(value.clone(), name, mime, uid, description, 0, 0, false);
    }
  }

  private State state;

  /// Creates an attachment with copied data.
  ///
  /// @param data        attachment bytes to copy
  /// @param name        the attachment filename
  /// @param mime        the MIME type
  /// @param uid         the unsigned 64-bit UID bit pattern, or zero if unspecified
  /// @param description attachment description text
  public AttachedFile(byte[] data, String name, String mime, long uid, String description) {
    state = new State(Objects.requireNonNull(data).clone(), Objects.requireNonNull(name), Objects.requireNonNull(mime), uid,
      Objects.requireNonNull(description), 0, 0, false);
  }

  /// Creates an attachment with copied data.
  ///
  /// @param data attachment bytes to copy
  /// @param name the attachment filename
  /// @param mime the MIME type
  /// @param uid  the unsigned 64-bit UID bit pattern, or zero if unspecified
  public AttachedFile(byte[] data, String name, String mime, long uid) {
    this(data, name, mime, uid, "");
  }

  /// Creates an attachment with copied data.
  ///
  /// @param data attachment bytes to copy
  /// @param name the attachment filename
  /// @param mime the MIME type
  public AttachedFile(byte[] data, String name, String mime) {
    this(data, name, mime, 0, "");
  }

  /// Creates an attachment with copied data.
  ///
  /// @param data        attachment bytes to copy
  /// @param name        the attachment filename
  /// @param mime        the MIME type
  /// @param uid         the unsigned 64-bit UID bit pattern, or zero if unspecified
  /// @param description attachment description text
  public AttachedFile(ByteVector data, String name, String mime, long uid, String description) {
    this(data.data(), name, mime, uid, description);
  }

  /// Creates an attachment with copied data.
  ///
  /// @param data attachment bytes to copy
  /// @param name the attachment filename
  /// @param mime the MIME type
  /// @param uid  the unsigned 64-bit UID bit pattern, or zero if unspecified
  public AttachedFile(ByteVector data, String name, String mime, long uid) {
    this(data, name, mime, uid, "");
  }

  /// Creates an attachment with copied data.
  ///
  /// @param data attachment bytes to copy
  /// @param name the attachment filename
  /// @param mime the MIME type
  public AttachedFile(ByteVector data, String name, String mime) {
    this(data, name, mime, 0, "");
  }

  /// Creates a copy of the attachment value.
  ///
  /// @param other the value to copy
  public AttachedFile(AttachedFile other) {
    state = Objects.requireNonNull(other).state;
  }

  AttachedFile(long offset, long size, String name, String mime, long uid, String description) {
    state = new State(new byte[0], name, mime, uid, description, offset, size, true);
  }

  int loadedDataSize() {
    return state.data.length;
  }

  boolean isDataDeferred() {
    return state.deferred;
  }

  long deferredDataOffset() {
    return state.offset;
  }

  long deferredDataSize() {
    return state.size;
  }

  void setLoadedData(byte[] data) {
    state = state.withLoadedData(data);
  }

  public String fileName() {
    return state.name;
  }

  public String mediaType() {
    return state.mime;
  }

  public String description() {
    return state.description;
  }

  /// Returns independent byte storage.
  ///
  /// @return the copied values
  public byte[] data() {
    return state.data.clone();
  }

  /// Returns independent byte storage.
  ///
  /// @return the copied values
  public ByteVector byteVector() {
    return new ByteVector(state.data);
  }

  public long uid() {
    return state.uid;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof AttachedFile f && uid() == f.uid() && fileName().equals(f.fileName()) && mediaType().equals(f.mediaType())
      && description().equals(f.description()) && Arrays.equals(state.data, f.state.data);
  }

  @Override
  public int hashCode() {
    return 31 * Objects.hash(fileName(), mediaType(), uid(), description()) + Arrays.hashCode(state.data);
  }
}
