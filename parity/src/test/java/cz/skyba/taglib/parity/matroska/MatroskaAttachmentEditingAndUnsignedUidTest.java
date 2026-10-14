package cz.skyba.taglib.parity.matroska;

import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.TagLib;
import cz.skyba.taglib.matroska.AttachedFile;
import cz.skyba.taglib.matroska.Attachments;
import cz.skyba.taglib.matroska.MatroskaFile;
import cz.skyba.taglib.parity.support.NativeSavedFileComparison;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("parity")
@org.junit.jupiter.api.Tag("matroska")
class MatroskaAttachmentEditingAndUnsignedUidTest {
  @TempDir
  Path directory;
  private static final byte[] FONT = {0, -1, 7};
  private static final byte[] COVER = {(byte) 0x89, 'P', 'N', 'G'};

  @Test
  void collectionRemovesFirstMatchingUidAndSaveRetainsOtherSegmentBytes() throws Exception {
    byte[] tags =
      element(
        0x1254c367L,
        element(
          0x7373,
          element(
            0x67c8, join(element(0x45a3, utf8("TITLE")), element(0x4487, utf8("Kept"))))));
    byte[] chapters = element(0x1043a770L, new byte[0]);
    byte[] cluster = element(0x1f43b675L, element(0xe7, new byte[]{1}));
    byte[] cues = element(0x1c53bb6bL, new byte[0]);
    Path path =
      write(
        "retained",
        join(
          tags,
          chapters,
          cluster,
          cues,
          element(0x1941a469L, attachment("old.bin", "application/bin", "", 1, FONT))));
    byte[] before = Files.readAllBytes(path);
    var collection =
      new Attachments(
        List.of(
          new AttachedFile(FONT, "first.bin", "application/bin", -1),
          new AttachedFile(COVER, "second.bin", "application/bin", -1)));
    collection.removeAttachedFile(-1);
    assertEquals("second.bin", collection.attachedFileList().getFirst().fileName());
    collection.addAttachedFile(new AttachedFile(FONT, "font-č.ttf", "font/ttf", 0, "Písmo"));
    collection.save(path);
    var loaded = new MatroskaFile(path).attachments().attachedFileList();
    assertEquals(2, loaded.size());
    assertNotEquals(0, loaded.get(1).uid());
    assertEquals("Kept", TagLib.read(path, ReadOptions.TAGS_ONLY).tag().title());
    byte[] after = Files.readAllBytes(path);
    NativeSavedFileComparison.compare(
      before, path, "attachments", Long.toUnsignedString(loaded.get(1).uid()));
    // Native Compact re-renders typed tags/chapters; complete-output comparison checks those
    // changes.
    for (byte[] preserved : List.of(cluster, cues)) {
      int position = indexOf(after, preserved);
      assertTrue(position >= 0);
      assertArrayEquals(
        preserved, Arrays.copyOfRange(after, position, position + preserved.length));
    }
    collection.clear();
    collection.save(path);
    assertNull(new MatroskaFile(path).attachments());
    assertEquals("Kept", TagLib.read(path, ReadOptions.TAGS_ONLY).tag().title());
  }

  @Test
  void nativeDeferredReadsAndAttachmentParsingMatchPinnedRelease() throws Exception {
    String oracle = oracle();
    Path fields = write("native-fields", unusualAttachments());
    assertEquals(dump(new MatroskaFile(fields)), nativeRun(oracle, "read", fields));
    var session = new MatroskaFile(fields);
    for (String selector :
      List.of(
        "font-č.ttf",
        "font/ttf",
        "font/é",
        "18446744073709551615",
        "PICTURE",
        "picture",
        "cover.png",
        "image/png",
        "")) {
      var expected = new StringBuilder();
      for (var property : session.complexProperties(selector))
        expected
          .append("FILE\t")
          .append(hex(utf8(property.get("fileName").toString())))
          .append('\t')
          .append(hex(utf8(property.get("mimeType").toString())))
          .append('\t')
          .append(hex(utf8(property.get("description").toString())))
          .append('\t')
          .append(Long.toUnsignedString(property.get("uid").toUnsignedLong()))
          .append('\t')
          .append(hex(property.get("data").toByteVector().toByteArray()))
          .append('\n');
      assertEquals(expected.toString(), nativeRun(oracle, "query", fields, selector), selector);
    }
    byte[] payload = new byte[4096];
    Arrays.fill(payload, (byte) 0x35);
    Path deferred =
      write(
        "native-deferred",
        element(0x1941a469L, attachment("font.ttf", "font/ttf", "", 1, payload)));
    assertEquals(
      "before=0\nloaded=1\nposition=3\nagain=1\n",
      nativeRun(
        oracle,
        "deferred",
        deferred,
        Integer.toString(indexOf(Files.readAllBytes(deferred), payload)),
        Integer.toString(payload.length)));
    Path last =
      write(
        "native-last",
        join(
          element(0x1941a469L, attachment("old.bin", "application/bin", "", 1, FONT)),
          element(0x1941a469L, attachment("new.bin", "application/bin", "", 2, COVER))));
    assertEquals(dump(new MatroskaFile(last)), nativeRun(oracle, "read", last));
  }

  @Test
  void nativeAndJavaAttachmentSavesCrossReadWithNonImageDataAndUnsignedUid() throws Exception {
    String oracle = oracle();
    byte[] cluster = element(0x1f43b675L, element(0xe7, new byte[]{3}));
    Path javaPath = write("java-save", cluster), nativePath = write("native-save", cluster);
    var values =
      List.of(
        new AttachedFile(FONT, "font-č.ttf", "font/ttf", -1, "Písmo"),
        new AttachedFile(COVER, "cover.png", "image/png", 7, "Front"));
    var session = new MatroskaFile(javaPath);
    values.forEach(session.getOrCreateAttachments()::addAttachedFile);
    session.save();
    assertEquals(values, session.attachments().attachedFileList());
    nativeRun(oracle, "set", nativePath);
    assertEquals(values, new MatroskaFile(nativePath).attachments().attachedFileList());
    assertEquals(nativeRun(oracle, "read", nativePath), nativeRun(oracle, "read", javaPath));
    assertEquals(dump(new MatroskaFile(javaPath)), nativeRun(oracle, "read", javaPath));
  }

  private static String oracle() {
    String executable = System.getProperty("matroska.attachment.oracle");
    if (Boolean.getBoolean("taglib.requireNative"))
      assertNotNull(executable, "Run ./mvnw -Pparity verify or provide matroska.attachment.oracle");
    org.junit.jupiter.api.Assumptions.assumeTrue(
      executable != null, "Run ./mvnw -Pparity verify for the pinned native oracle");
    return executable;
  }

  private static String nativeRun(String executable, String command, Path path, String... args)
    throws Exception {
    var invocation = new java.util.ArrayList<>(List.of(executable, command, path.toString()));
    invocation.addAll(List.of(args));
    var process = new ProcessBuilder(invocation).redirectErrorStream(true).start();
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Native attachment probe timed out");
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.exitValue(), output);
    return output;
  }

  private static String dump(MatroskaFile file) throws Exception {
    var output = new StringBuilder();
    for (var key : file.complexPropertyKeys())
      output.append("KEY\t").append(hex(utf8(key))).append('\n');
    var collection = file.attachments();
    if (collection != null)
      for (var value : collection.attachedFileList())
        output
          .append("FILE\t")
          .append(hex(utf8(value.fileName())))
          .append('\t')
          .append(hex(utf8(value.mediaType())))
          .append('\t')
          .append(hex(utf8(value.description())))
          .append('\t')
          .append(Long.toUnsignedString(value.uid()))
          .append('\t')
          .append(hex(value.data()))
          .append('\n');
    return output.toString();
  }

  private Path write(String name, byte[] segment) throws Exception {
    Path path = directory.resolve(name + ".mka");
    Files.write(path, document(segment));
    return path;
  }

  private static byte[] unusualAttachments() throws Exception {
    return element(
      0x1941a469L,
      join(
        element(0x61a7, join(element(0x4660, utf8("image/png")), element(0x465c, COVER))),
        element(0x61a7, element(0x466e, utf8("missing-data.bin"))),
        element(
          0x61a7,
          join(
            element(0x466e, utf8("old")),
            element(0x466e, utf8("font-č.ttf")),
            element(0x4660, utf8("application/old")),
            element(0x4660, utf8("font/ttf")),
            element(0x467e, utf8("Old")),
            element(0x467e, utf8("Písmo\0ignored")),
            element(0x465c, new byte[]{4}),
            element(0x465c, FONT),
            element(0x46ae, new byte[]{1}),
            element(0x46ae, new byte[]{-1, -1, -1, -1, -1, -1, -1, -1}))),
        attachment("", "font/é", "", 7, new byte[0]),
        attachment("", "", "", -1, FONT),
        attachment("cover.png", "image/png", "Front", 7, COVER),
        attachment("upper.png", "IMAGE/PNG", "", 8, COVER)));
  }

  private static byte[] attachment(
    String name, String mime, String description, long uid, byte[] data) throws Exception {
    var number = new byte[8];
    for (int i = 0; i < 8; i++) number[i] = (byte) (uid >>> ((7 - i) * 8));
    return element(
      0x61a7,
      join(
        element(0x466e, utf8(name)),
        element(0x4660, mime.getBytes(StandardCharsets.ISO_8859_1)),
        element(0x467e, utf8(description)),
        element(0x46ae, number),
        element(0x465c, data)));
  }

  private static byte[] document(byte[] segment) throws Exception {
    return join(element(0x1a45dfa3L, new byte[0]), element(0x18538067L, segment));
  }

  private static byte[] element(long value, byte[] payload) throws Exception {
    var output = new ByteArrayOutputStream();
    output.write(id(value));
    int width = value == 0x18538067L ? 8 : 1;
    while (payload.length >= (1L << (7 * width)) - 1) width++;
    long length = payload.length;
    byte[] size = new byte[width];
    for (int i = width - 1; i >= 0; i--) {
      size[i] = (byte) length;
      length >>>= 8;
    }
    size[0] |= (byte) (1 << (8 - width));
    output.write(size);
    output.write(payload);
    return output.toByteArray();
  }

  private static byte[] id(long value) {
    int width = (64 - Long.numberOfLeadingZeros(value) + 7) / 8;
    byte[] bytes = new byte[width];
    for (int i = 0; i < width; i++) bytes[i] = (byte) (value >>> (8 * (width - 1 - i)));
    return bytes;
  }

  private static byte[] join(byte[]... pieces) throws Exception {
    var output = new ByteArrayOutputStream();
    for (byte[] piece : pieces) output.write(piece);
    return output.toByteArray();
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    for (int i = 0; i <= haystack.length - needle.length; i++)
      if (Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)) return i;
    return -1;
  }

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }

  private static final class CountingStream extends ByteVectorStream {
    private final long payloadStart, payloadEnd;
    private int payloadReads;

    CountingStream(byte[] bytes, long payloadStart, long payloadSize) {
      super(bytes);
      assertTrue(payloadStart >= 0);
      this.payloadStart = payloadStart;
      payloadEnd = payloadStart + payloadSize;
    }

    @Override
    public ByteVector readBlock(long length) {
      if (tell() < payloadEnd && tell() + length > payloadStart) payloadReads++;
      return super.readBlock(length);
    }
  }
}
