// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.riff.info;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.PropertyUpdateResult;
import cz.skyba.taglib.TagBase;
import cz.skyba.taglib.internal.ByteVectorOutput;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.TagString;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Mutable RIFF INFO text fields keyed by four-byte identifiers.
 * Unknown identifiers and empty fields can be retained. Editing changes the in-memory tag;
 * an owning RIFF file writes the tag on save.
 */
public class Tag extends TagBase {
  private static final StringHandler DEFAULT_HANDLER = new StringHandler();
  private static volatile StringHandler stringHandler = DEFAULT_HANDLER;
  private static final Map<String, String> PROPERTY_KEYS = Map.ofEntries(
    Map.entry("IPRD", "ALBUM"), Map.entry("IENG", "ARRANGER"),
    Map.entry("IART", "ARTIST"), Map.entry("IBSU", "ARTISTWEBPAGE"),
    Map.entry("IBPM", "BPM"), Map.entry("ICMT", "COMMENT"),
    Map.entry("IMUS", "COMPOSER"), Map.entry("ICOP", "COPYRIGHT"),
    Map.entry("ICRD", "DATE"), Map.entry("PRT1", "DISCSUBTITLE"),
    Map.entry("ITCH", "ENCODEDBY"), Map.entry("ISFT", "ENCODING"),
    Map.entry("IDIT", "ENCODINGTIME"), Map.entry("IGNR", "GENRE"),
    Map.entry("ISRC", "ISRC"), Map.entry("IPUB", "LABEL"),
    Map.entry("ILNG", "LANGUAGE"), Map.entry("IWRI", "LYRICIST"),
    Map.entry("IMED", "MEDIA"), Map.entry("ISTR", "PERFORMER"),
    Map.entry("ICNT", "RELEASECOUNTRY"), Map.entry("IEDT", "REMIXER"),
    Map.entry("INAM", "TITLE"), Map.entry("IPRT", "TRACKNUMBER"));
  private static final Map<String, String> IDS = reverse(PROPERTY_KEYS);

  private final NavigableMap<ByteVector, String> fields = new TreeMap<>();

  private static Map<String, String> reverse(Map<String, String> source) {
    var result = new HashMap<String, String>();
    source.forEach((id, key) -> result.put(key, id));
    return Map.copyOf(result);
  }

  public Tag() {
  }

  /**
   * Decodes an INFO payload into mutable fields.
   *
   * @param data serialized INFO data
   */
  public Tag(ByteVector data) {
    parseFields(Objects.requireNonNull(data));
  }

  /**
   * Installs the shared INFO text decoder and encoder. Null restores the default handler.
   * Existing decoded fields are unchanged.
   *
   * @param handler the handler to borrow, or null for the default
   */
  public static void setStringHandler(StringHandler handler) {
    stringHandler = handler == null ? DEFAULT_HANDLER : handler;
  }

  /**
   * Returns an independent map snapshot with copied byte-vector identifiers.
   * Editing the returned map or keys does not change this tag.
   *
   * @return copied field identifiers and text values
   */
  public final Map<ByteVector, String> fieldListMap() {
    var result = new TreeMap<ByteVector, String>();
    fields.forEach((id, value) -> result.put(new ByteVector(id), value));
    return result;
  }

  /**
   * Looks up a text field without creating it.
   *
   * @param id the four-byte field identifier
   * @return the field value, or an empty string when absent
   */
  public final String fieldText(ByteVector id) {
    return fields.getOrDefault(id, "");
  }

  /**
   * Sets a valid four-byte INFO field; invalid identifiers are ignored and empty text removes the
   * field.
   */
  public final void setFieldText(ByteVector id, String value) {
    Objects.requireNonNull(id);
    Objects.requireNonNull(value);
    if (!validId(id)) {
      return;
    }
    if (value.isEmpty()) {
      removeField(id);
    } else {
      fields.put(new ByteVector(id), value);
    }
  }

  /**
   * Removes a field in memory.
   *
   * @param id the field identifier to remove
   */
  public final void removeField(ByteVector id) {
    fields.remove(Objects.requireNonNull(id));
  }

  private static ByteVector id(String text) {
    return new ByteVector(text.getBytes(StandardCharsets.ISO_8859_1));
  }

  private String text(String id) {
    return fieldText(id(id));
  }

  private void text(String id, String value) {
    setFieldText(id(id), value);
  }

  @Override
  public String title() {
    return text("INAM");
  }

  @Override
  public String artist() {
    return text("IART");
  }

  @Override
  public String album() {
    return text("IPRD");
  }

  @Override
  public String comment() {
    return text("ICMT");
  }

  @Override
  public String genre() {
    return text("IGNR");
  }

  @Override
  public long year() {
    String value = text("ICRD");
    return Integer.toUnsignedLong(new TagString(value.substring(0, Math.min(4, value.length()))).toInt());
  }

  @Override
  public long track() {
    return Integer.toUnsignedLong(new TagString(text("IPRT")).toInt());
  }

  @Override
  public void setTitle(String value) {
    text("INAM", value);
  }

  @Override
  public void setArtist(String value) {
    text("IART", value);
  }

  @Override
  public void setAlbum(String value) {
    text("IPRD", value);
  }

  @Override
  public void setComment(String value) {
    text("ICMT", value);
  }

  @Override
  public void setGenre(String value) {
    text("IGNR", value);
  }

  @Override
  public void setYear(long value) {
    number("ICRD", value);
  }

  @Override
  public void setTrack(long value) {
    number("IPRT", value);
  }

  private void number(String id, long value) {
    int nativeValue = (int) value;
    if (nativeValue == 0) {
      removeField(id(id));
    } else {
      text(id, Integer.toString(nativeValue));
    }
  }

  @Override
  public boolean isEmpty() {
    return fields.isEmpty();
  }

  /**
   * Exports mapped INFO fields and records an empty opaque-data identifier for each unknown field ID.
   *
   * @return the projected properties
   */
  @Override
  public PropertyMap properties() {
    var result = PropertyMap.builder();
    fields.forEach((id, value) -> {
      String key = PROPERTY_KEYS.get(new TagString(id).toString());
      if (key == null) {
        result.addUnsupportedData("");
      } else {
        result.add(key, value);
      }
    });
    return result.build();
  }

  @Override
  public PropertyUpdateResult replaceProperties(PropertyMap input) {
    Objects.requireNonNull(input);
    for (String key : properties().asMap().keySet()) {
      if (!input.containsKey(key) || input.get(key).isEmpty()) {
        fields.remove(id(IDS.get(key)));
      }
    }
    var rejected = PropertyMap.builder();
    input.asMap().forEach((key, values) -> {
      String id = IDS.get(key);
      if (id != null && !values.isEmpty()) {
        fields.put(id(id), values.getFirst());
      } else {
        rejected.put(key, values);
      }
    });
    return PropertyUpdateResult.fromRejections(input, rejected.build());
  }

  @Override
  public void removeUnsupportedProperties(List<String> identifiers) {
    for (String identifier : identifiers) {
      removeField(new TagString(identifier).data(TagString.Type.LATIN1));
    }
  }

  /**
   * Serializes this tag to its stored byte representation.
   */
  public final ByteVector render() {
    var data = new ByteVectorOutput();
    data.append(new byte[]{'I', 'N', 'F', 'O'});
    for (var entry : fields.entrySet()) {
      byte[] text = stringHandler.render(entry.getValue()).toByteArray();
      if (text.length == 0) {
        continue;
      }
      int length = Math.addExact(text.length, 1);
      int paddedEnd = Math.addExact(data.size(), Math.addExact(8, Math.addExact(length, length & 1)));
      data.append(entry.getKey().toByteArray());
      data.append(ByteVector.fromUnsignedInt(Integer.toUnsignedLong(length), false).toByteArray());
      data.append(text);
      do {
        data.append((byte) 0);
      } while (data.size() < paddedEnd);
    }
    return data.size() == 4 ? new ByteVector() : data.toByteVector();
  }

  /**
   * Decodes field chunks beginning at byte offset four of the supplied data. An INFO signature
   * is not required by this operation.
   *
   * @param data the bytes to decode
   */
  protected final void parse(ByteVector data) {
    parseFields(data);
  }

  private void parseFields(ByteVector data) {
    int position = 4;
    while (position < data.size()) {
      long length = position + 4L >= data.size() ? 0 : data.toUnsignedInt(position + 4, false);
      // Native unsigned subtraction admits a short header with a valid four-byte
      // ID. It can overwrite a previous field with an empty string. Available
      // payload is clamped; the declared length must never drive allocation.
      if (length > ((data.size() - position - 8L) & 0xffff_ffffL)) {
        break;
      }
      ByteVector id = data.mid(position, 4);
      if (validId(id)) {
        int available = (int) Math.clamp(data.size() - position - 8L, 0L, length);
        ByteVector payload = available == 0 ? new ByteVector() : data.mid(position + 8, available);
        fields.put(id, stringHandler.parse(payload));
      }
      long next = position + 8L + length + (length & 1);
      if (next > data.size()) {
        break;
      }
      position = (int) next;
    }
  }

  private static boolean validId(ByteVector id) {
    if (id.size() != 4) {
      return false;
    }
    for (byte value : id) {
      if ((value & 255) < 32 || (value & 255) > 127) {
        return false;
      }
    }
    return true;
  }
}
