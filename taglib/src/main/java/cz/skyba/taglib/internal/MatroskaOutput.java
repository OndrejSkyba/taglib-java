// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * Local EBML render plan; payload arrays are borrowed until the final output is copied.
 */
public final class MatroskaOutput {
  private sealed interface Part {
    int write(byte[] destination, int offset);
  }

  private record Leaf(byte[] header, byte[] payload) implements Part {
    public int write(byte[] destination, int offset) {
      System.arraycopy(header, 0, destination, offset, header.length);
      System.arraycopy(payload, 0, destination, offset + header.length, payload.length);
      return offset + header.length + payload.length;
    }
  }

  private record Container(byte[] header, List<Part> children) implements Part {
    public int write(byte[] destination, int offset) {
      System.arraycopy(header, 0, destination, offset, header.length);
      offset += header.length;
      for (Part child : children) offset = child.write(destination, offset);
      return offset;
    }
  }

  private final List<Part> parts = new ArrayList<>();
  private int size;

  public int size() {
    return size;
  }

  public MatroskaOutput element(long id, byte[] payload) {
    byte[] header = MatroskaWire.join(MatroskaWire.id(id), MatroskaWire.size(payload.length, 0));
    size = Math.addExact(size, Math.addExact(header.length, payload.length));
    parts.add(new Leaf(header, payload));
    return this;
  }

  public MatroskaOutput element(long id, MatroskaOutput body) {
    byte[] header = MatroskaWire.join(MatroskaWire.id(id), MatroskaWire.size(body.size, 0));
    size = Math.addExact(size, Math.addExact(header.length, body.size));
    parts.add(new Container(header, List.copyOf(body.parts)));
    return this;
  }

  public byte[] toByteArray() {
    byte[] result = new byte[size];
    int offset = 0;
    for (Part part : parts) offset = part.write(result, offset);
    return result;
  }
}
