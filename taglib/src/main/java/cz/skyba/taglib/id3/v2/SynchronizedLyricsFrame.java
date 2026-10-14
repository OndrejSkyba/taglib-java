// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.internal.ByteVectorOutput;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.Debug;
import cz.skyba.taglib.toolkit.TagString;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A mutable ID3v2 frame containing SYLT language, description and timed text.
 * Editing a frame does not write a file; use the owning file's save operation to persist changes.
 */
public class SynchronizedLyricsFrame extends Frame {
  private static final TimestampFormat[] TIMESTAMP_FORMATS = TimestampFormat.values();
  private static final Type[] TYPES = Type.values();

  /**
   * The unit used by frame timestamps.
   */
  public enum TimestampFormat {
    Unknown, AbsoluteMpegFrames, AbsoluteMilliseconds;

    public int code() {
      return ordinal();
    }
  }

  /**
   * The category of synchronized text.
   */
  public enum Type {
    Other, Lyrics, TextTranscription, Movement, Events, Chord, Trivia, WebpageUrls, ImageUrls;

    public int code() {
      return ordinal();
    }
  }

  /**
   * An immutable timed entry. Timestamps retain their low 32 bits and use the containing frame's units.
   *
   * @param time the timestamp in the containing frame's selected units
   * @param text the synchronized text
   */
  public record SynchedText(long time, String text) {
    /**
     * Creates a timed entry, retaining the timestamp's low 32 bits.
     *
     * @throws NullPointerException if the text is null
     */
    public SynchedText {
      time &= 0xffff_ffffL;
      Objects.requireNonNull(text);
    }
  }

  private TagString.Type encoding = TagString.Type.LATIN1;
  private ByteVector language = new ByteVector();
  private TimestampFormat format = TimestampFormat.AbsoluteMilliseconds;
  private Type type = Type.Lyrics;
  private String description = "";
  private final List<SynchedText> text = new ArrayList<>();

  /**
   * Creates a frame containing SYLT language, description and timed text. The initial text encoding is ISO-8859-1.
   */
  public SynchronizedLyricsFrame() {
    this(TagString.Type.LATIN1);
  }

  /**
   * Creates a frame containing SYLT language, description and timed text.
   *
   * @param encoding the requested text encoding
   */
  public SynchronizedLyricsFrame(TagString.Type encoding) {
    super(new ByteVector("SYLT"));
    this.encoding = Objects.requireNonNull(encoding);
  }

  /**
   * Decodes SYLT language, description and timed text from serialized frame bytes, including their frame header.
   *
   * @param data serialized bytes including the frame header
   */
  public SynchronizedLyricsFrame(ByteVector data) {
    super(data);
    header().setData(data);
    parseLyrics(fieldData(data));
  }

  SynchronizedLyricsFrame(ByteVector data, Header header) {
    super(header);
    parseLyrics(fieldData(data));
  }

  public final TagString.Type textEncoding() {
    return encoding;
  }

  public final void setTextEncoding(TagString.Type value) {
    encoding = Objects.requireNonNull(value);
  }

  /**
   * Returns an independent copy of the stored language-code bytes.
   *
   * @return copied language bytes
   */
  public final ByteVector language() {
    return new ByteVector(language);
  }

  /**
   * Copies up to the first three language-code bytes into the live frame.
   *
   * @param value language bytes to copy
   */
  public final void setLanguage(ByteVector value) {
    language = value.mid(0, 3);
  }

  public final TimestampFormat timestampFormat() {
    return format;
  }

  public final void setTimestampFormat(TimestampFormat value) {
    format = Objects.requireNonNull(value);
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

  /**
   * Returns an independent mutable list of immutable timed text entries.
   *
   * @return an independent list of entries
   */
  public final List<SynchedText> synchedText() {
    return new ArrayList<>(text);
  }

  /**
   * Replaces the timed text entries, copying the list and rejecting null entries.
   *
   * @param value replacement entries
   */
  public final void setSynchedText(List<SynchedText> value) {
    var copy = List.copyOf(value);
    text.clear();
    text.addAll(copy);
  }

  @Override
  public String toString() {
    return description;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void parseFields(ByteVector data) {
    parseLyrics(data);
  }

  private void parseLyrics(ByteVector data) {
    int end = data.size();
    if (end < 7) {
      Debug.debug(new TagString("A synchronized lyrics frame must contain at least 7 bytes."));
      return;
    }
    encoding = Id3Text.encoding(data.at(0));
    language = data.mid(1, 3);
    int formatCode = data.at(4) & 255, typeCode = data.at(5) & 255;
    format = formatCode < TIMESTAMP_FORMATS.length ? TIMESTAMP_FORMATS[formatCode] : TimestampFormat.Unknown;
    type = typeCode < TYPES.length ? TYPES[typeCode] : Type.Other;
    StringField field = stringField(data, encoding, 6);
    description = field.value();
    int position = field.nextPosition();
    if (position == 6) {
      return;
    }
    TagString.Type inherited = encoding;
    if (encoding == TagString.Type.UTF16) {
      int bom = data.toUnsignedShort(6, true);
      if (bom == 0xfffe) {
        inherited = TagString.Type.UTF16LE;
      } else if (bom == 0xfeff) {
        inherited = TagString.Type.UTF16BE;
      }
    }
    text.clear();
    while (position < end) {
      TagString.Type entryEncoding = encoding;
      if (entryEncoding == TagString.Type.UTF16 && end - position > 1) {
        int bom = data.toUnsignedShort(position, true);
        if (bom != 0xfffe && bom != 0xfeff) {
          entryEncoding = inherited;
        }
      }
      field = stringField(data, entryEncoding, position);
      String value = field.value();
      position = field.nextPosition();
      if (end - position < 4) {
        return;
      }
      long time = data.toUnsignedInt(position, true);
      position += 4;
      text.add(new SynchedText(time, value));
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected ByteVector renderFields() {
    TagString.Type rendered = checkTextEncoding(List.of(description), encoding);
    for (var entry : text) {
      rendered = checkTextEncoding(List.of(entry.text()), rendered);
    }
    var data = new ByteVectorOutput().append((byte) rendered.ordinal()).append(language.size() == 3 ? language : new ByteVector("XXX"))
      .append((byte) format.code())
      .append((byte) type.code())
      .append(Id3Text.encode(description, rendered))
      .append(textDelimiter(rendered));
    for (var entry : text) {
      data.append(Id3Text.encode(entry.text(), rendered)).append(textDelimiter(rendered)).append(ByteVector.fromUnsignedInt(entry.time()));
    }
    return data.toByteVector();
  }
}
