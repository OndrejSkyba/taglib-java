package cz.skyba.taglib.parity.aiff.probes;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.riff.aiff.Properties;
import cz.skyba.taglib.toolkit.ByteVectorStream;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

class AiffPropertiesProbe {
  static void print(String key, Properties p, long cursor, boolean open) {
    System.out.println(
      key
        + "\t"
        + p.lengthInMilliseconds()
        + "\t"
        + p.lengthInSeconds()
        + "\t"
        + p.bitrate()
        + "\t"
        + p.sampleRate()
        + "\t"
        + p.channels()
        + "\t"
        + p.bitsPerSample()
        + "\t"
        + p.sampleFrames()
        + "\t"
        + (p.isAiffC() ? 1 : 0)
        + "\t"
        + HexFormat.of().formatHex(p.compressionType().toByteArray())
        + "\t"
        + HexFormat.of().formatHex(p.compressionName().getBytes(StandardCharsets.UTF_8))
        + "\t"
        + cursor
        + "\t"
        + (open ? 1 : 0));
    if (!p.common()
      .equals(
        new AudioProperties(
          p.lengthInMilliseconds(),
          p.bitrate(),
          p.sampleRate(),
          p.channels(),
          p.bitsPerSample(),
          p.common().lossless()))
      || p.lengthInSeconds() != p.common().lengthInSeconds())
      throw new AssertionError("Common projection");
  }

  public static void main(String[] args) throws Exception {
    try (var paths = Files.list(Path.of(args[0], "inputs"))) {
      for (var path : paths.sorted().toList()) {
        String name = path.getFileName().toString();
        for (String mode : new String[]{"DEFAULT", "FAST", "AVERAGE", "ACCURATE"}) {
          var style = mode.equals("DEFAULT") ? ReadStyle.AVERAGE : ReadStyle.valueOf(mode);
          var p = mode.equals("DEFAULT") ? new Properties(path) : new Properties(path, style);
          print(name + "-" + mode + "-path", p, -1, true);
          var stream = new ByteVectorStream(Files.readAllBytes(path));
          p = mode.equals("DEFAULT") ? new Properties(stream) : new Properties(stream, style);
          long cursor = stream.tell();
          print(name + "-" + mode + "-stream", p, cursor, stream.isOpen());
          stream.data().clear();
          p.compressionType().clear();
          print(name + "-" + mode + "-snapshot", p, cursor, stream.isOpen());
        }
      }
    }
  }
}
