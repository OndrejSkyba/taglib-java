package cz.skyba.taglib.parity;


import cz.skyba.taglib.flac.FlacFile;
import cz.skyba.taglib.id3.v2.Tag.Version;
import cz.skyba.taglib.parity.support.SaveOutcome;

import cz.skyba.taglib.parity.support.FileRefAdapter;

import cz.skyba.taglib.*;
import cz.skyba.taglib.riff.wav.WavFile;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.IOStream;
import cz.skyba.taglib.toolkit.Variant;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Constructor defaults, callback phases, subclass dispatch and mutable Xiph operations.
 */
public class FlacWaveConstructorsAndCallbacksProbe extends FlacWaveTagOwnerProbe {
  static class OverrideFlac extends FlacFile {
    TagBase alternate = new cz.skyba.taglib.id3.v1.Tag();

    OverrideFlac(IOStream s) {
      super(s, ReadOptions.TAGS_ONLY);
      alternate.setTitle("virtual");
    }

    @Override
    public TagBase tag() {
      return alternate;
    }

    TagBase stored() {
      return super.tag();
    }
  }

  static class OverrideWav extends WavFile {
    TagBase alternate = new cz.skyba.taglib.id3.v1.Tag();

    OverrideWav(IOStream s) {
      super(s, ReadOptions.TAGS_ONLY);
      alternate.setTitle("virtual");
    }

    @Override
    public TagBase tag() {
      return alternate;
    }

    TagBase stored() {
      return super.tag();
    }
  }

  static class RenderPicture extends cz.skyba.taglib.flac.Picture {
    int calls;

    @Override
    public byte[] render() {
      calls++;
      return super.render();
    }
  }

  static class Exposed extends cz.skyba.taglib.ogg.XiphComment {
    void read(ByteVector data) {
      parse(data);
    }
  }

  static void comment(String key, cz.skyba.taglib.ogg.XiphComment c) {
    tag(key + "/tag", c);
    var fields = new StringBuilder();
    for (var e : c.fieldListMap().entrySet()) {
      fields.append(hex(e.getKey())).append('=');
      for (var v : e.getValue()) fields.append(hex(v)).append(',');
      fields.append(';');
    }
    row(
      key + "/state",
      c.fieldCount(),
      hex(c.vendorID()),
      fields,
      hex(c.render()),
      hex(c.render(false)),
      c.pictureList().size());
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    for (boolean flac : new boolean[]{false, true}) {
      Path path = root.resolve("inputs/" + (flac ? "bare.flac" : "bare.wav"));
      byte[] bytes = Files.readAllBytes(path);
      String key = flac ? "extra/flac" : "extra/wav";
      var factory = new Factory();
      FileBase[] defaults =
        flac
          ? new FileBase[]{
          new FlacFile(path),
          new FlacFile(path, ReadOptions.TAGS_ONLY),
          new FlacFile(path, new ReadOptions(false, ReadStyle.FAST, ReadOptions.DEFAULT.maxMetadataBytes())),
          new FlacFile(path, new ReadOptions(false, ReadStyle.ACCURATE, ReadOptions.DEFAULT.maxMetadataBytes()), factory),
          new FlacFile(path, ReadOptions.DEFAULT, factory),
          new FlacFile(path, ReadOptions.TAGS_ONLY, factory),
          new FlacFile(path, new ReadOptions(false, ReadStyle.FAST, ReadOptions.DEFAULT.maxMetadataBytes()), factory),
          new FlacFile(new ByteVectorStream(bytes)),
          new FlacFile(new ByteVectorStream(bytes), ReadOptions.TAGS_ONLY),
          new FlacFile(new ByteVectorStream(bytes), new ReadOptions(false, ReadStyle.FAST, ReadOptions.DEFAULT.maxMetadataBytes())),
          new FlacFile(new ByteVectorStream(bytes), ReadOptions.DEFAULT, factory),
          new FlacFile(new ByteVectorStream(bytes), ReadOptions.TAGS_ONLY, factory),
          new FlacFile(new ByteVectorStream(bytes), new ReadOptions(false, ReadStyle.FAST, ReadOptions.DEFAULT.maxMetadataBytes()), factory)
        }
          : new FileBase[]{
          new WavFile(path),
          new WavFile(path, ReadOptions.TAGS_ONLY),
          new WavFile(path, new ReadOptions(false, ReadStyle.FAST, ReadOptions.DEFAULT.maxMetadataBytes())),
          new WavFile(path, new ReadOptions(false, ReadStyle.ACCURATE, ReadOptions.DEFAULT.maxMetadataBytes()), factory),
          new WavFile(new ByteVectorStream(bytes)),
          new WavFile(new ByteVectorStream(bytes), ReadOptions.TAGS_ONLY),
          new WavFile(new ByteVectorStream(bytes), new ReadOptions(false, ReadStyle.FAST, ReadOptions.DEFAULT.maxMetadataBytes()))
        };
      for (int i = 0; i < defaults.length; i++)
        try (var f = defaults[i]) {
          state(key + "/default/" + i, f);
        }
      for (int overload = 0; overload < 5; overload++) {
        var stream = new Stream(bytes, "sample" + (flac ? ".flac" : ".wav"));
        try (var f = open(stream, flac, false, ReadStyle.AVERAGE, null)) {
          f.tag().setTitle("default");
          boolean saved;
          if (flac) {
            var a = (FlacFile) f;
            if (overload == 0) a.removeTags();
            if (overload == 1) a.removeTags(java.util.Set.of());
            saved = SaveOutcome.save(a);
          } else {
            var a = (WavFile) f;
            if (overload == 0) a.strip();
            saved =
              overload == 0
                ? SaveOutcome.save(a)
                : overload == 1
                ? SaveOutcome.save(a, 65535)
                : overload == 2
                ? SaveOutcome.save(a, 65535, FileBase.StripTags.STRIP_NONE)
                : SaveOutcome.save(a,
                65535,
                FileBase.StripTags.STRIP_NONE,
                overload == 3
                  ? Version.V3
                  : Version.V4);
          }
          row(key + "/overload/" + overload, saved, hex(stream.data()));
        }
      }
      for (int point = 1; point <= 18; point++) {
        var stream = new Stream(bytes, "sample" + (flac ? ".flac" : ".wav"));
        try (var f = open(stream, flac, false, ReadStyle.AVERAGE, null)) {
          edit(f, 3, 0);
          edit(f, 5, 0);
          stream.trace.setLength(0);
          stream.calls = 0;
          stream.fault = point;
          boolean saved = false, threw = false;
          try {
            saved = SaveOutcome.save(f);
          } catch (IllegalStateException failure) {
            threw = true;
          }
          row(key + "/callback/" + point, threw, saved, f.tell(), hex(stream.data()), stream.trace);
          state(key + "/callback/" + point, f);
        }
      }
      var stream = new Stream(bytes, "sample" + (flac ? ".flac" : ".wav"));
      try (var f = flac ? new OverrideFlac(stream) : new OverrideWav(stream)) {
        f.replaceProperties(PropertyMap.builder().put("TITLE", "stored").build());
        row(
          key + "/subclass",
          hex(f.tag().title()),
          props(f.properties()),
          SaveOutcome.save(f),
          hex(stream.data()));
      }
      var stream2 = new Stream(bytes, "sample" + (flac ? ".flac" : ".wav"));
      try (var ref = new FileRefAdapter(stream2)) {
        ref.tag().setTitle("FileRef shared");
        row(key + "/ref-save", ref.save(), hex(stream2.data()));
      }
    }
    try (var f =
           new FlacFile(new ByteVectorStream(Files.readAllBytes(root.resolve("inputs/bare.flac"))), ReadOptions.TAGS_ONLY)) {
      var p = new RenderPicture();
      p.setData(new byte[]{1, 2});
      f.addPicture(p);
      var container = f.pictureList();
      row("extra/picture/identity", container.getFirst() == p);
      SaveOutcome.save(f);
      row("extra/picture/render", p.calls, f.pictureList().getFirst() == p);
      f.removePicture(p);
      row("extra/picture/remove", container.getFirst() == p, f.pictureList().isEmpty());
    }
    var c = new Exposed();
    comment("xiph/empty", c);
    int step = 0;
    for (String k :
      List.of(
        "title",
        "Title",
        "COMMENT",
        "DESCRIPTION",
        "YEAR",
        "DATE",
        "TRACKNUM",
        "TRACKNUMBER",
        "",
        "A=B",
        "A~B",
        "A\u007fB",
        "A\u3456B",
        " ",
        "}")) {
      c.addField(k, "value", false);
      comment("xiph/add/" + (step++), c);
    }
    c.addField("TITLE", "first", true);
    c.addField("title", "first", false);
    c.addField("title", "second", false);
    c.removeFields("title", "first");
    c.removeFields("absent", "x");
    comment("xiph/remove", c);
    c.setComment("sticky");
    c.setYear(0xffffffffL);
    c.setTrack(0x80000000L);
    comment("xiph/numeric", c);
    c.setYear(0);
    c.setTrack(0);
    comment("xiph/zero", c);
    row(
      "xiph/rejected",
      props(
        c.replaceProperties(
          PropertyMap.builder()
            .put("TITLE", List.of("a", "", "b"))
            .put("A=B", "bad")
            .put("EMPTY", List.of())
            .build()).rejected()));
    comment("xiph/properties", c);
    row("xiph/lookup", c.fieldListMap().getOrDefault("GHOST", List.of()).size(), c.contains("ghost"));
    comment("xiph/bucket", c);
    var picture = new cz.skyba.taglib.flac.Picture();
    picture.setDescription("embedded");
    picture.setData(new byte[]{0, 1, (byte) 255});
    c.addPicture(picture);
    comment("xiph/picture", c);
    var rendered = c.render(false);
    c.read(rendered);
    comment("xiph/parse-twice", c);
    c.removePicture(picture);
    comment("xiph/remove-picture", c);
    c.removeAllFields();
    c.removeAllPictures();
    comment("xiph/clear", c);
    for (ByteVector data :
      List.of(
        new ByteVector(),
        new ByteVector(7),
        ByteVector.fromUnsignedInt(100, false).append(new ByteVector(4)),
        ByteVector.fromUnsignedInt(3, false)
          .append(new ByteVector("new"))
          .append(ByteVector.fromUnsignedInt(50001, false)),
        ByteVector.fromUnsignedInt(0, false)
          .append(ByteVector.fromUnsignedInt(1, false))
          .append(ByteVector.fromUnsignedInt(7, false))
          .append(new ByteVector("title=x")))) {
      c.read(data);
      comment("xiph/parse/" + (step++), c);
    }
    var fields = ByteVector.fromUnsignedInt(0, false).append(ByteVector.fromUnsignedInt(3, false));
    for (String v :
      List.of(
        "title=lower",
        "COVERART=AP8=",
        "METADATA_BLOCK_PICTURE=" + new ByteVector(picture.render()).toBase64().toString())) {
      var field = new ByteVector(v);
      fields.append(ByteVector.fromUnsignedInt(field.size(), false)).append(field);
    }
    c.read(fields);
    comment("xiph/legacy", c);
    for (String k : List.of("PICTURE", "picture", "other")) {
      row(
        "xiph/complex/" + k,
        c.setComplexProperties(
          k,
          List.of(
            Map.of(
              "data",
              new Variant(new ByteVector("image")),
              "width",
              new Variant("wrong"),
              "pictureType",
              new Variant("Front Cover")))),
        c.complexProperties(k).size());
      comment("xiph/complex-state/" + k, c);
    }
    // Defined invalid UTF8 handling in native FLAC::Picture::parse.
    var raw =
      ByteVector.fromUnsignedInt(3, true)
        .append(ByteVector.fromUnsignedInt(1, true))
        .append(new ByteVector(new byte[]{(byte) 255}))
        .append(ByteVector.fromUnsignedInt(1, true))
        .append(new ByteVector(new byte[]{(byte) 255}))
        .append(new ByteVector(20));
    var p = new cz.skyba.taglib.flac.Picture();
    row(
      "picture/invalid-utf8",
      p.parse(raw.toByteArray()),
      hex(p.mimeType()),
      hex(p.description()),
      hex(new ByteVector(p.render())));
    var oversized = new Stream(Files.readAllBytes(root.resolve("inputs/bare.flac")), "large.flac");
    try (var f = new FlacFile(oversized, ReadOptions.TAGS_ONLY)) {
      f.addPicture(
        new cz.skyba.taglib.flac.Picture() {
          @Override
          public byte[] render() {
            return new byte[0x1000000];
          }
        });
      row("extra/flac/large-block", SaveOutcome.save(f), f.pictureList().size(), hex(oversized.data()));
    }
  }
}
