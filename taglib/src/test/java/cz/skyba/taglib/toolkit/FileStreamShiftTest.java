package cz.skyba.taglib.toolkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class FileStreamShiftTest {
  @TempDir
  Path directory;

  @Test
  void shiftsPreserveBytesAndCursorAcrossCopyBoundaries() throws Exception {
    byte[] original = new byte[3 * 65536 + 17];
    new Random(42).nextBytes(original);
    Path path = directory.resolve("shift.bin");
    for (int start : new int[]{0, 65535, original.length - 1, original.length}) {
      for (int removed : new int[]{0, 1, 65535, 65536, 65537}) {
        if (removed > original.length - start) continue;
        for (int inserted : new int[]{0, 1, 1025, 65535, 65536, 65537, 131073}) {
          byte[] replacement = new byte[inserted];
          new Random(inserted).nextBytes(replacement);
          byte[] expected = new byte[original.length - removed + inserted];
          System.arraycopy(original, 0, expected, 0, start);
          System.arraycopy(replacement, 0, expected, start, inserted);
          System.arraycopy(original, start + removed, expected, start + inserted,
            original.length - start - removed);
          Files.write(path, original);
          Object identity = Files.readAttributes(path, BasicFileAttributes.class).fileKey();
          try (var stream = new FileStream(path)) {
            stream.insert(new ByteVector(replacement), start, removed);
            assertArrayEquals(expected, Files.readAllBytes(path),
              "start=" + start + ", removed=" + removed + ", inserted=" + inserted);
            assertEquals(inserted == removed ? start + inserted : expected.length, stream.tell());
            assertEquals(expected.length, stream.length());
          }
          assertEquals(identity, Files.readAttributes(path, BasicFileAttributes.class).fileKey());
        }
      }
    }
  }
}
