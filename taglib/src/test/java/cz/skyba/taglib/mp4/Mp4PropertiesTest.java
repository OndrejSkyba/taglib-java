package cz.skyba.taglib.mp4;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Mp4PropertiesTest {
  @TempDir
  Path temporary;

  private static byte[] join(byte[]... values) {
    var out = new ByteArrayOutputStream();
    for (var value : values) out.writeBytes(value);
    return out.toByteArray();
  }

  private static byte[] atom(String name, byte[] payload) {
    return join(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(payload.length + 8).put(name.getBytes(StandardCharsets.ISO_8859_1)).array(), payload);
  }

  private static byte[] sample(String codec, int size) {
    byte[] payload = new byte[size];
    var b = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
    b.putInt(4, 1).putInt(8, size - 8);
    System.arraycopy(codec.getBytes(StandardCharsets.ISO_8859_1), 0, payload, 12, 4);
    if (size >= 42) b.putShort(32, (short) 2).putShort(34, (short) 16).putInt(38, 44100);
    return payload;
  }

  private static byte[] media() {
    byte[] payload = new byte[24];
    ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).putInt(12, 44100).putInt(16, 44100);
    return atom("mdhd", payload);
  }

  private static byte[] track(byte[] sample, boolean handler, byte[] media) {
    byte[] h = handler ? atom("hdlr", join(new byte[8], "soun".getBytes(StandardCharsets.US_ASCII), new byte[12])) : new byte[0];
    return atom("trak", atom("mdia", join(h, media, atom("minf", atom("stbl", atom("stsd", sample))))));
  }

  private static byte[] input(byte[] movie) {
    return join(atom("ftyp", join("M4A ".getBytes(StandardCharsets.US_ASCII), new byte[8])), atom("moov", movie), atom("mdat", new byte[128]));
  }

  private static Properties read(byte[] movie) throws Exception {
    return new Properties(new ByteVectorStream(input(movie)));
  }

  @Test
  void partialAacRetainsCodecAndUnrecognizedEsdsHasNoBitrateFallback() throws Exception {
    var p = read(track(sample("mp4a", 16), true, media()));
    assertEquals(Properties.Codec.AAC, p.codec());
    assertEquals("mp4a", p.codecId());
    assertEquals(1000, p.lengthInMilliseconds());
    assertEquals(0, p.sampleRate());
    assertEquals(0, p.channels());
    assertEquals(0, p.bitrate());
    p = read(track(sample("mp4a", 42), true, media()));
    assertEquals(44100, p.sampleRate());
    assertEquals(2, p.channels());
    assertEquals(16, p.bitsPerSample());
    assertEquals(0, p.bitrate());
  }

  @Test
  void signedSampleEntryFieldsAndAvailableBigEndianPrefixMatchNative() throws Exception {
    byte[] payload = sample("mp4a", 42);
    ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).putShort(32, (short) 0x8000).putShort(34, (short) 0xffff).putInt(38, -1);
    var p = read(track(payload, true, media()));
    assertEquals(-32768, p.channels());
    assertEquals(-1, p.bitsPerSample());
    assertEquals(-1, p.sampleRate());
    payload = sample("mp4a", 33);
    payload[32] = (byte) 0xff;
    p = read(track(payload, true, media()));
    assertEquals(255, p.channels());
    assertEquals(0, p.bitsPerSample());
  }

  @Test
  void firstMissingHandlerAndMissingOrShortMediaHeaderReturnDefaultState() throws Exception {
    assertEquals(AudioProperties.EMPTY, read(join(track(sample("mp4a", 42), false, media()), track(sample("mp4a", 42), true, media()))).common());
    assertEquals(AudioProperties.EMPTY, read(track(sample("mp4a", 42), true, new byte[0])).common());
    assertEquals(Properties.Codec.Unknown, read(track(sample("mp4a", 42), true, atom("mdhd", new byte[20]))).codec());
  }

  @Test
  void alacBytesAreSignedAndInvalidConfigurationRetainsFourCc() throws Exception {
    var p = read(track(sample("alac", 42), true, media()));
    assertEquals(Properties.Codec.Unknown, p.codec());
    assertEquals("alac", p.codecId());
    assertEquals(0, p.channels());
    assertEquals(0, p.bitrate());
    byte[] payload = sample("alac", 80);
    System.arraycopy("alac".getBytes(StandardCharsets.US_ASCII), 0, payload, 48, 4);
    payload[61] = (byte) 128;
    payload[65] = (byte) 255;
    ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).putInt(76, 44100);
    p = read(track(payload, true, media()));
    assertEquals(Properties.Codec.ALAC, p.codec());
    assertEquals(-128, p.bitsPerSample());
    assertEquals(-1, p.channels());
    assertEquals(1, p.bitrate());
  }

  @Test
  void nestedMediaLengthsAndDirectDrmsSampleEntryAreRetained() throws Exception {
    var p = read(join(track(sample("zzzz", 42), true, media()), atom("udta", atom("mdat", new byte[1000]))));
    assertEquals(Properties.Codec.Unknown, p.codec());
    assertEquals("zzzz", p.codecId());
    assertEquals(9, p.bitrate());
    p = read(track(join(sample("mp4a", 42), atom("drms", new byte[0])), true, media()));
    assertTrue(p.isEncrypted());
    assertEquals(Properties.Codec.AAC, p.codec());
  }

  @Test
  void movieFallbackUsesNativeVersionZeroOffsets() throws Exception {
    byte[] zero = new byte[24];
    ByteBuffer.wrap(zero).order(ByteOrder.BIG_ENDIAN).putInt(12, 44100);
    byte[] movie = new byte[36];
    movie[0] = 1;
    ByteBuffer.wrap(movie).order(ByteOrder.BIG_ENDIAN).putInt(20, 1000).putLong(24, 1000);
    var p = read(join(track(sample("mp4a", 42), true, atom("mdhd", zero)), atom("mvhd", movie)));
    assertEquals(0, p.lengthInMilliseconds());
    assertEquals(44100, p.sampleRate());
    assertEquals(Properties.Codec.AAC, p.codec());
  }

  @Test
  void borrowedInitialCursorIsIgnoredAndSnapshotsRetainValues() throws Exception {
    byte[] bytes = input(track(sample("mp4a", 42), true, media()));
    Path path = temporary.resolve("input.any");
    Files.write(path, bytes);
    for (var style : ReadStyle.values()) {
      var p = new Properties(path, style);
      var stream = new ByteVectorStream(bytes);
      stream.seek(7);
      var borrowed = new Properties(stream, style);
      assertEquals(p.common(), borrowed.common());
      assertEquals(bytes.length - 136, stream.tell());
      assertTrue(stream.isOpen());
      stream.data().clear();
      assertEquals("mp4a", borrowed.codecId());
      assertEquals(44100, borrowed.sampleRate());
      assertEquals(Properties.Codec.AAC, borrowed.codec());
    }
  }
}
