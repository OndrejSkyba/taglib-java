package cz.skyba.taglib.parity.dsd.probes;

import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.parity.support.OpenOutcome;
import cz.skyba.taglib.parity.support.SaveOutcome;

import cz.skyba.taglib.ReadOptions;

import cz.skyba.taglib.dsdiff.DsdiffFile;
import cz.skyba.taglib.id3.v2.Frame;
import cz.skyba.taglib.id3.v2.Latin1StringHandler;
import cz.skyba.taglib.id3.v2.Tag;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.FileStream;
import cz.skyba.taglib.toolkit.TagString;

import java.nio.file.Files;
import java.nio.file.Path;

public class DsdiffFileHelpers {
  static class ThrowFrame extends Frame {
    ThrowFrame() {
      super(new ByteVector("X001"));
    }

    public String toString() {
      return "";
    }

    protected void parseFields(ByteVector d) {
    }

    protected ByteVector renderFields() {
      throw new IllegalStateException("render hook");
    }
  }

  static class Trace extends ByteVectorStream {
    StringBuilder ops = new StringBuilder();
    DsdiffFile file;
    boolean callback, after;
    int fail = -1, count;

    Trace(byte[] b) {
      super(b);
    }

    public void insert(ByteVector d, long at, long replace) {
      ops.append("insert:")
        .append(at)
        .append(':')
        .append(replace)
        .append(':')
        .append(d.size())
        .append(';');
      int stage = count++;
      if (stage == fail && !after) throw new IllegalStateException("write hook");
      super.insert(d, at, replace);
      if (callback) file.getOrCreateDiinTag().setTitle("IO callback title");
      if (stage == fail && after) throw new IllegalStateException("write hook");
    }

    public void removeBlock(long at, long count) {
      ops.append("remove:").append(at).append(':').append(count).append(';');
      super.removeBlock(at, count);
    }

    public void writeBlock(ByteVector d) {
      ops.append("write:").append(tell()).append(':').append(d.size()).append(';');
      super.writeBlock(d);
    }

    public void truncate(long size) {
      ops.append("truncate:").append(size).append(';');
      super.truncate(size);
    }
  }

  static class Getters extends cz.skyba.taglib.dsdiff.diin.Tag {
    int titles, artists;

    public String title() {
      return switch (titles++) {
        case 0 -> "condition";
        case 1 -> "";
        default -> "A\0B";
      };
    }

    public String artist() {
      artists++;
      return "";
    }
  }

  static class Utf8 extends Latin1StringHandler {
    int calls;

    public String parse(ByteVector d) {
      calls++;
      return new TagString(d, TagString.Type.UTF8).toString();
    }
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    byte[] original = Files.readAllBytes(root.resolve("inputs/both.dff"));
    var s = new ByteVectorStream(original);
    try (var f = new DsdiffFile(s)) {
      f.id3v2Tag().addFrame(new ThrowFrame());
      boolean threw = false;
      try {
        SaveOutcome.save(f, 2);
      } catch (IllegalStateException expected) {
        threw = true;
      }
      System.out.println(
        "helper/render-after-strip\t"
          + DsdiffFileProbe.b(threw)
          + "\t"
          + DsdiffFileProbe.b(f.hasId3v2Tag())
          + ":"
          + DsdiffFileProbe.b(f.hasDiinTag())
          + "\t"
          + DsdiffFileProbe.hex(s.data()));
      DsdiffFileProbe.state("helper/render-after-strip", f);
    }
    for (int mode = 0; mode < 2; ++mode) {
      var stream = new Trace(Files.readAllBytes(root.resolve("inputs/plain.dff")));
      try (var f = new DsdiffFile(stream, ReadOptions.TAGS_ONLY)) {
        stream.file = f;
        stream.callback = mode != 0;
        f.id3v2Tag().setTitle("modern");
        f.diinTag().setTitle("diin");
        for (int n = 0; n < 3; ++n) {
          stream.ops.setLength(0);
          boolean ok = SaveOutcome.save(f);
          System.out.println(
            "helper/io/"
              + mode
              + "/"
              + n
              + "\t"
              + DsdiffFileProbe.b(ok)
              + ":"
              + f.tell()
              + "\t"
              + stream.ops
              + "\t"
              + DsdiffFileProbe.hex(stream.data()));
          DsdiffFileProbe.state("helper/io/" + mode + "/" + n, f);
          if (n == 0) f.id3v2Tag().setTitle("x".repeat(5000));
          if (n == 1) f.tag().setTitle("");
        }
      }
    }
    for (int after = 0; after < 2; ++after)
      for (int stage = 0; stage < 15; ++stage) {
        var stream = new Trace(Files.readAllBytes(root.resolve("inputs/plain.dff")));
        try (var f = new DsdiffFile(stream)) {
          stream.file = f;
          stream.after = after != 0;
          stream.fail = stage;
          f.tag().setTitle("modified");
          f.tag().setArtist("artist");
          boolean threw = false;
          try {
            SaveOutcome.save(f);
          } catch (IllegalStateException expected) {
            threw = true;
          }
          String base = "helper/failure/" + after + "/" + stage;
          System.out.println(
            base
              + "\t"
              + DsdiffFileProbe.b(threw)
              + ":"
              + f.tell()
              + "\t"
              + stream.ops
              + "\t"
              + DsdiffFileProbe.hex(stream.data()));
          DsdiffFileProbe.state(base, f);
        }
      }
    var customStream = new ByteVectorStream(Files.readAllBytes(root.resolve("inputs/plain.dff")));
    try (var f = new DsdiffFile(customStream)) {
      var custom = new Getters();
      f.tag().set(1, custom);
      boolean ok = SaveOutcome.save(f);
      System.out.println(
        "helper/diin-getters\t"
          + DsdiffFileProbe.b(ok)
          + ":"
          + custom.titles
          + ":"
          + custom.artists
          + "\t"
          + DsdiffFileProbe.hex(customStream.data()));
    }
    for (int mode = 0; mode < 2; ++mode) {
      var handler = new Utf8();
      Tag.setLatin1StringHandler(mode != 0 ? handler : null);
      var stream =
        new ByteVectorStream(Files.readAllBytes(root.resolve("inputs/lowercase-root.dff")));
      try (var f = new DsdiffFile(stream)) {
        DsdiffFileProbe.state("helper/latin1/" + mode, f);
        System.out.println("helper/latin1/" + mode + "/calls\t" + handler.calls);
      }
      Tag.setLatin1StringHandler(null);
    }
    // Native mask values assumed by TagSelections; Java exposes enum sets instead.
    System.out.println(
      "helper/enums\t"
        + 0
        + ":"
        + 1
        + ":"
        + 2
        + ":"
        + 65535
        + ":"
        + FileBase.StripTags.STRIP_NONE.ordinal()
        + ":"
        + FileBase.StripTags.STRIP_OTHERS.ordinal());
    System.out.println("helper/support/null\t" + DsdiffFileProbe.b(DsdiffFile.isSupported(null)));
    System.out.println("helper/open-stream/rejected\t" + DsdiffFileProbe.b(OpenOutcome.rejected(
      () -> new FileStream(root.resolve("missing.dff")))));
    System.out.println("helper/open-file/rejected\t" + DsdiffFileProbe.b(OpenOutcome.rejected(
      () -> new DsdiffFile(root.resolve("missing.dff")))));
  }
}
