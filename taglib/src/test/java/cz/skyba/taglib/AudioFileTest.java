package cz.skyba.taglib;

import cz.skyba.taglib.flac.FlacFile;
import cz.skyba.taglib.internal.Binary;
import cz.skyba.taglib.internal.id3.Id3v2;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.IOStream;
import cz.skyba.taglib.toolkit.Variant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AudioFileTest {
  @TempDir
  Path dir;

  @AfterEach
  void clearRegistry() {
    AudioFile.clearFileTypeResolvers();
  }

  @Test
  void unsupportedInputThrowsAndLeavesBorrowedStreamOpen() throws Exception {
    var stream = new ByteVectorStream(new byte[0]);
    assertThrows(TagLibException.class, () -> new AudioFile(stream));
    assertTrue(stream.isOpen());
    assertThrows(NullPointerException.class, () -> new AudioFile((FileBase) null));
  }

  static class TestFile extends FileBase {
    final cz.skyba.taglib.ape.Tag tag = new cz.skyba.taglib.ape.Tag();
    int closes;

    TestFile(IOStream stream, boolean valid) {
      super(stream);
      setValid(valid);
    }

    @Override
    public TagBase tag() {
      return tag;
    }

    @Override
    public AudioProperties audioProperties() {
      return null;
    }

    @Override
    public void save() {
      requireWritable();
    }

    @Override
    public void close() {
      closes++;
      setValid(false);
    }
  }

  @Test
  void ordinaryReferencesShareOneIdempotentOwner() throws Exception {
    var stream = new ByteVectorStream(new byte[0]);
    var file = new TestFile(stream, true);
    var owner = new AudioFile(file);
    var shared = owner;
    shared.tag().setTitle("shared");
    assertEquals("shared", owner.tag().title());
    shared.close();
    owner.close();
    assertEquals(1, file.closes);
    assertFalse(owner.isOpen());
    assertThrows(IllegalStateException.class, owner::file);
    assertThrows(IllegalStateException.class, owner::tag);
    assertThrows(IllegalStateException.class, owner::save);
    assertTrue(stream.isOpen());
  }

  @Test
  void resolverOrderIsStreamBeforeFilenameAndInvalidResultStopsResolution() throws Exception {
    var stream = new ByteVectorStream(new byte[0]) {
      @Override
      public String name() {
        return "custom.flac";
      }
    };
    var events = new ArrayList<String>();
    var invalid = new TestFile(stream, false);
    var requested = new ReadOptions(false, ReadStyle.ACCURATE, 4096);
    AudioFile.addFileTypeResolver((name, options) -> {
      assertSame(requested, options);
      events.add("old-file");
      return invalid;
    });
    AudioFile.addFileTypeResolver(new AudioFile.StreamTypeResolver() {
      @Override
      public FileBase createFileFromStream(IOStream value, ReadOptions options) {
        events.add("new-stream");
        assertSame(requested, options);
        assertFalse(options.readAudioProperties());
        assertEquals(ReadStyle.ACCURATE, options.readStyle());
        return null;
      }

      @Override
      public FileBase createFile(Path name, ReadOptions options) {
        events.add("new-file");
        assertSame(requested, options);
        return null;
      }
    });
    assertThrows(TagLibException.class, () -> new AudioFile(stream, requested));
    assertEquals(List.of("new-stream", "new-file", "old-file"), events);
    AudioFile.clearFileTypeResolvers();
    assertTrue(stream.isOpen());
    assertEquals(1, invalid.closes);
  }

  static byte[] flac() {
    byte[] bytes = new byte[42 + 8];
    System.arraycopy(new byte[]{'f', 'L', 'a', 'C', (byte) 0x80, 0, 0, 34}, 0, bytes, 0, 8);
    bytes[8] = 0x10;
    bytes[10] = 0x10;
    long parameters = ((long) 44100 << 44) | (1L << 41) | (15L << 36) | 44100L;
    for (int i = 0; i < 8; i++) bytes[18 + i] = (byte) (parameters >>> (56 - i * 8));
    Arrays.fill(bytes, 42, bytes.length, (byte) 0x55);
    return bytes;
  }

  @Test
  void borrowedMutableStreamStagesTextAndArtworkThenSavesRealBytes() throws Exception {
    var stream = new ByteVectorStream(flac());
    byte[] original = stream.data().toByteArray();
    try (var ref = new AudioFile(stream, ReadOptions.TAGS_ONLY)) {
      assertTrue(ref.isOpen());
      assertNull(ref.audioProperties());
      var rejected = ref.replaceProperties(PropertyMap.builder().put("TITLE", "saved")
        .put("BAD=KEY", "bad").put("ARTIST", List.of("a", "b")).build()).rejected();
      assertEquals(List.of("bad"), rejected.get("BAD=KEY"));
      assertEquals("a / b", ref.tag().artist());
      assertArrayEquals(original, stream.data().toByteArray());
      assertTrue(ref.setComplexProperties("picture", List.of(Map.of(
        "data", new Variant(new ByteVector(new byte[]{1, 2, 3})), "mimeType", new Variant("image/png"),
        "pictureType", new Variant("Front Cover"), "width", new Variant(10)))));
      assertDoesNotThrow(ref::save);
      assertDoesNotThrow(ref::save);
      var persisted = TagLib.read(stream);
      assertEquals("saved", persisted.tag().title());
      assertEquals(10, persisted.pictures().getFirst().width());
      assertArrayEquals(new byte[]{1, 2, 3}, persisted.pictures().getFirst().data());
      assertArrayEquals(Arrays.copyOfRange(original, 42, 50), Arrays.copyOfRange(stream.data().toByteArray(), (int) stream.length() - 8, (int) stream.length()));
    }
    assertTrue(stream.isOpen());
  }

  @Test
  void facadeSnapshotUsesTypedId3Semantics() throws Exception {
    var frames = new ByteArrayOutputStream();
    Id3v2.writeFrame(
      frames, 4, "TIT2", 0, new byte[]{0, 'S', 'o', 'n', 'g'});
    Id3v2.writeFrame(
      frames, 4, "TCON", 0,
      new byte[]{0, '(', '2', '2', ')', 'D', 'e', 'a', 't', 'h', ' ', 'M', 'e', 't', 'a', 'l'});
    Id3v2.writeFrame(
      frames, 4, "TORY", 0, new byte[]{0, '1', '9', '9', '9'});
    byte[] tag = Id3v2.renderTag(4, frames, 0, 0);
    byte[] mpegFrame = new byte[417];
    System.arraycopy(new byte[]{(byte) 0xff, (byte) 0xfb, (byte) 0x90, 0x64}, 0,
      mpegFrame, 0, 4);
    var bytes = new ByteArrayOutputStream();
    bytes.writeBytes(tag);
    for (int i = 0; i < 20; i++) {
      bytes.writeBytes(mpegFrame);
    }
    Path path = dir.resolve("typed-facade.mp3");
    Files.write(path, bytes.toByteArray());

    var facade = TagLib.read(path, ReadOptions.TAGS_ONLY);
    try (var ref = new AudioFile(path, ReadOptions.TAGS_ONLY)) {
      assertEquals(ref.properties(), facade.properties());
      assertEquals("Death Metal", facade.properties().first("GENRE"));
      assertFalse(facade.properties().containsKey("ORIGINALDATE"));
      assertEquals(List.of("TORY"), facade.properties().unsupportedData());
      assertEquals(ref.tag().title(), facade.tag().title());
      assertEquals(ref.tag().genre(), facade.tag().genre());
    }
  }

  @Test
  void closingOwnerClosesPathAndMissingPathThrows() throws Exception {
    var path = dir.resolve("audio.flac");
    Files.write(path, flac());
    var owner = new AudioFile(path);
    var file = owner.file();
    assertTrue(file.isOpen());
    owner.close();
    assertFalse(file.isOpen());
    assertFalse(owner.isOpen());
    assertThrows(java.io.UncheckedIOException.class, () -> new AudioFile(dir.resolve("missing.flac")));
  }

  static byte[] trueAudioWithUnknown() throws Exception {
    var body = new ByteArrayOutputStream();
    Id3v2.writeFrame(body, 4, "ZZZZ", 0, new byte[]{1, 2, 3});
    byte[] tag = Id3v2.renderTag(4, body, 0, 0);
    byte[] audio = new byte[26];
    System.arraycopy(new byte[]{'T', 'T', 'A', '1', 1, 0, 2, 0, 16, 0}, 0, audio, 0, 10);
    Binary.put32le(audio, 10, 44100);
    Binary.put32le(audio, 14, 44100);
    byte[] result = Arrays.copyOf(tag, tag.length + audio.length);
    System.arraycopy(audio, 0, result, tag.length, audio.length);
    return result;
  }

  @Test
  void failedSaveRetainsRemovalRequestAndScopedRenderingDoesNotLeakToOtherSessions() throws Exception {
    class ToggleStream extends ByteVectorStream {
      boolean locked;

      ToggleStream(byte[] bytes) {
        super(bytes);
      }

      @Override
      public String name() {
        return "audio.tta";
      }

      @Override
      public boolean readOnly() {
        return locked;
      }
    }
    var bytes = trueAudioWithUnknown();
    var stream = new ToggleStream(bytes);
    try (var ref = new AudioFile(stream)) {
      assertEquals(List.of("UNKNOWN/ZZZZ"), ref.properties().unsupportedData());
      ref.removeUnsupportedProperties(List.of("UNKNOWN/ZZZZ"));
      assertTrue(ref.properties().unsupportedData().isEmpty());
      assertEquals(List.of("UNKNOWN/ZZZZ"), TagLib.read(stream).properties().unsupportedData());
      stream.locked = true;
      assertThrows(TagLibException.class, ref::save);
      assertArrayEquals(bytes, stream.data().toByteArray());
      stream.locked = false;
      assertDoesNotThrow(ref::save);
      assertDoesNotThrow(ref::save);
      assertTrue(TagLib.read(stream).properties().unsupportedData().isEmpty());
      try (var independent = new AudioFile(new ToggleStream(bytes))) {
        assertEquals(List.of("UNKNOWN/ZZZZ"), independent.properties().unsupportedData());
        assertDoesNotThrow(independent::save);
        assertEquals(List.of("UNKNOWN/ZZZZ"), TagLib.read(new ToggleStream(bytes)).properties().unsupportedData());
      }
    }
  }

  @Test
  void resolverIoFailureRetainsItsCauseAndBorrowedStream() {
    var cause = new IOException("storage unavailable");
    AudioFile.addFileTypeResolver((path, options) -> {
      throw new UncheckedIOException(cause);
    });
    var stream = new ByteVectorStream(flac()) {
      @Override
      public String name() {
        return "custom.flac";
      }
    };
    assertSame(cause, assertThrows(java.io.UncheckedIOException.class, () -> new AudioFile(stream)).getCause());
    assertTrue(stream.isOpen());
    assertSame(cause, assertThrows(java.io.UncheckedIOException.class, () -> new AudioFile(dir.resolve("custom.flac"))).getCause());
  }

  @Test
  void customPathAndStreamResolversEnforceTheCallersMetadataBudget() throws Exception {
    Path path = Files.write(dir.resolve("limited.flac"), flac());
    TagLib.write(path, PropertyMap.builder().put("TITLE", "x".repeat(5000)).build());
    byte[] original = Files.readAllBytes(path);
    var requested = new ReadOptions(false, ReadStyle.FAST, 1024);
    AudioFile.addFileTypeResolver(new AudioFile.StreamTypeResolver() {
      @Override
      public FileBase createFile(Path input, ReadOptions options) throws IOException {
        assertSame(requested, options);
        return new FlacFile(input, options);
      }

      @Override
      public FileBase createFileFromStream(IOStream input, ReadOptions options) throws IOException {
        assertSame(requested, options);
        return new FlacFile(input, options);
      }
    });
    assertInstanceOf(MetadataReadException.class,
      assertThrows(TagLibException.class, () -> new AudioFile(path, requested)));
    try (var stream = new ByteVectorStream(original)) {
      assertInstanceOf(MetadataReadException.class,
        assertThrows(TagLibException.class, () -> new AudioFile(stream, requested)));
      assertTrue(stream.isOpen());
      assertArrayEquals(original, stream.data().toByteArray());
    }
    assertArrayEquals(original, Files.readAllBytes(path));
  }

  @Test
  void resolverCheckedIoFailuresPreserveTheCauseAndBorrowedStream() {
    var failure = new IOException("custom input unavailable");
    AudioFile.addFileTypeResolver((path, options) -> {
      throw failure;
    });
    assertSame(failure, assertThrows(java.io.UncheckedIOException.class,
      () -> new AudioFile(dir.resolve("custom.flac"))).getCause());
    AudioFile.clearFileTypeResolvers();
    AudioFile.addFileTypeResolver(new AudioFile.StreamTypeResolver() {
      @Override
      public FileBase createFile(Path path, ReadOptions options) {
        return null;
      }

      @Override
      public FileBase createFileFromStream(IOStream stream, ReadOptions options) throws IOException {
        throw failure;
      }
    });
    var stream = new ByteVectorStream(flac());
    assertSame(failure, assertThrows(java.io.UncheckedIOException.class, () -> new AudioFile(stream)).getCause());
    assertTrue(stream.isOpen());
  }

  @Test
  void rejectedTypedFileRemainsCallerOwned() {
    var file = new TestFile(new ByteVectorStream(new byte[0]), false);
    assertThrows(IllegalArgumentException.class, () -> new AudioFile(file));
    assertEquals(0, file.closes);
    file.close();
    assertEquals(1, file.closes);
  }

}
