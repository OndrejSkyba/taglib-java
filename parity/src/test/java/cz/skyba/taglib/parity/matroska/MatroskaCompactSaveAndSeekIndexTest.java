package cz.skyba.taglib.parity.matroska;

import cz.skyba.taglib.parity.support.FileRefAdapter;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.Variant;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Synthetic native contracts, with an independent EBML scanner and full-byte save comparison.
 */
@org.junit.jupiter.api.Tag("parity")
@org.junit.jupiter.api.Tag("matroska")
class MatroskaCompactSaveAndSeekIndexTest {
  private static final long SEGMENT = 0x18538067L,
    TAGS = 0x1254c367L,
    SEEK = 0x114d9b74L,
    CLUSTER = 0x1f43b675L,
    CUES = 0x1c53bb6bL;
  @TempDir
  Path directory;

  @TestFactory
  List<DynamicTest> compactSavesAndSeekIndexesMatchCpp() throws Exception {
    var tests = new ArrayList<DynamicTest>();
    for (boolean seek : new boolean[]{false, true})
      for (int rounds : new int[]{1, 2}) {
        String name = "relocate-" + seek + "-" + rounds;
        tests.add(
          DynamicTest.dynamicTest(
            name,
            () ->
              compareSave(
                name, indexed(seek, false), "X".repeat(600), rounds, seek ? 0 : 1)));
      }
    for (String title : new String[]{"s", "<empty>"}) {
      String name = "shrink-" + (title.equals("s") ? "short" : "remove");
      tests.add(
        DynamicTest.dynamicTest(
          name,
          () -> compareSave(name, indexed(true, true), title, 2, title.equals("s") ? 0 : 1)));
    }
    tests.add(
      DynamicTest.dynamicTest(
        "remove-once", () -> compareSave("remove-once", indexed(true, true), "<empty>", 1, 0)));
    tests.add(
      DynamicTest.dynamicTest(
        "remove-three",
        () -> compareSave("remove-three", indexed(true, true), "<empty>", 3, 2)));
    for (int rounds : new int[]{1, 2}) {
      String name = "new-attachments-tags-" + rounds;
      tests.add(
        DynamicTest.dynamicTest(
          name, () -> compareSave(name, noMetadata(), "new", rounds, 1, true)));
    }
    tests.add(
      readCase("duplicate-tags", document(join(tags("first"), tags("last"))), ReadStyle.AVERAGE));
    tests.add(
      readCase(
        "nested-simple",
        document(
          element(
            TAGS, element(0x7373, simple("TITLE", "outer", simple("ARTIST", "nested"))))),
        ReadStyle.AVERAGE));
    tests.add(
      readCase(
        "malformed-nested-master",
        document(
          element(
            TAGS,
            join(
              element(0x7373, new byte[]{0}),
              element(0x7373, simple("TITLE", "kept"))))),
        ReadStyle.AVERAGE));
    byte[] first = tags("indexed"), second = tags("unindexed");
    byte[] head = seekHead(Map.of(TAGS, 0L));
    head = seekHead(Map.of(TAGS, (long) head.length));
    tests.add(readCase("seek-select", document(join(head, first, second)), ReadStyle.AVERAGE));
    tests.add(
      readCase(
        "seek-invalid", document(join(seekHead(Map.of(TAGS, 1L)), first)), ReadStyle.AVERAGE));
    tests.add(
      readCase(
        "seek-invalid-accurate",
        document(join(seekHead(Map.of(TAGS, 1L)), first)),
        ReadStyle.ACCURATE));
    tests.add(
      readCase(
        "seek-missing-position",
        document(join(element(SEEK, element(0x4dbb, element(0x53ab, id(TAGS)))), first)),
        ReadStyle.AVERAGE));
    byte[] invalidIdHead =
      element(
        SEEK, element(0x4dbb, join(element(0x53ab, new byte[]{1}), element(0x53ac, uint(0)))));
    tests.add(
      readCase("seek-invalid-id-width", document(join(invalidIdHead, first)), ReadStyle.AVERAGE));
    byte[] primary = seekHead(Map.of(SEEK, 0L)), secondary = seekHead(Map.of(TAGS, 0L));
    primary = seekHead(Map.of(SEEK, (long) primary.length));
    secondary = seekHead(Map.of(TAGS, (long) primary.length + secondary.length));
    tests.add(
      readCase("seek-chained", document(join(primary, secondary, first)), ReadStyle.AVERAGE));
    byte[] opaque =
      element(CLUSTER, join(element(0xa3, new byte[]{(byte) 0x81, 0, 0, 0, 1}), tags("inside")));
    opaque[4] = (byte) 0xff;
    tests.add(
      readCase(
        "unknown-cluster",
        document(join(tags("before"), opaque, tags("hidden"))),
        ReadStyle.AVERAGE));
    tests.add(
      readCase(
        "malformed-tail", document(join(tags("kept"), new byte[]{0})), ReadStyle.AVERAGE));
    tests.add(
      readCase(
        "multiple-segments",
        join(document(tags("first")), element(SEGMENT, tags("second"))),
        ReadStyle.AVERAGE));
    byte[] unknown = document(tags("unknown"));
    unknown[9] = (byte) 0xff;
    tests.add(
      DynamicTest.dynamicTest(
        "unknown-segment", () -> compareSave("unknown-segment", unknown, "new", 2, 0)));
    tests.add(
      DynamicTest.dynamicTest(
        "borrowed-valid-prefix",
        () -> {
          byte[] source = join(new byte[]{1, 2, 3, 4, 5, 6, 7}, document(tags("prefix")));
          Path path = fixture("borrowed-valid-prefix", source);
          String expected = nativeRun("read-prefix", path.toString());
          var stream =
            new ByteVectorStream(source) {
              @Override
              public String name() {
                return "prefix.mka";
              }
            };
          stream.seek(7);
          try (var ref = new FileRefAdapter(stream, false)) {
            assertEquals(expected, dump(ref));
          }
        }));
    byte[] late = document(join(element(0xec, new byte[600_000]), tags("late")));
    tests.add(readCase("fast-late-tags", late, ReadStyle.FAST));
    for (int rounds : new int[]{1, 2}) {
      String name = "fast-save-late-tags-" + rounds;
      tests.add(
        DynamicTest.dynamicTest(
          name, () -> compareSave(name, late, "new", rounds, 0, false, ReadStyle.FAST)));
    }
    for (byte[] source : List.of(indexed(true, false), indexed(false, false), late))
      for (ReadStyle style : ReadStyle.values())
        for (boolean audio : new boolean[]{false, true}) {
          String name = "stream-read-" + tests.size();
          tests.add(
            DynamicTest.dynamicTest(
              name, () -> compareStream(name, source, style, audio, null, 1)));
        }
    for (boolean seek : new boolean[]{true, false})
      for (String title : List.of("X".repeat(600), "<empty>")) {
        String name = "stream-save-" + tests.size();
        tests.add(
          DynamicTest.dynamicTest(
            name,
            () ->
              compareStream(name, indexed(seek, false), ReadStyle.AVERAGE, false, title, 2)));
      }
    byte[] malformedCues =
      document(
        join(
          element(CUES, new byte[]{0}),
          tags("after-cues"),
          element(0xec, new byte[600_000])));
    byte[] cueHead = seekHead(Map.of(TAGS, 0L, CUES, 1L));
    cueHead = seekHead(Map.of(TAGS, (long) cueHead.length, CUES, 1L));
    byte[] invalidCueReference =
      document(join(cueHead, tags("indexed"), element(0xec, new byte[600_000])));
    for (byte[] source : List.of(malformedCues, invalidCueReference))
      for (ReadStyle style : ReadStyle.values()) {
        String name = "readonly-cues-" + tests.size();
        tests.add(
          DynamicTest.dynamicTest(
            name, () -> compareStream(name, source, style, false, null, 1, true)));
      }
    return tests;
  }

  private void compareStream(
    String name, byte[] source, ReadStyle style, boolean audio, String title, int rounds)
    throws Exception {
    compareStream(name, source, style, audio, title, rounds, false);
  }

  private void compareStream(
    String name,
    byte[] source,
    ReadStyle style,
    boolean audio,
    String title,
    int rounds,
    boolean readOnly)
    throws Exception {
    Path path = fixture(name, source);
    String expected =
      title == null
        ? nativeRun(
        readOnly ? "read-readonly-stream" : "read-stream",
        path.toString(),
        style.name().toLowerCase(),
        Boolean.toString(audio),
        "0")
        : nativeRun(
        "set-stream",
        path.toString(),
        style.name().toLowerCase(),
        Boolean.toString(audio),
        "0",
        title,
        Integer.toString(rounds));
    var stream =
      new ByteVectorStream(source) {
        @Override
        public String name() {
          return "fixture.mka";
        }

        @Override
        public boolean readOnly() {
          return readOnly;
        }
      };
    var actual = new StringBuilder();
    try (var ref = new FileRefAdapter(stream, audio, style)) {
      actual
        .append("cursorAfterConstruction=")
        .append(stream.tell())
        .append('\n')
        .append(dump(ref));
      if (title != null) {
        ref.replaceProperties(
          title.equals("<empty>")
            ? PropertyMap.EMPTY
            : PropertyMap.builder().put("TITLE", title).build());
        for (int n = 0; n < rounds; n++)
          actual
            .append("save=")
            .append(ref.save() ? 1 : 0)
            .append("\ncursorAfterSave=")
            .append(stream.tell())
            .append('\n');
      }
    }
    evidence(name, "native-stream.txt", expected.getBytes(StandardCharsets.UTF_8));
    evidence(name, "java-stream.txt", actual.toString().getBytes(StandardCharsets.UTF_8));
    assertEquals(expected, actual.toString(), name + " native stream state");
    if (title != null)
      assertArrayEquals(
        Files.readAllBytes(path),
        stream.data().toByteArray(),
        name + " native entire stream bytes");
  }

  private DynamicTest readCase(String name, byte[] source, ReadStyle style) {
    return DynamicTest.dynamicTest(name, () -> compareRead(name, source, style));
  }

  private void compareRead(String name, byte[] source, ReadStyle style) throws Exception {
    Path path = fixture(name, source);
    String expected =
      nativeRun(
        "read",
        path.toString(),
        style == ReadStyle.ACCURATE
          ? "accurate"
          : style == ReadStyle.FAST ? "fast" : "average");
    String actual;
    try (var file = new FileRefAdapter(path, false, style)) {
      actual = dump(file);
    }
    evidence(name, "native-read.txt", expected.getBytes(StandardCharsets.UTF_8));
    evidence(name, "java-read.txt", actual.getBytes(StandardCharsets.UTF_8));
    assertEquals(expected, actual, name);
  }

  private void compareSave(
    String name, byte[] source, String title, int rounds, int expectedBadIndices)
    throws Exception {
    compareSave(name, source, title, rounds, expectedBadIndices, false);
  }

  private void compareSave(
    String name, byte[] source, String title, int rounds, int expectedBadIndices, boolean picture)
    throws Exception {
    compareSave(name, source, title, rounds, expectedBadIndices, picture, ReadStyle.AVERAGE);
  }

  private void compareSave(
    String name,
    byte[] source,
    String title,
    int rounds,
    int expectedBadIndices,
    boolean picture,
    ReadStyle style)
    throws Exception {
    Path javaPath = fixture(name, source), nativePath = directory.resolve(name + "-native.mka");
    Files.write(nativePath, source);
    String nativeResult =
      nativeRun(
        picture ? "set-picture" : style == ReadStyle.FAST ? "set-fast" : "set",
        nativePath.toString(),
        title,
        Integer.toString(rounds));
    assertEquals(
      rounds, nativeResult.lines().filter("save=1"::equals).count(), name + " native save");
    try (var ref = new FileRefAdapter(javaPath, false, style)) {
      assertFalse(ref.isNull(), name + " Java valid");
      assertEquals(
        PropertyMap.EMPTY,
        ref.replaceProperties(
          title.equals("<empty>")
            ? PropertyMap.EMPTY
            : PropertyMap.builder().put("TITLE", title).build()).rejected());
      if (picture)
        assertTrue(
          ref.setComplexProperties(
            "PICTURE",
            List.of(
              Map.of(
                "data",
                new Variant(new ByteVector(new byte[]{(byte) 0x89, 'P', 'N', 'G'})),
                "mimeType",
                new Variant("image/png"),
                "description",
                new Variant("Front")))));
      List<Long> firstUids = null;
      for (int n = 0; n < rounds; n++) {
        assertTrue(ref.save(), name + " Java save " + n);
        if (picture) {
          List<Long> uids = attachmentUids(Files.readAllBytes(javaPath));
          if (firstUids == null) firstUids = uids;
          else assertEquals(firstUids, uids, name + " same-instance attachment UID retention");
        }
      }
    }
    byte[] nativeBytes = Files.readAllBytes(nativePath), javaBytes = Files.readAllBytes(javaPath);
    evidence(name, "native.mka", nativeBytes);
    evidence(name, "java.mka", javaBytes);
    evidence(name, "native-save.txt", nativeResult.getBytes(StandardCharsets.UTF_8));
    // Both SimpleBlocks and all unrelated Cluster bytes must survive at their relocated position.
    assertEquals(clusterBytes(source), clusterBytes(javaBytes), name + " retained clusters");
    var nativeProblems = indexProblems(nativeBytes);
    var javaProblems = indexProblems(javaBytes);
    evidence(
      name,
      "indices.txt",
      ("native=" + nativeProblems + "\njava=" + javaProblems + "\n")
        .getBytes(StandardCharsets.UTF_8));
    // The pinned source has a specific Segment-header-growth cue bug. This case explicitly
    // proves the same malformed effect while preserving exact Cluster bytes; it never calls
    // invalid native cue positions valid or exempts arbitrary index corruption.
    assertEquals(expectedBadIndices, nativeProblems.size(), name + " pinned native index outcome");
    assertEquals(nativeProblems, javaProblems, name + " independent index outcome");
    if (picture) {
      assertEquals(
        1,
        nativeResult.lines().filter(line -> line.startsWith("uid=")).distinct().count(),
        "Native retains attachment UID across saves");
      nativeBytes = normalizeNewUid(nativeBytes);
      javaBytes = normalizeNewUid(javaBytes);
    }
    assertArrayEquals(
      nativeBytes,
      javaBytes,
      name
        + " complete native default-save bytes; mismatch="
        + Arrays.mismatch(nativeBytes, javaBytes));
  }

  private static String dump(FileRefAdapter ref) {
    if (ref.isNull()) return "valid=0\n";
    var out = new StringBuilder("valid=1\nTITLE\t").append(hex(ref.tag().title())).append('\n');
    for (var item : new TreeMap<>(ref.properties().asMap()).entrySet()) {
      out.append("PROP\t").append(hex(item.getKey()));
      for (var value : item.getValue()) out.append('\t').append(hex(value));
      out.append('\n');
    }
    for (var item : ref.properties().unsupportedData())
      out.append("UNSUPPORTED\t").append(hex(item)).append('\n');
    return out.toString();
  }

  private String nativeRun(String... args) throws Exception {
    String oracle = System.getProperty("matroska.compact.oracle");
    if (Boolean.getBoolean("taglib.requireNative")) {
      assertNotNull(oracle, "Required pinned Matroska Compact probe");
      assertTrue(
        Files.isExecutable(Path.of(oracle)), "Required pinned Matroska Compact probe executable");
    }
    assumeTrue(oracle != null, "Run ./mvnw -Pparity verify for pinned native contracts");
    var command = new ArrayList<String>();
    command.add(oracle);
    command.addAll(List.of(args));
    var process =
      new ProcessBuilder(command)
        .redirectError(directory.resolve("native-errors.txt").toFile())
        .start();
    assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Bounded native probe");
    assertEquals(0, process.exitValue(), Files.readString(directory.resolve("native-errors.txt")));
    return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
  }

  private Path fixture(String name, byte[] source) throws Exception {
    Path path = directory.resolve(name + ".mka");
    Files.write(path, source);
    evidence(name, "source.mka", source);
    return path;
  }

  private static void evidence(String name, String suffix, byte[] bytes) throws Exception {
    String destination = System.getProperty("matroska.compact.evidence");
    if (destination != null) {
      Path path = Path.of(destination).resolve(name);
      Files.createDirectories(path);
      Files.write(path.resolve(suffix), bytes);
    }
  }

  private static byte[] indexed(boolean includeSeek, boolean bigTag) throws Exception {
    byte[] tag = tags(bigTag ? "L".repeat(600) : "x"), padding = element(0xec, new byte[12]);
    byte[] cluster =
      element(
        CLUSTER,
        join(
          element(0xe7, uint(0)),
          element(0xa3, new byte[]{(byte) 0x81, 0, 0, 0, 1, 2}),
          element(0xa3, new byte[]{(byte) 0x81, 0, 1, 0, 3, 4})));
    byte[] head = new byte[0], cues = new byte[0];
    for (int n = 0; n < 5; n++) {
      long position = head.length + tag.length + padding.length;
      cues =
        element(
          CUES,
          element(
            0xbb,
            join(
              element(0xb3, uint(0)),
              element(
                0xb7,
                join(
                  element(0xf7, uint(1)),
                  element(0xf1, uint(position)),
                  element(0xf0, uint(3)),
                  element(0xb2, uint(10)),
                  element(0x5378, uint(2)),
                  element(0xea, uint(0)),
                  element(
                    0xdb, join(element(0x96, uint(7)), element(0x96, uint(9)))))))));
      if (includeSeek)
        head =
          seekHead(
            Map.of(
              TAGS, (long) head.length, CLUSTER, position, CUES, position + cluster.length));
    }
    return document(join(head, tag, padding, cluster, cues));
  }

  private static byte[] noMetadata() throws Exception {
    byte[] head = seekHead(Map.of());
    byte[] cluster =
      element(
        CLUSTER,
        join(element(0xe7, uint(0)), element(0xa3, new byte[]{(byte) 0x81, 0, 0, 0, 1, 2})));
    byte[] cues =
      element(
        CUES,
        element(
          0xbb,
          join(
            element(0xb3, uint(0)),
            element(
              0xb7, join(element(0xf7, uint(1)), element(0xf1, uint(head.length)))))));
    return document(join(head, cluster, cues));
  }

  private static byte[] tags(String title) throws Exception {
    return element(TAGS, element(0x7373, simple("TITLE", title)));
  }

  private static byte[] simple(String name, String value, byte[]... nested) throws Exception {
    return element(
      0x67c8,
      join(
        element(0x45a3, name.getBytes(StandardCharsets.UTF_8)),
        element(0x4487, value.getBytes(StandardCharsets.UTF_8)),
        join(nested)));
  }

  private static byte[] seekHead(Map<Long, Long> entries) throws Exception {
    var out = new ByteArrayOutputStream();
    for (var entry : new TreeMap<>(entries).entrySet())
      out.write(
        element(
          0x4dbb,
          join(element(0x53ab, id(entry.getKey())), element(0x53ac, uint(entry.getValue())))));
    return element(SEEK, out.toByteArray());
  }

  private static byte[] document(byte[] payload) throws Exception {
    return join(element(0x1a45dfa3L, new byte[0]), element(SEGMENT, payload));
  }

  private static byte[] id(long value) {
    int width = Math.max(1, (64 - Long.numberOfLeadingZeros(value) + 7) / 8);
    byte[] bytes = new byte[width];
    for (int i = 0; i < width; i++) bytes[width - i - 1] = (byte) (value >>> (8 * i));
    return bytes;
  }

  private static byte[] uint(long value) {
    return id(value);
  }

  private static byte[] element(long id, byte[] payload) throws Exception {
    int width = 1;
    while (payload.length >= (1L << (7 * width)) - 1) width++;
    byte[] size = new byte[width];
    long value = payload.length | (1L << (7 * width));
    for (int i = 0; i < width; i++) size[width - i - 1] = (byte) (value >>> (8 * i));
    return join(id(id), size, payload);
  }

  private static byte[] join(byte[]... parts) throws Exception {
    var out = new ByteArrayOutputStream();
    for (var part : parts) out.write(part);
    return out.toByteArray();
  }

  private static String hex(String value) {
    return HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8));
  }

  // The scanner belongs to the test and has no dependency on MatroskaCodec's parser/planner.
  private record E(long id, int offset, int data, int end) {
  }

  private static E parse(byte[] bytes, int offset, int parentEnd) {
    int n = width(bytes[offset]);
    assertTrue(n <= 4);
    long id = 0;
    for (int i = 0; i < n; i++) id = (id << 8) | (bytes[offset + i] & 255);
    int sizeAt = offset + n, m = width(bytes[sizeAt]), data = sizeAt + m;
    long size = bytes[sizeAt] & ((1 << (8 - m)) - 1);
    for (int i = 1; i < m; i++) size = (size << 8) | (bytes[sizeAt + i] & 255);
    if (size == (1L << (7 * m)) - 1) size = parentEnd - data;
    assertTrue(size >= 0 && size <= parentEnd - data, "independent EBML extent");
    return new E(id, offset, data, data + (int) size);
  }

  private static int width(byte first) {
    int n = 1;
    while (n <= 8 && (first & (0x80 >>> (n - 1))) == 0) n++;
    assertTrue(n <= 8);
    return n;
  }

  private static List<E> scan(byte[] bytes, int start, int end) {
    var list = new ArrayList<E>();
    for (int at = start; at < end; ) {
      var item = parse(bytes, at, end);
      list.add(item);
      at = item.end;
    }
    return list;
  }

  private static E segment(byte[] bytes) {
    return scan(bytes, 0, bytes.length).stream()
      .filter(e -> e.id == SEGMENT)
      .findFirst()
      .orElseThrow();
  }

  private static List<String> clusterBytes(byte[] bytes) {
    E segment = segment(bytes);
    return scan(bytes, segment.data, segment.end).stream()
      .filter(e -> e.id == CLUSTER)
      .map(e -> HexFormat.of().formatHex(bytes, e.offset, e.end))
      .toList();
  }

  private static long value(byte[] bytes, E e) {
    long value = 0;
    for (int at = e.data; at < e.end; at++) value = (value << 8) | (bytes[at] & 255);
    return value;
  }

  private static List<E> uidElements(byte[] bytes) {
    var result = new ArrayList<E>();
    E segment = segment(bytes);
    for (E top : scan(bytes, segment.data, segment.end))
      if (top.id == 0x1941a469L)
        for (E attached : scan(bytes, top.data, top.end))
          if (attached.id == 0x61a7)
            for (E field : scan(bytes, attached.data, attached.end))
              if (field.id == 0x46ae) result.add(field);
    return result;
  }

  private static List<Long> attachmentUids(byte[] bytes) {
    return uidElements(bytes).stream().map(e -> value(bytes, e)).toList();
  }

  private static byte[] normalizeNewUid(byte[] bytes) {
    byte[] copy = bytes.clone();
    List<E> uids = uidElements(copy);
    assertEquals(1, uids.size());
    E uid = uids.getFirst();
    assertEquals(8, uid.end - uid.data);
    assertNotEquals(0, value(copy, uid));
    Arrays.fill(copy, uid.data, uid.end, (byte) 0);
    return copy;
  }

  private static List<String> indexProblems(byte[] bytes) {
    var problems = new ArrayList<String>();
    E segment = segment(bytes);
    for (E item : scan(bytes, segment.data, segment.end)) {
      if (item.id == SEEK)
        for (E seek : scan(bytes, item.data, item.end))
          if (seek.id == 0x4dbb) {
            long id = 0, position = -1;
            for (E field : scan(bytes, seek.data, seek.end)) {
              if (field.id == 0x53ab) id = value(bytes, field);
              if (field.id == 0x53ac) position = value(bytes, field);
            }
            if (!pointsAt(bytes, segment, id, position))
              problems.add("seek/" + Long.toHexString(id) + "/" + position);
          }
      if (item.id == CUES)
        for (E point : scan(bytes, item.data, item.end))
          if (point.id == 0xbb)
            for (E track : scan(bytes, point.data, point.end))
              if (track.id == 0xb7)
                for (E field : scan(bytes, track.data, track.end))
                  if (field.id == 0xf1)
                    if (!pointsAt(bytes, segment, CLUSTER, value(bytes, field)))
                      problems.add("cue/" + value(bytes, field));
    }
    return problems;
  }

  private static boolean pointsAt(byte[] bytes, E segment, long id, long position) {
    if (position < 0 || position >= segment.end - segment.data) return false;
    int at = segment.data + (int) position;
    if (bytes[at] == 0) return false;
    int width = 1;
    while (width <= 4 && (bytes[at] & (128 >>> (width - 1))) == 0) width++;
    if (width > 4 || width > segment.end - at) return false;
    long actual = 0;
    for (int i = 0; i < width; i++) actual = (actual << 8) | (bytes[at + i] & 255);
    return actual == id;
  }
}
