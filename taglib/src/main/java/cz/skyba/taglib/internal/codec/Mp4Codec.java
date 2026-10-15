// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.codec;

import cz.skyba.taglib.*;
import cz.skyba.taglib.internal.*;
import cz.skyba.taglib.internal.id3.Id3v1;
import cz.skyba.taglib.mp4.Atoms;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.FileStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static cz.skyba.taglib.internal.Binary.*;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * ISO BMFF/iTunes metadata. Media payloads are streamed, never loaded into the metadata tree.
 */
public final class Mp4Codec implements Codec {
  private NativeLayout nativeLayout;

  public record Details(AudioProperties common, int codec, String codecId, boolean encrypted) {
    private static final Details EMPTY = new Details(AudioProperties.EMPTY, 0, "", false);
  }

  public static Details readProperties(BinaryFile file, ReadStyle style) throws IOException {
    var codec = new Mp4Codec();
    codec.readNative(file, new ReadOptions(false, style, ReadOptions.DEFAULT.maxMetadataBytes()));
    return properties(file, codec.nativeLayout.movie.atom, codec.nativeLayout.tops);
  }

  /**
   * Direct properties from the live public atom tree, without reconstructing tag state.
   */
  public static Details readProperties(FileBase owner, Atoms atoms) throws IOException {
    var roots = new ArrayList<Top>();
    var encrypted = new HashSet<Long>();
    for (var atom : atoms.atoms()) {
      roots.add(new Top(new String(atom.name().toByteArray(), StandardCharsets.ISO_8859_1), atom.offset(), atom.length(), 8,
        propertyMediaLength(atom)));
    }
    var movie = atoms.find("moov");
    if (movie == null) {
      return Details.EMPTY;
    }
    for (var description : movie.findall("stsd", true)) {
      if (description.find("drms") != null) {
        encrypted.add(description.offset());
      }
    }
    var adapter = FileOwnerStream.properties(owner);
    try (var file = new BinaryFile(adapter)) {
      return properties(file, propertyTree(movie), new TopScan(roots, encrypted, 0));
    }
  }

  private static long propertyMediaLength(cz.skyba.taglib.mp4.Atom atom) {
    if (atom.length() == 0) {
      return 0;
    }
    long count = atom.name().equals(new ByteVector("mdat")) ? atom.length() : 0;
    for (var child : atom.children()) {
      count = Math.addExact(count, propertyMediaLength(child));
    }
    return count;
  }

  private static Atom propertyTree(cz.skyba.taglib.mp4.Atom source) {
    Atom atom = new Atom(new String(source.name().toByteArray(), StandardCharsets.ISO_8859_1), new byte[0],
      source.children().stream().map(Mp4Codec::propertyTree).toList());
    atom.propertyOffset = source.offset();
    atom.propertyLength = source.length();
    atom.sourceLength = (int) Math.min(source.length(), Integer.MAX_VALUE);
    return atom;
  }

  // MP4::Atoms survives save(). Its original ilst/padding lengths remain the
  // replacement extents, while chunk-table and fragment positions are updated.
  // Each NativeSession owns its codec, so this state cannot leak across files.
  private static final class NativeLayout {
    final TopScan tops;
    final WritableMovie movie;
    final Map<Atom, Long> positions = new IdentityHashMap<>();
    final List<Atom> fragmentHeaders = new ArrayList<>();

    NativeLayout(BinaryFile file) throws IOException {
      tops = tops(file);
      Top original = tops.stream().filter(t -> t.name.equals("moov")).findFirst().orElseThrow();
      movie = new WritableMovie(movie(file, tops, ReadOptions.DEFAULT, false), original);
      nativePositions(movie.atom, original, positions);
      Top fragment = tops.stream().filter(t -> t.name.equals("moof")).findFirst().orElse(null);
      if (fragment != null) {
        byte[] bytes = file.read(fragment.offset, (int) fragment.size);
        for (Atom atom : parse(bytes, fragment.header, bytes.length, 1, false)) {
          nativeFind(atom, "tfhd", fragmentHeaders);
        }
        for (Atom header : fragmentHeaders) {
          positions.put(header, fragment.offset + header.sourceOffset);
        }
      }
    }
  }

  private static final String FREE = "----:com.apple.iTunes:";
  private static final Set<String> CONTAINERS =
    Set.of("moov", "trak", "mdia", "minf", "stbl", "udta", "meta", "ilst", "moof", "traf");
  private static final Map<String, String> KEYS = keys();
  private static final Map<String, String> NAMES = reverse(KEYS);
  private static final Set<String> BOOL_NAMES = Set.of("cpil", "pgap", "pcst", "shwm");
  private static final Set<String> INT_NAMES = Set.of("tmpo", "©mvi", "©mvc", "hdvd");
  private static final Set<String> UINT_NAMES =
    Set.of("tvsn", "tves", "sfID", "atID", "geID", "cmID");
  private static final Set<String> LONG_NAMES = Set.of("cnID", "plID");
  private static final Set<String> BYTE_NAMES = Set.of("stik", "rtng", "akID");

  private static Map<String, String> keys() {
    var result = new LinkedHashMap<String, String>();
    result.put("©nam", "TITLE");
    result.put("©ART", "ARTIST");
    result.put("©alb", "ALBUM");
    result.put("©cmt", "COMMENT");
    result.put("©gen", "GENRE");
    result.put("©day", "DATE");
    result.put("©wrt", "COMPOSER");
    result.put("©grp", "GROUPING");
    result.put("aART", "ALBUMARTIST");
    result.put("trkn", "TRACKNUMBER");
    result.put("disk", "DISCNUMBER");
    result.put("cpil", "COMPILATION");
    result.put("tmpo", "BPM");
    result.put("cprt", "COPYRIGHT");
    result.put("©lyr", "LYRICS");
    result.put("©too", "ENCODING");
    result.put("©enc", "ENCODEDBY");
    result.put("soal", "ALBUMSORT");
    result.put("soaa", "ALBUMARTISTSORT");
    result.put("soar", "ARTISTSORT");
    result.put("sonm", "TITLESORT");
    result.put("soco", "COMPOSERSORT");
    result.put("sosn", "SHOWSORT");
    result.put("shwm", "SHOWWORKMOVEMENT");
    result.put("pgap", "GAPLESSPLAYBACK");
    result.put("pcst", "PODCAST");
    result.put("catg", "PODCASTCATEGORY");
    result.put("desc", "PODCASTDESC");
    result.put("egid", "PODCASTID");
    result.put("purl", "PODCASTURL");
    result.put("tves", "TVEPISODE");
    result.put("tven", "TVEPISODEID");
    result.put("tvnn", "TVNETWORK");
    result.put("tvsn", "TVSEASON");
    result.put("tvsh", "TVSHOW");
    result.put("©wrk", "WORK");
    result.put("©mvn", "MOVEMENTNAME");
    result.put("©mvi", "MOVEMENTNUMBER");
    result.put("©mvc", "MOVEMENTCOUNT");
    result.put("ownr", "OWNER");
    result.put("----:com.apple.iTunes:MusicBrainz Track Id", "MUSICBRAINZ_TRACKID");
    result.put("----:com.apple.iTunes:MusicBrainz Artist Id", "MUSICBRAINZ_ARTISTID");
    result.put("----:com.apple.iTunes:MusicBrainz Album Id", "MUSICBRAINZ_ALBUMID");
    result.put("----:com.apple.iTunes:MusicBrainz Album Artist Id", "MUSICBRAINZ_ALBUMARTISTID");
    result.put("----:com.apple.iTunes:MusicBrainz Release Group Id", "MUSICBRAINZ_RELEASEGROUPID");
    result.put("----:com.apple.iTunes:MusicBrainz Release Track Id", "MUSICBRAINZ_RELEASETRACKID");
    result.put("----:com.apple.iTunes:MusicBrainz Work Id", "MUSICBRAINZ_WORKID");
    result.put("----:com.apple.iTunes:MusicBrainz Album Release Country", "RELEASECOUNTRY");
    result.put("----:com.apple.iTunes:MusicBrainz Album Status", "RELEASESTATUS");
    result.put("----:com.apple.iTunes:MusicBrainz Album Type", "RELEASETYPE");
    result.put("----:com.apple.iTunes:ARTISTS", "ARTISTS");
    result.put("----:com.apple.iTunes:ORIGINALDATE", "ORIGINALDATE");
    result.put("----:com.apple.iTunes:RELEASEDATE", "RELEASEDATE");
    result.put("----:com.apple.iTunes:ASIN", "ASIN");
    result.put("----:com.apple.iTunes:LABEL", "LABEL");
    result.put("----:com.apple.iTunes:LYRICIST", "LYRICIST");
    result.put("----:com.apple.iTunes:CONDUCTOR", "CONDUCTOR");
    result.put("----:com.apple.iTunes:REMIXER", "REMIXER");
    result.put("----:com.apple.iTunes:ENGINEER", "ENGINEER");
    result.put("----:com.apple.iTunes:PRODUCER", "PRODUCER");
    result.put("----:com.apple.iTunes:DJMIXER", "DJMIXER");
    result.put("----:com.apple.iTunes:MIXER", "MIXER");
    result.put("----:com.apple.iTunes:SUBTITLE", "SUBTITLE");
    result.put("----:com.apple.iTunes:DISCSUBTITLE", "DISCSUBTITLE");
    result.put("----:com.apple.iTunes:MOOD", "MOOD");
    result.put("----:com.apple.iTunes:ISRC", "ISRC");
    result.put("----:com.apple.iTunes:CATALOGNUMBER", "CATALOGNUMBER");
    result.put("----:com.apple.iTunes:BARCODE", "BARCODE");
    result.put("----:com.apple.iTunes:SCRIPT", "SCRIPT");
    result.put("----:com.apple.iTunes:LANGUAGE", "LANGUAGE");
    result.put("----:com.apple.iTunes:LICENSE", "LICENSE");
    result.put("----:com.apple.iTunes:MEDIA", "MEDIA");
    return Collections.unmodifiableMap(result);
  }

  private static Map<String, String> reverse(Map<String, String> source) {
    var result = new HashMap<String, String>();
    source.forEach((name, property) -> result.put(property, name));
    return Map.copyOf(result);
  }

  @Override
  public PropertyMap unsupportedProperties(PropertyMap properties) {
    var rejected = PropertyMap.builder();
    properties
      .asMap()
      .forEach(
        (key, values) -> {
          if (nameForKey(key) == null) {
            rejected.put(key, values);
          }
        });
    return rejected.build();
  }

  private static String nameForKey(String key) {
    String name = NAMES.get(key);
    return name != null
      ? name
      : !key.isEmpty() && key.charAt(0) >= 'A' && key.charAt(0) <= 'Z' ? FREE + key : null;
  }

  private record Top(String name, long offset, long size, int header, long mdatLength) {
    Top(String name, long offset, long size, int header) {
      this(name, offset, size, header, name.equals("mdat") ? size : 0);
    }
  }

  private sealed interface AtomBody {
  }

  private record LeafBody(byte[] payload) implements AtomBody {
  }

  private record ContainerBody(byte[] prefix, List<Atom> children) implements AtomBody {
  }

  private static final class Atom {
    final String name;
    final AtomBody body;
    int sourceOffset = -1;
    int sourceLength;
    boolean sourceCanonical;
    long propertyOffset = -1, propertyLength = -1;

    Atom(String name, byte[] payload) {
      this.name = name;
      body = new LeafBody(payload);
    }

    Atom(String name, byte[] prefix, List<Atom> children) {
      this.name = name;
      body = new ContainerBody(prefix, children);
    }

    byte[] payload() {
      return body instanceof LeafBody(var payload) ? payload : null;
    }

    byte[] leafData() {
      return Objects.requireNonNull(payload());
    }

    List<Atom> children() {
      return body instanceof ContainerBody container ? container.children : null;
    }

    Atom child(String name) {
      if (!(body instanceof ContainerBody container)) {
        return null;
      }
      return container.children.stream().filter(a -> a.name.equals(name)).findFirst().orElse(null);
    }

    List<Atom> childList() {
      return Objects.requireNonNull(children(), name + " is not an MP4 container");
    }

    Atom path(String... names) {
      Atom a = this;
      for (String name : names) {
        if (a == null) {
          return null;
        }
        a = a.child(name);
      }
      return a;
    }

    int size() throws IOException {
      long size = 8L;
      switch (body) {
        case LeafBody leaf -> size += leaf.payload.length;
        case ContainerBody container -> {
          size += container.prefix.length;
          for (Atom child : container.children) {
            size += child.size();
          }
        }
      }
      if (size > ReadOptions.DEFAULT.maxMetadataBytes()) {
        throw new IOException("MP4 metadata exceeds allocation limit");
      }
      return (int) size;
    }

    byte[] render() throws IOException {
      byte[] result = new byte[size()];
      renderInto(result, 0);
      return result;
    }

    private int renderInto(byte[] output, int offset) {
      int start = offset;
      System.arraycopy(bytes(name), 0, output, offset + 4, 4);
      offset += 8;
      switch (body) {
        case LeafBody(var payload) -> {
          System.arraycopy(payload, 0, output, offset, payload.length);
          offset += payload.length;
        }
        case ContainerBody(var prefix, var children) -> {
          System.arraycopy(prefix, 0, output, offset, prefix.length);
          offset += prefix.length;
          for (Atom child : children) offset = child.renderInto(output, offset);
        }
      }
      put32be(output, start, offset - start);
      return offset;
    }

    private boolean canonicalChildren(int end) {
      if (!(body instanceof ContainerBody(var prefix, var children))) {
        return true;
      }
      for (Atom child : children) {
        if (!child.sourceCanonical) {
          return false;
        }
      }
      return children.isEmpty()
        ? sourceOffset + 8 + prefix.length == end
        : children.getLast().sourceOffset + children.getLast().sourceLength == end;
    }
  }

  private static final int MAX_ATOM_COUNT = 50_000;
  private static final int MAX_ATOM_DEPTH = 64;
  private static final Set<String> META_CHILDREN = Set.of("hdlr", "ilst", "mhdr", "ctry", "lang");

  private static final class AtomScan {
    final Set<Long> encryptedSampleDescriptions = new HashSet<>();
    long cursor;
    int count;
  }

  private record ScannedAtom(Top top, boolean valid) {
  }

  private record TopScan(List<Top> roots, Set<Long> encryptedSampleDescriptions, long constructorCursor)
    implements Iterable<Top> {
    Stream<Top> stream() {
      return roots.stream();
    }

    Top getLast() {
      return roots.getLast();
    }

    public Iterator<Top> iterator() {
      return roots.iterator();
    }
  }

  // MP4::Atoms shares the counter across roots and recognized descendants.
  // Invalid trailing roots are discarded only after a valid moov; an invalid
  // moof still invalidates the file. Leaf item data is not part of that counter.
  private static TopScan tops(BinaryFile file) throws IOException {
    var result = new ArrayList<Top>();
    var scan = new AtomScan();
    boolean validMovie = false;
    while (scan.cursor + 8 <= file.size()) {
      ScannedAtom atom = scanAtom(file, scan, 0);
      if (!atom.valid) {
        if (!validMovie || atom.top.name.equals("moof")) {
          file.seekNative(scan.cursor);
          throw new IOException("Invalid MP4 atom tree");
        }
        break;
      }
      result.add(atom.top);
      if (atom.top.name.equals("moov")) {
        validMovie = true;
      }
      if (result.size() > MAX_ATOM_COUNT) {
        throw new IOException("Too many MP4 root atoms");
      }
    }
    file.seekNative(scan.cursor);
    return new TopScan(result, scan.encryptedSampleDescriptions, scan.cursor);
  }

  private static ScannedAtom scanAtom(BinaryFile file, AtomScan scan, int depth)
    throws IOException {
    long offset = scan.cursor;
    if (++scan.count > MAX_ATOM_COUNT) {
      scan.cursor = file.size();
      return new ScannedAtom(new Top("", offset, 0, 8), false);
    }
    byte[] header = file.readNative(offset, 8);
    scan.cursor += header.length;
    if (header.length != 8) {
      scan.cursor = file.size();
      return new ScannedAtom(new Top("", offset, 0, 8), false);
    }
    long length = u32be(header, 0);
    String name = ascii(header, 4, 4);
    int headerSize = 8;
    if (length == 0) {
      length = file.size() - offset;
    } else if (length == 1) {
      byte[] extended = file.readNative(scan.cursor, 8);
      scan.cursor += extended.length;
      length = new ByteVector(extended).toLong();
      headerSize = 16;
    }
    Top top = new Top(name, offset, length, headerSize);
    if (length < 8 || length > file.size() - offset) {
      scan.cursor = file.size();
      return new ScannedAtom(top, false);
    }
    if (CONTAINERS.contains(name) || name.equals("stsd")) {
      if (name.equals("meta")) {
        byte[] next = file.readNative(scan.cursor, 8);
        if (next.length < 8 || !META_CHILDREN.contains(ascii(next, 4, 4))) {
          scan.cursor += 4;
        }
      } else if (name.equals("stsd")) {
        scan.cursor += 8;
      }
      // Native returns with the cursor after the container prefix. Its parent
      // then sees deeper headers as siblings, rather than rejecting the file.
      if (depth > MAX_ATOM_DEPTH) {
        return new ScannedAtom(top, true);
      }
      int children = 0;
      long mdatLength = 0;
      while (scan.cursor < offset + length) {
        if (children++ >= MAX_ATOM_COUNT) {
          scan.cursor = file.size();
          return new ScannedAtom(top, false);
        }
        ScannedAtom child = scanAtom(file, scan, depth + 1);
        if (!child.valid) {
          return new ScannedAtom(top, false);
        }
        if (name.equals("stsd") && child.top.name.equals("drms")) {
          scan.encryptedSampleDescriptions.add(offset);
        }
        if (child.top.mdatLength > Long.MAX_VALUE - mdatLength) {
          throw new IOException("MP4 media length overflow");
        }
        mdatLength += child.top.mdatLength;
      }
      top = new Top(name, offset, length, headerSize, mdatLength);
    } else {
      scan.cursor = offset + length;
    }
    return new ScannedAtom(top, true);
  }

  private static List<Atom> parse(
    byte[] data, int from, int end, int depth, boolean allowParentOverrun) throws IOException {
    return new AtomParser(data, allowParentOverrun).parse(from, end, depth);
  }

  private static final class AtomParser {
    private final byte[] data;
    private final boolean allowParentOverrun;
    private int count;

    private AtomParser(byte[] data, boolean allowParentOverrun) {
      this.data = data;
      this.allowParentOverrun = allowParentOverrun;
    }

    private List<Atom> parse(int from, int end, int depth) throws IOException {
      var result = new ArrayList<Atom>();
      int p = from;
      while (p + 8 <= end) {
        if (++count > MAX_ATOM_COUNT) {
          throw new IOException("Too many nested MP4 atoms");
        }
        long n = u32be(data, p);
        String name = ascii(data, p + 4, 4);
        int h = 8;
        if (n == 1) {
          if (p + 16 > end) {
            throw new IOException("Truncated extended atom");
          }
          n = u64be(data, p + 8);
          h = 16;
        }
        if (n == 0) {
          n = end - p;
        }
        if (n < h || n > (allowParentOverrun ? data.length : end) - p) {
          throw new IOException("Invalid nested MP4 atom " + name);
        }
        int stop = p + (int) n, start = p + h;
        Atom parsed;
        if (CONTAINERS.contains(name)) {
          int prefix = name.equals("meta") ? 4 : 0;
          // QuickTime permits a non-full 'meta' container.
          if (name.equals("meta")
            && stop - start >= 8
            && META_CHILDREN.contains(ascii(data, start + 4, 4))) {
            prefix = 0;
          }
          if (start + prefix > stop) {
            throw new IOException("Short meta atom");
          }
          parsed =
            new Atom(
              name,
              Arrays.copyOfRange(data, start, start + prefix),
              depth > MAX_ATOM_DEPTH
                ? new ArrayList<>()
                : parse(start + prefix, stop, depth + 1));
          if (depth > MAX_ATOM_DEPTH) {
            stop = start + prefix;
          }
        } else {
          parsed = new Atom(name, Arrays.copyOfRange(data, start, stop));
        }
        parsed.sourceOffset = p;
        parsed.sourceLength = (int) n;
        parsed.sourceCanonical = h == 8 && u32be(data, p) != 0 && parsed.canonicalChildren(stop);
        result.add(parsed);
        p = stop;
      }
      return result;
    }
  }

  private static Atom movie(BinaryFile f, TopScan tops, ReadOptions options, boolean strict)
    throws IOException {
    Top top =
      tops.stream()
        .filter(t -> t.name.equals("moov"))
        .findFirst()
        .orElseThrow(() -> new IOException("MP4 has no moov atom"));
    if (top.size > options.maxMetadataBytes()) {
      throw new IOException("MP4 metadata exceeds allocation limit");
    }
    int length =
      (int)
        (Math.min(top.size + (!strict && top.header == 16 ? 8 : 0), f.size() - top.offset)
          - top.header);
    Atom movie =
      new Atom(
        "moov",
        new byte[0],
        parse(
          f.read(top.offset + top.header, length),
          0,
          (int) (top.size - top.header),
          1,
          !strict && top.header == 16));
    // Child positions are relative to the loaded moov body, not the file.
    movie.sourceOffset = -8;
    movie.sourceLength = (int) top.size;
    movie.sourceCanonical =
      top.header == 8 && f.u32be(top.offset) == top.size && movie.canonicalChildren(length);
    return movie;
  }

  private record WritableMovie(Atom atom, Top original) {
  }

  private static WritableMovie writableMovie(BinaryFile file, TopScan tops) throws IOException {
    Top original =
      tops.stream()
        .filter(top -> top.name.equals("moov"))
        .findFirst()
        .orElseThrow(() -> new IOException("MP4 has no moov atom"));
    try {
      return new WritableMovie(movie(file, tops, ReadOptions.DEFAULT, true), original);
    } catch (IOException failure) {
      // Historical TagLib extended-size metadata can omit eight bytes from each
      // enclosing container. Repair only a complete, terminal metadata-only chain:
      // there can be no sibling overlap, media/index relocation, or unexplained tail.
      if (original.size > ReadOptions.DEFAULT.maxMetadataBytes() - 8L) {
        throw failure;
      }
      long physicalSize = original.size + 8;
      if (original.header != 16
        || tops.getLast() != original
        || original.offset + physicalSize != file.size()
        || physicalSize > ReadOptions.DEFAULT.maxMetadataBytes()) {
        throw failure;
      }
      byte[] bytes = file.read(original.offset, (int) physicalSize);
      int at = 0;
      for (String expected : List.of("moov", "udta", "meta")) {
        if (at + 16 > bytes.length
          || u32be(bytes, at) != 1
          || !ascii(bytes, at + 4, 4).equals(expected)
          || u64be(bytes, at + 8) != bytes.length - at - 8L) {
          throw failure;
        }
        put64be(bytes, at + 8, bytes.length - at);
        at += 16;
      }
      if (at + 12 > bytes.length || u32be(bytes, at) != 0) {
        throw failure;
      }
      // Native saveExisting may put free padding around ilst. Accept only those
      // siblings; all ilst entries must fill their parent, with opaque payloads retained.
      boolean foundItems = false;
      int child = at + 4;
      while (child < bytes.length) {
        if (child + 8 > bytes.length) {
          throw failure;
        }
        long size = u32be(bytes, child);
        if (size < 8 || size > bytes.length - child) {
          throw failure;
        }
        String name = ascii(bytes, child + 4, 4);
        if (name.equals("ilst")) {
          if (foundItems) {
            throw failure;
          }
          foundItems = true;
          int end = child + (int) size;
          int item = child + 8;
          while (item < end) {
            if (item + 8 > end) {
              throw failure;
            }
            long itemSize = u32be(bytes, item);
            if (itemSize < 8 || itemSize > end - item) {
              throw failure;
            }
            item += (int) itemSize;
          }
        } else if (!name.equals("free")) {
          throw failure;
        }
        child += (int) size;
      }
      if (!foundItems) {
        throw failure;
      }
      var repaired =
        new Atom("moov", new byte[0], parse(bytes, 16, bytes.length, 1, false));
      return new WritableMovie(
        repaired, new Top(original.name, original.offset, physicalSize, original.header));
    }
  }

  @Override
  public AudioMetadata read(Path path, ReadOptions options) throws IOException {
    try (var f = new BinaryFile(path)) {
      return read(f, options);
    }
  }

  @Override
  public AudioMetadata readNative(BinaryFile file, ReadOptions options) throws IOException {
    AudioMetadata snapshot = read(file, options);
    long cursor = file.stream() == null ? 0 : file.stream().tell();
    nativeLayout = new NativeLayout(file);
    if (file.stream() != null) {
      file.stream().seek(cursor);
    }
    return snapshot;
  }

  @Override
  public AudioMetadata read(BinaryFile f, ReadOptions options) throws IOException {
    var top = tops(f);
    var moov = movie(f, top, options, false);
    var props = PropertyMap.builder();
    var pictures = new ArrayList<Picture>();
    Atom ilst = moov.path("udta", "meta", "ilst");
    var unique = new TreeMap<String, Atom>();
    if (ilst != null) {
      for (Atom item : ilst.childList()) {
        unique.putIfAbsent(itemName(item), item);
      }
    }
    Atom stem = moov.path("udta", "stem");
    if (stem != null) {
      unique.putIfAbsent("stem", stem);
    }
    for (var entry : unique.entrySet()) {
      if (entry.getKey().equals("stem")) {
        props.addUnsupportedData("stem");
      } else {
        Atom item = entry.getValue();
        var itemProps = PropertyMap.builder();
        readItem(item, itemProps, pictures);
        var values = itemProps.build();
        props.putAll(values);
        values.unsupportedData().forEach(props::addUnsupportedData);
      }
    }
    if (f.stream() != null) {
      f.stream().seek(nativeTagCursor(moov, top));
    }
    Details nativeProperties =
      options.readAudioProperties() ? properties(f, moov, top) : Details.EMPTY;
    return new AudioMetadata(
      Format.MP4,
      props.build(),
      nativeProperties.common(),
      pictures);
  }

  // The snapshot parser uses positional reads, but a borrowed native IOStream
  // exposes the last constructor read. Reproduce Atoms/Tag cursor effects here;
  // the shared property decoder performs its own native reads afterward.
  private static long nativeTagCursor(Atom moov, TopScan tops) {
    Top movie = tops.stream().filter(t -> t.name.equals("moov")).findFirst().orElseThrow();
    long body = movie.offset + movie.header;
    long cursor = tops.constructorCursor;
    Atom ilst = moov.path("udta", "meta", "ilst");
    if (ilst != null && !ilst.childList().isEmpty()) {
      Atom last = ilst.childList().getLast();
      cursor = body + last.sourceOffset + last.sourceLength;
    }
    Atom stem = moov.path("udta", "stem");
    if (stem != null) {
      cursor = body + stem.sourceOffset + stem.sourceLength;
    }
    return cursor;
  }

  private static List<Atom> itemChildren(byte[] b) {
    var out = new ArrayList<Atom>();
    int p = 0;
    while (p + 12 <= b.length) {
      long n = u32be(b, p);
      if (n < 12 || n > b.length - p) {
        break;
      }
      out.add(new Atom(ascii(b, p + 4, 4), Arrays.copyOfRange(b, p + 8, p + (int) n)));
      p += (int) n;
    }
    return out;
  }

  private static String itemName(Atom item) {
    if (!item.name.equals("----")) {
      return item.name;
    }
    String mean = "", name = "";
    for (Atom a : itemChildren(item.leafData())) {
      if (a.leafData().length >= 4) {
        if (a.name.equals("mean")) {
          mean = utf8(a.leafData());
        } else if (a.name.equals("name")) {
          name = utf8(a.leafData());
        }
      }
    }
    return "----:" + mean + ":" + name;
  }

  private static String keyFor(String name) {
    return KEYS.getOrDefault(
      name,
      name.startsWith(FREE)
        ? Ascii.upper(name.substring(FREE.length()))
        : null);
  }

  /**
   * Native item-factory projection, also used by the mutable AudioFile removal preview.
   */
  public static String propertyKeyForItem(String name) {
    return keyFor(name);
  }

  /**
   * Raw native Stem manifest, including an empty manifest; null denotes an absent atom.
   */
  public static byte[] stemData(BinaryFile file) throws IOException {
    var moov = movie(file, tops(file), ReadOptions.DEFAULT, false);
    Atom stem = moov.path("udta", "stem");
    return stem == null ? null : stem.leafData().clone();
  }

  /**
   * Physical free-form items retained by the native factory's canonical-name erasure.
   */
  public static Map<String, PropertyMap> nonCanonicalPropertyItems(BinaryFile file)
    throws IOException {
    var moov = movie(file, tops(file), ReadOptions.DEFAULT, false);
    var result = new TreeMap<String, PropertyMap>();
    Atom ilst = moov.path("udta", "meta", "ilst");
    if (ilst != null) {
      for (Atom item : ilst.childList()) {
        String name = itemName(item), key = keyFor(name);
        if (key != null && !item.name.equals("gnre") && !name.equals(nameForKey(key))) {
          var p = PropertyMap.builder();
          readItem(item, p, new ArrayList<>());
          result.putIfAbsent(name, p.build());
        }
      }
    }
    return Collections.unmodifiableMap(result);
  }

  private static void readItem(Atom item, PropertyMap.Builder props, List<Picture> pictures) {
    if (item.payload() == null) {
      return;
    }
    String name = itemName(item), key = keyFor(name);
    boolean unsupportedAdded = false;
    for (Atom data : itemChildren(item.payload())) {
      if (!data.name.equals("data") || data.leafData().length < 8) {
        continue;
      }
      byte[] p = data.leafData();
      long type = u32be(p, 0);
      byte[] value = Arrays.copyOfRange(p, 8, p.length);
      if (name.equals("covr")) {
        if (!unsupportedAdded) {
          props.addUnsupportedData(name);
          unsupportedAdded = true;
        }
        String mime =
          switch ((int) type) {
            case 13 -> "image/jpeg";
            case 14 -> "image/png";
            case 12 -> "image/gif";
            case 27 -> "image/bmp";
            case 0 -> "image/";
            default -> imageMime(value);
          };
        pictures.add(new Picture(0, mime, "", value));
        continue;
      }
      if (name.equals("gnre") && value.length >= 2) {
        String genre = Id3v1.genre(u16be(value, 0) - 1);
        if (!genre.isEmpty()) {
          props.put("GENRE", genre);
        }
        continue;
      }
      if (key == null) {
        if (!unsupportedAdded && (type == 1 || isNumber(name))) {
          props.addUnsupportedData(name);
          unsupportedAdded = true;
        }
        continue;
      }
      if (name.equals("trkn") || name.equals("disk")) {
        if (value.length >= 6) {
          int a = (short) u16be(value, 2), b = (short) u16be(value, 4);
          props.put(key, a + (b != 0 ? "/" + b : ""));
        }
      } else if (isNumber(name)) {
        long n = 0;
        if (BOOL_NAMES.contains(name)) {
          n = value.length > 0 && value[0] != 0 ? 1 : 0;
        } else if (INT_NAMES.contains(name)) {
          n = value.length >= 2 ? (short) u16be(value, 0) : 0;
        } else if (BYTE_NAMES.contains(name)) {
          n = value.length > 0 ? value[0] & 255 : 0;
        } else if (UINT_NAMES.contains(name)) {
          n = value.length >= 4 ? u32be(value, 0) : 0;
        } else if (LONG_NAMES.contains(name)) {
          n = value.length >= 8 ? u64be(value, 0) : 0;
        }
        props.put(key, Long.toString(n));
      } else if (name.startsWith(FREE) && type != 1) {
        props.put(key, List.of());
      } else if (type == 1 || type == 0) {
        int zero = 0;
        while (zero < value.length && value[zero] != 0) {
          zero++;
        }
        props.add(key, new String(value, 0, zero, StandardCharsets.UTF_8));
      }
    }
  }

  private static boolean isNumber(String name) {
    Objects.requireNonNull(name);
    return BOOL_NAMES.contains(name) || INT_NAMES.contains(name) || UINT_NAMES.contains(name)
      || LONG_NAMES.contains(name) || BYTE_NAMES.contains(name);
  }

  private static boolean knownArtworkType(int type) {
    return switch (type) {
      case 0, 12, 13, 14, 27 -> true;
      default -> false;
    };
  }

  private static final byte[] PNG_SIGNATURE = {(byte) 137, 'P', 'N', 'G', 13, 10, 26, 10};

  private static String imageMime(byte[] p) {
    if (p.length > 1 && (p[0] & 255) == 255 && (p[1] & 255) == 216) {
      return "image/jpeg";
    }
    if (p.length >= 8
      && Arrays.equals(p, 0, 8, PNG_SIGNATURE, 0, PNG_SIGNATURE.length)) {
      return "image/png";
    }
    if (p.length > 5 && ascii(p, 0, 4).equals("GIF8")) {
      return "image/gif";
    }
    if (p.length >= 14 && ascii(p, 0, 2).equals("BM") && u32le(p, 6) == 0) {
      return "image/bmp";
    }
    return "image/";
  }

  private static String utf8(byte[] b) {
    return new String(b, 4, b.length - 4, StandardCharsets.UTF_8);
  }

  private static Details properties(BinaryFile file, Atom moov, TopScan tops) throws IOException {
    var movie = tops.stream().filter(t -> t.name.equals("moov")).findFirst().orElseThrow();
    long body = movie.offset + movie.header;
    Atom track = null;
    for (Atom candidate : moov.childList()) {
      if (!candidate.name.equals("trak")) {
        continue;
      }
      Atom handler = candidate.path("mdia", "hdlr");
      if (handler == null) {
        return Details.EMPTY;
      }
      var data = propertyAtom(file, body, handler);
      if (data.containsAt(new ByteVector("soun"), 16)) {
        track = candidate;
        break;
      }
    }
    if (track == null) {
      return Details.EMPTY;
    }
    Atom mdhd = track.path("mdia", "mdhd");
    if (mdhd == null) {
      return Details.EMPTY;
    }
    var data = propertyAtom(file, body, mdhd);
    long unit, frames;
    if (data.at(8) == 1) {
      if (data.size() < 44) {
        return Details.EMPTY;
      }
      unit = data.toUnsignedInt(28, true);
      frames = data.toLong(32);
    } else {
      if (data.size() < 32) {
        return Details.EMPTY;
      }
      unit = data.toUnsignedInt(20, true);
      frames = data.toUnsignedInt(24, true);
    }
    if (frames == 0) {
      Atom mvhd = moov.child("mvhd");
      if (mvhd != null) {
        data = propertyAtom(file, body, mvhd);
        if (data.size() >= 28) {
          unit = data.toUnsignedInt(20, true);
          frames = data.toUnsignedInt(24, true);
        }
      }
    }
    int length = 0, rate = 0, channels = 0, bits = 0, bitrate = 0, codec = 0;
    if (unit > 0 && frames > 0) {
      double milliseconds = (double) frames * 1000.0 / unit;
      if (milliseconds > 0 && milliseconds < Integer.MAX_VALUE) {
        length = (int) (milliseconds + 0.5);
      }
    }
    Atom stsd = track.path("mdia", "minf", "stbl", "stsd");
    if (stsd == null) {
      return new Details(new AudioProperties(length, 0, 0, 0, 0, false), 0, "", false);
    }
    data = propertyAtom(file, body, stsd);
    String codecId = data.size() >= 24 ? TextBytes.decodeTerminated(data.data(), 20, 4, ISO_8859_1) : "";
    boolean lossless = data.size() >= 50 && (data.containsAt(new ByteVector("alac"), 20) || data.containsAt(
      new ByteVector("fLaC"), 20));
    boolean fallback = false;
    if (data.containsAt(new ByteVector("mp4a"), 20)) {
      codec = 1;
      channels = data.toShort(40);
      bits = data.toShort(42);
      rate = (int) data.toUnsignedInt(46, true);
      if (data.containsAt(new ByteVector("esds"), 56) && data.at(64) == 3) {
        int position = 65;
        if (data.containsAt(new ByteVector(new byte[]{(byte) 128, (byte) 128, (byte) 128}), position)) {
          position += 3;
        }
        position += 4;
        if (data.at(position) == 4) {
          position++;
          if (data.containsAt(new ByteVector(new byte[]{(byte) 128, (byte) 128, (byte) 128}), position)) {
            position += 3;
          }
          position += 10;
          long nominal = data.toUnsignedInt(position, true);
          if (nominal != 0 || length <= 0) {
            bitrate = (int) (nominal / 1000.0 + 0.5);
          } else {
            fallback = true;
          }
        }
      }
    } else if (data.containsAt(new ByteVector("alac"), 20)) {
      if (stsd.sourceLength == 88 && data.containsAt(new ByteVector("alac"), 56)) {
        codec = 2;
        bits = data.at(69);
        channels = data.at(73);
        bitrate = (int) (data.toUnsignedInt(80, true) / 1000.0 + 0.5);
        rate = (int) data.toUnsignedInt(84, true);
        fallback = bitrate == 0 && length > 0;
      }
    } else if (data.size() >= 50) {
      codec = switch (new String(data.mid(20, 4).toByteArray(), StandardCharsets.ISO_8859_1)) {
        case "ac-3" -> 3;
        case "ec-3" -> 4;
        case "fLaC" -> 5;
        case "dtsc", "dtse", "dtsh", "dtsl" -> 6;
        case "Opus" -> 7;
        default -> 0;
      };
      channels = data.toShort(40);
      bits = data.toShort(42);
      rate = (int) data.toUnsignedInt(46, true);
      if (codec == 5) {
        int position = data.find(new ByteVector("dfLa"));
        if (position >= 0 && data.size() >= position + 26 && (data.at(position + 8) & 127) == 0) {
          long info = data.toUnsignedInt(position + 22, true);
          if ((info >>> 12) != 0) {
            rate = (int) (info >>> 12);
          }
          channels = (int) ((info >>> 9) & 7) + 1;
          bits = (int) ((info >>> 4) & 31) + 1;
        }
      } else if (codec == 4) {
        int position = data.find(new ByteVector("dec3"));
        if (position >= 0 && data.size() >= position + 6) {
          bitrate = data.toUnsignedShort(position + 4) >>> 3;
        }
      }
      fallback = bitrate == 0 && length > 0;
    }
    if (fallback) {
      long bytes = 0;
      for (Top top : tops) {
        if (top.mdatLength > Long.MAX_VALUE - bytes) {
          bytes = -1;
          break;
        }
        bytes += top.mdatLength;
      }
      // Upstream's signed product is undefined if it overflows; return an unknown estimate.
      if (bytes >= 0 && bytes <= Long.MAX_VALUE / 8) {
        bitrate = (int) (bytes * 8 / length);
      }
    }
    boolean encrypted = tops.encryptedSampleDescriptions.contains(stsd.propertyOffset >= 0 ? stsd.propertyOffset : body + stsd.sourceOffset);
    return new Details(new AudioProperties(length, bitrate, rate, channels, bits, lossless), codec, codecId, encrypted);
  }

  private static ByteVector propertyAtom(BinaryFile file, long body, Atom atom)
    throws IOException {
    long length = atom.propertyLength >= 0 ? atom.propertyLength : atom.sourceLength;
    if (length > Integer.MAX_VALUE) {
      throw new IOException("MP4 property atom exceeds Java array bound");
    }
    return new ByteVector(
      file.readNative(atom.propertyOffset >= 0 ? atom.propertyOffset : body + atom.sourceOffset, (int) length));
  }

  @Override
  public void write(Path path, PropertyMap properties) throws IOException {
    write(path, properties, null);
  }

  @Override
  public void write(
    BinaryFile f, PropertyMap properties, List<Picture> pictures)
    throws IOException {
    if (f.stream() != null) {
      readNative(f, ReadOptions.TAGS_ONLY);
      writeCore(f, properties, pictures, true);
    } else {
      try (var stream = new FileStream(f.path());
           var borrowed = new BinaryFile(stream)) {
        if (!stream.isOpen() || stream.readOnly()) {
          throw new IOException("MP4 stream is not writable");
        }
        readNative(borrowed, ReadOptions.TAGS_ONLY);
        WriteGuard.check(f.requirePath().toRealPath());
        writeCore(borrowed, properties, pictures, true);
      }
    }
  }

  @Override
  public void writeNative(
    BinaryFile f,
    long constructorOffset,
    PropertyMap properties,
    List<Picture> pictures
  )
    throws IOException {
    writeCore(f, properties, pictures, f.stream() != null);
  }

  private void writeCore(
    BinaryFile f,
    PropertyMap properties,
    List<Picture> pictures,
    boolean nativeProtocol
  )
    throws IOException {
    Rewrite.Edit metadataEdit;
    var extraEdits = new ArrayList<Rewrite.Edit>();

    var tops = nativeProtocol && nativeLayout != null ? nativeLayout.tops : tops(f);
    WritableMovie prepared =
      nativeProtocol && nativeLayout != null
        ? nativeLayout.movie
        : nativeProtocol
        ? new WritableMovie(
        movie(f, tops, ReadOptions.DEFAULT, false),
        tops.stream().filter(t -> t.name.equals("moov")).findFirst().orElseThrow())
        : writableMovie(f, tops);
    Atom moov = prepared.atom;
    Top original = prepared.original;
    Atom oldUdta = moov.child("udta"), oldIlst = moov.path("udta", "meta", "ilst");
    Atom oldMeta = oldUdta == null ? null : oldUdta.child("meta");
    var oldMovieChildren = new ArrayList<>(moov.childList());
    var oldUdtaChildren = oldUdta == null ? null : new ArrayList<>(oldUdta.childList());
    var oldMetaChildren = oldMeta == null ? null : new ArrayList<>(oldMeta.childList());
    Atom udta = ensure(moov, "udta"), meta = ensure(udta, "meta");
    boolean focused =
      moov.sourceCanonical
        && meta.sourceOffset >= 0
        && meta.child("hdlr") != null
        && tops.stream().noneMatch(top -> top.name.equals("moof"))
        && tops.getLast().offset + tops.getLast().size == f.size();
    if (meta.child("hdlr") == null && (!nativeProtocol || oldIlst == null)) {
      byte[] h = new byte[25];
      System.arraycopy(bytes("mdirappl"), 0, h, 8, 8);
      meta.childList().add(new Atom("hdlr", h));
    }
    Atom ilst = meta.child("ilst");
    focused &= ilst != null;
    int focusedOffset = ilst == null ? -1 : ilst.sourceOffset;
    int capacity = ilst == null ? 0 : nativeProtocol ? ilst.sourceLength : ilst.size();
    if (ilst != null) {
      var children = meta.childList();
      int index = children.indexOf(ilst);
      var padding = new ArrayList<Atom>();
      for (Atom neighbor : adjacentPadding(children, index)) {
        capacity += nativeProtocol ? neighbor.sourceLength : neighbor.size();
        focusedOffset = Math.min(focusedOffset, neighbor.sourceOffset);
        padding.add(neighbor);
      }
      if (!nativeProtocol) {
        children.removeAll(padding);
      }
    } else {
      ilst = ensure(meta, "ilst");
    }
    var keep = new ArrayList<Atom>();
    for (Atom item : ilst.childList()) {
      String itemName = itemName(item);
      // upstream renderFreeForm cannot serialize a name with additional colons.
      // Full-property saves drop that item upstream, even when its value is unchanged.
      if (itemName.startsWith("----:") && itemName.split(":", -1).length != 3) {
        continue;
      }
      String key = keyFor(itemName);
      // Native erases the factory's canonical name. Case-varied free-form
      // items remain distinct physical entries even when their projected key matches.
      if (UnsupportedDataRemoval.contains(itemName)) {
        continue;
      }
      if (!item.name.equals("gnre")
        && (key == null
        || !itemName.equals(nameForKey(key))
        || nativeProtocol
        && !NativeSaveState.mp4SetPropertiesCalled()
        && itemName.startsWith("----:"))) {
        Atom retained = nativeProtocol ? nativeRetainedItem(item) : item;
        if (retained != null) {
          keep.add(retained);
        }
      }
    }
    for (var entry : properties.asMap().entrySet()) {
      String name = nameForKey(entry.getKey());
      if (name == null) {
        continue; // Native returns these in setProperties' rejected map.
      }
      if (nativeProtocol && !NativeSaveState.mp4SetPropertiesCalled() && name.startsWith("----:")) {
        continue;
      }
      Atom item = renderItem(name, entry.getValue());
      if (item != null) {
        keep.add(item);
      }
    }
    if (pictures != null) {
      keep.removeIf(item -> item.name.equals("covr"));
      if (!pictures.isEmpty()) {
        var cover = new ByteArrayOutputStream();
        for (Picture pic : pictures) {
          int kind =
            switch (pic.mimeType()) {
              case "image/jpeg" -> 13;
              case "image/png" -> 14;
              case "image/gif" -> 12;
              case "image/bmp" -> 27;
              default -> 0;
            };
          cover.write(data(kind, pic.data()));
        }
        keep.add(new Atom("covr", cover.toByteArray()));
      } else if (nativeProtocol && NativeSaveState.mp4PictureSetCalled()) {
        keep.add(new Atom("covr", new byte[0]));
      }
    }
    if (nativeProtocol) {
      var sorted = new TreeMap<String, Atom>();
      for (Atom item : keep) {
        sorted.putIfAbsent(itemName(item), item);
      }
      keep = new ArrayList<>(sorted.values());
    }
    ilst.childList().clear();
    ilst.childList().addAll(keep);
    if (nativeProtocol) {
      Map<Atom, Long> positions =
        nativeLayout == null ? new IdentityHashMap<>() : nativeLayout.positions;
      if (nativeLayout == null) {
        nativePositions(moov, original, positions);
      }
      if (oldIlst != null) {
        nativeReplaceExisting(
          f,
          tops,
          original,
          moov,
          List.of(moov, udta, meta),
          original.offset + original.header + focusedOffset,
          capacity,
          ilst.render(),
          positions);
      } else {
        byte[] items = ilst.render();
        byte[] newMeta =
          atom(
            "meta",
            concat(
              new byte[4],
              atom("hdlr", concat(new byte[8], bytes("mdirappl"), new byte[9])),
              items,
              nativePadding(items, -1)));
        byte[] insertion = oldUdta == null ? atom("udta", newMeta) : newMeta;
        long offset =
          oldUdta == null
            ? original.offset + 8
            : original.offset + original.header + oldUdta.sourceOffset + 8;
        f.requireStream().insert(new ByteVector(insertion), offset, 0);
        nativeUpdateParents(
          f, original, oldUdta == null ? List.of(moov) : List.of(moov, udta), insertion.length);
        nativeUpdateOffsets(f, tops, moov, offset, insertion.length, positions);
        if (nativeLayout != null) {
          // saveNew prepends a parsed child to the retained tree. In particular,
          // an existing meta without ilst stays present behind the new meta.
          moov.childList().clear();
          moov.childList().addAll(oldMovieChildren);
          if (oldUdta != null) {
            oldUdta.childList().clear();
            oldUdta.childList().addAll(oldUdtaChildren);
          }
          if (oldMeta != null) {
            oldMeta.childList().clear();
            oldMeta.childList().addAll(oldMetaChildren);
          }
          // Read the bytes after parent-size updates, as native Atom does. A
          // 64-bit parent still inserts at +8, so its size write can modify the
          // new child's header; reparsing the pristine buffer would invent a
          // different retained tree for the next save.
          byte[] insertedBytes = NativeTagIO.read(f.requireStream(), offset, insertion.length);
          Atom inserted =
            parse(insertedBytes, 0, insertedBytes.length, 0, false).getFirst();
          rebaseNativeAtom(inserted, Math.toIntExact(offset - original.offset - original.header));
          (oldUdta == null ? moov : oldUdta).childList().addFirst(inserted);
          nativePositions(inserted, original, positions);
        }
        // saveNew reparses the inserted atom after updating offsets.
        f.requireStream().seek(offset + insertion.length);
      }
      Atom stemUdta = moov.child("udta");
      Atom stem = stemUdta == null ? null : stemUdta.child("stem");
      var stemPlan = NativeSaveState.mp4Stem();
      boolean stemPresent =
        stemPlan == null
          ? stem != null && !UnsupportedDataRemoval.contains("stem")
          : stemPlan.present();
      byte[] stemBytes =
        stemPresent
          ? stemPlan == null ? stem.render() : atom("stem", stemPlan.manifest())
          : new byte[0];
      if (stem != null) {
        if (!stemPresent) {
          // saveExisting receives the three-atom STEM path. Its empty-data
          // branch removes path[-2] (udta), retaining the original Atom extent.
          long offset = original.offset + original.header + stemUdta.sourceOffset;
          f.requireStream().removeBlock(offset, stemUdta.sourceLength);
          moov.childList().remove(stemUdta);
          nativeUpdateParents(f, original, List.of(moov), -stemUdta.sourceLength);
          nativeUpdateOffsets(f, tops, moov, offset, -stemUdta.sourceLength, positions);
        } else {
          var children = stemUdta.childList();
          int stemCapacity = stem.sourceLength, index = children.indexOf(stem);
          long offset = original.offset + original.header + stem.sourceOffset;
          for (Atom neighbor : adjacentPadding(children, index)) {
            stemCapacity += neighbor.sourceLength;
            offset = Math.min(offset, original.offset + original.header + neighbor.sourceOffset);
          }
          nativeReplaceExisting(
            f,
            tops,
            original,
            moov,
            List.of(moov, stemUdta),
            offset,
            stemCapacity,
            stemBytes,
            positions);
        }
      } else if (stemPresent) {
        nativeSaveNewChild(f, tops, original, moov, stemBytes, positions);
      }
      return;
    }
    int spare = capacity - ilst.size();
    Atom nextPadding = null;
    if (spare != 0) {
      int length = spare >= 8 ? spare - 8 : ((ilst.size() + 1023) & ~1023) - ilst.size();
      byte[] padding = new byte[length];
      Arrays.fill(padding, (byte) 1);
      nextPadding = new Atom("free", padding);
      meta.childList().add(meta.childList().indexOf(ilst) + 1, nextPadding);
    }
    int renderedCapacity = ilst.size() + (nextPadding == null ? 0 : nextPadding.size());
    if (focused && renderedCapacity == capacity) {
      // Every enclosing header and all chunk positions stay unchanged. Rewrite only the
      // existing metadata span instead of materializing moov and visiting each chunk offset.
      byte[] replacement = ilst.render();
      if (nextPadding != null) {
        replacement = concat(replacement, nextPadding.render());
      }
      metadataEdit =
        new Rewrite.Edit(
          original.offset + original.header + focusedOffset, capacity, replacement);
    } else {
      long delta = moov.size() - original.size;
      // MP4::Tag::saveExisting adjusts from the replaced ilst/padding's
      // original position, including offsets that point inside the movie.
      // saveNew inserts at the beginning of the existing udta or movie body.
      long rewritePosition =
        original.offset
          + original.header
          + (focusedOffset >= 0 ? focusedOffset : Math.max(0, udta.sourceOffset + 8));
      adjustOffsets(moov, rewritePosition, delta);
      metadataEdit = new Rewrite.Edit(original.offset, original.size, moov.render());
      long metadataBytes = original.size;
      boolean firstFragment = true;
      for (Top top : tops) {
        if (top.name.equals("moof") && firstFragment) {
          firstFragment = false; // Native Atoms::find returns the first moof only.
          metadataBytes += top.size;
          if (metadataBytes > ReadOptions.DEFAULT.maxMetadataBytes()) {
            throw new IOException("Fragment metadata exceeds allocation limit");
          }
          byte[] raw = f.read(top.offset, (int) top.size);
          adjustFragment(raw, top.header, raw.length, rewritePosition, delta, 0);
          extraEdits.add(new Rewrite.Edit(top.offset, top.size, raw));
        }
      }
    }

    extraEdits.add(metadataEdit);
    Rewrite.replace(f, extraEdits);
  }

  private static byte[] nativePadding(byte[] data, int payloadSize) throws IOException {
    int size = payloadSize >= 0 ? payloadSize : ((data.length + 1023) & ~1023) - data.length;
    byte[] padding = new byte[size];
    Arrays.fill(padding, (byte) 1);
    return atom("free", padding);
  }

  private static Atom nativeRetainedItem(Atom item) throws IOException {
    String name = itemName(item);
    if (name.equals("covr")) {
      var cover = new ByteArrayOutputStream();
      for (Atom child : itemChildren(item.leafData())) {
        if (!child.name.equals("data")) {
          break;
        }
        if (child.leafData().length < 8) {
          continue;
        }
        int type = (int) u32be(child.leafData(), 0);
        byte[] value = Arrays.copyOfRange(child.leafData(), 8, child.leafData().length);
        if (!knownArtworkType(type)) {
          String mime = imageMime(value);
          type =
            switch (mime) {
              case "image/jpeg" -> 13;
              case "image/png" -> 14;
              case "image/gif" -> 12;
              case "image/bmp" -> 27;
              default -> 0;
            };
        }
        cover.write(data(type, value));
      }
      return cover.size() == 0 ? null : new Atom("covr", cover.toByteArray());
    }
    if (name.equals("stem") || isNumber(name)) {
      return item;
    }
    // The default four-byte factory handler is Text, even for names with no
    // PropertyMap key. Parsed text is reconstructed from native UTF8 strings;
    // source terminators and overallocated data bodies are not copied verbatim.
    var values = new ArrayList<String>();
    boolean free = name.startsWith("----:");
    for (Atom child : itemChildren(item.leafData())) {
      if (child.name.equals("data") && child.leafData().length >= 8) {
        long type = u32be(child.leafData(), 0);
        if (free && type != 1) {
          return item; // List<ByteVector> values retain binary bytes and type.
        }
        if (type == 1 || type == 0 && (name.equals("purl") || name.equals("egid"))) {
          byte[] value = child.leafData();
          values.add(TextBytes.decodeTerminated(value, 8, value.length - 8, UTF_8));
        }
      }
    }
    if (values.isEmpty()) {
      return null;
    }
    return renderItem(name, values);
  }

  private static void nativePositions(Atom atom, Top movie, Map<Atom, Long> positions) {
    positions.put(
      atom,
      atom.name.equals("moov") ? movie.offset : movie.offset + movie.header + atom.sourceOffset);
    if (atom.children() != null) {
      for (Atom child : atom.children()) {
        nativePositions(child, movie, positions);
      }
    }
  }

  private static void rebaseNativeAtom(Atom atom, int offset) {
    atom.sourceOffset += offset;
    if (atom.children() != null) {
      for (Atom child : atom.children()) {
        rebaseNativeAtom(child, offset);
      }
    }
  }

  private void nativeSaveNewChild(
    BinaryFile file,
    TopScan tops,
    Top original,
    Atom movie,
    byte[] child,
    Map<Atom, Long> positions
  )
    throws IOException {
    Atom udta = movie.child("udta");
    byte[] insertion = udta == null ? atom("udta", child) : child;
    long offset =
      udta == null
        ? original.offset + 8
        : original.offset + original.header + udta.sourceOffset + 8;
    var stream = file.requireStream();
    stream.insert(new ByteVector(insertion), offset, 0);
    nativeUpdateParents(
      file, original, udta == null ? List.of(movie) : List.of(movie, udta), insertion.length);
    nativeUpdateOffsets(file, tops, movie, offset, insertion.length, positions);
    byte[] bytes = NativeTagIO.read(stream, offset, insertion.length);
    Atom inserted = parse(bytes, 0, bytes.length, 0, false).getFirst();
    rebaseNativeAtom(inserted, Math.toIntExact(offset - original.offset - original.header));
    (udta == null ? movie : udta).childList().addFirst(inserted);
    nativePositions(inserted, original, positions);
    stream.seek(offset + insertion.length);
  }

  private void nativeReplaceExisting(
    BinaryFile file,
    TopScan tops,
    Top original,
    Atom movie,
    List<Atom> parents,
    long offset,
    int capacity,
    byte[] data,
    Map<Atom, Long> positions
  )
    throws IOException {
    long delta = data.length - (long) capacity;
    if (data.length > 0) {
      if (delta > 0 || delta < 0 && delta > -8) {
        data = concat(data, nativePadding(data, -1));
      } else if (delta < 0) {
        data = concat(data, nativePadding(data, (int) -delta - 8));
      }
      delta = data.length - (long) capacity;
      file.requireStream().insert(new ByteVector(data), offset, capacity);
      if (delta != 0) {
        nativeUpdateParents(file, original, parents, delta);
        nativeUpdateOffsets(file, tops, movie, offset, delta, positions);
      }
    } else {
      // Existing STEM removal strips that atom; ilst is always rendered even when empty.
      file.requireStream().removeBlock(offset, capacity);
      nativeUpdateParents(file, original, parents, -capacity);
      nativeUpdateOffsets(file, tops, movie, offset, -capacity, positions);
    }
  }

  private static void nativeUpdateParents(
    BinaryFile file, Top original, List<Atom> parents, long delta) throws IOException {
    var stream = file.requireStream();
    for (Atom parent : parents) {
      long offset =
        parent.name.equals("moov")
          ? original.offset
          : original.offset + original.header + parent.sourceOffset;
      byte[] size = NativeTagIO.read(stream, offset, 4);
      if (u32be(size, 0) == 1) {
        byte[] extended = NativeTagIO.read(stream, offset + 8, 8);
        put64be(extended, 0, u64be(extended, 0) + delta);
        NativeTagIO.write(stream, offset + 8, extended);
      } else {
        put32be(size, 0, u32be(size, 0) + delta);
        NativeTagIO.write(stream, offset, size);
      }
    }
  }

  private void nativeUpdateOffsets(
    BinaryFile file,
    TopScan tops,
    Atom movie,
    long threshold,
    long delta,
    Map<Atom, Long> positions
  )
    throws IOException {
    var stream = file.requireStream();
    for (String kind : List.of("stco", "co64")) {
      var atoms = new ArrayList<Atom>();
      nativeFind(movie, kind, atoms);
      int width = kind.equals("stco") ? 4 : 8;
      for (Atom atom : atoms) {
        long offset = positions.get(atom);
        if (offset > threshold) {
          offset += delta;
          positions.put(atom, offset);
        }
        if (atom.sourceLength < 12 + width) {
          continue;
        }
        byte[] data = NativeTagIO.read(stream, offset + 12, atom.sourceLength - 12);
        long count = data.length < 4 ? 0 : Math.min(u32be(data, 0), (data.length - 4) / width);
        stream.seek(offset + 16);
        for (int i = 0; i < count; i++) {
          long value = width == 4 ? u32be(data, 4 + i * width) : u64be(data, 4 + i * width);
          if (value > threshold) {
            value += delta;
          }
          byte[] rendered = new byte[width];
          if (width == 4) {
            put32be(rendered, 0, value);
          } else {
            put64be(rendered, 0, value);
          }
          stream.writeBlock(new ByteVector(rendered));
        }
      }
    }
    if (nativeLayout != null) {
      for (Atom header : nativeLayout.fragmentHeaders) {
        long offset = positions.get(header);
        if (offset > threshold) {
          offset += delta;
          positions.put(header, offset);
        }
        if (header.sourceLength < 24) {
          continue;
        }
        byte[] data = NativeTagIO.read(stream, offset + 9, header.sourceLength - 9);
        if (data.length >= 15 && (data[2] & 1) != 0) {
          long value = u64be(data, 7);
          if (value > threshold) {
            value += delta;
          }
          byte[] rendered = new byte[8];
          put64be(rendered, 0, value);
          NativeTagIO.write(stream, offset + 16, rendered);
        }
      }
      return;
    }
    Top fragment = tops.stream().filter(t -> t.name.equals("moof")).findFirst().orElse(null);
    if (fragment != null) {
      // The native atom tree retains first-moof offsets; only tfhd entries are relocated.
      byte[] raw =
        NativeTagIO.read(
          stream,
          fragment.offset + (fragment.offset > threshold ? delta : 0),
          fragment.size);
      var atoms = parse(raw, fragment.header, raw.length, 1, false);
      var headers = new ArrayList<Atom>();
      for (Atom atom : atoms) {
        nativeFind(atom, "tfhd", headers);
      }
      for (Atom header : headers) {
        long offset = fragment.offset + header.sourceOffset;
        if (offset > threshold) {
          offset += delta;
        }
        if (header.sourceLength < 24) {
          continue;
        }
        byte[] data = NativeTagIO.read(stream, offset + 9, header.sourceLength - 9);
        if (data.length >= 15 && (data[2] & 1) != 0) {
          long value = u64be(data, 7);
          if (value > threshold) {
            value += delta;
          }
          byte[] rendered = new byte[8];
          put64be(rendered, 0, value);
          NativeTagIO.write(stream, offset + 16, rendered);
        }
      }
    }
  }

  private static void nativeFind(Atom atom, String name, List<Atom> result) {
    if (atom.children() == null) {
      return;
    }
    for (Atom child : atom.children()) {
      if (child.name.equals(name)) {
        result.add(child);
      }
      nativeFind(child, name, result);
    }
  }

  private static void adjustFragment(
    byte[] b, int start, int end, long threshold, long delta, int depth) throws IOException {
    if (depth > MAX_ATOM_DEPTH) {
      return;
    }
    for (int p = start; p + 8 <= end; ) {
      long n = u32be(b, p);
      String name = ascii(b, p + 4, 4);
      int h = 8;
      if (n == 1) {
        if (p + 16 > end) {
          throw new IOException("Short fragment atom");
        }
        n = u64be(b, p + 8);
        h = 16;
      }
      if (n == 0) {
        n = end - p;
      }
      if (n < h || n > end - p) {
        throw new IOException("Invalid fragment atom");
      }
      if (name.equals("traf")) {
        adjustFragment(b, p + h, p + (int) n, threshold, delta, depth + 1);
      } else if (name.equals("tfhd")) {
        int payload = p + h;
        if (n - h < 8) {
          throw new IOException("Short tfhd");
        }
        long flags = u32be(b, payload) & 0xffffff;
        if ((flags & 1) != 0) {
          if (n - h < 16) {
            throw new IOException("Short tfhd base offset");
          }
          long offset = u64be(b, payload + 8);
          if (offset > threshold) {
            put64be(b, payload + 8, offset + delta);
          }
        }
      }
      p += (int) n;
    }
  }

  private static List<Atom> adjacentPadding(List<Atom> atoms, int index) {
    var result = new ArrayList<Atom>(2);
    if (index > 0 && atoms.get(index - 1).name.equals("free")) {
      result.add(atoms.get(index - 1));
    }
    if (index + 1 < atoms.size() && atoms.get(index + 1).name.equals("free")) {
      result.add(atoms.get(index + 1));
    }
    return result;
  }

  private static Atom ensure(Atom parent, String name) {
    Atom child = parent.child(name);
    if (child == null) {
      child = new Atom(name, name.equals("meta") ? new byte[4] : new byte[0], new ArrayList<>());
      parent.childList().add(child);
    }
    return child;
  }

  private static void adjustOffsets(Atom a, long threshold, long delta) {
    int width = chunkOffsetWidth(a);
    if (width != 0) {
      byte[] p = a.leafData();
      long count = Math.min(u32be(p, 4), (p.length - 8) / width);
      for (int j = 0; j < count; j++) {
        int at = 8 + j * width;
        long old = width == 4 ? u32be(p, at) : u64be(p, at);
        if (old > threshold) {
          long next = old + delta; // Native narrows stco results to uint32, including overflow.
          if (width == 4) {
            put32be(p, at, next);
          } else {
            put64be(p, at, next);
          }
        }
      }
    }
    if (a.children() != null) {
      for (Atom c : a.children()) {
        adjustOffsets(c, threshold, delta);
      }
    }
  }

  private static int chunkOffsetWidth(Atom atom) {
    if (!(atom.name.equals("stco") || atom.name.equals("co64")) || atom.payload() == null) {
      return 0;
    }
    byte[] payload = atom.payload();
    if (payload.length < (atom.name.equals("stco") ? 8 : 12)) {
      return 0;
    }
    return atom.name.equals("stco") ? 4 : 8;
  }

  private static Atom renderItem(String name, List<String> values) throws IOException {
    var out = new ByteArrayOutputStream();
    String atomName = name;
    String first = values.isEmpty() ? "" : values.getFirst();
    if (name.startsWith("----:")) {
      String[] parts = name.split(":", -1);
      if (parts.length != 3) {
        return null;
      }
      atomName = "----";
      out.write(atom("mean", concat(new byte[4], parts[1].getBytes(StandardCharsets.UTF_8))));
      out.write(atom("name", concat(new byte[4], parts[2].getBytes(StandardCharsets.UTF_8))));
    }
    if (name.equals("trkn") || name.equals("disk")) {
      String[] pair = first.split("/", 2);
      byte[] b = new byte[name.equals("trkn") ? 8 : 6];
      put16be(b, 2, number(pair[0]));
      if (pair.length > 1) {
        put16be(b, 4, number(pair[1]));
      }
      out.write(data(0, b));
    } else if (isNumber(name)) {
      int width =
        BOOL_NAMES.contains(name) || BYTE_NAMES.contains(name)
          ? 1
          : UINT_NAMES.contains(name)
          ? 4
          : LONG_NAMES.contains(name) ? 8 : 2;
      long n =
        LONG_NAMES.contains(name)
          ? TextNumbers.parseLong(first, 10)
          : TextNumbers.parseInt(first);
      if (BOOL_NAMES.contains(name)) {
        n = n == 0 ? 0 : 1;
      }
      byte[] b = new byte[width];
      for (int i = width - 1; i >= 0; i--) {
        b[i] = (byte) n;
        n >>>= 8;
      }
      out.write(data(21, b));
    } else {
      for (String value : values) {
        out.write(
          data(
            name.equals("purl") || name.equals("egid") ? 0 : 1,
            value.getBytes(StandardCharsets.UTF_8)));
      }
    }
    return new Atom(atomName, out.toByteArray());
  }

  private static int number(String s) {
    return TextNumbers.parseInt(s);
  }

  private static byte[] data(int type, byte[] data) throws IOException {
    byte[] head = new byte[8];
    put32be(head, 0, type);
    return atom("data", concat(head, data));
  }

  private static byte[] concat(byte[]... parts) {
    int size = 0;
    for (byte[] part : parts) {
      size = Math.addExact(size, part.length);
    }
    byte[] out = new byte[size];
    int offset = 0;
    for (byte[] part : parts) {
      System.arraycopy(part, 0, out, offset, part.length);
      offset += part.length;
    }
    return out;
  }

  private static byte[] atom(String name, byte[] data) throws IOException {
    if (data.length > Integer.MAX_VALUE - 8) {
      throw new IOException("Atom too large");
    }
    byte[] out = new byte[data.length + 8];
    put32be(out, 0, out.length);
    System.arraycopy(bytes(name), 0, out, 4, 4);
    System.arraycopy(data, 0, out, 8, data.length);
    return out;
  }
}
