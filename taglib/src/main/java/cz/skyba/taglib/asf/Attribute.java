// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.asf;

import cz.skyba.taglib.internal.TextBytes;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.Debug;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.UTF_16LE;

/**
 * A mutable typed ASF attribute with stream and language scope.
 * Explicit copies have independent scope and picture state. Binary storage is copied on input
 * and exported as detached bytes. Named views can share the stored value state.
 */
public final class Attribute {
  public static final int UnicodeType = 0, BytesType = 1, BoolType = 2, DWordType = 3, QWordType = 4, WordType = 5, GuidType = 6;

  private static final class State {
    int type, stream, language;
    String text = "";
    long number;
    // Internal payloads are replaced, never mutated.
    ByteVector bytes;
    Picture picture;

    State() {
      bytes = new ByteVector();
      picture = Picture.fromInvalid();
    }

    State(State other) {
      type = other.type;
      stream = other.stream;
      language = other.language;
      text = other.text;
      number = other.number;
      bytes = other.bytes;
      picture = new Picture(other.picture);
    }
  }

  private final State state;
  private String name = "";

  /**
   * Creates an empty Unicode-text attribute.
   */
  public Attribute() {
    state = new State();
  }

  /**
   * Creates an ASF attribute from the supplied value or encoded descriptor fields.
   *
   * @param other the attribute to copy
   */
  public Attribute(Attribute other) {
    state = new State(Objects.requireNonNull(other).state);
    name = other.name;
  }

  private Attribute(State state, String name) {
    this.state = state;
    this.name = Objects.requireNonNull(name);
  }

  // A named live view is needed when the same value appears under different tag keys.
  Attribute namedView(String name) {
    return new Attribute(state, name);
  }

  /**
   * Creates an ASF attribute from the supplied value or encoded descriptor fields.
   *
   * @param value the value to copy or store
   */
  public Attribute(String value) {
    this();
    state.text = Objects.requireNonNull(value);
  }

  /**
   * Creates an ASF attribute from the supplied value or encoded descriptor fields.
   *
   * @param value the value to copy or store
   */
  public Attribute(ByteVector value) {
    this();
    state.type = BytesType;
    state.bytes = new ByteVector(value);
  }

  /**
   * Creates an ASF attribute from the supplied value or encoded descriptor fields.
   *
   * @param value the value to copy or store
   */
  public Attribute(Picture value) {
    this();
    state.type = BytesType;
    state.picture = new Picture(value);
  }

  /**
   * Creates an ASF attribute from the supplied value or encoded descriptor fields.
   *
   * @param value the value to copy or store
   */
  public Attribute(boolean value) {
    this();
    state.type = BoolType;
    state.number = value ? 1 : 0;
  }

  /**
   * Creates an ASF attribute from the supplied value or encoded descriptor fields.
   *
   * @param value the value to copy or store
   */
  public Attribute(int value) {
    this();
    state.type = DWordType;
    state.number = Integer.toUnsignedLong(value);
  }

  /**
   * Creates an unsigned 64-bit numeric attribute using all bits of a Java long.
   *
   * @param value the value to copy or store
   */
  public Attribute(long value) {
    this();
    state.type = QWordType;
    state.number = value;
  }

  /**
   * Creates an ASF attribute from the supplied value or encoded descriptor fields.
   *
   * @param value the value to copy or store
   */
  public Attribute(short value) {
    this();
    state.type = WordType;
    state.number = Short.toUnsignedLong(value);
  }

  /**
   * Creates an ASF attribute from the supplied value or encoded descriptor fields. Encoded fields use ASF byte order.
   *
   * @param name     the attribute name without NUL
   * @param type     the descriptor data-type code from 0 to 65535
   * @param stream   the stream index from 0 to 65535
   * @param language the language index from 0 to 65535
   * @param data     the encoded descriptor value bytes
   * @throws IllegalArgumentException if descriptor identity or scope is outside its range
   */
  public Attribute(String name, int type, int stream, int language, byte[] data) {
    this();
    this.name = Objects.requireNonNull(name);
    if (name.indexOf('\0') >= 0 || type < 0 || type > 65535 || stream < 0 || stream > 65535 || language < 0 || language > 65535) {
      throw new IllegalArgumentException("Invalid named ASF attribute identity");
    }
    state.type = type;
    state.stream = stream;
    state.language = language;
    var bytes = new ByteVector(data);
    if (type == UnicodeType) {
      int size = data.length / 2;
      if (size > 0 && data[2 * size - 2] == 0 && data[2 * size - 1] == 0) {
        size--;
      }
      char[] chars = new char[size];
      for (int i = 0; i < size; i++) {
        chars[i] = (char) ((data[2 * i] & 255) | ((data[2 * i + 1] & 255) << 8));
      }
      state.text = new String(chars);
    } else if (type == BoolType || type == DWordType || type == QWordType || type == WordType) {
      int width = numericWidth(type, 1);
      state.number = bytes.size() >= width ? bytes.mid(0, width).toLong(false) : 0;
      if (type == BoolType) {
        state.number = state.number == 0 ? 0 : 1;
      }
    } else {
      state.bytes = bytes;
    }
  }

  /**
   * Decodes a named descriptor, interpreting WM/Picture binary content as picture data.
   *
   * @param name     the attribute name
   * @param type     the descriptor data-type code
   * @param stream   the stream index
   * @param language the language index
   * @param data     the encoded value bytes
   * @return the new attribute
   */
  public static Attribute parsed(String name, int type, int stream, int language, byte[] data) {
    var value = new Attribute(name, type, stream, language, data);
    if (type == BytesType && name.equals("WM/Picture")) {
      value.state.picture = new Picture(new ByteVector(data));
      value.clearPictureBytes();
    }
    return value;
  }

  public String name() {
    return name;
  }

  /**
   * Returns an independent attribute copy with the supplied name.
   */
  public Attribute named(String value) {
    var copy = new Attribute(this);
    copy.name = Objects.requireNonNull(value);
    return copy;
  }

  public int type() {
    return state.type;
  }

  public int stream() {
    return state.stream;
  }

  public void setStream(int value) {
    state.stream = value;
  }

  public int language() {
    return state.language;
  }

  public void setLanguage(int value) {
    state.language = value;
  }

  /**
   * Reads one ASF attribute descriptor at the file's current cursor into this value.
   * The borrowed file remains open and its cursor advances.
   *
   * @param file the ASF source
   * @return the decoded attribute name
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public String parse(AsfFile file) {
    return parse(file, 0);
  }

  /**
   * Reads one ASF attribute descriptor at the file's current cursor into this value.
   * The borrowed file remains open and its cursor advances.
   *
   * @param file the ASF source
   * @param kind zero for Extended Content Description, one for Metadata, or two for Metadata Library
   * @return the decoded attribute name
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public String parse(AsfFile file, int kind) {
    Objects.requireNonNull(file);
    state.picture = Picture.fromInvalid();
    int nameLength, dataType;
    long size;
    String parsedName;
    if (kind == 0) {
      nameLength = (int) readNumber(file, 2);
      parsedName = readText(file, nameLength);
      dataType = (int) readNumber(file, 2);
      size = readNumber(file, 2);
    } else {
      int scope = (int) readNumber(file, 2);
      if (kind == 2) {
        state.language = scope;
      }
      state.stream = (int) readNumber(file, 2);
      nameLength = (int) readNumber(file, 2);
      dataType = (int) readNumber(file, 2);
      size = readNumber(file, 4);
      parsedName = readText(file, nameLength);
    }
    if (kind != 2 && size > 65535) {
      Debug.debug("ASF::Attribute::parse() -- Value larger than 64kB");
    }
    switch (dataType) {
      case WordType, BoolType, DWordType, QWordType -> state.number = readNumeric(file, kind, dataType);
      case UnicodeType -> state.text = readText(file, size);
      case BytesType, GuidType -> state.bytes = new ByteVector(file.readBlock(size));
      default -> {
        Debug.debug(String.format(Locale.ROOT, "ASF::Attribute::parse() -- Unknown data type 0x%x", dataType));
        return "";
      }
    }
    state.type = dataType;
    if (dataType == BytesType && parsedName.equals("WM/Picture")) {
      state.picture.parse(state.bytes);
      clearPictureBytes();
    }
    return parsedName;
  }

  private void clearPictureBytes() {
    if (state.picture.isValid()) {
      state.bytes = new ByteVector();
    }
  }

  private static int numericWidth(int type, int kind) {
    return switch (type) {
      case WordType -> 2;
      case BoolType -> kind == 0 ? 4 : 2;
      case DWordType -> 4;
      case QWordType -> 8;
      default -> throw new IllegalArgumentException("Not a numeric ASF type: " + type);
    };
  }

  private static long readNumeric(AsfFile file, int kind, int type) {
    long value = readNumber(file, numericWidth(type, kind));
    return type == BoolType ? value == 0 ? 0 : 1 : value;
  }

  private static long readNumber(AsfFile file, int width) {
    var data = file.readBlock(width);
    return data.size() == width ? data.toLong(0, false) : 0;
  }

  private static String readText(AsfFile file, long length) {
    var data = file.readBlock(length);
    int size = data.size();
    while (size >= 2 && data.at(size - 1) == 0 && data.at(size - 2) == 0) {
      size -= 2;
    }
    return TextBytes.decodeTerminated(data.data(), 0, size, UTF_16LE);
  }

  /**
   * Returns whether the stored numeric value is nonzero, without checking the active attribute type.
   */
  public boolean toBoolean() {
    return state.number != 0;
  }

  /**
   * Returns the low 16 bits of the stored numeric value as an unsigned Java value.
   *
   * @return the numeric bits
   */
  public int toUnsignedShort() {
    return (int) (state.number & 65535);
  }

  /**
   * Returns the low 32 bits of the stored numeric value as an unsigned Java value.
   *
   * @return the numeric bits
   */
  public long toUnsignedInt() {
    return state.number & 0xffff_ffffL;
  }

  /**
   * Returns the numeric value as an unsigned 64-bit bit pattern.
   * Use {@link Long#toUnsignedString(long)} to display it.
   */
  public long toUnsignedLong() {
    return state.number;
  }

  @Override
  public String toString() {
    return state.text;
  }

  /**
   * Returns independent bytes for the active binary or picture payload.
   *
   * @return copied or serialized value bytes
   */
  public ByteVector toByteVector() {
    return state.picture.isValid() ? state.picture.render() : new ByteVector(state.bytes);
  }

  /**
   * Returns the live picture object stored in this attribute.
   *
   * @return the picture value, which can be invalid for a non-picture attribute
   */
  public Picture toPicture() {
    return state.picture;
  }

  /**
   * Creates a GUID-typed attribute with copied identifier bytes.
   *
   * @param value the value to store
   * @return the new attribute
   */
  public static Attribute fromGuid(ByteVector value) {
    var a = new Attribute(value);
    a.state.type = GuidType;
    return a;
  }

  /**
   * Returns the descriptor payload byte size. The size reported for a QWORD value is five
   * bytes even though its serialized numeric payload is eight bytes.
   *
   * @return the reported descriptor size
   */
  public int dataSize() {
    return switch (type()) {
      case WordType -> 2;
      case BoolType, DWordType -> 4;
      case QWordType -> 5;
      case UnicodeType -> Math.addExact(Math.multiplyExact(state.text.length(), 2), 2);
      case BytesType -> state.picture.isValid() ? state.picture.dataSize() : state.bytes.size();
      case GuidType -> state.bytes.size();
      default -> 0;
    };
  }

  /**
   * Serializes the active value using its ASF type and width. Pictures use the WM/Picture encoding.
   *
   * @return serialized value bytes
   */
  public byte[] data() {
    return switch (type()) {
      case UnicodeType -> Picture.stringBytes(state.text).data();
      case WordType -> ByteVector.fromShort((short) toUnsignedShort(), false).data();
      case BoolType -> ByteVector.fromShort((short) (toBoolean() ? 1 : 0), false).data();
      case DWordType -> ByteVector.fromUnsignedInt(toUnsignedInt(), false).data();
      case QWordType -> ByteVector.fromLong(state.number, false).data();
      default -> toByteVector().data();
    };
  }

  /**
   * Returns Unicode text, rejecting attributes whose active type is not Unicode.
   */
  public static Attribute text(String name, String value) {
    return new Attribute(value).named(name);
  }

  /**
   * Creates a named binary attribute with copied bytes.
   *
   * @param name  the attribute name
   * @param value the value to store
   * @return the new attribute
   */
  public static Attribute binary(String name, byte[] value) {
    var attribute = new Attribute();
    attribute.state.type = BytesType;
    attribute.state.bytes = new ByteVector(value);
    return attribute.named(name);
  }

  /**
   * Creates a named boolean attribute.
   *
   * @param name  the attribute name
   * @param value the value to store
   * @return the new attribute
   */
  public static Attribute bool(String name, boolean value) {
    return new Attribute(value).named(name);
  }

  /**
   * Creates a named unsigned numeric attribute of the selected width, rejecting values outside that
   * width.
   */
  public static Attribute unsigned(String name, int type, BigInteger value) {
    int width = switch (type) {
      case WordType -> 2;
      case DWordType -> 4;
      case QWordType -> 8;
      default -> throw new IllegalArgumentException("Not an ASF unsigned integer type");
    };
    if (value.signum() < 0 || value.bitLength() > width * 8) {
      throw new IllegalArgumentException("ASF integer is out of range");
    }
    return (width == 2
      ? new Attribute(value.shortValue())
      : width == 4 ? new Attribute(value.intValue()) : new Attribute(value.longValue())).named(name);
  }

  /**
   * Creates a named GUID attribute requiring exactly 16 identifier bytes.
   *
   * @param name  the attribute name
   * @param value the GUID bytes
   * @return the GUID attribute
   * @throws IllegalArgumentException if the byte count is not 16
   */
  public static Attribute guid(String name, byte[] value) {
    if (value.length != 16) {
      throw new IllegalArgumentException("ASF GUID requires 16 bytes");
    }
    return fromGuid(new ByteVector(value)).named(name);
  }

  /**
   * Creates an independent attribute copy with replacement stream and language scope.
   *
   * @param stream   the stream index
   * @param language the language index
   * @return the new attribute
   */
  public Attribute withScope(int stream, int language) {
    return new Attribute(name, type(), stream, language, data());
  }

  /**
   * Returns Unicode text, rejecting attributes whose active type is not Unicode.
   */
  public String text() {
    if (type() != UnicodeType) {
      throw new IllegalStateException("Not a Unicode ASF attribute");
    }
    return state.text;
  }

  private static boolean isNumeric(int type) {
    return switch (type) {
      case BoolType, DWordType, QWordType, WordType -> true;
      default -> false;
    };
  }

  /**
   * Returns the numeric bit pattern as a nonnegative integer, rejecting nonnumeric attribute types.
   */
  public BigInteger unsigned() {
    if (!isNumeric(type())) {
      throw new IllegalStateException("Not a numeric ASF attribute");
    }
    return new BigInteger(Long.toUnsignedString(state.number));
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Attribute a && name.equals(a.name) && type() == a.type() && stream() == a.stream()
      && language() == a.language() && Arrays.equals(data(), a.data());
  }

  @Override
  public int hashCode() {
    return 31 * Objects.hash(name, type(), stream(), language()) + Arrays.hashCode(data());
  }
}
