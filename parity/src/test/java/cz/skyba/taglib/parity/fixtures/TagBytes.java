package cz.skyba.taglib.parity.fixtures;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static cz.skyba.taglib.parity.fixtures.Bytes.*;

/**
 * Raw tag/carrier encoding used only to create independent test inputs.
 */
final class TagBytes {
  private TagBytes() {
  }

  static byte[] sync(long n) {
    return new byte[]{
      (byte) ((n >> 21) & 127), (byte) ((n >> 14) & 127), (byte) ((n >> 7) & 127), (byte) (n & 127)
    };
  }

  static byte[] frame(String id, byte[] body, int version) {
    return frame(id, body, version, body.length, 0);
  }

  static byte[] frame(String id, byte[] body, int version, long size, int flags) {
    return join(
      text(id),
      version < 3 ? uint(size, 3, false) : version == 3 ? uint(size, 4, false) : sync(size),
      version < 3 ? new byte[0] : uint(flags, 2, false),
      body);
  }

  static byte[] id3(byte[] body, int version, int padding, int flags) {
    return join(
      text("ID3"),
      new byte[]{(byte) version, 0, (byte) flags},
      sync(body.length + padding),
      body,
      new byte[padding]);
  }

  static byte[] legacy() {
    return join(
      text("TAG"),
      Arrays.copyOf(text("legacy"), 30),
      Arrays.copyOf(text("fallback"), 30),
      Arrays.copyOf(text("album"), 30),
      text("2026"),
      Arrays.copyOf(text("comment"), 28),
      hex("0007ff"));
  }

  static byte[] apeItem(String key, byte[] value, int flags) {
    return join(pack("<II", value.length, flags), text(key), new byte[1], value);
  }

  static byte[] apeBody() {
    return join(
      apeItem("TITLE", text("APE title"), 0),
      apeItem("OPAQUE", join(hex("0001"), text("bytes")), 2),
      apeItem("ARTIST", text("one\0two"), 0));
  }

  static byte[] ape(byte[] body, boolean header) {
    return ape(body, header, 32L + body.length, header ? 0x80000000L : 0, 3);
  }

  static byte[] ape(byte[] body, boolean header, long size, long flags, int count) {
    byte[] footer = join(text("APETAGEX"), pack("<IIII", 2000, size, count, flags), new byte[8]);
    return join(
      header
        ? join(
        slice(footer, 0, 20),
        uint(flags | 0x20000000L, 4, true),
        slice(footer, 24, footer.length))
        : new byte[0],
      body,
      footer);
  }

  static byte[] encoded(String value, int encoding) {
    if (encoding == 0) return value.getBytes(StandardCharsets.ISO_8859_1);
    if (encoding == 3) return value.getBytes(StandardCharsets.UTF_8);
    byte[] result = new byte[value.length() * 2 + (encoding == 1 ? 2 : 0)];
    int offset = 0;
    if (encoding == 1) {
      result[0] = (byte) 255;
      result[1] = (byte) 254;
      offset = 2;
    }
    boolean little = encoding == 1 || encoding == 4;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      result[offset++] = (byte) (little ? c : c >>> 8);
      result[offset++] = (byte) (little ? c >>> 8 : c);
    }
    return result;
  }

  static byte[] delimiter(int encoding) {
    return new byte[encoding == 0 || encoding == 3 ? 1 : 2];
  }

  static byte[] riffChunk(String id, byte[] data) {
    return join(text(id), pack("<I", data.length), data, new byte[data.length % 2]);
  }

  static byte[] aiffChunk(String id, byte[] data) {
    return join(text(id), pack(">I", data.length), data, new byte[data.length % 2]);
  }

  static byte[] wave(byte[]... chunks) {
    byte[] body = join(text("WAVE"), join(chunks));
    return join(text("RIFF"), pack("<I", body.length), body);
  }

  static byte[] aiff(String type, byte[]... chunks) {
    byte[] body = join(text(type), join(chunks));
    return join(text("FORM"), pack(">I", body.length), body);
  }
}
