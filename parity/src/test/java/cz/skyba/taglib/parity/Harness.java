package cz.skyba.taglib.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cz.skyba.taglib.Format;
import cz.skyba.taglib.Picture;
import cz.skyba.taglib.TagLib;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Executable read/write comparisons, outside the library artifact.
 */
public final class Harness {
  private static final Map<String, String> WRITE_FIELDS =
    Map.of(
      "TITLE",
      "Port conformance — Žluťoučký",
      "ARTIST",
      "TagLib Java",
      "ALBUM",
      "Cross-language test",
      "COMMENT",
      "Preserve the audio payload",
      "GENRE",
      "Jazz",
      "DATE",
      "2026",
      "TRACKNUMBER",
      "7");

  private Harness() {
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 0 || !args[0].equals("verify"))
      throw new IllegalArgumentException("verify ORACLE CORPUS OUTPUT [--read-only]");
    verify(args);
  }

  record Result(int exitCode, String output, String error) {
  }

  private static Result process(List<String> command, Path scratch, int seconds) throws Exception {
    Files.createDirectories(scratch);
    Path stdout = Files.createTempFile(scratch, "stdout-", ".txt"),
      stderr = Files.createTempFile(scratch, "stderr-", ".txt");
    try {
      Process p =
        new ProcessBuilder(command)
          .redirectOutput(stdout.toFile())
          .redirectError(stderr.toFile())
          .start();
      if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
        p.destroyForcibly().waitFor();
        throw new IOException("Timed out after " + seconds + " seconds: " + command);
      }
      return new Result(p.exitValue(), Files.readString(stdout), Files.readString(stderr));
    } finally {
      Files.deleteIfExists(stdout);
      Files.deleteIfExists(stderr);
    }
  }

  private static JsonNode nativeSnapshot(Path oracle, Path file, Path scratch) throws Exception {
    var result = process(List.of(oracle.toString(), "snapshot", file.toString()), scratch, 30);
    if (result.exitCode != 0) throw new IOException("Native oracle failed: " + result.error);
    return Snapshots.JSON.readTree(result.output);
  }

  private static ObjectNode javaSnapshot(Path file, ObjectNode row) {
    try {
      return Snapshots.of(TagLib.read(file));
    } catch (cz.skyba.taglib.TagLibException | java.io.UncheckedIOException e) {
      row.put("javaRejection", e.getClass().getSimpleName() + ": " + e.getMessage());
      return Snapshots.invalid();
    } catch (RuntimeException e) {
      row.put("javaCrash", e.getClass().getSimpleName() + ": " + e.getMessage());
      return Snapshots.invalid();
    }
  }

  public static ObjectNode verify(String[] args) throws Exception {
    if (args.length < 4) throw new IllegalArgumentException("verify requires ORACLE CORPUS OUTPUT");
    Path oracle = Path.of(args[1]).toAbsolutePath(),
      corpus = Path.of(args[2]).toAbsolutePath(),
      output = Path.of(args[3]).toAbsolutePath();
    boolean reportOnly = Arrays.asList(args).contains("--report-only"),
      writes = !Arrays.asList(args).contains("--read-only");
    if (output.startsWith(corpus))
      throw new IllegalArgumentException("Report directory must not be inside the corpus");
    Files.createDirectories(output);
    Path scratch = output.resolve("scratch");
    var nativeVersion = process(List.of(oracle.toString(), "version"), scratch, 10);
    if (nativeVersion.exitCode != 0
      || !Snapshots.JSON
      .readTree(nativeVersion.output)
      .path("version")
      .asText()
      .equals(Snapshots.upstreamVersion()))
      throw new IOException(
        "Oracle release does not match Java upstream target "
          + Snapshots.upstreamVersion()
          + ": "
          + nativeVersion.output);
    List<Path> files;
    try (var stream = Files.walk(corpus)) {
      files = stream.filter(Files::isRegularFile).sorted().toList();
    }
    if (files.isEmpty()) throw new IOException("No corpus files: " + corpus);
    Path manifest = output.resolve("all-files.txt");
    Files.write(manifest, files.stream().map(Path::toString).toList());
    var nativeResult =
      process(List.of(oracle.toString(), "manifest", manifest.toString()), scratch, 180);
    if (nativeResult.exitCode != 0)
      throw new IOException("Native oracle failed: " + nativeResult.error);
    Files.writeString(output.resolve("native-diagnostics.log"), nativeResult.error);
    var nativeByPath = new HashMap<Path, JsonNode>();
    for (String line : nativeResult.output.lines().toList()) {
      var node = Snapshots.JSON.readTree(line);
      nativeByPath.put(Path.of(node.get("path").asText()), node.get("snapshot"));
    }
    var report = Snapshots.JSON.createObjectNode();
    report
      .put("upstreamVersion", Snapshots.upstreamVersion())
      .put("generatedAt", Instant.now().toString())
      .put("corpus", corpus.toString());
    var rows = report.putArray("files");
    var artworkSources = new EnumMap<Format, Path>(Format.class);
    var artworkScores = new EnumMap<Format, Integer>(Format.class);
    int matched = 0,
      nativeValid = 0,
      invalidMatched = 0,
      writeMatched = 0,
      writeFailed = 0,
      writeUnsupported = 0,
      writeUnsupportedMatched = 0,
      writeUnsupportedFailed = 0,
      audioVerified = 0,
      structureVerified = 0;
    for (Path file : files) {
      var row = rows.addObject().put("file", corpus.relativize(file).toString());
      var inputHash = PayloadHash.wholeFile(file);
      row.put("inputBytes", inputHash.bytes()).put("inputSha256", inputHash.sha256());
      JsonNode expected = nativeByPath.get(file);
      if (expected == null) throw new IOException("Missing native snapshot: " + file);
      var actual = javaSnapshot(file, row);
      row.set("native", expected);
      row.set("java", actual);
      var differences = Snapshots.differences(expected, actual);
      if (row.has("javaCrash"))
        differences.add("Java unchecked exception: " + row.get("javaCrash").asText());
      row.set("differences", Snapshots.JSON.valueToTree(differences));
      boolean valid = expected.path("valid").asBoolean();
      if (valid) nativeValid++;
      if (writes && valid && actual.path("valid").asBoolean()) {
        var format = TagLib.read(file).format();
        int score =
          (differences.isEmpty() ? 100 : 0)
            + (actual.path("audio").path("sampleRate").asInt() > 0 ? 20 : 0)
            + (actual.path("audio").path("lengthInMilliseconds").asLong() > 0 ? 20 : 0)
            - (int) Math.min(Files.size(file) / 100_000, 10);
        if (score > artworkScores.getOrDefault(format, -1)) {
          artworkScores.put(format, score);
          artworkSources.put(format, file);
        }
      }
      if (differences.isEmpty()) {
        matched++;
        if (!valid) invalidMatched++;
      }
      if (writes && valid) {
        var result =
          writeCheck(
            oracle,
            file,
            output.resolve("write-copies").resolve(corpus.relativize(file)),
            scratch,
            expected,
            actual);
        row.set("write", result);
        if (result.path("nativeUnsupported").asBoolean()) {
          writeUnsupported++;
          if (result.path("passed").asBoolean()) writeUnsupportedMatched++;
          else writeUnsupportedFailed++;
        }
        if (result.path("passed").asBoolean()) writeMatched++;
        else writeFailed++;
        if (result.path("audioPayloadVerified").asBoolean()) audioVerified++;
        if (result.path("writePreservationVerified").asBoolean()) structureVerified++;
      }
    }
    int artworkFailed = 0, artworkMatched = 0, artworkNativeUnsupported = 0;
    var artworkCases = report.putArray("artworkCases");
    if (writes)
      for (var source : artworkSources.entrySet()) {
        var art =
          artworkCheck(
            oracle,
            source.getValue(),
            output.resolve("artwork-copies").resolve(source.getKey().name()),
            scratch);
        art.put("format", source.getKey().name())
          .put("source", corpus.relativize(source.getValue()).toString());
        artworkCases.add(art);
        if (art.path("nativeUnsupported").asBoolean()) artworkNativeUnsupported++;
        if (art.path("passed").asBoolean()) artworkMatched++;
        else artworkFailed++;
      }
    var summary =
      report
        .putObject("summary")
        .put("totalFixtures", files.size())
        .put("nativeAccepted", nativeValid)
        .put("readMatched", matched)
        .put("invalidMatched", invalidMatched)
        .put("readMismatched", files.size() - matched)
        .put("writesEnabled", writes)
        .put("writeAttempted", writes ? nativeValid : 0)
        .put("writeMatched", writeMatched)
        .put("writeFailed", writeFailed)
        .put("nativeWriteUnsupported", writeUnsupported)
        .put("nativeWriteUnsupportedMatched", writeUnsupportedMatched)
        .put("nativeWriteUnsupportedFailed", writeUnsupportedFailed)
        .put(
          "writeCountPolicy",
          "writeAttempted = writeMatched + writeFailed; nativeWriteUnsupported is an"
            + " overlapping subset, with explicit matched/failed counts")
        .put("audioPayloadVerified", audioVerified)
        .put("independentStructureVerified", structureVerified)
        .put("artworkCases", artworkSources.size())
        .put("artworkMatched", artworkMatched)
        .put("artworkFailed", artworkFailed)
        .put("artworkNativeUnsupported", artworkNativeUnsupported);
    var completeProofs = new ArrayList<JsonNode>();
    for (var row : rows)
      if (row.path("write").has("completeNativeBytes"))
        completeProofs.add(row.path("write").get("completeNativeBytes"));
    for (var artwork : artworkCases)
      for (var operation : artwork.path("operations"))
        if (operation.has("completeNativeBytes"))
          completeProofs.add(operation.get("completeNativeBytes"));
    summary
      .put("completeOutputComparisons", completeProofs.size())
      .put(
        "completeOutputByteDifferences",
        completeProofs.stream().filter(p -> !p.path("verified").asBoolean()).count())
      .put(
        "completeOutputRawByteMatches",
        completeProofs.stream().filter(p -> p.path("rawEqual").asBoolean()).count())
      .put(
        "generatedMatroskaUidNormalizations",
        completeProofs.stream()
          .filter(p -> p.path("normalizedUidFieldCount").asInt() > 0)
          .count())
      .put(
        "normalizedGeneratedUidFields",
        completeProofs.stream().mapToInt(p -> p.path("normalizedUidFieldCount").asInt()).sum())
      .put(
        "derivedGeneratedUidFields",
        completeProofs.stream().mapToInt(p -> p.path("derivedUidFields").size()).sum())
      .put(
        "completeOutputPolicy",
        "Complete native/Java serialized file SHA-256 and length required for every successful"
          + " text/artwork write. Only source-proven fresh Matroska PICTURE FileUIDs and"
          + " their independently derived containing sizes/target-resolved SeekPositions may"
          + " be normalized; all unrelated bytes, raw hashes, original widths and field"
          + " lifetime proofs retained. Filesize bitrate effects require that full byte proof"
          + " and both independent native reads verifying their own exact filesize formula.");
    summary.put(
      "strictPassed",
      files.size() == matched && (!writes || (writeFailed == 0 && artworkFailed == 0)));
    Files.writeString(
      output.resolve("media-preservation.json"),
      Snapshots.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    System.out.println(Snapshots.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
    return report;
  }

  private static ObjectNode writeCheck(
    Path oracle,
    Path source,
    Path copies,
    Path scratch,
    JsonNode originalNative,
    JsonNode originalJava)
    throws Exception {
    var result = Snapshots.JSON.createObjectNode();
    var issues = new ArrayList<String>();
    Files.createDirectories(copies);
    Path nativeCopy = copies.resolve("native-" + source.getFileName()),
      javaCopy = copies.resolve("java-" + source.getFileName());
    Files.copy(source, nativeCopy, StandardCopyOption.REPLACE_EXISTING);
    Files.copy(source, javaCopy, StandardCopyOption.REPLACE_EXISTING);
    var nativeWrite =
      process(List.of(oracle.toString(), "write", nativeCopy.toString()), scratch, 30);
    JsonNode nativeWriteResult;
    try {
      nativeWriteResult = Snapshots.JSON.readTree(nativeWrite.output);
    } catch (Exception e) {
      nativeWriteResult = Snapshots.JSON.createObjectNode().put("parseError", nativeWrite.error);
    }
    result.set("nativeWrite", nativeWriteResult);
    if (nativeWriteResult != null
      && !nativeWriteResult.path("saved").asBoolean()
      && nativeWrite.exitCode == 4) {
      result.put("nativeUnsupported", true);
      try {
        var file = TagLib.read(javaCopy);
        var properties = file.properties().toBuilder();
        WRITE_FIELDS.forEach(properties::put);
        TagLib.write(javaCopy, properties.build());
        result
          .put("javaRejected", false)
          .put("passed", false)
          .put("unsupportedMismatch", "Java accepted a text write unsupported by native TagLib");
      } catch (cz.skyba.taglib.TagLibException | UnsupportedOperationException e) {
        boolean unchanged = Files.mismatch(source, javaCopy) == -1;
        result
          .put("javaRejected", true)
          .put("passed", unchanged)
          .put("rejectedWriteLeftFileUnchanged", unchanged)
          .put("javaRejection", e.getMessage());
      }
      return result;
    }
    if (nativeWrite.exitCode != 0) issues.add("Native write failed: " + nativeWrite.error);
    var nativeAfter = nativeSnapshot(oracle, nativeCopy, scratch);
    var crossReadRow = Snapshots.JSON.createObjectNode();
    var javaReadsNative = javaSnapshot(nativeCopy, crossReadRow);
    result.set("nativeWrittenReadByNative", nativeAfter);
    result.set("nativeWrittenReadByJava", javaReadsNative);
    result.set("nativeWrittenJavaDiagnostics", crossReadRow);
    for (var d : Snapshots.differences(nativeAfter, javaReadsNative))
      issues.add("Native write / Java read: " + d);
    try {
      var original = TagLib.read(javaCopy);
      var builder = original.properties().toBuilder();
      WRITE_FIELDS.forEach(builder::put);
      var writeResult = TagLib.write(javaCopy, builder.build());
      var nativeRejected = new TreeSet<String>();
      if (nativeWriteResult != null)
        nativeWriteResult.path("rejectedKeys").forEach(k -> nativeRejected.add(k.asText()));
      var javaRejected = new TreeSet<>(writeResult.rejected().asMap().keySet());
      result.set("javaRejectedKeys", Snapshots.JSON.valueToTree(javaRejected));
      if (!nativeRejected.equals(javaRejected))
        issues.add(
          "Rejected property keys differ: native=" + nativeRejected + ", java=" + javaRejected);
      var javaWrittenNativeRead = nativeSnapshot(oracle, javaCopy, scratch);
      result.set("javaWrittenReadByNative", javaWrittenNativeRead);
      var byteProof = verifyCompleteBytes(source, javaCopy, nativeCopy, "text", result, issues);
      var expectedRead =
        expectedJavaOutputRead(nativeAfter, javaWrittenNativeRead, byteProof, result);
      for (var d : Snapshots.differences(expectedRead, javaWrittenNativeRead))
        issues.add("Java write / native read: " + d);
      var javaWrittenJavaRead = Snapshots.of(TagLib.read(javaCopy));
      result.set("javaWrittenReadByJava", javaWrittenJavaRead);
      for (var d : Snapshots.differences(javaWrittenNativeRead, javaWrittenJavaRead))
        issues.add("Java write / Java read: " + d);
      var nativeAudioEffects =
        Snapshots.differences(originalNative.path("audio"), nativeAfter.path("audio"));
      if (!nativeAudioEffects.isEmpty())
        result.set("nativeAudioPropertyEffects", Snapshots.JSON.valueToTree(nativeAudioEffects));
      for (var d :
        Snapshots.differences(
          originalNative.path("pictures"), javaWrittenNativeRead.path("pictures")))
        issues.add("Artwork changed: " + d);
      verifyPayload(source, javaCopy, nativeCopy, scratch, result, issues);
      var opaqueBefore = nativeCommand(oracle, "opaque", source, scratch);
      var opaqueAfter = nativeCommand(oracle, "opaque", javaCopy, scratch);
      var nativeOpaqueAfter = nativeCommand(oracle, "opaque", nativeCopy, scratch);
      result.set("opaqueId3FramesBefore", opaqueBefore);
      result.set("opaqueId3FramesAfter", opaqueAfter);
      result.set("nativeOpaqueId3FramesAfter", nativeOpaqueAfter);
      var nativeTransforms = Snapshots.differences(opaqueBefore, nativeOpaqueAfter);
      if (!nativeTransforms.isEmpty())
        result.set("nativeOpaqueTransformations", Snapshots.JSON.valueToTree(nativeTransforms));
      var originalToJava = Snapshots.differences(opaqueBefore, opaqueAfter);
      if (!originalToJava.isEmpty())
        result.set("originalToJavaOpaqueChanges", Snapshots.JSON.valueToTree(originalToJava));
      for (var d : Snapshots.differences(nativeOpaqueAfter, opaqueAfter))
        issues.add("Unsupported ID3 frame differs from native write: " + d);
    } catch (Exception e) {
      issues.add("Java write/read failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
    result.set("differences", Snapshots.JSON.valueToTree(issues));
    result.put("passed", issues.isEmpty());
    return result;
  }

  private static JsonNode nativeCommand(Path oracle, String command, Path path, Path scratch)
    throws Exception {
    var result = process(List.of(oracle.toString(), command, path.toString()), scratch, 30);
    if (result.exitCode != 0)
      throw new IOException("Native " + command + " failed: " + result.error);
    return Snapshots.JSON.readTree(result.output);
  }

  private static void verifyPayload(
    Path source,
    Path javaCopy,
    Path nativeCopy,
    Path scratch,
    ObjectNode result,
    List<String> issues)
    throws Exception {
    var rawBefore = PayloadHash.of(source);
    var rawAfter = PayloadHash.of(javaCopy);
    if (rawBefore != null && rawAfter != null && rawBefore.scope().equals(rawAfter.scope())) {
      boolean equal =
        rawBefore.sha256().equals(rawAfter.sha256()) && rawBefore.bytes() == rawAfter.bytes();
      result
        .put("audioPayloadVerified", equal && rawBefore.bytes() > 0)
        .put("audioPayloadScope", rawBefore.scope())
        .put("audioPayloadBytes", rawBefore.bytes())
        .put("audioPayloadBefore", rawBefore.sha256())
        .put("audioPayloadAfter", rawAfter.sha256());
      if (equal && rawBefore.bytes() == 0)
        result
          .put("audioPayloadAbsent", true)
          .put(
            "audioPayloadStatus",
            "NO_MEDIA_PAYLOAD: parsed container has no encoded media bytes; no"
              + " audio-preservation claim is made");
      if (!equal) issues.add("Encoded media bytes changed");
    } else {
      var proof = PayloadHash.preservation(source, javaCopy, nativeCopy);
      if (proof != null) {
        result
          .put("audioPayloadVerified", false)
          .put("writePreservationVerified", proof.verified())
          .put("audioPayloadAbsent", proof.mediaAbsent())
          .put("audioPayloadScope", proof.before().scope())
          .put("retainedOriginalBytes", proof.before().bytes())
          .put("retainedOriginalSha256", proof.before().sha256())
          .put("retainedJavaSha256", proof.after().sha256())
          .put("retainedNativeSha256", proof.nativeAfter().sha256())
          .put("independentJavaMetadataSha256", proof.javaStructure().sha256())
          .put("independentNativeMetadataSha256", proof.nativeStructure().sha256())
          .put("writePreservationPolicy", proof.policy())
          .put("writePreservationDetails", proof.details())
          .put(
            "audioPayloadStatus",
            proof.verified()
              ? "INDEPENDENT_WRITE_PROOF: complete structure/native effect and original"
              + " retained bytes verified; no empty decode is counted as preserved"
              + " audio"
              : "FAILED_INDEPENDENT_WRITE_PROOF: retained bytes or complete native metadata"
              + " effect differ");
        if (!proof.verified())
          issues.add("Independent structure/preservation differs from source/native write");
        return;
      }
      String before = audioHash(source, scratch),
        after = audioHash(javaCopy, scratch),
        nativeHash = audioHash(nativeCopy, scratch);
      result.put("audioPayloadScope", "FFmpeg audio packet SHA-256 fallback");
      if (before != null) result.put("audioPayloadBefore", before);
      if (after != null) result.put("audioPayloadAfter", after);
      if (nativeHash != null) result.put("nativeAudioPayloadAfter", nativeHash);
      if (before == null || after == null) {
        result
          .put("audioPayloadVerified", false)
          .put(
            "audioPayloadStatus",
            "UNVERIFIED: no independent extractor or ffmpeg packet hash available");
        issues.add("Audio payload preservation unverified");
      } else if (!before.equals(after) && nativeHash != null && !before.equals(nativeHash)) {
        result
          .put("audioPayloadVerified", false)
          .put(
            "audioPayloadStatus",
            "UNVERIFIED: ffmpeg packet hash also changes after native write; may include"
              + " container metadata");
        issues.add("Audio payload preservation unverified: native write also changes demuxer hash");
      } else {
        boolean equal = before.equals(after);
        result.put("audioPayloadVerified", equal);
        if (!equal) issues.add("Audio packet payload changed");
      }
    }
  }

  private static ObjectNode artworkCheck(Path oracle, Path source, Path copies, Path scratch)
    throws Exception {
    Files.createDirectories(copies);
    Path nativeCopy = copies.resolve("native-" + source.getFileName()),
      javaCopy = copies.resolve("java-" + source.getFileName());
    Files.copy(source, nativeCopy, StandardCopyOption.REPLACE_EXISTING);
    Files.copy(source, javaCopy, StandardCopyOption.REPLACE_EXISTING);
    var result = Snapshots.JSON.createObjectNode();
    result.put(
      "operationContract",
      "Replace text properties with the existing map and replace/clear artwork in the same save,"
        + " matching TagLib.write(path, properties, pictures)");
    var issues = new ArrayList<String>();
    var operations = result.putArray("operations");
    byte[] png =
      HexFormat.of()
        .parseHex(
          "89504e470d0a1a0a0000000d4948445200000001000000010804000000b51c0c020000000b4944415478da6364f80f00010501012718e3660000000049454e44ae426082");
    for (String operation : List.of("replace", "clear")) {
      var row = operations.addObject().put("operation", operation);
      var nativeResult =
        process(
          List.of(oracle.toString(), "write-artwork", nativeCopy.toString(), operation),
          scratch,
          30);
      var nativeStatus = Snapshots.JSON.readTree(nativeResult.output);
      row.set("nativeStatus", nativeStatus);
      List<Picture> pictures =
        operation.equals("clear")
          ? List.of()
          : List.of(
          new Picture(3, "image/png", "Front cover", png, 1, 1, 8, 0),
          new Picture(4, "image/png", "Back cover", png, 1, 1, 8, 0));
      if (nativeStatus != null
        && !nativeStatus.path("supported").asBoolean()
        && nativeResult.exitCode == 5) {
        result.put("nativeUnsupported", true);
        var before = PayloadHash.wholeFile(javaCopy);
        try {
          var file = TagLib.read(javaCopy);
          TagLib.write(javaCopy, file.properties(), pictures);
          issues.add("Java accepted artwork " + operation + " unsupported by native generic API");
        } catch (cz.skyba.taglib.TagLibException | UnsupportedOperationException e) {
          boolean unchanged = before.equals(PayloadHash.wholeFile(javaCopy));
          row.put("javaRejected", e.getMessage()).put("rejectedWriteLeftFileUnchanged", unchanged);
          if (!unchanged) issues.add("Rejected artwork write changed the file");
        }
        continue;
      }
      if (nativeResult.exitCode != 0) {
        issues.add("Native artwork " + operation + " failed: " + nativeResult.error);
        continue;
      }
      var nativeAfter = nativeSnapshot(oracle, nativeCopy, scratch);
      row.set("nativeWrittenReadByNative", nativeAfter);
      var diagnostics = Snapshots.JSON.createObjectNode();
      var nativeReadByJava = javaSnapshot(nativeCopy, diagnostics);
      row.set("nativeWrittenReadByJava", nativeReadByJava);
      row.set("javaReadDiagnostics", diagnostics);
      for (var d : Snapshots.differences(nativeAfter, nativeReadByJava))
        issues.add(operation + " native write / Java read: " + d);
      try {
        var file = TagLib.read(javaCopy);
        TagLib.write(javaCopy, file.properties(), pictures);
        var javaAfter = nativeSnapshot(oracle, javaCopy, scratch);
        row.set("javaWrittenReadByNative", javaAfter);
        var byteProof =
          verifyCompleteBytes(source, javaCopy, nativeCopy, "pictures-" + operation, row, issues);
        var expectedRead = expectedJavaOutputRead(nativeAfter, javaAfter, byteProof, row);
        for (var d : Snapshots.differences(expectedRead, javaAfter))
          issues.add(operation + " Java write / native read: " + d);
        var ownRead = Snapshots.of(TagLib.read(javaCopy));
        row.set("javaWrittenReadByJava", ownRead);
        for (var d : Snapshots.differences(javaAfter, ownRead))
          issues.add(operation + " Java write / Java read: " + d);
        verifyPayload(source, javaCopy, nativeCopy, scratch, row, issues);
      } catch (Exception e) {
        issues.add(
          "Java artwork "
            + operation
            + " failed: "
            + e.getClass().getSimpleName()
            + ": "
            + e.getMessage());
      }
    }
    result.set("differences", Snapshots.JSON.valueToTree(issues));
    result.put("passed", issues.isEmpty());
    return result;
  }

  private static PayloadHash.NativeBytes verifyCompleteBytes(
    Path source,
    Path javaCopy,
    Path nativeCopy,
    String operation,
    ObjectNode result,
    List<String> issues)
    throws Exception {
    var proof = PayloadHash.nativeBytesEquivalent(source, javaCopy, nativeCopy, operation);
    result.set("completeNativeBytes", Snapshots.JSON.valueToTree(proof));
    if (!proof.verified()) issues.add("Complete native save bytes differ: " + proof.details());
    return proof;
  }

  /**
   * Shares the matrix's source formula only after its independent complete-file UID proof.
   */
  public static JsonNode expectedJavaOutputRead(
    JsonNode nativeAfter,
    JsonNode nativeReadOfJava,
    PayloadHash.NativeBytes proof,
    ObjectNode report) {
    var admission =
      Snapshots.JSON
        .createObjectNode()
        .put("completeOutputVerified", proof.verified())
        .put("completeOutputBytesEqual", proof.rawEqual())
        .put("nativeOutputLengthForAudioDerivation", proof.nativeLength());
    admission.set("completeOutputComparison", Snapshots.JSON.valueToTree(proof));
    admission.putObject("completeOutput").put("length", proof.javaLength());
    var expected =
      FileReferenceReadAndMutationMatrix.expectedJavaOutputPost(
        nativeAfter, nativeReadOfJava, admission);
    if (admission.has("nativeFileSizeAudioDerivation"))
      report.set("nativeFileSizeAudioDerivation", admission.get("nativeFileSizeAudioDerivation"));
    return expected;
  }

  private static String audioHash(Path path, Path scratch) throws Exception {
    try {
      var p =
        process(
          List.of(
            "ffmpeg",
            "-v",
            "error",
            "-i",
            path.toString(),
            "-map",
            "0:a",
            "-c",
            "copy",
            "-f",
            "streamhash",
            "-hash",
            "sha256",
            "-"),
          scratch,
          30);
      if (p.exitCode != 0 || p.output.isBlank()) return null;
      return p.output.strip();
    } catch (IOException e) {
      return null;
    }
  }
}
