package cz.skyba.taglib.parity;

import cz.skyba.taglib.parity.support.FileRefAdapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cz.skyba.taglib.*;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.Variant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;

/**
 * Required independent common FileRefAdapter differential matrix; no fixture exclusion list.
 */
public final class FileReferenceReadAndMutationMatrix {
  private static final List<String> MODES = List.of("FAST", "AVERAGE", "ACCURATE", "TAGS_ONLY");
  private static final List<String> SCENARIOS =
    List.of(
      "unchanged",
      "empty",
      "empty-values",
      "multivalue",
      "aliases",
      "unicode",
      "rejected-keys",
      "numeric-0",
      "numeric--1",
      "numeric-2147483648",
      "numeric-4294967295",
      "numeric-4294967296",
      "numeric-+7",
      "numeric-7/11",
      "pictures-replace",
      "pictures-remove");
  private static final List<String> SESSION_SCENARIOS;

  static {
    var scenarios = new ArrayList<>(SCENARIOS);
    scenarios.addAll(
      List.of(
        "save-only",
        "remove-unsupported",
        "common-0",
        "common-2147483648",
        "common-4294967295"));
    SESSION_SCENARIOS = List.copyOf(scenarios);
  }

  private static final byte[] PNG =
    HexFormat.of()
      .parseHex(
        "89504e470d0a1a0a0000000d4948445200000001000000010804000000b51c0c020000000b4944415478da6364f80f00010501012718e3660000000049454e44ae426082");

  private FileReferenceReadAndMutationMatrix() {
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 3 || args.length > 4) {
      throw new IllegalArgumentException(
        "FileReferenceReadAndMutationMatrix ORACLE CORPUS REPORT_DIRECTORY [reads-only]");
    }
    var report = run(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), args.length == 4);
    System.out.println(Snapshots.JSON.writeValueAsString(report.get("summary")));
    if (report.path("summary").path("differences").asInt() != 0) System.exit(1);
  }

  public static ObjectNode run(Path oracle, Path corpus, Path output, boolean readsOnly)
    throws Exception {
    if (!Files.isExecutable(oracle))
      throw new IOException("Required native core oracle missing: " + oracle);
    if (!Files.isDirectory(corpus))
      throw new IOException("Required pinned fixture corpus missing: " + corpus);
    if (!command(oracle, "version").path("version").asText().equals(Snapshots.upstreamVersion())) {
      throw new IOException(
        "Core native oracle version does not match " + Snapshots.upstreamVersion());
    }
    Files.createDirectories(output);
    List<Path> files;
    try (var walk = Files.walk(corpus)) {
      files = walk.filter(Files::isRegularFile).sorted().toList();
    }
    if (cz.skyba.taglib.parity.support.Parity.defaultUpstream() && files.size() != 123)
      throw new IOException("Pinned complete corpus changed: " + files.size() + " files");
    if (files.isEmpty()) throw new IOException("Empty upstream corpus: " + corpus);
    var report = Snapshots.JSON.createObjectNode();
    report.put("upstreamVersion", Snapshots.upstreamVersion());
    report.put(
      "contract",
      "native FileRef open/read styles/properties/common tag/audio/pictures; isolated"
        + " native setProperties / Java replaceProperties and default save; combined text/picture replacement");
    report.put(
      "scope",
      "Complete "
        + files.size()
        + "-file source corpus for reads; every native-valid corpus file for each"
        + " mutation; no fixture exclusions. Encoded media preservation is a separate required"
        + " gate.");
    report.put(
      "observableMethods",
      "FileRef constructors(Path,IOStream), isNull, properties, tag/common getters and setters,"
        + " audioProperties/common getters, complexProperties(PICTURE), complexPropertyKeys,"
        + " native setProperties / Java replaceProperties.rejected()/exact rejected map, setComplexProperties/boolean acceptance,"
        + " removeUnsupportedProperties, save/bool, Tag::isEmpty, IOStream::tell and borrowed"
        + " ownership. Direct sessions cover rejection maps even when save fails; the combined"
        + " facade cannot return that map on an exception.");
    report.set("borrowedStreamOrigins", Snapshots.JSON.valueToTree(List.of(0, 7)));
    report.set("readModes", Snapshots.JSON.valueToTree(MODES));
    report.set("mutationScenarios", Snapshots.JSON.valueToTree(readsOnly ? List.of() : SCENARIOS));
    report.set(
      "sessionMutationScenarios",
      Snapshots.JSON.valueToTree(readsOnly ? List.of() : SESSION_SCENARIOS));
    var records = report.putArray("records");
    var formats = EnumSet.noneOf(Format.class);
    int valid = 0;
    for (Path source : files) {
      boolean nativeValid = false;
      for (String mode : MODES) {
        var expected = command(oracle, "snapshot", source.toString(), mode);
        var actual = javaSnapshot(source, mode);
        var row =
          records
            .addObject()
            .put("file", source.getFileName().toString())
            .put("operation", "read")
            .put("mode", mode);
        row.set("differences", Snapshots.JSON.valueToTree(Snapshots.differences(expected, actual)));
        row.set("native", expected);
        row.set("java", actual);
        if (mode.equals("AVERAGE")) nativeValid = expected.path("valid").asBoolean();
        if (actual.path("valid").asBoolean())
          formats.add(TagLib.read(source, options(mode)).format());
        for (long origin : List.of(-1L, 0L, 7L)) {
          boolean borrowed = origin >= 0;
          var nativeStreamResult =
            borrowed
              ? command(
              oracle, "snapshot-stream", source.toString(), mode, Long.toString(origin))
              : null;
          var nativeSession = borrowed ? nativeStreamResult.get("snapshot") : expected;
          ObjectNode javaSession;
          if (borrowed) {
            var memory =
              new NamedMemory(Files.readAllBytes(source), source.getFileName().toString());
            memory.seek(origin);
            try (var ref = new FileRefAdapter(memory, options(mode))) {
              javaSession = sessionSnapshot(ref);
            }
            if (memory.closed)
              throw new IOException("FileRef closed borrowed read stream: " + source);
          } else
            try (var ref = new FileRefAdapter(source, options(mode))) {
              javaSession = sessionSnapshot(ref);
            }
          var sessionRow =
            records
              .addObject()
              .put("file", source.getFileName().toString())
              .put("operation", "session-read")
              .put("mode", mode)
              .put("transport", transport(origin));
          var sessionDifferences =
            new ArrayList<>(Snapshots.differences(nativeSession, javaSession));
          sessionRow.set("differences", Snapshots.JSON.valueToTree(sessionDifferences));
          sessionRow.set("native", nativeSession);
          sessionRow.set("java", javaSession);
        }
      }
      if (!nativeValid) continue;
      valid++;
      if (!readsOnly) {
        for (String scenario : SCENARIOS)
          mutate(oracle, source, scenario, output.resolve("copies/facade"), records);
        for (long origin : List.of(-1L, 0L, 7L))
          for (String scenario : SESSION_SCENARIOS) {
            sessionMutation(oracle, source, scenario, output.resolve("copies"), records, origin);
          }
      }
    }
    if (!formats.equals(EnumSet.allOf(Format.class))) {
      var missing = EnumSet.allOf(Format.class);
      missing.removeAll(formats);
      records
        .addObject()
        .put("operation", "required-format-coverage")
        .putArray("differences")
        .add("Unverified formats: " + missing);
    }
    var summary = report.putObject("summary");
    summary
      .put("corpusFiles", files.size())
      .put("nativeValidFiles", valid)
      .put("readOperations", files.size() * MODES.size());
    summary.put("sessionReadOperations", files.size() * MODES.size() * 3);
    summary.put("mutationOperations", readsOnly ? 0 : valid * SCENARIOS.size());
    summary.put("sessionMutationOperations", readsOnly ? 0 : valid * SESSION_SCENARIOS.size() * 3);
    summary.put("javaSafetyOperations", records.findValues("javaSafetyBoundary").size());
    summary.put("nativeFaultsExecuted", 0);
    summary.put("qualificationPolicy", "ordinary-port-v1");
    summary.put("requiredFormats", Format.values().length).put("observedFormats", formats.size());
    int differences = 0, rejectedSaves = 0, picturesUnsupported = 0;
    for (var row : records) {
      if (!row.path("differences").isEmpty()) differences++;
      if (row.path("operation").asText().endsWith("mutation")
        && !row.has("javaSafetyBoundary")
        && !row.path("native").path("saved").asBoolean()) rejectedSaves++;
      if (row.path("operation").asText().endsWith("mutation")
        && !row.path("native").path("pictureSupported").asBoolean()) picturesUnsupported++;
    }
    summary
      .put("differences", differences)
      .put("nativeRejectedSaves", rejectedSaves)
      .put("nativeUnsupportedPictureOperations", picturesUnsupported);
    int outputComparisons = 0, outputDifferences = 0;
    for (var row : records)
      if (row.path("operation").asText().endsWith("mutation") && !row.has("javaSafetyBoundary")) {
        outputComparisons++;
        if (!row.path("java").path("completeOutputVerified").asBoolean()) outputDifferences++;
      }
    summary.put("completeOutputComparisons", outputComparisons);
    summary.put("completeOutputByteDifferences", outputDifferences);
    int rawOutputDifferences = 0, generatedUidNormalizations = 0;
    for (var row : records)
      if (row.path("operation").asText().endsWith("mutation") && !row.has("javaSafetyBoundary")) {
        if (!row.path("java").path("completeOutputBytesEqual").asBoolean()) rawOutputDifferences++;
        if (row.path("java").path("completeOutputVerified").asBoolean()
          && !row.path("java").path("completeOutputBytesEqual").asBoolean())
          generatedUidNormalizations++;
      }
    summary.put("completeOutputRawByteDifferences", rawOutputDifferences);
    summary.put("generatedMatroskaUidNormalizations", generatedUidNormalizations);
    summary.put("definedComparisonsVerified", differences == 0 && outputDifferences == 0);
    summary.put("verified", differences == 0 && outputDifferences == 0);
    var methodCalls = summary.putObject("nativeMutationMethodCalls");
    for (String method :
      List.of(
        "setProperties",
        "setComplexProperties",
        "save",
        "removeUnsupportedProperties",
        "commonSetters")) {
      int calls = 0;
      for (var row : records)
        if (row.path("operation").asText().endsWith("mutation"))
          calls += row.path("native").path("methodCalls").path(method).asInt();
      methodCalls.put(method, calls);
    }
    Files.writeString(
      output.resolve("matrix.json"),
      Snapshots.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    var failures = Snapshots.JSON.createArrayNode();
    for (var row : records) if (!row.path("differences").isEmpty()) failures.add(row);
    Files.writeString(
      output.resolve("differences.json"),
      Snapshots.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(failures));
    return report;
  }

  private static String transport(long origin) {
    return origin < 0 ? "path" : origin == 0 ? "borrowed-stream-zero" : "borrowed-stream";
  }

  private static void sessionMutation(
    Path oracle, Path source, String scenario, Path copies, ArrayNode records, long origin)
    throws Exception {
    boolean borrowed = origin >= 0;
    String transport = transport(origin);
    Path directory =
      copies
        .resolve("fileref-" + transport)
        .resolve(source.getFileName().toString())
        .resolve(scenario);
    Files.createDirectories(directory);
    Path nativeCopy = directory.resolve("native-" + source.getFileName()),
      javaCopy = directory.resolve("java-" + source.getFileName());
    Files.copy(source, nativeCopy, StandardCopyOption.REPLACE_EXISTING);
    Files.copy(source, javaCopy, StandardCopyOption.REPLACE_EXISTING);
    Object nativeIdentity = identity(nativeCopy), javaIdentity = identity(javaCopy);
    ObjectNode boundary =
      qualifyCompressedMemoryBoundary(oracle, source, nativeCopy, scenario, origin, directory);
    var expected =
      borrowed
        ? command(
        oracle,
        boundary == null ? "mutate-stream" : "stage-stream",
        nativeCopy.toString(),
        scenario,
        Long.toString(origin))
        : command(oracle, "mutate", nativeCopy.toString(), scenario);
    var actual = Snapshots.JSON.createObjectNode();
    var differences = new ArrayList<String>();
    var memory =
      borrowed
        ? new NamedMemory(Files.readAllBytes(javaCopy), javaCopy.getFileName().toString())
        : null;
    if (memory != null) memory.seek(origin);
    try (var ref = borrowed ? new FileRefAdapter(memory, false) : new FileRefAdapter(javaCopy, false)) {
      actual.put("opened", !ref.isNull());
      PropertyMap rejected = PropertyMap.EMPTY;
      if (ref.isNull()) {
        actual.set("rejected", propertyResult(rejected));
        actual.put("pictureSupported", true);
        actual.set("beforeSave", Snapshots.invalid());
        actual.putArray("beforeSaveComplexPropertyKeys");
        actual.put("beforeSaveIsEmpty", true).put("saved", false);
        if (memory != null) actual.put("borrowedStreamCursorAfterSave", memory.tell());
      } else {
        if (scenario.equals("remove-unsupported"))
          ref.removeUnsupportedProperties(ref.properties().unsupportedData());
        else if (scenario.startsWith("common-")) {
          var tag = ref.tag();
          tag.setTitle("Common Žluť");
          tag.setArtist("Common artist");
          tag.setAlbum("Common album");
          tag.setComment("Common comment");
          tag.setGenre("Jazz");
          long value = Long.parseLong(scenario.substring(7));
          tag.setYear(value);
          tag.setTrack(value);
        } else if (!scenario.equals("save-only"))
          rejected = ref.replaceProperties(plan(ref.properties(), scenario)).rejected();
        actual.set("rejected", propertyResult(rejected));
        boolean supported =
          !scenario.startsWith("pictures-")
            || ref.setComplexProperties(
            "PICTURE", pictureVariants(scenario.equals("pictures-remove")));
        actual.put("pictureSupported", supported);
        actual.set("beforeSave", sessionSnapshot(ref));
        actual.set(
          "beforeSaveComplexPropertyKeys", Snapshots.JSON.valueToTree(ref.complexPropertyKeys()));
        actual.put("beforeSaveIsEmpty", ref.tag().isEmpty());
        actual.put("saved", supported && ref.save());
        if (memory != null) actual.put("borrowedStreamCursorAfterSave", memory.tell());
      }
    } catch (RuntimeException failure) {
      actual.put("exception", failure.toString());
      actual.put("saved", false);
      differences.add("session threw instead of returning native operation result: " + failure);
    }
    if (memory != null) {
      actual.put("borrowedStreamOpenAfterClose", !memory.closed);
      Files.write(javaCopy, memory.data().toByteArray());
    }
    for (String field :
      List.of(
        "opened",
        "pictureSupported",
        "saved",
        "rejected",
        "beforeSave",
        "beforeSaveComplexPropertyKeys",
        "beforeSaveIsEmpty")) {
      differences.addAll(
        prefix(field, Snapshots.differences(expected.get(field), actual.get(field))));
    }
    if (memory != null) {
      differences.addAll(prefix("borrowedStreamOpenAfterClose",
        Snapshots.differences(expected.get("borrowedStreamOpenAfterClose"),
          actual.get("borrowedStreamOpenAfterClose"))));
    }
    var nativePost = expected.get("post");
    actual.set("post", javaSnapshot(javaCopy, "AVERAGE"));
    if (boundary == null) {
      compareCompleteOutput(
        source, javaCopy, nativeCopy, scenario, (ObjectNode) expected, actual, differences);
    } else {
      boolean unchanged = Files.mismatch(source, javaCopy) == -1;
      actual.put(
        "undefinedSaveRejectedWithoutByteChanges",
        !actual.path("saved").asBoolean() && unchanged);
      if (!actual.path("undefinedSaveRejectedWithoutByteChanges").asBoolean())
        differences.add(
          "Undefined native memory save must be rejected with every original byte unchanged");
    }
    // Cursor parity covers actual byte changes; no-op saves retain differing construction positions.
    if (memory != null && Files.mismatch(source, javaCopy) != -1)
      differences.addAll(
        prefix(
          "borrowedStreamCursorAfterSave",
          Snapshots.differences(
            expectedJavaOutputCursor(expected, actual, scenario),
            actual.get("borrowedStreamCursorAfterSave"))));
    compareOutputSnapshots(oracle, nativeCopy, javaCopy, nativePost, actual, differences);
    boolean ni = nativeIdentity.equals(identity(nativeCopy)),
      ji = javaIdentity.equals(identity(javaCopy));
    ((ObjectNode) expected).put("identityPreserved", ni);
    actual.put("identityPreserved", ji);
    if (ni != ji) differences.add("default-save identity: native=" + ni + ", java=" + ji);
    var row =
      records
        .addObject()
        .put("file", source.getFileName().toString())
        .put("operation", "session-mutation")
        .put("transport", transport)
        .put("scenario", scenario);
    row.set("differences", Snapshots.JSON.valueToTree(differences));
    row.set("native", expected);
    row.set("java", actual);
    if (boundary != null) row.set("javaSafetyBoundary", boundary);
  }

  /**
   * Known unsafe save: compare staged native values and test Java safety only.
   */
  private static ObjectNode qualifyCompressedMemoryBoundary(
    Path oracle, Path source, Path copy, String scenario, long origin, Path directory)
    throws Exception {
    if (origin < 0
      || !source.getFileName().toString().equals("compressed_id3_frame.mp3")
      || Set.of("pictures-replace", "pictures-remove", "remove-unsupported").contains(scenario))
      return null;
    String inputHash =
      HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
    if (!inputHash.equals("6e6699216a0494900002ac3f2285328902189aea5ff2f5e7d1f8642c4d0ae834"))
      throw new IOException("Reviewed compressed-ID3 fixture changed");
    var result =
      Snapshots.JSON
        .createObjectNode()
        .put("inputSha256", inputHash)
        .put("operation", scenario)
        .put("origin", origin)
        .put("nativeSaveExecuted", false)
        .put("policy", "ordinary-port-v1")
        .put(
          "javaOutput",
          directory.resolve("java-" + source.getFileName()).toAbsolutePath().toString())
        .put(
          "scope",
          "native staged values; Java rejection and unchanged bytes; unsafe native save"
            + " excluded");
    Files.writeString(
      directory.resolve("java-safety.json"),
      Snapshots.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    return result;
  }

  private static ObjectNode sessionSnapshot(FileRefAdapter ref) {
    if (ref.isNull()) return Snapshots.invalid();
    var root = Snapshots.JSON.createObjectNode().put("valid", true);
    var properties = ref.properties();
    root.set("properties", Snapshots.JSON.valueToTree(properties.asMap()));
    root.set("unsupportedData", Snapshots.JSON.valueToTree(properties.unsupportedData()));
    var tag = ref.tag();
    root.putObject("common")
      .put("title", tag.title())
      .put("artist", tag.artist())
      .put("album", tag.album())
      .put("comment", tag.comment())
      .put("genre", tag.genre())
      .put("year", tag.year())
      .put("track", tag.track());
    var audio = ref.audioProperties();
    if (audio == null) audio = AudioProperties.EMPTY;
    root.putObject("audio")
      .put("lengthInMilliseconds", audio.lengthInMilliseconds())
      .put("bitrate", audio.bitrate())
      .put("sampleRate", audio.sampleRate())
      .put("channels", audio.channels());
    var pictures = root.putArray("pictures");
    for (var p : ref.complexProperties("PICTURE")) {
      byte[] data = p.getOrDefault("data", new Variant()).toByteVector().toByteArray();
      pictures
        .addObject()
        .put(
          "type",
          cz.skyba.taglib.flac.Picture.typeFromString(
              p.getOrDefault("pictureType", new Variant()).toText())
            .code())
        .put("mimeType", p.getOrDefault("mimeType", new Variant()).toText())
        .put("description", p.getOrDefault("description", new Variant()).toText())
        .put("size", data.length)
        .put("fnv1a64", Snapshots.fnv(data))
        .put("width", p.getOrDefault("width", new Variant()).toInt())
        .put("height", p.getOrDefault("height", new Variant()).toInt())
        .put("colorDepth", p.getOrDefault("colorDepth", new Variant()).toInt())
        .put("indexedColors", p.getOrDefault("numColors", new Variant()).toInt());
    }
    return root;
  }

  private static List<Map<String, Variant>> pictureVariants(boolean remove) {
    if (remove) return List.of();
    var result = new ArrayList<Map<String, Variant>>();
    for (int type : List.of(3, 4))
      result.add(
        Map.of(
          "data",
          new Variant(new ByteVector(PNG)),
          "mimeType",
          new Variant("image/png"),
          "description",
          new Variant(type == 3 ? "Front Žluť" : "Back 東京"),
          "pictureType",
          new Variant(
            cz.skyba.taglib.flac.Picture.typeToString(cz.skyba.taglib.flac.Picture.typeFromUInt(type))),
          "width",
          new Variant(1),
          "height",
          new Variant(1),
          "colorDepth",
          new Variant(8),
          "numColors",
          new Variant(0)));
    return result;
  }

  private static final class NamedMemory extends ByteVectorStream {
    private final String name;
    boolean closed;

    NamedMemory(byte[] bytes, String name) {
      super(bytes);
      this.name = name;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  private static void mutate(
    Path oracle, Path source, String scenario, Path copies, ArrayNode records) throws Exception {
    Path directory = copies.resolve(source.getFileName().toString()).resolve(scenario);
    Files.createDirectories(directory);
    Path nativeCopy = directory.resolve("native-" + source.getFileName());
    Path javaCopy = directory.resolve("java-" + source.getFileName());
    Files.copy(source, nativeCopy, StandardCopyOption.REPLACE_EXISTING);
    Files.copy(source, javaCopy, StandardCopyOption.REPLACE_EXISTING);
    Object nativeIdentity = identity(nativeCopy), javaIdentity = identity(javaCopy);
    var expected = command(oracle, "mutate", nativeCopy.toString(), scenario);
    var differences = new ArrayList<String>();
    boolean saved = false;
    PropertyMap rejected = null;
    String error = "";
    try {
      PropertyMap requested = plan(javaCopy, scenario);
      if (scenario.startsWith("pictures-")) {
        TagLib.write(
          javaCopy,
          requested,
          scenario.equals("pictures-remove")
            ? List.of()
            : List.of(
            new Picture(3, "image/png", "Front Žluť", PNG, 1, 1, 8, 0),
            new Picture(4, "image/png", "Back 東京", PNG, 1, 1, 8, 0)));
      } else {
        rejected = TagLib.write(javaCopy, requested).rejected();
      }
      saved = true;
    } catch (IOException | RuntimeException failure) {
      error = failure.toString();
    }
    if (expected.path("saved").asBoolean() != saved)
      differences.add("save: native=" + expected.path("saved") + ", java=" + saved + "; " + error);
    var actual = Snapshots.JSON.createObjectNode().put("saved", saved).put("exception", error);
    // The combined facade throws on a failed save, so there is no returned rejection map to
    // compare.
    // Do not claim a rejection-map match for that outcome. FileRefAdapter session probes cover that method
    // separately.
    if (rejected != null) {
      var rejection = propertyResult(rejected);
      actual.set("rejected", rejection);
      differences.addAll(
        prefix("rejected", Snapshots.differences(expected.get("rejected"), rejection)));
    } else actual.put("rejectionMapObservable", false);
    var nativePost = expected.get("post");
    var javaPost = javaSnapshot(javaCopy, "AVERAGE");
    actual.set("post", javaPost);
    compareCompleteOutput(
      source, javaCopy, nativeCopy, scenario, (ObjectNode) expected, actual, differences);
    compareOutputSnapshots(oracle, nativeCopy, javaCopy, nativePost, actual, differences);
    boolean nativeIdentityPreserved = nativeIdentity.equals(identity(nativeCopy));
    boolean javaIdentityPreserved = javaIdentity.equals(identity(javaCopy));
    actual.put("identityPreserved", javaIdentityPreserved);
    ((ObjectNode) expected).put("identityPreserved", nativeIdentityPreserved);
    if (nativeIdentityPreserved != javaIdentityPreserved)
      differences.add(
        "default-save identity: native="
          + nativeIdentityPreserved
          + ", java="
          + javaIdentityPreserved);
    var row =
      records
        .addObject()
        .put("file", source.getFileName().toString())
        .put("operation", "mutation")
        .put("scenario", scenario);
    row.set("differences", Snapshots.JSON.valueToTree(differences));
    row.set("native", expected);
    row.set("java", actual);
  }

  private static void compareOutputSnapshots(
    Path oracle,
    Path nativeCopy,
    Path javaCopy,
    JsonNode nativePost,
    ObjectNode actual,
    List<String> differences)
    throws Exception {
    JsonNode nativeJavaPost = command(oracle, "snapshot", javaCopy.toString(), "AVERAGE");
    actual.set("nativeReadOfJavaOutput", nativeJavaPost);
    JsonNode expectedJavaPost = expectedJavaOutputPost(nativePost, nativeJavaPost, actual);
    differences.addAll(
      prefix(
        "native-written/Java-read",
        Snapshots.differences(nativePost, javaSnapshot(nativeCopy, "AVERAGE"))));
    differences.addAll(
      prefix(
        "Java-written/native-read", Snapshots.differences(expectedJavaPost, nativeJavaPost)));
    differences.addAll(
      prefix(
        "Java-written/Java-read", Snapshots.differences(nativeJavaPost, actual.get("post"))));
  }

  /**
   * Admits only equivalent EOF positions after a complete, independently bounded UID byte proof.
   */
  public static JsonNode expectedJavaOutputCursor(
    JsonNode expected, ObjectNode actual, String scenario) {
    JsonNode nativeCursor = expected.get("borrowedStreamCursorAfterSave");
    if (!scenario.equals("pictures-replace")
      || !expected.path("saved").asBoolean()
      || !actual.path("saved").asBoolean()
      || !actual.path("completeOutputVerified").asBoolean()
      || actual.path("completeOutputBytesEqual").asBoolean()
      || actual.path("completeOutputComparison").path("generatedUidFields").isEmpty())
      return nativeCursor;
    long nativeLength = expected.path("completeOutput").path("length").asLong(-1);
    long javaLength = actual.path("completeOutput").path("length").asLong(-1);
    JsonNode javaCursor = actual.get("borrowedStreamCursorAfterSave");
    if (nativeLength < 0
      || javaLength < 0
      || nativeCursor == null
      || javaCursor == null
      || !nativeCursor.isIntegralNumber()
      || !javaCursor.isIntegralNumber()
      || nativeCursor.asLong() != nativeLength
      || javaCursor.asLong() != javaLength) return nativeCursor;
    actual
      .putObject("nativeFileSizeCursorDerivation")
      .put(
        "source",
        "TagLib Matroska::File::save writes offset-sorted elements; Element::write"
          + " inserts rendered data; ByteVectorStream::insert seeks to the insertion and"
          + " writeBlock ends at start+data.size. Both observed final cursors equal their own"
          + " completely proved output EOF.")
      .put("nativeOutputLength", nativeLength)
      .put("javaOutputLength", javaLength)
      .put("nativeCursor", nativeCursor.asLong())
      .put("javaCursor", javaCursor.asLong())
      .put("bothAtOwnEof", true)
      .put("completeUidByteProofRequired", true);
    return javaCursor;
  }

  /**
   * Derives just Matroska's native filesize bitrate after the independent full-byte UID proof.
   */
  public static JsonNode expectedJavaOutputPost(
    JsonNode nativePost, JsonNode nativeJavaPost, ObjectNode actual) {
    JsonNode byteProof = actual.path("completeOutputComparison");
    if (!actual.path("completeOutputVerified").asBoolean()
      || actual.path("completeOutputBytesEqual").asBoolean()
      || byteProof.path("generatedUidFields").isEmpty()) return nativePost;
    long nativeLength = actual.path("nativeOutputLengthForAudioDerivation").asLong(-1);
    long javaLength = actual.path("completeOutput").path("length").asLong(-1);
    long duration = nativePost.path("audio").path("lengthInMilliseconds").asLong(-1);
    if (nativeLength < 0
      || javaLength < 0
      || duration < 0
      || nativeJavaPost.path("audio").path("lengthInMilliseconds").asLong(-1) != duration)
      return nativePost;
    int nativeBitrate = duration == 0 ? 0 : (int) (nativeLength * 8 / duration);
    int javaBitrate = duration == 0 ? 0 : (int) (javaLength * 8 / duration);
    boolean formulaVerified =
      nativePost.path("audio").path("bitrate").asInt() == nativeBitrate
        && nativeJavaPost.path("audio").path("bitrate").asInt() == javaBitrate;
    actual
      .putObject("nativeFileSizeAudioDerivation")
      .put(
        "source",
        "TagLib matroskaproperties.cpp Properties::bitrate:"
          + " int(file.length()*8/lengthInMilliseconds), zero when duration is zero")
      .put("nativeOutputLength", nativeLength)
      .put("javaOutputLength", javaLength)
      .put("durationInMilliseconds", duration)
      .put("nativeOutputBitrate", nativeBitrate)
      .put("javaOutputBitrate", javaBitrate)
      .put("bothNativeReadsVerifyFormula", formulaVerified)
      .put("completeUidByteProofRequired", true);
    if (!formulaVerified) return nativePost;
    ObjectNode derived = nativePost.deepCopy();
    ((ObjectNode) derived.path("audio")).put("bitrate", javaBitrate);
    return derived;
  }

  private static void compareCompleteOutput(
    Path source,
    Path javaCopy,
    Path nativeCopy,
    String scenario,
    ObjectNode expected,
    ObjectNode actual,
    List<String> differences)
    throws Exception {
    expected.set("completeOutput", outputIdentity(nativeCopy));
    actual.set("completeOutput", outputIdentity(javaCopy));
    actual.put("nativeOutputLengthForAudioDerivation", Files.size(nativeCopy));
    long mismatch = Files.mismatch(nativeCopy, javaCopy);
    actual.put("completeOutputBytesEqual", mismatch < 0);
    actual.put("completeOutputFirstMismatch", mismatch);
    boolean verified = mismatch < 0;
    if (mismatch >= 0) {
      var bounded = PayloadHash.nativeBytesEquivalent(source, javaCopy, nativeCopy, scenario);
      verified = bounded.verified();
      var proof = actual.putObject("completeOutputComparison");
      proof.put("policy", bounded.policy()).put("details", bounded.details());
      proof.put("normalizedJavaSha256", bounded.normalizedJavaSha256());
      proof.put("normalizedNativeSha256", bounded.normalizedNativeSha256());
      proof.set("generatedUidFields", Snapshots.JSON.valueToTree(bounded.generatedUidFields()));
      proof.set("derivedUidFields", Snapshots.JSON.valueToTree(bounded.derivedUidFields()));
      if (!verified)
        differences.add(
          "complete output bytes differ at offset "
            + mismatch
            + ": native length="
            + Files.size(nativeCopy)
            + ", Java length="
            + Files.size(javaCopy)
            + "; "
            + bounded.details());
    }
    actual.put("completeOutputVerified", verified);
  }

  private static ObjectNode outputIdentity(Path path) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    try (var input = Files.newInputStream(path)) {
      byte[] buffer = new byte[64 * 1024];
      for (int count; (count = input.read(buffer)) != -1; ) digest.update(buffer, 0, count);
    }
    return Snapshots.JSON
      .createObjectNode()
      .put("length", Files.size(path))
      .put("sha256", HexFormat.of().formatHex(digest.digest()));
  }

  private static Object identity(Path file) throws IOException {
    var key =
      Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
    if (key == null)
      throw new IOException("Required default-save identity check unsupported: " + file);
    return key;
  }

  private static List<String> prefix(String prefix, List<String> differences) {
    return differences.stream().map(value -> prefix + ": " + value).toList();
  }

  private static ObjectNode propertyResult(PropertyMap map) {
    var result = Snapshots.JSON.createObjectNode();
    result.set("properties", Snapshots.JSON.valueToTree(map.asMap()));
    result.set("unsupportedData", Snapshots.JSON.valueToTree(map.unsupportedData()));
    return result;
  }

  private static ObjectNode javaSnapshot(Path path, String mode) throws Exception {
    try {
      var file = TagLib.read(path, options(mode));
      var result = Snapshots.of(file);
      result.set(
        "unsupportedData", Snapshots.JSON.valueToTree(file.properties().unsupportedData()));
      return result;
    } catch (cz.skyba.taglib.TagLibException | java.io.UncheckedIOException | IllegalArgumentException failure) {
      return Snapshots.invalid();
    }
  }

  private static ReadOptions options(String mode) {
    return mode.equals("TAGS_ONLY")
      ? ReadOptions.TAGS_ONLY
      : new ReadOptions(true, ReadStyle.valueOf(mode), ReadOptions.DEFAULT.maxMetadataBytes());
  }

  private static PropertyMap plan(Path file, String scenario) throws IOException {
    return plan(
      scenario.equals("unchanged") || scenario.startsWith("pictures-")
        ? TagLib.read(file, ReadOptions.TAGS_ONLY).properties()
        : PropertyMap.EMPTY,
      scenario);
  }

  private static PropertyMap plan(PropertyMap existing, String scenario) {
    if (scenario.equals("unchanged") || scenario.startsWith("pictures-")) return existing;
    var p = PropertyMap.builder();
    switch (scenario) {
      case "empty" -> {
      }
      case "empty-values" -> p.put("TITLE", "")
        .put("ARTIST", List.of())
        .put("COMMENT", "")
        .put("DATE", List.of())
        .put("TRACKNUMBER", List.of())
        .put("CUSTOM", List.of());
      case "multivalue" -> p.put("TITLE", List.of("First", "Second"))
        .put("ARTIST", List.of("Alpha", "Beta"))
        .put("ALBUM", List.of("One", "Two"))
        .put("COMMENT", List.of("Comment A", "Comment B"))
        .put("GENRE", List.of("Jazz", "Rock"))
        .put("DATE", List.of("2026-10-03", "2027"))
        .put("TRACKNUMBER", List.of("7/11", "8"))
        .put("CUSTOM", List.of("one", "two"));
      case "aliases" -> p.put("title", "Alias title")
        .put("Album Artist", "Spaced")
        .put("albumartist", "Joined")
        .put("year", "1999")
        .put("date", "2026-10-03")
        .put("track", "2")
        .put("tracknumber", "7/11")
        .put("DESCRIPTION", "Description alias")
        .put("COMMENT", "Comment alias");
      case "unicode" -> p.put("TITLE", "Žluťoučký 🐦")
        .put("ARTIST", List.of("東京", "é"))
        .put("COMMENT", "Line one\nLine two")
        .put("CUSTOM", List.of("Ελληνικά", "العربية"));
      case "rejected-keys" -> List.of("", "=", "éx", "~", "}", "TITLE", "EMPTY", "KEY\n", "X=A")
        .forEach(key -> p.put(key, "value"));
      default -> {
        if (!scenario.startsWith("numeric-"))
          throw new IllegalArgumentException("Unknown scenario " + scenario);
        p.put("DATE", scenario.substring(8)).put("TRACKNUMBER", scenario.substring(8));
      }
    }
    return p.build();
  }

  private static JsonNode command(Path oracle, String... args) throws Exception {
    var command = new ArrayList<String>();
    command.add(oracle.toAbsolutePath().toString());
    command.addAll(List.of(args));
    var process =
      new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    byte[] bytes = process.getInputStream().readAllBytes();
    if (process.waitFor() != 0)
      throw new IOException(
        "Native core operation failed: "
          + command
          + ": "
          + new String(bytes, StandardCharsets.UTF_8));
    return Snapshots.JSON.readTree(bytes);
  }
}
