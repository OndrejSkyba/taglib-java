// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.mp4;

import cz.skyba.taglib.internal.TextNumbers;
import cz.skyba.taglib.toolkit.ByteVector;

import java.util.*;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Extensible MP4 item decoding, serialization and common-property mappings.
 * Override parser and renderer hooks to support additional item names or encodings. Shared
 * instances reuse lazily initialized mappings; custom factory instances keep their own mappings.
 */
public class ItemFactory {
  /**
   * The parsing and rendering strategy associated with an item name.
   */
  protected enum ItemHandlerType {
    Unknown,
    FreeForm,
    IntPair,
    IntPairNoTrailing,
    Bool,
    Int,
    TextOrInt,
    UInt,
    LongLong,
    Byte,
    Gnre,
    Covr,
    Stem,
    TextImplicit,
    Text
  }

  /**
   * Parsed MP4 name and item.
   */
  public record ParsedItem(String name, Item item) {
  }

  /**
   * Property conversion result with copied atom-name bytes.
   */
  public record PropertyItem(ByteVector name, Item item) {
    /**
     * Creates a conversion result, copying mutable names or text-value containers.
     */
    public PropertyItem {
      name = new ByteVector(name);
    }

    @Override
    public ByteVector name() {
      return new ByteVector(name);
    }
  }

  /**
   * Property conversion result with copied text values.
   */
  public record PropertyValue(String key, List<String> values) {
    /**
     * Creates a conversion result, copying the text-value list and rejecting null elements.
     */
    public PropertyValue {
      values = List.copyOf(values);
    }

    /**
     * Returns an independent mutable copy of the text-value list.
     */
    @Override
    public List<String> values() {
      return new ArrayList<>(values);
    }
  }

  @FunctionalInterface
  private interface Parser {
    ParsedItem parse(Atom atom, ByteVector bytes);
  }

  @FunctionalInterface
  private interface Renderer {
    ByteVector render(String itemName, ByteVector name, Item item);
  }

  private record HandlerSpec(Parser parser, Renderer renderer, PropertyFormat property) {
  }

  private enum PropertyFormat {
    NONE, PAIR, INT, UINT, LONG, BYTE, BOOL, TEXT;

    Item importValues(List<String> values) {
      var first = Objects.requireNonNull(values.getFirst());
      return switch (this) {
        case PAIR -> {
          String[] parts = values.getFirst().split("/", -1);
          yield new Item(TextNumbers.parseInt(parts[0]), parts.length > 1 ? TextNumbers.parseInt(parts[1]) : 0);
        }
        case INT -> new Item(TextNumbers.parseInt(first));
        case UINT -> Item.fromUnsignedInt(TextNumbers.parseInt(first));
        case LONG -> new Item(TextNumbers.parseLong(first, 10));
        case BYTE -> Item.fromByte(TextNumbers.parseInt(first));
        case BOOL -> new Item(TextNumbers.parseInt(first) != 0);
        case TEXT -> Item.textValues(values);
        case NONE -> new Item();
      };
    }

    PropertyValue exportValue(String key, Item item) {
      if (this == TEXT) {
        return new PropertyValue(key, item.toStringList());
      }
      if (this == NONE) {
        return new PropertyValue("", List.of());
      }
      String value = switch (this) {
        case PAIR -> {
          var pair = item.toIntPair();
          yield pair.first() + (pair.second() != 0 ? "/" + pair.second() : "");
        }
        case INT -> Integer.toString(item.toInt());
        case UINT -> Long.toString(item.toUnsignedInt());
        case LONG -> Long.toString(item.toLong());
        case BYTE -> Integer.toString(item.toByte());
        case BOOL -> item.toBoolean() ? "1" : "0";
        case NONE, TEXT -> throw new AssertionError(this);
      };
      return new PropertyValue(key, List.of(value));
    }
  }

  private static final Map<ItemHandlerType, HandlerSpec> HANDLER_SPECS = handlerSpecs();

  private static Map<ItemHandlerType, HandlerSpec> handlerSpecs() {
    var result = new EnumMap<ItemHandlerType, HandlerSpec>(ItemHandlerType.class);
    result.put(ItemHandlerType.Unknown, new HandlerSpec(
      (atom, _) -> new ParsedItem(latin(atom.name()), new Item()), (_, _, _) -> new ByteVector(), PropertyFormat.NONE));
    result.put(ItemHandlerType.FreeForm, new HandlerSpec(
      ItemFactory::parseFreeForm, (itemName, _, item) -> renderFreeForm(itemName, item), PropertyFormat.TEXT));
    result.put(ItemHandlerType.IntPair, new HandlerSpec(
      ItemFactory::parseIntPair, (_, name, item) -> renderIntPair(name, item), PropertyFormat.PAIR));
    result.put(ItemHandlerType.IntPairNoTrailing, new HandlerSpec(
      ItemFactory::parseIntPair, (_, name, item) -> renderIntPairNoTrailing(name, item), PropertyFormat.PAIR));
    result.put(ItemHandlerType.Bool, new HandlerSpec(
      ItemFactory::parseBool, (_, name, item) -> renderBool(name, item), PropertyFormat.BOOL));
    result.put(ItemHandlerType.Int, new HandlerSpec(
      ItemFactory::parseInt, (_, name, item) -> renderInt(name, item), PropertyFormat.INT));
    result.put(ItemHandlerType.TextOrInt, new HandlerSpec(
      ItemFactory::parseTextOrInt, (_, name, item) -> renderTextOrInt(name, item), PropertyFormat.TEXT));
    result.put(ItemHandlerType.UInt, new HandlerSpec(
      ItemFactory::parseUInt, (_, name, item) -> renderUInt(name, item), PropertyFormat.UINT));
    result.put(ItemHandlerType.LongLong, new HandlerSpec(
      ItemFactory::parseLongLong, (_, name, item) -> renderLongLong(name, item), PropertyFormat.LONG));
    result.put(ItemHandlerType.Byte, new HandlerSpec(
      ItemFactory::parseByte, (_, name, item) -> renderByte(name, item), PropertyFormat.BYTE));
    result.put(ItemHandlerType.Gnre, new HandlerSpec(
      (_, data) -> parseGnre(data), (_, name, item) -> renderInt(name, item), PropertyFormat.INT));
    result.put(ItemHandlerType.Covr, new HandlerSpec(
      ItemFactory::parseCovr, (_, name, item) -> renderCovr(name, item), PropertyFormat.NONE));
    result.put(ItemHandlerType.Stem, new HandlerSpec(
      ItemFactory::parseStem, (_, name, item) -> renderStem(name, item), PropertyFormat.NONE));
    result.put(ItemHandlerType.TextImplicit, new HandlerSpec(
      (atom, data) -> parseText(atom, data, -1), (_, name, item) -> renderText(name, item, 0), PropertyFormat.TEXT));
    result.put(ItemHandlerType.Text, new HandlerSpec(
      ItemFactory::parseText, (_, name, item) -> renderText(name, item), PropertyFormat.TEXT));
    return Map.copyOf(result);
  }

  private static final String FREE = "----:com.apple.iTunes:";
  private static final ItemFactory INSTANCE = new ItemFactory();
  private Map<ByteVector, ItemHandlerType> handlers;
  private Map<ByteVector, String> properties;
  private Map<String, ByteVector> names;

  protected ItemFactory() {
  }

  /**
   * Returns the shared default item factory.
   *
   * @return the factory
   */
  public static ItemFactory instance() {
    return INSTANCE;
  }

  private static String latin(ByteVector value) {
    return value.decodeTerminatedText(ISO_8859_1);
  }

  /**
   * Selects the registered handler for an item name.
   *
   * @param name the atom-name bytes or free-form item name
   * @return the registered mapping or fallback
   */
  protected synchronized ItemHandlerType handlerTypeForName(ByteVector name) {
    if (handlers == null) {
      handlers = new TreeMap<>(nameHandlerMap());
    }
    ItemHandlerType type = handlers.getOrDefault(name, ItemHandlerType.Unknown);
    return type == ItemHandlerType.Unknown && name.size() == 4 ? ItemHandlerType.Text : type;
  }

  private synchronized void cacheProperties() {
    if (properties != null) {
      return;
    }
    var forward = new TreeMap<>(namePropertyMap());
    var reverse = new TreeMap<String, ByteVector>();
    forward.forEach((name, key) -> reverse.put(key, new ByteVector(name)));
    properties = forward;
    names = reverse;
  }

  /**
   * Looks up the common property key for an item name.
   *
   * @param name the atom-name bytes or free-form item name
   * @return the registered mapping or fallback
   */
  public String propertyKeyForName(ByteVector name) {
    cacheProperties();
    String key = properties.getOrDefault(name, "");
    return key.isEmpty() && name.startsWith(new ByteVector(FREE)) ? latin(name.mid(FREE.length())) : key;
  }

  /**
   * Maps a common property key to an atom name, using a free-form item name when needed.
   *
   * @param key the property key
   * @return the registered mapping or fallback
   */
  public ByteVector nameForPropertyKey(String key) {
    cacheProperties();
    ByteVector name = names.get(key);
    return name != null ? new ByteVector(name) : !key.isEmpty() && key.charAt(0) >= 'A' && key.charAt(0) <= 'Z'
      ? ByteVector.fromText(FREE + key, UTF_8) : new ByteVector();
  }

  /**
   * Builds a new mapping used to initialize this factory's lookup cache.
   * Subclasses can supply additional names or mappings.
   *
   * @return atom-name mappings
   */
  protected Map<ByteVector, ItemHandlerType> nameHandlerMap() {
    var result = new TreeMap<ByteVector, ItemHandlerType>();
    result.put(new ByteVector("----"), ItemHandlerType.FreeForm);
    result.put(new ByteVector("trkn"), ItemHandlerType.IntPair);
    result.put(new ByteVector("disk"), ItemHandlerType.IntPairNoTrailing);
    result.put(new ByteVector("cpil"), ItemHandlerType.Bool);
    result.put(new ByteVector("pgap"), ItemHandlerType.Bool);
    result.put(new ByteVector("pcst"), ItemHandlerType.Bool);
    result.put(new ByteVector("shwm"), ItemHandlerType.Bool);
    result.put(new ByteVector("tmpo"), ItemHandlerType.Int);
    result.put(new ByteVector("©mvi"), ItemHandlerType.Int);
    result.put(new ByteVector("©mvc"), ItemHandlerType.Int);
    result.put(new ByteVector("hdvd"), ItemHandlerType.Int);
    result.put(new ByteVector("rate"), ItemHandlerType.TextOrInt);
    result.put(new ByteVector("tvsn"), ItemHandlerType.UInt);
    result.put(new ByteVector("tves"), ItemHandlerType.UInt);
    result.put(new ByteVector("cnID"), ItemHandlerType.LongLong);
    result.put(new ByteVector("sfID"), ItemHandlerType.UInt);
    result.put(new ByteVector("atID"), ItemHandlerType.UInt);
    result.put(new ByteVector("geID"), ItemHandlerType.UInt);
    result.put(new ByteVector("cmID"), ItemHandlerType.UInt);
    result.put(new ByteVector("plID"), ItemHandlerType.LongLong);
    result.put(new ByteVector("stik"), ItemHandlerType.Byte);
    result.put(new ByteVector("rtng"), ItemHandlerType.Byte);
    result.put(new ByteVector("akID"), ItemHandlerType.Byte);
    result.put(new ByteVector("gnre"), ItemHandlerType.Gnre);
    result.put(new ByteVector("covr"), ItemHandlerType.Covr);
    result.put(new ByteVector("stem"), ItemHandlerType.Stem);
    result.put(new ByteVector("purl"), ItemHandlerType.TextImplicit);
    result.put(new ByteVector("egid"), ItemHandlerType.TextImplicit);
    return result;
  }

  /**
   * Builds a new mapping used to initialize this factory's lookup cache.
   * Subclasses can supply additional names or mappings.
   *
   * @return atom-name mappings
   */
  protected Map<ByteVector, String> namePropertyMap() {
    var result = new TreeMap<ByteVector, String>();
    result.put(new ByteVector("©nam"), "TITLE");
    result.put(new ByteVector("©ART"), "ARTIST");
    result.put(new ByteVector("©alb"), "ALBUM");
    result.put(new ByteVector("©cmt"), "COMMENT");
    result.put(new ByteVector("©gen"), "GENRE");
    result.put(new ByteVector("©day"), "DATE");
    result.put(new ByteVector("©wrt"), "COMPOSER");
    result.put(new ByteVector("©grp"), "GROUPING");
    result.put(new ByteVector("aART"), "ALBUMARTIST");
    result.put(new ByteVector("trkn"), "TRACKNUMBER");
    result.put(new ByteVector("disk"), "DISCNUMBER");
    result.put(new ByteVector("cpil"), "COMPILATION");
    result.put(new ByteVector("tmpo"), "BPM");
    result.put(new ByteVector("cprt"), "COPYRIGHT");
    result.put(new ByteVector("©lyr"), "LYRICS");
    result.put(new ByteVector("©too"), "ENCODING");
    result.put(new ByteVector("©enc"), "ENCODEDBY");
    result.put(new ByteVector("soal"), "ALBUMSORT");
    result.put(new ByteVector("soaa"), "ALBUMARTISTSORT");
    result.put(new ByteVector("soar"), "ARTISTSORT");
    result.put(new ByteVector("sonm"), "TITLESORT");
    result.put(new ByteVector("soco"), "COMPOSERSORT");
    result.put(new ByteVector("sosn"), "SHOWSORT");
    result.put(new ByteVector("shwm"), "SHOWWORKMOVEMENT");
    result.put(new ByteVector("pgap"), "GAPLESSPLAYBACK");
    result.put(new ByteVector("pcst"), "PODCAST");
    result.put(new ByteVector("catg"), "PODCASTCATEGORY");
    result.put(new ByteVector("desc"), "PODCASTDESC");
    result.put(new ByteVector("egid"), "PODCASTID");
    result.put(new ByteVector("purl"), "PODCASTURL");
    result.put(new ByteVector("tves"), "TVEPISODE");
    result.put(new ByteVector("tven"), "TVEPISODEID");
    result.put(new ByteVector("tvnn"), "TVNETWORK");
    result.put(new ByteVector("tvsn"), "TVSEASON");
    result.put(new ByteVector("tvsh"), "TVSHOW");
    result.put(new ByteVector("©wrk"), "WORK");
    result.put(new ByteVector("©mvn"), "MOVEMENTNAME");
    result.put(new ByteVector("©mvi"), "MOVEMENTNUMBER");
    result.put(new ByteVector("©mvc"), "MOVEMENTCOUNT");
    result.put(new ByteVector("ownr"), "OWNER");
    result.put(new ByteVector("----:com.apple.iTunes:MusicBrainz Track Id"), "MUSICBRAINZ_TRACKID");
    result.put(new ByteVector("----:com.apple.iTunes:MusicBrainz Artist Id"), "MUSICBRAINZ_ARTISTID");
    result.put(new ByteVector("----:com.apple.iTunes:MusicBrainz Album Id"), "MUSICBRAINZ_ALBUMID");
    result.put(new ByteVector("----:com.apple.iTunes:MusicBrainz Album Artist Id"), "MUSICBRAINZ_ALBUMARTISTID");
    result.put(new ByteVector("----:com.apple.iTunes:MusicBrainz Release Group Id"), "MUSICBRAINZ_RELEASEGROUPID");
    result.put(new ByteVector("----:com.apple.iTunes:MusicBrainz Release Track Id"), "MUSICBRAINZ_RELEASETRACKID");
    result.put(new ByteVector("----:com.apple.iTunes:MusicBrainz Work Id"), "MUSICBRAINZ_WORKID");
    result.put(new ByteVector("----:com.apple.iTunes:MusicBrainz Album Release Country"), "RELEASECOUNTRY");
    result.put(new ByteVector("----:com.apple.iTunes:MusicBrainz Album Status"), "RELEASESTATUS");
    result.put(new ByteVector("----:com.apple.iTunes:MusicBrainz Album Type"), "RELEASETYPE");
    result.put(new ByteVector("----:com.apple.iTunes:ARTISTS"), "ARTISTS");
    result.put(new ByteVector("----:com.apple.iTunes:ORIGINALDATE"), "ORIGINALDATE");
    result.put(new ByteVector("----:com.apple.iTunes:RELEASEDATE"), "RELEASEDATE");
    result.put(new ByteVector("----:com.apple.iTunes:ASIN"), "ASIN");
    result.put(new ByteVector("----:com.apple.iTunes:LABEL"), "LABEL");
    result.put(new ByteVector("----:com.apple.iTunes:LYRICIST"), "LYRICIST");
    result.put(new ByteVector("----:com.apple.iTunes:CONDUCTOR"), "CONDUCTOR");
    result.put(new ByteVector("----:com.apple.iTunes:REMIXER"), "REMIXER");
    result.put(new ByteVector("----:com.apple.iTunes:ENGINEER"), "ENGINEER");
    result.put(new ByteVector("----:com.apple.iTunes:PRODUCER"), "PRODUCER");
    result.put(new ByteVector("----:com.apple.iTunes:DJMIXER"), "DJMIXER");
    result.put(new ByteVector("----:com.apple.iTunes:MIXER"), "MIXER");
    result.put(new ByteVector("----:com.apple.iTunes:SUBTITLE"), "SUBTITLE");
    result.put(new ByteVector("----:com.apple.iTunes:DISCSUBTITLE"), "DISCSUBTITLE");
    result.put(new ByteVector("----:com.apple.iTunes:MOOD"), "MOOD");
    result.put(new ByteVector("----:com.apple.iTunes:ISRC"), "ISRC");
    result.put(new ByteVector("----:com.apple.iTunes:CATALOGNUMBER"), "CATALOGNUMBER");
    result.put(new ByteVector("----:com.apple.iTunes:BARCODE"), "BARCODE");
    result.put(new ByteVector("----:com.apple.iTunes:SCRIPT"), "SCRIPT");
    result.put(new ByteVector("----:com.apple.iTunes:LANGUAGE"), "LANGUAGE");
    result.put(new ByteVector("----:com.apple.iTunes:LICENSE"), "LICENSE");
    result.put(new ByteVector("----:com.apple.iTunes:MEDIA"), "MEDIA");
    return result;
  }

  /**
   * Decodes an item atom using its name and this factory's registered handler.
   *
   * @param atom the parsed item descriptor
   * @param data encoded item content without its outer atom header
   * @return the decoded name and item; unsupported content can yield a void item
   */
  public ParsedItem parseItem(Atom atom, ByteVector data) {
    return HANDLER_SPECS.get(handlerTypeForName(atom.name())).parser.parse(atom, data);
  }

  /**
   * Serializes a named item into metadata atom bytes.
   *
   * @param itemName the item name
   * @param item     the typed value to encode
   * @return encoded atom bytes, or an empty vector for an unsupported handler
   */
  public ByteVector renderItem(String itemName, Item item) {
    if (itemName.startsWith("----")) {
      return renderFreeForm(itemName, item);
    }
    ByteVector name = ByteVector.fromText(itemName, ISO_8859_1);
    return HANDLER_SPECS.get(handlerTypeForName(name)).renderer.render(itemName, name, item);
  }

  private ItemHandlerType propertyHandler(ByteVector name) {
    return name.size() >= 4 && name.at(0) == '-' && name.at(1) == '-'
      && name.at(2) == '-' && name.at(3) == '-' ? ItemHandlerType.FreeForm : handlerTypeForName(name);
  }

  /**
   * Converts a common property key and text values to a typed item.
   *
   * @param key    the property key
   * @param values property values
   * @return an atom name and item; a void item indicates rejection
   */
  public PropertyItem itemFromProperty(String key, List<String> values) {
    values = List.copyOf(values);
    ByteVector name = nameForPropertyKey(key);
    Item item = new Item();
    if (!name.isEmpty()) {
      if (values.isEmpty()) {
        return new PropertyItem(name, Item.textValues(values));
      }
      item = HANDLER_SPECS.get(propertyHandler(name)).property.importValues(values);
    }
    return new PropertyItem(name, item);
  }

  /**
   * Converts a named typed item to its common text-property representation.
   *
   * @param name the atom-name bytes
   * @param item the item to convert
   * @return a property key and copied values; an empty key indicates no mapping
   */
  public PropertyValue itemToProperty(ByteVector name, Item item) {
    String key = propertyKeyForName(name);
    if (!key.isEmpty()) {
      return HANDLER_SPECS.get(propertyHandler(name)).property.exportValue(key, item);
    }
    return new PropertyValue("", List.of());
  }

  /**
   * Decodes child data atoms from encoded item content.
   *
   * @param data the encoded bytes or payload
   * @return decoded values
   */
  protected static List<AtomData> parseData2(ByteVector data) {
    return ItemAtomCodec.parseData2(data);
  }

  /**
   * Decodes child data atoms from encoded item content. Only matching data-type flags are selected when a filter is supplied.
   *
   * @param data          the encoded bytes or payload
   * @param expectedFlags the selected type flags, or a negative value for any type
   * @return decoded values
   */
  protected static List<AtomData> parseData2(ByteVector data, int expectedFlags) {
    return ItemAtomCodec.parseData2(data, expectedFlags);
  }

  /**
   * Decodes child data atoms from encoded item content. Only matching data-type flags are selected when a filter is supplied. Free-form items include additional name and meaning children.
   *
   * @param data          the encoded bytes or payload
   * @param expectedFlags the selected type flags, or a negative value for any type
   * @param freeForm      whether the content uses a free-form item layout
   * @return decoded values
   */
  protected static List<AtomData> parseData2(ByteVector data, int expectedFlags, boolean freeForm) {
    return ItemAtomCodec.parseData2(data, expectedFlags, freeForm);
  }

  /**
   * Decodes child data atoms from encoded item content.
   *
   * @param data the encoded bytes or payload
   * @return decoded values
   */
  protected static List<ByteVector> parseData(ByteVector data) {
    return ItemAtomCodec.parseData(data);
  }

  /**
   * Decodes child data atoms from encoded item content. Only matching data-type flags are selected when a filter is supplied.
   *
   * @param data  the encoded bytes or payload
   * @param flags the data-type flags
   * @return decoded values
   */
  protected static List<ByteVector> parseData(ByteVector data, int flags) {
    return ItemAtomCodec.parseData(data, flags);
  }

  /**
   * Decodes child data atoms from encoded item content. Only matching data-type flags are selected when a filter is supplied. Free-form items include additional name and meaning children.
   *
   * @param data     the encoded bytes or payload
   * @param flags    the data-type flags
   * @param freeForm whether the content uses a free-form item layout
   * @return decoded values
   */
  protected static List<ByteVector> parseData(ByteVector data, int flags, boolean freeForm) {
    return ItemAtomCodec.parseData(data, flags, freeForm);
  }

  /**
   * Decodes a signed integer using this handler.
   *
   * @param a the item atom descriptor
   * @param b the encoded item content
   * @return the decoded item or an invalid item for unsupported data
   */
  protected static ParsedItem parseInt(Atom a, ByteVector b) {
    return ItemAtomCodec.parseInt(a, b);
  }

  /**
   * Decodes an unsigned integer using this handler.
   *
   * @param a the item atom descriptor
   * @param b the encoded item content
   * @return the decoded item or an invalid item for unsupported data
   */
  protected static ParsedItem parseUInt(Atom a, ByteVector b) {
    return ItemAtomCodec.parseUInt(a, b);
  }

  /**
   * Decodes a 64-bit integer using this handler.
   *
   * @param a the item atom descriptor
   * @param b the encoded item content
   * @return the decoded item or an invalid item for unsupported data
   */
  protected static ParsedItem parseLongLong(Atom a, ByteVector b) {
    return ItemAtomCodec.parseLongLong(a, b);
  }

  /**
   * Decodes an unsigned byte using this handler.
   *
   * @param a the item atom descriptor
   * @param b the encoded item content
   * @return the decoded item or an invalid item for unsupported data
   */
  protected static ParsedItem parseByte(Atom a, ByteVector b) {
    return ItemAtomCodec.parseByte(a, b);
  }

  /**
   * Decodes a boolean using this handler.
   *
   * @param a the item atom descriptor
   * @param b the encoded item content
   * @return the decoded item or an invalid item for unsupported data
   */
  protected static ParsedItem parseBool(Atom a, ByteVector b) {
    return ItemAtomCodec.parseBool(a, b);
  }

  /**
   * Decodes an integer pair using this handler.
   *
   * @param a the item atom descriptor
   * @param b the encoded item content
   * @return the decoded item or an invalid item for unsupported data
   */
  protected static ParsedItem parseIntPair(Atom a, ByteVector b) {
    return ItemAtomCodec.parseIntPair(a, b);
  }

  /**
   * Decodes text or numeric metadata using this handler.
   *
   * @param atom  the item atom descriptor
   * @param bytes the encoded item content
   * @return the decoded item or an invalid item for unsupported data
   */
  protected static ParsedItem parseTextOrInt(Atom atom, ByteVector bytes) {
    return ItemAtomCodec.parseTextOrInt(atom, bytes);
  }

  /**
   * Decodes text metadata using this handler.
   *
   * @param atom  the item atom descriptor
   * @param bytes the encoded item content
   * @return the decoded item or an invalid item for unsupported data
   */
  protected static ParsedItem parseText(Atom atom, ByteVector bytes) {
    return ItemAtomCodec.parseText(atom, bytes);
  }

  /**
   * Decodes text metadata using this handler.
   *
   * @param atom  the item atom descriptor
   * @param bytes the encoded item content
   * @param flags the data-type flags
   * @return the decoded item or an invalid item for unsupported data
   */
  protected static ParsedItem parseText(Atom atom, ByteVector bytes, int flags) {
    return ItemAtomCodec.parseText(atom, bytes, flags);
  }

  /**
   * Decodes a genre code using this handler.
   *
   * @param bytes the encoded item content
   * @return the decoded item or an invalid item for unsupported data
   */
  protected static ParsedItem parseGnre(ByteVector bytes) {
    return ItemAtomCodec.parseGnre(bytes);
  }

  /**
   * Decodes a free-form named item using this handler.
   *
   * @param atom  the item atom descriptor
   * @param bytes the encoded item content
   * @return the decoded item or an invalid item for unsupported data
   */
  protected static ParsedItem parseFreeForm(Atom atom, ByteVector bytes) {
    return ItemAtomCodec.parseFreeForm(atom, bytes);
  }

  /**
   * Decodes cover-art values using this handler.
   *
   * @param atom  the item atom descriptor
   * @param bytes the encoded item content
   * @return the decoded item or an invalid item for unsupported data
   */
  protected static ParsedItem parseCovr(Atom atom, ByteVector bytes) {
    return ItemAtomCodec.parseCovr(atom, bytes);
  }

  /**
   * Decodes Stem manifest bytes using this handler.
   *
   * @param atom  the item atom descriptor
   * @param bytes the encoded item content
   * @return the decoded item or an invalid item for unsupported data
   */
  protected static ParsedItem parseStem(Atom atom, ByteVector bytes) {
    return ItemAtomCodec.parseStem(atom, bytes);
  }

  /**
   * Serializes an atom with its header without writing storage.
   *
   * @param name the atom-name bytes or free-form item name
   * @param data the encoded bytes or payload
   * @return encoded atom bytes
   */
  protected static ByteVector renderAtom(ByteVector name, ByteVector data) {
    return ItemAtomCodec.renderAtom(name, data);
  }

  /**
   * Serializes typed data child atoms without writing storage.
   *
   * @param name   the atom-name bytes or free-form item name
   * @param flags  the data-type flags
   * @param values the payload values to encode
   * @return encoded atom bytes
   */
  protected static ByteVector renderData(ByteVector name, int flags, List<ByteVector> values) {
    return ItemAtomCodec.renderData(name, flags, values);
  }

  /**
   * Serializes a boolean item without writing storage.
   *
   * @param name the atom-name bytes or free-form item name
   * @param item the typed item value
   * @return encoded atom bytes
   */
  protected static ByteVector renderBool(ByteVector name, Item item) {
    return ItemAtomCodec.renderBool(name, item);
  }

  /**
   * Serializes a signed integer item without writing storage.
   *
   * @param name the atom-name bytes or free-form item name
   * @param item the typed item value
   * @return encoded atom bytes
   */
  protected static ByteVector renderInt(ByteVector name, Item item) {
    return ItemAtomCodec.renderInt(name, item);
  }

  /**
   * Serializes an unsigned integer item without writing storage.
   *
   * @param name the atom-name bytes or free-form item name
   * @param item the typed item value
   * @return encoded atom bytes
   */
  protected static ByteVector renderUInt(ByteVector name, Item item) {
    return ItemAtomCodec.renderUInt(name, item);
  }

  /**
   * Serializes a 64-bit item without writing storage.
   *
   * @param name the atom-name bytes or free-form item name
   * @param item the typed item value
   * @return encoded atom bytes
   */
  protected static ByteVector renderLongLong(ByteVector name, Item item) {
    return ItemAtomCodec.renderLongLong(name, item);
  }

  /**
   * Serializes a byte item without writing storage.
   *
   * @param name the atom-name bytes or free-form item name
   * @param item the typed item value
   * @return encoded atom bytes
   */
  protected static ByteVector renderByte(ByteVector name, Item item) {
    return ItemAtomCodec.renderByte(name, item);
  }

  /**
   * Serializes a padded integer pair without writing storage.
   *
   * @param name the atom-name bytes or free-form item name
   * @param item the typed item value
   * @return encoded atom bytes
   */
  protected static ByteVector renderIntPair(ByteVector name, Item item) {
    return ItemAtomCodec.renderIntPair(name, item);
  }

  /**
   * Serializes an integer pair without trailing padding without writing storage.
   *
   * @param name the atom-name bytes or free-form item name
   * @param item the typed item value
   * @return encoded atom bytes
   */
  protected static ByteVector renderIntPairNoTrailing(ByteVector name, Item item) {
    return ItemAtomCodec.renderIntPairNoTrailing(name, item);
  }

  /**
   * Serializes text or integer metadata without writing storage.
   *
   * @param name the atom-name bytes or free-form item name
   * @param item the typed item value
   * @return encoded atom bytes
   */
  protected static ByteVector renderTextOrInt(ByteVector name, Item item) {
    return ItemAtomCodec.renderTextOrInt(name, item);
  }

  /**
   * Serializes text metadata without writing storage.
   *
   * @param name the atom-name bytes or free-form item name
   * @param item the typed item value
   * @return encoded atom bytes
   */
  protected static ByteVector renderText(ByteVector name, Item item) {
    return ItemAtomCodec.renderText(name, item);
  }

  /**
   * Serializes text metadata without writing storage.
   *
   * @param name  the atom-name bytes or free-form item name
   * @param item  the typed item value
   * @param flags the data-type flags
   * @return encoded atom bytes
   */
  protected static ByteVector renderText(ByteVector name, Item item, int flags) {
    return ItemAtomCodec.renderText(name, item, flags);
  }

  /**
   * Serializes cover-art values without writing storage.
   *
   * @param name the atom-name bytes or free-form item name
   * @param item the typed item value
   * @return encoded atom bytes
   */
  protected static ByteVector renderCovr(ByteVector name, Item item) {
    return ItemAtomCodec.renderCovr(name, item);
  }

  /**
   * Serializes Stem manifest bytes without writing storage.
   *
   * @param name the atom-name bytes or free-form item name
   * @param item the typed item value
   * @return encoded atom bytes
   */
  protected static ByteVector renderStem(ByteVector name, Item item) {
    return ItemAtomCodec.renderStem(name, item);
  }

  /**
   * Serializes a free-form named item without writing storage.
   *
   * @param name the atom-name bytes or free-form item name
   * @param item the typed item value
   * @return encoded atom bytes
   */
  protected static ByteVector renderFreeForm(String name, Item item) {
    return ItemAtomCodec.renderFreeForm(name, item);
  }
}
