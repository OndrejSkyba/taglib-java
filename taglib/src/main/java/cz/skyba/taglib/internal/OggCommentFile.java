// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal;

import cz.skyba.taglib.*;
import cz.skyba.taglib.ogg.OggFile;
import cz.skyba.taglib.ogg.XiphComment;
import cz.skyba.taglib.toolkit.*;

import java.nio.file.Path;
import java.util.Objects;

import static java.lang.System.Logger.Level.DEBUG;

/// Ogg codec metadata with a live comment view and staged packet persistence.
public abstract class OggCommentFile extends OggFile {
  private static final System.Logger LOG = System.getLogger(OggCommentFile.class.getName());

  private static final long FLAC_PACKET_MAX = (1L << 24) + 17;
  private final IOStream source;
  private final boolean owned;
  private final Format format;
  private final int limit;
  private XiphComment comment;
  private Object details;
  private AudioProperties common;
  private boolean scanned, hasComment;
  private int commentPacket, lastHeaderPacket;
  private long flacStreamLength;
  private ByteVector streamInfo = new ByteVector(), commentData = new ByteVector();

  protected static IOStream openPath(Path path, ReadOptions options) {
    Objects.requireNonNull(options);
    return new FileStream(path);
  }

  protected OggCommentFile(IOStream stream, boolean owned, ReadOptions options, Format format) {
    super(stream);
    this.source = stream;
    this.owned = owned;
    this.format = format;
    limit = Objects.requireNonNull(options).maxMetadataBytes();
    try {
      read(options);
    } catch (RuntimeException | Error failure) {
      if (owned) {
        FailureSupport.closeOnFailure(stream, failure);
      }
      throw failure;
    }
  }

  private ByteVector metadataPacket(int index, long maximum) {
    ByteVector value = packet(index, Math.min(maximum, limit));
    if (packetLimitExceeded() && limit < maximum) {
      throw new MetadataLimitException("Ogg metadata exceeds configured read limit");
    }
    return value;
  }

  private void read(ReadOptions options) {
    if (format == Format.OGG_FLAC) {
      scanFlac();
      if (!scanned) {
        throw new InvalidAudioFileException("Ogg FLAC metadata could not be read: " + name());
      }
      comment = hasComment ? new XiphComment(commentData) : new XiphComment();
      if (options.readAudioProperties()) {
        var p = new cz.skyba.taglib.flac.Properties(streamInfo.data(), flacStreamLength, options.readStyle());
        details = p;
        common = p.common();
      }
      return;
    }
    ByteVector data;
    if (format == Format.OGG_VORBIS) {
      selectStream(new ByteVector("\u0001vorbis"));
      data = metadataPacket(1, 0xffff_ffffL);
      if (!data.startsWith(new ByteVector("\u0003vorbis"))) {
        LOG.log(DEBUG, "Vorbis comment header is missing.");
        throw new InvalidAudioFileException("Vorbis comment header is missing: " + name());
      }
      data = data.mid(7);
    } else {
      boolean opus = format == Format.OGG_OPUS;
      if (!metadataPacket(0, 0xffff_ffffL).startsWith(new ByteVector(opus ? "OpusHead" : "Speex   "))) {
        LOG.log(DEBUG, opus
          ? "Invalid Opus identification header."
          : "Invalid Speex identification header.");
        throw new InvalidAudioFileException("Ogg identification header is invalid: " + name());
      }
      data = metadataPacket(1, 0xffff_ffffL);
      if (opus) {
        if (!data.startsWith(new ByteVector("OpusTags"))) {
          LOG.log(DEBUG, "Invalid Opus tags header.");
          throw new InvalidAudioFileException("Opus tags header is invalid: " + name());
        }
        data = data.mid(8);
      }
    }
    comment = new XiphComment(data);
    if (!options.readAudioProperties()) {
      return;
    }
    switch (format) {
      case OGG_VORBIS -> {
        var p = new cz.skyba.taglib.ogg.vorbis.Properties(this);
        details = p;
        common = p.common();
      }
      case OGG_OPUS -> {
        var p = new cz.skyba.taglib.ogg.opus.Properties(this);
        details = p;
        common = p.common();
      }
      case OGG_SPEEX -> {
        var p = new cz.skyba.taglib.ogg.speex.Properties(this);
        details = p;
        common = p.common();
      }
      default -> throw new AssertionError(format);
    }
  }

  private void scanFlac() {
    if (scanned) {
      return;
    }
    int index = 0, count = 1;
    long overhead = 0, retained = 0;
    ByteVector data = metadataPacket(index, FLAC_PACKET_MAX);
    if (data.isEmpty()) {
      return;
    }
    if (data.startsWith(new ByteVector("fLaC"))) {
      data = metadataPacket(++index, FLAC_PACKET_MAX);
    } else {
      if (data.size() < 13 || data.at(0) != 127 || !data.containsAt(new ByteVector("FLAC"), 1)
        || (data.at(5) != 1 && data.at(6) != 0) || !data.containsAt(new ByteVector("fLaC"), 9)) {
        return;
      }
      data = data.mid(13);
    }
    var header = data.mid(0, 4);
    if (header.size() != 4) {
      LOG.log(DEBUG, "Invalid Ogg FLAC metadata header.");
      return;
    }
    int type = header.at(0) & 127;
    boolean last = (header.at(0) & 128) != 0;
    long size = header.toUnsignedInt(1, 3, true);
    overhead = Math.addExact(overhead, size);
    retained = Math.addExact(retained, data.size());
    if (type != 0) {
      LOG.log(DEBUG, "Ogg FLAC stream does not start with a stream-info block.");
      return;
    }
    streamInfo = data.mid(4, (int) size);
    while (!last) {
      if (count++ >= 1024) {
        LOG.log(DEBUG, "Maximum Ogg FLAC metadata block count exceeded.");
        return;
      }
      data = metadataPacket(++index, FLAC_PACKET_MAX);
      retained = Math.addExact(retained, data.size());
      if (retained > limit) {
        throw new MetadataLimitException("Ogg FLAC aggregate metadata exceeds configured read limit");
      }
      header = data.mid(0, 4);
      if (header.size() != 4) {
        LOG.log(DEBUG, "Invalid Ogg FLAC metadata header.");
        return;
      }
      if ((header.at(0) & 255) == 255 && (header.at(1) & 255) == 248) {
        index--;
        LOG.log(DEBUG, "Found an audio frame before the last Ogg FLAC metadata block marker.");
        break;
      }
      type = header.at(0) & 127;
      last = (header.at(0) & 128) != 0;
      size = header.toUnsignedInt(1, 3, true);
      overhead = Math.addExact(overhead, size);
      if (type == 4) {
        commentData = data.mid(4, (int) size);
        hasComment = true;
        commentPacket = index;
      } else if (type == 127) {
        LOG.log(DEBUG, "Forbidden Ogg FLAC metadata block type.");
      } else if (type > 6) {
        LOG.log(DEBUG, "Unknown Ogg FLAC metadata block type.");
      }
    }
    lastHeaderPacket = index;
    flacStreamLength = Math.subtractExact(length(), overhead);
    scanned = true;
  }

  @Override
  public XiphComment tag() {
    return comment;
  }

  // Native codec properties access stored comment state, independently of a virtual tag override.
  @Override
  public PropertyMap properties() {
    return Objects.requireNonNull(comment, "Ogg comment unavailable").properties();
  }

  @Override
  public PropertyUpdateResult replaceProperties(PropertyMap values) {
    return Objects.requireNonNull(comment, "Ogg comment unavailable").replaceProperties(values);
  }

  @Override
  public AudioProperties audioProperties() {
    return common;
  }

  protected final Object codecProperties() {
    return details;
  }

  protected final boolean flacHasXiphComment() {
    return hasComment;
  }

  protected final long flacStreamLength() {
    scanFlac();
    return flacStreamLength;
  }

  @Override
  public void save() {
    requireWritable();
    if (format == Format.OGG_FLAC && commentPacket == 0) {
      throw new TagLibException("Ogg FLAC has no Vorbis comment metadata block: " + name());
    }
    if (comment == null) {
      comment = new XiphComment();
    }
    ByteVector data;
    int index = 1;
    switch (format) {
      case OGG_VORBIS -> data = new ByteVector("\u0003vorbis").append(comment.render());
      case OGG_OPUS -> data = new ByteVector("OpusTags").append(comment.render(false));
      case OGG_SPEEX -> data = comment.render();
      case OGG_FLAC -> {
        commentData = comment.render(false);
        if (commentData.size() > 0xffffff) {
          comment.removeAllPictures();
          commentData = comment.render(false);
          if (commentData.size() > 0xffffff) {
            throw new TagLibException("Ogg FLAC comment exceeds the 24-bit metadata size limit: " + name());
          }
          LOG.log(DEBUG, "Ogg FLAC metadata block is too large; removing pictures.");
        }
        data = ByteVector.fromUnsignedInt(commentData.size(), true);
        data.set(0, (byte) (commentPacket == lastHeaderPacket ? 132 : 4));
        data.append(commentData);
        index = commentPacket;
      }
      default -> throw new AssertionError(format);
    }
    setPacket(index, data);
    try {
      super.save();
    } catch (UnsafeMemorySpan failure) {
      throw new TagLibException("Ogg packet replacement extends beyond the memory stream: " + name(), failure);
    }
  }

  // ByteVectorStream's native growing insert can use a negative tail length. Guard only that
  // undefined case; source-defined shrinking/overwriting truncated layouts remain unchanged.
  @Override
  protected void insert(ByteVector data, long start, long replace) {
    if (source instanceof ByteVectorStream && data.size() > replace
      && (start < 0 || start > source.length() || replace > source.length() - start)) {
      throw new UnsafeMemorySpan();
    }
    super.insert(data, start, replace);
  }

  private static final class UnsafeMemorySpan extends IllegalArgumentException {
  }

  @Override
  public void close() {
    super.close();
    if (owned) {
      source.close();
    }
  }

  protected static boolean supported(IOStream stream, String marker) {
    var data = TagUtils.readHeader(stream, 1024, false);
    return data.find(new ByteVector("OggS")) >= 0 && data.find(new ByteVector(marker)) >= 0;
  }
}
