package cz.skyba.taglib;

import cz.skyba.taglib.internal.Binary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class Mp4Test {
  @TempDir
  Path dir;

  static byte[] box(String name, byte[]... payload) throws Exception {
    var out = new ByteArrayOutputStream();
    for (byte[] p : payload) out.write(p);
    byte[] b = new byte[out.size() + 8];
    Binary.put32be(b, 0, b.length);
    System.arraycopy(Binary.bytes(name), 0, b, 4, 4);
    System.arraycopy(out.toByteArray(), 0, b, 8, out.size());
    return b;
  }

  static byte[] join(byte[]... parts) throws Exception {
    var out = new ByteArrayOutputStream();
    for (byte[] p : parts) out.write(p);
    return out.toByteArray();
  }

  @Test
  void paddedPropertyWritesRetainFileSizeAndEncodedMedia() throws Exception {
    Path path = dir.resolve("padded.m4a");
    byte[] media = new byte[8000];
    new Random(22).nextBytes(media);
    byte[] handler = new byte[25];
    System.arraycopy(Binary.bytes("mdirappl"), 0, handler, 8, 8);
    Files.write(
      path,
      join(
        box("ftyp", Binary.bytes("M4A \0\0\0\0")),
        box(
          "moov",
          box(
            "udta",
            box(
              "meta",
              new byte[4],
              box("hdlr", handler),
              box("free", new byte[128]),
              box("ilst"),
              box("free", new byte[2048])))),
        box("mdat", media)));
    long length = Files.size(path);
    Object identity =
      Files.readAttributes(path, BasicFileAttributes.class).fileKey();
    for (String title : List.of("Longer title — 日本語", "x", "Again")) {
      TagLib.write(path, PropertyMap.builder().put("TITLE", title).build());
      assertEquals(title, TagLib.read(path).properties().first("TITLE"));
      assertEquals(length, Files.size(path));
      assertEquals(
        identity,
        Files.readAttributes(path, BasicFileAttributes.class).fileKey());
      byte[] after = Files.readAllBytes(path);
      assertArrayEquals(
        media, Arrays.copyOfRange(after, after.length - media.length, after.length));
    }
  }

  @Test
  void paddedWritesPreserveEveryByteOutsideTheMetadataSpanAndOffsetTables() throws Exception {
    byte[] metadata = paddedMetadata(true), stco = new byte[8 + 4 * 8192], co64 = new byte[24];
    Binary.put32be(stco, 4, 8192);
    Binary.put32be(co64, 4, 2);
    byte[] opaque = new byte[1024 * 1024], media = new byte[4096];
    new Random(61).nextBytes(opaque);
    new Random(62).nextBytes(media);
    byte[] track =
      box("trak", box("mdia", box("minf", box("stbl", box("stco", stco), box("co64", co64)))));
    byte[] moov = box("moov", track, box("Xtra", opaque), metadata);
    long mediaOffset = moov.length + 8L;
    for (int i = 0; i < 8192; i++) Binary.put32be(stco, 8 + 4 * i, mediaOffset + i % media.length);
    Binary.put64be(co64, 8, mediaOffset);
    Binary.put64be(
      co64, 16, Long.MIN_VALUE); // Existing delta-zero behavior leaves this value untouched.
    track =
      box("trak", box("mdia", box("minf", box("stbl", box("stco", stco), box("co64", co64)))));
    moov = box("moov", track, box("Xtra", opaque), metadata);
    byte[] original = join(moov, box("mdat", media));
    int spanStart = index(original, Binary.bytes("free")) - 4;
    int spanEnd = moov.length;
    Path path = dir.resolve("focused.m4a");
    Files.write(path, original);
    for (String title : List.of("An expanded title — 日本語", "x")) {
      TagLib.write(
        path,
        PropertyMap.builder().put("TITLE", title).build(),
        List.of(new Picture(3, "image/png", "", new byte[]{1, 2, 3})));
      byte[] written = Files.readAllBytes(path);
      assertEquals(original.length, written.length);
      assertArrayEquals(
        Arrays.copyOfRange(original, 0, spanStart), Arrays.copyOfRange(written, 0, spanStart));
      assertArrayEquals(
        Arrays.copyOfRange(original, spanEnd, original.length),
        Arrays.copyOfRange(written, spanEnd, written.length));
      assertEquals(title, TagLib.read(path).properties().first("TITLE"));
      assertArrayEquals(media, Arrays.copyOfRange(written, (int) mediaOffset, written.length));
      assertArrayEquals(new byte[]{1, 2, 3}, TagLib.read(path).pictures().getFirst().data());
    }
  }


  private static byte[] paddedMetadata(boolean handler) throws Exception {
    return box(
      "udta",
      box(
        "meta",
        new byte[4],
        handler ? box("hdlr", new byte[25]) : new byte[0],
        box("free", new byte[13]),
        box("ilst"),
        box("free", new byte[1024])));
  }

  @Test
  void metadataGrowthRelocatesChunksAndPreservesMdatAndOpaqueAtoms() throws Exception {
    byte[] ftyp = box("ftyp", Binary.bytes("M4A \0\0\0\0")),
      unknown = box("Xtra", new byte[]{7, 8, 9}),
      stcoData = new byte[12];
    Binary.put32be(stcoData, 4, 1);
    byte[] moov =
      box(
        "moov",
        box("trak", box("mdia", box("minf", box("stbl", box("stco", stcoData))))),
        unknown);
    Binary.put32be(stcoData, 8, ftyp.length + moov.length + 8);
    moov =
      box(
        "moov",
        box("trak", box("mdia", box("minf", box("stbl", box("stco", stcoData))))),
        unknown);
    byte[] audio = new byte[4096];
    new Random(42).nextBytes(audio);
    Path p = dir.resolve("sample.m4a");
    Files.write(p, join(ftyp, moov, box("mdat", audio)));
    var properties =
      PropertyMap.builder()
        .put("TITLE", "日本語 " + "long ".repeat(1024))
        .put("ARTIST", List.of("A", "B"))
        .put("TRACKNUMBER", "3/8")
        .put("MUSICBRAINZ_TRACKID", "some-id")
        .build();
    TagLib.write(p, properties);
    assertEquals(properties, TagLib.read(p).properties());
    byte[] all = Files.readAllBytes(p);
    int chunk = index(all, Binary.bytes("stco"));
    long offset = Binary.u32be(all, chunk + 12);
    assertArrayEquals(audio, Arrays.copyOfRange(all, (int) offset, (int) offset + audio.length));
    assertTrue(index(all, unknown) > 0);
    TagLib.write(p, PropertyMap.EMPTY);
    assertTrue(TagLib.read(p).properties().isEmpty());
    all = Files.readAllBytes(p);
    chunk = index(all, Binary.bytes("stco"));
    offset = Binary.u32be(all, chunk + 12);
    assertArrayEquals(audio, Arrays.copyOfRange(all, (int) offset, (int) offset + audio.length));
  }

  @Test
  void hostileNestedAtomLengthIsRejectedWithoutAllocation() throws Exception {
    byte[] bad = box("moov", new byte[]{127, -1, -1, -1, 't', 'r', 'a', 'k'});
    Path p = dir.resolve("bad.mp4");
    Files.write(p, bad);
    assertThrows(TagLibException.class, () -> TagLib.read(p));
  }


  @Test
  void excessiveNestedAtomCountIsRejectedWithinSmallByteBudget() throws Exception {
    var items = new ByteArrayOutputStream();
    for (int i = 0; i < 100_001; i++) items.write(box("free"));
    Path p = dir.resolve("many.mp4");
    Files.write(p, box("moov", items.toByteArray()));
    assertThrows(TagLibException.class, () -> TagLib.read(p));
  }


  private static byte[] legacyExtended(String name, byte[] payload) throws Exception {
    byte[] out = new byte[16 + payload.length];
    Binary.put32be(out, 0, 1);
    System.arraycopy(Binary.bytes(name), 0, out, 4, 4);
    Binary.put64be(out, 8, out.length - 8);
    System.arraycopy(payload, 0, out, 16, payload.length);
    return out;
  }


  static int index(byte[] data, byte[] needle) {
    outer:
    for (int i = 0; i <= data.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) if (data[i + j] != needle[j]) continue outer;
      return i;
    }
    return -1;
  }
}
