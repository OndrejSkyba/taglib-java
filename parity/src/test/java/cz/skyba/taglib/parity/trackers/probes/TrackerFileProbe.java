package cz.skyba.taglib.parity.trackers.probes;

import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.it.ItFile;
import cz.skyba.taglib.mod.ModFile;
import cz.skyba.taglib.parity.support.OpenOutcome;
import cz.skyba.taglib.parity.support.SaveOutcome;
import cz.skyba.taglib.s3m.S3mFile;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.IOStream;
import cz.skyba.taglib.xm.XmFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HexFormat;

import static java.nio.charset.StandardCharsets.UTF_8;

public class TrackerFileProbe {
  static String fields(cz.skyba.taglib.mod.Properties p) {
    return ""
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
      + p.instrumentCount()
      + "\t"
      + p.lengthInPatterns();
  }

  static String fields(cz.skyba.taglib.s3m.Properties p) {
    return ""
      + p.lengthInMilliseconds()
      + "\t"
      + p.lengthInSeconds()
      + "\t"
      + p.bitrate()
      + "\t"
      + p.sampleRate()
      + "\t"
      + p.lengthInPatterns()
      + "\t"
      + p.channels()
      + "\t"
      + (p.stereo() ? 1 : 0)
      + "\t"
      + p.sampleCount()
      + "\t"
      + p.patternCount()
      + "\t"
      + p.flags()
      + "\t"
      + p.trackerVersion()
      + "\t"
      + p.fileFormatVersion()
      + "\t"
      + p.globalVolume()
      + "\t"
      + p.masterVolume()
      + "\t"
      + p.tempo()
      + "\t"
      + p.bpmSpeed();
  }

  static String fields(cz.skyba.taglib.it.Properties p) {
    return ""
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
      + p.lengthInPatterns()
      + "\t"
      + p.instrumentCount()
      + "\t"
      + p.sampleCount()
      + "\t"
      + p.patternCount()
      + "\t"
      + p.version()
      + "\t"
      + p.compatibleVersion()
      + "\t"
      + p.flags()
      + "\t"
      + p.special()
      + "\t"
      + p.globalVolume()
      + "\t"
      + p.mixVolume()
      + "\t"
      + p.tempo()
      + "\t"
      + p.bpmSpeed()
      + "\t"
      + p.panningSeparation()
      + "\t"
      + p.pitchWheelDepth()
      + "\t"
      + (p.stereo() ? 1 : 0);
  }

  static String fields(cz.skyba.taglib.xm.Properties p) {
    return ""
      + p.lengthInMilliseconds()
      + "\t"
      + p.lengthInSeconds()
      + "\t"
      + p.bitrate()
      + "\t"
      + p.sampleRate()
      + "\t"
      + p.lengthInPatterns()
      + "\t"
      + p.channels()
      + "\t"
      + p.version()
      + "\t"
      + p.restartPosition()
      + "\t"
      + p.patternCount()
      + "\t"
      + p.instrumentCount()
      + "\t"
      + p.sampleCount()
      + "\t"
      + p.flags()
      + "\t"
      + p.tempo()
      + "\t"
      + p.bpmSpeed();
  }

  static String hex(String s) {
    return HexFormat.of().formatHex(ByteVector.fromText(s, UTF_8).toByteArray());
  }

  static void state(String id, cz.skyba.taglib.internal.codec.TrackerFile file, long cursor) {
    if (file == null) {
      System.out.println(id + "/rejected\t1");
      return;
    }
    var tag = file.tag();
    System.out.println(
      id
        + "/tag\t"
        + (file.isValid() ? 1 : 0)
        + "\t"
        + hex(tag.title())
        + "\t"
        + hex(tag.comment())
        + "\t"
        + hex(tag.trackerName())
        + "\t"
        + cursor);
    String p =
      switch (file) {
        case ModFile f -> fields(f.detailedProperties());
        case S3mFile f -> fields(f.detailedProperties());
        case ItFile f -> fields(f.detailedProperties());
        case XmFile f -> fields(f.detailedProperties());
        default -> throw new AssertionError();
      };
    System.out.println(id + "/props\t" + p + "\t" + cursor);
  }

  static cz.skyba.taglib.internal.codec.TrackerFile open(String ext, IOStream stream) {
    return OpenOutcome.file(() -> switch (ext) {
      case "mod" -> new ModFile(stream);
      case "s3m" -> new S3mFile(stream);
      case "it" -> new ItFile(stream);
      case "xm" -> new XmFile(stream);
      default -> throw new AssertionError();
    });
  }

  static cz.skyba.taglib.internal.codec.TrackerFile open(String ext, Path path) {
    return OpenOutcome.file(() -> switch (ext) {
      case "mod" -> new ModFile(path);
      case "s3m" -> new S3mFile(path);
      case "it" -> new ItFile(path);
      case "xm" -> new XmFile(path);
      default -> throw new AssertionError();
    });
  }

  static cz.skyba.taglib.internal.codec.TrackerFile open(
    String ext, Path path, boolean enabled, ReadStyle style) {
    return OpenOutcome.file(() -> switch (ext) {
      case "mod" -> new ModFile(path, style);
      case "s3m" -> new S3mFile(path, style);
      case "it" -> new ItFile(path, style);
      case "xm" -> new XmFile(path, style);
      default -> throw new AssertionError();
    });
  }

  static cz.skyba.taglib.internal.codec.TrackerFile open(
    String ext, IOStream stream, boolean enabled, ReadStyle style) {
    return OpenOutcome.file(() -> switch (ext) {
      case "mod" -> new ModFile(stream, style);
      case "s3m" -> new S3mFile(stream, style);
      case "it" -> new ItFile(stream, style);
      case "xm" -> new XmFile(stream, style);
      default -> throw new AssertionError();
    });
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    try (var paths = Files.list(root.resolve("inputs"))) {
      for (var input : paths.sorted().toList()) {
        String id = input.getFileName().toString();
        String ext = id.substring(id.lastIndexOf('.') + 1);
        byte[] data = Files.readAllBytes(input);
        for (int origin : new int[]{0, 7}) {
          var stream = new ByteVectorStream(data);
          stream.seek(origin);
          try (var file = open(ext, stream)) {
            String base = id + "/io/" + origin;
            state(base, file, stream.tell());
            if (file == null) continue;
            file.tag().setTitle("Edited title");
            file.tag().setComment("first\nsecond\nthird\nmessage");
            file.tag().setTrackerName("Edited tracker");
            for (int repeat = 0; repeat < 2; repeat++) {
              boolean saved = SaveOutcome.save(file);
              System.out.println(
                base
                  + "/save/"
                  + repeat
                  + "\t"
                  + (saved ? 1 : 0)
                  + "\t"
                  + stream.tell()
                  + "\t"
                  + HexFormat.of().formatHex(stream.data().toByteArray()));
              state(base + "/memory/" + repeat, file, stream.tell());
            }
          }
        }
        for (var style : ReadStyle.values())
          for (boolean enabled : new boolean[]{false, true}) {
            String base = id + "/controls/" + style.ordinal() + "/" + (enabled ? 1 : 0);
            try (var f = open(ext, input, enabled, style)) {
              state(base + "/path", f, f == null ? 0 : f.tell());
            }
            for (int origin : new int[]{0, 7}) {
              var stream = new ByteVectorStream(data);
              stream.seek(origin);
              try (var f = open(ext, stream, enabled, style)) {
                state(base + "/io/" + origin, f, stream.tell());
              }
            }
          }
        Path copy = root.resolve("outputs").resolve(id + ".java");
        Files.copy(input, copy, StandardCopyOption.REPLACE_EXISTING);
        try (var file = open(ext, copy)) {
          state(id + "/path", file, file == null ? 0 : file.tell());
          if (file == null) continue;
          file.tag().setTitle("Edited title");
          file.tag().setComment("first\nsecond\nthird\nmessage");
          file.tag().setTrackerName("Edited tracker");
          for (int repeat = 0; repeat < 2; repeat++) {
            boolean saved = SaveOutcome.save(file);
            file.seek(file.tell());
            System.out.println(
              id
                + "/save/"
                + repeat
                + "\t"
                + (saved ? 1 : 0)
                + "\t"
                + file.tell()
                + "\t"
                + HexFormat.of().formatHex(Files.readAllBytes(copy)));
            state(id + "/memory/" + repeat, file, file.tell());
          }
        }
      }
    }
  }
}
