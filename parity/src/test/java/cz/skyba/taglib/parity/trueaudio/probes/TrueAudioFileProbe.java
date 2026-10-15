package cz.skyba.taglib.parity.trueaudio.probes;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.id3.v2.*;
import cz.skyba.taglib.internal.TextBytes;
import cz.skyba.taglib.parity.support.FileRefAdapter;
import cz.skyba.taglib.parity.support.OpenOutcome;
import cz.skyba.taglib.parity.support.SaveOutcome;
import cz.skyba.taglib.parity.support.TagSelections;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.FileStream;
import cz.skyba.taglib.trueaudio.TrueAudioFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_16BE;
import static java.nio.charset.StandardCharsets.UTF_8;

// Independent Java side of the pinned TrueAudio File/shared ID3 owner protocol.

public class TrueAudioFileProbe {
  static String hex(ByteVector b) {
    return b.toHex().toString();
  }

  static String hex(String s) {
    return hex(ByteVector.fromText(s, UTF_16BE));
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
      setDefaultTextEncoding(UTF_8);
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
      return TextBytes.decodeTerminated(d.data(), UTF_8);
    }
  }

  static void state(String id, TrueAudioFile f) {
    var t = f.tag();
    var p = f.detailedProperties();
    var v2 = f.id3v2Tag();
    var v1 = f.id3v1Tag();
    System.out.println(
      id
        + "/handles\t"
        + b(f.isOpen())
        + "\t"
        + b(f.isValid())
        + "\t"
        + b(t != null)
        + "\t"
        + b(p != null)
        + "\t"
        + b(v2 != null)
        + ":"
        + b(v1 != null)
        + ":"
        + b(f.hasId3v2Tag())
        + ":"
        + b(f.hasId3v1Tag()));
    System.out.println(
      id
        + "/union\t"
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
        + props(f.properties()));
    if (v2 != null) {
      var h = v2.header();
      StringBuilder line =
        new StringBuilder(
          id
            + "/modern\t"
            + b(v2.isEmpty())
            + "\t"
            + hex(v2.title())
            + "\t"
            + props(v2.properties())
            + "\t"
            + h.majorVersion()
            + ":"
            + h.tagSize()
            + ":"
            + h.completeTagSize()
            + "\t");
      for (var frame : v2.frameList())
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
    if (v1 != null)
      System.out.println(
        id
          + "/legacy\t"
          + b(v1.isEmpty())
          + "\t"
          + hex(v1.title())
          + "\t"
          + props(v1.properties()));
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
          + p.ttaVersion()
          + "\t"
          + p.sampleFrames());
  }

  static class Named extends ByteVectorStream {
    String n;
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

  }

  static void edit(TrueAudioFile f, int mode) {
    if (mode == 0) return;
    if (mode == 1) {
      f.tag().setArtist("edited artist");
      f.tag().setYear(0xffffffffL);
      f.tag().setTrack(0x80000000L);
      return;
    }
    if (mode == 2) {
      f.getOrCreateId3v2Tag().setTitle("modern edited");
      f.getOrCreateId3v1Tag().setTitle("legacy edited");
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
      var t = f.getOrCreateId3v2Tag();
      t.addFrame(
        new UnknownFrame(new ByteVector(new byte[]{'Z', 'Z', 'Z', 'Z', 0, 0, 0, 1, 0, 0, 'x'})));
      var picture = new AttachedPictureFrame();
      picture.setPicture(new ByteVector("opaque picture"));
      picture.setMimeType("image/test");
      t.addFrame(picture);
      return;
    }
    if (mode >= 5 && mode <= 7) {
      f.removeTags(TagSelections.trueAudio(mode == 5 ? 2 : mode == 6 ? 1 : 65535));
      return;
    }
    if (mode == 8) {
      f.getOrCreateId3v2Tag().setTitle("x".repeat(5000));
      return;
    }
    if (mode == 9) {
      f.getOrCreateId3v1Tag().setTitle("legacy only");
      f.removeTags(TagSelections.trueAudio(2));
      return;
    }
    if (mode == 10) {
      if (f.id3v2Tag() != null)
        for (var x : new ArrayList<>(f.id3v2Tag().frameList())) f.id3v2Tag().removeFrame(x);
      if (f.id3v1Tag() != null) f.id3v1Tag().replaceProperties(PropertyMap.EMPTY);
      return;
    }
    f.removeUnsupportedProperties(f.properties().unsupportedData());
  }

  static void mutations(String base, TrueAudioFile f, int mode, Supplier<ByteVector> bytes, boolean memory) {
    long original = f.hasId3v2Tag() ? f.id3v2Tag().header().completeTagSize() : 0;
    edit(f, mode);
    var t = f.tag();
    var p = f.detailedProperties();
    var v2 = f.id3v2Tag();
    var v1 = f.id3v1Tag();
    var h = v2 != null ? v2.header() : null;
    var first = v2 != null && !v2.frameList().isEmpty() ? v2.frameList().getFirst() : null;
    state(base + "/edited", f);
    for (int n = 0; n < 3; n++) {
      if (memory && original > bytes.get().size() && v2 != null && !v2.isEmpty()) {
        var candidate = v2.render();
        if (candidate.size() > original) {
          System.out.println(
            base
              + "/undefined-memory-growth/"
              + n
              + "\t"
              + original
              + "\t"
              + bytes.get().size()
              + "\t"
              + candidate.size());
          var before = new ByteVector(bytes.get());
          if (SaveOutcome.save(f) || !before.equals(bytes.get()))
            throw new AssertionError("unsafe native memory plan was not rejected before write");
          break;
        }
      }
      boolean saved = SaveOutcome.save(f);
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
          + b(p == f.detailedProperties())
          + ":"
          + b(v2 == f.id3v2Tag())
          + ":"
          + b(v1 == f.id3v1Tag())
          + ":"
          + b(h != null && h == v2.header())
          + ":"
          + b(first != null && v2.frameList().contains(first)));
      state(base + "/after/" + n, f);
      original = f.hasId3v2Tag() ? v2.header().completeTagSize() : 0;
      if (n == 1) f.tag().setTitle("short");
    }
  }

  static class CustomFrame extends Frame {
    long value;

    CustomFrame(long v) {
      super(new ByteVector("CUST"));
      value = v;
    }

    CustomFrame(ByteVector d, Frame.Header h) {
      super(h);
      value = fieldData(d).toUnsignedInt();
    }

    public String toString() {
      return Long.toString(value);
    }

    protected PropertyMap asProperties() {
      return PropertyMap.builder().put("CUSTOM", toString()).build();
    }

    protected void parseFields(ByteVector d) {
      value = d.toUnsignedInt();
    }

    protected ByteVector renderFields() {
      return ByteVector.fromUnsignedInt(value);
    }
  }

  static class CustomFactory extends FrameFactory {
    protected Frame createFrame(ByteVector d, Frame.Header h, Header t) {
      return h.frameID().equals(new ByteVector("CUST"))
        ? new CustomFrame(d, h)
        : super.createFrame(d, h, t);
    }

    public Frame createFrameForProperty(String k, List<String> v) {
      return k.equals("CUSTOM")
        ? new CustomFrame(v.isEmpty() ? 0 : Long.parseLong(v.getFirst()))
        : super.createFrameForProperty(k, v);
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
            try (var f = new TrueAudioFile(input, new ReadOptions(enabled, style, ReadOptions.DEFAULT.maxMetadataBytes()))) {
              state(base + "/path", f);
            }
            for (int origin : new int[]{0, 7}) {
              var s = new ByteVectorStream(data);
              s.seek(origin);
              try (var f = new TrueAudioFile(s, new ReadOptions(enabled, style, ReadOptions.DEFAULT.maxMetadataBytes()))) {
                state(base + "/io/" + origin, f);
              }
            }
          }
        for (int origin : new int[]{0, 7}) {
          var s = new ByteVectorStream(data);
          s.seek(origin);
          boolean supported = TrueAudioFile.isSupported(s);
          System.out.println(id + "/support/" + origin + "\t" + b(supported) + "\t" + s.tell());
        }
        for (int mode = 0; mode < 4; mode++)
          for (int decode = 0; decode < 2; decode++) {
            var c = new Factory(mode);
            var u = new Utf8();
            Tag.setLatin1StringHandler(decode != 0 ? u : null);
            var s = new Named(data, "sample.tta");
            try (var f = new TrueAudioFile(s, new ReadOptions(false, ReadStyle.FAST, ReadOptions.DEFAULT.maxMetadataBytes()), mode != 0 ? c : null)) {
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
                  + u.calls);
              if (f.tag() != null) {
                f.tag().setComment("factory setter");
                System.out.println(base + "/render\t" + hex(f.getOrCreateId3v2Tag().render()));
              }
            }
            Tag.setLatin1StringHandler(null);
          }
        for (String name : List.of("sample.tta", "sample.unknown"))
          for (int origin : new int[]{0, 7})
            for (boolean enabled : new boolean[]{false, true}) {
              var s = new Named(data, name);
              s.seek(origin);
              try (var ref = new cz.skyba.taglib.parity.support.FileRefAdapter(s, enabled)) {
                var f = ref.file() instanceof TrueAudioFile actual ? actual : null;
                String base = id + "/ref/" + name + "/" + origin + "/" + b(enabled);
                System.out.println(
                  base + "\t" + b(ref.isNull()) + "\t" + b(f != null));
                if (f != null) {
                  state(base, f);
                  System.out.println(base + "/identity\t" + b(ref.tag() == f.tag()));
                }
              }
            }
        for (int mode = 0; mode < 12; mode++) {
          String base = id + "/edit/" + mode;
          var s = new ByteVectorStream(data);
          try (var f = new TrueAudioFile(s)) {
            mutations(base + "/io", f, mode, s::data, true);
          }
          Path copy = root.resolve("outputs").resolve(id + "." + mode + ".java");
          Files.copy(input, copy, StandardCopyOption.REPLACE_EXISTING);
          try (var f = new TrueAudioFile(copy)) {
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
              },
              false);
          }
        }
        for (int mask : new int[]{0, 1, 2, 3, 4, 65535, -1, 0x80000000}) {
          var stream = new ByteVectorStream(data);
          try (var f = new TrueAudioFile(stream, ReadOptions.TAGS_ONLY)) {
            f.removeTags(TagSelections.trueAudio(mask));
            state(id + "/strip/" + mask, f);
          }
        }
        var custom = new Factory(1);
        var stream = new ByteVectorStream(data);
        try (var f = new TrueAudioFile(stream, ReadOptions.TAGS_ONLY, custom)) {
          state(id + "/custom-factory", f);
          System.out.println(
            id
              + "/custom-factory/hooks\t"
              + custom.raw
              + ":"
              + custom.typed
              + ":"
              + custom.aggregate);
        }
        var ro = new Named(data, "readonly.tta");
        try (var f = new TrueAudioFile(ro)) {
          ro.ro = true;
          boolean saved = SaveOutcome.save(f);
          System.out.println(
            id + "/readonly\t" + b(saved) + "\t" + f.tell() + "\t" + hex(ro.data()));
        }
      }
    }
    System.out.println("support/null\t" + b(TrueAudioFile.isSupported(null)));
    System.out.println("open-stream/rejected\t" + b(OpenOutcome.rejected(
      () -> new FileStream(root.resolve("missing.tta")))));
    System.out.println("open-file/rejected\t" + b(OpenOutcome.rejected(
      () -> new TrueAudioFile(root.resolve("missing.tta")))));
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
    var original = new ByteVector(Files.readAllBytes(root.resolve("inputs/plain.tta")));
    var stream = new ByteVectorStream(original);
    try (var f = new TrueAudioFile(stream)) {
      var frame = new ThrowFrame();
      f.getOrCreateId3v2Tag().addFrame(frame);
      boolean threw = false;
      try {
        SaveOutcome.save(f);
      } catch (IllegalStateException expected) {
        threw = true;
      }
      System.out.println(
        "helper/render-throw\t"
          + b(threw)
          + "\t"
          + b(stream.data().equals(original))
          + "\t"
          + f.id3v2Tag().header().majorVersion()
          + "\t"
          + frame.header().version()
          + "\t"
          + b(f.hasId3v2Tag()));
    }

    for (int mode = 0; mode < 3; mode++) {
      var streamNull = new ByteVectorStream(Files.readAllBytes(root.resolve("inputs/plain.tta")));
      try (var f = new TrueAudioFile(streamNull, ReadOptions.TAGS_ONLY, null)) {
        f.seek(0);
        var t = f.id3v2Tag();
        t.setTitle("");
        t.setComment("");
        if (mode == 1) {
          var title = new TextIdentificationFrame(new ByteVector("TIT2"), ISO_8859_1);
          title.setText("existing");
          t.addFrame(title);
          t.setTitle("changed");
        }
        if (mode == 2) f.replaceProperties(PropertyMap.builder().put("ARRANGER", "a").build());
        state("helper/null-factory/" + mode, f);
        boolean saved = SaveOutcome.save(f);
        System.out.println(
          "helper/null-factory/"
            + mode
            + "/save\t"
            + b(saved)
            + "\t"
            + f.tell()
            + "\t"
            + hex(streamNull.data()));
      }
    }

    class Trace extends ByteVectorStream {
      StringBuilder ops = new StringBuilder();
      TrueAudioFile file;
      boolean callback;

      Trace(byte[] d) {
        super(d);
      }

      public void insert(ByteVector d, long start, long replace) {
        ops.append("insert:")
          .append(start)
          .append(':')
          .append(replace)
          .append(':')
          .append(d.size())
          .append(';');
        super.insert(d, start, replace);
        if (callback) file.getOrCreateId3v1Tag().setTitle("from IO callback");
      }

      public void removeBlock(long start, long size) {
        ops.append("remove:").append(start).append(':').append(size).append(';');
        super.removeBlock(start, size);
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
    for (int mode = 0; mode < 2; mode++) {
      var traced = new Trace(Files.readAllBytes(root.resolve("inputs/plain.tta")));
      try (var f = new TrueAudioFile(traced, ReadOptions.TAGS_ONLY)) {
        traced.file = f;
        traced.callback = mode == 1;
        f.id3v2Tag().setTitle("modern");
        if (mode == 0) f.getOrCreateId3v1Tag().setTitle("legacy");
        for (int n = 0; n < 3; n++) {
          traced.ops.setLength(0);
          boolean saved = SaveOutcome.save(f);
          System.out.println(
            "helper/io-order/"
              + mode
              + "/"
              + n
              + "\t"
              + b(saved)
              + "\t"
              + traced.ops
              + "\t"
              + hex(traced.data()));
          state("helper/io-order/" + mode + "/" + n, f);
          if (n == 0) f.id3v2Tag().setTitle("x".repeat(5000));
          if (n == 1) f.removeTags();
        }
      }
    }
    for (int mode = 0; mode < 4; mode++) {
      var c = new Factory(1);
      var path = root.resolve("inputs/plain.tta");
      try (var f =
             switch (mode) {
               case 0 -> new TrueAudioFile(path, new ReadOptions(false, ReadStyle.FAST, ReadOptions.DEFAULT.maxMetadataBytes()), c);
               case 1 -> new TrueAudioFile(path, ReadOptions.TAGS_ONLY, c);
               case 2 -> new TrueAudioFile(path, ReadOptions.TAGS_ONLY, null);
               default -> new TrueAudioFile(path, new ReadOptions(false, ReadStyle.FAST, ReadOptions.DEFAULT.maxMetadataBytes()), null);
             }) {
        state("helper/path-factory/" + mode, f);
      }
    }

    class Failing extends ByteVectorStream {
      Failing(byte[] d) {
        super(d);
      }

      public void truncate(long size) {
        throw new IllegalStateException("growth hook");
      }
    }
    var failedStream = new Failing(Files.readAllBytes(root.resolve("inputs/plain.tta")));
    try (var f = new TrueAudioFile(failedStream, ReadOptions.TAGS_ONLY)) {
      f.getOrCreateId3v1Tag().setTitle("legacy");
      boolean failed = false;
      try {
        SaveOutcome.save(f);
      } catch (IllegalStateException expected) {
        failed = true;
      }
      System.out.println(
        "helper/io-failure\t"
          + b(failed)
          + "\t"
          + b(f.hasId3v1Tag())
          + "\t"
          + b(f.hasId3v2Tag())
          + "\t"
          + hex(failedStream.data()));
    }

    var customPath = root.resolve("outputs/helper-custom-factory.java");
    Files.copy(root.resolve("inputs/plain.tta"), customPath, StandardCopyOption.REPLACE_EXISTING);
    var customFactory = new CustomFactory();
    for (int n = 0; n < 6; n++) {
      try (var f = new TrueAudioFile(customPath, ReadOptions.DEFAULT, n >= 3 ? customFactory : null)) {
        var t = f.getOrCreateId3v2Tag();
        if (n == 0) {
          for (var x : new ArrayList<>(t.frameList())) t.removeFrame(x);
          t.setArtist("An artist");
          t.setTitle("A title");
        }
        if (n == 1) t.addFrame(new CustomFrame(1234567890));
        if (n == 3) f.removeTags();
        if (n == 4) t.replaceProperties(PropertyMap.builder().put("CUSTOM", "305419896").build());
        state("helper/custom-factory/" + n, f);
        if (n == 0 || n == 1 || n == 3 || n == 4) {
          boolean saved = SaveOutcome.save(f);
          f.seek(f.tell());
          System.out.println(
            "helper/custom-factory/save/"
              + n
              + "\t"
              + b(saved)
              + "\t"
              + f.tell()
              + "\t"
              + hex(new ByteVector(Files.readAllBytes(customPath))));
        }
      }
    }
  }
}
