package cz.skyba.taglib;

import cz.skyba.taglib.flac.FlacFile;
import cz.skyba.taglib.riff.RiffFile;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.FileStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class PublicExceptionContractTest {
  @TempDir
  Path directory;

  @AfterEach
  void clearResolvers() {
    AudioFile.clearFileTypeResolvers();
  }

  @Test
  void metadataOperationsComposeWithStandardFunctionalInterfaces() throws Exception {
    Path path = Files.write(directory.resolve("input.flac"), AudioFileTest.flac());
    Function<Path, AudioFile> open = AudioFile::new;
    Function<Path, FlacFile> typedOpen = FlacFile::new;
    Function<Path, cz.skyba.taglib.mpeg.Properties> properties = cz.skyba.taglib.mpeg.Properties::new;
    Function<Path, FileStream> streams = FileStream::new;
    Consumer<FileBase> save = FileBase::save;
    try (var file = open.apply(path)) {
      file.tag().setTitle("lambda");
      save.accept(file.file());
    }
    try (var file = typedOpen.apply(path); var stream = streams.apply(path)) {
      assertEquals("lambda", file.tag().title());
      assertTrue(stream.isOpen());
    }
    assertTrue(properties.apply(AudioFixtures.fixture("lame_cbr.mp3")).sampleRate() > 0);
    assertEquals(List.of("lambda"), List.of(path).stream().map(TagLib::read)
      .map(metadata -> metadata.tag().title()).toList());
    var document = MetadataDocument.open(path);
    Consumer<MetadataDocument> documentSave = MetadataDocument::save;
    documentSave.accept(document);
  }

  @Test
  void asynchronousReadsPreserveTheOperationalFailure() {
    Path missing = directory.resolve("missing.flac");
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var future = CompletableFuture.supplyAsync(() -> TagLib.read(missing), executor);
      var completion = assertThrows(CompletionException.class, future::join);
      var failure = assertInstanceOf(UncheckedIOException.class, completion.getCause());
      assertInstanceOf(java.nio.file.NoSuchFileException.class, failure.getCause());
    }
  }

  @Test
  void formatRejectionIsPublicAndDistinctFromStorageFailure() {
    var input = new ByteVectorStream(new byte[]{1, 2, 3});
    assertThrows(InvalidAudioFileException.class, () -> TagLib.read(input));
    assertTrue(input.isOpen());
  }

  @Test
  void missingPropertyInputIsAStorageFailure() {
    var failure = assertThrows(UncheckedIOException.class,
      () -> new cz.skyba.taglib.mpeg.Properties(directory.resolve("missing.mp3")));
    assertInstanceOf(java.nio.file.NoSuchFileException.class, failure.getCause());
  }

  @Test
  void borrowedPropertyInputPreservesItsStorageFailure() {
    var cause = new IOException("storage unavailable");
    var original = new UncheckedIOException(cause);
    var stream = new ByteVectorStream(new byte[32]) {
      @Override
      public cz.skyba.taglib.toolkit.ByteVector readBlock(long count) {
        throw original;
      }
    };
    var failure = assertThrows(UncheckedIOException.class,
      () -> new cz.skyba.taglib.mpeg.Properties(stream));
    assertSame(original, failure);
    assertSame(cause, failure.getCause());
    assertTrue(stream.isOpen());
  }

  @Test
  void facadePropagatesCustomSnapshotAndEditIoFailures() throws Exception {
    Path path = Files.write(directory.resolve("input.flac"), AudioFileTest.flac());
    var cause = new IOException("custom storage failure");
    var original = new UncheckedIOException(cause);
    AudioFile.addFileTypeResolver((input, options) ->
      new FlacFile(new ByteVectorStream(AudioFileTest.flac()), options) {
        @Override
        public PropertyMap properties() {
          throw original;
        }

        @Override
        public PropertyUpdateResult replaceProperties(PropertyMap values) {
          throw original;
        }
      });
    assertSame(original, assertThrows(UncheckedIOException.class, () -> TagLib.read(path)));
    assertSame(original, assertThrows(UncheckedIOException.class,
      () -> TagLib.write(path, PropertyMap.EMPTY)));
  }

  @Test
  void resolverArgumentAndCapabilityErrorsKeepTheirIdentity() {
    var argument = new IllegalArgumentException("resolver configuration");
    AudioFile.addFileTypeResolver((path, options) -> {
      throw argument;
    });
    assertSame(argument, assertThrows(IllegalArgumentException.class,
      () -> new AudioFile(directory.resolve("input.flac"))));
    AudioFile.clearFileTypeResolvers();
    var unsupported = new UnsupportedOperationException("resolver capability");
    AudioFile.addFileTypeResolver((path, options) -> {
      throw unsupported;
    });
    assertSame(unsupported, assertThrows(UnsupportedOperationException.class,
      () -> new AudioFile(directory.resolve("input.flac"))));
  }

  @Test
  void customFrameFactoryErrorsAreNotReclassifiedAsDamagedInput() {
    var failure = new IllegalArgumentException("frame factory configuration");
    var tag = new cz.skyba.taglib.id3.v2.Tag();
    tag.setTitle("original");
    var bytes = tag.render().append(new cz.skyba.taglib.toolkit.ByteVector(AudioFileTest.flac()));
    var input = new ByteVectorStream(bytes);
    var factory = new cz.skyba.taglib.id3.v2.FrameFactory() {
      @Override
      public cz.skyba.taglib.id3.v2.Frame createFrame(cz.skyba.taglib.toolkit.ByteVector data,
                                                      cz.skyba.taglib.id3.v2.Header header) {
        throw failure;
      }
    };
    assertSame(failure, assertThrows(IllegalArgumentException.class,
      () -> new FlacFile(input, ReadOptions.TAGS_ONLY, factory)));
    assertTrue(input.isOpen());
  }

  @Test
  void rejectedResolverResourcePreservesThePrimaryAndCleanupFailures() {
    var cleanup = new TagLibException("close failed", new IOException("storage close failure"));
    var resource = new AudioFileTest.TestFile(new ByteVectorStream(new byte[0]), false) {
      @Override
      public void close() {
        super.close();
        throw cleanup;
      }
    };
    AudioFile.addFileTypeResolver((path, options) -> resource);
    var failure = assertThrows(InvalidAudioFileException.class,
      () -> new AudioFile(directory.resolve("input.flac")));
    assertArrayEquals(new Throwable[]{cleanup}, failure.getSuppressed());
    assertEquals(1, resource.closes);
  }

  @Test
  void failedOwnedConstructionClosesTheSourceAndPreservesTheFailure() {
    var original = new UncheckedIOException(new IOException("read failed"));
    var cleanup = new UncheckedIOException(new IOException("close failed"));
    var stream = new ByteVectorStream(new byte[16]) {
      int closes;

      @Override
      public cz.skyba.taglib.toolkit.ByteVector readBlock(long count) {
        throw original;
      }

      @Override
      public void close() {
        closes++;
        throw cleanup;
      }
    };
    class Owner extends RiffFile {
      Owner() {
        super(stream, true, Endianness.LittleEndian, ReadOptions.TAGS_ONLY);
      }

      public TagBase tag() {
        return null;
      }

      public AudioProperties audioProperties() {
        return null;
      }

      public void save() {
        throw new cz.skyba.taglib.TagLibException("Test save rejected");
      }
    }
    assertSame(original, assertThrows(UncheckedIOException.class, Owner::new));
    assertEquals(1, stream.closes);
    assertArrayEquals(new Throwable[]{cleanup}, original.getSuppressed());
  }
}
