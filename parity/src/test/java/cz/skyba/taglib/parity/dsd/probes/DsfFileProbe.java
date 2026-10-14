package cz.skyba.taglib.parity.dsd.probes;

import cz.skyba.taglib.parity.support.OpenOutcome;
import cz.skyba.taglib.parity.support.SaveOutcome;

import cz.skyba.taglib.ReadOptions;

import cz.skyba.taglib.parity.support.FileRefAdapter;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.dsf.DsfFile;
import cz.skyba.taglib.id3.v2.*;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.FileStream;
import cz.skyba.taglib.toolkit.TagString;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

// Independent Java side of the pinned DSF File/shared ID3 owner protocol.

public class DsfFileProbe {
  static String hex(ByteVector b) {
    return b.toHex().toString();
  }

  static String hex(String s) {
    return hex(new TagString(s).data(TagString.Type.UTF16BE));
  }

  static String list(List<String> v) {
    StringBuilder s = new StringBuilder(v.size() + ":");
    v.forEach(a -> s.append(hex(a)).append(','));
    return s.toString();
  }

  static String props(PropertyMap p) {
    StringBuilder s = new StringBuilder();
    p.asMap().forEach((k, v) -> s.append(hex(k)).append('=').append(list(v)).append(';'));
    return s + "/" + list(p.unsupportedData());
  }

  static int b(boolean v) {
    return v ? 1 : 0;
  }

  static class Factory extends FrameFactory {
    int raw, typed, aggregate, mode;

    Factory(int m) {
      mode = m;
      setDefaultTextEncoding(TagString.Type.UTF8);
    }

    public Frame createFrame(ByteVector d, Header h) {
      raw++;
      return mode == 2 ? null : super.createFrame(d, h);
    }

    protected Frame createFrame(ByteVector d, Frame.Header h, Header t) {
      typed++;
      var f = super.createFrame(d, h, t);
      if (mode == 3 && f instanceof TextIdentificationFrame s) s.setText("factory text");
      return f;
    }

    public void rebuildAggregateFrames(Tag t) {
      aggregate++;
      super.rebuildAggregateFrames(t);
    }
  }

  static class Utf8 extends Latin1StringHandler {
    int calls;

    public String parse(ByteVector d) {
      calls++;
      return new TagString(d, TagString.Type.UTF8).toString();
    }
  }

  static DsfFile open(Path path) {
    return open(path, ReadOptions.DEFAULT);
  }

  static DsfFile open(Path path, ReadOptions options) {
    return OpenOutcome.file(() -> new DsfFile(path, options));
  }

  static DsfFile open(cz.skyba.taglib.toolkit.IOStream stream) {
    return open(stream, ReadOptions.DEFAULT);
  }

  static DsfFile open(cz.skyba.taglib.toolkit.IOStream stream, ReadOptions options) {
    return OpenOutcome.file(() -> new DsfFile(stream, options));
  }

  static DsfFile open(cz.skyba.taglib.toolkit.IOStream stream, ReadOptions options, FrameFactory factory) {
    return OpenOutcome.file(() -> new DsfFile(stream, options, factory));
  }

  static void state(String id, DsfFile f) {
    if (f == null) {
      System.out.println(id + "/rejected\t1");
      return;
    }
    var t = f.tag();
    var p = f.detailedProperties();
    System.out.println(
      id
        + "/handles\t"
        + b(f.isOpen())
        + "\t"
        + b(f.isValid())
        + "\t"
        + f.tell()
        + "\t"
        + b(t != null)
        + "\t"
        + b(p != null));
    if (t != null) {
      var h = t.header();
      StringBuilder line =
        new StringBuilder(
          id
            + "/tag\t"
            + b(t.isEmpty())
            + "\t"
            + hex(t.title())
            + "\t"
            + hex(t.artist())
            + "\t"
            + hex(t.album())
            + "\t"
            + hex(t.comment())
            + "\t"
            + hex(t.genre())
            + "\t"
            + t.year()
            + "\t"
            + t.track()
            + "\t"
            + props(f.properties())
            + "\t"
            + h.majorVersion()
            + ":"
            + h.tagSize()
            + ":"
            + h.completeTagSize()
            + "\t");
      for (var frame : t.frameList())
        line.append(hex(frame.frameID()))
          .append(':')
          .append(hex(frame.toString()))
          .append(':')
          .append(frame.header().version())
          .append(':')
          .append(frame.size())
          .append(',');
      System.out.println(line);
    }
    if (p != null)
      System.out.println(
        id
          + "/audio\t"
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
          + p.formatVersion()
          + "\t"
          + p.formatID()
          + "\t"
          + p.channelType()
          + "\t"
          + p.sampleCount()
          + "\t"
          + p.blockSizePerChannel());
  }

  static class Named extends ByteVectorStream {
    String n;
    StringBuilder trace = new StringBuilder();
    boolean ro;

    Named(byte[] d, String n) {
      super(d);
      this.n = n;
    }

    public String name() {
      return n;
    }

    public boolean readOnly() {
      return ro;
    }

    public ByteVector readBlock(long count) {
      long at = tell();
      var data = super.readBlock(count);
      trace.append(at).append(',').append(count).append(',').append(data.size()).append(';');
      return data;
    }
  }

  static void edit(DsfFile f, int mode) {
    var t = f.tag();
    if (mode == 0) return;
    if (mode == 1) {
      t.setArtist("edited artist");
      t.setYear(0xffffffffL);
      t.setTrack(0x80000000L);
      return;
    }
    if (mode == 2) {
      t.removeFrames(new ByteVector("TIT2"));
      t.removeFrames(new ByteVector("ZZZZ"));
      return;
    }
    if (mode == 3) {
      f.replaceProperties(
        PropertyMap.builder()
          .put("TITLE", List.of("one", "two"))
          .put("DATE", "2026-10-05T12:34")
          .put("ARRANGER", List.of("a", "b"))
          .put("PERFORMER:GUITAR", "g")
          .put("ALBUMARTIST", "other")
          .build());
      return;
    }
    if (mode == 4) {
      t.addFrame(
        new UnknownFrame(new ByteVector(new byte[]{'Z', 'Z', 'Z', 'Z', 0, 0, 0, 1, 0, 0, 'x'})));
      var picture = new AttachedPictureFrame();
      picture.setPicture(new ByteVector("opaque picture"));
      picture.setMimeType("image/test");
      t.addFrame(picture);
      return;
    }
    if (mode == 5) {
      for (var x : new ArrayList<>(t.frameList())) t.removeFrame(x);
      return;
    }
    t.setTitle("x".repeat(5000));
  }

  static void mutations(String base, DsfFile f, int mode, Supplier<ByteVector> bytes) {
    if (f == null || f.tag() == null) return;
    var t = f.tag();
    var h = t.header();
    var p = f.detailedProperties();
    var first =
      mode == 2 || mode == 3 || mode == 5 || t.frameList().isEmpty()
        ? null
        : t.frameList().getFirst();
    edit(f, mode);
    state(base + "/edited", f);
    for (int n = 0; n < 3; n++) {
      boolean saved = SaveOutcome.save(f, n == 1 ? Tag.Version.V3 : Tag.Version.V4);
      f.seek(f.tell());
      System.out.println(
        base
          + "/save/"
          + n
          + "\t"
          + b(saved)
          + "\t"
          + f.tell()
          + "\t"
          + hex(bytes.get())
          + "\t"
          + b(t == f.tag())
          + ":"
          + b(h == t.header())
          + ":"
          + b(p == f.detailedProperties())
          + ":"
          + b(first != null && t.frameList().contains(first)));
      state(base + "/after/" + n, f);
      if (n == 1) t.setTitle("short");
    }
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    Files.createDirectories(root.resolve("outputs"));
    try (var paths = Files.list(root.resolve("inputs"))) {
      for (var input : paths.sorted().toList()) {
        String id = input.getFileName().toString();
        byte[] data = Files.readAllBytes(input);
        for (var style : ReadStyle.values())
          for (boolean enabled : new boolean[]{false, true}) {
            String base = id + "/controls/" + style.ordinal() + "/" + b(enabled);
            try (var f = open(input, new ReadOptions(enabled, style, ReadOptions.DEFAULT.maxMetadataBytes()))) {
              state(base + "/path", f);
            }
            for (int origin : new int[]{0, 7}) {
              var s = new ByteVectorStream(data);
              s.seek(origin);
              try (var f = open(s, new ReadOptions(enabled, style, ReadOptions.DEFAULT.maxMetadataBytes()))) {
                state(base + "/io/" + origin, f);
              }
            }
          }
        for (int origin : new int[]{0, 7}) {
          var s = new ByteVectorStream(data);
          s.seek(origin);
          boolean supported = DsfFile.isSupported(s);
          System.out.println(id + "/support/" + origin + "\t" + b(supported) + "\t" + s.tell());
        }
        for (int mode = 0; mode < 4; mode++)
          for (int decode = 0; decode < 2; decode++) {
            var c = new Factory(mode);
            var u = new Utf8();
            Tag.setLatin1StringHandler(decode != 0 ? u : null);
            var s = new Named(data, "sample.dsf");
            try (var f = open(s, new ReadOptions(false, ReadStyle.FAST, ReadOptions.DEFAULT.maxMetadataBytes()), mode != 0 ? c : null)) {
              String base = id + "/factory/" + mode + "/" + decode;
              state(base, f);
              System.out.println(
                base
                  + "/hooks\t"
                  + c.raw
                  + ":"
                  + c.typed
                  + ":"
                  + c.aggregate
                  + ":"
                  + u.calls
                  + "\t"
                  + s.trace);
              if (f != null && f.tag() != null) {
                f.tag().setComment("factory setter");
                System.out.println(base + "/render\t" + hex(f.tag().render()));
              }
            }
            Tag.setLatin1StringHandler(null);
          }
        for (String name : List.of("sample.dsf", "sample.unknown"))
          for (int origin : new int[]{0, 7})
            for (boolean enabled : new boolean[]{false, true}) {
              var s = new Named(data, name);
              s.seek(origin);
              try (var ref = new cz.skyba.taglib.parity.support.FileRefAdapter(s, enabled)) {
                var f = ref.file() instanceof DsfFile actual ? actual : null;
                String base = id + "/ref/" + name + "/" + origin + "/" + b(enabled);
                System.out.println(
                  base + "\t" + b(ref.isNull()) + "\t" + b(f != null) + "\t" + s.tell());
                if (f != null) {
                  state(base, f);
                  System.out.println(base + "/identity\t" + b(ref.tag() == f.tag()));
                }
              }
            }
        for (int mode = 0; mode < 7; mode++) {
          String base = id + "/edit/" + mode;
          var s = new ByteVectorStream(data);
          try (var f = open(s)) {
            mutations(base + "/io", f, mode, s::data);
          }
          Path copy = root.resolve("outputs").resolve(id + "." + mode + ".java");
          Files.copy(input, copy, StandardCopyOption.REPLACE_EXISTING);
          try (var f = open(copy)) {
            mutations(
              base + "/path",
              f,
              mode,
              () -> {
                try {
                  return new ByteVector(Files.readAllBytes(copy));
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });
          }
        }
        if (id.startsWith("prefix-")) {
          int origin = id.startsWith("prefix-7-") ? 7 : 31;
          var stream = new ByteVectorStream(data);
          stream.seek(origin);
          try (var f = open(stream)) {
            mutations(id + "/origin-save/io", f, 1, stream::data);
          }
          Path copy = root.resolve("outputs").resolve(id + ".origin.java");
          Files.copy(input, copy, StandardCopyOption.REPLACE_EXISTING);
          try (var pathStream = new FileStream(copy)) {
            pathStream.seek(origin);
            try (var f = open(pathStream)) {
              mutations(
                id + "/origin-save/path",
                f,
                1,
                () -> {
                  try {
                    return new ByteVector(Files.readAllBytes(copy));
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                });
            }
          }
          var named = new Named(data, "sample.dsf");
          named.seek(origin);
          try (var ref = new cz.skyba.taglib.parity.support.FileRefAdapter(named)) {
            var actual = ref.file() instanceof DsfFile found ? found : null;
            System.out.println(
              id
                + "/origin-ref\t"
                + b(ref.isNull())
                + "\t"
                + b(actual != null)
                + "\t"
                + b(actual != null && ref.tag() == actual.tag()));
            if (actual != null) state(id + "/origin-ref", actual);
          }
        }
        var ro = new Named(data, "readonly.dsf");
        try (var f = open(ro)) {
          ro.ro = true;
          boolean saved = SaveOutcome.save(f);
          System.out.println(
            id + "/readonly\t" + b(saved) + "\t" + ro.tell() + "\t" + hex(ro.data()));
        }
      }
    }
    System.out.println("support/null\t" + b(DsfFile.isSupported(null)));
    System.out.println("open-stream/rejected\t" + b(OpenOutcome.rejected(
      () -> new FileStream(root.resolve("missing.dsf")))));
    System.out.println("open-file/rejected\t" + b(OpenOutcome.rejected(
      () -> new DsfFile(root.resolve("missing.dsf")))));
    class ThrowFrame extends Frame {
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
    var original = new ByteVector(Files.readAllBytes(root.resolve("inputs/plain.dsf")));
    var stream = new ByteVectorStream(original);
    try (var f = open(stream)) {
      var frame = new ThrowFrame();
      f.tag().addFrame(frame);
      boolean threw = false;
      try {
        SaveOutcome.save(f, Tag.Version.V3);
      } catch (IllegalStateException expected) {
        threw = true;
      }
      System.out.println(
        "helper/render-throw\t"
          + b(threw)
          + "\t"
          + b(stream.data().equals(original))
          + "\t"
          + f.tag().header().majorVersion()
          + "\t"
          + frame.header().version());
    }
  }
}
