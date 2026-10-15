// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v1;

import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.TagBase;
import cz.skyba.taglib.internal.TextBytes;
import cz.skyba.taglib.internal.TextNumbers;
import cz.skyba.taglib.internal.id3.Id3v1;
import cz.skyba.taglib.toolkit.ByteVector;

import java.util.Objects;

import static java.lang.System.Logger.Level.DEBUG;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

/**
 * Mutable ID3v1 common metadata. Field width and encoding limits apply when rendering.
 * Values remain editable in memory; the owning file writes the rendered tag on save.
 */
public class Tag extends TagBase {
  private static final System.Logger LOG = System.getLogger(Tag.class.getName());

  private final FileBase file;
  private final long tagOffset;
  private String title = "", artist = "", album = "", year = "", comment = "";
  private int track, genre = 255;

  /**
   * Creates an empty ID3v1 tag.
   */
  public Tag() {
    this(null, 0);
  }

  /**
   * Decodes ID3v1 fields from a borrowed file at a byte offset.
   *
   * @param file      the borrowed source, or null for an empty tag
   * @param tagOffset the absolute tag byte offset
   */
  public Tag(FileBase file, long tagOffset) {
    this.file = file;
    this.tagOffset = tagOffset;
    read();
  }

  /**
   * Decodes ID3v1 fields from a 128-byte tag. Invalid size or identifier leaves default field values.
   *
   * @param data encoded ID3v1 bytes
   */
  public Tag(ByteVector data) {
    file = null;
    tagOffset = 0;
    Objects.requireNonNull(data);
    if (data.size() == 128 && data.startsWith(fileIdentifier())) {
      parse(data);
    }
  }

  public static ByteVector fileIdentifier() {
    return ByteVector.fromCString("TAG");
  }

  /**
   * Installs the shared ID3v1 text decoder and encoder. Null restores the default handler.
   * Existing decoded tag fields are unchanged.
   *
   * @param handler the handler to borrow, or null for the default
   */
  public static void setStringHandler(StringHandler handler) {
    Id3v1.setStringHandler(handler);
  }

  @Override
  public String title() {
    return title;
  }

  @Override
  public String artist() {
    return artist;
  }

  @Override
  public String album() {
    return album;
  }

  @Override
  public String comment() {
    return comment;
  }

  @Override
  public String genre() {
    return Genres.genre(genre);
  }

  @Override
  public long year() {
    return Integer.toUnsignedLong(TextNumbers.parseInt(year));
  }

  @Override
  public long track() {
    return track;
  }

  @Override
  public void setTitle(String value) {
    title = Objects.requireNonNull(value);
  }

  @Override
  public void setArtist(String value) {
    artist = Objects.requireNonNull(value);
  }

  @Override
  public void setAlbum(String value) {
    album = Objects.requireNonNull(value);
  }

  @Override
  public void setComment(String value) {
    comment = Objects.requireNonNull(value);
  }

  @Override
  public void setGenre(String value) {
    genre = Genres.genreIndex(Objects.requireNonNull(value));
  }

  @Override
  public void setYear(long value) {
    int nativeValue = (int) value;
    year = nativeValue == 0 ? "" : Integer.toString(nativeValue);
  }

  @Override
  public void setTrack(long value) {
    long nativeValue = value & 0xffff_ffffL;
    track = nativeValue < 256 ? (int) nativeValue : 0;
  }

  public final long genreNumber() {
    return genre;
  }

  public final void setGenreNumber(long value) {
    long nativeValue = value & 0xffff_ffffL;
    genre = nativeValue < 256 ? (int) nativeValue : 255;
  }

  /**
   * Serializes this tag to its stored byte representation.
   */
  public final ByteVector render() {
    var result = fileIdentifier();
    result.append(Id3v1.renderText(title).resize(30));
    result.append(Id3v1.renderText(artist).resize(30));
    result.append(Id3v1.renderText(album).resize(30));
    result.append(Id3v1.renderText(year).resize(4));
    result.append(Id3v1.renderText(comment).resize(28));
    result.append((byte) 0).append((byte) track).append((byte) genre);
    return result;
  }

  /**
   * Decodes ID3v1 fields into this tag. A tag without a source file is unchanged.
   */
  protected final void read() {
    if (file != null && file.isValid()) {
      file.seek(tagOffset);
      var data = file.readBlock(128);
      if (data.size() == 128 && data.startsWith(fileIdentifier())) {
        parse(data);
      } else {
        LOG.log(DEBUG, "ID3v1 tag is not valid or could not be read at the specified offset.");
      }
    }
  }

  /**
   * Decodes ID3v1 fields into this tag.
   *
   * @param data encoded tag bytes
   */
  protected final void parse(ByteVector data) {
    if (data.size() < 128) {
      throw new IllegalArgumentException("ID3v1 footer requires 128 bytes");
    }
    title = Id3v1.parseText(data.mid(3, 30));
    artist = Id3v1.parseText(data.mid(33, 30));
    album = Id3v1.parseText(data.mid(63, 30));
    year = Id3v1.parseText(data.mid(93, 4));
    if (data.get(125) == 0 && data.get(126) != 0) {
      comment = Id3v1.parseText(data.mid(97, 28));
      track = data.get(126) & 255;
    } else {
      // Native v1.0 assigns ByteVector directly to String, bypassing StringHandler.
      comment = TextBytes.decodeTerminated(data.data(), 97, 30, ISO_8859_1);
    }
    genre = data.get(127) & 255;
  }
}
