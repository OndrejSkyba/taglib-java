package cz.skyba.taglib.internal.codec;

import cz.skyba.taglib.*;
import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.ape.ApeTag;
import cz.skyba.taglib.internal.id3.Id3v1;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regressions for the distinction between APE properties and TagUnion's common-field view.
 */
class ApeRegressionTest {
  @TempDir
  Path directory;
  private static final List<Format> FORMATS = List.of(Format.APE, Format.WAVPACK, Format.MPC);
  private static final byte[] AUDIO = audioBytes();

  private record Item(String key, int flags, byte[] value) {
  }

  @Test
  void invalidUtf8BecomesEmptyAndNativeNumericPrefixesAreUsed() throws Exception {
    Path path =
      fixture(
        "native-decoding",
        PropertyMap.EMPTY,
        List.of(
          new Item("TITLE", 0, new byte[]{(byte) 0xc3}),
          new Item("YEAR", 0, "20345tail".getBytes(StandardCharsets.US_ASCII)),
          new Item("TRACK", 0, "-1/9".getBytes(StandardCharsets.US_ASCII))),
        PropertyMap.EMPTY);
    try (var file = new BinaryFile(path)) {
      var data = ApeTag.read(file, ReadOptions.DEFAULT);
      assertEquals(List.of(""), data.properties().get("TITLE"));
      assertEquals("", data.basic().title());
      assertEquals(20345, data.basic().year());
      assertEquals(4294967295L, data.basic().track());
    }
  }

  @Test
  void commonSnapshotPreservesNativeUnsignedNumericBoundaries() throws Exception {
    String[][] fields = {{"-1", "2147483648"}, {"2147483648", "-1"}};
    long[][] expected = {{4294967295L, 2147483648L}, {2147483648L, 4294967295L}};
    for (int i = 0; i < fields.length; i++) {
      Path path = fixture("unsigned-" + i, PropertyMap.EMPTY, List.of(
        new Item("YEAR", 0, fields[i][0].getBytes(StandardCharsets.US_ASCII)),
        new Item("TRACK", 0, fields[i][1].getBytes(StandardCharsets.US_ASCII))), PropertyMap.EMPTY);
      try (var file = new BinaryFile(path)) {
        var data = ApeTag.read(file, ReadOptions.DEFAULT);
        assertEquals(expected[i][0], data.basic().year());
        assertEquals(expected[i][1], data.basic().track());
      }
      for (Format format : FORMATS) {
        var read = new ApeCodec(format).read(path, ReadOptions.TAGS_ONLY);
        assertEquals(expected[i][0], read.tag().year());
        assertEquals(expected[i][1], read.tag().track());
      }
    }
  }

  @Test
  void pictureReplacementSelectsLastExplicitFrontAndFirstBack() throws Exception {
    var data = new ApeTag.Data(0, 0, PropertyMap.EMPTY, List.of(), List.of());
    var pictures = new ArrayList<Picture>();
    for (int type : new int[]{0, 3, 3, 4, 4})
      pictures.add(new Picture(type, "", "", new byte[]{(byte) (pictures.size() + 1)}));
    Path path = directory.resolve("picture-selection.bin");
    Files.write(path, ApeTag.render(PropertyMap.EMPTY, data, pictures));
    try (var file = new BinaryFile(path)) {
      var read = ApeTag.read(file, ReadOptions.DEFAULT);
      assertArrayEquals(new byte[]{3}, read.pictures().get(0).data());
      assertArrayEquals(new byte[]{4}, read.pictures().get(1).data());
    }
  }

  @Test
  void nativeItemMapReplacesDuplicateKeysSkipsInvalidKeysAndRetainsEmptyComponents()
    throws Exception {
    var items =
      List.of(
        new Item("TITLE", 0, "first".getBytes(StandardCharsets.UTF_8)),
        new Item("title", 1, "last".getBytes(StandardCharsets.UTF_8)),
        new Item("X", 0, "short".getBytes(StandardCharsets.UTF_8)),
        new Item("ID3", 0, "reserved".getBytes(StandardCharsets.UTF_8)),
        new Item("A\u007fB", 0, "control".getBytes(StandardCharsets.UTF_8)),
        new Item("A=B", 0, "accepted".getBytes(StandardCharsets.UTF_8)),
        new Item("COMMENT", 0, "\0one\0\0two\0".getBytes(StandardCharsets.UTF_8)),
        new Item("COVER ART (OTHER)", 2, new byte[]{'x', 0, 1, 2}));
    Path path = fixture("item-map", PropertyMap.EMPTY, items, PropertyMap.EMPTY);
    ApeTag.Data data;
    try (var file = new BinaryFile(path)) {
      data = ApeTag.read(file, ReadOptions.DEFAULT);
    }
    assertEquals(4, data.items().size());
    assertEquals("last", data.properties().first("TITLE"));
    assertEquals("accepted", data.properties().first("A=B"));
    assertEquals(List.of("", "one", "", "two"), data.properties().get("COMMENT"));
    assertEquals(" / one /  / two", data.basic().comment());
    assertTrue(data.pictures().isEmpty());
    byte[] saved = ApeTag.render(data.properties(), data);
    Files.write(path, saved);
    try (var file = new BinaryFile(path)) {
      var reread = ApeTag.read(file, ReadOptions.DEFAULT);
      var title =
        reread.items().stream().filter(i -> i.key().equals("title")).findFirst().orElseThrow();
      assertEquals(
        1, title.flags(), "An unchanged native text item retains its read-only flag and casing");
      assertEquals(data.properties(), reread.properties());
    }
    var changed = data.properties().toBuilder().put("TITLE", "edited").build();
    Files.write(path, ApeTag.render(changed, data));
    try (var file = new BinaryFile(path)) {
      var title =
        ApeTag.read(file, ReadOptions.DEFAULT).items().stream()
          .filter(i -> i.key().equals("TITLE"))
          .findFirst()
          .orElseThrow();
      assertEquals(
        0, title.flags(), "Changing text constructs a new item, as native addValue does");
    }
  }

  @Test
  void propertyAliasesMergeExistingNativeKeyValuesAndEmptySettersAreSkipped() throws Exception {
    Path path =
      fixture(
        "set-properties",
        PropertyMap.EMPTY,
        List.of(new Item("Opaque", 2, new byte[]{1})),
        PropertyMap.EMPTY);
    ApeTag.Data data;
    try (var file = new BinaryFile(path)) {
      data = ApeTag.read(file, ReadOptions.DEFAULT);
    }
    var properties =
      PropertyMap.builder()
        .put("YEAR", "old-alias")
        .put("DATE", "2026")
        .put("TITLE", List.of("", "valid", ""))
        .build();
    Files.write(path, ApeTag.render(properties, data));
    try (var file = new BinaryFile(path)) {
      var read = ApeTag.read(file, ReadOptions.DEFAULT);
      assertEquals(List.of("old-alias", "2026"), read.properties().get("DATE"));
      assertEquals(List.of("valid"), read.properties().get("TITLE"));
    }
  }

  @Test
  void artworkReplacementPreservesUnrecognizedCoverArtNamesInPhysicalEditor() throws Exception {
    byte[] opaque = {'x', 0, 1, 2, 3};
    Path source =
      fixture(
        "artwork-names",
        PropertyMap.EMPTY,
        List.of(
          new Item("COVER ART (OTHER)", 2, opaque),
          new Item("COVER ART (FRONT)", 2, new byte[]{'x', 0, 4, 5, 6})),
        PropertyMap.EMPTY);
    Path path = directory.resolve("artwork.wv");
    Files.move(source, path);
    var document = MetadataDocument.open(path);
    assertEquals(
      "binary",
      document.entries().stream()
        .filter(e -> e.key().equals("COVER ART (OTHER)"))
        .findFirst()
        .orElseThrow()
        .kind());
    assertEquals(1, document.entries().stream().filter(e -> e.picture() != null).count());
    document.replacePictures(List.of());
    document.save();
    try (var file = new BinaryFile(path)) {
      var read = ApeTag.read(file, ReadOptions.DEFAULT);
      assertTrue(read.pictures().isEmpty());
      assertEquals(1, read.items().size());
      assertArrayEquals(opaque, read.items().getFirst().value());
    }
  }

  @Test
  void partialApePropertiesKeepPrecedenceWhileCommonFieldsFallBackToId3v1() throws Exception {
    PropertyMap modern = PropertyMap.builder().put("TITLE", "APE title").build();
    PropertyMap legacy =
      PropertyMap.builder()
        .put("TITLE", "Legacy title")
        .put("ARTIST", "Legacy artist")
        .put("ALBUM", "Legacy album")
        .put("COMMENT", "Legacy comment")
        .put("GENRE", "Jazz")
        .put("DATE", "1997")
        .put("TRACKNUMBER", "9")
        .build();

    for (Format format : FORMATS) {
      Path path = fixture(format + "-partial", modern, List.of(), legacy);
      AudioMetadata read = new ApeCodec(format).read(path, ReadOptions.TAGS_ONLY);

      assertEquals(modern, read.properties(), "The APE property map must not absorb legacy fields");
      assertEquals("APE title", read.tag().title());
      assertEquals("Legacy artist", read.tag().artist());
      assertEquals("Legacy album", read.tag().album());
      assertEquals("Legacy comment", read.tag().comment());
      assertEquals("Jazz", read.tag().genre());
      assertEquals(1997, read.tag().year());
      assertEquals(9, read.tag().track());
    }
  }

  @Test
  void opaqueOnlyApeItemsBlockPropertyFallbackButPermitCommonFieldFallback() throws Exception {
    PropertyMap legacy =
      PropertyMap.builder().put("TITLE", "Legacy title").put("ARTIST", "Legacy artist").build();
    Item opaque = new Item("Opaque binary item", 2, new byte[]{9, 0, 7, (byte) 255});

    for (Format format : FORMATS) {
      Path path = fixture(format + "-opaque", PropertyMap.EMPTY, List.of(opaque), legacy);
      AudioMetadata read = new ApeCodec(format).read(path, ReadOptions.TAGS_ONLY);

      assertTrue(read.properties().isEmpty(), "A binary-only APE tag is still the primary tag");
      assertEquals("Legacy title", read.tag().title());
      assertEquals("Legacy artist", read.tag().artist());
      assertTrue(read.pictures().isEmpty(), "An arbitrary binary item is not artwork");
    }
  }

  @Test
  void multiValuedApeCommentsAreJoinedInTheCommonTagView() throws Exception {
    PropertyMap modern =
      PropertyMap.builder()
        .put("COMMENT", List.of("First note", "Second note", "Third note"))
        .build();
    PropertyMap legacy = PropertyMap.builder().put("COMMENT", "Legacy note").build();

    for (Format format : FORMATS) {
      Path path = fixture(format + "-comments", modern, List.of(), legacy);
      AudioMetadata read = new ApeCodec(format).read(path, ReadOptions.TAGS_ONLY);

      assertEquals(
        List.of("First note", "Second note", "Third note"), read.properties().get("COMMENT"));
      assertEquals("First note / Second note / Third note", read.tag().comment());
    }
  }

  @Test
  void arbitraryDateAndTracknumberItemsDoNotBecomeNativeCommonAliases() throws Exception {
    var raw =
      List.of(
        new Item("DATE", 0, "2026".getBytes(StandardCharsets.UTF_8)),
        new Item("TRACKNUMBER", 0, "8".getBytes(StandardCharsets.UTF_8)));
    Path p = fixture("aliases", PropertyMap.EMPTY, raw, PropertyMap.EMPTY);
    var read = new ApeCodec(Format.WAVPACK).read(p, ReadOptions.TAGS_ONLY);
    assertEquals("2026", read.properties().first("DATE"));
    assertEquals("8", read.properties().first("TRACKNUMBER"));
    assertEquals(0, read.tag().year());
    assertEquals(0, read.tag().track());
  }

  @Test
  void metadataWritesUpdateExistingId3v1AlongsideTheApeTag() throws Exception {
    PropertyMap replacement =
      PropertyMap.builder()
        .put("TITLE", "New title")
        .put("ARTIST", "New artist")
        .put("ALBUM", "New album")
        .put("COMMENT", "New comment")
        .put("GENRE", "Rock")
        .put("DATE", "2026")
        .put("TRACKNUMBER", "7")
        .build();
    PropertyMap stale =
      PropertyMap.builder()
        .put("TITLE", "Old title")
        .put("ARTIST", "Old artist")
        .put("DATE", "1990")
        .build();

    for (Format format : FORMATS) {
      for (boolean existingApe : List.of(false, true)) {
        Path path =
          fixture(
            format + "-update-" + existingApe,
            existingApe ? stale : PropertyMap.EMPTY,
            List.of(),
            stale);
        var codec = new ApeCodec(format);
        codec.write(path, replacement);

        byte[] saved = Files.readAllBytes(path);
        assertArrayEquals(
          AUDIO, Arrays.copyOf(saved, AUDIO.length), "Encoded bytes must be untouched");
        assertEquals(
          replacement,
          Id3v1.read(Arrays.copyOfRange(saved, saved.length - 128, saved.length)),
          "Legacy readers must see the updated ID3v1 fields");
        assertEquals(replacement, codec.read(path, ReadOptions.TAGS_ONLY).properties());
      }
    }
  }

  @Test
  void clearingMetadataRemovesLegacyFieldsInsteadOfResurrectingThem() throws Exception {
    PropertyMap stale =
      PropertyMap.builder()
        .put("TITLE", "Old title")
        .put("ARTIST", "Old artist")
        .put("GENRE", "Jazz")
        .build();

    for (Format format : FORMATS) {
      for (boolean existingApe : List.of(false, true)) {
        Path path =
          fixture(
            format + "-clear-" + existingApe,
            existingApe ? stale : PropertyMap.EMPTY,
            List.of(),
            stale);
        var codec = new ApeCodec(format);
        codec.write(path, PropertyMap.EMPTY);

        assertArrayEquals(
          AUDIO, Files.readAllBytes(path), "Both empty metadata blocks should be removed");
        AudioMetadata read = codec.read(path, ReadOptions.TAGS_ONLY);
        assertTrue(read.properties().isEmpty());
        assertEquals("", read.tag().title());
        assertEquals("", read.tag().artist());
        assertEquals("", read.tag().genre());
      }
    }
  }

  private Path fixture(String name, PropertyMap properties, List<Item> opaque, PropertyMap legacy)
    throws Exception {
    var output = new ByteArrayOutputStream();
    output.writeBytes(AUDIO);
    output.writeBytes(apeTag(properties, opaque));
    output.writeBytes(Id3v1.render(legacy));
    Path path = directory.resolve(name + ".bin");
    Files.write(path, output.toByteArray());
    return path;
  }

  /**
   * Builds APEv2 framing independently of the writer under test.
   */
  private static byte[] apeTag(PropertyMap properties, List<Item> opaque) {
    var items = new ArrayList<>(opaque);
    properties
      .asMap()
      .forEach(
        (key, values) -> {
          String diskKey =
            switch (key) {
              case "DATE" -> "Year";
              case "TRACKNUMBER" -> "Track";
              default -> key;
            };
          items.add(
            new Item(diskKey, 0, String.join("\0", values).getBytes(StandardCharsets.UTF_8)));
        });
    if (items.isEmpty()) return new byte[0];
    var body = new ByteArrayOutputStream();
    for (Item item : items) {
      byte[] header = new byte[8];
      littleEndian(header, 0, item.value().length);
      littleEndian(header, 4, item.flags());
      body.writeBytes(header);
      body.writeBytes(item.key().getBytes(StandardCharsets.US_ASCII));
      body.write(0);
      body.writeBytes(item.value());
    }
    byte[] footer = new byte[32];
    System.arraycopy("APETAGEX".getBytes(StandardCharsets.US_ASCII), 0, footer, 0, 8);
    littleEndian(footer, 8, 2000);
    littleEndian(footer, 12, body.size() + 32);
    littleEndian(footer, 16, items.size());
    littleEndian(footer, 20, 0x80000000L);
    byte[] header = footer.clone();
    littleEndian(header, 20, 0xa0000000L);
    var output = new ByteArrayOutputStream();
    output.writeBytes(header);
    output.writeBytes(body.toByteArray());
    output.writeBytes(footer);
    return output.toByteArray();
  }

  private static void littleEndian(byte[] output, int offset, long value) {
    for (int i = 0; i < 4; i++) output[offset + i] = (byte) (value >>> (8 * i));
  }

  private static byte[] audioBytes() {
    byte[] bytes = new byte[256];
    for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 37 + 11);
    return bytes;
  }
}
