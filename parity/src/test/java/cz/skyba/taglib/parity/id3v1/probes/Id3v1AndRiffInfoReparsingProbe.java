package cz.skyba.taglib.parity.id3v1.probes;


import cz.skyba.taglib.ape.ApeFile;
import cz.skyba.taglib.flac.FlacFile;
import cz.skyba.taglib.mpc.MpcFile;
import cz.skyba.taglib.mpeg.MpegFile;
import cz.skyba.taglib.parity.support.SaveOutcome;

import cz.skyba.taglib.parity.support.FileRefAdapter;

import cz.skyba.taglib.*;
import cz.skyba.taglib.riff.wav.WavFile;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.IOStream;
import cz.skyba.taglib.toolkit.TagString;
import cz.skyba.taglib.trueaudio.TrueAudioFile;
import cz.skyba.taglib.wavpack.WavPackFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HexFormat;
import java.util.List;

/**
 * ID3v1 and RIFF INFO owner operations; output schema independently matches the pinned C++
 * observer.
 */
class Id3v1AndRiffInfoReparsingProbe {
  static String hex(ByteVector b) {
    return HexFormat.of().formatHex(b.toByteArray());
  }

  static String text(String s) {
    return hex(new TagString(s).data(TagString.Type.UTF16BE));
  }

  static String props(PropertyMap p) {
    var out = new StringBuilder();
    p.asMap()
      .forEach(
        (k, values) -> {
          out.append(text(k)).append('=');
          for (var v : values) out.append(text(v)).append(',');
          out.append(';');
        });
    out.append('/');
    for (var v : p.unsupportedData()) out.append(text(v)).append(',');
    return out.toString();
  }

  static String common(TagBase t) {
    return text(t.title())
      + ":"
      + text(t.artist())
      + ":"
      + text(t.album())
      + ":"
      + text(t.comment())
      + ":"
      + text(t.genre())
      + ":"
      + t.year()
      + ":"
      + t.track()
      + ":"
      + (t.isEmpty() ? 1 : 0)
      + ":"
      + props(t.properties());
  }

  static class IdHandler extends cz.skyba.taglib.id3.v1.StringHandler {
    int mode, parsed, rendered;

    IdHandler(int mode) {
      this.mode = mode;
    }

    public String parse(ByteVector b) {
      parsed++;
      String s =
        mode == 1
          ? new TagString(b, TagString.Type.UTF8).stripWhiteSpace().toString()
          : super.parse(b);
      return mode == 2 ? s + "!" + parsed : s;
    }

    public ByteVector render(String s) {
      rendered++;
      return new TagString(mode == 2 ? s + "~" + rendered : s)
        .data(mode == 1 ? TagString.Type.UTF8 : TagString.Type.LATIN1);
    }
  }

  static class InfoHandler extends cz.skyba.taglib.riff.info.StringHandler {
    int mode, parsed, rendered;

    InfoHandler(int mode) {
      this.mode = mode;
    }

    public String parse(ByteVector b) {
      parsed++;
      String s =
        new TagString(b, mode == 2 ? TagString.Type.LATIN1 : TagString.Type.UTF8).toString();
      return mode == 2 ? s + "!" + parsed : s;
    }

    public ByteVector render(String s) {
      rendered++;
      return new TagString(mode == 2 ? s + "~" + rendered : s)
        .data(mode == 2 ? TagString.Type.LATIN1 : TagString.Type.UTF8);
    }
  }

  static class Bare extends FileBase {
    Bare(IOStream s) {
      super(s);
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

  static class Id extends cz.skyba.taglib.id3.v1.Tag {
    Id() {
      super();
    }

    Id(FileBase f, long offset) {
      super(f, offset);
    }

    void apply(ByteVector b) {
      parse(b);
    }

    void again() {
      read();
    }
  }

  static class Info extends cz.skyba.taglib.riff.info.Tag {
    Info() {
      super();
    }

    Info(ByteVector b) {
      super(b);
    }

    void apply(ByteVector b) {
      parse(b);
    }
  }

  static void idstate(String key, cz.skyba.taglib.id3.v1.Tag t, IdHandler h) {
    var out = t.render();
    System.out.println(
      key
        + "\t"
        + common(t)
        + ":"
        + t.genreNumber()
        + ":"
        + h.parsed
        + ":"
        + h.rendered
        + "\t"
        + hex(out));
  }

  static void infostate(String key, cz.skyba.taglib.riff.info.Tag t, InfoHandler h) {
    var out = t.render();
    var fields = new StringBuilder();
    t.fieldListMap()
      .forEach((k, v) -> fields.append(hex(k)).append('=').append(text(v)).append(';'));
    System.out.println(
      key
        + "\t"
        + common(t)
        + ":"
        + h.parsed
        + ":"
        + h.rendered
        + ":"
        + fields
        + "\t"
        + hex(out));
  }

  static class Named extends ByteVectorStream {
    final String fileName;

    Named(ByteVector b, Path name) {
      super(b);
      fileName = name.toString();
    }

    public String name() {
      return fileName;
    }
  }

  static FileBase open(Path p, IOStream s, int kind) {
    return switch (kind) {
      case 0 -> s == null ? new MpegFile(p, ReadOptions.TAGS_ONLY) : new MpegFile(s, ReadOptions.TAGS_ONLY);
      case 1 -> s == null
        ? new TrueAudioFile(p, ReadOptions.TAGS_ONLY)
        : new TrueAudioFile(s, ReadOptions.TAGS_ONLY);
      case 2 -> s == null ? new WavPackFile(p, ReadOptions.TAGS_ONLY) : new WavPackFile(s, ReadOptions.TAGS_ONLY);
      case 3 -> s == null ? new ApeFile(p, ReadOptions.TAGS_ONLY) : new ApeFile(s, ReadOptions.TAGS_ONLY);
      case 4 -> s == null ? new MpcFile(p, ReadOptions.TAGS_ONLY) : new MpcFile(s, ReadOptions.TAGS_ONLY);
      case 5 -> s == null ? new FlacFile(p, ReadOptions.TAGS_ONLY) : new FlacFile(s, ReadOptions.TAGS_ONLY);
      default -> s == null
        ? new WavFile(p, ReadOptions.TAGS_ONLY)
        : new WavFile(s, ReadOptions.TAGS_ONLY);
    };
  }

  static TagBase child(FileBase f, int kind) {
    return switch (kind) {
      case 0 -> ((MpegFile) f).id3v1Tag();
      case 1 -> ((TrueAudioFile) f).id3v1Tag();
      case 2 -> ((WavPackFile) f).id3v1Tag();
      case 3 -> ((ApeFile) f).id3v1Tag();
      case 4 -> ((MpcFile) f).id3v1Tag();
      case 5 -> ((FlacFile) f).id3v1Tag();
      default -> ((WavFile) f).infoTag();
    };
  }

  static void snapshot(
    String key,
    String step,
    FileBase f,
    TagBase tag,
    TagBase c,
    int kind,
    IdHandler ih,
    InfoHandler fh) {
    System.out.println(
      key
        + "/"
        + step
        + "\t"
        + common(tag)
        + "\t"
        + common(c)
        + "\t"
        + ih.parsed
        + ":"
        + ih.rendered
        + ":"
        + fh.parsed
        + ":"
        + fh.rendered
        + ":"
        + (child(f, kind) == c ? 1 : 0)
        + ":"
        + (f.tag() == tag ? 1 : 0));
  }

  static ByteVector liveBytes(FileBase f) {
    long position = f.tell();
    long size = f.length();
    f.seek(0);
    var bytes = f.readBlock(size);
    f.seek(position);
    return bytes;
  }

  static void parents(Path root) throws Exception {
    int kind = 0;
    for (String name : Files.readAllLines(root.resolve("parent-cases.tsv"))) {
      var original = new ByteVector(Files.readAllBytes(root.resolve("parents").resolve(name)));
      for (int mode = 0; mode < 3; mode++)
        for (boolean memory : List.of(false, true))
          for (boolean wrapper : List.of(false, true)) {
            var ih = new IdHandler(mode);
            var fh = new InfoHandler(mode);
            cz.skyba.taglib.id3.v1.Tag.setStringHandler(mode == 0 ? null : ih);
            cz.skyba.taglib.riff.info.Tag.setStringHandler(mode == 0 ? null : fh);
            String flags = kind + "-" + mode + "-" + (memory ? 1 : 0) + "-" + (wrapper ? 1 : 0);
            String key =
              "parent/" + kind + "/" + mode + "/" + (memory ? 1 : 0) + "/" + (wrapper ? 1 : 0);
            var target = root.resolve("outputs/java").resolve(flags + "-" + name);
            Files.createDirectories(target.getParent());
            Files.copy(
              root.resolve("parents").resolve(name), target, StandardCopyOption.REPLACE_EXISTING);
            var stream = new Named(original, Path.of(name));
            FileRefAdapter ref =
              wrapper ? (memory ? new FileRefAdapter(stream, false) : new FileRefAdapter(target, false)) : null;
            FileBase f = wrapper ? ref.file() : open(target, memory ? stream : null, kind);
            if (f == null || !f.isValid()) throw new AssertionError("Ordinary parent rejected");
            var c = child(f, kind);
            var tag = f.tag();
            snapshot(key, "read", f, tag, c, kind, ih, fh);
            for (int round = 0; round < 2; round++) {
              if (round == 0) {
                c.setArtist(" Artist café ");
                c.setYear(0xffff_ffffL);
              } else {
                cz.skyba.taglib.riff.info.Tag.setStringHandler(null);
                cz.skyba.taglib.id3.v1.Tag.setStringHandler(null);
                c.setTitle("Second");
                c.setTrack(255);
              }
              if (!SaveOutcome.save(f)) throw new AssertionError("Ordinary parent save rejected");
              snapshot(key, "saved" + round, f, tag, c, kind, ih, fh);
              System.out.println(
                key + "/save/" + round + "/bytes\t" + hex(memory ? stream.data() : liveBytes(f)));
            }
            if (wrapper) ref.close();
            else f.close();
            System.out.println(key + "/borrowed-open\t" + (stream.isOpen() ? 1 : 0));
            if (memory) Files.write(target, stream.data().toByteArray());
            try (var reread = open(target, null, kind)) {
              System.out.println(
                key + "/reread\t" + common(reread.tag()) + "\t" + common(child(reread, kind)));
            }
            cz.skyba.taglib.id3.v1.Tag.setStringHandler(null);
            cz.skyba.taglib.riff.info.Tag.setStringHandler(null);
          }
      kind++;
    }
  }

  public static void main(String[] args) throws Exception {
    var root = Path.of(args[0]);
    for (String line : Files.readAllLines(root.resolve("cases.tsv"))) {
      char kind = line.charAt(0);
      String name = line.substring(2);
      var raw = new ByteVector(Files.readAllBytes(root.resolve("inputs").resolve(name)));
      for (int mode = 0; mode < 3; mode++) {
        String key = name + "/" + mode;
        if (kind == 'I') {
          var h = new IdHandler(mode);
          cz.skyba.taglib.id3.v1.Tag.setStringHandler(mode == 0 ? null : h);
          var stream = new ByteVectorStream(new ByteVector(7).append(raw));
          var file = new Bare(stream);
          var t = new Id(file, 7);
          idstate(key + "/read", t, h);
          System.out.println(key + "/cursor\t" + stream.tell());
          if (raw.size() >= 128) {
            t.setTrack(55);
            t.apply(raw);
            idstate(key + "/parse", t, h);
            var v10 = new ByteVector(raw);
            v10.set(126, (byte) 0);
            t.apply(v10);
            idstate(key + "/v10", t, h);
          }
          cz.skyba.taglib.id3.v1.Tag.setStringHandler(null);
        } else {
          var h = new InfoHandler(mode);
          cz.skyba.taglib.riff.info.Tag.setStringHandler(mode == 0 ? null : h);
          var t = new Info(raw);
          infostate(key + "/read", t, h);
          var merged = new Info();
          merged.setTitle("old");
          merged.setArtist("kept");
          merged.apply(raw);
          infostate(key + "/merge", merged, h);
          merged.apply(raw);
          infostate(key + "/repeat", merged, h);
          cz.skyba.taglib.riff.info.Tag.setStringHandler(null);
        }
      }
    }
    for (int n = 0; n <= 300; n++) {
      var ih = new IdHandler(0);
      var fh = new InfoHandler(0);
      var t = new Id();
      var info = new Info();
      t.setYear(n);
      t.setTrack(n);
      t.setGenreNumber(n);
      info.setYear(n);
      info.setTrack(n);
      idstate("number/id3/" + n, t, ih);
      infostate("number/info/" + n, info, fh);
    }
    contractHelpers(root);
    for (String name : List.of("collision-empty.mpc", "collision-real.ape")) {
      var target = root.resolve("outputs/java").resolve(name);
      Files.createDirectories(target.getParent());
      Files.copy(root.resolve(name), target, StandardCopyOption.REPLACE_EXISTING);
      boolean saved;
      try (var f = new ApeFile(target, ReadOptions.TAGS_ONLY)) {
        f.getOrCreateApeTag().setArtist("Filltointersect    ");
        f.apeTag().setTitle("Filltointersect    ");
        saved = SaveOutcome.save(f);
      }
      try (var f = new ApeFile(target, ReadOptions.TAGS_ONLY)) {
        System.out.println(
          "collision/"
            + name
            + "\t"
            + (saved ? 1 : 0)
            + ":"
            + (f.hasId3v1Tag() ? 1 : 0)
            + "\t"
            + hex(new ByteVector(Files.readAllBytes(target))));
      }
    }
    parents(root);
  }

  static void contractHelpers(Path root) throws Exception {
    var raw = new ByteVector(Files.readAllBytes(root.resolve("inputs/id3-short-128")));
    for (int fail = 1; fail <= 5; fail++) {
      var t = new Id();
      t.setTitle("old-title");
      t.setArtist("old-artist");
      t.setAlbum("old-album");
      t.setYear(2020);
      t.setComment("old-comment");
      t.setTrack(55);
      t.setGenreNumber(222);
      int at = fail;
      var h =
        new IdHandler(0) {
          public String parse(ByteVector b) {
            if (parsed + 1 == at) {
              parsed++;
              throw new IllegalArgumentException("callback");
            }
            return super.parse(b);
          }
        };
      cz.skyba.taglib.id3.v1.Tag.setStringHandler(h);
      boolean caught = false;
      try {
        t.apply(raw);
      } catch (IllegalArgumentException e) {
        caught = true;
      }
      cz.skyba.taglib.id3.v1.Tag.setStringHandler(null);
      System.out.println(
        "callback/id3/fail/"
          + fail
          + "\t"
          + (caught ? 1 : 0)
          + ":"
          + h.parsed
          + ":"
          + common(t)
          + ":"
          + t.genreNumber()
          + "\t"
          + hex(t.render()));
    }
    var iraw =
      new ByteVector("INFO")
        .append(field("IART", "new-artist"))
        .append(field("IPRD", "new-album"))
        .append(field("INAM", "new-title"));
    var info = new Info();
    info.setTitle("old-title");
    info.setArtist("old-artist");
    info.setAlbum("old-album");
    var h =
      new InfoHandler(0) {
        public String parse(ByteVector b) {
          if (parsed + 1 == 2) {
            parsed++;
            throw new IllegalArgumentException("callback");
          }
          return super.parse(b);
        }
      };
    cz.skyba.taglib.riff.info.Tag.setStringHandler(h);
    boolean caught = false;
    try {
      info.apply(iraw);
    } catch (IllegalArgumentException e) {
      caught = true;
    }
    cz.skyba.taglib.riff.info.Tag.setStringHandler(null);
    System.out.println(
      "callback/info/fail/2\t"
        + (caught ? 1 : 0)
        + ":"
        + h.parsed
        + ":"
        + common(info)
        + "\t"
        + hex(info.render()));
    int[] ic = {0, 0};
    var shared = new ByteVector(40, (byte) 'A');
    var next =
      new cz.skyba.taglib.id3.v1.StringHandler() {
        public String parse(ByteVector b) {
          ic[1]++;
          return "replacement";
        }

        public ByteVector render(String s) {
          ic[1]++;
          return new ByteVector("B");
        }
      };
    var first =
      new cz.skyba.taglib.id3.v1.StringHandler() {
        public String parse(ByteVector b) {
          ic[0]++;
          b.clear();
          cz.skyba.taglib.id3.v1.Tag.setStringHandler(next);
          return "first";
        }

        public ByteVector render(String s) {
          ic[0]++;
          cz.skyba.taglib.id3.v1.Tag.setStringHandler(next);
          return shared;
        }
      };
    cz.skyba.taglib.id3.v1.Tag.setStringHandler(first);
    var replaced = new Id();
    replaced.apply(raw);
    cz.skyba.taglib.id3.v1.Tag.setStringHandler(null);
    System.out.println(
      "callback/id3/replace-parse\t"
        + ic[0]
        + ":"
        + ic[1]
        + ":"
        + common(replaced)
        + "\t"
        + hex(raw));
    ic[0] = ic[1] = 0;
    var rendered = new Id();
    rendered.setTitle("unchanged");
    cz.skyba.taglib.id3.v1.Tag.setStringHandler(first);
    var idOut = rendered.render();
    cz.skyba.taglib.id3.v1.Tag.setStringHandler(null);
    System.out.println(
      "callback/id3/replace-render\t"
        + ic[0]
        + ":"
        + ic[1]
        + ":"
        + text(rendered.title())
        + ":"
        + hex(shared)
        + "\t"
        + hex(idOut));
    int[] fc = {0, 0};
    var infoShared = new ByteVector("A");
    var fnext =
      new cz.skyba.taglib.riff.info.StringHandler() {
        public String parse(ByteVector b) {
          fc[1]++;
          return "replacement";
        }

        public ByteVector render(String s) {
          fc[1]++;
          return new ByteVector("B");
        }
      };
    var ffirst =
      new cz.skyba.taglib.riff.info.StringHandler() {
        public String parse(ByteVector b) {
          fc[0]++;
          b.clear();
          cz.skyba.taglib.riff.info.Tag.setStringHandler(fnext);
          return "first";
        }

        public ByteVector render(String s) {
          fc[0]++;
          cz.skyba.taglib.riff.info.Tag.setStringHandler(fnext);
          return infoShared;
        }
      };
    var infoRaw =
      new ByteVector("INFO").append(field("IART", "artist")).append(field("INAM", "title"));
    cz.skyba.taglib.riff.info.Tag.setStringHandler(ffirst);
    var infoReplaced = new Info(infoRaw);
    cz.skyba.taglib.riff.info.Tag.setStringHandler(null);
    System.out.println(
      "callback/info/replace-parse\t"
        + fc[0]
        + ":"
        + fc[1]
        + ":"
        + common(infoReplaced)
        + "\t"
        + hex(infoRaw));
    fc[0] = fc[1] = 0;
    var infoRendered = new Info();
    infoRendered.setTitle("title");
    infoRendered.setArtist("artist");
    cz.skyba.taglib.riff.info.Tag.setStringHandler(ffirst);
    var infoOut = infoRendered.render();
    cz.skyba.taglib.riff.info.Tag.setStringHandler(null);
    System.out.println(
      "callback/info/replace-render\t"
        + fc[0]
        + ":"
        + fc[1]
        + ":"
        + text(infoRendered.title())
        + ":"
        + hex(infoShared)
        + "\t"
        + hex(infoOut));
  }

  static ByteVector field(String id, String s) {
    var b = new TagString(s).data(TagString.Type.UTF8).append((byte) 0);
    var out = new ByteVector(id).append(ByteVector.fromUnsignedInt(b.size(), false)).append(b);
    if (b.size() % 2 != 0) out.append((byte) 0);
    return out;
  }
}
