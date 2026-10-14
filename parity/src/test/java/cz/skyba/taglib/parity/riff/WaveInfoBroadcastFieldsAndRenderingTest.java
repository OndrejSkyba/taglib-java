package cz.skyba.taglib.parity.riff;

import cz.skyba.taglib.parity.support.FileRefAdapter;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.TagLib;
import cz.skyba.taglib.parity.Snapshots;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unknown INFO fields and default/repeated writes compared with the actual release FileRefAdapter.
 */
@org.junit.jupiter.api.Tag("parity")
@org.junit.jupiter.api.Tag("riff")
class WaveInfoBroadcastFieldsAndRenderingTest {
  @TempDir
  Path directory;

  static byte[] chunk(String id, byte[] bytes) {
    var b =
      ByteBuffer.allocate(8 + bytes.length + (bytes.length & 1)).order(ByteOrder.LITTLE_ENDIAN);
    return b.put(id.getBytes(StandardCharsets.ISO_8859_1)).putInt(bytes.length).put(bytes).array();
  }

  static byte[] join(byte[]... values) {
    var b = new java.io.ByteArrayOutputStream();
    for (var v : values) b.writeBytes(v);
    return b.toByteArray();
  }

  static byte[] fixture(boolean mixed, boolean empty) {
    byte[] fmt =
      ByteBuffer.allocate(16)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort((short) 1)
        .putShort((short) 1)
        .putInt(8000)
        .putInt(16000)
        .putShort((short) 2)
        .putShort((short) 16)
        .array();
    byte[] info =
      join(
        new byte[]{'I', 'N', 'F', 'O'},
        chunk("ZZZZ", empty ? new byte[]{0} : new byte[]{'o', 'p', 'a', 'q', 'u', 'e', 0}),
        chunk("YYYY", new byte[]{'x', 0}));
    // Independent v2.4 TIT2 wire payload; no library renderer constructs the input fixture.
    byte[] id3 =
      chunk(
        "ID3 ",
        new byte[]{
          'I', 'D', '3', 4, 0, 0, 0, 0, 0, 12, 'T', 'I', 'T', '2', 0, 0, 0, 2, 0, 0, 3, 't'
        });
    byte[] body =
      join(
        new byte[]{'W', 'A', 'V', 'E'},
        chunk("fmt ", fmt),
        chunk("LIST", info),
        mixed ? id3 : new byte[0],
        chunk("data", new byte[]{1, 2, 3, 4}));
    return join(
      new byte[]{'R', 'I', 'F', 'F'},
      ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.length).array(),
      body);
  }

  @Test
  void unknownInfoLiveStateAndFullSaveBytesMatchNative() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("parity/native")))
      root = root.getParent();
    assertNotNull(root);
    Path tool = root.resolve("parity/target/native-build/bin/file-reference-reads-and-mutations");
    if (Boolean.getBoolean("taglib.requireNative")) assertTrue(Files.isExecutable(tool));
    assumeTrue(Files.isExecutable(tool));
    for (boolean mixed : List.of(false, true))
      for (boolean empty : List.of(false, true))
        for (String scenario :
          List.of("save-only", "remove-unsupported", "common-title", "empty", "grow-shrink")) {
          byte[] original = fixture(mixed, empty);
          Path nativeCopy =
            Files.write(
              directory.resolve(mixed + "-" + empty + "-" + scenario + ".wav"), original);
          var process =
            new ProcessBuilder(
              tool.toString(),
              scenario.equals("grow-shrink") ? "mutate-stream-twice" : "mutate-stream",
              nativeCopy.toString(),
              scenario,
              "0")
              .start();
          byte[] output = process.getInputStream().readAllBytes();
          assertEquals(0, process.waitFor());
          var expected = Snapshots.JSON.readTree(output);
          var stream =
            new ByteVectorStream(original) {
              @Override
              public String name() {
                return "fixture.wav";
              }
            };
          try (var file = new FileRefAdapter(stream, false)) {
            assertFalse(file.isNull());
            assertFalse(file.tag().isEmpty());
            assertEquals(mixed ? List.of() : List.of("", ""), file.properties().unsupportedData());
            if (!mixed)
              assertEquals(
                List.of("", ""),
                TagLib.read(stream, ReadOptions.TAGS_ONLY).properties().unsupportedData());
            switch (scenario) {
              case "remove-unsupported" -> file.removeUnsupportedProperties(file.properties().unsupportedData());
              case "common-title" -> file.tag().setTitle("Common title");
              case "empty" -> file.replaceProperties(PropertyMap.EMPTY);
              case "grow-shrink" -> file.replaceProperties(PropertyMap.builder().put("TITLE", "x".repeat(8192)).build());
            }
            assertTrue(file.save());
            if (scenario.equals("grow-shrink")) {
              file.tag().setTitle("x");
              assertTrue(file.save());
            }
            assertEquals(
              expected.path("borrowedStreamCursorAfterSave").asLong(),
              stream.tell(),
              nativeCopy.toString());
          }
          assertArrayEquals(
            Files.readAllBytes(nativeCopy), stream.data().toByteArray(), nativeCopy.toString());
        }
  }
}
