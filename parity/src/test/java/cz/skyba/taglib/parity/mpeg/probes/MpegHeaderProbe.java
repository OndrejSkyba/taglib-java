package cz.skyba.taglib.parity.mpeg.probes;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.TagBase;
import cz.skyba.taglib.mpeg.Header;
import cz.skyba.taglib.mpeg.XingHeader;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.IOStream;

import java.nio.file.Files;
import java.nio.file.Path;

class MpegHeaderProbe {
  static class MemoryFile extends FileBase {
    MemoryFile(IOStream stream) {
      super(stream);
    }

    void invalidate() {
      setValid(false);
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

  static int bit(boolean value) {
    return value ? 1 : 0;
  }

  static void header(String name, Header h, long cursor) {
    System.out.println(
      name
        + "\t"
        + bit(h.isValid())
        + "\t"
        + h.version().ordinal()
        + "\t"
        + h.layer()
        + "\t"
        + bit(h.protectionEnabled())
        + "\t"
        + h.bitrate()
        + "\t"
        + h.sampleRate()
        + "\t"
        + bit(h.isPadded())
        + "\t"
        + h.channelMode().ordinal()
        + "\t"
        + h.channelConfiguration().ordinal()
        + "\t"
        + bit(h.isADTS())
        + "\t"
        + bit(h.isCopyrighted())
        + "\t"
        + bit(h.isOriginal())
        + "\t"
        + h.frameLength()
        + "\t"
        + h.samplesPerFrame()
        + "\t"
        + cursor);
  }

  public static void main(String[] args) throws Exception {
    for (int first = 0; first < 256; first++)
      for (int second = 0; second < 256; second++)
        System.out.println(
          "sync-"
            + first
            + "-"
            + second
            + "\t"
            + bit(
            cz.skyba.taglib.internal.MpegHeaderData.isFrameSync(
              new byte[]{(byte) first, (byte) second}, 0)));
    for (String kind : new String[]{"headers", "xing"}) {
      try (var files = Files.list(Path.of(args[0], kind))) {
        for (var path : files.sorted().toList()) {
          var data = new ByteVector(Files.readAllBytes(path));
          String name = path.getFileName().toString();
          if (kind.equals("xing")) {
            var h = new XingHeader(data);
            System.out.println(
              name
                + "\t"
                + bit(h.isValid())
                + "\t"
                + h.type().ordinal()
                + "\t"
                + h.totalFrames()
                + "\t"
                + h.totalSize());
          } else {
            for (int origin : new int[]{0, 7})
              for (boolean check : new boolean[]{false, true}) {
                var stream = new ByteVectorStream(new ByteVector(origin, (byte) '!').append(data));
                var file = new MemoryFile(stream);
                if (name.equals("invalid-file.bin")) file.invalidate();
                var h = check ? new Header(file, origin) : new Header(file, origin, false);
                long cursor = stream.tell();
                String key = name + "-" + origin + "-" + bit(check);
                header(key, h, cursor);
                var copy = new Header(h);
                stream.data().clear();
                header(key + "-copy", copy, cursor);
              }
          }
        }
      }
    }
  }
}
