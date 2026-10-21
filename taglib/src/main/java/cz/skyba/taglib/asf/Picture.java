// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.asf;

import cz.skyba.taglib.internal.PictureTypes;
import cz.skyba.taglib.internal.TextBytes;
import cz.skyba.taglib.toolkit.ByteVector;

import java.util.Objects;

import static java.nio.charset.StandardCharsets.UTF_16LE;

/// A mutable ASF picture value with copied image inputs and detached binary exports.
public final class Picture {
  /// A picture-role code that also represents unnamed integer codes.
  ///
  /// @param code the stored role code
  public record Type(int code) {
    public static final Type Other = new Type(0);
    public static final Type FileIcon = new Type(1);
    public static final Type OtherFileIcon = new Type(2);
    public static final Type FrontCover = new Type(3);
    public static final Type BackCover = new Type(4);
    public static final Type LeafletPage = new Type(5);
    public static final Type Media = new Type(6);
    public static final Type LeadArtist = new Type(7);
    public static final Type Artist = new Type(8);
    public static final Type Conductor = new Type(9);
    public static final Type Band = new Type(10);
    public static final Type Composer = new Type(11);
    public static final Type Lyricist = new Type(12);
    public static final Type RecordingLocation = new Type(13);
    public static final Type DuringRecording = new Type(14);
    public static final Type DuringPerformance = new Type(15);
    public static final Type MovieScreenCapture = new Type(16);
    public static final Type ColouredFish = new Type(17);
    public static final Type Illustration = new Type(18);
    public static final Type BandLogo = new Type(19);
    public static final Type PublisherLogo = new Type(20);
  }

  private boolean valid = true;
  private Type type = Type.Other;
  private String mime = "", description = "";
  // Internal payloads are replaced, never mutated; copies can safely share them.
  private ByteVector bytes;

  public Picture() {
    bytes = new ByteVector();
  }

  /// Creates an independently mutable copy of a picture value.
  ///
  /// @param other the picture to copy
  public Picture(Picture other) {
    Objects.requireNonNull(other);
    valid = other.valid;
    type = other.type;
    mime = other.mime;
    description = other.description;
    bytes = other.bytes;
  }

  /// Decodes a serialized picture value.
  ///
  /// @param bytes encoded picture bytes
  public Picture(ByteVector bytes) {
    this();
    parse(bytes);
  }

  /// Creates a picture value with its validity flag unset.
  ///
  /// @return an invalid picture value
  public static Picture fromInvalid() {
    var p = new Picture();
    p.valid = false;
    return p;
  }

  public boolean isValid() {
    return valid;
  }

  public String mimeType() {
    return mime;
  }

  public void setMimeType(String value) {
    mime = Objects.requireNonNull(value);
  }

  public Type type() {
    return type;
  }

  public void setType(Type value) {
    type = Objects.requireNonNull(value);
  }

  public String description() {
    return description;
  }

  public void setDescription(String value) {
    description = Objects.requireNonNull(value);
  }

  /// Returns an independent copy of the image bytes.
  ///
  /// @return picture bytes
  public ByteVector picture() {
    return new ByteVector(bytes);
  }

  public void setPicture(ByteVector value) {
    bytes = new ByteVector(value);
  }

  /// Returns the serialized picture value's byte size.
  ///
  /// @return the byte size
  public int dataSize() {
    return Math.addExact(9, Math.addExact(Math.multiplyExact(mime.length() + description.length(), 2), bytes.size()));
  }

  /// Serializes the picture fields and image data without writing a file.
  ///
  /// @return picture bytes
  public ByteVector render() {
    if (!isValid()) {
      return new ByteVector();
    }
    return new ByteVector(new byte[]{(byte) type.code()}).append(ByteVector.fromUnsignedInt(bytes.size(), false))
      .append(stringBytes(mime)).append(stringBytes(description)).append(bytes);
  }

  /// Decodes an ASF picture value, updating fields as they are read. Invalid or incomplete data
  /// leaves the validity flag unset.
  ///
  /// @param bytes serialized picture value bytes
  public void parse(ByteVector bytes) {
    valid = false;
    if (bytes.size() < 9) {
      return;
    }
    type = typeFromByte(bytes.at(0));
    long length = bytes.toUnsignedInt(1, false);
    int position = 5;
    var terminator = new ByteVector(2);
    int end = bytes.find(terminator, position, 2);
    if (end < 0) {
      return;
    }
    mime = TextBytes.decodeTerminated(bytes.data(), position, end - position, UTF_16LE);
    position = end + 2;
    end = bytes.find(terminator, position, 2);
    if (end < 0) {
      return;
    }
    description = TextBytes.decodeTerminated(bytes.data(), position, end - position, UTF_16LE);
    position = end + 2;
    if (((length + position) & 0xffff_ffffL) != bytes.size()) {
      return;
    }
    this.bytes = bytes.mid(position, (int) length);
    valid = true;
  }

  static ByteVector stringBytes(String value) {
    return ByteVector.fromText(value + '\0', UTF_16LE);
  }

  public static Type typeFromUInt(long value) {
    long n = value & 0xffff_ffffL;
    return n < 21 ? new Type((int) n) : Type.Other;
  }

  public static Type typeFromByte(byte value) {
    return typeFromUInt(value & 255);
  }

  public static Type typeFromString(String value) {
    return new Type(PictureTypes.codeForName(value));
  }

  public static String typeToString(Type type) {
    int n = type.code();
    return n >= 0 && n < 21 ? PictureTypes.nameForCode(n) : "";
  }
}
