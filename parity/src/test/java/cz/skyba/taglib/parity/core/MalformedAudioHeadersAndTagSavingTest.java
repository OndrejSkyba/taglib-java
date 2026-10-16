package cz.skyba.taglib.parity.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.skyba.taglib.*;
import cz.skyba.taglib.flac.FlacFile;
import cz.skyba.taglib.internal.Binary;
import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.xiph.OggPages;
import cz.skyba.taglib.internal.xiph.XiphComment;
import cz.skyba.taglib.parity.Snapshots;
import cz.skyba.taglib.riff.wav.WavFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Minimized cases derived independently from the released native format implementations.
 */
final class MalformedAudioHeadersAndTagSavingTest {
  @TempDir
  Path directory;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Path ROOT = root();
  private static final Path ORACLE = ROOT.resolve("parity/target/native-build/bin/oracle");
  private static final Path API_ORACLE = ROOT.resolve("parity/target/native-build/bin/audio-format-oracle");

  @Test
  @org.junit.jupiter.api.Tag("parity")
  @org.junit.jupiter.api.Tag("core")
  void xmRejectsInvalidEscapeMarkerWithoutModifyingInput() throws Exception {
    byte[] b = xm();
    b[37] = 7;
    Path p = fixture("invalid-marker.xm", b);
    rejects(p);
    assertThrows(cz.skyba.taglib.TagLibException.class, () -> TagLib.write(p, PropertyMap.EMPTY));
    assertArrayEquals(b, Files.readAllBytes(p));
  }

  @Test
  @org.junit.jupiter.api.Tag("parity")
  @org.junit.jupiter.api.Tag("core")
  void malformedModChannelIdentifiersAreNotNoiseTracker() throws Exception {
    for (String id : List.of("FLT?", "TDZ?", "xCHN", "1xCH", "x1CN")) {
      byte[] b = mod();
      System.arraycopy(Binary.bytes(id), 0, b, 1080, 4);
      rejects(fixture(id.replace('?', '_') + ".mod", b));
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("parity")
  @org.junit.jupiter.api.Tag("core")
  void trackerOrderListsAreValidatedEvenWithoutInstruments() throws Exception {
    byte[] it = it();
    Binary.put16le(it, 32, 1);
    rejects(fixture("missing-orders.it", it));
    byte[] s3m = s3m();
    Binary.put16le(s3m, 32, 1);
    rejects(fixture("missing-orders.s3m", s3m));
  }

  @Test
  @org.junit.jupiter.api.Tag("parity")
  @org.junit.jupiter.api.Tag("core")
  void itMessageKeepsLatin1FFWhileInstrumentFillUsesSpaces() throws Exception {
    byte[] b = Arrays.copyOf(it(), 198);
    Binary.put16le(b, 46, 1);
    Binary.put16le(b, 54, 6);
    Binary.put32le(b, 56, 192);
    b[192] = 'A';
    b[193] = (byte) 255;
    b[194] = '\r';
    b[195] = 'B';
    Path p = fixture("latin1.it", b);
    assertEquals("Aÿ\nB", TagLib.read(p).properties().first("COMMENT"));
    matchesNative(p);
  }

  @Test
  @org.junit.jupiter.api.Tag("parity")
  @org.junit.jupiter.api.Tag("core")
  void fixedTrackerNameIsAcceptedButOnlyXmPersistsIt() throws Exception {
    for (var e : Map.of("mod", mod(), "it", it(), "s3m", s3m(), "xm", xm()).entrySet()) {
      Path p = fixture("tracker." + e.getKey(), e.getValue());
      Path n = fixture("native-tracker." + e.getKey(), e.getValue());
      String original = TagLib.read(p).properties().first("TRACKERNAME");
      TagLib.write(
        p,
        TagLib.read(p).properties().toBuilder()
          .put("TRACKERNAME", "Replacement tracker")
          .build());
      assertEquals(
        e.getKey().equals("xm") ? "Replacement tracker" : original,
        TagLib.read(p).properties().first("TRACKERNAME"));
      if (available(API_ORACLE)) {
        assertTrue(command(API_ORACLE, "tracker", n).path("saved").asBoolean());
        assertArrayEquals(Files.readAllBytes(n), Files.readAllBytes(p));
      }
      matchesNative(p);
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("parity")
  @org.junit.jupiter.api.Tag("core")
  void shortenBoundsPcmArithmeticAndKeepsNativeVerbatimTraversal() throws Exception {
    for (int[] spec :
      new int[][]{
        {44100, 16, 2, 176400, 16},
        {4800000, 65535, 8, 1000, 16},
        {1, 16, 2, -1, 16},
        {44100, 16, 2, 176400, 18}
      }) {
      byte[] wave = wave(spec[0], spec[1], spec[2], spec[3], spec[4]);
      Path p = fixture("pcm-" + spec[0] + "-" + spec[4] + ".shn", shorten(wave, spec[2]));
      AudioProperties a = TagLib.read(p).audioProperties();
      if (spec[0] == 4800000) {
        assertEquals(0, a.bitrate());
      }
      if (spec[0] == 1) {
        assertEquals(0, a.lengthInMilliseconds());
      }
      if (spec[4] == 18) {
        assertEquals(0, a.lengthInMilliseconds());
      }
      matchesNative(p);
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("parity")
  @org.junit.jupiter.api.Tag("core")
  void shortenRejectsAiffTrailingFragmentAndSubHertzRate() throws Exception {
    byte[] aiff = new byte[46];
    System.arraycopy(Binary.bytes("FORM"), 0, aiff, 0, 4);
    System.arraycopy(Binary.bytes("AIFFCOMM"), 0, aiff, 8, 8);
    Binary.put32be(aiff, 16, 18);
    Binary.put16be(aiff, 20, 2);
    Binary.put32be(aiff, 22, 44100);
    Binary.put16be(aiff, 26, 16);
    Binary.put16be(aiff, 28, 0x400e);
    Binary.put64be(aiff, 30, 0xac44000000000000L);
    // Eight trailing bytes form an empty unknown chunk, and are accepted.
    System.arraycopy(Binary.bytes("JUNK"), 0, aiff, 38, 4);
    matchesNative(fixture("aiff.shn", shorten(aiff, 2)));
    rejects(fixture("aiff-tail.shn", shorten(Arrays.copyOf(aiff, 44), 2)), java.io.UncheckedIOException.class);
    Binary.put16be(aiff, 28, 0x3ffe);
    rejects(fixture("aiff-rate.shn", shorten(aiff, 2)), java.io.UncheckedIOException.class);
  }

  @Test
  @org.junit.jupiter.api.Tag("parity")
  @org.junit.jupiter.api.Tag("core")
  void speexUsesFramingByteForGenericAndPhysicalEdits() throws Exception {
    byte[] id = new byte[80];
    System.arraycopy(Binary.bytes("Speex   "), 0, id, 0, 8);
    Binary.put32le(id, 28, 1);
    Binary.put32le(id, 32, 80);
    Binary.put32le(id, 36, 8000);
    Binary.put32le(id, 48, 1);
    Binary.put32le(id, 52, 15000);
    Binary.put32le(id, 56, 160);
    Binary.put32le(id, 64, 1);
    byte[] comment =
      XiphComment.empty().render(PropertyMap.builder().put("TITLE", "Before").build(), false);
    byte[] bytes =
      join(
        OggPages.page(5, 0, 2, 0, new int[]{80}, id),
        OggPages.page(5, 1, 0, 0, new int[]{comment.length}, comment),
        OggPages.page(5, 2, 4, 8000, new int[]{2}, new byte[]{4, 5}));
    for (boolean physical : List.of(false, true)) {
      Path p = fixture(physical ? "physical.spx" : "generic.spx", bytes);
      if (physical) {
        var d = MetadataDocument.open(p);
        var e =
          d.entries().stream()
            .filter(x -> x.standardKey().equals("TITLE"))
            .findFirst()
            .orElseThrow();
        d.apply(List.of(new MetadataChange.Update(e.id(), e.key(), List.of("Changed"))));
        d.save();
      } else {
        TagLib.write(p, PropertyMap.builder().put("TITLE", "Changed").build());
      }
      try (var f = new BinaryFile(p)) {
        byte[] packet = OggPages.headers(f, 65536).packets().get(1).data();
        assertEquals(1, packet[packet.length - 1]);
      }
      matchesNative(p);
    }
    if (available(ORACLE)) {
      Path n = fixture("native.spx", bytes);
      command(ORACLE, "write", n);
      try (var f = new BinaryFile(n)) {
        byte[] packet = OggPages.headers(f, 65536).packets().get(1).data();
        assertEquals(1, packet[packet.length - 1]);
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("parity")
  @org.junit.jupiter.api.Tag("core")
  void flacBroadcastPayloadsAndStreamInfoMatchCppValues() throws Exception {
    byte[] info = new byte[34];
    Binary.put64be(info, 10, ((long) 44100 << 44) | (1L << 41) | (15L << 36) | 88200);
    for (int i = 0; i < 16; i++) {
      info[18 + i] = (byte) i;
    }
    byte[] audio = new byte[2048];
    new Random(456).nextBytes(audio);
    byte[] unknown = flacBlock(2, Binary.bytes("testOpaque data"));
    byte[] source =
      join(
        Binary.bytes("fLaC"),
        flacBlock(0, info),
        flacBlock(2, Binary.bytes("iXMLFirst")),
        flacBlock(2, Binary.bytes("iXMLDuplicate")),
        flacBlock(2, join(Binary.bytes("bext"), new byte[]{7, 8})),
        unknown,
        flacBlock(
          0x84,
          XiphComment.empty()
            .render(PropertyMap.builder().put("TITLE", "Before").build(), false)),
        audio);
    {
      Path p = fixture("broadcast.flac", source);
      Path n = fixture("native-broadcast.flac", source);
      var file = new FlacFile(p);
      assertEquals("First", file.iXMLData());
      assertArrayEquals(new byte[]{7, 8}, file.BEXTData());
      assertTrue(file.hasiXMLData());
      assertTrue(file.hasBEXTData());
      assertEquals(88200, file.detailedProperties().sampleFrames());
      byte[] md5 = file.detailedProperties().signature();
      md5[0] = 99;
      assertEquals(0, file.detailedProperties().signature()[0]);
      if (available(API_ORACLE)) {
        assertEquals(
          "First",
          new String(
            HexFormat.of()
              .parseHex(command(API_ORACLE, "flac-read", p).path("xmlHex").asText()),
            StandardCharsets.UTF_8));
      }
      file.setiXMLData("<BWFXML>Žluť</BWFXML>");
      file.setBEXTData(new byte[]{0, (byte) 255, 1, 2});
      file.save();
      assertArrayEquals(
        audio,
        Arrays.copyOfRange(
          Files.readAllBytes(p), (int) Files.size(p) - audio.length, (int) Files.size(p)));
      assertTrue(contains(Files.readAllBytes(p), unknown));
      if (available(API_ORACLE)) {
        JsonNode expected = command(API_ORACLE, "flac-set", n);
        assertEquals(expected, command(API_ORACLE, "flac-read", p));
        assertEquals(88200, expected.path("sampleFrames").asLong());
        assertEquals(
          HexFormat.of().formatHex(file.detailedProperties().signature()),
          expected.path("signature").asText());
      }
      file.setiXMLData("");
      file.setBEXTData(new byte[0]);
      file.save();
      assertFalse(file.hasiXMLData());
      assertFalse(file.hasBEXTData());
      if (available(API_ORACLE)) {
        assertEquals(command(API_ORACLE, "flac-clear", n), command(API_ORACLE, "flac-read", p));
      }
      matchesNative(p);
    }
  }

  @Test
  void flacLiveSessionPreservesExternalAppendsAndOwnsBinaryPayloads() throws Exception {
    byte[] source = join(Binary.bytes("fLaC"), flacBlock(0x80, new byte[34]), new byte[]{1, 2});
    Path p = fixture("stale.flac", source);
    var file = new FlacFile(p);
    byte[] bext = {9, 8};
    file.setBEXTData(bext);
    bext[0] = 0;
    assertEquals(9, file.BEXTData()[0]);
    byte[] view = file.BEXTData();
    view[0] = 0;
    assertEquals(9, file.BEXTData()[0]);
    Files.write(p, new byte[]{3}, StandardOpenOption.APPEND);
    byte[] changed = Files.readAllBytes(p);
    file.save();
    assertArrayEquals(
      new byte[]{1, 2, 3},
      Arrays.copyOfRange(Files.readAllBytes(p), (int) Files.size(p) - 3, (int) Files.size(p)));
    file.close();
  }

  @Test
  @org.junit.jupiter.api.Tag("parity")
  @org.junit.jupiter.api.Tag("core")
  void flacUnchangedSaveSeparatesPhysicalXiphFromId3Fallback() throws Exception {
    PropertyMap fallback =
      PropertyMap.builder()
        .put("TITLE", List.of("First", "Second"))
        .put("COMMENT", "ID3 comment")
        .put("DATE", "2026-10-03")
        .put("CUSTOM", "Do not copy")
        .build();
    byte[] id3 = cz.skyba.taglib.internal.id3.Id3v2.render(fallback, new byte[0]);
    for (boolean hasXiph : List.of(false, true)) {
      byte[] source =
        join(
          id3,
          Binary.bytes("fLaC"),
          flacBlock(hasXiph ? 0 : 0x80, new byte[34]),
          hasXiph
            ? flacBlock(0x84, XiphComment.empty().render(PropertyMap.EMPTY, false))
            : new byte[0]);
      {
        Path p = fixture("mixed-" + hasXiph + ".flac", source),
          n = fixture("native-mixed-" + hasXiph + ".flac", source);
        var f = new FlacFile(p);
        assertEquals(hasXiph, f.hasXiphComment());
        f.save();
        assertEquals(
          hasXiph
            ? PropertyMap.EMPTY
            : PropertyMap.builder()
            .put("TITLE", "First / Second")
            .put("COMMENT", "ID3 comment")
            .put("DATE", "2026")
            .build(),
          cz.skyba.taglib.internal.codec.FlacCodec.details(p, ReadOptions.DEFAULT).xiphProperties());
        assertArrayEquals(id3, Arrays.copyOf(Files.readAllBytes(p), id3.length));
        if (available(API_ORACLE)) {
          command(API_ORACLE, "flac-save", n);
          assertEquals(command(ORACLE, "snapshot", n), command(ORACLE, "snapshot", p));
          assertEquals(
            cz.skyba.taglib.internal.codec.FlacCodec.details(n, ReadOptions.DEFAULT).xiphProperties(),
            cz.skyba.taglib.internal.codec.FlacCodec.details(p, ReadOptions.DEFAULT).xiphProperties());
        }
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("parity")
  @org.junit.jupiter.api.Tag("core")
  void flacPictureParseRenderAndRejectedPropertyKeysMatchNative() throws Exception {
    var picture = new cz.skyba.taglib.flac.Picture();
    picture.setType(cz.skyba.taglib.flac.Picture.Type.FrontCover);
    picture.setMimeType("image/png");
    picture.setDescription("Cover Žluť");
    picture.setWidth(300);
    picture.setHeight(200);
    picture.setColorDepth(24);
    picture.setNumColors(0);
    picture.setData(new byte[]{0, (byte) 255, 8, 9});
    byte[] valid = picture.render();
    byte[] tooShort = new byte[31];
    byte[] hugeMime = valid.clone();
    Binary.put32be(hugeMime, 4, 0xffffffffL);
    byte[] missingData = Arrays.copyOf(valid, valid.length - 1);
    for (byte[] payload : List.of(valid, tooShort, hugeMime, missingData)) {
      var parsed = new cz.skyba.taglib.flac.Picture();
      parsed.setDescription("old description");
      parsed.setData(Binary.bytes("old"));
      boolean ok = parsed.parse(payload);
      Path raw = fixture("picture.bin", payload);
      if (available(API_ORACLE)) {
        var nativeResult = command(API_ORACLE, "picture", raw);
        assertEquals(nativeResult.path("parsed").asBoolean(), ok);
        assertEquals(
          nativeResult.path("renderHex").asText(), HexFormat.of().formatHex(parsed.render()));
      }
    }
    Path flac = fixture("keys.flac", join(Binary.bytes("fLaC"), flacBlock(0x80, new byte[34])));
    var f = new FlacFile(flac);
    var p = PropertyMap.builder();
    for (String key : List.of("", "=", "éx", "~", "}", "TITLE", "EMPTY")) {
      p.put(key, key.equals("EMPTY") ? List.of() : List.of("value"));
    }
    var rejected = f.replaceProperties(p.build()).rejected();
    assertEquals(Set.of("TITLE", "}"), f.properties().asMap().keySet());
    assertEquals(Set.of("", "=", "éX", "~"), rejected.asMap().keySet());
    if (available(API_ORACLE)) {
      var expected = command(API_ORACLE, "flac-keys", flac);
      assertEquals(
        expected.path("acceptedKeysHex"),
        JSON.valueToTree(
          f.properties().asMap().keySet().stream()
            .map(k -> HexFormat.of().formatHex(k.getBytes(StandardCharsets.UTF_8)))
            .toList()));
      assertEquals(
        expected.path("rejectedKeysHex"),
        JSON.valueToTree(
          rejected.asMap().keySet().stream()
            .map(k -> HexFormat.of().formatHex(k.getBytes(StandardCharsets.UTF_8)))
            .toList()));
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("parity")
  @org.junit.jupiter.api.Tag("core")
  void wavBroadcastPayloadEditingKeepsTagChunksAndMatchesNative() throws Exception {
    byte[] samples = new byte[2000];
    new Random(881).nextBytes(samples);
    byte[] fmt = Arrays.copyOfRange(wave(44100, 16, 2, samples.length, 16), 20, 36);
    byte[] unknown = riffChunk("JUNK", new byte[]{1, 2, 3});
    byte[] info =
      riffChunk("LIST", join(Binary.bytes("INFO"), riffChunk("INAM", Binary.bytes("Before\0"))));
    byte[] source =
      join(
        Binary.bytes("RIFF"),
        new byte[4],
        Binary.bytes("WAVE"),
        riffChunk("fmt ", fmt),
        riffChunk("iXML", Binary.bytes("First")),
        riffChunk("iXML", Binary.bytes("Last")),
        riffChunk("bext", new byte[]{8, 9}),
        unknown,
        info,
        riffChunk("data", samples));
    Binary.put32le(source, 4, source.length - 8);
    {
      Path p = fixture("payload.wav", source), n = fixture("native-payload.wav", source);
      var f = new WavFile(p);
      assertEquals("Last", f.iXMLData());
      assertTrue(f.hasInfoTag());
      assertFalse(f.hasId3v2Tag());
      assertEquals(500, f.detailedProperties().sampleFrames());
      assertEquals(1, f.detailedProperties().format());
      f.setiXMLData("<BWFXML>Žluť</BWFXML>");
      f.setBEXTData(new byte[]{0, (byte) 255, 1, 2});
      f.save();
      assertTrue(contains(Files.readAllBytes(p), unknown));
      assertTrue(contains(Files.readAllBytes(p), info));
      assertTrue(contains(Files.readAllBytes(p), riffChunk("data", samples)));
      assertFalse(f.hasId3v2Tag());
      if (available(API_ORACLE)) {
        assertEquals(command(API_ORACLE, "wav-set", n), command(API_ORACLE, "wav-read", p));
      }
      matchesNative(p);
      f.setiXMLData("");
      f.setBEXTData(new byte[0]);
      f.save();
      assertFalse(f.hasiXMLData());
      assertFalse(f.hasBEXTData());
      if (available(API_ORACLE)) {
        assertEquals(command(API_ORACLE, "wav-clear", n), command(API_ORACLE, "wav-read", p));
      }
      matchesNative(p);
      Files.write(p, new byte[]{1}, StandardOpenOption.APPEND);
      byte[] changed = Files.readAllBytes(p);
      f.save();
      assertArrayEquals(changed, Files.readAllBytes(p));
      f.close();
    }
  }

  private static byte[] riffChunk(String id, byte[] payload) {
    byte[] b = new byte[8 + payload.length + (payload.length & 1)];
    System.arraycopy(Binary.bytes(id), 0, b, 0, 4);
    Binary.put32le(b, 4, payload.length);
    System.arraycopy(payload, 0, b, 8, payload.length);
    return b;
  }

  private static byte[] flacBlock(int type, byte[] data) {
    return join(
      new byte[]{
        (byte) type, (byte) (data.length >>> 16), (byte) (data.length >>> 8), (byte) data.length
      },
      data);
  }

  private static boolean contains(byte[] bytes, byte[] value) {
    outer:
    for (int i = 0; i <= bytes.length - value.length; i++) {
      for (int j = 0; j < value.length; j++) {
        if (bytes[i + j] != value[j]) {
          continue outer;
        }
      }
      return true;
    }
    return false;
  }

  @Test
  @org.junit.jupiter.api.Tag("parity")
  @org.junit.jupiter.api.Tag("core")
  void riffMalformedWritesFollowNativeIncludingHiddenTagsAndInvalidTails() throws Exception {
    for (boolean aiff : List.of(false, true)) {
      for (int kind = 0; kind < 3; kind++) {
        byte[] b =
          join(
            Binary.bytes(aiff ? "FORM" : "RIFF"),
            new byte[4],
            Binary.bytes(aiff ? "AIFF" : "WAVE"),
            kind == 2
              ? new byte[0]
              : join(Binary.bytes(aiff ? "SSND" : "data"), new byte[4], new byte[]{1, 2, 3}),
            kind == 1 ? new byte[]{0, 1, 2, 3, 4, 5, 6, 7} : new byte[0]);
        if (aiff) {
          Binary.put32be(b, 4, b.length - 8);
          if (kind != 2) {
            Binary.put32be(b, 16, kind == 0 ? 1000 : 3);
          }
        } else {
          Binary.put32le(b, 4, b.length - 8);
          if (kind != 2) {
            Binary.put32le(b, 16, kind == 0 ? 1000 : 3);
          }
        }
        {
          String suffix = (aiff ? ".aiff" : ".wav");
          Path p = fixture("malformed-" + kind + suffix, b),
            n = fixture("native-malformed-" + kind + suffix, b);
          assertDoesNotThrow(() -> TagLib.read(p));
          TagLib.write(p, PropertyMap.builder().put("TITLE", "Must remain reachable").build());
          byte[] after = Files.readAllBytes(p);
          if (kind == 2) {
            assertArrayEquals(b, after); // No recognized anchor: native save does nothing.
          } else {
            assertArrayEquals(new byte[]{1, 2, 3}, Arrays.copyOfRange(after, 20, 23));
          }
          if (kind == 1) {
            assertArrayEquals(
              Arrays.copyOfRange(b, b.length - 8, b.length),
              Arrays.copyOfRange(after, after.length - 8, after.length));
          }
          if (available(API_ORACLE)) {
            assertTrue(command(API_ORACLE, "riff-title", n).path("saved").asBoolean());
            assertEquals(command(ORACLE, "snapshot", n), command(ORACLE, "snapshot", p));
            byte[] nativeBytes = Files.readAllBytes(n);
            assertArrayEquals(
              Arrays.copyOfRange(nativeBytes, 4, 8), Arrays.copyOfRange(after, 4, 8));
          }
          matchesNative(p);
        }
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("parity")
  @org.junit.jupiter.api.Tag("core")
  void nativeId3OnlyCorpusSavesLoseReplacementWithoutChangingOpaqueTail() throws Exception {
    for (String name : List.of("excessive_alloc.aif", "invalid-chunk.wav")) {
      Path source = Snapshots.upstreamSource(ROOT).resolve("tests/data").resolve(name);
      if (Boolean.getBoolean("taglib.requireNative")) {
        assertTrue(Files.isRegularFile(source));
      }
      if (!Files.isRegularFile(source)) {
        continue;
      }
      {
        Path p = fixture("java-" + name, Files.readAllBytes(source));
        Path n = fixture("native-" + name, Files.readAllBytes(source));
        TagLib.write(p, PropertyMap.builder().put("TITLE", "Must remain reachable").build());
        assertTrue(TagLib.read(p).properties().isEmpty());
        if (available(API_ORACLE)) {
          assertTrue(command(API_ORACLE, "riff-title", n).path("saved").asBoolean());
          // Complete byte equality includes malformed header and opaque trailing data.
          assertArrayEquals(Files.readAllBytes(n), Files.readAllBytes(p));
          assertEquals(command(ORACLE, "snapshot", n), command(ORACLE, "snapshot", p));
        }
      }
    }
  }

  private void rejects(Path p) throws Exception {
    rejects(p, cz.skyba.taglib.TagLibException.class);
  }

  private void rejects(Path p, Class<? extends RuntimeException> failureType) throws Exception {
    assertThrows(failureType, () -> TagLib.read(p));
    if (available(ORACLE)) {
      assertFalse(command(ORACLE, "snapshot", p).path("valid").asBoolean(), p.toString());
    }
  }

  private void matchesNative(Path p) throws Exception {
    AudioMetadata a = TagLib.read(p);
    if (!available(ORACLE)) {
      return;
    }
    JsonNode n = command(ORACLE, "snapshot", p);
    assertTrue(n.path("valid").asBoolean(), p.toString());
    assertEquals(
      n.path("audio").path("lengthInMilliseconds").asLong(),
      a.audioProperties().lengthInMilliseconds(),
      p.toString());
    assertEquals(
      n.path("audio").path("bitrate").asInt(), a.audioProperties().bitrate(), p.toString());
    assertEquals(
      n.path("audio").path("sampleRate").asInt(), a.audioProperties().sampleRate(), p.toString());
    assertEquals(
      n.path("audio").path("channels").asInt(), a.audioProperties().channels(), p.toString());
    assertEquals(n.path("properties"), JSON.valueToTree(a.properties().asMap()), p.toString());
  }

  private static boolean available(Path tool) {
    boolean present = Files.isExecutable(tool);
    if (Boolean.getBoolean("taglib.requireNative")) {
      assertTrue(present, "Build required oracle: " + tool);
    }
    return present;
  }

  private static JsonNode command(Path tool, String command, Path p) throws Exception {
    var process =
      new ProcessBuilder(tool.toString(), command, p.toString())
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start();
    byte[] out = process.getInputStream().readAllBytes();
    assertEquals(0, process.waitFor(), new String(out, StandardCharsets.UTF_8));
    return JSON.readTree(out);
  }

  private Path fixture(String name, byte[] b) throws IOException {
    return Files.write(directory.resolve(name), b);
  }

  private static Path root() {
    Path p = Path.of("").toAbsolutePath();
    while (p != null && !Files.isDirectory(p.resolve("parity/native"))) {
      p = p.getParent();
    }
    return Objects.requireNonNull(p);
  }

  private static byte[] mod() {
    byte[] b = new byte[2200];
    System.arraycopy(Binary.bytes("8CHN"), 0, b, 1080, 4);
    return b;
  }

  private static byte[] xm() {
    byte[] b = new byte[80];
    System.arraycopy(Binary.bytes("Extended Module: "), 0, b, 0, 17);
    b[37] = 26;
    Binary.put32le(b, 60, 20);
    Binary.put16le(b, 68, 8);
    return b;
  }

  private static byte[] it() {
    byte[] b = new byte[192];
    System.arraycopy(Binary.bytes("IMPM"), 0, b, 0, 4);
    Arrays.fill(b, 128, 192, (byte) 64);
    return b;
  }

  private static byte[] s3m() {
    byte[] b = new byte[96];
    b[28] = 26;
    b[29] = 16;
    System.arraycopy(Binary.bytes("SCRM"), 0, b, 44, 4);
    Arrays.fill(b, 64, 96, (byte) 255);
    return b;
  }

  private static byte[] wave(int rate, int bits, int channels, int data, int fmtSize) {
    byte[] b = new byte[44 + fmtSize - 16];
    System.arraycopy(Binary.bytes("RIFF"), 0, b, 0, 4);
    System.arraycopy(Binary.bytes("WAVEfmt "), 0, b, 8, 8);
    Binary.put32le(b, 16, fmtSize);
    Binary.put16le(b, 20, 1);
    Binary.put16le(b, 22, channels);
    Binary.put32le(b, 24, rate);
    Binary.put16le(b, 32, 4);
    Binary.put16le(b, 34, bits);
    System.arraycopy(Binary.bytes("data"), 0, b, 20 + fmtSize, 4);
    Binary.put32le(b, 24 + fmtSize, Integer.toUnsignedLong(data));
    return b;
  }

  private static byte[] shorten(byte[] header, int channels) {
    var bits = new StringBuilder();
    for (long v : new long[]{3, channels, 256, 0, 0, 0}) {
      uint(bits, v);
    }
    rice(bits, 9, 2);
    rice(bits, header.length, 5);
    for (byte b : header) {
      rice(bits, b & 255, 8);
    }
    while ((bits.length() & 31) != 0) {
      bits.append('0');
    }
    var out = new ByteArrayOutputStream();
    out.writeBytes(Binary.bytes("ajkg"));
    out.write(2);
    for (int i = 0; i < bits.length(); i += 8) {
      out.write(Integer.parseInt(bits.substring(i, i + 8), 2));
    }
    return out.toByteArray();
  }

  private static void uint(StringBuilder b, long v) {
    int k = v == 0 ? 0 : 64 - Long.numberOfLeadingZeros(v);
    rice(b, k, 2);
    rice(b, v, k);
  }

  private static void rice(StringBuilder b, long v, int k) {
    b.repeat('0', (int) (v >>> k));
    b.append('1');
    for (int i = k - 1; i >= 0; i--) {
      b.append((v >>> i) & 1);
    }
  }

  private static byte[] join(byte[]... arrays) {
    var out = new ByteArrayOutputStream();
    for (byte[] a : arrays) {
      out.writeBytes(a);
    }
    return out.toByteArray();
  }
}
