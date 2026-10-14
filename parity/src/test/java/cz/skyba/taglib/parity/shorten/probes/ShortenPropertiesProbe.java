package cz.skyba.taglib.parity.shorten.probes;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.shorten.Properties;
import cz.skyba.taglib.toolkit.ByteVectorStream;

import java.nio.file.Files;
import java.nio.file.Path;

class ShortenPropertiesProbe {
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
        + p.shortenVersion()
        + "\t"
        + p.fileType()
        + "\t"
        + Long.toUnsignedString(p.sampleFrames())
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
          p.common().lossless()))) throw new AssertionError("Common projection");
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    for (String line : Files.readAllLines(root.resolve("cases.tsv"))) {
      var f = line.split("\t");
      var values =
        f[0].equals("null")
          ? null
          : new Properties.Values(
          Integer.parseInt(f[1]),
          Integer.parseInt(f[2]),
          Integer.parseInt(f[3]),
          Integer.parseInt(f[4]),
          Integer.parseInt(f[5]),
          Long.parseUnsignedLong(f[6]));
      for (String mode : new String[]{"DEFAULT", "FAST", "AVERAGE", "ACCURATE"}) {
        var style = mode.equals("DEFAULT") ? ReadStyle.AVERAGE : ReadStyle.valueOf(mode);
        var p = mode.equals("DEFAULT") ? new Properties(values) : new Properties(values, style);
        print(f[0] + "-" + mode + "-data", p, -1, true);
        print(f[0] + "-" + mode + "-snapshot", p, -1, true);
      }
    }
    try (var paths = Files.list(root.resolve("inputs"))) {
      for (var path : paths.sorted().toList())
        for (String mode : new String[]{"DEFAULT", "FAST", "AVERAGE", "ACCURATE"}) {
          var style = mode.equals("DEFAULT") ? ReadStyle.AVERAGE : ReadStyle.valueOf(mode);
          String name = path.getFileName().toString();
          var p = mode.equals("DEFAULT") ? new Properties(path) : new Properties(path, style);
          print(name + "-" + mode + "-path", p, -1, true);
          for (int origin : new int[]{0, 7}) {
            byte[] raw = Files.readAllBytes(path), bytes = new byte[origin + raw.length];
            java.util.Arrays.fill(bytes, 0, origin, (byte) 'p');
            System.arraycopy(raw, 0, bytes, origin, raw.length);
            var stream = new ByteVectorStream(bytes);
            stream.seek(origin);
            p = mode.equals("DEFAULT") ? new Properties(stream) : new Properties(stream, style);
            long cursor = stream.tell();
            print(name + "-" + mode + "-stream-" + origin, p, cursor, stream.isOpen());
            stream.data().clear();
            print(name + "-" + mode + "-snapshot-" + origin, p, cursor, stream.isOpen());
          }
        }
    }
  }
}
