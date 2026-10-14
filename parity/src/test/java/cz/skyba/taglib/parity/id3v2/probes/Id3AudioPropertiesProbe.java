package cz.skyba.taglib.parity.id3v2.probes;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.internal.codec.ApeAudioFixtures;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;

/**
 * Synthetic and full released-fixture comparisons of every detailed property accessor.
 */
class Id3AudioPropertiesProbe {
  public static void main(String[] args) throws Exception {
    Path reports = Path.of(args[0]), corpus = Path.of(args[1]);
    Path generated = Files.createDirectories(reports.resolve("fixtures"));
    var files = new TreeMap<String, Path>();
    for (var entry : ApeAudioFixtures.cases().entrySet()) {
      Path path = generated.resolve(entry.getKey());
      Files.write(path, entry.getValue());
      files.put("synthetic-" + entry.getKey(), path);
    }
    try (var paths = Files.list(corpus)) {
      for (Path path : paths.sorted().toList()) {
        String name = path.getFileName().toString();
        if (name.endsWith(".ape") || name.endsWith(".mpc") || name.endsWith(".wv"))
          files.put("release-" + name, path);
      }
    }
    var manifest = new StringBuilder();
    for (var entry : files.entrySet()) {
      String name = entry.getKey();
      Path path = entry.getValue();
      manifest.append(name).append('\t').append(path.toAbsolutePath()).append('\n');
      System.out.print(name + "|");
      if (name.endsWith(".ape")) {
        var p = new cz.skyba.taglib.ape.Properties(path);
        common(p.common());
        System.out.println("|" + p.version() + "|" + p.bitsPerSample() + "|" + p.sampleFrames());
      } else if (name.endsWith(".wv")) {
        var p = new cz.skyba.taglib.wavpack.Properties(path);
        common(p.common());
        System.out.println(
          "|"
            + p.version()
            + "|"
            + p.bitsPerSample()
            + "|"
            + p.sampleFrames()
            + "|"
            + (p.isLossless() ? 1 : 0)
            + "|"
            + (p.isDsd() ? 1 : 0));
      } else {
        var p = new cz.skyba.taglib.mpc.Properties(path);
        common(p.common());
        System.out.println(
          "|"
            + p.mpcVersion()
            + "|"
            + p.totalFrames()
            + "|"
            + Long.toUnsignedString(p.sampleFrames())
            + "|"
            + p.trackGain()
            + "|"
            + p.trackPeak()
            + "|"
            + p.albumGain()
            + "|"
            + p.albumPeak());
      }
    }
    Files.writeString(reports.resolve("files.tsv"), manifest);
  }

  private static void common(AudioProperties p) {
    System.out.print(
      p.lengthInMilliseconds() + "|" + p.bitrate() + "|" + p.sampleRate() + "|" + p.channels());
  }
}
