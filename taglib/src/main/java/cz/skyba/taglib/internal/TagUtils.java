// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal;

import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.id3.v2.Header;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.IOStream;

/// Tag finders without payload reads.
public final class TagUtils {
  // Matching methods only read these private patterns; no mutable storage escapes.
  private static final ByteVector ID3V2_IDENTIFIER = new ByteVector("ID3");
  private static final ByteVector ID3V1_IDENTIFIER = new ByteVector("TAG");
  private static final ByteVector APE_IDENTIFIER = new ByteVector("APETAGEX");

  private TagUtils() {
  }

  public static long findID3v2(FileBase file) {
    if (!file.isValid()) {
      return -1;
    }
    file.seek(0);
    return file.readBlock(3).equals(ID3V2_IDENTIFIER) ? 0 : -1;
  }

  public static long findID3v1(FileBase file) {
    if (!file.isValid()) {
      return -1;
    }
    long length = file.length();
    if (length < 128) {
      return -1;
    }
    if (length >= 131) {
      file.seek(-131, IOStream.Position.END);
      long position = file.tell() + 3;
      var data = file.readBlock(8);
      return data.containsAt(ID3V1_IDENTIFIER, 3)
        && !data.equals(APE_IDENTIFIER) ? position : -1;
    }
    file.seek(-128, IOStream.Position.END);
    long position = file.tell();
    return file.readBlock(3).equals(ID3V1_IDENTIFIER) ? position : -1;
  }

  public static long findAPE(FileBase file, long id3v1Location) {
    if (!file.isValid()) {
      return -1;
    }
    long end = id3v1Location >= 0 ? id3v1Location : file.length();
    if (end < 32) {
      return -1;
    }
    file.seek(end - 32);
    long position = file.tell();
    return file.readBlock(8).equals(APE_IDENTIFIER) ? position : -1;
  }

  /// Header parsing retains default body/flags when the native Header rejects its data.
  public static long completeID3v2Size(ByteVector header) {
    return new Header(header).completeTagSize();
  }

  public static ByteVector readHeader(IOStream stream, long length, boolean skipID3v2) {
    return readHeaderWithOffset(stream, length, skipID3v2).data();
  }

  /// Retains the native output offset without rereading a header or invoking a decoder twice.
  public record HeaderRead(ByteVector data, long offset) {
  }

  public static HeaderRead readHeaderWithOffset(IOStream stream, long length, boolean skipID3v2) {
    if (stream == null || !stream.isOpen()) {
      return new HeaderRead(new ByteVector(), 0);
    }
    long original = stream.tell(), offset = 0;
    try {
      if (skipID3v2) {
        stream.seek(0);
        var header = stream.readBlock(10);
        if (header.startsWith(ID3V2_IDENTIFIER)) {
          offset = completeID3v2Size(header);
        }
      }
      stream.seek(offset);
      return new HeaderRead(stream.readBlock(length), offset);
    } finally {
      stream.seek(original);
    }
  }
}
