package cz.skyba.taglib.parity.ogg.probes;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.TagBase;
import cz.skyba.taglib.ogg.OggFile;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.IOStream;

import java.nio.file.Files;
import java.nio.file.Path;

class OggPropertiesProbe {
  static final class PacketFile extends OggFile {
    PacketFile(IOStream stream, String ext) {
      super(stream);
      if (ext.equals("ogg")) {
        selectStream(new ByteVector("\u0001vorbis"));
        packet(1);
      } else if (packet(0).startsWith(new ByteVector(ext.equals("opus") ? "OpusHead" : "Speex   ")))
        packet(1);
    }

    public TagBase tag() {
      return null;
    }

    public AudioProperties audioProperties() {
      return null;
    }
  }

  record Snapshot(AudioProperties common, String fields) {
  }

  static Object read(Object source, String ext, String mode) throws Exception {
    var style = mode.equals("DEFAULT") ? ReadStyle.AVERAGE : ReadStyle.valueOf(mode);
    boolean defaults = mode.equals("DEFAULT");
    if (ext.equals("opus")) {
      var p =
        source instanceof Path v
          ? defaults
          ? new cz.skyba.taglib.ogg.opus.Properties(v)
          : new cz.skyba.taglib.ogg.opus.Properties(v, style)
          : source instanceof IOStream v
          ? defaults
          ? new cz.skyba.taglib.ogg.opus.Properties(v)
          : new cz.skyba.taglib.ogg.opus.Properties(v, style)
          : defaults
          ? new cz.skyba.taglib.ogg.opus.Properties((PacketFile) source)
          : new cz.skyba.taglib.ogg.opus.Properties((PacketFile) source, style);
      return p;
    }
    if (ext.equals("spx")) {
      var p =
        source instanceof Path v
          ? defaults
          ? new cz.skyba.taglib.ogg.speex.Properties(v)
          : new cz.skyba.taglib.ogg.speex.Properties(v, style)
          : source instanceof IOStream v
          ? defaults
          ? new cz.skyba.taglib.ogg.speex.Properties(v)
          : new cz.skyba.taglib.ogg.speex.Properties(v, style)
          : defaults
          ? new cz.skyba.taglib.ogg.speex.Properties((PacketFile) source)
          : new cz.skyba.taglib.ogg.speex.Properties((PacketFile) source, style);
      return p;
    }
    var p =
      source instanceof Path v
        ? defaults
        ? new cz.skyba.taglib.ogg.vorbis.Properties(v)
        : new cz.skyba.taglib.ogg.vorbis.Properties(v, style)
        : source instanceof IOStream v
        ? defaults
        ? new cz.skyba.taglib.ogg.vorbis.Properties(v)
        : new cz.skyba.taglib.ogg.vorbis.Properties(v, style)
        : defaults
        ? new cz.skyba.taglib.ogg.vorbis.Properties((PacketFile) source)
        : new cz.skyba.taglib.ogg.vorbis.Properties((PacketFile) source, style);
    return p;
  }

  static Snapshot snapshot(Object properties) {
    if (properties instanceof cz.skyba.taglib.ogg.opus.Properties p) {
      check(
        p.common(),
        p.lengthInMilliseconds(),
        p.lengthInSeconds(),
        p.bitrate(),
        p.sampleRate(),
        p.channels());
      return new Snapshot(
        p.common(), p.opusVersion() + "\t" + p.inputSampleRate() + "\t" + p.outputGain());
    }
    if (properties instanceof cz.skyba.taglib.ogg.speex.Properties p) {
      check(
        p.common(),
        p.lengthInMilliseconds(),
        p.lengthInSeconds(),
        p.bitrate(),
        p.sampleRate(),
        p.channels());
      return new Snapshot(p.common(), p.speexVersion() + "\t" + p.bitrateNominal());
    }
    var p = (cz.skyba.taglib.ogg.vorbis.Properties) properties;
    check(
      p.common(),
      p.lengthInMilliseconds(),
      p.lengthInSeconds(),
      p.bitrate(),
      p.sampleRate(),
      p.channels());
    return new Snapshot(
      p.common(),
      p.vorbisVersion()
        + "\t"
        + p.bitrateMaximum()
        + "\t"
        + p.bitrateNominal()
        + "\t"
        + p.bitrateMinimum());
  }

  static void check(AudioProperties p, long ms, long seconds, int bitrate, int rate, int channels) {
    if (!p.equals(new AudioProperties(ms, bitrate, rate, channels, 0, false))
      || p.lengthInSeconds() != seconds) throw new AssertionError("Common projection");
  }

  static void print(String key, Object properties, long cursor, boolean open) {
    var snapshot = snapshot(properties);
    var p = snapshot.common();
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
        + snapshot.fields()
        + "\t"
        + cursor
        + "\t"
        + (open ? 1 : 0));
  }

  public static void main(String[] args) throws Exception {
    try (var paths = Files.list(Path.of(args[0], "inputs"))) {
      for (var path : paths.sorted().toList()) {
        String name = path.getFileName().toString(),
          ext = name.substring(name.lastIndexOf('.') + 1);
        for (String mode : new String[]{"DEFAULT", "FAST", "AVERAGE", "ACCURATE"}) {
          var p = read(path, ext, mode);
          print(name + "-" + mode + "-path", p, -1, true);
          for (int origin : new int[]{0, 7}) {
            var stream = new ByteVectorStream(Files.readAllBytes(path));
            stream.seek(origin);
            p = read(stream, ext, mode);
            long cursor = stream.tell();
            print(name + "-" + mode + "-stream-" + origin, p, cursor, stream.isOpen());
            // Reuse a packet index exactly as an existing native codec File does.
            stream.seek(origin);
            var file = new PacketFile(stream, ext);
            read(file, ext, mode);
            var repeated = read(file, ext, mode);
            print(
              name + "-" + mode + "-existing-" + origin,
              repeated,
              stream.tell(),
              stream.isOpen());
            stream.data().clear();
            print(name + "-" + mode + "-snapshot-" + origin, repeated, cursor, stream.isOpen());
            if (!snapshot(p).equals(snapshot(repeated)))
              throw new AssertionError("Existing packet index properties");
          }
        }
      }
    }
  }
}
