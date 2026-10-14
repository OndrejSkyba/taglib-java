package cz.skyba.taglib.parity.dsd.probes;

import cz.skyba.taglib.parity.support.TagSelections;
import cz.skyba.taglib.parity.support.OpenOutcome;
import cz.skyba.taglib.parity.support.SaveOutcome;

import cz.skyba.taglib.ReadOptions;

import cz.skyba.taglib.parity.support.FileRefAdapter;

import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.dsdiff.DsdiffFile;
import cz.skyba.taglib.id3.v2.*;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.TagString;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

public class DsdiffFileProbe {
  static int b(boolean v) {
    return v ? 1 : 0;
  }

  static String hex(ByteVector v) {
    return v.toHex().toString();
  }

  static String hex(String v) {
    return hex(new TagString(v).data(TagString.Type.UTF16BE));
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

  static DsdiffFile open(Path path) {
    return open(path, ReadOptions.DEFAULT);
  }

  static DsdiffFile open(Path path, ReadOptions options) {
    return OpenOutcome.file(() -> new DsdiffFile(path, options));
  }

  static DsdiffFile open(cz.skyba.taglib.toolkit.IOStream stream) {
    return open(stream, ReadOptions.DEFAULT);
  }

  static DsdiffFile open(cz.skyba.taglib.toolkit.IOStream stream, ReadOptions options) {
    return OpenOutcome.file(() -> new DsdiffFile(stream, options));
  }

  static DsdiffFile open(cz.skyba.taglib.toolkit.IOStream stream, ReadOptions options, FrameFactory factory) {
    return OpenOutcome.file(() -> new DsdiffFile(stream, options, factory));
  }

  static void state(String id, DsdiffFile f) {
    if (f == null) {
      System.out.println(id + "/rejected\t1");
      return;
    }
    var t = f.tag();
    var p = f.detailedProperties();
    var m = f.id3v2Tag();
    var d = f.diinTag();
    System.out.println(
      id
        + "/handles\t"
        + b(f.isOpen())
        + ":"
        + b(f.isValid())
        + ":"
        + f.tell()
        + ":"
        + b(p != null)
        + ":"
        + b(m != null)
        + ":"
        + b(d != null)
        + ":"
        + b(f.hasId3v2Tag())
        + ":"
        + b(f.hasDiinTag()));
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
    if (m != null) {
      var h = m.header();
      StringBuilder s =
        new StringBuilder(
          id
            + "/modern\t"
            + b(m.isEmpty())
            + "\t"
            + hex(m.title())
            + "\t"
            + props(m.properties())
            + "\t"
            + h.majorVersion()
            + ":"
            + h.tagSize()
            + ":"
            + h.completeTagSize()
            + "\t");
      for (var x : m.frameList())
        s.append(hex(x.frameID())).append(':').append(hex(x.toString())).append(',');
      System.out.println(s);
    }
    if (d != null)
      System.out.println(
        id
          + "/diin\t"
          + b(d.isEmpty())
          + "\t"
          + hex(d.title())
          + "\t"
          + hex(d.artist())
          + "\t"
          + props(d.properties()));
    if (p != null)
      System.out.println(
        id
          + "/audio\t"
          + p.lengthInMilliseconds()
          + ":"
          + p.lengthInSeconds()
          + ":"
          + p.bitrate()
          + ":"
          + p.sampleRate()
          + ":"
          + p.channels()
          + ":"
          + p.bitsPerSample()
          + ":"
          + p.sampleCount());
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
      if (mode == 3 && f instanceof TextIdentificationFrame x) x.setText("factory text");
      return f;
    }

    public void rebuildAggregateFrames(Tag t) {
      aggregate++;
      super.rebuildAggregateFrames(t);
    }
  }

  static class Named extends ByteVectorStream {
    StringBuilder trace = new StringBuilder();
    String n = "";
    boolean ro;

    Named(byte[] d) {
      super(d);
    }

    public String name() {
      return n;
    }

    public boolean readOnly() {
      return ro;
    }

    public ByteVector readBlock(long n) {
      long at = tell();
      var v = super.readBlock(n);
      trace.append(at).append(',').append(n).append(',').append(v.size()).append(';');
      return v;
    }
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]), input = root.resolve("inputs").resolve(args[1]);
    String id = args[1];
    byte[] data = Files.readAllBytes(input);
    boolean writes = args.length > 2;
    for (var style : ReadStyle.values())
      for (boolean enabled : new boolean[]{false, true}) {
        String base = id + "/controls/" + style.ordinal() + "/" + b(enabled);
        try (var f = open(input, new ReadOptions(enabled, style, ReadOptions.DEFAULT.maxMetadataBytes()))) {
          state(base + "/path", f);
        }
        for (int origin : new int[]{0, 7}) {
          var s = new ByteVectorStream(data);
          s.seek(origin);
          try (var m = open(s, new ReadOptions(enabled, style, ReadOptions.DEFAULT.maxMetadataBytes()))) {
            state(base + "/io/" + origin, m);
          }
        }
        var pref = new ByteVector("prefix!");
        pref.append(new ByteVector(data));
        var s = new ByteVectorStream(pref);
        s.seek(7);
        try (var m = open(s, new ReadOptions(enabled, style, ReadOptions.DEFAULT.maxMetadataBytes()))) {
          state(base + "/prefixed", m);
        }
      }
    for (int origin : new int[]{0, 7}) {
      var s = new ByteVectorStream(data);
      s.seek(origin);
      System.out.println(
        id + "/support/" + origin + "\t" + b(DsdiffFile.isSupported(s)) + ":" + s.tell());
    }
    for (int mode = 0; mode < 4; ++mode) {
      var c = new Factory(mode);
      var s = new Named(data);
      try (var f = open(s, new ReadOptions(false, ReadStyle.FAST, ReadOptions.DEFAULT.maxMetadataBytes()), mode != 0 ? c : null)) {
        String base = id + "/factory/" + mode;
        state(base, f);
        System.out.println(
          base + "/hooks\t" + c.raw + ":" + c.typed + ":" + c.aggregate + "\t" + s.trace);
        if (f != null) {
          f.replaceProperties(PropertyMap.builder().put("TITLE", "factory setter").build());
          System.out.println(base + "/render\t" + hex(f.id3v2Tag().render()));
        }
      }
    }
    for (String name : List.of("sample.dff", "sample.unknown"))
      for (int origin : new int[]{0, 7})
        for (boolean enabled : new boolean[]{false, true}) {
          var s = new Named(data);
          s.n = name;
          s.seek(origin);
          try (var ref = new cz.skyba.taglib.parity.support.FileRefAdapter(s, enabled)) {
            var f = ref.file() instanceof DsdiffFile actual ? actual : null;
            String base = id + "/ref/" + name + "/" + origin + "/" + b(enabled);
            System.out.println(base + "\t" + b(ref.isNull()) + ":" + b(f != null) + ":" + s.tell());
            if (f != null) {
              state(base, f);
              System.out.println(base + "/identity\t" + b(ref.tag() == f.tag()));
            }
          }
        }
    {
      var s = new ByteVectorStream(data);
      try (var f = open(s)) {
        if (f == null) {
          System.out.println(id + "/invalid-open\t1:" + b(s.data().equals(new ByteVector(data))));
        }
      }
    }
    if (!writes) return;
    for (int mask : new int[]{0, 1, 2, 3, 4, 65535, -1, 0x80000000})
      for (var strip : FileBase.StripTags.values())
        for (var ver : Tag.Version.values()) {
          String base = id + "/save/" + mask + "/" + strip.ordinal() + "/" + ver.code();
          var s = new ByteVectorStream(data);
          try (var f = open(s)) {
            f.getOrCreateId3v2Tag().setTitle("modern edited");
            f.getOrCreateDiinTag().setTitle("diin edited");
            f.diinTag().setArtist("diin artist");
            var t = f.tag();
            var p = f.detailedProperties();
            var m = f.id3v2Tag();
            var d = f.diinTag();
            for (int n = 0; n < 3; ++n) {
              boolean ok = SaveOutcome.save(f, mask, strip, ver);
              System.out.println(
                base
                  + "/"
                  + n
                  + "/bytes\t"
                  + b(ok)
                  + ":"
                  + f.tell()
                  + ":"
                  + b(t == f.tag())
                  + ":"
                  + b(p == f.detailedProperties())
                  + ":"
                  + b((mask & 2) == 0 || m == f.id3v2Tag())
                  + ":"
                  + b((mask & 1) == 0 || d == f.diinTag())
                  + "\t"
                  + hex(s.data()));
              state(base + "/" + n, f);
              try (var reopened = open(new ByteVectorStream(s.data()))) {
                state(base + "/" + n + "/reopen", reopened);
              }
              if (n == 0) f.tag().setTitle("x");
              if (n == 1) {
                f.getOrCreateId3v2Tag().setTitle("");
                f.id3v2Tag().setArtist("");
                f.getOrCreateDiinTag().setTitle("");
                f.diinTag().setArtist("");
              }
            }
          }
        }
    for (int mask : new int[]{0, 1, 2, 3, 4, 65535, -1, 0x80000000}) {
      var s = new Named(data);
      try (var f = open(s)) {
        s.ro = true;
        String base = id + "/strip/" + mask;
        boolean saved = SaveOutcome.save(f, mask);
        System.out.println(base + "/readonly-save\t" + b(saved) + "\t" + hex(s.data()));
        f.strip(TagSelections.dsdiff(mask));
        state(base, f);
        System.out.println(base + "/bytes\t" + hex(s.data()));
        try (var reopened = open(new ByteVectorStream(s.data()))) {
          state(base + "/reopen", reopened);
        }
      }
    }
    for (int operation = 0; operation < 6; ++operation) {
      Path copy = root.resolve("outputs").resolve(id + "." + operation + ".java");
      Files.copy(input, copy, StandardCopyOption.REPLACE_EXISTING);
      try (var f = open(copy)) {
        String base = id + "/path/" + operation;
        if (operation == 0) f.tag().setArtist("union artist");
        if (operation == 1)
          f.replaceProperties(
            PropertyMap.builder()
              .put("TITLE", List.of("one", "two"))
              .put("DATE", "2026-10-05")
              .put("ALBUM", "album")
              .build());
        if (operation == 2) f.getOrCreateDiinTag().setTitle("A\u0100\u00e9");
        if (operation == 3) f.getOrCreateId3v2Tag().setTitle("x".repeat(5000));
        if (operation == 4) {
          f.getOrCreateId3v2Tag().setTitle("");
          f.getOrCreateDiinTag().setTitle("");
          f.diinTag().setArtist("");
        }
        if (operation == 5) f.removeUnsupportedProperties(f.properties().unsupportedData());
        for (int n = 0; n < 3; ++n) {
          boolean ok = SaveOutcome.save(f);
          f.seek(f.tell());
          System.out.println(
            base
              + "/"
              + n
              + "/bytes\t"
              + b(ok)
              + ":"
              + f.tell()
              + "\t"
              + hex(new ByteVector(Files.readAllBytes(copy))));
          state(base + "/" + n, f);
        }
      }
    }
  }
}
