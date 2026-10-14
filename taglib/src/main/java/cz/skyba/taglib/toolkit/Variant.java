// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.toolkit;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * An immutable value with an explicit type and no numeric coercions.
 * Conversions return zero or an empty value if the stored type differs. Extraction methods
 * also report whether the type matched. Mutable byte inputs are copied, and returned byte
 * values are independent copies. Stored collection values are immutable snapshots.
 */
public final class Variant {
  private static final int STREAM_PRECISION = 6;
  private static final MathContext STREAM_CONTEXT =
    new MathContext(STREAM_PRECISION, RoundingMode.HALF_EVEN);

  /**
   * The exact stored type of a variant.
   */
  public enum Type {
    VOID,
    BOOL,
    INT,
    UNSIGNED_INT,
    LONG,
    UNSIGNED_LONG,
    DOUBLE,
    STRING,
    STRING_LIST,
    BYTE_VECTOR,
    BYTE_VECTOR_LIST,
    VARIANT_LIST,
    VARIANT_MAP
  }

  /**
   * A converted value and a success indicator.
   * A zero or empty value can be a successful result; inspect {@code ok} rather than the value.
   *
   * @param value the converted value or type-specific fallback
   * @param ok    whether extraction or parsing succeeded
   * @param <T>   the converted Java type
   */
  public record Extraction<T>(T value, boolean ok) {
  }

  private sealed interface Payload {
  }

  private record VoidPayload() implements Payload {
  }

  private record BoolPayload(boolean value) implements Payload {
  }

  private record IntPayload(int value) implements Payload {
  }

  private record UIntPayload(long value) implements Payload {
  }

  private record LongPayload(long value) implements Payload {
  }

  private record ULongPayload(long value) implements Payload {
  }

  private record DoublePayload(double value) implements Payload {
  }

  private record TextPayload(String value) implements Payload {
  }

  private record TextsPayload(List<String> value) implements Payload {
  }

  private record BytesPayload(ByteVector value) implements Payload {
  }

  private record ByteListsPayload(List<ByteVector> value) implements Payload {
  }

  private record VariantsPayload(List<Variant> value) implements Payload {
  }

  private record MappingPayload(Map<String, Variant> value) implements Payload {
  }

  private final Payload payload;

  /**
   * Creates an empty variant with type {@code VOID}.
   */
  public Variant() {
    this(new VoidPayload());
  }

  /**
   * Creates a variant storing the exact supplied value type.
   *
   * @param value the value to store
   */
  public Variant(boolean value) {
    this(new BoolPayload(value));
  }

  /**
   * Creates a variant storing the exact supplied value type.
   *
   * @param value the value to store
   */
  public Variant(int value) {
    this(new IntPayload(value));
  }

  /**
   * Creates a variant storing the exact supplied value type.
   *
   * @param value the value to store
   */
  public Variant(long value) {
    this(new LongPayload(value));
  }

  /**
   * Creates a variant storing the exact supplied value type.
   *
   * @param value the value to store
   */
  public Variant(double value) {
    this(new DoublePayload(value));
  }

  /**
   * Creates a variant storing the exact supplied value type.
   *
   * @param value the value to store
   */
  public Variant(String value) {
    this(new TextPayload(Objects.requireNonNull(value)));
  }

  /**
   * Creates a variant storing the exact supplied byte value, copying its bytes.
   *
   * @param value the value to store
   */
  public Variant(ByteVector value) {
    this(new BytesPayload(new ByteVector(value)));
  }

  /**
   * Creates a variant containing a copied byte value.
   *
   * @param value the values or bit pattern to store
   * @return the typed variant
   */
  public static Variant fromByteArray(byte[] value) {
    return new Variant(new BytesPayload(new ByteVector(value)));
  }

  /**
   * Creates a variant storing the exact supplied immutable variant value.
   *
   * @param value the value to store
   */
  public Variant(Variant value) {
    this(value.payload);
  }

  private Variant(Payload payload) {
    this.payload = payload;
  }

  /**
   * Creates a variant containing an unsigned 32-bit value using the input's low 32 bits.
   *
   * @param value the values or bit pattern to store
   * @return the typed variant
   */
  public static Variant fromUnsignedInt(long value) {
    return new Variant(new UIntPayload(value & 0xffffffffL));
  }

  /**
   * Creates a variant containing an unsigned 64-bit value retaining the complete bit pattern.
   *
   * @param value the values or bit pattern to store
   * @return the typed variant
   */
  public static Variant fromUnsignedLong(long value) {
    return new Variant(new ULongPayload(value));
  }

  /**
   * Creates a variant containing an immutable copy of the string list.
   *
   * @param value the values or bit pattern to store
   * @return the typed variant
   */
  public static Variant fromStringList(List<String> value) {
    return new Variant(new TextsPayload(List.copyOf(value)));
  }

  /**
   * Creates a variant containing an independent copy of every vector in the list.
   *
   * @param value the values or bit pattern to store
   * @return the typed variant
   */
  public static Variant fromByteVectorList(List<ByteVector> value) {
    return new Variant(new ByteListsPayload(value.stream().map(ByteVector::new).toList()));
  }

  /**
   * Creates a variant containing an immutable copy of the variant list.
   *
   * @param value the values or bit pattern to store
   * @return the typed variant
   */
  public static Variant fromList(List<Variant> value) {
    return new Variant(new VariantsPayload(List.copyOf(value)));
  }

  /**
   * Creates a variant containing an immutable map snapshot with sorted string keys.
   *
   * @param value the values or bit pattern to store
   * @return the typed variant
   */
  public static Variant fromMap(Map<String, Variant> value) {
    var copy = new TreeMap<String, Variant>();
    value.forEach((k, v) -> copy.put(Objects.requireNonNull(k), Objects.requireNonNull(v)));
    return new Variant(new MappingPayload(Collections.unmodifiableMap(copy)));
  }

  public Type type() {
    return switch (payload) {
      case VoidPayload _ -> Type.VOID;
      case BoolPayload _ -> Type.BOOL;
      case IntPayload _ -> Type.INT;
      case UIntPayload _ -> Type.UNSIGNED_INT;
      case LongPayload _ -> Type.LONG;
      case ULongPayload _ -> Type.UNSIGNED_LONG;
      case DoublePayload _ -> Type.DOUBLE;
      case TextPayload _ -> Type.STRING;
      case TextsPayload _ -> Type.STRING_LIST;
      case BytesPayload _ -> Type.BYTE_VECTOR;
      case ByteListsPayload _ -> Type.BYTE_VECTOR_LIST;
      case VariantsPayload _ -> Type.VARIANT_LIST;
      case MappingPayload _ -> Type.VARIANT_MAP;
    };
  }

  /**
   * Tests whether the stored type is {@code VOID}. Stored zero, false, empty text and empty
   * collections are values and are not considered empty variants.
   *
   * @return whether the variant has no stored value
   */
  public boolean isEmpty() {
    return payload instanceof VoidPayload;
  }

  /**
   * Returns the stored signed 32-bit integer, or zero if the type differs.
   *
   * @return the exact stored value or its type-specific fallback
   */
  public int toInt() {
    return payload instanceof IntPayload(var value) ? value : 0;
  }

  /**
   * Returns the stored unsigned 32-bit integer, or zero if the type differs.
   *
   * @return the exact stored value or its type-specific fallback
   */
  public long toUnsignedInt() {
    return payload instanceof UIntPayload(var value) ? value : 0L;
  }

  /**
   * Returns the stored signed 64-bit integer, or zero if the type differs.
   *
   * @return the exact stored value or its type-specific fallback
   */
  public long toLong() {
    return payload instanceof LongPayload(var value) ? value : 0L;
  }

  /**
   * Returns the stored unsigned 64-bit integer bit pattern, or zero if the type differs.
   *
   * @return the exact stored value or its type-specific fallback
   */
  public long toUnsignedLong() {
    return payload instanceof ULongPayload(var value) ? value : 0L;
  }

  /**
   * Returns the stored boolean, or false if the type differs.
   *
   * @return the exact stored value or its type-specific fallback
   */
  public boolean toBoolean() {
    return payload instanceof BoolPayload(var value) && value;
  }

  /**
   * Returns the stored double, or zero if the type differs.
   *
   * @return the exact stored value or its type-specific fallback
   */
  public double toDouble() {
    return payload instanceof DoublePayload(var value) ? value : 0.0;
  }

  /**
   * Returns the stored string, or an empty string if the type differs.
   *
   * @return the exact stored value or its type-specific fallback
   */
  public String toText() {
    return payload instanceof TextPayload(var value) ? value : "";
  }

  /**
   * Returns a diagnostic value representation; use {@link #toText()} for string extraction.
   */
  @Override
  public String toString() {
    return switch (payload) {
      case ULongPayload p -> Long.toUnsignedString(p.value());
      case BytesPayload p -> "0x" + HexFormat.of().formatHex(p.value().data());
      case ByteListsPayload p -> renderList(p.value(), value -> "0x" + HexFormat.of().formatHex(value.data()));
      default -> String.valueOf(rawValue());
    };
  }

  /**
   * Returns the stored string list, or an empty list if the type differs. The returned collection is immutable.
   *
   * @return the exact stored value or its type-specific fallback
   */
  public List<String> toStringList() {
    return payload instanceof TextsPayload(var value) ? value : List.of();
  }

  /**
   * Returns the stored byte vector, or an empty vector if the type differs. Byte storage is copied.
   *
   * @return the exact stored value or its type-specific fallback
   */
  public ByteVector toByteVector() {
    return payload instanceof BytesPayload(var value) ? new ByteVector(value) : new ByteVector();
  }

  /**
   * Returns an independent mutable list of copied byte vectors, or an empty list if the type differs.
   *
   * @return the exact stored value or its type-specific fallback
   */
  public List<ByteVector> toByteVectorList() {
    return payload instanceof ByteListsPayload(var value)
      ? value.stream().map(ByteVector::new).collect(Collectors.toCollection(ArrayList::new))
      : new ArrayList<>();
  }

  /**
   * Returns the stored variant list, or an empty list if the type differs. The returned collection is immutable.
   *
   * @return the exact stored value or its type-specific fallback
   */
  public List<Variant> toList() {
    return payload instanceof VariantsPayload(var value) ? value : List.of();
  }

  /**
   * Returns the stored variant map, or an empty map if the type differs. The returned collection is immutable.
   *
   * @return the exact stored value or its type-specific fallback
   */
  public Map<String, Variant> toMap() {
    return payload instanceof MappingPayload(var value) ? value : Map.of();
  }

  /**
   * Extracts the exact stored type and reports whether it matched.
   * The fallback is zero or empty when the type differs; a stored zero or empty value is
   * still a successful extraction.
   *
   * @return the converted value and type-match flag
   */
  public Extraction<Integer> extractInt() {
    return new Extraction<>(toInt(), type() == Type.INT);
  }

  /**
   * Extracts the exact stored type and reports whether it matched.
   * The fallback is zero or empty when the type differs; a stored zero or empty value is
   * still a successful extraction.
   *
   * @return the converted value and type-match flag
   */
  public Extraction<Long> extractUnsignedInt() {
    return new Extraction<>(toUnsignedInt(), type() == Type.UNSIGNED_INT);
  }

  /**
   * Extracts the exact stored type and reports whether it matched.
   * The fallback is zero or empty when the type differs; a stored zero or empty value is
   * still a successful extraction.
   *
   * @return the converted value and type-match flag
   */
  public Extraction<Long> extractLong() {
    return new Extraction<>(toLong(), type() == Type.LONG);
  }

  /**
   * Extracts the exact stored type and reports whether it matched.
   * The fallback is zero or empty when the type differs; a stored zero or empty value is
   * still a successful extraction.
   *
   * @return the converted value and type-match flag
   */
  public Extraction<Long> extractUnsignedLong() {
    return new Extraction<>(toUnsignedLong(), type() == Type.UNSIGNED_LONG);
  }

  /**
   * Extracts the exact stored type and reports whether it matched.
   * The fallback is zero or empty when the type differs; a stored zero or empty value is
   * still a successful extraction.
   *
   * @return the converted value and type-match flag
   */
  public Extraction<Boolean> extractBoolean() {
    return new Extraction<>(toBoolean(), type() == Type.BOOL);
  }

  /**
   * Extracts the exact stored type and reports whether it matched.
   * The fallback is zero or empty when the type differs; a stored zero or empty value is
   * still a successful extraction.
   *
   * @return the converted value and type-match flag
   */
  public Extraction<Double> extractDouble() {
    return new Extraction<>(toDouble(), type() == Type.DOUBLE);
  }

  /**
   * Extracts the exact stored type and reports whether it matched.
   * The fallback is zero or empty when the type differs; a stored zero or empty value is
   * still a successful extraction.
   *
   * @return the converted value and type-match flag
   */
  public Extraction<String> extractString() {
    return new Extraction<>(toText(), type() == Type.STRING);
  }

  /**
   * Extracts the exact stored type and reports whether it matched.
   * The fallback is zero or empty when the type differs; a stored zero or empty value is
   * still a successful extraction.
   *
   * @return the converted value and type-match flag
   */
  public Extraction<List<String>> extractStringList() {
    return new Extraction<>(toStringList(), type() == Type.STRING_LIST);
  }

  /**
   * Extracts the exact stored type and reports whether it matched.
   * The fallback is zero or empty when the type differs; a stored zero or empty value is
   * still a successful extraction.
   *
   * @return the converted value and type-match flag
   */
  public Extraction<ByteVector> extractByteVector() {
    return new Extraction<>(toByteVector(), type() == Type.BYTE_VECTOR);
  }

  /**
   * Extracts the exact stored type and reports whether it matched.
   * The fallback is zero or empty when the type differs; a stored zero or empty value is
   * still a successful extraction.
   *
   * @return the converted value and type-match flag
   */
  public Extraction<List<ByteVector>> extractByteVectorList() {
    return new Extraction<>(toByteVectorList(), type() == Type.BYTE_VECTOR_LIST);
  }

  /**
   * Extracts the exact stored type and reports whether it matched.
   * The fallback is zero or empty when the type differs; a stored zero or empty value is
   * still a successful extraction.
   *
   * @return the converted value and type-match flag
   */
  public Extraction<List<Variant>> extractList() {
    return new Extraction<>(toList(), type() == Type.VARIANT_LIST);
  }

  /**
   * Extracts the exact stored type and reports whether it matched.
   * The fallback is zero or empty when the type differs; a stored zero or empty value is
   * still a successful extraction.
   *
   * @return the converted value and type-match flag
   */
  public Extraction<Map<String, Variant>> extractMap() {
    return new Extraction<>(toMap(), type() == Type.VARIANT_MAP);
  }

  /**
   * Compares exact types and stored values using Java value equality. NaNs compare equal;
   * positive and negative floating-point zero are distinct, including in nested values.
   */
  @Override
  public boolean equals(Object other) {
    return other instanceof Variant v && payload.equals(v.payload);
  }

  /**
   * Hashes the exact type and stored value consistently with {@link #equals(Object)}.
   */
  @Override
  public int hashCode() {
    return 31 * type().hashCode() + payload.hashCode();
  }

  private Object rawValue() {
    return switch (payload) {
      case VoidPayload _ -> null;
      case BoolPayload p -> p.value();
      case IntPayload p -> p.value();
      case UIntPayload p -> p.value();
      case LongPayload p -> p.value();
      case ULongPayload p -> p.value();
      case DoublePayload p -> p.value();
      case TextPayload p -> p.value();
      case TextsPayload p -> p.value();
      case BytesPayload p -> p.value();
      case ByteListsPayload p -> p.value();
      case VariantsPayload p -> p.value();
      case MappingPayload p -> p.value();
    };
  }

  private static String quote(String value) {
    return '"' + value.replace("\"", "\\\"") + '"';
  }

  private static String bytes(ByteVector value) {
    var result = new StringBuilder("\"");
    for (byte b : value.data()) {
      if (b == 0) {
        break;
      }
      result
        .append("\\x")
        .append(Character.forDigit((b & 255) >>> 4, 16))
        .append(Character.forDigit(b & 15, 16));
    }
    return result.append('"').toString();
  }

  private static String decimal(double value) {
    if (Double.isNaN(value)) {
      return Double.doubleToRawLongBits(value) < 0 ? "-nan" : "nan";
    }
    if (Double.isInfinite(value)) {
      return value < 0 ? "-inf" : "inf";
    }
    boolean negative = Double.doubleToRawLongBits(value) < 0;
    if (value == 0.0) {
      return negative ? "-0" : "0";
    }
    BigDecimal rounded = new BigDecimal(Math.abs(value)).round(STREAM_CONTEXT);
    int exponent = rounded.precision() - rounded.scale() - 1;
    String prefix = negative ? "-" : "";
    if (exponent >= STREAM_PRECISION || exponent < -4) {
      String exponentDigits = Integer.toString(Math.abs(exponent));
      if (exponentDigits.length() < 2) {
        exponentDigits = "0" + exponentDigits;
      }
      return prefix + rounded.movePointLeft(exponent).stripTrailingZeros().toPlainString()
        + "e" + (exponent < 0 ? "-" : "+") + exponentDigits;
    }
    return prefix + rounded.stripTrailingZeros().toPlainString();
  }

  /**
   * Formats the value as structured text with brackets for collections and quoted strings.
   * Doubles use six significant digits. Binary values use hex escapes up to the first NUL.
   * String quotes are escaped, but control characters and backslashes are not JSON-escaped;
   * this output is not JSON.
   *
   * @return the structured representation
   */
  public String render() {
    return switch (payload) {
      case VoidPayload _ -> "null";
      case BoolPayload p -> Boolean.toString(p.value());
      case IntPayload p -> Integer.toString(p.value());
      case UIntPayload p -> Long.toString(p.value());
      case LongPayload p -> Long.toString(p.value());
      case ULongPayload p -> Long.toUnsignedString(p.value());
      case DoublePayload p -> decimal(p.value());
      case TextPayload p -> quote(p.value());
      case TextsPayload p -> renderList(p.value(), Variant::quote);
      case BytesPayload p -> bytes(p.value());
      case ByteListsPayload p -> renderList(p.value(), Variant::bytes);
      case VariantsPayload p -> renderList(p.value(), Variant::render);
      case MappingPayload p -> renderMap(p.value());
    };
  }

  private static <T> String renderList(
    List<T> values, Function<T, String> renderer
  ) {
    var result = new StringJoiner(", ", "[", "]");
    values.forEach(value -> result.add(renderer.apply(value)));
    return result.toString();
  }

  private static String renderMap(Map<String, Variant> values) {
    var result = new StringJoiner(", ", "{", "}");
    values.forEach((key, value) -> result.add(quote(key) + ": " + value.render()));
    return result.toString();
  }
}
