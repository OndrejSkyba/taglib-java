// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.ogg;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.PropertyUpdateResult;
import cz.skyba.taglib.TagBase;
import cz.skyba.taglib.flac.Picture;
import cz.skyba.taglib.internal.*;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.Variant;

import java.util.*;

import static cz.skyba.taglib.internal.xiph.XiphComment.*;
import static java.lang.System.Logger.Level.DEBUG;
import static java.nio.charset.StandardCharsets.UTF_8;

/// Mutable Vorbis-style text comments and picture blocks.
/// Field keys are matched using ASCII uppercase. Collection containers are copied on export,
/// while picture lists retain live picture references. OggFile-bound changes require saving
/// the owner.
public class XiphComment extends TagBase {
  private static final System.Logger LOG = System.getLogger(XiphComment.class.getName());

  private final NavigableMap<String, List<String>> fields = new TreeMap<>();
  private final List<Picture> pictures = new ArrayList<>();
  private String vendor = "", commentField = "";
  private final Map<String, List<String>> view = new AbstractMap<>() {
    @Override
    public List<String> get(Object key) {
      var values = key instanceof String ? fields.get(key) : null;
      return values == null ? null : Collections.unmodifiableList(values);
    }

    @Override
    public boolean containsKey(Object key) {
      return fields.containsKey(key);
    }

    @Override
    public Set<Entry<String, List<String>>> entrySet() {
      var result = new LinkedHashSet<Entry<String, List<String>>>();
      fields.forEach((key, value) -> result.add(new SimpleImmutableEntry<>(key, Collections.unmodifiableList(value))));
      return Collections.unmodifiableSet(result);
    }
  };

  public XiphComment() {
  }

  /// Decodes a comment payload into mutable text fields and pictures.
  ///
  /// @param data serialized comment payload
  public XiphComment(ByteVector data) {
    parseData(Objects.requireNonNull(data));
  }

  private static String upper(String value) {
    return Ascii.upper(value);
  }

  private List<String> values(String key) {
    return fields.getOrDefault(key, List.of());
  }

  private String text(String key) {
    return joinTagValues(values(key));
  }

  @Override
  public String title() {
    return text("TITLE");
  }

  @Override
  public String artist() {
    return text("ARTIST");
  }

  @Override
  public String album() {
    return text("ALBUM");
  }

  @Override
  public String genre() {
    return text("GENRE");
  }

  /// Prefers DESCRIPTION over COMMENT and remembers the selected field for later comment updates.
  @Override
  public String comment() {
    if (!values("DESCRIPTION").isEmpty()) {
      commentField = "DESCRIPTION";
      return text(commentField);
    }
    if (!values("COMMENT").isEmpty()) {
      commentField = "COMMENT";
      return text(commentField);
    }
    return "";
  }

  private long number(String primary, String alternate) {
    var values = values(primary);
    if (values.isEmpty()) {
      values = values(alternate);
    }
    return values.isEmpty() ? 0 : Integer.toUnsignedLong(TextNumbers.parseInt(values.getFirst()));
  }

  @Override
  public long year() {
    return number("DATE", "YEAR");
  }

  @Override
  public long track() {
    return number("TRACKNUMBER", "TRACKNUM");
  }

  @Override
  public void setTitle(String value) {
    addField("TITLE", value);
  }

  @Override
  public void setArtist(String value) {
    addField("ARTIST", value);
  }

  @Override
  public void setAlbum(String value) {
    addField("ALBUM", value);
  }

  @Override
  public void setGenre(String value) {
    addField("GENRE", value);
  }

  /// Updates the remembered comment field; the first write prefers an existing DESCRIPTION field
  /// over COMMENT.
  @Override
  public void setComment(String value) {
    if (commentField.isEmpty()) {
      commentField = values("DESCRIPTION").isEmpty() ? "COMMENT" : "DESCRIPTION";
    }
    addField(commentField, value);
  }

  @Override
  public void setYear(long value) {
    setNumber("DATE", "YEAR", value);
  }

  @Override
  public void setTrack(long value) {
    setNumber("TRACKNUMBER", "TRACKNUM", value);
  }

  private void setNumber(String primary, String alternate, long value) {
    removeFields(alternate);
    if ((int) value == 0) {
      removeFields(primary);
    } else {
      addField(primary, Integer.toString((int) value));
    }
  }

  @Override
  public boolean isEmpty() {
    return fields.values().stream().allMatch(List::isEmpty);
  }

  /// Counts serialized text values and picture entries.
  ///
  /// @return the comment entry count
  public final long fieldCount() {
    long count = pictures.size();
    for (var values : fields.values()) {
      count += values.size();
    }
    return count & 0xffff_ffffL;
  }

  /// {@return a read-only live map} Looking up an absent key returns `null` without adding it.
  public final Map<String, List<String>> fieldListMap() {
    return Collections.unmodifiableMap(view);
  }

  @Override
  public PropertyMap properties() {
    var result = PropertyMap.builder();
    fields.forEach(result::put);
    return result.build();
  }

  @Override
  public PropertyUpdateResult replaceProperties(PropertyMap properties) {
    for (var key : new ArrayList<>(fields.keySet())) {
      if (!properties.containsKey(key)) {
        removeFields(key);
      }
    }
    var invalid = PropertyMap.builder();
    for (var entry : properties.asMap().entrySet()) {
      var key = entry.getKey();
      var values = entry.getValue();
      if (!checkKey(key)) {
        invalid.put(key, values);
      } else if (!fields.containsKey(key) || !values.equals(fields.get(key))) {
        if (values.isEmpty()) {
          removeFields(key);
        } else {
          addField(key, values.getFirst(), true);
          for (int i = 1; i < values.size(); i++) {
            addField(key, values.get(i), false);
          }
        }
      }
    }
    return PropertyUpdateResult.fromRejections(properties, invalid.build());
  }

  @Override
  public boolean supportsPictureEditing() {
    return true;
  }

  @Override
  public List<String> complexPropertyKeys() {
    return pictures.isEmpty() ? List.of() : List.of("PICTURE");
  }

  @Override
  public List<Map<String, Variant>> complexProperties(String key) {
    return upper(key).equals("PICTURE") ? pictures.stream().map(PictureProperties::export).toList() : List.of();
  }

  @Override
  public boolean setComplexProperties(String key, List<Map<String, Variant>> value) {
    if (!upper(key).equals("PICTURE")) {
      return false;
    }
    removeAllPictures();
    for (var entry : value) {
      addPicture(PictureProperties.create(entry));
    }
    return true;
  }

  /// Tests whether a field key uses allowed printable ASCII bytes without an equals sign.
  ///
  /// @param key the key to validate
  /// @return whether it can be represented as a comment key
  public static boolean checkKey(String key) {
    return validKey(key);
  }

  public final String vendorID() {
    return vendor;
  }

  /// Adds a text value after validating and ASCII-normalizing its key.
  /// Invalid keys are ignored. Empty text is not added; replacing with empty text clears prior values. Existing values are replaced.
  ///
  /// @param key   the comment field key
  /// @param value the text value
  public final void addField(String key, String value) {
    addField(key, value, true);
  }

  /// Adds a text value after validating and ASCII-normalizing its key.
  /// Invalid keys are ignored. Empty text is not added; replacing with empty text clears prior values.
  ///
  /// @param key     the comment field key
  /// @param value   the text value
  /// @param replace whether existing values are removed first
  public final void addField(String key, String value, boolean replace) {
    Objects.requireNonNull(value);
    if (!checkKey(key)) {
      LOG.log(DEBUG, "Ignoring a field with an invalid key.");
      return;
    }
    String upper = upper(key);
    if (replace) {
      removeFields(upper);
    }
    if (!value.isEmpty()) {
      fields.computeIfAbsent(upper, _ -> new ArrayList<>()).add(value);
    }
  }

  /// Removes all values of a normalized field key.
  ///
  /// @param key the comment field key
  public final void removeFields(String key) {
    fields.remove(upper(key));
  }

  /// Removes values equal to the supplied text from a normalized field key. An absent key gains an empty list.
  ///
  /// @param key   the comment field key
  /// @param value the exact text to remove
  public final void removeFields(String key, String value) {
    fields.computeIfAbsent(upper(key), _ -> new ArrayList<>()).removeIf(value::equals);
  }

  /// Clears all text fields in memory, preserving vendor text and pictures.
  public final void removeAllFields() {
    fields.clear();
  }

  /// Tests whether a normalized field key has at least one value.
  ///
  /// @param key the key to query
  /// @return whether a nonempty value list exists
  public final boolean contains(String key) {
    return !values(upper(key)).isEmpty();
  }

  /// Returns an immutable list snapshot containing live picture references.
  ///
  /// @return the retained pictures
  public final List<Picture> pictureList() {
    return List.copyOf(pictures);
  }

  /// Retains a live picture reference without copying it. The change is in memory.
  ///
  /// @param value the picture to retain
  public final void addPicture(Picture value) {
    pictures.add(Objects.requireNonNull(value));
  }

  /// Removes the first matching picture by identity.
  ///
  /// @param value the picture instance to remove
  public final void removePicture(Picture value) {
    for (int i = 0; i < pictures.size(); i++) {
      if (pictures.get(i) == value) {
        pictures.remove(i);
        break;
      }
    }
  }

  /// Removes every picture reference in memory, preserving text fields.
  public final void removeAllPictures() {
    pictures.clear();
  }

  /// Serializes text comments and picture blocks without writing storage. A framing byte is appended.
  ///
  /// @return serialized comment payload
  public final ByteVector render() {
    return render(true);
  }

  /// Serializes text comments and picture blocks without writing storage.
  ///
  /// @param framing whether to append the comment framing byte
  /// @return serialized comment payload
  public final ByteVector render(boolean framing) {
    var data = new ByteVectorOutput();
    var vendorData = ByteVector.fromText(vendor, UTF_8);
    data.append(ByteVector.fromUnsignedInt(vendorData.size(), false)).append(vendorData).append(ByteVector.fromUnsignedInt(fieldCount(), false));
    for (var entry : fields.entrySet()) {
      for (var value : entry.getValue()) {
        var field = ByteVector.fromText(entry.getKey(), UTF_8)
          .append(new ByteVector("="))
          .append(ByteVector.fromText(value, UTF_8));
        data.append(ByteVector.fromUnsignedInt(field.size(), false)).append(field);
      }
    }
    for (var picture : pictures) {
      var field = new ByteVector("METADATA_BLOCK_PICTURE=").append(new ByteVector(picture.render()).toBase64());
      data.append(ByteVector.fromUnsignedInt(field.size(), false)).append(field);
    }
    if (framing) {
      data.append(new ByteVector(new byte[]{1}));
    }
    return data.toByteVector();
  }

  /// Decodes serialized comment content into this object's fields and pictures.
  ///
  /// @param data serialized comment payload
  protected final void parse(ByteVector data) {
    parseData(data);
  }

  private void parseData(ByteVector data) {
    if (data.size() < 8) {
      LOG.log(DEBUG, "Comment data is too short.");
      return;
    }
    long vendorLength = data.toUnsignedInt(0, false);
    if (vendorLength > data.size() - 4L) {
      LOG.log(DEBUG, "Vendor ID exceeds comment data.");
      return;
    }
    vendor = TextBytes.decodeTerminated(data.data(), 4, (int) vendorLength, UTF_8);
    int pos = 4 + (int) vendorLength;
    if (data.size() - pos < 4) {
      LOG.log(DEBUG, "Missing comment field count.");
      return;
    }
    long count = data.toUnsignedInt(pos, false);
    pos += 4;
    if (count > 50_000 || count > (data.size() - 8L) / 4) {
      LOG.log(DEBUG, "Maximum comment field count exceeded.");
      return;
    }
    for (int i = 0; i < count; i++) {
      if (data.size() - pos < 4) {
        break;
      }
      long length = data.toUnsignedInt(pos, false);
      pos += 4;
      if (length > data.size() - (long) pos) {
        break;
      }
      var entry = data.mid(pos, (int) length);
      pos += (int) length;
      int sep = entry.find(new ByteVector("="));
      if (sep < 1) {
        LOG.log(DEBUG, "Discarding a field without a key/value separator.");
        continue;
      }
      String key = upper(TextBytes.decodeTerminated(entry.data(), 0, sep, UTF_8));
      if (!checkKey(key)) {
        LOG.log(DEBUG, "Discarding a field with an invalid key.");
        continue;
      }
      if (key.equals("METADATA_BLOCK_PICTURE") || key.equals("COVERART")) {
        var encoded = entry.mid(sep + 1);
        var compact = withoutPictureWhitespace(encoded.data());
        var bytes = ByteVector.fromBase64(compact == encoded.data() ? encoded : new ByteVector(compact));
        if (bytes.isEmpty()) {
          LOG.log(DEBUG, "Discarding a picture field with invalid Base64 data.");
          continue;
        }
        var picture = new Picture();
        if (key.charAt(0) == 'M') {
          if (!picture.parse(bytes.data())) {
            LOG.log(DEBUG, "Discarding an invalid FLAC picture field.");
            continue;
          }
        } else {
          picture.setData(bytes.data());
          picture.setMimeType("image/");
          picture.setType(Picture.Type.Other);
        }
        pictures.add(picture);
      } else {
        addField(key, TextBytes.decodeTerminated(entry.data(), sep + 1, entry.size() - sep - 1, UTF_8), false);
      }
    }
  }
}
