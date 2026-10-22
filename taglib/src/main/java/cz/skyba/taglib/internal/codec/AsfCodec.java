// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.codec;

import cz.skyba.taglib.*;
import cz.skyba.taglib.internal.*;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

import static cz.skyba.taglib.internal.Binary.*;
import static java.nio.charset.StandardCharsets.UTF_16LE;

/// ASF header objects and multi-valued typed attributes; unknown objects survive writes verbatim.
public final class AsfCodec implements Codec {
  private Header nativeHeader;

  public record Details(AudioProperties common, int codecId, String codecName,
                        String codecDescription, boolean encrypted) {
  }

  private Details nativeProperties;

  public static Details readProperties(BinaryFile file, ReadStyle style) throws IOException {
    var codec = new AsfCodec();
    codec.readNative(file, new ReadOptions(true, style, ReadOptions.DEFAULT.maxMetadataBytes()));
    return codec.nativeProperties;
  }

  private static final String HEADER = "3026b2758e66cf11a6d900aa0062ce6c",
    FILE = "a1dcab8c47a9cf118ee400c00c205365",
    STREAM = "9107dcb7b7a9cf118ee600c00c205365",
    CONTENT = "3326b2758e66cf11a6d900aa0062ce6c",
    EXTENDED = "40a4d0d207e3d21197f000a0c95ea850",
    EXTENSION = "b503bf5f2ea9cf118ee300c00c205365",
    METADATA = "eacbf8c5af5b77488467aa8c44fa4cca",
    LIBRARY = "941c23449894d149a1411d134e457054",
    CODEC_LIST = "4052d1861d31d011a3a400a0c90348f6";
  // The pinned extended-encryption literal includes a space and is truncated to 16 bytes.
  private static final Set<String> ENCRYPTION = Set.of(
    "fbb3112223bdd211b4b700a0c955fc6e", "14e68a29222620174cb935dae07ee928",
    "b69b077aa4da124ea5ca91d38dc11a8d");

  private record Mapping(String name, String key, int type) {
  }

  private record CodecDescription(String name, String description) {
  }

  private record ContentDescription(
    String title, String artist, String copyright, String comment, String rating) {
  }

  private static final List<Mapping> MAPPINGS = mappings();
  private static final Map<String, Mapping> MAPPINGS_BY_NAME = mappingIndex(true);
  private static final Map<String, Mapping> MAPPINGS_BY_KEY = mappingIndex(false);
  private static final Set<String> SUPPORTED_PROPERTY_KEYS = mappingKeys();
  private static final List<String> BASIC =
    List.of("TITLE", "ARTIST", "COPYRIGHT", "COMMENT", "RATING");

  public static Set<String> supportedPropertyKeys() {
    return SUPPORTED_PROPERTY_KEYS;
  }

  private static Set<String> mappingKeys() {
    var keys = new HashSet<>(List.of("TITLE", "ARTIST", "COPYRIGHT", "COMMENT"));
    MAPPINGS.forEach(mapping -> keys.add(mapping.key()));
    return Set.copyOf(keys);
  }

  @Override
  public PropertyMap unsupportedProperties(PropertyMap properties) {
    var rejected = PropertyMap.builder();
    var keys = supportedPropertyKeys();
    properties
      .asMap()
      .forEach(
        (key, values) -> {
          if (!keys.contains(key)) {
            rejected.put(key, values);
          }
        });
    return rejected.build();
  }

  private static List<Mapping> mappings() {
    var mappings = new ArrayList<Mapping>();
    mappings.add(new Mapping("WM/AlbumTitle", "ALBUM", 0));
    mappings.add(new Mapping("WM/AlbumArtist", "ALBUMARTIST", 0));
    mappings.add(new Mapping("WM/AuthorURL", "ARTISTWEBPAGE", 0));
    mappings.add(new Mapping("WM/Composer", "COMPOSER", 0));
    mappings.add(new Mapping("WM/Writer", "LYRICIST", 0));
    mappings.add(new Mapping("WM/Conductor", "CONDUCTOR", 0));
    mappings.add(new Mapping("WM/ModifiedBy", "REMIXER", 0));
    mappings.add(new Mapping("WM/Year", "DATE", 0));
    mappings.add(new Mapping("WM/OriginalAlbumTitle", "ORIGINALALBUM", 0));
    mappings.add(new Mapping("WM/OriginalArtist", "ORIGINALARTIST", 0));
    mappings.add(new Mapping("WM/OriginalFilename", "ORIGINALFILENAME", 0));
    mappings.add(new Mapping("WM/OriginalLyricist", "ORIGINALLYRICIST", 0));
    mappings.add(new Mapping("WM/OriginalReleaseYear", "ORIGINALDATE", 0));
    mappings.add(new Mapping("WM/Producer", "PRODUCER", 0));
    mappings.add(new Mapping("WM/ContentGroupDescription", "WORK", 0));
    mappings.add(new Mapping("WM/SubTitle", "SUBTITLE", 0));
    mappings.add(new Mapping("WM/SetSubTitle", "DISCSUBTITLE", 0));
    mappings.add(new Mapping("WM/TrackNumber", "TRACKNUMBER", 0));
    mappings.add(new Mapping("WM/PartOfSet", "DISCNUMBER", 0));
    mappings.add(new Mapping("WM/Genre", "GENRE", 0));
    mappings.add(new Mapping("WM/BeatsPerMinute", "BPM", 0));
    mappings.add(new Mapping("WM/Mood", "MOOD", 0));
    mappings.add(new Mapping("WM/InitialKey", "INITIALKEY", 0));
    mappings.add(new Mapping("WM/ISRC", "ISRC", 0));
    mappings.add(new Mapping("WM/Lyrics", "LYRICS", 0));
    mappings.add(new Mapping("WM/Media", "MEDIA", 0));
    mappings.add(new Mapping("WM/Publisher", "LABEL", 0));
    mappings.add(new Mapping("WM/CatalogNo", "CATALOGNUMBER", 0));
    mappings.add(new Mapping("WM/Barcode", "BARCODE", 0));
    mappings.add(new Mapping("WM/EncodedBy", "ENCODEDBY", 0));
    mappings.add(new Mapping("WM/EncodingSettings", "ENCODING", 0));
    mappings.add(new Mapping("WM/EncodingTime", "ENCODINGTIME", 4));
    mappings.add(new Mapping("WM/AudioFileURL", "FILEWEBPAGE", 0));
    mappings.add(new Mapping("WM/AlbumSortOrder", "ALBUMSORT", 0));
    mappings.add(new Mapping("WM/AlbumArtistSortOrder", "ALBUMARTISTSORT", 0));
    mappings.add(new Mapping("WM/ArtistSortOrder", "ARTISTSORT", 0));
    mappings.add(new Mapping("WM/TitleSortOrder", "TITLESORT", 0));
    mappings.add(new Mapping("WM/Script", "SCRIPT", 0));
    mappings.add(new Mapping("WM/Language", "LANGUAGE", 0));
    mappings.add(new Mapping("WM/ARTISTS", "ARTISTS", 0));
    mappings.add(new Mapping("ASIN", "ASIN", 0));
    mappings.add(new Mapping("MusicBrainz/Track Id", "MUSICBRAINZ_TRACKID", 0));
    mappings.add(new Mapping("MusicBrainz/Artist Id", "MUSICBRAINZ_ARTISTID", 0));
    mappings.add(new Mapping("MusicBrainz/Album Id", "MUSICBRAINZ_ALBUMID", 0));
    mappings.add(new Mapping("MusicBrainz/Album Artist Id", "MUSICBRAINZ_ALBUMARTISTID", 0));
    mappings.add(new Mapping("MusicBrainz/Album Release Country", "RELEASECOUNTRY", 0));
    mappings.add(new Mapping("MusicBrainz/Album Status", "RELEASESTATUS", 0));
    mappings.add(new Mapping("MusicBrainz/Album Type", "RELEASETYPE", 0));
    mappings.add(new Mapping("MusicBrainz/Release Group Id", "MUSICBRAINZ_RELEASEGROUPID", 0));
    mappings.add(new Mapping("MusicBrainz/Release Track Id", "MUSICBRAINZ_RELEASETRACKID", 0));
    mappings.add(new Mapping("MusicBrainz/Work Id", "MUSICBRAINZ_WORKID", 0));
    mappings.add(new Mapping("MusicIP/PUID", "MUSICIP_PUID", 0));
    mappings.add(new Mapping("Acoustid/Id", "ACOUSTID_ID", 0));
    mappings.add(new Mapping("Acoustid/Fingerprint", "ACOUSTID_FINGERPRINT", 0));
    mappings.add(new Mapping("replaygain_track_gain", "REPLAYGAIN_TRACK_GAIN", 0));
    mappings.add(new Mapping("replaygain_track_peak", "REPLAYGAIN_TRACK_PEAK", 0));
    mappings.add(new Mapping("replaygain_album_gain", "REPLAYGAIN_ALBUM_GAIN", 0));
    mappings.add(new Mapping("replaygain_album_peak", "REPLAYGAIN_ALBUM_PEAK", 0));
    mappings.add(new Mapping("WM/MediaClassPrimaryID", "MEDIACLASSPRIMARYID", 6));
    mappings.add(new Mapping("WM/MediaClassSecondaryID", "MEDIACLASSSECONDARYID", 6));
    mappings.add(new Mapping("WM/WMCollectionGroupID", "COLLECTIONGROUPID", 6));
    mappings.add(new Mapping("WM/WMCollectionID", "COLLECTIONID", 6));
    mappings.add(new Mapping("WM/WMContentID", "CONTENTID", 6));
    mappings.add(new Mapping("WM/ContentDistributor", "CONTENTDISTRIBUTOR", 0));
    mappings.add(new Mapping("WM/ParentalRating", "PARENTALRATING", 0));
    mappings.add(new Mapping("WM/Period", "PERIOD", 0));
    mappings.add(new Mapping("WM/PromotionURL", "PROMOTIONURL", 0));
    mappings.add(new Mapping("WM/ToolName", "TOOLNAME", 0));
    mappings.add(new Mapping("WM/ToolVersion", "TOOLVERSION", 0));
    mappings.add(new Mapping("WM/Provider", "PROVIDER", 0));
    mappings.add(new Mapping("WM/UniqueFileIdentifier", "UNIQUEFILEIDENTIFIER", 0));
    mappings.add(new Mapping("WMFSDKVersion", "WMFSDKVERSION", 0));
    mappings.add(new Mapping("WMFSDKNeeded", "WMFSDKNEEDED", 0));
    mappings.add(new Mapping("DeviceConformanceTemplate", "DEVICECONFORMANCETEMPLATE", 0));
    mappings.add(new Mapping("MediaFoundationVersion", "MEDIAFOUNDATIONVERSION", 0));
    mappings.add(new Mapping("IsVBR", "ISVBR", 2));
    mappings.add(new Mapping("PeakValue", "PEAKVALUE", 3));
    mappings.add(new Mapping("AverageLevel", "AVERAGELEVEL", 3));
    return List.copyOf(mappings);
  }

  private enum ObjectKind {
    OPAQUE, ATTRIBUTES, CONTENT_DESCRIPTION
  }

  private static ObjectKind objectKind(String guid) {
    return guid.equals(CONTENT) ? ObjectKind.CONTENT_DESCRIPTION
      : isAttributes(guid) ? ObjectKind.ATTRIBUTES : ObjectKind.OPAQUE;
  }

  private static ObjectKind nativeObjectKind(String guid, boolean child) {
    if (!child && guid.equals(CONTENT)) {
      return ObjectKind.CONTENT_DESCRIPTION;
    }
    boolean attributes = child ? guid.equals(METADATA) || guid.equals(LIBRARY) : guid.equals(EXTENDED);
    return attributes ? ObjectKind.ATTRIBUTES : ObjectKind.OPAQUE;
  }

  private static final class ObjectData {
    final String guid;
    byte[] data;
    List<ObjectData> children;
    ObjectKind kind;
    long declaredSize;

    ObjectData(String guid, byte[] data) {
      this(guid, data, objectKind(guid));
    }

    ObjectData(String guid, byte[] data, ObjectKind kind) {
      this.guid = guid;
      this.data = data;
      this.kind = kind;
    }
  }

  private record Attribute(
    String name, int type, int stream, int language, byte[] value, byte[] raw) {
  }

  private record Header(long size, List<ObjectData> objects) {
  }

  private static Map<String, Mapping> mappingIndex(boolean nativeNames) {
    var result = new HashMap<String, Mapping>();
    for (var mapping : MAPPINGS) {
      String key = nativeNames ? normalizedName(mapping.name) : mapping.key;
      result.putIfAbsent(key, mapping);
    }
    return Collections.unmodifiableMap(result);
  }

  private static String normalizedName(String name) {
    return Ascii.upper(name);
  }

  private static Mapping byName(String name) {
    return MAPPINGS_BY_NAME.get(normalizedName(name));
  }

  private static Mapping byKey(String key) {
    return MAPPINGS_BY_KEY.get(key);
  }

  private static String guid(byte[] b) {
    int p = 0;
    return HexFormat.of().formatHex(b, p, p + 16);
  }

  private static Header header(BinaryFile f, ReadOptions options) throws IOException {
    return header(f, options, 0);
  }

  private static Header header(BinaryFile f, ReadOptions options, long start) throws IOException {
    return header(f, options, start, _ -> {
    });
  }

  private static Header header(
    BinaryFile f, ReadOptions options, long start,
    Consumer<Header> partial
  ) throws IOException {
    byte[] signature = f.readNative(start, 16);
    if (signature.length != 16 || !guid(signature).equals(HEADER)) {
      throw new InvalidAudioFileException("Not an ASF file");
    }
    var objects = new ArrayList<ObjectData>();
    partial.accept(new Header(0, objects));
    if (start > Long.MAX_VALUE - 16) {
      throw new InvalidAudioFileException("ASF cursor overflow");
    }
    var reader = new NativeReader(f, options, start + 16);
    long n = reader.number(8, true), count = reader.number(4, true);
    partial.accept(new Header(n, objects));
    if (count > 50000) {
      throw new InvalidAudioFileException("Invalid ASF header size/count");
    }
    reader.advance(2);
    f.seekNative(reader.position);
    for (long i = 0; i < count; i++) {
      objects.add(reader.object(false, Long.MAX_VALUE));
    }
    if (!reader.valid
      || objects.stream().noneMatch(o -> o.guid.equals(FILE))
      || objects.stream().noneMatch(o -> o.guid.equals(STREAM))) {
      throw new InvalidAudioFileException("Missing ASF file/stream properties");
    }
    return new Header(n, objects);
  }

  /// The native reader follows object counts and actual reads, not the declared header extent.
  private static final class NativeReader {
    final BinaryFile file;
    final ReadOptions options;
    long position;
    long remainingBytes;
    boolean valid = true;

    NativeReader(BinaryFile file, ReadOptions options, long position) {
      this.file = file;
      this.options = options;
      this.position = position;
      remainingBytes = options.maxMetadataBytes();
    }

    byte[] bytes(long count) throws IOException {
      // This is a cumulative read budget, not a per-object limit or a total JVM heap bound.
      if (count < 0 || count > remainingBytes) {
        throw new IOException("ASF metadata exceeds configured limit");
      }
      byte[] value = file.readNative(position, (int) count);
      if (value.length > remainingBytes) {
        throw new IOException("ASF metadata exceeds configured limit");
      }
      remainingBytes -= value.length;
      advance(value.length);
      return value;
    }

    void advance(long count) throws IOException {
      try {
        position = Math.addExact(position, count);
      } catch (ArithmeticException overflow) {
        throw new IOException("ASF cursor overflow", overflow);
      }
    }

    long number(int width, boolean required) throws IOException {
      byte[] value = bytes(width);
      if (value.length != width) {
        if (required) {
          throw new IOException("Truncated ASF number");
        }
        return 0;
      }
      return new ByteVector(value).toLong(0, false);
    }

    ObjectData object(boolean child, long remaining) throws IOException {
      byte[] identifier = bytes(16);
      if (identifier.length != 16) {
        throw new IOException("Truncated ASF object GUID");
      }
      String id = guid(identifier);
      long size = number(8, true);
      if (child && (size < 24 || size > remaining)) {
        throw new IOException("Invalid ASF child object size");
      }
      var object = new ObjectData(id, new byte[0], nativeObjectKind(id, child));
      object.declaredSize = size;
      if (object.kind == ObjectKind.CONTENT_DESCRIPTION) {
        var lengths = new ContentLengths(
          (int) number(2, false), (int) number(2, false), (int) number(2, false),
          (int) number(2, false), (int) number(2, false));
        object.data = new EncodedContent(
          utf16(utf16(bytes(lengths.title))), utf16(utf16(bytes(lengths.artist))),
          utf16(utf16(bytes(lengths.copyright))), utf16(utf16(bytes(lengths.comment))),
          utf16(utf16(bytes(lengths.rating)))).render();
      } else if (object.kind == ObjectKind.ATTRIBUTES) {
        if (size < 26 || position > file.size() || size - 24 > file.size() - position) {
          valid = false;
          return object;
        }
        long end = position + size - 24;
        int count = (int) number(2, false);
        var records = new ArrayList<byte[]>();
        for (int i = 0; i < count; i++) {
          if (position >= end) {
            valid = false;
            break;
          }
          byte[] record = attribute(id);
          if (record != null) {
            records.add(record);
          }
          if (position > end) {
            valid = false;
            break;
          }
        }
        object.data = attributeBlock(records);
      } else if (!child && id.equals(EXTENSION)) {
        if (size < 46) {
          valid = false;
          return object;
        }
        advance(18);
        file.seekNative(position);
        long length;
        try {
          length = number(4, true);
        } catch (IOException exception) {
          valid = false;
          return object;
        }
        if (length > size - 46) {
          valid = false;
          return object;
        }
        object.children = new ArrayList<>();
        for (long consumed = 0; consumed < length; ) {
          if (object.children.size() >= 50000) {
            valid = false;
            break;
          }
          ObjectData next;
          try {
            next = object(true, length - consumed);
          } catch (IOException exception) {
            valid = false;
            break;
          }
          // The native loop advances its declared data count independently of the consumed cursor.
          consumed += next.declaredSize;
          object.children.add(next);
        }
        object.data = new byte[22];
      } else if (size > 24 && size <= file.size()) {
        object.data = bytes(size - 24);
      }
      return object;
    }

    byte[] attribute(String carrier) throws IOException {
      boolean extended = carrier.equals(EXTENDED);
      int language = 0, stream = 0, nameLength, type;
      long length;
      String name;
      if (extended) {
        nameLength = (int) number(2, false);
        name = utf16(bytes(nameLength));
        type = (int) number(2, false);
        length = number(2, false);
      } else {
        language = (int) number(2, false);
        if (!carrier.equals(LIBRARY)) {
          language = 0;
        }
        stream = (int) number(2, false);
        nameLength = (int) number(2, false);
        type = (int) number(2, false);
        length = number(4, false);
        name = utf16(bytes(nameLength));
      }
      byte[] data;
      switch (type) {
        case 0 -> data = utf16(utf16(bytes(length)));
        case 1, 6 -> data = bytes(length);
        case 2, 3, 4, 5 -> {
          int width = type == 4 ? 8 : type == 3 || type == 2 && extended ? 4 : 2;
          long value = number(width, false);
          if (type == 2) {
            value = value == 0 ? 0 : 1;
          }
          data = new byte[width];
          for (int i = 0; i < width; i++) {
            data[i] = (byte) (value >>> (i * 8));
          }
        }
        default -> {
          return null;
        }
      }
      if (name.isEmpty()) {
        return null;
      }
      byte[] encodedName = utf16(name);
      if (extended) {
        byte[] prefix = new byte[2], suffix = new byte[4];
        put16le(prefix, 0, encodedName.length);
        put16le(suffix, 0, type);
        put16le(suffix, 2, data.length);
        return concatenate(prefix, encodedName, suffix, data);
      }
      byte[] prefix = new byte[12];
      put16le(prefix, 0, language);
      put16le(prefix, 2, stream);
      put16le(prefix, 4, encodedName.length);
      put16le(prefix, 6, type);
      put32le(prefix, 8, data.length);
      return concatenate(prefix, encodedName, data);
    }
  }

  @Override
  public AudioMetadata read(Path path, ReadOptions options) throws IOException {
    try (var f = new BinaryFile(path)) {
      return read(f, options);
    }
  }

  @Override
  public AudioMetadata read(BinaryFile f, ReadOptions options) throws IOException {
    return read(header(f, options));
  }

  @Override
  public AudioMetadata readNative(BinaryFile f, ReadOptions options) throws IOException {
    try {
      nativeHeader = header(f, options, f.initialPosition(), partial -> nativeHeader = partial);
    } catch (IOException | InvalidAudioFileException invalid) {
      if (nativeHeader != null) {
        read(nativeHeader);
      }
      throw invalid;
    }
    return read(nativeHeader);
  }

  /// Captured during the single native constructor parse, including assigned partial state.
  public cz.skyba.taglib.asf.Tag liveTag() throws IOException {
    if (nativeHeader == null) {
      return null;
    }
    var tag = new cz.skyba.taglib.asf.Tag();
    var values = new ArrayList<cz.skyba.taglib.asf.Attribute>();
    collectAttributes(nativeHeader.objects, values);
    for (var value : values) {
      tag.addAttribute(value.name(), value);
    }
    for (var object : nativeHeader.objects) {
      if (object.kind == ObjectKind.CONTENT_DESCRIPTION) {
        var fields = content(object.data);
        tag.setTitle(fields.title());
        tag.setArtist(fields.artist());
        tag.setCopyright(fields.copyright());
        tag.setComment(fields.comment());
        tag.setRating(fields.rating());
      }
    }
    return tag;
  }

  public Details liveProperties() {
    return nativeProperties;
  }

  private AudioMetadata read(Header h) throws IOException {
    var p = new TreeMap<String, List<String>>();
    var pictures = new ArrayList<Picture>();
    readObjects(h.objects, p, pictures);
    var nativeAttributes = new ArrayList<cz.skyba.taglib.asf.Attribute>();
    collectAttributes(h.objects, nativeAttributes);
    nativeAttributes.sort(Comparator.comparing(cz.skyba.taglib.asf.Attribute::name));
    for (var mapping : MAPPINGS) {
      p.remove(mapping.key);
    }
    p.putAll(propertiesForAttributes(nativeAttributes).asMap());
    long length = 0;
    int rate = 0, bitrate = 0, channels = 0, bits = 0;
    boolean lossless = false, encrypted = false;
    int codecId = 0;
    String codecName = "", codecDescription = "";
    // Native ASF::File::read receives no readProperties argument and always creates it.
    for (var o : h.objects) {
      byte[] b = o.data;
      if (o.guid.equals(FILE) && b.length >= 64) {
        double ms = u64le(b, 40) / 10000.0 - u64le(b, 56) + .5;
        if (ms > Integer.MIN_VALUE && ms < Integer.MAX_VALUE) {
          length = (int) ms;
        }
      } else if (o.guid.equals(STREAM) && b.length >= 70) {
        codecId = u16le(b, 54);
        channels = u16le(b, 56);
        rate = (int) u32le(b, 58);
        bitrate = (int) Math.round(u32le(b, 62) * 8.0 / 1000);
        bits = u16le(b, 68);
        lossless = codecId == 0x163;
      } else if (o.guid.equals(CODEC_LIST)) {
        CodecDescription fields = codecListFields(b);
        if (fields != null) {
          codecName = fields.name();
          codecDescription = fields.description();
        }
      } else if (ENCRYPTION.contains(o.guid)) {
        encrypted = true;
      }
    }
    nativeProperties = new Details(new AudioProperties(length, bitrate, rate, channels, bits, lossless),
      codecId, codecName, codecDescription, encrypted);
    var projected = new PropertyMap(p).toBuilder();
    var opaqueNames = new TreeSet<String>();
    unsupportedAttributes(h.objects, opaqueNames);
    opaqueNames.forEach(projected::addUnsupportedData);
    PropertyMap properties = projected.build();
    Tag common = canonicalCommon(properties, nativeAttributes);
    return new AudioMetadata(
      Format.ASF,
      properties,
      nativeProperties.common(),
      pictures,
      common);
  }

  /// Native codec-list parsing keeps the first audio entry of each top-level object.
  private static CodecDescription codecListFields(byte[] bytes) {
    if (bytes.length <= 20) {
      return null;
    }
    var data = new ByteVector(bytes);
    int count = (int) data.toUnsignedInt(16, false);
    long position = 20;
    for (int i = 0; i < count && position < bytes.length; i++) {
      int type = availableShort(bytes, position);
      position += 2;
      int nameLength = availableShort(bytes, position);
      position += 2;
      long namePosition = position;
      position += nameLength * 2L;
      int descriptionLength = availableShort(bytes, position);
      position += 2;
      long descriptionPosition = position;
      position += descriptionLength * 2L;
      int informationLength = availableShort(bytes, position);
      position += 2 + informationLength * 2L;
      if (type == 2) {
        return new CodecDescription(
          codecText(data, namePosition, nameLength), codecText(data, descriptionPosition, descriptionLength));
      }
    }
    return null;
  }

  private static int availableShort(byte[] data, long position) {
    if (position >= data.length) {
      return 0;
    }
    int result = data[(int) position] & 255;
    return position + 1 < data.length ? result | (data[(int) position + 1] & 255) << 8 : result;
  }

  private static String codecText(ByteVector data, long offset, int units) {
    var bytes = offset >= data.size() ? new ByteVector() : data.mid((int) offset, units * 2);
    return Ascii.stripWhiteSpace(bytes.decodeTerminatedText(UTF_16LE));
  }

  /// Common getters use exact canonical names while PropertyMap translates case-insensitively.
  public static Tag canonicalCommon(
    PropertyMap properties, List<cz.skyba.taglib.asf.Attribute> attributes) {
    var album = new ArrayList<String>();
    var genre = new ArrayList<String>();
    cz.skyba.taglib.asf.Attribute year = null, track = null, legacy = null;
    for (var attribute : attributes) {
      String text = attribute.type() == 0 ? attribute.text() : "";
      if (attribute.name().equals("WM/AlbumTitle")) {
        album.add(text);
      }
      if (attribute.name().equals("WM/Genre")) {
        genre.add(text);
      }
      if (year == null && attribute.name().equals("WM/Year")) {
        year = attribute;
      }
      if (track == null && attribute.name().equals("WM/TrackNumber")) {
        track = attribute;
      }
      if (legacy == null && attribute.name().equals("WM/Track")) {
        legacy = attribute;
      }
    }
    long y =
      year != null && year.type() == 0
        ? Integer.toUnsignedLong(TextNumbers.parseInt(utf16(year.data())))
        : 0;
    long t = 0;
    if (track != null) {
      if (track.type() == 3) {
        t = track.toUnsignedInt();
      } else if (track.type() == 0) {
        t = Integer.toUnsignedLong(TextNumbers.parseInt(utf16(track.data())));
      }
    } else if (legacy != null && legacy.type() >= 2 && legacy.type() <= 5) {
      t = legacy.unsigned().longValue() & 0xffffffffL;
    }
    return new Tag(
      properties.first("TITLE"),
      properties.first("ARTIST"),
      String.join(" / ", album),
      properties.first("COMMENT"),
      String.join(" / ", genre),
      y,
      t);
  }

  private static void unsupportedAttributes(List<ObjectData> objects, Set<String> names)
    throws IOException {
    for (var object : objects) {
      if (object.kind == ObjectKind.ATTRIBUTES) {
        for (var attribute : attributes(object)) {
          if (byName(attribute.name) == null) {
            names.add(attribute.name);
          }
        }
      }
      if (object.children != null) {
        unsupportedAttributes(object.children, names);
      }
    }
  }

  private static void readObjects(
    List<ObjectData> objects, Map<String, List<String>> props, List<Picture> pictures)
    throws IOException {
    for (var o : objects) {
      if (o.kind == ObjectKind.CONTENT_DESCRIPTION) {
        ContentDescription values = content(o.data);
        putContentField(props, BASIC.get(0), values.title());
        putContentField(props, BASIC.get(1), values.artist());
        putContentField(props, BASIC.get(2), values.copyright());
        putContentField(props, BASIC.get(3), values.comment());
      } else if (o.kind == ObjectKind.ATTRIBUTES) {
        for (var a : attributes(o)) {
          if (a.name.equals("WM/Picture")) {
            Picture pic = picture(a.value);
            if (pic != null) {
              pictures.add(pic);
            }
          }
          Mapping map = byName(a.name);
          if (map != null) {
            String value = value(a);
            var list = props.computeIfAbsent(map.key, _ -> new ArrayList<>());
            if (!list.contains(value)) {
              list.add(value);
            }
          }
        }
      }
      if (o.children != null) {
        readObjects(o.children, props, pictures);
      }
    }
  }

  private static void putContentField(Map<String, List<String>> properties, String key, String value) {
    if (value.isEmpty()) {
      properties.remove(key);
    } else {
      properties.put(key, new ArrayList<>(List.of(value)));
    }
  }

  private record ContentLengths(int title, int artist, int copyright, int comment, int rating) {
  }

  private record EncodedContent(byte[] title, byte[] artist, byte[] copyright, byte[] comment, byte[] rating) {
    byte[] render() {
      var fields = List.of(title, artist, copyright, comment, rating);
      byte[] sizes = new byte[10];
      for (int i = 0; i < fields.size(); i++) {
        put16le(sizes, i * 2, fields.get(i).length);
      }
      var result = new ByteArrayOutputStream();
      result.writeBytes(sizes);
      fields.forEach(result::writeBytes);
      return result.toByteArray();
    }
  }

  private static byte[] checkedContentField(String text) throws IOException {
    byte[] result = utf16(text);
    if (result.length > 65535) {
      throw new IOException("ASF content field too large");
    }
    return result;
  }

  private static ContentDescription content(byte[] b) throws IOException {
    if (b.length < 10) {
      throw new IOException("Short ASF content description");
    }
    var reader = new ContentDescriptionReader(b);
    return new ContentDescription(
      reader.next(), reader.next(), reader.next(), reader.next(), reader.next());
  }

  private static final class ContentDescriptionReader {
    private final byte[] data;
    private int field;
    private int position = 10;

    private ContentDescriptionReader(byte[] data) {
      this.data = data;
    }

    private String next() throws IOException {
      int length = u16le(data, field++ * 2);
      if (length > data.length - position) {
        throw new IOException("Truncated ASF content field");
      }
      String value = utf16(Arrays.copyOfRange(data, position, position + length));
      position += length;
      return value;
    }
  }

  private static boolean isAttributes(String g) {
    return g.equals(EXTENDED) || g.equals(METADATA) || g.equals(LIBRARY);
  }

  /// Typed ASF API; values keep stream/language identity that PropertyMap cannot express.
  public static List<cz.skyba.taglib.asf.Attribute> readAttributes(Path path) throws IOException {
    try (var file = new BinaryFile(path)) {
      var result = new ArrayList<cz.skyba.taglib.asf.Attribute>();
      collectAttributes(header(file, ReadOptions.DEFAULT).objects, result);
      // Native AttributeListMap iterates sorted names; retain insertion order within each name.
      result.sort(Comparator.comparing(cz.skyba.taglib.asf.Attribute::name));
      return List.copyOf(result);
    }
  }

  public static List<cz.skyba.taglib.asf.Attribute> readAttributes(BinaryFile file) throws IOException {
    return readAttributes(file, 0);
  }

  public static List<cz.skyba.taglib.asf.Attribute> readAttributes(BinaryFile file, long origin)
    throws IOException {
    var result = new ArrayList<cz.skyba.taglib.asf.Attribute>();
    collectAttributes(header(file, ReadOptions.DEFAULT, origin).objects, result);
    result.sort(Comparator.comparing(cz.skyba.taglib.asf.Attribute::name));
    return List.copyOf(result);
  }

  public static boolean isMappedAttribute(String name) {
    return byName(name) != null;
  }

  public static List<cz.skyba.taglib.asf.Attribute> attributesForProperty(String key, List<String> values) throws IOException {
    Mapping mapping = byKey(key);
    if (mapping == null) return List.of();
    var result = new ArrayList<cz.skyba.taglib.asf.Attribute>();
    if (mapping.type == 0) {
      for (String value : values) result.add(cz.skyba.taglib.asf.Attribute.text(mapping.name, value));
    } else {
      var raw = new ArrayList<byte[]>();
      for (String value : values) raw.add(attribute(mapping, value));
      collectAttributes(List.of(new ObjectData(LIBRARY, attributeBlock(raw))), result);
    }
    result.sort(Comparator.comparing(cz.skyba.taglib.asf.Attribute::name));
    return List.copyOf(result);
  }

  public static PropertyMap propertiesForAttributes(List<cz.skyba.taglib.asf.Attribute> attributes) {
    var projected = new TreeMap<String, List<String>>();
    var opaque = new TreeSet<String>();
    for (var item : attributes) {
      Mapping m = byName(item.name());
      if (m == null) {
        opaque.add(item.name());
      } else {
        String text =
          item.type() == 0
            ? item.text()
            : value(
            new Attribute(
              item.name(),
              item.type(),
              item.stream(),
              item.language(),
              item.data(),
              new byte[0]));
        var values = projected.computeIfAbsent(m.key, _ -> new ArrayList<>());
        if (!values.contains(text)) {
          values.add(text);
        }
      }
    }
    var result = new PropertyMap(projected).toBuilder();
    opaque.forEach(result::addUnsupportedData);
    return result.build();
  }

  private static void collectAttributes(
    List<ObjectData> objects, List<cz.skyba.taglib.asf.Attribute> result) throws IOException {
    for (var object : objects) {
      if (object.kind == ObjectKind.ATTRIBUTES) {
        for (var attribute : attributes(object)) {
          result.add(
            cz.skyba.taglib.asf.Attribute.parsed(
              attribute.name,
              attribute.type,
              attribute.stream,
              attribute.language,
              attribute.type == 2
                ? new byte[]{(byte) (value(attribute).equals("0") ? 0 : 1), 0}
                : attribute.value));
        }
      }
      if (object.children != null) {
        collectAttributes(object.children, result);
      }
    }
  }

  public static void writeAttributes(
    Path path, List<cz.skyba.taglib.asf.Attribute> values) throws IOException {
    Header header;
    byte[] replacement;
    try (var file = new BinaryFile(path)) {
      header = header(file, ReadOptions.DEFAULT);
      placeAttributes(header, values);
      replacement = render(header.objects);
    }
    Rewrite.replace(path, 0, header.size, replacement);
  }

  /// Native save redistributes the complete AttributeListMap into its existing three carriers.
  private static void placeAttributes(Header header, List<cz.skyba.taglib.asf.Attribute> values)
    throws IOException {
    var extended = new ArrayList<byte[]>();
    var metadata = new ArrayList<byte[]>();
    var library = new ArrayList<byte[]>();
    var firstGlobal = new HashSet<String>();
    var firstStream = new HashSet<String>();
    var sorted = new ArrayList<>(values);
    sorted.sort(Comparator.comparing(cz.skyba.taglib.asf.Attribute::name));
    long total = 0;
    for (var value : sorted) {
      byte[] name = utf16(value.name()), data = value.data();
      if (data.length > ReadOptions.DEFAULT.maxMetadataBytes()
        || (total += name.length + (long) data.length + 12)
        > ReadOptions.DEFAULT.maxMetadataBytes()) {
        throw new IOException("ASF attributes exceed metadata limits");
      }
      int kind = 2;
      if (value.dataSize() <= 65535 && value.type() != 6 && value.language() == 0) {
        if (value.stream() == 0 && firstGlobal.add(value.name())) {
          kind = 0;
        } else if (value.stream() != 0 && firstStream.add(value.name())) {
          kind = 1;
        }
      }
      // Boolean values occupy DWORD in Extended Content Description and WORD elsewhere.
      if (value.type() == 2) {
        boolean on = value.unsigned().signum() != 0;
        data = new byte[kind == 0 ? 4 : 2];
        data[0] = (byte) (on ? 1 : 0);
      }
      byte[] raw;
      if (kind == 0) {
        byte[] prefix = new byte[2], tail = new byte[4];
        put16le(prefix, 0, name.length);
        put16le(tail, 0, value.type());
        put16le(tail, 2, data.length);
        raw = concatenate(prefix, name, tail, data);
      } else {
        byte[] prefix = new byte[12];
        put16le(prefix, 0, kind == 2 ? value.language() : 0);
        put16le(prefix, 2, value.stream());
        put16le(prefix, 4, name.length);
        put16le(prefix, 6, value.type());
        put32le(prefix, 8, data.length);
        raw = concatenate(prefix, name, data);
      }
      (kind == 0 ? extended : kind == 1 ? metadata : library).add(raw);
    }
    emptyAttributeCarriers(header.objects);
    ensureObject(header.objects, EXTENDED).data = attributeBlock(extended);
    ObjectData extension = null;
    for (var object : header.objects) {
      if (object.children != null) {
        extension = object;
      }
    }
    if (extension == null) {
      byte[] data = new byte[22];
      System.arraycopy(HexFormat.of().parseHex("11d2d3abbaa9cf118ee600c00c205365"), 0, data, 0, 16);
      put16le(data, 16, 6);
      extension = new ObjectData(EXTENSION, data);
      extension.children = new ArrayList<>();
      header.objects.add(extension);
    }
    var metadataCarrier = lastAttributeCarrier(header.objects, METADATA);
    if (metadataCarrier == null) {
      metadataCarrier = ensureObject(extension.children, METADATA);
    }
    var libraryCarrier = lastAttributeCarrier(header.objects, LIBRARY);
    if (libraryCarrier == null) {
      libraryCarrier = ensureObject(extension.children, LIBRARY);
    }
    metadataCarrier.data = attributeBlock(metadata);
    libraryCarrier.data = attributeBlock(library);
  }

  private static void emptyAttributeCarriers(List<ObjectData> objects) {
    for (var object : objects) {
      if (object.kind == ObjectKind.ATTRIBUTES) {
        object.data = new byte[2];
      }
      if (object.children != null) {
        emptyAttributeCarriers(object.children);
      }
    }
  }

  private static ObjectData lastAttributeCarrier(List<ObjectData> objects, String guid) {
    ObjectData found = null;
    for (var object : objects) {
      if (object.kind == ObjectKind.ATTRIBUTES && object.guid.equals(guid)) {
        found = object;
      }
      if (object.children != null) {
        var child = lastAttributeCarrier(object.children, guid);
        if (child != null) {
          found = child;
        }
      }
    }
    return found;
  }

  private static ObjectData ensureObject(List<ObjectData> objects, String guid) {
    ObjectData found = null;
    for (var object : objects) {
      if (object.guid.equals(guid)) {
        found = object;
      }
    }
    if (found == null) {
      found = new ObjectData(guid, new byte[2]);
      objects.add(found);
    }
    return found;
  }

  private static byte[] concatenate(byte[]... parts) throws IOException {
    var out = new ByteArrayOutputStream();
    for (var part : parts) {
      out.write(part);
    }
    return out.toByteArray();
  }

  private static List<Attribute> attributes(ObjectData o) throws IOException {
    byte[] b = o.data;
    if (b.length < 2) {
      throw new IOException("Truncated ASF attribute count");
    }
    int count = u16le(b, 0), p = 2;
    var out = new ArrayList<Attribute>();
    for (int i = 0; i < count; i++) {
      int start = p, nameLength, type, stream = 0, language = 0;
      long size;
      String name;
      if (o.guid.equals(EXTENDED)) {
        if (p + 2 > b.length) {
          throw new IOException("Truncated ASF attribute");
        }
        nameLength = u16le(b, p);
        p += 2;
        if (nameLength > b.length - p - 4) {
          throw new IOException("Truncated ASF attribute name");
        }
        name = utf16(Arrays.copyOfRange(b, p, p + nameLength));
        p += nameLength;
        type = u16le(b, p);
        size = u16le(b, p + 2);
        p += 4;
      } else {
        if (p + 12 > b.length) {
          throw new IOException("Truncated ASF attribute");
        }
        language = o.guid.equals(LIBRARY) ? u16le(b, p) : 0;
        stream = u16le(b, p + 2);
        nameLength = u16le(b, p + 4);
        type = u16le(b, p + 6);
        size = u32le(b, p + 8);
        p += 12;
        if (nameLength > b.length - p) {
          throw new IOException("Truncated ASF attribute name");
        }
        name = utf16(Arrays.copyOfRange(b, p, p + nameLength));
        p += nameLength;
      }
      if (size > b.length - p) {
        throw new IOException("Truncated ASF attribute value");
      }
      byte[] value = Arrays.copyOfRange(b, p, p + (int) size);
      p += (int) size;
      out.add(new Attribute(name, type, stream, language, value, Arrays.copyOfRange(b, start, p)));
    }
    return out;
  }

  private static String value(Attribute a) {
    byte[] b = a.value;
    return switch (a.type) {
      case 0 -> utf16(b);
      case 2, 3, 4, 5 -> {
        long n = 0;
        for (int i = Math.min(b.length, 8) - 1; i >= 0; i--) {
          n = (n << 8) | (b[i] & 255);
        }
        yield Long.toUnsignedString(a.type == 2 ? (n == 0 ? 0 : 1) : n);
      }
      case 6 -> {
        if (b.length != 16) {
          yield "";
        }
        String s = HexFormat.of().withUpperCase().formatHex(b);
        yield s.substring(0, 8)
          + "-"
          + s.substring(8, 12)
          + "-"
          + s.substring(12, 16)
          + "-"
          + s.substring(16, 20)
          + "-"
          + s.substring(20);
      }
      default -> "";
    };
  }

  private static String utf16(byte[] b) {
    return TextBytes.decodeTerminated(b, UTF_16LE);
  }

  private static byte[] utf16(String s) {
    return TextBytes.encode(s + '\0', UTF_16LE);
  }

  private static Picture picture(byte[] b) {
    if (b.length < 9) {
      return null;
    }
    int type = b[0] & 255;
    long n = u32le(b, 1);
    int p = 5;
    while (p + 1 < b.length && (b[p] != 0 || b[p + 1] != 0)) {
      p += 2;
    }
    if (p + 2 > b.length) {
      return null;
    }
    String mime = utf16(Arrays.copyOfRange(b, 5, p));
    int start = p + 2;
    p = start;
    while (p + 1 < b.length && (b[p] != 0 || b[p + 1] != 0)) {
      p += 2;
    }
    if (p + 2 > b.length || n != b.length - p - 2) {
      return null;
    }
    String description = utf16(Arrays.copyOfRange(b, start, p));
    return new Picture(type, mime, description, Arrays.copyOfRange(b, p + 2, p + 2 + (int) n));
  }

  @Override
  public void write(Path path, PropertyMap properties) throws IOException {
    write(path, properties, null);
  }

  @Override
  public void write(
    BinaryFile f, PropertyMap properties, List<Picture> pictures)
    throws IOException {
    write(f, properties, pictures, header(f, ReadOptions.DEFAULT), false);
  }

  @Override
  public void writeNative(
    BinaryFile f,
    long constructorOffset,
    PropertyMap properties,
    List<Picture> pictures
  )
    throws IOException {
    write(
      f,
      properties,
      pictures,
      nativeHeader != null ? nativeHeader : header(f, ReadOptions.DEFAULT, constructorOffset),
      true);
  }

  private void write(
    BinaryFile f,
    PropertyMap properties,
    List<Picture> pictures,
    Header header,
    boolean nativeSave
  )
    throws IOException {
    byte[] replacement;
    filter(header.objects, pictures != null);
    ObjectData content = null;
    for (var object : header.objects) {
      if (object.kind == ObjectKind.CONTENT_DESCRIPTION) {
        content = object;
      }
    }
    String rating = content == null ? "" : content(content.data).rating();
    if (content == null) {
      content = new ObjectData(CONTENT, new byte[0]);
      header.objects.add(content);
    }
    content.data = new EncodedContent(
      checkedContentField(String.join(" ", properties.get("TITLE"))),
      checkedContentField(String.join(" ", properties.get("ARTIST"))),
      checkedContentField(String.join(" ", properties.get("COPYRIGHT"))),
      checkedContentField(String.join(" ", properties.get("COMMENT"))),
      checkedContentField(rating)).render();
    for (ObjectData duplicate : header.objects) {
      if (duplicate != content && duplicate.kind == ObjectKind.CONTENT_DESCRIPTION) {
        duplicate.data = content.data.clone();
      }
    }
    var added = new ArrayList<byte[]>();
    for (var entry : properties.asMap().entrySet()) {
      if (BASIC.subList(0, 4).contains(entry.getKey())) {
        continue;
      }
      Mapping m = byKey(entry.getKey());
      if (m == null) {
        throw new IOException("Unmapped ASF property: " + entry.getKey());
      }
      for (String value : entry.getValue()) {
        added.add(attribute(m, value));
      }
    }
    if (pictures != null) {
      for (Picture pic : pictures) {
        var raw = new ByteArrayOutputStream();
        raw.write(pic.type());
        byte[] n = new byte[4];
        put32le(n, 0, pic.data().length);
        raw.write(n);
        raw.write(utf16(pic.mimeType()));
        raw.write(utf16(pic.description()));
        raw.write(pic.data());
        added.add(binaryAttribute(raw.toByteArray()));
      }
    }
    var values = new ArrayList<cz.skyba.taglib.asf.Attribute>();
    var exact = NativeSaveState.asfAttributes();
    if (exact == null) {
      collectAttributes(header.objects, values);
    } else {
      for (var item : exact) {
        if (!UnsupportedDataRemoval.contains(item.name())
          && !(pictures != null && item.name().equals("WM/Picture"))) {
          values.add(item);
        }
      }
    }
    for (var item : attributes(new ObjectData(LIBRARY, attributeBlock(added)))) {
      if (exact == null || item.name.equals("WM/Picture")) {
        values.add(
          new cz.skyba.taglib.asf.Attribute(
            item.name, item.type, item.stream, item.language, item.value));
      }
    }
    placeAttributes(header, values);
    replacement = render(header.objects);

    if (nativeSave) {
      // ASF deliberately writes the fixed header at absolute offsets, even if construction began
      // at another cursor. The GUID itself is never rewritten by native save().
      var stream = f.requireStream();
      stream.seek(16);
      stream
        .writeBlock(new ByteVector(Arrays.copyOfRange(replacement, 16, 30)));
      stream
        .insert(
          new ByteVector(
            Arrays.copyOfRange(replacement, 30, replacement.length)),
          30,
          header.size - 30);
      nativeHeader = new Header(replacement.length, header.objects);
    } else {
      Rewrite.replace(f, 0, header.size, replacement);
    }
  }

  private static void filter(List<ObjectData> objects, boolean replacePictures) throws IOException {
    for (ObjectData o : objects) {
      if (o.kind == ObjectKind.ATTRIBUTES) {
        var keep = new ArrayList<byte[]>();
        for (Attribute a : attributes(o)) {
          if (byName(a.name) == null
            && (!replacePictures || !a.name.equals("WM/Picture"))
            && !UnsupportedDataRemoval.contains(a.name)) {
            keep.add(a.raw);
          }
        }
        o.data = attributeBlock(keep);
      }
      if (o.children != null) {
        filter(o.children, replacePictures);
      }
    }
  }

  /// Native attribute names accepted by removeUnsupportedProperties can include mapped entries.
  public static String propertyKeyForAttribute(String name) {
    var mapping = byName(name);
    return mapping == null ? null : mapping.key;
  }

  public static String attributeNameForProperty(String key) {
    var mapping = byKey(key);
    return mapping == null ? null : mapping.name;
  }

  /// Render the live tag from retained objects, with native absolute offsets and I/O order.
  public void saveLive(BinaryFile file, cz.skyba.taglib.asf.Tag tag) throws IOException {
    if (nativeHeader == null) {
      throw new TagLibException("ASF has no parsed header to save");
    }
    byte[] replacement = renderTag(nativeHeader, tag);
    // Validate the unsigned header extent before subtracting: high-bit values cannot be
    // represented by Java stream positions and must not wrap into a positive replace span.
    if (nativeHeader.size < 30) {
      throw new TagLibException("ASF header size is smaller than the fixed header");
    }
    long old = nativeHeader.size - 30;
    var stream = file.requireStream();
    if (stream instanceof ByteVectorStream
      && replacement.length > nativeHeader.size && nativeHeader.size > stream.length()) {
      throw new TagLibException("ASF header replacement exceeds the memory stream");
    }
    stream.seek(16);
    stream.writeBlock(new ByteVector(Arrays.copyOfRange(replacement, 16, 24)));
    stream.writeBlock(new ByteVector(Arrays.copyOfRange(replacement, 24, 28)));
    stream.writeBlock(new ByteVector(Arrays.copyOfRange(replacement, 28, 30)));
    stream.insert(new ByteVector(Arrays.copyOfRange(replacement, 30, replacement.length)), 30, old);
    nativeHeader = new Header(replacement.length, nativeHeader.objects);
  }

  public static void writeTag(Path path, cz.skyba.taglib.asf.Tag tag) throws IOException {
    Header header;
    byte[] replacement;
    try (var file = new BinaryFile(path)) {
      header = header(file, ReadOptions.DEFAULT);
      replacement = renderTag(header, tag);
    }
    Rewrite.replace(path, 0, header.size, replacement);
  }

  private static byte[] renderTag(Header header, cz.skyba.taglib.asf.Tag tag) throws IOException {
    // Native Content Description lengths narrow to WORD while retaining every rendered byte.
    ensureObject(header.objects, CONTENT).kind = ObjectKind.CONTENT_DESCRIPTION;
    var fields = new ContentDescription(
      tag.title(), tag.artist(), tag.copyright(), tag.comment(), tag.rating());
    byte[] lengths = new byte[10];
    var body = new ByteArrayOutputStream();
    var values = List.of(
      utf16(fields.title()),
      utf16(fields.artist()),
      utf16(fields.copyright()),
      utf16(fields.comment()),
      utf16(fields.rating()));
    for (int i = 0; i < values.size(); i++) {
      put16le(lengths, i * 2, values.get(i).length);
    }
    body.writeBytes(lengths);
    for (var value : values) {
      body.writeBytes(value);
    }
    for (var object : header.objects) {
      if (object.kind == ObjectKind.CONTENT_DESCRIPTION) {
        object.data = body.toByteArray();
      }
    }
    placeAttributes(header, tag.attributes());
    return render(header.objects);
  }

  private static byte[] binaryAttribute(byte[] value) throws IOException {
    byte[] name = utf16("WM/Picture"), head = new byte[12];
    put16le(head, 4, name.length);
    put16le(head, 6, 1);
    put32le(head, 8, value.length);
    var out = new ByteArrayOutputStream();
    out.write(head);
    out.write(name);
    out.write(value);
    return out.toByteArray();
  }

  private static byte[] attributeBlock(List<byte[]> entries) throws IOException {
    var out = new ByteArrayOutputStream();
    byte[] count = new byte[2];
    put16le(count, 0, entries.size());
    out.write(count);
    for (byte[] b : entries) {
      out.write(b);
    }
    return out.toByteArray();
  }

  private static byte[] attribute(Mapping m, String value) throws IOException {
    byte[] name = utf16(m.name), data;
    try {
      data =
        switch (m.type) {
          case 0 -> utf16(value);
          // This unusual TRUE handling is the verified upstream behavior, not a conventional
          // Boolean parser. A later upstream release may deliberately change it.
          case 2 -> new byte[]{
            (byte)
              (!value.isEmpty() && !value.equals("0") && !value.equalsIgnoreCase("TRUE")
                ? 1
                : 0),
            0
          };
          case 6 -> {
            String hex = value.replaceAll("[^0-9a-fA-F]", "");
            yield hex.length() == 32 ? HexFormat.of().parseHex(hex) : new byte[0];
          }
          default -> {
            long n = TextNumbers.parseUnsignedLong(value, 10);
            byte[] b = new byte[m.type == 4 ? 8 : m.type == 3 ? 4 : 2];
            for (int i = 0; i < b.length; i++) {
              b[i] = (byte) n;
              n >>>= 8;
            }
            yield b;
          }
        };
    } catch (IllegalArgumentException e) {
      throw new IOException("Invalid ASF " + m.key + " value", e);
    }
    if (name.length > 65535) {
      throw new IOException("ASF name too large");
    }
    byte[] head = new byte[12];
    put16le(head, 4, name.length);
    put16le(head, 6, m.type);
    put32le(head, 8, data.length);
    var out = new ByteArrayOutputStream();
    out.write(head);
    out.write(name);
    out.write(data);
    return out.toByteArray();
  }

  private static final byte[] HEADER_BYTES = HexFormat.of().parseHex(HEADER);
  private static final byte[] EXTENSION_PREFIX = HexFormat.of().parseHex("11d2d3abbaa9cf118ee600c00c2053650600");

  private static int objectBytes(List<ObjectData> objects, Map<ObjectData, Integer> lengths) {
    int length = 0;
    for (ObjectData object : objects) {
      int payload = object.children == null ? object.data.length : Math.addExact(22, objectBytes(object.children, lengths));
      int size = Math.addExact(24, payload);
      lengths.put(object, size);
      length = Math.addExact(length, size);
    }
    return length;
  }

  private static byte[] render(List<ObjectData> objects) {
    var lengths = new IdentityHashMap<ObjectData, Integer>();
    byte[] result = new byte[Math.addExact(30, objectBytes(objects, lengths))];
    System.arraycopy(HEADER_BYTES, 0, result, 0, 16);
    put64le(result, 16, result.length);
    put32le(result, 24, objects.size());
    result[28] = 1;
    result[29] = 2;
    renderObjects(objects, result, 30, lengths);
    return result;
  }

  private static int renderObjects(List<ObjectData> objects, byte[] output, int offset, Map<ObjectData, Integer> lengths) {
    for (ObjectData object : objects) {
      int length = lengths.get(object);
      System.arraycopy(HexFormat.of().parseHex(object.guid), 0, output, offset, 16);
      put64le(output, offset + 16, length);
      offset += 24;
      if (object.children == null) {
        System.arraycopy(object.data, 0, output, offset, object.data.length);
        offset += object.data.length;
      } else {
        System.arraycopy(EXTENSION_PREFIX, 0, output, offset, 18);
        put32le(output, offset + 18, length - 46);
        offset = renderObjects(object.children, output, offset + 22, lengths);
      }
    }
    return offset;
  }
}
