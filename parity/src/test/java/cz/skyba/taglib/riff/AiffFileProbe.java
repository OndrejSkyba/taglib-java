package cz.skyba.taglib.riff;

import cz.skyba.taglib.parity.support.OpenOutcome;


import cz.skyba.taglib.parity.support.SaveOutcome;

import cz.skyba.taglib.parity.support.FileRefAdapter;

import cz.skyba.taglib.*;
import cz.skyba.taglib.id3.v2.*;
import cz.skyba.taglib.id3.v2.Tag;
import cz.skyba.taglib.riff.aiff.AiffFile;
import cz.skyba.taglib.riff.aiff.Properties;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.IOStream;
import cz.skyba.taglib.toolkit.TagString;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.TreeMap;

public class AiffFileProbe {
  static String hex(ByteVector b) {
    return b.toHex().toString();
  }

  static String hex(String s) {
    return hex(new TagString(s).data(TagString.Type.UTF8));
  }

  static String list(List<String> values) {
    var out = new StringBuilder(values.size() + ":");
    for (var v : values) out.append(hex(v)).append(',');
    return out.toString();
  }

  static String props(PropertyMap p) {
    var out = new StringBuilder();
    for (var e : new TreeMap<>(p.asMap()).entrySet())
      out.append(hex(e.getKey())).append('=').append(list(e.getValue())).append(';');
    return out + "/" + list(p.unsupportedData());
  }

  static int bit(boolean b) {
    return b ? 1 : 0;
  }

  static void row(String key, Object... values) {
    var out = new StringBuilder(key);
    for (var v : values) out.append('\t').append(v instanceof Boolean b ? bit(b) : v);
    System.out.println(out);
  }

  static class Aiff extends AiffFile {
    Aiff(Path path, boolean enabled, ReadStyle style) {
      super(path, new ReadOptions(enabled, style, ReadOptions.DEFAULT.maxMetadataBytes()));
    }

    Aiff(Path path) {
      super(path);
    }

    Aiff(IOStream stream) {
      super(stream);
    }

    Aiff(IOStream stream, boolean enabled, ReadStyle style, FrameFactory factory) {
      super(stream, new ReadOptions(enabled, style, ReadOptions.DEFAULT.maxMetadataBytes()), factory);
    }
  }

  static class Chunks extends RiffFile {
    Chunks(IOStream stream, boolean big) {
      super(stream, big ? Endianness.BigEndian : Endianness.LittleEndian);
    }

    Chunks(Path path, boolean big) {
      super(path, big ? Endianness.BigEndian : Endianness.LittleEndian);
    }

    @Override
    public TagBase tag() {
      return null;
    }

    @Override
    public AudioProperties audioProperties() {
      return null;
    }

    @Override
    public void save() {
      throw new cz.skyba.taglib.TagLibException("Test save rejected");
    }
  }

  static class OverrideTag extends Aiff {
    int mode;
    Tag alternate = new Tag();

    OverrideTag(IOStream stream, int mode) {
      super(stream);
      this.mode = mode;
      if (mode == 2) alternate.setTitle("guard");
    }

    @Override
    public Tag tag() {
      return mode == 0 ? null : mode == 3 ? super.tag() : alternate;
    }

    Tag ownedTag() {
      return super.tag();
    }
  }

  static class Named extends ByteVectorStream {
    String filename;
    StringBuilder trace = new StringBuilder();
    boolean ro;
    int fault, calls;

    Named(byte[] b, String name) {
      super(b);
      filename = name;
    }

    @Override
    public String name() {
      return filename;
    }

    @Override
    public boolean readOnly() {
      return ro;
    }

    @Override
    public void insert(ByteVector b, long start, long replace) {
      trace
        .append('I')
        .append(start)
        .append(',')
        .append(replace)
        .append(',')
        .append(b.size())
        .append(';');
      if (fault != 0 && ++calls == fault) throw new IllegalStateException("I/O failure");
      super.insert(b, start, replace);
    }

    @Override
    public void removeBlock(long start, long count) {
      trace.append('R').append(start).append(',').append(count).append(';');
      if (fault != 0 && ++calls == fault) throw new IllegalStateException("I/O failure");
      super.removeBlock(start, count);
    }
  }

  static class Factory extends FrameFactory {
    int calls;

    @Override
    public Frame createFrame(ByteVector data, Header header) {
      calls++;
      return super.createFrame(data, header);
    }
  }

  static void layout(String key, RiffFile f) {
    var chunks = new StringBuilder();
    for (int i = 0; i < f.chunkCount(); i++)
      chunks
        .append(hex(f.chunkName(i)))
        .append(',')
        .append(f.chunkOffset(i))
        .append(',')
        .append(f.chunkDataSize(i))
        .append(',')
        .append(f.chunkDataSize64(i))
        .append(',')
        .append(f.chunkPadding(i))
        .append(';');
    row(
      key + "/layout",
      f.isValid(),
      f.riffSize(),
      f.chunkCount(),
      chunks,
      f.isOpen() ? f.tell() : 0);
  }

  static void properties(String key, Properties p) {
    if (p == null) row(key + "/audio", false);
    else
      row(
        key + "/audio",
        true,
        p.lengthInMilliseconds(),
        p.lengthInSeconds(),
        p.bitrate(),
        p.sampleRate(),
        p.channels(),
        p.bitsPerSample(),
        p.sampleFrames(),
        p.isAiffC(),
        hex(p.compressionType()),
        hex(p.compressionName()));
  }

  static void state(String key, Aiff f) {
    var t = f.tag();
    if (t == null) row(key + "/tag", f.isOpen(), f.isValid(), f.hasId3v2Tag(), false);
    else
      row(
        key + "/tag",
        f.isOpen(),
        f.isValid(),
        f.hasId3v2Tag(),
        true,
        t.isEmpty(),
        hex(t.title()),
        hex(t.artist()),
        hex(t.album()),
        hex(t.comment()),
        hex(t.genre()),
        t.year(),
        t.track(),
        t.header().majorVersion(),
        props(f.properties()));
    properties(key, f.detailedProperties());
    layout(key, f);
  }

  static void edit(Aiff f, int mode, int step) {
    var t = f.tag();
    if (mode == 0) return;
    if (mode == 1) {
      t.setTitle("Title");
      t.setArtist("Artist");
      t.setYear(0xffff_ffffL);
      t.setTrack(0x8000_0000L);
    } else if (mode == 2)
      f.replaceProperties(
        PropertyMap.builder()
          .put("TITLE", List.of("one", "two"))
          .put("DATE", "2026")
          .put("BAD KEY", "reject")
          .put("COMMENT", "comment")
          .build());
    else if (mode == 3) while (!t.frameList().isEmpty()) t.removeFrame(t.frameList().getFirst());
    else if (mode == 4) {
      var a = new AttachedPictureFrame();
      a.setMimeType("image/png");
      a.setDescription("picture");
      a.setPicture(new ByteVector("picture bytes"));
      t.addFrame(a);
      t.addFrame(
        new UnknownFrame(
          new ByteVector(new byte[]{'Z', 'Z', 'Z', 'Z', 0, 0, 0, 3, 0, 0, 'r', 'a', 'w'})));
    } else if (mode == 5) t.setTitle(step == 0 ? "x".repeat(8192) : step == 1 ? "short" : "");
    else {
      t.setTitle("X".repeat(254));
      t.setArtist("Artist A");
    }
  }

  public static void main(String[] args) throws Exception {
    var root = Path.of(args[0]);
    List<Path> inputs;
    try (var paths = Files.list(root.resolve("inputs"))) {
      inputs = paths.sorted().toList();
    }
    for (var path : inputs) {
      String name = path.getFileName().toString();
      byte[] data = Files.readAllBytes(path);
      for (int style = 0; style < 3; style++)
        for (int enabled = 0; enabled < 2; enabled++) {
          String key = name + "/controls/" + style + "/" + enabled;
          var mode = ReadStyle.values()[style];
          try (var f = new Aiff(path, enabled != 0, mode)) {
            state(key + "/path", f);
          }
          for (int origin : new int[]{0, 7}) {
            var stream = new Named(data, name);
            stream.seek(origin);
            var factory = new Factory();
            try (var f = new Aiff(stream, enabled != 0, mode, factory)) {
              state(key + "/io/" + origin, f);
              row(key + "/factory/" + origin, factory.calls);
              properties(key + "/direct/" + origin, new Properties(f, mode));
              row(key + "/direct-cursor/" + origin, f.tell());
            }
            row(key + "/lifetime/" + origin, stream.isOpen());
          }
        }
      for (String filename : new String[]{"sample.aiff", "sample.unknown"})
        for (int origin : new int[]{0, 7}) {
          var stream = new Named(data, filename);
          stream.seek(origin);
          row(
            name + "/support/" + filename + "/" + origin,
            Aiff.isSupported(stream),
            stream.tell());
          try (var ref = new FileRefAdapter(stream)) {
            row(
              name + "/ref/" + filename + "/" + origin,
              ref.isNull(),
              ref.file() instanceof AiffFile,
              stream.tell());
          }
        }
      for (int mode = 0; mode < 7; mode++)
        for (int transport = 0; transport < 2; transport++) {
          String key = name + "/edit/" + mode + "/" + transport;
          var copy = root.resolve("outputs/" + name + "." + mode + ".java");
          Files.copy(path, copy, StandardCopyOption.REPLACE_EXISTING);
          var stream = new Named(data, name);
          try (var f = transport != 0 ? new Aiff(stream) : new Aiff(copy)) {
            var t = f.tag();
            var h = t.header();
            for (int step = 0; step < 3; step++) {
              edit(f, mode, step);
              state(key + "/before/" + step, f);
              var version =
                mode == 6
                  ? step % 2 == 0 ? Tag.Version.V3 : Tag.Version.V4
                  : mode % 2 == 0 ? Tag.Version.V4 : Tag.Version.V3;
              boolean saved = SaveOutcome.save(f, version);
              if (transport == 0) f.seek(f.tell());
              byte[] bytes =
                transport != 0 ? stream.data().toByteArray() : Files.readAllBytes(copy);
              row(
                key + "/save/" + step,
                saved,
                f.tell(),
                hex(new ByteVector(bytes)),
                f.tag() == t,
                t.header() == h);
              state(key + "/after/" + step, f);
              try (var reopened = new Aiff(new ByteVectorStream(bytes))) {
                state(key + "/reopen/" + step, reopened);
              }
            }
            row(key + "/trace", stream.trace);
          }
        }
      for (int big : new int[]{0, 1})
        for (int transport : new int[]{0, 1}) {
          var stream = new Named(data, name);
          var copy = root.resolve("outputs/" + name + ".riff." + big + ".java");
          Files.copy(path, copy, StandardCopyOption.REPLACE_EXISTING);
          try (var f = transport != 0 ? new Chunks(stream, big != 0) : new Chunks(copy, big != 0)) {
            String key = name + "/chunks/" + big + "/" + transport;
            layout(key + "/initial", f);
            for (int i = 0; i < f.chunkCount(); i++)
              row(key + "/data/" + i, hex(f.chunkData(i)), f.tell());
            for (int operation = 0; operation < 8; operation++) {
              if (operation == 0) f.setChunkData(0, new ByteVector("five!"));
              if (operation == 1)
                f.setChunkData(new ByteVector("LIST"), new ByteVector("one"), true);
              if (operation == 2)
                f.setChunkData(new ByteVector("LIST"), new ByteVector("two"), true);
              if (operation == 3)
                f.setChunkData(new ByteVector("LIST"), new ByteVector("replacement"));
              if (operation == 4)
                f.setChunkData(new ByteVector("JUNK"), new ByteVector("ignored"), true);
              if (operation == 5) f.removeChunk(new ByteVector("LIST"));
              if (operation == 6) f.removeChunk(0);
              if (operation == 7) {
                f.setChunkData(999999, new ByteVector("ignored"));
                f.removeChunk(999999);
              }
              layout(key + "/after/" + operation, f);
              if (transport == 0) f.seek(f.tell());
              row(
                key + "/bytes/" + operation,
                hex(transport != 0 ? stream.data() : new ByteVector(Files.readAllBytes(copy))),
                f.tell());
            }
            row(
              key + "/invalid",
              f.chunkOffset(999999),
              f.chunkDataSize(999999),
              f.chunkDataSize64(999999),
              f.chunkPadding(999999),
              hex(f.chunkName(999999)),
              hex(f.chunkData(999999)),
              f.tell());
            row(key + "/trace", stream.trace);
          }
        }
      var stream = new Named(data, name);
      stream.ro = true;
      try (var f = new Aiff(stream)) {
        f.tag().setTitle("readonly");
        row(name + "/readonly", SaveOutcome.save(f), hex(stream.data()), f.hasId3v2Tag());
      }
    }
    byte[] data = Files.readAllBytes(root.resolve("inputs/duplicates.aiff"));
    for (int mode = 0; mode < 4; mode++) {
      var stream = new Named(data, "subclass.aiff");
      try (var f = new OverrideTag(stream, mode)) {
        String key = "subclass/" + mode;
        row(key + "/properties-before", props(f.properties()));
        f.replaceProperties(PropertyMap.builder().put("TITLE", "owned").build());
        f.ownedTag()
          .addFrame(
            new UnknownFrame(
              new ByteVector(
                new byte[]{'Z', 'Z', 'Z', 'Z', 0, 0, 0, 3, 0, 0, 'r', 'a', 'w'})));
        row(key + "/unsupported", props(f.properties()));
        f.removeUnsupportedProperties(List.of("UNKNOWN/ZZZZ"));
        row(
          key + "/properties-after",
          props(f.properties()),
          hex(f.ownedTag().title()),
          hex(f.alternate.title()));
        row(
          key + "/save/0",
          SaveOutcome.save(f),
          f.hasId3v2Tag(),
          hex(stream.data()),
          hex(f.ownedTag().title()));
        state(key + "/after", f);
      }
    }
    for (int fault = 1; fault <= 12; fault++) {
      var stream = new Named(data, "callback.aiff");
      try (var f = new Aiff(stream)) {
        stream.fault = fault;
        f.tag().setTitle("callback");
        boolean threw = false, saved = false;
        try {
          saved = SaveOutcome.save(f);
        } catch (IllegalStateException failure) {
          threw = true;
        }
        String key = "callback/" + fault;
        row(
          key + "/result",
          threw,
          saved,
          f.hasId3v2Tag(),
          f.tell(),
          hex(stream.data()),
          stream.trace);
        layout(key, f);
      }
    }
    row("support/null", Aiff.isSupported(null));
    row("missing/rejected", OpenOutcome.rejected(() -> new Aiff(root.resolve("missing.aiff"))));
  }
}
