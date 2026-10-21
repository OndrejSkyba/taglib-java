import cz.skyba.taglib.AudioMetadata;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.TagLib;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Measures public metadata operations; setup and result reporting are outside timers.
 */
public final class AllFormatsBenchmark {
  private static volatile long blackhole;

  private record Case(String id, Path source, int iterations, int writes) {
  }

  private record Write(Case input, String workload, PropertyMap properties, Path target) {
  }

  public static void main(String[] args) throws Exception {
    var cases = new ArrayList<Case>();
    for (String line : Files.readAllLines(Path.of(args[1]))) {
      String[] fields = line.split("\t");
      cases.add(new Case(fields[0], Path.of(fields[1]), Integer.parseInt(fields[2]), Integer.parseInt(fields[3])));
    }
    int warmup = Integer.parseInt(args[3]), samples = Integer.parseInt(args[4]);
    if (cases.isEmpty() || warmup < 0 || samples < 1) throw new IllegalArgumentException("Invalid settings");
    System.out.println("{\"kind\":\"environment\",\"javaVersion\":" + quote(System.getProperty("java.version"))
      + ",\"vmName\":" + quote(System.getProperty("java.vm.name")) + "}");
    if (args[0].equals("read")) {
      for (int sample = -warmup; sample < samples; sample++) {
        for (int index = 0; index < cases.size(); index++) {
          var input = cases.get(Math.floorMod(index + sample, cases.size()));
          long total = 0, start = System.nanoTime();
          for (int iteration = 0; iteration < input.iterations; iteration++)
            total += consume(TagLib.read(input.source));
          long nanos = System.nanoTime() - start;
          blackhole = total;
          if (sample >= 0) timing(input.id, "read", sample, nanos, input.iterations);
        }
      }
      return;
    }
    if (!args[0].equals("write")) throw new IllegalArgumentException("Unknown mode");
    Path output = Path.of(args[2]);
    Files.createDirectories(output);
    var writes = new ArrayList<Write>();
    for (var input : cases) {
      if (input.writes == 0) continue;
      var original = TagLib.read(input.source, ReadOptions.TAGS_ONLY).properties();
      for (int kind = 0; kind < 2; kind++) {
        if ((input.writes & (1 << kind)) == 0) continue;
        String workload = kind == 0 ? "title" : "growth";
        var builder = original.toBuilder().put("TITLE", "Benchmark title");
        if (kind == 1) builder.put("COMMENT", "x".repeat(8192));
        String name = input.source.getFileName().toString();
        Path target = output.resolve(input.id + "-" + workload + name.substring(name.lastIndexOf('.')));
        writes.add(new Write(input, workload, builder.build(), target));
      }
    }
    for (int sample = -warmup; sample < samples; sample++) {
      for (int index = 0; index < writes.size(); index++) {
        var write = writes.get(Math.floorMod(index + sample, writes.size()));
        Files.copy(write.input.source, write.target, StandardCopyOption.REPLACE_EXISTING);
        long start = System.nanoTime();
        TagLib.write(write.target, write.properties);
        long nanos = System.nanoTime() - start;
        if (sample >= 0) timing(write.input.id, write.workload, sample, nanos, 1);
      }
    }
  }

  // Consume returned values without hashing or checking their correctness.
  private static long consume(AudioMetadata file) {
    long total = 0;
    for (var entry : file.properties().asMap().entrySet()) {
      total += entry.getKey().length();
      for (String value : entry.getValue()) total += value.length();
    }
    var tag = file.tag();
    for (String value : List.of(tag.title(), tag.artist(), tag.album(), tag.comment(), tag.genre()))
      total += value.length();
    var audio = file.audioProperties();
    total += tag.year() + (long) tag.track() + audio.lengthInMilliseconds()
      + audio.bitrate() + audio.sampleRate() + audio.channels();
    for (var picture : file.pictures())
      total += picture.data().length + (long) picture.type() + picture.mimeType().length()
        + picture.description().length() + picture.width() + picture.height()
        + picture.colorDepth() + picture.indexedColors();
    return total;
  }

  private static void timing(String id, String workload, int sample, long nanos, int iterations) {
    System.out.println("{\"kind\":\"timing\",\"id\":" + quote(id) + ",\"workload\":" + quote(workload)
      + ",\"sample\":" + sample + ",\"nanos\":" + nanos + ",\"iterations\":" + iterations + "}");
  }

  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
