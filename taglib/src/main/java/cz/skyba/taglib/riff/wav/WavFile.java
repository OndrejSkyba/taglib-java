// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.riff.wav;

import cz.skyba.taglib.*;
import cz.skyba.taglib.id3.v2.FrameFactory;
import cz.skyba.taglib.internal.FailureSupport;
import cz.skyba.taglib.internal.TagUtils;
import cz.skyba.taglib.riff.RiffFile;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.FileStream;
import cz.skyba.taglib.toolkit.IOStream;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static java.lang.System.Logger.Level.DEBUG;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * An editable WAV file with live metadata and format-specific audio properties.
 * Path construction owns storage; stream construction borrows it. Metadata edits require
 * {@link #save()} unless an operation explicitly writes immediately. Closing does not save.
 */
public class WavFile extends RiffFile {
  private static final System.Logger LOG = System.getLogger(WavFile.class.getName());

  private static final int ID3v2 = 1, Info = 2, AllTags = 65535;

  /**
   * Tag families supported by this format's save and removal operations.
   */
  public enum TagType {
    ID3V2(ID3v2), INFO(Info);

    private final int mask;

    TagType(int mask) {
      this.mask = mask;
    }
  }

  private static int tagMask(Set<TagType> types) {
    int mask = 0;
    for (var type : Objects.requireNonNull(types)) {
      mask |= type.mask;
    }
    return mask;
  }

  private static final ByteVector ID3 = new ByteVector("ID3 "), LOWER = new ByteVector("id3 "), LIST = new ByteVector("LIST");
  private final TagUnion tags = new TagUnion();
  private final FrameFactory factory;
  private Properties details;
  private boolean hasID3, hasInfo, hasXml, hasBext;
  private String xml = "";
  private ByteVector bext = new ByteVector();

  /**
   * Opens a WAV file and owns the file handle. Uses default read options.
   *
   * @param path the file to open
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if parsing rejects the format or its metadata
   */
  public WavFile(Path path) {
    this(path, ReadOptions.DEFAULT);
  }

  /**
   * Opens a WAV file and owns the file handle.
   *
   * @param path    the file to open
   * @param options audio property selection and metadata allocation limit
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if parsing rejects the format or its metadata
   */
  public WavFile(Path path, ReadOptions options) {
    this(path, options, null);
  }

  /**
   * Opens a WAV file and owns the file handle.
   * The frame factory controls ID3v2 parsing; null selects the shared default factory.
   *
   * @param path    the file to open
   * @param options audio property selection and metadata allocation limit
   * @param factory the frame decoder factory, or {@code null} for the default
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if parsing rejects the format or its metadata
   */
  public WavFile(Path path, ReadOptions options, FrameFactory factory) {
    Objects.requireNonNull(options);
    this(new FileStream(path), true, options, factory);
  }

  /**
   * Opens a WAV file over a borrowed stream that remains open when this file closes. Uses default read options.
   *
   * @param stream the open, seekable audio stream
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if parsing rejects the format or its metadata
   * @throws IllegalStateException        if the supplied stream is closed
   */
  public WavFile(IOStream stream) {
    this(stream, ReadOptions.DEFAULT);
  }

  /**
   * Opens a WAV file over a borrowed stream that remains open when this file closes.
   *
   * @param stream  the open, seekable audio stream
   * @param options audio property selection and metadata allocation limit
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if parsing rejects the format or its metadata
   * @throws IllegalStateException        if the supplied stream is closed
   */
  public WavFile(IOStream stream, ReadOptions options) {
    this(stream, options, null);
  }

  /**
   * Opens a WAV file over a borrowed stream that remains open when this file closes.
   * The frame factory controls ID3v2 parsing; null selects the shared default factory.
   *
   * @param stream  the open, seekable audio stream
   * @param options audio property selection and metadata allocation limit
   * @param factory the frame decoder factory, or {@code null} for the default
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if parsing rejects the format or its metadata
   * @throws IllegalStateException        if the supplied stream is closed
   */
  public WavFile(IOStream stream, ReadOptions options, FrameFactory factory) {
    this(stream, false, options, factory);
  }

  private WavFile(IOStream stream, boolean owned, ReadOptions options, FrameFactory factory) {
    super(stream, owned, Endianness.LittleEndian, options);
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

  private void read(boolean audio) {
    for (int i = 0; i < chunkCount(); i++) {
      var name = chunkName(i);
      if (name.equals(ID3) || name.equals(LOWER)) {
        if (!hasID3) {
          tags.set(0, new cz.skyba.taglib.id3.v2.Tag(this, chunkOffset(i), factory));
          hasID3 = true;
        } else {
          LOG.log(DEBUG, "Duplicate ID3v2 tag found.");
        }
      } else if (name.equals(LIST)) {
        var data = chunkData(i);
        if (data.startsWith(new ByteVector("INFO"))) {
          if (!hasInfo) {
            tags.set(1, new cz.skyba.taglib.riff.info.Tag(data));
            hasInfo = true;
          } else {
            LOG.log(DEBUG, "Duplicate INFO tag found.");
          }
        }
      } else if (name.equals(new ByteVector("iXML"))) {
        var data = chunkData(i);
        xml = data.decodeTerminatedText(UTF_8);
        hasXml = true;
      } else if (name.equals(new ByteVector("bext"))) {
        bext = chunkData(i);
        hasBext = true;
      }
    }
    if (tags.tag(0) == null) {
      tags.set(0, new cz.skyba.taglib.id3.v2.Tag(null, 0, factory));
    }
    if (tags.tag(1) == null) {
      tags.set(1, new cz.skyba.taglib.riff.info.Tag());
    }
    if (audio) {
      details = new Properties(this, ReadStyle.AVERAGE);
    }
  }

  @Override
  public TagBase tag() {
    return tags;
  }

  /**
   * Returns the live ID3v2 tag without creating one.
   *
   * @return the live tag object, or {@code null} if no object exists
   */
  public final cz.skyba.taglib.id3.v2.Tag id3v2Tag() {
    return (cz.skyba.taglib.id3.v2.Tag) tags.tag(0);
  }

  public final cz.skyba.taglib.riff.info.Tag infoTag() {
    return (cz.skyba.taglib.riff.info.Tag) tags.tag(1);
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
    infoTag().replaceProperties(values);
    return id3v2Tag().replaceProperties(values);
  }

  @Override
  public AudioProperties audioProperties() {
    return details == null ? null : details.common();
  }

  /**
   * Returns the format-specific parsed audio properties.
   *
   * @return the detailed properties, or {@code null} if properties were not read or are unavailable
   */
  public Properties detailedProperties() {
    return details;
  }

  public final boolean hasId3v2Tag() {
    return hasID3;
  }

  public final boolean hasInfoTag() {
    return hasInfo;
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

  /**
   * Stages replacement of the iXML text.
   * An empty value requests removal when the file is saved.
   *
   * @param value the replacement text
   */
  public final void setiXMLData(String value) {
    xml = Objects.requireNonNull(value);
  }

  /**
   * Returns an independent copy of the stored Broadcast Wave extension bytes.
   *
   * @return copied extension bytes
   */
  public final byte[] BEXTData() {
    return bext.toByteArray();
  }

  /**
   * Stages replacement of the Broadcast Wave extension bytes, copying the input.
   * An empty value requests removal when the file is saved.
   *
   * @param value the replacement bytes
   */
  public final void setBEXTData(byte[] value) {
    bext = new ByteVector(value);
  }

  /**
   * Stages replacement of the Broadcast Wave extension bytes, copying the input.
   * An empty value requests removal when the file is saved.
   *
   * @param value the replacement bytes
   */
  public final void setBEXTData(ByteVector value) {
    bext = new ByteVector(value);
  }

  /**
   * Checks for a recognizable WAV header without validating the complete file.
   * The input cursor is restored after inspection.
   *
   * @param stream the stream to inspect
   * @return whether a format header is recognized
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public static boolean isSupported(IOStream stream) {
    var header = TagUtils.readHeader(stream, 12, false);
    return (header.startsWith(new ByteVector("RIFF")) || header.startsWith(new ByteVector("RF64"))
      || header.startsWith(new ByteVector("BW64"))) && header.containsAt(new ByteVector("WAVE"), 8);
  }

  Properties readProperties() {
    ByteVector fmt = new ByteVector();
    long data = 0, fact = 0;
    for (int i = 0; i < chunkCount(); i++) {
      var name = chunkName(i);
      if (name.equals(new ByteVector("fmt "))) {
        if (fmt.isEmpty()) {
          fmt = chunkData(i);
        } else {
          LOG.log(DEBUG, "Duplicate 'fmt ' chunk found.");
        }
      } else if (name.equals(new ByteVector("data"))) {
        if (data == 0) {
          data = Math.addExact(chunkDataSize64(i), chunkPadding(i));
        } else {
          LOG.log(DEBUG, "Duplicate 'data' chunk found.");
        }
      } else if (name.equals(new ByteVector("fact"))) {
        if (fact == 0) {
          fact = chunkData(i).toUnsignedInt(false);
        } else {
          LOG.log(DEBUG, "Duplicate 'fact' chunk found.");
        }
      }
    }
    if (fmt.size() < 16) {
      LOG.log(DEBUG, "'fmt ' chunk not found or too short.");
    } else if (data == 0) {
      LOG.log(DEBUG, "'data' chunk not found.");
    }
    return Properties.fromChunks(fmt.toByteArray(), data, fact);
  }

  private void removeTagChunks(int mask) {
    if ((mask & ID3v2) != 0 && hasID3) {
      removeChunk(ID3);
      removeChunk(LOWER);
      hasID3 = false;
    }
    if ((mask & Info) != 0 && hasInfo) {
      for (int i = chunkCount() - 1; i >= 0; i--) {
        if (chunkName(i).equals(LIST) && chunkData(i).startsWith(new ByteVector("INFO"))) {
          removeChunk(i);
        }
      }
      hasInfo = false;
    }
  }

  /**
   * Removes all supported tag types from storage immediately, without calling save().
   */
  public final void strip() {
    strip(Set.of(TagType.values()));
  }

  /**
   * Removes the selected tag types from storage immediately, without calling save().
   */
  public final void strip(Set<TagType> types) {
    stripMask(tagMask(types));
  }

  private void stripMask(int mask) {
    if (!cachedLayoutWritable()) {
      throw new IllegalStateException("Unsafe cached WAV layout");
    }
    removeTagChunks(mask);
    if ((mask & ID3v2) != 0) {
      tags.set(0, new cz.skyba.taglib.id3.v2.Tag(null, 0, factory));
    }
    if ((mask & Info) != 0) {
      tags.set(1, new cz.skyba.taglib.riff.info.Tag());
    }
  }

  /**
   * Writes staged metadata to storage in place using {@link WavSaveOptions#DEFAULT}.
   * Saves all supported tag families using ID3v2.4. An I/O failure can leave partial changes.
   */
  @Override
  public void save() {
    save(WavSaveOptions.DEFAULT);
  }

  /**
   * Writes staged metadata to storage in place using the supplied options.
   * Selected empty tags are removed from storage. BEXT and iXML edits are saved
   * regardless of the tag selection.
   * Unselected tag families are preserved with {@link StripTags#STRIP_NONE} and removed
   * with {@link StripTags#STRIP_OTHERS}. An I/O failure can leave partial changes.
   *
   * @param options tag selection, preservation policy and ID3v2 output settings
   * @throws NullPointerException         if {@code options} is null
   * @throws java.io.UncheckedIOException if storage access fails
   * @throws TagLibException              if storage is read-only or the cached layout cannot be saved
   * @throws IllegalStateException        if the file is closed
   */
  public final void save(WavSaveOptions options) {
    requireWritable();
    int mask = tagMask(options.tags());
    var strip = options.unselectedTags();
    var version = options.id3v2Version();
    if (!cachedLayoutWritable()) {
      throw new TagLibException("WAV cached chunk layout is no longer writable: " + name());
    }
    if (strip == StripTags.STRIP_OTHERS) {
      stripMask(AllTags & ~mask);
    }
    if (!bext.isEmpty()) {
      removeChunk(new ByteVector("bext"));
      setChunkData(new ByteVector("bext"), bext);
      hasBext = true;
    } else if (hasBext) {
      removeChunk(new ByteVector("bext"));
      hasBext = false;
    }
    if (!xml.isEmpty()) {
      removeChunk(new ByteVector("iXML"));
      setChunkData(new ByteVector("iXML"), ByteVector.fromText(xml, UTF_8));
      hasXml = true;
    } else if (hasXml) {
      removeChunk(new ByteVector("iXML"));
      hasXml = false;
    }
    if ((mask & ID3v2) != 0) {
      removeTagChunks(ID3v2);
      var modernTag = id3v2Tag();
      if (modernTag != null && !modernTag.isEmpty()) {
        setChunkData(ID3, id3v2Tag().render(version));
        hasID3 = true;
      }
    }
    if ((mask & Info) != 0) {
      removeTagChunks(Info);
      var infoTag = infoTag();
      if (infoTag != null && !infoTag.isEmpty()) {
        setChunkData(LIST, infoTag().render(), true);
        hasInfo = true;
      }
    }
  }
}
