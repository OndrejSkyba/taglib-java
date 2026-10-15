// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.internal.Ascii;
import cz.skyba.taglib.internal.TextBytes;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.Debug;
import cz.skyba.taglib.toolkit.MutablePropertyMap;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static java.nio.charset.StandardCharsets.*;

/**
 * A mutable ID3v2 frame with a live header and extensible field parsing and rendering.
 * Payload size and frame identifier come from the header. Rendering updates that header's
 * payload size and emits serialized bytes without writing storage. Subclasses define their
 * field layout and common-property mapping.
 * Text-encoding parameters accept {@link StandardCharsets#ISO_8859_1},
 * {@link StandardCharsets#UTF_8}, {@link StandardCharsets#UTF_16},
 * {@link StandardCharsets#UTF_16BE} and {@link StandardCharsets#UTF_16LE}.
 * Unsupported charsets are rejected with {@link IllegalArgumentException}.
 */
public abstract class Frame {
  public static final String instrumentPrefix = "PERFORMER:";
  public static final String commentPrefix = "COMMENT:";
  public static final String lyricsPrefix = "LYRICS:";
  public static final String urlPrefix = "URL:";
  private static final long UINT_MASK = 0xffff_ffffL;
  private static final long MAX_COMPRESSED_OUTPUT = 64L * 1024 * 1024;
  private static final Set<String> LEGACY_DATE_IDS = Set.of("TRDA", "TDAT", "TYER", "TIME");
  private static final Map<String, String> TRANSLATION = translations();
  private static final Map<String, String> FRAME_IDS = reverseTranslations();

  private static Map<String, String> reverseTranslations() {
    var reverse = new HashMap<String, String>();
    TRANSLATION.forEach((id, key) -> reverse.putIfAbsent(key, id));
    return Map.copyOf(reverse);
  }

  private Header header;

  /**
   * Initializes a frame by reading a version-4 header from serialized bytes. The subclass parses its fields.
   *
   * @param data serialized frame bytes
   */
  protected Frame(ByteVector data) {
    header = new Header(data);
  }

  /**
   * Initializes a frame with a live header reference, without copying it.
   *
   * @param header the live frame header
   */
  protected Frame(Header header) {
    this.header = header;
  }

  public final int headerSize() {
    return header.size();
  }

  /**
   * Returns independent frame-identifier bytes.
   *
   * @return copied identifier bytes
   */
  public final ByteVector frameID() {
    return header == null ? new ByteVector() : header.frameID();
  }

  public final long size() {
    return header == null ? 0 : header.frameSize();
  }

  public final Header header() {
    return header;
  }

  /**
   * Reparses serialized frame bytes using an ID3v2.4 header and the concrete field decoder.
   * The existing header object is updated rather than replaced when available.
   *
   * @param data bytes including the frame header
   */
  public final void setData(ByteVector data) {
    parse(data);
  }

  public void setText(String text) {
  }

  @Override
  public abstract String toString();

  /**
   * Returns a text representation as a detached list. The base implementation wraps
   * {@link #toString()} as one value; text frames can expose multiple values.
   *
   * @return displayed text values
   */
  public List<String> toStringList() {
    return new ArrayList<>(List.of(toString()));
  }

  /**
   * Serializes fields and updates the live header's payload size. Serialized frame flags are zero.
   * This does not write a file.
   *
   * @return the header and encoded payload
   */
  public final ByteVector render() {
    ByteVector fields = Objects.requireNonNull(renderFields());
    header.setFrameSize(fields.size());
    return header.render().append(fields);
  }

  /**
   * Creates the NUL delimiter for an encoding: two zero bytes for UTF-16 forms, one otherwise.
   *
   * @param encoding the text encoding
   * @return delimiter bytes
   */
  public static ByteVector textDelimiter(Charset encoding) {
    Id3Text.requireEncoding(encoding);
    return new ByteVector(UTF_16.equals(encoding) || UTF_16BE.equals(encoding)
      || UTF_16LE.equals(encoding) ? 2 : 1);
  }

  /**
   * Maps a frame identifier to a common property key. Recognized date-frame identifiers map to DATE.
   *
   * @param id the frame identifier
   * @return the property key, or an empty string if unmapped
   */
  public static String frameIDToKey(ByteVector id) {
    String value = new String(id.data(), StandardCharsets.ISO_8859_1);
    if (LEGACY_DATE_IDS.contains(value)) {
      value = "TDRC";
    }
    return TRANSLATION.getOrDefault(value, "");
  }

  /**
   * Maps a standard property key to a frame identifier, or an empty vector when unmapped.
   */
  public static ByteVector keyToFrameID(String key) {
    String upper = Ascii.upper(key);
    return new ByteVector(FRAME_IDS.getOrDefault(upper, ""));
  }

  protected final void setHeader(Header value) {
    header = value;
  }

  /**
   * Reparses a version-4 frame header and delegates the payload to {@link #parseFields(ByteVector)}.
   *
   * @param data serialized frame bytes
   */
  protected final void parse(ByteVector data) {
    Objects.requireNonNull(data);
    if (header == null) {
      header = new Header(data);
    } else {
      header.setData(data);
    }
    parseFields(fieldData(data));
  }

  /**
   * Decodes a frame payload into the concrete frame's fields. The supplied bytes exclude
   * the frame header and have passed through frame-level decoding.
   *
   * @param data decoded payload bytes
   */
  protected abstract void parseFields(ByteVector data);

  /**
   * Serializes the concrete frame fields without a frame header.
   *
   * @return encoded payload bytes
   */
  protected abstract ByteVector renderFields();

  /**
   * Extracts and decodes payload bytes according to the live header, including eligible compressed data.
   * A missing or rejected compressed payload returns empty data.
   *
   * @param data serialized bytes including the frame header
   * @return decoded payload bytes
   */
  protected final ByteVector fieldData(ByteVector data) {
    Objects.requireNonNull(data);
    long headerSize = header.size(), offset = headerSize, length = size();
    if (header.compression() || header.dataLengthIndicator()) {
      length = SynchData.toUnsignedInt(mid(data, headerSize, 4));
      offset += 4;
    }
    if (!header.compression() && data.size() >= headerSize
      && ((offset + length) & UINT_MASK) > data.size()) {
      if (offset > data.size()) {
        Debug.debug("Invalid frame data length");
        return new ByteVector();
      }
      length = data.size() - offset;
    }
    if (header.compression() && !header.encryption()) {
      if (data.size() <= offset) {
        Debug.debug("Compressed frame doesn't have enough data to decode");
        return new ByteVector();
      }
      ByteVector compressed = mid(data, offset, data.size());
      if (length > MAX_COMPRESSED_OUTPUT || length > (long) compressed.size() * 64) {
        Debug.debug("Compressed frame exceeds decompression limit");
        return new ByteVector();
      }
      var decoded = FrameCompression.decompress(compressed, (int) length);
      if (!decoded.isEmpty() && length != decoded.size()) {
        Debug.debug("frameDataLength does not match the data length returned by zlib");
      }
      return decoded;
    }
    return mid(data, offset, length);
  }

  /**
   * Reads one NUL-terminated string field using the selected encoding.
   * A missing delimiter returns empty text without advancing the position.
   *
   * @param data     the payload bytes
   * @param encoding the text encoding
   * @return decoded field text
   */
  protected final String readStringField(ByteVector data, Charset encoding) {
    return readStringField(data, encoding, null);
  }

  record StringField(String value, int nextPosition) {
  }

  final StringField stringField(ByteVector data, Charset encoding, int start) {
    Objects.requireNonNull(data);
    ByteVector delimiter = textDelimiter(encoding);
    int end = data.find(delimiter, start, delimiter.size());
    return end < start
      ? new StringField("", start)
      : new StringField(
      Id3Text.decode(data.mid(start, end - start), encoding), end + delimiter.size());
  }

  /**
   * Reads one NUL-terminated string field using the selected encoding.
   * A missing delimiter returns empty text without advancing the position.
   *
   * @param data     the payload bytes
   * @param encoding the text encoding
   * @param position a one-element cursor array updated past the delimiter, or null to start at zero
   * @return decoded field text
   * @throws IllegalArgumentException if the cursor array does not have one element
   */
  protected final String readStringField(ByteVector data, Charset encoding, int[] position) {
    Objects.requireNonNull(data);
    if (position != null && position.length != 1) {
      throw new IllegalArgumentException("String position must have exactly one element");
    }
    int start = position == null ? 0 : position[0];
    StringField field = stringField(data, encoding, start);
    if (position != null && field.nextPosition() != start) {
      position[0] = field.nextPosition();
    }
    return field.value();
  }

  /**
   * Selects an encoding that can represent all fields in the header's target version.
   * Non-Latin-1 text promotes Latin-1 to UTF-8 for version 4 or UTF-16 for earlier versions.
   *
   * @param fields   text fields to represent
   * @param encoding the requested encoding
   * @return an encoding suitable for the fields and version
   */
  protected final Charset checkTextEncoding(List<String> fields, Charset encoding) {
    Objects.requireNonNull(fields);
    Id3Text.requireEncoding(encoding);
    if ((UTF_8.equals(encoding) || UTF_16BE.equals(encoding))
      && header.version() != 4) {
      return UTF_16;
    }
    if (!ISO_8859_1.equals(encoding)) {
      return encoding;
    }
    for (String field : fields) {
      if (!TextBytes.isLatin1(field)) {
        Debug.debug(header.version() == 4
          ? "Frame::checkEncoding() -- Rendering using UTF8." : "Frame::checkEncoding() -- Rendering using UTF16.");
        return header.version() == 4 ? UTF_8 : UTF_16;
      }
    }
    return ISO_8859_1;
  }

  /**
   * Returns the standard properties represented by this frame.
   */
  protected PropertyMap asProperties() {
    String id = Id3Text.fixedLatin1(frameID());
    return PropertyMap.builder().addUnsupportedData(this instanceof UnknownFrame ? "UNKNOWN/" + id : id).build();
  }

  /**
   * Partitions properties into ordinary fields, involved-people credits and musician credits.
   * The destination maps are cleared before values are copied; the source is unchanged.
   *
   * @param original the source properties
   * @param single   ordinary text properties
   * @param tipl     involved-people properties
   * @param tmcl     musician-credit properties
   */
  protected static void splitProperties(
    MutablePropertyMap original,
    MutablePropertyMap single, MutablePropertyMap tipl,
    MutablePropertyMap tmcl
  ) {
    single.clear();
    tipl.clear();
    tmcl.clear();
    for (var entry : original) {
      var destination = TextIdentificationFrame.involvedPeopleMap().containsKey(entry.getKey()) ? tipl
        : entry.getKey().startsWith(instrumentPrefix) ? tmcl : single;
      destination.add(entry.getKey(), entry.getValue());
    }
  }

  private static ByteVector mid(ByteVector data, long offset, long length) {
    if (offset >= data.size()) {
      return new ByteVector();
    }
    return data.mid((int) offset, (int) Math.min(length, data.size() - offset));
  }


  private static Map<String, String> translations() {
    var result = new TreeMap<String, String>();
    for (String line : """
      TALB ALBUM
      TBPM BPM
      TCOM COMPOSER
      TCON GENRE
      TCOP COPYRIGHT
      TDEN ENCODINGTIME
      TDLY PLAYLISTDELAY
      TDOR ORIGINALDATE
      TDRC DATE
      TDRL RELEASEDATE
      TDTG TAGGINGDATE
      TENC ENCODEDBY
      TEXT LYRICIST
      TFLT FILETYPE
      TIT1 WORK
      TIT2 TITLE
      TIT3 SUBTITLE
      TKEY INITIALKEY
      TLAN LANGUAGE
      TLEN LENGTH
      TMED MEDIA
      TMOO MOOD
      TOAL ORIGINALALBUM
      TOFN ORIGINALFILENAME
      TOLY ORIGINALLYRICIST
      TOPE ORIGINALARTIST
      TOWN OWNER
      TPE1 ARTIST
      TPE2 ALBUMARTIST
      TPE3 CONDUCTOR
      TPE4 REMIXER
      TPOS DISCNUMBER
      TPRO PRODUCEDNOTICE
      TPUB LABEL
      TRCK TRACKNUMBER
      TRSN RADIOSTATION
      TRSO RADIOSTATIONOWNER
      TSOA ALBUMSORT
      TSOC COMPOSERSORT
      TSOP ARTISTSORT
      TSOT TITLESORT
      TSO2 ALBUMARTISTSORT
      TSRC ISRC
      TSSE ENCODING
      TSST DISCSUBTITLE
      WCOP COPYRIGHTURL
      WOAF FILEWEBPAGE
      WOAR ARTISTWEBPAGE
      WOAS AUDIOSOURCEWEBPAGE
      WORS RADIOSTATIONWEBPAGE
      WPAY PAYMENTWEBPAGE
      WPUB PUBLISHERWEBPAGE
      COMM COMMENT
      PCST PODCAST
      TCAT PODCASTCATEGORY
      TDES PODCASTDESC
      TGID PODCASTID
      WFED PODCASTURL
      MVNM MOVEMENTNAME
      MVIN MOVEMENTNUMBER
      GRP1 GROUPING
      TCMP COMPILATION
      """.strip().split("\n")) {
      String[] pair = line.strip().split(" ", 2);
      result.put(pair[0], pair[1]);
    }
    return Map.copyOf(result);
  }

  /**
   * A live mutable frame header. Rendering emits a four-byte size field and two zero flag bytes.
   */
  public static class Header {
    private ByteVector frameID = new ByteVector();
    private long frameSize, version = 4;
    private boolean tagAlter, fileAlter, readOnly, grouping, compression, encryption, unsync, dli;

    /**
     * Decodes the frame header from serialized bytes. The version is ID3v2.4.
     *
     * @param data bytes starting at the frame header
     */
    public Header(ByteVector data) {
      this(data, 4);
    }

    /**
     * Decodes the frame header from serialized bytes.
     *
     * @param data    bytes starting at the frame header
     * @param version the tag major version
     */
    public Header(ByteVector data, long version) {
      parseData(data, version);
    }

    Header(ByteVector data, int offset, long version) {
      parseData(data, offset, version);
    }

    /**
     * Updates header fields from serialized frame bytes. The version is ID3v2.4.
     *
     * @param data bytes starting at the frame header
     */
    public final void setData(ByteVector data) {
      parseData(data, 4);
    }

    /**
     * Updates header fields from serialized frame bytes.
     *
     * @param data    bytes starting at the frame header
     * @param version the tag major version
     */
    public final void setData(ByteVector data, long version) {
      parseData(data, version);
    }

    /**
     * Returns independent frame-identifier bytes.
     *
     * @return copied identifier bytes
     */
    public final ByteVector frameID() {
      return new ByteVector(frameID);
    }

    public final void setFrameID(ByteVector id) {
      frameID = id.mid(0, 4);
    }

    public final long frameSize() {
      return frameSize;
    }

    public final void setFrameSize(long size) {
      frameSize = size & UINT_MASK;
    }

    public final long version() {
      return version;
    }

    public final void setVersion(long version) {
      this.version = version & UINT_MASK;
    }

    public final int size() {
      return version <= 2 ? 6 : 10;
    }

    public final boolean tagAlterPreservation() {
      return tagAlter;
    }

    public final void setTagAlterPreservation(boolean preserve) {
      tagAlter = preserve;
    }

    public final boolean fileAlterPreservation() {
      return fileAlter;
    }

    public final boolean readOnly() {
      return readOnly;
    }

    public final boolean groupingIdentity() {
      return grouping;
    }

    public final boolean compression() {
      return compression;
    }

    public final boolean encryption() {
      return encryption;
    }

    public final boolean unsynchronisation() {
      return unsync;
    }

    public final boolean dataLengthIndicator() {
      return dli;
    }

    /**
     * Serializes the identifier, four-byte size and two zero flag bytes.
     *
     * @return frame-header bytes
     */
    public final ByteVector render() {
      return new ByteVector(frameID).append(version == 3 ? ByteVector.fromUnsignedInt(frameSize)
        : SynchData.fromUnsignedInt(frameSize)).append(new ByteVector(2));
    }

    private void parseData(ByteVector data, long newVersion) {
      parseData(data, 0, newVersion);
    }

    private void parseData(ByteVector data, int offset, long newVersion) {
      Objects.requireNonNull(data);
      version = newVersion & UINT_MASK;
      int idSize = version <= 2 ? 3 : 4;
      if (data.size() - offset < idSize) {
        Debug.debug("You must at least specify a frame ID.");
        return;
      }
      frameID = data.mid(offset, idSize);
      if (data.size() - offset < size()) {
        frameSize = 0;
        return;
      }
      if (version <= 2) {
        frameSize = data.toUnsignedInt(offset + 3, 3, true);
        return;
      }
      frameSize = version == 3 ? data.toUnsignedInt(offset + 4, true) : SynchData.toUnsignedInt(data.mid(offset + 4, 4));
      if (version != 3 && frameSize > 127 && !validIDAt(data, offset + ((frameSize + 10) & UINT_MASK))) {
        long uintSize = data.toUnsignedInt(offset + 4, true);
        if (validIDAt(data, offset + ((uintSize + 10) & UINT_MASK))) {
          frameSize = uintSize;
        }
      }
      int first = data.at(offset + 8) & 255, second = data.at(offset + 9) & 255;
      tagAlter = (first & (version == 3 ? 128 : 64)) != 0;
      fileAlter = (first & (version == 3 ? 64 : 32)) != 0;
      readOnly = (first & (version == 3 ? 32 : 16)) != 0;
      grouping = (second & (version == 3 ? 32 : 64)) != 0;
      compression = (second & (version == 3 ? 128 : 8)) != 0;
      encryption = (second & (version == 3 ? 64 : 4)) != 0;
      if (version != 3) {
        unsync = (second & 2) != 0;
        dli = (second & 1) != 0;
      }
    }

    private static boolean validIDAt(ByteVector data, long offset) {
      if (offset > data.size() - 4L) {
        return false;
      }
      for (int i = 0; i < 4; i++) {
        int value = data.at((int) offset + i) & 255;
        if ((value < 'A' || value > 'Z') && (value < '0' || value > '9')) {
          return false;
        }
      }
      return true;
    }
  }
}
