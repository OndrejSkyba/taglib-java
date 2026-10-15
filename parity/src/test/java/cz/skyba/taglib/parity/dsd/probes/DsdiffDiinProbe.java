package cz.skyba.taglib.parity.dsd.probes;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.TagBase;
import cz.skyba.taglib.TagUnion;
import cz.skyba.taglib.dsdiff.diin.Tag;
import cz.skyba.taglib.toolkit.ByteVector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_16BE;

class DsdiffDiinProbe {
  static String hex(String value) {
    return HexFormat.of()
      .formatHex(ByteVector.fromText(value, UTF_16BE).toByteArray());
  }

  static String text(String raw) {
    var value = new StringBuilder();
    for (int i = 0; i < raw.length(); i += 4)
      value.append((char) Integer.parseInt(raw.substring(i, i + 4), 16));
    return value.toString();
  }

  static String list(List<String> values) {
    var result = new StringBuilder(values.size() + ":");
    for (var value : values) result.append(hex(value)).append(',');
    return result.toString();
  }

  static String props(PropertyMap values) {
    var result = new StringBuilder();
    values.asMap().forEach((k, v) -> result.append(hex(k)).append('=').append(list(v)).append(';'));
    return result + "/" + list(values.unsupportedData());
  }

  static PropertyMap baseProps(TagBase tag) {
    var values = PropertyMap.builder();
    if (!tag.title().isEmpty()) values.put("TITLE", tag.title());
    if (!tag.artist().isEmpty()) values.put("ARTIST", tag.artist());
    if (!tag.album().isEmpty()) values.put("ALBUM", tag.album());
    if (!tag.comment().isEmpty()) values.put("COMMENT", tag.comment());
    if (!tag.genre().isEmpty()) values.put("GENRE", tag.genre());
    if (tag.year() != 0) values.put("DATE", Integer.toString((int) tag.year()));
    if (tag.track() != 0) values.put("TRACKNUMBER", Integer.toString((int) tag.track()));
    return values.build();
  }

  static void state(String id, TagBase tag) {
    System.out.println(
      id
        + "\t"
        + (tag.isEmpty() ? 1 : 0)
        + "\t"
        + hex(tag.title())
        + "\t"
        + hex(tag.artist())
        + "\t"
        + hex(tag.album())
        + "\t"
        + hex(tag.comment())
        + "\t"
        + hex(tag.genre())
        + "\t"
        + tag.year()
        + "\t"
        + tag.track()
        + "\t"
        + props(tag.properties())
        + "\t"
        + props(baseProps(tag)));
  }

  static class Custom extends Tag {
    int calls;

    @Override
    public String title() {
      return "custom getter";
    }

    @Override
    public void setTitle(String value) {
      calls++;
      super.setTitle("custom:" + value);
    }
  }

  public static void main(String[] args) throws Exception {
    state("empty", new Tag());
    for (var line : Files.readAllLines(Path.of(args[0], "cases.tsv"))) {
      var v = line.split("\t", -1);
      var id = v[0];
      var title = text(v[1]);
      var artist = text(v[2]);
      int nt = Integer.parseInt(v[3]), na = Integer.parseInt(v[4]);
      var tag = new Tag();
      tag.setTitle(title);
      tag.setArtist(artist);
      state(id + "/stored", tag);
      var snapshot = tag.properties();
      tag.setTitle("later");
      System.out.println(id + "/snapshot\t" + props(snapshot));
      tag.setTitle(title);
      tag.setAlbum("unsupported");
      tag.setComment(title);
      tag.setGenre(artist);
      tag.setYear(0xffff_ffffL);
      tag.setTrack(0x8000_0000L);
      state(id + "/unsupported", tag);
      var titles = new ArrayList<String>();
      var artists = new ArrayList<String>();
      for (int n = 0; n < nt; n++) titles.add(n == 0 ? title : title + ":" + n);
      for (int n = 0; n < na; n++) artists.add(n == 0 ? artist : artist + ":" + n);
      var input =
        PropertyMap.builder()
          .put("TITLE", titles)
          .put("ARTIST", artists)
          .put("ALBUM", List.of("album", "album2"))
          .put("EMPTY", List.of())
          .put("DATE", "-1")
          .put("CUSTOM", List.of("x", ""))
          .put("äkey", "unicode key")
          .addUnsupportedData("opaque")
          .addUnsupportedData("TITLE")
          .addUnsupportedData("opaque")
          .build();
      var rejected = tag.replaceProperties(input).rejected();
      System.out.println(id + "/import\t" + props(rejected) + "\t" + props(input));
      state(id + "/imported", tag);
      tag.removeUnsupportedProperties(List.of("TITLE", "ARTIST", "opaque"));
      System.out.println(
        id
          + "/complex\t"
          + list(tag.complexPropertyKeys())
          + "\t"
          + tag.complexProperties("PICTURE").size()
          + "\t"
          + (tag.setComplexProperties("PICTURE", List.of()) ? 1 : 0)
          + "\t"
          + props(tag.properties()));
      var source = new Tag();
      source.setTitle(title);
      source.setArtist(artist);
      TagBase.copyFields(source, tag, false);
      state(id + "/duplicate-fill", tag);
      TagBase.copyFields(source, tag);
      state(id + "/duplicate-overwrite", tag);
      tag.replaceProperties(PropertyMap.EMPTY);
      state(id + "/cleared", tag);
    }
    var custom = new Custom();
    custom.setTitle("direct");
    state("custom/direct", custom);
    var p = PropertyMap.builder().put("TITLE", List.of("imported", "rejected")).build();
    System.out.println("custom/import\t" + props(custom.replaceProperties(p).rejected()) + "\t" + custom.calls);
    state("custom/imported", custom);
    var a = new Tag();
    var b = new Tag();
    var union = new TagUnion(a, b, null);
    state("union/empty", union);
    b.setTitle("second");
    b.setArtist("artist");
    state("union/fallback", union);
    a.setArtist("first");
    state("union/map-primary", union);
    union.setTitle("broadcast");
    state("union/broadcast", union);
    state("union/child0", a);
    state("union/child1", b);
  }
}
