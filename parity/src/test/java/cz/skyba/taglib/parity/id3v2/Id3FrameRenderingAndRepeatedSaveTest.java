package cz.skyba.taglib.parity.id3v2;

import cz.skyba.taglib.parity.support.FileRefAdapter;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.toolkit.ByteVector;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Independent physical frame fixtures and native whole-file comparisons.
 */
@org.junit.jupiter.api.Tag("parity")
@org.junit.jupiter.api.Tag("id3v2")
class Id3FrameRenderingAndRepeatedSaveTest {
  @TempDir
  Path directory;

  @TestFactory
  List<DynamicTest> frameRenderingEditsAndRepeatedSavesMatchCpp() {
    var tests = new ArrayList<DynamicTest>();
    for (String scenario :
      List.of(
        "title",
        "comment",
        "genre",
        "year",
        "empty",
        "empty-description",
        "properties-common",
        "common-properties"))
      for (int rounds : new int[]{1, 2}) {
        String name = scenario + "-" + rounds;
        tests.add(
          DynamicTest.dynamicTest(
            name, () -> compare(name, fixture(false, false), scenario, rounds)));
      }
    tests.add(
      DynamicTest.dynamicTest(
        "encrypted-title", () -> compare("encrypted-title", fixture(true, false), "title", 2)));
    tests.add(
      DynamicTest.dynamicTest(
        "described-comment",
        () -> compare("described-comment", fixture(false, true), "comment", 2)));
    tests.add(
      DynamicTest.dynamicTest(
        "mpeg-duplicate-empty-unknown",
        () -> compare("mpeg-duplicate-empty-unknown", fixture(true, false), "save-only", 2)));
    tests.add(
      DynamicTest.dynamicTest(
        "mpeg-duplicate-zero-date",
        () -> {
          byte[] source = fixture(false, false);
          for (int at = 10; at + 10 < 1034; ) {
            int size = ByteBuffer.wrap(source).getInt(at + 4);
            if (new String(source, at, 4, StandardCharsets.ISO_8859_1).equals("TDRC")) {
              System.arraycopy(utf16("bad!"), 0, source, at + 10, size);
              break;
            }
            at += 10 + size;
          }
          compare("mpeg-duplicate-zero-date", source, "save-only", 2);
        }));
    for (String scenario : List.of("general-object", "general-object-empty", "complex-unknown"))
      for (int rounds : new int[]{1, 2}) {
        String name = scenario + "-" + rounds;
        tests.add(
          DynamicTest.dynamicTest(
            name, () -> compare(name, fixture(false, false), scenario, rounds)));
        String multi = "multitag-" + name;
        tests.add(
          DynamicTest.dynamicTest(
            multi, () -> compare(multi, withApe(fixture(false, false)), scenario, rounds)));
      }
    for (int version : new int[]{3, 4}) {
      for (String shape :
        List.of(
          "extended-six",
          "extended-synchsafe-128",
          "extended-highbit-128",
          "extended-oversize",
          "highbit-frame",
          "itunes-frame-256",
          "experimental",
          "experimental-extended-unsync",
          "footer"))
        for (String scenario : List.of("save-only", "title")) {
          String name = "header-" + version + '-' + shape + '-' + scenario;
          tests.add(
            DynamicTest.dynamicTest(
              name, () -> compare(name, headerFixture(version, shape), scenario, 2)));
        }
    }
    for (String scenario : List.of("save-only", "title")) {
      String name = "header-2-experimental-" + scenario;
      tests.add(
        DynamicTest.dynamicTest(
          name, () -> compare(name, headerFixture(2, "experimental"), scenario, 2)));
    }
    return tests;
  }

  private static byte[] headerFixture(int version, String shape) throws Exception {
    var body = new ByteArrayOutputStream();
    int flags = 0;
    if (shape.startsWith("extended") || shape.equals("experimental-extended-unsync")) {
      flags = 64;
      if (!shape.equals("extended-oversize")) {
        int size = shape.contains("128") ? 128 : 6;
        byte[] extended = new byte[size];
        if (shape.equals("extended-highbit-128")) extended[3] = (byte) 128;
        else {
          extended[2] = (byte) (size >>> 7);
          extended[3] = (byte) (size & 127);
        }
        body.writeBytes(extended);
      }
    }
    if (shape.startsWith("experimental")) flags |= 32;
    if (shape.equals("experimental-extended-unsync")) flags |= 128;
    byte[] title =
      new byte[shape.equals("highbit-frame") ? 128 : shape.equals("itunes-frame-256") ? 256 : 2];
    title[1] = 'x';
    Arrays.fill(title, 2, title.length, (byte) 'y');
    byte[] titleFrame = frame("TIT2", 0, title);
    // Both versions intentionally use an ordinary BE size. v4's high bit invokes
    // SynchData::toUnsignedInt's native integer fallback rather than rejecting the frame.
    if (version == 2) {
      body.writeBytes(new byte[]{'T', 'T', '2', 0, 0, 2, 0, 'x', 'T', 'P', '1', 0, 0, 2, 0, 'a'});
    } else {
      body.writeBytes(titleFrame);
      body.writeBytes(frame("TPE1", 0, new byte[]{0, 'a'}));
    }
    if (shape.equals("footer")) {
      flags |= 16;
      body.writeBytes(new byte[10]);
    }
    byte[] content = body.toByteArray(),
      header = {'I', 'D', '3', (byte) version, 0, (byte) flags, 0, 0, 0, 0};
    for (int i = 0; i < 4; i++) header[9 - i] = (byte) ((content.length >>> (7 * i)) & 127);
    var out = new ByteArrayOutputStream();
    out.writeBytes(header);
    out.writeBytes(content);
    if (shape.equals("footer"))
      out.writeBytes(
        new byte[]{
          '3', 'D', 'I', (byte) version, 0, (byte) flags, 0, 0, 0, (byte) content.length
        });
    byte[] fixture = fixture(false, false);
    out.writeBytes(Arrays.copyOfRange(fixture, 1034, fixture.length));
    return out.toByteArray();
  }

  private void compare(String name, byte[] source, String scenario, int rounds) throws Exception {
    String oracle = System.getProperty("id3.frame.oracle");
    if (Boolean.getBoolean("taglib.requireNative")) {
      assertNotNull(oracle, "Required pinned ID3 frame-state probe");
      assertTrue(Files.isExecutable(Path.of(oracle)), "Required pinned ID3 probe executable");
    }
    assumeTrue(oracle != null, "Run ./mvnw -Pparity verify");
    Path javaPath = directory.resolve(name + ".mp3"),
      nativePath = directory.resolve(name + "-native.mp3");
    Files.write(javaPath, source);
    Files.write(nativePath, source);
    var process =
      new ProcessBuilder(oracle, nativePath.toString(), scenario, Integer.toString(rounds))
        .redirectError(directory.resolve("native-errors.txt").toFile())
        .start();
    assertTrue(process.waitFor(10, TimeUnit.SECONDS));
    assertEquals(0, process.exitValue(), Files.readString(directory.resolve("native-errors.txt")));
    String expected = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
      actual;
    try (var ref = new FileRefAdapter(javaPath, false)) {
      assertFalse(ref.isNull());
      var prefix = new StringBuilder();
      switch (scenario) {
        case "title" -> ref.tag().setTitle("Common");
        case "comment" -> ref.tag().setComment("Comment");
        case "genre" -> ref.tag().setGenre("Rock");
        case "year" -> ref.tag().setYear(Integer.MIN_VALUE);
        case "save-only" -> {
        }
        case "empty" -> ref.replaceProperties(
          PropertyMap.builder()
            .put("ARTIST", List.of())
            .put("CUSTOM", List.of())
            .put("TITLE", "")
            .build());
        case "empty-description" -> {
          var values = PropertyMap.builder();
          values.add("", List.of("first", "second"));
          ref.replaceProperties(values.build());
        }
        case "properties-common" -> {
          ref.replaceProperties(
            PropertyMap.builder().put("TITLE", List.of("property", "second")).build());
          ref.tag().setTitle("Common");
        }
        case "common-properties" -> {
          ref.tag().setTitle("Common");
          ref.replaceProperties(
            PropertyMap.builder().put("TITLE", List.of("property", "second")).build());
        }
        case "general-object", "general-object-empty", "complex-unknown" -> {
          var objects =
            scenario.equals("general-object-empty")
              ? List.<Map<String, Variant>>of()
              : List.of(
              Map.of(
                "data",
                new Variant(new ByteVector(new byte[]{0, 1, (byte) 255})),
                "mimeType",
                new Variant("application/x-object"),
                "description",
                new Variant("Déjà"),
                "fileName",
                new Variant("图.bin")));
          prefix
            .append("complexSupported=")
            .append(
              ref.setComplexProperties(
                scenario.equals("complex-unknown") ? "UNKNOWNKEY" : "GENERALOBJECT",
                objects)
                ? 1
                : 0)
            .append('\n');
        }
        default -> throw new AssertionError(scenario);
      }
      var out = prefix.append(dump(ref));
      for (int i = 0; i < rounds; i++) out.append("save=").append(ref.save() ? 1 : 0).append('\n');
      actual = out.toString();
    }
    byte[] nativeBytes = Files.readAllBytes(nativePath), javaBytes = Files.readAllBytes(javaPath);
    evidence(name, "source.mp3", source);
    evidence(name, "native.mp3", nativeBytes);
    evidence(name, "java.mp3", javaBytes);
    evidence(name, "native-state.txt", expected.getBytes(StandardCharsets.UTF_8));
    evidence(name, "java-state.txt", actual.getBytes(StandardCharsets.UTF_8));
    assertEquals(expected, actual, name + " native live-frame state");
    assertArrayEquals(media(source), media(javaBytes), name + " complete retained MPEG bytes");
    assertArrayEquals(
      nativeBytes,
      javaBytes,
      name + " entire native save bytes; mismatch=" + Arrays.mismatch(nativeBytes, javaBytes));
  }

  private static String dump(FileRefAdapter ref) {
    var out =
      new StringBuilder("valid=1\nTITLE\t")
        .append(hex(ref.tag().title()))
        .append("\nCOMMENT\t")
        .append(hex(ref.tag().comment()))
        .append("\nGENRE\t")
        .append(hex(ref.tag().genre()))
        .append('\n');
    for (var e : new TreeMap<>(ref.properties().asMap()).entrySet()) {
      out.append("PROP\t").append(hex(e.getKey()));
      for (String value : e.getValue()) out.append('\t').append(hex(value));
      out.append('\n');
    }
    for (String id : ref.properties().unsupportedData())
      out.append("UNSUPPORTED\t").append(hex(id)).append('\n');
    return out.toString();
  }

  private static String hex(String value) {
    return HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] fixture(boolean encrypted, boolean onlyDescribed) throws Exception {
    var body = new ByteArrayOutputStream();
    body.writeBytes(
      frame(
        "TIT2",
        encrypted ? 64 : 0,
        encrypted ? new byte[]{9, 0, 's', 'e', 'c', 'r', 'e', 't'} : utf16("first")));
    body.writeBytes(frame("TIT2", 0, utf16("second")));
    body.writeBytes(frame("TPE1", 0, utf16("Artist")));
    body.writeBytes(frame("COMM", 0, comment("fra", "memo", "described")));
    if (!onlyDescribed) body.writeBytes(frame("COMM", 0, comment("deu", "", "default")));
    body.writeBytes(frame("TCON", 0, new byte[]{0, '1', '7'}));
    body.writeBytes(frame("TDRC", 0, utf16("2001")));
    body.writeBytes(frame("PRIV", 0, new byte[]{'o', 'w', 'n', 'e', 'r', 0, 1, 2, 3, 4}));
    body.writeBytes(frame("GEOB", 0, new byte[]{0, 'x', 0, 'n', 0, 'd', 0, 8, 7, 6}));
    body.writeBytes(new byte[1024 - body.size()]);
    var out = new ByteArrayOutputStream();
    out.writeBytes(new byte[]{'I', 'D', '3', 3, 0, 0, 0, 0, 8, 0});
    out.writeBytes(body.toByteArray());
    byte[] audio = new byte[417 * 2];
    for (int i : new int[]{0, 417}) {
      audio[i] = (byte) 0xff;
      audio[i + 1] = (byte) 0xfb;
      audio[i + 2] = (byte) 0x90;
    }
    for (int i = 4; i < audio.length; i++) if (i % 417 >= 4) audio[i] = (byte) (i * 31);
    out.writeBytes(audio);
    return out.toByteArray();
  }

  private static byte[] frame(String id, int flags, byte[] data) throws Exception {
    var out = new ByteArrayOutputStream();
    out.writeBytes(id.getBytes(StandardCharsets.ISO_8859_1));
    out.writeBytes(
      new byte[]{
        (byte) (data.length >>> 24),
        (byte) (data.length >>> 16),
        (byte) (data.length >>> 8),
        (byte) data.length,
        (byte) (flags >>> 8),
        (byte) flags
      });
    out.writeBytes(data);
    return out.toByteArray();
  }

  private static byte[] utf16(String text) throws Exception {
    var out = new ByteArrayOutputStream();
    out.writeBytes(new byte[]{1, (byte) 0xff, (byte) 0xfe});
    out.writeBytes(text.getBytes(StandardCharsets.UTF_16LE));
    return out.toByteArray();
  }

  private static byte[] comment(String language, String description, String text) throws Exception {
    var out = new ByteArrayOutputStream();
    out.write(1);
    out.writeBytes(language.getBytes(StandardCharsets.ISO_8859_1));
    out.writeBytes(Arrays.copyOfRange(utf16(description), 1, utf16(description).length));
    out.writeBytes(new byte[]{0, 0});
    out.writeBytes(Arrays.copyOfRange(utf16(text), 1, utf16(text).length));
    return out.toByteArray();
  }

  private static byte[] media(byte[] file) {
    int size = 0;
    if (file.length >= 10 && file[0] == 'I' && file[1] == 'D' && file[2] == '3') {
      size = 10;
      for (int i = 6; i < 10; i++) size += (file[i] & 127) << (7 * (9 - i));
      if ((file[5] & 16) != 0) size += 10;
    }
    int end = file.length;
    if (end >= 128 && file[end - 128] == 'T' && file[end - 127] == 'A' && file[end - 126] == 'G')
      end -= 128;
    if (end >= 32
      && new String(file, end - 32, 8, StandardCharsets.ISO_8859_1).equals("APETAGEX")) {
      long apeSize =
        Integer.toUnsignedLong(
          ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN).getInt(end - 20));
      assertTrue(apeSize >= 32 && apeSize <= end - size, "Bounded complete native APE footer span");
      end -= (int) apeSize;
      if (end >= 32
        && new String(file, end - 32, 8, StandardCharsets.ISO_8859_1).equals("APETAGEX"))
        end -= 32;
    }
    assertTrue(size <= end, "Bounded complete physical ID3 span");
    return Arrays.copyOfRange(file, size, end);
  }

  private static byte[] withApe(byte[] source) throws Exception {
    byte[] item = new byte[17];
    var body = ByteBuffer.wrap(item).order(ByteOrder.LITTLE_ENDIAN);
    body.putInt(2)
      .putInt(2)
      .put("OPAQUE".getBytes(StandardCharsets.ISO_8859_1))
      .put((byte) 0)
      .put((byte) 7)
      .put((byte) 8);
    byte[] header = new byte[32], footer = new byte[32];
    for (byte[] block : List.of(header, footer))
      ByteBuffer.wrap(block)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put("APETAGEX".getBytes(StandardCharsets.ISO_8859_1))
        .putInt(2000)
        .putInt(49)
        .putInt(1)
        .putInt(block == header ? 0xa0000000 : 0x80000000);
    var out = new ByteArrayOutputStream();
    out.writeBytes(source);
    out.writeBytes(header);
    out.writeBytes(item);
    out.writeBytes(footer);
    return out.toByteArray();
  }

  private static void evidence(String name, String suffix, byte[] bytes) throws Exception {
    String root = System.getProperty("id3.frame.evidence");
    if (root == null) return;
    Path path = Path.of(root).resolve(name);
    Files.createDirectories(path);
    Files.write(path.resolve(suffix), bytes);
  }
}
