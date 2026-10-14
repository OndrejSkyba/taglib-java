// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal;

import cz.skyba.taglib.internal.ape.ApeTag;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.IOStream;

import java.io.IOException;

/**
 * Native tag detection reads, including failed probes and their observable cursors.
 */
public final class NativeTagIO {
  private static final int MAX_DUPLICATE_ID3V2_TAGS = 100_000;

  private NativeTagIO() {
  }

  public record Span(long offset, long length) {
  }

  /**
   * Footer::completeTagSize trusts the header-present flag even when magic is absent.
   */
  public static Span apeSpan(BinaryFile file, ApeTag.Data parsed)
    throws IOException {
    if (parsed.length() == 0) {
      return new Span(parsed.offset(), 0);
    }
    long footer = parsed.offset() + parsed.length() - 32;
    byte[] data = file.read(footer, 32);
    long length = Binary.u32le(data, 12) + ((Binary.u32le(data, 20) & 0x80000000L) != 0 ? 32 : 0);
    return new Span(footer + 32 - length, length);
  }

  public static byte[] read(IOStream stream, long offset, long length) throws IOException {
    stream.seek(offset);
    return stream.readBlock(length).toByteArray();
  }

  /**
   * ID3v2::Tag::read: body read, consecutive duplicate extents, and final header probe.
   */
  public static Span id3v2(IOStream stream, long offset) throws IOException {
    byte[] header = read(stream, offset, 10);
    long body = id3Body(header), complete = 10 + body + id3Footer(header);
    if (body != 0) {
      stream.readBlock(body);
    }
    long extra = 0;
    for (int count = 0; count < MAX_DUPLICATE_ID3V2_TAGS; count++) {
      byte[] next = read(stream, offset + complete + extra, 10);
      if (next.length < 10 || !Binary.ascii(next, 0, 3).equals("ID3")) {
        break;
      }
      extra += 10 + id3Body(next) + id3Footer(next);
    }
    return new Span(offset, complete + extra);
  }

  /**
   * Same declared replacement extent for positional/path writers, without reading the body.
   */
  public static Span id3v2(BinaryFile file, long offset) throws IOException {
    if (offset < 0 || offset > file.size()) {
      throw new IOException("Invalid ID3 tag offset");
    }
    byte[] header = file.read(offset, (int) Math.min(10, file.size() - offset));
    long complete = 10 + id3Body(header) + id3Footer(header), extra = 0;
    for (int count = 0; count < MAX_DUPLICATE_ID3V2_TAGS; count++) {
      long next = offset + complete + extra;
      if (next > file.size() - 10) {
        break;
      }
      byte[] duplicate = file.read(next, 10);
      if (!Binary.ascii(duplicate, 0, 3).equals("ID3")) {
        break;
      }
      extra += 10 + id3Body(duplicate) + id3Footer(duplicate);
    }
    return new Span(offset, complete + extra);
  }

  private static long id3Body(byte[] header) {
    if (header.length < 10 || (header[3] & 255) == 255 || (header[4] & 255) == 255) {
      return 0;
    }
    for (int i = 6; i < 10; i++) {
      if (header[i] < 0) {
        return 0;
      }
    }
    return Binary.synchsafe(header, 6);
  }

  private static int id3Footer(byte[] header) {
    if (header.length != 10 || (header[3] & 255) == 255 || (header[4] & 255) == 255) {
      return 0;
    }
    for (int i = 6; i < 10; i++) {
      if (header[i] < 0) {
        return 0;
      }
    }
    return (header[5] & 16) != 0 ? 10 : 0;
  }

  public static long id3v1(IOStream stream) throws IOException {
    long size = stream.length();
    if (size < 128) {
      return -1;
    }
    long offset = size >= 131 ? size - 131 : size - 128;
    byte[] data = read(stream, offset, size >= 131 ? 8 : 3);
    int at = size >= 131 ? 3 : 0;
    if (data.length >= at + 3
      && Binary.ascii(data, at, 3).equals("TAG")
      && !(data.length == 8 && Binary.ascii(data, 0, 8).equals("APETAGEX"))) {
      long position = offset + at;
      read(stream, position, 128);
      return position;
    }
    return -1;
  }

  public static long ape(IOStream stream, long id3v1) throws IOException {
    long offset = (id3v1 >= 0 ? id3v1 : stream.length()) - 32;
    if (offset < 0) {
      return -1;
    }
    byte[] magic = read(stream, offset, 8);
    if (magic.length != 8 || !Binary.ascii(magic, 0, 8).equals("APETAGEX")) {
      return -1;
    }
    byte[] footer = read(stream, offset, 32);
    if (footer.length >= 16) {
      long size = Binary.u32le(footer, 12);
      if (size > 32 && size <= stream.length()) {
        read(stream, offset + 32 - size, size - 32);
      }
    }
    return offset;
  }

  public static void write(IOStream stream, long offset, byte[] data) throws IOException {
    stream.seek(offset);
    stream.writeBlock(new ByteVector(data));
  }
}
