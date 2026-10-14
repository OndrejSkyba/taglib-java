package cz.skyba.taglib.internal.codec;

import cz.skyba.taglib.*;
import cz.skyba.taglib.internal.Binary;
import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.Codec;
import cz.skyba.taglib.internal.id3.Id3v2;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RiffDsdCodecTest {
  @TempDir
  Path temp;

  @Test
  void wavAndRf64MetadataUpdatesPreserveAudioAndUnknownChunks() throws Exception {
    for (String magic : List.of("RIFF", "RF64", "BW64")) {
      byte[] fmt = new byte[16];
      Binary.put16le(fmt, 0, 1);
      Binary.put16le(fmt, 2, 2);
      Binary.put32le(fmt, 4, 44_100);
      Binary.put32le(fmt, 8, 176_400);
      Binary.put16le(fmt, 12, 4);
      Binary.put16le(fmt, 14, 16);
      byte[] audio = new byte[176_400];
      new Random(22).nextBytes(audio);
      byte[] unknown = {1, 3, 5, 7, 9};
      byte[] body =
        join(
          chunk32("fmt ", fmt, false),
          chunk32("JUNK", unknown, false),
          chunk32(
            "LIST",
            join(Binary.bytes("INFO"), chunk32("INAM", Binary.bytes("old\0"), false)),
            false),
          chunk32("data", audio, false));
      if (!magic.equals("RIFF")) {
        byte[] ds64 = new byte[28];
        Binary.put64le(ds64, 0, body.length + 4 + 36);
        Binary.put64le(ds64, 8, audio.length);
        Binary.put64le(ds64, 16, 44_100);
        Binary.put32le(body, body.length - audio.length - 4, 0xffffffffL);
        body = join(chunk32("ds64", ds64, false), body);
      }
      byte[] header = join(Binary.bytes(magic), new byte[4], Binary.bytes("WAVE"));
      Binary.put32le(header, 4, magic.equals("RIFF") ? body.length + 4 : 0xffffffffL);
      Path path = temp.resolve(magic + ".wav");
      Files.write(path, join(header, body));
      var codec = new RiffCodec();
      AudioMetadata before = codec.read(path, ReadOptions.DEFAULT);
      assertEquals("old", before.properties().first("TITLE"));
      assertEquals(1000, before.audioProperties().lengthInMilliseconds());
      assertEquals(1411, before.audioProperties().bitrate());
      var properties =
        PropertyMap.builder()
          .put("TITLE", "新しいタイトル")
          .add("ARTIST", "One")
          .add("ARTIST", "Two")
          .build();
      codec.write(path, properties);
      assertEquals(properties, codec.read(path, ReadOptions.DEFAULT).properties());
      assertEquals(
        before.audioProperties(), codec.read(path, ReadOptions.DEFAULT).audioProperties());
      try (var file = new BinaryFile(path)) {
        assertEquals(magic.equals("RIFF") ? file.size() - 8 : 0xffffffffL, file.u32le(4));
        if (!magic.equals("RIFF")) assertEquals(file.size() - 8, file.u64le(20));
      }
      byte[] after = Files.readAllBytes(path);
      assertTrue(indexOf(after, audio) >= 0);
      assertTrue(indexOf(after, chunk32("JUNK", unknown, false)) >= 0);
      artworkRoundTrip(codec, path, properties);
    }
  }

  @Test
  void aiffExtendedSampleRateAndTagWrite() throws Exception {
    byte[] comm = new byte[18];
    Binary.put16be(comm, 0, 2);
    Binary.put32be(comm, 2, 44_100);
    Binary.put16be(comm, 6, 16);
    comm[8] = 0x40;
    comm[9] = 0x0e;
    comm[10] = (byte) 0xac;
    comm[11] = 0x44;
    byte[] body = join(chunk32("COMM", comm, true), chunk32("SSND", new byte[176_408], true));
    byte[] header = join(Binary.bytes("FORM"), new byte[4], Binary.bytes("AIFF"));
    Binary.put32be(header, 4, body.length + 4);
    Path path = temp.resolve("test.aiff");
    Files.write(path, join(header, body));
    var codec = new RiffCodec();
    var before = codec.read(path, ReadOptions.DEFAULT);
    assertEquals(44_100, before.audioProperties().sampleRate());
    assertEquals(1000, before.audioProperties().lengthInMilliseconds());
    codec.write(
      path, PropertyMap.builder().put("TITLE", "AIFF test").build());
    assertEquals("AIFF test", codec.read(path, ReadOptions.DEFAULT).properties().first("TITLE"));
    assertEquals(before.audioProperties(), codec.read(path, ReadOptions.DEFAULT).audioProperties());
    try (var file = new BinaryFile(path)) {
      assertEquals(file.size() - 8, file.u32be(4));
    }
    artworkRoundTrip(codec, path, codec.read(path, ReadOptions.DEFAULT).properties());
  }

  @Test
  void artworkOnlyId3KeepsPropertyPrecedenceButCommonFieldsFallBackToInfo() throws Exception {
    var picture = new Picture(3, "image/png", "front", new byte[]{1, 2, 3});
    byte[] id3 = Id3v2.render(PropertyMap.builder().build(), new byte[0], List.of(picture));
    byte[] body =
      join(
        chunk32("ID3 ", id3, false),
        chunk32(
          "LIST",
          join(
            Binary.bytes("INFO"), chunk32("INAM", Binary.bytes("Fallback title\0"), false)),
          false));
    byte[] header = join(Binary.bytes("RIFF"), new byte[4], Binary.bytes("WAVE"));
    Binary.put32le(header, 4, body.length + 4);
    Path path = temp.resolve("artwork-only.wav");
    Files.write(path, join(header, body));
    AudioMetadata result = new RiffCodec().read(path, ReadOptions.DEFAULT);
    assertTrue(result.properties().isEmpty());
    assertEquals("Fallback title", result.tag().title());
    assertEquals(List.of(picture), result.pictures());
  }

  @Test
  void dsfMetadataPointerAndFileSizeStayConsistent() throws Exception {
    byte[] header = new byte[80];
    System.arraycopy(Binary.bytes("DSD "), 0, header, 0, 4);
    Binary.put64le(header, 4, 28);
    System.arraycopy(Binary.bytes("fmt "), 0, header, 28, 4);
    Binary.put64le(header, 32, 52);
    Binary.put32le(header, 40, 1);
    Binary.put32le(header, 52, 2);
    Binary.put32le(header, 56, 2_822_400);
    Binary.put32le(header, 60, 1);
    Binary.put64le(header, 64, 2_822_400);
    Binary.put32le(header, 72, 4096);
    byte[] audio = new byte[8192];
    new Random(99).nextBytes(audio);
    byte[] dataHeader = join(Binary.bytes("data"), new byte[8]);
    Binary.put64le(dataHeader, 4, audio.length + 12);
    byte[] body = join(dataHeader, audio);
    Binary.put64le(header, 12, header.length + body.length);
    Path path = temp.resolve("test.dsf");
    Files.write(path, join(header, body));
    var codec = new DsdCodec();
    var before = codec.read(path, ReadOptions.DEFAULT);
    assertEquals(1000, before.audioProperties().lengthInMilliseconds());
    assertEquals(5645, before.audioProperties().bitrate());
    codec.write(
      path, PropertyMap.builder().put("TITLE", "DSF metadata").build());
    assertEquals("DSF metadata", codec.read(path, ReadOptions.DEFAULT).properties().first("TITLE"));
    assertEquals(before.audioProperties(), codec.read(path, ReadOptions.DEFAULT).audioProperties());
    try (var file = new BinaryFile(path)) {
      assertEquals(file.size(), file.u64le(12));
      assertEquals(80 + body.length, file.u64le(20));
      assertArrayEquals(audio, file.read(92, audio.length));
    }
    artworkRoundTrip(codec, path, codec.read(path, ReadOptions.DEFAULT).properties());
  }

  @Test
  void dsdiffNestedId3UpdatesParentAndRootSizes() throws Exception {
    byte[] rate = new byte[4];
    Binary.put32be(rate, 0, 2_822_400);
    byte[] channels = new byte[2];
    Binary.put16be(channels, 0, 2);
    byte[] oldTag = Id3v2.render(PropertyMap.builder().put("TITLE", "before").build(), new byte[0]);
    byte[] prop =
      join(
        Binary.bytes("SND "),
        chunk64("FS  ", rate),
        chunk64("CHNL", channels),
        chunk64("ID3 ", oldTag));
    byte[] audio = new byte[705_600];
    new Random(1).nextBytes(audio);
    byte[] body = join(chunk64("PROP", prop), chunk64("DSD ", audio));
    byte[] header = join(Binary.bytes("FRM8"), new byte[8], Binary.bytes("DSD "));
    Binary.put64be(header, 4, body.length + 4);
    Path path = temp.resolve("test.dff");
    Files.write(path, join(header, body));
    var codec = new DsdCodec();
    var before = codec.read(path, ReadOptions.DEFAULT);
    assertEquals("before", before.properties().first("TITLE"));
    assertEquals(1000, before.audioProperties().lengthInMilliseconds());
    assertEquals(5644, before.audioProperties().bitrate());
    codec.write(
      path, PropertyMap.builder().put("TITLE", "D".repeat(50_000)).build());
    assertEquals(
      "D".repeat(50_000), codec.read(path, ReadOptions.DEFAULT).properties().first("TITLE"));
    assertEquals(before.audioProperties(), codec.read(path, ReadOptions.DEFAULT).audioProperties());
    try (var file = new BinaryFile(path)) {
      assertEquals(file.size() - 12, file.u64be(4));
      assertArrayEquals(audio, file.read(file.size() - audio.length, audio.length));
    }
    artworkRoundTrip(codec, path, codec.read(path, ReadOptions.DEFAULT).properties());
  }

  @Test
  void genericWritesPadAnUnpaddedOddFinalAudioChunk() throws Exception {
    for (boolean aiff : new boolean[]{false, true}) {
      byte[] audio = {0, 0, 0, 0, 0, 0, 0, 0, 7};
      byte[] padded = chunk32(aiff ? "SSND" : "data", audio, aiff);
      byte[] body = Arrays.copyOf(padded, padded.length - 1);
      byte[] header =
        join(
          Binary.bytes(aiff ? "FORM" : "RIFF"),
          new byte[4],
          Binary.bytes(aiff ? "AIFF" : "WAVE"));
      if (aiff) Binary.put32be(header, 4, body.length + 4);
      else Binary.put32le(header, 4, body.length + 4);
      Path path = temp.resolve(aiff ? "odd.aiff" : "odd.wav");
      Files.write(path, join(header, body));
      var properties = PropertyMap.builder().put("TITLE", "Aligned").build();
      TagLib.write(
        path, properties, List.of(new Picture(3, "image/png", "Cover", new byte[]{1, 2})));
      byte[] saved = Files.readAllBytes(path);
      assertArrayEquals(audio, Arrays.copyOfRange(saved, 20, 29));
      assertEquals(0, saved[29]);
      assertEquals("ID3 ", Binary.ascii(saved, 30, 4));
      assertEquals("Aligned", TagLib.read(path).properties().first("TITLE"));
      assertTrue(
        MetadataDocument.open(path).entries().stream()
          .anyMatch(entry -> entry.kind().equals("artwork")));
      TagLib.write(path, properties);
      assertEquals(
        "Aligned",
        MetadataDocument.open(path).entries().stream()
          .filter(entry -> entry.standardKey().equals("TITLE"))
          .findFirst()
          .orElseThrow()
          .values()
          .getFirst());
      saved = Files.readAllBytes(path);
      assertEquals(saved.length - 8, aiff ? Binary.u32be(saved, 4) : Binary.u32le(saved, 4));
    }
  }

  @Test
  void removingAnUnpaddedOddFinalTagDoesNotAddSpuriousPadding() throws Exception {
    byte[] tag = Id3v2.render(PropertyMap.builder().put("TITLE", "AB").build(), new byte[0]);
    assertEquals(1, tag.length & 1);
    byte[] padded = chunk32("ID3 ", tag, false);
    byte[] body =
      join(chunk32("data", new byte[]{1, 2}, false), Arrays.copyOf(padded, padded.length - 1));
    byte[] header = join(Binary.bytes("RIFF"), new byte[4], Binary.bytes("WAVE"));
    Binary.put32le(header, 4, body.length + 4);
    Path path = temp.resolve("odd-tag.wav");
    Files.write(path, join(header, body));
    TagLib.write(path, PropertyMap.builder().put("TITLE", "New").build());
    byte[] saved = Files.readAllBytes(path);
    assertEquals("ID3 ", Binary.ascii(saved, 22, 4));
    assertEquals(
      "New",
      MetadataDocument.open(path).entries().stream()
        .filter(entry -> entry.standardKey().equals("TITLE"))
        .findFirst()
        .orElseThrow()
        .values()
        .getFirst());
  }

  private static void artworkRoundTrip(
    Codec codec, Path path, PropertyMap properties) throws Exception {
    var picture = new Picture(3, "image/png", "front", new byte[65_536]);
    AudioProperties audio = codec.read(path, ReadOptions.DEFAULT).audioProperties();
    codec.write(path, properties, List.of(picture));
    assertEquals(List.of(picture), codec.read(path, ReadOptions.DEFAULT).pictures());
    codec.write(path, properties);
    assertEquals(List.of(picture), codec.read(path, ReadOptions.DEFAULT).pictures());
    codec.write(path, properties, List.of());
    assertTrue(codec.read(path, ReadOptions.DEFAULT).pictures().isEmpty());
    assertEquals(audio, codec.read(path, ReadOptions.DEFAULT).audioProperties());
    assertEquals(properties, codec.read(path, ReadOptions.DEFAULT).properties());
  }

  @Test
  void dsfRefusesOutOfBoundsMetadataWithoutModifyingFile() throws Exception {
    Path path = temp.resolve("bad.dsf");
    byte[] bytes = new byte[80];
    System.arraycopy(Binary.bytes("DSD "), 0, bytes, 0, 4);
    Binary.put64le(bytes, 4, 28);
    Binary.put64le(bytes, 12, 80);
    Binary.put64le(bytes, 20, Long.MAX_VALUE);
    System.arraycopy(Binary.bytes("fmt "), 0, bytes, 28, 4);
    Binary.put64le(bytes, 32, 52);
    Files.write(path, bytes);
    assertThrows(
      IOException.class,
      () ->
        new DsdCodec()
          .write(
            path, PropertyMap.builder().put("TITLE", "bad").build()));
    assertArrayEquals(bytes, Files.readAllBytes(path));
  }

  private static byte[] chunk32(String id, byte[] payload, boolean be) {
    byte[] header = join(Binary.bytes(id), new byte[4]);
    if (be) Binary.put32be(header, 4, payload.length);
    else Binary.put32le(header, 4, payload.length);
    return join(header, payload, new byte[payload.length & 1]);
  }

  private static byte[] chunk64(String id, byte[] payload) {
    byte[] header = join(Binary.bytes(id), new byte[8]);
    Binary.put64be(header, 4, payload.length);
    return join(header, payload, new byte[payload.length & 1]);
  }

  private static byte[] join(byte[]... arrays) {
    var out = new ByteArrayOutputStream();
    for (byte[] array : arrays) out.writeBytes(array);
    return out.toByteArray();
  }

  private static int indexOf(byte[] data, byte[] needle) {
    outer:
    for (int i = 0; i <= data.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) if (data[i + j] != needle[j]) continue outer;
      return i;
    }
    return -1;
  }
}
