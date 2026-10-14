package cz.skyba.taglib.parity.core;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.TagLib;
import cz.skyba.taglib.mp4.Chapter;
import cz.skyba.taglib.mp4.Stem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static cz.skyba.taglib.internal.Binary.*;
import static cz.skyba.taglib.parity.mp4.Mp4FixtureBytes.box;
import static cz.skyba.taglib.parity.mp4.Mp4FixtureBytes.join;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("parity")
@org.junit.jupiter.api.Tag("core")
class ContainerPropertyAndStructuredTagEditingTest {
  @TempDir
  Path directory;
  private static final byte[] MEDIA = {11, 22, 33, 44, 55, 66, 77};

  private static cz.skyba.taglib.matroska.Tag typedMatroska() {
    return new cz.skyba.taglib.matroska.Tag(
      List.of(
        new cz.skyba.taglib.matroska.SimpleTag(
          "TITLE",
          new cz.skyba.taglib.matroska.SimpleTag.Text("Local"),
          30,
          "eng",
          true,
          0,
          0,
          0,
          0),
        new cz.skyba.taglib.matroska.SimpleTag(
          "DATA",
          new cz.skyba.taglib.matroska.SimpleTag.Binary(new byte[]{0, -1, 7}),
          50,
          "deu",
          false,
          -1L,
          2,
          3,
          4)));
  }

  private static cz.skyba.taglib.matroska.Chapters typedMatroskaChapters() {
    return new cz.skyba.taglib.matroska.Chapters(
      List.of(
        new cz.skyba.taglib.matroska.ChapterEdition(
          List.of(
            new cz.skyba.taglib.matroska.Chapter(
              1000,
              4000,
              List.of(
                new cz.skyba.taglib.matroska.Chapter.Display("Začátek", "ces"),
                new cz.skyba.taglib.matroska.Chapter.Display("Opening", "eng")),
              101,
              true),
            new cz.skyba.taglib.matroska.Chapter(
              4000,
              9000,
              List.of(new cz.skyba.taglib.matroska.Chapter.Display("Second", "eng")),
              102,
              false)),
          true,
          true,
          9)));
  }

  @Test
  void containerPairedNativePropertiesAndStructuredReadsWrites() throws Exception {
    String executable = System.getProperty("container.oracle");
    org.junit.jupiter.api.Assumptions.assumeTrue(
      executable != null, "Run ./mvnw -Pparity verify for native probes");
    for (String value : List.of("", "0", "TRUE", "true", "false", "1", "yes")) {
      Path java = asf("java-" + value), nativeFile = asf("native-" + value);
      TagLib.write(
        java,
        PropertyMap.builder()
          .put("ISVBR", value)
          .put("TITLE", List.of("One", "Two"))
          .put("MEDIACLASSPRIMARYID", "{01234567-89ab-cdef-0123-456789abcdef}")
          .build());
      nativeRun(executable, "asf-set", nativeFile.toString(), value);
      assertEquals(
        nativeRun(executable, "read", nativeFile.toString()),
        nativeRun(executable, "read", java.toString()));
      assertEquals(TagLib.read(nativeFile).properties(), TagLib.read(java).properties());
    }
    Path java = mp4("java-structured"), nativeFile = mp4("native-structured");
    var tag =
      new cz.skyba.taglib.mp4.Tag(
        List.of(new Chapter("Opening", 0), new Chapter("Second", 12001)),
        new Stem(bytes("{\"stems\":[]}")));
    tag.save(java);
    nativeRun(executable, "mp4-set", nativeFile.toString());
    assertEquals(tag, cz.skyba.taglib.mp4.Tag.read(nativeFile));
    assertEquals(
      nativeRun(executable, "mp4-read", nativeFile.toString()),
      nativeRun(executable, "mp4-read", java.toString()));
    assertEquals(
      nativeRun(executable, "read", matroska().toString()),
      "TITLE\t4265666f7265\nPICTURE\t696d6167652fc3a9\t0b16212c37424d\n");
    Path colon = mp4("native-colon");
    nativeRun(executable, "mp4-colon", colon.toString());
    assertFalse(TagLib.read(colon).properties().containsKey("UFIDHTTP://EXAMPLE.COM/ID3"));
    Path scopedNative = asf("scoped-native"), scopedJava = asf("scoped-java");
    nativeRun(executable, "asf-scoped", scopedNative.toString());
    var scoped = cz.skyba.taglib.asf.Tag.read(scopedNative);
    assertEquals(42, TagLib.read(scopedNative).tag().track());
    scoped.save(scopedJava);
    assertEquals(
      nativeRun(executable, "asf-attributes", scopedNative.toString()),
      nativeRun(executable, "asf-attributes", scopedJava.toString()));
    Path mkvNative = directory.resolve("typed-native.mka"),
      mkvJava = directory.resolve("typed-java.mka");
    Files.copy(matroska(), mkvNative);
    Files.copy(matroska(), mkvJava);
    nativeRun(executable, "mkv-set", mkvNative.toString());
    typedMatroska().save(mkvJava);
    assertEquals(typedMatroska(), cz.skyba.taglib.matroska.Tag.read(mkvNative));
    assertEquals(
      nativeRun(executable, "mkv-read", mkvNative.toString()),
      nativeRun(executable, "mkv-read", mkvJava.toString()));
    nativeRun(executable, "mkv-chapters-set", mkvNative.toString());
    typedMatroskaChapters().save(mkvJava);
    assertEquals(typedMatroskaChapters(), cz.skyba.taglib.matroska.Chapters.read(mkvNative));
    assertEquals(
      nativeRun(executable, "mkv-chapters-read", mkvNative.toString()),
      nativeRun(executable, "mkv-chapters-read", mkvJava.toString()));
  }

  private static String nativeRun(String executable, String... args) throws Exception {
    var command = new ArrayList<String>();
    command.add(executable);
    command.addAll(List.of(args));
    var process =
      new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), String.join(" ", command));
    return output;
  }

  private Path asf(String name) throws Exception {
    byte[] stream = new byte[78];
    System.arraycopy(HexFormat.of().parseHex("409e69f84d5bcf11a8fd00805f5c442b"), 0, stream, 0, 16);
    put16le(stream, 54, 0x161);
    put16le(stream, 56, 2);
    put32le(stream, 58, 44100);
    byte[] body =
      join(
        asfObject("a1dcab8c47a9cf118ee400c00c205365", new byte[80]),
        asfObject("9107dcb7b7a9cf118ee600c00c205365", stream));
    byte[] header = new byte[30];
    System.arraycopy(HexFormat.of().parseHex("3026b2758e66cf11a6d900aa0062ce6c"), 0, header, 0, 16);
    put64le(header, 16, body.length + 30);
    put32le(header, 24, 2);
    header[28] = 1;
    header[29] = 2;
    Path path = directory.resolve(name + ".wma");
    Files.write(path, join(header, body, MEDIA));
    return path;
  }

  private static byte[] asfObject(String guid, byte[] data) throws Exception {
    byte[] header = new byte[24];
    System.arraycopy(HexFormat.of().parseHex(guid), 0, header, 0, 16);
    put64le(header, 16, data.length + 24);
    return join(header, data);
  }

  private Path mp4(String name) throws Exception {
    byte[] offsets = new byte[12];
    put32be(offsets, 4, 1);
    byte[] ftyp = box("ftyp", bytes("M4A \0\0\0\0"));
    byte[] moov =
      box("moov", box("trak", box("mdia", box("minf", box("stbl", box("stco", offsets))))));
    put32be(
      moov,
      cz.skyba.taglib.parity.mp4.Mp4FixtureBytes.index(moov, bytes("stco")) + 12,
      ftyp.length + moov.length + 8);
    Path path = directory.resolve(name + ".m4a");
    Files.write(path, join(ftyp, moov, box("mdat", MEDIA)));
    return path;
  }

  private void assertChunkOffset(Path path) throws Exception {
    byte[] bytes = Files.readAllBytes(path);
    assertEquals(
      cz.skyba.taglib.parity.mp4.Mp4FixtureBytes.index(bytes, bytes("mdat")) + 4,
      u32be(bytes, cz.skyba.taglib.parity.mp4.Mp4FixtureBytes.index(bytes, bytes("stco")) + 12));
  }

  private void assertMedia(Path path) throws Exception {
    byte[] bytes = Files.readAllBytes(path);
    assertArrayEquals(MEDIA, Arrays.copyOfRange(bytes, bytes.length - MEDIA.length, bytes.length));
  }

  private Path matroska() throws Exception {
    byte[] title =
      element(
        0x67c8, join(element(0x45a3, bytes("TITLE")), element(0x4487, bytes("Before\0After"))));
    byte[] picture =
      element(
        0x61a7,
        join(
          element(0x466e, bytes("cover")),
          element(0x4660, "image/é".getBytes(StandardCharsets.ISO_8859_1)),
          element(0x465c, MEDIA)));
    Path path = directory.resolve("strings.mka");
    Files.write(
      path,
      join(
        element(0x1a45dfa3L, new byte[0]),
        element(
          0x18538067L,
          join(
            element(0x1254c367L, element(0x7373, title)), element(0x1941a469L, picture)))));
    return path;
  }

  private static byte[] element(long id, byte[] data) throws Exception {
    var out = new ByteArrayOutputStream();
    int width = (64 - Long.numberOfLeadingZeros(id) + 7) / 8;
    for (int i = width - 1; i >= 0; i--) out.write((int) (id >>> (i * 8)));
    if (id == 0x18538067L) {
      byte[] length = new byte[8];
      put64be(length, 0, data.length);
      length[0] = 1;
      out.write(length);
    } else if (data.length < 127) out.write(0x80 | data.length);
    else {
      out.write(0x40 | (data.length >>> 8));
      out.write(data.length);
    }
    out.write(data);
    return out.toByteArray();
  }
}
