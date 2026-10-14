package cz.skyba.taglib;

import cz.skyba.taglib.asf.Attribute;
import cz.skyba.taglib.matroska.ChapterEdition;
import cz.skyba.taglib.matroska.Chapters;
import cz.skyba.taglib.matroska.SimpleTag;
import cz.skyba.taglib.mp4.Chapter;
import cz.skyba.taglib.mp4.Stem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static cz.skyba.taglib.Mp4Test.box;
import static cz.skyba.taglib.Mp4Test.join;
import static cz.skyba.taglib.internal.Binary.*;
import static org.junit.jupiter.api.Assertions.*;

class ContainerMetadataEditingTest {
  @TempDir
  Path directory;
  private static final byte[] MEDIA = {11, 22, 33, 44, 55, 66, 77};

  @Test
  void containerAsfBooleanGuidAndJoinedContentFollowReleaseSemantics() throws Exception {
    for (String value : List.of("", "0", "TRUE", "true", "false", "1", "yes")) {
      Path path = asf("bool-" + value);
      TagLib.write(path, PropertyMap.builder().put("ISVBR", value)
        .put("TITLE", List.of("One", "Two"))
        .put("MEDIACLASSPRIMARYID", "{01234567-89ab-cdef-0123-456789abcdef}").build());
      var result = TagLib.read(path);
      assertEquals(Set.of("", "0", "TRUE", "true").contains(value) ? "0" : "1",
        result.properties().first("ISVBR"));
      assertEquals("One Two", result.tag().title());
      assertEquals("01234567-89AB-CDEF-0123-456789ABCDEF",
        result.properties().first("MEDIACLASSPRIMARYID"));
      assertMedia(path);
    }
    Path malformed = asf("bad-guid");
    TagLib.write(malformed, PropertyMap.builder().put("MEDIACLASSPRIMARYID", "xyz").build());
    assertEquals("", TagLib.read(malformed).properties().first("MEDIACLASSPRIMARYID"));
  }

  @Test
  void containerAsfTypedValuesRetainScopeUnsignedRangeAndBinaryBytes() throws Exception {
    Path path = asf("typed");
    List<Attribute> attributes = List.of(
      Attribute.text("Scoped", "global"),
      Attribute.text("Scoped", "stream").withScope(4, 0),
      Attribute.text("Scoped", "language").withScope(4, 9),
      Attribute.binary("Opaque", new byte[]{0, -1, 7}),
      Attribute.unsigned("Counter", 4, new BigInteger("18446744073709551615")),
      Attribute.bool("Switch", true));
    new cz.skyba.taglib.asf.Tag(attributes).save(path);
    var values = cz.skyba.taglib.asf.Tag.read(path).attributes();
    assertEquals(List.of("Counter", "Opaque", "Scoped", "Scoped", "Scoped", "Switch"),
      values.stream().map(Attribute::name).toList());
    for (var attribute : attributes) {
      var found = values.stream().filter(a -> a.name().equals(attribute.name())
        && a.stream() == attribute.stream() && a.language() == attribute.language()).findFirst().orElseThrow();
      if (attribute.type() == 2) assertEquals(BigInteger.ONE, found.unsigned());
      else assertEquals(attribute, found);
    }
    byte[] copy = values.getFirst().data();
    Arrays.fill(copy, (byte) 0);
    assertEquals(attributes.get(4), values.stream().filter(a -> a.name().equals("Counter")).findFirst().orElseThrow());
    new cz.skyba.taglib.asf.Tag(List.of()).save(path);
    assertTrue(cz.skyba.taglib.asf.Tag.read(path).attributes().isEmpty());
    assertMedia(path);
  }

  @Test
  void containerAsfLegacyTrackIsACommonOnlyFallback() throws Exception {
    Path path = asf("legacy-track");
    new cz.skyba.taglib.asf.Tag(List.of(Attribute.unsigned("WM/Track", 3, BigInteger.valueOf(42)))).save(path);
    var read = TagLib.read(path);
    assertEquals(42, read.tag().track());
    assertFalse(read.properties().containsKey("TRACKNUMBER"));
    new cz.skyba.taglib.asf.Tag(List.of(Attribute.unsigned("WM/Track", 3, BigInteger.valueOf(42)),
      Attribute.text("WM/TrackNumber", "7"))).save(path);
    assertEquals(7, TagLib.read(path).tag().track());
  }

  @Test
  void containerMp4NeroAndStemCreateGrowRemoveAndRetainMediaOffsets() throws Exception {
    Path path = mp4("structured");
    var chapters = List.of(new Chapter("Opening — 日本語", 0), new Chapter("Second", 12001));
    var tag = new cz.skyba.taglib.mp4.Tag(chapters, new Stem(bytes("{\"stems\":[]}")));
    tag.save(path);
    assertEquals(tag, cz.skyba.taglib.mp4.Tag.read(path));
    assertMedia(path);
    assertChunkOffset(path);
    new cz.skyba.taglib.mp4.Tag(List.of(new Chapter("a".repeat(300), 25)),
      new Stem(new byte[5000])).save(path);
    assertEquals("a".repeat(255), cz.skyba.taglib.mp4.Tag.read(path).neroChapters().getFirst().title());
    assertChunkOffset(path);
    new cz.skyba.taglib.mp4.Tag(List.of()).save(path);
    assertEquals(new cz.skyba.taglib.mp4.Tag(List.of()), cz.skyba.taglib.mp4.Tag.read(path));
    assertMedia(path);
    assertChunkOffset(path);
  }

  @Test
  void containerNeroVersionZeroAndTruncationFollowNativeParser() throws Exception {
    Path path = directory.resolve("version-zero.m4a");
    byte[] time = new byte[8];
    put64be(time, 0, 123450000);
    byte[] data = join(new byte[]{0, 0, 0, 0, 2}, time, new byte[]{3}, bytes("one"), time, new byte[]{30});
    Files.write(path, join(box("ftyp", bytes("M4A \0\0\0\0")),
      box("moov", box("udta", box("chpl", data))), box("mdat", MEDIA)));
    assertEquals(List.of(new Chapter("one", 12345), new Chapter("", 12345)),
      cz.skyba.taglib.mp4.Tag.read(path).neroChapters());
  }

  @Test
  void containerMp4UnsafeStructuredSaveAndOverflowLeaveBytesUnchanged() throws Exception {
    Path path = mp4("unsafe");
    byte[] before = Files.readAllBytes(path);
    var huge = new cz.skyba.taglib.mp4.Tag(List.of(new Chapter("x", Long.MAX_VALUE)));
    assertThrows(java.io.UncheckedIOException.class, () -> huge.save(path));
    assertArrayEquals(before, Files.readAllBytes(path));
    Files.write(path, box("sidx", new byte[12]), StandardOpenOption.APPEND);
    before = Files.readAllBytes(path);
    var ordinary = new cz.skyba.taglib.mp4.Tag(List.of(new Chapter("x", 0)));
    assertThrows(TagLibException.class, () -> ordinary.save(path));
    assertArrayEquals(before, Files.readAllBytes(path));
  }

  @Test
  void containerMp4FullPropertySaveMatchesNativeColonNameLoss() throws Exception {
    Path path = mp4("colon");
    var p = PropertyMap.builder().put("TITLE", "kept").put("UFIDHTTP://EXAMPLE.COM/ID3", "native drops").build();
    TagLib.write(path, p);
    var read = TagLib.read(path);
    assertEquals("kept", read.tag().title());
    assertFalse(read.properties().containsKey("UFIDHTTP://EXAMPLE.COM/ID3"));
    assertMedia(path);
  }

  @Test
  void containerEbmlFirstNulAndLatin1MediaType() throws Exception {
    Path path = matroska();
    var read = TagLib.read(path, ReadOptions.TAGS_ONLY);
    assertEquals("Before", read.tag().title());
    assertEquals("image/é", read.pictures().getFirst().mimeType());
    assertArrayEquals(MEDIA, read.pictures().getFirst().data());
  }

  @Test
  void containerMatroskaTypedTargetsBinaryAndLanguagesRoundTrip() throws Exception {
    Path path = matroska();
    var tag = typedMatroska();
    tag.save(path);
    assertEquals(tag, cz.skyba.taglib.matroska.Tag.read(path));
    var read = TagLib.read(path, ReadOptions.TAGS_ONLY);
    assertEquals("Local", read.tag().title());
    assertFalse(read.properties().containsKey("DATA"));
    assertArrayEquals(MEDIA, read.pictures().getFirst().data());
    new cz.skyba.taglib.matroska.Tag(List.of()).save(path);
    assertTrue(cz.skyba.taglib.matroska.Tag.read(path).simpleTags().isEmpty());
    assertArrayEquals(MEDIA, TagLib.read(path).pictures().getFirst().data());
  }

  @Test
  void containerMatroskaFirstValueAndDirectChildrenMatchNativeSelection() throws Exception {
    Path path = directory.resolve("tag-selection.mka");
    byte[] nested = element(0x67c8, join(element(0x45a3, bytes("ALBUM")), element(0x4487, bytes("nested"))));
    byte[] simple = element(0x67c8, join(element(0x45a3, bytes("TITLE")),
      element(0x4487, bytes("First")), element(0x4487, bytes("Second")), nested));
    Files.write(path, join(element(0x1a45dfa3L, new byte[0]),
      element(0x18538067L, element(0x1254c367L, element(0x7373, simple)))));
    assertEquals("First", TagLib.read(path).tag().title());
    assertFalse(TagLib.read(path).properties().containsKey("ALBUM"));
    assertEquals(1, cz.skyba.taglib.matroska.Tag.read(path).simpleTags().size());
  }

  private static cz.skyba.taglib.matroska.Tag typedMatroska() {
    return new cz.skyba.taglib.matroska.Tag(List.of(
      new SimpleTag("TITLE", new SimpleTag.Text("Local"),
        30, "eng", true, 0, 0, 0, 0),
      new SimpleTag("DATA", new SimpleTag.Binary(new byte[]{0, -1, 7}),
        50, "deu", false, -1L, 2, 3, 4)));
  }

  @Test
  void containerMatroskaChapterEditionsRetainDisplaysFlagsAndOtherMetadata() throws Exception {
    Path path = matroska();
    var original = TagLib.read(path);
    var chapters = typedMatroskaChapters();
    chapters.save(path);
    assertEquals(chapters, Chapters.read(path));
    assertEquals(original.properties(), TagLib.read(path).properties());
    assertEquals(original.pictures(), TagLib.read(path).pictures());
    new Chapters(List.of()).save(path);
    assertTrue(Chapters.read(path).chapterEditionList().isEmpty());
    assertEquals(original.pictures(), TagLib.read(path).pictures());
  }

  @Test
  void containerMatroskaChapterSeekUpdateRetainsClusterAndCuePositions() throws Exception {
    byte[] cluster = element(0x1f43b675L, element(0xa3, MEDIA));
    byte[] cues = element(0x1c53bb6bL, element(0xbb, new byte[0]));
    byte[] head = element(0x114d9b74L, new byte[0]);
    byte[] padding = element(0xec, new byte[250]);
    byte[] children = join(head, padding, cluster, cues);
    Path path = directory.resolve("chapter-seek.mka");
    byte[] prefix = element(0x1a45dfa3L, new byte[0]);
    Files.write(path, join(prefix, element(0x18538067L, children)));
    long originalLength = Files.size(path);
    int clusterOffset = prefix.length + 12 + head.length + padding.length;
    int cueOffset = clusterOffset + cluster.length;
    typedMatroskaChapters().save(path);
    byte[] after = Files.readAllBytes(path);
    assertArrayEquals(cluster, Arrays.copyOfRange(after, clusterOffset, clusterOffset + cluster.length));
    assertArrayEquals(cues, Arrays.copyOfRange(after, cueOffset, cueOffset + cues.length));
    int seekPosition = Mp4Test.index(after, new byte[]{0x53, (byte) 0xac});
    assertTrue(seekPosition > 0);
    int width = after[seekPosition + 2] & 0x7f;
    long value = 0;
    for (int i = 0; i < width; i++) value = (value << 8) | (after[seekPosition + 3 + i] & 255);
    assertEquals(originalLength - prefix.length - 12, value);
    assertEquals(typedMatroskaChapters(), Chapters.read(path));
  }

  @Test
  void containerMatroskaOrphanChaptersSynthesizeMissingUids() throws Exception {
    byte[] chapter = element(0xb6, join(element(0x91, new byte[]{3}),
      element(0x80, join(element(0x85, bytes("orphan")), element(0x437c, bytes("eng"))))));
    Path path = directory.resolve("orphan.mka");
    Files.write(path, join(element(0x1a45dfa3L, new byte[0]), element(0x18538067L,
      element(0x1043a770L, chapter))));
    var editions = Chapters.read(path).chapterEditionList();
    assertEquals(1, editions.size());
    assertTrue(editions.getFirst().isDefault());
    var parsed = editions.getFirst().chapterList().getFirst();
    assertTrue(parsed.uid() < 0, "Synthetic UID must set the unsigned high bit");
    assertEquals(3, parsed.timeStart());
    assertEquals("orphan", parsed.displayList().getFirst().string());
  }

  private static Chapters typedMatroskaChapters() {
    return new Chapters(List.of(new ChapterEdition(List.of(
      new cz.skyba.taglib.matroska.Chapter(1000, 4000, List.of(
        new cz.skyba.taglib.matroska.Chapter.Display("Začátek", "ces"),
        new cz.skyba.taglib.matroska.Chapter.Display("Opening", "eng")), 101, true),
      new cz.skyba.taglib.matroska.Chapter(4000, 9000, List.of(
        new cz.skyba.taglib.matroska.Chapter.Display("Second", "eng")), 102, false)), true, true, 9)));
  }

  private Path asf(String name) throws Exception {
    byte[] stream = new byte[78];
    System.arraycopy(HexFormat.of().parseHex("409e69f84d5bcf11a8fd00805f5c442b"), 0, stream, 0, 16);
    put16le(stream, 54, 0x161);
    put16le(stream, 56, 2);
    put32le(stream, 58, 44100);
    byte[] body = join(asfObject("a1dcab8c47a9cf118ee400c00c205365", new byte[80]),
      asfObject("9107dcb7b7a9cf118ee600c00c205365", stream));
    byte[] header = new byte[30];
    System.arraycopy(HexFormat.of().parseHex("3026b2758e66cf11a6d900aa0062ce6c"), 0, header, 0, 16);
    put64le(header, 16, body.length + 30);
    put32le(header, 24, 2);
    header[28] = 1;
    header[29] = 2;
    Path path = directory.resolve(name + ".wma");
    Files.write(path, join(header, body, MEDIA));
    return path;
  }

  private static byte[] asfObject(String guid, byte[] data) throws Exception {
    byte[] header = new byte[24];
    System.arraycopy(HexFormat.of().parseHex(guid), 0, header, 0, 16);
    put64le(header, 16, data.length + 24);
    return join(header, data);
  }

  private Path mp4(String name) throws Exception {
    byte[] offsets = new byte[12];
    put32be(offsets, 4, 1);
    byte[] ftyp = box("ftyp", bytes("M4A \0\0\0\0"));
    byte[] moov = box("moov", box("trak", box("mdia", box("minf", box("stbl", box("stco", offsets))))));
    put32be(moov, Mp4Test.index(moov, bytes("stco")) + 12, ftyp.length + moov.length + 8);
    Path path = directory.resolve(name + ".m4a");
    Files.write(path, join(ftyp, moov, box("mdat", MEDIA)));
    return path;
  }

  private void assertChunkOffset(Path path) throws Exception {
    byte[] bytes = Files.readAllBytes(path);
    assertEquals(Mp4Test.index(bytes, bytes("mdat")) + 4, u32be(bytes, Mp4Test.index(bytes, bytes("stco")) + 12));
  }

  private void assertMedia(Path path) throws Exception {
    byte[] bytes = Files.readAllBytes(path);
    assertArrayEquals(MEDIA, Arrays.copyOfRange(bytes, bytes.length - MEDIA.length, bytes.length));
  }

  private Path matroska() throws Exception {
    byte[] title = element(0x67c8, join(element(0x45a3, bytes("TITLE")), element(0x4487, bytes("Before\0After"))));
    byte[] picture = element(0x61a7, join(element(0x466e, bytes("cover")),
      element(0x4660, "image/é".getBytes(StandardCharsets.ISO_8859_1)), element(0x465c, MEDIA)));
    Path path = directory.resolve("strings.mka");
    Files.write(path, join(element(0x1a45dfa3L, new byte[0]), element(0x18538067L,
      join(element(0x1254c367L, element(0x7373, title)), element(0x1941a469L, picture)))));
    return path;
  }

  private static byte[] element(long id, byte[] data) throws Exception {
    var out = new ByteArrayOutputStream();
    int width = (64 - Long.numberOfLeadingZeros(id) + 7) / 8;
    for (int i = width - 1; i >= 0; i--) out.write((int) (id >>> (i * 8)));
    if (id == 0x18538067L) {
      byte[] length = new byte[8];
      put64be(length, 0, data.length);
      length[0] = 1;
      out.write(length);
    } else if (data.length < 127) out.write(0x80 | data.length);
    else {
      out.write(0x40 | (data.length >>> 8));
      out.write(data.length);
    }
    out.write(data);
    return out.toByteArray();
  }
}
