package cz.skyba.taglib.parity.asf.probes;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.asf.Properties;
import cz.skyba.taglib.toolkit.ByteVectorStream;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

class AsfPropertiesProbe {
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
        + HexFormat.of().formatHex(p.codecName().getBytes(StandardCharsets.UTF_8))
        + "\t"
        + HexFormat.of().formatHex(p.codecDescription().getBytes(StandardCharsets.UTF_8))
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
          p.codec() == Properties.Codec.WMA9Lossless))
      || p.lengthInSeconds() != p.common().lengthInSeconds())
      throw new AssertionError("Common projection");
  }

  static void print(String key, Properties p) {
    print(key, p, -1, true);
  }

  public static void main(String[] args) throws Exception {
    var p = new Properties();
    print("default", p);
    for (int value :
      new int[]{Integer.MIN_VALUE, -1001, -999, -1, 0, 1, 999, 1000, Integer.MAX_VALUE}) {
      p.setLengthInMilliseconds(value);
      print("length-" + value, p);
      p.setBitrate(value);
      print("bitrate-" + value, p);
      p.setSampleRate(value);
      print("rate-" + value, p);
      p.setChannels(value);
      print("channels-" + value, p);
      p.setBitsPerSample(value);
      print("bits-" + value, p);
    }
    for (int value : new int[]{-1, 0, 1, 0x15f, 0x160, 0x161, 0x162, 0x163, 0x164, 65535}) {
      p.setCodec(value);
      print("codec-" + value, p);
    }
    String[] texts = {"", " \t name\n ", "a", "é音", "a\u0000b"};
    for (int i = 0; i < texts.length; i++) {
      p.setCodecName(texts[i]);
      print("name-" + i, p);
      p.setCodecDescription(texts[i]);
      print("description-" + i, p);
    }
    p.setEncrypted(true);
    print("encrypted", p);
    p.setEncrypted(false);
    print("unencrypted", p);
    try (var paths = Files.list(Path.of(args[0], "inputs"))) {
      for (var path : paths.sorted().toList())
        for (String mode : new String[]{"DEFAULT", "FAST", "AVERAGE", "ACCURATE"}) {
          var style = mode.equals("DEFAULT") ? ReadStyle.AVERAGE : ReadStyle.valueOf(mode);
          String name = path.getFileName().toString();
          p = mode.equals("DEFAULT") ? new Properties(path) : new Properties(path, style);
          print(name + "-" + mode + "-path", p);
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
