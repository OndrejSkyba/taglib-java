package cz.skyba.taglib.parity.id3v2.probes;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.id3.v2.Frame;
import cz.skyba.taglib.id3.v2.UnknownFrame;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.TagString;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

public class Id3FrameBaseProbe {
  static ByteVector bytes(String s) {
    return s.equals("-") ? new ByteVector() : new ByteVector(HexFormat.of().parseHex(s));
  }

  static String hex(ByteVector v) {
    return v.toHex().toString();
  }

  static String text(String v) {
    return hex(new TagString(v).data(TagString.Type.UTF16BE));
  }

  static int b(boolean v) {
    return v ? 1 : 0;
  }

  static void state(String id, Frame.Header h) {
    System.out.println(
      id
        + "\t"
        + hex(h.frameID())
        + "\t"
        + h.frameSize()
        + "\t"
        + h.version()
        + "\t"
        + h.size()
        + "\t"
        + b(h.tagAlterPreservation())
        + b(h.fileAlterPreservation())
        + b(h.readOnly())
        + b(h.groupingIdentity())
        + b(h.compression())
        + b(h.encryption())
        + b(h.unsynchronisation())
        + b(h.dataLengthIndicator())
        + "\t"
        + hex(h.render()));
  }

  static String props(PropertyMap p) {
    var out = new StringBuilder();
    p.asMap()
      .forEach(
        (k, vs) -> {
          out.append(text(k)).append('=');
          vs.forEach(v -> out.append(text(v)).append(','));
          out.append(';');
        });
    out.append('|');
    p.unsupportedData().forEach(v -> out.append(text(v)).append(','));
    return out.toString();
  }

  static class Raw extends Frame {
    ByteVector fields;
    int parses, renders;

    Raw(ByteVector data, long version) {
      super(new Header(data, version));
      parseFields(fieldData(data));
    }

    public String toString() {
      return "custom";
    }

    protected void parseFields(ByteVector data) {
      fields = new ByteVector(data);
      parses++;
    }

    protected ByteVector renderFields() {
      renders++;
      return new ByteVector(fields);
    }

    ByteVector extract(ByteVector data) {
      return fieldData(data);
    }

    void parsed(ByteVector data) {
      parse(data);
    }

    void replace(Header h) {
      setHeader(h);
    }

    String stringField(ByteVector data, TagString.Type type, int[] pos) {
      return readStringField(data, type, pos);
    }

    TagString.Type check(List<String> fields, TagString.Type type) {
      return checkTextEncoding(fields, type);
    }

    PropertyMap properties() {
      return asProperties();
    }

    static void split(
      cz.skyba.taglib.toolkit.MutablePropertyMap a,
      cz.skyba.taglib.toolkit.MutablePropertyMap b,
      cz.skyba.taglib.toolkit.MutablePropertyMap c,
      cz.skyba.taglib.toolkit.MutablePropertyMap d) {
      splitProperties(a, b, c, d);
    }
  }

  static class UnknownChild extends UnknownFrame {
    UnknownChild(ByteVector v) {
      super(v);
    }

    PropertyMap properties() {
      return asProperties();
    }
  }

  static class Custom extends Raw {
    Custom() {
      super(new ByteVector("X001"), 4);
    }

    public void setText(String s) {
      fields = new TagString(s).data(TagString.Type.UTF8);
    }

    public List<String> toStringList() {
      return List.of("virtual-list");
    }

    protected PropertyMap asProperties() {
      return PropertyMap.builder().put("TITLE", "virtual-properties").build();
    }
  }

  public static void main(String[] args) throws Exception {
    var seed = bytes("5345454400000003ff7f010203");
    try (var input = Files.newBufferedReader(Path.of(args[0]))) {
      String line;
      while ((line = input.readLine()) != null) {
        var f = line.split("\t", -1);
        var id = f[0];
        var data = bytes(f[2]);
        long version = Long.parseUnsignedLong(f[3]), number = Long.parseUnsignedLong(f[4]);
        var other = bytes(f[5]);
        switch (f[1]) {
          case "H" -> {
            var h = new Frame.Header(data, version);
            state(id + "/ctor", h);
            var kept = new Frame.Header(seed);
            kept.setData(data, version);
            state(id + "/kept", kept);
            kept.setData(data);
            state(id + "/default-version", kept);
            h.setFrameID(other);
            h.setFrameSize(number);
            h.setVersion(number);
            h.setTagAlterPreservation(true);
            state(id + "/set", h);
            h.setTagAlterPreservation(false);
            state(id + "/unset", h);
          }
          case "F" -> {
            var r = new Raw(data, version);
            state(id + "/ctor", r.header());
            System.out.println(
              id
                + "/fields\t"
                + hex(r.fields)
                + "\t"
                + r.parses
                + "\t"
                + r.renders
                + "\t"
                + text(r.toString())
                + "\t"
                + text(String.join(" ", r.toStringList()))
                + "\t"
                + props(r.properties()));
            r.setText("ignored");
            var live = r.header();
            var rendered = r.render();
            System.out.println(
              id + "/render\t" + hex(rendered) + "\t" + r.renders + "\t" + b(live == r.header()));
            state(id + "/after", r.header());
            r.setData(other);
            System.out.println(
              id
                + "/set-data\t"
                + hex(r.fields)
                + "\t"
                + r.parses
                + "\t"
                + r.size()
                + "\t"
                + r.headerSize());
            state(id + "/set-header", r.header());
            r.parsed(data);
            System.out.println(id + "/protected-parse\t" + hex(r.fields) + "\t" + r.parses);
            r.replace(null);
            System.out.println(id + "/null-header\t" + hex(r.frameID()) + "\t" + r.size());
            r.setData(data);
            state(id + "/restored", r.header());
            var replacement = new Frame.Header(other, version);
            r.replace(replacement);
            r.header().setFrameSize(number);
            System.out.println(
              id + "/replacement\t" + hex(r.extract(data)) + "\t" + b(replacement == r.header()));
            var u = new UnknownChild(data);
            System.out.println(
              id
                + "/unknown\t"
                + hex(u.data())
                + "\t"
                + text(u.toString())
                + "\t"
                + props(u.properties())
                + "\t"
                + hex(u.render()));
            u.setData(other);
            System.out.println(id + "/unknown-set\t" + hex(u.data()) + "\t" + hex(u.render()));
          }
          case "R" -> {
            var r = new Raw(new ByteVector("X001"), 4);
            int[] position = {(int) number};
            var type = TagString.Type.values()[(int) version];
            var value = r.stringField(data, type, position);
            System.out.println(
              id
                + "/string\t"
                + text(value)
                + "\t"
                + position[0]
                + "\t"
                + text(r.stringField(data, type, null))
                + "\t"
                + hex(data));
          }
          case "E" -> {
            var r = new Raw(new ByteVector("X001"), number);
            var fields =
              List.of(
                new TagString(data, TagString.Type.UTF8).toString(),
                new TagString(other, TagString.Type.UTF8).toString());
            var type = TagString.Type.values()[(int) version];
            System.out.println(
              id
                + "/encoding\t"
                + r.check(fields, type).ordinal()
                + "\t"
                + hex(Frame.textDelimiter(type)));
          }
          case "K" -> System.out.println(
            id
              + "/mapping\t"
              + hex(Frame.keyToFrameID(new TagString(data, TagString.Type.UTF8).toString()))
              + "\t"
              + text(Frame.frameIDToKey(other)));
          default -> throw new IllegalArgumentException(line);
        }
      }
    }
    var a = new cz.skyba.taglib.toolkit.MutablePropertyMap();
    var b = new cz.skyba.taglib.toolkit.MutablePropertyMap();
    var c = new cz.skyba.taglib.toolkit.MutablePropertyMap();
    var d = new cz.skyba.taglib.toolkit.MutablePropertyMap();
    a.add("title", List.of("title"));
    a.add("ARRANGER", List.of("arranger"));
    a.add("DJMIXER", List.of("dj"));
    a.add("DJ-MIX", List.of("other"));
    a.add("PERFORMER:guitar", List.of("performer"));
    a.add("MIXER", List.of());
    a.addUnsupportedData("ignored");
    Raw.split(a, b, c, d);
    System.out.println(
      "split\t"
        + props(a.snapshot())
        + "\t"
        + props(b.snapshot())
        + "\t"
        + props(c.snapshot())
        + "\t"
        + props(d.snapshot()));
    var custom = new Custom();
    Frame base = custom;
    base.setText("changed");
    System.out.println(
      "virtual\t"
        + hex(custom.render())
        + "\t"
        + text(String.join(" ", base.toStringList()))
        + "\t"
        + props(custom.properties()));
    var listOwner = new Raw(new ByteVector("X001"), 4);
    var detached = listOwner.toStringList();
    detached.set(0, "changed");
    detached.add("added");
    System.out.println(
      "list-copy\t"
        + text(String.join(" ", detached))
        + "\t"
        + text(String.join(" ", listOwner.toStringList())));
    System.out.println(
      "prefix\t"
        + text(Frame.instrumentPrefix)
        + "\t"
        + text(Frame.commentPrefix)
        + "\t"
        + text(Frame.lyricsPrefix)
        + "\t"
        + text(Frame.urlPrefix));
  }
}
