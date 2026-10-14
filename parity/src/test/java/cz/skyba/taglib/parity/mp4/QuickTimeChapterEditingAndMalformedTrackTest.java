package cz.skyba.taglib.parity.mp4;

import cz.skyba.taglib.mp4.Chapter;
import cz.skyba.taglib.mp4.QuickTimeChapters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static cz.skyba.taglib.internal.Binary.*;
import static cz.skyba.taglib.parity.mp4.Mp4FixtureBytes.box;
import static cz.skyba.taglib.parity.mp4.Mp4FixtureBytes.join;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("parity")
@org.junit.jupiter.api.Tag("mp4")
class QuickTimeChapterEditingAndMalformedTrackTest {
  @TempDir
  Path directory;
  private static final byte[] MEDIA = {11, 22, 33, 44, 55, 66, 77};
  private static final List<Chapter> CHAPTERS =
    List.of(new Chapter("Opening — 日本語", 1001), new Chapter("Second", 12001));

  @Test
  void containerQuickTimePairedNativeCreationReplacementRemovalAndMalformedReads()
    throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("container.oracle") != null);
    Path java = fixture("java", true), nativePath = fixture("native", true);
    new QuickTimeChapters(CHAPTERS).save(java);
    nativeRun("qt-set", nativePath);
    assertEquals(CHAPTERS, QuickTimeChapters.read(nativePath).chapters());
    assertEquals(nativeRun("qt-read", nativePath), nativeRun("qt-read", java));
    // Compare native-generated sample-track bytes as well as semantic results. stco contains a
    // different absolute position because native File::save adds ordinary metadata padding.
    assertArrayEquals(normalizedTextTrack(java), normalizedTextTrack(nativePath));
    for (int i = 0; i < 2; i++) {
      new QuickTimeChapters(CHAPTERS).save(java);
      nativeRun("qt-set", nativePath);
      assertEquals(nativeRun("qt-read", nativePath), nativeRun("qt-read", java));
      assertAudio(java);
      assertAudio(nativePath);
      assertEquals(2, topCount(java, "mdat"));
      assertEquals(2, topCount(nativePath, "mdat"));
    }
    nativeRun("qt-clear", java);
    new QuickTimeChapters(List.of()).save(nativePath);
    assertEquals("", nativeRun("qt-read", nativePath));
    assertTrue(QuickTimeChapters.read(java).chapters().isEmpty());
    assertEquals(1, topCount(java, "mdat"));
    assertEquals(1, topCount(nativePath, "mdat"));
    assertAudio(java);
    assertAudio(nativePath);
    for (boolean defaults : List.of(false, true)) {
      Path multi = chapterFixture("paired-" + defaults, defaults, defaults, false);
      assertEquals(javaTsv(multi), nativeRun("qt-read", multi));
      byte[] data = Files.readAllBytes(multi);
      if (!defaults) put32be(data, index(data, "stsz", 0) + 20, 0xffffffffL);
      else put32be(data, index(data, "stco", index(data, "stco", 0) + 4) + 16, 0xffffffffL);
      Files.write(multi, data);
      assertEquals(javaTsv(multi), nativeRun("qt-read", multi));
    }
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
    return box(
      "trak",
      join(
        box("tkhd", tkhd),
        refs,
        box(
          "mdia",
          join(box("hdlr", hdlr), box("minf", box("stbl", box("stco", uints(0, 1, 0))))))));
  }

  private Path chapterFixture(String name, boolean defaults, boolean versionOne, boolean co64)
    throws Exception {
    byte[] tkhd = new byte[versionOne ? 96 : 84];
    tkhd[0] = (byte) (versionOne ? 1 : 0);
    put32be(tkhd, versionOne ? 20 : 12, 2);
    byte[] mdhd = new byte[versionOne ? 36 : 24];
    mdhd[0] = (byte) (versionOne ? 1 : 0);
    put32be(mdhd, versionOne ? 20 : 12, 30000);
    byte[] stsz = defaults ? uints(0, 5, 2) : uints(0, 0, 2, 5, 5);
    byte[] stsc = defaults ? new byte[0] : box("stsc", uints(0, 1, 1, 1, 1));
    byte[] stco = co64 ? box("co64", new byte[24]) : box("stco", uints(0, 2, 0, 0));
    byte[] track =
      box(
        "trak",
        join(
          box("tkhd", tkhd),
          box(
            "mdia",
            join(
              box("mdhd", mdhd),
              box(
                "minf",
                box(
                  "stbl",
                  join(
                    box("stts", uints(0, 1, 2, 1000)),
                    box("stsz", stsz),
                    stsc,
                    stco)))))));
    byte[] movie = box("moov", join(audioTrack(box("tref", box("chap", uints(2)))), track));
    byte[] ftyp = box("ftyp", bytes("M4A \0\0\0\0"));
    byte[] data =
      join(
        ftyp,
        movie,
        box(
          "mdat",
          join(
            MEDIA,
            new byte[]{0, 3},
            bytes("one"),
            new byte[7],
            new byte[]{0, 3},
            bytes("two"))));
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

  private static byte[] normalizedTextTrack(Path path) throws Exception {
    byte[] data = Files.readAllBytes(path);
    int text = index(data, "text", 0), track = -1;
    for (int at = index(data, "trak", 0); at >= 0 && at < text; at = index(data, "trak", at + 4))
      track = at - 4;
    byte[] bytes = Arrays.copyOfRange(data, track, track + (int) u32be(data, track));
    put32be(bytes, index(bytes, "stco", 0) + 12, 0);
    return bytes;
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

  private static String javaTsv(Path path) throws Exception {
    var out = new StringBuilder();
    for (var c : QuickTimeChapters.read(path).chapters())
      out.append(c.startTime())
        .append('\t')
        .append(HexFormat.of().formatHex(c.title().getBytes(StandardCharsets.UTF_8)))
        .append('\n');
    return out.toString();
  }

  private static String nativeRun(String command, Path path) throws Exception {
    var process =
      new ProcessBuilder(System.getProperty("container.oracle"), command, path.toString())
        .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String errors = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), errors);
    return output;
  }
}
