package cz.skyba.taglib.parity.flac;

import cz.skyba.taglib.parity.support.FileRefAdapter;

import com.fasterxml.jackson.databind.JsonNode;
import cz.skyba.taglib.*;
import cz.skyba.taglib.parity.Snapshots;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.Variant;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Constructor admission, actual stream protocol, and complete native saves beyond the corpus.
 */
@org.junit.jupiter.api.Tag("parity")
@org.junit.jupiter.api.Tag("flac")
final class FlacConstructorAndSaveTest {
  @TempDir
  Path directory;
  private static final byte[] MEDIA = {11, 9, 7, 5, 3, 1, 2, 4, 6, 8, 10};

  private record Fixture(String name, byte[] bytes) {
  }

  @TestFactory
  List<DynamicTest> nativeConstructorBranches() {
    var fixtures = new ArrayList<Fixture>();
    for (int length : new int[]{0, 1, 17, 18, 34})
      fixtures.add(
        new Fixture(
          "streaminfo-" + length,
          join(ascii("fLaC"), block(0x80, Arrays.copyOf(info(), length)), MEDIA)));
    for (int type : new int[]{1, 3, 4, 6, 127})
      for (int length : new int[]{0, 1})
        fixtures.add(
          new Fixture(
            "type-" + type + '-' + length,
            join(ascii("fLaC"), block(0, info()), block(type | 128, new byte[length]), MEDIA)));
    for (int prefix : new int[]{3, 1022, 5000}) {
      byte[] garbage = new byte[prefix];
      Arrays.fill(garbage, (byte) 'x');
      fixtures.add(new Fixture("garbage-" + prefix, join(garbage, base())));
    }
    fixtures.add(
      new Fixture("leading-empty-id3", join(id3(new byte[0]), ascii("garbage"), base())));
    fixtures.add(
      new Fixture(
        "leading-physical-id3",
        join(
          id3(new byte[]{'T', 'I', 'T', '2', 0, 0, 0, 2, 0, 0, 0, 'x'}),
          ascii("garbage"),
          base())));
    fixtures.add(new Fixture("consecutive-id3", join(id3(new byte[0]), id3(new byte[0]), base())));
    fixtures.add(new Fixture("marker-inside-id3", join(id3(ascii("fLaC ignored")), base())));
    for (boolean empty : new boolean[]{false, true}) {
      byte[] legacy = new byte[128];
      System.arraycopy(ascii("TAG"), 0, legacy, 0, 3);
      legacy[127] = (byte) 255;
      if (!empty) {
        legacy[3] = 'y';
        System.arraycopy(ascii("bad!"), 0, legacy, 93, 4);
      }
      fixtures.add(new Fixture("legacy-" + empty, join(base(), legacy)));
      fixtures.add(
        new Fixture(
          "leading-and-legacy-" + empty,
          join(
            id3(new byte[]{'T', 'I', 'T', '2', 0, 0, 0, 2, 0, 0, 0, 'x'}), base(), legacy)));
    }
    byte[] oversize = id3(new byte[0]);
    oversize[8] = 1;
    fixtures.add(new Fixture("oversize-id3", join(oversize, base())));
    for (int count : new int[]{1024, 1025, 49999, 50000, 50001}) {
      var out = new ByteArrayOutputStream();
      out.writeBytes(ascii("fLaC"));
      out.writeBytes(block(0, info()));
      for (int i = 1; i < count; i++) out.writeBytes(block(i == count - 1 ? 129 : 1, new byte[0]));
      out.writeBytes(MEDIA);
      fixtures.add(new Fixture("count-" + count, out.toByteArray()));
    }
    fixtures.add(
      new Fixture("zero-first-block", join(ascii("fLaC"), block(0x81, new byte[0]), MEDIA)));
    fixtures.add(new Fixture("truncated-header", join(ascii("fLaC"), new byte[]{0, 0, 0})));
    fixtures.add(
      new Fixture(
        "truncated-body",
        join(ascii("fLaC"), new byte[]{(byte) 128, 0, 0, 34}, new byte[17])));
    fixtures.add(
      new Fixture("first-marker-invalid", join(ascii("fLaC"), block(0x81, new byte[1]), base())));
    for (boolean riff : new boolean[]{false, true})
      for (byte[] text :
        List.of(
          new byte[]{(byte) 0xc0, (byte) 0x80},
          new byte[]{'x', 0, 'y'},
          ascii("valid XML"))) {
        byte[] wrapper;
        if (riff) {
          byte[] header = ascii("riffiXMLxxxx");
          ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(8, text.length);
          wrapper = join(header, text);
        } else wrapper = join(ascii("iXML"), text);
        String name = "application-" + riff + '-' + (text[0] & 255);
        fixtures.add(
          new Fixture(
            name,
            join(
              ascii("fLaC"),
              block(0, info()),
              block(2, wrapper),
              block(0x82, join(ascii("iXML"), ascii("discarded duplicate"))),
              MEDIA)));
      }
    var tests = new ArrayList<DynamicTest>();
    for (var fixture : fixtures)
      tests.add(DynamicTest.dynamicTest(fixture.name, () -> compare(fixture)));
    return tests;
  }

  private void compare(Fixture fixture) throws Exception {
    Path source = directory.resolve(fixture.name + ".flac");
    Files.write(source, fixture.bytes);
    evidence(fixture.name, "source.flac", fixture.bytes);
    boolean valid = false;
    for (String mode : List.of("FAST", "AVERAGE", "ACCURATE", "TAGS_ONLY")) {
      ReadOptions options =
        mode.equals("TAGS_ONLY")
          ? ReadOptions.TAGS_ONLY
          : new ReadOptions(true, ReadStyle.valueOf(mode), Integer.MAX_VALUE);
      JsonNode expected = command("snapshot", source, mode);
      valid = expected.path("valid").asBoolean();
      JsonNode actual;
      try {
        actual = snapshot(TagLib.read(source, options));
      } catch (cz.skyba.taglib.TagLibException rejected) {
        actual = Snapshots.invalid();
      }
      assertEquals(
        List.of(),
        Snapshots.differences(expected, actual),
        fixture.name + '-' + mode + " facade admission/state");
      for (long origin : new long[]{0, 7}) {
        JsonNode protocol = command("snapshot-stream", source, mode, Long.toString(origin));
        var memory = memory(fixture.bytes);
        memory.seek(origin);
        try (var ref = new FileRefAdapter(memory, options)) {
          assertEquals(
            protocol.path("cursorAfterConstruction").asLong(),
            memory.tell(),
            fixture.name + " constructor cursor " + mode + '-' + origin);
          assertEquals(
            List.of(),
            Snapshots.differences(protocol.get("snapshot"), snapshot(ref)),
            fixture.name + " borrowed state " + mode + '-' + origin);
          assertEquals(
            protocol.path("cursorAfterSnapshot").asLong(),
            memory.tell(),
            fixture.name + " getter cursor " + mode + '-' + origin);
        }
      }
    }
    if (!valid) return;
    for (boolean facade : List.of(false, true)) {
      Path javaPath = directory.resolve(fixture.name + '-' + facade + "-java.flac"),
        nativePath = directory.resolve(fixture.name + '-' + facade + "-native.flac");
      Files.write(javaPath, fixture.bytes);
      Files.write(nativePath, fixture.bytes);
      for (int round = 0; round < 2; round++) {
        JsonNode expected = command("mutate", nativePath, facade ? "unchanged" : "save-only");
        assertTrue(expected.path("saved").asBoolean());
        if (facade)
          TagLib.write(javaPath, TagLib.read(javaPath, ReadOptions.TAGS_ONLY).properties());
        else
          try (var ref = new FileRefAdapter(javaPath, false)) {
            assertTrue(ref.save());
          }
        assertBytes(
          fixture.name + '-' + facade + '-' + round,
          fixture.bytes,
          Files.readAllBytes(nativePath),
          Files.readAllBytes(javaPath));
      }
    }
    for (long origin : new long[]{0, 7}) {
      Path nativePath = directory.resolve(fixture.name + "-borrowed-" + origin + ".flac");
      Files.write(nativePath, fixture.bytes);
      JsonNode expected =
        command("mutate-stream-twice", nativePath, "save-only", Long.toString(origin));
      var memory = memory(fixture.bytes);
      memory.seek(origin);
      try (var ref = new FileRefAdapter(memory, false)) {
        assertFalse(ref.isNull());
        assertEquals(
          expected.path("borrowedStreamCursorAfterConstruction").asLong(), memory.tell());
        assertEquals(List.of(), Snapshots.differences(expected.get("beforeSave"), snapshot(ref)));
        assertEquals(expected.path("borrowedStreamCursorBeforeSave").asLong(), memory.tell());
        assertTrue(ref.save());
        assertTrue(ref.save());
        assertEquals(
          expected.path("borrowedStreamCursorAfterSave").asLong(),
          memory.tell(),
          fixture.name + " twice-save cursor " + origin);
      }
      assertBytes(
        fixture.name + "-borrowed-" + origin,
        fixture.bytes,
        Files.readAllBytes(nativePath),
        memory.data().toByteArray());
    }
  }

  private void assertBytes(String name, byte[] source, byte[] expected, byte[] actual)
    throws Exception {
    evidence(name, "native.flac", expected);
    evidence(name, "java.flac", actual);
    if (source.length >= MEDIA.length
      && Arrays.equals(
      MEDIA, Arrays.copyOfRange(source, source.length - MEDIA.length, source.length)))
      assertArrayEquals(
        MEDIA,
        Arrays.copyOfRange(actual, actual.length - MEDIA.length, actual.length),
        "Every source media byte retained");
    assertArrayEquals(
      expected,
      actual,
      name + " complete native bytes; mismatch=" + Arrays.mismatch(expected, actual));
  }

  private static JsonNode snapshot(AudioMetadata file) {
    var result = Snapshots.of(file);
    result.set("unsupportedData", Snapshots.JSON.valueToTree(file.properties().unsupportedData()));
    return result;
  }

  private static JsonNode snapshot(FileRefAdapter ref) {
    if (ref.isNull()) return Snapshots.invalid();
    var pictures = new ArrayList<Picture>();
    for (var p : ref.complexProperties("PICTURE"))
      pictures.add(
        new Picture(
          cz.skyba.taglib.flac.Picture.typeFromString(
              p.getOrDefault("pictureType", new Variant()).toText())
            .code(),
          p.getOrDefault("mimeType", new Variant()).toText(),
          p.getOrDefault("description", new Variant()).toText(),
          p.getOrDefault("data", new Variant()).toByteVector().toByteArray(),
          p.getOrDefault("width", new Variant()).toInt(),
          p.getOrDefault("height", new Variant()).toInt(),
          p.getOrDefault("colorDepth", new Variant()).toInt(),
          p.getOrDefault("numColors", new Variant()).toInt()));
    var tag = ref.tag();
    return snapshot(
      new AudioMetadata(
        Format.FLAC,
        ref.properties(),
        ref.audioProperties() == null ? AudioProperties.EMPTY : ref.audioProperties(),
        pictures,
        new Tag(
          tag.title(),
          tag.artist(),
          tag.album(),
          tag.comment(),
          tag.genre(),
          tag.year(),
          tag.track())));
  }

  private static ByteVectorStream memory(byte[] bytes) {
    return new ByteVectorStream(bytes) {
      @Override
      public String name() {
        return "source.flac";
      }
    };
  }

  private static JsonNode command(String operation, Path file, String... args) throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("parity/native")))
      root = root.getParent();
    assertNotNull(root);
    Path tool = root.resolve("parity/target/native-build/bin/file-reference-reads-and-mutations");
    if (Boolean.getBoolean("taglib.requireNative"))
      assertTrue(Files.isExecutable(tool), "Required ./mvnw -Pparity verify");
    assumeTrue(Files.isExecutable(tool));
    var command = new ArrayList<String>(List.of(tool.toString(), operation, file.toString()));
    command.addAll(List.of(args));
    var process =
      new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    byte[] output = process.getInputStream().readAllBytes();
    assertEquals(0, process.waitFor());
    return Snapshots.JSON.readTree(output);
  }

  private void evidence(String name, String kind, byte[] bytes) throws Exception {
    String target = System.getProperty("xiph.encoding.evidence");
    if (target == null) return;
    Path path = Path.of(target, "constructor", name);
    Files.createDirectories(path);
    Files.write(path.resolve(kind), bytes);
  }

  private static byte[] base() {
    return join(ascii("fLaC"), block(0, info()), block(0x81, new byte[512]), MEDIA);
  }

  private static byte[] info() {
    byte[] bytes = new byte[34];
    ByteBuffer.wrap(bytes).putLong(10, (44100L << 44) | (1L << 41) | (15L << 36) | 1000);
    return bytes;
  }

  private static byte[] id3(byte[] body) {
    byte[] header = {'I', 'D', '3', 3, 0, 0, 0, 0, 0, (byte) body.length};
    return join(header, body);
  }

  private static byte[] block(int type, byte[] body) {
    return join(
      new byte[]{
        (byte) type, (byte) (body.length >>> 16), (byte) (body.length >>> 8), (byte) body.length
      },
      body);
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] join(byte[]... values) {
    var out = new ByteArrayOutputStream();
    for (byte[] value : values) out.writeBytes(value);
    return out.toByteArray();
  }
}
