package cz.skyba.taglib.parity.ogg;

import cz.skyba.taglib.parity.support.FileRefAdapter;

import com.fasterxml.jackson.databind.JsonNode;
import cz.skyba.taglib.*;
import cz.skyba.taglib.internal.xiph.XiphComment;
import cz.skyba.taglib.parity.Snapshots;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Independent native UTF-8 boundaries and native FLAC block placement/full-byte proofs.
 */
@org.junit.jupiter.api.Tag("parity")
@org.junit.jupiter.api.Tag("ogg")
final class XiphUtf8AndFlacCommentPlacementTest {
  @TempDir
  Path directory;
  private static final byte[] MEDIA = {0, 7, 3, 1, 9, 5, 2, 4, 6, 8, (byte) 255};

  @TestFactory
  List<DynamicTest> malformedUtf8AndEmbeddedNulsFollowNativeByteVectorStrings() {
    var cases = new LinkedHashMap<String, byte[]>();
    cases.put("overlong", new byte[]{(byte) 0xc0, (byte) 0xaf});
    cases.put("surrogate", new byte[]{(byte) 0xed, (byte) 0xa0, (byte) 0x80});
    cases.put("outside-unicode", new byte[]{(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80});
    cases.put("truncated", new byte[]{(byte) 0xe2, (byte) 0x82});
    cases.put("invalid-tail", new byte[]{'a', (byte) 0x80});
    cases.put("nul", new byte[]{'x', 0, 'y'});
    cases.put("valid", "東京 é".getBytes(StandardCharsets.UTF_8));
    var tests = new ArrayList<DynamicTest>();
    cases.forEach(
      (kind, text) -> {
        for (String position :
          List.of("vendor", "key", "value", "picture-mime", "picture-description"))
          tests.add(
            DynamicTest.dynamicTest(
              kind + "-" + position,
              () -> {
                String name = kind + '-' + position;
                if (position.startsWith("picture-")) {
                  byte[] input =
                    picture(
                      position.equals("picture-mime") ? text : ascii("image/png"),
                      position.equals("picture-description") ? text : ascii("cover"));
                  var actual = XiphComment.parsePicture(input);
                  assertArrayEquals(
                    nativeRender(name, "picture", input),
                    XiphComment.renderPicture(actual));
                  compareFlac(
                    name, comment(ascii("Vendor"), ascii("TITLE=valid")), input, false);
                } else {
                  byte[] input =
                    comment(
                      position.equals("vendor") ? text : ascii("Vendor"),
                      position.equals("key")
                        ? join(text, ascii("=value"))
                        : position.equals("value")
                        ? join(ascii("TITLE="), text)
                        : ascii("TITLE=valid"));
                  var actual = XiphComment.parse(input);
                  assertArrayEquals(
                    nativeRender(name, "comment", input),
                    actual.render(actual.properties(), false));
                  compareFlac(name, input, null, false);
                }
              }));
      });
    return tests;
  }

  @Test
  void malformedUtf16RenderKeepsNativeFieldSeparatorAndClearsPictureStrings() throws Exception {
    byte[] input = comment(ascii("Vendor"));
    var source = XiphComment.parse(input);
    var requested = PropertyMap.builder().put("TITLE", "\ud800").build();
    var picture = new Picture(0, "\ud800", "\ud800", new byte[]{1, 2, 3});
    byte[] actual = source.render(requested, false, List.of(picture));
    byte[] expected = nativeRender("invalid-utf16-render", "render-invalid", input);
    assertArrayEquals(expected, actual);
    var reparsed = XiphComment.parse(actual);
    assertFalse(reparsed.properties().containsKey("TITLE"));
    assertEquals("", reparsed.pictures().getFirst().mimeType());
    assertEquals("", reparsed.pictures().getFirst().description());
  }

  @Test
  void nativeFlacMovesCommentAfterRetainedBlocksAndBeforeFirstPictureAcrossSaves()
    throws Exception {
    for (boolean withPicture : List.of(false, true))
      compareFlac(
        "source-order-" + withPicture,
        comment(ascii("Vendor"), ascii("TITLE=valid")),
        withPicture ? picture(ascii("image/png"), ascii("cover")) : null,
        true);
  }

  @Test
  void nativeFlacDiscardsStructurallyUnparseablePicturesBeforeSave() throws Exception {
    byte[] valid = picture(ascii("image/png"), ascii("cover"));
    byte[] badMime = valid.clone();
    ByteBuffer.wrap(badMime).putInt(4, Integer.MAX_VALUE);
    byte[] badPayload = valid.clone();
    ByteBuffer.wrap(badPayload).putInt(valid.length - 7, Integer.MAX_VALUE);
    int index = 0;
    for (byte[] malformed : List.of(new byte[]{1, 2, 3}, badMime, badPayload))
      compareFlac(
        "malformed-picture-" + index++,
        comment(ascii("Vendor"), ascii("TITLE=valid")),
        malformed,
        true);
  }

  @Test
  void nativeTruncatedFieldHeadersRetainAlreadyParsedVendorAndFields() throws Exception {
    byte[] full = comment(ascii("Vendor"), ascii("TITLE=valid"), ascii("ARTIST=second"));
    int index = 0;
    for (byte[] input :
      List.of(
        Arrays.copyOf(full, 10),
        Arrays.copyOf(full, 12),
        Arrays.copyOf(full, full.length - 3))) {
      String name = "truncated-fields-" + index++;
      var actual = XiphComment.parse(input);
      assertArrayEquals(
        nativeRender(name, "comment", input), actual.render(actual.properties(), false));
      compareFlac(name, input, null, true);
    }
  }

  private void compareFlac(String name, byte[] comment, byte[] picture, boolean repeated)
    throws Exception {
    byte[] source = flac(comment, picture);
    for (boolean facade : List.of(false, true)) {
      Path javaPath = directory.resolve(name + '-' + facade + ".flac"),
        nativePath = directory.resolve(name + '-' + facade + "-native.flac");
      Files.write(javaPath, source);
      Files.write(nativePath, source);
      JsonNode nativeBefore = core("snapshot", nativePath, "AVERAGE");
      assertEquals(
        List.of(), Snapshots.differences(nativeBefore, snapshot(javaPath)), name + " before");
      int rounds = repeated ? 2 : 1;
      for (int round = 0; round < rounds; round++) {
        JsonNode expected = core("mutate", nativePath, facade ? "unchanged" : "save-only");
        assertTrue(expected.path("saved").asBoolean(), name + " native accepts fixture");
        if (facade)
          TagLib.write(javaPath, TagLib.read(javaPath, ReadOptions.TAGS_ONLY).properties());
        else
          try (var file = new FileRefAdapter(javaPath, false)) {
            assertTrue(file.save());
          }
        byte[] actual = Files.readAllBytes(javaPath), nativeBytes = Files.readAllBytes(nativePath);
        evidence(name + '-' + facade + '-' + round, "source.flac", source);
        evidence(name + '-' + facade + '-' + round, "native.flac", nativeBytes);
        evidence(name + '-' + facade + '-' + round, "java.flac", actual);
        assertArrayEquals(
          MEDIA, Arrays.copyOfRange(actual, actual.length - MEDIA.length, actual.length));
        assertArrayEquals(
          nativeBytes,
          actual,
          name + " entire native saved file; mismatch=" + Arrays.mismatch(nativeBytes, actual));
        assertEquals(
          List.of(),
          Snapshots.differences(expected.get("post"), snapshot(javaPath)),
          name + " after");
      }
    }
  }

  private byte[] nativeRender(String name, String mode, byte[] input) throws Exception {
    Path source = directory.resolve(name + ".bin"),
      output = directory.resolve(name + "-native.bin");
    Files.write(source, input);
    Path tool = root().resolve("parity/target/native-build/bin/xiph-utf8-and-flac-comment-placement");
    if (Boolean.getBoolean("taglib.requireNative"))
      assertTrue(Files.isExecutable(tool), "Required ./mvnw -Pparity verify");
    assumeTrue(Files.isExecutable(tool));
    var p =
      new ProcessBuilder(tool.toString(), mode, source.toString(), output.toString())
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start();
    assertEquals(0, p.waitFor());
    byte[] result = Files.readAllBytes(output);
    evidence(name, "source.bin", input);
    evidence(name, "native.bin", result);
    return result;
  }

  private static JsonNode core(String operation, Path file, String scenario) throws Exception {
    Path tool = root().resolve("parity/target/native-build/bin/file-reference-reads-and-mutations");
    if (Boolean.getBoolean("taglib.requireNative"))
      assertTrue(Files.isExecutable(tool), "Required ./mvnw -Pparity verify");
    assumeTrue(Files.isExecutable(tool));
    var p =
      new ProcessBuilder(tool.toString(), operation, file.toString(), scenario)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start();
    byte[] out = p.getInputStream().readAllBytes();
    assertEquals(0, p.waitFor());
    return Snapshots.JSON.readTree(out);
  }

  private static JsonNode snapshot(Path path) throws Exception {
    var file = TagLib.read(path);
    var result = Snapshots.of(file);
    result.set("unsupportedData", Snapshots.JSON.valueToTree(file.properties().unsupportedData()));
    return result;
  }

  private void evidence(String name, String kind, byte[] bytes) throws Exception {
    String target = System.getProperty("xiph.encoding.evidence");
    if (target == null) return;
    Path path = Path.of(target, name);
    Files.createDirectories(path);
    Files.write(path.resolve(kind), bytes);
  }

  private static Path root() {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("parity/native")))
      root = root.getParent();
    assertNotNull(root);
    return root;
  }

  private static byte[] flac(byte[] comment, byte[] picture) {
    byte[] stream = new byte[34];
    long info = (44100L << 44) | (1L << 41) | (15L << 36) | 1000;
    ByteBuffer.wrap(stream).putLong(10, info);
    return join(
      ascii("fLaC"),
      block(0, stream),
      block(4, comment),
      block(3, new byte[18]),
      block(5, new byte[]{1, 2, 3, 4, 5}),
      picture == null ? new byte[0] : block(6, picture),
      block(0x81, new byte[512]),
      MEDIA);
  }

  private static byte[] picture(byte[] mime, byte[] description) {
    return join(
      number(3, true),
      number(mime.length, true),
      mime,
      number(description.length, true),
      description,
      number(1, true),
      number(1, true),
      number(8, true),
      number(0, true),
      number(3, true),
      new byte[]{1, 2, 3});
  }

  private static byte[] comment(byte[] vendor, byte[]... fields) {
    var out = new ByteArrayOutputStream();
    out.writeBytes(number(vendor.length, false));
    out.writeBytes(vendor);
    out.writeBytes(number(fields.length, false));
    for (byte[] field : fields) {
      out.writeBytes(number(field.length, false));
      out.writeBytes(field);
    }
    return out.toByteArray();
  }

  private static byte[] block(int type, byte[] body) {
    return join(
      new byte[]{
        (byte) type, (byte) (body.length >>> 16), (byte) (body.length >>> 8), (byte) body.length
      },
      body);
  }

  private static byte[] number(int value, boolean big) {
    return ByteBuffer.allocate(4)
      .order(big ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN)
      .putInt(value)
      .array();
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] join(byte[]... pieces) {
    var out = new ByteArrayOutputStream();
    for (byte[] piece : pieces) out.writeBytes(piece);
    return out.toByteArray();
  }
}
