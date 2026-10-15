package cz.skyba.taglib.parity.ape.probes;

import cz.skyba.taglib.ape.Footer;
import cz.skyba.taglib.ape.Item;
import cz.skyba.taglib.ape.Tag;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.Variant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

class ApeTagValuesProbe {
  static void core(String name, ByteVector body, long count) throws Exception {
    var footer = new Footer();
    footer.setItemCount(count);
    footer.setTagSize(body.size() + 32);
    footer.setHeaderPresent(true);
    var stream =
      new cz.skyba.taglib.toolkit.ByteVectorStream(
        footer.renderHeader().append(body).append(footer.renderFooter()));
    try (var file = new cz.skyba.taglib.internal.BinaryFile(stream)) {
      var t = cz.skyba.taglib.internal.ape.ApeTag.read(file, cz.skyba.taglib.ReadOptions.DEFAULT);
      var out =
        new StringBuilder(
          name
            + "|"
            + properties(t.properties())
            + "|"
            + hex(t.basic().title())
            + "|"
            + t.basic().year()
            + "|"
            + t.basic().track()
            + "|");
      t.pictures().forEach(p -> out.append(hex(new ByteVector(p.data()))).append(';'));
      System.out.println(
        out.append('|')
          .append(
            hex(new ByteVector(cz.skyba.taglib.internal.ape.ApeTag.render(t.properties(), t)))));
    }
  }

  static String hex(ByteVector v) {
    return v.toHex().toString();
  }

  static String hex(String s) {
    return hex(ByteVector.fromText(s, UTF_8));
  }

  static String list(List<String> v) {
    var out = new StringBuilder(v.size() + ":");
    v.forEach(s -> out.append(hex(s)).append(','));
    return out.toString();
  }

  static void item(String name, Item v) {
    var before = v.binaryData();
    var bytes = v.render();
    System.out.println(
      name
        + "|"
        + hex(v.key())
        + "|"
        + v.type().ordinal()
        + "|"
        + (v.isReadOnly() ? 1 : 0)
        + "|"
        + v.size()
        + "|"
        + (v.isEmpty() ? 1 : 0)
        + "|"
        + hex(v.toString())
        + "|"
        + list(v.values())
        + "|"
        + hex(before)
        + "|"
        + hex(bytes)
        + "|"
        + hex(v.binaryData()));
  }

  static String properties(cz.skyba.taglib.PropertyMap p) {
    var out = new StringBuilder();
    p.asMap().forEach((k, v) -> out.append(hex(k)).append('=').append(list(v)).append(';'));
    return out + "/" + list(p.unsupportedData());
  }

  static void tag(String name, Tag v) {
    var out =
      new StringBuilder(
        name
          + "|"
          + (v.isEmpty() ? 1 : 0)
          + "|"
          + hex(v.title())
          + "|"
          + hex(v.artist())
          + "|"
          + v.year()
          + "|"
          + v.track()
          + "|"
          + properties(v.properties())
          + "|");
    v.itemListMap()
      .forEach(
        (k, i) ->
          out.append(hex(k))
            .append(':')
            .append(hex(i.key()))
            .append(':')
            .append(i.type().ordinal())
            .append(':')
            .append(i.isReadOnly() ? 1 : 0)
            .append(';'));
    out.append('|').append(list(v.complexPropertyKeys())).append('|');
    for (var p : v.complexProperties("picture"))
      out.append(hex(p.get("pictureType").toText()))
        .append(':')
        .append(hex(p.getOrDefault("description", new Variant()).toText()))
        .append(':')
        .append(hex(p.get("data").toByteVector()))
        .append(';');
    System.out.println(out.append('|').append(hex(v.render())));
  }

  static class ParsedTag extends Tag {
    void load(ByteVector b, long n) {
      footer().setItemCount(n);
      parse(b);
    }
  }

  static ByteVector payload(String key, ByteVector value, long flags) {
    return ByteVector.fromUnsignedInt(value.size(), false)
      .append(ByteVector.fromUnsignedInt(flags, false))
      .append(new ByteVector(key))
      .append(new ByteVector((byte) 0))
      .append(value);
  }

  public static void main(String[] args) throws Exception {
    var live = new Tag();
    var view = live.itemListMap();
    live.addValue("TITLE", "value");
    String row = "map-live|" + view.size() + "|" + hex(view.get("TITLE").toString());
    live.removeItem("TITLE");
    System.out.println(row + "|" + view.size());
    for (var key : List.of("\ud800\ud800", "\ud800", "éé", "ID3", "A=B"))
      System.out.println("check-key|" + (Tag.checkKey(key) ? 1 : 0));
    List<List<String>> values =
      List.of(
        List.of(),
        List.of(""),
        List.of("a"),
        List.of("", ""),
        List.of("", "a", ""),
        List.of("a", "b"),
        List.of("é", "音"));
    int n = 0;
    for (var v : values)
      for (int t = 0; t < 4; t++)
        for (int ro = 0; ro < 2; ro++) {
          var i = new Item("MiXeD", v);
          i.setType(Item.ItemType.values()[t]);
          i.setReadOnly(ro != 0);
          item("item-" + n++, i);
        }
    var i = new Item("Title", new ByteVector("abc"), true);
    item("binary", i);
    i.setType(Item.ItemType.Locator);
    item("locator", i);
    i.setType(Item.ItemType.Text);
    item("type-text", i);
    i.appendValue("new");
    item("append", i);
    i.parse(payload("Other", new ByteVector("raw"), 2));
    item("reuse-binary", i);
    i.parse(payload("Name", new ByteVector(new byte[]{'x', 0, 0}), 1));
    item("reuse-text", i);
    i.parse(new ByteVector("short"));
    item("short-parse", i);
    var latin = new Item("Text", new ByteVector(new byte[]{(byte) 0xc3, (byte) 0xa9}), false);
    item("latin-constructor", latin);
    n = 0;
    for (long flags : new long[]{0, 0x80000000L, 0x40000000L, 0x20000000L, 0xffffffffL})
      for (long size : new long[]{0, 32, 0xfffffff0L, 0xffffffffL}) {
        var b =
          new ByteVector("NOTMAGIC")
            .append(ByteVector.fromUnsignedInt(1000, false))
            .append(ByteVector.fromUnsignedInt(size, false))
            .append(ByteVector.fromUnsignedInt(17, false))
            .append(ByteVector.fromUnsignedInt(flags, false))
            .append(new ByteVector(8));
        var f = new Footer(b);
        f.setData(new ByteVector("short"));
        System.out.println(
          "footer-"
            + n++
            + "|"
            + f.version()
            + "|"
            + (f.headerPresent() ? 1 : 0)
            + "|"
            + (f.footerPresent() ? 1 : 0)
            + "|"
            + (f.isHeader() ? 1 : 0)
            + "|"
            + f.itemCount()
            + "|"
            + f.tagSize()
            + "|"
            + f.completeTagSize()
            + "|"
            + hex(f.renderHeader())
            + "|"
            + hex(f.renderFooter()));
      }
    var a = new Tag();
    tag("empty-tag", a);
    a.addValue("Title", "one");
    a.addValue("TITLE", "two", false);
    a.setYear(0xffffffffL);
    a.setTrack(0x80000000L);
    a.setItem("A B", new Item("different", List.of("v")));
    a.setItem("id3", new Item("id3", List.of("invalid")));
    a.setData("binary", new ByteVector("opaque"));
    tag("mixed-tag", a);
    var p =
      cz.skyba.taglib.PropertyMap.builder()
        .put("TITLE", List.of("", "updated", ""))
        .put("DATE", List.of("2026"))
        .put("YEAR", List.of("2000"))
        .put("x", List.of("rejected"))
        .put("ID3", List.of("invalid"))
        .put("EMPTY", List.of())
        .build();
    System.out.println("rejected|" + properties(a.replaceProperties(p).rejected()));
    tag("properties-tag", a);
    a.removeUnsupportedProperties(List.of("binary"));
    a.addValue("Title", "", true);
    tag("removed-tag", a);
    var pictures = new ArrayList<Map<String, Variant>>();
    for (String type : List.of("Other", "Front Cover", "Front Cover", "Back Cover", "Back Cover"))
      pictures.add(
        Map.of(
          "pictureType",
          new Variant(type),
          "description",
          new Variant(type),
          "data",
          new Variant(ByteVector.fromUnsignedInt(pictures.size() + 1, false))));
    a.setComplexProperties("picture", pictures);
    tag("picture-tag", a);
    a.setComplexProperties("PICTURE", List.of());
    tag("clear-pictures", a);
    var parsed = new ParsedTag();
    parsed.load(
      payload("TITLE", new ByteVector("one"), 0)
        .append(payload("title", new ByteVector("two"), 1))
        .append(payload("bad", new ByteVector(new byte[]{(byte) 0xc3}), 0))
        .append(payload("reserved", new ByteVector("payload"), 6)),
      4);
    tag("parsed-tag", parsed);
    var truncated = new ParsedTag();
    var truncatedBody = payload("TITLE", new ByteVector("xy"), 0);
    truncatedBody.set(0, (byte) 10);
    truncated.load(truncatedBody, 1);
    tag("truncated-value", truncated);
    core(
      "core-numeric",
      payload("TITLE", new ByteVector(new byte[]{(byte) 0xc3}), 0)
        .append(payload("YEAR", new ByteVector("20345tail"), 0))
        .append(payload("TRACK", new ByteVector("-1/9"), 0)),
      3);
    core("core-truncated", truncatedBody, 1);
    core(
      "core-reserved",
      payload("EMPTY", new ByteVector(), 0)
        .append(payload("binary", new ByteVector("opaque"), 0xfffffffaL))
        .append(payload("locator", new ByteVector("url"), 4))
        .append(payload("reserved", new ByteVector("r"), 6)),
      4);
    core("core-empty", payload("TITLE", new ByteVector(), 0), 1);
    core(
      "core-roundtrip-unsupported",
      payload(
        "ARTIST",
        ByteVector.fromText("α", UTF_8)
          .append(new ByteVector((byte) 0))
          .append(ByteVector.fromText("β", UTF_8)),
        0)
        .append(payload("TRACK", new ByteVector("2/12"), 0))
        .append(payload("YEAR", new ByteVector("2026"), 0))
        .append(payload("Opaque", new ByteVector(new byte[]{0, 1, 2, 3}), 2)),
      4);
    core(
      "core-unsigned-maximum",
      payload("YEAR", new ByteVector("-1"), 0)
        .append(payload("TRACK", new ByteVector("2147483648"), 0)),
      2);
    core(
      "core-unsigned-reversed",
      payload("YEAR", new ByteVector("2147483648"), 0)
        .append(payload("TRACK", new ByteVector("-1"), 0)),
      2);
  }
}
