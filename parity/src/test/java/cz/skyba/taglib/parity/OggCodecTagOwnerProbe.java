package cz.skyba.taglib.parity;

import cz.skyba.taglib.ogg.OggFile;
import cz.skyba.taglib.ogg.flac.OggFlacFile;
import cz.skyba.taglib.ogg.opus.OpusFile;
import cz.skyba.taglib.ogg.speex.SpeexFile;
import cz.skyba.taglib.ogg.vorbis.VorbisFile;
import cz.skyba.taglib.parity.support.OpenOutcome;


import cz.skyba.taglib.parity.support.SaveOutcome;

import cz.skyba.taglib.parity.support.FileRefAdapter;

import cz.skyba.taglib.*;
import cz.skyba.taglib.ogg.XiphComment;
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
 * Defined ordinary codec-owner operations; faulting native inputs are excluded.
 */
public class OggCodecTagOwnerProbe {
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
    final String name;
    boolean ro;
    int fault, calls;
    StringBuilder trace = new StringBuilder();

    Stream(byte[] b, String n) {
      super(b);
      name = n;
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

  static int kind(String name) {
    return name.endsWith(".opus") ? 1 : name.endsWith(".spx") ? 2 : name.endsWith(".oga") ? 3 : 0;
  }

  static OggFile open(Path p, int codec, boolean read, ReadStyle style) {
    return OpenOutcome.file(() -> switch (codec) {
      case 0 -> new VorbisFile(p, new ReadOptions(read, style, ReadOptions.DEFAULT.maxMetadataBytes()));
      case 1 -> new OpusFile(p, new ReadOptions(read, style, ReadOptions.DEFAULT.maxMetadataBytes()));
      case 2 -> new SpeexFile(p, new ReadOptions(read, style, ReadOptions.DEFAULT.maxMetadataBytes()));
      default -> new OggFlacFile(p, new ReadOptions(read, style, ReadOptions.DEFAULT.maxMetadataBytes()));
    });
  }

  static OggFile open(IOStream s, int codec, boolean read, ReadStyle style) {
    return OpenOutcome.file(() -> switch (codec) {
      case 0 -> new VorbisFile(s, new ReadOptions(read, style, ReadOptions.DEFAULT.maxMetadataBytes()));
      case 1 -> new OpusFile(s, new ReadOptions(read, style, ReadOptions.DEFAULT.maxMetadataBytes()));
      case 2 -> new SpeexFile(s, new ReadOptions(read, style, ReadOptions.DEFAULT.maxMetadataBytes()));
      default -> new OggFlacFile(s, new ReadOptions(read, style, ReadOptions.DEFAULT.maxMetadataBytes()));
    });
  }

  static boolean support(IOStream s, int codec) {
    return switch (codec) {
      case 0 -> VorbisFile.isSupported(s);
      case 1 -> OpusFile.isSupported(s);
      case 2 -> SpeexFile.isSupported(s);
      default -> OggFlacFile.isSupported(s);
    };
  }

  static XiphComment tag(FileBase f) {
    return (XiphComment) f.tag();
  }

  static void state(String key, OggFile f, int codec) {
    if (f == null) {
      row(key + "/rejected", true);
      return;
    }
    row(key + "/file", f.isOpen(), f.isValid(), f.tell(), f.length());
    var t = tag(f);
    if (t == null) row(key + "/tag", false);
    else {
      row(
        key + "/tag",
        true,
        t.isEmpty(),
        hex(t.title()),
        hex(t.artist()),
        hex(t.album()),
        hex(t.comment()),
        hex(t.genre()),
        t.year(),
        t.track(),
        hex(t.vendorID()),
        t.fieldCount(),
        props(f.properties()));
      row(key + "/pictures", t.pictureList().size());
      for (int i = 0; i < t.pictureList().size(); i++)
        row(key + "/picture/" + i, hex(new ByteVector(t.pictureList().get(i).render())));
    }
    var a = f.audioProperties();
    if (a == null) row(key + "/audio", false);
    else {
      String extra = "";
      if (f instanceof VorbisFile x) {
        var p = x.detailedProperties();
        extra =
          p.vorbisVersion()
            + ","
            + p.bitrateMaximum()
            + ","
            + p.bitrateNominal()
            + ","
            + p.bitrateMinimum();
      } else if (f instanceof OpusFile x) {
        var p = x.detailedProperties();
        extra = p.opusVersion() + "," + p.inputSampleRate() + "," + p.outputGain();
      } else if (f instanceof SpeexFile x) {
        var p = x.detailedProperties();
        extra = p.speexVersion() + "," + p.bitrateNominal();
      } else {
        var p = ((OggFlacFile) f).detailedProperties();
        extra =
          p.bitsPerSample() + "," + p.sampleFrames() + "," + hex(new ByteVector(p.signature()));
      }
      row(
        key + "/audio",
        true,
        a.lengthInMilliseconds(),
        a.bitrate(),
        a.sampleRate(),
        a.channels(),
        extra);
    }
    if (codec == 3) {
      var x = (OggFlacFile) f;
      row(key + "/flac", x.hasXiphComment(), x.streamLength());
    }
    for (int i = 0; i < 4; i++) row(key + "/packet/" + i, hex(f.packet(i)));
    var first = f.firstPageHeader();
    var last = f.lastPageHeader();
    row(
      key + "/headers",
      first != null,
      last != null,
      first == null ? 0 : first.absoluteGranularPosition(),
      last == null ? 0 : last.absoluteGranularPosition(),
      last == null ? 0 : last.pageSequenceNumber(),
      f.tell());
  }

  static void direct(String key, OggFile f, int codec, ReadStyle style)
    throws Exception {
    AudioProperties a;
    String extra;
    if (codec == 0) {
      var p = new cz.skyba.taglib.ogg.vorbis.Properties(f, style);
      a = p.common();
      extra =
        p.vorbisVersion()
          + ","
          + p.bitrateMaximum()
          + ","
          + p.bitrateNominal()
          + ","
          + p.bitrateMinimum();
    } else if (codec == 1) {
      var p = new cz.skyba.taglib.ogg.opus.Properties(f, style);
      a = p.common();
      extra = p.opusVersion() + "," + p.inputSampleRate() + "," + p.outputGain();
    } else {
      var p = new cz.skyba.taglib.ogg.speex.Properties(f, style);
      a = p.common();
      extra = p.speexVersion() + "," + p.bitrateNominal();
    }
    row(key, a.lengthInMilliseconds(), a.bitrate(), a.sampleRate(), a.channels(), extra, f.tell());
  }

  static void complex(String key, FileBase f) {
    var keys = new StringBuilder();
    for (var k : f.complexPropertyKeys()) keys.append(hex(k)).append(',');
    row(key + "/keys", keys);
    for (String k : List.of("PICTURE", "picture", "OTHER")) {
      var values = f.complexProperties(k);
      row(key + "/complex/" + k, values.size());
      for (int i = 0; i < values.size(); i++) {
        var out = new StringBuilder();
        for (var e : new TreeMap<>(values.get(i)).entrySet()) {
          out.append(hex(e.getKey())).append('=');
          var v = e.getValue();
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

  static void edit(OggFile f, int mode, int step) {
    var t = tag(f);
    if (mode == 1) {
      t.setTitle(step == 0 ? "Title" : "changed");
      t.setArtist("Artist");
      t.setAlbum("Album");
      t.setComment("Comment");
      t.setGenre("Jazz");
      t.setYear(0xffffffffL);
      t.setTrack(0x80000000L);
    }
    if (mode == 3) t.setTitle(step == 0 ? "x".repeat(65536) : step == 1 ? "short" : "");
    if (mode == 4) {
      if (step == 0) {
        var p = new cz.skyba.taglib.flac.Picture();
        p.setData(new byte[]{0, 1, (byte) 255});
        p.setMimeType("image/png");
        p.setDescription("picture");
        p.setType(cz.skyba.taglib.flac.Picture.Type.FrontCover);
        p.setWidth(5);
        p.setHeight(6);
        p.setColorDepth(24);
        p.setNumColors(7);
        t.addPicture(p);
      } else if (step == 1) {
        var p = t.pictureList().getFirst();
        p.setDescription("mutated");
        p.setWidth(9);
      } else t.removeAllPictures();
    }
    if (mode == 5) {
      t.addField("title", "first");
      t.addField("TITLE", "first", false);
      t.addField("title", "second", false);
      t.addField("COMMENT", "comment");
      t.addField("DESCRIPTION", "description");
      if (step == 1) t.removeFields("TITLE", "first");
      if (step == 2) t.removeAllFields();
    }
    if (mode == 6) {
      f.setPacket(1, new ByteVector("raw comment"));
      t.setTitle("live overwrites raw");
      f.setPacket(99, new ByteVector("ignored"));
    }
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    for (var path : Files.list(root.resolve("inputs")).sorted().toList()) {
      String name = path.getFileName().toString();
      int codec = kind(name);
      byte[] bytes = Files.readAllBytes(path);
      for (int style = 0; style < 3; style++)
        for (int enabled = 0; enabled < 2; enabled++) {
          String key = name + "/controls/" + style + "/" + enabled;
          var mode = ReadStyle.values()[style];
          try (var f = open(path, codec, enabled != 0, mode)) {
            state(key + "/path", f, codec);
          }
          for (int origin : new int[]{0, 7}) {
            var stream = new Stream(bytes, name);
            stream.seek(origin);
            try (var f = open(stream, codec, enabled != 0, mode)) {
              state(key + "/io/" + origin, f, codec);
              if (f != null && codec < 3) direct(key + "/direct/" + origin, f, codec, mode);
            }
            row(key + "/lifetime/" + origin, stream.isOpen());
          }
        }
      for (String filename : List.of(name, "sample.unknown")) {
        var stream = new Stream(bytes, filename);
        stream.seek(7);
        row(name + "/support/" + filename, support(stream, codec), stream.tell());
        stream.seek(0);
        try (var ref = new FileRefAdapter(stream)) {
          int actual =
            ref.file() instanceof VorbisFile
              ? 0
              : ref.file() instanceof OpusFile
              ? 1
              : ref.file() instanceof SpeexFile
              ? 2
              : ref.file() instanceof OggFlacFile ? 3 : -1;
          row(name + "/ref/" + filename, ref.isNull(), actual, stream.tell());
        }
      }
      try (var f = open(path, codec, false, ReadStyle.AVERAGE)) {
        if (f == null) continue;
      }
      for (int transport = 0; transport < 2; transport++)
        for (int mode = 0; mode < 7; mode++) {
          String key = name + "/edit/" + transport + "/" + mode;
          var stream = new Stream(bytes, name);
          Path output = root.resolve("outputs/" + key.replace('/', '_') + ".java");
          Path named =
            output.resolveSibling(
              output.getFileName()
                + "."
                + (codec == 0 ? "ogg" : codec == 1 ? "opus" : codec == 2 ? "spx" : "oga"));
          Files.copy(path, named, StandardCopyOption.REPLACE_EXISTING);
          try (var f =
                 transport == 0
                   ? open(named, codec, true, ReadStyle.AVERAGE)
                   : open(stream, codec, true, ReadStyle.AVERAGE)) {
            var original = f.tag();
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
              state(key + "/before/" + step, f, codec);
              complex(key + "/before/" + step, f);
              boolean saved = SaveOutcome.save(f);
              byte[] data =
                transport == 0 ? Files.readAllBytes(named) : stream.data().toByteArray();
              row(
                key + "/save/" + step,
                saved,
                f.tell(),
                hex(new ByteVector(data)),
                f.tag() == original);
              state(key + "/after/" + step, f, codec);
              try (var reopened =
                     open(new ByteVectorStream(data), codec, true, ReadStyle.AVERAGE)) {
                state(key + "/reopen/" + step, reopened, codec);
              }
            }
            row(key + "/trace", stream.trace);
            Files.write(
              output, transport == 0 ? Files.readAllBytes(named) : stream.data().toByteArray());
          }
          Files.delete(named);
        }
      var ro = new Stream(bytes, name);
      ro.ro = true;
      try (var f = open(ro, codec, false, ReadStyle.AVERAGE)) {
        var original = f.tag();
        original.setTitle("readonly");
        boolean saved = SaveOutcome.save(f);
        row(name + "/readonly", saved, hex(ro.data()), f.tag() == original);
        ro.ro = false;
        row(name + "/readonly-retry", SaveOutcome.save(f), hex(ro.data()));
      }
    }
  }
}
