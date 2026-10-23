package cz.skyba.taglib;

import cz.skyba.taglib.mpeg.MpegFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TagLibEditTest {
  @TempDir
  Path directory;

  @Test
  void constructionDoesNotAccessTheFileAndBuiltRequestsAreIndependent() throws Exception {
    Path path = directory.resolve("later.mp3");
    var values = new ArrayList<>(List.of("First", "Second"));
    var builder = TagLib.edit(path).set("TITLE", "Snapshot").set("ARTIST", values).remove("COMMENT");
    var request = builder.build();
    values.set(0, "Changed input");
    builder.set("TITLE", "Changed builder").remove("ARTIST");
    assertFalse(Files.exists(path));
    Files.copy(AudioFixtures.fixture("lame_cbr.mp3"), path);
    TagLib.write(path, PropertyMap.builder().put("ALBUM", "Keep album").put("COMMENT", "Remove comment").build());

    request.save();

    var after = TagLib.read(path).properties();
    assertEquals("Snapshot", after.first("TITLE"));
    assertEquals(List.of("First", "Second"), after.get("ARTIST"));
    assertEquals("Keep album", after.first("ALBUM"));
    assertFalse(after.containsKey("COMMENT"));

    TagLib.write(path, after.toBuilder().put("ALBUM", "Updated album").build());
    request.save();
    assertEquals("Updated album", TagLib.read(path).properties().first("ALBUM"));
  }

  @Test
  void lastOperationWins() throws Exception {
    Path path = AudioFixtures.copy(directory, "lame_cbr.mp3");
    TagLib.edit(path).set("TITLE", "First").remove("title").set("Title", "Last")
      .set("ARTIST", "Remove").remove("artist").save();
    assertEquals("Last", TagLib.read(path).properties().first("TITLE"));
    assertFalse(TagLib.read(path).properties().containsKey("ARTIST"));
  }

  @Test
  void enumEditsShareStringKeysAndPreserveCustomProperties() throws Exception {
    Path path = AudioFixtures.copy(directory, "lame_cbr.mp3");
    TagLib.edit(path)
      .set("ALBUMARTIST", "Original")
      .set(PropertyKey.ALBUM_ARTIST, List.of("First", "Second"))
      .set(PropertyKey.TRACK_NUMBER, "3/12")
      .set("COMMENT", "Remove")
      .remove(PropertyKey.COMMENT)
      .set("MY_CUSTOM_FIELD", "Custom value")
      .save();

    var properties = TagLib.read(path).properties();
    assertEquals(List.of("First", "Second"), properties.get("ALBUMARTIST"));
    assertEquals("3/12", properties.first(PropertyKey.TRACK_NUMBER));
    assertFalse(properties.containsKey(PropertyKey.COMMENT));
    assertEquals("Custom value", properties.first("MY_CUSTOM_FIELD"));
  }

  @Test
  void savingUsesOneFileSession() throws Exception {
    Path path = AudioFixtures.copy(directory, "lame_cbr.mp3");
    var opens = new AtomicInteger();
    AudioFile.addFileTypeResolver((name, options) -> {
      assertEquals(path, name);
      assertFalse(options.readAudioProperties());
      opens.incrementAndGet();
      return new MpegFile(name, options);
    });
    try {
      TagLib.edit(path).set("TITLE", "Replacement").save();
      assertEquals(1, opens.get());
    } finally {
      AudioFile.clearFileTypeResolvers();
    }
    assertEquals("Replacement", TagLib.read(path).properties().first("TITLE"));
  }

  @Test
  void concurrentEditsToTheSameFilePreserveBothChanges() throws Exception {
    Path path = AudioFixtures.copy(directory, "lame_cbr.mp3");
    TagLib.write(path, PropertyMap.builder().put("TITLE", "Original").put("ARTIST", "Original").build());
    var firstSaving = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var opens = new AtomicInteger();
    AudioFile.addFileTypeResolver((name, options) -> {
      boolean first = opens.incrementAndGet() == 1;
      return new MpegFile(name, options) {
        @Override
        public void save() {
          if (first) {
            firstSaving.countDown();
            try {
              assertTrue(releaseFirst.await(10, TimeUnit.SECONDS), "First edit was not released");
            } catch (InterruptedException failure) {
              Thread.currentThread().interrupt();
              throw new AssertionError(failure);
            }
          }
          super.save();
        }
      };
    });
    var firstSave = new FutureTask<Void>(() -> TagLib.edit(path).set("TITLE", "New title").save(), null);
    var secondSave = new FutureTask<Void>(() -> TagLib.edit(path).set("ARTIST", "New artist").save(), null);
    var firstThread = Thread.ofPlatform().unstarted(firstSave);
    var secondThread = Thread.ofPlatform().unstarted(secondSave);
    try {
      firstThread.start();
      assertTrue(firstSaving.await(10, TimeUnit.SECONDS), "First edit did not reach saving");
      secondThread.start();

      // Observe actual lock contention before releasing the first edit, rather than assuming
      // the second thread has reached save() after a fixed delay.
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (secondThread.getState() != Thread.State.WAITING && secondThread.isAlive()
        && System.nanoTime() < deadline) {
        Thread.sleep(1);
      }
      assertEquals(Thread.State.WAITING, secondThread.getState(), "Second edit did not wait for the lock");
      assertEquals(1, opens.get(), "Second edit opened the file before acquiring the lock");

      releaseFirst.countDown();
      firstSave.get(10, TimeUnit.SECONDS);
      secondSave.get(10, TimeUnit.SECONDS);
    } finally {
      releaseFirst.countDown();
      firstSave.cancel(true);
      secondSave.cancel(true);
      firstThread.join(TimeUnit.SECONDS.toMillis(10));
      secondThread.join(TimeUnit.SECONDS.toMillis(10));
      AudioFile.clearFileTypeResolvers();
    }

    var after = TagLib.read(path).properties();
    assertEquals("New title", after.first("TITLE"));
    assertEquals("New artist", after.first("ARTIST"));
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "lame_cbr.mp3", "sinewave.flac", "test.ogg", "correctness_gain_silent_output.opus",
    "empty.spx", "empty_flac.oga", "has-tags.m4a", "silence-1.wma", "empty.wav",
    "noise.aif", "mac-399.ape", "click.wv", "click.mpc", "tagged.tta",
    "empty10ms.dsf", "empty10ms.dff", "test.mod", "test.s3m", "test.it", "test.xm",
    "no-tags.mka"
  })
  void savingMatchesTheExistingReplacementApi(String fixture) throws Exception {
    Path path = AudioFixtures.copy(directory, fixture);
    Path expected = directory.resolve("expected-" + fixture);
    Files.copy(path, expected);
    var replacement = TagLib.read(expected, ReadOptions.TAGS_ONLY).properties().toBuilder()
      .put("TITLE", "Replacement").remove("COMMENT").build();

    assertTrue(TagLib.write(expected, replacement).rejected().isEmpty());
    TagLib.edit(path).set("TITLE", "Replacement").remove("COMMENT").save();
    assertArrayEquals(Files.readAllBytes(expected), Files.readAllBytes(path));
  }

  @ParameterizedTest
  @ValueSource(strings = {"lame_cbr.mp3", "sinewave.flac", "has-tags.m4a", "empty.ogg",
    "silence-1.wma", "no-tags.mka", "empty10ms.dsf", "empty10ms.dff"})
  void editsPreserveArtworkAccordingToTheReplacementApi(String fixture) throws Exception {
    Path path = AudioFixtures.copy(directory, fixture);
    var picture = new Picture(3, "image/png", "cover", new byte[]{(byte) 137, 80, 78, 71, 1, 2, 3});
    TagLib.write(path, PropertyMap.builder().put("TITLE", "Original").put("COMMENT", "Remove too").build(), List.of(picture));
    var before = TagLib.read(path);
    Path expected = directory.resolve("expected-" + fixture);
    Files.copy(path, expected);
    TagLib.write(expected, before.properties().toBuilder().put("TITLE", "Replacement").remove("COMMENT").build());

    TagLib.edit(path).set("TITLE", "Replacement").remove("COMMENT").save();

    var after = TagLib.read(path);
    assertArrayEquals(Files.readAllBytes(expected), Files.readAllBytes(path));
    assertEquals(before.pictures(), after.pictures());
  }

  @Test
  void clearRemovesDescribedId3Comments() throws Exception {
    Path path = AudioFixtures.copy(directory, "lame_cbr.mp3");
    TagLib.write(path, PropertyMap.builder().put("COMMENT:OTHER", "Remove too").build());
    assertEquals("Remove too", TagLib.read(path).properties().first("COMMENT:OTHER"));

    TagLib.edit(path).remove("COMMENT:OTHER").save();

    assertTrue(TagLib.read(path).properties().isEmpty());
  }

  @ParameterizedTest
  @CsvSource({"has-tags.m4a,1INVALID", "silence-1.wma,ZZZ_UNSUPPORTED", "test.it,ARTIST"})
  void rejectedValuesPreventSavingEarlierClearsOrSets(String fixture, String key) throws Exception {
    Path path = AudioFixtures.copy(directory, fixture);
    byte[] before = Files.readAllBytes(path);

    var failure = assertThrows(MetadataEditException.class,
      () -> TagLib.edit(path).remove("COMMENT").set("TITLE", "Do not save").set(key, "Unsupported").save());

    assertTrue(failure.getMessage().contains(key));
    assertArrayEquals(before, Files.readAllBytes(path));
  }

  @Test
  void validationIsImmediateAndAnEmptyStringFollowsReplacementSemantics() throws Exception {
    Path missing = directory.resolve("missing.mp3");
    var builder = TagLib.edit(missing);
    assertThrows(NullPointerException.class, () -> TagLib.edit(null));
    assertThrows(IllegalArgumentException.class, () -> builder.set("TITLE"));
    assertThrows(IllegalArgumentException.class, () -> builder.set("TITLE", List.of()));
    assertThrows(IllegalArgumentException.class, () -> builder.set("", "value"));
    assertThrows(IllegalArgumentException.class, () -> builder.remove(""));
    assertThrows(IllegalArgumentException.class, () -> builder.set("TITLE", "Invalid\0value"));
    assertThrows(IllegalArgumentException.class, () -> builder.remove("Invalid\0key"));
    assertThrows(NullPointerException.class, () -> builder.set("TITLE", (String) null));
    assertThrows(NullPointerException.class, () -> builder.set((String) null, "value"));
    assertThrows(NullPointerException.class, () -> builder.set((PropertyKey) null, "value"));
    assertThrows(IllegalArgumentException.class, () -> builder.set(PropertyKey.TITLE));
    assertThrows(IllegalArgumentException.class, () -> builder.set(PropertyKey.TITLE, List.of()));
    assertThrows(IllegalArgumentException.class, () -> builder.set(PropertyKey.TITLE, "Invalid\0value"));

    Path path = AudioFixtures.copy(directory, "has-tags.m4a");
    Path expected = directory.resolve("expected.m4a");
    Files.copy(path, expected);
    TagLib.write(expected, TagLib.read(expected, ReadOptions.TAGS_ONLY).properties().toBuilder().put("TITLE", "").build());
    TagLib.edit(path).set("TITLE", "").save();
    assertArrayEquals(Files.readAllBytes(expected), Files.readAllBytes(path));
    TagLib.edit(path).remove("TITLE").save();
    assertFalse(TagLib.read(path).properties().containsKey("TITLE"));
  }

  @Test
  void emptyEditDoesNotOpenTheFileAndMissingSourceRetainsItsCause() {
    Path missing = directory.resolve("missing.mp3");
    assertDoesNotThrow(() -> TagLib.edit(missing).save());
    assertFalse(Files.exists(missing));
    var failure = assertThrows(UncheckedIOException.class,
      () -> TagLib.edit(missing).set("TITLE", "New").save());
    assertInstanceOf(NoSuchFileException.class, failure.getCause());
  }

  @Test
  void shortenRetainsTheReplacementApisReadOnlyBehavior() throws Exception {
    Path path = AudioFixtures.copy(directory, "2sec-silence.shn");
    byte[] before = Files.readAllBytes(path);
    assertThrows(UnsupportedOperationException.class, () -> TagLib.edit(path).remove("TITLE").save());
    assertArrayEquals(before, Files.readAllBytes(path));
  }
}
