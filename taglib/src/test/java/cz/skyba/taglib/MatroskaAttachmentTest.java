package cz.skyba.taglib;

import cz.skyba.taglib.matroska.AttachedFile;
import cz.skyba.taglib.matroska.Attachments;
import cz.skyba.taglib.matroska.MatroskaFile;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MatroskaAttachmentTest {
  @TempDir
  Path directory;
  private static final byte[] FONT = {0, -1, 7};
  private static final byte[] COVER = {(byte) 0x89, 'P', 'N', 'G'};

  @Test
  void openingAndKeysDeferEveryPayloadAndLoadingPreservesBorrowedStreamPosition() throws Exception {
    byte[] payload = new byte[4096];
    Arrays.fill(payload, (byte) 0x35);
    byte[] bytes = document(element(0x1941a469L, attachment("font.ttf", "font/ttf", "", 9, payload)));
    var stream = new CountingStream(bytes, indexOf(bytes, payload), payload.length);
    var file = new MatroskaFile(stream);
    assertTrue(file.isValid());
    assertEquals(bytes.length, stream.tell());
    assertEquals(List.of("font.ttf"), file.complexPropertyKeys());
    assertEquals(0, stream.payloadReads);
    stream.seek(5);
    var attachments = file.attachments();
    assertEquals(5, stream.tell());
    assertEquals(1, stream.payloadReads);
    assertArrayEquals(payload, attachments.attachedFileList().getFirst().data());
    assertSame(attachments, file.attachments());
    assertEquals(1, stream.payloadReads, "Loaded payloads must be reused");
    byte[] copy = attachments.attachedFileList().getFirst().data();
    copy[0] = 0;
    assertArrayEquals(payload, attachments.attachedFileList().getFirst().data());
    assertTrue(stream.isOpen());
    file.save();
    assertTrue(stream.isOpen());
    stream.seek(0);
    try (var reopened = new MatroskaFile(stream)) {
      assertArrayEquals(payload, reopened.attachments().attachedFileList().getFirst().data());
    }
  }

  @Test
  void requestingPicturesWithoutArtworkDoesNotLoadOtherAttachments() throws Exception {
    byte[] payload = new byte[4096];
    Arrays.fill(payload, (byte) 0x35);
    byte[] bytes = document(element(0x1941a469L, attachment("font.ttf", "font/ttf", "", 9, payload)));
    try (var stream = new CountingStream(bytes, indexOf(bytes, payload), payload.length);
         var owner = new AudioFile(new MatroskaFile(stream, ReadOptions.TAGS_ONLY))) {
      stream.seek(5);
      assertTrue(owner.pictures().isEmpty());
      assertEquals(0, stream.payloadReads);
      assertEquals(5, stream.tell());
      var attachments = ((MatroskaFile) owner.file()).attachments();
      assertEquals(1, stream.payloadReads);
      assertArrayEquals(payload, attachments.attachedFileList().getFirst().data());
      assertEquals(5, stream.tell());
    }
  }

  @Test
  void attachmentParserUsesMandatoryPresenceLastFieldsLatin1AndUnsignedKeys() throws Exception {
    Path path = write("fields", unusualAttachments());
    var file = new MatroskaFile(path);
    assertEquals(List.of("font-č.ttf", "font/é", "18446744073709551615", "PICTURE", "upper.png"),
      file.complexPropertyKeys());
    var values = file.attachments().attachedFileList();
    assertEquals(5, values.size());
    assertEquals(new AttachedFile(FONT, "font-č.ttf", "font/ttf", -1, "Písmo"), values.getFirst());
    assertArrayEquals(new byte[0], values.get(1).data());
    assertEquals(2, file.complexProperties("18446744073709551615").size());
    assertEquals(1, file.complexProperties("font/ttf").size());
    assertEquals(1, file.complexProperties("PICTURE").size());
    assertEquals(0, file.complexProperties("picture").size());
    assertEquals(0, file.complexProperties("").size());
    var font = file.complexProperties("font-č.ttf").getFirst();
    assertEquals(-1, font.get("uid").toUnsignedLong());
    assertArrayEquals(FONT, font.get("data").toByteVector().toByteArray());
    assertEquals("Písmo", font.get("description").toString());
    assertEquals(1, TagLib.read(path, ReadOptions.TAGS_ONLY).pictures().size());
  }

  @Test
  void nonImagePayloadsDoNotConsumeReadBudgetUntilRequested() throws Exception {
    byte[] payload = new byte[4096];
    Arrays.fill(payload, (byte) 0x35);
    byte[] bytes = document(element(0x1941a469L, attachment("font.ttf", "font/ttf", "", 1, payload)));
    var stream = new CountingStream(bytes, indexOf(bytes, payload), payload.length);
    var options = new ReadOptions(false, ReadStyle.AVERAGE, 1024);
    var file = new MatroskaFile(stream, options);
    assertEquals(List.of("font.ttf"), file.complexPropertyKeys());
    assertThrows(java.io.UncheckedIOException.class, file::attachments);
    assertEquals(0, stream.payloadReads, "Reject total allocation before reading deferred data");
    Path path = directory.resolve("large-font.mka");
    Files.write(path, bytes);
    assertTrue(TagLib.read(path, options).pictures().isEmpty());
    assertEquals(1, new MatroskaFile(path).attachments().attachedFileList().size());
    Path image = write("large-picture", element(0x1941a469L,
      attachment("cover.png", "image/png", "", 1, payload)));
    assertEquals(List.of("PICTURE"), new MatroskaFile(image, options).complexPropertyKeys());
    assertThrows(java.io.UncheckedIOException.class, () -> TagLib.read(image, options));
  }

  @Test
  void aggregateAttachmentBudgetRejectsBeforeLoadingAndFilenameIsRequiredForPictures() throws Exception {
    byte[] payload = new byte[510];
    Arrays.fill(payload, (byte) 0x35);
    byte[] bytes = document(element(0x1941a469L, join(
      attachment("one.bin", "application/bin", "", 1, payload),
      attachment("two.bin", "application/bin", "", 2, payload))));
    var stream = new CountingStream(bytes, indexOf(bytes, payload), payload.length);
    var file = new MatroskaFile(stream, new ReadOptions(false, ReadStyle.AVERAGE, 1024));
    assertThrows(java.io.UncheckedIOException.class, file::attachments);
    assertEquals(0, stream.payloadReads);
    Path path = write("missing-name", element(0x1941a469L, element(0x61a7,
      join(element(0x4660, utf8("image/png")), element(0x465c, COVER)))));
    assertTrue(TagLib.read(path, ReadOptions.TAGS_ONLY).pictures().isEmpty());
    assertTrue(new MatroskaFile(path).attachments().attachedFileList().isEmpty());
  }

  @Test
  void missingAttachmentBlocksAndLastBlockSelectionFollowNative() throws Exception {
    Path empty = write("no-attachments", element(0xec, new byte[16]));
    var file = new MatroskaFile(empty);
    assertNull(file.attachments());
    assertTrue(file.complexPropertyKeys().isEmpty());
    assertNotNull(file.getOrCreateAttachments());
    assertTrue(file.attachments().attachedFileList().isEmpty());
    file.save();
    assertNull(file.attachments());
    Path last = write("last-block", join(
      element(0x1941a469L, attachment("old.bin", "application/bin", "", 1, FONT)),
      element(0x1941a469L, attachment("new.bin", "application/bin", "", 2, COVER))));
    assertEquals(List.of("new.bin"), new MatroskaFile(last).complexPropertyKeys());
  }

  @Test
  void ownedPathRetainsNativeSessionWhenUnrelatedBytesAreAppended() throws Exception {
    Path path = write("changed", element(0x1941a469L, attachment("font.ttf", "font/ttf", "", 1, FONT)));
    var file = new MatroskaFile(path);
    Files.write(path, join(Files.readAllBytes(path), new byte[]{0}));
    assertArrayEquals(FONT, file.attachments().attachedFileList().getFirst().data());
    file.save();
    assertEquals(0, Files.readAllBytes(path)[Files.readAllBytes(path).length - 1]);
    file.close();
  }

  @Test
  void attachmentSaveUpdatesSeekEntryWithoutMovingClusterAndRemovesEmptyEntry() throws Exception {
    byte[] cluster = element(0x1f43b675L, element(0xe7, new byte[]{4}));
    byte[] head = element(0x114d9b74L, new byte[0]);
    Path path = write("seek", join(head, element(0xec, new byte[256]), cluster));
    byte[] before = Files.readAllBytes(path);
    new Attachments(List.of(new AttachedFile(FONT, "font.ttf", "font/ttf", 9))).save(path);
    byte[] after = Files.readAllBytes(path);
    int offset = indexOf(before, cluster);
    assertArrayEquals(cluster, Arrays.copyOfRange(after, offset, offset + cluster.length));
    assertTrue(indexOf(after, element(0x53ab, id(0x1941a469L))) >= 0);
    assertEquals(List.of("font.ttf"), new MatroskaFile(path).complexPropertyKeys());
    new Attachments().save(path);
    assertNull(new MatroskaFile(path).attachments());
    assertEquals(-1, indexOf(Files.readAllBytes(path), element(0x53ab, id(0x1941a469L))));
  }

  private Path write(String name, byte[] segment) throws Exception {
    Path path = directory.resolve(name + ".mka");
    Files.write(path, document(segment));
    return path;
  }

  private static byte[] unusualAttachments() throws Exception {
    return element(0x1941a469L, join(
      element(0x61a7, join(element(0x4660, utf8("image/png")), element(0x465c, COVER))),
      element(0x61a7, element(0x466e, utf8("missing-data.bin"))),
      element(0x61a7, join(element(0x466e, utf8("old")), element(0x466e, utf8("font-č.ttf")),
        element(0x4660, utf8("application/old")), element(0x4660, utf8("font/ttf")),
        element(0x467e, utf8("Old")), element(0x467e, utf8("Písmo\0ignored")),
        element(0x465c, new byte[]{4}), element(0x465c, FONT),
        element(0x46ae, new byte[]{1}), element(0x46ae, new byte[]{-1, -1, -1, -1, -1, -1, -1, -1}))),
      attachment("", "font/é", "", 7, new byte[0]),
      attachment("", "", "", -1, FONT),
      attachment("cover.png", "image/png", "Front", 7, COVER),
      attachment("upper.png", "IMAGE/PNG", "", 8, COVER)));
  }

  private static byte[] attachment(String name, String mime, String description, long uid, byte[] data)
    throws Exception {
    var number = new byte[8];
    for (int i = 0; i < 8; i++) number[i] = (byte) (uid >>> ((7 - i) * 8));
    return element(0x61a7, join(element(0x466e, utf8(name)),
      element(0x4660, mime.getBytes(StandardCharsets.ISO_8859_1)), element(0x467e, utf8(description)),
      element(0x46ae, number), element(0x465c, data)));
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
