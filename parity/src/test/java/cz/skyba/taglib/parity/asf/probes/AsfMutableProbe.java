package cz.skyba.taglib.parity.asf.probes;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.asf.AsfFile;
import cz.skyba.taglib.asf.Attribute;
import cz.skyba.taglib.asf.Picture;
import cz.skyba.taglib.asf.Tag;
import cz.skyba.taglib.parity.support.FileRefAdapter;
import cz.skyba.taglib.parity.support.OpenOutcome;
import cz.skyba.taglib.parity.support.SaveOutcome;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.Variant;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import static java.nio.charset.StandardCharsets.UTF_16BE;
import static java.nio.charset.StandardCharsets.UTF_16LE;

// Independent Java ASF mutable value/tag/file protocol.

public class AsfMutableProbe {
  static int b(boolean v) {
    return v ? 1 : 0;
  }

  static String hex(ByteVector v) {
    return v.toHex().toString();
  }

  static String hex(String v) {
    return hex(ByteVector.fromText(v, UTF_16BE));
  }

  static String list(List<String> v) {
    var s = new StringBuilder(v.size() + ":");
    for (var a : v) s.append(hex(a)).append(',');
    return s.toString();
  }

  static String props(PropertyMap p) {
    var s = new StringBuilder();
    p.asMap().forEach((k, v) -> s.append(hex(k)).append('=').append(list(v)).append(';'));
    return s + "/" + list(p.unsupportedData());
  }

  static String picture(Picture p) {
    return b(p.isValid())
      + ":"
      + p.type().code()
      + ":"
      + hex(p.mimeType())
      + ":"
      + hex(p.description())
      + ":"
      + hex(p.picture())
      + ":"
      + p.dataSize()
      + ":"
      + hex(p.render());
  }

  static String attr(Attribute a) {
    return a.type()
      + ":"
      + a.stream()
      + ":"
      + a.language()
      + ":"
      + b(a.toBoolean())
      + ":"
      + a.toUnsignedShort()
      + ":"
      + a.toUnsignedInt()
      + ":"
      + Long.toUnsignedString(a.toUnsignedLong())
      + ":"
      + hex(a.toString())
      + ":"
      + hex(a.toByteVector())
      + ":"
      + a.dataSize()
      + ":"
      + picture(a.toPicture());
  }

  static void tag(String id, Tag t) {
    System.out.println(
      id
        + "/common\t"
        + hex(t.title())
        + ":"
        + hex(t.artist())
        + ":"
        + hex(t.album())
        + ":"
        + hex(t.comment())
        + ":"
        + hex(t.genre())
        + ":"
        + t.year()
        + ":"
        + t.track()
        + ":"
        + hex(t.rating())
        + ":"
        + hex(t.copyright())
        + ":"
        + b(t.isEmpty())
        + "\t"
        + props(t.properties()));
    var map = new StringBuilder();
    t.attributeListMap()
      .forEach(
        (k, v) -> {
          map.append(hex(k)).append('=');
          for (var a : v) map.append(attr(a)).append(';');
          map.append('/');
        });
    System.out.println(id + "/map\t" + map);
    var complex = new StringBuilder(id + "/complex\t" + list(t.complexPropertyKeys()) + "\t");
    for (var p : t.complexProperties("picture")) {
      for (var e : p.entrySet())
        complex
          .append(hex(e.getKey()))
          .append('=')
          .append(
            e.getKey().equals("data")
              ? hex(e.getValue().toByteVector())
              : hex(e.getValue().toString()))
          .append(';');
      complex.append('/');
    }
    System.out.println(complex);
  }

  static void state(String id, AsfFile f) {
    if (f == null) {
      System.out.println(id + "/rejected\t1");
      return;
    }
    System.out.println(
      id
        + "/file\t"
        + b(f.isOpen())
        + ":"
        + b(f.isValid())
        + ":"
        + f.tell()
        + ":"
        + b(f.tag() != null)
        + ":"
        + b(f.detailedProperties() != null));
    if (f.tag() != null) tag(id, f.tag());
    var p = f.detailedProperties();
    if (p != null)
      System.out.println(
        id
          + "/audio\t"
          + p.lengthInMilliseconds()
          + ":"
          + p.bitrate()
          + ":"
          + p.sampleRate()
          + ":"
          + p.channels()
          + ":"
          + p.bitsPerSample()
          + ":"
          + p.codec().ordinal()
          + ":"
          + hex(p.codecName())
          + ":"
          + hex(p.codecDescription())
          + ":"
          + b(p.isEncrypted()));
  }

  static class Named extends ByteVectorStream {
    String name;

    Named(byte[] data) {
      super(data);
      name = "input.wma";
    }

    public String name() {
      return name;
    }
  }

  static void edit(Tag t, int mode, String id) {
    if (mode == 0) return;
    if (mode == 1) {
      t.setTitle("Title é音");
      t.setArtist("Artist");
      t.setAlbum("Album");
      t.setComment("Comment");
      t.setGenre("Jazz");
      t.setYear(0xffffffffL);
      t.setTrack(0x80000000L);
      t.setRating("rating");
      t.setCopyright("copyright");
    }
    if (mode == 2) {
      t.setAttribute("Unknown", new Attribute(new ByteVector("opaque")));
      t.addAttribute("Unknown", new Attribute(true));
      t.setAttribute("WM/TrackNumber", new Attribute(123));
      t.setAttribute("WM/AlbumTitle", List.of(new Attribute("one"), new Attribute("two")));
    }
    if (mode == 3) {
      var p =
        PropertyMap.builder()
          .put("TITLE", List.of("one", "two"))
          .put("DATE", List.of("-1", "7"))
          .put("ISVBR", List.of("TRUE", "true", "False", "0", "1", ""))
          .put("ENCODINGTIME", List.of("18446744073709551615", "-1", "abc"))
          .put("MEDIACLASSPRIMARYID", List.of("abc", "01234567-89ab-cdef-0123-456789ab-cdef"))
          .put("UNKNOWN", "rejected")
          .build();
      System.out.println(id + "/rejected/3\t" + props(t.replaceProperties(p).rejected()));
    }
    if (mode == 4) {
      var a = new Attribute("scoped");
      a.setStream(-1);
      a.setLanguage(-2);
      t.setAttribute("WM/AlbumTitle", a);
      a.setStream(43);
      t.addAttribute("WM/AlbumTitle", a);
      a.setLanguage(0);
      t.addAttribute("WM/AlbumTitle", a);
    }
    if (mode == 5) {
      var p = new Picture();
      p.setType(Picture.Type.FrontCover);
      p.setMimeType("image/test");
      p.setDescription("desc");
      p.setPicture(new ByteVector("picture"));
      t.setAttribute("WM/Picture", new Attribute(p));
      var q = new Picture(p);
      q.setType(Picture.Type.BackCover);
      q.setDescription("shared");
      t.addAttribute("WM/Picture", new Attribute(q));
    }
    if (mode == 6) {
      t.attributeListMap().clear();
      t.setTitle("");
      t.setArtist("");
      t.setComment("");
      t.setCopyright("");
      t.setRating("");
    }
    if (mode == 7) {
      t.attribute("missing");
      t.setAttribute("empty", List.of());
      t.removeUnsupportedProperties(List.of("Unknown", "WM/Picture"));
    }
    if (mode == 8) {
      t.setAttribute("REPLAYGAIN_TRACK_GAIN", new Attribute("upper"));
      t.setAttribute("replaygain_track_gain", new Attribute("lower"));
      t.replaceProperties(t.properties().toBuilder().put("REPLAYGAIN_TRACK_GAIN", "edited").build());
    }
    if (mode == 9) t.setAttribute("WM/Blob", new Attribute(new ByteVector(70000, (byte) 'x')));
    if (mode == 10) t.setTitle("x".repeat(128 * 1024));
    if (mode == 11) t.setAttribute("WM/Picture", new Attribute(new ByteVector("invalid")));
  }

  static void parseAttributes(Path root) throws Exception {
    for (int kind : new int[]{-1, 0, 1, 2, 3})
      for (int type = 0; type <= 7; type++)
        for (int declared : new int[]{0, 1, 2, 4, 8, 65536}) {
          var name =
            ByteVector.fromText("WM/Picture", UTF_16LE).append(new ByteVector(4));
          var payload = ByteVector.fromUnsignedInt(0x80000001L, false).append(new ByteVector("abcdefghi"));
          ByteVector wire;
          if (kind == 0)
            wire =
              ByteVector.fromShort((short) name.size(), false)
                .append(name)
                .append(ByteVector.fromShort((short) type, false))
                .append(ByteVector.fromShort((short) declared, false))
                .append(payload);
          else
            wire =
              ByteVector.fromShort((short) 23, false)
                .append(ByteVector.fromShort((short) 43, false))
                .append(ByteVector.fromShort((short) name.size(), false))
                .append(ByteVector.fromShort((short) type, false))
                .append(ByteVector.fromUnsignedInt(declared, false))
                .append(name)
                .append(payload);
          for (int cut = 0; cut <= wire.size(); cut++) {
            var stream = new Named(Files.readAllBytes(root.resolve("inputs/plain.wma")));
            try (var file = new AsfFile(stream, ReadOptions.TAGS_ONLY)) {
              stream.data().clear();
              stream.data().append(wire.mid(0, cut));
              file.seek(0);
              var picture = new Picture();
              picture.setPicture(new ByteVector("old payload"));
              var value = new Attribute(picture);
              value.setStream(-7);
              value.setLanguage(-9);
              String parsed = value.parse(file, kind);
              System.out.println(
                "attribute-parse/"
                  + kind
                  + "/"
                  + type
                  + "/"
                  + declared
                  + "/"
                  + cut
                  + "\t"
                  + hex(parsed)
                  + "\t"
                  + file.tell()
                  + "\t"
                  + attr(value));
            }
          }
        }
    class ReadFailure extends Named {
      boolean armed;
      int call, fail;
      StringBuilder trace = new StringBuilder();

      ReadFailure(byte[] data) {
        super(data);
      }

      public ByteVector readBlock(long n) {
        if (armed) {
          trace.append(n).append(':').append(tell()).append(';');
          if (call++ == fail) throw new IllegalStateException("read failure");
        }
        return super.readBlock(n);
      }
    }
    for (int fail = 0; fail < 8; fail++) {
      var stream = new ReadFailure(Files.readAllBytes(root.resolve("inputs/plain.wma")));
      try (var file = new AsfFile(stream, ReadOptions.TAGS_ONLY)) {
        var name =
          ByteVector.fromText("WM/Picture", UTF_16LE).append(new ByteVector(4));
        var payload = ByteVector.fromText("text", UTF_16LE).append(new ByteVector(4));
        stream.data().clear();
        stream
          .data()
          .append(ByteVector.fromShort((short) 23, false))
          .append(ByteVector.fromShort((short) 43, false))
          .append(ByteVector.fromShort((short) name.size(), false))
          .append(ByteVector.fromShort((short) 0, false))
          .append(ByteVector.fromUnsignedInt(payload.size(), false))
          .append(name)
          .append(payload);
        file.seek(0);
        var picture = new Picture();
        picture.setPicture(new ByteVector("old"));
        var value = new Attribute(picture);
        value.setStream(-7);
        value.setLanguage(-9);
        stream.armed = true;
        stream.fail = fail;
        boolean failed = false;
        String result = "";
        try {
          result = value.parse(file, 2);
        } catch (IllegalStateException failure) {
          failed = true;
        }
        System.out.println(
          "attribute-read-failure/"
            + fail
            + "\t"
            + b(failed)
            + "\t"
            + hex(result)
            + "\t"
            + file.tell()
            + "\t"
            + stream.trace
            + "\t"
            + attr(value));
      }
    }
    {
      var stream = new Named(Files.readAllBytes(root.resolve("inputs/plain.wma")));
      try (var file = new AsfFile(stream, ReadOptions.TAGS_ONLY)) {
        var value = new Attribute();
        for (int type : new int[]{0, 3, 1, 7, 6, 4, 5, 2}) {
          var name = ByteVector.fromText("name", UTF_16LE).append(new ByteVector(4));
          var payload = ByteVector.fromText("old", UTF_16LE).append(new ByteVector(4));
          stream.data().clear();
          stream
            .data()
            .append(ByteVector.fromShort((short) name.size(), false))
            .append(name)
            .append(ByteVector.fromShort((short) type, false))
            .append(ByteVector.fromShort((short) payload.size(), false))
            .append(payload);
          file.seek(0);
          String parsed = value.parse(file);
          System.out.println(
            "attribute-reparse/"
              + type
              + "\t"
              + hex(parsed)
              + "\t"
              + file.tell()
              + "\t"
              + attr(value));
        }
      }
    }
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    parseAttributes(root);
    System.out.println("value/default\t" + attr(new Attribute()));
    for (long n :
      new long[]{0, 1, 65535, 65536, 2147483648L, 4294967295L, 4294967296L, Long.MIN_VALUE, -1})
      System.out.println(
        "value/"
          + Long.toUnsignedString(n)
          + "\t"
          + attr(new Attribute((short) n))
          + "\t"
          + attr(new Attribute((int) n))
          + "\t"
          + attr(new Attribute(n))
          + "\t"
          + attr(new Attribute(n != 0)));
    for (int n = 0; n <= 34; n++) {
      var v = new ByteVector(n, (byte) 'x');
      System.out.println(
        "bytes/" + n + "\t" + attr(new Attribute(v)) + "\t" + attr(Attribute.fromGuid(v)));
    }
    for (String s : List.of("", "123", "é音", "a\0b"))
      System.out.println("text/" + hex(s) + "\t" + attr(new Attribute(s)));
    {
      var t = new Tag();
      tag("tag/default", t);
      t.attribute("missing");
      tag("tag/missing", t);
      for (int m = 1; m < 12; m++) {
        edit(t, m, "tag");
        tag("tag/edit/" + m, t);
      }
    }
    for (int n = 0; n < 65536; n++)
      System.out.println("word/" + n + "\t" + attr(new Attribute((short) n)));
    for (int n = 0; n < 65536; n++)
      System.out.println(
        "type/"
          + n
          + "\t"
          + Picture.typeFromUInt(n).code()
          + "\t"
          + hex(Picture.typeToString(new Picture.Type(n))));
    for (int n : new int[]{32766, 32767, 32768, 65535}) {
      var s = new Named(Files.readAllBytes(root.resolve("inputs/plain.wma")));
      try (var f = new AsfFile(s, ReadOptions.TAGS_ONLY)) {
        f.tag().setAttribute("x".repeat(n), new Attribute("value"));
        boolean saved = SaveOutcome.save(f);
        System.out.println(
          "long-name/" + n + "/save/0\t" + b(saved) + "\t" + s.tell() + "\t" + hex(s.data()));
      }
    }
    for (int n : new int[]{65535, 65536}) {
      var s = new Named(Files.readAllBytes(root.resolve("inputs/plain.wma")));
      try (var f = new AsfFile(s, ReadOptions.TAGS_ONLY)) {
        var list = new ArrayList<Attribute>();
        for (int i = 0; i < n; i++) list.add(new Attribute(""));
        f.tag().setAttribute("many", list);
        boolean saved = SaveOutcome.save(f);
        System.out.println(
          "count/" + n + "/save/0\t" + b(saved) + "\t" + s.tell() + "\t" + hex(s.data()));
      }
    }
    for (int n : new int[]{-1, 0, 1, 20, 21, 255, 256, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
      var p = new Picture();
      p.setType(new Picture.Type(n));
      System.out.println(
        "picture/enum/"
          + n
          + "\t"
          + picture(p)
          + "\t"
          + hex(Picture.typeToString(p.type()))
          + "\t"
          + Picture.typeFromUInt(n).code()
          + "\t"
          + Picture.typeFromByte((byte) n).code());
    }
    Files.createDirectories(root.resolve("outputs"));
    try (var paths = Files.list(root.resolve("inputs"))) {
      for (var input : paths.sorted().toList()) {
        String id = input.getFileName().toString();
        byte[] data = Files.readAllBytes(input);
        for (int origin : new int[]{0, 7})
          for (var style : ReadStyle.values())
            for (boolean enabled : new boolean[]{false, true}) {
              var s = new Named(data);
              s.seek(origin);
              try (var f = OpenOutcome.file(() -> new AsfFile(s, new ReadOptions(enabled, style, ReadOptions.DEFAULT.maxMetadataBytes())))) {
                state(id + "/read/" + origin + "/" + style.ordinal() + "/" + b(enabled), f);
              }
            }
        for (int origin : new int[]{0, 7}) {
          var s = new Named(data);
          s.seek(origin);
          System.out.println(
            id + "/support/" + origin + "\t" + b(AsfFile.isSupported(s)) + "\t" + s.tell());
          var named = new Named(data);
          named.seek(origin);
          try (var ref = new FileRefAdapter(named, false)) {
            System.out.println(
              id
                + "/ref/"
                + origin
                + "\t"
                + b(ref.isNull())
                + ":"
                + b(ref.file() instanceof AsfFile)
                + ":"
                + named.tell());
          }
        }
        if (Set.of(
            "plain.wma",
            "duplicates.wma",
            "carrier-0.wma",
            "upstream-silence-1-wma.wma",
            "case-names.wma")
          .contains(id))
          for (int m = 0; m < 12; m++) {
            var s = new Named(data);
            try (var f = new AsfFile(s, ReadOptions.TAGS_ONLY)) {
              edit(f.tag(), m, id);
              String base = id + "/edit/" + m;
              var t = f.tag();
              var p = f.detailedProperties();
              state(base + "/staged", f);
              for (int n = 0; n < 3; n++) {
                boolean saved = SaveOutcome.save(f);
                f.seek(f.tell());
                System.out.println(
                  base
                    + "/save/"
                    + n
                    + "\t"
                    + b(saved)
                    + "\t"
                    + s.tell()
                    + "\t"
                    + b(t == f.tag())
                    + ":"
                    + b(p == f.detailedProperties())
                    + "\t"
                    + hex(s.data()));
                state(base + "/after/" + n, f);
                if (n == 0 && m == 10) t.setTitle("x");
              }
              Files.write(
                root.resolve("outputs/" + id + "." + m + ".java"), s.data().toByteArray());
            }
          }
        if (Set.of(
            "plain.wma",
            "duplicates.wma",
            "carrier-0.wma",
            "upstream-silence-1-wma.wma",
            "case-names.wma")
          .contains(id))
          for (int m = 0; m < 12; m++) {
            var output = root.resolve("outputs/" + id + ".path." + m + ".java");
            Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
            try (var f = new AsfFile(output, ReadOptions.TAGS_ONLY)) {
              String base = id + "/path/" + m;
              edit(f.tag(), m, base);
              var t = f.tag();
              var p = f.detailedProperties();
              state(base + "/staged", f);
              for (int n = 0; n < 3; n++) {
                boolean saved = SaveOutcome.save(f);
                f.seek(f.tell());
                System.out.println(
                  base
                    + "/save/"
                    + n
                    + "\t"
                    + b(saved)
                    + "\t"
                    + f.tell()
                    + "\t"
                    + b(t == f.tag())
                    + ":"
                    + b(p == f.detailedProperties())
                    + "\t"
                    + hex(new ByteVector(Files.readAllBytes(output))));
                state(base + "/after/" + n, f);
                if (n == 0 && m == 10) t.setTitle("x");
              }
            }
          }
      }
    }

    {
      var t = new Tag();
      for (int n = 0; n < 5; n++) {
        var m = new TreeMap<String, Variant>();
        if (n == 1) {
          m.put("data", new Variant(new ByteVector("data")));
          m.put("mimeType", new Variant("image/test"));
          m.put("description", new Variant("desc"));
          m.put("pictureType", new Variant("Front Cover"));
        }
        if (n == 2) {
          m.put("data", new Variant(1));
          m.put("mimeType", new Variant(true));
          m.put("description", new Variant(new ByteVector("wrong")));
          m.put("pictureType", new Variant(21));
        }
        System.out.println(
          "complex/set/"
            + n
            + "\t"
            + b(
            t.setComplexProperties(
              n == 3 ? "unknown" : "pIcTuRe", n == 4 ? List.of() : List.of(m))));
        tag("complex/state/" + n, t);
      }
    }
    class Virtual extends Tag {
      public String title() {
        return "virtual";
      }

      public String rating() {
        return "virtual rating";
      }
    }
    {
      var t = new Virtual();
      t.setTitle("stored");
      t.setRating("stored rating");
      tag("virtual/stored", t);
      System.out.println(
        "virtual/rejected\t"
          + props(
          t.replaceProperties(
            PropertyMap.builder()
              .put("TITLE", List.of("one", "two"))
              .put("RATING", "ignored")
              .build()).rejected()));
      tag("virtual/after", t);
    }
    class Trace extends Named {
      StringBuilder trace = new StringBuilder();
      AsfFile file;
      boolean callback;

      Trace(byte[] v) {
        super(v);
      }

      public void writeBlock(ByteVector v) {
        trace.append("write:").append(tell()).append(':').append(v.size()).append(';');
        super.writeBlock(v);
        if (callback) file.tag().setTitle("callback");
      }

      public void insert(ByteVector v, long start, long replace) {
        trace
          .append("insert:")
          .append(start)
          .append(':')
          .append(replace)
          .append(':')
          .append(v.size())
          .append(';');
        super.insert(v, start, replace);
      }

      public void truncate(long n) {
        trace.append("truncate:").append(n).append(';');
        super.truncate(n);
      }

      public void removeBlock(long start, long n) {
        trace.append("remove:").append(start).append(':').append(n).append(';');
        super.removeBlock(start, n);
      }
    }
    for (int m = 0; m < 2; m++) {
      var s = new Trace(Files.readAllBytes(root.resolve("inputs/plain.wma")));
      try (var f = new AsfFile(s, ReadOptions.TAGS_ONLY)) {
        s.file = f;
        s.callback = m == 1;
        f.tag().setTitle("title");
        for (int n = 0; n < 3; n++) {
          s.trace.setLength(0);
          boolean saved = SaveOutcome.save(f);
          System.out.println(
            "io/" + m + "/save/" + n + "\t" + b(saved) + "\t" + s.trace + "\t" + hex(s.data()));
          state("io/" + m + "/after/" + n, f);
          if (n == 0) f.tag().setTitle("x".repeat(5000));
          if (n == 1) f.tag().setTitle("short");
        }
      }
    }
    class Readonly extends Named {
      boolean ro;

      Readonly(byte[] v) {
        super(v);
      }

      public boolean readOnly() {
        return ro;
      }
    }
    {
      var s = new Readonly(Files.readAllBytes(root.resolve("inputs/plain.wma")));
      try (var f = new AsfFile(s)) {
        s.ro = true;
        System.out.println("readonly\t" + b(SaveOutcome.save(f)) + "\t" + f.tell() + "\t" + hex(s.data()));
      }
    }
    class Fail extends Named {
      Fail(byte[] v) {
        super(v);
      }

      public void writeBlock(ByteVector v) {
        throw new IllegalStateException("write failure");
      }
    }
    {
      var s = new Fail(Files.readAllBytes(root.resolve("inputs/plain.wma")));
      try (var f = new AsfFile(s)) {
        boolean failed = false;
        try {
          SaveOutcome.save(f);
        } catch (IllegalStateException failure) {
          failed = true;
        }
        System.out.println("io/failure\t" + b(failed) + "\t" + f.tell() + "\t" + hex(s.data()));
      }
    }
  }
}
