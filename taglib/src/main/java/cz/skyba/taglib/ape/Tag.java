// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.ape;

import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.PropertyUpdateResult;
import cz.skyba.taglib.TagBase;
import cz.skyba.taglib.internal.Ascii;
import cz.skyba.taglib.internal.ByteVectorOutput;
import cz.skyba.taglib.internal.TextBytes;
import cz.skyba.taglib.internal.TextNumbers;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.Debug;
import cz.skyba.taglib.toolkit.MutablePropertyMap;
import cz.skyba.taglib.toolkit.Variant;

import java.util.*;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A mutable APE tag with copied item inputs and exports, a live footer and case-normalized keys.
 * Editing and rendering operate in memory; an owning audio file persists the result on save.
 */
public class Tag extends TagBase {
  private static final Variant EMPTY_VARIANT = new Variant();

  private static final String FRONT = "COVER ART (FRONT)", BACK = "COVER ART (BACK)";
  private static final Set<String> RESERVED_KEYS = Set.of("ID3", "TAG", "OGGS", "MP+");

  private record Conversion(String propertyKey, String itemKey) {
  }

  private static final List<Conversion> CONVERSIONS = List.of(
    new Conversion("TRACKNUMBER", "TRACK"),
    new Conversion("DATE", "YEAR"),
    new Conversion("ALBUMARTIST", "ALBUM ARTIST"),
    new Conversion("DISCNUMBER", "DISC"),
    new Conversion("REMIXER", "MIXARTIST"),
    new Conversion("RELEASESTATUS", "MUSICBRAINZ_ALBUMSTATUS"),
    new Conversion("RELEASETYPE", "MUSICBRAINZ_ALBUMTYPE"));
  private final NavigableMap<String, Item> items = new TreeMap<>();
  private final Footer footer = new Footer();
  private final FileBase file;
  private final long footerLocation;

  public Tag() {
    this(null, 0);
  }

  /**
   * Reads an APE tag from a borrowed file using the supplied footer offset.
   *
   * @param file           the source file, or null for an empty tag
   * @param footerLocation the absolute footer byte offset
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public Tag(FileBase file, long footerLocation) {
    this.file = file;
    this.footerLocation = footerLocation;
    read();
  }

  /**
   * Reads footer and item bytes from the borrowed source. A tag without a source is unchanged.
   */
  protected final void read() {
    if (file != null && file.isValid()) {
      file.seek(footerLocation);
      footer.setData(file.readBlock(Footer.size()));
      if (footer.tagSize() <= Footer.size() || footer.tagSize() > file.length()) {
        return;
      }
      file.seek(footerLocation + Footer.size() - footer.tagSize());
      parse(file.readBlock(footer.tagSize() - Footer.size()));
    }
  }

  private static String upper(String key) {
    return Ascii.upper(key);
  }

  /**
   * Tests whether the supplied key is valid for this tag format.
   */
  public static boolean checkKey(String key) {
    if (key.length() < 2 || key.length() > 255) {
      return false;
    }
    byte[] encoded = TextBytes.encode(key, UTF_8);
    // Native validates converted bytes. A malformed UTF-16 key converts to an empty
    // byte array, which passes its byte validation even though the original key is nonempty.
    for (byte b : encoded) {
      if ((b & 255) < 32 || (b & 255) > 126) {
        return false;
      }
    }
    return !RESERVED_KEYS.contains(Ascii.upper(TextBytes.decodeTerminated(encoded, ISO_8859_1)));
  }

  public static ByteVector fileIdentifier() {
    return Footer.fileIdentifier();
  }

  public final Footer footer() {
    return footer;
  }

  /**
   * Returns a structurally read-only live map of normalized keys. Item values exported
   * through this map are independent copies; editing those copies does not change the tag.
   *
   * @return the live key view with copied item values
   */
  public final Map<String, Item> itemListMap() {
    return Collections.unmodifiableMap(new ItemMap());
  }

  private final class ItemMap extends AbstractMap<String, Item> {
    @Override
    public int size() {
      return items.size();
    }

    @Override
    public boolean containsKey(Object key) {
      return items.containsKey(key);
    }

    @Override
    public Item get(Object key) {
      var item = items.get(key);
      return item == null ? null : new Item(item);
    }

    @Override
    public Set<Entry<String, Item>> entrySet() {
      return new ItemEntries();
    }
  }

  private final class ItemEntries extends AbstractSet<Map.Entry<String, Item>> {
    @Override
    public int size() {
      return items.size();
    }

    @Override
    public Iterator<Map.Entry<String, Item>> iterator() {
      return new ItemIterator(items.entrySet().iterator());
    }
  }

  private static final class ItemIterator implements Iterator<Map.Entry<String, Item>> {
    private final Iterator<Map.Entry<String, Item>> source;

    private ItemIterator(Iterator<Map.Entry<String, Item>> source) {
      this.source = source;
    }

    public boolean hasNext() {
      return source.hasNext();
    }

    public Map.Entry<String, Item> next() {
      var entry = source.next();
      return new AbstractMap.SimpleImmutableEntry<>(entry.getKey(), new Item(entry.getValue()));
    }
  }

  /**
   * Removes a case-normalized item key from this tag in memory.
   *
   * @param key the item key
   */
  public final void removeItem(String key) {
    items.remove(upper(key));
  }

  /**
   * Copies an item into a normalized key when the key is valid. Invalid keys are ignored.
   * Changes remain in memory.
   *
   * @param key  the destination key
   * @param item the value to copy
   */
  public final void setItem(String key, Item item) {
    if (checkKey(key)) {
      items.put(upper(key), new Item(item));
    } else {
      Debug.debug("APE::Tag::setItem() - Couldn't set an item due to an invalid key.");
    }
  }

  /**
   * Replaces the key with a binary item; an empty byte vector removes the existing item without
   * adding one.
   */
  public final void setData(String key, ByteVector value) {
    removeItem(key);
    if (!value.isEmpty()) {
      setItem(key, new Item(key, value, true));
    }
  }

  /**
   * Adds text to a normalized item key, replacing existing values by default.
   *
   * @param key   the item key
   * @param value the text value
   */
  public final void addValue(String key, String value) {
    addValue(key, value, true);
  }

  /**
   * Replaces existing values when requested, then appends nonempty text; an empty replacement only
   * removes the previous item.
   */
  public final void addValue(String key, String value, boolean replace) {
    if (replace) {
      removeItem(key);
    }
    if (value.isEmpty()) {
      return;
    }
    var item = items.get(upper(key));
    if (item != null && item.type() == Item.ItemType.Text) {
      item.appendValue(value);
    } else {
      setItem(key, new Item(key, List.of(value)));
    }
  }

  @Override
  public boolean isEmpty() {
    return items.isEmpty();
  }

  private String common(String key) {
    var item = items.get(key);
    return item == null || item.isEmpty() ? "" : joinTagValues(item.values());
  }

  private long number(String key) {
    var item = items.get(key);
    return item == null || item.isEmpty()
      ? 0
      : Integer.toUnsignedLong(TextNumbers.parseInt(item.toString()));
  }

  @Override
  public String title() {
    return common("TITLE");
  }

  @Override
  public String artist() {
    return common("ARTIST");
  }

  @Override
  public String album() {
    return common("ALBUM");
  }

  @Override
  public String comment() {
    return common("COMMENT");
  }

  @Override
  public String genre() {
    return common("GENRE");
  }

  @Override
  public long year() {
    return number("YEAR");
  }

  @Override
  public long track() {
    return number("TRACK");
  }

  @Override
  public void setTitle(String value) {
    addValue("TITLE", value);
  }

  @Override
  public void setArtist(String value) {
    addValue("ARTIST", value);
  }

  @Override
  public void setAlbum(String value) {
    addValue("ALBUM", value);
  }

  @Override
  public void setComment(String value) {
    addValue("COMMENT", value);
  }

  @Override
  public void setGenre(String value) {
    addValue("GENRE", value);
  }

  @Override
  public void setYear(long value) {
    setNumber("YEAR", value);
  }

  @Override
  public void setTrack(long value) {
    setNumber("TRACK", value);
  }

  private void setNumber(String key, long value) {
    if ((value & 0xffff_ffffL) == 0) {
      removeItem(key);
    } else {
      addValue(key, Integer.toString((int) value));
    }
  }

  /**
   * Maps APE text items to standard keys, reporting non-text items and empty keys as unsupported.
   */
  @Override
  public PropertyMap properties() {
    var result = new MutablePropertyMap();
    items.forEach(
      (key, item) -> {
        if (item.type() != Item.ItemType.Text || key.isEmpty()) {
          result.addUnsupportedData(key);
        } else {
          String name = key;
          for (var conversion : CONVERSIONS) {
            if (name.equals(conversion.itemKey())) {
              name = conversion.propertyKey();
            }
          }
          result.add(name, item.values());
        }
      });
    return result.snapshot();
  }

  @Override
  public void removeUnsupportedProperties(List<String> keys) {
    keys.forEach(this::removeItem);
  }

  @Override
  public PropertyUpdateResult replaceProperties(PropertyMap original) {
    var props = new MutablePropertyMap(original);
    for (var conversion : CONVERSIONS) {
      if (props.containsKey(conversion.propertyKey())) {
        props.add(conversion.itemKey(), props.values(conversion.propertyKey()));
        props.remove(conversion.propertyKey());
      }
    }
    items
      .entrySet()
      .removeIf(
        e ->
          !e.getKey().isEmpty()
            && e.getValue().type() == Item.ItemType.Text
            && !props.containsKey(e.getKey()));
    var invalid = new MutablePropertyMap();
    for (var entry : props) {
      var key = entry.getKey();
      var values = entry.getValue();
      if (!checkKey(key)) {
        invalid.add(key, values);
      } else if (!items.containsKey(key) || !items.get(key).values().equals(values)) {
        if (values.isEmpty()) {
          removeItem(key);
        } else {
          addValue(key, values.getFirst());
          for (int i = 1; i < values.size(); i++) {
            addValue(key, values.get(i), false);
          }
        }
      }
    }
    return PropertyUpdateResult.fromRejections(original, invalid.snapshot());
  }

  @Override
  public boolean supportsPictureEditing() {
    return true;
  }

  @Override
  public List<String> complexPropertyKeys() {
    return items.containsKey(FRONT) || items.containsKey(BACK) ? List.of("PICTURE") : List.of();
  }

  @Override
  public List<Map<String, Variant>> complexProperties(String key) {
    var result = new ArrayList<Map<String, Variant>>();
    if (!upper(key).equals("PICTURE")) {
      return result;
    }
    for (String name : List.of(FRONT, BACK)) {
      var item = items.get(name);
      if (item == null || item.type() != Item.ItemType.Binary) {
        continue;
      }
      var data = item.binaryData();
      int zero =
        data.isEmpty() || data.at(0) == (byte) 255 || data.at(0) == (byte) 137
          ? -1
          : data.find((byte) 0);
      var description =
        zero >= 0 ? TextBytes.decodeTerminated(data.data(), 0, zero, UTF_8) : "";
      if (zero >= 0) {
        data = data.mid(zero + 1);
      }
      var picture = new TreeMap<String, Variant>();
      picture.put("data", new Variant(data));
      if (!description.isEmpty()) {
        picture.put("description", new Variant(description));
      }
      picture.put("pictureType", new Variant(name.equals(BACK) ? "Back Cover" : "Front Cover"));
      result.add(Collections.unmodifiableMap(picture));
    }
    return List.copyOf(result);
  }

  @Override
  public boolean setComplexProperties(String key, List<Map<String, Variant>> values) {
    if (!upper(key).equals("PICTURE")) {
      return false;
    }
    removeItem(FRONT);
    removeItem(BACK);
    Item front = null, back = null;
    for (var picture : values) {
      var description = picture.getOrDefault("description", EMPTY_VARIANT).toText();
      var data =
        ByteVector.fromText(description, UTF_8)
          .append(new ByteVector(new byte[]{0}))
          .append(picture.getOrDefault("data", EMPTY_VARIANT).toByteVector());
      String type = picture.getOrDefault("pictureType", EMPTY_VARIANT).toText();
      if (type.equals("Back Cover")) {
        if (back == null) {
          back = new Item(BACK, data, true);
        }
      } else if (type.equals("Front Cover")) {
        front = new Item(FRONT, data, true);
      } else if (front == null) {
        front = new Item(FRONT, data, true);
      }
    }
    if (front != null) {
      setItem(FRONT, front);
    }
    if (back != null) {
      setItem(BACK, back);
    }
    return true;
  }

  /**
   * Serializes this tag to its stored byte representation.
   */
  public final ByteVector render() {
    var output = new ByteVectorOutput();
    items.values().forEach(item -> output.append(item.render()));
    var data = output.toByteVector();
    footer.setItemCount(items.size());
    footer.setTagSize(data.size() + Footer.size());
    footer.setHeaderPresent(true);
    return footer.renderHeader().append(data).append(footer.renderFooter());
  }

  /**
   * Decodes serialized APE item data into the live item map.
   *
   * @param data encoded item bytes
   */
  protected final void parse(ByteVector data) {
    long position = 0;
    for (long i = 0; i < footer.itemCount() && position <= data.size() - 11L; i++) {
      if (i >= 50000) {
        Debug.debug("APE::Tag::parse() - Maximum item count exceeded. Stopped parsing.");
        return;
      }
      int start = (int) position;
      int zero = data.find((byte) 0, start + 8);
      if (zero < 0) {
        Debug.debug("APE::Tag::parse() - Couldn't find a key/value separator. Stopped parsing.");
        return;
      }
      int keyLength = zero - start - 8;
      long valueLength = data.toUnsignedInt(start, false);
      if (valueLength >= data.size() || position > data.size() - valueLength) {
        Debug.debug("APE::Tag::parse() - Invalid val length. Stopped parsing.");
        return;
      }
      String key = TextBytes.decodeTerminated(data.data(), start + 8, keyLength, ISO_8859_1);
      if (checkKey(key)) {
        var item = new Item();
        item.parse(data, start);
        items.put(upper(item.key()), item);
      } else {
        Debug.debug("APE::Tag::parse() - Skipped an item due to an invalid key.");
      }
      position += keyLength + valueLength + 9;
    }
  }
}
