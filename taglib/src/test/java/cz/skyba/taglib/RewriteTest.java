package cz.skyba.taglib;

import cz.skyba.taglib.internal.Rewrite;
import cz.skyba.taglib.internal.WriteGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RewriteTest {
  @TempDir
  Path dir;

  @Test
  void inPlaceMixedEditsMatchIndependentSplicingAcrossBufferBoundaries() throws Exception {
    var random = new Random(0x7a611bL);
    Path file = dir.resolve("audio");
    for (int trial = 0; trial < 100; trial++) {
      byte[] source = new byte[200_000 + random.nextInt(200_000)];
      random.nextBytes(source);
      var edits = new ArrayList<Rewrite.Edit>();
      for (int position = 0; position < source.length; ) {
        int offset = Math.min(source.length, position + random.nextInt(20_000));
        int removed = random.nextInt(Math.min(90_000, source.length - offset) + 1);
        byte[] inserted = new byte[random.nextInt(90_000)];
        random.nextBytes(inserted);
        edits.add(new Rewrite.Edit(offset, removed, inserted));
        position = offset + Math.max(1, removed);
      }
      var expected = new ByteArrayOutputStream();
      int cursor = 0;
      for (var edit : edits) {
        expected.write(source, cursor, (int) edit.offset() - cursor);
        expected.writeBytes(edit.insertion());
        cursor = (int) (edit.offset() + edit.removeLength());
      }
      expected.write(source, cursor, source.length - cursor);
      Collections.shuffle(edits, random);
      Files.write(file, source);
      var identity = Files.readAttributes(file, BasicFileAttributes.class).fileKey();
      WriteGuard.run(file, () -> Rewrite.replace(file, edits));
      assertArrayEquals(expected.toByteArray(), Files.readAllBytes(file), "trial " + trial);
      assertEquals(identity, Files.readAttributes(file, BasicFileAttributes.class).fileKey());
    }
  }

  @Test
  void identicalEditsDoNotReplaceOrTouchTheFile() throws Exception {
    Path file = dir.resolve("audio");
    byte[] bytes = new byte[200_000];
    new Random(7).nextBytes(bytes);
    Files.write(file, bytes);
    var before = Files.readAttributes(file, BasicFileAttributes.class);
    Rewrite.replace(file, 0, bytes.length, bytes);
    var after = Files.readAttributes(file, BasicFileAttributes.class);
    assertEquals(before.fileKey(), after.fileKey());
    assertEquals(before.lastModifiedTime(), after.lastModifiedTime());
    assertArrayEquals(bytes, Files.readAllBytes(file));
  }

  @Test
  void equalLengthChangesPreserveHardLinkIdentityAndUntouchedBytes() throws Exception {
    Path file = dir.resolve("audio");
    byte[] bytes = new byte[300_000];
    new Random(55).nextBytes(bytes);
    Files.write(file, bytes);
    Path link = dir.resolve("hardlink");
    Files.createLink(link, file);
    bytes[128_001] ^= 0x55;
    bytes[220_999] ^= 0x55;
    Rewrite.replace(file, 0, bytes.length, bytes);
    assertArrayEquals(bytes, Files.readAllBytes(file));
    assertArrayEquals(bytes, Files.readAllBytes(link));
  }

  @Test
  void invalidLaterEditAndStaleSnapshotAreRejectedBeforeFirstMutation() throws Exception {
    Path file = dir.resolve("audio");
    byte[] bytes = {1, 2, 3, 4, 5};
    Files.write(file, bytes);
    assertThrows(
      IOException.class,
      () ->
        Rewrite.replace(
          file,
          List.of(
            new Rewrite.Edit(0, 1, new byte[]{9}), new Rewrite.Edit(4, 2, new byte[0]))));
    assertArrayEquals(bytes, Files.readAllBytes(file));
    assertThrows(
      IOException.class,
      () ->
        WriteGuard.run(
          file,
          () -> {
            Files.write(file, new byte[]{6}, StandardOpenOption.APPEND);
            Rewrite.replace(file, 0, 1, new byte[]{9});
          }));
    assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6}, Files.readAllBytes(file));
  }

  @Test
  void allDeleteEmptyInsertAndSharedEndsAreHandled() throws Exception {
    Path file = dir.resolve("audio");
    Files.writeString(file, "abcXYZdef");
    Rewrite.replace(file, 0, 9, "abcQdef".getBytes());
    assertEquals("abcQdef", Files.readString(file));
    Rewrite.replace(file, 0, 7, new byte[0]);
    assertEquals(0, Files.size(file));
    Rewrite.replace(file, 0, 0, "restored".getBytes());
    assertEquals("restored", Files.readString(file));
  }

  @Test
  void adjacentReplacementsAndSameOffsetInsertionsKeepCallerOrder() throws Exception {
    Path file = dir.resolve("audio");
    var edits =
      List.of(
        new Rewrite.Edit(1, 0, "first".getBytes()),
        new Rewrite.Edit(1, 0, "second".getBytes()),
        new Rewrite.Edit(1, 2, "third".getBytes()),
        new Rewrite.Edit(3, 1, new byte[0]),
        new Rewrite.Edit(4, 0, "last".getBytes()));
    Files.writeString(file, "012345");
    Rewrite.replace(file, edits);
    assertEquals("0firstsecondthirdlast45", Files.readString(file));
  }
}
