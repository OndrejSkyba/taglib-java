package cz.skyba.taglib.parity;


import cz.skyba.taglib.parity.support.TagSelections;
import cz.skyba.taglib.parity.support.SaveOutcome;

import cz.skyba.taglib.ReadOptions;

import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.parity.support.FileRefAdapter;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.mp4.*;
import cz.skyba.taglib.toolkit.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Mp4MutableCallbacksAndSavingProbe extends Mp4MutableProbe {
  static class Stream extends ByteVectorStream {
    StringBuilder trace = new StringBuilder();
    boolean ro;
    int calls, point;

    Stream(byte[] v) {
      super(v);
    }

    @Override
    public String name() {
      return "sample.m4a";
    }

    @Override
    public boolean readOnly() {
      return ro;
    }

    void event(String s) {
      trace.append(s);
      if (point > 0 && ++calls == point)
        throw new IllegalStateException("ordinary callback exception");
    }

    @Override
    public void insert(ByteVector d, long s, long r) {
      event("I" + s + "," + r + "," + d.size() + ";");
      super.insert(d, s, r);
    }

    @Override
    public void removeBlock(long s, long n) {
      event("R" + s + "," + n + ";");
      super.removeBlock(s, n);
    }

    @Override
    public void writeBlock(ByteVector d) {
      event("W" + tell() + "," + d.size() + ";");
      super.writeBlock(d);
    }

    @Override
    public void truncate(long n) {
      event("T" + n + ";");
      super.truncate(n);
    }
  }

  static void audio(String k, Mp4File f) {
    var p = f.detailedProperties();
    if (p == null) {
      row(k, "null");
      return;
    }
    row(
      k,
      p.lengthInSeconds()
        + ","
        + p.lengthInMilliseconds()
        + ","
        + p.bitrate()
        + ","
        + p.sampleRate()
        + ","
        + p.channels()
        + ","
        + p.bitsPerSample()
        + ","
        + b(p.isEncrypted())
        + ","
        + p.codec().ordinal()
        + ","
        + hs(p.codecId()));
  }

  static class TypedOverride extends Mp4File {
    cz.skyba.taglib.mp4.Tag alternate = new cz.skyba.taglib.mp4.Tag();

    TypedOverride(IOStream s) {
      super(s, ReadOptions.TAGS_ONLY);
      alternate.setTitle("virtual");
    }

    @java.lang.Override
    public cz.skyba.taglib.mp4.Tag tag() {
      return alternate;
    }
  }

  static class Helpers extends ItemFactory {
    public Map<ByteVector, String> propertyMap() {
      return namePropertyMap();
    }

    public int handler(ByteVector n) {
      return handlerTypeForName(n).ordinal();
    }

    static String data(ByteVector d, int flags, boolean free) {
      String s = "";
      for (var v : parseData2(d, flags, free))
        s += v.type.value() + ":" + v.locale + ":" + hex(v.data) + ",";
      return s;
    }

    static ParsedItem parse(int n, Atom a, ByteVector d) {
      return switch (n) {
        case 0 -> parseInt(a, d);
        case 1 -> parseUInt(a, d);
        case 2 -> parseLongLong(a, d);
        case 3 -> parseByte(a, d);
        case 4 -> parseBool(a, d);
        case 5 -> parseIntPair(a, d);
        case 6 -> parseTextOrInt(a, d);
        case 7 -> parseText(a, d);
        case 8 -> parseText(a, d, -1);
        case 9 -> parseGnre(d);
        case 10 -> parseCovr(a, d);
        case 11 -> parseStem(a, d);
        default -> parseFreeForm(a, d);
      };
    }

    static ByteVector render(int n, ByteVector name, Item i) {
      return switch (n) {
        case 0 -> renderInt(name, i);
        case 1 -> renderUInt(name, i);
        case 2 -> renderLongLong(name, i);
        case 3 -> renderByte(name, i);
        case 4 -> renderBool(name, i);
        case 5 -> renderIntPair(name, i);
        case 6 -> renderTextOrInt(name, i);
        case 7 -> renderText(name, i);
        case 8 -> renderText(name, i, 0);
        case 9 -> renderIntPairNoTrailing(name, i);
        case 10 -> renderCovr(name, i);
        case 11 -> renderStem(name, i);
        default -> renderFreeForm("----:org.TagLib:TEST", i);
      };
    }

    static ByteVector ra(ByteVector name, ByteVector d) {
      return renderAtom(name, d);
    }

    static ByteVector rd(ByteVector name, int flags, List<ByteVector> v) {
      return renderData(name, flags, v);
    }
  }

  static class ExposedAtom extends Atom {
    ExposedAtom(FileBase f, int depth, int[] count) {
      super(f, depth, count);
    }
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    byte[] input = Files.readAllBytes(root.resolve("inputs/upstream-no-tags.m4a"));
    for (int depth : new int[]{0, 64, 65})
      for (long initial : new long[]{0, 49999, 50000, 0xffffffffL}) {
        var s = new Stream(input);
        var f = new Mp4File(s, ReadOptions.TAGS_ONLY);
        f.seek(0);
        int[] count = {(int) initial};
        var a = new ExposedAtom(f, depth, count);
        row(
          "extra/atom/counter/" + depth + "/" + initial,
          atom(a) + "\t" + Integer.toUnsignedLong(count[0]) + "\t" + f.tell());
      }
    {
      var s = new Stream(input);
      var f = new Mp4File(s, ReadOptions.TAGS_ONLY);
      var a = new Atoms(f);
      var m = a.find("moov");
      var p = new ArrayList<Atom>();
      boolean found = m.path(p, "trak", "absent");
      String names = "";
      for (var x : p) names += hex(x.name()) + ",";
      row("extra/atom/partial", b(found) + "\t" + names + "\t" + a.path("moov", "absent").size());
      f.seek(0);
      var child = new Atom(f);
      m.prependChild(child);
      row("extra/atom/prepend", b(m.children().getFirst() == child) + "\t" + m.children().size());
      boolean removed = m.removeChild(child);
      row(
        "extra/atom/remove",
        b(removed) + "\t" + b(m.removeChild(child)) + "\t" + m.children().size());
    }
    for (String name :
      List.of("upstream-no-tags.m4a", "upstream-has-tags.m4a", "upstream-blank_video.m4v")) {
      byte[] raw = Files.readAllBytes(root.resolve("inputs/" + name));
      for (int style = 0; style < 3; style++)
        for (int enabled = 0; enabled < 2; enabled++)
          for (int origin : new int[]{0, 7}) {
            var s = new Stream(raw);
            s.seek(origin);
            var f = new Mp4File(s, new ReadOptions(enabled != 0, ReadStyle.values()[style], ReadOptions.DEFAULT.maxMetadataBytes()));
            String k = "extra/" + name + "/control/" + style + "/" + enabled + "/" + origin;
            row(k, b(f.isValid()) + "\t" + f.tell() + "\t" + tag(f.tag()));
            audio(k + "/audio", f);
          }
    }
    for (int mode = 0; mode < 6; mode++) {
      var s = new Stream(input);
      var f = new Mp4File(s, ReadOptions.TAGS_ONLY);
      String k = "extra/repeated/" + mode;
      for (int step = 0; step < 3; step++) {
        if (mode == 0) f.tag().setTitle(step == 0 ? "X".repeat(8192) : step == 1 ? "short" : "");
        if (mode == 1)
          f.setNeroChapters(
            step == 2
              ? List.of()
              : List.of(new Chapter(step == 0 ? "A" : "B", 0), new Chapter("Second", 1500)));
        if (mode == 2)
          f.setQtChapters(
            step == 2
              ? List.of()
              : List.of(new Chapter(step == 0 ? "A" : "B", 0), new Chapter("Second", 1500)));
        if (mode == 3) {
          f.tag().setTitle("combined");
          f.setNeroChapters(List.of(new Chapter("Nero", 0)));
          f.setQtChapters(List.of(new Chapter("Qt", 0)));
        }
        if (mode == 4) {
          if (step == 0) f.tag().setItem("stem", new Item(new Stem(new ByteVector("manifest"))));
          else f.tag().removeItem("stem");
        }
        if (mode == 5) {
          if (step == 0) f.tag().setTitle("strip");
          else f.strip(TagSelections.mp4(step == 1 ? 0 : 65535));
        }
        boolean result = SaveOutcome.save(f);
        row(
          k + "/save/" + step,
          b(result) + "\t" + f.tell() + "\t" + hex(s.data()) + "\t" + tag(f.tag()));
      }
      row(k + "/trace", s.trace.toString());
    }
    for (int mode = 0; mode < 3; mode++)
      for (int point = 0; point <= 24; point++) {
        var s = new Stream(input);
        var f = new Mp4File(s, ReadOptions.TAGS_ONLY);
        f.tag().setTitle("pending");
        if (mode == 1) f.setNeroChapters(List.of(new Chapter("chapter", 0)));
        if (mode == 2) f.setQtChapters(List.of(new Chapter("chapter", 0)));
        s.point = point;
        boolean saved = false, threw = false;
        try {
          saved = SaveOutcome.save(f);
        } catch (IllegalStateException failure) {
          threw = true;
        }
        row(
          "extra/callback/" + mode + "/" + point,
          b(threw)
            + "\t"
            + b(saved)
            + "\t"
            + f.tell()
            + "\t"
            + hex(s.data())
            + "\t"
            + tag(f.tag())
            + "\t"
            + s.trace);
      }
    {
      var s = new Stream(input);
      s.ro = true;
      var f = new Mp4File(s, ReadOptions.TAGS_ONLY);
      f.tag().setTitle("pending");
      boolean save = SaveOutcome.save(f), strip = SaveOutcome.save(f::strip);
      row("extra/readonly", b(save) + "\t" + b(strip) + "\t" + hex(s.data()) + "\t" + tag(f.tag()));
    }
    {
      var s = new Stream(input);
      s.seek(7);
      boolean support = Mp4File.isSupported(s);
      row("extra/support", b(support) + "\t" + s.tell());
      try (var r = new FileRefAdapter(s)) {
        row("extra/ref", b(r.file() instanceof Mp4File) + "\t" + s.tell());
        r.tag().setTitle("shared");
        boolean saved = r.save();
        row("extra/ref-save", b(saved) + "\t" + hex(s.data()));
      }
    }
    {
      var s = new Stream(input);
      var f = new TypedOverride(s);
      f.replaceProperties(PropertyMap.builder().put("TITLE", "stored").build());
      f.setComplexProperties(
        "PICTURE",
        List.of(
          Map.of(
            "data",
            new Variant(new ByteVector("cover")),
            "mimeType",
            new Variant("image/png"))));
      boolean saved = SaveOutcome.save(f);
      row(
        "extra/subclass",
        hs(f.tag().title())
          + "\t"
          + props(f.properties())
          + "\t"
          + f.tag().complexProperties("PICTURE").size()
          + "\t"
          + b(saved)
          + "\t"
          + hex(s.data()));
    }
    for (String key : List.of("PICTURE", "picture", "STEM", "stem", "OTHER"))
      for (String mime : List.of("image/png", "image/jpeg", "image/gif", "image/bmp", "other")) {
        var t = new cz.skyba.taglib.mp4.Tag();
        boolean result =
          t.setComplexProperties(
            key,
            List.of(
              Map.of(
                "data",
                new Variant(new ByteVector("image")),
                "mimeType",
                new Variant(mime),
                "manifest",
                new Variant(new ByteVector("manifest")))));
        row(
          "extra/complex/" + key + "/" + mime,
          b(result)
            + "\t"
            + sl(t.complexPropertyKeys())
            + "\t"
            + tag(t)
            + "\t"
            + t.complexProperties(key).size());
      }
    var h = new Helpers();
    for (var x : h.propertyMap().entrySet()) {
      var name = h.nameForPropertyKey(x.getValue());
      row(
        "extra/mapping/" + hex(x.getKey()),
        hs(x.getValue())
          + "\t"
          + hs(h.propertyKeyForName(x.getKey()))
          + "\t"
          + hex(name)
          + "\t"
          + h.handler(x.getKey()));
    }
    var s = new Stream(input);
    var f = new Mp4File(s, ReadOptions.TAGS_ONLY);
    var tree = new Atoms(f);
    var a = tree.find("moov");
    for (int flags : new int[]{0, 1, 21, 255})
      for (int size = 0; size < 20; size++) {
        var d =
          Helpers.rd(
              new ByteVector("test"),
              flags,
              List.of(new ByteVector(size, (byte) 'A')))
            .mid(8);
        String k = "extra/helper/" + flags + "/" + size;
        row(k + "/data", Helpers.data(d, -1, false));
        for (int n = 0; n < 12; n++) {
          var r = Helpers.parse(n, a, d);
          row(k + "/parse/" + n, hs(r.name()) + "\t" + item(r.item()));
        }
      }
    List<Item> values =
      List.of(
        new Item(-2),
        Item.fromUnsignedInt(0xffffffffL),
        new Item(-7L),
        Item.fromByte(255),
        new Item(true),
        new Item(2, 10),
        Item.textValues(List.of("rate")),
        Item.textValues(List.of("one", "two")),
        Item.textValues(List.of("implicit")),
        new Item(3, 4),
        Item.coverArt(List.of(new CoverArt(CoverArt.Format.GIF, new ByteVector("GIF89a")))),
        new Item(new Stem(new ByteVector("stem"))),
        new Item(List.of(new ByteVector("a"), new ByteVector("bc"))));
    for (int n = 0; n < 13; n++)
      row(
        "extra/helper/render/" + n,
        hex(Helpers.render(n, new ByteVector("test"), values.get(n))));
    for (int n = 0; n < values.size(); n++)
      for (int m = 0; m < values.size(); m++)
        row("extra/item/equality/" + n + "/" + m, b(values.get(n).equals(values.get(m))));
    for (boolean qt : new boolean[]{false, true}) {
      var chapterInput = new Stream(input);
      var cf = new Mp4File(chapterInput, ReadOptions.TAGS_ONLY);
      var n = new NeroChapterList();
      var q = new QtChapterList();
      boolean read = qt ? q.read(cf) : n.read(cf);
      row("extra/direct/" + b(qt) + "/read", b(read) + "\t" + cf.tell());
      var list = new ArrayList<Chapter>();
      for (int i = 0; i < 260; i++) list.add(new Chapter("x".repeat(300), i * 10));
      if (qt) q.setChapters(list);
      else n.setChapters(list);
      boolean wrote = cz.skyba.taglib.parity.support.SaveOutcome.save(() -> {
        if (qt) q.write(cf);
        else n.write(cf);
      });
      row(
        "extra/direct/" + b(qt) + "/write",
        b(wrote) + "\t" + cf.tell() + "\t" + hex(chapterInput.data()));
      boolean reread = qt ? q.read(cf) : n.read(cf);
      row(
        "extra/direct/" + b(qt) + "/reread",
        b(reread) + "\t" + chapters(qt ? q.chapters() : n.chapters()));
      boolean removed = cz.skyba.taglib.parity.support.SaveOutcome.save(() -> {
        if (qt) q.remove(cf);
        else n.remove(cf);
      });
      row(
        "extra/direct/" + b(qt) + "/remove",
        b(removed) + "\t" + cf.tell() + "\t" + hex(chapterInput.data()));
    }
  }
}
