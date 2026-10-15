package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.dsdiff.DsdiffFile;
import cz.skyba.taglib.dsf.DsfFile;
import cz.skyba.taglib.flac.FlacFile;
import cz.skyba.taglib.mpeg.MpegFile;
import cz.skyba.taglib.parity.TextCharsets;
import cz.skyba.taglib.parity.support.SaveOutcome;
import cz.skyba.taglib.riff.aiff.AiffFile;
import cz.skyba.taglib.riff.wav.WavFile;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.trueaudio.TrueAudioFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static cz.skyba.taglib.id3.v2.Id3OwnerProbe.*;
import static java.nio.charset.StandardCharsets.UTF_16;

/**
 * Complete real-file state through ordinary encodings/handlers and versioned saves.
 */
public class Id3ReleaseProbe {
  static String counts(Factory factory) {
    return factory.raw + ":" + factory.typed + ":" + factory.update + ":" + factory.aggregate;
  }

  static void snapshot(String key, MpegFile file, Factory factory) {
    var tag = file.id3v2Tag();
    var h = tag.header();
    System.out.println(
      key
        + "/file\t"
        + b(file.isValid())
        + ":"
        + b(file.hasId3v2Tag())
        + ":"
        + file.length()
        + ":"
        + counts(factory));
    System.out.println(
      key
        + "/header\t"
        + h.majorVersion()
        + ":"
        + h.revisionNumber()
        + ":"
        + h.tagSize()
        + ":"
        + h.completeTagSize()
        + ":"
        + b(h.unsynchronisation())
        + ":"
        + b(h.extendedHeader())
        + ":"
        + b(h.experimentalIndicator())
        + ":"
        + b(h.footerPresent())
        + ":"
        + (tag.extendedHeader() == null ? "null" : tag.extendedHeader().size()));
    System.out.println(
      key
        + "/tag\t"
        + fields(List.of(tag.title(), tag.artist(), tag.album(), tag.comment(), tag.genre()))
        + ":"
        + tag.year()
        + ":"
        + tag.track()
        + ":"
        + b(tag.isEmpty())
        + ":"
        + props(tag.properties())
        + ":"
        + map(tag.frameListMap()));
    int n = 0;
    for (var f : tag.frameList()) System.out.println(key + "/frame/" + n++ + "\t" + frame(f));
  }

  static void common(Tag tag) {
    tag.setTitle("Title café");
    tag.setYear(4294967295L);
    tag.setTrack(2147483648L);
    tag.setComment("comment");
    tag.addFrame(new TableOfContentsFrame(new ByteVector("toc")));
  }

  static void properties(Tag tag) {
    var p =
      PropertyMap.builder()
        .put("TITLE", "Second")
        .put("ARTIST", List.of("First", "Second"))
        .put("PERFORMER:VIOLIN", "a violinist")
        .put("ARRANGER", "arranger")
        .put("T01:CUSTOM", "café")
        .build();
    if (!tag.replaceProperties(p).rejected().isEmpty())
      throw new AssertionError("Unexpected rejected ordinary properties");
  }

  static FileBase parent(Path p, String kind, Factory factory) {
    return switch (kind) {
      case "mpeg.mp3" -> new MpegFile(p, ReadOptions.TAGS_ONLY, factory);
      case "flac.flac" -> new FlacFile(p, ReadOptions.TAGS_ONLY, factory);
      case "wav.wav" -> new WavFile(p, ReadOptions.TAGS_ONLY, factory);
      case "aiff.aiff" -> new AiffFile(p, ReadOptions.TAGS_ONLY, factory);
      case "dsf.dsf" -> new DsfFile(p, ReadOptions.TAGS_ONLY, factory);
      case "tta.tta" -> new TrueAudioFile(p, ReadOptions.TAGS_ONLY, factory);
      case "dff.dff" -> new DsdiffFile(p, ReadOptions.TAGS_ONLY, factory);
      default -> throw new IllegalArgumentException(kind);
    };
  }

  static Tag parentTag(FileBase f) {
    return switch (f) {
      case MpegFile p -> p.id3v2Tag();
      case FlacFile p -> p.id3v2Tag();
      case WavFile p -> p.id3v2Tag();
      case TrueAudioFile p -> p.id3v2Tag();
      case DsdiffFile p -> p.id3v2Tag();
      default -> (Tag) f.tag();
    };
  }

  static void parents(Path root) throws Exception {
    for (String name :
      List.of("mpeg.mp3", "flac.flac", "wav.wav", "aiff.aiff", "dsf.dsf", "tta.tta", "dff.dff"))
      for (int decode : new int[]{0, 1}) {
        var factory = new Factory();
        factory.setDefaultTextEncoding(UTF_16);
        var handler = new Utf8();
        Tag.setLatin1StringHandler(decode == 1 ? handler : null);
        String key = "parent/" + name + "/" + decode;
        Path target = root.resolve("outputs").resolve("parent-" + name + "-" + decode + ".java");
        Files.copy(
          root.resolve("parents").resolve(name), target, StandardCopyOption.REPLACE_EXISTING);
        try (var f = parent(target, name, factory)) {
          var t = parentTag(f);
          if (!f.isValid() || t == null || t.frameList().size() != 2)
            throw new AssertionError("Unexpected ordinary parent read");
          var child = t.frameList().getFirst();
          System.out.println(
            key
              + "/read\t"
              + text(t.title())
              + ":"
              + props(t.properties())
              + ":"
              + counts(factory)
              + ":"
              + frame(child));
          t.setTitle("changed café");
          t.addFrame(new TableOfContentsFrame(new ByteVector("toc")));
          if (!SaveOutcome.save(f)) throw new AssertionError("Unexpected ordinary parent save");
          System.out.println(
            key
              + "/identity\t"
              + b(t == parentTag(f))
              + ":"
              + b(child == t.frameList().getFirst())
              + ":"
              + text(t.title())
              + ":"
              + props(t.properties())
              + ":"
              + frame(child));
        }
        System.out.println(
          key + "/save/0/bytes\t" + hex(new ByteVector(Files.readAllBytes(target))));
        try (var reread = parent(target, name, factory)) {
          var r = parentTag(reread);
          System.out.println(
            key
              + "/reread\t"
              + text(r.title())
              + ":"
              + props(r.properties())
              + ":"
              + counts(factory)
              + ":"
              + frame(r.frameList().getFirst()));
        }
        Tag.setLatin1StringHandler(null);
      }
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    var toc = new TableOfContentsFrame(new ByteVector("toc"));
    System.out.println("default/toc\t" + frame(toc) + "\t" + hex(toc.render()));
    for (String line : Files.readAllLines(root.resolve("inputs.tsv"))) {
      String[] columns = line.split("\t");
      String name = columns[0];
      boolean mutate = columns[1].equals("1");
      byte[] original = Files.readAllBytes(root.resolve("inputs").resolve(name));
      for (int mode : new int[]{-1, 0, 1, 3})
        for (int decode : new int[]{0, 1})
          for (boolean path : new boolean[]{false, true}) {
            var factory = new Factory();
            if (mode >= 0) factory.setDefaultTextEncoding(TextCharsets.ALL[mode]);
            var handler = new Utf8();
            Tag.setLatin1StringHandler(decode == 1 ? handler : null);
            String key = name + "/" + mode + "/" + decode + "/" + (path ? "path" : "borrowed");
            Path target =
              root.resolve("outputs")
                .resolve(
                  name
                    + "-"
                    + mode
                    + "-"
                    + decode
                    + "-"
                    + (path ? "path" : "borrowed")
                    + ".java");
            var stream = new ByteVectorStream(original);
            if (path) Files.write(target, original);
            try (var file =
                   path
                     ? new MpegFile(target, ReadOptions.TAGS_ONLY, factory)
                     : new MpegFile(stream, ReadOptions.TAGS_ONLY, factory)) {
              snapshot(key + "/read", file, factory);
              for (var version : Tag.Version.values()) {
                var rendered = file.id3v2Tag().render(version);
                System.out.println(key + "/render/" + version.code() + "\t" + hex(rendered));
                snapshot(key + "/rendered/" + version.code(), file, factory);
              }
              if (mutate)
                for (int round = 0; round < 2; round++) {
                  if (round == 0) common(file.id3v2Tag());
                  else properties(file.id3v2Tag());
                  boolean saved =
                    SaveOutcome.save(file,
                      2,
                      MpegFile.StripTags.STRIP_OTHERS,
                      round == 0 ? Tag.Version.V3 : Tag.Version.V4);
                  if (!saved) throw new AssertionError("Unexpected ordinary save rejection");
                  snapshot(key + "/save/" + round, file, factory);
                  ByteVector output =
                    path ? new ByteVector(Files.readAllBytes(target)) : stream.data();
                  System.out.println(key + "/save/" + round + "/bytes\t" + hex(output));
                }
              if (mutate && !path) Files.write(target, stream.data().toByteArray());
            }
            if (mutate)
              try (var reread = new MpegFile(target, ReadOptions.TAGS_ONLY, factory)) {
                snapshot(key + "/reread", reread, factory);
              }
            else if (path) Files.delete(target);
            Tag.setLatin1StringHandler(null);
          }
    }
    parents(root);
  }
}
