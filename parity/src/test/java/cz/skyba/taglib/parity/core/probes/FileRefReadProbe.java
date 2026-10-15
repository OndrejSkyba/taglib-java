package cz.skyba.taglib.parity.core.probes;

import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.parity.support.FileRefAdapter;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;

import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_8;

class FileRefReadProbe {
  static class Memory extends ByteVectorStream {
    final String name;

    Memory(byte[] bytes, String name) {
      super(bytes);
      this.name = name;
    }

    @Override
    public String name() {
      return name;
    }
  }

  static String hex(String value) {
    return ByteVector.fromText(value, UTF_8).toHex().toString();
  }

  public static void main(String[] args) throws Exception {
    var stream = new Memory(Files.readAllBytes(Path.of(args[0])), args[0]);
    stream.seek(Long.parseLong(args[2]));
    var mode = args[1];
    var style =
      mode.equals("FAST")
        ? ReadStyle.FAST
        : mode.equals("ACCURATE") ? ReadStyle.ACCURATE : ReadStyle.AVERAGE;
    try (var file = new FileRefAdapter(stream, !mode.equals("TAGS_ONLY"), style)) {
      System.out.println("constructor|" + (file.isNull() ? 1 : 0) + "|" + stream.tell());
      if (!file.isNull()) {
        var out = new StringBuilder("properties|");
        var props = file.properties();
        props
          .asMap()
          .forEach(
            (key, values) -> {
              out.append(hex(key)).append('=');
              values.forEach(value -> out.append(hex(value)).append(','));
              out.append(';');
            });
        out.append('/');
        props.unsupportedData().forEach(key -> out.append(hex(key)).append(','));
        System.out.println(out);
        var tag = file.tag();
        System.out.println(
          "common|"
            + hex(tag.title())
            + ","
            + hex(tag.artist())
            + ","
            + hex(tag.album())
            + ","
            + hex(tag.comment())
            + ","
            + hex(tag.genre())
            + ","
            + tag.year()
            + ","
            + tag.track()
            + ","
            + (tag.isEmpty() ? 1 : 0));
        var audio = file.audioProperties();
        System.out.println(
          "audio|"
            + (audio == null
            ? "0"
            : "1|"
            + audio.lengthInMilliseconds()
            + ","
            + audio.bitrate()
            + ","
            + audio.sampleRate()
            + ","
            + audio.channels()));
        var complex = new StringBuilder("complex|");
        for (var key : file.complexPropertyKeys()) complex.append(hex(key)).append(',');
        System.out.println(complex);
      }
      System.out.println("getters-cursor|" + stream.tell());
    }
    System.out.println("closed|" + (stream.isOpen() ? 1 : 0) + "|" + stream.tell());
  }
}
