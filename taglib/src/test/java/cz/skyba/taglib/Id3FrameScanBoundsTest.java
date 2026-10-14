package cz.skyba.taglib;

import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.NativeTagIO;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Independent native Header and Tag::read controls for defined malformed/foreign metadata.
 */
final class Id3FrameScanBoundsTest {
  @TempDir
  Path directory;


  @Test
  void borrowedAndPositionalDuplicateScansShareTheSameBound() throws Exception {
    byte[] header = tag(4, 0, new byte[0]);
    byte[] bytes = new byte[header.length * 100_002];
    for (int offset = 0; offset < bytes.length; offset += header.length) {
      System.arraycopy(header, 0, bytes, offset, header.length);
    }
    long positional;
    Path source = Files.write(directory.resolve("many-duplicate-tags.mp3"), bytes);
    try (var file = new BinaryFile(source)) {
      positional = NativeTagIO.id3v2(file, 0).length();
    }
    try (var stream = new ByteVectorStream(new ByteVector(bytes))) {
      assertEquals(positional, NativeTagIO.id3v2(stream, 0).length());
      assertEquals(positional, stream.tell());
    }
    assertEquals(header.length * 100_001L, positional);
  }


  private static byte[] tag(int version, int flags, byte[] body) {
    byte[] header = {'I', 'D', '3', (byte) version, 0, (byte) flags, 0, 0, 0, 0};
    for (int i = 0; i < 4; i++) header[9 - i] = (byte) ((body.length >>> (7 * i)) & 127);
    return join(header, body);
  }

  private static byte[] join(byte[]... arrays) {
    var out = new ByteArrayOutputStream();
    for (byte[] a : arrays) out.writeBytes(a);
    return out.toByteArray();
  }


}
