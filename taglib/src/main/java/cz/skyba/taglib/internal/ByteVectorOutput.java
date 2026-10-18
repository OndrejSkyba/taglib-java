// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal;

import cz.skyba.taglib.toolkit.ByteVector;

import java.io.ByteArrayOutputStream;
import java.util.Objects;

/**
 * Checked local serialization buffer; appends consume their input immediately.
 */
public final class ByteVectorOutput {
  private static final class Buffer extends ByteArrayOutputStream {
    ByteVector vector(int padding) {
      var result = new ByteVector(Math.addExact(count, padding));
      result.set(0, buf, 0, count);
      return result;
    }
  }

  private final Buffer output = new Buffer();

  public int size() {
    return output.size();
  }

  public ByteVectorOutput append(ByteVector value) {
    return append(value.data());
  }

  public ByteVectorOutput append(byte[] value) {
    OverflowChecks.checkAddition(output.size(), value.length);
    output.writeBytes(value);
    return this;
  }

  public ByteVectorOutput append(byte[] value, int offset, int length) {
    Objects.checkFromIndexSize(offset, length, value.length);
    OverflowChecks.checkAddition(output.size(), length);
    output.write(value, offset, length);
    return this;
  }

  public ByteVectorOutput append(byte value) {
    OverflowChecks.checkAddition(output.size(), 1);
    output.write(value);
    return this;
  }

  public ByteVectorOutput number(long value, int width, boolean bigEndian) {
    Objects.checkFromIndexSize(0, width, Long.BYTES);
    OverflowChecks.checkAddition(output.size(), width);
    for (int i = 0; i < width; i++) output.write((int) (value >>> ((bigEndian ? width - 1 - i : i) * 8)));
    return this;
  }

  public ByteVectorOutput zeros(int count) {
    if (count < 0) throw new IllegalArgumentException("Negative padding");
    OverflowChecks.checkAddition(output.size(), count);
    byte[] zeros = new byte[Math.min(count, 1024)];
    while (count > 0) {
      int n = Math.min(count, zeros.length);
      output.write(zeros, 0, n);
      count -= n;
    }
    return this;
  }

  public byte[] toByteArray() {
    return output.toByteArray();
  }

  public ByteVector toByteVector() {
    return output.vector(0);
  }

  public ByteVector toByteVector(int padding) {
    return output.vector(padding);
  }
}
