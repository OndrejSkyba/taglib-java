package cz.skyba.taglib.parity.core;

import cz.skyba.taglib.parity.support.FileRefAdapter;
import cz.skyba.taglib.InvalidAudioFileException;
import cz.skyba.taglib.MetadataReadException;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.TagLib;
import cz.skyba.taglib.parity.Snapshots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Native defaults do not impose an application-selected global metadata budget.
 */
@org.junit.jupiter.api.Tag("parity")
@org.junit.jupiter.api.Tag("core")
final class LargeMetadataReadLimitTest {
  @TempDir
  Path directory;

  @Test
  void nativeAcceptsPictureBeyondTheFormerSixtyFourMiBDefault() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("parity/native")))
      root = root.getParent();
    assertNotNull(root);
    Path tool = root.resolve("parity/target/native-build/bin/file-reference-reads-and-mutations");
    if (Boolean.getBoolean("taglib.requireNative")) assertTrue(Files.isExecutable(tool));
    assumeTrue(Files.isExecutable(tool));
    int pictureSize = 65 * 1024 * 1024;
    byte[] description = new byte[]{0, 'i', 'm', 'a', 'g', 'e', '/', 'p', 'n', 'g', 0, 3, 0};
    byte[] header = new byte[20 + description.length];
    System.arraycopy("ID3".getBytes(StandardCharsets.ISO_8859_1), 0, header, 0, 3);
    header[3] = 4;
    synchsafe(header, 6, 10 + description.length + pictureSize);
    System.arraycopy("APIC".getBytes(StandardCharsets.ISO_8859_1), 0, header, 10, 4);
    synchsafe(header, 14, description.length + pictureSize);
    System.arraycopy(description, 0, header, 20, description.length);
    Path path = directory.resolve("large-picture.mp3");
    try (var file = new RandomAccessFile(path.toFile(), "rw")) {
      file.write(header);
      file.setLength(header.length + (long) pictureSize);
    }
    var process =
      new ProcessBuilder(tool.toString(), "snapshot", path.toString(), "TAGS_ONLY")
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start();
    byte[] output = process.getInputStream().readAllBytes();
    assertEquals(0, process.waitFor());
    var nativeSnapshot = Snapshots.JSON.readTree(output);
    assertTrue(nativeSnapshot.path("valid").asBoolean());
    assertEquals(pictureSize, nativeSnapshot.path("pictures").get(0).path("size").asInt());
    assertEquals(Integer.MAX_VALUE, ReadOptions.DEFAULT.maxMetadataBytes());
    assertEquals(Integer.MAX_VALUE, ReadOptions.TAGS_ONLY.maxMetadataBytes());
    var javaFile = TagLib.read(path, ReadOptions.TAGS_ONLY);
    var javaSnapshot = Snapshots.of(javaFile);
    javaSnapshot.set(
      "unsupportedData", Snapshots.JSON.valueToTree(javaFile.properties().unsupportedData()));
    assertEquals(java.util.List.of(), Snapshots.differences(nativeSnapshot, javaSnapshot));
    try (var reference = new FileRefAdapter(path, false)) {
      assertFalse(reference.isNull());
      assertEquals(
        pictureSize,
        reference.complexProperties("PICTURE").getFirst().get("data").toByteVector().size());
    }
    assertThrows(
      MetadataReadException.class,
      () -> TagLib.read(path, new ReadOptions(false, ReadStyle.AVERAGE, 64 * 1024 * 1024)));
  }

  @Test
  void mp4ResourceLimitsFollowNativeRecognizedAtomsAndDepth() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("parity/native")))
      root = root.getParent();
    assertNotNull(root);
    Path tool = root.resolve("parity/target/native-build/bin/file-reference-reads-and-mutations");
    if (Boolean.getBoolean("taglib.requireNative")) assertTrue(Files.isExecutable(tool));
    assumeTrue(Files.isExecutable(tool));
    var cases = new LinkedHashMap<String, byte[]>();
    byte[] free = atom("free", new byte[0]);
    for (int depth : List.of(33, 64, 65, 66, 70)) {
      byte[] nested = free;
      for (int i = 0; i < depth; i++) nested = atom("udta", nested);
      cases.put("depth-" + depth, atom("moov", nested));
    }
    for (int count : List.of(49_999, 50_000)) {
      byte[] many = repeat(free, count);
      cases.put("children-" + count, atom("moov", many));
      cases.put("prior-roots-" + count, join(many, atom("moov", new byte[0])));
      cases.put("following-roots-" + count, join(atom("moov", new byte[0]), many));
    }
    // Data children inside a metadata item are parsed by the item factory;
    // MP4::Atom treats that item as a leaf, so the shared atom counter excludes them.
    byte[] values = repeat(atom("data", new byte[]{0, 0, 0, 1, 0, 0, 0, 0}), 100_001);
    cases.put(
      "item-values-100001",
      atom(
        "moov",
        atom("udta", atom("meta", join(new byte[4], atom("ilst", atom("©nam", values)))))));
    for (var entry : cases.entrySet()) {
      Path path = directory.resolve(entry.getKey() + ".m4a");
      Files.write(path, entry.getValue());
      var process =
        new ProcessBuilder(tool.toString(), "snapshot", path.toString(), "TAGS_ONLY")
          .redirectError(ProcessBuilder.Redirect.INHERIT)
          .start();
      byte[] output = process.getInputStream().readAllBytes();
      assertEquals(0, process.waitFor(), entry.getKey());
      var expected = Snapshots.JSON.readTree(output);
      try (var reference = new FileRefAdapter(path, false)) {
        assertEquals(expected.path("valid").asBoolean(), !reference.isNull(), entry.getKey());
      }
      if (expected.path("valid").asBoolean()) {
        var file = TagLib.read(path, ReadOptions.TAGS_ONLY);
        var actual = Snapshots.of(file);
        actual.set(
          "unsupportedData", Snapshots.JSON.valueToTree(file.properties().unsupportedData()));
        assertEquals(List.of(), Snapshots.differences(expected, actual), entry.getKey());
      } else
        assertThrows(
          InvalidAudioFileException.class, () -> TagLib.read(path, ReadOptions.TAGS_ONLY), entry.getKey());
      for (long origin : List.of(0L, 7L)) {
        var streamProcess =
          new ProcessBuilder(
            tool.toString(),
            "snapshot-stream",
            path.toString(),
            "TAGS_ONLY",
            Long.toString(origin))
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
        byte[] streamOutput = streamProcess.getInputStream().readAllBytes();
        assertEquals(0, streamProcess.waitFor(), entry.getKey());
        var streamExpected = Snapshots.JSON.readTree(streamOutput);
        var memory =
          new cz.skyba.taglib.toolkit.ByteVectorStream(entry.getValue()) {
            @Override
            public String name() {
              return "limit.m4a";
            }
          };
        memory.seek(origin);
        try (var reference = new FileRefAdapter(memory, false)) {
          assertEquals(
            streamExpected.path("snapshot").path("valid").asBoolean(),
            !reference.isNull(),
            entry.getKey());
          assertEquals(
            streamExpected.path("cursorAfterConstruction").asLong(),
            memory.tell(),
            entry.getKey() + '-' + origin);
        }
      }
    }
  }

  private static byte[] atom(String name, byte[] body) {
    byte[] result = new byte[8 + body.length];
    int size = result.length;
    for (int i = 3; i >= 0; i--) {
      result[i] = (byte) size;
      size >>>= 8;
    }
    System.arraycopy(name.getBytes(StandardCharsets.ISO_8859_1), 0, result, 4, 4);
    System.arraycopy(body, 0, result, 8, body.length);
    return result;
  }

  private static byte[] repeat(byte[] bytes, int count) {
    byte[] result = new byte[bytes.length * count];
    for (int i = 0; i < count; i++)
      System.arraycopy(bytes, 0, result, i * bytes.length, bytes.length);
    return result;
  }

  private static byte[] join(byte[] first, byte[] second) {
    byte[] result = Arrays.copyOf(first, first.length + second.length);
    System.arraycopy(second, 0, result, first.length, second.length);
    return result;
  }

  private static void synchsafe(byte[] bytes, int offset, int value) {
    for (int i = 3; i >= 0; i--) {
      bytes[offset + i] = (byte) (value & 127);
      value >>>= 7;
    }
  }
}
