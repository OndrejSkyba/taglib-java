package cz.skyba.taglib.parity;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.mp4.*;
import cz.skyba.taglib.parity.support.OpenOutcome;
import cz.skyba.taglib.parity.support.SaveOutcome;
import cz.skyba.taglib.toolkit.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

public class Mp4MutableProbe {
  static String hex(ByteVector b) {
    return HexFormat.of().formatHex(b.toByteArray());
  }

  static String hs(String s) {
    return hex(ByteVector.fromText(s, UTF_8));
  }

  static String sl(List<String> v) {
    String s = "";
    for (var x : v) s += hs(x) + ",";
    return s;
  }

  static String props(PropertyMap p) {
    String s = "";
    for (var x : p.asMap().entrySet())
      s +=
        hs(x.getKey())
          + "="
          + sl(x.getValue())
          + ";";
    return s + "/" + sl(p.unsupportedData());
  }

  static String item(Item i) {
    String s = i.type().ordinal() + ":" + i.atomDataType().value() + ":";
    return s
      + switch (i.type()) {
      case VOID -> "";
      case BOOL -> b(i.toBoolean());
      case INT -> Integer.toString(i.toInt());
      case BYTE -> Integer.toString(i.toByte());
      case UNSIGNED_INT -> Long.toString(i.toUnsignedInt());
      case LONG -> Long.toString(i.toLong());
      case INT_PAIR -> i.toIntPair().first() + "," + i.toIntPair().second();
      case STRING_LIST -> sl(i.toStringList());
      case BYTE_VECTOR_LIST -> {
        String v = "";
        for (var x : i.toByteVectorList()) v += hex(x) + ",";
        yield v;
      }
      case COVER_ART_LIST -> {
        String v = "";
        for (var x : i.toCoverArtList()) v += x.format().value() + ":" + hex(x.data()) + ",";
        yield v;
      }
      case STEM -> hex(i.toStem().byteVector());
    };
  }

  static String tag(cz.skyba.taglib.mp4.Tag t) {
    String s = "";
    for (var x : t.itemMap().entrySet()) s += hs(x.getKey()) + "=" + item(x.getValue()) + ";";
    return s;
  }

  static String chapters(List<Chapter> v) {
    String s = "";
    for (var x : v) s += hs(x.title()) + ":" + x.startTime() + ",";
    return s;
  }

  static void row(String k, String v) {
    System.out.println(k + "\t" + v);
  }

  static String b(boolean v) {
    return v ? "1" : "0";
  }

  static String atom(Atom a) {
    String s = hex(a.name()) + ":" + a.offset() + ":" + a.length() + "[";
    for (var x : a.children()) s += atom(x) + ",";
    return s + "]";
  }

  public static class Custom extends ItemFactory {
    @Override
    protected Map<ByteVector, ItemHandlerType> nameHandlerMap() {
      var map = super.nameHandlerMap();
      map.put(new ByteVector("tsti"), ItemHandlerType.Int);
      map.put(new ByteVector("tstt"), ItemHandlerType.Text);
      return map;
    }

    @Override
    protected Map<ByteVector, String> namePropertyMap() {
      var map = super.namePropertyMap();
      map.put(new ByteVector("tsti"), "TESTINTEGER");
      return map;
    }
  }

  static PropertyMap changes() {
    return PropertyMap.builder()
      .put("TITLE", List.of("New", "Second"))
      .put("MOOD", "Calm")
      .put("TRACKNUMBER", "5/12")
      .put("BPM", "120")
      .put("COMPILATION", "1")
      .put("UNKNOWN", "value")
      .build();
  }

  static void edit(Mp4File f, int mode) {
    var t = f.tag();
    switch (mode) {
      case 0 -> {
        t.setTitle("Title");
        t.setArtist("Artist");
        t.setAlbum("Album");
        t.setComment("Comment");
        t.setGenre("Ska");
        t.setYear(2026);
        t.setTrack(7);
      }
      case 1 -> {
        t.setItem(
          "----:org.kde.TagLib:Foo",
          Item.textValues(List.of("Bar", "Baz")));
        t.setItem(
          "----:com.apple.iTunes:RAW",
          new Item(List.of(new ByteVector("a"), new ByteVector("bc"))));
        t.setItem("cpil", new Item(true));
        t.setItem("tmpo", new Item(123));
        t.setItem("trkn", new Item(2, 10));
        t.setItem("disk", new Item(1, 3));
        t.setItem("plID", new Item(-7L));
        t.setItem("cnID", new Item(9876543210L));
        t.setItem("rtng", Item.fromByte(255));
        t.setItem("rate", new Item(80));
        t.setItem(
          "covr",
          Item.coverArt(List.of(
            new CoverArt(CoverArt.Format.PNG, new ByteVector("foo")),
            new CoverArt(CoverArt.Format.JPEG, new ByteVector("bar")))));
      }
      case 3 -> t.setItem("stem", new Item(new Stem(new ByteVector("{\"version\":1}"))));
      case 4 -> f.setNeroChapters(List.of(new Chapter("First", 0), new Chapter("日本語", 1234)));
      case 5 -> f.setQtChapters(List.of(new Chapter("First", 0), new Chapter("日本語", 1234)));
      case 6 -> f.setQtChapters(List.of(new Chapter("First", 999), new Chapter("Second", 1999)));
      case 7 -> {
        f.setNeroChapters(List.of());
        f.setQtChapters(List.of());
      }
    }
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    List<Path> files;
    try (var s = Files.list(root.resolve("inputs"))) {
      files = s.sorted().toList();
    }
    Custom custom = new Custom();
    for (var p : files) {
      String k = p.getFileName().toString();
      byte[] input = Files.readAllBytes(p);
      var stream = new ByteVectorStream(input);
      stream.seek(7);
      var f = OpenOutcome.file(() -> new Mp4File(stream));
      row(k + "/read", b(f != null) + "\t" + stream.tell());
      if (f == null) continue;
      row(k + "/tag", tag(f.tag()));
      row(k + "/properties", props(f.properties()));
      row(
        k + "/common",
        hs(f.tag().title())
          + "\t"
          + hs(f.tag().artist())
          + "\t"
          + f.tag().year()
          + "\t"
          + f.tag().track());
      row(k + "/nero", chapters(f.neroChapters()) + "\t" + f.tell());
      row(k + "/qt", chapters(f.qtChapters()) + "\t" + f.tell());
      var tree = new Atoms(f);
      String a = "";
      for (var x : tree.atoms()) a += atom(x) + ",";
      row(k + "/atoms", a + "\t" + b(tree.checkRootLevelAtoms()) + "\t" + f.tell());
      for (int mode = 0; mode < 8; mode++) {
        var out = new ByteVectorStream(input);
        var owner = new Mp4File(out, ReadOptions.TAGS_ONLY);
        if (!owner.isValid()) continue;
        String key = k + "/edit/" + mode;
        if (mode == 2) row(key + "/rejected", props(owner.replaceProperties(changes()).rejected()));
        else edit(owner, mode);
        boolean saved = SaveOutcome.save(owner);
        row(key + "/save", b(saved) + "\t" + owner.tell() + "\t" + hex(out.data()));
        row(key + "/live", tag(owner.tag()) + "\t" + b(owner.hasMp4Tag()));
        if (saved) {
          var reopened = OpenOutcome.file(() -> new Mp4File(out, ReadOptions.TAGS_ONLY));
          row(
            key + "/reopen",
            b(reopened != null) + "\t" + (reopened != null ? tag(reopened.tag()) : ""));
          if (reopened != null)
            row(
              key + "/chapters",
              chapters(reopened.neroChapters()) + "/" + chapters(reopened.qtChapters()));
        }
        if (k.equals("upstream-no-tags.m4a") || k.startsWith("layout-"))
          Files.write(
            root.resolve("outputs").resolve(k + "-" + mode + ".java"), out.data().toByteArray());
      }
    }
    var mem = new ByteVectorStream(Files.readAllBytes(root.resolve("inputs/upstream-no-tags.m4a")));
    var c = new Mp4File(mem, ReadOptions.DEFAULT, custom);
    c.tag().setItem("tsti", new Item(123));
    c.tag().setItem("tstt", Item.textValues(List.of("Test text")));
    SaveOutcome.save(c);
    row("factory/custom-save", hex(mem.data()));
    var cr = new Mp4File(mem, ReadOptions.DEFAULT, custom);
    row("factory/custom-read", tag(cr.tag()) + "\t" + props(cr.properties()));
    var cp = cr.properties().toBuilder().put("TESTINTEGER", "456").build();
    cr.replaceProperties(cp);
    row("factory/custom-resave", b(SaveOutcome.save(cr)) + "\t" + hex(mem.data()));
    var factory = ItemFactory.instance();
    for (String key :
      List.of(
        "TITLE",
        "MOOD",
        "BPM",
        "TRACKNUMBER",
        "DISCNUMBER",
        "COMPILATION",
        "RATING",
        "ENCODING",
        "UNKNOWN"))
      for (String val :
        List.of("", "0", "1", "-1", "2/10", "4294967295", "9223372036854775807", "Text")) {
        var result = factory.itemFromProperty(key, List.of(val));
        var back = factory.itemToProperty(result.name(), result.item());
        row(
          "factory/" + key + "/" + val,
          hex(result.name())
            + "\t"
            + item(result.item())
            + "\t"
            + hs(back.key())
            + "\t"
            + sl(back.values()));
      }
    var t = new cz.skyba.taglib.mp4.Tag();
    row("tag/missing-before", Integer.toString(t.itemMap().size()));
    var value = t.item("zzzz");
    row("tag/missing-item", item(value) + "\t" + t.itemMap().size());
  }
}
