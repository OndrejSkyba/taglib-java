package cz.skyba.taglib.parity.mpeg;

import cz.skyba.taglib.*;
import cz.skyba.taglib.internal.Binary;
import cz.skyba.taglib.internal.codec.FlacCodec;
import cz.skyba.taglib.internal.codec.MpegCodec;
import cz.skyba.taglib.internal.id3.Id3v1;
import cz.skyba.taglib.internal.id3.Id3v2;
import cz.skyba.taglib.parity.support.NativeSavedFileComparison;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("parity")
@org.junit.jupiter.api.Tag("mpeg")
class Id3VersionPaddingAndOpaqueFramePreservationTest {
  @TempDir
  Path directory;

  @Test
  void mpegDefaultAndPhysicalEditorFollowTheirId3VersionPoliciesPreservingOpaqueFramesAndAudio()
    throws Exception {
    byte[] audio = mpegAudio();
    for (int version : new int[]{3, 4})
      for (boolean physical : new boolean[]{false, true}) {
        Path path = directory.resolve("capacity.mp3");
        byte[] opaque = id3Frame(version, "XZZZ", new byte[]{9, 8, 7, 6});
        byte[] tag =
          id3(version, 2048, id3Frame(version, "TIT2", Binary.bytes("\u0000before")), opaque);
        byte[] legacy = Id3v1.render(properties("before"));
        Files.write(path, join(tag, audio, legacy));
        for (String title : List.of("Longer title ".repeat(20), "short")) {
          byte[] before = Files.readAllBytes(path);
          changeTitle(path, title, physical);
          if (!physical) NativeSavedFileComparison.compareTitle(before, path, title, false);
          byte[] saved = Files.readAllBytes(path);
          assertEquals(tag.length, Id3v2.tagSize(saved), "Existing ID3 allocation should fit edit");
          assertEquals(tag.length + audio.length + legacy.length, saved.length);
          assertEquals(physical ? version : 4, saved[3]);
          assertArrayEquals(
            audio, Arrays.copyOfRange(saved, tag.length, tag.length + audio.length));
          assertTrue(contains(saved, opaque));
          assertEquals(title, TagLib.read(path, ReadOptions.TAGS_ONLY).properties().first("TITLE"));
        }
      }
  }

  private static void changeTitle(Path path, String title, boolean physical) throws Exception {
    if (physical) {
      var document = MetadataDocument.open(path);
      var entry =
        document.entries().stream()
          .filter(e -> e.standardKey().equals("TITLE") && !e.block().equals("ID3v1"))
          .findFirst()
          .orElseThrow();
      document.apply(
        List.of(new MetadataChange.Update(entry.id(), entry.key(), List.of(title))));
      document.save();
    } else if (path.toString().endsWith("flac")) new FlacCodec().write(path, properties(title));
    else new MpegCodec().write(path, properties(title));
  }

  private static PropertyMap properties(String title) {
    return PropertyMap.builder().put("TITLE", title).build();
  }

  private static byte[] mpegAudio() {
    byte[] audio = new byte[417 * 1000];
    for (int offset = 0; offset < audio.length; offset += 417) {
      audio[offset] = (byte) 255;
      audio[offset + 1] = (byte) 251;
      audio[offset + 2] = (byte) 144;
    }
    return audio;
  }

  private static byte[] id3(int version, int padding, byte[]... frames) {
    byte[] body = join(frames);
    byte[] bytes = new byte[10 + body.length + padding];
    System.arraycopy(Binary.bytes("ID3"), 0, bytes, 0, 3);
    bytes[3] = (byte) version;
    Binary.putSynchsafe(bytes, 6, bytes.length - 10);
    System.arraycopy(body, 0, bytes, 10, body.length);
    return bytes;
  }

  private static byte[] id3Frame(int version, String id, byte[] data) {
    byte[] bytes = new byte[10 + data.length];
    System.arraycopy(Binary.bytes(id), 0, bytes, 0, 4);
    if (version == 4) Binary.putSynchsafe(bytes, 4, data.length);
    else Binary.put32be(bytes, 4, data.length);
    System.arraycopy(data, 0, bytes, 10, data.length);
    return bytes;
  }

  private static int id3Padding(byte[] tag) throws Exception {
    int offset = 10;
    while (offset + 10 <= tag.length && tag[offset] != 0)
      offset +=
        10
          + (tag[3] == 4
          ? Binary.synchsafe(tag, offset + 4)
          : (int) Binary.u32be(tag, offset + 4));
    for (int i = offset; i < tag.length; i++) assertEquals(0, tag[i]);
    return tag.length - offset;
  }

  private static byte[] streamInfo() {
    byte[] info = new byte[34];
    Binary.put64be(info, 10, ((long) 44_100 << 44) | (1L << 41) | (15L << 36) | 88_200);
    return info;
  }

  private static byte[] flacBlock(int type, byte[] data) {
    byte[] bytes = new byte[4 + data.length];
    Binary.put32be(bytes, 0, data.length);
    bytes[0] = (byte) type;
    System.arraycopy(data, 0, bytes, 4, data.length);
    return bytes;
  }

  private static int flacAudioOffset(byte[] bytes) {
    int offset = 4;
    while (true) {
      int size = (int) Binary.u32be(bytes, offset) & 0xffffff;
      boolean last = (bytes[offset] & 0x80) != 0;
      offset += 4 + size;
      if (last) return offset;
    }
  }

  private static int flacPadding(byte[] bytes) {
    int offset = 4;
    while (true) {
      int size = (int) Binary.u32be(bytes, offset) & 0xffffff;
      if ((bytes[offset] & 0x7f) == 1) return size;
      if ((bytes[offset] & 0x80) != 0) return -1;
      offset += 4 + size;
    }
  }

  private static int flacPaddingBlocks(byte[] bytes) {
    int count = 0, offset = 4;
    while (offset < flacAudioOffset(bytes)) {
      int size = (int) Binary.u32be(bytes, offset) & 0xffffff;
      if ((bytes[offset] & 0x7f) == 1) count++;
      offset += 4 + size;
    }
    return count;
  }

  private static byte[] join(byte[]... arrays) {
    var out = new ByteArrayOutputStream();
    for (byte[] array : arrays) out.writeBytes(array);
    return out.toByteArray();
  }

  private static boolean contains(byte[] bytes, byte[] expected) {
    outer:
    for (int i = 0; i <= bytes.length - expected.length; i++) {
      for (int j = 0; j < expected.length; j++) if (bytes[i + j] != expected[j]) continue outer;
      return true;
    }
    return false;
  }
}
