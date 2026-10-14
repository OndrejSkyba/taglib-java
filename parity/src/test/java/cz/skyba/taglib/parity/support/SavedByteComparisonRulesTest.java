package cz.skyba.taglib.parity.support;

import cz.skyba.taglib.parity.PayloadHash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent adversarial proof that complete structural preservation cannot pass corruption.
 */
final class SavedByteComparisonRulesTest {
  @TempDir
  Path directory;

  @Test
  void opaqueBinaryAndPictureChangesFailEvenWhenCommonSnapshotsDoNotChange() throws Exception {
    byte[] popm = frame("POPM", new byte[]{'a', 0, 64, 0, 0, 1});
    byte[] url = frame("W000", "https://example.invalid".getBytes(StandardCharsets.ISO_8859_1));
    byte[] picture =
      frame(
        "APIC",
        join(
          new byte[]{3},
          "image/png".getBytes(StandardCharsets.ISO_8859_1),
          new byte[]{0, 3, 0},
          new byte[]{(byte) 137, 'P', 'N', 'G', 9, 8, 7, 6}));
    byte[] sourceBytes = id3(join(popm, url, picture), new byte[]{1, 3, 5, 7, 9});
    Path source = save("source.mp3", sourceBytes), nativeAfter = save("native.mp3", sourceBytes);
    assertVerified(source, nativeAfter, nativeAfter);
    // Binary count, nonstandard URL and picture bytes are outside the common text projection.
    for (int at :
      List.of(
        10 + popm.length - 1,
        10 + popm.length + url.length - 1,
        10 + popm.length + url.length + picture.length - 1)) {
      byte[] corrupt = sourceBytes.clone();
      corrupt[at] ^= 1;
      Path changed = save("corrupt.mp3", corrupt);
      assertUnverified(source, changed, nativeAfter);
      assertUnverified(source, changed, changed);
    }
  }

  @Test
  void retainedTailCorruptionAndTruncationFailEvenWhenBothOutputsAgree() throws Exception {
    byte[] bytes = id3(frame("W000", new byte[]{1, 2, 3}), new byte[]{11, 12, 13, 14, 15});
    Path source = save("tail.mp3", bytes), reference = save("reference.mp3", bytes);
    byte[] corrupt = bytes.clone();
    corrupt[corrupt.length - 1] ^= 1;
    Path bothCorrupt = save("both-corrupt.mp3", corrupt);
    assertUnverified(source, bothCorrupt, bothCorrupt);
    Path truncated = save("truncated.mp3", Arrays.copyOf(bytes, bytes.length - 1));
    assertUnverified(source, truncated, reference);
    assertUnverified(source, truncated, truncated);
    Path invented = save("invented.mp3", join(bytes, new byte[]{99}));
    assertUnverified(source, invented, reference);
  }

  @Test
  void hiddenNonzeroTagPaddingAndUnsupportedFramingAreUnverified() throws Exception {
    byte[] padding = new byte[16];
    byte[] valid = id3(join(frame("W000", new byte[]{1, 2, 3}), padding), new byte[0]);
    Path reference = save("padding-reference.mp3", valid);
    assertVerified(reference, reference, reference);
    byte[] hidden = valid.clone();
    hidden[hidden.length - 1] = 42;
    Path source = save("hidden.mp3", hidden);
    assertNull(
      PayloadHash.preservation(source, source, source),
      "Nonzero hidden padding has no preservation proof");
    byte[] malformed = valid.clone();
    malformed[10] = '!';
    Path framing = save("bad-frame.mp3", malformed);
    assertNull(
      PayloadHash.preservation(reference, framing, framing),
      "Matching malformed outputs cannot prove preservation");
    byte[] overrun = valid.clone();
    overrun[9] = 127;
    Path length = save("bad-length.mp3", overrun);
    assertNull(
      PayloadHash.preservation(reference, length, length),
      "Output framing must fit the physical tag");
    byte[] unsupported = valid.clone();
    unsupported[3] = 5;
    Path version = save("unsupported.mp3", unsupported);
    assertNull(PayloadHash.preservation(version, version, version));
  }

  @Test
  void riffUnknownChunkHeadersPayloadPaddingAndOpaqueTailAreAllProtected() throws Exception {
    byte[] bytes =
      join(
        "RIFF".getBytes(StandardCharsets.ISO_8859_1),
        new byte[4],
        "WAVE".getBytes(StandardCharsets.ISO_8859_1),
        chunk("JUNK", new byte[]{1, 2, 3}),
        chunk("data", new byte[]{6, 7, 8, 9}),
        new byte[]{0, 1, 2, 3, 4, 5});
    little(bytes, 4, bytes.length - 8);
    Path source = save("opaque.wav", bytes), reference = save("native.wav", bytes);
    assertVerified(source, reference, reference);
    for (int at : List.of(12, 20, 23, bytes.length - 1)) {
      byte[] corrupt = bytes.clone();
      corrupt[at] ^= 1;
      Path changed = save("changed.wav", corrupt);
      assertUnverified(source, changed, reference);
      assertUnverified(source, changed, changed);
    }
    Path truncated = save("truncated.wav", Arrays.copyOf(bytes, bytes.length - 1));
    assertUnverified(source, truncated, truncated);
  }

  private static void assertVerified(Path source, Path javaAfter, Path nativeAfter)
    throws Exception {
    var proof = PayloadHash.preservation(source, javaAfter, nativeAfter);
    assertNotNull(proof);
    assertTrue(proof.verified(), proof.details());
  }

  private static void assertUnverified(Path source, Path javaAfter, Path nativeAfter)
    throws Exception {
    var proof = PayloadHash.preservation(source, javaAfter, nativeAfter);
    assertTrue(
      proof == null || !proof.verified(), "Corrupted retained or binary data must not pass");
  }

  private Path save(String name, byte[] bytes) throws Exception {
    return Files.write(directory.resolve(name), bytes);
  }

  private static byte[] id3(byte[] frames, byte[] tail) {
    byte[] header = {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0};
    synch(header, 6, frames.length);
    return join(header, frames, tail);
  }

  private static byte[] frame(String id, byte[] fields) {
    byte[] header = new byte[10];
    System.arraycopy(id.getBytes(StandardCharsets.ISO_8859_1), 0, header, 0, 4);
    synch(header, 4, fields.length);
    return join(header, fields);
  }

  private static byte[] chunk(String id, byte[] fields) {
    byte[] header = new byte[8];
    System.arraycopy(id.getBytes(StandardCharsets.ISO_8859_1), 0, header, 0, 4);
    little(header, 4, fields.length);
    return join(header, fields, new byte[fields.length & 1]);
  }

  private static void synch(byte[] bytes, int at, int value) {
    for (int i = 3; i >= 0; i--) {
      bytes[at + i] = (byte) (value & 127);
      value >>>= 7;
    }
  }

  private static void little(byte[] bytes, int at, int value) {
    for (int i = 0; i < 4; i++) {
      bytes[at + i] = (byte) value;
      value >>>= 8;
    }
  }

  private static byte[] join(byte[]... arrays) {
    var out = new ByteArrayOutputStream();
    for (byte[] array : arrays) out.writeBytes(array);
    return out.toByteArray();
  }
}
