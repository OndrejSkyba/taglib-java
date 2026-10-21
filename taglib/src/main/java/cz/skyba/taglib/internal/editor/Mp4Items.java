// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.editor;

import cz.skyba.taglib.MetadataEditException;
import cz.skyba.taglib.internal.id3.Id3v1;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

import static cz.skyba.taglib.internal.Binary.*;

/// Physical MP4 item values. All unknown payloads remain opaque and losslessly removable.
final class Mp4Items {
  private static final Pattern SIGNED_INTEGER = Pattern.compile("-?[0-9]+");
  private static final Pattern UNSIGNED_INTEGER = Pattern.compile("[0-9]+");
  private static final Pattern DECIMAL =
    Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");

  static final String FREE = "----:com.apple.iTunes:";
  static final Map<String, String> PROPERTIES = properties();
  static final Map<String, String> NAMES = reverse(PROPERTIES);

  private static Map<String, String> properties() {
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
    result.put(
      "----:com.apple.iTunes:MusicBrainz Album Artist Id", "MUSICBRAINZ_ALBUMARTISTID");
    result.put(
      "----:com.apple.iTunes:MusicBrainz Release Group Id", "MUSICBRAINZ_RELEASEGROUPID");
    result.put(
      "----:com.apple.iTunes:MusicBrainz Release Track Id", "MUSICBRAINZ_RELEASETRACKID");
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
    result.put(FREE + "initialkey", "INITIALKEY");
    return Collections.unmodifiableMap(result);
  }

  private static Map<String, String> reverse(Map<String, String> source) {
    var result = new HashMap<String, String>();
    source.forEach((name, property) -> result.put(property, name));
    return Map.copyOf(result);
  }

  enum Kind {
    TEXT,
    PAIR,
    BOOL,
    BYTE,
    UINT,
    INT,
    LONG,
    ARTWORK,
    BINARY
  }

  record Value(byte[] bytes, long type, long locale) {
  }

  private sealed interface Content {
    List<String> text();

    List<Value> values();
  }

  private record TextContent(List<String> text, List<Value> values) implements Content {
  }

  private record NumberContent(Kind kind, List<String> text, List<Value> values) implements Content {
  }

  private record ArtworkContent(List<String> text, List<Value> values) implements Content {
  }

  private record OpaqueContent(List<String> text, List<Value> values) implements Content {
  }

  private record KeyedContent(Kind kind, List<String> text, List<Value> values) implements Content {
  }

  record Item(String nativeName, Content content) {
    Item(String nativeName, Kind kind, List<String> text, List<Value> values, boolean keyed) {
      this(nativeName, keyed ? new KeyedContent(kind, text, values) : switch (kind) {
        case TEXT -> new TextContent(text, values);
        case ARTWORK -> new ArtworkContent(text, values);
        case BINARY -> new OpaqueContent(text, values);
        case PAIR, BOOL, BYTE, UINT, INT, LONG -> new NumberContent(kind, text, values);
      });
    }

    Kind kind() {
      return switch (content) {
        case TextContent _ -> Kind.TEXT;
        case ArtworkContent _ -> Kind.ARTWORK;
        case OpaqueContent _ -> Kind.BINARY;
        case NumberContent c -> c.kind();
        case KeyedContent c -> c.kind();
      };
    }

    List<String> text() {
      return content.text();
    }

    List<Value> values() {
      return content.values();
    }

    boolean keyed() {
      return content instanceof KeyedContent;
    }

    boolean same(Item other) {
      if (other == null
        || !nativeName.equals(other.nativeName)
        || kind() != other.kind()
        || !text().equals(other.text())
        || keyed() != other.keyed()
        || values().size() != other.values().size()) {
        return false;
      }
      for (int i = 0; i < values().size(); i++) {
        var a = values().get(i);
        var b = other.values().get(i);
        if (a.type != b.type || a.locale != b.locale || !Arrays.equals(a.bytes, b.bytes)) {
          return false;
        }
      }
      return true;
    }
  }

  private Mp4Items() {
  }

  static Item parse(String name, byte[] payload, boolean keyed) {
    if (keyed) {
      var values = dataValues(payload, true);
      if (values.isEmpty()) {
        return binary(name, true);
      }
      var text = new ArrayList<String>();
      try {
        for (var value : values) {
          text.add(decode(value));
        }
      } catch (IOException | MetadataEditException _) {
        text.clear();
      }
      boolean pictures = values.stream().noneMatch(v -> mime(v.type).isEmpty());
      return new Item(
        name,
        pictures ? Kind.ARTWORK : text.isEmpty() ? Kind.BINARY : Kind.TEXT,
        List.copyOf(text),
        values,
        true);
    }
    if (name.equals("----")) {
      return freeform(payload);
    }
    var values = dataValues(payload, false);
    if (values.isEmpty() || name.equals("stem")) {
      return binary(name, false);
    }
    var first = values.getFirst();
    try {
      if (name.equals("covr")) {
        var covers = new ArrayList<Value>();
        for (var value : values) {
          covers.add(new Value(value.bytes, imageType(value.type, value.bytes), 0));
        }
        return new Item(name, Kind.ARTWORK, List.of(), List.copyOf(covers), false);
      }
      if (name.equals("gnre")) {
        if (first.bytes.length < 2 || (short) u16be(first.bytes, 0) <= 0) {
          return binary("©gen", false);
        }
        return text("©gen", List.of(Id3v1.genre((short) u16be(first.bytes, 0) - 1)));
      }
      if (Set.of("trkn", "disk").contains(name)) {
        if (first.bytes.length < 6) {
          return binary(name, false);
        }
        return new Item(
          name,
          Kind.PAIR,
          List.of(
            Short.toString((short) u16be(first.bytes, 2)),
            Short.toString((short) u16be(first.bytes, 4))),
          List.of(),
          false);
      }
      Kind kind = conventionalKind(name, first.type);
      if (kind == Kind.TEXT) {
        var text = new ArrayList<String>();
        for (var value : values) {
          if (value.type == 1 || name.equals("purl") || name.equals("egid")) {
            text.add(new String(value.bytes, StandardCharsets.UTF_8));
          }
        }
        if (text.isEmpty()) {
          return binary(name, false);
        }
        return new Item(name, Kind.TEXT, List.copyOf(text), List.of(), false);
      }
      String number =
        switch (kind) {
          case BOOL -> first.bytes.length > 0 && first.bytes[0] != 0 ? "1" : "0";
          case BYTE -> Integer.toString(first.bytes.length > 0 ? first.bytes[0] & 255 : 0);
          case INT -> first.bytes.length >= 2 ? Short.toString((short) u16be(first.bytes, 0)) : "0";
          case UINT -> first.bytes.length >= 4 ? Long.toString(u32be(first.bytes, 0)) : "0";
          case LONG -> first.bytes.length >= 8 ? Long.toString(u64be(first.bytes, 0)) : "0";
          default -> throw new IOException("Binary metadata");
        };
      return new Item(name, kind, List.of(number), List.of(), false);
    } catch (IOException | MetadataEditException | IllegalArgumentException _) {
      return binary(name, false);
    }
  }

  private static Item binary(String name, boolean keyed) {
    return new Item(name, Kind.BINARY, List.of(), List.of(), keyed);
  }

  private static Kind conventionalKind(String name, long type) {
    return switch (name) {
      case "cpil", "pgap", "pcst", "shwm" -> Kind.BOOL;
      case "tmpo", "©mvi", "©mvc", "hdvd" -> Kind.INT;
      case "rate" -> type == 1 ? Kind.TEXT : Kind.INT;
      case "tvsn", "tves", "sfID", "atID", "geID", "cmID" -> Kind.UINT;
      case "cnID", "plID" -> Kind.LONG;
      case "stik", "rtng", "akID" -> Kind.BYTE;
      default -> Kind.TEXT;
    };
  }

  private static Item freeform(byte[] payload) {
    byte[] meaning = null, name = null;
    var values = new ArrayList<Value>();
    int at = 0;
    while (at < payload.length) {
      if (payload.length - at < 12) {
        return binary("----", false);
      }
      long length = u32be(payload, at);
      if (length < 12 || length > payload.length - at) {
        return binary("----", false);
      }
      String type = ascii(payload, at + 4, 4);
      if (type.equals("mean") && meaning == null) {
        meaning = Arrays.copyOfRange(payload, at + 12, at + (int) length);
      } else if (type.equals("name") && name == null) {
        name = Arrays.copyOfRange(payload, at + 12, at + (int) length);
      } else if (type.equals("data") && length >= 16) {
        values.add(
          new Value(
            Arrays.copyOfRange(payload, at + 16, at + (int) length),
            u32be(payload, at + 8),
            u32be(payload, at + 12)));
      } else {
        return binary("----", false);
      }
      at += (int) length;
    }
    if (meaning == null || name == null || values.isEmpty()) {
      return binary("----", false);
    }
    String key =
      "----:"
        + new String(meaning, StandardCharsets.UTF_8)
        + ":"
        + new String(name, StandardCharsets.UTF_8);
    if (values.getFirst().type != 1) {
      return new Item(key, Kind.BINARY, List.of(), List.copyOf(values), false);
    }
    return new Item(
      key,
      Kind.TEXT,
      values.stream().map(v -> new String(v.bytes, StandardCharsets.UTF_8)).toList(),
      List.of(),
      false);
  }

  static List<Value> dataValues(byte[] data, boolean strict) {
    var values = new ArrayList<Value>();
    for (int at = 0; at < data.length; ) {
      if (data.length - at < 16) {
        return strict ? List.of() : List.copyOf(values);
      }
      long size = u32be(data, at);
      if (size < 16 || size > data.length - at || !ascii(data, at + 4, 4).equals("data")) {
        return strict ? List.of() : List.copyOf(values);
      }
      values.add(
        new Value(
          Arrays.copyOfRange(data, at + 16, at + (int) size),
          u32be(data, at + 8),
          u32be(data, at + 12)));
      if (values.size() > 100_000) {
        return List.of();
      }
      at += (int) size;
    }
    return List.copyOf(values);
  }

  static String property(String nativeName) {
    return PROPERTIES.getOrDefault(nativeName, "");
  }

  static String keyedProperty(String key) {
    if (!key.startsWith("mdta:")) {
      return "";
    }
    String name = key.substring(5);
    if (name.startsWith("com.apple.quicktime.")) {
      name = name.substring(20);
    }
    return switch (name.toUpperCase(Locale.ROOT)) {
      case "ALBUM_ARTIST" -> "ALBUMARTIST";
      case "TRACK" -> "TRACKNUMBER";
      case "DISC" -> "DISCNUMBER";
      case "ARTWORK" -> "PICTURE";
      default -> name.toUpperCase(Locale.ROOT);
    };
  }

  static Item text(String name, List<String> values) {
    var data = new ArrayList<Value>();
    for (var value : values) {
      data.add(new Value(value.getBytes(StandardCharsets.UTF_8), 1, 0));
    }
    return new Item(name, Kind.TEXT, List.copyOf(values), List.copyOf(data), false);
  }

  static Item keyedText(String name, List<String> values) {
    var data = new ArrayList<Value>();
    for (var value : values) {
      validateText(value);
      data.add(new Value(value.getBytes(StandardCharsets.UTF_8), 1, 0));
    }
    return new Item(name, Kind.TEXT, List.copyOf(values), List.copyOf(data), true);
  }

  static Item fromProperty(String key, List<String> values) {
    String name = NAMES.get(key);
    if (name == null && !key.isEmpty() && key.charAt(0) >= 'A' && key.charAt(0) <= 'Z') {
      name = FREE + key;
    }
    if (name == null || values.isEmpty()) {
      throw new MetadataEditException("Tag is not supported by MP4.");
    }
    Kind kind = name.startsWith("----:") ? Kind.TEXT : conventionalKind(name, 1);
    if (name.equals("trkn") || name.equals("disk")) {
      String[] parts = values.getFirst().split("/", -1);
      return edit(
        new Item(name, Kind.PAIR, List.of("0", "0"), List.of(), false),
        List.of(parts[0], parts.length > 1 ? parts[1] : "0"));
    }
    return edit(new Item(name, kind, List.of(), List.of(), false), values);
  }

  static Item edit(Item original, List<String> text) {
    for (var value : text) {
      validateText(value);
    }
    if (original.text().equals(text)) {
      return original;
    }
    if (original.keyed()) {
      var values = original.values();
      if (values.isEmpty() || original.text().isEmpty()) {
        throw new MetadataEditException("Binary metadata can only be removed.");
      }
      if (values.size() != text.size()) {
        var first = values.getFirst();
        for (var value : values) {
          if (value.type != first.type
            || value.locale != first.locale
            || ((signed(value.type) || unsigned(value.type))
            && value.bytes.length != first.bytes.length)) {
            throw new MetadataEditException("This tag requires the same number of values.");
          }
        }
      }
      var updated = new ArrayList<Value>();
      for (int i = 0; i < text.size(); i++) {
        var old = values.get(Math.min(i, values.size() - 1));
        updated.add(new Value(encode(text.get(i), old), old.type, old.locale));
      }
      return new Item(
        original.nativeName, original.kind(), List.copyOf(text), List.copyOf(updated), true);
    }
    if (original.kind() == Kind.TEXT) {
      return new Item(original.nativeName, Kind.TEXT, List.copyOf(text), List.of(), false);
    }
    if (original.kind() == Kind.PAIR) {
      if (text.size() != 2) {
        throw new MetadataEditException("Track/disc fields require number and total.");
      }
      for (var value : text) {
        integer(value, false, 16);
      }
      return new Item(original.nativeName, Kind.PAIR, List.copyOf(text), List.of(), false);
    }
    if (text.size() != 1) {
      throw new MetadataEditException("This field requires one number.");
    }
    switch (original.kind()) {
      case BOOL -> {
        if (!text.getFirst().equals("0") && !text.getFirst().equals("1")) {
          throw new MetadataEditException("Value is outside the supported tag range.");
        }
      }
      case BYTE -> integer(text.getFirst(), false, 8);
      case INT -> integer(text.getFirst(), false, 16);
      case UINT -> integer(text.getFirst(), false, 32);
      case LONG -> integer(text.getFirst(), true, 64);
      default -> throw new MetadataEditException("Binary metadata can only be removed.");
    }
    return new Item(original.nativeName, original.kind(), List.copyOf(text), List.of(), false);
  }

  static byte[] render(String name, Item item) {
    if (item.keyed()) {
      return atom(indexName(name), renderValues(item.values()));
    }
    if (item.kind() == Kind.BINARY) {
      throw new MetadataEditException("Binary metadata can only be removed.");
    }
    if (name.startsWith("----:")) {
      String[] parts = name.split(":", -1);
      if (parts.length != 3 || parts[1].isEmpty() || parts[2].isEmpty()) {
        throw new MetadataEditException("MP4 custom keys use ----:namespace:name.");
      }
      var out = new ByteArrayOutputStream();
      out.writeBytes(atom("mean", join(new byte[4], parts[1].getBytes(StandardCharsets.UTF_8))));
      out.writeBytes(atom("name", join(new byte[4], parts[2].getBytes(StandardCharsets.UTF_8))));
      for (var value : item.text()) {
        writeData(out, new Value(value.getBytes(StandardCharsets.UTF_8), 1, 0));
      }
      return atom("----", out.toByteArray());
    }
    if (item.kind() == Kind.ARTWORK) {
      return atom(name, renderValues(item.values()));
    }
    var values = new ArrayList<Value>();
    switch (item.kind()) {
      case TEXT -> {
        for (var value : item.text()) {
          values.add(
            new Value(
              value.getBytes(StandardCharsets.UTF_8),
              name.equals("purl") || name.equals("egid") ? 0 : 1,
              0));
        }
      }
      case PAIR -> {
        byte[] b = new byte[name.equals("disk") ? 6 : 8];
        put16be(b, 2, Integer.parseInt(item.text().get(0)));
        put16be(b, 4, Integer.parseInt(item.text().get(1)));
        values.add(new Value(b, 0, 0));
      }
      default -> {
        int width =
          switch (item.kind()) {
            case BOOL, BYTE -> 1;
            case INT -> 2;
            case UINT -> 4;
            case LONG -> 8;
            default -> throw new MetadataEditException("Unsupported MP4 item");
          };
        byte[] b = new byte[width];
        long n = new BigInteger(item.text().getFirst()).longValue();
        for (int i = width - 1; i >= 0; i--) {
          b[i] = (byte) n;
          n >>>= 8;
        }
        values.add(new Value(b, 21, 0));
      }
    }
    return atom(name, renderValues(values));
  }

  static String mime(long type) {
    return switch ((int) type) {
      case 13 -> "image/jpeg";
      case 14 -> "image/png";
      case 12 -> "image/gif";
      case 27 -> "image/bmp";
      default -> "";
    };
  }

  static long imageType(long type, byte[] b) {
    if (type == 0 || !mime(type).isEmpty()) {
      return type;
    }
    if (b.length >= 2 && (b[0] & 255) == 255 && (b[1] & 255) == 216) {
      return 13;
    }
    if (b.length >= 8 && (b[0] & 255) == 137 && ascii(b, 1, 3).equals("PNG")) {
      return 14;
    }
    if (b.length >= 6 && ascii(b, 0, 4).equals("GIF8")) {
      return 12;
    }
    if (b.length >= 14 && ascii(b, 0, 2).equals("BM") && u32le(b, 6) == 0) {
      return 27;
    }
    return 255;
  }

  static byte[] renderValues(List<Value> values) {
    var out = new ByteArrayOutputStream();
    for (var value : values) {
      writeData(out, value);
      if (out.size() > Mp4Tree.LIMIT) {
        throw new MetadataEditException("MP4 metadata exceeds 64 MB.");
      }
    }
    return out.toByteArray();
  }

  private static void writeData(ByteArrayOutputStream out, Value value) {
    // Retain the per-atom limits used by join(prefix, bytes) and atom("data", body).
    if (value.bytes.length > Mp4Tree.LIMIT - 8) {
      throw new MetadataEditException("MP4 metadata exceeds 64 MB.");
    }
    if (value.bytes.length > Mp4Tree.LIMIT - 16) {
      throw new MetadataEditException("MP4 metadata exceeds the 64 MB editing limit.");
    }
    byte[] header = new byte[16];
    put32be(header, 0, value.bytes.length + 16L);
    System.arraycopy(bytes("data"), 0, header, 4, 4);
    put32be(header, 8, value.type);
    put32be(header, 12, value.locale);
    out.writeBytes(header);
    out.writeBytes(value.bytes);
  }

  static byte[] atom(String name, byte[] body) {
    if (name.length() != 4 || body.length > Mp4Tree.LIMIT - 8) {
      throw new MetadataEditException("MP4 metadata exceeds the 64 MB editing limit.");
    }
    byte[] result = new byte[body.length + 8];
    put32be(result, 0, result.length);
    System.arraycopy(bytes(name), 0, result, 4, 4);
    System.arraycopy(body, 0, result, 8, body.length);
    return result;
  }

  static String indexName(String index) {
    try {
      long value = Long.parseLong(index);
      if (value < 1 || value > 0xffffffffL) {
        throw new NumberFormatException();
      }
      byte[] bytes = new byte[4];
      put32be(bytes, 0, value);
      return ascii(bytes, 0, 4);
    } catch (NumberFormatException e) {
      throw new MetadataEditException("Invalid MP4 key index");
    }
  }

  static byte[] join(byte[] a, byte[] b) {
    if (a.length > Mp4Tree.LIMIT - b.length) {
      throw new MetadataEditException("MP4 metadata exceeds 64 MB.");
    }
    byte[] result = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, result, a.length, b.length);
    return result;
  }

  static String strictText(byte[] bytes, Charset charset) {
    try {
      return charset
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
    } catch (CharacterCodingException e) {
      throw new MetadataEditException("This tag contains unreadable text.");
    }
  }

  static void validateText(String text) {
    if (text.indexOf('\0') >= 0) {
      throw new MetadataEditException("Invalid metadata text.");
    }
    try {
      StandardCharsets.UTF_8
        .newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .encode(CharBuffer.wrap(text));
    } catch (CharacterCodingException e) {
      throw new MetadataEditException("Invalid metadata text.");
    }
  }

  private static boolean signed(long type) {
    return type == 21 || (type >= 65 && type <= 67) || type == 74;
  }

  private static boolean unsigned(long type) {
    return type == 22 || (type >= 75 && type <= 78);
  }

  private static int fixedWidth(long type) {
    return switch ((int) type) {
      case 65, 75 -> 1;
      case 66, 76 -> 2;
      case 67, 77 -> 4;
      case 74, 78 -> 8;
      default -> 0;
    };
  }

  private static boolean validInteger(Value value) {
    int n = value.bytes.length, fixed = fixedWidth(value.type);
    return fixed != 0 ? n == fixed : (n >= 1 && n <= 4) || n == 8;
  }

  private static String decode(Value value) throws IOException {
    byte[] b = value.bytes;
    if (value.type == 1 || value.type == 4 || value.type == 2 || value.type == 5) {
      String text =
        strictText(
          b,
          value.type == 1 || value.type == 4
            ? StandardCharsets.UTF_8
            : StandardCharsets.UTF_16BE);
      if (text.indexOf('\0') >= 0) {
        throw new MetadataEditException("This tag contains an unsupported text character.");
      }
      return text;
    }
    if ((signed(value.type) || unsigned(value.type)) && validInteger(value)) {
      return signed(value.type) ? new BigInteger(b).toString() : new BigInteger(1, b).toString();
    }
    if (value.type == 23 && b.length == 4) {
      float n = Float.intBitsToFloat((int) u32be(b, 0));
      if (!Float.isFinite(n)) {
        throw new MetadataEditException("This tag contains an invalid number.");
      }
      return floating(n, 9);
    }
    if (value.type == 24 && b.length == 8) {
      double n = Double.longBitsToDouble(u64be(b, 0));
      if (!Double.isFinite(n)) {
        throw new MetadataEditException("This tag contains an invalid number.");
      }
      return floating(n, 17);
    }
    throw new MetadataEditException("This metadata value contains binary data.");
  }

  private static String floating(double n, int digits) {
    String text = String.format(Locale.ROOT, "%.*g".replace("*", Integer.toString(digits)), n);
    int exponent = text.indexOf('e');
    String mantissa = exponent >= 0 ? text.substring(0, exponent) : text;
    if (mantissa.indexOf('.') >= 0) {
      while (mantissa.endsWith("0")) {
        mantissa = mantissa.substring(0, mantissa.length() - 1);
      }
      if (mantissa.endsWith(".")) {
        mantissa = mantissa.substring(0, mantissa.length() - 1);
      }
    }
    return mantissa + (exponent >= 0 ? text.substring(exponent) : "");
  }

  private static BigInteger integer(String text, boolean signed, int bits) {
    if (!(signed ? SIGNED_INTEGER : UNSIGNED_INTEGER).matcher(text).matches()) {
      throw new MetadataEditException(
        "Enter a whole number within the range supported by this tag.");
    }
    BigInteger n;
    try {
      n = new BigInteger(text);
    } catch (NumberFormatException e) {
      throw new MetadataEditException(
        "Enter a whole number within the range supported by this tag.");
    }
    BigInteger max = BigInteger.ONE.shiftLeft(signed ? bits - 1 : bits).subtract(BigInteger.ONE),
      min = signed ? BigInteger.ONE.shiftLeft(bits - 1).negate() : BigInteger.ZERO;
    if (n.compareTo(min) < 0 || n.compareTo(max) > 0) {
      throw new MetadataEditException("This number is outside the range supported by the tag.");
    }
    return n;
  }

  private static byte[] encode(String text, Value value) {
    validateText(text);
    if (value.type == 1 || value.type == 4) {
      return text.getBytes(StandardCharsets.UTF_8);
    }
    if (value.type == 2 || value.type == 5) {
      return text.getBytes(StandardCharsets.UTF_16BE);
    }
    int width = value.bytes.length;
    long bits;
    if ((signed(value.type) || unsigned(value.type)) && validInteger(value)) {
      bits = integer(text, signed(value.type), width * 8).longValue();
    } else if ((value.type == 23 && width == 4) || (value.type == 24 && width == 8)) {
      if (!DECIMAL.matcher(text).matches()) {
        throw new MetadataEditException("Enter a valid number for this tag.");
      }
      double n;
      try {
        n = Double.parseDouble(text);
      } catch (NumberFormatException e) {
        throw new MetadataEditException("Enter a valid number for this tag.");
      }
      if (!Double.isFinite(n) || (width == 4 && !Float.isFinite((float) n))) {
        throw new MetadataEditException("This number is outside the range supported by the tag.");
      }
      bits = width == 4 ? Float.floatToRawIntBits((float) n) : Double.doubleToRawLongBits(n);
    } else {
      throw new MetadataEditException("Binary metadata can only be removed.");
    }
    byte[] out = new byte[width];
    for (int i = width - 1; i >= 0; i--) {
      out[i] = (byte) bits;
      bits >>>= 8;
    }
    return out;
  }
}
