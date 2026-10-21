// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.internal.PictureTypes;
import cz.skyba.taglib.toolkit.ByteVector;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static java.lang.System.Logger.Level.DEBUG;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

/// A mutable ID3v2 frame for embedded artwork (APIC).
/// Editing a frame does not write a file; use the owning file's save operation to persist changes.
public class AttachedPictureFrame extends Frame {
  private static final System.Logger LOG = System.getLogger(AttachedPictureFrame.class.getName());

  private static final Type[] TYPE_VALUES = Type.values();

  /// The picture role recorded in an APIC frame.
  public enum Type {
    Other, FileIcon, OtherFileIcon, FrontCover, BackCover, LeafletPage, Media,
    LeadArtist, Artist, Conductor, Band, Composer, Lyricist, RecordingLocation,
    DuringRecording, DuringPerformance, MovieScreenCapture, ColouredFish,
    Illustration, BandLogo, PublisherLogo;

    public int code() {
      return ordinal();
    }
  }

  private Charset encoding = ISO_8859_1;
  private String mimeType = "", description = "";
  private Type type = Type.Other;
  private ByteVector picture = new ByteVector();

  public AttachedPictureFrame() {
    super(new ByteVector("APIC"));
  }

  /// Decodes APIC MIME type, picture role, description and image bytes from serialized frame bytes, including their frame header.
  ///
  /// @param data serialized bytes including the frame header
  public AttachedPictureFrame(ByteVector data) {
    super(data);
    header().setData(data);
    parsePicture(fieldData(data));
  }

  AttachedPictureFrame(ByteVector data, Header header) {
    super(header);
    parsePicture(fieldData(data));
  }

  /// Returns the display name for a picture role.
  ///
  /// @param value the role or code to convert
  /// @return the converted picture role or its display name
  public static String typeToString(Type value) {
    return PictureTypes.nameForCode(Objects.requireNonNull(value).code());
  }

  /// Looks up a picture role by its display name.
  ///
  /// @param value the role or code to convert
  /// @return the converted picture role or its display name
  public static Type typeFromString(String value) {
    return TYPE_VALUES[PictureTypes.codeForName(value)];
  }

  /// Maps the low eight bits of a numeric code to a picture role.
  ///
  /// @param value the role or code to convert
  /// @return the converted picture role or its display name
  public static Type typeFromUInt(long value) {
    long narrowed = value & 0xffff_ffffL;
    return narrowed < TYPE_VALUES.length ? TYPE_VALUES[(int) narrowed] : Type.Other;
  }

  /// Maps an unsigned byte code to a picture role.
  ///
  /// @param value the role or code to convert
  /// @return the converted picture role or its display name
  public static Type typeFromByte(byte value) {
    return typeFromUInt(value & 255);
  }

  public final Charset textEncoding() {
    return encoding;
  }

  public final void setTextEncoding(Charset value) {
    encoding = Id3Text.requireEncoding(value);
  }

  public final String mimeType() {
    return mimeType;
  }

  public final void setMimeType(String value) {
    mimeType = Objects.requireNonNull(value);
  }

  public final Type type() {
    return type;
  }

  public final void setType(Type value) {
    type = Objects.requireNonNull(value);
  }

  public final String description() {
    return description;
  }

  public final void setDescription(String value) {
    description = Objects.requireNonNull(value);
  }

  public final ByteVector picture() {
    return new ByteVector(picture);
  }

  public final void setPicture(ByteVector value) {
    picture = new ByteVector(value);
  }

  @Override
  public String toString() {
    String summary = "[" + Id3Text.detachedLeft(mimeType) + "]";
    return description.isEmpty() ? summary : Id3Text.detachedLeft(description) + " " + summary;
  }

  @Override
  public List<String> toStringList() {
    return new ArrayList<>(List.of(description, mimeType));
  }

  @Override
  protected void parseFields(ByteVector data) {
    parsePicture(data);
  }

  private void parsePicture(ByteVector data) {
    if (data.size() < 5) {
      LOG.log(DEBUG, "A picture frame must contain at least 5 bytes.");
      return;
    }
    encoding = Id3Text.encoding(data.at(0));
    StringField field = stringField(data, ISO_8859_1, 1);
    mimeType = field.value();
    int position = field.nextPosition();
    if (position + 1 >= data.size()) {
      LOG.log(DEBUG, "Truncated picture frame.");
      return;
    }
    type = typeFromByte(data.at(position++));
    field = stringField(data, encoding, position);
    description = field.value();
    picture = data.mid(field.nextPosition());
  }

  // The native V22 subclass shares protected private-state storage. Keep the Java access
  // restricted to that package-owned implementation, without exposing mutable field values.
  final void parsePictureV22(ByteVector data) {
    if (data.size() < 5) {
      LOG.log(DEBUG, "A picture frame must contain at least 5 bytes.");
      return;
    }
    encoding = Id3Text.encoding(data.at(0));
    String format = Id3Text.fixedLatin1(data.mid(1, 3));
    mimeType = switch (Id3Text.upper(format)) {
      case "JPG" -> "image/jpeg";
      case "PNG" -> "image/png";
      default -> {
        LOG.log(DEBUG, "Unrecognized image format; deriving the MIME type from its identifier.");
        yield "image/" + format;
      }
    };
    type = typeFromByte(data.at(4));
    StringField field = stringField(data, encoding, 5);
    description = field.value();
    picture = data.mid(field.nextPosition());
  }

  @Override
  protected ByteVector renderFields() {
    Charset rendered = checkTextEncoding(List.of(description), encoding);
    return new ByteVector(Id3Text.encodingByte(rendered)).append(ByteVector.fromText(mimeType, ISO_8859_1))
      .append(textDelimiter(ISO_8859_1)).append((byte) type.ordinal())
      .append(ByteVector.fromText(description, rendered)).append(textDelimiter(rendered)).append(picture);
  }
}
