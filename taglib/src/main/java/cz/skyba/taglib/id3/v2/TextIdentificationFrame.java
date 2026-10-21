// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.id3.v1.Genres;
import cz.skyba.taglib.internal.ByteVectorOutput;
import cz.skyba.taglib.internal.TextNumbers;
import cz.skyba.taglib.toolkit.ByteVector;

import java.nio.charset.Charset;
import java.util.*;

import static java.nio.charset.StandardCharsets.*;

/// A mutable ID3v2 frame containing text identification values.
/// Editing a frame does not write a file; use the owning file's save operation to persist changes.
/// Text values also support mappings for involved people and musician credits.
public class TextIdentificationFrame extends Frame {
  private static final Map<String, String> ROLES = Map.of(
    "ARRANGER", "ARRANGER", "ENGINEER", "ENGINEER", "PRODUCER", "PRODUCER",
    "DJMIXER", "DJ-MIX", "MIXER", "MIX");
  private static final Map<String, String> REVERSE_ROLES = reverseRoles();
  private static final Map<String, String> PEOPLE = Collections.unmodifiableMap(new TreeMap<>(ROLES));
  private Charset encoding = ISO_8859_1;
  private List<String> fields = new ArrayList<>();

  private static Map<String, String> reverseRoles() {
    var result = new HashMap<String, String>();
    ROLES.forEach((key, role) -> result.putIfAbsent(role, key));
    return Map.copyOf(result);
  }

  private static String roleFor(String key) {
    return PEOPLE.getOrDefault(key, "");
  }

  /// Creates a frame containing text identification values.
  ///
  /// @param encoding the requested text encoding
  /// @param type     the frame identifier to copy
  ///                 The encoding can be adjusted when rendered to represent the field values.
  public TextIdentificationFrame(ByteVector type, Charset encoding) {
    super(type);
    this.encoding = Id3Text.requireEncoding(encoding);
  }

  /// Decodes text identification values from serialized frame bytes, including their frame header.
  ///
  /// @param data serialized bytes including the frame header
  public TextIdentificationFrame(ByteVector data) {
    super(data);
    parseText(fieldData(data));
  }

  /// Decodes text identification values from serialized frame bytes, including their frame header.
  /// The supplied parsed frame header is retained as a live reference.
  ///
  /// @param data   serialized bytes including the frame header
  /// @param header the live parsed frame header
  protected TextIdentificationFrame(ByteVector data, Header header) {
    super(header);
    parseText(fieldData(data));
  }

  /// Replaces the complete logical field list with copied text values.
  /// For a TXXX frame this base operation includes the description field; use its
  /// `setValues` operation to preserve an existing description.
  ///
  /// @param values the complete logical field list
  public final void setText(List<String> values) {
    fields = new ArrayList<>(List.copyOf(values));
  }

  @Override
  public void setText(String value) {
    fields = new ArrayList<>(List.of(Objects.requireNonNull(value)));
  }

  @Override
  public String toString() {
    return String.join(" ", fields);
  }

  @Override
  public List<String> toStringList() {
    return new ArrayList<>(fields);
  }

  /// Returns an independent list of text values, including a TXXX description when present.
  ///
  /// @return copied text fields
  public final List<String> fieldList() {
    return new ArrayList<>(fields);
  }

  final String firstField() {
    return fields.isEmpty() ? "" : fields.getFirst();
  }

  final int fieldCount() {
    return fields.size();
  }

  public final Charset textEncoding() {
    return encoding;
  }

  public final void setTextEncoding(Charset value) {
    encoding = Id3Text.requireEncoding(value);
  }

  /// Returns the read-only role map. Looking up an absent key does not add it.
  public static Map<String, String> involvedPeopleMap() {
    return PEOPLE;
  }

  /// Creates an involved-people frame from supported role properties.
  ///
  /// @param properties role or performer properties to convert
  /// @return the constructed credit frame
  public static TextIdentificationFrame createTIPLFrame(PropertyMap properties) {
    var frame = new TextIdentificationFrame(new ByteVector("TIPL"));
    var values = new ArrayList<String>();
    properties.asMap().forEach((key, names) -> {
      String role = roleFor(key);
      if (!role.isEmpty()) {
        values.add(role);
        values.add(String.join(",", names));
      }
    });
    frame.setText(values);
    return frame;
  }

  /// Creates a musician-credit frame from performer properties.
  ///
  /// @param properties role or performer properties to convert
  /// @return the constructed credit frame
  public static TextIdentificationFrame createTMCLFrame(PropertyMap properties) {
    var frame = new TextIdentificationFrame(new ByteVector("TMCL"));
    var values = new ArrayList<String>();
    properties.asMap().forEach((key, names) -> {
      if (key.startsWith(instrumentPrefix)) {
        values.add(key.substring(instrumentPrefix.length()));
        values.add(String.join(",", names));
      }
    });
    frame.setText(values);
    return frame;
  }

  @Override
  public PropertyMap asProperties() {
    if (frameID().equals(new ByteVector("TIPL"))) {
      return peopleProperties(false);
    }
    if (frameID().equals(new ByteVector("TMCL"))) {
      return peopleProperties(true);
    }
    String key = frameIDToKey(frameID());
    if (key.isEmpty()) {
      return Id3Text.unsupported(frameID());
    }
    var values = fieldList();
    for (int i = 0; i < values.size(); i++) {
      String value = values.get(i);
      if (key.equals("GENRE")) {
        var number = TextNumbers.extractInt(value);
        if (number.ok()) {
          values.set(i, Genres.genre(number.value()));
        }
      } else if (key.equals("DATE")) {
        int position = value.indexOf('T');
        if (position >= 0) {
          values.set(i, value.substring(0, position) + " " + value.substring(position + 1));
        }
      }
    }
    var result = PropertyMap.builder();
    result.add(key, values);
    return result.build();
  }

  private PropertyMap peopleProperties(boolean instruments) {
    if ((fields.size() & 1) != 0) {
      return Id3Text.unsupported(frameID());
    }
    var result = PropertyMap.builder();
    for (int i = 0; i < fields.size(); i += 2) {
      String role = Id3Text.upper(fields.get(i)), key;
      if (instruments) {
        if (role.isEmpty()) {
          return Id3Text.unsupported(frameID());
        }
        key = instrumentPrefix + role;
      } else {
        key = REVERSE_ROLES.getOrDefault(role, "");
        if (key.isEmpty()) {
          return Id3Text.unsupported(frameID());
        }
      }
      result.add(key, Arrays.asList(fields.get(i + 1).split(",", -1)));
    }
    return result.build();
  }

  @Override
  protected void parseFields(ByteVector data) {
    parseText(data);
  }

  private void parseText(ByteVector data) {
    if (data.size() < 2) {
      return;
    }
    encoding = Id3Text.encoding(data.at(0));
    int alignment = ISO_8859_1.equals(encoding) || UTF_8.equals(encoding) ? 1 : 2;
    int length = data.size() - 1;
    while (length > 0 && data.at(length) == 0) {
      length--;
    }
    while (length % alignment != 0) {
      length++;
    }
    var parts = data.mid(1, length).split(textDelimiter(encoding), alignment);
    fields.clear();
    int firstBom = 0;
    for (int i = 0; i < parts.size(); i++) {
      ByteVector part = parts.get(i);
      if (part.isEmpty() && !(i == 0 && frameID().equals(new ByteVector("TXXX")))) {
        continue;
      }
      Charset type = encoding;
      if (UTF_16.equals(type)) {
        if (i == 0) {
          firstBom = part.toUnsignedShort();
        } else {
          int bom = part.toUnsignedShort();
          if (bom != 0xfeff && bom != 0xfffe) {
            if (firstBom == 0xfeff) {
              type = UTF_16BE;
            } else if (firstBom == 0xfffe) {
              type = UTF_16LE;
            }
          }
        }
      }
      fields.add(Id3Text.decode(part, type));
    }
  }

  @Override
  protected ByteVector renderFields() {
    Charset type = checkTextEncoding(fields, encoding);
    var result = new ByteVectorOutput();
    result.append(Id3Text.encodingByte(type));
    for (int i = 0; i < fields.size(); i++) {
      if (i > 0) {
        result.append(textDelimiter(type));
      }
      result.append(ByteVector.fromText(fields.get(i), type));
    }
    return result.toByteVector();
  }
}
