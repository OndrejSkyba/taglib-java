package cz.skyba.taglib.parity.id3v2;

import cz.skyba.taglib.parity.support.FileRefAdapter;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.NativeTagIO;
import cz.skyba.taglib.internal.id3.Id3v2;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Independent native Header and Tag::read controls for defined malformed/foreign metadata.
 */
@org.junit.jupiter.api.Tag("parity")
@org.junit.jupiter.api.Tag("id3v2")
final class Id3HeaderAdmissionAndChunkSaveTest {
  @TempDir
  Path directory;

  private record Fixture(String name, byte[] bytes) {
  }

  @TestFactory
  List<DynamicTest> nativeHeaderAdmission() throws Exception {
    var fixtures = new ArrayList<Fixture>();
    for (int length : new int[]{3, 4, 5, 6, 7, 8, 9}) {
      byte[] bytes = Arrays.copyOf(new byte[]{'I', 'D', '3', 3, 0, 32, 0, 0, 0, 0}, length);
      fixtures.add(new Fixture("short-" + length, bytes));
    }
    for (int version : new int[]{0, 1, 2, 3, 4, 5, 128, 254, 255}) {
      fixtures.add(new Fixture("empty-" + version, tag(version, 0, new byte[0])));
      if (version < 255)
        fixtures.add(new Fixture("frames-" + version, tag(version, 32, frameBody(version))));
    }
    byte[] revision = tag(4, 32, frameBody(4));
    revision[4] = (byte) 255;
    fixtures.add(new Fixture("revision-ff", revision));
    byte[] invalidSize = tag(4, 32, frameBody(4));
    invalidSize[6] = (byte) 128;
    fixtures.add(new Fixture("size-highbit", invalidSize));
    for (int version : new int[]{0, 2, 3, 4, 5}) {
      fixtures.add(new Fixture("empty-footer-" + version, tag(version, 16, new byte[0])));
      byte[] ext = join(new byte[]{0, 0, 0, 6, 0, 0}, frameBody(version));
      fixtures.add(new Fixture("extended-" + version, tag(version, 64, ext)));
    }
    fixtures.add(
      new Fixture("duplicate-empty", join(tag(5, 0, new byte[0]), tag(3, 0, new byte[0]))));
    fixtures.add(
      new Fixture("duplicate-framed", join(tag(5, 32, frameBody(5)), tag(4, 0, frameBody(4)))));
    for (int version : new int[]{2, 3, 4, 5}) {
      byte[] selected = tag(version, 32, frameBody(version));
      selected[0] = 'N';
      selected[1] = 'O';
      selected[2] = 'T';
      fixtures.add(new Fixture("selected-magic-" + version, selected));
    }
    var tests = new ArrayList<DynamicTest>();
    for (var fixture : fixtures)
      tests.add(DynamicTest.dynamicTest(fixture.name, () -> compare(fixture)));
    return tests;
  }

  @TestFactory
  List<DynamicTest> nativeSelectedChunkParsingAndTwoCompleteSaves() {
    var tests = new ArrayList<DynamicTest>();
    for (int version : new int[]{2, 3, 4, 5})
      tests.add(
        DynamicTest.dynamicTest(
          "selected-WAV-" + version,
          () -> {
            byte[] metadata = tag(version, 32, frameBody(version));
            metadata[0] = 'N';
            metadata[1] = 'O';
            metadata[2] = 'T';
            compareSelectedChunk("selected-" + version, metadata);
          }));
    for (int length : new int[]{3, 9})
      tests.add(
        DynamicTest.dynamicTest(
          "selected-short-WAV-" + length,
          () ->
            compareSelectedChunk(
              "selected-short-" + length,
              Arrays.copyOf(new byte[]{'N', 'O', 'T', 4, 0, 32, 0, 0, 0, 0}, length))));
    return tests;
  }

  private void compareSelectedChunk(String name, byte[] metadata) throws Exception {
    String oracle = System.getProperty("id3.frame.oracle");
    if (Boolean.getBoolean("taglib.requireNative")) {
      assertNotNull(oracle);
      assertTrue(Files.isExecutable(Path.of(oracle)));
    }
    assumeTrue(oracle != null);
    byte[] fmt = {1, 0, 1, 0, 68, (byte) 172, 0, 0, (byte) 136, 88, 1, 0, 2, 0, 16, 0};
    byte[] body =
      join(
        "WAVE".getBytes(StandardCharsets.US_ASCII),
        chunk("fmt ", fmt),
        chunk("data", new byte[]{1, 2, 3, 4}),
        chunk("ID3 ", metadata));
    byte[] original = join("RIFF".getBytes(StandardCharsets.US_ASCII), le(body.length), body);
    Path nativePath = Files.write(directory.resolve(name + "-native.wav"), original),
      javaPath = Files.write(directory.resolve(name + "-java.wav"), original);
    var process = new ProcessBuilder(oracle, "selected", nativePath.toString()).start();
    String expected = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor());
    var actual = new StringBuilder("valid=1\n");
    try (var ref = new FileRefAdapter(javaPath, false)) {
      assertFalse(ref.isNull());
      actual
        .append("TITLE\t")
        .append(hex(ref.tag().title()))
        .append("\nCOMMENT\t")
        .append(hex(ref.tag().comment()))
        .append("\nGENRE\t")
        .append(hex(ref.tag().genre()))
        .append('\n');
      for (var entry : new TreeMap<>(ref.properties().asMap()).entrySet()) {
        actual.append("PROP\t").append(hex(entry.getKey()));
        for (String value : entry.getValue()) actual.append('\t').append(hex(value));
        actual.append('\n');
      }
      for (String id : ref.properties().unsupportedData())
        actual.append("UNSUPPORTED\t").append(hex(id)).append('\n');
      for (int round = 0; round < 2; round++)
        actual.append("save=").append(ref.save() ? 1 : 0).append('\n');
    }
    String evidence = System.getProperty("id3.frame.evidence");
    if (evidence != null) {
      Path target = Path.of(evidence, "selected-chunks", name);
      Files.createDirectories(target);
      Files.write(target.resolve("source.wav"), original);
      Files.write(target.resolve("native.wav"), Files.readAllBytes(nativePath));
      Files.write(target.resolve("java.wav"), Files.readAllBytes(javaPath));
      Files.writeString(target.resolve("native.txt"), expected);
      Files.writeString(target.resolve("java.txt"), actual.toString());
    }
    assertEquals(
      expected, actual.toString(), name + " selected tag live metadata/two native save results");
    assertArrayEquals(
      Files.readAllBytes(nativePath),
      Files.readAllBytes(javaPath),
      name + " entire two-save native output");
    assertArrayEquals(
      new byte[]{1, 2, 3, 4},
      Arrays.copyOfRange(Files.readAllBytes(javaPath), 44, 48),
      "Every independent source PCM byte retained");
  }

  private static byte[] le(int value) {
    return new byte[]{
      (byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)
    };
  }

  private static byte[] chunk(String id, byte[] data) {
    return join(
      id.getBytes(StandardCharsets.US_ASCII), le(data.length), data, new byte[data.length & 1]);
  }

  private void compare(Fixture fixture) throws Exception {
    String oracle = System.getProperty("id3.frame.oracle");
    if (Boolean.getBoolean("taglib.requireNative")) {
      assertNotNull(oracle);
      assertTrue(Files.isExecutable(Path.of(oracle)));
    }
    assumeTrue(oracle != null, "Required ./mvnw -Pparity verify header probe");
    Path source = Files.write(directory.resolve(fixture.name + ".mp3"), fixture.bytes);
    var process =
      new ProcessBuilder(oracle, "header", source.toString())
        .redirectError(directory.resolve("native-errors.txt").toFile())
        .start();
    String expected = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), Files.readString(directory.resolve("native-errors.txt")));
    var parsed = Id3v2.parseNative(fixture.bytes, ReadOptions.TAGS_ONLY);
    var tag = Id3v2.read(parsed);
    long span;
    try (var file = new BinaryFile(source)) {
      span = NativeTagIO.id3v2(file, 0).length();
    }
    var actual =
      new StringBuilder("major=")
        .append(Integer.toUnsignedString(parsed.version()))
        .append("\nflags=")
        .append(parsed.headerFlags() & 240)
        .append("\ndeclared=")
        .append(Id3v2.nativeTagSize(fixture.bytes))
        .append("\nspan=")
        .append(span)
        .append("\nempty=")
        .append(tag.empty() ? 1 : 0)
        .append('\n');
    actual
      .append("TITLE\t")
      .append(hex(tag.basic().title()))
      .append("\nCOMMENT\t")
      .append(hex(tag.basic().comment()))
      .append("\nGENRE\t")
      .append(hex(tag.basic().genre()))
      .append('\n');
    for (var entry : new TreeMap<>(tag.properties().asMap()).entrySet()) {
      actual.append("PROP\t").append(hex(entry.getKey()));
      for (String value : entry.getValue()) actual.append('\t').append(hex(value));
      actual.append('\n');
    }
    for (String id : tag.properties().unsupportedData())
      actual.append("UNSUPPORTED\t").append(hex(id)).append('\n');
    String evidence = System.getProperty("id3.frame.evidence");
    if (evidence != null) {
      Path target = Path.of(evidence, "header-admission", fixture.name);
      Files.createDirectories(target);
      Files.write(target.resolve("source.mp3"), fixture.bytes);
      Files.writeString(target.resolve("native.txt"), expected);
      Files.writeString(target.resolve("java.txt"), actual.toString());
    }
    assertEquals(
      expected, actual.toString(), fixture.name + " native Header/Tag metadata and extent");
    if (fixture.name.startsWith("selected-magic"))
      assertEquals(
        0,
        Id3v2.tagSize(fixture.bytes),
        "Signature discovery stays separate from parsing codec-selected tag bytes");
  }

  private static byte[] frameBody(int version) {
    if (version <= 2)
      return new byte[]{'T', 'T', '2', 0, 0, 2, 0, 'x', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    return new byte[]{'T', 'I', 'T', '2', 0, 0, 0, 2, 0, 0, 0, 'x', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
  }

  private static byte[] tag(int version, int flags, byte[] body) {
    byte[] header = {'I', 'D', '3', (byte) version, 0, (byte) flags, 0, 0, 0, 0};
    for (int i = 0; i < 4; i++) header[9 - i] = (byte) ((body.length >>> (7 * i)) & 127);
    return join(header, body);
  }

  private static byte[] join(byte[]... arrays) {
    var out = new ByteArrayOutputStream();
    for (byte[] a : arrays) out.writeBytes(a);
    return out.toByteArray();
  }

  private static String hex(String value) {
    return HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8));
  }
}
