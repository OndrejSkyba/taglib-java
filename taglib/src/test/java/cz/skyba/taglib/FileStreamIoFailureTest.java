package cz.skyba.taglib;

import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.FileStream;
import cz.skyba.taglib.toolkit.IOStream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class FileStreamIoFailureTest {
  @TempDir
  Path directory;

  enum Operation {READ, WRITE, SIZE, TRUNCATE, CLOSE}

  record FailureCase(String name, Operation operation, Consumer<FileStream> action) {
  }

  @TestFactory
  List<DynamicTest> streamOperationsPreserveIoFailures() {
    return List.of(
      new FailureCase("read", Operation.READ, s -> s.readBlock(4)),
      new FailureCase("read size", Operation.SIZE, s -> s.readBlock(4)),
      new FailureCase("seek from end", Operation.SIZE, s -> s.seek(0, IOStream.Position.END)),
      new FailureCase("length", Operation.SIZE, FileStream::length),
      new FailureCase("write", Operation.WRITE, s -> s.writeBlock(new ByteVector("x"))),
      new FailureCase("insert read", Operation.READ, s -> s.insert(new ByteVector("xx"), 0, 1)),
      new FailureCase("insert write", Operation.WRITE, s -> s.insert(new ByteVector("xx"), 0, 1)),
      new FailureCase("remove read", Operation.READ, s -> s.removeBlock(0, 1)),
      new FailureCase("remove write", Operation.WRITE, s -> s.removeBlock(0, 1)),
      new FailureCase("remove truncate", Operation.TRUNCATE, s -> s.removeBlock(0, 1)),
      new FailureCase("truncate", Operation.TRUNCATE, s -> s.truncate(1)),
      new FailureCase("extend", Operation.WRITE, s -> s.truncate(10)),
      new FailureCase("close", Operation.CLOSE, FileStream::close)
    ).stream().map(c -> DynamicTest.dynamicTest(c.name(), () -> {
      Path path = Files.writeString(directory.resolve(c.name()), "abcd");
      try (var channel = new FaultChannel(path); var stream = new FileStream(channel)) {
        channel.failAt = c.operation();
        try {
          var failure = assertThrows(UncheckedIOException.class, () -> c.action().accept(stream));
          assertSame(channel.failure, failure.getCause());
          assertEquals(1, channel.failures, "Stop at the first I/O failure");
        } finally {
          channel.failAt = null;
        }
      }
    })).toList();
  }

  @Test
  void noProgressReadsAndWritesFailInsteadOfReturningSuccessOrLooping() throws Exception {
    Path path = Files.writeString(directory.resolve("no-progress"), "abcd");
    try (var channel = new FaultChannel(path); var stream = new FileStream(channel)) {
      channel.noProgress = Operation.READ;
      assertThrows(UncheckedIOException.class, () -> stream.readBlock(4));
      assertEquals(0, stream.tell());
      channel.noProgress = Operation.WRITE;
      assertThrows(UncheckedIOException.class, () -> stream.writeBlock(new ByteVector("x")));
      assertThrows(UncheckedIOException.class, () -> stream.truncate(10));
      assertEquals(0, stream.tell());
      assertEquals("abcd", Files.readString(path));
    }
  }

  @Test
  void shortReadsAndWritesStillCompleteAndEofReturnsAvailableData() throws Exception {
    Path path = Files.writeString(directory.resolve("short-io"), "abcd");
    try (var channel = new FaultChannel(path); var stream = new FileStream(channel)) {
      channel.maxTransfer = 1;
      assertEquals("abcd", stream.readBlock(8).toString());
      assertTrue(stream.readBlock(1).isEmpty());
      stream.seek(0);
      stream.writeBlock(new ByteVector("1234"));
      assertEquals(4, stream.tell());
      assertEquals("1234", Files.readString(path));
    }
  }

  @Test
  void fileShiftsCompleteShortTransfersWithoutCorruptingTheTail() throws Exception {
    byte[] original = new byte[150_000];
    new Random(42).nextBytes(original);
    Path path = Files.write(directory.resolve("short-shift"), original);
    try (var channel = new FaultChannel(path); var stream = new FileStream(channel)) {
      channel.maxTransfer = 997;
      stream.insert(new ByteVector("xyz"), 3, 1);
      byte[] expected = new byte[original.length + 2];
      System.arraycopy(original, 0, expected, 0, 3);
      System.arraycopy(new byte[]{'x', 'y', 'z'}, 0, expected, 3, 3);
      System.arraycopy(original, 4, expected, 6, original.length - 4);
      assertArrayEquals(expected, Files.readAllBytes(path));
      assertEquals(expected.length, stream.tell());
      stream.insert(new ByteVector(new byte[]{original[3]}), 3, 3);
      assertArrayEquals(original, Files.readAllBytes(path));
      assertEquals(original.length, stream.tell());
    }
  }

  @Test
  void failureAfterPartialWriteReportsTheErrorAndRetainsTheActualPosition() throws Exception {
    Path path = Files.writeString(directory.resolve("partial-write"), "abcd");
    try (var channel = new FaultChannel(path); var stream = new FileStream(channel)) {
      channel.maxTransfer = 1;
      channel.transfersBeforeFailure = 1;
      channel.failAt = Operation.WRITE;
      assertSame(channel.failure, assertThrows(UncheckedIOException.class,
        () -> stream.writeBlock(new ByteVector("1234"))).getCause());
      assertEquals(1, stream.tell());
      assertEquals("1bcd", Files.readString(path));
      assertEquals(1, channel.failures);
    }
  }

  @Test
  void readOnlyResizeReportsFailureAndLeavesTheFileUnchanged() throws Exception {
    Path path = Files.writeString(directory.resolve("read-only"), "abcd");
    try (var stream = new FileStream(path, true)) {
      assertThrows(NonWritableChannelException.class, () -> stream.truncate(1));
      assertEquals("abcd", Files.readString(path));
    }
  }

  @Test
  void failedSizeQueryPreservesTheStreamPosition() throws Exception {
    Path path = Files.writeString(directory.resolve("size"), "abcd");
    try (var channel = new FaultChannel(path); var stream = new FileStream(channel)) {
      stream.seek(2);
      channel.failAt = Operation.SIZE;
      assertSame(channel.failure, assertThrows(UncheckedIOException.class, stream::length).getCause());
      assertEquals(2, stream.tell());
    }
  }

  @Test
  void audioFileSaveReportsTheOriginalWriteFailureAndStops() throws Exception {
    Path path = Files.write(directory.resolve("save.flac"), AudioFileTest.flac());
    byte[] original = Files.readAllBytes(path);
    try (var channel = new FaultChannel(path); var stream = new FileStream(channel);
         var audio = new AudioFile(stream, ReadOptions.TAGS_ONLY)) {
      audio.tag().setTitle("new title");
      channel.failAt = Operation.WRITE;
      assertSame(channel.failure, assertThrows(UncheckedIOException.class, audio::save).getCause());
      assertEquals(1, channel.failures);
      assertArrayEquals(original, Files.readAllBytes(path));
      assertTrue(stream.isOpen());
    }
  }

  @Test
  void facadeWriteReportsTheOriginalWriteFailureAndBorrowsTheStream() throws Exception {
    Path path = Files.write(directory.resolve("facade.flac"), AudioFileTest.flac());
    byte[] original = Files.readAllBytes(path);
    try (var channel = new FaultChannel(path); var stream = new FileStream(channel)) {
      channel.failAt = Operation.WRITE;
      assertSame(channel.failure, assertThrows(UncheckedIOException.class,
        () -> TagLib.write(stream, PropertyMap.builder().put("TITLE", "new title").build())).getCause());
      assertEquals(1, channel.failures);
      assertArrayEquals(original, Files.readAllBytes(path));
      assertTrue(stream.isOpen());
    }
  }

  @Test
  void audioFileSaveReportsReadFailuresWithoutOverwritingUnreadBytes() throws Exception {
    Path path = Files.write(directory.resolve("save-read.flac"), AudioFileTest.flac());
    byte[] original = Files.readAllBytes(path);
    try (var channel = new FaultChannel(path); var stream = new FileStream(channel);
         var audio = new AudioFile(stream, ReadOptions.TAGS_ONLY)) {
      audio.tag().setTitle("new title");
      channel.failAt = Operation.READ;
      assertSame(channel.failure, assertThrows(UncheckedIOException.class, audio::save).getCause());
      assertEquals(1, channel.failures);
      assertArrayEquals(original, Files.readAllBytes(path));
    }
  }

  @Test
  void failedReadIsNotMistakenForAnEmptyOrUnsupportedFile() throws Exception {
    Path path = Files.write(directory.resolve("read.flac"), AudioFileTest.flac());
    try (var channel = new FaultChannel(path); var stream = new FileStream(channel)) {
      channel.failAt = Operation.READ;
      assertSame(channel.failure, assertThrows(UncheckedIOException.class, () -> TagLib.read(stream)).getCause());
      assertTrue(stream.isOpen());
    }
  }

  /**
   * A real channel with deterministic failures, avoiding filesystem or permission dependencies.
   */
  static final class FaultChannel extends FileChannel {
    private final FileChannel delegate;
    final IOException failure = new IOException("injected storage failure");
    Operation failAt, noProgress;
    int failures, transfersBeforeFailure, maxTransfer = Integer.MAX_VALUE;

    FaultChannel(Path path) throws IOException {
      delegate = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    private void check(Operation operation) throws IOException {
      if (failAt == operation && transfersBeforeFailure-- <= 0) {
        failures++;
        throw failure;
      }
    }

    @Override
    public int read(ByteBuffer buffer, long position) throws IOException {
      check(Operation.READ);
      if (noProgress == Operation.READ) return 0;
      int limit = buffer.limit();
      buffer.limit(buffer.position() + Math.min(buffer.remaining(), maxTransfer));
      try {
        return delegate.read(buffer, position);
      } finally {
        buffer.limit(limit);
      }
    }

    @Override
    public int write(ByteBuffer buffer, long position) throws IOException {
      check(Operation.WRITE);
      if (noProgress == Operation.WRITE) return 0;
      int limit = buffer.limit();
      buffer.limit(buffer.position() + Math.min(buffer.remaining(), maxTransfer));
      try {
        return delegate.write(buffer, position);
      } finally {
        buffer.limit(limit);
      }
    }

    @Override
    public long size() throws IOException {
      check(Operation.SIZE);
      return delegate.size();
    }

    @Override
    public FileChannel truncate(long size) throws IOException {
      check(Operation.TRUNCATE);
      delegate.truncate(size);
      return this;
    }

    @Override
    protected void implCloseChannel() throws IOException {
      delegate.close();
      check(Operation.CLOSE);
    }

    @Override
    public long position() throws IOException {
      return delegate.position();
    }

    @Override
    public FileChannel position(long value) throws IOException {
      delegate.position(value);
      return this;
    }

    @Override
    public void force(boolean metadata) throws IOException {
      delegate.force(metadata);
    }

    @Override
    public int read(ByteBuffer buffer) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int write(ByteBuffer buffer) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long read(ByteBuffer[] buffers, int offset, int length) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long write(ByteBuffer[] buffers, int offset, int length) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long transferTo(long position, long count, WritableByteChannel target) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long transferFrom(ReadableByteChannel source, long position, long count) {
      throw new UnsupportedOperationException();
    }

    @Override
    public MappedByteBuffer map(MapMode mode, long position, long size) {
      throw new UnsupportedOperationException();
    }

    @Override
    public FileLock lock(long position, long size, boolean shared) {
      throw new UnsupportedOperationException();
    }

    @Override
    public FileLock tryLock(long position, long size, boolean shared) {
      throw new UnsupportedOperationException();
    }
  }
}
