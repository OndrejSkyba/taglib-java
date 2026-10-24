package cz.skyba.taglib.parity.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cz.skyba.taglib.parity.Harness;
import cz.skyba.taglib.parity.PayloadHash;
import cz.skyba.taglib.parity.Snapshots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies byte preservation and bitrate normalization when Matroska attachment UID widths change.
 */
@org.junit.jupiter.api.Tag("parity")
@org.junit.jupiter.api.Tag("matroska")
final class MatroskaUidBitrateNormalizationTest {
  @TempDir
  Path directory;

  @Test
  void nativeSevenAndEightByteOutputsRequireCompleteProofAndTheirOwnNativeBitrates()
    throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("parity/native")))
      root = root.getParent();
    assertNotNull(root);
    Path uid = root.resolve("parity/target/native-build/bin/matroska-attachment-uids-and-bit-rates"),
      core = root.resolve("parity/target/native-build/bin/file-reference-reads-and-mutations");
    if (Boolean.getBoolean("taglib.requireNative")) {
      assertTrue(Files.isExecutable(uid));
      assertTrue(Files.isExecutable(core));
    }
    assumeTrue(Files.isExecutable(uid) && Files.isExecutable(core));
    Path source = Snapshots.upstreamSource(root).resolve("tests/data/no-tags.webm"),
      nativePath = directory.resolve("seven.webm"),
      javaPath = directory.resolve("eight.webm");
    Files.copy(source, nativePath);
    Files.copy(source, javaPath);
    for (Path path : List.of(nativePath, javaPath)) {
      long value = path.equals(nativePath) ? 0xff_ffff_ffff_ffffL : -1;
      var process =
        new ProcessBuilder(
          uid.toString(),
          "attachment",
          path.toString(),
          Long.toUnsignedString(value, 16),
          "9")
          .start();
      assertEquals(0, process.waitFor());
    }
    var complete =
      PayloadHash.nativeBytesEquivalent(source, javaPath, nativePath, "pictures-replace");
    assertTrue(complete.verified(), complete.details());
    assertFalse(complete.rawEqual());
    JsonNode original = nativeSnapshot(core, nativePath),
      javaNative = nativeSnapshot(core, javaPath);
    JsonNode frozen = original.deepCopy();
    var report = Snapshots.JSON.createObjectNode();
    var expected = Harness.expectedJavaOutputRead(original, javaNative, complete, report);
    assertEquals(List.of(), Snapshots.differences(expected, javaNative));
    assertTrue(
      report
        .path("nativeFileSizeAudioDerivation")
        .path("bothNativeReadsVerifyFormula")
        .asBoolean());
    assertEquals(frozen, original, "Raw native snapshot and bitrate remain visible and unchanged");

    ObjectNode wrong = javaNative.deepCopy();
    ((ObjectNode) wrong.path("audio"))
      .put("bitrate", wrong.path("audio").path("bitrate").asInt() + 1);
    assertEquals(
      original,
      Harness.expectedJavaOutputRead(
        original, wrong, complete, Snapshots.JSON.createObjectNode()));
    assertFalse(
      Snapshots.differences(original, wrong).isEmpty(),
      "An arbitrary bitrate error remains a failure");
    ObjectNode wrongTitle = javaNative.deepCopy();
    ((ObjectNode) wrongTitle.path("common")).put("title", "corrupt");
    assertFalse(
      Snapshots.differences(
          Harness.expectedJavaOutputRead(
            original, wrongTitle, complete, Snapshots.JSON.createObjectNode()),
          wrongTitle)
        .isEmpty(),
      "Only the derived bitrate changes");

    byte[] corrupt = Files.readAllBytes(javaPath);
    corrupt[0] ^= 1;
    Files.write(javaPath, corrupt);
    var unverified =
      PayloadHash.nativeBytesEquivalent(source, javaPath, nativePath, "pictures-replace");
    assertFalse(unverified.verified());
    assertEquals(
      original,
      Harness.expectedJavaOutputRead(
        original, javaNative, unverified, Snapshots.JSON.createObjectNode()),
      "An unproved byte difference cannot admit a filesize effect");
    assertFalse(Snapshots.differences(original, javaNative).isEmpty());
  }

  private static JsonNode nativeSnapshot(Path core, Path path) throws Exception {
    var process =
      new ProcessBuilder(core.toString(), "snapshot", path.toString(), "AVERAGE").start();
    byte[] output = process.getInputStream().readAllBytes();
    assertEquals(0, process.waitFor());
    return Snapshots.JSON.readTree(output);
  }
}
