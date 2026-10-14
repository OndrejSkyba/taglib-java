package cz.skyba.taglib.parity.trueaudio;

import cz.skyba.taglib.Picture;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.internal.codec.TrueAudioCodec;
import cz.skyba.taglib.internal.id3.Id3v2;
import cz.skyba.taglib.parity.support.NativeSavedFileComparison;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static cz.skyba.taglib.internal.Binary.bytes;
import static cz.skyba.taglib.internal.Binary.put32le;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

@org.junit.jupiter.api.Tag("parity")
@org.junit.jupiter.api.Tag("trueaudio")
class TrueAudioArtworkAndLosslessStreamPreservationTest {
  @TempDir
  Path directory;

  @Test
  void trueAudioWritesPreserveLosslessStream() throws Exception {
    byte[] audio = new byte[2000];
    System.arraycopy(bytes("TTA1"), 0, audio, 0, 4);
    audio[4] = 1;
    audio[6] = 2;
    audio[8] = 16;
    put32le(audio, 10, 44100);
    put32le(audio, 14, 44100);
    Path path = directory.resolve("sample.tta");
    Files.write(path, audio);
    var codec = new TrueAudioCodec();
    assertEquals(
      1000, codec.read(path, ReadOptions.DEFAULT).audioProperties().lengthInMilliseconds());
    var props = PropertyMap.builder().put("TITLE", "Lossless").build();
    var pictures = List.of(new Picture(3, "image/png", "cover", new byte[]{8, 9, 10}));
    codec.write(path, props, pictures);
    var read = codec.read(path, ReadOptions.DEFAULT);
    NativeSavedFileComparison.compare(audio, path, "tta-artwork");
    assertEquals(props.toBuilder().addUnsupportedData("APIC").build(), read.properties());
    assertEquals(1, read.pictures().size());
    byte[] output = Files.readAllBytes(path);
    assertArrayEquals(
      audio,
      Arrays.copyOfRange(output, Id3v2.tagSize(output), Id3v2.tagSize(output) + audio.length));
    assertEquals(Id3v2.tagSize(output) + audio.length, output.length);
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
