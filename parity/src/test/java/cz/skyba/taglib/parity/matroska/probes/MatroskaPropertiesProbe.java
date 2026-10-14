package cz.skyba.taglib.parity.matroska.probes;

import cz.skyba.taglib.ReadOptions;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.TagBase;
import cz.skyba.taglib.matroska.MatroskaFile;
import cz.skyba.taglib.matroska.Properties;
import cz.skyba.taglib.toolkit.ByteVectorStream;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

class MatroskaPropertiesProbe {
  static final class RawFile extends FileBase {
    RawFile(cz.skyba.taglib.toolkit.IOStream stream) {
      super(stream);
    }

    public TagBase tag() {
      return null;
    }

    public AudioProperties audioProperties() {
      return null;
    }

    public void save() {
      throw new cz.skyba.taglib.TagLibException("Test save rejected");
    }
  }

  static String hex(String s) {
    return HexFormat.of().formatHex(s.getBytes(StandardCharsets.UTF_8));
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
        + hex(p.docType())
        + "\t"
        + p.docTypeVersion()
        + "\t"
        + hex(p.codecName())
        + "\t"
        + hex(p.title())
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
    for (String mode : new String[]{"DEFAULT", "FAST", "AVERAGE", "ACCURATE"}) {
      var style = mode.equals("DEFAULT") ? ReadStyle.AVERAGE : ReadStyle.valueOf(mode);
      var p = mode.equals("DEFAULT") ? new Properties() : new Properties(style);
      print("null-" + mode, p, -1, true);
    }
    try (var paths = Files.list(Path.of(args[0], "inputs"))) {
      for (var path : paths.sorted().toList())
        for (String mode : new String[]{"DEFAULT", "FAST", "AVERAGE", "ACCURATE"}) {
          var style = mode.equals("DEFAULT") ? ReadStyle.AVERAGE : ReadStyle.valueOf(mode);
          String name = path.getFileName().toString();
          var owner = new MatroskaFile(path, new ReadOptions(true, style, ReadOptions.DEFAULT.maxMetadataBytes()));
          var p = owner.detailedProperties();
          print(name + "-" + mode + "-path", p, -1, true);
          if (!p.common().equals(new Properties(path, style).common()))
            throw new AssertionError("Physical property adapter");
          owner.close();
          try (var file = new RawFile(new ByteVectorStream(Files.readAllBytes(path)))) {
            p = mode.equals("DEFAULT") ? new Properties() : new Properties(style);
            print(name + "-" + mode + "-direct", p, -1, true);
          }
          for (int origin : new int[]{0, 7}) {
            byte[] raw = Files.readAllBytes(path), bytes = new byte[origin + raw.length];
            java.util.Arrays.fill(bytes, 0, origin, (byte) 'p');
            System.arraycopy(raw, 0, bytes, origin, raw.length);
            var stream = new ByteVectorStream(bytes);
            stream.seek(origin);
            p = new MatroskaFile(stream, new ReadOptions(true, style, ReadOptions.DEFAULT.maxMetadataBytes())).detailedProperties();
            long cursor = stream.tell();
            print(name + "-" + mode + "-stream-" + origin, p, cursor, stream.isOpen());
            stream.data().clear();
            print(name + "-" + mode + "-snapshot-" + origin, p, cursor, stream.isOpen());
            var lazy = new ByteVectorStream(bytes);
            lazy.seek(origin);
            p = new MatroskaFile(lazy, new ReadOptions(true, style, ReadOptions.DEFAULT.maxMetadataBytes())).detailedProperties();
            long last = lazy.tell();
            lazy.data().clear();
            print(name + "-" + mode + "-lazy-clear-" + origin, p, last, lazy.isOpen());
            lazy.data().append(new cz.skyba.taglib.toolkit.ByteVector(new byte[1000]));
            print(name + "-" + mode + "-lazy-grow-" + origin, p, last, lazy.isOpen());
          }
        }
    }
  }
}
