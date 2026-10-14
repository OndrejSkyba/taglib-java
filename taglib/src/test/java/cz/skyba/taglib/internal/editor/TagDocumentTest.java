package cz.skyba.taglib.internal.editor;

import cz.skyba.taglib.*;
import cz.skyba.taglib.ape.Item;
import cz.skyba.taglib.ape.Tag;
import cz.skyba.taglib.internal.Binary;
import cz.skyba.taglib.internal.id3.Id3v2;
import cz.skyba.taglib.internal.xiph.XiphComment;
import cz.skyba.taglib.toolkit.ByteVector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TagDocumentTest {
  @TempDir
  Path temporary;

  @Test
  void apeEditingMatchesTypedSerializationAndPreservesOpaqueDataAndAudio() throws Exception {
    byte[] opaque = new byte[16 * 1024];
    for (int i = 0; i < opaque.length; i++) opaque[i] = (byte) i;
    var expected = new Tag();
    expected.setTitle("Original");
    expected.setItem("REMOVE", new Item("REMOVE", List.of("discard")));
    expected.setItem("OPAQUE", new Item("OPAQUE", new ByteVector(opaque), true));
    Path path = temporary.resolve("physical-ape.mp3");
    Files.write(path, join(audio(), expected.render().toByteArray()));
    var document = MetadataDocument.open(path);
    var title = document.entries().stream().filter(e -> e.block().equals("APE") && e.key().equals("TITLE")).findFirst().orElseThrow();
    var removed = document.entries().stream().filter(e -> e.block().equals("APE") && e.key().equals("REMOVE")).findFirst().orElseThrow();
    String replacement = "Changed title ".repeat(100);
    document.apply(List.of(update(title, List.of(replacement)), new MetadataChange("remove", removed.id(), null, null, false)));
    document.save();
    expected.setTitle(replacement);
    expected.removeItem("REMOVE");
    assertArrayEquals(join(audio(), expected.render().toByteArray()), Files.readAllBytes(path));
  }

  @Test
  void editsOneCommentAndPreservesOtherLanguagesOpaqueFramesAndAudio() throws Exception {
    byte[] second = frame(3, "COMM", 0, utf8("\u0003czeother\u0000two"));
    byte[] opaque = frame(3, "XABC", 0, new byte[]{(byte) 255, 0, 7, 6, 5});
    Path file = temporary.resolve("comments.mp3");
    Files.write(
      file,
      join(
        tag(3, frame(3, "COMM", 0, utf8("\u0003engfirst\u0000one")), second, opaque), audio()));
    var doc = MetadataDocument.open(file);
    var comments = doc.entries().stream().filter(e -> e.key().equals("COMM")).toList();
    assertEquals(2, comments.size());
    assertEquals("first / eng", comments.getFirst().details());
    doc.apply(List.of(update(comments.getFirst(), List.of("Žluťoučký"))));
    doc.save();
    byte[] saved = Files.readAllBytes(file);
    assertEquals(3, saved[3]);
    assertTrue(contains(saved, second));
    assertTrue(contains(saved, opaque));
    assertArrayEquals(
      audio(), Arrays.copyOfRange(saved, saved.length - audio().length, saved.length));
    var after =
      MetadataDocument.open(file).entries().stream().filter(e -> e.key().equals("COMM")).toList();
    assertEquals(List.of("Žluťoučký"), after.getFirst().values());
    assertEquals(List.of("two"), after.getLast().values());
  }

  @Test
  void explicitDiscardRemovalAndVersion22ReadOnlyAreEnforced() throws Exception {
    Path file = temporary.resolve("flags.mp3");
    Files.write(file, join(tag(3, frame(3, "XABC", 0x8000, new byte[]{1, 2, 3})), audio()));
    byte[] before = Files.readAllBytes(file);
    var doc = MetadataDocument.open(file);
    assertTrue(doc.entries().getFirst().requiresRemoval());
    assertThrows(MetadataEditException.class, () -> doc.apply(List.of(add("TITLE", "new"))));
    assertArrayEquals(before, Files.readAllBytes(file));
    var revised = MetadataDocument.open(file);
    revised.apply(
      List.of(
        new MetadataChange("remove", revised.entries().getFirst().id(), null, null, false),
        add("TITLE", "new")));
    revised.save();
    assertEquals("new", TagLib.read(file).properties().first("TITLE"));
    Files.write(file, join(tag(2, frame(2, "TT2", 0, utf8("\u0000old"))), audio()));
    var old = MetadataDocument.open(file);
    assertFalse(old.readOnlyReason().isEmpty());
    assertFalse(old.entries().getFirst().editable());
    assertThrows(MetadataEditException.class, () -> old.apply(List.of(add("TITLE", "new"))));
  }

  @Test
  void legacyCapacityRejectsWholeBatchAndUntouchedLossyLegacyTag() throws Exception {
    Path file = temporary.resolve("legacy.mp3");
    byte[] legacy = new byte[128];
    legacy[0] = 'T';
    legacy[1] = 'A';
    legacy[2] = 'G';
    System.arraycopy(utf8("Legacy"), 0, legacy, 3, 6);
    legacy[127] = (byte) 255;
    Files.write(file, join(tag(4, frame(4, "TIT2", 0, utf8("\u0003original"))), audio(), legacy));
    byte[] before = Files.readAllBytes(file);
    var doc = MetadataDocument.open(file);
    var titles = doc.entries().stream().filter(e -> e.standardKey().equals("TITLE")).toList();
    assertEquals(2, titles.size());
    assertThrows(
      MetadataEditException.class,
      () -> doc.apply(titles.stream().map(e -> update(e, List.of("x".repeat(31)))).toList()));
    assertThrows(MetadataEditException.class, doc::save);
    assertArrayEquals(before, Files.readAllBytes(file));
    Arrays.fill(legacy, 97, 127, (byte) 'x');
    Files.write(file, join(tag(4), audio(), legacy));
    var longComment = MetadataDocument.open(file);
    longComment.apply(List.of(add("ALBUM", "other")));
    assertThrows(MetadataEditException.class, longComment::save);
  }

  @Test
  void flacEditsVorbisWithoutRewritingLegacyId3OrOtherMetadata() throws Exception {
    Path file = temporary.resolve("physical.flac");
    byte[] legacy = tag(3, frame(3, "TIT2", 0, utf8("\u0003Legacy")));
    byte[] unknown = {1, 2, 3, 4, 5, 6};
    byte[] sound = {9, 8, 7, 6, 5};
    byte[] comment =
      XiphComment.empty()
        .render(
          PropertyMap.builder()
            .put("TITLE", "Original")
            .put("CUSTOM", List.of("one", "two"))
            .build(),
          false);
    Files.write(
      file,
      join(
        legacy,
        utf8("fLaC"),
        flac(0, new byte[34]),
        flac(2, unknown),
        flac(0x84, comment),
        sound));
    var doc = MetadataDocument.open(file);
    var entry =
      doc.entries().stream()
        .filter(e -> e.block().equals("Vorbis") && e.key().equals("CUSTOM"))
        .findFirst()
        .orElseThrow();
    doc.apply(
      List.of(
        new MetadataChange("update", entry.id(), "RENAMED", List.of("three", "four"), false)));
    doc.save();
    byte[] saved = Files.readAllBytes(file);
    assertArrayEquals(legacy, Arrays.copyOf(saved, legacy.length));
    assertTrue(contains(saved, flac(2, unknown)));
    assertArrayEquals(sound, Arrays.copyOfRange(saved, saved.length - sound.length, saved.length));
    assertEquals(List.of("three", "four"), TagLib.read(file).properties().get("RENAMED"));
  }

  @Test
  void riffUpdatesId3IndependentlyFromInfoAndOpaqueChunks() throws Exception {
    Path file = temporary.resolve("physical.wav");
    byte[] format = new byte[16];
    Binary.put16le(format, 0, 1);
    Binary.put16le(format, 2, 1);
    Binary.put32le(format, 4, 8000);
    Binary.put32le(format, 8, 16000);
    Binary.put16le(format, 12, 2);
    Binary.put16le(format, 14, 16);
    byte[] info = chunk("LIST", join(utf8("INFO"), chunk("INAM", utf8("Legacy\u0000"))));
    byte[] opaque = chunk("abcd", new byte[]{1, 2, 3});
    byte[] body =
      join(
        utf8("WAVE"),
        chunk("fmt ", format),
        info,
        chunk("ID3 ", tag(3, frame(3, "TIT2", 0, utf8("\u0003Primary")))),
        opaque,
        chunk("data", new byte[100]));
    Files.write(file, chunk("RIFF", body));
    var doc = MetadataDocument.open(file);
    var title =
      doc.entries().stream()
        .filter(e -> e.block().equals("ID3v2") && e.standardKey().equals("TITLE"))
        .findFirst()
        .orElseThrow();
    doc.apply(List.of(update(title, List.of("Changed"))));
    doc.save();
    byte[] saved = Files.readAllBytes(file);
    assertTrue(contains(saved, info));
    assertTrue(contains(saved, opaque));
    assertEquals(saved.length - 8, Binary.u32le(saved, 4));
    var entries = MetadataDocument.open(file).entries();
    assertTrue(
      entries.stream()
        .anyMatch(e -> e.block().equals("RIFF INFO") && e.values().equals(List.of("Legacy"))));
  }

  @Test
  void existingId3AfterGarbagePrefixIsEditedInPlace() throws Exception {
    Path file = temporary.resolve("prefix.mp3");
    byte[] prefix = new byte[2047];
    Arrays.fill(prefix, (byte) 0x55);
    Files.write(file, join(prefix, tag(3, frame(3, "TIT2", 0, utf8("\u0003Original"))), audio()));
    var doc = MetadataDocument.open(file);
    assertEquals(1, doc.entries().size());
    doc.apply(List.of(update(doc.entries().getFirst(), List.of("Changed"))));
    doc.save();
    byte[] saved = Files.readAllBytes(file);
    assertArrayEquals(prefix, Arrays.copyOf(saved, prefix.length));
    assertEquals("Changed", TagLib.read(file).properties().first("TITLE"));
    assertArrayEquals(
      audio(), Arrays.copyOfRange(saved, saved.length - audio().length, saved.length));
  }

  @Test
  void appendingMetadataPadsAnOriginallyUnpaddedOddFinalAiffChunk() throws Exception {
    Path file = temporary.resolve("odd.aiff");
    byte[] sound = new byte[]{1, 2, 3};
    byte[] header = new byte[8];
    System.arraycopy(Binary.bytes("SSND"), 0, header, 0, 4);
    Binary.put32be(header, 4, sound.length);
    byte[] body = join(Binary.bytes("AIFF"), header, sound);
    byte[] form = new byte[8];
    System.arraycopy(Binary.bytes("FORM"), 0, form, 0, 4);
    Binary.put32be(form, 4, body.length);
    Files.write(file, join(form, body));
    var doc = MetadataDocument.open(file);
    doc.apply(List.of(add("TITLE", "Aligned")));
    doc.save();
    assertEquals("Aligned", TagLib.read(file).properties().first("TITLE"));
    assertEquals("Aligned", MetadataDocument.open(file).entries().getFirst().values().getFirst());
    byte[] saved = Files.readAllBytes(file);
    assertArrayEquals(sound, Arrays.copyOfRange(saved, 20, 23));
    assertEquals(0, saved[23]);
    assertEquals('I', saved[24]);
  }

  @Test
  void malformedContainersReportMetadataReadFailureAndMissingFilesRetainFilesystemErrors()
    throws Exception {
    Path file = temporary.resolve("malformed.wav");
    byte[] malformed = new byte[20];
    System.arraycopy(Binary.bytes("RIFF"), 0, malformed, 0, 4);
    System.arraycopy(Binary.bytes("WAVEdata"), 0, malformed, 8, 8);
    Binary.put32le(malformed, 4, 12);
    Binary.put32le(malformed, 16, 1024);
    Files.write(file, malformed);
    assertThrows(MetadataReadException.class, () -> MetadataDocument.open(file));
    assertInstanceOf(NoSuchFileException.class, assertThrows(java.io.UncheckedIOException.class,
      () -> MetadataDocument.open(temporary.resolve("absent.wav"))).getCause());
  }

  @Test
  void aSecondEditBatchInvalidatesTheSessionInsteadOfReusingStaleEntryIds() throws Exception {
    Path file = temporary.resolve("batch.mp3");
    Files.write(file, join(tag(4, frame(4, "TIT2", 0, utf8("\u0003Original"))), audio()));
    byte[] before = Files.readAllBytes(file);
    var doc = MetadataDocument.open(file);
    doc.apply(List.of(add("ALBUM", "Staged")));
    assertThrows(
      MetadataEditException.class, () -> doc.apply(List.of(add("ARTIST", "Second batch"))));
    assertThrows(MetadataEditException.class, doc::save);
    assertArrayEquals(before, Files.readAllBytes(file));
  }

  @Test
  void artworkFailurePreventsSavingPreviouslyStagedText() throws Exception {
    Path file = temporary.resolve("failed-art.mp3");
    Files.write(file, join(tag(4, frame(4, "TIT2", 0, utf8("\u0003Original"))), audio()));
    byte[] before = Files.readAllBytes(file);
    var doc = MetadataDocument.open(file);
    doc.apply(List.of(add("ALBUM", "Staged")));
    assertThrows(
      MetadataEditException.class,
      () ->
        doc.replacePictures(
          List.of(new Picture(3, "image/png", "bad\u0000description", new byte[]{1}))));
    assertThrows(MetadataEditException.class, doc::save);
    assertArrayEquals(before, Files.readAllBytes(file));
  }

  @Test
  void emptyPhysicalId3IsDistinguishedFromSynthesizedPrimaryTag() throws Exception {
    Path file = temporary.resolve("empty-tag.mp3");
    Files.write(file, join(tag(4), audio()));
    var existing = MetadataDocument.open(file);
    assertTrue(existing.entries().isEmpty());
    assertTrue(existing.hasId3v2Tag());
    Files.write(file, audio());
    var absent = MetadataDocument.open(file);
    assertTrue(absent.entries().isEmpty());
    assertFalse(absent.hasId3v2Tag());
    absent.apply(List.of(add("TITLE", "Staged")));
    assertFalse(absent.hasId3v2Tag());
  }

  @Test
  void editingRejectsTruncatedTagInsteadOfDroppingUnparsedBytes() throws Exception {
    byte[] original = tag(3, frame(3, "TIT2", 0, utf8("\u0003title")));
    original[9] = 127;
    assertThrows(IOException.class, () -> Id3v2.parseForEditing(original));
  }

  private static MetadataChange update(MetadataEntry e, List<String> values) {
    return new MetadataChange("update", e.id(), e.key(), values, false);
  }

  private static MetadataChange add(String key, String value) {
    return new MetadataChange("add", null, key, List.of(value), false);
  }

  private static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] audio() {
    byte[] result = new byte[834];
    for (int at : new int[]{0, 417}) {
      result[at] = (byte) 255;
      result[at + 1] = (byte) 251;
      result[at + 2] = (byte) 144;
    }
    return result;
  }

  private static byte[] tag(int version, byte[]... frames) throws IOException {
    var out = new ByteArrayOutputStream();
    out.writeBytes(join(frames));
    return Id3v2.renderTag(version, out);
  }

  private static byte[] frame(int version, String id, int flags, byte[] data) throws IOException {
    var out = new ByteArrayOutputStream();
    Id3v2.writeFrame(out, version, id, flags, data);
    return out.toByteArray();
  }

  private static byte[] flac(int type, byte[] bytes) {
    byte[] result = new byte[bytes.length + 4];
    result[0] = (byte) type;
    result[1] = (byte) (bytes.length >>> 16);
    result[2] = (byte) (bytes.length >>> 8);
    result[3] = (byte) bytes.length;
    System.arraycopy(bytes, 0, result, 4, bytes.length);
    return result;
  }

  private static byte[] chunk(String id, byte[] payload) {
    byte[] result = new byte[8 + payload.length + (payload.length & 1)];
    System.arraycopy(Binary.bytes(id), 0, result, 0, 4);
    Binary.put32le(result, 4, payload.length);
    System.arraycopy(payload, 0, result, 8, payload.length);
    return result;
  }

  private static byte[] join(byte[]... parts) {
    var out = new ByteArrayOutputStream();
    for (var part : parts) out.writeBytes(part);
    return out.toByteArray();
  }

  private static boolean contains(byte[] bytes, byte[] expected) {
    outer:
    for (int i = 0; i <= bytes.length - expected.length; i++) {
      for (int j = 0; j < expected.length; j++) if (bytes[i + j] != expected[j]) continue outer;
      return true;
    }
    return false;
  }
}
