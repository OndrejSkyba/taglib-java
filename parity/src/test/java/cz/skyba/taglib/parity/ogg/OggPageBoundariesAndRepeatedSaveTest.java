package cz.skyba.taglib.parity.ogg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.parity.support.FileRefAdapter;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.parity.Snapshots;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.Variant;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Independently encoded native constructor, page-admission and default-save source branches.
 */
@org.junit.jupiter.api.Tag("parity")
@org.junit.jupiter.api.Tag("ogg")
final class OggPageBoundariesAndRepeatedSaveTest {
  @TempDir
  Path directory;

  private record Fixture(String name, byte[] bytes) {
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.ISO_8859_1);
  }

  private static byte[] join(byte[]... parts) {
    var out = new ByteArrayOutputStream();
    for (var p : parts) out.writeBytes(p);
    return out.toByteArray();
  }

  private static byte[] page(int serial, int sequence, int flags, long granule, byte[]... packets) {
    var laces = new ByteArrayOutputStream();
    var body = new ByteArrayOutputStream();
    for (var packet : packets) {
      for (int n = 0; n < packet.length / 255; n++) laces.write(255);
      laces.write(packet.length % 255);
      body.writeBytes(packet);
    }
    var out = ByteBuffer.allocate(27 + laces.size() + body.size()).order(ByteOrder.LITTLE_ENDIAN);
    out.put(bytes("OggS"))
      .put((byte) 0)
      .put((byte) flags)
      .putLong(granule)
      .putInt(serial)
      .putInt(sequence)
      .putInt(0)
      .put((byte) laces.size())
      .put(laces.toByteArray())
      .put(body.toByteArray());
    // Zero CRC intentionally proves native readers do not add checksum admission.
    return out.array();
  }

  private static byte[] comment() {
    byte[] vendor = bytes("vendor"), field = bytes("TITLE=old");
    var out = ByteBuffer.allocate(12 + vendor.length + field.length).order(ByteOrder.LITTLE_ENDIAN);
    return out.putInt(vendor.length).put(vendor).putInt(1).putInt(field.length).put(field).array();
  }

  private static byte[] vorbis() {
    byte[] id = new byte[30];
    System.arraycopy(bytes("\u0001vorbis"), 0, id, 0, 7);
    id[11] = 2;
    ByteBuffer.wrap(id).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 48000).putInt(20, 128000);
    return id;
  }

  private static byte[] opus(int length) {
    byte[] id = new byte[length];
    System.arraycopy(bytes("OpusHead"), 0, id, 0, Math.min(8, length));
    if (length > 9) id[9] = 2;
    if (length > 10) id[10] = 7;
    return id;
  }

  private static byte[] speex(int length) {
    byte[] id = new byte[length];
    System.arraycopy(bytes("Speex   "), 0, id, 0, Math.min(8, length));
    if (length >= 64)
      ByteBuffer.wrap(id)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(36, 48000)
        .putInt(48, 2)
        .putInt(52, 128000);
    return id;
  }

  private static byte[] block(int type, int declared, byte[] data) {
    return join(
      new byte[]{
        (byte) type, (byte) (declared >>> 16), (byte) (declared >>> 8), (byte) declared
      },
      data);
  }

  private static byte[] flac(int major, int minor, int firstType, int length) {
    byte[] info = new byte[length];
    if (length >= 18) {
      long packed = (48000L << 44) | (1L << 41) | (15L << 36) | 48000;
      ByteBuffer.wrap(info).putLong(10, packed);
    }
    return join(
      new byte[]{127, 'F', 'L', 'A', 'C', (byte) major, (byte) minor, 0, 1, 'f', 'L', 'a', 'C'},
      block(firstType, length, info));
  }

  private static List<Fixture> fixtures() {
    var fixtures = new ArrayList<Fixture>();
    byte[] id = vorbis(), comm = join(bytes("\u0003vorbis"), comment(), new byte[]{1});
    byte[] base =
      join(page(1, 0, 2, 0, id), page(1, 1, 0, 0, comm), page(1, 2, 4, 48000, bytes("audio")));
    fixtures.add(new Fixture("no-setup.ogg", base));
    fixtures.add(
      new Fixture(
        "unrecognized-setup.ogg",
        join(
          page(1, 0, 2, 0, id),
          page(1, 1, 0, 0, comm, bytes("wrong setup")),
          page(1, 2, 4, 48000, bytes("audio")))));
    fixtures.add(
      new Fixture(
        "no-identification.ogg",
        join(page(1, 0, 2, 0, bytes("id?")), page(1, 1, 4, 48000, comm))));
    fixtures.add(new Fixture("leading-garbage.ogg", join(bytes("garbage-prefix"), base)));
    byte[] version = base.clone();
    version[4] = 7;
    fixtures.add(new Fixture("version-seven.ogg", version));
    fixtures.add(
      new Fixture(
        "nonconsecutive-sequence.ogg",
        join(
          page(1, 20, 2, 0, id),
          page(1, 77, 0, 0, comm),
          page(1, 92, 4, 48000, bytes("audio")))));
    fixtures.add(
      new Fixture(
        "continuation-without-start.ogg",
        join(
          page(1, 0, 3, 0, id),
          page(1, 1, 1, 0, comm),
          page(1, 2, 4, 48000, bytes("audio")))));
    fixtures.add(
      new Fixture("foreign-last-page.ogg", join(base, page(2, 0, 6, 96000, bytes("foreign")))));
    fixtures.add(
      new Fixture(
        "invalid-last-capture.ogg", join(base, bytes("OggS invalid terminal capture"))));
    fixtures.add(
      new Fixture(
        "multiplexed-bos.ogg",
        join(
          page(2, 0, 2, 100, bytes("video")),
          page(1, 0, 2, 0, id),
          page(1, 1, 4, 48000, comm))));
    fixtures.add(
      new Fixture(
        "late-bos.ogg",
        join(page(2, 0, 2, 0, bytes("video")), page(2, 1, 0, 0, bytes("badcomment")), base)));
    fixtures.add(new Fixture("comment-only.oga", base));
    for (int n : new int[]{8, 9, 10, 11, 12, 18, 19})
      fixtures.add(
        new Fixture(
          "id-" + n + ".opus",
          join(
            page(1, 0, 2, 0, opus(n)),
            page(1, 1, 0, 0, join(bytes("OpusTags"), comment())),
            page(1, 2, 4, 48000, bytes("audio")))));
    // Eleven-byte identification headers expose native partial little-endian conversion.
    // The original low value 7 rounded to the same millisecond as a missing pre-skip.
    for (int skip : new int[]{0, 1, 24, 25, 127, 128, 255})
      for (long end : new long[]{48000, skip}) {
        byte[] shortId = opus(11);
        shortId[10] = (byte) skip;
        fixtures.add(
          new Fixture(
            "partial-preskip-" + skip + "-" + end + ".opus",
            join(
              page(1, 0, 2, 0, shortId),
              page(1, 1, 0, 0, join(bytes("OpusTags"), comment())),
              page(1, 2, 4, end, bytes("audio")))));
      }
    fixtures.add(new Fixture("missing-tags.opus", page(1, 0, 6, 48000, opus(19))));
    for (int n : new int[]{8, 63, 64, 80})
      fixtures.add(
        new Fixture(
          "id-" + n + ".spx",
          join(page(1, 0, 2, 0, speex(n)), page(1, 1, 4, 48000, comment()))));
    fixtures.add(new Fixture("missing-comment.spx", page(1, 0, 6, 48000, speex(80))));
    for (int[] versionPair : new int[][]{{1, 0}, {1, 9}, {2, 0}, {2, 9}})
      fixtures.add(
        new Fixture(
          "mapping-" + versionPair[0] + "-" + versionPair[1] + ".oga",
          join(
            page(1, 0, 2, 0, flac(versionPair[0], versionPair[1], 0, 34)),
            page(1, 1, 4, 48000, block(132, comment().length, comment())))));
    for (int n : new int[]{0, 1, 17, 18, 33, 34})
      fixtures.add(
        new Fixture(
          "streaminfo-" + n + ".oga",
          join(
            page(1, 0, 2, 0, flac(1, 0, 0, n)),
            page(1, 1, 4, 48000, block(132, comment().length, comment())))));
    fixtures.add(
      new Fixture(
        "declared-long.oga",
        join(
          page(1, 0, 2, 0, flac(1, 0, 0, 34)),
          page(1, 1, 4, 48000, block(132, comment().length + 50, comment())))));
    fixtures.add(
      new Fixture(
        "missing-last-flag.oga",
        join(
          page(1, 0, 2, 0, flac(1, 0, 0, 34)),
          page(1, 1, 0, 0, block(4, comment().length, comment())),
          page(1, 2, 4, 48000, new byte[]{(byte) 255, (byte) 248, 0, 0, 1}))));
    fixtures.add(new Fixture("no-comment.oga", page(1, 0, 6, 48000, flac(1, 0, 128, 34))));
    fixtures.add(
      new Fixture(
        "historical.oga",
        join(
          page(1, 0, 2, 0, bytes("fLaC")),
          page(1, 1, 0, 0, block(0, 34, Arrays.copyOfRange(flac(1, 0, 0, 34), 17, 51))),
          page(1, 2, 4, 48000, block(132, comment().length, comment())))));
    fixtures.add(
      new Fixture(
        "non-streaminfo-first.oga",
        join(
          page(1, 0, 2, 0, flac(1, 0, 4, 34)),
          page(1, 1, 4, 0, block(132, comment().length, comment())))));
    fixtures.add(
      new Fixture(
        "no-id-comment-only.oga",
        join(page(1, 0, 2, 0, bytes("id?")), page(1, 1, 4, 0, comm))));
    fixtures.add(new Fixture("leading-garbage.bin", join(bytes("garbage-prefix"), base)));
    byte[] shortBody = page(1, 1, 4, 48000, comm);
    shortBody[27] = (byte) ((shortBody[27] & 255) + 10);
    fixtures.add(new Fixture("truncated-page-body.ogg", join(page(1, 0, 2, 0, id), shortBody)));
    for (int count : new int[]{1023, 1024, 1025}) {
      var parts = new ArrayList<byte[]>();
      parts.add(page(1, 0, 2, 0, flac(1, 0, 0, 34)));
      for (int i = 1; i <= count; i++)
        parts.add(page(1, i, i == count ? 4 : 0, 0, block(i == count ? 129 : 1, 0, new byte[0])));
      fixtures.add(
        new Fixture("block-count-" + count + ".oga", join(parts.toArray(byte[][]::new))));
    }
    return fixtures;
  }

  private Path probe() {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("parity/native")))
      root = root.getParent();
    assertNotNull(root);
    Path probe = root.resolve("parity/target/native-build/bin/file-reference-reads-and-mutations");
    if (Boolean.getBoolean("taglib.requireNative")) assertTrue(Files.isExecutable(probe));
    assumeTrue(Files.isExecutable(probe));
    return probe;
  }

  @TestFactory
  List<DynamicTest> nativeSourceReads() {
    Path probe = probe();
    var tests = new ArrayList<DynamicTest>();
    for (var fixture : fixtures())
      for (String style : List.of("FAST", "AVERAGE", "ACCURATE", "TAGS_ONLY"))
        for (String transport : List.of("path", "0", "7", "prefix7"))
          tests.add(
            DynamicTest.dynamicTest(
              fixture.name + "/" + style + "/" + transport,
              () -> compareRead(probe, fixture, style, transport)));
    return tests;
  }

  private void compareRead(Path probe, Fixture fixture, String style, String transport)
    throws Exception {
    byte[] source = transport.equals("prefix7") ? join(new byte[7], fixture.bytes) : fixture.bytes;
    Path copy = Files.write(directory.resolve(fixture.name), source);
    boolean path = transport.equals("path");
    String offset = transport.equals("0") ? "0" : "7";
    var args =
      new ArrayList<>(
        List.of(
          probe.toString(), path ? "snapshot" : "snapshot-stream", copy.toString(), style));
    if (!path) args.add(offset);
    var process = new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.PIPE).start();
    JsonNode expected = nativeResult(process, probe, fixture, copy, "constructor", offset);
    var stream =
      new ByteVectorStream(source) {
        @Override
        public String name() {
          return copy.toString();
        }
      };
    if (!path) stream.seek(Long.parseLong(offset));
    try (var ref =
           path
             ? new FileRefAdapter(
             copy,
             !style.equals("TAGS_ONLY"),
             ReadStyle.valueOf(style.equals("TAGS_ONLY") ? "AVERAGE" : style))
             : new FileRefAdapter(
             stream,
             !style.equals("TAGS_ONLY"),
             ReadStyle.valueOf(style.equals("TAGS_ONLY") ? "AVERAGE" : style))) {
      long cursor = stream.tell();
      assertEquals(
        List.of(),
        Snapshots.differences(path ? expected : expected.path("snapshot"), snapshot(ref)),
        fixture.name + "/" + style + "/" + transport);
      if (!path) {
        assertEquals(
          expected.path("cursorAfterConstruction").asLong(), cursor, "constructor cursor");
        assertEquals(
          expected.path("cursorAfterSnapshot").asLong(), stream.tell(), "snapshot cursor");
      }
    }
  }

  @TestFactory
  List<DynamicTest> nativeDefaultWrites() {
    Path probe = probe();
    var tests = new ArrayList<DynamicTest>();
    for (var fixture : fixtures())
      for (String operation : List.of("save-only", "unicode"))
        for (String offset : List.of("0", "7"))
          tests.add(
            DynamicTest.dynamicTest(
              fixture.name + "/" + operation + "/" + offset,
              () -> compareWrite(probe, fixture, operation, offset)));
    return tests;
  }

  private void compareWrite(Path probe, Fixture fixture, String operation, String offset)
    throws Exception {
    if (deferNativeFault(fixture, operation, offset)) {
      assertSafeUndefinedGrowth(fixture, offset, operation);
      return;
    }
    Path copy = Files.write(directory.resolve(fixture.name), fixture.bytes);
    var process =
      new ProcessBuilder(probe.toString(), "mutate-stream", copy.toString(), operation, offset)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .start();
    JsonNode expected = nativeResult(process, probe, fixture, copy, operation, offset);
    if (expected == null) {
      assertSafeUndefinedGrowth(fixture, offset, operation);
      return;
    }
    var stream =
      new ByteVectorStream(fixture.bytes) {
        @Override
        public String name() {
          return copy.toString();
        }
      };
    stream.seek(Long.parseLong(offset));
    try (var ref = new FileRefAdapter(stream, false)) {
      assertEquals(expected.path("opened").asBoolean(), !ref.isNull());
      long cursor = stream.tell();
      boolean saved = false;
      if (!ref.isNull()) {
        if (operation.equals("unicode"))
          ref.replaceProperties(
            PropertyMap.builder()
              .put("TITLE", "Žluťoučký 🐦")
              .put("ARTIST", List.of("東京", "é"))
              .put("COMMENT", "Line one\nLine two")
              .put("CUSTOM", List.of("Ελληνικά", "العربية"))
              .build());
        saved = ref.save();
      }
      assertEquals(expected.path("saved").asBoolean(), saved, "save outcome");
      assertEquals(
        expected.path("borrowedStreamCursorAfterConstruction").asLong(),
        cursor,
        "constructor cursor");
      assertEquals(
        expected.path("borrowedStreamCursorAfterSave").asLong(), stream.tell(), "save cursor");
      assertArrayEquals(
        Files.readAllBytes(copy), stream.data().toByteArray(), "complete native output");
    }
  }

  @TestFactory
  List<DynamicTest> nativeRepeatedWrites() {
    Path probe = probe();
    var tests = new ArrayList<DynamicTest>();
    for (var fixture : fixtures())
      for (String offset : List.of("0", "7"))
        tests.add(
          DynamicTest.dynamicTest(
            fixture.name + "/repeat/" + offset, () -> compareRepeat(probe, fixture, offset)));
    return tests;
  }

  private void compareRepeat(Path probe, Fixture fixture, String offset) throws Exception {
    if (deferNativeFault(fixture, "grow-shrink", offset)) {
      assertSafeUndefinedGrowth(fixture, offset, "grow-shrink");
      return;
    }
    Path copy = Files.write(directory.resolve(fixture.name), fixture.bytes);
    var process =
      new ProcessBuilder(
        probe.toString(), "mutate-stream-twice", copy.toString(), "grow-shrink", offset)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .start();
    JsonNode expected = nativeResult(process, probe, fixture, copy, "grow-shrink", offset);
    if (expected == null) {
      assertSafeUndefinedGrowth(fixture, offset, "grow-shrink");
      return;
    }
    var stream =
      new ByteVectorStream(fixture.bytes) {
        @Override
        public String name() {
          return copy.toString();
        }
      };
    stream.seek(Long.parseLong(offset));
    try (var ref = new FileRefAdapter(stream, false)) {
      assertEquals(expected.path("opened").asBoolean(), !ref.isNull());
      boolean saved = false;
      if (!ref.isNull()) {
        ref.replaceProperties(PropertyMap.builder().put("TITLE", "x".repeat(8192)).build());
        saved = ref.save();
        if (saved) {
          ref.tag().setTitle("x");
          saved = ref.save();
        }
      }
      assertEquals(expected.path("saved").asBoolean(), saved, "repeated native result");
      assertEquals(
        expected.path("borrowedStreamCursorAfterSave").asLong(),
        stream.tell(),
        "repeated save cursor");
      assertArrayEquals(
        Files.readAllBytes(copy), stream.data().toByteArray(), "complete repeated native output");
    }
  }

  private static boolean deferNativeFault(Fixture fixture, String operation, String offset)
    throws Exception {
    if (!fixture.name.equals("truncated-page-body.ogg")
      || !Set.of("unicode", "grow-shrink").contains(operation)) return false;
    String sha =
      java.util.HexFormat.of()
        .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(fixture.bytes));
    assertEquals(
      "63816c5d0cb9cf8830e23eb908622b5b8379062920cbc6563df9de9fc74e432c",
      sha,
      "Changed safety fixture requires review");
    assertTrue(Set.of("0", "7").contains(offset));
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("parity/native")))
      root = root.getParent();
    assertNotNull(root);
    Path evidence =
      Path.of(
          System.getProperty(
            "ogg.source.evidence",
            root.resolve("parity/target/ogg-safety").toString()))
        .resolve(fixture.name + "-" + operation + "-" + offset);
    Files.createDirectories(evidence);
    Files.write(evidence.resolve("input.bin"), fixture.bytes);
    Files.writeString(
      evidence.resolve("java-safety.json"),
      Snapshots.JSON
        .writerWithDefaultPrettyPrinter()
        .writeValueAsString(
          Map.of(
            "executed",
            false,
            "inputSha256",
            sha,
            "operation",
            operation,
            "origin",
            offset,
            "scope",
            "Java unchanged-byte rejection only; native fault not executed")));
    return true;
  }

  private static JsonNode nativeResult(
    Process process, Path probe, Fixture fixture, Path copy, String operation, String offset)
    throws Exception {
    byte[] stdout = process.getInputStream().readAllBytes(),
      stderr = process.getErrorStream().readAllBytes();
    int exit = process.waitFor();
    if (exit == 0) return Snapshots.JSON.readTree(stdout);
    String sha =
      java.util.HexFormat.of()
        .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(fixture.bytes));
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("parity/native")))
      root = root.getParent();
    assertNotNull(root);
    Path evidence =
      Path.of(
          System.getProperty(
            "ogg.source.evidence", root.resolve("parity/target/ogg-source-crashes").toString()))
        .resolve(fixture.name + "-" + operation + "-" + offset);
    Files.createDirectories(evidence);
    Files.write(evidence.resolve("input.bin"), fixture.bytes);
    Files.write(evidence.resolve("native-output.bin"), Files.readAllBytes(copy));
    Files.write(evidence.resolve("native.stdout"), stdout);
    Files.write(evidence.resolve("native.stderr"), stderr);
    Files.writeString(
      evidence.resolve("outcome.txt"),
      "exit="
        + exit
        + "\ninput-sha256="
        + sha
        + "\noperation="
        + operation
        + "\norigin="
        + offset
        + "\nnative-sha256="
        + java.util.HexFormat.of()
        .formatHex(
          java.security.MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(probe)))
        + "\n");
    fail(
      "Unexpected native failure: "
        + fixture.name
        + " / "
        + operation
        + " exit="
        + exit
        + "; retained diagnostics: "
        + evidence);
    return null;
  }

  private static void assertSafeUndefinedGrowth(Fixture fixture, String offset, String operation) {
    var stream =
      new ByteVectorStream(fixture.bytes) {
        @Override
        public String name() {
          return fixture.name;
        }
      };
    stream.seek(Long.parseLong(offset));
    try (var ref = new FileRefAdapter(stream, false)) {
      assertFalse(ref.isNull());
      if (operation.equals("unicode"))
        ref.replaceProperties(
          PropertyMap.builder()
            .put("TITLE", "Žluťoučký 🐦")
            .put("ARTIST", List.of("東京", "é"))
            .put("COMMENT", "Line one\nLine two")
            .put("CUSTOM", List.of("Ελληνικά", "العربية"))
            .build());
      else ref.replaceProperties(PropertyMap.builder().put("TITLE", "x".repeat(8192)).build());
      assertFalse(ref.save(), "Java must reject the undefined growing insertion");
      assertArrayEquals(
        fixture.bytes, stream.data().toByteArray(), "Rejected growth must not mutate bytes");
    }
  }

  private static ObjectNode snapshot(FileRefAdapter ref) {
    if (ref.isNull()) return Snapshots.invalid();
    var result = Snapshots.JSON.createObjectNode().put("valid", true);
    result.set("properties", Snapshots.JSON.valueToTree(ref.properties().asMap()));
    result.set("unsupportedData", Snapshots.JSON.valueToTree(ref.properties().unsupportedData()));
    var tag = ref.tag();
    result
      .putObject("common")
      .put("title", tag.title())
      .put("artist", tag.artist())
      .put("album", tag.album())
      .put("comment", tag.comment())
      .put("genre", tag.genre())
      .put("year", tag.year())
      .put("track", tag.track());
    var audio = ref.audioProperties();
    if (audio == null) audio = AudioProperties.EMPTY;
    result
      .putObject("audio")
      .put("lengthInMilliseconds", audio.lengthInMilliseconds())
      .put("bitrate", audio.bitrate())
      .put("sampleRate", audio.sampleRate())
      .put("channels", audio.channels());
    var pictures = result.putArray("pictures");
    for (var p : ref.complexProperties("PICTURE")) {
      byte[] bytes = p.getOrDefault("data", new Variant()).toByteVector().toByteArray();
      pictures
        .addObject()
        .put(
          "type",
          cz.skyba.taglib.flac.Picture.typeFromString(
              p.getOrDefault("pictureType", new Variant()).toText())
            .code())
        .put("mimeType", p.getOrDefault("mimeType", new Variant()).toText())
        .put("description", p.getOrDefault("description", new Variant()).toText())
        .put("size", bytes.length)
        .put("fnv1a64", Snapshots.fnv(bytes))
        .put("width", p.getOrDefault("width", new Variant()).toInt())
        .put("height", p.getOrDefault("height", new Variant()).toInt())
        .put("colorDepth", p.getOrDefault("colorDepth", new Variant()).toInt())
        .put("indexedColors", p.getOrDefault("numColors", new Variant()).toInt());
    }
    return result;
  }
}
