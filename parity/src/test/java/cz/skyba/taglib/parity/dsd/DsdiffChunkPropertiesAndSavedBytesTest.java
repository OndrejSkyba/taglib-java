package cz.skyba.taglib.parity.dsd;

import cz.skyba.taglib.parity.support.FileRefAdapter;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.internal.Binary;
import cz.skyba.taglib.internal.id3.Id3v2;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Native DSDIFF source branches, including physical duplicates and same-file repeated saves.
 */
@org.junit.jupiter.api.Tag("parity")
@org.junit.jupiter.api.Tag("dsd")
class DsdiffChunkPropertiesAndSavedBytesTest {
  @TempDir
  Path directory;

  @TestFactory
  List<DynamicTest> chunkPropertiesEditsAndSavedBytesMatchCpp() throws Exception {
    String command = System.getProperty("dsdiff.source.oracle");
    if (Boolean.getBoolean("taglib.requireNative"))
      assertTrue(
        command != null && Files.isExecutable(Path.of(command)), "Required DSDIFF source probe");
    assumeTrue(command != null, "Run ./mvnw -Pparity verify");
    var tests = new ArrayList<DynamicTest>();
    for (String layout :
      List.of(
        "absent",
        "diin",
        "root-before-diin",
        "root-after-diin",
        "prop",
        "duplicates",
        "prop-marker",
        "short-fs",
        "short-prop",
        "short-frte",
        "frte-later-invalid",
        "split-prop"))
      for (String operation : List.of("save-only", "empty", "unicode", "common"))
        tests.add(
          DynamicTest.dynamicTest(
            layout + "/" + operation, () -> compare(command, layout, operation)));
    return tests;
  }

  private void compare(String command, String layout, String operation) throws Exception {
    byte[] source = document(layout);
    Path java = Files.write(directory.resolve(layout + operation + "-java.dff"), source),
      nativeCopy = Files.write(directory.resolve(layout + operation + "-native.dff"), source);
    var process =
      new ProcessBuilder(command, nativeCopy.toString(), operation, "3")
        .redirectError(directory.resolve("native-errors.txt").toFile())
        .start();
    String expected = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), Files.readString(directory.resolve("native-errors.txt")));
    var actual = new StringBuilder();
    try (var ref = new FileRefAdapter(java, false)) {
      dump(ref, actual);
      assertFalse(ref.isNull());
      if (operation.equals("empty")) ref.replaceProperties(PropertyMap.EMPTY);
      if (operation.equals("unicode"))
        ref.replaceProperties(
          PropertyMap.builder()
            .put("TITLE", "Žluť")
            .put("ARTIST", "東京")
            .put("COMMENT", "comment")
            .build());
      if (operation.equals("common")) {
        ref.tag().setTitle("new title");
        ref.tag().setArtist("new artist");
        ref.tag().setAlbum("new album");
        ref.tag().setYear(4294967295L);
        ref.tag().setTrack(2147483648L);
      }
      dump(ref, actual);
      for (int i = 0; i < 3; i++) {
        actual.append("save=").append(ref.save() ? 1 : 0).append('\n');
        dump(ref, actual);
      }
    }
    actual.append("reread\n");
    try (var ref = new FileRefAdapter(java, false)) {
      dump(ref, actual);
    }
    String evidence = System.getProperty("dsdiff.source.evidence");
    if (evidence != null) {
      Path out = Path.of(evidence, layout, operation);
      Files.createDirectories(out);
      Files.write(out.resolve("source.dff"), source);
      Files.copy(nativeCopy, out.resolve("native.dff"), StandardCopyOption.REPLACE_EXISTING);
      Files.copy(java, out.resolve("java.dff"), StandardCopyOption.REPLACE_EXISTING);
      Files.writeString(out.resolve("native.txt"), expected);
      Files.writeString(out.resolve("java.txt"), actual);
    }
    assertEquals(expected, actual.toString(), "native staged/after-save/reopen transcript");
    assertArrayEquals(
      Files.readAllBytes(nativeCopy),
      Files.readAllBytes(java),
      "native complete default-save bytes");
  }

  private static void dump(FileRefAdapter ref, StringBuilder out) {
    if (ref.isNull()) {
      out.append("valid=0\n");
      return;
    }
    var t = ref.tag();
    out.append("valid=1\nempty=")
      .append(t.isEmpty() ? 1 : 0)
      .append("\ntitle=")
      .append(hex(t.title()))
      .append("\nartist=")
      .append(hex(t.artist()))
      .append("\nalbum=")
      .append(hex(t.album()))
      .append("\ncomment=")
      .append(hex(t.comment()))
      .append("\ngenre=")
      .append(hex(t.genre()))
      .append("\nyear=")
      .append(t.year())
      .append("\ntrack=")
      .append(t.track())
      .append('\n');
    for (var e : new TreeMap<>(ref.properties().asMap()).entrySet()) {
      out.append("P\t").append(hex(e.getKey()));
      for (String value : e.getValue()) out.append('\t').append(hex(value));
      out.append('\n');
    }
    for (String value : ref.properties().unsupportedData())
      out.append("U\t").append(hex(value)).append('\n');
  }

  private static String hex(String text) {
    return HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] document(String layout) throws Exception {
    byte[] fs = new byte[4];
    Binary.put32be(fs, 0, 44100);
    byte[] channels = {0, 2, 'S', 'L', 'F', 'T', 'S', 'R', 'G', 'T'};
    byte[] prop =
      chunk(
        "PROP",
        join(
          Binary.bytes("SND "),
          chunk("FS  ", fs),
          chunk("CHNL", channels),
          layout.equals("prop") || layout.equals("duplicates")
            ? chunk("ID3 ", tag("prop title"))
            : new byte[0]));
    byte[] diin =
      layout.equals("absent") || layout.equals("prop")
        ? new byte[0]
        : chunk(
        "DIIN",
        join(
          chunk("DITI", string("old title")),
          chunk("DIAR", string("old artist")),
          chunk("ZZZZ", new byte[]{7, 8, 9})));
    byte[] rootTag =
      layout.startsWith("root-") || layout.equals("duplicates")
        ? chunk("ID3 ", tag("root title"))
        : new byte[0];
    byte[] duplicate = layout.equals("duplicates") ? chunk("ID3 ", tag("last root")) : new byte[0];
    byte[] audio = new byte[128];
    for (int i = 0; i < audio.length; i++) audio[i] = (byte) (i * 13 + 7);
    if (layout.equals("prop-marker")) prop[12] = 'X';
    if (layout.equals("short-fs"))
      prop =
        chunk(
          "PROP",
          join(Binary.bytes("SND "), chunk("FS  ", new byte[0]), chunk("CHNL", channels)));
    if (layout.equals("short-prop")) prop = chunk("PROP", new byte[0]);
    if (layout.equals("split-prop"))
      prop =
        join(
          chunk("PROP", join(Binary.bytes("SND "), chunk("FS  ", fs))),
          chunk("PROP", join(Binary.bytes("SND "), chunk("CHNL", channels))));
    byte[] audioChunk = chunk("DSD ", audio);
    if (layout.equals("short-frte") || layout.equals("frte-later-invalid")) {
      byte[] frames = {0, 0, 0, 75, 0, 75};
      audioChunk =
        chunk(
          "DST ",
          join(
            chunk("FRTE", layout.equals("short-frte") ? Arrays.copyOf(frames, 4) : frames),
            layout.equals("short-frte") ? chunk("DSTF", audio) : new byte[12]));
    }
    byte[] roots =
      layout.equals("root-after-diin")
        ? join(prop, diin, rootTag, audioChunk)
        : join(prop, rootTag, diin, duplicate, audioChunk);
    return chunk("FRM8", join(Binary.bytes("DSD "), roots));
  }

  private static byte[] tag(String title) throws Exception {
    var body = new ByteArrayOutputStream();
    Id3v2.writeFrame(
      body, 4, "TIT2", 0, join(new byte[]{3}, title.getBytes(StandardCharsets.UTF_8)));
    return Id3v2.renderTag(4, body, 0, 32);
  }

  private static byte[] string(String text) {
    byte[] data = text.getBytes(StandardCharsets.ISO_8859_1), out = new byte[data.length + 4];
    Binary.put32be(out, 0, data.length);
    System.arraycopy(data, 0, out, 4, data.length);
    return out;
  }

  private static byte[] chunk(String id, byte[] data) {
    byte[] out = new byte[12 + data.length + (data.length & 1)];
    System.arraycopy(Binary.bytes(id), 0, out, 0, 4);
    Binary.put64be(out, 4, data.length);
    System.arraycopy(data, 0, out, 12, data.length);
    return out;
  }

  private static byte[] join(byte[]... parts) {
    var out = new ByteArrayOutputStream();
    for (byte[] part : parts) out.writeBytes(part);
    return out.toByteArray();
  }
}
