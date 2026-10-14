package cz.skyba.taglib;

import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.Rewrite;
import cz.skyba.taglib.internal.ape.ApeTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetadataOwnershipAndRewriteTest {
  @TempDir
  Path dir;

  @Test
  void metadataSnapshotsDoNotLeakMutableCollectionsOrArtwork() {
    var values = new ArrayList<>(List.of("one", "two"));
    var p = new PropertyMap(Map.of("artist", values));
    values.clear();
    assertEquals(List.of("one", "two"), p.get("Artist"));
    assertThrows(UnsupportedOperationException.class, () -> p.get("ARTIST").add("three"));
    byte[] data = {1, 2};
    var picture = new Picture(3, "image/png", "", data);
    data[0] = 9;
    assertArrayEquals(new byte[]{1, 2}, picture.data());
    picture.data()[1] = 9;
    assertArrayEquals(new byte[]{1, 2}, picture.data());
  }

  @Test
  void failedEditLeavesOriginalUnchanged() throws Exception {
    Path f = dir.resolve("audio");
    byte[] original = {1, 2, 3, 4, 5};
    Files.write(f, original);
    assertThrows(
      IOException.class, () -> Rewrite.replace(f, 4, 2, new byte[0]));
    assertArrayEquals(original, Files.readAllBytes(f));
    assertEquals(1, Files.list(dir).count());
  }

  @Test
  void multipleDisjointEditsPreserveEveryOtherByte() throws Exception {
    Path f = dir.resolve("audio");
    Files.writeString(f, "0123456789");
    Rewrite.replace(
      f,
      List.of(new Rewrite.Edit(7, 1, "XYZ".getBytes()), new Rewrite.Edit(1, 2, "A".getBytes())));
    assertEquals("0A3456XYZ89", Files.readString(f));
  }

  @Test
  void positionalReadRejectsOverflowAndTruncation() throws Exception {
    Path p = dir.resolve("file");
    Files.write(p, new byte[]{1, 2, 3});
    try (var f = new BinaryFile(p)) {
      assertThrows(EOFException.class, () -> f.read(Long.MAX_VALUE, 2));
      assertThrows(EOFException.class, () -> f.read(2, 2));
      assertThrows(EOFException.class, () -> f.read(-1, 1));
      assertArrayEquals(new byte[]{2, 3}, f.read(1, 2));
    }
  }

  @Test
  void apeTextRoundTripKeepsBinaryItems() throws Exception {
    byte[] opaque = {0, 1, 2, 3};
    var source =
      new ApeTag.Data(
        0, 0, PropertyMap.EMPTY, List.of(), List.of(new ApeTag.Item("Opaque", 2, opaque)));
    var props =
      PropertyMap.builder()
        .put("ARTIST", List.of("α", "β"))
        .put("TRACKNUMBER", "2/12")
        .put("DATE", "2026")
        .build();
    byte[] tag = ApeTag.render(props, source);
    Path p = dir.resolve("file");
    Files.write(p, tag);
    try (var f = new BinaryFile(p)) {
      var read = ApeTag.read(f, ReadOptions.DEFAULT);
      assertEquals(props.toBuilder().addUnsupportedData("OPAQUE").build(), read.properties());
      assertEquals(
        List.of("ARTIST", "Opaque", "TRACK", "YEAR"),
        read.items().stream().map(ApeTag.Item::key).toList());
      assertArrayEquals(
        opaque,
        read.items().stream()
          .filter(item -> item.key().equals("Opaque"))
          .findFirst()
          .orElseThrow()
          .value());
    }
  }
}
