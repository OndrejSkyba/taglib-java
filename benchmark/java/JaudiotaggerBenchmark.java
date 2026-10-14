import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.TagTextField;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.LogManager;

/** Measures the same public metadata workloads as AllFormatsBenchmark. */
public final class JaudiotaggerBenchmark {
  private static volatile long blackhole;
  private static final String TITLE = "Benchmark title";
  private static final String COMMENT = "x".repeat(8192);

  private record Case(String id, Path source, int iterations, int writes) {
  }

  private record Write(Case input, String workload, Path target) {
  }

  public static void main(String[] args) throws Exception {
    // Library logging would otherwise dominate timings and flood the process logs.
    LogManager.getLogManager().reset();
    var cases = new ArrayList<Case>();
    for (String line : Files.readAllLines(Path.of(args[1]))) {
      String[] fields = line.split("\t");
      cases.add(new Case(fields[0], Path.of(fields[1]), Integer.parseInt(fields[2]), Integer.parseInt(fields[3])));
    }
    int warmup = Integer.parseInt(args[3]), samples = Integer.parseInt(args[4]);
    if (cases.isEmpty() || warmup < 0 || samples < 1) throw new IllegalArgumentException("Invalid settings");
    System.out.println("{\"kind\":\"environment\",\"version\":" + quote(System.getProperty("jaudiotagger.version"))
      + ",\"javaVersion\":" + quote(System.getProperty("java.version")) + "}");
    if (args[0].equals("read")) {
      for (int sample = -warmup; sample < samples; sample++) {
        for (int index = 0; index < cases.size(); index++) {
          var input = cases.get(Math.floorMod(index + sample, cases.size()));
          long total = 0, start = System.nanoTime();
          for (int iteration = 0; iteration < input.iterations; iteration++)
            total += consume(AudioFileIO.read(input.source.toFile()));
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
      for (int kind = 0; kind < 2; kind++) {
        if ((input.writes & (1 << kind)) == 0) continue;
        String workload = kind == 0 ? "title" : "growth";
        String name = input.source.getFileName().toString();
        writes.add(new Write(input, workload,
          output.resolve(input.id + "-" + workload + name.substring(name.lastIndexOf('.')))));
      }
    }
    for (int sample = -warmup; sample < samples; sample++) {
      for (int index = 0; index < writes.size(); index++) {
        var write = writes.get(Math.floorMod(index + sample, writes.size()));
        Files.copy(write.input.source, write.target, StandardCopyOption.REPLACE_EXISTING);
        long start = System.nanoTime();
        // jaudiotagger has no tags-only public read option; include its required header read.
        var file = AudioFileIO.read(write.target.toFile());
        var tag = file.getTagOrCreateAndSetDefault();
        tag.setField(FieldKey.TITLE, TITLE);
        if (write.workload.equals("growth")) tag.setField(FieldKey.COMMENT, COMMENT);
        file.commit();
        long nanos = System.nanoTime() - start;
        if (sample >= 0) timing(write.input.id, write.workload, sample, nanos, 1);
      }
    }
  }

  private static long consume(AudioFile file) {
    long total = 0;
    var tag = file.getTag();
    if (tag != null) {
      var fields = tag.getFields();
      while (fields.hasNext()) {
        var field = fields.next();
        total += field.getId().length();
        if (!field.isBinary())
          total += field instanceof TagTextField text ? text.getContent().length() : field.toString().length();
      }
      for (var key : List.of(FieldKey.TITLE, FieldKey.ARTIST, FieldKey.ALBUM, FieldKey.COMMENT,
                            FieldKey.GENRE, FieldKey.YEAR, FieldKey.TRACK))
        total += tag.getFirst(key).length();
      for (var picture : tag.getArtworkList()) {
        var data = picture.getBinaryData();
        total += (data == null ? 0 : data.length) + (long) picture.getPictureType()
          + picture.getMimeType().length() + picture.getDescription().length()
          + picture.getWidth() + picture.getHeight();
      }
    }
    var audio = file.getAudioHeader();
    total += (long) (audio.getPreciseTrackLength() * 1000) + audio.getBitRateAsNumber()
      + audio.getSampleRateAsNumber() + audio.getChannels().length();
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
