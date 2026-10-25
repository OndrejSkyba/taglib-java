package cz.skyba.taglib.parity.mp4;

import com.fasterxml.jackson.databind.JsonNode;
import cz.skyba.taglib.parity.support.FileRefAdapter;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.MetadataDocument;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.TagLib;
import cz.skyba.taglib.parity.Snapshots;
import cz.skyba.taglib.toolkit.Variant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Source-derived probes for MP4 layouts beyond the original released fixture corpus.
 */
@org.junit.jupiter.api.Tag("parity")
@org.junit.jupiter.api.Tag("mp4")
final class Mp4AtomLayoutAndRepeatedSaveTest {
  @TempDir
  Path directory;

  @Test
  void malformedMetadataPaddingKeepsUpstreamReadBehavior() throws Exception {
    Path tool = oracle();
    byte[] oversizedFree = atom("free", new byte[16]);
    be(oversizedFree, 0, oversizedFree.length + 22);
    var paddingCases = List.of(new byte[4], new byte[6], oversizedFree);
    for (int i = 0; i < paddingCases.size(); i++) {
      byte[] item = atom("©nam", atom("data", join(
        new byte[]{0, 0, 0, 1, 0, 0, 0, 0}, "Readable title".getBytes(StandardCharsets.UTF_8))));
      byte[] source = join(
        atom("ftyp", new byte[]{'M', '4', 'A', ' ', 0, 0, 0, 0, 'i', 's', 'o', 'm'}),
        atom("mdat", new byte[8]),
        atom("moov", atom("udta", atom("meta", join(
          new byte[4], atom("hdlr", new byte[25]), atom("ilst", item), paddingCases.get(i))))),
        atom("free", new byte[64]));
      Path path = save("metadata-padding-" + i + ".m4a", source);

      JsonNode expected = command(tool, "snapshot", path, "TAGS_ONLY");
      assertTrue(expected.path("valid").asBoolean());
      var file = TagLib.read(path, ReadOptions.TAGS_ONLY);
      var actual = Snapshots.of(file);
      actual.set("unsupportedData", Snapshots.JSON.valueToTree(file.properties().unsupportedData()));
      assertEquals(List.of(), Snapshots.differences(expected, actual));
      assertEquals(List.of("Readable title"),
        MetadataDocument.open(path).entries().getFirst().values());
      assertArrayEquals(source, Files.readAllBytes(path));
    }
  }

  @Test
  void nativeIgnoredIndexAtomsAndShortChunkTablesDoNotRejectCommonSave() throws Exception {
    Path oracle = oracle();
    var requested =
      PropertyMap.builder()
        .put("TITLE", "Žluťoučký 🐦")
        .put("ARTIST", List.of("東京", "é"))
        .put("COMMENT", "Line one\nLine two")
        .put("CUSTOM", List.of("Ελληνικά", "العربية"))
        .build();
    // Native MP4::Tag::updateOffsets visits stco/co64/first-moof tfhd only. Its
    // ignored index/auxiliary atoms remain present and cannot introduce a stricter setter guard.
    for (String ignored : List.of("sidx", "mfra", "saio", "iloc")) {
      byte[] source = movie(ignored, atom("stco", new byte[8]));
      Path java = save("java-" + ignored + ".m4a", source),
        nativeCopy = save("native-" + ignored + ".m4a", source);
      JsonNode expected = command(oracle, "mutate", nativeCopy, "unicode");
      assertTrue(expected.path("opened").asBoolean());
      assertTrue(expected.path("saved").asBoolean());
      assertEquals(PropertyMap.EMPTY, TagLib.write(java, requested).rejected());
      assertEquals(List.of(), Snapshots.differences(expected.get("post"), snapshot(java)));
      assertArrayEquals(payload(source, ignored), payload(Files.readAllBytes(java), ignored));
    }
    for (String kind : List.of("stco", "co64")) {
      byte[] table = new byte[8];
      be(table, 4, 100); // Native bounds iteration by available data.
      byte[] source = movie("", atom(kind, table));
      Path java = save("java-short-" + kind + ".m4a", source),
        nativeCopy = save("native-short-" + kind + ".m4a", source);
      var expected = command(oracle, "mutate", nativeCopy, "unicode");
      assertTrue(expected.path("saved").asBoolean());
      TagLib.write(java, requested);
      assertEquals(List.of(), Snapshots.differences(expected.get("post"), snapshot(java)));
    }
  }

  @Test
  void nativeImplicitAndRecoveredCoverFormatsRetainExactFactorySemantics() throws Exception {
    Path tool = oracle();
    byte[] png = {(byte) 137, 'P', 'N', 'G', 13, 10, 26, 10};
    byte[] badPng = {(byte) 137, 'P', 'N', 'G', 0, 0, 0, 0};
    byte[] badBmp = new byte[14];
    badBmp[0] = 'B';
    badBmp[1] = 'M';
    badBmp[6] = 1;
    byte[][] values = {
      png,
      badPng,
      badBmp,
      png,
      new byte[]{(byte) 255, (byte) 216},
      new byte[]{'G', 'I', 'F', '8', '9', 'a'}
    };
    for (int i = 0; i < values.length; i++) {
      byte[] type = {0, 0, 0, (byte) (i == 0 ? 0 : 1), 0, 0, 0, 0};
      byte[] source =
        atom(
          "moov",
          atom(
            "udta",
            atom(
              "meta",
              join(
                new byte[4],
                atom("ilst", atom("covr", atom("data", join(type, values[i]))))))));
      Path nativeCopy = save("native-cover-type-" + i + ".m4a", source);
      Path javaCopy = save("java-cover-type-" + i + ".m4a", source);
      var nativeRead = command(tool, "snapshot", nativeCopy, "TAGS_ONLY");
      var file = TagLib.read(javaCopy, ReadOptions.TAGS_ONLY);
      var actual = Snapshots.of(file);
      actual.set(
        "unsupportedData", Snapshots.JSON.valueToTree(file.properties().unsupportedData()));
      assertEquals(List.of(), Snapshots.differences(nativeRead, actual), "cover type " + i);
      var nativeWrite = command(tool, "mutate", nativeCopy, "save-only");
      try (var reference = new FileRefAdapter(javaCopy, false)) {
        assertTrue(reference.save());
      }
      assertTrue(nativeWrite.path("saved").asBoolean());
      assertArrayEquals(
        Files.readAllBytes(nativeCopy), Files.readAllBytes(javaCopy), "cover type " + i);
    }
  }

  @Test
  void nativeOffsetThresholdAndUint32OverflowAreReproduced() throws Exception {
    Path oracle = oracle();
    byte[] table = new byte[24];
    be(table, 4, 4);
    byte[] source = movie("", atom("stco", table));
    int ilst = index(source, "ilst") - 4;
    int stco = index(source, "stco") + 4;
    // Native increments offsets strictly greater than the edit's original byte
    // position, then narrows stco results to uint32 without promoting to co64.
    for (int i = 0; i < 4; i++)
      be(source, stco + 8 + i * 4, new long[]{ilst, ilst + 1L, 0xfffffff0L, 0}[i]);
    Path java = save("java-offsets.m4a", source), nativeCopy = save("native-offsets.m4a", source);
    var expected = command(oracle, "mutate", nativeCopy, "unicode");
    assertTrue(expected.path("saved").asBoolean());
    var p =
      PropertyMap.builder()
        .put("TITLE", "Žluťoučký 🐦")
        .put("ARTIST", List.of("東京", "é"))
        .put("COMMENT", "Line one\nLine two")
        .put("CUSTOM", List.of("Ελληνικά", "العربية"))
        .build();
    TagLib.write(java, p);
    assertEquals(List.of(), Snapshots.differences(expected.get("post"), snapshot(java)));
    assertArrayEquals(
      payload(Files.readAllBytes(nativeCopy), "stco"), payload(Files.readAllBytes(java), "stco"));
  }

  @Test
  void nativeStemSetterCreatesEmptyItemsAndRemovesTheNativeParent() throws Exception {
    Path tool = oracle();
    byte[] item =
      atom("©nam", atom("data", join(new byte[]{0, 0, 0, 1, 0, 0, 0, 0}, new byte[]{'x'})));
    byte[] metadata = atom("meta", join(new byte[4], atom("ilst", item)));
    for (boolean existing : List.of(false, true))
      for (long origin : List.of(0L, 7L))
        for (String scenario : List.of("stem-replace", "stem-empty", "stem-remove")) {
          byte[] source =
            atom(
              "moov",
              atom(
                "udta",
                join(metadata, existing ? atom("stem", new byte[]{1, 2, 3}) : new byte[0])));
          Path nativeCopy =
            save(
              "native-stem-setter-" + existing + '-' + origin + '-' + scenario + ".m4a",
              source);
          var process =
            new ProcessBuilder(
              tool.toString(),
              "mutate-stream",
              nativeCopy.toString(),
              scenario,
              Long.toString(origin))
              .start();
          byte[] output = process.getInputStream().readAllBytes();
          assertEquals(0, process.waitFor());
          var expected = Snapshots.JSON.readTree(output);
          assertTrue(expected.path("pictureSupported").asBoolean());
          var memory =
            new cz.skyba.taglib.toolkit.ByteVectorStream(source) {
              @Override
              public String name() {
                return "test.m4a";
              }
            };
          memory.seek(origin);
          try (var file = new FileRefAdapter(memory, false)) {
            byte[] manifest =
              scenario.equals("stem-empty")
                ? new byte[0]
                : "{\"stem\":1}".getBytes(StandardCharsets.UTF_8);
            List<Map<String, Variant>> values =
              scenario.equals("stem-remove")
                ? List.of()
                : List.of(
                Map.of(
                  "manifest", new Variant(new cz.skyba.taglib.toolkit.ByteVector(manifest))));
            assertTrue(file.setComplexProperties("STEM", values));
            var actualStem = file.complexProperties("STEM");
            if (actualStem.isEmpty()) assertTrue(expected.path("beforeSaveStemData").isNull());
            else
              assertEquals(
                expected.path("beforeSaveStemData").asText(),
                HexFormat.of()
                  .formatHex(
                    actualStem.getFirst().get("manifest").toByteVector().toByteArray()));
            assertEquals(
              expected.path("beforeSaveComplexPropertyKeys"),
              Snapshots.JSON.valueToTree(file.complexPropertyKeys()));
            assertEquals(
              expected.path("beforeSave").path("unsupportedData"),
              Snapshots.JSON.valueToTree(file.properties().unsupportedData()));
            assertTrue(file.save());
            assertEquals(
              expected.path("borrowedStreamCursorAfterSave").asLong(), memory.tell(), scenario);
          }
          assertArrayEquals(Files.readAllBytes(nativeCopy), memory.data().toByteArray(), scenario);
        }
  }

  @Test
  void nativeStemUnsupportedRemovalUsesTheOriginalParentExtent() throws Exception {
    Path tool = oracle();
    byte[] item =
      atom("©nam", atom("data", join(new byte[]{0, 0, 0, 1, 0, 0, 0, 0}, new byte[]{'x'})));
    byte[] source =
      atom(
        "moov",
        atom(
          "udta",
          join(
            atom("meta", join(new byte[4], atom("ilst", item))),
            atom("stem", "{}".getBytes(StandardCharsets.UTF_8)))));
    for (String scenario : List.of("save-only", "remove-unsupported"))
      for (long origin : List.of(0L, 7L)) {
        Path nativeCopy = save("native-stem-" + scenario + '-' + origin + ".m4a", source);
        var process =
          new ProcessBuilder(
            tool.toString(),
            "mutate-stream",
            nativeCopy.toString(),
            scenario,
            Long.toString(origin))
            .start();
        byte[] output = process.getInputStream().readAllBytes();
        assertEquals(0, process.waitFor());
        JsonNode expected = Snapshots.JSON.readTree(output);
        var memory =
          new cz.skyba.taglib.toolkit.ByteVectorStream(source) {
            @Override
            public String name() {
              return "test.m4a";
            }
          };
        memory.seek(origin);
        try (var file = new FileRefAdapter(memory, false)) {
          assertFalse(file.isNull());
          assertEquals(List.of("stem"), file.properties().unsupportedData());
          assertEquals(List.of("STEM"), file.complexPropertyKeys());
          if (scenario.equals("remove-unsupported"))
            file.removeUnsupportedProperties(file.properties().unsupportedData());
          assertEquals(
            expected.path("beforeSaveComplexPropertyKeys"),
            Snapshots.JSON.valueToTree(file.complexPropertyKeys()));
          assertTrue(file.save());
          assertEquals(
            expected.path("borrowedStreamCursorAfterSave").asLong(), memory.tell(), scenario);
        }
        assertArrayEquals(Files.readAllBytes(nativeCopy), memory.data().toByteArray(), scenario);
      }
  }

  @Test
  void nativeRepeatedSaveRetainsConstructorAtomExtents() throws Exception {
    Path tool = oracle();
    byte[] item =
      atom("©nam", atom("data", join(new byte[]{0, 0, 0, 1, 0, 0, 0, 0}, new byte[]{'x'})));
    var layouts = new LinkedHashMap<String, byte[]>();
    layouts.put("chunk-table", movie("", atom("stco", new byte[8])));
    layouts.put(
      "padding",
      atom(
        "moov",
        atom(
          "udta",
          atom(
            "meta",
            join(
              new byte[4],
              atom("free", new byte[16]),
              atom("ilst", item),
              atom("free", new byte[32]))))));
    layouts.put(
      "fragment",
      join(
        atom("moov", atom("udta", atom("meta", join(new byte[4], atom("ilst", item))))),
        atom(
          "moof",
          atom(
            "traf",
            atom(
              "tfhd",
              new byte[]{0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, (byte) 255})))));
    layouts.put("new-udta", atom("moov", atom("free", new byte[8])));
    layouts.put("new-meta", atom("moov", atom("udta", atom("free", new byte[8]))));
    layouts.put(
      "new-ilst",
      atom("moov", atom("udta", atom("meta", join(new byte[4], atom("free", new byte[8]))))));
    layouts.put(
      "extended",
      extended(
        "moov", extended("udta", extended("meta", join(new byte[4], atom("ilst", item))))));
    layouts.put("extended-new-udta", extended("moov", atom("free", new byte[8])));
    layouts.put("extended-new-meta", extended("moov", extended("udta", atom("free", new byte[8]))));
    for (var layout : layouts.entrySet())
      for (long origin : List.of(0L, 7L)) {
        byte[] source = layout.getValue();
        Path nativeCopy = save("native-repeat-" + layout.getKey() + '-' + origin + ".m4a", source);
        var process =
          new ProcessBuilder(
            tool.toString(),
            "mutate-stream-twice",
            nativeCopy.toString(),
            "common-0",
            Long.toString(origin))
            .start();
        byte[] output = process.getInputStream().readAllBytes();
        assertEquals(0, process.waitFor());
        JsonNode expected = Snapshots.JSON.readTree(output);
        assertEquals(2, expected.path("methodCalls").path("save").asInt());
        var memory =
          new cz.skyba.taglib.toolkit.ByteVectorStream(source) {
            @Override
            public String name() {
              return "test.m4a";
            }
          };
        memory.seek(origin);
        try (var file = new FileRefAdapter(memory, false)) {
          assertFalse(file.isNull());
          var tag = file.tag();
          tag.setTitle("Common Žluť");
          tag.setArtist("Common artist");
          tag.setAlbum("Common album");
          tag.setComment("Common comment");
          tag.setGenre("Jazz");
          tag.setYear(0);
          tag.setTrack(0);
          assertTrue(file.save());
          assertTrue(file.save());
          assertEquals(
            expected.path("borrowedStreamCursorAfterSave").asLong(),
            memory.tell(),
            layout.getKey());
        }
        assertArrayEquals(
          Files.readAllBytes(nativeCopy), memory.data().toByteArray(), layout.getKey());
      }
  }

  @Test
  void nativeBorrowedSavePreservesContainerHeadersAndFactoryItemState() throws Exception {
    Path tool = oracle();
    byte[] opaque =
      atom(
        "gspu",
        atom(
          "data",
          join(
            new byte[]{0, 0, 0, 1, 0, 0, 0, 0}, new byte[]{'a', 0, 'x', 'x', 'x', 'x'})));
    byte[] free =
      atom(
        "----",
        join(
          atom(
            "mean", join(new byte[4], "com.apple.iTunes".getBytes(StandardCharsets.UTF_8))),
          atom("name", join(new byte[4], "iTunNORM".getBytes(StandardCharsets.UTF_8))),
          atom("data", join(new byte[]{0, 0, 0, 1, 0, 0, 0, 0}, new byte[]{'v'}))));
    byte[] items = atom("ilst", join(free, opaque));
    for (boolean extended : List.of(false, true)) {
      byte[] meta =
        extended
          ? extended("meta", join(new byte[4], items))
          : atom("meta", join(new byte[4], items));
      byte[] udta = extended ? extended("udta", meta) : atom("udta", meta);
      byte[] source = extended ? extended("moov", udta) : atom("moov", udta);
      for (long origin : List.of(0L, 7L))
        for (String scenario : List.of("save-only", "pictures-remove", "common-0")) {
          Path nativeCopy =
            save("native-session-" + extended + '-' + origin + '-' + scenario + ".m4a", source);
          var process =
            new ProcessBuilder(
              tool.toString(),
              "mutate-stream",
              nativeCopy.toString(),
              scenario,
              Long.toString(origin))
              .start();
          byte[] output = process.getInputStream().readAllBytes();
          assertEquals(0, process.waitFor());
          JsonNode expected = Snapshots.JSON.readTree(output);
          var memory =
            new cz.skyba.taglib.toolkit.ByteVectorStream(source) {
              @Override
              public String name() {
                return "test.m4a";
              }
            };
          memory.seek(origin);
          try (var file = new FileRefAdapter(memory, false)) {
            assertFalse(file.isNull());
            assertEquals(
              expected.path("borrowedStreamCursorAfterConstruction").asLong(), memory.tell());
            if (scenario.equals("pictures-remove")) {
              assertEquals(PropertyMap.EMPTY, file.replaceProperties(file.properties()).rejected());
              assertTrue(file.setComplexProperties("PICTURE", List.of()));
            }
            if (scenario.equals("common-0")) {
              var tag = file.tag();
              tag.setTitle("Common Žluť");
              tag.setArtist("Common artist");
              tag.setAlbum("Common album");
              tag.setComment("Common comment");
              tag.setGenre("Jazz");
              tag.setYear(0);
              tag.setTrack(0);
            }
            assertTrue(file.save());
            assertEquals(
              expected.path("borrowedStreamCursorAfterSave").asLong(), memory.tell(), scenario);
          }
          assertArrayEquals(
            Files.readAllBytes(nativeCopy),
            memory.data().toByteArray(),
            scenario + ", extended=" + extended + ", origin=" + origin);
          assertTrue(memory.isOpen());
        }
    }
  }

  private static JsonNode snapshot(Path path) throws Exception {
    var file = TagLib.read(path);
    var result = Snapshots.of(file);
    result.set("unsupportedData", Snapshots.JSON.valueToTree(file.properties().unsupportedData()));
    return result;
  }

  private static byte[] movie(String ignored, byte[] table) {
    byte[] item =
      atom("©nam", atom("data", join(new byte[]{0, 0, 0, 1, 0, 0, 0, 0}, new byte[]{'x'})));
    byte[] metadata =
      atom(
        "udta",
        atom("meta", join(new byte[4], atom("hdlr", new byte[25]), atom("ilst", item))));
    byte[] index =
      atom(
        "trak",
        atom(
          "mdia",
          atom(
            "minf",
            atom(
              "stbl",
              join(
                table,
                ignored.equals("saio") || ignored.equals("iloc")
                  ? atom(ignored, new byte[]{9, 8, 7, 6})
                  : new byte[0])))));
    return join(
      atom("ftyp", new byte[]{'M', '4', 'A', ' ', 0, 0, 0, 0, 'i', 's', 'o', 'm'}),
      ignored.equals("sidx") || ignored.equals("mfra")
        ? atom(ignored, new byte[]{9, 8, 7, 6})
        : new byte[0],
      atom("moov", join(index, metadata)),
      atom("mdat", new byte[]{1, 2, 3, 4}));
  }

  private Path save(String name, byte[] bytes) throws Exception {
    return Files.write(directory.resolve(name), bytes);
  }

  private static Path oracle() {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("parity/native")))
      root = root.getParent();
    assertNotNull(root);
    Path tool = root.resolve("parity/target/native-build/bin/file-reference-reads-and-mutations");
    if (Boolean.getBoolean("taglib.requireNative"))
      assertTrue(Files.isExecutable(tool), "Required core native probe");
    assumeTrue(Files.isExecutable(tool), "Build ./mvnw -Pparity verify");
    return tool;
  }

  private static JsonNode command(Path oracle, String operation, Path file, String scenario)
    throws Exception {
    var p =
      new ProcessBuilder(oracle.toString(), operation, file.toString(), scenario)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start();
    byte[] out = p.getInputStream().readAllBytes();
    assertEquals(0, p.waitFor());
    return Snapshots.JSON.readTree(out);
  }

  private static int index(byte[] bytes, String name) {
    byte[] id = name.getBytes(StandardCharsets.ISO_8859_1);
    outer:
    for (int i = 0; i <= bytes.length - id.length; i++) {
      for (int j = 0; j < id.length; j++) if (bytes[i + j] != id[j]) continue outer;
      return i;
    }
    throw new AssertionError("Missing atom " + name);
  }

  private static byte[] payload(byte[] bytes, String name) {
    int p = index(bytes, name) - 4;
    int size =
      ((bytes[p] & 255) << 24)
        | ((bytes[p + 1] & 255) << 16)
        | ((bytes[p + 2] & 255) << 8)
        | (bytes[p + 3] & 255);
    return Arrays.copyOfRange(bytes, p + 8, p + size);
  }

  private static byte[] atom(String name, byte[] fields) {
    byte[] header = new byte[8];
    be(header, 0, fields.length + 8);
    System.arraycopy(name.getBytes(StandardCharsets.ISO_8859_1), 0, header, 4, 4);
    return join(header, fields);
  }

  private static byte[] extended(String name, byte[] fields) {
    byte[] header = new byte[16];
    be(header, 0, 1);
    be(header, 12, fields.length + 16);
    System.arraycopy(name.getBytes(StandardCharsets.ISO_8859_1), 0, header, 4, 4);
    return join(header, fields);
  }

  private static void be(byte[] bytes, int at, long value) {
    for (int i = 3; i >= 0; i--) {
      bytes[at + i] = (byte) value;
      value >>>= 8;
    }
  }

  private static byte[] join(byte[]... arrays) {
    var out = new ByteArrayOutputStream();
    for (byte[] array : arrays) out.writeBytes(array);
    return out.toByteArray();
  }
}
