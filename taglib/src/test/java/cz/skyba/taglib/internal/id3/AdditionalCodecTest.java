package cz.skyba.taglib.internal.id3;

import cz.skyba.taglib.Format;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.internal.codec.ShortenCodec;
import cz.skyba.taglib.internal.codec.TrackerCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static cz.skyba.taglib.internal.Binary.*;
import static org.junit.jupiter.api.Assertions.*;

class AdditionalCodecTest {
  @TempDir
  Path directory;


  @Test
  void trackerFieldsRewriteWithoutChangingPatterns() throws Exception {
    byte[] module = new byte[2200];
    System.arraycopy(bytes("8CHN"), 0, module, 1080, 4);
    Arrays.fill(module, 1084, module.length, (byte) 73);
    Path path = directory.resolve("sample.mod");
    Files.write(path, module);
    var codec = new TrackerCodec(Format.MOD);
    codec.write(
      path,
      PropertyMap.builder().put("TITLE", "Module title").put("COMMENT", "First\nSecond").build());
    var result = codec.read(path, ReadOptions.DEFAULT);
    assertEquals("Module title", result.properties().first("TITLE"));
    assertEquals(8, result.audioProperties().channels());
    assertTrue(result.properties().first("COMMENT").startsWith("First\nSecond\n"));
    assertArrayEquals(
      Arrays.copyOfRange(module, 1080, module.length),
      Arrays.copyOfRange(Files.readAllBytes(path), 1080, module.length));
  }

  @Test
  void strippedXmAcceptsCommentsWhenNoInstrumentSlotsExist() throws Exception {
    byte[] module = new byte[80];
    System.arraycopy(bytes("Extended Module: "), 0, module, 0, 17);
    module[37] = 26;
    put32le(module, 60, 20);
    put16le(module, 68, 8);
    Path path = directory.resolve("stripped.xm");
    Files.write(path, module);
    var codec = new TrackerCodec(Format.XM);
    codec.write(
      path,
      PropertyMap.builder()
        .put("TITLE", "Updated")
        .put("COMMENT", "No slot for this line\nOr this line")
        .build());
    var read = codec.read(path, ReadOptions.DEFAULT);
    assertEquals("Updated", read.properties().first("TITLE"));
    assertEquals("", read.properties().first("COMMENT"));
    assertEquals(module.length, Files.size(path));
    assertArrayEquals(
      Arrays.copyOfRange(module, 58, module.length),
      Arrays.copyOfRange(Files.readAllBytes(path), 58, module.length));
  }

  @Test
  void impulseTrackerMessageCanBeAppendedSafely() throws Exception {
    byte[] module = new byte[192];
    System.arraycopy(bytes("IMPM"), 0, module, 0, 4);
    Arrays.fill(module, 128, 192, (byte) 64);
    Path path = directory.resolve("sample.it");
    Files.write(path, module);
    var codec = new TrackerCodec(Format.IT);
    var props =
      PropertyMap.builder()
        .put("TITLE", "Impulse")
        .put("COMMENT", "First line\nSecond line")
        .build();
    codec.write(path, props);
    var read = codec.read(path, ReadOptions.DEFAULT);
    assertEquals(props.first("TITLE"), read.properties().first("TITLE"));
    assertEquals(props.first("COMMENT"), read.properties().first("COMMENT"));
    assertEquals(64, read.audioProperties().channels());
  }

  @Test
  void shortenRiceHeaderComputesPcmPropertiesAndRejectsBrokenCode() throws Exception {
    byte[] wav = new byte[44];
    System.arraycopy(bytes("RIFF"), 0, wav, 0, 4);
    put32le(wav, 4, 176400 + 36);
    System.arraycopy(bytes("WAVEfmt "), 0, wav, 8, 8);
    put32le(wav, 16, 16);
    wav[20] = 1;
    wav[22] = 2;
    put32le(wav, 24, 44100);
    put32le(wav, 28, 176400);
    wav[32] = 4;
    wav[34] = 16;
    System.arraycopy(bytes("data"), 0, wav, 36, 4);
    put32le(wav, 40, 176400);
    StringBuilder bits = new StringBuilder();
    for (long value : new long[]{3, 2, 256, 0, 0, 0}) uint(bits, value);
    rice(bits, 9, 2);
    rice(bits, 44, 5);
    for (byte value : wav) rice(bits, value & 255, 8);
    while (bits.length() % 8 != 0) bits.append('0');
    var data = new ByteArrayOutputStream();
    data.writeBytes(bytes("ajkg"));
    data.write(2);
    for (int i = 0; i < bits.length(); i += 8)
      data.write(Integer.parseInt(bits.substring(i, i + 8), 2));
    Path path = directory.resolve("sample.shn");
    Files.write(path, data.toByteArray());
    var codec = new ShortenCodec();
    var read = codec.read(path, ReadOptions.DEFAULT);
    assertEquals(1000, read.audioProperties().lengthInMilliseconds());
    assertEquals(1411, read.audioProperties().bitrate());
    assertEquals(44100, read.audioProperties().sampleRate());
    assertThrows(
      UnsupportedOperationException.class,
      () -> codec.write(path, PropertyMap.EMPTY));
    assertArrayEquals(data.toByteArray(), Files.readAllBytes(path));
    Files.write(path, Arrays.copyOf(data.toByteArray(), 8));
    assertThrows(IOException.class, () -> codec.read(path, ReadOptions.DEFAULT));
  }

  private static void uint(StringBuilder bits, long value) {
    int k = value == 0 ? 0 : 64 - Long.numberOfLeadingZeros(value);
    rice(bits, k, 2);
    rice(bits, value, k);
  }

  private static void rice(StringBuilder bits, long value, int k) {
    bits.repeat('0', (int) (value >>> k));
    bits.append('1');
    for (int i = k - 1; i >= 0; i--) bits.append((value >>> i) & 1);
  }
}
