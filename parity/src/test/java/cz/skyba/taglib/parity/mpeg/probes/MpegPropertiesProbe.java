package cz.skyba.taglib.parity.mpeg.probes;

import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.mpeg.Properties;
import cz.skyba.taglib.toolkit.ByteVectorStream;

import java.nio.file.Files;
import java.nio.file.Path;

class MpegPropertiesProbe {
  static int bit(boolean value) {
    return value ? 1 : 0;
  }

  static void print(String key, Properties p, long cursor, boolean open) {
    var x = p.xingHeader();
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
        + p.version().ordinal()
        + "\t"
        + p.layer()
        + "\t"
        + bit(p.protectionEnabled())
        + "\t"
        + p.channelMode().ordinal()
        + "\t"
        + p.channelConfiguration().ordinal()
        + "\t"
        + bit(p.isADTS())
        + "\t"
        + bit(p.isCopyrighted())
        + "\t"
        + bit(p.isOriginal())
        + "\t"
        + bit(x != null)
        + "\t"
        + (x == null ? -1 : x.type().ordinal())
        + "\t"
        + (x == null ? 0 : x.totalFrames())
        + "\t"
        + (x == null ? 0 : x.totalSize())
        + "\t"
        + cursor
        + "\t"
        + bit(open));
    if (!p.common()
      .equals(
        new cz.skyba.taglib.AudioProperties(
          p.lengthInMilliseconds(), p.bitrate(), p.sampleRate(), p.channels(), 0, false)))
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
          for (int origin : new int[]{0, 7}) {
            var stream = new ByteVectorStream(Files.readAllBytes(path));
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
}
