package cz.skyba.taglib.internal;

import cz.skyba.taglib.*;
import cz.skyba.taglib.internal.codec.FlacCodec;
import cz.skyba.taglib.internal.codec.MpegCodec;
import cz.skyba.taglib.internal.id3.Id3v2;
import cz.skyba.taglib.internal.xiph.XiphComment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class MetadataPaddingTest {
  @TempDir
  Path directory;


  @Test
  void id3ExactFitAndExhaustedCapacityReserve1024BytesAndExcessPaddingIsCapped() throws Exception {
    byte[] title = id3Frame(4, "TIT2", Binary.bytes("\u0003same"));
    byte[] exact = id3(4, 0, title);
    byte[] rendered = Id3v2.render(properties("same"), exact, null, 200_000);
    assertEquals(exact.length + 1024, rendered.length);
    assertEquals(1024, id3Padding(rendered));
    rendered = Id3v2.render(properties("grown".repeat(1000)), exact, null, 200_000);
    assertEquals(1024, id3Padding(rendered));
    rendered = Id3v2.render(properties("same"), id3(4, 8000, title), null, 200_000);
    assertEquals(1024, id3Padding(rendered));
    // Native uses one percent of the containing file, bounded by a 1 MiB ceiling.
    rendered = Id3v2.render(properties("same"), id3(4, 2000, title), null, 200_000);
    assertEquals(2000, id3Padding(rendered));
    rendered = Id3v2.render(properties("same"), id3(4, 1024 * 1024, title), null, 200_000_000);
    assertEquals(1024 * 1024, id3Padding(rendered));
    rendered = Id3v2.render(properties("same"), id3(4, 1024 * 1024 + 1, title), null, 200_000_000);
    assertEquals(1024, id3Padding(rendered));
  }

  @Test
  void flacAndPhysicalEditorConsolidatePaddingAndKeepAudioOffsetWhileEditsFit() throws Exception {
    byte[] audio = new byte[200_000];
    new Random(42).nextBytes(audio);
    byte[] unknown = flacBlock(2, new byte[]{4, 3, 2, 1});
    byte[] picture =
      flacBlock(
        6,
        XiphComment.renderPicture(new Picture(3, "image/png", "cover", new byte[]{9, 8, 7})));
    for (boolean physical : new boolean[]{false, true}) {
      Path path = directory.resolve("capacity.flac");
      byte[] header =
        join(
          Binary.bytes("fLaC"),
          flacBlock(0, streamInfo()),
          flacBlock(1, new byte[512]),
          unknown,
          flacBlock(4, XiphComment.empty().render(properties("before"), false)),
          picture,
          flacBlock(0x81, new byte[1536]));
      Files.write(path, join(header, audio));
      for (String title : List.of("Longer title ".repeat(20), "short")) {
        changeTitle(path, title, physical);
        byte[] saved = Files.readAllBytes(path);
        assertEquals(header.length, flacAudioOffset(saved));
        assertEquals(header.length + audio.length, saved.length);
        assertArrayEquals(audio, Arrays.copyOfRange(saved, header.length, saved.length));
        assertTrue(contains(saved, unknown));
        assertTrue(contains(saved, picture));
        assertEquals(1, flacPaddingBlocks(saved));
        assertEquals(title, TagLib.read(path, ReadOptions.TAGS_ONLY).properties().first("TITLE"));
      }
      changeTitle(path, "large".repeat(2000), physical);
      byte[] saved = Files.readAllBytes(path);
      assertEquals(4096, flacPadding(saved), "Exhausted capacity should get a reusable reserve");
      assertArrayEquals(audio, Arrays.copyOfRange(saved, flacAudioOffset(saved), saved.length));
      changeTitle(path, "small", physical);
      saved = Files.readAllBytes(path);
      assertEquals(
        4096, flacPadding(saved), "Excess reserve should return to the native minimum");
      assertArrayEquals(audio, Arrays.copyOfRange(saved, flacAudioOffset(saved), saved.length));
    }
  }

  @Test
  void flacWithoutPaddingOrWithAnExactFitGetsFresh4096ByteReserve() throws Exception {
    for (boolean physical : new boolean[]{false, true})
      for (int padding : new int[]{-1, 0}) {
        Path path = directory.resolve("exact.flac");
        byte[] comment = XiphComment.empty().render(properties("same"), false);
        byte[] header =
          join(
            Binary.bytes("fLaC"),
            flacBlock(0, streamInfo()),
            flacBlock(padding < 0 ? 0x84 : 4, comment),
            padding < 0 ? new byte[0] : flacBlock(0x81, new byte[padding]));
        byte[] audio = new byte[]{1, 2, 3, 4, 5};
        Files.write(path, join(header, audio));
        changeTitle(path, "same", physical);
        byte[] saved = Files.readAllBytes(path);
        assertEquals(4096, flacPadding(saved));
        assertArrayEquals(audio, Arrays.copyOfRange(saved, flacAudioOffset(saved), saved.length));
      }
  }

  private static void changeTitle(Path path, String title, boolean physical)
    throws Exception {
    if (physical) {
      var document = MetadataDocument.open(path);
      var entry =
        document.entries().stream()
          .filter(e -> e.standardKey().equals("TITLE") && !e.block().equals("ID3v1"))
          .findFirst()
          .orElseThrow();
      document.apply(
        List.of(new MetadataChange("update", entry.id(), entry.key(), List.of(title), false)));
      document.save();
    } else if (path.toString().endsWith("flac"))
      new FlacCodec().write(path, properties(title));
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
