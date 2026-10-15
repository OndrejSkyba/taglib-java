// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.mod;

import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.IOStream;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

/**
 * Base for tracker format implementations with fixed-width binary stream helpers.
 * Reads advance the shared cursor and invoke output callbacks only after a complete field
 * has been read. Writes change the underlying storage immediately.
 */
public abstract class FileBase extends cz.skyba.taglib.FileBase {
  /**
   * Initializes a tracker format over owned path storage.
   *
   * @param path the input path
   * @throws java.io.UncheckedIOException if storage access fails
   */
  protected FileBase(Path path) {
    super(path);
  }

  /**
   * Initializes a tracker format over a borrowed stream.
   *
   * @param stream the borrowed stream
   * @throws java.io.UncheckedIOException if storage access fails
   */
  protected FileBase(IOStream stream) {
    super(stream);
  }

  /**
   * Writes a fixed-width ISO-8859-1 field, truncating or padding encoded bytes.
   *
   * @param size  the field byte count
   * @param value the value to encode
   * @throws java.io.UncheckedIOException if storage access fails
   */
  protected void writeString(String value, long size) {
    writeString(value, size, (byte) 0);
  }

  /**
   * Writes a fixed-width ISO-8859-1 field, truncating or padding encoded bytes.
   *
   * @param size    the field byte count
   * @param value   the value to encode
   * @param padding the extension fill byte
   * @throws java.io.UncheckedIOException if storage access fails
   */
  protected void writeString(String value, long size, byte padding) {
    long width = size & 0xffff_ffffL; // Native resize narrows unsigned long to unsigned int.
    if (width > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("String width exceeds Java array range");
    }
    writeBlock(ByteVector.fromText(value, ISO_8859_1).resize((int) width, padding));
  }

  /**
   * Writes the low 8 bits of an integer in little-endian order.
   *
   * @param value the value to encode
   * @throws java.io.UncheckedIOException if storage access fails
   */
  protected void writeByte(int value) {
    writeBlock(new ByteVector((byte) value));
  }

  /**
   * Writes the low 16 bits of an integer in little-endian order.
   *
   * @param value the value to encode
   * @throws java.io.UncheckedIOException if storage access fails
   */
  protected void writeU16L(int value) {
    writeBlock(ByteVector.fromShort((short) value, false));
  }

  /**
   * Writes the low 16 bits of an integer in big-endian order.
   *
   * @param value the value to encode
   * @throws java.io.UncheckedIOException if storage access fails
   */
  protected void writeU16B(int value) {
    writeBlock(ByteVector.fromShort((short) value, true));
  }

  /**
   * Writes the low 32 bits of an integer in little-endian order.
   *
   * @param value the value to encode
   * @throws java.io.UncheckedIOException if storage access fails
   */
  protected void writeU32L(long value) {
    writeBlock(ByteVector.fromUnsignedInt(value, false));
  }

  /**
   * Writes the low 32 bits of an integer in big-endian order.
   *
   * @param value the value to encode
   * @throws java.io.UncheckedIOException if storage access fails
   */
  protected void writeU32B(long value) {
    writeBlock(ByteVector.fromUnsignedInt(value, true));
  }

  /**
   * Reads a fixed-width ISO-8859-1 string, ending at NUL and replacing 0xff with space.
   * The callback runs only after a complete read; failure can still advance the cursor.
   *
   * @param output the callback for the decoded value
   * @param size   the field byte count
   * @return whether the complete field was read
   * @throws java.io.UncheckedIOException if storage access fails
   */
  protected boolean readString(Consumer<String> output, long size) {
    Objects.requireNonNull(output);
    if (size < 0) {
      throw new IllegalArgumentException("Negative string width");
    }
    var bytes = readBlock(size);
    if (bytes.size() < size) {
      return false;
    }
    int end = bytes.find((byte) 0);
    if (end >= 0) {
      bytes.resize(end);
    }
    for (int i = 0; i < bytes.size(); i++) {
      if (bytes.data()[i] == (byte) 0xff) {
        bytes.data()[i] = ' ';
      }
    }
    output.accept(bytes.decodeTerminatedText(ISO_8859_1));
    return true;
  }

  /**
   * Reads an unsigned 8-bit field in little-endian order.
   * The callback runs only after a complete read; failure can still advance the cursor.
   *
   * @param output the callback for the decoded value
   * @return whether the complete field was read
   * @throws java.io.UncheckedIOException if storage access fails
   */
  protected boolean readByte(IntConsumer output) {
    Objects.requireNonNull(output);
    var bytes = readBlock(1);
    if (bytes.size() != 1) {
      return false;
    }
    output.accept(bytes.data()[0] & 0xff);
    return true;
  }

  /**
   * Reads an unsigned 16-bit field in little-endian order.
   * The callback runs only after a complete read; failure can still advance the cursor.
   *
   * @param output the callback for the decoded value
   * @return whether the complete field was read
   * @throws java.io.UncheckedIOException if storage access fails
   */
  protected boolean readU16L(IntConsumer output) {
    return readU16(output, false);
  }

  /**
   * Reads an unsigned 16-bit field in big-endian order.
   * The callback runs only after a complete read; failure can still advance the cursor.
   *
   * @param output the callback for the decoded value
   * @return whether the complete field was read
   * @throws java.io.UncheckedIOException if storage access fails
   */
  protected boolean readU16B(IntConsumer output) {
    return readU16(output, true);
  }

  /**
   * Reads an unsigned 32-bit field in little-endian order.
   * The callback runs only after a complete read; failure can still advance the cursor.
   *
   * @param output the callback for the decoded value
   * @return whether the complete field was read
   * @throws java.io.UncheckedIOException if storage access fails
   */
  protected boolean readU32L(LongConsumer output) {
    return readU32(output, false);
  }

  /**
   * Reads an unsigned 32-bit field in big-endian order.
   * The callback runs only after a complete read; failure can still advance the cursor.
   *
   * @param output the callback for the decoded value
   * @return whether the complete field was read
   * @throws java.io.UncheckedIOException if storage access fails
   */
  protected boolean readU32B(LongConsumer output) {
    return readU32(output, true);
  }

  private boolean readU16(IntConsumer output, boolean big) {
    Objects.requireNonNull(output);
    var bytes = readBlock(2);
    if (bytes.size() != 2) {
      return false;
    }
    output.accept(bytes.toUnsignedShort(big));
    return true;
  }

  private boolean readU32(LongConsumer output, boolean big) {
    Objects.requireNonNull(output);
    var bytes = readBlock(4);
    if (bytes.size() != 4) {
      return false;
    }
    output.accept(bytes.toUnsignedInt(big));
    return true;
  }
}
