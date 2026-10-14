package cz.skyba.taglib;

import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.trueaudio.TrueAudioFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PropertyValuesAndUnsupportedDataTest {
  @Test
  void constructorUsesAsciiFoldingAndNativeSortedMergeOrder() {
    var source = new LinkedHashMap<String, List<String>>();
    source.put("title", List.of("lower"));
    source.put("TITLE", List.of("upper"));
    source.put("éx", List.of("unicode"));
    var map = new PropertyMap(source);
    assertEquals(List.of("upper", "lower"), map.get("title"));
    assertTrue(map.containsKey("éX"));
    assertFalse(map.containsKey("ÉX"));
  }

  @Test
  void constructorRejectsEmptyKeyButInsertionAcceptsIt() {
    var map = new PropertyMap(Map.of("", List.of("ignored")));
    assertTrue(map.isEmpty());
    assertEquals(List.of(""), map.unsupportedData());
    var builder = PropertyMap.builder();
    assertSame(builder, builder.add("", List.of("empty")));
    assertSame(builder, builder.add("a=b", List.of("equals")));
    assertSame(builder, builder.add("a\0b", List.of("nul")));
    assertEquals(3, builder.build().size());
    assertEquals(List.of(), builder.build().unsupportedData());
    assertEquals("empty", builder.build().first(""));
  }

  @Test
  void insertionReplacementMergeAndEraseFollowNativeOperations() {
    var builder = PropertyMap.builder();
    builder.add("title", List.of("one"));
    builder.add("TITLE", List.of("two"));
    var snapshot = builder.build();
    assertEquals(List.of("one", "two"), snapshot.get("title"));
    builder.put("title", List.of("three"));
    var other =
      PropertyMap.builder()
        .put("title", "four")
        .put("artist", "a")
        .addUnsupportedData("opaque")
        .build();
    builder.merge(other);
    var merged = builder.build();
    assertEquals(List.of("three", "four"), merged.get("title"));
    assertEquals(List.of("opaque"), merged.unsupportedData());
    assertTrue(merged.containsAll(PropertyMap.builder().put("artist", "a").build()));
    assertFalse(merged.containsAll(other));
    builder.removeAll(PropertyMap.builder().put("title", "unrelated value").build());
    assertFalse(builder.build().containsKey("title"));
    assertEquals(List.of("one", "two"), snapshot.get("title"));
  }

  @Test
  void unsupportedDataAffectsEqualityAndNativeTextOutput() {
    var base =
      PropertyMap.builder()
        .put("title", List.of("one", "two"))
        .put("empty", List.<String>of())
        .build();
    var extra =
      base.toBuilder().addUnsupportedData("frame/A").addUnsupportedData("frame/B").build();
    assertNotEquals(base, extra);
    assertTrue(base.containsAll(extra));
    assertEquals("EMPTY=\nTITLE=one, two\nUnsupported Data: frame/A, frame/B\n", extra.toString());
    assertEquals(List.of("fallback"), extra.getOrDefault("absent", List.of("fallback")));
    assertEquals(List.of(), extra.getOrDefault("empty", List.of("fallback")));
    assertEquals(PropertyMap.EMPTY, extra.toBuilder().clear().build());
    var clean = extra.toBuilder().removeEmpty().build();
    assertFalse(clean.containsKey("empty"));
    assertEquals(List.of("frame/A", "frame/B"), clean.unsupportedData());
    assertEquals("TITLE=one, two\nUnsupported Data: frame/A, frame/B\n", clean.toString());
    assertTrue(extra.containsKey("empty"));
    assertEquals(List.of("frame/A", "frame/B"), extra.unsupportedData());
  }

  @Test
  void existingKeysDoNotInspectOrCopyUnusedFallbacks() {
    var map = PropertyMap.builder().put("TITLE", "stored").put("EMPTY", List.of()).build();
    List<String> unusable = new AbstractList<>() {
      @Override
      public String get(int index) {
        throw new AssertionError("Unused fallback read");
      }

      @Override
      public int size() {
        throw new AssertionError("Unused fallback inspected");
      }
    };
    for (String key : List.of("title", "empty")) {
      assertSame(map.get(key), map.getOrDefault(key, unusable));
      assertSame(map.get(key), map.getOrDefault(key, null));
      assertSame(map.get(key), map.getOrDefault(key, Collections.singletonList(null)));
    }
  }

  @Test
  void missingKeysCopyAndValidateOnlyTheRequiredFallback() {
    var map = PropertyMap.builder().put("TITLE", "stored").build();
    var fallback = new ArrayList<>(List.of("default"));
    var returned = map.getOrDefault("absent", fallback);
    fallback.clear();
    assertEquals(List.of("default"), returned);
    assertThrows(UnsupportedOperationException.class, () -> returned.add("changed"));
    assertFalse(map.containsKey("absent"));
    assertThrows(NullPointerException.class, () -> map.getOrDefault("absent", null));
    assertThrows(NullPointerException.class,
      () -> map.getOrDefault("absent", Collections.singletonList(null)));
  }

  @Test
  void removeEmptyRetainsIdentifiersEvenWithoutAnyPropertyEntries() {
    var builder = PropertyMap.builder().addUnsupportedData("opaque").put("EMPTY", List.of());
    var original = builder.build();
    assertSame(builder, builder.removeEmpty());
    assertTrue(builder.build().isEmpty());
    assertEquals(List.of("opaque"), builder.build().unsupportedData());
    assertSame(builder, builder.clear());
    assertEquals(PropertyMap.EMPTY, builder.build());
    assertTrue(original.containsKey("EMPTY"));
    assertEquals(List.of("opaque"), original.unsupportedData());
  }

  @Test
  void clearingIdentifiersPreservesAllValuesAndComposesWithRemoveEmpty() {
    var builder = PropertyMap.builder().put("TITLE", List.of("one", "two"))
      .put("BLANK", "").put("EMPTY", List.of()).addUnsupportedData("opaque");
    var original = builder.build();
    assertSame(builder, builder.clearUnsupportedData());
    assertEquals(original.asMap(), builder.build().asMap());
    assertTrue(builder.build().unsupportedData().isEmpty());
    builder.removeEmpty();
    var cleaned = builder.build();
    assertEquals(Map.of("TITLE", List.of("one", "two"), "BLANK", List.of("")), cleaned.asMap());
    assertTrue(cleaned.unsupportedData().isEmpty());
    assertEquals(cleaned, builder.clearUnsupportedData().build());
    assertTrue(original.containsKey("EMPTY"));
    assertEquals(List.of("opaque"), original.unsupportedData());
  }

  @Test
  void mapCleanupAndClearDoNotRequestOpaqueTagRemoval(@TempDir Path directory) throws Exception {
    Path path = Files.write(directory.resolve("opaque.tta"), AudioFileTest.trueAudioWithUnknown());
    ByteVector opaque;
    try (var file = new TrueAudioFile(path, ReadOptions.TAGS_ONLY)) {
      opaque = file.id3v2Tag().frameList(new ByteVector("ZZZZ")).getFirst().render();
    }
    var initial = TagLib.read(path).properties();
    assertEquals(List.of("UNKNOWN/ZZZZ"), initial.unsupportedData());
    var cleaned = initial.toBuilder().put("TITLE", "updated").put("EMPTY", List.of())
      .removeEmpty().build();
    assertEquals(initial.unsupportedData(), cleaned.unsupportedData());
    assertTrue(TagLib.write(path, cleaned).rejected().unsupportedData().isEmpty());
    assertEquals("updated", TagLib.read(path).tag().title());
    assertEquals(initial.unsupportedData(), TagLib.read(path).properties().unsupportedData());
    var identifiersCleared = cleaned.toBuilder().clearUnsupportedData().build();
    assertTrue(identifiersCleared.unsupportedData().isEmpty());
    TagLib.write(path, identifiersCleared);
    assertEquals(initial.unsupportedData(), TagLib.read(path).properties().unsupportedData());
    var cleared = cleaned.toBuilder().clear().build();
    assertEquals(PropertyMap.EMPTY, cleared);
    TagLib.write(path, cleared);
    try (var file = new TrueAudioFile(path, ReadOptions.TAGS_ONLY)) {
      assertEquals("", file.tag().title());
      assertEquals(opaque, file.id3v2Tag().frameList(new ByteVector("ZZZZ")).getFirst().render());
    }
  }
}
