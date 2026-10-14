package cz.skyba.taglib.asf;

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
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class AsfPropertiesTest {
  @TempDir
  Path temporary;
  private static final String HEADER = "3026b2758e66cf11a6d900aa0062ce6c", FILE = "a1dcab8c47a9cf118ee400c00c205365",
    STREAM = "9107dcb7b7a9cf118ee600c00c205365", LIST = "4052d1861d31d011a3a400a0c90348f6";

  private static byte[] object(String guid, byte[] payload) throws Exception {
    var out = new ByteArrayOutputStream();
    out.write(HexFormat.of().parseHex(guid));
    out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(payload.length + 24).array());
    out.write(payload);
    return out.toByteArray();
  }

  private static byte[] input(byte[]... extra) throws Exception {
    byte[] file = new byte[80], stream = new byte[70];
    ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN).putLong(40, 10000000);
    var s = ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN);
    s.putShort(54, (short) 0x161).putShort(56, (short) 2).putInt(58, 44100).putInt(62, 16000).putShort(68, (short) 16);
    var body = new ByteArrayOutputStream();
    body.write(object(FILE, file));
    body.write(object(STREAM, stream));
    for (var e : extra) body.write(e);
    var out = new ByteArrayOutputStream();
    out.write(HexFormat.of().parseHex(HEADER));
    out.write(ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN).putLong(30 + body.size()).putInt(2 + extra.length).put((byte) 1).put((byte) 2).array());
    out.write(body.toByteArray());
    return out.toByteArray();
  }

  private static byte[] entry(int type, String name, String description) throws Exception {
    byte[] n = name.getBytes(StandardCharsets.UTF_16LE), d = description.getBytes(StandardCharsets.UTF_16LE);
    var out = new ByteArrayOutputStream();
    out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort((short) type).putShort((short) (n.length / 2)).array());
    out.write(n);
    out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) (d.length / 2)).array());
    out.write(d);
    out.write(new byte[2]);
    return out.toByteArray();
  }

  private static byte[] codecs(byte[]... entries) throws Exception {
    var out = new ByteArrayOutputStream();
    out.write(new byte[16]);
    out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(entries.length).array());
    for (var e : entries) out.write(e);
    return object(LIST, out.toByteArray());
  }

  @Test
  void defaultAndSettersRetainNativeSignedValuesAndWaveCodecNumbers() {
    var p = new Properties();
    assertEquals(0, p.lengthInMilliseconds());
    assertEquals(Properties.Codec.Unknown, p.codec());
    assertEquals("", p.codecName());
    assertFalse(p.isEncrypted());
    p.setLengthInMilliseconds(-1001);
    p.setBitrate(-17);
    p.setSampleRate(Integer.MIN_VALUE);
    p.setChannels(-2);
    p.setBitsPerSample(65535);
    p.setCodec(0x163);
    p.setCodecName(" \t name \n ");
    p.setCodecDescription("é音\u0000tail");
    p.setEncrypted(true);
    assertEquals(-1, p.lengthInSeconds());
    assertEquals(-1001, p.lengthInMilliseconds());
    assertEquals(-17, p.bitrate());
    assertEquals(Integer.MIN_VALUE, p.sampleRate());
    assertEquals(-2, p.channels());
    assertEquals(65535, p.bitsPerSample());
    assertEquals(Properties.Codec.WMA9Lossless, p.codec());
    assertTrue(p.common().lossless());
    assertEquals(" \t name \n ", p.codecName());
    assertEquals("é音\u0000tail", p.codecDescription());
    assertTrue(p.isEncrypted());
    int[] codes = {0x160, 0x161, 0x162, 0x163};
    for (int i = 0; i < codes.length; i++) {
      p.setCodec(codes[i]);
      assertEquals(Properties.Codec.values()[i + 1], p.codec());
    }
    p.setCodec(4);
    assertEquals(Properties.Codec.Unknown, p.codec());
    assertFalse(p.common().lossless());
  }

  @Test
  void firstAudioEntryAndEachTopLevelCodecListAreObserved() throws Exception {
    var p = new Properties(new ByteVectorStream(input(codecs(entry(1, "Video", ""), entry(2, " \t Audio é音 \n ", " Description \t "), entry(2, "Ignored", "Ignored")))));
    assertEquals("Audio é音", p.codecName());
    assertEquals("Description", p.codecDescription());
    assertEquals(Properties.Codec.WMA2, p.codec());
    assertEquals(128, p.bitrate());
    assertEquals(1000, p.lengthInMilliseconds());
    p = new Properties(new ByteVectorStream(input(codecs(entry(2, "First", "First desc")), codecs(entry(1, "Video", "")))));
    assertEquals("First", p.codecName());
    p = new Properties(new ByteVectorStream(input(codecs(entry(2, "First", "First desc")), codecs(entry(2, "", "")))));
    assertEquals("", p.codecName());
    assertEquals("", p.codecDescription());
  }

  @Test
  void codecListUsesAvailableUtf16PrefixAndNullTermination() throws Exception {
    var raw = entry(2, "a\u0000b", "desc");
    var p = new Properties(new ByteVectorStream(input(codecs(raw))));
    assertEquals("a", p.codecName());
    assertEquals("desc", p.codecDescription());
    byte[] partial = new byte[27];
    ByteBuffer.wrap(partial).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 1).putShort(20, (short) 2).putShort(22, (short) 3).putShort(24, (short) 'A');
    partial[26] = 'B';
    p = new Properties(new ByteVectorStream(input(object(LIST, partial))));
    assertEquals("A", p.codecName());
    assertEquals("", p.codecDescription());
  }

  @Test
  void encryptionIncludesPinnedLiteralAndIgnoresTheDifferentSpecificationGuid() throws Exception {
    for (String guid : new String[]{"fbb3112223bdd211b4b700a0c955fc6e", "14e68a29222620174cb935dae07ee928", "b69b077aa4da124ea5ca91d38dc11a8d"})
      assertTrue(new Properties(new ByteVectorStream(input(object(guid, new byte[0])))).isEncrypted());
    assertFalse(new Properties(new ByteVectorStream(input(object("14e68a292226174cb935dae07ee9289c", new byte[0])))).isEncrypted());
  }

  @Test
  void fileAndBorrowedConstructorsRetainDetachedValuesAndCallerOwnership() throws Exception {
    byte[] bytes = input(codecs(entry(2, "codec", "description")));
    var path = temporary.resolve("input.any");
    Files.write(path, bytes);
    for (var style : ReadStyle.values()) {
      var p = new Properties(path, style);
      var stream = new ByteVectorStream(bytes);
      var borrowed = new Properties(stream, style);
      assertEquals(p.common(), borrowed.common());
      assertEquals("codec", borrowed.codecName());
      assertTrue(stream.isOpen());
      assertEquals(bytes.length, stream.tell());
      stream.data().clear();
      assertEquals("description", borrowed.codecDescription());
      assertEquals(44100, borrowed.sampleRate());
    }
  }
}
