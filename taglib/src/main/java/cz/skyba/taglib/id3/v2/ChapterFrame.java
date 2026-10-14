// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.internal.ByteVectorOutput;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.Debug;
import cz.skyba.taglib.toolkit.TagString;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A mutable ID3v2 frame containing CHAP timing, byte offsets and embedded frames.
 * Editing a frame does not write a file; use the owning file's save operation to persist changes.
 */
public class ChapterFrame extends Frame {
  private final cz.skyba.taglib.id3.v2.Header tagHeader;
  private final FrameCollection embedded = new FrameCollection();
  private ByteVector elementID = new ByteVector();
  private long startTime, endTime, startOffset, endOffset;

  /**
   * Decodes CHAP timing, byte offsets and embedded frames from serialized frame bytes, including their frame header.
   * The tag header supplies the version used for embedded-frame parsing.
   *
   * @param data      serialized bytes including the frame header
   * @param tagHeader the enclosing tag header
   */
  public ChapterFrame(cz.skyba.taglib.id3.v2.Header tagHeader, ByteVector data) {
    super(data);
    this.tagHeader = Objects.requireNonNull(tagHeader);
    parseChapter(fieldData(data));
  }

  /**
   * Creates a frame containing CHAP timing, byte offsets and embedded frames.
   *
   * @param elementID   the chapter or table identifier to copy
   * @param startTime   the starting time in milliseconds
   * @param endTime     the ending time in milliseconds
   * @param startOffset the starting byte offset in the audio file
   * @param endOffset   the ending byte offset in the audio file
   */
  public ChapterFrame(ByteVector elementID, long startTime, long endTime, long startOffset, long endOffset) {
    this(elementID, startTime, endTime, startOffset, endOffset, List.of());
  }

  /**
   * Creates a frame containing CHAP timing, byte offsets and embedded frames.
   *
   * @param elementID      the chapter or table identifier to copy
   * @param embeddedFrames live embedded frames to retain
   * @param startTime      the starting time in milliseconds
   * @param endTime        the ending time in milliseconds
   * @param startOffset    the starting byte offset in the audio file
   * @param endOffset      the ending byte offset in the audio file
   */
  public ChapterFrame(
    ByteVector elementID, long startTime, long endTime, long startOffset, long endOffset,
    List<? extends Frame> embeddedFrames
  ) {
    super(new ByteVector("CHAP"));
    tagHeader = null;
    setElementID(elementID);
    setStartTime(startTime);
    setEndTime(endTime);
    setStartOffset(startOffset);
    setEndOffset(endOffset);
    for (Frame frame : embeddedFrames) {
      addEmbeddedFrame(frame);
    }
  }

  ChapterFrame(cz.skyba.taglib.id3.v2.Header tagHeader, ByteVector data, Frame.Header header) {
    super(header);
    this.tagHeader = Objects.requireNonNull(tagHeader);
    parseChapter(fieldData(data));
  }

  /**
   * Returns a copy of the element identifier bytes.
   *
   * @return the element identifier
   */
  public final ByteVector elementID() {
    return new ByteVector(elementID);
  }

  public final long startTime() {
    return startTime;
  }

  public final long endTime() {
    return endTime;
  }

  public final long startOffset() {
    return startOffset;
  }

  public final long endOffset() {
    return endOffset;
  }

  /**
   * Copies the identifier and removes one trailing NUL byte when present.
   */
  public final void setElementID(ByteVector id) {
    elementID = new ByteVector(id);
    if (elementID.endsWith(new ByteVector((byte) 0))) {
      elementID = elementID.mid(0, elementID.size() - 1);
    }
  }

  public final void setStartTime(long value) {
    startTime = value & 0xffff_ffffL;
  }

  public final void setEndTime(long value) {
    endTime = value & 0xffff_ffffL;
  }

  public final void setStartOffset(long value) {
    startOffset = value & 0xffff_ffffL;
  }

  public final void setEndOffset(long value) {
    endOffset = value & 0xffff_ffffL;
  }

  /**
   * Returns a read-only live map grouping embedded frames by identifier.
   * An absent lookup does not create a group.
   *
   * @return the embedded-frame groups
   */
  public final Map<ByteVector, List<Frame>> embeddedFrameListMap() {
    return embedded.map();
  }

  /**
   * Returns a read-only live list of embedded frame references.
   * Frames remain mutable; structural changes use the frame-management methods.
   *
   * @return live embedded frames
   */
  public final List<Frame> embeddedFrameList() {
    return embedded.list();
  }

  /**
   * Returns a read-only live list for an existing ID, or an empty list without creating a bucket.
   */
  public final List<Frame> embeddedFrameList(ByteVector id) {
    return embedded.bucket(id);
  }

  /**
   * Retains an embedded frame reference without copying it. The change remains in memory.
   *
   * @param frame the live frame to add
   */
  public final void addEmbeddedFrame(Frame frame) {
    embedded.add(frame);
  }

  /**
   * Removes the supplied embedded frame reference without invalidating it for the caller.
   *
   * @param frame the embedded reference to remove
   */
  public final void removeEmbeddedFrame(Frame frame) {
    embedded.remove(frame, false);
  }

  /**
   * Removes all embedded frames with the supplied identifier.
   *
   * @param id the identifier bytes to match
   */
  public final void removeEmbeddedFrames(ByteVector id) {
    embedded.removeAll(id, false);
  }

  @Override
  public String toString() {
    String result = Id3Text.fixedLatin1(elementID) + ": start time: " + (int) startTime + ", end time: " + (int) endTime;
    if (startOffset != 0xffff_ffffL) {
      result += ", start offset: " + (int) startOffset;
    }
    if (endOffset != 0xffff_ffffL) {
      result += ", end offset: " + (int) endOffset;
    }
    if (!embedded.list().isEmpty()) {
      result += ", sub-frames: [ " + embeddedIDs() + " ]";
    }
    return result;
  }

  private String embeddedIDs() {
    return String.join(", ", embedded.list().stream().map(f -> Id3Text.fixedLatin1(f.frameID())).toList());
  }

  @Override
  public PropertyMap asProperties() {
    return PropertyMap.builder().addUnsupportedData(Id3Text.fixedLatin1(frameID()) + "/" + Id3Text.fixedLatin1(elementID)).build();
  }

  /**
   * Returns the first live matching frame from a tag.
   *
   * @param tag the tag to search
   * @param id  the element identifier bytes to match
   * @return the matching frame, or {@code null} if absent
   */
  public static ChapterFrame findByElementID(Tag tag, ByteVector id) {
    for (Frame frame : tag.frameList(new ByteVector("CHAP"))) {
      if (frame instanceof ChapterFrame chapter && chapter.elementID().equals(id)) {
        return chapter;
      }
    }
    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void parseFields(ByteVector data) {
    parseChapter(data);
  }

  private void parseChapter(ByteVector data) {
    if (data.size() < 18) {
      Debug.debug(new TagString("A CHAP frame must contain at least 18 bytes (1 byte element ID terminated by null and 4x4 bytes for start and end time and offset)."));
      return;
    }
    StringField field = stringField(data, TagString.Type.LATIN1, 0);
    elementID = Id3Text.encode(field.value(), TagString.Type.LATIN1);
    int pos = field.nextPosition();
    startTime = data.toUnsignedInt(pos, true);
    pos += 4;
    endTime = data.toUnsignedInt(pos, true);
    pos += 4;
    startOffset = data.toUnsignedInt(pos, true);
    pos += 4;
    endOffset = data.toUnsignedInt(pos, true);
    pos += 4;
    embedded.parseEmbedded(data, pos, tagHeader, this);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected ByteVector renderFields() {
    var data = new ByteVectorOutput().append(new ByteVector(elementID)).append((byte) 0).append(ByteVector.fromUnsignedInt(startTime))
      .append(ByteVector.fromUnsignedInt(endTime)).append(ByteVector.fromUnsignedInt(startOffset)).append(ByteVector.fromUnsignedInt(endOffset));
    for (Frame child : embedded.list()) {
      child.header().setVersion(header().version());
      data.append(child.render());
    }
    return data.toByteVector();
  }
}
