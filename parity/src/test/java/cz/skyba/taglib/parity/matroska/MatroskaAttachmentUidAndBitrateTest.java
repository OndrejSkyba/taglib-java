package cz.skyba.taglib.parity.matroska;

import cz.skyba.taglib.parity.FileReferenceReadAndMutationMatrix;
import cz.skyba.taglib.parity.PayloadHash;
import cz.skyba.taglib.parity.Snapshots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent complete-file proof boundaries; no library EBML parser/renderer is used.
 */
final class MatroskaAttachmentUidAndBitrateTest {
  @TempDir
  Path directory;
  private static final long SEGMENT = 0x18538067L,
    ATTACHMENTS = 0x1941a469L,
    FILE = 0x61a7L,
    UID = 0x46aeL,
    CLUSTER = 0x1f43b675L;

  @Test
  void everyMinimumUnsignedWidthMatchesSevenAndEightByteUidsInBothDirections() throws Exception {
    Path source = save("source", document(new byte[0], 1));
    for (int width = 1; width <= 8; width++) {
      long uid = width == 8 ? Long.MIN_VALUE : (1L << ((width - 1) * 8));
      byte[] small = document(attachment(uid, 9), 1), large = document(attachment(-1, 9), 1);
      if (width == 8) large = document(attachment(-2, 9), 1);
      for (boolean reverse : List.of(false, true)) {
        var proof = proof(source, reverse ? large : small, reverse ? small : large);
        assertTrue(proof.verified(), "width=" + width + " " + proof.details());
        assertFalse(proof.rawEqual());
        assertEquals(1, proof.normalizedUidFieldCount());
        assertEquals(reverse ? 8 : width, proof.generatedUidFields().getFirst().javaBytes());
        assertEquals(reverse ? width : 8, proof.generatedUidFields().getFirst().nativeBytes());
        assertEquals(proof.normalizedJavaSha256(), proof.normalizedNativeSha256());
      }
    }
  }

  @Test
  void containingMasterSizeThresholdsAreDerivedFromUidWidths() throws Exception {
    Path source = save("source", document(new byte[0], 8));
    int crossed = 0;
    for (int payload = 70; payload < 130; payload++) {
      byte[] a = document(attachment(0x0100_0000_0000_00L, payload), 8);
      byte[] b = document(attachment(Long.MIN_VALUE, payload), 8);
      var proof = proof(source, a, b);
      assertTrue(proof.verified(), "payload=" + payload + " " + proof.details());
      if (b.length - a.length > 1) {
        crossed++;
        assertTrue(
          proof.derivedUidFields().stream().anyMatch(f -> f.kind().startsWith("Master size")));
      }
    }
    assertTrue(
      crossed >= 2, "Both AttachedFile and Attachments cross a 128-byte sizeVINT threshold");
  }

  @Test
  void segmentUsesSourceWidthOrWideningToEightAndRejectsOtherWidths() throws Exception {
    Path source = save("source", document(new byte[0], 1));
    boolean threshold = false;
    for (int payload = 60; payload < 120; payload++) {
      byte[] a = document(attachment(0x0100_0000_0000_00L, payload), 1);
      byte[] b = document(attachment(Long.MIN_VALUE, payload), 1);
      var proof = proof(source, a, b);
      assertTrue(proof.verified(), proof.details());
      if (b.length - a.length > 5) threshold = true;
    }
    assertTrue(threshold, "Segment length crosses native source-width to eight-byte threshold");
    byte[] a = document(attachment(7, 100), 1), b = document(attachment(-1, 100), 1);
    assertFalse(
      proof(source, a, join(header(), element(SEGMENT, attachment(-1, 100), 2))).verified(),
      "Minimum width 2 cannot replace native source-width widening to eight");
    byte[] badSize = b.clone();
    badSize[header().length + 4 + 7]--;
    assertFalse(proof(source, a, badSize).verified());
  }

  @Test
  void shiftedSeekPositionsMustResolveTheSamePairedTarget() throws Exception {
    Path source = save("source", document(new byte[0], 8));
    for (int payload : List.of(3, 190, 220, 240, 250, 260)) {
      byte[] a = withSeek(7, payload), b = withSeek(-1, payload);
      var proof = proof(source, a, b);
      assertTrue(proof.verified(), proof.details());
      assertTrue(proof.derivedUidFields().stream().anyMatch(f -> f.kind().equals("SeekPosition")));
      byte[] changed = b.clone();
      int position = index(changed, new byte[]{0x53, (byte) 0xac});
      changed[position + 3]++;
      assertFalse(
        proof(source, a, changed).verified(), "A plausible but wrong SeekPosition must fail");
    }
  }

  @Test
  void unrelatedHeadersCrcPayloadAndUnknownBytesRemainExact() throws Exception {
    Path source = save("source", document(new byte[0], 8));
    byte[] a =
      document(
        join(
          element(0xbf, new byte[]{1, 2, 3, 4}),
          attachment(7, 12),
          element(CLUSTER, new byte[]{11, 12, 13})),
        8);
    byte[] b =
      document(
        join(
          element(0xbf, new byte[]{1, 2, 3, 4}),
          attachment(-1, 12),
          element(CLUSTER, new byte[]{11, 12, 13})),
        8);
    assertTrue(proof(source, a, b).verified());
    for (int at :
      List.of(0, header().length + 12 + 2, index(b, new byte[]{0x46, 0x5c}) + 3, b.length - 1)) {
      byte[] corrupt = b.clone();
      corrupt[at] ^= 1;
      assertFalse(proof(source, a, corrupt).verified(), "Unrelated physical byte at " + at);
    }
    assertFalse(proof(source, a, join(b, new byte[]{1})).verified());
    byte[] unknown = b.clone();
    Arrays.fill(unknown, header().length + 4, header().length + 12, (byte) 255);
    assertFalse(
      proof(source, a, unknown).verified(), "Unknown output Segment size is not a native render");
  }

  @Test
  void nonminimalDuplicateZeroAndSourceOwnedUidsAreRejected() throws Exception {
    Path source = save("source", document(new byte[0], 8));
    byte[] reference = document(attachment(-1, 9), 8);
    assertFalse(proof(source, document(attachment(new byte[]{0, 7}, 9), 8), reference).verified());
    assertFalse(proof(source, document(attachment(0, 9), 8), reference).verified());
    byte[] a = document(join(attachment(7, 9), attachment(7, 9)), 8);
    byte[] b = document(join(attachment(-1, 9), attachment(-2, 9)), 8);
    assertFalse(proof(source, a, b).verified());
    Path existing = save("existing", document(attachment(7, 9), 8));
    assertFalse(proof(existing, document(attachment(7, 9), 8), reference).verified());
    Path duplicateSource = save("duplicate-source", a);
    assertFalse(proof(duplicateSource, document(attachment(8, 9), 8), reference).verified());
    byte[] extraHeader = join(id(UID), vint(8, 2), uint(-1));
    byte[] wrongUidHeader =
      document(element(ATTACHMENTS, element(FILE, join(fields(9), extraHeader))), 8);
    assertFalse(proof(source, document(attachment(7, 9), 8), wrongUidHeader).verified());
  }

  @Test
  void pictureReplacementAndImmutableIdentityAreRequired() throws Exception {
    Path source = save("source", document(new byte[0], 8));
    byte[] a = document(attachment(7, 9), 8), b = document(attachment(-1, 9), 8);
    Path java = save("java", a), nativeFile = save("native", b);
    assertFalse(
      PayloadHash.nativeBytesEquivalent(source, java, nativeFile, "unchanged").verified());
    byte[] changed = b.clone();
    int at = index(changed, "image/png".getBytes(StandardCharsets.US_ASCII));
    changed[at] = 'x';
    assertFalse(proof(source, a, changed).verified());
    changed = b.clone();
    at = index(changed, "cover.png".getBytes(StandardCharsets.US_ASCII));
    changed[at] = 'X';
    assertFalse(proof(source, a, changed).verified());
  }

  @Test
  @org.junit.jupiter.api.Tag("parity")
  @org.junit.jupiter.api.Tag("matroska")
  void actualNativeAttachmentSaveConfirmsSevenEightWidthsAndParentBoundaries() throws Exception {
    Path probe = root().resolve("parity/target/native-build/bin/matroska-attachment-uids-and-bit-rates");
    if (Boolean.getBoolean("taglib.requireNative"))
      assertTrue(Files.isExecutable(probe), "Required ./mvnw -Pparity verify");
    org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(probe));
    Path source = Snapshots.upstreamSource(root()).resolve("tests/data/no-tags.webm");
    for (long uid : new long[]{0xff_ffff_ffff_ffffL, Long.MIN_VALUE, -1})
      for (int payload : new int[]{80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90, 91, 92, 127, 128}) {
        Path javaPath = directory.resolve("attachment-java.webm"),
          nativePath = directory.resolve("attachment-native.webm");
        Files.copy(source, javaPath, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(source, nativePath, StandardCopyOption.REPLACE_EXISTING);
        var process =
          new ProcessBuilder(
            probe.toString(),
            "attachment",
            nativePath.toString(),
            Long.toUnsignedString(uid, 16),
            Integer.toString(payload))
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
        assertEquals(0, process.waitFor());
        byte[] data = new byte[payload];
        Arrays.fill(data, (byte) '*');
        cz.skyba.taglib.internal.codec.MatroskaCodec.writeAttachments(
          javaPath,
          List.of(new cz.skyba.taglib.matroska.AttachedFile(data, "cover.png", "image/png", uid, "")));
        assertArrayEquals(
          Files.readAllBytes(nativePath),
          Files.readAllBytes(javaPath),
          "Explicit UID="
            + Long.toUnsignedString(uid)
            + " payload="
            + payload
            + " complete native rendering");
      }
  }

  @Test
  @org.junit.jupiter.api.Tag("parity")
  @org.junit.jupiter.api.Tag("matroska")
  void independentNativeReadsOfDeterministicWidthOutputsProveTheirOwnBitrates() throws Exception {
    Path probe = root().resolve("parity/target/native-build/bin/matroska-attachment-uids-and-bit-rates"),
      core = root().resolve("parity/target/native-build/bin/file-reference-reads-and-mutations");
    if (Boolean.getBoolean("taglib.requireNative")) {
      assertTrue(Files.isExecutable(probe));
      assertTrue(Files.isExecutable(core));
    }
    org.junit.jupiter.api.Assumptions.assumeTrue(
      Files.isExecutable(probe) && Files.isExecutable(core));
    Path source = Snapshots.upstreamSource(root()).resolve("tests/data/no-tags.webm"),
      nativePath = directory.resolve("width-seven.webm"),
      javaPath = directory.resolve("width-eight.webm");
    Files.copy(source, nativePath);
    Files.copy(source, javaPath);
    for (Path path : List.of(nativePath, javaPath)) {
      long uid = path.equals(nativePath) ? 0xff_ffff_ffff_ffffL : -1;
      var process =
        new ProcessBuilder(
          probe.toString(),
          "attachment",
          path.toString(),
          Long.toUnsignedString(uid, 16),
          "9")
          .start();
      assertEquals(0, process.waitFor());
    }
    var complete =
      PayloadHash.nativeBytesEquivalent(source, javaPath, nativePath, "pictures-replace");
    assertTrue(complete.verified(), complete.details());
    assertEquals(1, Files.size(javaPath) - Files.size(nativePath));
    var rawNative = nativeSnapshot(core, nativePath);
    var rawJavaNative = nativeSnapshot(core, javaPath);
    var actual =
      Snapshots.JSON
        .createObjectNode()
        .put("completeOutputVerified", true)
        .put("completeOutputBytesEqual", false)
        .put("nativeOutputLengthForAudioDerivation", Files.size(nativePath));
    actual.putObject("completeOutput").put("length", Files.size(javaPath));
    actual
      .putObject("completeOutputComparison")
      .set("generatedUidFields", Snapshots.JSON.valueToTree(complete.generatedUidFields()));
    var derived =
      FileReferenceReadAndMutationMatrix.expectedJavaOutputPost(rawNative, rawJavaNative, actual);
    assertTrue(
      actual
        .path("nativeFileSizeAudioDerivation")
        .path("bothNativeReadsVerifyFormula")
        .asBoolean());
    assertEquals(List.of(), Snapshots.differences(derived, rawJavaNative));
    for (Path path : List.of(nativePath, javaPath)) {
      var file = cz.skyba.taglib.TagLib.read(path);
      var snapshot = Snapshots.of(file);
      snapshot.set(
        "unsupportedData", Snapshots.JSON.valueToTree(file.properties().unsupportedData()));
      assertEquals(List.of(), Snapshots.differences(nativeSnapshot(core, path), snapshot));
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("parity")
  @org.junit.jupiter.api.Tag("matroska")
  void nativeStreamSavesProveTheirOwnEofForAllUnsignedUidWidths() throws Exception {
    Path probe = root().resolve("parity/target/native-build/bin/matroska-attachment-uids-and-bit-rates");
    if (Boolean.getBoolean("taglib.requireNative")) assertTrue(Files.isExecutable(probe));
    org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(probe));
    Path source = Snapshots.upstreamSource(root()).resolve("tests/data/no-tags.webm");
    for (int width = 1; width <= 8; width++) {
      Path nativePath = directory.resolve("cursor-native.webm"),
        javaPath = directory.resolve("cursor-java.webm");
      Files.copy(source, nativePath, StandardCopyOption.REPLACE_EXISTING);
      Files.copy(source, javaPath, StandardCopyOption.REPLACE_EXISTING);
      long nativeUid = width == 8 ? Long.MIN_VALUE : 1L << ((width - 1) * 8);
      long[] cursors = new long[2];
      int side = 0;
      for (Path path : List.of(nativePath, javaPath)) {
        long uid = path.equals(nativePath) ? nativeUid : -1;
        var process =
          new ProcessBuilder(
            probe.toString(),
            "attachment-stream",
            path.toString(),
            Long.toUnsignedString(uid, 16),
            "9")
            .start();
        var output =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.US_ASCII)
            .strip()
            .split("\\s+");
        assertEquals(0, process.waitFor());
        assertEquals(2, output.length);
        cursors[side++] = Long.parseLong(output[0]);
        assertEquals(
          Files.size(path), cursors[side - 1], "Actual native borrowed-stream save ends at EOF");
        assertEquals(cursors[side - 1], Long.parseLong(output[1]));
      }
      var proof =
        PayloadHash.nativeBytesEquivalent(source, javaPath, nativePath, "pictures-replace");
      assertTrue(proof.verified(), proof.details());
      var expected =
        Snapshots.JSON
          .createObjectNode()
          .put("saved", true)
          .put("borrowedStreamCursorAfterSave", cursors[0]);
      expected.putObject("completeOutput").put("length", Files.size(nativePath));
      var actual =
        Snapshots.JSON
          .createObjectNode()
          .put("saved", true)
          .put("completeOutputVerified", true)
          .put("completeOutputBytesEqual", false)
          .put("borrowedStreamCursorAfterSave", cursors[1]);
      actual.putObject("completeOutput").put("length", Files.size(javaPath));
      actual
        .putObject("completeOutputComparison")
        .set("generatedUidFields", Snapshots.JSON.valueToTree(proof.generatedUidFields()));
      assertEquals(
        cursors[1],
        FileReferenceReadAndMutationMatrix.expectedJavaOutputCursor(
            expected, actual, "pictures-replace")
          .asLong());
      assertTrue(actual.path("nativeFileSizeCursorDerivation").path("bothAtOwnEof").asBoolean());
      assertEquals(
        cursors[0],
        expected.path("borrowedStreamCursorAfterSave").asLong(),
        "Raw native evidence retained");
    }
  }

  @Test
  void eofCursorDerivationRejectsMissingProofAndOtherPositions() {
    var expected =
      Snapshots.JSON
        .createObjectNode()
        .put("saved", true)
        .put("borrowedStreamCursorAfterSave", 614);
    expected.putObject("completeOutput").put("length", 614);
    var actual =
      Snapshots.JSON
        .createObjectNode()
        .put("saved", true)
        .put("completeOutputVerified", true)
        .put("completeOutputBytesEqual", false)
        .put("borrowedStreamCursorAfterSave", 615);
    actual.putObject("completeOutput").put("length", 615);
    var fields = actual.putObject("completeOutputComparison").putArray("generatedUidFields");
    fields.addObject().put("javaBytes", 8).put("nativeBytes", 7);
    assertEquals(
      615,
      FileReferenceReadAndMutationMatrix.expectedJavaOutputCursor(
          expected, actual, "pictures-replace")
        .asLong());
    actual.put("completeOutputVerified", false);
    assertEquals(
      614,
      FileReferenceReadAndMutationMatrix.expectedJavaOutputCursor(
          expected, actual, "pictures-replace")
        .asLong());
    actual.put("completeOutputVerified", true).put("saved", false);
    assertEquals(
      614,
      FileReferenceReadAndMutationMatrix.expectedJavaOutputCursor(
          expected, actual, "pictures-replace")
        .asLong());
    actual.put("saved", true).put("borrowedStreamCursorAfterSave", 614);
    assertEquals(
      614,
      FileReferenceReadAndMutationMatrix.expectedJavaOutputCursor(
          expected, actual, "pictures-replace")
        .asLong());
    actual.put("borrowedStreamCursorAfterSave", 615);
    expected.put("borrowedStreamCursorAfterSave", 613);
    assertEquals(
      613,
      FileReferenceReadAndMutationMatrix.expectedJavaOutputCursor(
          expected, actual, "pictures-replace")
        .asLong());
    expected.put("borrowedStreamCursorAfterSave", 614);
    assertEquals(
      614,
      FileReferenceReadAndMutationMatrix.expectedJavaOutputCursor(expected, actual, "save-only")
        .asLong());
    fields.removeAll();
    assertEquals(
      614,
      FileReferenceReadAndMutationMatrix.expectedJavaOutputCursor(
          expected, actual, "pictures-replace")
        .asLong());
  }

  private static com.fasterxml.jackson.databind.JsonNode nativeSnapshot(Path core, Path path)
    throws Exception {
    var process =
      new ProcessBuilder(core.toString(), "snapshot", path.toString(), "AVERAGE").start();
    byte[] output = process.getInputStream().readAllBytes();
    assertEquals(0, process.waitFor());
    return Snapshots.JSON.readTree(output);
  }

  @Test
  void filesizeBitrateRequiresCompleteUidProofAndBothNativeFormulaValues() {
    var nativePost = Snapshots.JSON.createObjectNode().put("valid", true);
    nativePost.putObject("audio").put("lengthInMilliseconds", 1).put("bitrate", 4912);
    var nativeJava = nativePost.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) nativeJava.get("audio")).put("bitrate", 4920);
    var actual =
      Snapshots.JSON
        .createObjectNode()
        .put("completeOutputVerified", true)
        .put("completeOutputBytesEqual", false)
        .put("nativeOutputLengthForAudioDerivation", 614);
    actual.putObject("completeOutput").put("length", 615);
    actual
      .putObject("completeOutputComparison")
      .putArray("generatedUidFields")
      .addObject()
      .put("javaBytes", 8)
      .put("nativeBytes", 7);
    var derived =
      FileReferenceReadAndMutationMatrix.expectedJavaOutputPost(nativePost, nativeJava, actual);
    assertEquals(4920, derived.path("audio").path("bitrate").asInt());
    assertEquals(
      4912,
      nativePost.path("audio").path("bitrate").asInt(),
      "Raw native snapshot remains unchanged");
    assertTrue(
      actual
        .path("nativeFileSizeAudioDerivation")
        .path("bothNativeReadsVerifyFormula")
        .asBoolean());
    actual.put("completeOutputVerified", false);
    assertEquals(
      nativePost,
      FileReferenceReadAndMutationMatrix.expectedJavaOutputPost(nativePost, nativeJava, actual));
    actual.put("completeOutputVerified", true);
    ((com.fasterxml.jackson.databind.node.ObjectNode) nativeJava.get("audio")).put("bitrate", 4921);
    assertEquals(
      nativePost,
      FileReferenceReadAndMutationMatrix.expectedJavaOutputPost(nativePost, nativeJava, actual));
    ((com.fasterxml.jackson.databind.node.ObjectNode) nativeJava.get("audio")).put("bitrate", 4920);
    ((com.fasterxml.jackson.databind.node.ArrayNode)
      actual.path("completeOutputComparison").get("generatedUidFields"))
      .removeAll();
    assertEquals(
      nativePost,
      FileReferenceReadAndMutationMatrix.expectedJavaOutputPost(nativePost, nativeJava, actual));
  }

  @Test
  @org.junit.jupiter.api.Tag("parity")
  @org.junit.jupiter.api.Tag("matroska")
  void nativeUIntAndVintOracleConfirmUnsignedWidthsAndReservedBoundaryBehavior() throws Exception {
    Path probe = root().resolve("parity/target/native-build/bin/matroska-attachment-uids-and-bit-rates");
    if (Boolean.getBoolean("taglib.requireNative"))
      assertTrue(Files.isExecutable(probe), "Required ./mvnw -Pparity verify");
    org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(probe));
    long[] uids = {
      0,
      1,
      255,
      256,
      65535,
      65536,
      0xffff_ffffL,
      0x1_0000_0000L,
      0xff_ffff_ffff_ffffL,
      0x100_0000_0000_000L,
      Long.MIN_VALUE,
      -1
    };
    for (long uid : uids)
      for (int size : new int[]{126, 127, 128, 16382, 16383, 16384})
        for (int floor : new int[]{0, 2, 8}) {
          var process =
            new ProcessBuilder(
              probe.toString(),
              Long.toUnsignedString(uid, 16),
              Integer.toString(size),
              Integer.toString(floor))
              .start();
          String[] rendered =
            new String(process.getInputStream().readAllBytes(), StandardCharsets.US_ASCII)
              .strip()
              .split("\\R");
          assertEquals(0, process.waitFor());
          assertEquals(HexFormat.of().formatHex(element(UID, uint(uid))), rendered[0]);
          int width = 1;
          while (size > (1L << (7 * width)) - 1) width++;
          assertEquals(HexFormat.of().formatHex(vint(size, Math.max(width, floor))), rendered[1]);
        }
  }

  @Test
  void availableQualificationPairsHaveBoundedCompleteByteProofs() throws Exception {
    Path root = root();
    Path qualification = root.resolve("parity/target/qualification-final/matrix/copies");
    if (!Files.isDirectory(qualification))
      return; // Optional historical evidence; synthetic boundaries above are mandatory.
    for (String name : List.of("no-tags.webm", "optimized.mkv")) {
      Path pair = qualification.resolve("facade").resolve(name).resolve("pictures-replace");
      if (!Files.isDirectory(pair)) continue;
      var proof =
        PayloadHash.nativeBytesEquivalent(
          Snapshots.upstreamSource(root).resolve("tests/data/" + name),
          pair.resolve("java-" + name),
          pair.resolve("native-" + name),
          "pictures-replace");
      assertTrue(proof.verified(), name + " " + proof.details());
      // Fresh qualification may overwrite this evidence with equal-width random values.
      // Required width coverage is deterministic in the synthetic/native cases above.
    }
  }

  private PayloadHash.NativeBytes proof(Path source, byte[] a, byte[] b) throws Exception {
    return PayloadHash.nativeBytesEquivalent(
      source, save("java", a), save("native", b), "pictures-replace");
  }

  private Path save(String name, byte[] bytes) throws Exception {
    return Files.write(directory.resolve(name + ".mkv"), bytes);
  }

  private static Path root() {
    Path p = Path.of("").toAbsolutePath();
    while (p != null && !Files.isDirectory(p.resolve("parity/native"))) p = p.getParent();
    return p;
  }

  private static byte[] header() {
    return element(0x1a45dfa3L, new byte[0]);
  }

  private static byte[] document(byte[] body, int sourceWidth) {
    return join(
      header(),
      element(SEGMENT, body, body.length <= (1L << (7 * sourceWidth)) - 1 ? sourceWidth : 8));
  }

  private static byte[] attachment(long uid, int payload) {
    return attachment(uint(uid), payload);
  }

  private static byte[] attachment(byte[] uid, int payload) {
    return element(ATTACHMENTS, element(FILE, join(fields(payload), element(UID, uid))));
  }

  private static byte[] fields(int payload) {
    byte[] data = new byte[payload];
    Arrays.fill(data, (byte) 42);
    return join(
      element(0x466e, "cover.png".getBytes(StandardCharsets.UTF_8)),
      element(0x4660, "image/png".getBytes(StandardCharsets.US_ASCII)),
      element(0x465c, data));
  }

  private static byte[] withSeek(long uid, int payload) {
    byte[] attachment = attachment(uid, payload), seek = new byte[0];
    for (int i = 0; i < 10; i++) {
      byte[] next =
        element(
          0x114d9b74L,
          element(
            0x4dbb,
            join(
              element(0x53ab, id(CLUSTER)),
              element(0x53ac, uint(seek.length + attachment.length)))));
      if (Arrays.equals(seek, next)) break;
      seek = next;
    }
    return document(join(seek, attachment, element(CLUSTER, new byte[]{1, 2, 3})), 8);
  }

  private static byte[] element(long id, byte[] body) {
    int width = 1;
    while (body.length > (1L << (7 * width)) - 1) width++;
    return element(id, body, width);
  }

  private static byte[] element(long id, byte[] body, int width) {
    return join(id(id), vint(body.length, width), body);
  }

  private static byte[] id(long value) {
    return uint(value);
  }

  private static byte[] uint(long value) {
    int width = 1;
    while (width < 8 && value >>> (width * 8) != 0) width++;
    byte[] b = new byte[width];
    for (int i = width - 1; i >= 0; i--) {
      b[i] = (byte) value;
      value >>>= 8;
    }
    return b;
  }

  private static byte[] vint(long value, int width) {
    byte[] b = new byte[width];
    value |= 1L << (7 * width);
    for (int i = width - 1; i >= 0; i--) {
      b[i] = (byte) value;
      value >>>= 8;
    }
    return b;
  }

  private static byte[] join(byte[]... values) {
    var out = new ByteArrayOutputStream();
    for (byte[] value : values) out.writeBytes(value);
    return out.toByteArray();
  }

  private static int index(byte[] bytes, byte[] wanted) {
    outer:
    for (int i = 0; i <= bytes.length - wanted.length; i++) {
      for (int j = 0; j < wanted.length; j++) if (bytes[i + j] != wanted[j]) continue outer;
      return i;
    }
    throw new AssertionError("Missing test field");
  }
}
