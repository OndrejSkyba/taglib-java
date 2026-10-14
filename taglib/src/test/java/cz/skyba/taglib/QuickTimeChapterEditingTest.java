package cz.skyba.taglib;

import cz.skyba.taglib.mp4.Chapter;
import cz.skyba.taglib.mp4.QuickTimeChapters;
import cz.skyba.taglib.mp4.Stem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

import static cz.skyba.taglib.Mp4Test.box;
import static cz.skyba.taglib.Mp4Test.join;
import static cz.skyba.taglib.internal.Binary.*;
import static org.junit.jupiter.api.Assertions.*;

class QuickTimeChapterEditingTest {
  @TempDir
  Path directory;
  private static final byte[] MEDIA = {11, 22, 33, 44, 55, 66, 77};
  private static final List<Chapter> CHAPTERS = List.of(
    new Chapter("Opening — 日本語", 1001), new Chapter("Second", 12001));

  @Test
  void containerQuickTimeCreateReplaceRemoveRetainsAudioAndOtherReferences() throws Exception {
    Path path = fixture("cycle", true);
    long originalSize = Files.size(path);
    var nero = new cz.skyba.taglib.mp4.Tag(List.of(new Chapter("Nero", 42)),
      new Stem(bytes("{\"stems\":[]}")));
    nero.save(path);
    originalSize = Files.size(path);
    for (int i = 0; i < 3; i++) {
      new QuickTimeChapters(CHAPTERS).save(path);
      assertEquals(CHAPTERS, QuickTimeChapters.read(path).chapters());
      assertAudio(path);
      assertEquals(2, topCount(path, "mdat"));
      assertTrue(index(Files.readAllBytes(path), "tmcd", 0) >= 0);
      assertEquals(nero, cz.skyba.taglib.mp4.Tag.read(path));
      new QuickTimeChapters(List.of(new Chapter("Replaced", 0))).save(path);
      assertEquals(List.of(new Chapter("Replaced", 0)), QuickTimeChapters.read(path).chapters());
      assertEquals(2, topCount(path, "mdat"));
      new QuickTimeChapters(List.of()).save(path);
      assertTrue(QuickTimeChapters.read(path).chapters().isEmpty());
      assertEquals(originalSize, Files.size(path));
      assertEquals(1, topCount(path, "mdat"));
      assertAudio(path);
      assertTrue(index(Files.readAllBytes(path), "tmcd", 0) >= 0);
      assertEquals(nero, cz.skyba.taglib.mp4.Tag.read(path));
    }
  }

  @Test
  void containerQuickTimeSharedMediaNeverRemoved() throws Exception {
    Path path = fixture("shared", false);
    new QuickTimeChapters(CHAPTERS).save(path);
    byte[] data = Files.readAllBytes(path);
    int firstStco = index(data, "stco", 0), chapterStco = index(data, "stco", firstStco + 4);
    put32be(data, chapterStco + 12, u32be(data, firstStco + 12));
    Files.write(path, data);
    new QuickTimeChapters(List.of()).save(path);
    assertEquals(2, topCount(path, "mdat")); // The unrelated old title mdat also remains, as native.
    assertAudio(path);
  }

  @Test
  void containerQuickTimeTracksSurviveOrdinaryTagGrowthAndSizeToEofRemoval() throws Exception {
    Path path = fixture("metadata-growth", false);
    new QuickTimeChapters(CHAPTERS).save(path);
    TagLib.write(path, PropertyMap.builder().put("TITLE", "large".repeat(2000)).build());
    assertEquals(CHAPTERS, QuickTimeChapters.read(path).chapters());
    assertAudio(path);
    byte[] data = Files.readAllBytes(path);
    int lastMdat = index(data, "mdat", index(data, "mdat", 0) + 4);
    put32be(data, lastMdat - 4, 0);
    Files.write(path, data);
    new QuickTimeChapters(List.of()).save(path);
    assertTrue(QuickTimeChapters.read(path).chapters().isEmpty());
    assertAudio(path);
    assertEquals("large".repeat(2000), TagLib.read(path).properties().first("TITLE"));
    data = Files.readAllBytes(path);
    assertTrue(index(data, "mdat", index(data, "mdat", 0) + 4) >= 0);
  }

  @Test
  void containerQuickTimeDummyAndLongTitleRulesMatchRelease() throws Exception {
    Path path = fixture("dummy", false);
    var cases = List.of(List.of(new Chapter("", 0)),
      List.of(new Chapter("", 0), new Chapter("real", 5000)),
      List.of(new Chapter("x".repeat(65539), 0)));
    for (var chapters : cases) {
      new QuickTimeChapters(chapters).save(path);
      var expected = chapters.size() > 1 ? chapters.subList(1, 2)
        : chapters.getFirst().title().length() > 65535 ? List.of(new Chapter("xxx", 0)) : chapters;
      assertEquals(expected, QuickTimeChapters.read(path).chapters());
      assertAudio(path);
    }
  }

  @Test
  void containerQuickTimeReadsMultipleChunksDefaultsAndVersionOneHeaders() throws Exception {
    Path path = chapterFixture("chunks", false, false, false);
    assertEquals(List.of(new Chapter("one", 0), new Chapter("two", 33)), QuickTimeChapters.read(path).chapters());
    Path defaultSize = chapterFixture("default", true, true, false);
    assertEquals(QuickTimeChapters.read(path), QuickTimeChapters.read(defaultSize));
    Path co64 = chapterFixture("co64", false, false, true);
    assertTrue(QuickTimeChapters.read(co64).chapters().isEmpty()); // Native readStco deliberately has no co64 fallback.
  }

  @Test
  void containerQuickTimeMalformedLaterSampleRetainsNativeReadablePrefix() throws Exception {
    Path path = chapterFixture("damaged", false, false, false);
    byte[] data = Files.readAllBytes(path);
    int stsz = index(data, "stsz", 0);
    put32be(data, stsz + 20, 0xffffffffL); // Second size exceeds file; first still readable.
    Files.write(path, data);
    assertEquals(List.of(new Chapter("one", 0)), QuickTimeChapters.read(path).chapters());
  }

  @Test
  void containerQuickTimeRejectsUnsupportedPlansBeforeChangingSource() throws Exception {
    Path path = fixture("reject", false);
    byte[] original = Files.readAllBytes(path);
    assertThrows(TagLibException.class,
      () -> new QuickTimeChapters(List.of(new Chapter("bad", -1))).save(path));
    assertArrayEquals(original, Files.readAllBytes(path));
    new QuickTimeChapters(CHAPTERS).save(path);
    Files.write(path, box("free", new byte[8]), StandardOpenOption.APPEND);
    byte[] before = Files.readAllBytes(path);
    assertThrows(TagLibException.class, () -> new QuickTimeChapters(List.of()).save(path));
    assertArrayEquals(before, Files.readAllBytes(path));
    Path noDuration = fixture("no-duration", false);
    byte[] data = Files.readAllBytes(noDuration);
    put32be(data, index(data, "mvhd", 0) + 20, 0);
    Files.write(noDuration, data);
    assertThrows(TagLibException.class, () -> new QuickTimeChapters(CHAPTERS).save(noDuration));
    assertArrayEquals(data, Files.readAllBytes(noDuration));
  }


  private Path fixture(String name, boolean reference) throws Exception {
    byte[] ftyp = box("ftyp", bytes("M4A \0\0\0\0"));
    byte[] mvhd = new byte[100];
    put32be(mvhd, 12, 1000);
    put32be(mvhd, 16, 30000);
    put32be(mvhd, 96, 7);
    byte[] track = audioTrack(reference ? box("tref", box("tmcd", uints(4))) : new byte[0]);
    byte[] movie = box("moov", join(box("mvhd", mvhd), track));
    byte[] data = join(ftyp, movie, box("mdat", MEDIA));
    put32be(data, index(data, "stco", 0) + 12, ftyp.length + movie.length + 8);
    Path path = directory.resolve(name + ".m4a");
    Files.write(path, data);
    return path;
  }

  private static byte[] audioTrack(byte[] refs) throws Exception {
    byte[] tkhd = new byte[84];
    put32be(tkhd, 12, 1);
    byte[] hdlr = new byte[24];
    System.arraycopy(bytes("soun"), 0, hdlr, 8, 4);
    return box("trak", join(box("tkhd", tkhd), refs,
      box("mdia", join(box("hdlr", hdlr),
        box("minf", box("stbl", box("stco", uints(0, 1, 0))))))));
  }

  private Path chapterFixture(String name, boolean defaults, boolean versionOne, boolean co64) throws Exception {
    byte[] tkhd = new byte[versionOne ? 96 : 84];
    tkhd[0] = (byte) (versionOne ? 1 : 0);
    put32be(tkhd, versionOne ? 20 : 12, 2);
    byte[] mdhd = new byte[versionOne ? 36 : 24];
    mdhd[0] = (byte) (versionOne ? 1 : 0);
    put32be(mdhd, versionOne ? 20 : 12, 30000);
    byte[] stsz = defaults ? uints(0, 5, 2) : uints(0, 0, 2, 5, 5);
    byte[] stsc = defaults ? new byte[0] : box("stsc", uints(0, 1, 1, 1, 1));
    byte[] stco = co64 ? box("co64", new byte[24]) : box("stco", uints(0, 2, 0, 0));
    byte[] track = box("trak", join(box("tkhd", tkhd), box("mdia", join(box("mdhd", mdhd),
      box("minf", box("stbl", join(box("stts", uints(0, 1, 2, 1000)), box("stsz", stsz), stsc, stco)))))));
    byte[] movie = box("moov", join(audioTrack(box("tref", box("chap", uints(2)))), track));
    byte[] ftyp = box("ftyp", bytes("M4A \0\0\0\0"));
    byte[] data = join(ftyp, movie, box("mdat", join(MEDIA, new byte[]{0, 3}, bytes("one"),
      new byte[7], new byte[]{0, 3}, bytes("two"))));
    int media = ftyp.length + movie.length + 8;
    int first = index(data, "stco", 0);
    put32be(data, first + 12, media);
    if (!co64) {
      int second = index(data, "stco", first + 4);
      put32be(data, second + 12, media + MEDIA.length);
      put32be(data, second + 16, media + MEDIA.length + 12);
    }
    Path path = directory.resolve(name + ".m4a");
    Files.write(path, data);
    return path;
  }


  private static void assertAudio(Path path) throws Exception {
    byte[] data = Files.readAllBytes(path);
    int chunk = (int) u32be(data, index(data, "stco", 0) + 12);
    assertArrayEquals(MEDIA, Arrays.copyOfRange(data, chunk, chunk + MEDIA.length));
  }

  private static int topCount(Path path, String type) throws Exception {
    byte[] data = Files.readAllBytes(path);
    int count = 0;
    for (int at = 0; at < data.length; at += (int) u32be(data, at))
      if (ascii(data, at + 4, 4).equals(type)) count++;
    return count;
  }

  private static int index(byte[] data, String text, int from) {
    byte[] needle = bytes(text);
    outer:
    for (int at = from; at <= data.length - needle.length; at++) {
      for (int i = 0; i < needle.length; i++) if (data[at + i] != needle[i]) continue outer;
      return at;
    }
    return -1;
  }

  private static byte[] uints(long... values) {
    byte[] data = new byte[values.length * 4];
    for (int i = 0; i < values.length; i++) put32be(data, i * 4, values[i]);
    return data;
  }


}
