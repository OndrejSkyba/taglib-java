// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;

import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PropertyReplacementTest {
  @Test
  void reportsPartialAcceptanceWithoutLosingDuplicateValueOccurrences() {
    var tag = new cz.skyba.taglib.id3.v1.Tag();
    tag.setArtist("old artist");
    var requested = PropertyMap.builder()
      .put("TITLE", List.of("same", "same"))
      .put("DATE", "invalid")
      .put("PRIVATE", "unsupported")
      .put("EMPTY", List.of())
      .build();

    var result = tag.replaceProperties(requested);

    assertEquals(PropertyMap.builder().put("TITLE", "same").put("EMPTY", List.of()).build(),
      result.accepted());
    assertEquals(PropertyMap.builder().put("TITLE", "same").put("DATE", "invalid")
      .put("PRIVATE", "unsupported").build(), result.rejected());
    assertEquals("same", tag.title());
    assertEquals("", tag.artist());
    assertEquals(List.of("same", "same"), requested.get("TITLE"));
  }

  @Test
  void distinguishesCompleteAcceptanceFromCompleteRejection() {
    var requested = PropertyMap.builder().put("TITLE", "title").build();
    var accepted = new cz.skyba.taglib.ogg.XiphComment().replaceProperties(requested);
    assertEquals(requested, accepted.accepted());
    assertEquals(PropertyMap.EMPTY, accepted.rejected());

    var rejected = new cz.skyba.taglib.shorten.Tag().replaceProperties(requested);
    assertEquals(PropertyMap.EMPTY, rejected.accepted());
    assertEquals(requested, rejected.rejected());
  }

  @Test
  void sessionReplacementClearsOmittedKeysAndPersistsOnlyOnSave() {
    try (var stream = new ByteVectorStream(AudioFileTest.flac())) {
      var original = stream.data().toByteArray();
      try (var file = new AudioFile(stream, ReadOptions.TAGS_ONLY)) {
        file.tag().setArtist("staged artist");
        var result = file.replaceProperties(PropertyMap.builder()
          .put("TITLE", "replacement").put("A=B", "invalid key").build());

        assertEquals(PropertyMap.builder().put("TITLE", "replacement").build(), result.accepted());
        assertEquals(PropertyMap.builder().put("A=B", "invalid key").build(), result.rejected());
        assertEquals("", file.tag().artist());
        assertArrayEquals(original, stream.data().toByteArray());
        file.save();
      }
      stream.seek(0);
      try (var file = new AudioFile(stream, ReadOptions.TAGS_ONLY)) {
        assertEquals("replacement", file.tag().title());
        assertEquals("", file.tag().artist());
        assertFalse(file.properties().containsKey("A=B"));
      }
    }
  }
}
