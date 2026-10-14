package cz.skyba.taglib.parity.core;

import cz.skyba.taglib.parity.support.Parity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("parity")
@Tag("core")
class ComplexPropertiesAndCompleteFileSavingTest {
  @Test
  void artworkChaptersTargetsAndOpaquePropertiesMatchCppAcrossContainers() throws Exception {
    var directory = Parity.directory("complex-property-edits");
    long cases = 0, rows = 0;
    for (String suffix :
      List.of("mp3", "tta", "wav", "aiff", "dsf", "dff", "flac", "mkv", "wma", "m4a")) {
      String preferred =
        Map.of("mp3", "id3v24.mp3", "mkv", "tags-before-cues.mkv", "flac", "no-tags.flac")
          .get(suffix);
      Path source;
      if (preferred != null && Files.isRegularFile(Parity.corpus().resolve(preferred)))
        source = Parity.corpus().resolve(preferred);
      else
        try (var paths = Files.list(Parity.corpus())) {
          source =
            paths
              .filter(p -> p.toString().endsWith("." + suffix))
              .sorted()
              .findFirst()
              .orElseThrow();
        }
      for (String scenario :
        List.of(
          "general",
          "general-clear",
          "scoped",
          "scoped-clear",
          "chapters",
          "mime",
          "filename",
          "uid",
          "stem",
          "stem-clear",
          "unknown")) {
        var work =
          Files.createDirectories(
            directory.resolve(source.getFileName().toString()).resolve(scenario));
        var n = work.resolve("native." + suffix);
        var j = work.resolve("java." + suffix);
        Files.copy(source, n);
        Files.copy(source, j);
        var no = work.resolve("native.txt");
        var jo = work.resolve("java.txt");
        Parity.nativeRun(no, "complex-property-edits", n.toString(), scenario);
        Parity.javaRun(
          jo,
          "cz.skyba.taglib.parity.core.probes.ComplexPropertyEditingProbe",
          j.toString(),
          scenario);
        rows += Parity.transcripts(no, jo);
        Parity.bytes(n, j);
        cases++;
      }
    }
    assertEquals(110, cases);
    Parity.metrics("scenarios", cases);
    Parity.metrics("operations", rows);
    Parity.metrics("savedFiles", cases);
  }
}
