// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.PropertyUpdateResult;
import cz.skyba.taglib.TagBase;
import cz.skyba.taglib.id3.v1.Genres;
import cz.skyba.taglib.internal.ByteVectorOutput;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.MutablePropertyMap;
import cz.skyba.taglib.toolkit.TagString;
import cz.skyba.taglib.toolkit.Variant;

import java.util.*;

/**
 * A mutable ID3v2 tag containing live frame objects and a live tag header.
 * Frame list and map views are read-only but reflect structural edits. Frame objects remain
 * mutable. Editing and rendering operate in memory; the owning file writes serialized bytes
 * when saved.
 */
public class Tag extends TagBase {
  private static final Variant EMPTY_VARIANT = new Variant();

  private static final Set<String> V4_ONLY_FRAME_IDS =
    Set.of("ASPI", "EQU2", "RVA2", "SEEK", "SIGN", "TDRL", "TDTG", "TMOO", "TPRO", "TSST");

  /**
   * The output ID3v2 major version.
   */
  public enum Version {
    V3, V4;

    public int code() {
      return this == V3 ? 3 : 4;
    }
  }

  private final FrameFactory factory;
  private final FileBase file;
  private final long tagOffset;
  private final Header header = new Header();
  private ExtendedHeader extendedHeader;
  private final FrameCollection frames = new FrameCollection();

  /**
   * Creates an empty in-memory ID3v2 tag using the shared frame factory.
   */
  public Tag() {
    factory = FrameFactory.instance();
    file = null;
    tagOffset = 0;
  }

  /**
   * Reads an ID3v2 tag from a borrowed file at an absolute byte offset.
   *
   * @param file   the borrowed source, or null to create an empty tag
   * @param offset the absolute tag-header byte offset
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws IllegalStateException        if a required frame factory is unavailable
   */
  public Tag(FileBase file, long offset) {
    this(file, offset, FrameFactory.instance());
  }

  /**
   * Reads an ID3v2 tag from a borrowed file at an absolute byte offset.
   * A null factory leaves frame construction unavailable; operations that require it fail.
   *
   * @param file    the borrowed source, or null to create an empty tag
   * @param offset  the absolute tag-header byte offset
   * @param factory the decoder and frame-creation factory
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws IllegalStateException        if a required frame factory is unavailable
   */
  public Tag(FileBase file, long offset, FrameFactory factory) {
    this.file = file;
    tagOffset = offset;
    this.factory = factory;
    read();
  }

  // Explicit null factories have defined getter/render/existing-frame paths upstream.
  // Reject only at the operation that would actually dereference the native pointer.
  private FrameFactory checkedFactory() {
    if (factory == null) {
      throw new IllegalStateException("ID3v2 operation requires a frame factory");
    }
    return factory;
  }

  public final Header header() {
    return header;
  }

  public final ExtendedHeader extendedHeader() {
    return extendedHeader;
  }

  /**
   * Returns a read-only live map. Looking up an absent ID returns {@code null} without adding it.
   */
  public final Map<ByteVector, List<Frame>> frameListMap() {
    return frames.map();
  }

  /**
   * Returns a read-only live list of frame references in tag order.
   * Frame objects are mutable; use frame-management methods for structural changes.
   *
   * @return the live frame list
   */
  public final List<Frame> frameList() {
    return frames.list();
  }

  /**
   * Returns the read-only live list for an existing ID, or an empty list when absent.
   * An absent lookup does not create a bucket; query again after adding frames for a new ID.
   */
  public final List<Frame> frameList(ByteVector id) {
    return frames.bucket(id);
  }

  /**
   * Retains a live frame reference in this tag without copying it.
   *
   * @param frame the frame to add
   */
  public final void addFrame(Frame frame) {
    frames.add(frame);
  }

  /**
   * Removes the supplied frame reference without invalidating it for the caller.
   *
   * @param frame the retained frame to remove
   */
  public final void removeFrame(Frame frame) {
    frames.remove(frame, false);
  }

  /**
   * Removes all frames with the supplied identifier.
   *
   * @param id the frame identifier to match
   */
  public final void removeFrames(ByteVector id) {
    frames.removeAll(id, false);
  }

  @Override
  public boolean isEmpty() {
    return frames.list().isEmpty();
  }

  private String common(String id) {
    var list = frameList(new ByteVector(id));
    return list.isEmpty() ? "" : joinTagValues(list.getFirst().toStringList());
  }

  @Override
  public String title() {
    return common("TIT2");
  }

  @Override
  public String artist() {
    return common("TPE1");
  }

  @Override
  public String album() {
    return common("TALB");
  }

  /**
   * Prefers a comment with an empty description, falling back to the first comment when none matches.
   */
  @Override
  public String comment() {
    var list = frameList(new ByteVector("COMM"));
    if (list.isEmpty()) {
      return "";
    }
    for (Frame frame : list) {
      if (frame instanceof CommentsFrame comment && comment.description().isEmpty()) {
        return frame.toString();
      }
    }
    return list.getFirst().toString();
  }

  /**
   * Expands ID3 genre codes and joins nonempty genre names using the common-field separator.
   *
   * @return displayed genre text
   */
  @Override
  public String genre() {
    var list = frameList(new ByteVector("TCON"));
    if (list.isEmpty() || !(list.getFirst() instanceof TextIdentificationFrame text)) {
      return "";
    }
    var values = new ArrayList<String>();
    for (String field : text.fieldList()) {
      if (field.isEmpty()) {
        continue;
      }
      var number = new TagString(field).extractInt();
      if (number.ok() && number.value() >= 0 && number.value() <= 255) {
        field = Genres.genre(number.value());
      }
      if (!values.contains(field)) {
        values.add(field);
      }
    }
    return joinTagValues(values);
  }

  /**
   * Parses the leading date text as an unsigned 32-bit year.
   *
   * @return the year, or zero when absent or invalid
   */
  @Override
  public long year() {
    var list = frameList(new ByteVector("TDRC"));
    if (list.isEmpty()) {
      return 0;
    }
    String text = list.getFirst().toString();
    return Integer.toUnsignedLong(new TagString(text.substring(0, Math.min(4, text.length()))).toInt());
  }

  @Override
  public long track() {
    var list = frameList(new ByteVector("TRCK"));
    return list.isEmpty() ? 0 : Integer.toUnsignedLong(new TagString(list.getFirst().toString()).toInt());
  }

  @Override
  public void setTitle(String value) {
    setTextFrame(new ByteVector("TIT2"), value);
  }

  @Override
  public void setArtist(String value) {
    setTextFrame(new ByteVector("TPE1"), value);
  }

  @Override
  public void setAlbum(String value) {
    setTextFrame(new ByteVector("TALB"), value);
  }

  @Override
  public void setGenre(String value) {
    setTextFrame(new ByteVector("TCON"), value);
  }

  @Override
  public void setYear(long value) {
    setNumber("TDRC", value);
  }

  @Override
  public void setTrack(long value) {
    setNumber("TRCK", value);
  }

  private void setNumber(String id, long value) {
    value &= 0xffff_ffffL;
    if (value == 0) {
      removeFrames(new ByteVector(id));
    } else {
      setTextFrame(new ByteVector(id), Integer.toString((int) value));
    }
  }

  /**
   * Updates an empty-description comment or the first existing comment, creating one only when
   * absent. An empty value removes all comments.
   */
  @Override
  public void setComment(String value) {
    Objects.requireNonNull(value);
    if (value.isEmpty()) {
      removeFrames(new ByteVector("COMM"));
      return;
    }
    var list = frameList(new ByteVector("COMM"));
    if (!list.isEmpty()) {
      for (Frame frame : list) {
        if (frame instanceof CommentsFrame comment && comment.description().isEmpty()) {
          frame.setText(value);
          return;
        }
      }
      list.getFirst().setText(value);
      return;
    }
    var comment = new CommentsFrame(checkedFactory().defaultTextEncoding());
    addFrame(comment);
    comment.setText(value);
  }

  /**
   * Merges frame property exports, collecting identifiers for frames that cannot be represented as
   * ordinary text.
   */
  @Override
  public PropertyMap properties() {
    var properties = new MutablePropertyMap();
    for (Frame frame : frameList()) {
      properties.merge(new MutablePropertyMap(frame.asProperties()));
    }
    return properties.snapshot();
  }

  /**
   * Removes metadata identified by the supplied unsupported-data keys.
   */
  @Override
  public void removeUnsupportedProperties(List<String> properties) {
    for (String property : properties) {
      if (property.startsWith("UNKNOWN/")) {
        String id = property.substring(8);
        if (id.length() != 4) {
          continue;
        }
        for (Frame frame : new ArrayList<>(frameList(Id3Text.encode(id, TagString.Type.LATIN1)))) {
          if (frame instanceof UnknownFrame) {
            removeFrame(frame);
          }
        }
      } else if (property.length() == 4) {
        removeFrames(Id3Text.encode(property, TagString.Type.LATIN1));
      } else if (property.length() > 5) {
        ByteVector id = Id3Text.encode(property.substring(0, 4), TagString.Type.LATIN1);
        String description = property.substring(5);
        Frame frame = switch (Id3Text.fixedLatin1(id)) {
          case "TXXX" -> UserTextIdentificationFrame.find(frameList(id), description);
          case "WXXX" -> UserUrlLinkFrame.find(frameList(id), description);
          case "COMM" -> CommentsFrame.findByDescription(frameList(id), description);
          case "USLT" -> UnsynchronizedLyricsFrame.findByDescription(frameList(id), description);
          case "UFID" -> UniqueFileIdentifierFrame.findByOwner(frameList(id), description);
          default -> null;
        };
        if (frame != null) {
          removeFrame(frame);
        }
      }
    }
  }

  @Override
  public PropertyUpdateResult replaceProperties(PropertyMap properties) {
    var single = new MutablePropertyMap();
    var tipl = new MutablePropertyMap();
    var tmcl = new MutablePropertyMap();
    Frame.splitProperties(new MutablePropertyMap(properties), single, tipl, tmcl);
    var remove = new ArrayList<Frame>();
    for (var entry : frameListMap().entrySet()) {
      for (Frame frame : entry.getValue()) {
        var frameProperties = new MutablePropertyMap(frame.asProperties());
        var pending = entry.getKey().equals(new ByteVector("TIPL"))
          ? tipl
          : entry.getKey().equals(new ByteVector("TMCL")) ? tmcl : single;
        boolean matches = pending == single ? pending.containsAll(frameProperties) : pending.equals(frameProperties);
        if (matches) {
          pending.removeAll(frameProperties);
        } else {
          remove.add(frame);
        }
      }
    }
    for (Frame frame : remove) {
      removeFrame(frame);
    }
    if (!tipl.isEmpty()) {
      addFrame(TextIdentificationFrame.createTIPLFrame(tipl.snapshot()));
    }
    if (!tmcl.isEmpty()) {
      addFrame(TextIdentificationFrame.createTMCLFrame(tmcl.snapshot()));
    }
    for (var entry : single) {
      addFrame(checkedFactory().createFrameForProperty(entry.getKey(), entry.getValue()));
    }
    return PropertyUpdateResult.fromRejections(properties, PropertyMap.EMPTY);
  }

  @Override
  public boolean supportsPictureEditing() {
    return true;
  }

  /**
   * Reports PICTURE and GENERALOBJECT when their APIC or GEOB frame-ID buckets exist.
   */
  @Override
  public List<String> complexPropertyKeys() {
    var result = new ArrayList<String>();
    if (frameListMap().containsKey(new ByteVector("APIC"))) {
      result.add("PICTURE");
    }
    if (frameListMap().containsKey(new ByteVector("GEOB"))) {
      result.add("GENERALOBJECT");
    }
    return result;
  }

  @Override
  public List<Map<String, Variant>> complexProperties(String key) {
    String upper = Id3Text.upper(key);
    var result = new ArrayList<Map<String, Variant>>();
    ByteVector id = new ByteVector(upper.equals("PICTURE") ? "APIC" : "GEOB");
    if (!upper.equals("PICTURE") && !upper.equals("GENERALOBJECT")) {
      return result;
    }
    // Native value() does not create a bucket.
    if (!frameListMap().containsKey(id)) {
      return result;
    }
    for (Frame frame : frameList(id)) {
      var property = new TreeMap<String, Variant>();
      if (upper.equals("PICTURE") && frame instanceof AttachedPictureFrame picture) {
        property.put("data", new Variant(picture.picture()));
        property.put("mimeType", new Variant(picture.mimeType()));
        property.put("description", new Variant(picture.description()));
        property.put("pictureType", new Variant(AttachedPictureFrame.typeToString(picture.type())));
      } else if (upper.equals("GENERALOBJECT") && frame instanceof GeneralEncapsulatedObjectFrame object) {
        property.put("data", new Variant(object.object()));
        property.put("mimeType", new Variant(object.mimeType()));
        property.put("description", new Variant(object.description()));
        property.put("fileName", new Variant(object.fileName()));
      } else {
        continue;
      }
      result.add(property);
    }
    return result;
  }

  @Override
  public boolean setComplexProperties(String key, List<Map<String, Variant>> values) {
    String upper = Id3Text.upper(key);
    if (!upper.equals("PICTURE") && !upper.equals("GENERALOBJECT")) {
      return false;
    }
    removeFrames(new ByteVector(upper.equals("PICTURE") ? "APIC" : "GEOB"));
    for (var property : values) {
      if (upper.equals("PICTURE")) {
        var picture = new AttachedPictureFrame();
        picture.setPicture(value(property, "data").toByteVector());
        picture.setMimeType(value(property, "mimeType").toText());
        picture.setDescription(value(property, "description").toText());
        picture.setType(AttachedPictureFrame.typeFromString(value(property, "pictureType").toText()));
        addFrame(picture);
      } else {
        var object = new GeneralEncapsulatedObjectFrame();
        object.setObject(value(property, "data").toByteVector());
        object.setMimeType(value(property, "mimeType").toText());
        object.setDescription(value(property, "description").toText());
        object.setFileName(value(property, "fileName").toText());
        addFrame(object);
      }
    }
    return true;
  }

  private static Variant value(Map<String, Variant> map, String key) {
    return map.getOrDefault(key, EMPTY_VARIANT);
  }

  /**
   * Serializes this tag in memory, updating live header sizes, output version and writable flags.
   * Frames not representable in the selected version can be converted or omitted. No file is written. The output version is ID3v2.4.
   *
   * @return tag bytes including header and padding
   * @throws IllegalArgumentException if the result exceeds Java byte-vector representation
   */
  public final ByteVector render() {
    return render(Version.V4);
  }

  /**
   * Serializes this tag in memory, updating live header sizes, output version and writable flags.
   * Frames not representable in the selected version can be converted or omitted. No file is written.
   *
   * @param version the target output version
   * @return tag bytes including header and padding
   * @throws IllegalArgumentException if the result exceeds Java byte-vector representation
   */
  public final ByteVector render(Version version) {
    Objects.requireNonNull(version);
    var selected = new ArrayList<Frame>();
    if (version == Version.V4) {
      selected.addAll(frameList());
    } else {
      downgradeFrames(selected);
    }
    var output = new ByteVectorOutput().append(new ByteVector(Header.size()));
    for (Frame frame : selected) {
      frame.header().setVersion(version.code());
      if (frame.frameID().size() != 4 || frame.header().tagAlterPreservation()) {
        continue;
      }
      ByteVector rendered = frame.render();
      if (rendered.size() != frame.headerSize()) {
        output.append(rendered);
      }
    }
    long padding = header.tagSize() - (output.size() - Header.size());
    long threshold = Math.clamp(file == null ? 0 : file.length() / 100, 1024, 1024 * 1024);
    if (padding <= 0 || padding > threshold) {
      padding = 1024;
    }
    long length = output.size() + padding;
    if (length > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Rendered tag exceeds Java byte-vector extent");
    }
    var data = output.toByteVector((int) padding);
    header.setMajorVersion(version.code());
    header.setTagSize(data.size() - Header.size());
    byte[] renderedHeader = header.render().data();
    System.arraycopy(renderedHeader, 0, data.data(), 0, renderedHeader.length);
    return data;
  }

  /**
   * Returns the shared decoder for fields marked as ISO-8859-1.
   *
   * @return the active decoder
   */
  public static Latin1StringHandler latin1StringHandler() {
    return Id3Text.handler();
  }

  /**
   * Installs a decoder shared by ID3v2 text parsing. The handler is borrowed and is not closed.
   * Passing null restores the default decoder.
   *
   * @param handler the decoder to install, or null to restore the default
   */
  public static void setLatin1StringHandler(Latin1StringHandler handler) {
    Id3Text.setHandler(handler);
  }

  /**
   * Reads the tag from its borrowed file and stored offset. A tag without a source file is unchanged.
   *
   * @throws java.io.UncheckedIOException if storage access fails
   */
  protected final void read() {
    if (file == null || !file.isOpen()) {
      return;
    }
    file.seek(tagOffset);
    header.setData(file.readBlock(Header.size()));
    if (header.tagSize() != 0) {
      parse(file.readBlock(header.tagSize()));
    }
    long extra = 0;
    // Brent cycle detection retains constant space and performs exactly the native reads.
    // A repeated unsigned offset otherwise makes the native duplicate scan nonterminating.
    long checkpoint = 0, power = 1, distance = 0;
    while (true) {
      long offset = Math.addExact(Math.addExact(tagOffset, header.completeTagSize()), extra);
      file.seek(offset);
      ByteVector data = file.readBlock(Header.size());
      if (data.size() < Header.size() || !data.startsWith(Header.fileIdentifier())) {
        break;
      }
      extra = (extra + new Header(data).completeTagSize()) & 0xffff_ffffL;
      if (extra == checkpoint) {
        throw new IllegalStateException("Duplicate tag scan revisited an offset after unsigned size overflow");
      }
      if (++distance == power) {
        checkpoint = extra;
        power *= 2;
        distance = 0;
      }
    }
    if (extra != 0) {
      header.setTagSize(header.tagSize() + extra);
    }
  }

  /**
   * Decodes serialized tag content into the live frame collection.
   *
   * @param original bytes beginning after the tag header
   */
  protected final void parse(ByteVector original) {
    ByteVector data = new ByteVector(original);
    if (header.unsynchronisation() && header.majorVersion() <= 3) {
      data = SynchData.decode(data);
    }
    long position = 0, length = data.size();
    int count = 0;
    if (header.extendedHeader()) {
      if (extendedHeader == null) {
        extendedHeader = new ExtendedHeader();
      }
      extendedHeader.setData(data);
      if (extendedHeader.size() <= data.size()) {
        position += extendedHeader.size();
      }
    }
    if (header.footerPresent() && Footer.size() <= length) {
      length -= Footer.size();
    }
    while (position < ((length - Header.size()) & 0xffff_ffffL)) {
      if (position >= data.size() || data.at((int) position) == 0) {
        break;
      }
      if (count++ >= 50_000) {
        break;
      }
      long originalVersion = header.majorVersion();
      Frame frame = checkedFactory().createFrameAt(data, (int) position, header);
      if (frame == null) {
        return;
      }
      long step = frame.size() + frame.headerSize();
      if (frame.header().version() != originalVersion) {
        var originalHeader = new Frame.Header(data, (int) position, originalVersion);
        step = originalHeader.frameSize() + originalHeader.size();
      }
      position = (position + step) & 0xffff_ffffL;
      if (frame.size() > 0) {
        addFrame(frame);
      }
    }
    checkedFactory().rebuildAggregateFrames(this);
  }

  /**
   * Replaces the text value of an existing frame or creates the mapped frame when absent.
   * An empty value removes frames for the identifier. Changes remain in memory.
   *
   * @param id    the text-frame identifier
   * @param value replacement text
   */
  protected final void setTextFrame(ByteVector id, String value) {
    Objects.requireNonNull(value);
    if (value.isEmpty()) {
      removeFrames(id);
      return;
    }
    var list = frameList(id);
    if (!list.isEmpty()) {
      list.getFirst().setText(value);
    } else {
      var text = new TextIdentificationFrame(id, checkedFactory().defaultTextEncoding());
      addFrame(text);
      text.setText(value);
    }
  }

  /**
   * Appends frames suitable for version 3 output, converting supported version 4 representations.
   */
  protected final void downgradeFrames(List<Frame> selected) {
    TextIdentificationFrame tdor = null, tdrc = null, tipl = null, tmcl = null, tcon = null;
    for (Frame frame : frameList()) {
      String id = Id3Text.fixedLatin1(frame.frameID());
      if (V4_ONLY_FRAME_IDS.contains(id)) {
        continue;
      }
      var text = frame instanceof TextIdentificationFrame typed ? typed : null;
      switch (id) {
        case "TDOR" -> tdor = text;
        case "TDRC" -> tdrc = text;
        case "TIPL" -> tipl = text;
        case "TMCL" -> tmcl = text;
        case "TCON" -> tcon = text;
        default -> selected.add(frame);
      }
    }
    if (tdor != null) {
      String content = tdor.toString();
      if (content.length() >= 4) {
        addTemporary("TORY", content.substring(0, 4), downgraded(tdor), selected);
      }
    }
    if (tdrc != null) {
      String content = tdrc.toString();
      if (content.length() >= 4) {
        var encoding = downgraded(tdrc);
        addTemporary("TYER", content.substring(0, 4), encoding, selected);
        if (content.length() >= 10 && content.charAt(4) == '-' && content.charAt(7) == '-') {
          addTemporary("TDAT", content.substring(8, 10) + content.substring(5, 7), encoding, selected);
          if (content.length() >= 16 && content.charAt(10) == 'T' && content.charAt(13) == ':') {
            addTemporary("TIME", content.substring(11, 13) + content.substring(14, 16), encoding, selected);
          }
        }
      }
    }
    if (tipl != null || tmcl != null) {
      var encoding = (tipl == null || tipl.textEncoding() == TagString.Type.LATIN1) && (tmcl == null
        || tmcl.textEncoding() == TagString.Type.LATIN1)
        ? TagString.Type.LATIN1 : TagString.Type.UTF16;
      var people = new ArrayList<String>();
      if (tmcl != null) {
        addPairs(people, tmcl.fieldList());
      }
      if (tipl != null) {
        addPairs(people, tipl.fieldList());
      }
      var combined = new TextIdentificationFrame(new ByteVector("IPLS"), encoding);
      combined.setText(people);
      selected.add(combined);
    }
    if (tcon != null) {
      var genres = tcon.fieldList();
      var combined = new StringBuilder();
      String text = "";
      for (String genre : genres) {
        var number = new TagString(genre).extractInt();
        if ((number.ok() && number.value() >= 0 && number.value() <= 255) || genre.equals("RX") || genre.equals("CR")) {
          combined.append('(').append(genre).append(')');
        } else if (genres.size() > 1 && Genres.genreIndex(genre) != 255) {
          combined.append('(').append(Genres.genreIndex(genre)).append(')');
        } else if (text.isEmpty()) {
          text = genre;
        }
      }
      if (!text.isEmpty()) {
        combined.append(text);
      }
      addTemporary("TCON", combined.toString(), downgraded(tcon), selected);
    }
  }

  private static void addPairs(List<String> target, List<String> values) {
    for (int i = 0; i + 1 < values.size(); i += 2) {
      target.add(values.get(i));
      target.add(values.get(i + 1));
    }
  }

  private static TagString.Type downgraded(TextIdentificationFrame text) {
    return text.textEncoding() == TagString.Type.LATIN1 ? TagString.Type.LATIN1 : TagString.Type.UTF16;
  }

  private static void addTemporary(String id, String text, TagString.Type encoding, List<Frame> selected) {
    var frame = new TextIdentificationFrame(new ByteVector(id), encoding);
    frame.setText(text);
    selected.add(frame);
  }
}
