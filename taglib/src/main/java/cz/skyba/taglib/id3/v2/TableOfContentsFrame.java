// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.internal.ByteVectorOutput;
import cz.skyba.taglib.toolkit.ByteVector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static java.lang.System.Logger.Level.DEBUG;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

/// A mutable ID3v2 frame for chapter navigation (CTOC).
/// Editing a frame does not write a file; use the owning file's save operation to persist changes.
public class TableOfContentsFrame extends Frame {
  private static final System.Logger LOG = System.getLogger(TableOfContentsFrame.class.getName());

  private final cz.skyba.taglib.id3.v2.Header tagHeader;
  private final FrameCollection embedded = new FrameCollection();
  private ByteVector elementID = new ByteVector();
  private List<ByteVector> children = new ArrayList<>();
  private boolean topLevel, ordered;

  /// Decodes CTOC child identifiers and embedded frames from serialized frame bytes, including their frame header.
  /// The tag header supplies the version used for embedded-frame parsing.
  ///
  /// @param data      serialized bytes including the frame header
  /// @param tagHeader the enclosing tag header
  public TableOfContentsFrame(cz.skyba.taglib.id3.v2.Header tagHeader, ByteVector data) {
    super(data);
    this.tagHeader = Objects.requireNonNull(tagHeader);
    parseContents(fieldData(data));
  }

  /// Creates a frame containing CTOC child identifiers and embedded frames.
  ///
  /// @param elementID the chapter or table identifier to copy
  public TableOfContentsFrame(ByteVector elementID) {
    this(elementID, List.of());
  }

  /// Creates a frame containing CTOC child identifiers and embedded frames.
  ///
  /// @param elementID the chapter or table identifier to copy
  /// @param children  child identifiers to copy
  public TableOfContentsFrame(ByteVector elementID, List<ByteVector> children) {
    this(elementID, children, List.of());
  }

  /// Creates a frame containing CTOC child identifiers and embedded frames.
  ///
  /// @param elementID      the chapter or table identifier to copy
  /// @param children       child identifiers to copy
  /// @param embeddedFrames live embedded frames to retain
  public TableOfContentsFrame(ByteVector elementID, List<ByteVector> children, List<? extends Frame> embeddedFrames) {
    super(new ByteVector("CTOC"));
    tagHeader = null;
    setElementID(elementID);
    setChildElements(children);
    for (Frame frame : embeddedFrames) {
      addEmbeddedFrame(frame);
    }
  }

  TableOfContentsFrame(cz.skyba.taglib.id3.v2.Header tagHeader, ByteVector data, Frame.Header header) {
    super(header);
    this.tagHeader = Objects.requireNonNull(tagHeader);
    parseContents(fieldData(data));
  }

  /// Returns a copy of the element identifier bytes.
  ///
  /// @return the element identifier
  public final ByteVector elementID() {
    return new ByteVector(elementID);
  }

  public final boolean isTopLevel() {
    return topLevel;
  }

  public final boolean isOrdered() {
    return ordered;
  }

  public final long entryCount() {
    return children.size();
  }

  /// Returns independent copies of the child identifier bytes.
  ///
  /// @return an independent mutable list of copied child identifiers
  public final List<ByteVector> childElements() {
    return children.stream().map(ByteVector::new).collect(Collectors.toCollection(ArrayList::new));
  }

  /// Copies the identifier and removes one trailing NUL byte when present.
  public final void setElementID(ByteVector id) {
    elementID = new ByteVector(id);
  }

  public final void setIsTopLevel(boolean value) {
    topLevel = value;
  }

  public final void setIsOrdered(boolean value) {
    ordered = value;
  }

  /// Replaces the child identifiers, copying the list and every byte vector.
  ///
  /// @param values the identifiers to copy; neither the list nor its elements may be null
  public final void setChildElements(List<ByteVector> values) {
    children = values.stream().map(ByteVector::new).collect(Collectors.toCollection(ArrayList::new));
  }

  /// Appends a copy of a child identifier.
  ///
  /// @param id the identifier to add
  public final void addChildElement(ByteVector id) {
    children.add(new ByteVector(id));
  }

  /// Removes matching child identifiers from the table of contents.
  ///
  /// @param id the identifier to remove
  public final void removeChildElement(ByteVector id) {
    int index = children.indexOf(id);
    // Native cE + ByteVector("\0") appends an empty C-string; it does not add a NUL.
    if (index >= 0) {
      children.remove(index);
    }
  }

  /// Returns a read-only live map grouping embedded frames by identifier.
  /// An absent lookup does not create a group.
  ///
  /// @return the embedded-frame groups
  public final Map<ByteVector, List<Frame>> embeddedFrameListMap() {
    return embedded.map();
  }

  /// Returns a read-only live list of embedded frame references.
  /// Frames remain mutable; structural changes use the frame-management methods.
  ///
  /// @return live embedded frames
  public final List<Frame> embeddedFrameList() {
    return embedded.list();
  }

  /// Returns a read-only live list for an existing ID, or an empty list without creating a bucket.
  public final List<Frame> embeddedFrameList(ByteVector id) {
    return embedded.bucket(id);
  }

  /// Retains an embedded frame reference without copying it. The change remains in memory.
  ///
  /// @param frame the live frame to add
  public final void addEmbeddedFrame(Frame frame) {
    embedded.add(frame);
  }

  /// Removes the supplied embedded frame reference without invalidating it for the caller.
  ///
  /// @param frame the embedded reference to remove
  public final void removeEmbeddedFrame(Frame frame) {
    embedded.remove(frame, true);
  }

  /// Removes all embedded frames with the supplied identifier.
  ///
  /// @param id the identifier bytes to match
  public final void removeEmbeddedFrames(ByteVector id) {
    embedded.removeAll(id, true);
  }

  @Override
  public String toString() {
    String result = Id3Text.fixedLatin1(elementID) + ": top level: " + topLevel + ", ordered: " + ordered;
    if (!children.isEmpty()) {
      result += ", chapters: [ " + Id3Text.fixedLatin1(ByteVector.join(children, new ByteVector(", "))) + " ]";
    }
    if (!embedded.list().isEmpty()) {
      result += ", sub-frames: [ "
        + String.join(", ", embedded.list().stream().map(f -> Id3Text.fixedLatin1(f.frameID())).toList()) + " ]";
    }
    return result;
  }

  @Override
  public PropertyMap asProperties() {
    return PropertyMap.builder().addUnsupportedData(Id3Text.fixedLatin1(frameID()) + "/" + Id3Text.fixedLatin1(elementID)).build();
  }

  /// Returns the first live matching frame from a tag.
  ///
  /// @param tag the tag to search
  /// @param id  the element identifier bytes to match
  /// @return the matching frame, or `null` if absent
  public static TableOfContentsFrame findByElementID(Tag tag, ByteVector id) {
    for (Frame frame : tag.frameList(new ByteVector("CTOC"))) {
      if (frame instanceof TableOfContentsFrame toc && toc.elementID().equals(id)) {
        return toc;
      }
    }
    return null;
  }

  /// Returns the first live top-level table-of-contents frame from a tag.
  ///
  /// @param tag the tag to search
  /// @return the matching frame, or `null` if absent
  public static TableOfContentsFrame findTopLevel(Tag tag) {
    for (Frame frame : tag.frameList(new ByteVector("CTOC"))) {
      if (frame instanceof TableOfContentsFrame toc && toc.isTopLevel()) {
        return toc;
      }
    }
    return null;
  }

  @Override
  protected void parseFields(ByteVector data) {
    parseContents(data);
  }

  private void parseContents(ByteVector data) {
    if (data.size() < 6) {
      LOG.log(DEBUG, "A table-of-contents frame must contain at least 6 bytes.");
      return;
    }
    StringField field = stringField(data, ISO_8859_1, 0);
    elementID = ByteVector.fromText(field.value(), ISO_8859_1);
    int position = field.nextPosition();
    topLevel = (data.at(position) & 2) != 0;
    ordered = (data.at(position++) & 1) != 0;
    int count = data.at(position++) & 255;
    for (int i = 0; i < count; i++) {
      field = stringField(data, ISO_8859_1, position);
      children.add(ByteVector.fromText(field.value(), ISO_8859_1));
      position = field.nextPosition();
    }
    embedded.parseEmbedded(data, position, tagHeader, this);
  }

  @Override
  protected ByteVector renderFields() {
    var data = new ByteVectorOutput().append(new ByteVector(elementID)).append((byte) 0).append((byte) ((topLevel ? 2 : 0) | (ordered ? 1 : 0)))
      .append((byte) entryCount());
    for (ByteVector id : children) {
      data.append(id).append((byte) 0);
    }
    for (Frame child : embedded.list()) {
      child.header().setVersion(header().version());
      data.append(child.render());
    }
    return data.toByteVector();
  }
}
