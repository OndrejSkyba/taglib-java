package cz.skyba.taglib.parity.toolkit.probes;

import cz.skyba.taglib.*;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

public class TagBaseProbe {
  static class Memory extends TagBase {
    String t = "", a = "", b = "", c = "", g = "";
    long y, n;

    public String title() {
      return t;
    }

    public String artist() {
      return a;
    }

    public String album() {
      return b;
    }

    public String comment() {
      return c;
    }

    public String genre() {
      return g;
    }

    public long year() {
      return y;
    }

    public long track() {
      return n;
    }

    public void setTitle(String v) {
      t = v;
    }

    public void setArtist(String v) {
      a = v;
    }

    public void setAlbum(String v) {
      b = v;
    }

    public void setComment(String v) {
      c = v;
    }

    public void setGenre(String v) {
      g = v;
    }

    public void setYear(long v) {
      y = v & 0xffff_ffffL;
    }

    public void setTrack(long v) {
      n = v & 0xffff_ffffL;
    }
  }

  static class StreamFile extends FileBase {
    final Memory value = new Memory();

    StreamFile(cz.skyba.taglib.toolkit.IOStream stream) {
      super(stream);
    }

    public TagBase tag() {
      return value;
    }

    public AudioProperties audioProperties() {
      return null;
    }

    public void save() {
      throw new cz.skyba.taglib.TagLibException("Test save rejected");
    }
  }

  static String hex(String v) {
    return HexFormat.of().formatHex(v.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  static void show(String label, PropertyMap map) {
    System.out.println(label + "=" + hex(map.toString()));
  }

  public static void main(String[] args) {
    for (int offset : new int[]{0, 1019, 1020, 1021, 1022, 1023, 1024, 2046, 2047, 2048}) {
      byte[] bytes = new byte[3100];
      Arrays.fill(bytes, (byte) 'x');
      for (int i = 0; i < 4; i++) bytes[offset + i] = (byte) "ABCD".charAt(i);
      var stream = new cz.skyba.taglib.toolkit.ByteVectorStream(bytes);
      var file = new StreamFile(stream);
      file.seek(33);
      for (int start : new int[]{0, 2, 1020, 1024, 2048})
        for (String before : List.of("", "xx", "AB")) {
          var pattern = new cz.skyba.taglib.toolkit.ByteVector("ABCD");
          var stop = new cz.skyba.taglib.toolkit.ByteVector(before);
          System.out.println(
            "file-search="
              + offset
              + ","
              + start
              + ","
              + before
              + ":"
              + file.find(pattern, start, stop)
              + ":"
              + file.rfind(pattern, start, stop)
              + ":"
              + file.tell());
        }
    }

    for (String value :
      List.of(
        "",
        "0",
        "2026",
        "-2",
        "2026-10",
        " 42",
        "42 ",
        "2147483647",
        "-2147483648",
        "2147483646",
        "4294967295",
        "+7")) {
      var m = new Memory();
      var p =
        PropertyMap.builder()
          .put("TITLE", List.of("first", "second"))
          .put("DATE", value)
          .put("TRACKNUMBER", value)
          .put("EMPTY", List.of())
          .put("PRIVATE", "x")
          .addUnsupportedData("opaque")
          .build();
      var left = m.replaceProperties(p).rejected();
      System.out.println("numbers=" + hex(value) + ":" + m.year() + ":" + m.track());
      show("properties", m.properties());
      show("remainder", left);
    }
    var source = new Memory();
    var target = new Memory();
    source.setTitle("source");
    source.setArtist("artist");
    source.setTrack(7);
    target.setTitle("kept");
    target.setYear(2026);
    TagBase.copyFields(source, target, false);
    show("duplicate-fill", target.properties());
    TagBase.copyFields(source, target);
    show("duplicate-all", target.properties());
    var first = new Memory();
    var second = new Memory();
    var third = new Memory();
    first.setTitle("first");
    second.setArtist("second");
    second.setYear(2026);
    third.setTrack(4);
    var tags = new TagUnion(first, second, third);
    System.out.println(
      "union=" + tags.title() + ":" + tags.artist() + ":" + tags.year() + ":" + tags.track());
    show("union-map", tags.properties());
    var p = PropertyMap.builder().put("TITLE", "all").put("DATE", "2025").build();
    show("union-remainder", tags.replaceProperties(p).rejected());
    for (int i = 0; i < 3; i++) show("union-child", tags.tag(i).properties());
    var mut = new cz.skyba.taglib.toolkit.MutablePropertyMap();
    mut.getOrCreate("mixed").add("one");
    mut.getOrCreate("MIXED").add("two");
    var v = mut.values("mixed");
    v.add("copy");
    mut.getOrCreate("empty");
    mut.addUnsupportedData("opaque");
    show("mutable", mut.snapshot());
    var keys = new cz.skyba.taglib.toolkit.MutablePropertyMap(mut);
    mut.removeAll(keys);
    show("mutable-erase", mut.snapshot());
  }
}
