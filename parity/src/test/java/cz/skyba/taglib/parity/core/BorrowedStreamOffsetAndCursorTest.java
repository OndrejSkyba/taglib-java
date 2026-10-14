package cz.skyba.taglib.parity.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.parity.support.FileRefAdapter;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.internal.Binary;
import cz.skyba.taglib.parity.Snapshots;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.Variant;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Valid headers at nonzero origins ensure constructors really parse at their native cursor.
 */
@org.junit.jupiter.api.Tag("parity")
@org.junit.jupiter.api.Tag("core")
final class BorrowedStreamOffsetAndCursorTest {
  @TempDir
  Path directory;

  @TestFactory
  List<DynamicTest> validHeaderAtBorrowedCursor() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("parity/native")))
      root = root.getParent();
    assertNotNull(root);
    Path probe = root.resolve("parity/target/native-build/bin/file-reference-reads-and-mutations");
    if (Boolean.getBoolean("taglib.requireNative")) assertTrue(Files.isExecutable(probe));
    assumeTrue(Files.isExecutable(probe), "Build ./mvnw -Pparity verify");
    Path corpus = Snapshots.upstreamSource(root).resolve("tests/data");
    var tests = new ArrayList<DynamicTest>();
    for (String extension : List.of("wav", "aiff", "dsf", "dff", "shn", "wma", "s3m")) {
      Path source;
      try (var files = Files.list(corpus)) {
        source =
          files
            .filter(p -> p.getFileName().toString().endsWith("." + extension))
            .sorted()
            .findFirst()
            .orElse(null);
      }
      assertNotNull(source, "Pinned fixture for " + extension);
      byte[] original = Files.readAllBytes(source), bytes = new byte[original.length + 7];
      System.arraycopy(original, 0, bytes, 7, original.length);
      if (extension.equals("dsf")) {
        Binary.put64le(bytes, 19, Binary.u64le(original, 12) + 7);
        long metadata = Binary.u64le(original, 20);
        Binary.put64le(bytes, 27, metadata == 0 ? 0 : metadata + 7);
      }
      Path saveCopy = Files.write(directory.resolve("native-save." + extension), bytes);
      tests.add(
        DynamicTest.dynamicTest(
          extension + "/save-at-origin",
          () -> {
            var process =
              new ProcessBuilder(
                probe.toString(), "mutate-stream", saveCopy.toString(), "unicode")
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
            byte[] output = process.getInputStream().readAllBytes();
            assertEquals(0, process.waitFor());
            JsonNode expected = Snapshots.JSON.readTree(output);
            var stream =
              new ByteVectorStream(bytes) {
                @Override
                public String name() {
                  return saveCopy.toString();
                }
              };
            stream.seek(7);
            try (var ref = new FileRefAdapter(stream, false)) {
              assertEquals(expected.path("opened").asBoolean(), !ref.isNull());
              long constructor = stream.tell();
              boolean saved = false;
              if (!ref.isNull()) {
                ref.replaceProperties(
                  PropertyMap.builder()
                    .put("TITLE", "Žluťoučký 🐦")
                    .put("ARTIST", List.of("東京", "é"))
                    .put("COMMENT", "Line one\nLine two")
                    .put("CUSTOM", List.of("Ελληνικά", "العربية"))
                    .build());
                snapshot(ref);
                long before = stream.tell();
                saved = ref.save();
                assertEquals(
                  expected.path("borrowedStreamCursorBeforeSave").asLong(),
                  before,
                  "before save cursor");
              }
              assertEquals(expected.path("saved").asBoolean(), saved, "save outcome");
              assertEquals(
                expected.path("borrowedStreamCursorAfterConstruction").asLong(),
                constructor,
                "constructor cursor");
              assertEquals(
                expected.path("borrowedStreamCursorAfterSave").asLong(),
                stream.tell(),
                "save cursor");
              assertArrayEquals(
                Files.readAllBytes(saveCopy),
                stream.data().toByteArray(),
                "native physical save effect");
            }
          }));
      for (ReadStyle style : ReadStyle.values()) {
        Path copy = Files.write(directory.resolve("prefixed-" + style + "." + extension), bytes);
        tests.add(
          DynamicTest.dynamicTest(
            extension + "/" + style,
            () -> {
              var process =
                new ProcessBuilder(
                  probe.toString(), "snapshot-stream", copy.toString(), style.name())
                  .redirectError(ProcessBuilder.Redirect.INHERIT)
                  .start();
              byte[] output = process.getInputStream().readAllBytes();
              assertEquals(0, process.waitFor());
              JsonNode expected = Snapshots.JSON.readTree(output);
              var stream =
                new ByteVectorStream(bytes) {
                  @Override
                  public String name() {
                    return copy.toString();
                  }
                };
              stream.seek(7);
              try (var ref = new FileRefAdapter(stream, true, style)) {
                long afterConstructor = stream.tell();
                JsonNode actual = snapshot(ref);
                assertEquals(
                  List.of(), Snapshots.differences(expected.path("snapshot"), actual));
                assertEquals(
                  expected.path("cursorAfterConstruction").asLong(),
                  afterConstructor,
                  "constructor cursor");
                assertEquals(
                  expected.path("cursorAfterSnapshot").asLong(),
                  stream.tell(),
                  "snapshot cursor");
              }
              assertTrue(stream.isOpen(), "borrowed ownership");
            }));
      }
    }
    return tests;
  }

  @TestFactory
  List<DynamicTest> sourceScalarAndTruncationContracts() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("parity/native")))
      root = root.getParent();
    assertNotNull(root);
    Path probe = root.resolve("parity/target/native-build/bin/file-reference-reads-and-mutations");
    if (Boolean.getBoolean("taglib.requireNative")) assertTrue(Files.isExecutable(probe));
    assumeTrue(Files.isExecutable(probe), "Build ./mvnw -Pparity verify");
    Path corpus = Snapshots.upstreamSource(root).resolve("tests/data");
    byte[] dsf = Files.readAllBytes(corpus.resolve("empty10ms.dsf"));
    var tests = new ArrayList<DynamicTest>();
    for (int length : List.of(40, 44, 48, 52, 56, 60, 64, 68, 72, 76, 80, 84, 92)) {
      byte[] source = Arrays.copyOf(dsf, length);
      Binary.put64le(source, 12, length);
      Binary.put64le(source, 20, 0);
      tests.add(
        DynamicTest.dynamicTest(
          "dsf/truncated-format/" + length,
          () -> compareNativeRead(probe, "truncated-" + length + ".dsf", source)));
    }
    for (long value : List.of(0L, 2147483648L, 4294967295L)) {
      for (int field : List.of(52, 56, 60)) {
        byte[] source = Arrays.copyOf(dsf, 92);
        Binary.put64le(source, 12, source.length);
        Binary.put64le(source, 20, 0);
        Binary.put32le(source, field, value);
        tests.add(
          DynamicTest.dynamicTest(
            "dsf/unsigned-field/" + field + "/" + value,
            () -> compareNativeRead(probe, "scalar-" + field + "-" + value + ".dsf", source)));
      }
    }
    for (long samples : List.of(-1L, 3000000000000L, Long.MAX_VALUE)) {
      byte[] source = Arrays.copyOf(dsf, 92);
      Binary.put64le(source, 12, source.length);
      Binary.put64le(source, 20, 0);
      Binary.put64le(source, 64, samples);
      tests.add(
        DynamicTest.dynamicTest(
          "dsf/sample-count/" + samples,
          () -> compareNativeRead(probe, "samples-" + samples + ".dsf", source)));
    }
    for (int sampleHeader : List.of(0, 1, 4, 18, 19, 40)) {
      byte[] xm = new byte[80 + 33 + (sampleHeader == 0 ? 40 : sampleHeader)];
      System.arraycopy(Binary.bytes("Extended Module: "), 0, xm, 0, 17);
      xm[37] = 26;
      Binary.put32le(xm, 60, 20);
      xm[68] = 2;
      xm[72] = 1;
      Binary.put32le(xm, 80, 33);
      xm[107] = 1;
      Binary.put32le(xm, 109, sampleHeader);
      tests.add(
        DynamicTest.dynamicTest(
          "xm/sample-header-save/" + sampleHeader,
          () -> compareNativeUnicodeWrite(probe, "sample-" + sampleHeader + ".xm", xm)));
    }
    byte[] aiff = Files.readAllBytes(corpus.resolve("excessive_alloc.aif"));
    tests.add(
      DynamicTest.dynamicTest(
        "aiff/remove-final-anchor",
        () -> {
          Path copy = Files.write(directory.resolve("only-tags.aif"), aiff);
          var process =
            new ProcessBuilder(
              probe.toString(), "mutate-stream", copy.toString(), "save-only", "0")
              .start();
          JsonNode expected = Snapshots.JSON.readTree(process.getInputStream().readAllBytes());
          assertEquals(0, process.waitFor());
          var stream =
            new ByteVectorStream(aiff) {
              @Override
              public String name() {
                return copy.toString();
              }
            };
          try (var ref = new FileRefAdapter(stream, false)) {
            assertFalse(ref.isNull());
            assertEquals(expected.path("saved").asBoolean(), ref.save());
            assertEquals(
              expected.path("borrowedStreamCursorAfterSave").asLong(), stream.tell());
            assertArrayEquals(Files.readAllBytes(copy), stream.data().toByteArray());
          }
        }));
    return tests;
  }

  private void compareNativeUnicodeWrite(Path probe, String name, byte[] source) throws Exception {
    Path copy = Files.write(directory.resolve(name), source);
    var process =
      new ProcessBuilder(probe.toString(), "mutate-stream", copy.toString(), "unicode", "0")
        .start();
    JsonNode expected = Snapshots.JSON.readTree(process.getInputStream().readAllBytes());
    assertEquals(0, process.waitFor());
    var stream =
      new ByteVectorStream(source) {
        @Override
        public String name() {
          return copy.toString();
        }
      };
    try (var ref = new FileRefAdapter(stream, false)) {
      assertEquals(expected.path("opened").asBoolean(), !ref.isNull());
      assertFalse(ref.isNull(), "Synthetic source constructor");
      ref.replaceProperties(
        PropertyMap.builder()
          .put("TITLE", "Žluťoučký 🐦")
          .put("ARTIST", List.of("東京", "é"))
          .put("COMMENT", "Line one\nLine two")
          .put("CUSTOM", List.of("Ελληνικά", "العربية"))
          .build());
      assertEquals(expected.path("saved").asBoolean(), ref.save(), name);
      assertEquals(expected.path("borrowedStreamCursorAfterSave").asLong(), stream.tell(), name);
      assertArrayEquals(Files.readAllBytes(copy), stream.data().toByteArray(), name);
    }
  }

  private void compareNativeRead(Path probe, String name, byte[] source) throws Exception {
    Path copy = Files.write(directory.resolve(name), source);
    var process =
      new ProcessBuilder(probe.toString(), "snapshot-stream", copy.toString(), "ACCURATE", "0")
        .start();
    JsonNode expected = Snapshots.JSON.readTree(process.getInputStream().readAllBytes());
    assertEquals(0, process.waitFor());
    var stream =
      new ByteVectorStream(source) {
        @Override
        public String name() {
          return copy.toString();
        }
      };
    try (var ref = new FileRefAdapter(stream, true, ReadStyle.ACCURATE)) {
      long constructor = stream.tell();
      assertEquals(
        List.of(), Snapshots.differences(expected.path("snapshot"), snapshot(ref)), name);
      assertEquals(expected.path("cursorAfterConstruction").asLong(), constructor, name);
      assertEquals(expected.path("cursorAfterSnapshot").asLong(), stream.tell(), name);
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
