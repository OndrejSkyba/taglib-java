package cz.skyba.taglib;

import cz.skyba.taglib.it.ItFile;
import cz.skyba.taglib.mod.ModFile;
import cz.skyba.taglib.s3m.S3mFile;
import cz.skyba.taglib.shorten.ShortenFile;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.xm.XmFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fixed expectations independently established with pinned native typed File/Tag operations.
 */
class TrackerFileTest {
  @TempDir
  Path directory;

  private Path file(String name, byte[] data) throws Exception {
    Path path = directory.resolve(name);
    Files.write(path, data);
    return path;
  }

  private static void u16(byte[] data, int at, int value) {
    data[at] = (byte) value;
    data[at + 1] = (byte) (value >>> 8);
  }

  private static void u32(byte[] data, int at, long value) {
    for (int i = 0; i < 4; i++) data[at + i] = (byte) (value >>> (8 * i));
  }

  private static void text(byte[] data, int at, String value) {
    byte[] encoded = value.getBytes(StandardCharsets.ISO_8859_1);
    System.arraycopy(encoded, 0, data, at, encoded.length);
  }

  private static byte[] mod() {
    byte[] data = new byte[1084];
    text(data, 0, "Title");
    text(data, 20, "One");
    text(data, 50, "Two");
    text(data, 1080, "M.K.");
    return data;
  }

  private static byte[] xm(int width) {
    byte[] data = new byte[64 + width];
    text(data, 0, "Extended Module: ");
    text(data, 17, "Title");
    data[37] = 26;
    text(data, 38, "Tracker");
    u16(data, 58, 0x104);
    u32(data, 60, 4 + width);
    return data;
  }

  @Test
  void mutableTrackerTagRetainsOnlySupportedFieldsAndExportsEmptyValues() {
    var tag = new cz.skyba.taglib.mod.Tag();
    assertTrue(tag.isEmpty());
    assertEquals(Map.of("TITLE", List.of(""), "COMMENT", List.of("")), tag.properties().asMap());
    tag.setTrackerName("Tracker");
    assertTrue(tag.isEmpty());
    tag.setArtist("a");
    tag.setAlbum("b");
    tag.setGenre("g");
    tag.setYear(-1);
    tag.setTrack(9);
    assertEquals("", tag.artist());
    assertEquals("", tag.album());
    assertEquals("", tag.genre());
    assertEquals(0, tag.year());
    assertEquals(0, tag.track());
    assertEquals("Tracker", tag.properties().first("TRACKERNAME"));
  }

  @Test
  void trackerPropertyImportConsumesFirstValuesClearsAbsentAndRetainsRemainders() {
    var tag = new cz.skyba.taglib.mod.Tag();
    tag.setComment("old");
    tag.setTrackerName("old tracker");
    var input = PropertyMap.builder().put("TITLE", List.of("one", "two")).put("COMMENT", List.of()).put("TRACKERNAME", List.of("tracker", "extra")).put("ARTIST", "ignored").addUnsupportedData("physical").build();
    assertEquals(Map.of("TITLE", List.of("two"), "TRACKERNAME", List.of("extra"), "ARTIST", List.of("ignored")), tag.replaceProperties(input).rejected().asMap());
    assertEquals("one", tag.title());
    assertEquals("", tag.comment());
    assertEquals("tracker", tag.trackerName());
    tag.replaceProperties(PropertyMap.EMPTY);
    assertEquals("", tag.title());
    assertEquals("", tag.trackerName());
    assertEquals(List.of("one", "two"), input.get("TITLE"));
  }

  @Test
  void shortenStubRetainsNoFieldsAndRejectsTheOriginalMapUnchanged() {
    var tag = new cz.skyba.taglib.shorten.Tag();
    tag.setTitle("t");
    tag.setComment("c");
    tag.setArtist("a");
    tag.setAlbum("b");
    tag.setGenre("g");
    tag.setYear(4);
    tag.setTrack(5);
    assertTrue(tag.isEmpty());
    assertEquals(PropertyMap.EMPTY, tag.properties());
    var input = PropertyMap.builder().put("TITLE", List.of()).put("UNKNOWN", "v").addUnsupportedData("opaque").build();
    assertEquals(input, tag.replaceProperties(input).rejected());
    assertEquals(List.of("opaque"), tag.replaceProperties(input).rejected().unsupportedData());
  }

  @Test
  void malformedModMarkerThrowsWithoutClosingBorrowedStream() {
    byte[] data = mod();
    text(data, 1080, "FLTx");
    var stream = new ByteVectorStream(data);
    assertThrows(TagLibException.class, () -> new ModFile(stream, ReadStyle.FAST));
    assertEquals(1084, stream.tell());
    assertTrue(stream.isOpen());
  }

  @Test
  void truncatedS3mThrowsWithoutClosingBorrowedStream() {
    byte[] data = new byte[43];
    text(data, 0, "Partial S3M");
    data[28] = 26;
    data[29] = 16;
    u16(data, 32, 2);
    u16(data, 34, 5);
    u16(data, 36, 3);
    u16(data, 38, 0x8001);
    u16(data, 40, 0x1320);
    data[42] = 2;
    var stream = new ByteVectorStream(data);
    assertThrows(TagLibException.class, () -> new S3mFile(stream));
    assertEquals(43, stream.tell());
    assertTrue(stream.isOpen());
  }

  @Test
  void truncatedItCountsThrowOnOpen() {
    byte[] data = new byte[37];
    text(data, 0, "IMPM");
    text(data, 4, "Partial IT");
    u16(data, 32, 2);
    u16(data, 34, 5);
    data[36] = 3;
    assertThrows(TagLibException.class, () -> new ItFile(new ByteVectorStream(data)));
  }

  @Test
  void truncatedXmHeadersAndPatternsThrowOnOpen() {
    var header = new ByteVectorStream(Arrays.copyOf(xm(0), 62));
    assertThrows(TagLibException.class, () -> new XmFile(header));
    assertEquals(62, header.tell());
    byte[] data = xm(16);
    u16(data, 68, 7);
    u16(data, 70, 1);
    var patterns = new ByteVectorStream(data);
    assertThrows(TagLibException.class, () -> new XmFile(patterns));
    assertEquals(80, patterns.tell());
    assertTrue(header.isOpen());
    assertTrue(patterns.isOpen());
  }

  @Test
  void liveDetailedHandleChangesCommonProjectionAndModSaveNameCount() throws Exception {
    Path path = file("live.mod", mod());
    try (var file = new ModFile(path, ReadStyle.ACCURATE)) {
      assertTrue(file.isValid());
      assertSame(file.detailedProperties(), file.detailedProperties());
      file.detailedProperties().setChannels(-7);
      assertEquals(-7, file.audioProperties().channels());
      file.detailedProperties().setInstrumentCount(1);
      file.tag().setComment("Changed\nUnpersisted");
      file.save();
      assertEquals(50, file.tell());
    }
    byte[] output = Files.readAllBytes(path);
    assertEquals("Changed", new String(output, 20, 7, StandardCharsets.ISO_8859_1));
    assertEquals("Two", new String(output, 50, 3, StandardCharsets.ISO_8859_1));
  }

  @Test
  void invalidS3mAndXmCannotBeOpenedForEditing() throws Exception {
    for (String extension : List.of("s3m", "xm")) {
      Path path = file("invalid." + extension, new byte[0]);
      assertThrows(TagLibException.class, () -> {
        if (extension.equals("s3m")) new S3mFile(path);
        else new XmFile(path);
      });
      assertEquals(0, Files.size(path));
    }
  }

  @Test
  void invalidModAndItCannotBeOpenedForEditing() throws Exception {
    Path mod = file("empty.mod", new byte[0]);
    assertThrows(TagLibException.class, () -> new ModFile(mod));
    assertEquals(0, Files.size(mod));
    Path it = file("empty.it", new byte[0]);
    assertThrows(TagLibException.class, () -> new ItFile(it));
    assertEquals(0, Files.size(it));
  }

  @Test
  void missingTrackerAndInvalidShortenThrowOnOpen() {
    assertThrows(java.io.UncheckedIOException.class, () -> new ModFile(directory.resolve("missing.mod")));
    assertThrows(java.io.UncheckedIOException.class, () -> new ShortenFile(directory.resolve("missing.shn")));
    assertThrows(java.io.UncheckedIOException.class,
      () -> new ShortenFile(new ByteVectorStream(new byte[]{'n', 'o', 'p', 'e'})));
  }

  @Test
  void pathOwnershipAndBorrowedLifetimesRemainDistinct() throws Exception {
    var path = file("owned.mod", mod());
    var owned = new ModFile(path);
    assertTrue(owned.isOpen());
    owned.close();
    assertFalse(owned.isOpen());
    var stream = new ByteVectorStream(mod());
    var borrowed = new ModFile(stream);
    borrowed.close();
    assertTrue(stream.isOpen());
    assertEquals("Title", borrowed.tag().title());
  }

  @Test
  void failedS3mSaveReplaysPrecedingWritesBeforeReportingTheReadFailure() {
    byte[] data = new byte[96];
    data[28] = 26;
    data[29] = 16;
    text(data, 44, "SCRM");
    var stream = new ByteVectorStream(data);
    var file = new S3mFile(stream);
    stream.truncate(16);
    file.tag().setTitle("Changed");
    var failure = assertThrows(TagLibException.class, file::save);
    assertNotNull(failure.getCause());
    byte[] expected = new byte[28];
    text(expected, 0, "Changed");
    assertArrayEquals(expected, stream.data().toByteArray());
    assertEquals(32, stream.tell());
    assertTrue(stream.isOpen());
  }

  @Test
  void modRejectsAnUnrepresentableMemoryWriteBeforeChangingAnyBytes() {
    byte[] original = mod();
    var stream = new ByteVectorStream(original);
    var file = new ModFile(stream);
    file.detailedProperties().setInstrumentCount(0xffff_ffffL);
    file.tag().setTitle("Changed");
    long cursor = stream.tell();
    assertThrows(TagLibException.class, file::save);
    assertEquals(cursor, stream.tell());
    assertArrayEquals(original, stream.data().toByteArray());
  }

  @Test
  void fileRefExposesActualTypedTrackerAndAdoptsOnlyPathStreams() throws Exception {
    Path path = file("ref.mod", mod());
    FileBase owned;
    try (var ref = new AudioFile(path, new ReadOptions(false, ReadStyle.FAST, ReadOptions.DEFAULT.maxMetadataBytes()))) {
      assertInstanceOf(ModFile.class, ref.file());
      assertInstanceOf(cz.skyba.taglib.mod.Tag.class, ref.tag());
      owned = ref.file();
    }
    assertFalse(owned.isOpen());
    var stream = new ByteVectorStream(mod()) {
      @Override
      public String name() {
        return "borrowed.mod";
      }
    };
    try (var ref = new AudioFile(stream)) {
      assertInstanceOf(ModFile.class, ref.file());
      ref.tag().setTitle("Live");
      assertDoesNotThrow(ref::save);
    }
    assertTrue(stream.isOpen());
    assertEquals("Live", new ModFile(stream).tag().title());
  }

  @Test
  void fileRefUsesExactlyOneTypedConstructorRead() throws Exception {
    var stream = new ByteVectorStream(mod()) {
      int markerReads;

      @Override
      public String name() {
        return "single.mod";
      }

      @Override
      public ByteVector readBlock(long count) {
        if (tell() == 1080 && ++markerReads > 1) return new ByteVector();
        return super.readBlock(count);
      }
    };
    try (var ref = new AudioFile(stream)) {
      assertTrue(ref.isOpen());
      assertEquals("Title", ref.tag().title());
      assertEquals(1, stream.markerReads);
      assertEquals(951, stream.tell());
    }
  }

  @Test
  void fileRefDoesNotInventAnItContentDetector() throws Exception {
    byte[] data = new byte[192];
    text(data, 0, "IMPM");
    text(data, 4, "Title");
    try (var direct = new ItFile(new ByteVectorStream(data))) {
      assertTrue(direct.isValid());
    }
    assertThrows(TagLibException.class, () -> new AudioFile(new ByteVectorStream(data)));
  }

  @Test
  void plannedXmSaveReadsEachHeaderOnceAndReplaysTheValidatedEdits() {
    var stream = new ByteVectorStream(xm(16)) {
      int saveReads;
      boolean saving;

      @Override
      public ByteVector readBlock(long count) {
        if (saving) saveReads++;
        return super.readBlock(count);
      }
    };
    try (var file = new XmFile(stream)) {
      assertTrue(file.isValid());
      stream.saving = true;
      file.tag().setTitle("Changed");
      file.save();
      assertEquals(3, stream.saveReads);
      assertEquals(74, stream.tell());
      assertEquals("Changed", new String(stream.data().toByteArray(), 17, 7, StandardCharsets.ISO_8859_1));
    }
  }

  @Test
  void shortenAcceptsNativeShortRefillsEvenWhenReportedLengthIsZero() throws Exception {
    byte[] data = HexFormat.of().parseHex("616a6b6702df62e0133255952a4d1a8d34d8c0a0157a0d5a8b66b6dd241108040201018040a0144d64020110d8c0a0104804420164b0dd2c310d8c0a00");
    var stream = new ByteVectorStream(data) {
      int refills;

      @Override
      public String name() {
        return "reported-zero.shn";
      }

      @Override
      public long length() {
        return 0;
      }

      @Override
      public ByteVector readBlock(long count) {
        if (tell() == 5) {
          assertEquals(512, count);
          refills++;
        }
        return super.readBlock(count);
      }
    };
    try (var ref = new AudioFile(stream)) {
      assertInstanceOf(ShortenFile.class, ref.file());
      assertTrue(ref.isOpen());
      assertEquals(44100, ref.audioProperties().sampleRate());
      assertEquals(1, stream.refills);
      assertEquals(data.length, stream.tell());
    }
    assertTrue(stream.isOpen());
  }

  private static final class Helpers extends cz.skyba.taglib.mod.FileBase {
    Helpers(ByteVectorStream stream) {
      super(stream);
    }

    @Override
    public TagBase tag() {
      return new cz.skyba.taglib.mod.Tag();
    }

    @Override
    public AudioProperties audioProperties() {
      return AudioProperties.EMPTY;
    }

    @Override
    public void save() {
      throw new cz.skyba.taglib.TagLibException("Test save rejected");
    }

    @Override
    public boolean readString(Consumer<String> output, long size) {
      return super.readString(output, size);
    }

    @Override
    public boolean readU16L(IntConsumer output) {
      return super.readU16L(output);
    }

    @Override
    public boolean readU32B(LongConsumer output) {
      return super.readU32B(output);
    }

    @Override
    public void writeString(String value, long size, byte padding) {
      super.writeString(value, size, padding);
    }

    @Override
    public void writeU16L(int value) {
      super.writeU16L(value);
    }

    @Override
    public void writeU32B(long value) {
      super.writeU32B(value);
    }
  }

  @Test
  void protectedHelpersAssignOutputsOnlyOnFullReadsAndRetainNativeConversions() {
    var stream = new ByteVectorStream(new byte[]{'a', (byte) 255, 0, 'b', 1});
    var helpers = new Helpers(stream);
    String[] value = {"old"};
    assertTrue(helpers.readString(v -> value[0] = v, 4));
    assertEquals("a ", value[0]);
    assertFalse(helpers.readString(v -> value[0] = v, 2));
    assertEquals("a ", value[0]);
    assertEquals(5, stream.tell());
    int[] number = {42};
    assertFalse(helpers.readU16L(v -> number[0] = v));
    assertEquals(42, number[0]);
    stream.data().clear();
    stream.seek(0);
    helpers.writeString("\u20ac\u00ff", 4, (byte) 255);
    helpers.writeU16L(0x12345);
    helpers.writeU32B(-1);
    assertArrayEquals(new byte[]{(byte) 0xac, (byte) 255, (byte) 255, (byte) 255, 0x45, 0x23, (byte) 255, (byte) 255, (byte) 255, (byte) 255}, stream.data().toByteArray());
    stream.seek(4);
    assertTrue(helpers.readU16L(v -> number[0] = v));
    assertEquals(0x2345, number[0]);
    long[] wide = {0};
    assertTrue(helpers.readU32B(v -> wide[0] = v));
    assertEquals(0xffff_ffffL, wide[0]);
    stream.seek(0);
    assertFalse(helpers.readString(v -> value[0] = v, 0x1_0000_0001L));
    assertEquals("a ", value[0]);
    assertEquals(10, stream.tell());
  }
}
