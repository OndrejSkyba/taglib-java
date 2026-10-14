package cz.skyba.taglib.internal.editor;

import cz.skyba.taglib.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

import static cz.skyba.taglib.internal.Binary.*;
import static org.junit.jupiter.api.Assertions.*;

class Mp4DocumentTest {
  @TempDir
  Path directory;
  private static final byte[] MEDIA = new byte[4096];

  static {
    new Random(29).nextBytes(MEDIA);
  }

  @Test
  void adjacentPaddingAbsorbsTypedAndConventionalEditsWithoutMovingMedia() throws Exception {
    for (boolean keyed : List.of(false, true)) {
      byte[] keys =
        keyed ? box("keys", new byte[4], number(1, 4), box("mdta", bytes("title"))) : new byte[0];
      byte[] title =
        keyed ? item(1, value(1, 0, bytes("Before"))) : box("©nam", value(1, 0, bytes("Before")));
      byte[] meta =
        box(
          "meta",
          new byte[4],
          keys,
          box("free", new byte[128]),
          box("ilst", title),
          box("free", new byte[1024]));
      Path path = file(meta);
      long size = Files.size(path);
      Object key =
        Files.readAttributes(path, BasicFileAttributes.class).fileKey();
      for (String text : List.of("A longer title — 日本語", "Short")) {
        var doc = MetadataDocument.open(path);
        var entry = doc.entries().getFirst();
        doc.apply(List.of(update(entry, entry.key(), text)));
        doc.save();
        assertEquals(size, Files.size(path));
        assertEquals(
          key,
          Files.readAttributes(path, BasicFileAttributes.class)
            .fileKey());
        assertEquals(List.of(text), MetadataDocument.open(path).entries().getFirst().values());
        assertMedia(path);
      }
    }
  }

  @Test
  void typedLocalizedDuplicatesRetainIdentityAndOpaquePayloads() throws Exception {
    byte[] untouched = item(2, value(2, 42, "Deutsch".getBytes(StandardCharsets.UTF_16BE)));
    byte[] opaque = item(3, box("xxxx", new byte[]{8, 7, 6}));
    Path path =
      file(
        keyed(
          new String[]{"title", "title", "Opaque", "Unsigned", "Gain"},
          item(1, value(1, 0, bytes("First"))),
          untouched,
          opaque,
          item(4, value(78, 7, new byte[]{-1, -1, -1, -1, -1, -1, -1, -1})),
          item(5, value(23, 5, number(Float.floatToRawIntBits(1.25f), 4)))));
    var document = MetadataDocument.open(path);
    assertEquals(5, document.entries().size());
    assertEquals(List.of("18446744073709551615"), document.entries().get(3).values());
    assertEquals("binary", document.entries().get(2).kind());
    document.apply(
      List.of(
        update(document.entries().get(0), "mdta:title", "Changed"),
        update(document.entries().get(3), "mdta:Unsigned", "18446744073709551614"),
        update(document.entries().get(4), "mdta:NewGain", "-2.5")));
    document.save();
    byte[] result = Files.readAllBytes(path);
    assertContains(result, untouched);
    assertContains(result, opaque);
    assertContains(result, value(78, 7, new byte[]{-1, -1, -1, -1, -1, -1, -1, -2}));
    assertContains(result, value(23, 5, number(Float.floatToRawIntBits(-2.5f), 4)));
    var entries = MetadataDocument.open(path).entries();
    assertEquals(List.of("Changed"), entries.get(0).values());
    assertEquals(List.of("Deutsch"), entries.get(1).values());
    assertEquals("mdta:NewGain", entries.get(4).key());
    assertMedia(path);
  }

  @Test
  void paddedFastPathStillRejectsInvalidChunkOffsetsBeforeWriting() throws Exception {
    byte[] meta =
      box(
        "meta",
        new byte[4],
        handler("mdir"),
        box("ilst", box("©nam", value(1, 0, bytes("Before")))),
        box("free", new byte[1024]));
    for (boolean malformedCount : List.of(false, true)) {
      byte[] table = new byte[12];
      put32be(table, 4, malformedCount ? 2 : 1);
      put32be(table, 8, 16); // moov starts at 16: never a valid media chunk location.
      Path path = raw(box("moov", trackWithTable(table), box("udta", meta)));
      byte[] original = Files.readAllBytes(path);
      var doc = MetadataDocument.open(path);
      var entry = doc.entries().getFirst();
      doc.apply(List.of(update(entry, entry.key(), "Changed")));
      assertThrows(MetadataReadException.class, () -> doc.save());
      assertArrayEquals(original, Files.readAllBytes(path));
    }
  }

  @Test
  void rejectedNumericAndLocalizedEditsCannotChangeTheSource() throws Exception {
    for (var values : List.of(List.of("1"), List.of("256", "2"), List.of("-1", "2"))) {
      Path path =
        file(
          keyed(
            new String[]{"Count"},
            item(1, value(75, 1, new byte[]{9}), value(75, 2, new byte[]{10}))));
      var doc = MetadataDocument.open(path);
      byte[] before = Files.readAllBytes(path);
      assertThrows(
        MetadataEditException.class,
        () -> doc.apply(List.of(new MetadataChange("update", "0", "mdta:Count", values, false))));
      assertThrows(MetadataEditException.class, doc::save);
      assertArrayEquals(before, Files.readAllBytes(path));
    }
  }

  @Test
  void conventionalPairsAndNameFirstFreeformArePreserved() throws Exception {
    byte[] freeform =
      box(
        "----",
        box("name", new byte[4], bytes("SUBTITLE")),
        box("mean", new byte[4], bytes("com.apple.iTunes")),
        value(1, 0, bytes("Mix")));
    byte[] unknown =
      box("----", box("name", new byte[4], bytes("Unknown")), box("QABC", bytes("opaque")));
    byte[] pair = new byte[8];
    put16be(pair, 2, 3);
    put16be(pair, 4, 10);
    Path path = file(conventional(freeform, unknown, box("trkn", value(0, 0, pair))));
    var doc = MetadataDocument.open(path);
    assertEquals("SUBTITLE", doc.entries().get(0).standardKey());
    assertEquals("binary", doc.entries().get(1).kind());
    assertEquals(List.of("3", "10"), doc.entries().get(2).values());
    assertFalse(doc.entries().get(2).multipleValues());
    assertEquals(Map.of("valueCount", 2), doc.entries().get(2).constraints());
    doc.apply(
      List.of(
        update(doc.entries().get(2), "trkn", "4", "10"),
        new MetadataChange("add", null, "TITLE", List.of("Title"), false)));
    doc.save();
    assertContains(Files.readAllBytes(path), freeform);
    assertContains(Files.readAllBytes(path), unknown);
    assertEquals(List.of("4", "10"), MetadataDocument.open(path).entries().get(2).values());
    assertMedia(path);
  }

  @Test
  void multipleArtworkRemovalsPreserveRemainingTypeAndLocale() throws Exception {
    byte[] first = value(13, 1, bytes("jpeg")),
      middle = value(14, 53, bytes("png")),
      last = value(12, 9, bytes("gif"));
    Path path = file(keyed(new String[]{"artwork"}, item(1, first, middle, last)));
    var doc = MetadataDocument.open(path);
    assertTrue(doc.entries().stream().allMatch(e -> e.kind().equals("artwork")));
    doc.apply(List.of(remove(doc.entries().get(0)), remove(doc.entries().get(2))));
    doc.save();
    var remaining = MetadataDocument.open(path);
    assertEquals(1, remaining.entries().size());
    assertEquals("image/png", remaining.entries().getFirst().picture().mimeType());
    assertContains(Files.readAllBytes(path), middle);
    assertMedia(path);
    remaining.replacePictures(List.of(new Picture(3, "image/png", "ignored", bytes("new"))));
    remaining.save();
    assertEquals(1, MetadataDocument.open(path).entries().size());
    var clear = MetadataDocument.open(path);
    clear.replacePictures(List.of());
    clear.save();
    assertTrue(MetadataDocument.open(path).entries().isEmpty());
    assertMedia(path);
  }

  @Test
  void trackScopesAndNewGlobalMetadataDoNotMoveTrackAtoms() throws Exception {
    byte[] video = keyed(new String[]{"title"}, item(1, value(1, 0, bytes("Video"))));
    byte[] audio = keyed(new String[]{"title"}, item(1, value(1, 0, bytes("Audio"))));
    Path path = raw(box("moov", track("vide", video), track("soun", audio)));
    var doc = MetadataDocument.open(path);
    assertFalse(doc.entries().get(0).projected());
    assertTrue(doc.entries().get(1).projected());
    assertEquals("MP4 mdta · Track 2", doc.entries().get(1).block());
    assertEquals("MP4 mdta", doc.defaultBlock());
    doc.apply(List.of(new MetadataChange("add", null, "TITLE", List.of("Global"), false)));
    doc.save();
    assertContains(Files.readAllBytes(path), video);
    assertContains(Files.readAllBytes(path), audio);
    var global = MetadataDocument.open(path).entries().getLast();
    assertEquals("MP4 mdta", global.block());
    assertEquals(101, global.precedence());
  }

  @Test
  void growthPromotesStcoAndPreservesExtendedMovieHeader() throws Exception {
    byte[] table = new byte[12];
    put32be(table, 4, 1);
    put32be(table, 8, 0xfffffff6L);
    byte[] payload =
      join(
        box("trak", box("mdia", box("minf", box("stbl", box("stco", table))))), conventional());
    byte[] movie = new byte[payload.length + 16];
    put32be(movie, 0, 1);
    System.arraycopy(bytes("moov"), 0, movie, 4, 4);
    put64be(movie, 8, movie.length);
    System.arraycopy(payload, 0, movie, 16, payload.length);
    Path path = raw(movie);
    long before = Files.size(path);
    var doc = MetadataDocument.open(path);
    doc.apply(List.of(new MetadataChange("add", null, "Large", List.of("x".repeat(10000)), true)));
    doc.save();
    var tree = new Mp4Tree(path);
    assertEquals(16, tree.movie.header());
    var co64 = tree.movie.path("trak", "mdia", "minf", "stbl", "co64");
    assertNotNull(co64);
    assertEquals(0xfffffff6L + Files.size(path) - before, u64be(tree.source, co64.body() + 8));
    assertContains(Files.readAllBytes(path), MEDIA);
  }

  @Test
  void malformedNamesAreDistinguishedFromUnsupportedLayouts() throws Exception {
    Path unresolved = file(keyed(new String[]{"title"}, item(99, value(1, 0, bytes("lost")))));
    var doc = MetadataDocument.open(unresolved);
    assertTrue(doc.metadataReadFailed());
    assertFalse(doc.readOnlyReason().isEmpty());
    assertThrows(
      MetadataEditException.class,
      () -> doc.apply(List.of(new MetadataChange("add", null, "TITLE", List.of("no"), false))));
    Path malformed = file(box("meta", new byte[4], box("keys", new byte[4], number(1000, 4))));
    assertThrows(MetadataReadException.class, () -> MetadataDocument.open(malformed));
    for (String marker : List.of("mvex", "iloc", "saio", "cmov")) {
      Path path =
        raw(
          box(
            "moov",
            keyed(new String[]{"title"}, item(1, value(1, 0, bytes("visible")))),
            box(marker, new byte[8])));
      var readOnly = MetadataDocument.open(path);
      assertFalse(readOnly.metadataReadFailed());
      assertFalse(readOnly.readOnlyReason().isEmpty());
      assertEquals(List.of("visible"), readOnly.entries().getFirst().values());
      assertFalse(readOnly.entries().getFirst().editable());
    }
  }

  @Test
  void unreadableKeyCanBeViewedAndRemovedWithoutLosingOtherMetadata() throws Exception {
    Path path = file(box("meta", new byte[4],
      box("keys", new byte[4], number(2, 4), box("mdta", new byte[]{(byte) 0xff}), box("mdta", bytes("title"))),
      box("ilst", item(1, value(1, 0, bytes("opaque key"))), item(2, value(1, 0, bytes("kept"))))));
    var document = MetadataDocument.open(path);
    var entry = document.entries().getFirst();
    assertEquals("mdta:0xff", entry.key());
    assertFalse(entry.editable());
    assertEquals(List.of("kept"), document.entries().getLast().values());
    document.apply(List.of(new MetadataChange("remove", entry.id(), null, List.of(), false)));
    document.save();
    var remaining = MetadataDocument.open(path).entries();
    assertEquals(1, remaining.size());
    assertEquals("mdta:title", remaining.getFirst().key());
    assertEquals(List.of("kept"), remaining.getFirst().values());
    assertMedia(path);
  }

  @Test
  void noOpAndFailedRenameLeaveBytesUnchanged() throws Exception {
    Path path =
      file(
        keyed(
          new String[]{"a", "b"},
          item(1, value(2, 1, "A".getBytes(StandardCharsets.UTF_16BE))),
          item(2, value(1, 0, bytes("B")))));
    byte[] original = Files.readAllBytes(path);
    var noOp = MetadataDocument.open(path);
    noOp.apply(List.of());
    noOp.save();
    assertArrayEquals(original, Files.readAllBytes(path));
    var rename = MetadataDocument.open(path);
    assertThrows(
      MetadataEditException.class,
      () -> rename.apply(List.of(update(rename.entries().getFirst(), "mdta:b", "new"))));
    assertArrayEquals(original, Files.readAllBytes(path));
  }

  private Path file(byte[] metadata) throws Exception {
    byte[] table = new byte[12];
    put32be(table, 4, 1);
    byte[] ftyp = box("ftyp", bytes("M4A \0\0\0\0"));
    byte[] movie = box("moov", trackWithTable(table), box("udta", metadata));
    put32be(table, 8, ftyp.length + movie.length + 8L);
    return raw(box("moov", trackWithTable(table), box("udta", metadata)));
  }

  private Path raw(byte[] movie) throws Exception {
    Path path = directory.resolve(UUID.randomUUID() + ".m4a");
    Files.write(path, join(box("ftyp", bytes("M4A \0\0\0\0")), movie, box("mdat", MEDIA)));
    return path;
  }

  private static byte[] trackWithTable(byte[] table) throws Exception {
    return box("trak", box("mdia", handler("soun"), box("minf", box("stbl", box("stco", table)))));
  }

  private static byte[] track(String handler, byte[] metadata) throws Exception {
    return box("trak", box("mdia", handler(handler)), metadata);
  }

  private static byte[] handler(String name) throws Exception {
    return box("hdlr", new byte[8], bytes(name), new byte[13]);
  }

  private static byte[] conventional(byte[]... items) throws Exception {
    return box("meta", new byte[4], handler("mdir"), box("ilst", items));
  }

  private static byte[] keyed(String[] names, byte[]... items) throws Exception {
    var entries = new ArrayList<byte[]>();
    for (String name : names) entries.add(box("mdta", name.getBytes(StandardCharsets.UTF_8)));
    return box(
      "meta",
      new byte[4],
      handler("mdta"),
      box("keys", new byte[4], number(names.length, 4), join(entries.toArray(byte[][]::new))),
      box("ilst", items));
  }

  private static byte[] item(int index, byte[]... values) throws Exception {
    return box(ascii(number(index, 4), 0, 4), values);
  }

  private static byte[] value(long type, long locale, byte[] content) throws Exception {
    return box("data", number(type, 4), number(locale, 4), content);
  }

  private static byte[] number(long value, int width) {
    byte[] out = new byte[width];
    for (int i = width - 1; i >= 0; i--) {
      out[i] = (byte) value;
      value >>>= 8;
    }
    return out;
  }

  private static byte[] box(String name, byte[]... body) throws Exception {
    return Mp4Items.atom(name, join(body));
  }

  private static byte[] join(byte[]... parts) throws Exception {
    var out = new ByteArrayOutputStream();
    for (var part : parts) out.write(part);
    return out.toByteArray();
  }

  private static MetadataChange update(MetadataEntry entry, String key, String... values) {
    return new MetadataChange("update", entry.id(), key, List.of(values), false);
  }

  private static MetadataChange remove(MetadataEntry entry) {
    return new MetadataChange("remove", entry.id(), null, null, false);
  }

  private static void assertContains(byte[] data, byte[] fragment) {
    assertTrue(index(data, fragment) >= 0, "preserved byte sequence missing");
  }

  private static int index(byte[] data, byte[] fragment) {
    outer:
    for (int i = 0; i <= data.length - fragment.length; i++) {
      for (int j = 0; j < fragment.length; j++) if (data[i + j] != fragment[j]) continue outer;
      return i;
    }
    return -1;
  }

  private static void assertMedia(Path path) throws Exception {
    byte[] data = Files.readAllBytes(path);
    int stco = index(data, bytes("stco"));
    assertTrue(stco > 0);
    int offset = Math.toIntExact(u32be(data, stco + 12));
    assertArrayEquals(MEDIA, Arrays.copyOfRange(data, offset, offset + MEDIA.length));
  }
}
