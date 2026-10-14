package cz.skyba.taglib.parity.mp4;

import cz.skyba.taglib.MetadataDocument;
import cz.skyba.taglib.MetadataReadException;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.TagLib;
import cz.skyba.taglib.internal.Binary;
import cz.skyba.taglib.parity.support.NativeSavedFileComparison;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("parity")
@org.junit.jupiter.api.Tag("mp4")
class Mp4PaddingOffsetsAndExtendedAtomSaveTest {
  @TempDir
  Path dir;

  static byte[] box(String name, byte[]... payload) throws Exception {
    var out = new ByteArrayOutputStream();
    for (byte[] p : payload) out.write(p);
    byte[] b = new byte[out.size() + 8];
    Binary.put32be(b, 0, b.length);
    System.arraycopy(Binary.bytes(name), 0, b, 4, 4);
    System.arraycopy(out.toByteArray(), 0, b, 8, out.size());
    return b;
  }

  static byte[] join(byte[]... parts) throws Exception {
    var out = new ByteArrayOutputStream();
    for (byte[] p : parts) out.write(p);
    return out.toByteArray();
  }

  @Test
  void paddedWritesFollowNativeBoundedMalformedOffsetHandling() throws Exception {
    for (String kind : List.of("stco", "co64")) {
      for (byte[] bad : List.of(new byte[7], new byte[8], new byte[12])) {
        if (bad.length >= 8) Binary.put32be(bad, 4, 0xffffffffL);
        byte[] original =
          join(
            box(
              "moov",
              box("trak", box("mdia", box("minf", box("stbl", box(kind, bad))))),
              paddedMetadata(true)),
            box("mdat", new byte[]{1, 2, 3}));
        Path path = dir.resolve("bad-padded-" + kind + bad.length + ".m4a");
        Files.write(path, original);
        TagLib.write(path, PropertyMap.builder().put("TITLE", "Fits").build());
        NativeSavedFileComparison.compareTitle(original, path, "Fits", false);
      }
    }
  }

  @Test
  void extendedHeadersAndMissingHandlersFollowNativeExistingMetadataSave() throws Exception {
    for (boolean extended : new boolean[]{false, true}) {
      byte[] stco = new byte[12], media = {1, 2, 3, 4};
      Binary.put32be(stco, 4, 1);
      byte[] track = box("trak", box("mdia", box("minf", box("stbl", box("stco", stco)))));
      byte[] metadata = paddedMetadata(extended);
      byte[] movie =
        extended ? legacyExtended("moov", join(track, metadata)) : box("moov", track, metadata);
      if (extended) Binary.put64be(movie, 8, movie.length);
      Binary.put32be(movie, index(movie, Binary.bytes("stco")) + 12, movie.length + 8L);
      Path path = dir.resolve(extended ? "extended-valid.m4a" : "missing-handler.m4a");
      byte[] original = join(movie, box("mdat", media));
      Files.write(path, original);
      TagLib.write(path, PropertyMap.builder().put("TITLE", "Fits").build());
      byte[] written = Files.readAllBytes(path);
      NativeSavedFileComparison.compareTitle(original, path, "Fits", false);
      assertEquals(extended ? 1 : movie.length, Binary.u32be(written, 0));
      assertEquals(extended, index(written, Binary.bytes("hdlr")) >= 0);
      long mediaOffset = Binary.u32be(written, index(written, Binary.bytes("stco")) + 12);
      assertEquals(written.length - media.length, mediaOffset);
      assertArrayEquals(media, Arrays.copyOfRange(written, (int) mediaOffset, written.length));
      assertEquals("Fits", TagLib.read(path).properties().first("TITLE"));
      if (extended) assertEquals(movie.length, Binary.u64be(written, 8));
    }
  }

  private static byte[] paddedMetadata(boolean handler) throws Exception {
    return box(
      "udta",
      box(
        "meta",
        new byte[4],
        handler ? box("hdlr", new byte[25]) : new byte[0],
        box("free", new byte[13]),
        box("ilst"),
        box("free", new byte[1024])));
  }

  @Test
  void unrelatedIndexAndEncryptionAtomsFollowNativeSave() throws Exception {
    for (String kind : List.of("sidx", "mfra", "saio", "iloc")) {
      byte[] moov =
        box(
          "moov",
          kind.equals("saio") || kind.equals("iloc") ? box(kind, new byte[8]) : new byte[0]);
      byte[] original =
        join(
          box("ftyp", Binary.bytes("M4A \0\0\0\0")),
          moov,
          kind.equals("sidx") || kind.equals("mfra") ? box(kind, new byte[8]) : new byte[0],
          box("mdat", new byte[]{1, 2, 3}));
      Path p = dir.resolve(kind + ".m4a");
      Files.write(p, original);
      TagLib.write(p, PropertyMap.builder().put("TITLE", "changed").build());
      NativeSavedFileComparison.compareTitle(original, p, "changed", false);
      assertTrue(index(Files.readAllBytes(p), box(kind, new byte[8])) >= 0);
    }
  }

  @Test
  void legacyExtendedMetadataParentsRetainNativeHeaderAndSizeQuirks() throws Exception {
    byte[] item = box("cpil", box("data", new byte[]{0, 0, 0, 21, 0, 0, 0, 0, 1}));
    byte[] original =
      legacyExtended(
        "moov",
        legacyExtended("udta", legacyExtended("meta", join(new byte[4], box("ilst", item)))));
    assertEquals(85, original.length);
    Path file = dir.resolve("legacy-extended.mp4");
    Files.write(file, original);
    var properties = TagLib.read(file).properties().toBuilder().put("TITLE", "Repaired").build();
    TagLib.write(file, properties);
    NativeSavedFileComparison.compareTitle(original, file, "Repaired", true);
    assertEquals(properties, TagLib.read(file).properties());
    byte[] saved = Files.readAllBytes(file);
    assertEquals(1, Binary.u32be(saved, 0));
    assertTrue(index(saved, item) >= 0);
    // The physical editor is a Java extension with a stricter damaged-tree policy.
    assertThrows(MetadataReadException.class, () -> MetadataDocument.open(file));
    byte[] beforeSecond = Files.readAllBytes(file);
    TagLib.write(file, PropertyMap.builder().put("TITLE", "Shrunk").build());
    NativeSavedFileComparison.compareTitle(beforeSecond, file, "Shrunk", false);
    saved = Files.readAllBytes(file);
    assertEquals(1, Binary.u32be(saved, 0));
    assertEquals("Shrunk", TagLib.read(file).properties().first("TITLE"));
  }

  @Test
  void extendedMetadataUndercountsFollowNativeAcceptanceAndBytes() throws Exception {
    byte[] item = box("cpil", box("data", new byte[]{0, 0, 0, 21, 0, 0, 0, 0, 1}));
    byte[] original =
      legacyExtended(
        "moov",
        legacyExtended("udta", legacyExtended("meta", join(new byte[4], box("ilst", item)))));
    var cases = new ArrayList<byte[]>();
    cases.add(join(original, box("mdat", new byte[]{1, 2, 3, 4})));
    byte[] wrongUndercount = original.clone();
    Binary.put64be(wrongUndercount, 8, original.length - 4);
    cases.add(wrongUndercount);
    byte[] escapingItem = original.clone();
    Binary.put32be(escapingItem, 60, item.length + 1);
    cases.add(escapingItem);
    for (int i = 0; i < cases.size(); i++) {
      Path file = dir.resolve("ambiguous-" + i + ".mp4");
      Files.write(file, cases.get(i));
      if (i == 2) {
        // This child escapes its enclosing atom; both released readers reject the tree.
        assertThrows(
          cz.skyba.taglib.TagLibException.class,
          () -> TagLib.write(file, PropertyMap.builder().put("TITLE", "unsafe").build()));
        NativeSavedFileComparison.compareRejected(cases.get(i), file, "title", "unsafe", "replace");
        assertArrayEquals(cases.get(i), Files.readAllBytes(file));
      } else {
        TagLib.write(file, PropertyMap.builder().put("TITLE", "unsafe").build());
        NativeSavedFileComparison.compareTitle(cases.get(i), file, "unsafe", false);
      }
    }
  }

  private static byte[] legacyExtended(String name, byte[] payload) throws Exception {
    byte[] out = new byte[16 + payload.length];
    Binary.put32be(out, 0, 1);
    System.arraycopy(Binary.bytes(name), 0, out, 4, 4);
    Binary.put64be(out, 8, out.length - 8);
    System.arraycopy(payload, 0, out, 16, payload.length);
    return out;
  }

  @Test
  void legacyExtendedNativePaddingCanBeSafelyRewritten() throws Exception {
    byte[] item = box("cpil", box("data", new byte[]{0, 0, 0, 21, 0, 0, 0, 0, 1}));
    byte[] nativePadding = new byte[725];
    Arrays.fill(nativePadding, (byte) 1); // TagLib::MP4::Tag::padIlst uses 0x01.
    byte[] original =
      legacyExtended(
        "moov",
        legacyExtended(
          "udta",
          legacyExtended(
            "meta",
            join(
              new byte[4],
              box("free", new byte[7]),
              box("ilst", item),
              box("free", nativePadding)))));
    Path file = dir.resolve("legacy-padded.mp4");
    Files.write(file, original);
    TagLib.write(
      file, TagLib.read(file).properties().toBuilder().put("TITLE", "Repaired padding").build());
    NativeSavedFileComparison.compareTitle(original, file, "Repaired padding", true);
    assertEquals("Repaired padding", TagLib.read(file).properties().first("TITLE"));
    assertEquals("1", TagLib.read(file).properties().first("COMPILATION"));
    byte[] saved = Files.readAllBytes(file);
    assertEquals(1, Binary.u32be(saved, 0));
    assertTrue(index(saved, item) >= 0);
  }

  static int index(byte[] data, byte[] needle) {
    outer:
    for (int i = 0; i <= data.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) if (data[i + j] != needle[j]) continue outer;
      return i;
    }
    return -1;
  }
}
