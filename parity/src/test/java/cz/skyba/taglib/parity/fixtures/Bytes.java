package cz.skyba.taglib.parity.fixtures;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;

/**
 * Independent binary fixture construction, without using the implementation under test.
 */
public final class Bytes {
  private Bytes() {
  }

  public static byte[] text(String value) {
    return value.getBytes(StandardCharsets.ISO_8859_1);
  }

  public static byte[] hex(String value) {
    return HexFormat.of().parseHex(value.replace(" ", ""));
  }

  public static byte[] join(byte[]... parts) {
    var result = new ByteArrayOutputStream();
    for (byte[] part : parts) result.writeBytes(part);
    return result.toByteArray();
  }

  public static byte[] join(Collection<byte[]> parts) {
    return join(parts.toArray(byte[][]::new));
  }

  public static byte[] repeat(int value, int count) {
    byte[] bytes = new byte[count];
    Arrays.fill(bytes, (byte) value);
    return bytes;
  }

  public static byte[] range(int count) {
    byte[] bytes = new byte[count];
    for (int i = 0; i < count; i++) bytes[i] = (byte) i;
    return bytes;
  }

  public static byte[] uint(long value, int width, boolean little) {
    byte[] bytes = new byte[width];
    for (int i = 0; i < width; i++) bytes[little ? i : width - 1 - i] = (byte) (value >>> (8 * i));
    return bytes;
  }

  public static void field(byte[] bytes, int offset, int width, long value, boolean little) {
    byte[] raw = uint(value, width, little);
    if (offset < bytes.length)
      System.arraycopy(raw, 0, bytes, offset, Math.min(width, bytes.length - offset));
  }

  public static byte[] slice(byte[] bytes, int start, int end) {
    return Arrays.copyOfRange(bytes, Math.min(start, bytes.length), Math.min(end, bytes.length));
  }

  public static byte[] pack(String format, Object... values) {
    boolean little = format.startsWith("<");
    int index = 0, repeat = 0;
    var out = new ByteArrayOutputStream();
    for (char code : format.toCharArray()) {
      if (code == '<' || code == '>' || code == '!' || code == '=') continue;
      if (Character.isDigit(code)) {
        repeat = repeat * 10 + code - '0';
        continue;
      }
      int count = repeat == 0 ? 1 : repeat;
      repeat = 0;
      if (code == 's') {
        byte[] data = (byte[]) values[index++];
        out.writeBytes(Arrays.copyOf(data, count));
        continue;
      }
      if (code == 'x') {
        out.writeBytes(new byte[count]);
        continue;
      }
      int width =
        switch (code) {
          case 'b', 'B', 'c' -> 1;
          case 'h', 'H' -> 2;
          case 'i', 'I', 'l', 'L', 'f' -> 4;
          case 'q', 'Q', 'd' -> 8;
          default -> throw new IllegalArgumentException("Packing code " + code);
        };
      for (int i = 0; i < count; i++) {
        Object value = values[index++];
        long n;
        if (code == 'f') n = Float.floatToRawIntBits(((Number) value).floatValue());
        else if (code == 'd') n = Double.doubleToRawLongBits(((Number) value).doubleValue());
        else if (value instanceof byte[] bytes) n = bytes[0] & 255;
        else n = ((Number) value).longValue();
        out.writeBytes(uint(n, width, little));
      }
    }
    if (index != values.length) throw new IllegalArgumentException("Unused packing values");
    return out.toByteArray();
  }

  public static String bool(boolean value) {
    return value ? "True" : "False";
  }
}
