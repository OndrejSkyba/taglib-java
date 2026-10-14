package cz.skyba.taglib;

import cz.skyba.taglib.ape.ApeFile;
import cz.skyba.taglib.asf.AsfFile;
import cz.skyba.taglib.dsdiff.DsdiffFile;
import cz.skyba.taglib.dsf.DsfFile;
import cz.skyba.taglib.flac.FlacFile;
import cz.skyba.taglib.it.ItFile;
import cz.skyba.taglib.matroska.MatroskaFile;
import cz.skyba.taglib.mod.ModFile;
import cz.skyba.taglib.mp4.Mp4File;
import cz.skyba.taglib.mpc.MpcFile;
import cz.skyba.taglib.mpeg.MpegFile;
import cz.skyba.taglib.ogg.flac.OggFlacFile;
import cz.skyba.taglib.ogg.opus.OpusFile;
import cz.skyba.taglib.ogg.speex.SpeexFile;
import cz.skyba.taglib.ogg.vorbis.VorbisFile;
import cz.skyba.taglib.riff.aiff.AiffFile;
import cz.skyba.taglib.riff.wav.WavFile;
import cz.skyba.taglib.s3m.S3mFile;
import cz.skyba.taglib.shorten.ShortenFile;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.FileStream;
import cz.skyba.taglib.toolkit.IOStream;
import cz.skyba.taglib.trueaudio.TrueAudioFile;
import cz.skyba.taglib.wavpack.WavPackFile;
import cz.skyba.taglib.xm.XmFile;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class TypedFileIoContractTest {
  @TempDir
  Path directory;

  record FormatCase(String fixture, java.util.function.Function<IOStream, FileBase> open) {
  }

  private static final List<FormatCase> FORMATS = List.of(
    new FormatCase("lame_cbr.mp3", MpegFile::new),
    new FormatCase("sinewave.flac", FlacFile::new),
    new FormatCase("test.ogg", VorbisFile::new),
    new FormatCase("correctness_gain_silent_output.opus", OpusFile::new),
    new FormatCase("empty.spx", SpeexFile::new),
    new FormatCase("empty_flac.oga", OggFlacFile::new),
    new FormatCase("has-tags.m4a", Mp4File::new),
    new FormatCase("silence-1.wma", AsfFile::new),
    new FormatCase("empty.wav", WavFile::new),
    new FormatCase("noise.aif", AiffFile::new),
    new FormatCase("mac-399.ape", ApeFile::new),
    new FormatCase("click.wv", WavPackFile::new),
    new FormatCase("click.mpc", MpcFile::new),
    new FormatCase("tagged.tta", TrueAudioFile::new),
    new FormatCase("empty10ms.dsf", DsfFile::new),
    new FormatCase("empty10ms.dff", DsdiffFile::new),
    new FormatCase("test.mod", ModFile::new),
    new FormatCase("test.s3m", S3mFile::new),
    new FormatCase("test.it", ItFile::new),
    new FormatCase("test.xm", XmFile::new),
    new FormatCase("no-tags.mka", MatroskaFile::new),
    new FormatCase("2sec-silence.shn", ShortenFile::new));

  @Test
  void formatDetectionDoesNotRetryATransientStorageFailure() throws Exception {
    Path path = Files.write(directory.resolve("input.flac"), AudioFileTest.flac());
    var cause = new IOException("transient read failure");
    try (var stream = new FileStream(path) {
      boolean fail = true;

      @Override
      public ByteVector readBlock(long count) {
        if (fail) {
          fail = false;
          throw new UncheckedIOException(cause);
        }
        return super.readBlock(count);
      }
    }) {
      assertSame(cause, assertThrows(UncheckedIOException.class, () -> new AudioFile(stream)).getCause());
      assertTrue(stream.isOpen());
      assertEquals(Format.FLAC, TagLib.read(stream).format());
    }
  }

  @Test
  void formatDetectionDoesNotRetryAnArgumentFailureFromTheStream() {
    var failure = new IllegalArgumentException("custom stream failure");
    try (var stream = new ByteVectorStream(AudioFileTest.flac()) {
      int reads;

      @Override
      public String name() {
        return "input.flac";
      }

      @Override
      public ByteVector readBlock(long count) {
        if (++reads == 1) {
          throw failure;
        }
        return super.readBlock(count);
      }
    }) {
      assertSame(failure, assertThrows(IllegalArgumentException.class, () -> new AudioFile(stream)));
      assertEquals(1, stream.reads);
      assertTrue(stream.isOpen());
    }
  }

  @Test
  void rejectedChannelRemainsCallerOwnedAndPreservesItsFailure() throws Exception {
    Path path = Files.write(directory.resolve("channel.flac"), AudioFileTest.flac());
    try (var channel = new FileStreamIoFailureTest.FaultChannel(path)) {
      channel.failAt = FileStreamIoFailureTest.Operation.READ;
      assertSame(channel.failure, assertThrows(UncheckedIOException.class, () -> new FileStream(channel)).getCause());
      assertTrue(channel.isOpen());
    }
  }

  @TestFactory
  Stream<DynamicTest> openingClosedStreamsFailsBeforeReading() {
    return FORMATS.stream().map(format -> DynamicTest.dynamicTest(format.fixture(), () -> {
      var stream = new ByteVectorStream(new byte[0]) {
        @Override
        public boolean isOpen() {
          return false;
        }

        @Override
        public ByteVector readBlock(long count) {
          throw new AssertionError("Closed streams must not be read");
        }
      };
      assertThrows(IllegalStateException.class, () -> format.open().apply(stream));
    }));
  }

  @TestFactory
  Stream<DynamicTest> openingPreservesStorageFailureAndBorrowedLifetime() {
    return FORMATS.stream().map(format -> DynamicTest.dynamicTest(format.fixture(), () -> {
      Path path = Files.copy(UpstreamAudioFixtures.fixture(format.fixture()), directory.resolve(format.fixture()));
      try (var channel = new FileStreamIoFailureTest.FaultChannel(path); var stream = new FileStream(channel)) {
        channel.failAt = FileStreamIoFailureTest.Operation.READ;
        assertSame(channel.failure, assertThrows(UncheckedIOException.class, () -> format.open().apply(stream)).getCause());
        assertTrue(stream.isOpen());
      }
    }));
  }

  @TestFactory
  Stream<DynamicTest> savingReadOnlyFilesThrowsAndPreservesBytes() {
    return FORMATS.stream().map(format -> DynamicTest.dynamicTest(format.fixture(), () -> {
      Path path = UpstreamAudioFixtures.fixture(format.fixture());
      byte[] original = Files.readAllBytes(path);
      try (var stream = new FileStream(path, true); var file = format.open().apply(stream)) {
        file.tag().setTitle("changed");
        if (file instanceof ShortenFile) {
          assertThrows(UnsupportedOperationException.class, file::save);
        } else {
          assertThrows(TagLibException.class, file::save);
        }
        assertArrayEquals(original, Files.readAllBytes(path));
        assertTrue(stream.isOpen());
      }
    }));
  }

  @TestFactory
  Stream<DynamicTest> savingPreservesStorageFailureAndBorrowedLifetime() {
    return FORMATS.stream().filter(format -> !format.fixture().endsWith(".shn"))
      .map(format -> DynamicTest.dynamicTest(format.fixture(), () -> {
        Path path = Files.copy(UpstreamAudioFixtures.fixture(format.fixture()), directory.resolve(format.fixture()));
        try (var channel = new FileStreamIoFailureTest.FaultChannel(path);
             var stream = new FileStream(channel); var file = format.open().apply(stream)) {
          file.tag().setTitle("changed");
          channel.failAt = FileStreamIoFailureTest.Operation.WRITE;
          assertSame(channel.failure, assertThrows(UncheckedIOException.class, file::save).getCause());
          assertTrue(stream.isOpen());
        }
      }));
  }

  @TestFactory
  Stream<DynamicTest> savingClosedFilesThrowsBeforeWriting() {
    return FORMATS.stream().map(format -> DynamicTest.dynamicTest(format.fixture(), () -> {
      Path path = UpstreamAudioFixtures.fixture(format.fixture());
      try (var stream = new FileStream(path, true); var file = format.open().apply(stream)) {
        stream.close();
        assertThrows(IllegalStateException.class, file::save);
      }
    }));
  }
}
