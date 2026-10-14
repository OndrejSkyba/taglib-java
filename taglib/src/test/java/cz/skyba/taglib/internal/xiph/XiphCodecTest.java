package cz.skyba.taglib.internal.xiph;

import cz.skyba.taglib.*;
import cz.skyba.taglib.internal.Binary;
import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.codec.FlacCodec;
import cz.skyba.taglib.internal.codec.OggCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class XiphCodecTest {
  @TempDir
  Path temp;

  @Test
  void xiphMultivaluesUnicodeAndPicturesRoundTrip() throws Exception {
    var properties =
      PropertyMap.builder()
        .add("artist", "Björk")
        .add("ARTIST", "坂本龍一")
        .put("COMMENT", "equals=allowed\u0000in values")
        .build();
    var picture = new Picture(3, "image/png", "Front cover", new byte[]{1, 2, 3}, 30, 40, 24, 0);
    var comment = new XiphComment("encoder ✓", properties, List.of(picture));
    XiphComment parsed = XiphComment.parse(comment.render(properties, true));
    assertEquals("equals=allowed\u0000in values", comment.properties().first("COMMENT"));
    assertEquals(properties.toBuilder().put("COMMENT", "equals=allowed").build(), parsed.properties());
    assertEquals(comment.vendor(), parsed.vendor());
    assertEquals(List.of(picture), parsed.pictures());
  }

  @Test
  void malformedUnsignedLengthsAreBounded() throws Exception {
    assertTrue(XiphComment.parse(new byte[]{-1, -1, -1, -1, 0, 0, 0, 0}).properties().isEmpty());
    byte[] picture = new byte[32];
    Binary.put32be(picture, 4, 0xffffffffL);
    assertThrows(IOException.class, () -> XiphComment.parsePicture(picture));
    assertThrows(
      IOException.class,
      () -> XiphComment.empty().render(PropertyMap.builder().put("BAD=KEY", "x").build(), false));
  }

  @Test
  void flacPreservesUnknownBlocksPicturesAndAudioOnGrowth() throws Exception {
    byte[] info = new byte[34];
    Binary.put64be(info, 10, ((long) 44_100 << 44) | (1L << 41) | (15L << 36) | 88_200);
    byte[] audio = new byte[10_000];
    new Random(123).nextBytes(audio);
    var picture = new Picture(3, "image/jpeg", "art", new byte[]{9, 8, 7});
    var original = PropertyMap.builder().put("TITLE", "Before").build();
    byte[] unknown = {4, 3, 2, 1};
    var out = new ByteArrayOutputStream();
    out.writeBytes(Binary.bytes("fLaC"));
    out.writeBytes(block(0, info));
    out.writeBytes(block(2, unknown));
    out.writeBytes(block(6, XiphComment.renderPicture(picture)));
    out.writeBytes(block(0x84, XiphComment.empty().render(original, false)));
    out.writeBytes(audio);
    Path file = temp.resolve("test.flac");
    Files.write(file, out.toByteArray());
    var codec = new FlacCodec();
    var before = codec.read(file, ReadOptions.DEFAULT);
    assertEquals(2000, before.audioProperties().lengthInMilliseconds());
    assertEquals(40, before.audioProperties().bitrate());
    assertEquals(44_100, before.audioProperties().sampleRate());
    assertEquals(2, before.audioProperties().channels());
    assertEquals(16, before.audioProperties().bitsPerSample());
    assertEquals(List.of(picture), before.pictures());
    var replacement = PropertyMap.builder().put("TITLE", "x".repeat(200_000)).build();
    codec.write(file, replacement);
    var after = codec.read(file, ReadOptions.DEFAULT);
    assertEquals(replacement, after.properties());
    assertEquals(before.audioProperties(), after.audioProperties());
    assertEquals(before.pictures(), after.pictures());
    byte[] bytes = Files.readAllBytes(file);
    assertArrayEquals(audio, Arrays.copyOfRange(bytes, bytes.length - audio.length, bytes.length));
    assertArrayEquals(unknown, Arrays.copyOfRange(bytes, 46, 50));
    var newPicture = new Picture(4, "image/png", "New cover", new byte[80_000], 80, 60, 24, 0);
    codec.write(file, replacement, List.of(newPicture));
    assertEquals(List.of(newPicture), codec.read(file, ReadOptions.DEFAULT).pictures());
    assertEquals(replacement, codec.read(file, ReadOptions.DEFAULT).properties());
    codec.write(file, replacement, List.of());
    assertTrue(codec.read(file, ReadOptions.DEFAULT).pictures().isEmpty());
    bytes = Files.readAllBytes(file);
    assertArrayEquals(audio, Arrays.copyOfRange(bytes, bytes.length - audio.length, bytes.length));
  }

  @Test
  void oggRepagingMatchesNativeSpanAndForeignEosQuirks() throws Exception {
    oggRepaging();
  }

  private void oggRepaging() throws Exception {
    Path file = temp.resolve("test.opus");
    byte[] id = new byte[19];
    System.arraycopy(Binary.bytes("OpusHead"), 0, id, 0, 8);
    id[8] = 1;
    id[9] = 2;
    Binary.put16le(id, 10, 312);
    Binary.put32le(id, 12, 44_100);
    byte[] tags =
      join(
        Binary.bytes("OpusTags"),
        XiphComment.empty()
          .render(PropertyMap.builder().put("TITLE", "before").build(), false));
    byte[] audio = new byte[60_000];
    new Random(24).nextBytes(audio);
    byte[] foreign = packetPage(789, 0, 0, 6, Binary.bytes("foreign packet untouched"));
    Files.write(
      file,
      join(
        packetPage(123, 0, 0, 2, id),
        packetPage(123, 1, 0, 0, tags),
        foreign,
        packetPage(123, 2, 16_000, 0, audio),
        packetPage(123, 3, 32_000, 0, audio),
        packetPage(123, 4, 48_312, 4, audio)));
    var codec = new OggCodec();
    assertEquals(
      1000, codec.read(file, ReadOptions.DEFAULT).audioProperties().lengthInMilliseconds());
    Object originalKey = Files.readAttributes(file, BasicFileAttributes.class).fileKey();
    byte[] original = Files.readAllBytes(file);
    Path link = temp.resolve("linked.opus");
    Files.createLink(link, file);
    writeOgg(file, PropertyMap.builder().put("TITLE", "modest growth ✓").build(), null);
    checkPages(file, foreign, audio, true);
    checkIdentity(file, link, originalKey);
    var replacement = PropertyMap.builder().put("TITLE", "ø".repeat(70_000)).build();
    writeOgg(file, replacement, null);
    checkIdentity(file, link, originalKey);
    assertEquals(replacement, codec.read(file, ReadOptions.DEFAULT).properties());
    assertEquals(
      1000, codec.read(file, ReadOptions.DEFAULT).audioProperties().lengthInMilliseconds());
    // The native renumbering stops at this foreign EOS. Selected audio sequences remain 2..4.
    // Move that foreign page inside a continued comment span; native replacement removes it.
    interleaveForeign(file, foreign);
    checkPages(file, foreign, audio, true);
    var cover = new Picture(3, "image/png", "Opus cover", new byte[90_000], 32, 32, 24, 0);
    writeOgg(file, replacement, List.of(cover));
    checkIdentity(file, link, originalKey);
    assertEquals(List.of(cover), codec.read(file, ReadOptions.DEFAULT).pictures());
    writeOgg(file, replacement, null);
    checkIdentity(file, link, originalKey);
    assertEquals(List.of(cover), codec.read(file, ReadOptions.DEFAULT).pictures());
    writeOgg(file, replacement, List.of());
    checkIdentity(file, link, originalKey);
    assertTrue(codec.read(file, ReadOptions.DEFAULT).pictures().isEmpty());
    checkPages(file, foreign, audio, false);
    writeOgg(file, PropertyMap.builder().put("TITLE", "small").build(), null);
    checkIdentity(file, link, originalKey);
    assertEquals("small", codec.read(file, ReadOptions.DEFAULT).properties().first("TITLE"));
    checkPages(file, foreign, audio, false);
  }

  private static void writeOgg(
    Path file, PropertyMap properties, List<Picture> pictures)
    throws IOException {
    if (pictures == null) TagLib.write(file, properties);
    else TagLib.write(file, properties, pictures);
  }

  private static void checkIdentity(
    Path file, Path link, Object key) throws IOException {
    Object currentKey = Files.readAttributes(file, BasicFileAttributes.class).fileKey();
    assertEquals(key, currentKey);
    assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(link));
  }

  @Test
  void defaultRepagingSavesThenStopsAtMalformedTailLikeNative() throws Exception {
    Path file = temp.resolve("malformed-tail.opus");
    byte[] id = new byte[19];
    System.arraycopy(Binary.bytes("OpusHead"), 0, id, 0, 8);
    id[8] = 1;
    id[9] = 2;
    byte[] tags =
      join(Binary.bytes("OpusTags"), XiphComment.empty().render(PropertyMap.EMPTY, false));
    byte[] original =
      join(
        packetPage(123, 0, 0, 2, id),
        packetPage(123, 1, 0, 0, tags),
        Binary.bytes("truncated tail"));
    Files.write(file, original);
    Object key = Files.readAttributes(file, BasicFileAttributes.class).fileKey();
    String title = "x".repeat(100_000);
    TagLib.write(file, PropertyMap.builder().put("TITLE", title).build());
    assertEquals(key, Files.readAttributes(file, BasicFileAttributes.class).fileKey());
    byte[] saved = Files.readAllBytes(file);
    byte[] trailer = Binary.bytes("truncated tail");
    assertArrayEquals(trailer, Arrays.copyOfRange(saved, saved.length - trailer.length, saved.length));
    assertFalse(Arrays.equals(original, saved));
    assertEquals(title, TagLib.read(file, ReadOptions.TAGS_ONLY).properties().first("TITLE"));
  }

  @Test
  void defaultRepagingStopsRenumberingAtEndOfSelectedStream() throws Exception {
    Path file = temp.resolve("chained.opus");
    byte[] id = new byte[19];
    System.arraycopy(Binary.bytes("OpusHead"), 0, id, 0, 8);
    id[8] = 1;
    id[9] = 2;
    byte[] tags =
      join(Binary.bytes("OpusTags"), XiphComment.empty().render(PropertyMap.EMPTY, false));
    byte[] chain = packetPage(123, 0, 0, 6, Binary.bytes("later same-serial stream"));
    Files.write(
      file,
      join(
        packetPage(123, 0, 0, 2, id),
        packetPage(123, 1, 0, 0, tags),
        packetPage(123, 2, 1000, 4, new byte[]{1, 2, 3}),
        chain));
    Path link = temp.resolve("chained-link.opus");
    Files.createLink(link, file);
    for (String title : List.of("x".repeat(200_000), "small")) {
      // Exercise the physical editor's no-option save as well as TagLib.write above.
      var document = MetadataDocument.open(file);
      var existing =
        document.entries().stream().filter(e -> e.standardKey().equals("TITLE")).findFirst();
      document.apply(
        List.of(
          existing.isEmpty()
            ? new MetadataChange("add", null, "TITLE", List.of(title), false)
            : new MetadataChange(
            "update", existing.get().id(), "TITLE", List.of(title), false)));
      document.save();
      byte[] saved = Files.readAllBytes(file);
      assertArrayEquals(
        chain, Arrays.copyOfRange(saved, saved.length - chain.length, saved.length));
      assertArrayEquals(saved, Files.readAllBytes(link));
    }
  }

  private static void interleaveForeign(Path path, byte[] foreign) throws IOException {
    var pages = new ArrayList<byte[]>();
    try (var file = new BinaryFile(path)) {
      long pos = 0;
      while (pos < file.size()) {
        var page = OggPages.readPage(file, pos);
        if (page.serial() == 123) {
          pages.add(file.read(pos, page.size()));
        }
        pos += page.size();
      }
    }
    pages.add(2, foreign);
    Files.write(path, join(pages.toArray(byte[][]::new)));
  }

  @Test
  void oggMetadataLimitRejectsBeforeUnboundedAllocation() throws Exception {
    Path file = temp.resolve("huge.ogg");
    byte[] id = new byte[30];
    System.arraycopy(Binary.bytes("\u0001vorbis"), 0, id, 0, 7);
    Files.write(file, join(packetPage(1, 0, 0, 2, id), packetPage(1, 1, 0, 0, new byte[60_000])));
    assertThrows(
      IOException.class,
      () -> new OggCodec().read(file, new ReadOptions(false, ReadStyle.AVERAGE, 1024)));
  }

  private void checkPages(Path path, byte[] foreign, byte[] audio, boolean foreignExpected) throws Exception {
    try (var file = new BinaryFile(path)) {
      long pos = 0, metadataSequence = 0, audioSequence = -1;
      int audioCount = 0;
      boolean seenForeign = false;
      while (pos < file.size()) {
        var page = OggPages.readPage(file, pos);
        byte[] raw = file.read(pos, page.size());
        assertTrue(OggPages.validCrc(raw));
        if (page.serial() == 123) {
          if (page.granule() > 0) {
            assertArrayEquals(audio, file.read(page.payloadOffset(), page.payloadSize()));
            if (seenForeign) assertEquals(2 + audioCount, page.sequence());
            if (audioSequence >= 0) assertEquals(audioSequence + 1, page.sequence());
            audioSequence = page.sequence();
            audioCount++;
          } else {
            assertEquals(metadataSequence++, page.sequence());
          }
          if ((page.flags() & 4) != 0) {
            assertEquals(48_312, page.granule());
          }
        } else {
          byte[] expected = foreign.clone();
          Binary.put32le(expected, 18, page.sequence());
          Binary.put32le(expected, 22, Integer.toUnsignedLong(OggPages.crc(expected)));
          assertArrayEquals(expected, raw);
          seenForeign = true;
        }
        pos += page.size();
      }
      assertEquals(foreignExpected, seenForeign);
      assertEquals(3, audioCount);
    }
  }

  private static byte[] block(int type, byte[] data) {
    byte[] result = new byte[4 + data.length];
    Binary.put32be(result, 0, data.length);
    result[0] = (byte) type;
    System.arraycopy(data, 0, result, 4, data.length);
    return result;
  }

  private static byte[] packetPage(
    long serial, long sequence, long granule, int flags, byte[] packet) {
    int[] laces = new int[packet.length / 255 + 1];
    Arrays.fill(laces, 255);
    laces[laces.length - 1] = packet.length % 255;
    return OggPages.page(serial, sequence, granule, flags, laces, packet);
  }

  private static byte[] join(byte[]... arrays) {
    var out = new ByteArrayOutputStream();
    for (byte[] array : arrays) {
      out.writeBytes(array);
    }
    return out.toByteArray();
  }
}
