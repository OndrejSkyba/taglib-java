package cz.skyba.taglib.parity.core;

import cz.skyba.taglib.parity.support.FileRefAdapter;

import cz.skyba.taglib.parity.fixtures.Bytes;
import cz.skyba.taglib.parity.fixtures.FixtureInputs;
import cz.skyba.taglib.parity.support.Parity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("parity")
@Tag("core")
class FileReferenceLifecycleRemovalAndStreamReadingTest {
  private long rows;
  private long saved;

  private long compare(Path directory, String name, String probe, String[] n, String[] j)
    throws Exception {
    var no = directory.resolve(name + ".native.txt");
    var jo = directory.resolve(name + ".java.txt");
    Parity.nativeRun(no, "fileref-" + probe, n);
    Parity.javaRun(
      jo,
      "cz.skyba.taglib.parity.core.probes.FileRef"
        + Character.toUpperCase(probe.charAt(0))
        + probe.substring(1)
        + "Probe",
      j);
    long count = Parity.transcripts(no, jo);
    rows += count;
    return count;
  }

  private void state(Path directory, String name, Path source, String scenario) throws Exception {
    var n = directory.resolve(name + ".native");
    var j = directory.resolve(name + ".java");
    compare(
      directory,
      name,
      "state",
      new String[]{source.toString(), scenario, n.toString()},
      new String[]{source.toString(), scenario, j.toString()});
    Parity.bytes(n, j);
    saved++;
  }

  @Test
  void lifecycleTagRemovalRepeatedStateAndAsfObjectTraversalMatchCpp() throws Exception {
    var directory =
      FixtureInputs.generate(
        "asf-duplicate-objects-and-partial-headers",
        Parity.directory("file-reference-lifecycle"));
    var corpus = Parity.corpus();
    compare(
      directory,
      "lifecycle",
      "lifecycle",
      new String[]{corpus.resolve("silence-1.flac").toString()},
      new String[]{corpus.resolve("silence-1.flac").toString()});
    var seeded = new HashMap<String, Path>();
    for (String[] seed :
      new String[][]{{"tagged.wv", "seed-ape"}, {"silence-1.wma", "seed-asf"}}) {
      var copy = directory.resolve("seeded-" + seed[0]);
      Files.copy(corpus.resolve(seed[0]), copy);
      var result = directory.resolve(seed[1] + ".txt");
      Parity.nativeRun(result, "fileref-removal", copy.toString(), seed[1]);
      assertEquals("seeded|1\n", Files.readString(result));
      seeded.put(copy.getFileName().toString(), copy);
    }
    String[][] removals = {
      {"rare_frames.mp3", null},
      {"tagged.wv", null},
      {"silence-1.wma", null},
      {"optimized.mkv", null},
      {"has-tags.m4a", null},
      {"tagged.tta", null},
      {"rare_frames.mp3", "TXXX/userTextDescription1"},
      {"optimized.mkv", "TITLE"},
      {"silence-1.wma", "WM/AlbumTitle"},
      {"tagged.wv", "Title"},
      {"seeded-tagged.wv", "OPAQUE"},
      {"seeded-silence-1.wma", "Opaque"},
      {"optimized.mkv", "DURATION"},
      {"optimized.mkv", "TITLE"},
      {"rare_frames.mp3", "APIC"},
      {"tagged.tta", "TPE1"}
    };
    int index = 0;
    for (String[] removal : removals) {
      var source = seeded.getOrDefault(removal[0], corpus.resolve(removal[0]));
      var n = directory.resolve("remove-" + index + "-native-" + removal[0]);
      var j = directory.resolve("remove-" + index + "-java-" + removal[0]);
      Files.copy(source, n);
      Files.copy(source, j);
      var na = new ArrayList<>(List.of(n.toString()));
      var ja = new ArrayList<>(List.of(j.toString()));
      if (removal[1] != null) {
        na.add(removal[1]);
        ja.add(removal[1]);
      }
      compare(
        directory,
        "removal-" + index++,
        "removal",
        na.toArray(String[]::new),
        ja.toArray(String[]::new));
    }
    byte[] tagged = Files.readAllBytes(corpus.resolve("tagged.tta"));
    var mpc = directory.resolve("state-v1-only-click.mpc");
    Files.write(
      mpc,
      Bytes.join(
        Files.readAllBytes(corpus.resolve("click.mpc")),
        Arrays.copyOfRange(tagged, tagged.length - 128, tagged.length)));
    String[][] states = {
      {"ape-id3v1.mp3", "empty-common"},
      {"uint8we.wav", "empty-common"},
      {"uint8we.wav", "date"},
      {"empty.tta", "empty-frame"},
      {"tagged.tta", "empty-frame"},
      {"alaw.aifc", "empty-frame"},
      {"no-tags.mka", "picture"},
      {"optimized.mkv", "empty-common"},
      {"tagged.wv", "empty-common"},
      {"mac-399-tagged.ape", "empty-common"},
      {"state-v1-only-click.mpc", "empty-common"},
      {"silence-1.wma", "asf-nul-common"},
      {"silence-1.wma", "asf-nul-properties"}
    };
    index = 0;
    for (String[] entry : states)
      state(
        directory,
        "state-" + index++,
        entry[0].equals(mpc.getFileName().toString()) ? mpc : corpus.resolve(entry[0]),
        entry[1]);
    try (var paths = Files.list(directory.resolve("physical"))) {
      for (Path source : paths.sorted().toList())
        for (String scenario : List.of("save-only", "common", "properties", "remove"))
          state(directory, "asf-" + source.getFileName() + "-" + scenario, source, scenario);
    }
    try (var paths = Files.list(directory.resolve("constructor"))) {
      for (Path fixture : paths.sorted().toList())
        for (int variant = 0; variant < 3; variant++) {
          var source = directory.resolve("asf-read-" + fixture.getFileName() + "-" + variant);
          Files.write(
            source,
            Bytes.join(
              variant == 2 ? Bytes.text("prefix!") : new byte[0], Files.readAllBytes(fixture)));
          int origin = variant == 0 ? 0 : 7;
          for (String mode : List.of("TAGS_ONLY", "FAST", "AVERAGE", "ACCURATE")) {
            var args = new String[]{source.toString(), mode, Integer.toString(origin)};
            compare(directory, source.getFileName() + "-" + mode, "read", args, args);
          }
        }
    }
    Parity.metrics("operations", rows);
    Parity.metrics("savedFiles", saved);
  }
}
