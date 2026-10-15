package cz.skyba.taglib.parity.riff.probes;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.internal.TextBytes;
import cz.skyba.taglib.riff.info.StringHandler;
import cz.skyba.taglib.riff.info.Tag;
import cz.skyba.taglib.toolkit.ByteVector;

import java.util.HexFormat;
import java.util.List;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

class RiffInfoProbe {
  static ByteVector bytes(String v) {
    return ByteVector.fromText(v, ISO_8859_1);
  }

  static String hex(ByteVector v) {
    return HexFormat.of().formatHex(v.toByteArray());
  }

  static String text(String v) {
    return hex(ByteVector.fromText(v, UTF_8));
  }

  static String props(PropertyMap p) {
    var o = new StringBuilder();
    p.asMap()
      .forEach(
        (k, values) -> {
          o.append(text(k)).append('=');
          for (var v : values) o.append(text(v)).append(',');
          o.append(';');
        });
    return o.toString();
  }

  static void state(String name, Tag t) {
    var o =
      new StringBuilder(name)
        .append('\t')
        .append(t.isEmpty() ? 1 : 0)
        .append('\t')
        .append(t.year())
        .append('\t')
        .append(t.track())
        .append('\t');
    t.fieldListMap().forEach((k, v) -> o.append(hex(k)).append('=').append(text(v)).append(';'));
    o.append('\t').append(props(t.properties())).append('\t');
    for (var id : t.properties().unsupportedData()) o.append(text(id)).append(',');
    System.out.println(o.append('\t').append(hex(t.render())));
  }

  static ByteVector field(String id, byte[] value) {
    var r = bytes(id);
    r.append(ByteVector.fromUnsignedInt(value.length, false));
    r.append(new ByteVector(value));
    if ((r.size() & 1) != 0) r.append((byte) 0);
    return r;
  }

  public static void main(String[] args) {
    var t = new Tag();
    state("empty", t);
    t.setTitle("Title");
    t.setArtist("Artist");
    t.setAlbum("Album");
    t.setComment("Comment");
    t.setGenre("Genre");
    t.setYear(2026);
    t.setTrack(7);
    state("common", t);
    t.setFieldText(bytes("ZZZZ"), "opaque");
    t.setFieldText(bytes("YYYY"), "other");
    state("unknown", t);
    t.removeUnsupportedProperties(t.properties().unsupportedData());
    state("remove-empty-identifiers", t);
    t.removeUnsupportedProperties(List.of("ZZZZ"));
    state("remove-raw-id", t);
    var p =
      PropertyMap.builder()
        .put("TITLE", List.of("", "second"))
        .put("ARTIST", List.of())
        .put("CUSTOM", "x")
        .build();
    var rejected = t.replaceProperties(p).rejected();
    state("replace-properties", t);
    System.out.println("rejected\t" + props(rejected));
    t.setYear(0xffff_ffffL);
    t.setTrack(0xffff_ffffL);
    state("unsigned", t);
    t.setYear(0);
    t.setTrack(0);
    state("number-clear", t);
    var copy = t.fieldListMap();
    copy.clear();
    state("map-copy", t);
    for (String id : List.of("ABC", "ABCDE", "\u001fABC", "\u0080ABC"))
      t.setFieldText(bytes(id), "bad");
    state("invalid-ids", t);
    t.removeField(bytes("YYYY"));
    t.setTitle("");
    state("clear-all", t);
    var input = bytes("INFO");
    input.append(field("INAM", new byte[]{'a', 0}));
    input.append(field("INAM", new byte[]{'b', 0}));
    input.append(field("IART", new byte[]{0}));
    input.append(field("ZZZZ", new byte[]{(byte) 0xc3, 0}));
    state("parse-duplicate-empty-invalid-utf8", new Tag(input));
    Tag.setStringHandler(
      new StringHandler() {
        public String parse(ByteVector d) {
          return TextBytes.decodeTerminated(d.data(), ISO_8859_1);
        }

        public ByteVector render(String s) {
          return ByteVector.fromText(s, ISO_8859_1);
        }
      });
    var raw = bytes("INFO");
    raw.append(field("INAM", new byte[]{(byte) 0xe9, 0}));
    var local = new Tag(raw);
    state("latin1", local);
    Tag.setStringHandler(null);
    state("restored-utf8", local);
    var broken = bytes("INFO");
    broken.append(field("INAM", new byte[]{'x', 0}));
    broken.append(bytes("ZZZZ"));
    broken.append(ByteVector.fromUnsignedInt(0xffff_ffffL, false));
    state("oversized-field", new Tag(broken));
    for (String id :
      List.of(
        "IPRD", "IENG", "IART", "IBSU", "IBPM", "ICMT", "IMUS", "ICOP", "ICRD", "PRT1", "ITCH",
        "ISFT", "IDIT", "IGNR", "ISRC", "IPUB", "ILNG", "IWRI", "IMED", "ISTR", "ICNT", "IEDT",
        "INAM", "IPRT")) {
      var each = new Tag();
      each.setFieldText(bytes(id), "value");
      state(id, each);
    }
  }
}
