// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.ape;

import cz.skyba.taglib.internal.ByteVectorOutput;
import cz.skyba.taglib.internal.TextBytes;
import cz.skyba.taglib.toolkit.ByteVector;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static java.lang.System.Logger.Level.DEBUG;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A mutable APE item with a key, type, read-only flag and independent text and binary fields.
 * Changing only the type leaves both stored fields unchanged. Text and binary replacement
 * operations clear the other payload. Binary inputs and exports are copied.
 */
public final class Item {
  private static final System.Logger LOG = System.getLogger(Item.class.getName());

  /**
   * The encoded APE item kind.
   */
  public enum ItemType {
    Text,
    Binary,
    Locator,
    Reserved
  }

  private static final ItemType[] ITEM_TYPES = ItemType.values();

  private ItemType type = ItemType.Text;
  private String key = "";
  private ByteVector value = new ByteVector();
  private List<String> text = new ArrayList<>();
  private boolean readOnly;

  /**
   * Creates an empty text item.
   */
  public Item() {
  }

  /**
   * Creates an independent APE item from the supplied values.
   *
   * @param key    the item key
   * @param values text values to copy
   */
  public Item(String key, List<String> values) {
    this.key = Objects.requireNonNull(key);
    text.addAll(List.copyOf(values));
  }

  /**
   * Creates an independent APE item from the supplied values.
   *
   * @param key    the item key
   * @param value  bytes to copy or decode
   * @param binary whether the bytes form a binary payload; otherwise decode as ISO-8859-1 text
   */
  public Item(String key, ByteVector value, boolean binary) {
    this.key = Objects.requireNonNull(key);
    if (binary) {
      type = ItemType.Binary;
      this.value = new ByteVector(value);
    } else {
      text.add(value.decodeTerminatedText(ISO_8859_1));
    }
  }

  /**
   * Creates an independent APE item from the supplied values.
   *
   * @param other the item to copy
   */
  public Item(Item other) {
    key = other.key;
    type = other.type;
    readOnly = other.readOnly;
    value = new ByteVector(other.value);
    text = new ArrayList<>(other.text);
  }

  public String key() {
    return key;
  }

  public void setKey(String key) {
    this.key = Objects.requireNonNull(key);
  }

  public ItemType type() {
    return type;
  }

  public void setType(ItemType type) {
    this.type = Objects.requireNonNull(type);
  }

  public boolean isReadOnly() {
    return readOnly;
  }

  public void setReadOnly(boolean readOnly) {
    this.readOnly = readOnly;
  }

  /**
   * Returns an independent copy of the stored binary field, regardless of the active item type.
   *
   * @return copied binary bytes
   */
  public ByteVector binaryData() {
    return new ByteVector(value);
  }

  /**
   * Returns an immutable snapshot of stored text values, regardless of the active item type.
   *
   * @return text values
   */
  public List<String> values() {
    return List.copyOf(text);
  }

  /**
   * Switches the item to binary, copies the bytes, and clears its text values.
   */
  public void setBinaryData(ByteVector value) {
    type = ItemType.Binary;
    this.value = new ByteVector(value);
    text.clear();
  }

  public void setValue(String value) {
    setValues(List.of(value));
  }

  /**
   * Switches the item to text, replaces its text values, and clears the binary payload.
   */
  public void setValues(List<String> values) {
    type = ItemType.Text;
    text = new ArrayList<>(List.copyOf(values));
    value.clear();
  }

  /**
   * Appends a text value, selects the text type and clears the binary payload.
   *
   * @param value the text to append
   */
  public void appendValue(String value) {
    appendValues(List.of(value));
  }

  /**
   * Switches the item to text, appends the supplied values, and clears the binary payload.
   */
  public void appendValues(List<String> values) {
    type = ItemType.Text;
    text.addAll(List.copyOf(values));
    value.clear();
  }

  /**
   * Tests the active payload; one empty text value counts as empty, while a reserved item never
   * does.
   */
  public boolean isEmpty() {
    return switch (type) {
      case Text -> text.isEmpty() || text.size() == 1 && text.getFirst().isEmpty();
      case Binary, Locator -> value.isEmpty();
      case Reserved -> false;
    };
  }

  @Override
  public String toString() {
    return type == ItemType.Text && !isEmpty() ? text.getFirst() : "";
  }

  /**
   * Calculates the serialized item size from its encoded key, value bytes, and fixed header.
   */
  public int size() {
    int result = 9 + key.length();
    switch (type) {
      case Text -> {
        for (String part : text) {
          result += 1 + TextBytes.encode(part, UTF_8).length;
        }
        if (!text.isEmpty()) {
          result--;
        }
      }
      case Binary, Locator -> result += value.size();
      case Reserved -> {
      }
    }
    return result;
  }

  /**
   * Parses an item without clearing inactive storage, matching reuse semantics.
   */
  public void parse(ByteVector data) {
    parse(data, 0);
  }

  void parse(ByteVector data, int start) {
    if (data.size() - start < 11) {
      LOG.log(DEBUG, "Not enough data for an APE item.");
      return;
    }
    long length = data.toUnsignedInt(start, false), flags = data.toUnsignedInt(start + 4, false);
    int zero = data.find((byte) 0, start + 8);
    int end = zero < 0 ? data.size() : zero;
    key = new String(data.data(), start + 8, end - start - 8, StandardCharsets.ISO_8859_1);
    var payload = data.mid(start + 9 + key.length(), (int) Math.min(length, Integer.MAX_VALUE));
    readOnly = (flags & 1) != 0;
    type = ITEM_TYPES[(int) (flags >>> 1 & 3)];
    if (type == ItemType.Text) {
      text = new ArrayList<>();
      for (var part : payload.split(new ByteVector((byte) 0))) {
        text.add(part.decodeTerminatedText(UTF_8));
      }
    } else {
      value = payload;
    }
  }

  /**
   * Rendering a text item also updates binaryData(), as in Item::render().
   */
  public ByteVector render() {
    if (isEmpty()) {
      return new ByteVector();
    }
    if (type == ItemType.Text) {
      value = new ByteVector();
      var output = new ByteVectorOutput();
      try {
        for (int i = 0; i < text.size(); i++) {
          if (i > 0) {
            output.append((byte) 0);
          }
          output.append(TextBytes.encode(text.get(i), UTF_8));
        }
      } catch (RuntimeException failure) {
        value = output.toByteVector();
        throw failure;
      }
      value = output.toByteVector();
    }
    return ByteVector.fromUnsignedInt(value.size(), false)
      .append(ByteVector.fromUnsignedInt((readOnly ? 1 : 0) | type.ordinal() << 1, false))
      .append(ByteVector.fromText(key, ISO_8859_1))
      .append(new ByteVector(new byte[]{0}))
      .append(value);
  }
}
