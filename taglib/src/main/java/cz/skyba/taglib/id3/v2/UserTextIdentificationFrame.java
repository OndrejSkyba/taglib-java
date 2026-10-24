// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.toolkit.ByteVector;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

/// A mutable ID3v2 frame for user-defined text information (TXXX).
/// Editing a frame does not write a file; use the owning file's save operation to persist changes.
/// The description remains the first logical text field when text values are replaced.
public class UserTextIdentificationFrame extends TextIdentificationFrame {
  private static final Map<String, String> KEYS = Map.ofEntries(
    Map.entry("MUSICBRAINZ ALBUM ID", "MUSICBRAINZ_ALBUMID"),
    Map.entry("MUSICBRAINZ ARTIST ID", "MUSICBRAINZ_ARTISTID"),
    Map.entry("MUSICBRAINZ ALBUM ARTIST ID", "MUSICBRAINZ_ALBUMARTISTID"),
    Map.entry("MUSICBRAINZ ALBUM RELEASE COUNTRY", "RELEASECOUNTRY"),
    Map.entry("MUSICBRAINZ ALBUM STATUS", "RELEASESTATUS"),
    Map.entry("MUSICBRAINZ ALBUM TYPE", "RELEASETYPE"),
    Map.entry("MUSICBRAINZ RELEASE GROUP ID", "MUSICBRAINZ_RELEASEGROUPID"),
    Map.entry("MUSICBRAINZ RELEASE TRACK ID", "MUSICBRAINZ_RELEASETRACKID"),
    Map.entry("MUSICBRAINZ WORK ID", "MUSICBRAINZ_WORKID"),
    Map.entry("ACOUSTID ID", "ACOUSTID_ID"),
    Map.entry("ACOUSTID FINGERPRINT", "ACOUSTID_FINGERPRINT"),
    Map.entry("MUSICIP PUID", "MUSICIP_PUID"));
  private static final Map<String, String> DESCRIPTIONS =
    KEYS.entrySet().stream()
      .collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));

  public UserTextIdentificationFrame() {
    this(ISO_8859_1);
  }

  public UserTextIdentificationFrame(Charset encoding) {
    super(new ByteVector("TXXX"), encoding);
    setValues(List.of("", ""));
  }

  /// Decodes TXXX description and text values from serialized frame bytes, including their frame header.
  ///
  /// @param data serialized bytes including the frame header
  public UserTextIdentificationFrame(ByteVector data) {
    super(data);
    checkFields();
  }

  /// Creates a frame containing TXXX description and text values.
  ///
  /// @param description the descriptive text
  /// @param values      text values to copy
  public UserTextIdentificationFrame(String description, List<String> values) {
    this(description, values, UTF_8);
  }

  /// Creates a frame containing TXXX description and text values.
  ///
  /// @param encoding    the requested text encoding
  /// @param description the descriptive text
  /// @param values      text values to copy
  public UserTextIdentificationFrame(String description, List<String> values, Charset encoding) {
    super(new ByteVector("TXXX"), encoding);
    setDescription(description);
    setValues(values);
  }

  UserTextIdentificationFrame(ByteVector data, Header header) {
    super(data, header);
    checkFields();
  }

  public final String description() {
    return firstField();
  }

  public final void setDescription(String value) {
    Objects.requireNonNull(value);
    var fields = fieldList();
    if (fields.isEmpty()) {
      fields.add(value);
    } else {
      fields.set(0, value);
    }
    super.setText(fields);
  }

  @Override
  public void setText(String value) {
    setValues(List.of(Objects.requireNonNull(value)));
  }

  /// Replaces the text values while preserving the description as the first logical field.
  ///
  /// @param values the replacement text values, excluding the description
  public final void setValues(List<String> values) {
    var copy = List.copyOf(values);
    if (description().isEmpty()) {
      setDescription("");
    }
    super.setText(normalizedFields(description(), copy));
  }

  private static List<String> normalizedFields(String description, List<String> values) {
    var fields = new ArrayList<String>(values.size() + 1);
    fields.add(description);
    fields.addAll(values);
    return fields;
  }

  @Override
  public String toString() {
    var fields = fieldList();
    if (!fields.isEmpty()) {
      fields.removeFirst();
    }
    return "[" + Id3Text.detachedLeft(description()) + "] " + String.join(" ", fields);
  }

  @Override
  public PropertyMap asProperties() {
    var fields = fieldList();
    if (fields.isEmpty()) {
      throw new IllegalStateException("Native TXXX property iteration requires a description entry");
    }
    var result = PropertyMap.builder();
    String key = txxxToKey(description());
    for (int i = 1; i < fields.size(); i++) {
      result.add(key, List.of(fields.get(i)));
    }
    return result.build();
  }

  /// Maps a TXXX description to its standard property key, using ASCII uppercase and recognized aliases.
  ///
  /// @param description the field description
  /// @return the mapped property key
  public static String txxxToKey(String description) {
    String key = Id3Text.upper(description);
    return KEYS.getOrDefault(key, key);
  }

  /// Maps a recognized property key to its conventional TXXX description; otherwise retains the supplied key.
  ///
  /// @param key the property key
  /// @return the TXXX description
  public static String keyToTXXX(String key) {
    String upper = Id3Text.upper(key);
    return DESCRIPTIONS.getOrDefault(upper, key);
  }

  /// Returns the first live matching frame from a tag.
  ///
  /// @param tag   the tag to search
  /// @param value the owner or description to match exactly
  /// @return the matching frame, or `null` if absent
  public static UserTextIdentificationFrame find(Tag tag, String value) {
    return find(tag.frameList(new ByteVector("TXXX")), value);
  }

  /// Returns the first live matching frame from the supplied frame sequence.
  ///
  /// @param frames      the frames to search in iteration order
  /// @param description the description to match exactly
  /// @return the matching frame, or `null` if absent
  public static UserTextIdentificationFrame find(Iterable<? extends Frame> frames, String description) {
    for (Frame frame : frames) {
      if (frame instanceof UserTextIdentificationFrame user && user.description().equals(description)) {
        return user;
      }
    }
    return null;
  }

  private void checkFields() {
    int size = fieldCount();
    if (size == 0) {
      setDescription("");
    }
    if (size <= 1) {
      setValues(List.of(""));
    }
  }
}
