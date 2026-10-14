package cz.skyba.taglib.parity.mp4.probes;

import cz.skyba.taglib.ReadOptions;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.mp4.Mp4File;
import cz.skyba.taglib.mp4.Properties;
import cz.skyba.taglib.toolkit.ByteVectorStream;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

class Mp4PropertiesProbe {
  static Properties live(Mp4File file, ReadStyle style) throws Exception {
    long cursor = file.tell();
    var atoms = new cz.skyba.taglib.mp4.Atoms(file);
    if (!atoms.checkRootLevelAtoms()) throw new AssertionError("Invalid atoms");
    file.seek(cursor);
    return new Properties(file, atoms, style);
  }

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
        + p.codec().ordinal()
        + "\t"
        + HexFormat.of().formatHex(p.codecId().getBytes(StandardCharsets.UTF_8))
        + "\t"
        + (p.isEncrypted() ? 1 : 0)
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
    try (var paths = Files.list(Path.of(args[0], "inputs"))) {
      for (var path : paths.sorted().toList())
        for (String mode : new String[]{"DEFAULT", "FAST", "AVERAGE", "ACCURATE"}) {
          for (String prefix : new String[]{"", "live-"}) {
            var style = mode.equals("DEFAULT") ? ReadStyle.AVERAGE : ReadStyle.valueOf(mode);
            String name = path.getFileName().toString();
            Properties p;
            if (prefix.isEmpty())
              p = mode.equals("DEFAULT") ? new Properties(path) : new Properties(path, style);
            else
              try (var owner = new Mp4File(path, new ReadOptions(false, style, ReadOptions.DEFAULT.maxMetadataBytes()))) {
                p = live(owner, style);
              }
            print(prefix + name + "-" + mode + "-path", p, -1, true);
            for (int origin : new int[]{0, 7}) {
              var stream = new ByteVectorStream(Files.readAllBytes(path));
              stream.seek(origin);
              if (prefix.isEmpty())
                p = mode.equals("DEFAULT") ? new Properties(stream) : new Properties(stream, style);
              else
                try (var owner = new Mp4File(stream, new ReadOptions(false, style, ReadOptions.DEFAULT.maxMetadataBytes()))) {
                  p = live(owner, style);
                }
              long cursor = stream.tell();
              print(prefix + name + "-" + mode + "-stream-" + origin, p, cursor, stream.isOpen());
              stream.data().clear();
              print(prefix + name + "-" + mode + "-snapshot-" + origin, p, cursor, stream.isOpen());
            }
          }
          var ownerStyle = mode.equals("DEFAULT") ? ReadStyle.AVERAGE : ReadStyle.valueOf(mode);
          try (var owner = new Mp4File(path, new ReadOptions(true, ownerStyle, ReadOptions.DEFAULT.maxMetadataBytes()))) {
            print("owner-" + path.getFileName() + "-" + mode, owner.detailedProperties(), -1, true);
          }
        }
    }
  }
}
