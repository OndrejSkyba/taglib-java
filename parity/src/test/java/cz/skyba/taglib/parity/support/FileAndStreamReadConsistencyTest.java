package cz.skyba.taglib.parity.support;

import cz.skyba.taglib.*;
import cz.skyba.taglib.parity.Snapshots;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.FileStream;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Ensures file and borrowed-stream reads produce equivalent results without closing borrowed streams.
 */
final class FileAndStreamReadConsistencyTest {
  @Test
  void upstreamCorpusMatchesMemoryAndFileStreamsForEveryReadMode() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    if (!Files.exists(root.resolve("parity/native/upstream.env"))) root = root.getParent();
    Path corpus = Snapshots.upstreamSource(root).resolve("tests/data");
    assumeTrue(
      Files.isDirectory(corpus), "Run ./parity/run fixtures to provision upstream fixtures");
    List<Path> files;
    try (var walk = Files.walk(corpus)) {
      files = walk.filter(Files::isRegularFile).sorted().toList();
    }
    int accepted = 0, rejected = 0;
    var formats = EnumSet.noneOf(Format.class);
    for (Path path : files) {
      byte[] bytes = Files.readAllBytes(path);
      for (var options :
        List.of(
          ReadOptions.DEFAULT,
          ReadOptions.TAGS_ONLY,
          new ReadOptions(true, ReadStyle.FAST, ReadOptions.DEFAULT.maxMetadataBytes()),
          new ReadOptions(true, ReadStyle.ACCURATE, ReadOptions.DEFAULT.maxMetadataBytes()))) {
        var stream = new NamedMemory(bytes, path.getFileName().toString());
        stream.seek(7); // Parser positions are absolute, independent of the caller's cursor.
        AudioMetadata expected;
        try {
          expected = TagLib.read(path, options);
        } catch (cz.skyba.taglib.TagLibException | java.io.UncheckedIOException failure) {
          Class<? extends RuntimeException> type = failure instanceof java.io.UncheckedIOException
            ? java.io.UncheckedIOException.class : cz.skyba.taglib.TagLibException.class;
          assertThrows(type, () -> TagLib.read(stream, options), path.toString());
          assertFalse(stream.closed);
          assertArrayEquals(bytes, stream.data().toByteArray());
          rejected++;
          continue;
        }
        assertEquals(expected, TagLib.read(stream, options), path + " " + options);
        assertFalse(stream.closed);
        assertArrayEquals(bytes, stream.data().toByteArray());
        try (var fileStream = new FileStream(path, true)) {
          assertEquals(expected, TagLib.read(fileStream, options), path.toString());
          assertTrue(fileStream.isOpen());
        }
        formats.add(expected.format());
        accepted++;
      }
    }
    assertEquals(
      files.size() * 4, accepted + rejected, "Every selected source fixture must be visited");
    if (corpus.equals(root.resolve("work/upstream/verified/taglib-" + Parity.defaultUpstreamVersion() + "/tests/data"))) {
      assertEquals(123 * 4, accepted + rejected, "Default release corpus changed");
      assertEquals(116 * 4, accepted, "Valid default corpus changed");
    }
    assertTrue(rejected > 0, "Malformed fixtures must exercise rejection");
    assertEquals(EnumSet.allOf(Format.class), formats);
  }

  @Test
  void namelessMemoryStreamUsesTheContainerSignature() throws Exception {
    // A complete PCM RIFF header and a two-byte audio payload.
    byte[] wav =
      HexFormat.of()
        .parseHex(
          "524946462600000057415645666d7420100000000100010044ac0000885801000200100064617461020000000000");
    var stream = new NamedMemory(wav, "");
    assertEquals(Format.WAV, TagLib.read(stream).format());
    assertFalse(stream.closed);
  }

  private static final class NamedMemory extends ByteVectorStream {
    private final String name;
    boolean closed;

    NamedMemory(byte[] bytes, String name) {
      super(bytes);
      this.name = name;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}
