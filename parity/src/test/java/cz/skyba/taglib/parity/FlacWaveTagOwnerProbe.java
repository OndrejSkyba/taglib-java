package cz.skyba.taglib.parity;

import cz.skyba.taglib.parity.support.TagSelections;
import cz.skyba.taglib.flac.FlacFile;
import cz.skyba.taglib.id3.v2.Tag.Version;
import cz.skyba.taglib.parity.support.OpenOutcome;


import cz.skyba.taglib.parity.support.SaveOutcome;

import cz.skyba.taglib.parity.support.FileRefAdapter;

import cz.skyba.taglib.*;
import cz.skyba.taglib.id3.v2.Frame;
import cz.skyba.taglib.id3.v2.FrameFactory;
import cz.skyba.taglib.id3.v2.Header;
import cz.skyba.taglib.riff.wav.WavFile;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.IOStream;
import cz.skyba.taglib.toolkit.TagString;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.TreeMap;

/**
 * FLAC and Wave tag-owner operations; no native fault cases.
 */
public class FlacWaveTagOwnerProbe {
  static String hex(ByteVector b) {
    return b.toHex().toString();
  }

  static String hex(String s) {
    return hex(new TagString(s).data(TagString.Type.UTF8));
  }

  static String props(PropertyMap p) {
    var out = new StringBuilder();
    for (var e : new TreeMap<>(p.asMap()).entrySet()) {
      out.append(hex(e.getKey())).append('=');
      for (var v : e.getValue()) out.append(hex(v)).append(',');
      out.append(';');
    }
    out.append('/');
    for (var v : p.unsupportedData()) out.append(hex(v)).append(',');
    return out.toString();
  }

  static void row(String key, Object... values) {
    var out = new StringBuilder(key);
    for (var v : values) out.append('\t').append(v instanceof Boolean b ? b ? 1 : 0 : v);
    System.out.println(out);
  }

  static class Stream extends ByteVectorStream {
    String name;
    boolean ro;
    int fault, calls;
    StringBuilder trace = new StringBuilder();

    Stream(byte[] b, String name) {
      super(b);
      this.name = name;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public boolean readOnly() {
      return ro;
    }

    void edit(String s) {
      trace.append(s);
      if (fault > 0 && ++calls == fault)
        throw new IllegalStateException("ordinary callback failure");
    }

    @Override
    public void insert(ByteVector d, long s, long r) {
      edit("I" + s + "," + r + "," + d.size() + ";");
      super.insert(d, s, r);
    }

    @Override
    public void removeBlock(long s, long n) {
      edit("R" + s + "," + n + ";");
      super.removeBlock(s, n);
    }

    @Override
    public void writeBlock(ByteVector d) {
      edit("W" + tell() + "," + d.size() + ";");
      super.writeBlock(d);
    }

    @Override
    public void truncate(long n) {
      edit("T" + n + ";");
      super.truncate(n);
    }
  }

  static class Factory extends FrameFactory {
    int calls;

    @Override
    public Frame createFrame(ByteVector b, Header h) {
      calls++;
      return super.createFrame(b, h);
    }
  }

  static FileBase open(Path p, boolean read, ReadStyle style) {
    return OpenOutcome.file(() -> p.toString().endsWith(".flac")
      ? new FlacFile(p, new ReadOptions(read, style, ReadOptions.DEFAULT.maxMetadataBytes()))
      : new WavFile(p, new ReadOptions(read, style, ReadOptions.DEFAULT.maxMetadataBytes())));
  }

  static FileBase open(IOStream s, boolean flac, boolean read, ReadStyle style, FrameFactory ff) {
    return OpenOutcome.file(() -> flac
      ? new FlacFile(s, new ReadOptions(read, style, ReadOptions.DEFAULT.maxMetadataBytes()), ff)
      : new WavFile(s, new ReadOptions(read, style, ReadOptions.DEFAULT.maxMetadataBytes()), ff));
  }

  static cz.skyba.taglib.id3.v2.Tag id3(FileBase f, boolean create) {
    return f instanceof FlacFile a
      ? (create ? a.getOrCreateId3v2Tag() : a.id3v2Tag())
      : ((WavFile) f).id3v2Tag();
  }

  static void tag(String key, TagBase t) {
    if (t == null) {
      row(key, false);
      return;
    }
    row(
      key,
      true,
      t.isEmpty(),
      hex(t.title()),
      hex(t.artist()),
      hex(t.album()),
      hex(t.comment()),
      hex(t.genre()),
      t.year(),
      t.track(),
      props(t.properties()));
  }

  static void state(String key, FileBase f) {
    if (f == null) {
      row(key + "/rejected", true);
      return;
    }
    row(key + "/file", f.isOpen(), f.isValid(), f.tell(), f.length());
    tag(key + "/union", f.tag());
    tag(key + "/id3", id3(f, false));
    if (f instanceof FlacFile a) {
      tag(key + "/xiph", a.xiphComment());
      tag(key + "/v1", a.id3v1Tag());
      row(
        key + "/presence",
        a.hasXiphComment(),
        a.hasId3v1Tag(),
        a.hasId3v2Tag(),
        a.hasiXMLData(),
        a.hasBEXTData(),
        hex(a.iXMLData()),
        hex(new ByteVector(a.BEXTData())));
      var p = a.detailedProperties();
      if (p == null) row(key + "/audio", false);
      else
        row(
          key + "/audio",
          true,
          p.lengthInMilliseconds(),
          p.bitrate(),
          p.sampleRate(),
          p.channels(),
          p.bitsPerSample(),
          p.sampleFrames(),
          hex(new ByteVector(p.signature())));
      row(key + "/pictures", a.pictureList().size());
      for (int i = 0; i < a.pictureList().size(); i++) {
        var pic = a.pictureList().get(i);
        row(
          key + "/picture/" + i,
          pic.type().code(),
          pic.width(),
          pic.height(),
          pic.colorDepth(),
          pic.numColors(),
          hex(pic.mimeType()),
          hex(pic.description()),
          hex(new ByteVector(pic.data())));
      }
    } else {
      var a = (WavFile) f;
      tag(key + "/info", a.infoTag());
      row(
        key + "/presence",
        a.hasId3v2Tag(),
        a.hasInfoTag(),
        a.hasiXMLData(),
        a.hasBEXTData(),
        hex(a.iXMLData()),
        hex(new ByteVector(a.BEXTData())));
      var p = a.detailedProperties();
      if (p == null) row(key + "/audio", false);
      else
        row(
          key + "/audio",
          true,
          p.lengthInMilliseconds(),
          p.bitrate(),
          p.sampleRate(),
          p.channels(),
          p.bitsPerSample(),
          p.sampleFrames(),
          p.format());
    }
    row(key + "/properties", props(f.properties()));
  }

  static void strip(FileBase f, int mask) {
    if (f instanceof FlacFile a) a.removeTags(TagSelections.flac(mask));
    else ((WavFile) f).strip(TagSelections.wav(mask));
  }

  static void edit(FileBase f, int mode, int step) {
    if (mode == 0) return;
    if (mode == 1) {
      f.tag().setTitle(step == 0 ? "Title" : "changed");
      f.tag().setArtist("Artist");
      f.tag().setAlbum("Album");
      f.tag().setComment("Comment");
      f.tag().setGenre("Jazz");
      f.tag().setYear(0xffffffffL);
      f.tag().setTrack(0x80000000L);
    }
    if (mode == 2) row("unused", 0);
    if (mode == 3) {
      id3(f, true).setTitle("ID3 title");
      id3(f, true).setArtist("ID3 artist");
      if (f instanceof FlacFile a) {
        a.getOrCreateId3v1Tag().setTitle("v1 title");
        a.xiphComment().setTitle("Xiph title");
      } else ((WavFile) f).infoTag().setTitle("Info title");
    }
    if (mode == 4) {
      String v = step == 0 ? "x".repeat(8192) : step == 1 ? "short" : "";
      f.tag().setTitle(v);
    }
    if (mode == 5) {
      if (f instanceof FlacFile a) {
        a.setiXMLData(step < 2 ? "<xml>Žluť</xml>" : "");
        a.setBEXTData(step < 2 ? new ByteVector("broadcast") : new ByteVector());
        var p = new cz.skyba.taglib.flac.Picture();
        p.setData(new byte[]{0, 1, (byte) 255});
        p.setMimeType("image/png");
        p.setDescription("physical");
        p.setWidth(3);
        p.setHeight(2);
        p.setColorDepth(24);
        p.setType(cz.skyba.taglib.flac.Picture.Type.FrontCover);
        if (step == 0) {
          a.addPicture(p);
          var x = new cz.skyba.taglib.flac.Picture();
          x.setData(new byte[]{7, 8});
          a.xiphComment().addPicture(x);
        } else if (step == 1 && !a.pictureList().isEmpty()) {
          a.pictureList().getFirst().setDescription("mutated");
          a.removePicture(p);
        } else a.removePictures();
      } else {
        var a = (WavFile) f;
        a.setiXMLData(step < 2 ? "<xml>Žluť</xml>" : "");
        a.setBEXTData(step < 2 ? new ByteVector("broadcast") : new ByteVector());
      }
    }
    if (mode == 6)
      strip(f, step == 0 ? 0 : step == 1 ? f instanceof FlacFile ? 4 : 1 : 65535);
  }

  static void complex(String key, FileBase f) {
    var names = f.complexPropertyKeys();
    var text = new StringBuilder();
    for (var n : names) text.append(hex(n)).append(',');
    row(key + "/keys", text);
    for (String k : List.of("PICTURE", "picture", "OTHER")) {
      var values = f.complexProperties(k);
      row(key + "/complex/" + k, values.size());
      for (int i = 0; i < values.size(); i++) {
        var out = new StringBuilder();
        for (var e : new TreeMap<>(values.get(i)).entrySet()) {
          var v = e.getValue();
          out.append(hex(e.getKey())).append('=');
          if (e.getKey().equals("data")) out.append('b').append(hex(v.toByteVector()));
          else if (List.of("width", "height", "colorDepth", "numColors").contains(e.getKey()))
            out.append('i').append(v.toInt());
          else out.append('s').append(hex(v.toString()));
          out.append(';');
        }
        row(key + "/complex/" + k + "/" + i, out);
      }
    }
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    List<Path> inputs;
    try (var paths = Files.list(root.resolve("inputs"))) {
      inputs = paths.sorted().toList();
    }
    for (var path : inputs) {
      String name = path.getFileName().toString();
      boolean flac = name.endsWith(".flac");
      byte[] bytes = Files.readAllBytes(path);
      for (int style = 0; style < 3; style++)
        for (int read = 0; read < 2; read++) {
          String key = name + "/controls/" + style + "/" + read;
          try (var f = open(path, read != 0, ReadStyle.values()[style])) {
            state(key + "/path", f);
          }
          for (int origin : new int[]{0, 7}) {
            var stream = new Stream(bytes, name);
            stream.seek(origin);
            var factory = new Factory();
            try (var f = open(stream, flac, read != 0, ReadStyle.values()[style], factory)) {
              state(key + "/io/" + origin, f);
              row(key + "/factory/" + origin, factory.calls);
              if (f != null && !flac) {
                var p =
                  new cz.skyba.taglib.riff.wav.Properties(
                    (WavFile) f, ReadStyle.values()[style]);
                row(
                  key + "/direct/" + origin,
                  p.lengthInMilliseconds(),
                  p.bitrate(),
                  p.sampleRate(),
                  p.channels(),
                  p.bitsPerSample(),
                  p.sampleFrames(),
                  p.format(),
                  f.tell());
              }
            }
            row(key + "/lifetime/" + origin, stream.isOpen());
          }
        }
      for (String filename : List.of(name, "sample.unknown")) {
        var stream = new Stream(bytes, filename);
        stream.seek(7);
        row(
          name + "/support/" + filename,
          flac
            ? FlacFile.isSupported(stream)
            : WavFile.isSupported(stream),
          stream.tell());
        stream.seek(0);
        try (var ref = new FileRefAdapter(stream)) {
          row(
            name + "/ref/" + filename,
            ref.isNull(),
            ref.file() instanceof FlacFile
              || ref.file() instanceof WavFile,
            stream.tell());
        }
      }
      try (var check = open(path, false, ReadStyle.AVERAGE)) {
        if (check == null) continue;
      }
      for (int transport = 0; transport < 2; transport++)
        for (int mode : new int[]{0, 1, 2, 3, 4, 5, 6})
          for (int mask :
            flac
              ? new int[]{0, 1, 2, 3, 4, 5, 6, 7, 65535, 0xfff0}
              : new int[]{0, 1, 2, 3, 65535, 0xfff0})
            for (int retain = 0; retain < 2; retain++) {
              // FLAC has one save operation; masks apply to explicit strip, retain selects default
              // overload.
              if (flac && retain != 0) continue;
              if (mode != 3 && mode != 6 && (mask != 65535 || retain != 0)) continue;
              String key = name + "/edit/" + transport + "/" + mode + "/" + mask + "/" + retain;
              var stream = new Stream(bytes, name);
              Path copy = root.resolve("outputs/" + key.replace('/', '_') + ".java");
              Files.copy(path, copy, StandardCopyOption.REPLACE_EXISTING);
              Path named = copy.resolveSibling(copy.getFileName() + (flac ? ".flac" : ".wav"));
              Files.copy(path, named, StandardCopyOption.REPLACE_EXISTING);
              try (var f =
                     transport == 0
                       ? open(named, true, ReadStyle.AVERAGE)
                       : open(stream, flac, true, ReadStyle.AVERAGE, null)) {
                var union = f.tag();
                for (int step = 0; step < 3; step++) {
                  if (mode == 2)
                    row(
                      key + "/rejected/" + step,
                      props(
                        f.replaceProperties(
                          PropertyMap.builder()
                            .put("TITLE", List.of("one", "two"))
                            .put("COMMENT", "comment")
                            .put("CUSTOM", "custom")
                            .put("A=B", "invalid")
                            .build()).rejected()));
                  else edit(f, mode, step);
                  if (flac && mode == 6) strip(f, mask);
                  state(key + "/before/" + step, f);
                  complex(key + "/before/" + step, f);
                  boolean saved =
                    flac
                      ? SaveOutcome.save(f)
                      : SaveOutcome.save((WavFile) f,
                        mask,
                        retain == 0
                          ? FileBase.StripTags.STRIP_OTHERS
                          : FileBase.StripTags.STRIP_NONE,
                        step % 2 == 0
                          ? Version.V3
                          : Version.V4);
                  byte[] output =
                    transport == 0 ? Files.readAllBytes(named) : stream.data().toByteArray();
                  row(
                    key + "/save/" + step,
                    saved,
                    f.tell(),
                    hex(new ByteVector(output)),
                    f.tag() == union);
                  state(key + "/after/" + step, f);
                  try (var reopened =
                         open(new ByteVectorStream(output), flac, true, ReadStyle.AVERAGE, null)) {
                    state(key + "/reopen/" + step, reopened);
                  }
                }
                row(key + "/trace", stream.trace);
                if (transport == 0) Files.copy(named, copy, StandardCopyOption.REPLACE_EXISTING);
                else Files.write(copy, stream.data().toByteArray());
              }
              Files.deleteIfExists(named);
            }
      var ro = new Stream(bytes, name);
      ro.ro = true;
      try (var f = open(ro, flac, false, ReadStyle.AVERAGE, null)) {
        row(name + "/readonly", SaveOutcome.save(f), hex(ro.data()));
      }
    }
  }
}
