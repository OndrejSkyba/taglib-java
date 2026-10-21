// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.dsdiff;

import cz.skyba.taglib.*;
import cz.skyba.taglib.dsdiff.DsdiffLayout.Carrier;
import cz.skyba.taglib.dsdiff.DsdiffLayout.Chunk;
import cz.skyba.taglib.dsdiff.DsdiffLayout.ContainerState;
import cz.skyba.taglib.id3.v2.FrameFactory;
import cz.skyba.taglib.id3.v2.Tag;
import cz.skyba.taglib.internal.*;
import cz.skyba.taglib.toolkit.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;

import static java.lang.System.Logger.Level.DEBUG;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

/// An editable DSDIFF file with live metadata and format-specific audio properties.
/// Path construction owns storage; stream construction borrows it. Metadata edits require
/// [#save()] unless an operation explicitly writes immediately. Closing does not save.
public class DsdiffFile extends FileBase {
  private static final System.Logger LOG = System.getLogger(DsdiffFile.class.getName());

  static final int DIIN = 1, ID3v2 = 2;

  /// Tag families supported by this format's save and removal operations.
  public enum TagType {
    DIIN(DsdiffFile.DIIN), ID3V2(ID3v2);

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

  /// The byte order used by DSDIFF chunk integers.
  protected enum Endianness {BigEndian, LittleEndian}

  private final IOStream source;
  private final boolean owned;
  private final int metadataLimit;
  private final FrameFactory factory;
  private final TagUnion tag = new TagUnion();
  private Properties details;
  private final DsdiffLayout layout = new DsdiffLayout(new LiveTarget());
  private long retainedChunks;

  /// Opens a DSDIFF file and owns the file handle. Uses default read options.
  ///
  /// @param path the file to open
  /// @throws java.io.UncheckedIOException if storage access fails
  /// @throws TagLibException              if parsing rejects the format or its metadata
  public DsdiffFile(Path path) {
    this(path, ReadOptions.DEFAULT);
  }

  /// Opens a DSDIFF file and owns the file handle.
  ///
  /// @param path    the file to open
  /// @param options audio property selection and metadata allocation limit
  /// @throws java.io.UncheckedIOException if storage access fails
  /// @throws TagLibException              if parsing rejects the format or its metadata
  public DsdiffFile(Path path, ReadOptions options) {
    this(path, options, null);
  }

  /// Opens a DSDIFF file and owns the file handle.
  /// The frame factory controls ID3v2 parsing; null selects the shared default factory.
  ///
  /// @param path    the file to open
  /// @param options audio property selection and metadata allocation limit
  /// @param factory the frame decoder factory, or `null` for the default
  /// @throws java.io.UncheckedIOException if storage access fails
  /// @throws TagLibException              if parsing rejects the format or its metadata
  public DsdiffFile(Path path, ReadOptions options, FrameFactory factory) {
    Objects.requireNonNull(options);
    this(new FileStream(path), true, options, factory);
  }

  /// Opens a DSDIFF file over a borrowed stream that remains open when this file closes. Uses default read options.
  ///
  /// @param stream the open, seekable audio stream
  /// @throws java.io.UncheckedIOException if storage access fails
  /// @throws TagLibException              if parsing rejects the format or its metadata
  /// @throws IllegalStateException        if the supplied stream is closed
  public DsdiffFile(IOStream stream) {
    this(stream, ReadOptions.DEFAULT);
  }

  /// Opens a DSDIFF file over a borrowed stream that remains open when this file closes.
  ///
  /// @param stream  the open, seekable audio stream
  /// @param options audio property selection and metadata allocation limit
  /// @throws java.io.UncheckedIOException if storage access fails
  /// @throws TagLibException              if parsing rejects the format or its metadata
  /// @throws IllegalStateException        if the supplied stream is closed
  public DsdiffFile(IOStream stream, ReadOptions options) {
    this(stream, false, options, null);
  }

  /// Opens a DSDIFF file over a borrowed stream that remains open when this file closes.
  /// The frame factory controls ID3v2 parsing; null selects the shared default factory.
  ///
  /// @param stream  the open, seekable audio stream
  /// @param options audio property selection and metadata allocation limit
  /// @param factory the frame decoder factory, or `null` for the default
  /// @throws java.io.UncheckedIOException if storage access fails
  /// @throws TagLibException              if parsing rejects the format or its metadata
  /// @throws IllegalStateException        if the supplied stream is closed
  public DsdiffFile(IOStream stream, ReadOptions options, FrameFactory factory) {
    this(stream, false, options, factory);
  }

  private DsdiffFile(IOStream stream, boolean owned, ReadOptions options, FrameFactory factory) {
    super(stream);
    source = stream;
    this.owned = owned;
    metadataLimit = Objects.requireNonNull(options).maxMetadataBytes();
    this.factory = factory == null ? FrameFactory.instance() : factory;
    try {
      read(options.readAudioProperties(), options.readStyle());
      if (!isValid()) {
        throw new InvalidAudioFileException("Invalid DSDIFF chunk layout: " + name());
      }
    } catch (RuntimeException | Error failure) {
      if (owned) {
        FailureSupport.closeOnFailure(stream, failure);
      }
      throw failure;
    }
  }

  private boolean preflight(Consumer<DsdiffLayout> mutations) {
    try {
      mutations.accept(layout.snapshot(this::length, source instanceof ByteVectorStream));
      return true;
    } catch (DsdiffLayout.InvalidPlan | IndexOutOfBoundsException | ArithmeticException invalid) {
      return false;
    }
  }

  private void retainChunk() {
    // Charge retained records as well as payload reads to the explicit metadata limit.
    if (++retainedChunks > metadataLimit / 64L) {
      throw new MetadataLimitException("DSDIFF chunk index exceeds the configured metadata limit");
    }
  }

  @Override
  public TagUnion tag() {
    return tag;
  }

  @Override
  public PropertyMap properties() {
    return tag.properties();
  }

  @Override
  public void removeUnsupportedProperties(List<String> values) {
    tag.removeUnsupportedProperties(values);
  }

  @Override
  public AudioProperties audioProperties() {
    return details == null ? null : details.common();
  }

  /// @return the detailed properties, or `null` if properties were not read or are unavailable
  public Properties detailedProperties() {
    return details;
  }

  @Override
  public ByteVector readBlock(long length) {
    if (length > metadataLimit) {
      throw new MetadataLimitException("DSDIFF metadata exceeds the configured read limit");
    }
    return super.readBlock(length);
  }

  /// Checks for a recognizable DSDIFF header without validating the complete file.
  /// The input cursor is restored after inspection.
  ///
  /// @param source the stream to inspect
  /// @return whether a format header is recognized
  /// @throws java.io.UncheckedIOException if storage access fails
  public static boolean isSupported(IOStream source) {
    var header = TagUtils.readHeader(source, 16, false);
    return header.startsWith(new ByteVector("FRM8")) && header.containsAt(new ByteVector("DSD "), 12);
  }

  /// Returns the live ID3v2 tag without creating one.
  ///
  /// @return the live tag object, or `null` if no object exists
  public final Tag id3v2Tag() {
    return (Tag) tag.tag(0);
  }

  /// Returns the live ID3v2 tag, creating an empty tag in memory if absent. Creation does not write storage.
  ///
  /// @return the live tag object
  public final Tag getOrCreateId3v2Tag() {
    if (tag.tag(0) == null) {
      tag.set(0, new Tag(null, 0, factory));
    }
    return (Tag) tag.tag(0);
  }

  /// Returns the live DIIN tag without creating one.
  ///
  /// @return the live tag object, or `null` if no object exists
  public final cz.skyba.taglib.dsdiff.diin.Tag diinTag() {
    return (cz.skyba.taglib.dsdiff.diin.Tag) tag.tag(1);
  }

  /// Returns the live DIIN tag, creating an empty tag in memory if absent. Creation does not write storage.
  ///
  /// @return the live tag object
  public final cz.skyba.taglib.dsdiff.diin.Tag getOrCreateDiinTag() {
    if (tag.tag(1) == null) {
      tag.set(1, new cz.skyba.taglib.dsdiff.diin.Tag());
    }
    return (cz.skyba.taglib.dsdiff.diin.Tag) tag.tag(1);
  }

  public final boolean hasId3v2Tag() {
    return layout.hasModern;
  }

  public final boolean hasDiinTag() {
    return layout.hasDiin;
  }

  @Override
  public PropertyUpdateResult replaceProperties(PropertyMap values) {
    return getOrCreateId3v2Tag().replaceProperties(values);
  }

  private static boolean fits(long at, long count, long end) {
    return at >= 0 && at <= end && count >= 0 && count <= end - at;
  }

  private void invalid(String message) {
    LOG.log(DEBUG, message);
    setValid(false);
  }

  private Chunk nextChunk(long end, String context) {
    var name = readBlock(4);
    long count = readBlock(8).toUnsignedLong(true);
    String id = name.decodeTerminatedText(ISO_8859_1);
    if (name.size() != 4 || !validId(name)) {
      invalid(context + "Chunk '" + id + "' has invalid ID");
      return null;
    }
    if (!fits(tell(), count, end)) {
      String enclosing = context.isEmpty() ? "file size" : context.trim() + " chunk";
      invalid(context + "Chunk '" + id + "' has invalid size (larger than the " + enclosing + ")");
      return null;
    }
    return new Chunk(id, tell(), count, 0);
  }

  private static boolean validId(ByteVector id) {
    for (int i = 0; i < id.size(); i++) {
      int value = id.get(i) & 255;
      if (value < 32 || value > 126) {
        return false;
      }
    }
    return true;
  }

  private int padding() {
    long at = tell();
    if ((at & 1) == 0) {
      return 0;
    }
    var value = readBlock(1);
    if (value.size() == 1 && value.get(0) == 0) {
      return 1;
    }
    seek(at);
    return 0;
  }

  private void readChildren(Chunk root, ContainerState container) {
    long end = root.offset + root.size;
    seek(root.offset + container.prefixSize);
    while (fits(tell(), 12, end)) {
      long start = tell();
      var child = nextChunk(end, container.id + " ");
      if (child == null) {
        break;
      }
      long before = tell();
      seek(child.size, IOStream.Position.CURRENT);
      child.padding = padding();
      if (tell() <= start || tell() < before) {
        throw new IllegalArgumentException("DSDIFF stream does not advance");
      }
      retainChunk();
      container.children.add(child);
    }
  }

  private void read(boolean readProperties, ReadStyle style) {
    readBlock(4);
    layout.size = readBlock(8).toUnsignedLong(true);
    readBlock(4);
    long end = length();
    while (fits(tell(), 12, end)) {
      long start = tell();
      var root = nextChunk(end, "");
      if (root == null) {
        break;
      }
      long before = tell();
      seek(root.size, IOStream.Position.CURRENT);
      root.padding = padding();
      if (tell() <= start || tell() < before) {
        throw new IllegalArgumentException("DSDIFF stream does not advance");
      }
      retainChunk();
      layout.roots.add(root);
    }
    long samples = 0, audioSize = 0, frames = 0;
    int frameRate = 0;
    for (int i = 0; i < layout.roots.size(); i++) {
      var root = layout.roots.get(i);
      switch (root.id) {
        case "DSD " -> {
          samples = root.size * 8;
          audioSize = root.size;
        }
        case "DST " -> {
          audioSize = root.size;
          long dstEnd = root.offset + root.size;
          seek(root.offset);
          while (fits(tell(), 12, dstEnd)) {
            long start = tell();
            var child = nextChunk(dstEnd, "DST ");
            if (child == null) {
              break;
            }
            if (child.id.equals("FRTE")) {
              frames = readBlock(4).toUnsignedInt(true);
              frameRate = readBlock(2).toUnsignedShort(true);
              break;
            }
            seek(child.size, IOStream.Position.CURRENT);
            padding();
            if (tell() <= start) {
              throw new IllegalArgumentException("DSDIFF stream does not advance");
            }
          }
        }
        case "PROP" -> {
          layout.properties.attach(root);
          readChildren(root, layout.properties);
        }
        case "DIIN" -> {
          layout.diinContainer.attach(root);
          layout.hasDiin = true;
          readChildren(root, layout.diinContainer);
        }
        case "ID3 ", "id3 " -> {
          layout.placement = layout.placement.withId(root.id);
          tag.set(0, new Tag(this, root.offset, factory));
          layout.placement = layout.placement.withCarrier(Carrier.ROOT);
          layout.hasModern = true;
        }
        default -> {
        }
      }
    }
    if (!isValid()) {
      return;
    }
    if (layout.properties.parentIndex(layout.roots) < 0) {
      invalid("no PROP chunk found");
      return;
    }
    long rate = 0;
    int channels = 0;
    var prop = layout.properties.children;
    for (int i = 0; i < prop.size(); i++) {
      var child = prop.get(i);
      switch (child.id) {
        case "ID3 ", "id3 " -> {
          if (layout.hasModern) {
            layout.duplicate = OptionalInt.of(i);
            continue;
          }
          layout.placement = layout.placement.withId(child.id);
          tag.set(0, new Tag(this, child.offset, factory));
          layout.placement = layout.placement.withCarrier(Carrier.PROP);
          layout.hasModern = true;
        }
        case "FS  " -> {
          seek(child.offset);
          rate = readBlock(4).toUnsignedInt(0, 4, true);
        }
        case "CHNL" -> {
          seek(child.offset);
          channels = Short.toUnsignedInt(readBlock(2).toShort(0, true));
        }
        default -> {
        }
      }
    }
    getOrCreateDiinTag();
    if (layout.hasDiin) {
      for (var child : layout.diinContainer.children) {
        if (child.id.equals("DITI") || child.id.equals("DIAR")) {
          seek(child.offset);
          long count = readBlock(4).toUnsignedInt(0, 4, true);
          if (Long.compareUnsigned(count, child.size) <= 0) {
            var data = readBlock(count);
            String value = data.decodeTerminatedText(ISO_8859_1);
            if (child.id.equals("DITI")) {
              diinTag().setTitle(value);
            } else {
              diinTag().setArtist(value);
            }
          }
        }
      }
    }
    if (readProperties) {
      if (samples == 0) {
        samples = frameRate > 0 ? Long.divideUnsigned(frames * rate, frameRate) : 0;
      } else if (channels > 0) {
        samples = Long.divideUnsigned(samples, channels);
      }
      int bitrate = samples != 0 ? (int) Long.divideUnsigned(Long.divideUnsigned(audioSize * 8 * rate, samples), 1000) : 0;
      details = new Properties(rate, channels, samples, bitrate, style);
    }
    if (id3v2Tag() == null) {
      getOrCreateId3v2Tag();
      layout.placement = layout.placement.withCarrier(Carrier.ROOT);
      layout.hasModern = false;
    }
  }

  /// Writes staged metadata to storage in place using [DsdiffSaveOptions#DEFAULT].
  /// Saves all supported tag families using ID3v2.4. An I/O failure can leave partial changes.
  @Override
  public void save() {
    save(DsdiffSaveOptions.DEFAULT);
  }

  /// Writes staged metadata to storage in place using the supplied options.
  /// Unselected tag families are preserved with [StripTags#STRIP_NONE] and removed
  /// with [StripTags#STRIP_OTHERS]. An I/O failure can leave partial changes.
  ///
  /// @param options tag selection, preservation policy and ID3v2 output settings
  /// @throws NullPointerException         if `options` is null
  /// @throws java.io.UncheckedIOException if storage access fails
  /// @throws TagLibException              if storage is read-only or the cached layout cannot be saved
  /// @throws IllegalStateException        if the file is closed
  public final void save(DsdiffSaveOptions options) {
    requireWritable();
    int tags = tagMask(options.tags());
    var strip = options.unselectedTags();
    var version = options.id3v2Version();
    if (strip == StripTags.STRIP_OTHERS) {
      if (!preflight(plan -> plan.stripImpl(~tags))) {
        throw new TagLibException("Cannot strip DSDIFF chunks with the cached layout: " + name());
      }
      layout.stripImpl(~tags);
    }
    var modern = id3v2Tag();
    if ((tags & ID3v2) != 0 && modern != null) {
      boolean empty = modern.isEmpty();
      byte[] data = empty ? new byte[0] : modern.render(version).toByteArray();
      if (!preflight(plan -> {
        if (plan.placement.inProperties()) {
          plan.setChild(plan.properties, plan.placement.id(), data);
        } else {
          plan.setRoot(plan.placement.id(), data);
        }
        // Default DIIN getters are pure. Check their remaining complete plan before ID3 I/O.
        var d = diinTag();
        if ((tags & DIIN) != 0 && d != null && d.getClass() == cz.skyba.taglib.dsdiff.diin.Tag.class) {
          plan.setChild(plan.diinContainer, "DITI", diinBytes(d.title()));
          plan.setChild(plan.diinContainer, "DIAR", diinBytes(d.artist()));
        }
        if (plan.duplicate.isPresent()) {
          plan.removeChild(plan.properties, plan.duplicate.getAsInt());
        }
      })) {
        throw new TagLibException("DSDIFF ID3 tag does not fit the writable chunk layout: " + name());
      }
      if (layout.placement.inProperties()) {
        layout.setChild(layout.properties, layout.placement.id(), data);
      } else {
        layout.setRoot(layout.placement.id(), data);
      }
      layout.hasModern = !empty;
    }
    var diin = diinTag();
    if ((tags & DIIN) != 0 && diin != null) {
      if (!saveDiinChild(diin, "DITI", true)) {
        throw new TagLibException("DSDIFF title does not fit the writable DIIN chunk: " + name());
      }
      if (!saveDiinChild(diin, "DIAR", false)) {
        throw new TagLibException("DSDIFF artist does not fit the writable DIIN chunk: " + name());
      }
    }
    if (layout.duplicate.isPresent()) {
      if (!preflight(plan -> plan.removeChild(plan.properties, plan.duplicate.orElseThrow()))) {
        throw new TagLibException("Cannot remove duplicate DSDIFF ID3 chunk: " + name());
      }
      layout.removeChild(layout.properties, layout.duplicate.getAsInt());
      layout.duplicate = OptionalInt.empty();
    }
  }

  /// Removes all supported tag types from storage immediately, without calling save().
  public final void strip() {
    strip(Set.of(TagType.values()));
  }

  /// Validates the complete removal plan, then immediately strips the selected tag chunks and resets
  /// their live tags.
  public final void strip(Set<TagType> types) {
    int tags = tagMask(types);
    if (!preflight(plan -> plan.stripImpl(tags))) {
      throw new IllegalStateException("DSDIFF strip has no representable native write plan");
    }
    layout.stripImpl(tags);
  }

  private boolean saveDiinChild(cz.skyba.taglib.dsdiff.diin.Tag diin, String id, boolean title) {
    byte[] data;
    if ((title ? diin.title() : diin.artist()).isEmpty()) {
      data = new byte[0];
    } else {
      int count = (title ? diin.title() : diin.artist()).length();
      data = diinBytes(title ? diin.title() : diin.artist(), count);
    }
    if (!preflight(plan -> {
      plan.setChild(plan.diinContainer, id, data);
      if (plan.duplicate.isPresent()) {
        plan.removeChild(plan.properties, plan.duplicate.getAsInt());
      }
    })) {
      return false;
    }
    layout.setChild(layout.diinContainer, id, data);
    return true;
  }

  private static byte[] diinBytes(String value) {
    return value.isEmpty() ? new byte[0] : diinBytes(value, value.length());
  }

  private static byte[] diinBytes(String value, int declaredCount) {
    byte[] raw = TextBytes.encode(value, ISO_8859_1);
    int count = 0;
    while (count < raw.length && raw[count] != 0) {
      count++;
    }
    byte[] data = new byte[4 + count];
    Binary.put32be(data, 0, declaredCount);
    System.arraycopy(raw, 0, data, 4, count);
    return data;
  }

  private final class LiveTarget implements DsdiffLayout.Target {
    public void number(long at, long value) {
      DsdiffFile.this.insert(ByteVector.fromUnsignedLong(value, true), at, 8);
    }

    public void remove(long start, long count) {
      DsdiffFile.this.removeBlock(start, count);
    }

    public void render(Chunk chunk, byte[] data, long replace, int leading, int renderedSize) {
      byte[] body = new byte[renderedSize];
      System.arraycopy(Binary.bytes(chunk.id), 0, body, leading, 4);
      Binary.put64be(body, leading + 4, data.length);
      System.arraycopy(data, 0, body, leading + 12, data.length);
      DsdiffFile.this.insert(new ByteVector(body), chunk.offset - 12, replace);
    }

    public long length() {
      return DsdiffFile.this.length();
    }

    public void clearModern() {
      tag.set(0, new Tag(null, 0, factory));
    }

    public void clearDiin() {
      tag.set(1, new cz.skyba.taglib.dsdiff.diin.Tag());
    }

    public void debug(String message) {
      LOG.log(DEBUG, message);
    }
  }

  @Override
  public void close() {
    if (owned) {
      source.close();
    }
  }
}
