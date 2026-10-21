// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.codec;

import cz.skyba.taglib.*;
import cz.skyba.taglib.Tag;
import cz.skyba.taglib.internal.*;
import cz.skyba.taglib.internal.ebml.DeferredBinaryElement;
import cz.skyba.taglib.matroska.*;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.IOStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

/// EBML metadata parsing and native default Compact updates.
public final class MatroskaCodec implements Codec {
  private Segment nativeSegment;
  private ReadOptions nativeOptions;

  public record Details(AudioProperties common, String docType, int docTypeVersion,
                        String codecName, String title) {
  }

  private Details nativeProperties;

  public static Details readProperties(BinaryFile file, ReadStyle style) throws IOException {
    var codec = new MatroskaCodec();
    codec.readNative(file, new ReadOptions(true, style, ReadOptions.DEFAULT.maxMetadataBytes()));
    return codec.nativeProperties;
  }

  @Override
  public PropertyMap unsupportedProperties(PropertyMap properties) {
    var rejected = PropertyMap.builder();
    if (properties.containsKey("") && !properties.get("").isEmpty()) {
      rejected.put("", properties.get(""));
    }
    return rejected.build();
  }

  private static final long SEGMENT = 0x18538067L,
    TAGS = 0x1254c367L,
    CHAPTERS = 0x1043a770L,
    INFO = 0x1549a966L,
    TRACKS = 0x1654ae6bL,
    ATTACHMENTS = 0x1941a469L,
    SEEKHEAD = 0x114d9b74L;

  private static final List<Long> CUE_TRACK_FIELDS =
    List.of(0xf7L, 0xf1L, 0xf0L, 0xb2L, 0x5378L, 0xeaL);
  private static final List<Long> CUE_POSITION_FIELDS = List.of(0xf1L, 0xeaL);

  private record Mapping(String key, String name, int target, boolean strict) {
  }

  private static final List<Mapping> MAP = List.of(
    new Mapping("TITLE", "TITLE", 30, false),
    new Mapping("ALBUM", "TITLE", 50, true),
    new Mapping("ARTIST", "ARTIST", 30, false),
    new Mapping("ALBUMARTIST", "ARTIST", 50, true),
    new Mapping("TRACKNUMBER", "PART_NUMBER", 30, false),
    new Mapping("DISCNUMBER", "PART_NUMBER", 50, true),
    new Mapping("TRACKTOTAL", "TOTAL_PARTS", 30, false),
    new Mapping("DISCTOTAL", "TOTAL_PARTS", 50, true),
    new Mapping("DATE", "DATE_RECORDED", 30, false),
    new Mapping("TITLESORT", "TITLESORT", 30, false),
    new Mapping("ALBUMSORT", "TITLESORT", 50, true),
    new Mapping("ARTISTSORT", "ARTISTSORT", 30, false),
    new Mapping("ALBUMARTISTSORT", "ARTISTSORT", 50, true),
    new Mapping("MEDIA", "ORIGINAL_MEDIA_TYPE", 30, false),
    new Mapping("LABEL", "LABEL_CODE", 30, false),
    new Mapping("CATALOGNUMBER", "CATALOG_NUMBER", 30, false),
    new Mapping("DJMIXER", "MIXED_BY", 30, false),
    new Mapping("REMIXER", "REMIXED_BY", 30, false),
    new Mapping("INITIALKEY", "INITIAL_KEY", 30, false),
    new Mapping("RELEASEDATE", "DATE_RELEASED", 50, false),
    new Mapping("ENCODINGTIME", "DATE_ENCODED", 30, false),
    new Mapping("TAGGINGDATE", "DATE_TAGGED", 30, false),
    new Mapping("ENCODEDBY", "ENCODER", 30, false),
    new Mapping("ENCODING", "ENCODER_SETTINGS", 30, false),
    new Mapping("OWNER", "PURCHASE_OWNER", 30, false),
    new Mapping("REPLAYGAIN_TRACK_GAIN", "REPLAYGAIN_GAIN", 30, false),
    new Mapping("REPLAYGAIN_ALBUM_GAIN", "REPLAYGAIN_GAIN", 50, true),
    new Mapping("REPLAYGAIN_TRACK_PEAK", "REPLAYGAIN_PEAK", 30, false),
    new Mapping("REPLAYGAIN_ALBUM_PEAK", "REPLAYGAIN_PEAK", 50, true),
    new Mapping("MUSICBRAINZ_ALBUMARTISTID", "MUSICBRAINZ_ALBUMARTISTID", 50, false),
    new Mapping("MUSICBRAINZ_ALBUMID", "MUSICBRAINZ_ALBUMID", 50, false),
    new Mapping("MUSICBRAINZ_RELEASEGROUPID", "MUSICBRAINZ_RELEASEGROUPID", 50, false));

  /// All generic keys backed by a native SimpleTag name, irrespective of its target.
  public static List<String> propertyKeysForName(String name) {
    var keys = new ArrayList<String>();
    keys.add(name);
    for (var mapping : MAP) {
      if (mapping.name.equals(name)) {
        keys.add(mapping.key);
      }
    }
    return List.copyOf(keys);
  }

  /// An admitted element retains its successfully parsed children for an ordinary file read.
  private static final class Element {
    final long id, offset, length;
    final int header, sizeWidth;
    final boolean unknown;
    private List<Element> parsedChildren;
    private long parsedCursor;
    private byte[] valueBytes;

    Element(long id, long offset, int header, long length, int sizeWidth, boolean unknown) {
      this.id = id;
      this.offset = offset;
      this.header = header;
      this.length = length;
      this.sizeWidth = sizeWidth;
      this.unknown = unknown;
    }

    long data() {
      return offset + header;
    }

    long end() {
      return data() + length;
    }

    long total() {
      return header + length;
    }
  }

  private record RenderStatus(boolean cuesRendered, boolean seekRendered) {
    private static final RenderStatus ORIGINAL = new RenderStatus(false, false);
  }

  private record Segment(Element element, List<Element> selected, long nativeCursor,
                         RenderStatus renderStatus, String docType, int docTypeVersion) {
    Segment(
      Element element, List<Element> selected, long nativeCursor,
      RenderStatus renderStatus
    ) {
      this(element, selected, nativeCursor, renderStatus, "", 0);
    }

    Segment(Element element, List<Element> selected, long nativeCursor) {
      this(element, selected, nativeCursor, RenderStatus.ORIGINAL);
    }
  }

  private record VInt(long value, int width, boolean unknown) {
  }

  private static VInt vint(BinaryFile f, long at, boolean id) throws IOException {
    int b = f.u8(at), mask = 128, n = 1;
    while (n <= 8 && (b & mask) == 0) {
      n++;
      mask >>>= 1;
    }
    if (n > 8 || (id && n > 4)) {
      throw new IOException("Invalid EBML integer");
    }
    long value = id ? b : b & (mask - 1);
    if (n > 1 && f.retainReadData()) {
      for (byte next : f.read(at + 1, n - 1)) value = (value << 8) | (next & 255);
    } else {
      // Custom streams retain their existing request granularity and callbacks.
      for (int i = 1; i < n; i++) value = (value << 8) | f.u8(at + i);
    }
    long unknown = (1L << (7 * n)) - 1;
    return new VInt(value, n, !id && value == unknown);
  }

  private static Element element(BinaryFile f, long p, long end) throws IOException {
    VInt id = vint(f, p, true), size = vint(f, p + id.width(), false);
    int h = id.width() + size.width();
    long length = size.unknown() ? end - p - h : size.value();
    if (length < 0 || p > end - h || length > end - p - h) {
      throw new IOException("EBML element exceeds parent");
    }
    return new Element(id.value(), p, h, length, size.width(), size.unknown());
  }

  private static List<Element> children(BinaryFile f, Element parent) throws IOException {
    if (f.retainReadData() && parent.parsedChildren != null) {
      f.seekNative(parent.parsedCursor);
      return parent.parsedChildren;
    }
    return new ElementReader(f).children(parent, 0, false);
  }

  private static final Set<Long> MASTERS = Set.of(0x1a45dfa3L, INFO, TRACKS, TAGS,
    ATTACHMENTS, CHAPTERS, SEEKHEAD, 0x7373L, 0x63c0L, 0x67c8L, 0x61a7L,
    0x4dbbL, 0xaeL, 0xe1L, 0x1c53bb6bL, 0xbbL, 0xb7L, 0xdbL,
    0x45b9L, 0xb6L, 0x80L);
  private static final Set<Long> SELECTED_ELEMENT_IDS =
    Set.of(TAGS, INFO, TRACKS, ATTACHMENTS, CHAPTERS, 0x1c53bb6bL);
  private static final Set<Long> TOP_LEVEL_METADATA_IDS =
    Set.of(TAGS, INFO, TRACKS, ATTACHMENTS, CHAPTERS, 0x1c53bb6bL, SEEKHEAD);
  private static final Set<Long> CURSOR_SCANNED_IDS = Set.of(TAGS, ATTACHMENTS, CHAPTERS);
  private static final Set<String> COMMON_PROPERTY_KEYS =
    Set.of("TITLE", "ARTIST", "ALBUM", "COMMENT", "GENRE", "DATE", "TRACKNUMBER");

  private static final class ElementReader {
    private final BinaryFile file;
    private int count;

    private ElementReader(BinaryFile file) {
      this.file = file;
    }

    private List<Element> children(Element parent, int depth, boolean retainPrefix)
      throws IOException {
      if (depth > 64) {
        throw new IOException("EBML maximum nesting depth exceeded");
      }
      var out = new ArrayList<Element>();
      long p = parent.data();
      while (p < parent.end()) {
        if (count >= 50_000 || out.size() >= 50_000) {
          throw new IOException("Too many EBML elements");
        }
        Element e;
        try {
          e = element(file, p, parent.end());
        } catch (IOException malformedHeader) {
          // MasterElement::read accepts a factory stop exactly at its end, including a
          // final invalid ID byte. Other stops seek to the master end and report failure.
          long cursor =
            file.stream() == null ? factoryHeaderCursor(file, p) : file.stream().tell();
          if (cursor == parent.end()) {
            break;
          }
          file.seekNative(parent.end());
          if (retainPrefix) {
            break;
          }
          throw malformedHeader;
        }
        count++;
        boolean readable = true;
        if (MASTERS.contains(e.id)) {
          try {
            children(e, depth + 1, false);
          } catch (IOException invalidNestedMaster) {
            readable = false;
          }
        } else if ((e.id == 0x4489 || e.id == 0xb5)
          && e.length != 0 && e.length != 4 && e.length != 8) {
          readable = false;
        }
        // MasterElement::read discards a child whose read fails, then continues with siblings.
        if (readable) {
          out.add(e);
        }
        p = e.end();
      }
      if (file.retainReadData()) {
        parent.parsedChildren = List.copyOf(out);
        parent.parsedCursor = file.stream() == null ? 0 : file.stream().tell();
      }
      return out;
    }
  }

  private static long factoryHeaderCursor(BinaryFile file, long offset) throws IOException {
    byte[] prefix = file.read(offset, (int) Math.min(12, file.size() - offset));
    if (prefix.length == 0) {
      return offset;
    }
    int idWidth = vintWidth(prefix[0] & 255, 4);
    if (idWidth == 0) {
      return offset + 1;
    }
    if (prefix.length <= idWidth) {
      return offset + prefix.length;
    }
    int sizeWidth = vintWidth(prefix[idWidth] & 255, 8);
    return offset + Math.min(prefix.length, idWidth + Math.max(1, sizeWidth));
  }

  private static int vintWidth(int first, int maximum) {
    if (first == 0) {
      return 0;
    }
    int width = Integer.numberOfLeadingZeros(first) - 23;
    return width <= maximum ? width : 0;
  }

  private static Segment segment(BinaryFile f) throws IOException {
    return segment(f, ReadOptions.DEFAULT);
  }

  private static Segment segment(BinaryFile f, ReadOptions options) throws IOException {
    return segment(f, options, 0);
  }

  private static Segment segment(BinaryFile f, ReadOptions options, long origin) throws IOException {
    return segment(f, options, origin, true);
  }

  private static Segment segment(BinaryFile f, ReadOptions options, long origin, boolean validate) throws IOException {
    if (origin < 0 || origin >= f.size()) {
      throw new InvalidAudioFileException("No EBML element at constructor cursor");
    }
    Element header = element(f, origin, f.size());
    if (header.id != 0x1a45dfa3L) {
      throw new InvalidAudioFileException("Not an EBML header at constructor cursor");
    }
    String docType = "";
    int docTypeVersion = 0;
    if (options.readAudioProperties()) {
      // Native ignores a failed header read but keeps its successfully parsed prefix.
      for (Element child : new ElementReader(f).children(header, 0, true)) {
        if (child.id == 0x4282) {
          docType = text(f, child, options);
        } else if (child.id == 0x4287) {
          docTypeVersion = (int) uint(f, child);
        }
      }
    }
    long p = header.end();
    long scanEnd = options.readStyle() == ReadStyle.FAST ? Math.min(f.size(), 512 * 1024) : f.size();
    while (p < scanEnd) {
      Element e = element(f, p, f.size());
      if (e.id == SEGMENT) {
        Segment segment = readSegment(f, e, options, validate);
        return new Segment(segment.element, segment.selected, segment.nativeCursor,
          segment.renderStatus, docType, docTypeVersion);
      }
      p = e.end();
    }
    throw new InvalidAudioFileException("No Matroska segment");
  }

  private static Segment readSegment(BinaryFile file, Element top, ReadOptions options, boolean validate) throws IOException {
    int scanned = 0;
    var selected = new LinkedHashMap<Long, Element>();
    long nativeCursor = top.data();
    long limit = options.readStyle() == ReadStyle.FAST ? Math.min(top.end(), top.data() + 512 * 1024) : top.end();
    boolean skipCues = limit < top.end() && file.stream() != null && file.stream().readOnly();
    for (long at = top.data(); at < limit; ) {
      Element element;
      try {
        element = element(file, at, top.end());
      } catch (IOException malformedHeader) {
        // MkSegment's sequential scan terminates when Element::factory returns null.
        // Existing parsed elements remain valid and the unparsed tail remains in the source.
        break;
      }
      nativeCursor = element.end();
      if (++scanned > 100_000) {
        throw new IOException("Too many EBML elements");
      }
      if (TOP_LEVEL_METADATA_IDS.contains(element.id)
        && !(skipCues && element.id == 0x1c53bb6bL)) {
        children(file, element); // A selected native MasterElement must be readable.
        selected.put(element.id, element);
      }
      at = element.end();
      if (element.id == SEEKHEAD) {
        nativeCursor = nextHeaderCursor(file, element.end(), top.end(), limit, false);
        var entries = new ArrayList<>(seekEntries(file, element));
        int chained = 0;
        for (int index = 0; index < entries.size(); index++) {
          if (entries.size() > 100_000) {
            throw new IOException("Too many EBML seek entries");
          }
          SeekEntry entry = entries.get(index);
          if (entry.id == SEEKHEAD) {
            if (chained++ >= 8) {
              continue;
            }
            Element secondary = referencedElement(file, top, entry, false);
            if (secondary != null) {
              entries.addAll(seekEntries(file, secondary));
              nativeCursor = secondary.end();
            }
          } else if (SELECTED_ELEMENT_IDS.contains(entry.id)) {
            if (skipCues && entry.id == 0x1c53bb6bL) {
              continue;
            }
            // A live native File retains its removed, empty Tag and can emit an EOF
            // seek entry on every later save. Only that constructor-owned state permits
            // rereading its exact EOF entry; a fresh AudioFile still rejects the bytes.
            if (entry.id == TAGS && NativeSaveState.retainedEmptyMatroskaTag()
              && entry.position == file.size() - top.data()) {
              continue;
            }
            Element referenced = referencedElement(file, top, entry, true);
            selected.put(entry.id, referenced);
            nativeCursor = CURSOR_SCANNED_IDS.contains(entry.id)
              ? nextHeaderCursor(file, referenced.end(), top.end(), top.end(), true) : referenced.end();
          }
        }
        // SeekHead is authoritative. Ordinary files need no second physical scan;
        // custom streams retain their established callback sequence.
        if (!file.retainReadData()) {
          for (long rest = at; rest < top.end(); ) {
            try {
              Element next = element(file, rest, top.end());
              if (++scanned > 100_000) throw new IOException("Too many EBML elements");
              rest = next.end();
            } catch (IOException malformedHeader) {
              break;
            }
          }
        }
        break;
      }
    }
    Segment segment = new Segment(top, List.copyOf(selected.values()), nativeCursor);
    if (validate && options.readStyle() == ReadStyle.ACCURATE) {
      file.seekNative(nativeCursor);
      validateIndices(file, segment);
      if (file.stream() != null) {
        segment = new Segment(top, segment.selected, file.stream().tell());
      }
    }
    return segment;
  }

  private static long nextHeaderCursor(
    BinaryFile file, long offset, long end, long limit,
    boolean accumulateVoids
  ) {
    long cursor = offset;
    for (int count = 0; cursor < limit && count < 100_000; count++) {
      Element next;
      try {
        next = element(file, cursor, end);
      } catch (IOException malformedHeader) {
        return file.stream() == null ? cursor : file.stream().tell();
      }
      cursor = next.data();
      if (!accumulateVoids || next.id != 0xec) {
        break;
      }
      cursor = next.end();
    }
    return cursor;
  }

  private static Element referencedElement(
    BinaryFile file, Element segment, SeekEntry entry,
    boolean required
  ) {
    Element result = null;
    if (entry.position >= 0 && entry.position < segment.length) {
      try {
        Element candidate = element(file, segment.data() + entry.position, segment.end());
        if (candidate.id == entry.id) {
          children(file, candidate);
          result = candidate;
        }
      } catch (IOException malformedReference) { /* Native readElementAt returns null. */ }
    }
    if (result == null && required) {
      throw new InvalidAudioFileException("Invalid Matroska seek reference");
    }
    return result;
  }

  private static List<SeekEntry> seekEntries(BinaryFile file, Element head) throws IOException {
    var result = new ArrayList<SeekEntry>();
    for (Element entry : children(file, head)) {
      if (entry.id == 0x4dbb) {
        long id = 0, position = -1;
        for (Element field : children(file, entry)) {
          if (field.id == 0x53ab && id == 0 && field.length == 4) {
            id = uint(file, field);
          } else if (field.id == 0x53ac && position < 0) {
            position = uint(file, field);
          }
        }
        if (id != 0 && position >= 0) {
          result.add(new SeekEntry(id, position, false));
        }
      }
    }
    return result;
  }

  private static void validateIndices(BinaryFile file, Segment segment) throws IOException {
    Element seek = last(segment, SEEKHEAD), cues = last(segment, 0x1c53bb6bL);
    if (seek != null) {
      long cursor = file.stream() == null ? -1 : file.stream().tell();
      List<SeekEntry> entries;
      try {
        entries = seekEntries(file, seek);
      } finally {
        if (cursor >= 0) {
          file.seekNative(cursor);
        }
      }
      for (SeekEntry entry : entries) {
        if (!pointsAt(file, segment.element, entry.position, entry.id)) {
          throw new InvalidAudioFileException("Invalid Matroska seek position");
        }
      }
    }
    if (cues != null) {
      // Native checks the already parsed index object. Only checking an actual target ID
      // reads the stream; decoding our cached element representation must not move it.
      record CuePosition(long number, long cluster, long codecState) {
      }
      var positions = new ArrayList<CuePosition>();
      long cursor = file.stream() == null ? -1 : file.stream().tell();
      try {
        for (Element point : children(file, cues)) {
          if (point.id == 0xbb) {
            for (Element track : children(file, point)) {
              if (track.id == 0xb7) {
                long number = 0, cluster = 0, codecState = 0;
                for (Element field : children(file, track)) {
                  if (field.id == 0xf7) {
                    number = uint(file, field);
                  } else if (field.id == 0xf1) {
                    cluster = uint(file, field);
                  } else if (field.id == 0xea) {
                    codecState = uint(file, field);
                  }
                }
                positions.add(new CuePosition(number, cluster, codecState));
              }
            }
          }
        }
      } finally {
        if (cursor >= 0) {
          file.seekNative(cursor);
        }
      }
      for (CuePosition position : positions) {
        if (position.number == 0 || position.cluster == 0
          || !pointsAt(file, segment.element, position.cluster, 0x1f43b675L)
          || position.codecState != 0 && !pointsAt(file, segment.element, position.codecState, 0xa4)) {
          throw new InvalidAudioFileException("Invalid Matroska cue position");
        }
      }
    }
  }

  private static boolean pointsAt(BinaryFile file, Element segment, long position, long id) {
    if (position < 0 || position >= segment.length) {
      return false;
    }
    try {
      return vint(file, segment.data() + position, true).value() == id;
    } catch (IOException malformed) {
      return false;
    }
  }

  /// Native-dispatched Tags presence, used by a live AudioFile's retained empty-Tag state.
  public static boolean hasTags(BinaryFile file) throws IOException {
    return last(segment(file), TAGS) != null;
  }

  /// The Info title remains the fallback after generic TITLE is cleared on a live file.
  public static String segmentTitle(BinaryFile file) throws IOException {
    Element info = last(segment(file), INFO);
    if (info != null) {
      for (Element field : children(file, info)) {
        if (field.id == 0x7ba9) {
          return text(file, field, ReadOptions.DEFAULT);
        }
      }
    }
    return "";
  }

  private static byte[] valueBytes(BinaryFile file, Element element, int count) throws IOException {
    if (file.retainReadData() && element.valueBytes != null && element.valueBytes.length >= count) {
      file.seekNative(element.data() + count);
      return element.valueBytes.length == count ? element.valueBytes : Arrays.copyOf(element.valueBytes, count);
    }
    byte[] bytes = file.read(element.data(), count);
    if (file.retainReadData()) element.valueBytes = bytes;
    return bytes;
  }

  private static byte[] data(BinaryFile f, Element e, ReadOptions options) throws IOException {
    if (e.length > options.maxMetadataBytes()) {
      throw new MetadataLimitException("EBML metadata exceeds allocation limit");
    }
    return valueBytes(f, e, (int) e.length);
  }

  private static long uint(BinaryFile f, Element e) throws IOException {
    long n = 0;
    for (byte b : valueBytes(f, e, (int) Math.min(e.length, 8))) {
      n = (n << 8) | (b & 255);
    }
    return n;
  }

  private static double number(BinaryFile f, Element e) throws IOException {
    if (e.length == 4) {
      return ByteBuffer.wrap(valueBytes(f, e, 4)).getFloat();
    }
    if (e.length == 8) {
      return ByteBuffer.wrap(valueBytes(f, e, 8)).getDouble();
    }
    return 0;
  }

  private static String text(BinaryFile f, Element e, ReadOptions options) throws IOException {
    // EBML ASCII/Latin-1 fields are distinct from UTF-8 names and titles upstream.
    var charset = e.id == 0x4282 || e.id == 0x447a || e.id == 0x4660 || e.id == 0x86
      || e.id == 0x437c ? ISO_8859_1 : UTF_8;
    byte[] bytes = data(f, e, options);
    int length = 0;
    while (length < bytes.length && bytes[length] != 0) {
      length++;
    }
    // EBML::StringElement truncates the raw bytes before TagLib's strict decoding.
    return TextBytes.decodeTerminated(bytes, 0, length, charset);
  }

  private static String key(String name, int target) {
    for (Mapping m : MAP) {
      if (m.name.equals(name) && (m.target == target || (target == 0 && !m.strict))) {
        return m.key;
      }
    }
    return target == 0 || target == 30 ? name : null;
  }

  @Override
  public AudioMetadata read(Path path, ReadOptions options) throws IOException {
    try (var f = new BinaryFile(path)) {
      return read(f, options);
    }
  }

  @Override
  public AudioMetadata read(BinaryFile f, ReadOptions options) throws IOException {
    return read(f, options, segment(f, options, 0));
  }

  @Override
  public AudioMetadata readNative(BinaryFile file, ReadOptions options) throws IOException {
    nativeOptions = options;
    nativeSegment = segment(file, options, file.initialPosition());
    AudioMetadata snapshot = read(file, options, nativeSegment);
    file.seekNative(nativeSegment.nativeCursor);
    return snapshot;
  }

  private AudioMetadata read(BinaryFile f, ReadOptions options, Segment segment) throws IOException {
    return read(f, options, segment, true);
  }

  private AudioMetadata read(BinaryFile f, ReadOptions options, Segment segment, boolean loadPictures) throws IOException {
    long budget = 0;
    for (Element e : segment.selected) {
      if (e.id == TAGS || e.id == INFO || e.id == TRACKS) {
        if (e.length > options.maxMetadataBytes() - budget) {
          throw new MetadataLimitException("Aggregate Matroska metadata exceeds allocation limit");
        }
        budget += e.length;
      }
    }
    var props = PropertyMap.builder();
    var common = PropertyMap.builder();
    var pictures = new ArrayList<Picture>();
    var attachments = attachmentIndex(f, segment, options);
    budget = metadataBudget(budget, attachments.metadataBytes, options);
    for (var attachment : attachments.files) {
      if (loadPictures && attachment.mediaType.startsWith("image/")) {
        budget = metadataBudget(budget, attachment.data.getDataSize(), options);
      }
    }
    for (var attachment : attachments.files) {
      if (loadPictures && attachment.mediaType.startsWith("image/")) {
        pictures.add(new Picture(0, attachment.mediaType, attachment.description,
          attachment.data.load(f, options.maxMetadataBytes())));
      }
    }

    nativeProperties = readDetails(f, options, segment, props, common);
    PropertyMap properties = props.build();
    Tag basic = new Tag(common.build(), Format.MATROSKA);
    if (basic.title().isEmpty()) {
      basic =
        new Tag(
          nativeProperties.title(),
          basic.artist(),
          basic.album(),
          basic.comment(),
          basic.genre(),
          basic.year(),
          basic.track());
    }
    return new AudioMetadata(
      Format.MATROSKA,
      properties,
      nativeProperties.common(),
      pictures,
      basic);
  }

  private static Details readDetails(BinaryFile f, ReadOptions options, Segment segment,
                                     PropertyMap.Builder properties, PropertyMap.Builder common) throws IOException {
    long length = 0;
    int rate = 0, channels = 0, bits = 0;
    boolean lossless = false;
    String segmentTitle = "", codecName = "";
    for (Element e : segment.selected) {
      if (e.id == TAGS && properties != null) {
        readTags(f, e, options, properties, common);
      } else if (e.id == INFO) {
        long scale = 1000000;
        double duration = 0;
        boolean titleSeen = false;
        for (Element child : children(f, e)) {
          if (child.id == 0x7ba9 && !titleSeen) {
            segmentTitle = text(f, child, options);
            titleSeen = true;
          }
          if (child.id == 0x2ad7b1 && (options.readAudioProperties() || !f.retainReadData())) {
            scale = uint(f, child);
          }
          if (child.id == 0x4489 && (options.readAudioProperties() || !f.retainReadData())) {
            duration = number(f, child);
          }
        }
        double unsignedScale = scale >= 0 ? scale : (double) (scale & Long.MAX_VALUE) + 0x1.0p63;
        double ms = duration * unsignedScale / 1000000.0;
        if (ms >= 0 && ms + 0.5 <= Integer.MAX_VALUE) {
          length = (long) ms;
        }
      } else if (options.readAudioProperties() && e.id == TRACKS) {
        for (Element track : children(f, e)) {
          if (track.id == 0xae) {
            String codec = "";
            double frequency = 0;
            long trackChannels = 0, trackBits = 0;
            for (Element child : children(f, track)) {
              if (child.id == 0x86) {
                codec = text(f, child, options);
              }
              if (child.id == 0xe1) {
                for (Element a : children(f, child)) {
                  if (a.id == 0xb5) {
                    frequency = number(f, a);
                  }
                  if (a.id == 0x9f) {
                    trackChannels = uint(f, a);
                  }
                  if (a.id == 0x6264) {
                    trackBits = uint(f, a);
                  }
                }
              }
            }
            if ((trackChannels != 0 || trackBits != 0) && frequency >= 0
              && frequency + 0.5 <= Integer.MAX_VALUE
              && Long.compareUnsigned(trackChannels, Integer.MAX_VALUE) <= 0
              && Long.compareUnsigned(trackBits, Integer.MAX_VALUE) <= 0) {
              rate = (int) frequency;
              channels = (int) trackChannels;
              bits = (int) trackBits;
              codecName = codec;
              lossless = codec.contains("FLAC") || codec.contains("PCM");
              break;
            }
          }
        }
      }
    }
    AudioProperties audio = options.readAudioProperties()
      ? new AudioProperties(length, length > 0 && f.size() <= Long.MAX_VALUE / 8
      ? (int) (f.size() * 8 / length) : 0, rate, channels, bits, lossless)
      : AudioProperties.EMPTY;
    return new Details(audio, segment.docType, segment.docTypeVersion, codecName, segmentTitle);
  }

  public static List<SimpleTag> readSimpleTags(Path path) throws IOException {
    try (var file = new BinaryFile(path)) {
      return readSimpleTags(file);
    }
  }

  public static List<SimpleTag> readSimpleTags(BinaryFile file) throws IOException {
    Element tags = last(segment(file), TAGS);
    return tags == null ? List.of() : List.copyOf(parseSimpleTags(file, tags, ReadOptions.DEFAULT));
  }

  private static List<SimpleTag> parseSimpleTags(
    BinaryFile file, Element tags, ReadOptions options) throws IOException {
    var result = new ArrayList<SimpleTag>();
    for (var tag : children(file, tags)) {
      if (tag.id == 0x7373) {
        var fields = children(file, tag);
        int target = 0;
        long track = 0, edition = 0, chapter = 0, attachment = 0;
        var targets = fields.stream().filter(e -> e.id == 0x63c0).findFirst().orElse(null);
        if (targets != null) {
          for (var field : children(file, targets)) {
            if (field.id == 0x68ca && target == 0) {
              target = (int) uint(file, field);
            } else if (field.id == 0x63c5) {
              track = uint(file, field);
            } else if (field.id == 0x63c9) {
              edition = uint(file, field);
            } else if (field.id == 0x63c4) {
              chapter = uint(file, field);
            } else if (field.id == 0x63c6) {
              attachment = uint(file, field);
            }
          }
        }
        for (var simple : fields) {
          if (simple.id == 0x67c8) {
            String name = "", language = "", value = null;
            byte[] binary = null;
            boolean defaultLanguage = true;
            for (var field : children(file, simple)) {
              if (field.id == 0x45a3 && name.isEmpty()) {
                name = text(file, field, options);
              } else if (field.id == 0x4487 && value == null) {
                value = text(file, field, options);
              } else if (field.id == 0x4485 && binary == null) {
                binary = data(file, field, options);
              } else if (field.id == 0x447a && language.isEmpty()) {
                language = text(file, field, options);
              } else if (field.id == 0x4484) {
                defaultLanguage = uint(file, field) != 0;
              }
            }
            if (name.isEmpty() || (value == null) == (binary == null)) {
              continue;
            }
            SimpleTag.Value parsed = value != null
              ? new SimpleTag.Text(value)
              : new SimpleTag.Binary(binary);
            result.add(new SimpleTag(name, parsed, target, language,
              defaultLanguage, track, edition, chapter, attachment));
            if (result.size() > 100_000) {
              throw new IOException("Too many Matroska tags");
            }
          }
        }
      }
    }
    return result;
  }

  private static void readTags(
    BinaryFile file, Element tags, ReadOptions options,
    PropertyMap.Builder properties, PropertyMap.Builder common
  ) throws IOException {
    for (var tag : parseSimpleTags(file, tags, options)) {
      if (!(tag.value() instanceof SimpleTag.Text(var text))
        || tag.trackUid() != 0 || tag.editionUid() != 0 || tag.chapterUid() != 0
        || tag.attachmentUid() != 0) {
        continue;
      }
      String key = key(tag.name(), tag.targetType());
      if (key == null || key.isEmpty()) {
        properties.addUnsupportedData(tag.name());
        continue;
      }
      properties.add(key, text);
      if (COMMON_PROPERTY_KEYS.contains(key)) {
        Mapping mapping = MAP.stream().filter(m -> m.key.equals(key)).findFirst()
          .orElse(new Mapping(key, key, 30, false));
        if (mapping.name.equals(tag.name()) && (mapping.target == tag.targetType()
          || (tag.targetType() == 0 && !mapping.strict))) {
          common.add(key, text);
        }
      }
    }
  }

  public static void writeSimpleTags(
    Path path, List<SimpleTag> tags
  ) throws IOException {
    writeMetadata(path, PropertyMap.EMPTY, null, tags, null, null);
  }

  private static final AtomicLong SYNTHETIC_CHAPTER_UID =
    new AtomicLong(1);

  public static List<ChapterEdition> readChapters(Path path) throws IOException {
    try (var file = new BinaryFile(path)) {
      return readChapters(file);
    }
  }

  public static List<ChapterEdition> readChapters(BinaryFile file) throws IOException {
    return readChapters(file, segment(file));
  }

  private static List<ChapterEdition> readChapters(BinaryFile file, Segment segment) throws IOException {
    var top = last(segment, CHAPTERS);
    if (top == null) {
      return List.of();
    }
    if (top.length > ReadOptions.DEFAULT.maxMetadataBytes()) {
      throw new IOException("Matroska chapters exceed metadata limit");
    }
    var editions = new ArrayList<ChapterEdition>();
    var orphans = new ArrayList<Chapter>();
    for (var entry : children(file, top)) {
      if (entry.id == 0xb6) {
        orphans.add(parseChapter(file, entry));
      } else if (entry.id == 0x45b9) {
        var chapters = new ArrayList<Chapter>();
        long uid = 0;
        boolean isDefault = false, ordered = false;
        for (var field : children(file, entry)) {
          if (field.id == 0x45bc) {
            uid = uint(file, field);
          } else if (field.id == 0x45db) {
            isDefault = uint(file, field) != 0;
          } else if (field.id == 0x45dd) {
            ordered = uint(file, field) != 0;
          } else if (field.id == 0xb6) {
            chapters.add(parseChapter(file, field));
          }
        }
        if (!chapters.isEmpty()) {
          editions.add(new ChapterEdition(chapters, isDefault, ordered, uid));
        }
      }
    }
    if (!orphans.isEmpty()) {
      editions.add(new ChapterEdition(orphans, true, false, 0));
    }
    return List.copyOf(editions);
  }

  private static Chapter parseChapter(BinaryFile file, Element chapter) throws IOException {
    long uid = 0, start = 0, end = 0;
    boolean hidden = false;
    var displays = new ArrayList<Chapter.Display>();
    for (var field : children(file, chapter)) {
      if (field.id == 0x73c4) {
        uid = uint(file, field);
      } else if (field.id == 0x91) {
        start = uint(file, field);
      } else if (field.id == 0x92) {
        end = uint(file, field);
      } else if (field.id == 0x98) {
        hidden = uint(file, field) != 0;
      } else if (field.id == 0x80) {
        String name = "", language = "";
        for (var value : children(file, field)) {
          if (value.id == 0x85) {
            name = text(file, value, ReadOptions.DEFAULT);
          } else if (value.id == 0x437c) {
            language = text(file, value, ReadOptions.DEFAULT);
          }
        }
        if (!name.isEmpty()) {
          displays.add(new Chapter.Display(name, language));
        }
      }
    }
    if (uid == 0) {
      uid = Long.MIN_VALUE | SYNTHETIC_CHAPTER_UID.getAndIncrement();
    }
    return new Chapter(start, end, displays, uid, hidden);
  }

  public static void writeChapters(
    Path path, List<ChapterEdition> chapters
  ) throws IOException {
    writeMetadata(path, PropertyMap.EMPTY, null, null, chapters, null);
  }

  private static byte[] renderChapters(List<ChapterEdition> editions) throws IOException {
    var result = new MatroskaOutput();
    for (var edition : editions) {
      var body = new MatroskaOutput();
      if (edition.uid() != 0) {
        body.element(0x45bc, unsigned(edition.uid()));
      }
      body.element(0x45db, unsigned(edition.isDefault() ? 1 : 0));
      body.element(0x45dd, unsigned(edition.isOrdered() ? 1 : 0));
      for (var chapter : edition.chapterList()) {
        var fields = new MatroskaOutput();
        long uid = chapter.uid();
        if (uid == 0) {
          do {
            uid = ThreadLocalRandom.current().nextLong();
          } while (uid == 0);
        }
        fields.element(0x73c4, unsigned(uid));
        fields.element(0x91, unsigned(chapter.timeStart()));
        fields.element(0x92, unsigned(chapter.timeEnd()));
        fields.element(0x98, unsigned(chapter.isHidden() ? 1 : 0));
        for (var display : chapter.displayList()) {
          fields.element(0x80, new MatroskaOutput().element(0x85, display.string().getBytes(StandardCharsets.UTF_8))
            .element(0x437c, display.language().getBytes(StandardCharsets.ISO_8859_1)));
          checkRenderSize(fields);
        }
        body.element(0xb6, fields);
        checkRenderSize(body);
      }
      result.element(0x45b9, body);
      checkRenderSize(result);
    }
    return result.toByteArray();
  }


  private static void checkRenderSize(MatroskaOutput bytes) throws IOException {
    if (bytes.size() > ReadOptions.DEFAULT.maxMetadataBytes())
      throw new IOException("Matroska metadata exceeds allocation limit");
  }

  private record Scope(int target, long track, long edition, long chapter, long attachment) {
  }

  private static byte[] renderSimpleTags(List<SimpleTag> tags) throws IOException {
    var groups = new LinkedHashMap<Scope, List<SimpleTag>>();
    for (var tag : tags) {
      var scope = new Scope(tag.targetType(), tag.trackUid(), tag.editionUid(), tag.chapterUid(), tag.attachmentUid());
      groups.computeIfAbsent(scope, _ -> new ArrayList<>()).add(tag);
    }
    var all = new MatroskaOutput();
    for (var entry : groups.entrySet()) {
      var scope = entry.getKey();
      var targets = new MatroskaOutput();
      writeUnsigned(targets, 0x68ca, Integer.toUnsignedLong(scope.target));
      writeUnsigned(targets, 0x63c5, scope.track);
      writeUnsigned(targets, 0x63c9, scope.edition);
      writeUnsigned(targets, 0x63c4, scope.chapter);
      writeUnsigned(targets, 0x63c6, scope.attachment);
      var body = new MatroskaOutput();
      body.element(0x63c0, targets);
      for (var tag : entry.getValue()) {
        var fields = new MatroskaOutput().element(0x45a3, tag.name().getBytes(StandardCharsets.UTF_8));
        switch (tag.value()) {
          case SimpleTag.Text(var text) -> fields.element(0x4487, text.getBytes(StandardCharsets.UTF_8));
          case SimpleTag.Binary binary -> fields.element(0x4485, binary.value());
        }
        fields.element(0x447a, (tag.language().isEmpty() ? "und" : tag.language()).getBytes(StandardCharsets.ISO_8859_1))
          .element(0x4484, new byte[]{(byte) (tag.defaultLanguage() ? 1 : 0)});
        body.element(0x67c8, fields);
        if (body.size() > ReadOptions.DEFAULT.maxMetadataBytes()) {
          throw new IOException("Matroska tags exceed metadata limit");
        }
      }
      all.element(0x7373, body);
      if (all.size() > ReadOptions.DEFAULT.maxMetadataBytes()) {
        throw new IOException("Matroska tags exceed metadata limit");
      }
    }
    return all.toByteArray();
  }

  private static void writeUnsigned(MatroskaOutput output, long id, long value) {
    if (value != 0) output.element(id, unsigned(value));
  }


  /// Internal deferred attachment index; no payload bytes are read while constructing it.
  public record AttachmentData(String fileName, String mediaType, String description, long uid,
                               DeferredBinaryElement data) {
  }

  public record AttachmentIndex(boolean present, List<AttachmentData> files, long metadataBytes) {
    public AttachmentIndex {
      files = List.copyOf(files);
    }
  }

  public record NativeMetadata(List<SimpleTag> simpleTags,
                               List<ChapterEdition> chapters, AttachmentIndex attachments,
                               String segmentTitle, boolean hasTags) {
    public NativeMetadata {
      simpleTags = List.copyOf(simpleTags);
      chapters = List.copyOf(chapters);
    }
  }

  /// Constructor-selected metadata, including Fast scan limits and a borrowed stream origin.
  public NativeMetadata nativeMetadata(BinaryFile file) throws IOException {
    Segment selected = nativeSegment != null ? nativeSegment : segment(file);
    ReadOptions options = nativeOptions != null ? nativeOptions : ReadOptions.DEFAULT;
    Element tags = last(selected, TAGS), info = last(selected, INFO);
    String title = "";
    if (info != null) {
      for (Element field : children(file, info)) {
        if (field.id == 0x7ba9) {
          title = text(file, field, options);
          break;
        }
      }
    }
    return new NativeMetadata(tags == null ? List.of() : parseSimpleTags(file, tags, options),
      readChapters(file, selected), attachmentIndex(file, selected, options), title, tags != null);
  }

  public static AttachmentIndex readAttachmentIndex(BinaryFile file, ReadOptions options)
    throws IOException {
    return attachmentIndex(file, segment(file, options), options);
  }

  private static AttachmentIndex attachmentIndex(BinaryFile file, Segment segment, ReadOptions options)
    throws IOException {
    // MkSegment retains the last Attachments element encountered during its sequential scan.
    Element top = last(segment, ATTACHMENTS);
    if (top == null) {
      return new AttachmentIndex(false, List.of(), 0);
    }
    var files = new ArrayList<AttachmentData>();
    long budget = 0;
    for (var attached : children(file, top)) {
      if (attached.id == 0x61a7) {
        String name = null, mime = "", description = "";
        long uid = 0;
        DeferredBinaryElement payload = null;
        for (var field : children(file, attached)) {
          if (field.id == 0x465c) {
            payload = new DeferredBinaryElement(field.data(), field.length);
          } else if (field.id == 0x466e || field.id == 0x4660 || field.id == 0x467e || field.id == 0x46ae) {
            budget = metadataBudget(budget, field.length, options);
            if (field.id == 0x466e) {
              name = text(file, field, options);
            } else if (field.id == 0x4660) {
              mime = text(file, field, options);
            } else if (field.id == 0x467e) {
              description = text(file, field, options);
            } else {
              uid = uint(file, field);
            }
          }
        }
        // Filename presence and FileData presence are mandatory upstream; empty values are valid.
        if (name != null && payload != null) {
          files.add(new AttachmentData(name, mime, description, uid, payload));
        }
      }
    }
    return new AttachmentIndex(true, files, budget);
  }

  private static long metadataBudget(long used, long addition, ReadOptions options) {
    if (addition > options.maxMetadataBytes() - used) {
      throw new MetadataLimitException("Aggregate Matroska metadata exceeds allocation limit");
    }
    return used + addition;
  }

  public static List<AttachedFile> loadAttachments(
    BinaryFile file,
    AttachmentIndex index, ReadOptions options
  ) throws IOException {
    long budget = index.metadataBytes;
    // Check the complete allocation before materializing any of the deferred files.
    for (var attachment : index.files) {
      budget = metadataBudget(budget, attachment.data.getDataSize(), options);
    }
    var result = new ArrayList<AttachedFile>();
    for (var attachment : index.files) {
      result.add(new AttachedFile(
        attachment.data.load(file, options.maxMetadataBytes()), attachment.fileName,
        attachment.mediaType, attachment.uid, attachment.description));
    }
    return List.copyOf(result);
  }

  public static void writeAttachments(
    Path path, List<AttachedFile> files
  ) throws IOException {
    writeMetadata(path, PropertyMap.EMPTY, null, null, null, List.copyOf(files));
  }

  private static byte[] renderAttachments(List<AttachedFile> files)
    throws IOException {
    if (files.isEmpty()) {
      return new byte[0];
    }
    var output = new MatroskaOutput();
    for (var file : files) {
      byte[] payload = file.data();
      byte[] name = file.fileName().getBytes(StandardCharsets.UTF_8);
      byte[] mime = file.mediaType().getBytes(StandardCharsets.ISO_8859_1);
      byte[] description = file.description().getBytes(StandardCharsets.UTF_8);
      long required = (long) payload.length + name.length + mime.length + description.length + 64;
      if (required > ReadOptions.DEFAULT.maxMetadataBytes() - output.size()) {
        throw new IOException("Matroska attachments exceed metadata limit");
      }
      long uid = file.uid();
      // EBML::randomUID spans the complete unsigned uint64 range. UIntElement renders
      // the resulting bit pattern at its minimum width, just like supplied UIDs.
      if (uid == 0) {
        uid = ThreadLocalRandom.current().nextLong();
      }
      var body = new MatroskaOutput();
      body.element(0x466e, name);
      body.element(0x4660, mime);
      if (description.length != 0) {
        body.element(0x467e, description);
      }
      body.element(0x465c, payload);
      body.element(0x46ae, unsigned(uid));
      output.element(0x61a7, body);
      checkRenderSize(output);
    }
    return new MatroskaOutput().element(ATTACHMENTS, output).toByteArray();
  }

  /// Retained native element graph used by the actual Matroska MatroskaFile owner.
  public static final class LiveState {
    private final NativeMetadata metadata;
    private final Details details;
    private final long metadataBytes;
    private final int renderLimit;

    public enum ElementKind {
      CHAPTER(CHAPTERS), ATTACHMENT(ATTACHMENTS), TAG(TAGS);

      final long id;

      ElementKind(long id) {
        this.id = id;
      }
    }

    private static final ElementKind[] KINDS = ElementKind.values();
    private final EnumMap<ElementKind, LiveElement> dataElements = new EnumMap<>(ElementKind.class);
    private final LiveSegment segment;
    private final LiveSeek seek;
    private final LiveCues cues;
    private List<ChapterEdition> editions;
    private List<AttachedFile> files = List.of();
    private List<SimpleTag> tags;

    private LiveState(BinaryFile source, Segment original, NativeMetadata metadata, Details details, int renderLimit)
      throws IOException {
      this.metadata = metadata;
      this.details = details;
      this.renderLimit = renderLimit;
      long count = metadata.attachments().metadataBytes();
      for (var item : original.selected) {
        if (item.id != ATTACHMENTS) {
          count = Math.addExact(count, item.length);
        }
      }
      metadataBytes = count;
      tags = metadata.simpleTags();
      editions = metadata.chapters();
      segment = new LiveSegment(original.element);
      for (var kind : KINDS) {
        Element old = last(original, kind.id);
        if (old != null) {
          var element = new LiveElement(kind);
          element.setOffset(old.offset);
          element.setSize(paddedSpan(source, original, old));
          dataElements.put(kind, element);
        }
      }
      Element oldSeek = last(original, SEEKHEAD), oldCues = last(original, 0x1c53bb6bL);
      seek = oldSeek == null ? null : new LiveSeek(source, original, oldSeek, renderLimit);
      cues = oldCues == null ? null : new LiveCues(source, original, oldCues, renderLimit);
    }

    public NativeMetadata metadata() {
      return metadata;
    }

    public Details details() {
      return details;
    }

    public long metadataBytes() {
      return metadataBytes;
    }

    public boolean has(ElementKind kind) {
      return dataElements.containsKey(kind);
    }

    public void mark(ElementKind kind) {
      ensure(kind);
      dataElements.get(kind).setNeedsRender(true);
    }

    public void ensure(ElementKind kind) {
      dataElements.computeIfAbsent(kind, LiveElement::new);
    }

    public boolean discarded(ElementKind kind) {
      return !dataElements.containsKey(kind);
    }

    public boolean save(
      IOStream owner, List<ChapterEdition> editions,
      List<AttachedFile> files, List<SimpleTag> tags,
      WriteStyle style, Consumer<List<cz.skyba.taglib.matroska.Element>> validate
    ) {
      this.editions = editions == null ? List.of() : editions;
      this.files = files == null ? List.of() : files;
      this.tags = tags == null ? List.of() : tags;
      var emptyKinds = EnumSet.noneOf(ElementKind.class);
      for (var kind : KINDS) {
        if (empty(kind)) {
          emptyKinds.add(kind);
        }
      }
      var list = new ArrayList<cz.skyba.taglib.matroska.Element>();
      var fresh = new ArrayList<cz.skyba.taglib.matroska.Element>();
      for (var kind : KINDS) {
        var element = dataElements.get(kind);
        if (element == null) {
          continue;
        }
        if (emptyKinds.contains(kind) && element.size() == 0 && element.offset() == 0 && element.dataSize() == 0) {
          dataElements.remove(kind);
          continue;
        }
        if (element.size() != 0) {
          list.add(element);
        } else {
          element.setOffset(owner.length());
          fresh.add(element);
        }
      }
      if (list.isEmpty() && fresh.isEmpty()) {
        return true;
      }
      list.sort(Comparator.comparingLong(cz.skyba.taglib.matroska.Element::offset));
      list.addAll(fresh);
      if (style != WriteStyle.Compact) {
        cz.skyba.taglib.matroska.Element lastData = null;
        for (var element : list) {
          if (element.size() > 0) {
            lastData = element;
          }
        }
        long boundary = 0;
        if (style == WriteStyle.AvoidInsert && cues != null) {
          for (var point : cues.points) {
            for (var track : point.tracks) {
              boundary = Math.max(boundary, Math.addExact(segment.dataOffset(), track.values.getOrDefault(0xf1L, 0L)));
            }
          }
          if (boundary == 0) {
            boundary = cues.offset();
          }
        }
        for (var element : list) {
          if (element.size() > 0) {
            element.setWriteStyle(style);
            element.setIsLastElement(element == lastData && element.offset() >= boundary);
          }
        }
        if (style == WriteStyle.AvoidInsert) {
          cz.skyba.taglib.matroska.Element trailing = null;
          long maximum = 0;
          var candidates = new ArrayList<>(list);
          if (cues != null) {
            candidates.add(cues);
          }
          if (seek != null) {
            candidates.add(seek);
          }
          for (var element : candidates) {
            if (element.size() > 0 && element.offset() > maximum) {
              trailing = element;
              maximum = element.offset();
            }
          }
          if (trailing != null) {
            trailing.setIsTrailingInSegment(true);
          }
        }
      }
      if (seek != null) {
        for (var element : fresh) {
          seek.add(element.id(), element.offset() - segment.dataOffset());
        }
        seek.entries.sort(Comparator.comparingLong(e -> e.position));
      }
      for (int i = 0; i < list.size(); i++) {
        var element = list.get(i);
        element.clearSizeListeners();
        for (int j = i + 1; j < list.size(); j++) {
          element.addSizeListener(list.get(j));
        }
        if (cues != null) {
          element.addSizeListener(cues);
        }
        if (seek != null) {
          element.addSizeListener(seek);
        }
        element.addSizeListener(segment);
      }
      if (cues != null) {
        list.add(cues);
        cues.clearSizeListeners();
        cues.addSizeListeners(list);
        if (seek != null) {
          cues.addSizeListener(seek);
        }
        cues.addSizeListener(segment);
      }
      if (seek != null) {
        list.add(seek);
        seek.clearSizeListeners();
        seek.addSizeListeners(list);
        seek.addSizeListener(segment);
      }
      segment.clearSizeListeners();
      segment.addSizeListeners(list);
      list.add(segment);
      for (int round = 0; round < 5; round++) {
        boolean rendering = false;
        if (style == WriteStyle.AvoidInsert) {
          for (var element : list) {
            element.setAppendOffset(segment.endOffset());
          }
        }
        for (var element : list) {
          if (element.needsRender()) {
            rendering = true;
            if (!element.render()) {
              return false;
            }
          }
        }
        if (!rendering) {
          break;
        }
      }
      if (style == WriteStyle.AvoidInsert) {
        var moved = list.stream().filter(cz.skyba.taglib.matroska.Element::wasMoved).toList();
        long total = 0;
        for (var element : moved) {
          total = Math.addExact(total, element.dataSize());
        }
        long appendAt = Math.subtractExact(segment.endOffset(), total);
        for (var element : moved) {
          element.setOffset(appendAt);
          appendAt = Math.addExact(appendAt, element.dataSize());
        }
        if (seek != null) {
          for (var element : moved) {
            for (var entry : seek.entries) {
              if (entry.id == element.id()) {
                entry.position = element.offset() - segment.dataOffset();
                seek.setNeedsRender(true);
                break;
              }
            }
          }
          seek.setNeedsRender(true);
          for (var element : list) {
            if (element.needsRender() && !element.render()) {
              return false;
            }
          }
        }
      }
      list.sort(Comparator.comparingLong(cz.skyba.taglib.matroska.Element::offset));
      validate.accept(list.stream()
        .filter(element -> !(element instanceof LiveSeek || element instanceof LiveCues) || element.dataSize() != 0)
        .toList());
      for (var element : list) {
        element.write(owner);
      }
      return true;
    }

    private boolean empty(ElementKind kind) {
      return switch (kind) {
        case CHAPTER -> editions.isEmpty();
        case ATTACHMENT -> files.isEmpty();
        case TAG -> tags.isEmpty();
      };
    }

    private final class LiveElement extends cz.skyba.taglib.matroska.Element {
      private final ElementKind kind;

      LiveElement(ElementKind kind) {
        super(kind.id);
        this.kind = kind;
      }

      @Override
      protected ByteVector renderInternal() {
        try {
          if (writeStyle() != WriteStyle.Compact && !(
            writeStyle() == WriteStyle.AvoidInsert && isTrailingInSegment())
            && sizeRenderedOrWritten() > renderLimit) {
            throw new IllegalArgumentException("Matroska retained padding exceeds allocation limit");
          }
          byte[] bytes = switch (kind) {
            case CHAPTER -> editions.isEmpty() ? new byte[0] : MatroskaCodec.render(CHAPTERS, renderChapters(editions));
            case ATTACHMENT -> renderAttachments(files);
            case TAG -> tags.isEmpty() ? new byte[0] : MatroskaCodec.render(TAGS, renderSimpleTags(tags));
          };
          if (bytes.length != 0 && writeStyle() != WriteStyle.Compact && !(
            writeStyle() == WriteStyle.AvoidInsert && isTrailingInSegment())) {
            bytes = padCompact(bytes, sizeRenderedOrWritten());
          }
          return new ByteVector(bytes);
        } catch (IOException failure) {
          throw new UncheckedIOException(failure);
        }
      }
    }
  }

  private static final class LiveSegment extends cz.skyba.taglib.matroska.Element {
    private int width;
    private long dataSize;

    LiveSegment(Element source) {
      super(SEGMENT);
      width = source.sizeWidth;
      dataSize = source.length;
      setOffset(source.data() - width);
      setSize(width);
    }

    long dataOffset() {
      return Math.addExact(offset(), width);
    }

    long endOffset() {
      return Math.addExact(dataOffset(), dataSize);
    }

    @Override
    protected ByteVector renderInternal() {
      return new ByteVector(MatroskaWire.size(dataSize, width));
    }

    @Override
    public boolean render() {
      int before = width;
      var data = renderInternal();
      setNeedsRender(false);
      if (data.size() != before) {
        width = 8;
        data = renderInternal();
        setNeedsRender(false);
        if (!emitSizeChanged(data.size() - before)) {
          return false;
        }
      }
      setData(data);
      return true;
    }

    @Override
    public boolean sizeChanged(cz.skyba.taglib.matroska.Element caller, long delta) {
      dataSize = Math.addExact(dataSize, delta);
      setNeedsRender(true);
      return true;
    }
  }

  private static final class MutableSeek {
    final long id;
    long position;

    MutableSeek(long id, long position) {
      this.id = id;
      this.position = position;
    }
  }

  private static final class LiveSeek extends cz.skyba.taglib.matroska.Element {
    final long segmentDataOffset;
    final int renderLimit;
    final ArrayList<MutableSeek> entries = new ArrayList<>();

    LiveSeek(BinaryFile file, Segment source, Element old, int renderLimit) throws IOException {
      super(SEEKHEAD);
      this.renderLimit = renderLimit;
      segmentDataOffset = source.element.data();
      setOffset(old.offset);
      setSize(paddedSpan(file, source, old));
      setNeedsRender(false);
      for (var entry : seekEntries(file, old)) {
        add(entry.id, entry.position);
      }
    }

    void add(long id, long position) {
      entries.add(new MutableSeek(id, position));
      setNeedsRender(true);
    }

    @Override
    protected ByteVector renderInternal() {
      if (sizeRenderedOrWritten() > renderLimit) {
        throw new IllegalArgumentException("Matroska seek padding exceeds allocation limit");
      }
      var body = new ByteArrayOutputStream();
      for (var entry : entries) {
        body.writeBytes(MatroskaWire.render(0x4dbb,
          MatroskaWire.join(MatroskaWire.render(0x53ab, ByteVector.fromUnsignedInt(entry.id).toByteArray()),
            MatroskaWire.render(0x53ac, MatroskaWire.uint(entry.position)))));
      }
      return new ByteVector(
        MatroskaWire.pad(MatroskaWire.render(SEEKHEAD, body.toByteArray()), sizeRenderedOrWritten()));
    }

    @Override
    public void write(IOStream file) {
      if (dataSize() != 0) {
        super.write(file);
      }
    }

    @Override
    public boolean sizeChanged(cz.skyba.taglib.matroska.Element caller, long delta) {
      if (caller.id() == SEGMENT) {
        adjustOffset(delta);
        return true;
      }
      long position = caller.offset() - segmentDataOffset;
      for (var entry : entries) {
        if (entry.position >= position && entry.id != caller.id()) {
          entry.position = Math.addExact(entry.position, delta);
          setNeedsRender(true);
        }
      }
      if (caller.dataSize() == 0 && Math.addExact(caller.size(), delta) == 0) {
        for (int i = 0; i < entries.size(); i++) {
          if (entries.get(i).id == caller.id()) {
            entries.remove(i);
            break;
          }
        }
      }
      return true;
    }
  }

  private static final class CueData {
    final LinkedHashMap<Long, Long> values = new LinkedHashMap<>();
    final ArrayList<Long> refs = new ArrayList<>();
  }

  private static final class CuePointData {
    long time;
    final ArrayList<CueData> tracks = new ArrayList<>();
  }

  private static final class LiveCues extends cz.skyba.taglib.matroska.Element {
    final long segmentDataOffset;
    final int renderLimit;
    final ArrayList<CuePointData> points = new ArrayList<>();

    LiveCues(BinaryFile file, Segment source, Element old, int renderLimit) throws IOException {
      super(old.id);
      this.renderLimit = renderLimit;
      segmentDataOffset = source.element.data();
      setOffset(old.offset);
      setSize(old.total());
      setNeedsRender(false);
      for (var point : children(file, old)) {
        if (point.id == 0xbb) {
          var p = new CuePointData();
          for (var field : children(file, point)) {
            if (field.id == 0xb3) {
              p.time = uint(file, field);
            } else if (field.id == 0xb7) {
              var track = new CueData();
              for (var item : children(file, field)) {
                if (isCueTrackField(item.id)) {
                  track.values.put(item.id, uint(file, item));
                } else if (item.id == 0xdb) {
                  for (var reference : children(file, item)) {
                    if (reference.id == 0x96) {
                      track.refs.add(uint(file, reference));
                    }
                  }
                }
              }
              p.tracks.add(track);
            }
          }
          points.add(p);
        }
      }
    }

    @Override
    protected ByteVector renderInternal() {
      if (sizeRenderedOrWritten() > renderLimit) {
        throw new IllegalArgumentException("Matroska cue padding exceeds allocation limit");
      }
      var body = new ByteArrayOutputStream();
      for (var point : points) {
        var fields = new ByteArrayOutputStream();
        fields.writeBytes(MatroskaWire.render(0xb3, MatroskaWire.uint(point.time)));
        for (var track : point.tracks) {
          var values = new ByteArrayOutputStream();
          for (long id : CUE_TRACK_FIELDS) {
            if (track.values.containsKey(id) || id == 0xf7 || id == 0xf1) {
              values.writeBytes(MatroskaWire.render(id, MatroskaWire.uint(track.values.getOrDefault(id, 0L))));
            }
          }
          if (!track.refs.isEmpty()) {
            var refs = new ByteArrayOutputStream();
            for (long time : track.refs) {
              refs.writeBytes(MatroskaWire.render(0x96, MatroskaWire.uint(time)));
            }
            values.writeBytes(MatroskaWire.render(0xdb, refs.toByteArray()));
          }
          fields.writeBytes(MatroskaWire.render(0xb7, values.toByteArray()));
        }
        body.writeBytes(MatroskaWire.render(0xbb, fields.toByteArray()));
      }
      return new ByteVector(
        MatroskaWire.pad(MatroskaWire.render(id(), body.toByteArray()), sizeRenderedOrWritten()));
    }

    @Override
    public void write(IOStream file) {
      if (dataSize() != 0) {
        super.write(file);
      }
    }

    @Override
    public boolean sizeChanged(cz.skyba.taglib.matroska.Element caller, long delta) {
      if (!super.sizeChanged(caller, delta)) {
        return false;
      }
      long position = caller.offset() - segmentDataOffset;
      for (var point : points) {
        for (var track : point.tracks) {
          for (long id : CUE_POSITION_FIELDS) {
            long value = track.values.getOrDefault(id, 0L);
            if (value > position && (id == 0xf1 || value != 0)) {
              track.values.put(id, Math.addExact(value, delta));
              setNeedsRender(true);
            }
          }
        }
      }
      return true;
    }
  }

  /// Read native selected state without materializing any attachment payload, including images.
  public LiveState readLive(BinaryFile source, ReadOptions options) throws IOException {
    nativeOptions = options;
    nativeSegment = segment(source, options, source.initialPosition(), false);
    long budget = 0;
    for (var item : nativeSegment.selected) {
      if (item.id != ATTACHMENTS) {
        budget = metadataBudget(budget, item.length, options);
      }
    }
    if (source.retainReadData()) {
      nativeProperties = readDetails(source, options, nativeSegment, null, null);
    } else {
      // Borrowed and overridden I/O retain the established read/seek callback sequence.
      read(source, options, nativeSegment, false);
    }
    var metadata = nativeMetadata(source);
    metadataBudget(budget, metadata.attachments().metadataBytes(), options);
    var result = new LiveState(source, nativeSegment, metadata, nativeProperties, options.maxMetadataBytes());
    source.seekNative(nativeSegment.nativeCursor);
    if (options.readStyle() == ReadStyle.ACCURATE) {
      validateIndices(source, nativeSegment);
    }
    return result;
  }

  @Override
  public void write(Path path, PropertyMap properties) throws IOException {
    write(path, properties, null);
  }

  @Override
  public void write(Path path, PropertyMap properties, List<Picture> pictures)
    throws IOException {
    writeMetadata(path, properties, pictures, null, null, null);
  }

  private static void writeMetadata(
    Path path, PropertyMap properties, List<Picture> pictures,
    List<SimpleTag> typedTags,
    List<ChapterEdition> editions,
    List<AttachedFile> attachedFiles
  ) throws IOException {
    try (var f = new BinaryFile(path)) {
      writeMetadata(f, properties, pictures, typedTags, editions, attachedFiles);
    }
  }

  @Override
  public void write(BinaryFile file, PropertyMap properties, List<Picture> pictures) throws IOException {
    writeMetadata(file, properties, pictures, null, null, null);
  }

  @Override
  public void writeNative(
    BinaryFile file, long constructorOffset, PropertyMap properties,
    List<Picture> pictures
  ) throws IOException {
    Segment selected = nativeSegment != null ? nativeSegment : segment(file, ReadOptions.DEFAULT, constructorOffset);
    nativeSegment = writeMetadata(file, properties, pictures, null, null, null, selected,
      selected.renderStatus.cuesRendered, selected.renderStatus.seekRendered);
  }

  private static void writeMetadata(
    BinaryFile f, PropertyMap properties, List<Picture> pictures,
    List<SimpleTag> typedTags,
    List<ChapterEdition> editions,
    List<AttachedFile> attachedFiles
  ) throws IOException {
    if (typedTags == null && NativeSaveState.matroskaSimpleTags() != null) {
      typedTags = NativeSaveState.matroskaSimpleTags();
    }
    writeMetadata(f, properties, pictures, typedTags, editions, attachedFiles, segment(f), false, false);
  }

  private static Segment writeMetadata(
    BinaryFile f, PropertyMap properties, List<Picture> pictures,
    List<SimpleTag> typedTags,
    List<ChapterEdition> editions,
    List<AttachedFile> attachedFiles, Segment segment,
    boolean previousCuesRendered, boolean previousSeekRendered
  ) throws IOException {
    if (typedTags == null && NativeSaveState.matroskaSimpleTags() != null) {
      typedTags = NativeSaveState.matroskaSimpleTags();
    }
    if (editions == null && NativeSaveState.matroskaChapters() != null) {
      editions = NativeSaveState.matroskaChapters();
    }
    if (attachedFiles == null && NativeSaveState.matroskaAttachments() != null) {
      attachedFiles = NativeSaveState.matroskaAttachments();
    }
    Element oldTags = last(segment, TAGS), oldAttachments = last(segment, ATTACHMENTS);
    Element oldChapters = last(segment, CHAPTERS), oldSeek = last(segment, SEEKHEAD);
    Element oldCues = last(segment, 0x1c53bb6bL);
    var tags = new ArrayList<SimpleTag>();
    if (typedTags != null) {
      tags.addAll(typedTags);
    } else if (oldTags != null) {
      for (var tag : parseSimpleTags(f, oldTags, ReadOptions.DEFAULT)) {
        if (UnsupportedDataRemoval.contains(tag.name())) {
          continue;
        }
        String propertyKey = key(tag.name(), tag.targetType());
        boolean exposed = tag.value() instanceof SimpleTag.Text
          && tag.trackUid() == 0 && tag.editionUid() == 0 && tag.chapterUid() == 0
          && tag.attachmentUid() == 0 && propertyKey != null && !propertyKey.isEmpty();
        if (editions != null || attachedFiles != null || !exposed) {
          tags.add(tag);
        }
      }
    }
    if (typedTags == null && editions == null && attachedFiles == null) {
      for (var entry : properties.asMap().entrySet()) {
        if (entry.getKey().isEmpty()) {
          continue;
        }
        Mapping mapping = MAP.stream().filter(v -> v.key.equals(entry.getKey())).findFirst()
          .orElse(new Mapping(entry.getKey(), entry.getKey(), 30, false));
        for (String value : entry.getValue()) {
          tags.add(new SimpleTag(
            mapping.name, new SimpleTag.Text(value), mapping.target,
            "und", true, 0, 0, 0, 0));
        }
      }
    }
    var attachments = new ArrayList<AttachedFile>();
    if (attachedFiles != null) {
      attachments.addAll(attachedFiles);
    } else {
      attachments.addAll(loadAttachments(f, attachmentIndex(f, segment, ReadOptions.DEFAULT), ReadOptions.DEFAULT));
      List<AttachedFile> stagedPictures = NativeSaveState.matroskaPictures();
      if (stagedPictures != null) {
        attachments.removeIf(attachment -> attachment.mediaType().startsWith("image/"));
        attachments.addAll(stagedPictures);
      } else if (pictures != null) {
        attachments.removeIf(attachment -> attachment.mediaType().startsWith("image/"));
        for (Picture picture : pictures) {
          String mime = picture.mimeType().startsWith("image/") ? picture.mimeType() : "image/jpeg";
          String extension = mime.substring(mime.lastIndexOf('/') + 1);
          if (extension.equals("jpeg")) {
            extension = "jpg";
          }
          attachments.add(new AttachedFile(picture.data(), "attachment." + extension,
            mime, ThreadLocalRandom.current().nextLong(), picture.description()));
        }
      }
    }
    List<ChapterEdition> chapters = editions != null ? editions : readChapters(f, segment);
    var plans = new ArrayList<CompactElement>();
    addCompact(plans, f, segment, oldChapters, CHAPTERS, chapters.isEmpty() ? new byte[0] : render(CHAPTERS, renderChapters(chapters)));
    addCompact(plans, f, segment, oldAttachments, ATTACHMENTS, renderAttachments(attachments));
    addCompact(plans, f, segment, oldTags, TAGS, tags.isEmpty() ? new byte[0] : render(TAGS, renderSimpleTags(tags)));
    if (oldTags == null && tags.isEmpty() && NativeSaveState.retainedEmptyMatroskaTag()) {
      plans.add(new CompactElement(TAGS, f.size(), 0, new byte[0]));
    }
    if (plans.isEmpty()) {
      return segment;
    }
    plans.sort(Comparator.comparingLong(plan -> plan.offset));
    var seeks = new ArrayList<SeekEntry>();
    if (oldSeek != null) {
      seeks.addAll(seekEntries(f, oldSeek));
      for (CompactElement plan : plans) {
        if (plan.oldLength == 0) {
          seeks.add(new SeekEntry(plan.id, plan.offset - segment.element.data(), true));
        }
      }
      seeks.sort(Comparator.comparingLong(SeekEntry::position));
    }
    CompactElement seekPlan =
      oldSeek == null ? null : new CompactElement(SEEKHEAD, oldSeek.offset, paddedSpan(f, segment, oldSeek), raw(f, oldSeek));
    CompactElement cuePlan = oldCues == null ? null : new CompactElement(oldCues.id, oldCues.offset, oldCues.total(), raw(f, oldCues));
    if (cuePlan != null) {
      plans.add(cuePlan);
    }
    if (seekPlan != null) {
      plans.add(seekPlan);
    }
    int originalWidth = segment.element.sizeWidth, sizeWidth = originalWidth;
    byte[] segmentSize = null;
    // Native listeners can enlarge cues/seek-head integers and the segment size field.
    // Recompute their fixed point before applying original-offset edits.
    for (int round = 0; round < 5; round++) {
      boolean changed = false;
      if (cuePlan != null) {
        byte[] rendered = relocatedCues(f, oldCues, segment, plans, sizeWidth - originalWidth);
        if (!Arrays.equals(rendered, cuePlan.bytes)) {
          cuePlan.bytes = rendered;
          changed = true;
        }
      }
      if (seekPlan != null) {
        var body = new ByteArrayOutputStream();
        for (var entry : seeks) {
          boolean removed = plans.stream().anyMatch(plan -> plan.id == entry.id && plan.oldLength != 0 && plan.bytes.length == 0);
          if (removed) {
            continue;
          }
          long position = entry.newElement ? newElementPosition(entry, segment, plans)
            : relocated(entry.position, entry.id, segment, plans, false);
          body.writeBytes(render(0x4dbb, join(render(0x53ab, idBytes(entry.id)), render(0x53ac, unsigned(position)))));
        }
        byte[] rendered = padCompact(render(SEEKHEAD, body.toByteArray()), seekPlan.oldLength);
        if (!Arrays.equals(rendered, seekPlan.bytes)) {
          seekPlan.bytes = rendered;
          changed = true;
        }
      }
      long delta = plans.stream().mapToLong(plan -> plan.bytes.length - plan.oldLength).sum();
      long newSize = segment.element.length + delta;
      if (newSize > (1L << (sizeWidth * 7)) - 1 && sizeWidth != 8) {
        sizeWidth = 8;
        changed = true;
      }
      segmentSize = size(newSize, sizeWidth);
      if (!changed) {
        break;
      }
    }
    var edits = new ArrayList<Rewrite.Edit>();
    boolean cuesRendered = previousCuesRendered || cuePlan != null && !Arrays.equals(raw(f, oldCues), cuePlan.bytes);
    boolean seekRendered = previousSeekRendered || seekPlan != null && !seeks.isEmpty();
    // New elements share EOF in native construction order; combine them so sort stability
    // produces chapters, attachments, tags instead of reversing their insertions.
    var append = new ByteArrayOutputStream();
    for (CompactElement plan : plans) {
      if (plan == cuePlan && !cuesRendered || plan == seekPlan && !seekRendered) {
        continue;
      }
      if (plan.oldLength == 0) {
        append.writeBytes(plan.bytes);
      } else {
        edits.add(new Rewrite.Edit(plan.offset, plan.oldLength, plan.bytes));
      }
    }
    if (append.size() != 0) {
      edits.add(new Rewrite.Edit(f.size(), 0, append.toByteArray()));
    }
    edits.add(new Rewrite.Edit(segment.element.data() - originalWidth, originalWidth, segmentSize));
    edits.sort(Comparator.comparingLong(Rewrite.Edit::offset));
    writeCompactEdits(f, edits);
    long cursor = f.stream() == null ? 0 : f.stream().tell();
    try (var current = f.stream() == null ? new BinaryFile(f.path()) : new BinaryFile(f.stream())) {
      return savedSegment(current, segment, plans, sizeWidth - originalWidth, cuesRendered, seekRendered);
    } finally {
      if (f.stream() != null) {
        f.stream().seek(cursor);
      }
    }
  }

  private static Segment savedSegment(
    BinaryFile file, Segment original, List<CompactElement> plans,
    int headerGrowth, boolean cuesRendered, boolean seekRendered
  ) throws IOException {
    // Native objects retain their selected elements across saves. Re-dispatching from the
    // rewritten file would discover previously unparsed Fast-scan data or reject native's
    // own dangling EOF entries after removing an empty Tag.
    long delta = plans.stream().mapToLong(plan -> plan.bytes.length - plan.oldLength).sum();
    Element parent = new Element(SEGMENT, original.element.offset, original.element.header + headerGrowth,
      original.element.length + delta, original.element.sizeWidth + headerGrowth, false);
    var selected = new ArrayList<Element>();
    for (Element old : original.selected) {
      CompactElement replacement = plans.stream().filter(plan -> plan.id == old.id).findFirst().orElse(null);
      if (replacement != null && replacement.bytes.length == 0) {
        continue;
      }
      long offset = old.offset + headerGrowth;
      for (CompactElement plan : plans) {
        if (plan.offset <= old.offset && plan.id != old.id) {
          offset += plan.bytes.length - plan.oldLength;
        }
      }
      selected.add(element(file, offset, parent.end()));
    }
    long prefix = 0;
    for (CompactElement plan : plans) {
      if (plan.oldLength == 0) {
        long offset = plan.offset + headerGrowth + prefix;
        for (CompactElement existing : plans) {
          if (existing.oldLength != 0 && existing.offset <= plan.offset) {
            offset += existing.bytes.length - existing.oldLength;
          }
        }
        if (plan.bytes.length != 0) {
          selected.add(element(file, offset, parent.end()));
        }
        prefix += plan.bytes.length;
      }
    }
    if (!file.retainReadData()) {
      int count = 0;
      for (long at = parent.data(); at < parent.end(); ) {
        try {
          Element next = element(file, at, parent.end());
          at = next.end();
          if (++count > 100_000) throw new IOException("Too many EBML elements");
        } catch (IOException malformedTail) {
          break;
        }
      }
    }
    return new Segment(parent, List.copyOf(selected), 0, new RenderStatus(cuesRendered, seekRendered));
  }

  private static void writeCompactEdits(BinaryFile file, List<Rewrite.Edit> edits) throws IOException {
    if (file.stream() == null) {
      Rewrite.replace(file, edits);
      return;
    }
    if (file.stream().readOnly()) {
      throw new IOException("Matroska native stream is not writable");
    }
    long end = 0, maximum = file.size();
    for (var edit : edits) {
      if (edit.offset() < end || edit.offset() > file.size() || edit.removeLength() > file.size() - edit.offset()) {
        throw new IOException("Invalid or overlapping Matroska edit");
      }
      end = edit.offset() + edit.removeLength();
      maximum = Math.addExact(maximum, Math.max(0, edit.insertion().length - edit.removeLength()));
    }
    long delta = 0;
    for (var edit : edits) {
      // Element::write calls insert even for empty data; its final writeBlock fixes
      // the cursor at the removed element rather than removeBlock's copied-tail end.
      file.stream().insert(new ByteVector(edit.insertion()), edit.offset() + delta, edit.removeLength());
      delta += edit.insertion().length - edit.removeLength();
    }
  }

  private static final class CompactElement {
    final long id, offset, oldLength;
    byte[] bytes;

    CompactElement(long id, long offset, long oldLength, byte[] bytes) {
      this.id = id;
      this.offset = offset;
      this.oldLength = oldLength;
      this.bytes = bytes;
    }
  }

  private record SeekEntry(long id, long position, boolean newElement) {
  }

  private static long newElementPosition(SeekEntry entry, Segment segment, List<CompactElement> plans) {
    long position = entry.position;
    for (CompactElement plan : plans) {
      if (plan.oldLength != 0) {
        if (entry.position >= plan.offset - segment.element.data() && entry.id != plan.id) {
          position += plan.bytes.length - plan.oldLength;
        }
      }
    }
    // Native constructs all new elements at EOF, then listeners advance only subsequent
    // new elements as each renders, in Chapters/Attachments/Tags construction order.
    for (CompactElement plan : plans) {
      if (plan.oldLength == 0) {
        if (plan.id == entry.id) {
          break;
        }
        position += plan.bytes.length;
      }
    }
    return position;
  }

  private static Element last(Segment segment, long id) {
    Element result = null;
    for (var element : segment.selected) {
      if (element.id == id) {
        result = element;
      }
    }
    return result;
  }

  private static void addCompact(
    List<CompactElement> plans, BinaryFile file, Segment segment, Element old,
    long id, byte[] bytes
  ) {
    if (old == null && bytes.length == 0) {
      return;
    }
    long span = old == null ? 0 : paddedSpan(file, segment, old);
    plans.add(new CompactElement(id, old == null ? file.size() : old.offset, span, bytes));
  }

  private static long paddedSpan(BinaryFile file, Segment segment, Element old) {
    long span = old.total();
    // References and cached Fast-created elements can be absent from the physical header
    // list, for example inside an unknown-sized Cluster. Native padding is local to the
    // selected element's actual end, and SeekHead consumes only its first following Void.
    for (long at = old.end(), count = 0; at < segment.element.end() && count < 100_000; count++) {
      Element next;
      try {
        next = element(file, at, segment.element.end());
      } catch (IOException malformedTail) {
        break;
      }
      if (next.id != 0xec) {
        break;
      }
      span += next.total();
      at = next.end();
      if (old.id == SEEKHEAD) {
        break;
      }
    }
    return span;
  }

  private static long relocated(
    long position, long ownId, Segment segment,
    List<CompactElement> plans, boolean strict
  ) {
    long result = position;
    for (var plan : plans) {
      long threshold = plan.offset - segment.element.data();
      if ((strict || ownId != plan.id) && (strict ? position > threshold : position >= threshold)) {
        result += plan.bytes.length - plan.oldLength;
      }
    }
    return result;
  }

  private static byte[] padCompact(byte[] rendered, long minimum) throws IOException {
    return minimum >= rendered.length + 2 ? join(rendered, voidElement(minimum - rendered.length)) : rendered;
  }

  private static byte[] relocatedCues(
    BinaryFile file, Element cues, Segment segment,
    List<CompactElement> plans, int segmentHeaderGrowth
  ) throws IOException {
    var points = new ByteArrayOutputStream();
    boolean changed = false;
    for (Element point : children(file, cues)) {
      if (point.id == 0xbb) {
        long time = 0;
        var tracks = new ByteArrayOutputStream();
        for (Element field : children(file, point)) {
          if (field.id == 0xb3) {
            time = uint(file, field);
          } else if (field.id == 0xb7) {
            var values = new LinkedHashMap<Long, Long>();
            var references = new ArrayList<Long>();
            for (Element item : children(file, field)) {
              if (isCueTrackField(item.id)) {
                values.put(item.id, uint(file, item));
              } else if (item.id == 0xdb) {
                for (Element reference : children(file, item)) {
                  if (reference.id == 0x96) {
                    references.add(uint(file, reference));
                  }
                }
              }
            }
            var body = new ByteArrayOutputStream();
            for (long id : CUE_TRACK_FIELDS) {
              if (!values.containsKey(id) && id != 0xf7 && id != 0xf1) {
                continue;
              }
              long original = values.getOrDefault(id, 0L), value = original;
              if (id == 0xf1 || id == 0xea && original != 0) {
                value = relocated(original, cues.id, segment, plans, true) + segmentHeaderGrowth;
                changed |= value != original;
              }
              body.writeBytes(render(id, unsigned(value)));
            }
            if (!references.isEmpty()) {
              var referenceData = new ByteArrayOutputStream();
              for (long reference : references) {
                referenceData.writeBytes(render(0x96, unsigned(reference)));
              }
              body.writeBytes(render(0xdb, referenceData.toByteArray()));
            }
            tracks.writeBytes(render(0xb7, body.toByteArray()));
          }
        }
        points.writeBytes(render(0xbb, join(render(0xb3, unsigned(time)), tracks.toByteArray())));
      }
    }
    // Cues are lazy native elements: untouched references retain their original bytes.
    return changed ? padCompact(render(cues.id, points.toByteArray()), cues.total()) : raw(file, cues);
  }

  private static boolean isCueTrackField(long id) {
    return id == 0xf7 || id == 0xf1 || id == 0xf0 || id == 0xb2
      || id == 0x5378 || id == 0xea;
  }

  public static String propertyKeyForSimpleTag(String name, int targetType) {
    return key(name, targetType);
  }

  public static SimpleTag simpleTagForProperty(String key, String value) {
    if (key.isEmpty()) {
      return null;
    }
    Mapping mapping = MAP.stream().filter(entry -> entry.key.equals(key)).findFirst().orElse(new Mapping(key, key, 30, false));
    return new SimpleTag(mapping.name, new SimpleTag.Text(value), mapping.target, "", true, 0,
      0, 0, 0);
  }

  public static String commonProperty(List<SimpleTag> tags, String key) {
    Mapping mapping = MAP.stream().filter(entry -> entry.key.equals(key)).findFirst().orElse(new Mapping(key, key, 30, false));
    for (var tag : tags) {
      if (tag.name().equals(mapping.name) && tag.value() instanceof SimpleTag.Text(var text)
        && (tag.targetType() == mapping.target || tag.targetType() == 0 && !mapping.strict)
        && tag.trackUid() == 0 && tag.editionUid() == 0 && tag.chapterUid() == 0 && tag.attachmentUid() == 0) {
        return text;
      }
    }
    return "";
  }

  private static byte[] raw(BinaryFile f, Element e) throws IOException {
    if (e.total() > ReadOptions.DEFAULT.maxMetadataBytes()) {
      throw new IOException("Metadata exceeds allocation limit");
    }
    return f.read(e.offset, (int) e.total());
  }

  private static byte[] idBytes(long id) {
    int width = 1;
    while (width < 4 && (id >>> (8 * width)) != 0) {
      width++;
    }
    byte[] b = new byte[width];
    for (int i = width - 1; i >= 0; i--) {
      b[i] = (byte) id;
      id >>>= 8;
    }
    return b;
  }

  // EBML UIntElement serializes the unsigned bit pattern at its minimum width.
  static byte[] attachmentUid(long uid) {
    return unsigned(uid);
  }

  private static byte[] unsigned(long n) {
    int width = 1;
    while (width < 8 && (n >>> (8 * width)) != 0) {
      width++;
    }
    byte[] b = new byte[width];
    for (int i = width - 1; i >= 0; i--) {
      b[i] = (byte) n;
      n >>>= 8;
    }
    return b;
  }

  private static byte[] size(long n, int width) throws IOException {
    if (width < 1 || width > 8 || n < 0 || n > (1L << (width * 7)) - 1) {
      throw new IOException("EBML size field overflow");
    }
    byte[] b = new byte[width];
    for (int i = width - 1; i >= 0; i--) {
      b[i] = (byte) n;
      n >>>= 8;
    }
    b[0] |= (byte) (1 << (8 - width));
    return b;
  }

  private static byte[] render(long id, byte[] payload) throws IOException {
    int width = 1;
    while (payload.length > (1L << (width * 7)) - 1) {
      width++;
    }
    return join(idBytes(id), size(payload.length, width), payload);
  }

  private static byte[] voidElement(long total) throws IOException {
    if (total > ReadOptions.DEFAULT.maxMetadataBytes() || total < 2) {
      throw new IOException("Cannot void EBML element");
    }
    int width = (int) Math.min(total - 1, 8);
    byte[] result = new byte[(int) total];
    result[0] = (byte) 0xec;
    System.arraycopy(size(total - 1 - width, width), 0, result, 1, width);
    return result;
  }

  private static byte[] join(byte[]... parts) throws IOException {
    var out = new ByteArrayOutputStream();
    for (byte[] p : parts) out.write(p);
    return out.toByteArray();
  }
}
