package cz.skyba.taglib.matroska;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatroskaPropertiesTest {
  @TempDir
  Path temporary;

  private static byte[] join(byte[]... values) {
    var out = new ByteArrayOutputStream();
    for (var value : values) out.writeBytes(value);
    return out.toByteArray();
  }

  private static byte[] e(long id, byte[] data) {
    int idWidth = Math.max(1, (64 - Long.numberOfLeadingZeros(id) + 7) / 8), width = 1;
    while (data.length >= (1L << (width * 7)) - 1) width++;
    var out = new ByteArrayOutputStream();
    for (int i = idWidth - 1; i >= 0; i--) out.write((int) (id >>> (i * 8)));
    long size = (1L << (width * 7)) | data.length;
    for (int i = width - 1; i >= 0; i--) out.write((int) (size >>> (i * 8)));
    out.writeBytes(data);
    return out.toByteArray();
  }

  private static byte[] u(long id, long value) {
    return e(id, ByteBuffer.allocate(8).putLong(value).array());
  }

  private static byte[] f(long id, double value) {
    return e(id, ByteBuffer.allocate(8).putDouble(value).array());
  }

  private static byte[] track(byte[] codec, long channels, long bits, double rate) {
    return e(0xae, join(e(0x86, codec), e(0xe1, join(u(0x9f, channels), u(0x6264, bits), f(0xb5, rate)))));
  }

  private static byte[] header() {
    return join(e(0x4282, "matroska".getBytes(StandardCharsets.ISO_8859_1)), u(0x4287, 4));
  }

  private static byte[] segment(byte[] info, byte[] tracks, boolean padding) {
    return join(info, e(0x1654ae6b, tracks), padding ? e(0xec, new byte[2000]) : new byte[0]);
  }

  private static byte[] info(double duration, long scale, byte[]... titles) {
    var data = new ByteArrayOutputStream();
    data.writeBytes(u(0x2ad7b1, scale));
    data.writeBytes(f(0x4489, duration));
    for (var t : titles) data.writeBytes(e(0x7ba9, t));
    return e(0x1549a966, data.toByteArray());
  }

  private static byte[] input(byte[] header, byte[] segment) {
    return join(e(0x1a45dfa3, header), e(0x18538067, segment));
  }

  private static Properties read(byte[] header, byte[] info, byte[] tracks) throws Exception {
    return new Properties(new ByteVectorStream(input(header, segment(info, tracks, false))));
  }

  @Test
  void directNativeConstructionIsEmptyIncludingNullFilePointer() {
    for (var style : ReadStyle.values()) {
      var p = new Properties(style);
      assertEquals(AudioProperties.EMPTY, p.common());
      assertEquals("", p.docType());
      assertEquals(0, p.docTypeVersion());
      assertEquals("", p.codecName());
      assertEquals("", p.title());
    }
  }

  @Test
  void documentFieldsUseLatin1AndRetainParsedHeaderPrefixOnFailure() throws Exception {
    byte[] prefix = join(e(0x4282, new byte[]{(byte) 255}), u(0x4287, 0xffffffffL));
    var p = read(join(prefix, new byte[2]), info(1000, 1000000), track("A_FLAC".getBytes(StandardCharsets.US_ASCII), 2, 16, 44100));
    assertEquals("ÿ", p.docType());
    assertEquals(-1, p.docTypeVersion());
    assertEquals("A_FLAC", p.codecName());
    assertEquals(1000, p.lengthInMilliseconds());
    assertEquals(2, p.channels());
    assertEquals(16, p.bitsPerSample());
  }

  @Test
  void unsignedTimestampScaleAndFractionalDurationUseNativeTruncation() throws Exception {
    var tracks = track("A_AAC".getBytes(StandardCharsets.US_ASCII), 2, 16, 44100.5);
    var p = read(header(), info(1000.75, 1000000), tracks);
    assertEquals(1000, p.lengthInMilliseconds());
    assertEquals(44100, p.sampleRate());
    p = read(header(), info(1e-12, Long.MIN_VALUE), tracks);
    assertEquals(9, p.lengthInMilliseconds());
  }

  @Test
  void titleUsesFirstValueAndStrictUtf8DecodeAfterRawNullTermination() throws Exception {
    var tracks = track(new byte[]{(byte) 0xc3, (byte) 0xa9}, 2, 16, 44100);
    var p = read(header(), info(1000, 1000000, new byte[0], "ignored".getBytes(StandardCharsets.UTF_8)), tracks);
    assertEquals("", p.title());
    assertEquals("Ã©", p.codecName());
    p = read(header(), info(1000, 1000000, new byte[]{'a', 0, (byte) 255}), tracks);
    assertEquals("a", p.title());
    p = read(header(), info(1000, 1000000, new byte[]{(byte) 255}), tracks);
    assertEquals("", p.title());
    p = read(header(), info(1000, 1000000, "é音".getBytes(StandardCharsets.UTF_8)), tracks);
    assertEquals("é音", p.title());
  }

  @Test
  void rejectedTrackFieldsAllowTheNextTrack() throws Exception {
    var p = read(header(), info(1000, 1000000), join(track("invalid".getBytes(StandardCharsets.US_ASCII), 0x80000000L, 16, 44100), track("fallback".getBytes(StandardCharsets.US_ASCII), 1, 8, 48000)));
    assertEquals("fallback", p.codecName());
    assertEquals(1, p.channels());
    assertEquals(8, p.bitsPerSample());
    assertEquals(48000, p.sampleRate());
  }

  @Test
  void borrowedBitrateIsLazyAndThenCachedWhilePathValuesAreDetached() throws Exception {
    byte[] bytes = input(header(), segment(info(1000, 1000000, "title".getBytes(StandardCharsets.UTF_8)), track("A_FLAC".getBytes(StandardCharsets.US_ASCII), 2, 16, 44100), true));
    for (var style : ReadStyle.values()) {
      var stream = new ByteVectorStream(bytes);
      var p = new Properties(stream, style);
      assertEquals(1000, p.lengthInMilliseconds());
      assertTrue(stream.isOpen());
      stream.data().clear();
      assertEquals(0, p.bitrate());
      stream.data().append(new ByteVector(new byte[10000]));
      assertEquals(0, p.bitrate());
      assertEquals("title", p.title());
      var cachedStream = new ByteVectorStream(bytes);
      var cached = new Properties(cachedStream, style);
      int bitrate = cached.bitrate();
      assertTrue(bitrate > 0);
      cachedStream.data().clear();
      assertEquals(bitrate, cached.bitrate());
      Path path = temporary.resolve("input-" + style + ".any");
      Files.write(path, bytes);
      var detached = new Properties(path, style);
      Files.delete(path);
      assertEquals(bitrate, detached.bitrate());
      assertEquals("matroska", detached.docType());
    }
  }
}
