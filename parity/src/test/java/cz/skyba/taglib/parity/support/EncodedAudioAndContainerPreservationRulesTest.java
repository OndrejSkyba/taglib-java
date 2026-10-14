package cz.skyba.taglib.parity.support;

import cz.skyba.taglib.parity.PayloadHash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

final class EncodedAudioAndContainerPreservationRulesTest {
  @TempDir
  Path directory;

  @Test
  void completeBytesNormalizeOnlyExplicitReplacementGeneratedPictureUids() throws Exception {
    Path source = directory.resolve("uid-source.mka"),
      javaAfter = directory.resolve("uid-java.mka"),
      nativeAfter = directory.resolve("uid-native.mka");
    Files.write(source, HexFormat.of().parseHex("1a45dfa3801853806780"));
    String envelope =
      "1a45dfa38018538067aa1941a469a561a7a2466e8178466089696d6167652f706e67465c8401504e4746ae88";
    byte[] javaBytes = HexFormat.of().parseHex(envelope + "1111111111111111");
    byte[] nativeBytes = HexFormat.of().parseHex(envelope + "2222222222222222");
    Files.write(javaAfter, javaBytes);
    Files.write(nativeAfter, nativeBytes);
    var proof =
      PayloadHash.nativeBytesEquivalent(source, javaAfter, nativeAfter, "pictures-replace");
    assertTrue(proof.verified());
    assertFalse(proof.rawEqual());
    assertEquals(1, proof.normalizedUidFieldCount());
    assertNotEquals(proof.javaSha256(), proof.nativeSha256());
    assertEquals(proof.normalizedJavaSha256(), proof.normalizedNativeSha256());
    assertFalse(
      PayloadHash.nativeBytesEquivalent(source, javaAfter, nativeAfter, "save-only").verified(),
      "Existing/unrelated UID changes have no default-save normalization");
    Files.write(source, nativeBytes);
    assertFalse(
      PayloadHash.nativeBytesEquivalent(source, javaAfter, nativeAfter, "pictures-replace")
        .verified(),
      "A source UID cannot be reclassified as generated");
    byte[] duplicateUid = Arrays.copyOf(nativeBytes, nativeBytes.length + 11);
    for (int sizeOffset : new int[]{9, 14, 17}) duplicateUid[sizeOffset] += 11;
    System.arraycopy(
      HexFormat.of().parseHex("46ae883333333333333333"), 0, duplicateUid, nativeBytes.length, 11);
    Files.write(source, duplicateUid);
    assertFalse(
      PayloadHash.nativeBytesEquivalent(source, javaAfter, nativeAfter, "pictures-replace")
        .verified(),
      "Ambiguous source UID fields cannot conceal an existing native UID");
    Files.write(source, HexFormat.of().parseHex("1a45dfa3801853806780"));
    byte[] corrupt = javaBytes.clone();
    corrupt[corrupt.length - 12] ^= 1;
    Files.write(javaAfter, corrupt);
    assertFalse(
      PayloadHash.nativeBytesEquivalent(source, javaAfter, nativeAfter, "pictures-replace")
        .verified(),
      "Payload edits outside UID fields fail complete bytes");
    byte[] nonPictureJava = javaBytes.clone(), nonPictureNative = nativeBytes.clone();
    byte[] mime = "image/png".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    for (int at = 0; at < nonPictureJava.length - mime.length; at++)
      if (Arrays.equals(mime, Arrays.copyOfRange(nonPictureJava, at, at + mime.length))) {
        nonPictureJava[at] = 'x';
        nonPictureNative[at] = 'x';
        break;
      }
    Files.write(javaAfter, nonPictureJava);
    Files.write(nativeAfter, nonPictureNative);
    assertFalse(
      PayloadHash.nativeBytesEquivalent(source, javaAfter, nativeAfter, "pictures-replace")
        .verified(),
      "Unrelated attachment UID fields are never normalized");
  }

  @Test
  void consecutivePhysicalId3EnvelopesExcludeTagsButRetainEveryMediaByte() throws Exception {
    Path source = directory.resolve("duplicates.mp3"), output = directory.resolve("saved.mp3");
    byte[] first = HexFormat.of().parseHex("49443303000000000003585858"),
      second = HexFormat.of().parseHex("49443304000000000002595a"),
      media = {(byte) 255, (byte) 251, 1, 2, 3, 4};
    Files.write(source, joined(first, second, media));
    Files.write(output, joined(first, media));
    assertEquals(media.length, PayloadHash.of(source).bytes());
    assertEquals(PayloadHash.of(source), PayloadHash.of(output));
    for (int i = 0; i < media.length; i++) {
      byte[] corrupt = media.clone();
      corrupt[i] ^= 1;
      Files.write(output, joined(first, corrupt));
      assertNotEquals(PayloadHash.of(source).sha256(), PayloadHash.of(output).sha256());
    }
    byte[] truncated = second.clone();
    truncated[9] = 127;
    Files.write(source, joined(first, truncated, media));
    assertNull(PayloadHash.of(source), "Oversized duplicate header cannot hide unaccounted bytes");
  }

  @Test
  void missingDeclaredApeHeaderHasNamedDiscardEffectAndIndependentCompletePrefixProof()
    throws Exception {
    Path source = directory.resolve("source.mpc"), output = directory.resolve("saved.mpc");
    byte[] media = HexFormat.of().parseHex("4d50434b010203040506070809"),
      claimedHeader = new byte[32],
      rejectedBody =
        new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 127, 0, 0, 0, 0, 'T', 0, 1, 2};
    Arrays.fill(claimedHeader, (byte) 0xc8);
    byte[] original = joined(media, claimedHeader, rejectedBody, apeHeader(44, 1, false));
    byte[] item = {3, 0, 0, 0, 0, 0, 0, 0, 'T', 'I', 'T', 'L', 'E', 0, 'o', 'n', 'e'};
    byte[] saved =
      joined(
        media,
        apeHeader(32 + item.length, 1, true),
        item,
        apeHeader(32 + item.length, 1, false));
    Files.write(source, original);
    Files.write(output, saved);
    assertNull(
      PayloadHash.of(source), "Ambiguous discarded header is never counted as positive audio");
    var proof = PayloadHash.preservation(source, output, output);
    assertNotNull(proof);
    assertTrue(proof.verified());
    assertFalse(proof.mediaAbsent());
    assertEquals(media.length, proof.before().bytes());
    assertTrue(proof.details().contains("discarded header range bytes=32"));
    assertTrue(proof.details().contains("rejected source item tail bytes=12"));
    for (int i = 0; i < media.length; i++) {
      byte[] corrupt = saved.clone();
      corrupt[i] ^= 1;
      Files.write(output, corrupt);
      assertFalse(
        PayloadHash.preservation(source, output, output).verified(),
        "Identical output corruption cannot hide retained prefix byte " + i);
    }
    for (int offset :
      new int[]{
        media.length + 8,
        media.length + 20,
        media.length + 24,
        media.length + 32,
        saved.length - 20
      }) {
      byte[] corrupt = saved.clone();
      corrupt[offset] ^= 1;
      Files.write(output, corrupt);
      assertNull(
        PayloadHash.preservation(source, output, output),
        "Invalid output APE framing/header cannot pass at " + offset);
    }
    Files.write(output, saved);
    byte[] truncated = Arrays.copyOf(saved, saved.length - 1);
    Files.write(output, truncated);
    assertNull(PayloadHash.preservation(source, output, output));
  }

  private static byte[] apeHeader(int size, int count, boolean header) {
    byte[] bytes = new byte[32];
    System.arraycopy(
      "APETAGEX".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, bytes, 0, 8);
    var numbers = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    numbers
      .putInt(8, 2000)
      .putInt(12, size)
      .putInt(16, count)
      .putInt(20, header ? 0xa0000000 : 0x80000000);
    return bytes;
  }

  private static byte[] joined(byte[]... values) {
    var output = new java.io.ByteArrayOutputStream();
    for (byte[] value : values) output.writeBytes(value);
    return output.toByteArray();
  }

  @Test
  void completeOversizeMetadataProofComparesBinaryFramesAndDoesNotCountEmptyAudio()
    throws Exception {
    var source = directory.resolve("source.mp3");
    var javaAfter = directory.resolve("java.mp3");
    var nativeAfter = directory.resolve("native.mp3");
    byte[] original = HexFormat.of().parseHex("4944330400000000010057303030000000030000010203");
    byte[] rewritten = original.clone();
    rewritten[8] = 0;
    rewritten[9] = 13;
    Files.write(source, original);
    Files.write(javaAfter, rewritten);
    Files.write(nativeAfter, rewritten);
    var proof = PayloadHash.preservation(source, javaAfter, nativeAfter);
    assertNotNull(proof);
    assertTrue(proof.verified());
    assertTrue(proof.mediaAbsent());
    assertEquals(0, proof.before().bytes());
    rewritten[rewritten.length - 1] ^= 1;
    Files.write(javaAfter, rewritten);
    assertFalse(PayloadHash.preservation(source, javaAfter, nativeAfter).verified());
  }

  @Test
  void oversizedFinalRiffBodyRetainsEverySourceByteAndRequiresCompleteAppendedMetadata()
    throws Exception {
    var source = directory.resolve("oversize.wav");
    var output = directory.resolve("output.wav");
    byte[] original = HexFormat.of().parseHex("524946461000000057415645646174616400000001020304");
    byte[] metadata = HexFormat.of().parseHex("494433200a00000049443304000000000000");
    byte[] rewritten = Arrays.copyOf(original, original.length + metadata.length);
    System.arraycopy(metadata, 0, rewritten, original.length, metadata.length);
    ByteBuffer.wrap(rewritten)
      .order(java.nio.ByteOrder.LITTLE_ENDIAN)
      .putInt(4, rewritten.length - 8);
    Files.write(source, original);
    Files.write(output, rewritten);
    var proof = PayloadHash.preservation(source, output, output);
    assertNotNull(proof);
    assertTrue(proof.verified());
    assertFalse(proof.mediaAbsent());
    assertEquals(4, proof.before().bytes() - 16);
    byte[] corrupt = rewritten.clone();
    corrupt[23] ^= 1;
    Files.write(output, corrupt);
    assertFalse(
      PayloadHash.preservation(source, output, output).verified(),
      "Even identical native/Java media corruption fails");
    corrupt = Arrays.copyOf(rewritten, rewritten.length + 8);
    System.arraycopy(
      "JUNK".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
      0,
      corrupt,
      rewritten.length,
      4);
    Files.write(output, corrupt);
    assertNull(
      PayloadHash.preservation(source, output, output),
      "Invented opaque suffix cannot be classified as metadata");
    corrupt = rewritten.clone();
    corrupt[original.length + 4] = 20;
    Files.write(output, corrupt);
    assertNull(
      PayloadHash.preservation(source, output, output),
      "Oversized appended ID3 chunk cannot hide unaccounted bytes");
  }

  @Test
  void riffProofRetainsTheEntireOpaqueTailEvenWhenNativeAndJavaCorruptItIdentically()
    throws Exception {
    var source = directory.resolve("source.aif");
    var javaAfter = directory.resolve("java.aif");
    var nativeAfter = directory.resolve("native.aif");
    byte[] original =
      HexFormat.of().parseHex("464f524d0163c82e41494646434f4d4d000000000053534e440163c8080000");
    Files.write(source, original);
    Files.write(javaAfter, original);
    Files.write(nativeAfter, original);
    var proof = PayloadHash.preservation(source, javaAfter, nativeAfter);
    assertNotNull(proof);
    assertTrue(proof.verified());
    assertEquals(27, proof.before().bytes());
    assertFalse(proof.mediaAbsent());
    original[original.length - 1] ^= 1;
    Files.write(javaAfter, original);
    Files.write(nativeAfter, original);
    assertFalse(PayloadHash.preservation(source, javaAfter, nativeAfter).verified());
  }

  @Test
  void unexplainedMetadataPaddingAndUnsupportedGroupingRemainUnverified() throws Exception {
    var source = directory.resolve("source.mp3");
    var output = directory.resolve("output.mp3");
    byte[] original = HexFormat.of().parseHex("4944330400000000010057303030000000030000010203");
    Files.write(source, original);
    byte[] invalidPadding = Arrays.copyOf(original, original.length + 2);
    invalidPadding[8] = 0;
    invalidPadding[9] = 15;
    invalidPadding[invalidPadding.length - 1] = 1;
    Files.write(output, invalidPadding);
    assertNull(PayloadHash.preservation(source, output, output));
    byte[] grouping = original.clone();
    grouping[8] = 0;
    grouping[9] = 13;
    grouping[19] = 64;
    Files.write(output, grouping);
    assertNull(PayloadHash.preservation(source, output, output));
  }

  @Test
  void outerTagsDoNotChangeHashButMediaChangesDo() throws Exception {
    var raw = directory.resolve("raw.mp3");
    var tagged = directory.resolve("tagged.mp3");
    byte[] audio = {(byte) 255, (byte) 251, 1, 2, 3};
    Files.write(raw, audio);
    byte[] withTags = new byte[10 + audio.length + 128];
    System.arraycopy(new byte[]{'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0}, 0, withTags, 0, 10);
    System.arraycopy(audio, 0, withTags, 10, audio.length);
    System.arraycopy(new byte[]{'T', 'A', 'G'}, 0, withTags, 10 + audio.length, 3);
    Files.write(tagged, withTags);
    assertEquals(PayloadHash.of(raw).sha256(), PayloadHash.of(tagged).sha256());
    withTags[12] ^= 1;
    Files.write(tagged, withTags);
    assertNotEquals(PayloadHash.of(raw).sha256(), PayloadHash.of(tagged).sha256());
  }

  @Test
  void truncatedChunkIsNotDeclaredVerified() throws Exception {
    var file = directory.resolve("a.mp4");
    Files.write(file, new byte[]{0, 0, 1, 0, 'm', 'd', 'a', 't', 1});
    assertNull(PayloadHash.of(file));
  }

  @Test
  void id3AfterGarbagePreservesPrefixInChecksum() throws Exception {
    var original = directory.resolve("garbage.mp3");
    var rewritten = directory.resolve("rewritten.mp3");
    byte[] a = new byte[2070], b = new byte[2080];
    Arrays.fill(a, (byte) 7);
    Arrays.fill(b, (byte) 7);
    System.arraycopy(new byte[]{'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0}, 0, a, 2047, 10);
    System.arraycopy(new byte[]{'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0x0a}, 0, b, 2047, 10);
    Files.write(original, a);
    Files.write(rewritten, b);
    assertEquals(PayloadHash.of(original).sha256(), PayloadHash.of(rewritten).sha256());
    b[15] ^= 1;
    Files.write(rewritten, b);
    assertNotEquals(PayloadHash.of(original).sha256(), PayloadHash.of(rewritten).sha256());
  }

  @Test
  void trackerNameChangesAreExcludedButPatternChangesAreDetected() throws Exception {
    byte[] data = new byte[1100];
    System.arraycopy("8CHN".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, data, 1080, 4);
    var a = directory.resolve("original.mod");
    var b = directory.resolve("changed.mod");
    Files.write(a, data);
    data[3] = 42;
    data[21] = 31;
    Files.write(b, data);
    assertEquals(PayloadHash.of(a).sha256(), PayloadHash.of(b).sha256());
    data[1090] = 99;
    Files.write(b, data);
    assertNotEquals(PayloadHash.of(a).sha256(), PayloadHash.of(b).sha256());
  }

  @Test
  void matroskaTagsAreExcludedButClustersAreChecked() throws Exception {
    var a = directory.resolve("original.mkv");
    var b = directory.resolve("rewritten.mkv");
    Files.write(a, java.util.HexFormat.of().parseHex("1a45dfa38018538067891f43b67584e7820001"));
    byte[] data =
      java.util.HexFormat.of().parseHex("1a45dfa380185380678e1f43b67584e78200011254c36780");
    Files.write(b, data);
    assertEquals(PayloadHash.of(a).sha256(), PayloadHash.of(b).sha256());
    data[18] = 2;
    Files.write(b, data);
    assertNotEquals(PayloadHash.of(a).sha256(), PayloadHash.of(b).sha256());
  }

  @Test
  void largeCorpusFileUsesBoundedFallback() throws Exception {
    var file = directory.resolve("large.mp3");
    try (var channel =
           Files.newByteChannel(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      channel.position(PayloadHash.MAX_IN_MEMORY_BYTES);
      channel.write(java.nio.ByteBuffer.wrap(new byte[]{1}));
    }
    assertNull(PayloadHash.of(file));
    assertEquals(PayloadHash.MAX_IN_MEMORY_BYTES + 1L, PayloadHash.wholeFile(file).bytes());
  }

  @Test
  void oversizedId3AndTruncatedAiffRemainUnverified() throws Exception {
    var mp3 = directory.resolve("oversized.mp3");
    Files.write(mp3, new byte[]{'I', 'D', '3', 4, 0, 0, 0, 0, 1, 0});
    assertNull(PayloadHash.of(mp3));
    var aiff = directory.resolve("truncated.aif");
    Files.write(
      aiff,
      java.util.HexFormat.of()
        .parseHex("464f524d0163c82e41494646434f4d4d000000000053534e440163c8080000"));
    assertNull(PayloadHash.of(aiff));
  }

  @Test
  void legacyExtendedMetadataIsProvenMediaAbsentIncludingNativePadding() throws Exception {
    byte[] original = legacyExtendedMetadata();
    var file = directory.resolve("extended.mp4");
    Files.write(file, original);
    var empty = PayloadHash.of(file);
    assertNotNull(empty);
    assertEquals(0, empty.bytes());
    assertEquals("All MP4 mdat payloads (audio and video)", empty.scope());

    byte[] padded = Arrays.copyOf(original, original.length + 25);
    ByteBuffer.wrap(padded).putInt(original.length, 25);
    System.arraycopy(new byte[]{'f', 'r', 'e', 'e'}, 0, padded, original.length + 4, 4);
    Arrays.fill(padded, original.length + 8, padded.length, (byte) 1);
    for (int at : new int[]{0, 16, 32})
      ByteBuffer.wrap(padded).putLong(at + 8, padded.length - at - 8L);
    Files.write(file, padded);
    assertEquals(empty, PayloadHash.of(file));
  }

  @Test
  void ambiguousExtendedMetadataAndUnexplainedMp4TailRemainUnverified() throws Exception {
    var cases = new ArrayList<byte[]>();
    byte[] original = legacyExtendedMetadata();
    byte[] wrongParent = original.clone();
    ByteBuffer.wrap(wrongParent).putLong(8, original.length - 4L);
    cases.add(wrongParent);
    cases.add(Arrays.copyOf(original, original.length + 1));
    byte[] itemOverflow = original.clone();
    ByteBuffer.wrap(itemOverflow).putInt(60, 26);
    cases.add(itemOverflow);
    byte[] valueOverflow = original.clone();
    ByteBuffer.wrap(valueOverflow).putInt(68, 18);
    cases.add(valueOverflow);
    byte[] valueUnderflow = original.clone();
    ByteBuffer.wrap(valueUnderflow).putInt(68, 16);
    cases.add(valueUnderflow);
    byte[] hiddenMedia = original.clone();
    System.arraycopy(new byte[]{'m', 'd', 'a', 't'}, 0, hiddenMedia, 72, 4);
    cases.add(hiddenMedia);
    byte[] duplicateItems = Arrays.copyOf(original, original.length + 8);
    ByteBuffer.wrap(duplicateItems).putInt(original.length, 8);
    System.arraycopy(new byte[]{'i', 'l', 's', 't'}, 0, duplicateItems, original.length + 4, 4);
    for (int at : new int[]{0, 16, 32})
      ByteBuffer.wrap(duplicateItems).putLong(at + 8, duplicateItems.length - at - 8L);
    cases.add(duplicateItems);
    cases.add(HexFormat.of().parseHex("000000086d6f6f7601"));
    for (int i = 0; i < cases.size(); i++) {
      var file = directory.resolve("unverified-" + i + ".mp4");
      Files.write(file, cases.get(i));
      assertNull(PayloadHash.of(file), "Ambiguous case " + i);
    }
  }

  private static byte[] legacyExtendedMetadata() {
    return HexFormat.of()
      .parseHex(
        "000000016d6f6f76000000000000004d"
          + "0000000175647461000000000000003d"
          + "000000016d657461000000000000002d"
          + "0000000000000021696c7374000000196370696c0000001164617461000000150000000001");
  }
}
