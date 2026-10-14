package cz.skyba.taglib.shorten;

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

class ShortenPropertiesTest {
  @TempDir
  Path temporary;

  @Test
  void nullableInputAndNativeValuesRetainEveryField() {
    var empty = new Properties((Properties.Values) null);
    assertEquals(AudioProperties.EMPTY, empty.common());
    assertEquals(0, empty.shortenVersion());
    assertEquals(0, empty.fileType());
    assertEquals(0, empty.sampleFrames());
    var p = new Properties(new Properties.Values(2, 5, 2, 44100, 16, 88200));
    assertEquals(2000, p.lengthInMilliseconds());
    assertEquals(2, p.lengthInSeconds());
    assertEquals(1411, p.bitrate());
    assertEquals(44100, p.sampleRate());
    assertEquals(2, p.channels());
    assertEquals(16, p.bitsPerSample());
    assertEquals(2, p.shortenVersion());
    assertEquals(5, p.fileType());
    assertEquals(88200, p.sampleFrames());
  }

  @Test
  void rawUnsignedFrameCountAndNativeSignedFieldsAreVisible() {
    var p = new Properties(new Properties.Values(Integer.MIN_VALUE, -1, -2, 44100, -16, -1));
    assertEquals(-1, p.sampleFrames());
    assertEquals(0, p.lengthInMilliseconds());
    assertEquals(1411, p.bitrate());
    assertEquals(Integer.MIN_VALUE, p.shortenVersion());
    assertEquals(-1, p.fileType());
    assertEquals(-2, p.channels());
    assertEquals(-16, p.bitsPerSample());
    p = new Properties(new Properties.Values(3, 7, 2, -1, 16, 44100));
    assertEquals(-1, p.sampleRate());
    assertEquals(0, p.lengthInMilliseconds());
    assertEquals(0, p.bitrate());
    assertEquals(44100, p.sampleFrames());
  }

  @Test
  void durationRoundsAtHalfMillisecondsAndChecksTheRoundedIntRange() {
    assertEquals(1, new Properties(new Properties.Values(1, 3, 1, 2000, 16, 1)).lengthInMilliseconds());
    assertEquals(0, new Properties(new Properties.Values(1, 3, 1, 2000, 16, 0)).lengthInMilliseconds());
    assertEquals(0, new Properties(new Properties.Values(1, 3, 1, 2000, 16, 4294967294L)).lengthInMilliseconds());
    assertEquals(0, new Properties(new Properties.Values(1, 3, 8, Integer.MAX_VALUE, 65535, 1)).bitrate());
  }

  private static void rice(StringBuilder out, long value, int width) {
    out.repeat('0', (int) (value >>> width));
    out.append('1');
    for (int i = width - 1; i >= 0; i--) out.append((value >>> i) & 1);
  }

  private static void uint(StringBuilder out, long value) {
    int width = value == 0 ? 0 : Math.min(31, 64 - Long.numberOfLeadingZeros(value));
    rice(out, width, 2);
    rice(out, value, width);
  }

  private static byte[] input(int version, int type) {
    byte[] wave = new byte[44];
    var b = ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN);
    b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(176436).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 2).putInt(44100).putInt(176400).putShort((short) 4).putShort((short) 16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(176400);
    var bits = new StringBuilder();
    for (long value : new long[]{Integer.toUnsignedLong(type), 2, 256, 0, 0, 0}) uint(bits, value);
    rice(bits, 9, 2);
    rice(bits, 44, 5);
    for (byte value : wave) rice(bits, value & 255, 8);
    while (bits.length() % 32 != 0) bits.append('0');
    var out = new ByteArrayOutputStream();
    out.writeBytes("ajkg".getBytes(StandardCharsets.US_ASCII));
    out.write(version);
    for (int i = 0; i < bits.length(); i += 8) out.write(Integer.parseInt(bits.substring(i, i + 8), 2));
    return out.toByteArray();
  }

  @Test
  void riceFileTypeAndBorrowedSnapshotLifetimeArePreserved() throws Exception {
    for (int version : new int[]{1, 2, 3})
      for (var style : ReadStyle.values()) {
        byte[] bytes = input(version, -1);
        Path path = temporary.resolve(version + ".any");
        Files.write(path, bytes);
        var p = new Properties(path, style);
        var stream = new ByteVectorStream(bytes);
        var borrowed = new Properties(stream, style);
        assertEquals(1000, p.lengthInMilliseconds());
        assertEquals(1411, p.bitrate());
        assertEquals(version, p.shortenVersion());
        assertEquals(-1, p.fileType());
        assertEquals(44100, p.sampleFrames());
        assertEquals(p.common(), borrowed.common());
        assertTrue(stream.isOpen());
        assertEquals(bytes.length, stream.tell());
        stream.data().clear();
        assertEquals(version, borrowed.shortenVersion());
        assertEquals(-1, borrowed.fileType());
        assertEquals(44100, borrowed.sampleFrames());
      }
  }
}
