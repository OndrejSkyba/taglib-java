package cz.skyba.taglib;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MatroskaTest {
  @TempDir
  Path directory;

  @Test
  void rawPropertyAliasesDoNotMasqueradeAsNativeCommonSelectors() throws Exception {
    Path file = directory.resolve("aliases.mka");
    Files.write(
      file,
      document(tag(0, "ALBUM", "Raw album"), tag(0, "DATE", "2026"), tag(0, "ARTIST", "Artist")));
    var snapshot = TagLib.read(file, ReadOptions.TAGS_ONLY);
    assertEquals("Raw album", snapshot.properties().first("ALBUM"));
    assertEquals("2026", snapshot.properties().first("DATE"));
    assertEquals("", snapshot.tag().album());
    assertEquals(0, snapshot.tag().year());
    assertEquals("Artist", snapshot.tag().artist());
  }

  @Test
  void nativeAlbumTargetAndRecordedDatePopulateCommonFields() throws Exception {
    Path file = directory.resolve("mapped.mka");
    Files.write(
      file,
      document(
        tag(50, "TITLE", "Album"),
        tag(30, "DATE_RECORDED", "2026-10-03"),
        tag(30, "TITLE", "Track")));
    var snapshot = TagLib.read(file, ReadOptions.TAGS_ONLY);
    assertEquals("Album", snapshot.tag().album());
    assertEquals("Track", snapshot.tag().title());
    assertEquals(2026, snapshot.tag().year());
  }

  private static byte[] document(byte[]... tags) throws Exception {
    return join(
      element(0x1a45dfa3L, new byte[0]), element(0x18538067L, element(0x1254c367L, join(tags))));
  }

  private static byte[] tag(int target, String name, String value) throws Exception {
    byte[] targets =
      target == 0 ? new byte[0] : element(0x63c0, element(0x68ca, new byte[]{(byte) target}));
    return element(
      0x7373,
      join(
        targets,
        element(
          0x67c8,
          join(
            element(0x45a3, name.getBytes(StandardCharsets.UTF_8)),
            element(0x4487, value.getBytes(StandardCharsets.UTF_8))))));
  }

  private static byte[] element(long id, byte[] payload) throws Exception {
    var out = new ByteArrayOutputStream();
    int width = (64 - Long.numberOfLeadingZeros(id) + 7) / 8;
    for (int i = width - 1; i >= 0; i--) out.write((int) (id >>> (8 * i)));
    if (payload.length < 127) out.write(0x80 | payload.length);
    else {
      out.write(0x40 | (payload.length >>> 8));
      out.write(payload.length);
    }
    out.write(payload);
    return out.toByteArray();
  }

  private static byte[] join(byte[]... data) throws Exception {
    var out = new ByteArrayOutputStream();
    for (byte[] bytes : data) out.write(bytes);
    return out.toByteArray();
  }
}
