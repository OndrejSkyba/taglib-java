// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.flac;

import cz.skyba.taglib.*;
import cz.skyba.taglib.id3.v2.FrameFactory;
import cz.skyba.taglib.internal.*;
import cz.skyba.taglib.toolkit.*;

import java.nio.file.Path;
import java.util.*;

import static java.lang.System.Logger.Level.DEBUG;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

/// An editable FLAC file with live metadata and format-specific audio properties.
/// Path construction owns storage; stream construction borrows it. Metadata edits require
/// [#save()] unless an operation explicitly writes immediately. Closing does not save.
public class FlacFile extends FileBase {
  private static final System.Logger LOG = System.getLogger(FlacFile.class.getName());

  /// Tag families supported by this format's removal operations.
  public enum TagType {XIPH_COMMENT, ID3V1, ID3V2}

  private final IOStream source;
  private final boolean owned;
  private final FrameFactory factory;
  private final int limit;
  private final TagUnion tags = new TagUnion();
  private final List<MetadataBlock> blocks = new ArrayList<>();
  private Properties details;
  private long flacStart, streamStart, id3v2Location = -1, id3v1Location = -1, id3v2Size;
  private ByteVector commentData = new ByteVector(), bext = new ByteVector();
  private String xml = "";
  private boolean hasXml, hasBext;

  /// Opens a FLAC file and owns the file handle. Uses default read options.
  ///
  /// @param path the file to open
  /// @throws java.io.UncheckedIOException if storage access fails
  /// @throws TagLibException              if parsing rejects the format or its metadata
  public FlacFile(Path path) {
    this(path, ReadOptions.DEFAULT);
  }

  /// Opens a FLAC file and owns the file handle.
  ///
  /// @param path    the file to open
  /// @param options audio property selection and metadata allocation limit
  /// @throws java.io.UncheckedIOException if storage access fails
  /// @throws TagLibException              if parsing rejects the format or its metadata
  public FlacFile(Path path, ReadOptions options) {
    this(path, options, null);
  }

  /// Opens a FLAC file and owns the file handle.
  /// The frame factory controls ID3v2 parsing; null selects the shared default factory.
  ///
  /// @param path    the file to open
  /// @param options audio property selection and metadata allocation limit
  /// @param factory the frame decoder factory, or `null` for the default
  /// @throws java.io.UncheckedIOException if storage access fails
  /// @throws TagLibException              if parsing rejects the format or its metadata
  public FlacFile(Path path, ReadOptions options, FrameFactory factory) {
    Objects.requireNonNull(options);
    this(new FileStream(path), true, options, factory);
  }

  /// Opens a FLAC file over a borrowed stream that remains open when this file closes. Uses default read options.
  ///
  /// @param stream the open, seekable audio stream
  /// @throws java.io.UncheckedIOException if storage access fails
  /// @throws TagLibException              if parsing rejects the format or its metadata
  /// @throws IllegalStateException        if the supplied stream is closed
  public FlacFile(IOStream stream) {
    this(stream, ReadOptions.DEFAULT);
  }

  /// Opens a FLAC file over a borrowed stream that remains open when this file closes.
  ///
  /// @param stream  the open, seekable audio stream
  /// @param options audio property selection and metadata allocation limit
  /// @throws java.io.UncheckedIOException if storage access fails
  /// @throws TagLibException              if parsing rejects the format or its metadata
  /// @throws IllegalStateException        if the supplied stream is closed
  public FlacFile(IOStream stream, ReadOptions options) {
    this(stream, false, options, null);
  }

  /// Opens a FLAC file over a borrowed stream that remains open when this file closes.
  /// The frame factory controls ID3v2 parsing; null selects the shared default factory.
  ///
  /// @param stream  the open, seekable audio stream
  /// @param options audio property selection and metadata allocation limit
  /// @param factory the frame decoder factory, or `null` for the default
  /// @throws java.io.UncheckedIOException if storage access fails
  /// @throws TagLibException              if parsing rejects the format or its metadata
  /// @throws IllegalStateException        if the supplied stream is closed
  public FlacFile(IOStream stream, ReadOptions options, FrameFactory factory) {
    this(stream, false, options, factory);
  }

  private FlacFile(IOStream stream, boolean owned, ReadOptions options, FrameFactory factory) {
    super(stream);
    source = stream;
    this.owned = owned;
    limit = options.maxMetadataBytes();
    this.factory = factory == null ? FrameFactory.instance() : factory;
    try {
      read(options.readAudioProperties());
    } catch (RuntimeException | Error failure) {
      if (owned) {
        FailureSupport.closeOnFailure(stream, failure);
      }
      throw failure;
    }
  }

  @Override
  public ByteVector readBlock(long count) {
    if (count > limit && count > bufferSize()) {
      throw new MetadataLimitException("FLAC metadata exceeds configured read limit");
    }
    return super.readBlock(count);
  }

  private void read(boolean audio) {
    id3v2Location = TagUtils.findID3v2(this);
    if (id3v2Location >= 0) {
      tags.set(1, new cz.skyba.taglib.id3.v2.Tag(this, id3v2Location, factory));
      id3v2Size = id3v2Tag().header().completeTagSize();
    }
    id3v1Location = TagUtils.findID3v1(this);
    if (id3v1Location >= 0) {
      tags.set(2, new cz.skyba.taglib.id3.v1.Tag(this, id3v1Location));
    }
    scan();
    tags.set(0, commentData.isEmpty() ? new cz.skyba.taglib.ogg.XiphComment() : new cz.skyba.taglib.ogg.XiphComment(commentData));
    if (audio) {
      details = new Properties(blocks.getFirst().render(), (id3v1Location >= 0 ? id3v1Location : length()) - streamStart);
    }
  }

  private InvalidAudioFileException invalid(String reason) {
    LOG.log(DEBUG, reason);
    return new InvalidAudioFileException("FLAC " + reason + ": " + name());
  }

  private void scan() {
    long offset = find(new ByteVector("fLaC"), id3v2Location >= 0 ? Math.addExact(id3v2Location, id3v2Size) : 0);
    if (offset < 0) {
      throw invalid("FLAC stream not found");
    }
    flacStart = offset = Math.addExact(offset, 4);
    long retained = 0;
    for (int count = 0; ; count++) {
      if (count >= 50_000) {
        throw invalid("Maximum metadata block count exceeded");
      }
      seek(offset);
      var header = readBlock(4);
      if (header.size() != 4) {
        throw invalid("Failed to read a block header");
      }
      int code = header.at(0) & 127;
      long size = header.toUnsignedInt(1, 3, true);
      if (blocks.isEmpty() && code != 0) {
        throw invalid("First block should be the stream_info metadata");
      }
      if (size == 0 && code != 1 && code != 3) {
        throw invalid("Zero-sized metadata block found");
      }
      // Bound total retained payload, not merely an individual allocation.
      retained = Math.addExact(retained, size);
      if (retained > limit) {
        throw new MetadataLimitException("FLAC aggregate metadata exceeds configured read limit");
      }
      var data = readBlock(size);
      if (data.size() != size) {
        throw invalid("Failed to read a metadata block");
      }
      if (code == 4) {
        if (commentData.isEmpty()) {
          commentData = data;
          blocks.add(new UnknownMetadataBlock(code, data.data()));
        } else {
          LOG.log(DEBUG, "Discarding an additional Vorbis comment block.");
        }
      } else if (code == 6) {
        var picture = new Picture();
        if (picture.parse(data.data())) {
          blocks.add(picture);
        } else {
          LOG.log(DEBUG, "Discarding an invalid picture block.");
        }
      } else if (code != 1) {
        var inner = recognized(data);
        if (code == 2 && inner != null) {
          int skip = data.startsWith(new ByteVector("riff")) ? 12 : 4;
          var payload = skip == 12 ? data.mid(skip, (int) Math.min(data.toUnsignedInt(8, false), data.size() - skip)) : data.mid(skip);
          if (inner.equals("iXML") && !hasXml) {
            xml = payload.decodeTerminatedText(UTF_8);
            hasXml = true;
          } else if (inner.equals("bext") && !hasBext) {
            bext = payload;
            hasBext = true;
          } else {
            LOG.log(DEBUG, () ->
              "Discarding an additional " + (inner.equals("iXML") ? "iXML" : "BEXT")
                + " block.");
          }
        } else {
          blocks.add(new UnknownMetadataBlock(code, data.data()));
        }
      }
      offset = Math.addExact(offset, size + 4);
      if ((header.at(0) & 128) != 0) {
        break;
      }
    }
    streamStart = offset;
  }

  private static String recognized(ByteVector data) {
    if (data.size() < 4) {
      return null;
    }
    var id = data.mid(0, 4);
    if (id.equals(new ByteVector("riff")) && data.size() >= 12) {
      id = data.mid(4, 4);
    }
    String value = id.decodeTerminatedText(ISO_8859_1);
    return value.equals("iXML") || value.equals("bext") ? value : null;
  }

  @Override
  public TagBase tag() {
    return tags;
  }

  @Override
  public PropertyMap properties() {
    return tags.properties();
  }

  @Override
  public void removeUnsupportedProperties(List<String> values) {
    tags.removeUnsupportedProperties(values);
  }

  @Override
  public PropertyUpdateResult replaceProperties(PropertyMap values) {
    return getOrCreateXiphComment().replaceProperties(values);
  }

  @Override
  public AudioProperties audioProperties() {
    return details == null ? null : details.common();
  }

  /// @return the detailed properties, or `null` if properties were not read or are unavailable
  public Properties detailedProperties() {
    return details;
  }

  /// Returns the live ID3v2 tag without creating one.
  ///
  /// @return the live tag object, or `null` if no object exists
  public final cz.skyba.taglib.id3.v2.Tag id3v2Tag() {
    return (cz.skyba.taglib.id3.v2.Tag) tags.tag(1);
  }

  /// Returns the live ID3v2 tag, creating an empty tag in memory if absent. Creation does not write storage.
  ///
  /// @return the live tag object
  public final cz.skyba.taglib.id3.v2.Tag getOrCreateId3v2Tag() {
    if (tags.tag(1) == null) {
      tags.set(1, new cz.skyba.taglib.id3.v2.Tag(null, 0, factory));
    }
    return (cz.skyba.taglib.id3.v2.Tag) tags.tag(1);
  }

  /// Returns the live ID3v1 tag without creating one.
  ///
  /// @return the live tag object, or `null` if no object exists
  public final cz.skyba.taglib.id3.v1.Tag id3v1Tag() {
    return (cz.skyba.taglib.id3.v1.Tag) tags.tag(2);
  }

  /// Returns the live ID3v1 tag, creating an empty tag in memory if absent. Creation does not write storage.
  ///
  /// @return the live tag object
  public final cz.skyba.taglib.id3.v1.Tag getOrCreateId3v1Tag() {
    if (tags.tag(2) == null) {
      tags.set(2, new cz.skyba.taglib.id3.v1.Tag());
    }
    return (cz.skyba.taglib.id3.v1.Tag) tags.tag(2);
  }

  /// Returns the live Vorbis comment tag without creating one.
  ///
  /// @return the live tag object, or `null` if no object exists
  public final cz.skyba.taglib.ogg.XiphComment xiphComment() {
    return (cz.skyba.taglib.ogg.XiphComment) tags.tag(0);
  }

  /// Returns the live Vorbis comment tag, creating an empty tag in memory if absent. Creation does not write storage.
  ///
  /// @return the live tag object
  public final cz.skyba.taglib.ogg.XiphComment getOrCreateXiphComment() {
    if (tags.tag(0) == null) {
      tags.set(0, new cz.skyba.taglib.ogg.XiphComment());
    }
    return (cz.skyba.taglib.ogg.XiphComment) tags.tag(0);
  }

  public final boolean hasXiphComment() {
    return !commentData.isEmpty();
  }

  public final boolean hasId3v1Tag() {
    return id3v1Location >= 0;
  }

  public final boolean hasId3v2Tag() {
    return id3v2Location >= 0;
  }

  public final boolean hasiXMLData() {
    return hasXml;
  }

  public final boolean hasBEXTData() {
    return hasBext;
  }

  public final String iXMLData() {
    return xml;
  }

  /// Stages replacement of the iXML text.
  /// An empty value requests removal when the file is saved.
  ///
  /// @param value the replacement text
  public final void setiXMLData(String value) {
    xml = Objects.requireNonNull(value);
  }

  /// Returns an independent copy of the stored Broadcast Wave extension bytes.
  ///
  /// @return copied extension bytes
  public final byte[] BEXTData() {
    return bext.toByteArray();
  }

  /// Stages replacement of the Broadcast Wave extension bytes, copying the input.
  /// An empty value requests removal when the file is saved.
  ///
  /// @param value the replacement bytes
  public final void setBEXTData(byte[] value) {
    bext = new ByteVector(value);
  }

  /// Stages replacement of the Broadcast Wave extension bytes, copying the input.
  /// An empty value requests removal when the file is saved.
  ///
  /// @param value the replacement bytes
  public final void setBEXTData(ByteVector value) {
    bext = new ByteVector(value);
  }

  /// Returns an immutable list snapshot containing live FLAC picture objects.
  /// Editing a picture object stages changes for the next save.
  ///
  /// @return the live picture references
  public final List<Picture> pictureList() {
    return blocks.stream().filter(Picture.class::isInstance).map(Picture.class::cast).toList();
  }

  /// Appends a live FLAC picture object without copying it. The change is staged until save().
  ///
  /// @param picture the object to retain
  public final void addPicture(Picture picture) {
    blocks.add(Objects.requireNonNull(picture));
  }

  /// Stages removal of the first picture object identical to the supplied reference.
  /// An equal but different object is not removed.
  ///
  /// @param picture the retained object to remove
  public final void removePicture(Picture picture) {
    for (int i = 0; i < blocks.size(); i++) {
      if (blocks.get(i) == picture) {
        blocks.remove(i);
        break;
      }
    }
  }

  /// Stages removal of every FLAC picture block; other metadata is retained.
  public final void removePictures() {
    blocks.removeIf(Picture.class::isInstance);
  }

  /// Returns immutable, format-independent artwork snapshots with copied image bytes.
  ///
  /// @return artwork snapshots
  public final List<cz.skyba.taglib.Picture> pictures() {
    return pictureList().stream().map(Picture::snapshot).toList();
  }

  /// Copies a format-independent artwork snapshot into a new FLAC picture block.
  /// The addition is staged until save().
  ///
  /// @param value artwork to copy
  public final void addPicture(cz.skyba.taglib.Picture value) {
    var p = new Picture();
    p.parse(cz.skyba.taglib.internal.xiph.XiphComment.renderPicture(value));
    addPicture(p);
  }

  /// Stages complete artwork replacement with copies of the supplied snapshots.
  /// An empty list removes all pictures on save.
  ///
  /// @param values replacement artwork
  public final void setPictures(List<cz.skyba.taglib.Picture> values) {
    removePictures();
    values.forEach(this::addPicture);
  }

  @Override
  public boolean supportsPictureEditing() {
    return true;
  }

  @Override
  public List<String> complexPropertyKeys() {
    var keys = new ArrayList<>(super.complexPropertyKeys());
    if (!pictureList().isEmpty() && !keys.contains("PICTURE")) {
      keys.add("PICTURE");
    }
    return List.copyOf(keys);
  }

  @Override
  public List<Map<String, Variant>> complexProperties(String key) {
    return Ascii.upper(key).equals("PICTURE")
      ? pictureList().stream().map(PictureProperties::export).toList()
      : super.complexProperties(key);
  }

  @Override
  public boolean setComplexProperties(String key, List<Map<String, Variant>> values) {
    if (!Ascii.upper(key).equals("PICTURE")) {
      return super.setComplexProperties(key, values);
    }
    removePictures();
    values.forEach(value -> addPicture(PictureProperties.create(value)));
    return true;
  }

  /// Stages removal of all supported tag types; storage changes only on [#save()].
  public final void removeTags() {
    removeTags(Set.of(TagType.values()));
  }

  /// Stages removal of selected tag types from cached metadata; storage changes only
  /// on [#save()].
  public final void removeTags(Set<TagType> types) {
    types = Set.copyOf(types);
    if (types.contains(TagType.ID3V1)) {
      tags.set(2, null);
    }
    if (types.contains(TagType.ID3V2)) {
      tags.set(1, null);
    }
    if (types.contains(TagType.XIPH_COMMENT)) {
      var comment = xiphComment();
      if (comment != null) {
        comment.removeAllFields();
        xiphComment().removeAllPictures();
      }
    }
  }

  /// Checks for a recognizable FLAC header without validating the complete file.
  /// The input cursor is restored after inspection.
  ///
  /// @param stream the stream to inspect
  /// @return whether a format header is recognized
  /// @throws java.io.UncheckedIOException if storage access fails
  public static boolean isSupported(IOStream stream) {
    return TagUtils.readHeader(stream, 1024, true).find(new ByteVector("fLaC")) >= 0;
  }

  private ByteVector application(String id, ByteVector data) {
    return new ByteVector("riff").append(new ByteVector(id)).append(ByteVector.fromUnsignedInt(data.size(), false)).append(data);
  }

  @Override
  public void save() {
    requireWritable();
    // Validate cached physical spans before state changes or callbacks that render children.
    if (flacStart < 4 || streamStart < flacStart || streamStart > length()
      || id3v2Location >= 0 && (id3v2Location > length() || id3v2Size > length() - id3v2Location)
      || id3v1Location >= 0 && id3v1Location > length() - 128) {
      throw new TagLibException("FLAC cached metadata spans are outside the stream: " + name());
    }
    if (!hasXiphComment()) {
      TagBase.copyFields(tags, getOrCreateXiphComment(), false);
    }
    commentData = xiphComment().render(false);
    blocks.removeIf(block -> block.code() == 2 && recognized(new ByteVector(block.render())) != null);
    if (!xml.isEmpty()) {
      blocks.add(new UnknownMetadataBlock(2, application("iXML", ByteVector.fromText(xml, UTF_8)).data()));
    }
    hasXml = !xml.isEmpty();
    if (!bext.isEmpty()) {
      blocks.add(new UnknownMetadataBlock(2, application("bext", bext).data()));
    }
    hasBext = !bext.isEmpty();
    MetadataBlock comment = new UnknownMetadataBlock(4, commentData.data());
    for (int i = 0; i < blocks.size(); ) {
      if (blocks.get(i).code() == 4) {
        blocks.remove(i);
        continue;
      }
      if (comment != null && blocks.get(i).code() == 6) {
        blocks.add(i++, comment);
        comment = null;
      }
      i++;
    }
    if (comment != null) {
      blocks.add(comment);
    }
    var output = new ByteVectorOutput();
    for (int i = 0; i < blocks.size(); ) {
      byte[] payload = blocks.get(i).render();
      if (payload.length > 0xffffff) {
        LOG.log(DEBUG, "Removing a metadata block that exceeds the 24-bit size limit.");
        blocks.remove(i);
        continue;
      }
      writeMetadataHeader(output, blocks.get(i).code(), payload.length);
      output.append(payload);
      i++;
    }
    long original = streamStart - flacStart, padding = original - output.size() - 4;
    if (padding <= 0 || padding > Math.clamp(length() / 100, 4096L, 1_048_576L)) {
      padding = 4096;
    }
    writeMetadataHeader(output, 129, padding);
    int paddedLength = Math.toIntExact(output.size() + padding);
    var data = output.toByteVector(paddedLength - output.size());
    insert(data, flacStart, original);
    long delta = data.size() - original;
    streamStart += delta;
    if (id3v1Location >= 0) {
      id3v1Location += delta;
    }
    var modernTag = id3v2Tag();
    if (modernTag != null && !modernTag.isEmpty()) {
      if (id3v2Location < 0) {
        id3v2Location = 0;
      }
      data = id3v2Tag().render();
      insert(data, id3v2Location, id3v2Size);
      delta = data.size() - id3v2Size;
      flacStart += delta;
      streamStart += delta;
      if (id3v1Location >= 0) {
        id3v1Location += delta;
      }
      id3v2Size = data.size();
    } else if (id3v2Location >= 0) {
      removeBlock(id3v2Location, id3v2Size);
      flacStart -= id3v2Size;
      streamStart -= id3v2Size;
      if (id3v1Location >= 0) {
        id3v1Location -= id3v2Size;
      }
      id3v2Location = -1;
      id3v2Size = 0;
    }
    var legacyTag = id3v1Tag();
    if (legacyTag != null && !legacyTag.isEmpty()) {
      if (id3v1Location >= 0) {
        seek(id3v1Location);
      } else {
        seek(0, IOStream.Position.END);
        id3v1Location = tell();
      }
      writeBlock(id3v1Tag().render());
    } else if (id3v1Location >= 0) {
      truncate(id3v1Location);
      id3v1Location = -1;
    }
  }

  private static void writeMetadataHeader(ByteVectorOutput output, int code, long length) {
    var header = ByteVector.fromUnsignedInt(length, true);
    header.set(0, (byte) code);
    output.append(header);
  }

  @Override
  public void close() {
    if (owned) {
      source.close();
    }
  }
}
