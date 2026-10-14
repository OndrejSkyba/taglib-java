package cz.skyba.taglib.parity;


import cz.skyba.taglib.ogg.OggFile;
import cz.skyba.taglib.ogg.flac.OggFlacFile;
import cz.skyba.taglib.ogg.opus.OpusFile;
import cz.skyba.taglib.ogg.speex.SpeexFile;
import cz.skyba.taglib.ogg.vorbis.VorbisFile;
import cz.skyba.taglib.parity.support.SaveOutcome;

import cz.skyba.taglib.ReadOptions;

import cz.skyba.taglib.parity.support.FileRefAdapter;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.ogg.XiphComment;
import cz.skyba.taglib.toolkit.ByteVector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Ordinary default arguments, virtual routing, callback failures and defined 24-bit fallback.
 */
public class OggCodecSubclassesAndCallbacksProbe extends OggCodecTagOwnerProbe {
  static OggFile overload(Path path, Stream s, int codec, int variant) {
    return switch (codec) {
      case 0 -> switch (variant) {
        case 0 -> new VorbisFile(path);
        case 1 -> new VorbisFile(path, ReadOptions.TAGS_ONLY);
        case 2 -> new VorbisFile(s);
        default -> new VorbisFile(s, ReadOptions.TAGS_ONLY);
      };
      case 1 -> switch (variant) {
        case 0 -> new OpusFile(path);
        case 1 -> new OpusFile(path, ReadOptions.TAGS_ONLY);
        case 2 -> new OpusFile(s);
        default -> new OpusFile(s, ReadOptions.TAGS_ONLY);
      };
      case 2 -> switch (variant) {
        case 0 -> new SpeexFile(path);
        case 1 -> new SpeexFile(path, ReadOptions.TAGS_ONLY);
        case 2 -> new SpeexFile(s);
        default -> new SpeexFile(s, ReadOptions.TAGS_ONLY);
      };
      default -> switch (variant) {
        case 0 -> new OggFlacFile(path);
        case 1 -> new OggFlacFile(path, ReadOptions.TAGS_ONLY);
        case 2 -> new OggFlacFile(s);
        default -> new OggFlacFile(s, ReadOptions.TAGS_ONLY);
      };
    };
  }

  static OggFile derived(Stream s, int codec, XiphComment alternate) {
    return switch (codec) {
      case 0 -> new VorbisFile(s, ReadOptions.TAGS_ONLY) {
        @Override
        public XiphComment tag() {
          return alternate;
        }
      };
      case 1 -> new OpusFile(s, ReadOptions.TAGS_ONLY) {
        @Override
        public XiphComment tag() {
          return alternate;
        }
      };
      case 2 -> new SpeexFile(s, ReadOptions.TAGS_ONLY) {
        @Override
        public XiphComment tag() {
          return alternate;
        }
      };
      default -> new OggFlacFile(s, ReadOptions.TAGS_ONLY) {
        @Override
        public XiphComment tag() {
          return alternate;
        }
      };
    };
  }

  static class BarePage extends cz.skyba.taglib.ogg.Page {
    BarePage(List<ByteVector> p, long s, int n) {
      super(p, s, n);
    }

    BarePage(List<ByteVector> p, long s, int n, boolean c) {
      super(p, s, n, c);
    }

    BarePage(List<ByteVector> p, long s, int n, boolean c, boolean d) {
      super(p, s, n, c, d);
    }

    BarePage(List<ByteVector> p, long s, int n, boolean c, boolean d, boolean e) {
      super(p, s, n, c, d, e);
    }
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    String[] names = {
      "upstream-empty.ogg",
      "upstream-correctness_gain_silent_output.opus",
      "upstream-empty.spx",
      "upstream-empty_flac.oga"
    };
    for (int codec = 0; codec < 4; codec++) {
      String name = names[codec];
      Path path = root.resolve("inputs/" + name);
      byte[] bytes = Files.readAllBytes(path);
      String key = "extra/" + codec;
      for (int variant = 0; variant < 4; variant++) {
        var stream = new Stream(bytes, name);
        stream.seek(7);
        try (var f = overload(path, stream, codec, variant)) {
          state(key + "/overload/" + variant, f, codec);
        }
        row(key + "/overload-lifetime/" + variant, stream.isOpen());
      }
      var alt = new XiphComment();
      alt.setTitle("virtual");
      var picture = new cz.skyba.taglib.flac.Picture();
      picture.setData(new byte[]{1});
      alt.addPicture(picture);
      var s = new Stream(bytes, name);
      try (var f = derived(s, codec, alt)) {
        f.replaceProperties(PropertyMap.builder().put("TITLE", "stored").build());
        row(
          key + "/subclass-before",
          hex(f.tag().title()),
          props(f.properties()),
          f.complexProperties("PICTURE").size());
        boolean saved = SaveOutcome.save(f);
        row(key + "/subclass", saved, hex(f.tag().title()), props(f.properties()), hex(s.data()));
      }
      for (int phase = 1; phase <= 12; phase++) {
        var stream = new Stream(bytes, name);
        try (var f = open(stream, codec, false, ReadStyle.AVERAGE)) {
          var original = f.tag();
          original.setTitle("x".repeat(8192));
          stream.fault = phase;
          boolean threw = false, saved = false;
          try {
            saved = SaveOutcome.save(f);
          } catch (IllegalStateException failure) {
            threw = true;
          }
          row(
            key + "/callback/" + phase,
            threw,
            saved,
            f.tell(),
            hex(stream.data()),
            stream.trace,
            f.tag() == original);
          stream.fault = 0;
          boolean retry = SaveOutcome.save(f);
          row(key + "/callback-retry/" + phase, retry, f.tell(), hex(stream.data()));
        }
      }
      var stream = new Stream(bytes, name);
      try (var ref = new FileRefAdapter(stream, false)) {
        var copied = ref;
        row(key + "/ref-shared", copied.file() == ref.file(), copied.tag() == ref.tag());
        ref.tag().setTitle("shared");
        boolean saved = copied.save();
        row(key + "/ref-save", saved, hex(stream.data()), hex(ref.tag().title()));
        row(key + "/ref-lifetime", stream.isOpen(), !ref.isNull());
      }
    }
    var values = List.of(new ByteVector("a"), new ByteVector(256));
    for (int n = 0; n < 4; n++) {
      var page =
        n == 0
          ? new BarePage(values, 0xffffffffL, 7)
          : n == 1
          ? new BarePage(values, 0xffffffffL, 7, true)
          : n == 2
          ? new BarePage(values, 0xffffffffL, 7, true, false)
          : new BarePage(values, 0xffffffffL, 7, true, false, true);
      row(
        "extra/page-default/" + n,
        hex(page.render()),
        page.header().firstPacketContinued(),
        page.header().lastPacketCompleted(),
        page.header().lastPageOfStream());
    }
    for (int n = 0; n < 3; n++) {
      var pages =
        n == 0
          ? cz.skyba.taglib.ogg.Page.paginate(
          values, cz.skyba.taglib.ogg.Page.PaginationStrategy.SinglePagePerGroup, 0xffffffffL, 7)
          : n == 1
          ? cz.skyba.taglib.ogg.Page.paginate(
          values,
          cz.skyba.taglib.ogg.Page.PaginationStrategy.SinglePagePerGroup,
          0xffffffffL,
          7,
          true)
          : cz.skyba.taglib.ogg.Page.paginate(
          values,
          cz.skyba.taglib.ogg.Page.PaginationStrategy.SinglePagePerGroup,
          0xffffffffL,
          7,
          true,
          false);
      row("extra/paginate-default/" + n, hex(pages.getFirst().render()));
    }
    var ownerStream =
      new Stream(Files.readAllBytes(root.resolve("inputs/upstream-empty.ogg")), "page.ogg");
    try (var f = new VorbisFile(ownerStream, ReadOptions.TAGS_ONLY)) {
      var p = new cz.skyba.taglib.ogg.Page(f, 0);
      var data = p.render();
      row("extra/page-owner", hex(data), f.tell(), p.fileOffset(), p.packetCount(), p.size());
      var h = new cz.skyba.taglib.ogg.PageHeader(f, 0);
      row("extra/header-owner", h.isValid(), hex(h.render()), f.tell());
      row(
        "extra/header-default",
        new cz.skyba.taglib.ogg.PageHeader(f).isValid(),
        new cz.skyba.taglib.ogg.PageHeader((OggFile) null, 0).isValid(),
        f.tell());
    }
    byte[] bytes = Files.readAllBytes(root.resolve("inputs/upstream-empty_flac.oga"));
    for (int mode = 0; mode < 2; mode++) {
      var stream = new Stream(bytes, "large.oga");
      try (var f = new OggFlacFile(stream, ReadOptions.TAGS_ONLY)) {
        var picture = new cz.skyba.taglib.flac.Picture();
        picture.setData(new byte[mode == 0 ? 3 * (1 << 22) : 1]);
        f.tag().addPicture(picture);
        f.tag().setTitle(mode == 0 ? "retained" : "x".repeat(1 << 24));
        boolean saved = SaveOutcome.save(f);
        row(
          "extra/large/" + mode,
          saved,
          f.tag().pictureList().size(),
          f.tag().title().length(),
          hex(stream.data()));
      }
    }
  }
}
