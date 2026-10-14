// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.mp4;

import cz.skyba.taglib.toolkit.ByteVector;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A typed MP4 metadata value with mutable atom data-type metadata.
 * Construction copies mutable lists and byte values; exports return independent mutable copies.
 * Scalar conversion requires the exact stored type, except that an empty string-list value
 * converts to a zero scalar. A void item is not valid.
 */
public final class Item {
  /**
   * The exact stored payload type of an MP4 item.
   */
  public enum Type {VOID, BOOL, INT, INT_PAIR, BYTE, UNSIGNED_INT, LONG, STRING_LIST, BYTE_VECTOR_LIST, COVER_ART_LIST, STEM}

  /**
   * Two integer fields stored in a paired MP4 metadata value.
   */
  public record IntPair(int first, int second) {
  }

  private sealed interface Payload {
  }

  private record VoidPayload() implements Payload {
  }

  private record BoolPayload(boolean value) implements Payload {
  }

  private record IntPayload(int value) implements Payload {
  }

  private record BytePayload(int value) implements Payload {
  }

  private record UIntPayload(long value) implements Payload {
  }

  private record LongPayload(long value) implements Payload {
  }

  private record PairPayload(IntPair value) implements Payload {
  }

  private record TextsPayload(List<String> value) implements Payload {
  }

  private record BytesPayload(List<ByteVector> value) implements Payload {
  }

  private record CoversPayload(List<CoverArt> value) implements Payload {
  }

  private record StemPayload(Stem value) implements Payload {
  }

  private final Payload payload;
  private AtomDataType atomType = AtomDataType.TypeUndefined;

  private Item(Payload payload) {
    this.payload = payload;
  }

  /**
   * Creates a void item.
   */
  public Item() {
    this(new VoidPayload());
  }

  /**
   * Copies atom data-type metadata independently; immutable stored payload state can be shared.
   *
   * @param other the item to copy
   */
  public Item(Item other) {
    Objects.requireNonNull(other);
    payload = other.payload;
    atomType = other.atomType;
  }

  /**
   * Creates an item storing the supplied value, copying mutable input data.
   *
   * @param value the payload to store
   */
  public Item(boolean value) {
    this(new BoolPayload(value));
  }

  /**
   * Creates an item storing the supplied value, copying mutable input data.
   *
   * @param value the payload to store
   */
  public Item(int value) {
    this(new IntPayload(value));
  }

  /**
   * Creates an item storing the supplied value, copying mutable input data.
   *
   * @param value the payload to store
   */
  public Item(byte value) {
    this(new BytePayload(value & 255));
  }

  /**
   * Creates an item storing the supplied value, copying mutable input data.
   *
   * @param value the payload to store
   */
  public Item(long value) {
    this(new LongPayload(value));
  }

  /**
   * Creates an item storing the supplied value, copying mutable input data.
   *
   * @param first  the first integer
   * @param second the second integer
   */
  public Item(int first, int second) {
    this(new PairPayload(new IntPair(first, second)));
  }

  /**
   * Creates a text-list item, preserving value order and copying the list.
   *
   * @param values the text values; neither the list nor its elements may be null
   * @return the text-list item
   */
  public static Item textValues(List<String> values) {
    return new Item(new TextsPayload(List.copyOf(values)));
  }

  /**
   * Creates a binary-list item, copying the list and every byte array.
   *
   * @param values the binary values; neither the list nor its elements may be null
   * @return the binary-list item
   */
  public static Item binaryValues(List<byte[]> values) {
    return new Item(new BytesPayload(values.stream().map(ByteVector::new).toList()));
  }

  /**
   * Creates an artwork-list item, copying the list of immutable cover-art values.
   *
   * @param values the artwork; neither the list nor its elements may be null
   * @return the artwork-list item
   */
  public static Item coverArt(List<CoverArt> values) {
    return new Item(new CoversPayload(List.copyOf(values)));
  }

  /**
   * Creates a binary-list item, copying the list and every byte vector.
   *
   * @param values the binary values; neither the list nor its elements may be null
   */
  public Item(List<ByteVector> values) {
    this(new BytesPayload(values.stream().map(ByteVector::new).toList()));
  }

  /**
   * Creates an item storing the supplied value, copying mutable input data.
   *
   * @param value the payload to store
   */
  public Item(Stem value) {
    this(new StemPayload(Objects.requireNonNull(value)));
  }

  /**
   * Creates an unsigned 32-bit item from the low 32 bits of a Java long.
   *
   * @param value the bit pattern
   * @return the unsigned integer item
   */
  public static Item fromUnsignedInt(long value) {
    return new Item(new UIntPayload(value & 0xffff_ffffL));
  }

  /**
   * Creates an unsigned byte item from the low eight bits of an integer.
   *
   * @param value the bit pattern
   * @return the byte item
   */
  public static Item fromByte(int value) {
    return new Item((byte) value);
  }

  public void setAtomDataType(AtomDataType value) {
    atomType = Objects.requireNonNull(value);
  }

  public AtomDataType atomDataType() {
    return atomType;
  }

  public Type type() {
    return switch (payload) {
      case VoidPayload _ -> Type.VOID;
      case BoolPayload _ -> Type.BOOL;
      case IntPayload _ -> Type.INT;
      case BytePayload _ -> Type.BYTE;
      case UIntPayload _ -> Type.UNSIGNED_INT;
      case LongPayload _ -> Type.LONG;
      case PairPayload _ -> Type.INT_PAIR;
      case TextsPayload _ -> Type.STRING_LIST;
      case BytesPayload _ -> Type.BYTE_VECTOR_LIST;
      case CoversPayload _ -> Type.COVER_ART_LIST;
      case StemPayload _ -> Type.STEM;
    };
  }

  public boolean isValid() {
    return type() != Type.VOID;
  }

  private Object scalar(Type required) {
    // Empty string-list items imported from properties represent zero numeric values.
    if (payload instanceof TextsPayload(var value) && value.isEmpty()) {
      return switch (required) {
        case BOOL -> false;
        case INT, BYTE -> 0;
        case UNSIGNED_INT, LONG -> 0L;
        case INT_PAIR -> new IntPair(0, 0);
        default -> throw new IllegalArgumentException("Not a scalar item type: " + required);
      };
    }
    if (type() != required) {
      throw new IllegalStateException("MP4 item has type " + type() + ", expected " + required);
    }
    return rawValue();
  }

  /**
   * Returns the exact stored scalar type. An empty string-list item yields a zero scalar.
   *
   * @return the stored scalar or zero value
   * @throws IllegalStateException if the stored type differs and is not an empty string list
   */
  public boolean toBoolean() {
    return (boolean) scalar(Type.BOOL);
  }

  /**
   * Returns the exact stored scalar type. An empty string-list item yields a zero scalar.
   *
   * @return the stored scalar or zero value
   * @throws IllegalStateException if the stored type differs and is not an empty string list
   */
  public int toInt() {
    return (int) scalar(Type.INT);
  }

  /**
   * Returns the exact stored scalar type. An empty string-list item yields a zero scalar.
   *
   * @return the stored scalar or zero value
   * @throws IllegalStateException if the stored type differs and is not an empty string list
   */
  public int toByte() {
    return (int) scalar(Type.BYTE);
  }

  /**
   * Returns the exact stored scalar type. An empty string-list item yields a zero scalar.
   *
   * @return the stored scalar or zero value
   * @throws IllegalStateException if the stored type differs and is not an empty string list
   */
  public long toUnsignedInt() {
    return (long) scalar(Type.UNSIGNED_INT);
  }

  /**
   * Returns the exact stored scalar type. An empty string-list item yields a zero scalar.
   *
   * @return the stored scalar or zero value
   * @throws IllegalStateException if the stored type differs and is not an empty string list
   */
  public long toLong() {
    return (long) scalar(Type.LONG);
  }

  /**
   * Returns the exact stored scalar type. An empty string-list item yields a zero scalar.
   *
   * @return the stored scalar or zero value
   * @throws IllegalStateException if the stored type differs and is not an empty string list
   */
  public IntPair toIntPair() {
    return (IntPair) scalar(Type.INT_PAIR);
  }

  /**
   * Returns an independent mutable list of the stored text values.
   * A type mismatch returns an empty list.
   *
   * @return the stored payload or an empty value
   */
  public List<String> toStringList() {
    return new ArrayList<>(textValues());
  }

  /**
   * Returns a mutable list containing independent copies of the stored byte arrays.
   * A type mismatch returns an empty list.
   *
   * @return copied binary values, or an empty list
   */
  public List<byte[]> toByteArrays() {
    var result = new ArrayList<byte[]>();
    for (var value : byteValues()) {
      result.add(value.toByteArray());
    }
    return result;
  }

  /**
   * Returns an independent mutable list of copied byte vectors.
   * A type mismatch returns an empty list.
   *
   * @return the stored payload or an empty value
   */
  public List<ByteVector> toByteVectorList() {
    return byteValues().stream().map(ByteVector::new).collect(Collectors.toCollection(ArrayList::new));
  }

  /**
   * Returns an independent mutable list of the immutable cover-art values.
   * A type mismatch returns an empty list.
   *
   * @return the stored payload or an empty value
   */
  public List<CoverArt> toCoverArtList() {
    return new ArrayList<>(coverValues());
  }

  /**
   * Returns the immutable Stem payload.
   * A type mismatch returns an empty value.
   *
   * @return the stored payload or an empty value
   */
  public Stem toStem() {
    return payload instanceof StemPayload(var value) ? value : new Stem();
  }

  List<String> textValues() {
    return payload instanceof TextsPayload(var value) ? value : List.of();
  }

  List<ByteVector> byteValues() {
    return payload instanceof BytesPayload(var value) ? value : List.of();
  }

  List<CoverArt> coverValues() {
    return payload instanceof CoversPayload(var value) ? value : List.of();
  }

  private Object rawValue() {
    return switch (payload) {
      case VoidPayload _ -> null;
      case BoolPayload p -> p.value();
      case IntPayload p -> p.value();
      case BytePayload p -> p.value();
      case UIntPayload p -> p.value();
      case LongPayload p -> p.value();
      case PairPayload p -> p.value();
      case TextsPayload p -> p.value();
      case BytesPayload p -> p.value();
      case CoversPayload p -> p.value();
      case StemPayload p -> p.value();
    };
  }

  /**
   * Compares valid items by type, atom type and payload. Invalid items are equal only to themselves.
   */
  @Override
  public boolean equals(Object other) {
    return this == other || other instanceof Item item && isValid() && item.isValid()
      && type() == item.type() && atomDataType().equals(item.atomDataType())
      && Objects.equals(rawValue(), item.rawValue());
  }

  @Override
  public int hashCode() {
    return Objects.hash(type(), atomDataType(), rawValue());
  }
}
