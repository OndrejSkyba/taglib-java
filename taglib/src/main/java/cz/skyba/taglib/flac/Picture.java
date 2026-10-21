// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.flac;

import cz.skyba.taglib.internal.Binary;
import cz.skyba.taglib.internal.PictureTypes;
import cz.skyba.taglib.internal.TextBytes;
import cz.skyba.taglib.internal.xiph.XiphComment;

import java.util.Arrays;
import java.util.Objects;

import static java.lang.System.Logger.Level.DEBUG;
import static java.nio.charset.StandardCharsets.UTF_8;

/// A mutable FLAC picture value with copied image inputs and detached binary exports.
public class Picture extends MetadataBlock {
  private static final System.Logger LOG = System.getLogger(Picture.class.getName());

  private static final Type[] TYPE_VALUES = Type.values();

  /// The role of an embedded FLAC picture.
  public enum Type {
    Other, FileIcon, OtherFileIcon, FrontCover, BackCover, LeafletPage,
    Media, LeadArtist, Artist, Conductor, Band, Composer, Lyricist, RecordingLocation,
    DuringRecording, DuringPerformance, MovieScreenCapture, ColouredFish, Illustration,
    BandLogo, PublisherLogo;

    public int code() {
      return ordinal();
    }
  }

  private Type type = Type.Other;
  private String mimeType = "", description = "";
  private int width, height, colorDepth, numColors;
  private byte[] data = new byte[0];

  public Picture() {
  }

  /// Decodes a serialized picture value.
  ///
  /// @param payload the encoded FLAC picture payload without its block header
  public Picture(byte[] payload) {
    parse(payload);
  }

  public static String typeToString(Type type) {
    return PictureTypes.nameForCode(Objects.requireNonNull(type).code());
  }

  public static Type typeFromString(String value) {
    return TYPE_VALUES[PictureTypes.codeForName(value)];
  }

  public static Type typeFromUInt(long value) {
    long narrowed = value & 0xffff_ffffL;
    return narrowed < TYPE_VALUES.length ? TYPE_VALUES[(int) narrowed] : Type.Other;
  }

  public static Type typeFromByte(byte value) {
    return typeFromUInt(value & 255);
  }

  @Override
  public int code() {
    return BlockType.Picture.code();
  }

  public final Type type() {
    return type;
  }

  public final void setType(Type value) {
    type = Objects.requireNonNull(value);
  }

  public final String mimeType() {
    return mimeType;
  }

  public final void setMimeType(String value) {
    mimeType = Objects.requireNonNull(value);
  }

  public final String description() {
    return description;
  }

  public final void setDescription(String value) {
    description = Objects.requireNonNull(value);
  }

  public final int width() {
    return width;
  }

  public final void setWidth(int value) {
    width = value;
  }

  public final int height() {
    return height;
  }

  public final void setHeight(int value) {
    height = value;
  }

  public final int colorDepth() {
    return colorDepth;
  }

  public final void setColorDepth(int value) {
    colorDepth = value;
  }

  public final int numColors() {
    return numColors;
  }

  public final void setNumColors(int value) {
    numColors = value;
  }

  /// Returns an independent copy of the image bytes.
  ///
  /// @return picture bytes
  public final byte[] data() {
    return data.clone();
  }

  /// Replaces the stored binary payload with an independent copy.
  ///
  /// @param value the bytes to copy
  public final void setData(byte[] value) {
    data = Objects.requireNonNull(value).clone();
  }

  /// Decodes serialized picture fields into this object.
  ///
  /// @param payload encoded picture payload bytes
  /// @return whether the picture payload was readable
  public final boolean parse(byte[] payload) {
    Objects.requireNonNull(payload);
    if (payload.length < 32) {
      LOG.log(DEBUG, "A picture block must contain at least 32 bytes.");
      return false;
    }
    type = typeFromUInt(Binary.u32be(payload, 0));
    long mimeLength = Binary.u32be(payload, 4);
    if (mimeLength > payload.length - 32) {
      LOG.log(DEBUG, "Invalid picture block.");
      return false;
    }
    int pos = 8;
    mimeType = TextBytes.decodeTerminated(payload, pos, (int) mimeLength, UTF_8);
    pos += (int) mimeLength;
    long descriptionLength = Binary.u32be(payload, pos);
    pos += 4;
    if (descriptionLength > payload.length - pos - 20) {
      LOG.log(DEBUG, "Invalid picture block.");
      return false;
    }
    description = TextBytes.decodeTerminated(payload, pos, (int) descriptionLength, UTF_8);
    pos += (int) descriptionLength;
    width = (int) Binary.u32be(payload, pos);
    height = (int) Binary.u32be(payload, pos + 4);
    colorDepth = (int) Binary.u32be(payload, pos + 8);
    numColors = (int) Binary.u32be(payload, pos + 12);
    pos += 16;
    long length = Binary.u32be(payload, pos);
    pos += 4;
    if (length > payload.length - pos) {
      LOG.log(DEBUG, "Invalid picture block.");
      return false;
    }
    data = Arrays.copyOfRange(payload, pos, pos + (int) length);
    return true;
  }

  /// Creates an immutable format-independent picture snapshot with copied image bytes.
  ///
  /// @return the picture snapshot
  public final cz.skyba.taglib.Picture snapshot() {
    return new cz.skyba.taglib.Picture(type.code(), mimeType, description, data, width, height, colorDepth, numColors);
  }

  /// Serializes the picture fields and image data without writing a file.
  ///
  /// @return picture bytes
  @Override
  public byte[] render() {
    return XiphComment.renderPicture(type.code(), mimeType, description, data, width, height, colorDepth, numColors);
  }
}
