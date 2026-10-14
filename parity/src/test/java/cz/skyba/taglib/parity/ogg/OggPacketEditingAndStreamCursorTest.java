package cz.skyba.taglib.parity.ogg;


import cz.skyba.taglib.ogg.OggFile;
import cz.skyba.taglib.parity.support.SaveOutcome;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.TagBase;
import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.xiph.OggPages;
import cz.skyba.taglib.internal.xiph.XiphComment;
import cz.skyba.taglib.ogg.PageHeader;
import cz.skyba.taglib.parity.Snapshots;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.FileStream;
import cz.skyba.taglib.toolkit.IOStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("parity")
@org.junit.jupiter.api.Tag("ogg")
final class OggPacketEditingAndStreamCursorTest {
  @TempDir
  Path temp;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Path ORACLE = root().resolve("parity/target/native-build/bin/ogg-file-probe");
  private static final long UNLIMITED = 0xffff_ffffL;

  private static final class RawFile extends OggFile {
    RawFile(IOStream stream) {
      super(stream);
    }

    RawFile(Path path) {
      super(path);
    }

    @Override
    public TagBase tag() {
      return null;
    }

    @Override
    public AudioProperties audioProperties() {
      return null;
    }

    ByteVector limited(int index, long max) {
      return packet(index, max);
    }

    boolean select(ByteVector magic) {
      return selectStream(magic);
    }
  }

  @Test
  void lazilyIndexesContinuedPacketsAndSkipsOtherSerials() throws Exception {
    byte[] source =
      join(
        bytes("leading bytes"),
        page(10, 0, 2, 0, new int[]{3, 255}, join(bytes("id!"), payload(255, 1))),
        page(20, 0, 2, 0, new int[]{7}, bytes("foreign")),
        page(10, 1, 1, 0, new int[]{255}, payload(255, 2)),
        page(20, 1, 4, 20, new int[]{3}, bytes("end")),
        page(10, 2, 1, 8192, new int[]{2, 0, 4}, join(bytes("xy"), bytes("tail"))),
        page(10, 3, 4, 9000, new int[]{3}, bytes("end")),
        page(10, 0, 6, 123, new int[]{5}, bytes("chain")));
    compare(
      source,
      "seek 9\nfirst\npacket 1 "
        + UNLIMITED
        + "\npacket 0 "
        + UNLIMITED
        + "\npacket 2 0\npacket 3 4\npacket 4 "
        + UNLIMITED
        + "\npacket 5 "
        + UNLIMITED
        + "\nlast\nfirst\n",
      "memory",
      "path");
    try (var file = new RawFile(new ByteVectorStream(source))) {
      assertArrayEquals(
        join(payload(255, 1), payload(255, 2), bytes("xy")), file.packet(1).toByteArray());
      assertEquals("tail", file.packet(3).toString());
      assertTrue(file.packet(5).isEmpty(), "EOS stops indexing before a chained stream");
    }
  }

  @Test
  void bosMagicSelectionAndPhysicalHeadersMatchNative() throws Exception {
    byte[] source =
      join(
        page(10, 0, 2, 0, new int[]{4}, bytes("VID!")),
        page(20, 0, 2, 0, new int[]{4}, bytes("AUD!")),
        page(10, 1, 4, 10, new int[]{4}, bytes("vide")),
        page(20, 1, 4, 20, new int[]{4}, bytes("audi")));
    compare(
      source,
      "select 415544\nfirst\nlast\npacket 0 "
        + UNLIMITED
        + "\npacket 1 "
        + UNLIMITED
        + "\npacket 2 "
        + UNLIMITED
        + "\n",
      "memory",
      "path");
    compare(
      source,
      "select 6e6f7065\npacket 0 " + UNLIMITED + "\npacket 1 " + UNLIMITED + "\n",
      "memory");
    compare(source, "select -\npacket 0 " + UNLIMITED + "\n", "memory");
    // Discovery stops at the first non-BOS page, even if a later BOS has the requested magic.
    compare(
      join(
        page(10, 0, 2, 0, new int[]{4}, bytes("VID!")),
        page(10, 1, 0, 10, new int[]{4}, bytes("vide")),
        page(20, 0, 2, 0, new int[]{4}, bytes("AUD!"))),
      "select 415544\npacket 0 " + UNLIMITED + "\n",
      "memory");
  }

  @Test
  void packetLimitsAndFailureScanningStateMatchNative() throws Exception {
    byte[] source =
      join(
        page(10, 0, 2, 0, new int[]{0, 255}, payload(255, 1)),
        page(10, 1, 1, 0, new int[]{1, 4}, join(bytes("x"), bytes("next"))),
        page(10, 2, 4, 100, new int[]{4}, bytes("last")));
    for (long max : new long[]{0, 254, 255, 256, 257, UNLIMITED}) {
      compare(
        source,
        "packet 1 "
          + max
          + "\npacket 0 "
          + UNLIMITED
          + "\npacket 1 "
          + UNLIMITED
          + "\npacket 2 "
          + UNLIMITED
          + "\n",
        "memory");
      compare(source, "packet 1 " + UNLIMITED + "\npacket 1 " + max + "\n", "memory");
    }
    compare(
      source,
      "set 1 61626364\npacket 1 3\npacket 1 4\npacket 1 "
        + UNLIMITED
        + "\nsave\npacket 1 "
        + UNLIMITED
        + "\n",
      "memory");
  }

  @Test
  void stagedReplacementsGrowthShrinkAndMultipleSavesMatchNativeBytesAndCursor() throws Exception {
    byte[] source =
      join(
        page(10, 0, 2, 0, new int[]{3}, bytes("id!")),
        page(10, 1, 0, 20, new int[]{4, 4}, bytes("old!next")),
        page(10, 2, 4, 8192, new int[]{4}, bytes("last")),
        bytes("trailer"));
    for (int size : new int[]{0, 1, 255, 256, 8160, 8161, 65024, 65025}) {
      String replacement = size == 0 ? "-" : encode(payload(size, 7));
      compare(
        source,
        "first\nlast\nset 1 "
          + replacement
          + "\npacket 1 "
          + UNLIMITED
          + "\ndata\nsave\ndata\nfirst\nlast\npacket 1 "
          + UNLIMITED
          + "\npacket 2 "
          + UNLIMITED
          + "\npacket 3 "
          + UNLIMITED
          + "\nset 1 736d616c6c\nsave\ndata\nsave\n",
        "memory",
        "path");
    }
    compare(
      source,
      "set 3 6c61737432\nset 1 "
        + encode(payload(65025, 2))
        + "\nset 2 6e65787432\nset 99 69676e6f726564\nsave\ndata\npacket 2 "
        + UNLIMITED
        + "\npacket 3 "
        + UNLIMITED
        + "\n",
      "memory",
      "path");
  }

  @Test
  void nativeSpanningReplacementAndMultiplexWriteQuirksMatchExactly() throws Exception {
    byte[] source =
      join(
        page(10, 0, 2, 0, new int[]{3, 255}, join(bytes("id!"), payload(255, 1))),
        page(20, 0, 2, 0, new int[]{5}, bytes("other")),
        page(10, 1, 1, 100, new int[]{4, 255}, join(bytes("done"), payload(255, 2))),
        page(20, 1, 0, 20, new int[]{3}, bytes("mux")),
        page(10, 2, 1, 200, new int[]{3, 4}, bytes("endtail")),
        page(20, 2, 4, 30, new int[]{3}, bytes("eos")),
        page(10, 3, 4, 300, new int[]{4}, bytes("last")));
    compare(
      source,
      "set 1 "
        + encode(payload(65025, 8))
        + "\nsave\ndata\npacket 1 "
        + UNLIMITED
        + "\npacket 2 "
        + UNLIMITED
        + "\n",
      "memory",
      "path");
    compare(source, "set 2 6e6577\nsave\ndata\n", "memory", "path");
  }

  @Test
  void invalidHeadersTruncatedPayloadsAndCaptureSearchMatchNative() throws Exception {
    byte[] valid = page(10, 0, 6, 456, new int[]{4}, bytes("test"));
    byte[] unknownVersion = valid.clone();
    unknownVersion[4] = 7;
    unknownVersion[22] ^= 63; // Header parsing intentionally does not validate version or CRC.
    byte[] zeroSegments = Arrays.copyOf(valid, 27);
    zeroSegments[26] = 0;
    byte[] badMagic = valid.clone();
    badMagic[0] = 'X';
    byte[] incomplete = page(10, 0, 6, 0, new int[]{255}, payload(255, 3));
    for (byte[] source :
      List.of(
        new byte[0],
        new byte[26],
        badMagic,
        zeroSegments,
        unknownVersion,
        Arrays.copyOf(valid, 28),
        Arrays.copyOf(valid, 30),
        incomplete,
        join(valid, bytes("OggS trailing false capture")))) {
      compare(
        source,
        "seek 2\nfirst\nlast\npacket 0 "
          + UNLIMITED
          + "\npacket 1 "
          + UNLIMITED
          + "\nset 0 6e6577\nsave\ndata\nfirst\nlast\n",
        "memory",
        "path");
    }
    // A malformed next page stops native renumbering after the already supported packet edit.
    compare(
      join(page(10, 0, 2, 0, new int[]{4}, bytes("test")), bytes("truncated tail")),
      "set 0 " + encode(payload(65025, 4)) + "\nsave\ndata\n",
      "memory",
      "path");
  }

  @Test
  void borrowedOwnershipReadOnlyAndDefensiveValues() throws Exception {
    byte[] source = page(10, 0, 6, 123, new int[]{4}, bytes("test"));
    compare(source, "set 0 6e6577\nsave\npacket 0 " + UNLIMITED + "\ndata\n", "readonly");
    var stream = new ByteVectorStream(source);
    var file = new RawFile(stream);
    ByteVector replacement = new ByteVector("new");
    file.setPacket(0, replacement);
    replacement.set(0, (byte) 'X');
    ByteVector returned = file.packet(0);
    returned.set(0, (byte) 'Y');
    assertEquals("new", file.packet(0).toString());
    file.close();
    assertTrue(stream.isOpen());
    assertTrue(SaveOutcome.save(file));
    assertEquals("new", file.packet(0).toString());
    Path path = Files.write(temp.resolve("owned.ogg"), source);
    var owned = new RawFile(path);
    assertTrue(owned.isOpen());
    owned.close();
    assertFalse(owned.isOpen());
    assertThrows(IllegalArgumentException.class, () -> file.packet(-1));
    assertThrows(IllegalArgumentException.class, () -> file.limited(0, UNLIMITED + 1));
  }

  @Test
  void completePinnedOggFixtureSetMatchesPacketReadsAndRawPacketSaves() throws Exception {
    Path corpus = Snapshots.upstreamSource(root()).resolve("tests/data");
    if (!Files.isDirectory(corpus)) {
      assertFalse(
        Boolean.getBoolean("taglib.requireNative"), "Build the pinned upstream fixture corpus");
      return;
    }
    List<Path> fixtures;
    try (var files = Files.list(corpus)) {
      fixtures =
        files
          .filter(path -> path.getFileName().toString().matches(".*\\.(ogg|oga|opus|spx)$"))
          .sorted()
          .toList();
    }
    assertFalse(fixtures.isEmpty());
    for (Path fixture : fixtures) {
      compare(
        Files.readAllBytes(fixture),
        "first\nlast\npacket 0 "
          + UNLIMITED
          + "\npacket 1 "
          + UNLIMITED
          + "\npacket 2 "
          + UNLIMITED
          + "\npacket 3 "
          + UNLIMITED
          + "\nset 1 6e6577\nsave\ndata\n",
        "memory",
        "path");
    }
  }

  @Test
  void facadePacketReplacementMatchesNativeAcrossDefaultAndStagedPolicies() throws Exception {
    byte[] id = new byte[19];
    System.arraycopy(bytes("OpusHead"), 0, id, 0, 8);
    id[8] = 1;
    id[9] = 2;
    byte[] tags =
      join(
        bytes("OpusTags"),
        XiphComment.empty()
          .render(PropertyMap.builder().put("TITLE", "b".repeat(600)).build(), false));
    byte[] foreign = page(20, 0, 6, 999, new int[]{4}, bytes("mux!"));
    byte[] audio = page(10, 3, 4, 96000, new int[]{4}, bytes("audi"));
    byte[] unusualTail = audio.clone();
    unusualTail[4] = 7;
    unusualTail[5] |= 0x70;
    unusualTail[22] ^= 47;
    List<byte[]> sources =
      List.of(
        join(
          page(10, 0, 2, 0, new int[]{19}, id),
          packetPage(10, 1, 0, 0, tags),
          foreign,
          audio),
        join(
          page(10, 0, 2, 0, new int[]{19}, id),
          page(10, 1, 0, 0, new int[]{255}, Arrays.copyOfRange(tags, 0, 255)),
          foreign,
          packetPage(10, 2, 1, 1234, Arrays.copyOfRange(tags, 255, tags.length)),
          audio),
        join(page(10, 0, 2, 0, new int[]{19}, id), packetPage(10, 1, 4, 96000, tags), foreign),
        join(
          page(10, 0, 2, 0, new int[]{19}, id),
          packetPage(10, 1, 0, 0, tags),
          bytes("malformed tail")),
        join(page(10, 0, 2, 0, new int[]{19}, id), packetPage(10, 1, 0, 0, tags), unusualTail),
        join(
          page(10, 0, 2, 0, new int[]{19}, id),
          packetPage(10, 1, 0, 0, tags),
          Arrays.copyOf(audio, audio.length - 2)));
    for (byte[] source : sources) {
      for (int size : new int[]{5, 100000}) {
        byte[] replacement =
          join(
            bytes("OpusTags"),
            XiphComment.empty()
              .render(PropertyMap.builder().put("TITLE", "x".repeat(size)).build(), false));
        {
          Path javaPath = Files.write(temp.resolve("facade.ogg"), source);
          OggPages.Packet packet;
          try (var input = new BinaryFile(javaPath)) {
            packet = OggPages.headers(input, 64 * 1024 * 1024).packets().get(1);
          }
          OggPages.replacePacket(javaPath, packet, replacement);
          if (available()) {
            Path nativePath = Files.write(temp.resolve("facade-native.ogg"), source);
            Path recipe =
              Files.writeString(
                temp.resolve("facade-recipe.txt"),
                "set 1 " + encode(replacement) + "\nsave\ndata\n");
            var process =
              new ProcessBuilder(
                ORACLE.toString(), "path", nativePath.toString(), recipe.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
            byte[] output = process.getInputStream().readAllBytes();
            assertEquals(0, process.waitFor(), new String(output, StandardCharsets.UTF_8));
            assertArrayEquals(Files.readAllBytes(nativePath), Files.readAllBytes(javaPath));
          }
        }
      }
    }
  }

  private void compare(byte[] source, String recipe, String... modes) throws Exception {
    Path operations = Files.writeString(temp.resolve("recipe.txt"), recipe);
    for (String mode : modes) {
      Path javaPath = Files.write(temp.resolve("java.ogg"), source);
      Path nativePath = Files.write(temp.resolve("native.ogg"), source);
      JsonNode actual = execute(javaPath, source, mode, recipe);
      if (available()) {
        var process =
          new ProcessBuilder(
            ORACLE.toString(), mode, nativePath.toString(), operations.toString())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
        byte[] output = process.getInputStream().readAllBytes();
        assertEquals(0, process.waitFor(), new String(output, StandardCharsets.UTF_8));
        assertEquals(
          JSON.readTree(output),
          actual,
          mode + " recipe: " + recipe.substring(0, Math.min(recipe.length(), 300)));
        if (!mode.equals("memory"))
          assertArrayEquals(Files.readAllBytes(nativePath), Files.readAllBytes(javaPath));
      }
    }
  }

  private static JsonNode execute(Path path, byte[] source, String mode, String recipe)
    throws Exception {
    IOStream stream =
      mode.equals("memory")
        ? new ByteVectorStream(source)
        : mode.equals("readonly") ? new FileStream(path, true) : null;
    try (RawFile file = stream == null ? new RawFile(path) : new RawFile(stream)) {
      var results = new ArrayList<Map<String, Object>>();
      for (String line : recipe.lines().toList()) {
        String[] operation = line.split(" ");
        Object value =
          switch (operation[0]) {
            case "packet" -> encode(
              file.limited(Integer.parseInt(operation[1]), Long.parseLong(operation[2]))
                .toByteArray());
            case "set" -> {
              file.setPacket(
                Integer.parseInt(operation[1]), new ByteVector(decode(operation[2])));
              yield null;
            }
            case "select" -> file.select(new ByteVector(decode(operation[1])));
            case "seek" -> {
              file.seek(Long.parseLong(operation[1]));
              yield null;
            }
            case "save" -> SaveOutcome.save(file);
            case "first" -> header(file.firstPageHeader());
            case "last" -> header(file.lastPageHeader());
            case "data" -> encode(
              stream instanceof ByteVectorStream memory
                ? memory.data().toByteArray()
                : Files.readAllBytes(path));
            default -> throw new IllegalArgumentException(line);
          };
        var result = new LinkedHashMap<String, Object>();
        result.put("operation", operation[0]);
        result.put("value", value);
        result.put("cursor", file.tell());
        result.put("length", file.length());
        results.add(result);
      }
      return JSON.readTree(JSON.writeValueAsBytes(results));
    } finally {
      if (stream != null) stream.close();
    }
  }

  private static Map<String, Object> header(PageHeader header) {
    if (header == null) return null;
    var result = new LinkedHashMap<String, Object>();
    result.put("renderHex", encode(header.render().toByteArray()));
    result.put("size", header.size());
    result.put("dataSize", header.dataSize());
    result.put("serial", header.streamSerialNumber());
    result.put("sequence", header.pageSequenceNumber());
    result.put("granule", header.absoluteGranularPosition());
    result.put("first", header.firstPageOfStream());
    result.put("last", header.lastPageOfStream());
    return result;
  }

  // Build source pages independently of the Java Page pagination/render implementations.
  private static byte[] page(
    int serial, int sequence, int flags, long granule, int[] laces, byte[] payload) {
    byte[] result = new byte[27 + laces.length + payload.length];
    System.arraycopy(bytes("OggS"), 0, result, 0, 4);
    result[5] = (byte) flags;
    for (int i = 0; i < 8; i++) result[6 + i] = (byte) (granule >>> (8 * i));
    for (int i = 0; i < 4; i++) {
      result[14 + i] = (byte) (serial >>> (8 * i));
      result[18 + i] = (byte) (sequence >>> (8 * i));
    }
    result[26] = (byte) laces.length;
    for (int i = 0; i < laces.length; i++) result[27 + i] = (byte) laces[i];
    System.arraycopy(payload, 0, result, 27 + laces.length, payload.length);
    int checksum = 0;
    for (byte value : result) {
      checksum ^= (value & 255) << 24;
      for (int bit = 0; bit < 8; bit++)
        checksum = (checksum << 1) ^ (checksum < 0 ? 0x04c11db7 : 0);
    }
    for (int i = 0; i < 4; i++) result[22 + i] = (byte) (checksum >>> (8 * i));
    return result;
  }

  private static byte[] join(byte[]... values) {
    var output = new ByteArrayOutputStream();
    for (byte[] value : values) output.writeBytes(value);
    return output.toByteArray();
  }

  private static byte[] packetPage(
    int serial, int sequence, int flags, long granule, byte[] packet) {
    int[] laces = new int[packet.length / 255 + 1];
    Arrays.fill(laces, 255);
    laces[laces.length - 1] = packet.length % 255;
    return page(serial, sequence, flags, granule, laces, packet);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] payload(int size, int seed) {
    byte[] value = new byte[size];
    for (int i = 0; i < size; i++) value[i] = (byte) ((i + seed * 17) % 251);
    return value;
  }

  private static String encode(byte[] value) {
    return HexFormat.of().formatHex(value);
  }

  private static byte[] decode(String value) {
    return value.equals("-") ? new byte[0] : HexFormat.of().parseHex(value);
  }

  private static boolean available() {
    boolean present = Files.isExecutable(ORACLE);
    if (Boolean.getBoolean("taglib.requireNative"))
      assertTrue(present, "Run ./mvnw -Pparity verify");
    return present;
  }

  private static Path root() {
    Path path = Path.of("").toAbsolutePath();
    while (path != null && !Files.isDirectory(path.resolve("parity/native")))
      path = path.getParent();
    return Objects.requireNonNull(path);
  }
}
