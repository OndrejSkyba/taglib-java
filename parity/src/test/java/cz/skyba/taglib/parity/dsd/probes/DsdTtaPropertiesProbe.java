package cz.skyba.taglib.parity.dsd.probes;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.IOStream;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

class DsdTtaPropertiesProbe {
  static void print(String key, Object value, long cursor, boolean open) {
    AudioProperties p;
    String fields;
    if (value instanceof cz.skyba.taglib.dsf.Properties d) {
      p = d.common();
      check(
        p,
        d.lengthInMilliseconds(),
        d.lengthInSeconds(),
        d.bitrate(),
        d.sampleRate(),
        d.channels(),
        d.bitsPerSample());
      fields =
        d.formatVersion()
          + "\t"
          + d.formatID()
          + "\t"
          + d.channelType()
          + "\t"
          + d.sampleCount()
          + "\t"
          + d.blockSizePerChannel();
    } else if (value instanceof cz.skyba.taglib.trueaudio.Properties d) {
      p = d.common();
      check(
        p,
        d.lengthInMilliseconds(),
        d.lengthInSeconds(),
        d.bitrate(),
        d.sampleRate(),
        d.channels(),
        d.bitsPerSample());
      fields = d.ttaVersion() + "\t" + d.sampleFrames();
    } else {
      var d = (cz.skyba.taglib.dsdiff.Properties) value;
      p = d.common();
      check(
        p,
        d.lengthInMilliseconds(),
        d.lengthInSeconds(),
        d.bitrate(),
        d.sampleRate(),
        d.channels(),
        d.bitsPerSample());
      fields = "" + d.sampleCount();
    }
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
        + fields
        + "\t"
        + cursor
        + "\t"
        + (open ? 1 : 0));
  }

  static void check(
    AudioProperties p, long ms, long seconds, int bitrate, int rate, int channels, int bits) {
    if (!p.equals(new AudioProperties(ms, bitrate, rate, channels, bits, p.lossless()))
      || p.lengthInSeconds() != seconds) throw new AssertionError("Common getter projection");
  }

  static Object file(Object source, String ext, String mode) throws Exception {
    boolean defaults = mode.equals("DEFAULT");
    var style = defaults ? ReadStyle.AVERAGE : ReadStyle.valueOf(mode);
    if (ext.equals("dsf"))
      return source instanceof Path p
        ? defaults ? new cz.skyba.taglib.dsf.Properties(p) : new cz.skyba.taglib.dsf.Properties(p, style)
        : defaults
        ? new cz.skyba.taglib.dsf.Properties((IOStream) source)
        : new cz.skyba.taglib.dsf.Properties((IOStream) source, style);
    if (ext.equals("tta"))
      return source instanceof Path p
        ? defaults
        ? new cz.skyba.taglib.trueaudio.Properties(p)
        : new cz.skyba.taglib.trueaudio.Properties(p, style)
        : defaults
        ? new cz.skyba.taglib.trueaudio.Properties((IOStream) source)
        : new cz.skyba.taglib.trueaudio.Properties((IOStream) source, style);
    return source instanceof Path p
      ? defaults
      ? new cz.skyba.taglib.dsdiff.Properties(p)
      : new cz.skyba.taglib.dsdiff.Properties(p, style)
      : defaults
      ? new cz.skyba.taglib.dsdiff.Properties((IOStream) source)
      : new cz.skyba.taglib.dsdiff.Properties((IOStream) source, style);
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    for (String line : Files.readAllLines(root.resolve("cases.tsv"))) {
      String[] v = line.split("\t", -1);
      for (String mode : new String[]{"DEFAULT", "FAST", "AVERAGE", "ACCURATE"}) {
        var style = mode.equals("DEFAULT") ? ReadStyle.AVERAGE : ReadStyle.valueOf(mode);
        String key = v[0] + "-" + mode;
        if (v[1].equals("dff")) {
          var p =
            new cz.skyba.taglib.dsdiff.Properties(
              Long.parseLong(v[2]),
              Integer.parseInt(v[3]),
              Long.parseUnsignedLong(v[4]),
              Integer.parseInt(v[5]),
              style);
          print(key, p, -1, true);
        } else {
          var data = new ByteVector(HexFormat.of().parseHex(v[2]));
          Object p;
          if (v[1].equals("dsf")) p = new cz.skyba.taglib.dsf.Properties(data, style);
          else
            p =
              mode.equals("DEFAULT")
                ? new cz.skyba.taglib.trueaudio.Properties(data, Long.parseLong(v[3]))
                : new cz.skyba.taglib.trueaudio.Properties(data, Long.parseLong(v[3]), style);
          print(key, p, -1, true);
          data.clear();
          print(key + "-snapshot", p, -1, true);
        }
      }
    }
    try (var paths = Files.list(root.resolve("inputs"))) {
      for (var path : paths.sorted().toList()) {
        String name = path.getFileName().toString(),
          ext = name.substring(name.lastIndexOf('.') + 1);
        for (String mode : new String[]{"DEFAULT", "FAST", "AVERAGE", "ACCURATE"}) {
          print(name + "-" + mode + "-path", file(path, ext, mode), -1, true);
          var stream = new ByteVectorStream(Files.readAllBytes(path));
          var p = file(stream, ext, mode);
          long cursor = stream.tell();
          print(name + "-" + mode + "-stream", p, cursor, stream.isOpen());
          stream.data().clear();
          print(name + "-" + mode + "-snapshot", p, cursor, stream.isOpen());
        }
      }
    }
  }
}
