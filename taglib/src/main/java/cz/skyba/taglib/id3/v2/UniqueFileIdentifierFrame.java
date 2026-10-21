// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.toolkit.ByteVector;

import java.util.Objects;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

/// A mutable ID3v2 frame for file identification (UFID).
/// Editing a frame does not write a file; use the owning file's save operation to persist changes.
public class UniqueFileIdentifierFrame extends Frame {
  private String owner = "";
  private ByteVector identifier = new ByteVector();

  /// Decodes UFID owner and binary identifier from serialized frame bytes, including their frame header.
  ///
  /// @param data serialized bytes including the frame header
  public UniqueFileIdentifierFrame(ByteVector data) {
    super(data);
    parseIdentifier(fieldData(data));
  }

  /// Creates a frame containing UFID owner and binary identifier.
  ///
  /// @param owner      the owner identifier
  /// @param identifier binary identifier bytes to copy
  public UniqueFileIdentifierFrame(String owner, ByteVector identifier) {
    super(new ByteVector("UFID"));
    this.owner = Objects.requireNonNull(owner);
    this.identifier = new ByteVector(identifier);
  }

  UniqueFileIdentifierFrame(ByteVector data, Header header) {
    super(header);
    parseIdentifier(fieldData(data));
  }

  public final String owner() {
    return owner;
  }

  public final ByteVector identifier() {
    return new ByteVector(identifier);
  }

  public final void setOwner(String value) {
    owner = Objects.requireNonNull(value);
  }

  public final void setIdentifier(ByteVector value) {
    identifier = new ByteVector(value);
  }

  @Override
  public String toString() {
    return "";
  }

  @Override
  public PropertyMap asProperties() {
    return owner.equals("http://musicbrainz.org")
      ? PropertyMap.builder().put("MUSICBRAINZ_TRACKID", Id3Text.fixedLatin1(identifier)).build()
      : PropertyMap.builder().addUnsupportedData(Id3Text.fixedLatin1(frameID()) + "/" + owner).build();
  }

  /// Returns the first live matching frame from a tag.
  ///
  /// @param tag   the tag to search
  /// @param value the owner or description to match exactly
  /// @return the matching frame, or `null` if absent
  public static UniqueFileIdentifierFrame findByOwner(Tag tag, String value) {
    return findByOwner(tag.frameList(new ByteVector("UFID")), value);
  }

  /// Returns the first live matching frame from the supplied frame sequence.
  ///
  /// @param frames the frames to search in iteration order
  /// @param owner  the owner identifier to match exactly
  /// @return the matching frame, or `null` if absent
  public static UniqueFileIdentifierFrame findByOwner(Iterable<? extends Frame> frames, String owner) {
    for (Frame frame : frames) {
      if (frame instanceof UniqueFileIdentifierFrame id && id.owner().equals(owner)) {
        return id;
      }
    }
    return null;
  }

  @Override
  protected void parseFields(ByteVector data) {
    parseIdentifier(data);
  }

  private void parseIdentifier(ByteVector data) {
    if (data.isEmpty()) {
      return;
    }
    StringField field = stringField(data, ISO_8859_1, 0);
    owner = field.value();
    identifier = data.mid(field.nextPosition());
  }

  @Override
  protected ByteVector renderFields() {
    return ByteVector.fromText(owner, ISO_8859_1).append((byte) 0).append(identifier);
  }
}
