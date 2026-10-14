package cz.skyba.taglib.riff.aiff;

import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class AiffPropertiesTest {
  @TempDir
  Path directory;

  private static ByteVector chunk(String name, ByteVector bytes) {
    return new ByteVector(name).append(ByteVector.fromUnsignedInt(bytes.size())).append(bytes)
      .append(new ByteVector(bytes.size() % 2));
  }

  private static ByteVector comm(String rate) {
    return new ByteVector(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
      .putShort((short) 2).putInt(44100).putShort((short) 16).array())
      .append(new ByteVector(HexFormat.of().parseHex(rate)));
  }

  private static ByteVectorStream stream(ByteVector... headers) {
    var body = new ByteVector("AIFF");
    for (var header : headers) body.append(chunk("COMM", header));
    body.append(chunk("SSND", new ByteVector(64)));
    return new ByteVectorStream(new ByteVector("FORM").append(ByteVector.fromUnsignedInt(body.size())).append(body));
  }

  @Test
  void extendedSampleRateRoundingRetainsNativePrecisionBelowHalfBoundary() throws Exception {
    // Exact binary value 44100.5 - 2^-48; a double rounds this to 44100.5.
    var p = new Properties(stream(comm("400eac447fffffffffff")));
    assertEquals(44100, p.sampleRate());
    assertEquals(1000, p.lengthInMilliseconds());
    assertEquals(44100, p.sampleFrames());
    assertEquals(2, p.channels());
    assertEquals(16, p.bitsPerSample());
    assertEquals(1, p.bitrate());
    assertFalse(p.isAiffC());
    p = new Properties(stream(comm("400eac44800000000000")));
    assertEquals(44101, p.sampleRate());
  }

  @Test
  void emptyCommDoesNotShadowTheNextHeaderButNonemptyShortCommDoes() throws Exception {
    var full = comm("400eac44000000000000");
    var p = new Properties(stream(new ByteVector(), full));
    assertEquals(44100, p.sampleFrames());
    p = new Properties(stream(new ByteVector("!"), full));
    assertEquals(0, p.sampleFrames());
    assertEquals(0, p.channels());
  }

  @Test
  void compressionUsesPayloadPresenceAndBoundedLatin1PascalName() throws Exception {
    var data = comm("400eac44000000000000").append(new ByteVector("ALAW"));
    var p = new Properties(stream(data));
    assertFalse(p.isAiffC());
    assertTrue(p.compressionType().isEmpty());
    data.append(new ByteVector(new byte[]{(byte) 255, 'a', 0, 'b', (byte) 255}));
    p = new Properties(stream(data));
    assertTrue(p.isAiffC());
    assertEquals(new ByteVector("ALAW"), p.compressionType());
    assertEquals("a", p.compressionName());
    p.compressionType().clear();
    assertEquals(new ByteVector("ALAW"), p.compressionType());
  }

  @Test
  void infinityEncodingUsesNativeZeroRateButRetainsOtherDecodedFields() throws Exception {
    var p = new Properties(stream(comm("7fff8000000000000000")));
    assertEquals(0, p.sampleRate());
    assertEquals(0, p.lengthInMilliseconds());
    assertEquals(0, p.bitrate());
    assertEquals(44100, p.sampleFrames());
    assertEquals(16, p.bitsPerSample());
  }

  @Test
  void pathAndBorrowedSnapshotsRemainDetachedForEveryReadStyle() throws Exception {
    var bytes = stream(comm("400eac44000000000000").append(new ByteVector("NONE\u0005hello"))).data().toByteArray();
    var path = Files.write(directory.resolve("properties.bin"), bytes);
    var defaults = new Properties(path);
    for (var style : ReadStyle.values()) {
      var data = new ByteVectorStream(bytes);
      var p = new Properties(data, style);
      data.data().clear();
      assertTrue(data.isOpen());
      assertEquals(defaults.common(), p.common());
      assertEquals("hello", p.compressionName());
      assertEquals(44100, p.sampleFrames());
    }
    Files.delete(path);
    assertEquals("hello", defaults.compressionName());
  }
}
