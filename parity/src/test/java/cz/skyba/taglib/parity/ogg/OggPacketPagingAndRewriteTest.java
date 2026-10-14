package cz.skyba.taglib.parity.ogg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.skyba.taglib.internal.Binary;
import cz.skyba.taglib.ogg.Page;
import cz.skyba.taglib.ogg.PageHeader;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("parity")
@org.junit.jupiter.api.Tag("ogg")
final class OggPacketPagingAndRewriteTest {
  @TempDir
  Path temp;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Path ORACLE = root().resolve("parity/target/native-build/bin/audio-format-oracle");

  @Test
  void paginationMatchesNativeBoundariesFlagsAndEmptyPacketQuirks() throws Exception {
    List<int[]> groups =
      new ArrayList<>(
        List.of(
          new int[0],
          new int[]{0},
          new int[]{1},
          new int[]{254, 255, 256},
          new int[]{8160},
          new int[]{8161},
          new int[]{65024},
          new int[]{65025},
          new int[]{0, 10, 0, 9000, 0}));
    groups.add(new int[255]);
    groups.add(new int[256]);
    for (Page.PaginationStrategy strategy : Page.PaginationStrategy.values()) {
      for (int[] sizes : groups) {
        for (int flags = 0; flags < 4; flags++) {
          boolean continued = (flags & 1) != 0, completed = (flags & 2) == 0;
          var packets = packets(sizes);
          var pages =
            Page.paginate(
              packets, strategy, 0xfedcba98L, flags == 0 ? 0 : 12, continued, completed, true);
          JsonNode actual =
            tree(pages.stream().map(OggPacketPagingAndRewriteTest::snapshot).toList());
          Path recipe =
            recipe(
              strategy.ordinal(),
              0xfedcba98L,
              flags == 0 ? 0 : 12,
              continued,
              completed,
              true,
              sizes);
          if (available()) {
            assertEquals(
              nativeResult("ogg-paginate", recipe),
              actual,
              recipe.toString() + " " + Arrays.toString(sizes));
          }
          // Rendering must carry an Ogg checksum independently verified by a bitwise
          // implementation.
          for (Page p : pages) {
            byte[] rendered = p.render().toByteArray();
            long checksum = Binary.u32le(rendered, 22);
            Arrays.fill(rendered, 22, 26, (byte) 0);
            assertEquals(checksum, Integer.toUnsignedLong(crc(rendered)));
          }
        }
      }
    }
  }

  @Test
  void parsedHeadersAndShortPayloadsMatchNative() throws Exception {
    var base =
      Page.paginate(
          packets(new int[]{255, 1, 0}), Page.PaginationStrategy.SinglePagePerGroup, 123, 3)
        .getFirst()
        .render()
        .toByteArray();
    var unknownVersion = base.clone();
    unknownVersion[4] = 7;
    unknownVersion[5] = (byte) 0xf9;
    var zeroSegments = Arrays.copyOf(base, 27);
    zeroSegments[26] = 0;
    var badMagic = base.clone();
    badMagic[0] = 'X';
    var allContinued = Arrays.copyOf(base, 30);
    allContinued[26] = 3;
    Arrays.fill(allContinued, 27, 30, (byte) 255);
    for (byte[] bytes :
      List.of(
        base,
        unknownVersion,
        zeroSegments,
        badMagic,
        new byte[0],
        Arrays.copyOf(base, 26),
        Arrays.copyOf(base, 28),
        Arrays.copyOf(base, 34),
        allContinued)) {
      Path raw = Files.write(temp.resolve("page.ogg"), bytes);
      var source = new ByteVectorStream(bytes);
      var p = new Page(source, 0);
      JsonNode actual = tree(snapshot(p));
      if (available()) {
        assertEquals(nativeResult("ogg-read", raw), actual, HexFormat.of().formatHex(bytes));
      }
      assertEquals(actual, tree(snapshot(new Page(raw, 0))));
      assertTrue(source.isOpen());
    }
  }

  @Test
  void setterStateAndRenderingMatchNativeEvenBeyondOneLacingTable() throws Exception {
    for (int[] sizes :
      List.of(new int[0], new int[]{0}, new int[]{256}, new int[]{65025, 255})) {
      for (int flags = 0; flags < 4; flags++) {
        var h = new PageHeader();
        h.setPacketSizes(Arrays.stream(sizes).boxed().toList());
        h.setFirstPageOfStream(true);
        h.setFirstPacketContinued((flags & 1) != 0);
        h.setLastPacketCompleted((flags & 2) == 0);
        h.setLastPageOfStream(true);
        h.setStreamSerialNumber(0xfedcba98L);
        h.setPageSequenceNumber(flags == 0 ? 0 : -1);
        h.setAbsoluteGranularPosition(-991);
        if (available()) {
          assertEquals(
            nativeResult(
              "ogg-header",
              recipe(
                1,
                0xfedcba98L,
                flags == 0 ? 0 : -1,
                (flags & 1) != 0,
                (flags & 2) == 0,
                true,
                sizes)),
            tree(header(h)));
        }
        assertFalse(h.isValid());
        assertEquals(0, h.size());
        assertEquals(0, h.dataSize());
      }
    }
  }

  static Map<String, Object> header(PageHeader h) {
    var m = new LinkedHashMap<String, Object>();
    m.put("valid", h.isValid());
    m.put("size", h.size());
    m.put("dataSize", h.dataSize());
    m.put("firstContinued", h.firstPacketContinued());
    m.put("lastCompleted", h.lastPacketCompleted());
    m.put("firstPage", h.firstPageOfStream());
    m.put("lastPage", h.lastPageOfStream());
    m.put("granule", h.absoluteGranularPosition());
    m.put("serial", h.streamSerialNumber());
    m.put("sequence", h.pageSequenceNumber());
    m.put("packetSizes", h.packetSizes());
    m.put("renderHex", HexFormat.of().formatHex(h.render().toByteArray()));
    return m;
  }

  static Map<String, Object> snapshot(Page p) {
    var m = new LinkedHashMap<String, Object>();
    m.put("offset", p.fileOffset());
    m.put("size", p.size());
    m.put("firstPacketIndex", p.firstPacketIndex());
    m.put("header", header(p.header()));
    m.put("renderHex", HexFormat.of().formatHex(p.render().toByteArray()));
    m.put(
      "packetsHex",
      p.packets().stream().map(b -> HexFormat.of().formatHex(b.toByteArray())).toList());
    p.setFirstPacketIndex(7);
    var flags = new ArrayList<Integer>();
    for (int n = 6; n <= 8 + p.packetCount(); n++) {
      flags.add(p.containsPacket(n));
    }
    m.put("contains", flags);
    return m;
  }

  private Path recipe(
    int strategy,
    long serial,
    int sequence,
    boolean continued,
    boolean completed,
    boolean last,
    int[] sizes)
    throws Exception {
    var text =
      new StringBuilder()
        .append(strategy)
        .append(' ')
        .append(serial)
        .append(' ')
        .append(sequence)
        .append(' ')
        .append(continued ? 1 : 0)
        .append(' ')
        .append(completed ? 1 : 0)
        .append(' ')
        .append(last ? 1 : 0)
        .append(' ')
        .append(sizes.length);
    for (int size : sizes) {
      text.append(' ').append(size);
    }
    return Files.writeString(temp.resolve("recipe.txt"), text.toString());
  }

  private static List<ByteVector> packets(int[] sizes) {
    var packets = new ArrayList<ByteVector>();
    for (int i = 0; i < sizes.length; i++) {
      byte[] p = new byte[sizes[i]];
      for (int j = 0; j < p.length; j++) {
        p[j] = (byte) ((i * 17 + j) % 251);
      }
      packets.add(new ByteVector(p));
    }
    return packets;
  }

  private static JsonNode tree(Object value) throws Exception {
    return JSON.readTree(JSON.writeValueAsBytes(value));
  }

  private static int crc(byte[] bytes) {
    int crc = 0;
    for (byte b : bytes) {
      crc ^= (b & 255) << 24;
      for (int i = 0; i < 8; i++) {
        crc = (crc << 1) ^ (crc < 0 ? 0x04c11db7 : 0);
      }
    }
    return crc;
  }

  private static boolean available() {
    boolean present = Files.isExecutable(ORACLE);
    if (Boolean.getBoolean("taglib.requireNative")) {
      assertTrue(present, "Build audio-format-oracle");
    }
    return present;
  }

  private static JsonNode nativeResult(String command, Path path) throws Exception {
    var p =
      new ProcessBuilder(ORACLE.toString(), command, path.toString())
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start();
    byte[] out = p.getInputStream().readAllBytes();
    assertEquals(0, p.waitFor(), new String(out, StandardCharsets.UTF_8));
    return JSON.readTree(out);
  }

  private static Path root() {
    Path p = Path.of("").toAbsolutePath();
    while (p != null && !Files.isDirectory(p.resolve("parity/native"))) {
      p = p.getParent();
    }
    return Objects.requireNonNull(p);
  }
}
