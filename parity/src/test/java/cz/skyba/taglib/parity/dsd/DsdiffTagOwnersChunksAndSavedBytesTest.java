package cz.skyba.taglib.parity.dsd;

import cz.skyba.taglib.parity.fixtures.FixtureInputs;
import cz.skyba.taglib.parity.support.BatchedComparison;
import cz.skyba.taglib.parity.support.Parity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("parity")
@Tag("dsd")
class DsdiffTagOwnersChunksAndSavedBytesTest {
  @Test
  void everyChunkLayoutTagEditAndRepeatedSaveMatchesCpp() throws Exception {
    var directory =
      FixtureInputs.generate("dsdiff-files", Parity.directory("dsdiff-tag-owners"));
    var recipes = Parity.JSON.readTree(directory.resolve("recipes.json").toFile()).get("inputs");
    long rows = 0;
    try (var pool = Executors.newFixedThreadPool(4)) {
      var tasks = new ArrayList<Callable<Long>>();
      for (var recipe : recipes)
        tasks.add(
          () -> {
            String name = recipe.get("name").asText();
            var args = new ArrayList<>(List.of(directory.toString(), name));
            if (recipe.get("write").asBoolean()) args.add("write");
            var n = directory.resolve(name + ".native.txt");
            var j = directory.resolve(name + ".java.txt");
            Parity.nativeRun(n, "dsdiff-file", args.toArray(String[]::new));
            Parity.javaRun(
              j, "cz.skyba.taglib.parity.dsd.probes.DsdiffFileProbe", args.toArray(String[]::new));
            Parity.uniqueRows(n);
            return Parity.transcripts(n, j);
          });
      for (var future : pool.invokeAll(tasks)) rows += future.get();
    }
    var n = directory.resolve("helpers.native.txt");
    var j = directory.resolve("helpers.java.txt");
    Parity.nativeRun(n, "dsdiff-file-helpers", directory.toString());
    Parity.javaRun(j, "cz.skyba.taglib.parity.dsd.probes.DsdiffFileHelpers", directory.toString());
    rows += Parity.transcripts(n, j);
    assertEquals(47225, rows);
    Parity.metrics("operations", rows);
    Parity.metrics("savedFiles", BatchedComparison.savedFiles(directory));
    var boundary = directory.resolve("invalid-save.java.txt");
    Parity.javaRun(boundary, "cz.skyba.taglib.parity.dsd.probes.DsdiffFileBoundaryProbe");
    assertEquals(
      "first-root\tIllegalStateException\tunchanged\n"
        + "stale-parent\tfalse\tunchanged\n"
        + "memory-grow\tfalse\tunchanged\n",
      Files.readString(boundary));
  }
}
