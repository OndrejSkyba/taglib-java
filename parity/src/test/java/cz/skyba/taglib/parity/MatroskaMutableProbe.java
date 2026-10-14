package cz.skyba.taglib.parity;

import cz.skyba.taglib.parity.support.OpenOutcome;
import cz.skyba.taglib.parity.support.SaveOutcome;

import cz.skyba.taglib.ReadOptions;

import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.matroska.*;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.Variant;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class MatroskaMutableProbe {
  static String hx(String s) {
    return HexFormat.of().formatHex(s.getBytes(StandardCharsets.UTF_8));
  }

  static String hx(ByteVector v) {
    return HexFormat.of().formatHex(v.toByteArray());
  }

  static String b(boolean v) {
    return v ? "1" : "0";
  }

  static void row(String k, String v) {
    System.out.println(k + "\t" + v);
  }

  static String sl(List<String> v) {
    String s = "";
    for (var x : v) s += hx(x) + ",";
    return s;
  }

  static String props(PropertyMap p) {
    String s = "";
    for (var x : new TreeMap<>(p.asMap()).entrySet())
      s += hx(x.getKey()) + "=" + sl(x.getValue()) + ";";
    return s + "/" + sl(p.unsupportedData());
  }

  static String variant(Variant v) {
    String x =
      switch (v.type()) {
        case BOOL -> b(v.toBoolean());
        case INT -> Integer.toString(v.toInt());
        case UNSIGNED_INT -> Long.toString(v.toUnsignedInt());
        case LONG -> Long.toString(v.toLong());
        case UNSIGNED_LONG -> Long.toUnsignedString(v.toUnsignedLong());
        case STRING -> hx(v.toString());
        case BYTE_VECTOR -> hx(v.toByteVector());
        case VARIANT_LIST -> {
          String s = "[";
          for (var y : v.toList()) s += variant(y) + ",";
          yield s + "]";
        }
        case VARIANT_MAP -> {
          String s = "{";
          for (var y : new TreeMap<>(v.toMap()).entrySet())
            s += hx(y.getKey()) + "=" + variant(y.getValue()) + ",";
          yield s + "}";
        }
        default -> "unsupported";
      };
    return v.type().ordinal() + ":" + x;
  }

  static String st(SimpleTag t) {
    return hx(t.name())
      + ":"
      + t.type().ordinal()
      + ":"
      + hx(t.toString())
      + ":"
      + hx(t.toByteVector())
      + ":"
      + t.targetType()
      + ":"
      + hx(t.language())
      + ":"
      + b(t.defaultLanguageFlag())
      + ":"
      + Long.toUnsignedString(t.trackUid())
      + ":"
      + Long.toUnsignedString(t.editionUid())
      + ":"
      + Long.toUnsignedString(t.chapterUid())
      + ":"
      + Long.toUnsignedString(t.attachmentUid());
  }

  static String tag(Tag t) {
    String s = "";
    for (var v : t.simpleTagsList()) s += st(v) + ";";
    return s
      + "/"
      + props(t.properties())
      + "/"
      + hx(t.title())
      + ","
      + hx(t.artist())
      + ","
      + hx(t.album())
      + ","
      + hx(t.comment())
      + ","
      + hx(t.genre())
      + ","
      + t.year()
      + ","
      + t.track();
  }

  static String complex(FileBase f) {
    String s = "";
    for (var k : f.complexPropertyKeys()) {
      s += hx(k) + "=";
      for (var m : f.complexProperties(k)) s += variant(Variant.fromMap(m)) + ",";
      s += ";";
    }
    return s;
  }

  static String dump(MatroskaFile f) {
    return tag(f.tag()) + "/" + complex(f) + "/" + f.tell();
  }

  static PropertyMap propertyEdit() {
    var p = PropertyMap.builder();
    for (var k :
      List.of(
        "ALBUM",
        "ALBUMARTIST",
        "ALBUMARTISTSORT",
        "ALBUMSORT",
        "ARTIST",
        "ARTISTS",
        "ARTISTSORT",
        "ASIN",
        "BARCODE",
        "CATALOGNUMBER",
        "COMMENT",
        "DATE",
        "DISCNUMBER",
        "DISCTOTAL",
        "DJMIXER",
        "ENCODEDBY",
        "ENCODING",
        "ENCODINGTIME",
        "GENRE",
        "INITIALKEY",
        "ISRC",
        "LABEL",
        "MEDIA",
        "MUSICBRAINZ_ALBUMARTISTID",
        "MUSICBRAINZ_ALBUMID",
        "MUSICBRAINZ_ARTISTID",
        "MUSICBRAINZ_RELEASEGROUPID",
        "MUSICBRAINZ_RELEASETRACKID",
        "MUSICBRAINZ_TRACKID",
        "OWNER",
        "ORIGINALDATE",
        "RELEASECOUNTRY",
        "RELEASEDATE",
        "RELEASESTATUS",
        "RELEASETYPE",
        "REMIXER",
        "SCRIPT",
        "TAGGINGDATE",
        "TITLE",
        "TRACKNUMBER",
        "TRACKTOTAL",
        "BPM",
        "MOOD",
        "UNKNOWN"))
      p.put(k, List.of("First", "Second"));
    return p.build();
  }

  static void edit(MatroskaFile f, int mode) throws Exception {
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
        t.addSimpleTag(
          new SimpleTag(
            "BIN",
            new ByteVector(new byte[]{0, -1}),
            SimpleTag.TargetTypeValue.Collection,
            "jpn",
            false,
            1,
            2,
            3,
            4));
        t.insertSimpleTag(0, new SimpleTag("FIRST", "Value"));
        t.insertSimpleTag(0xffffffffL, new SimpleTag("LAST", "End"));
        t.removeSimpleTag(1);
        t.removeSimpleTag("MISSING", SimpleTag.TargetTypeValue.None);
      }
      case 2 -> f.replaceProperties(propertyEdit());
      case 3 -> {
        var c =
          new Chapter(
            123000,
            999000,
            List.of(new Chapter.Display("Chapter", "eng"), new Chapter.Display("", "jpn")),
            501,
            true);
        var ch = f.getOrCreateChapters();
        ch.addChapterEdition(new ChapterEdition(List.of(c), true, true, 500));
        ch.removeChapterEdition(-1);
      }
      case 4 -> {
        var a = f.getOrCreateAttachments();
        a.addAttachedFile(
          new AttachedFile(
            new byte[]{0, -1, 'D', 'O', 'C'}, "doc.bin", "application/bin", 601, "Doc"));
        a.addAttachedFile(
          new AttachedFile(new ByteVector("PNG"), "cover.png", "image/png", 602, "Front"));
        a.removeAttachedFile(-1);
      }
      case 5 -> {
        t.clearSimpleTags();
        if (f.chapters() != null) f.chapters().clear();
        if (f.attachments() != null) f.attachments().clear();
      }
      case 6 -> f.setComplexProperties(
        "picture",
        List.of(
          Map.of(
            "data",
            new Variant(
              new ByteVector(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10})),
            "uid",
            Variant.fromUnsignedLong(701),
            "description",
            new Variant("Picture"))));
      case 7 -> f.setComplexProperties(
        "BINARY",
        List.of(
          Map.of(
            "name",
            new Variant("BINARY"),
            "data",
            new Variant(new ByteVector(new byte[]{0, -1})),
            "targetTypeValue",
            Variant.fromUnsignedInt(50),
            "trackUid",
            Variant.fromUnsignedLong(42),
            "editionUid",
            Variant.fromUnsignedLong(43),
            "chapterUid",
            Variant.fromUnsignedLong(44),
            "attachmentUid",
            Variant.fromUnsignedLong(45),
            "language",
            new Variant("ces"),
            "defaultLanguage",
            new Variant(false))));
      case 8 -> {
        var d =
          Variant.fromMap(
            Map.of("string", new Variant("Chapter"), "language", new Variant("ces")));
        var c =
          Variant.fromMap(
            Map.of(
              "uid",
              Variant.fromUnsignedLong(802),
              "timeStart",
              Variant.fromUnsignedLong(123000),
              "timeEnd",
              Variant.fromUnsignedLong(999000),
              "isHidden",
              new Variant(true),
              "displays",
              Variant.fromList(List.of(d))));
        f.setComplexProperties(
          "chapters",
          List.of(
            Map.of(
              "uid",
              Variant.fromUnsignedLong(801),
              "isDefault",
              new Variant(true),
              "isOrdered",
              new Variant(true),
              "chapters",
              Variant.fromList(List.of(c)))));
      }
      case 9 -> t.setTitle("X".repeat(900));
      case 10 -> t.setTitle("s");
      case 11 -> {
      }
      case 12 -> {
        t.addSimpleTag(new SimpleTag("CUSTOM", "Value", SimpleTag.TargetTypeValue.Album));
        t.addSimpleTag(
          new SimpleTag("CUSTOM", "Scoped", SimpleTag.TargetTypeValue.None, "", true, 42));
        t.removeUnsupportedProperties(List.of("CUSTOM"));
      }
    }
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    List<Path> paths;
    try (var s = Files.list(root.resolve("inputs"))) {
      paths = s.sorted().toList();
    }
    for (var p : paths) {
      byte[] raw = Files.readAllBytes(p);
      String name = p.getFileName().toString();
      for (int style = 0; style < 3; style++)
        for (boolean audio : new boolean[]{false, true})
          for (int origin : new int[]{0, 7}) {
            byte[] data = new byte[origin + raw.length];
            Arrays.fill(data, 0, origin, (byte) 'p');
            System.arraycopy(raw, 0, data, origin, raw.length);
            var s = new ByteVectorStream(data);
            s.seek(origin);
            var options = new ReadOptions(audio, ReadStyle.values()[style], ReadOptions.DEFAULT.maxMetadataBytes());
            try (var f = OpenOutcome.file(() -> new MatroskaFile(s, options))) {
              String k = name + "/read/" + style + "/" + b(audio) + "/" + origin;
              if (f == null) {
                row(k + "/rejected", "1");
                continue;
              }
              row(
                k,
                b(f.isValid())
                  + "\t"
                  + f.tell()
                  + "\t"
                  + b(f.existingTag() != null)
                  + "\t"
                  + b(f.chapters() != null)
                  + "\t"
                  + b(f.audioProperties() != null));
              if (f.isValid()) row(k + "/state", dump(f));
            }
          }
      if (name.equals("read-large.mka")) continue;
      if (OpenOutcome.rejected(() -> new MatroskaFile(new ByteVectorStream(raw), ReadOptions.TAGS_ONLY))) continue;
      for (int style = 0; style < 3; style++)
        for (int mode = 0; mode < 13; mode++) {
          var s = new ByteVectorStream(raw);
          try (var f = new MatroskaFile(s, ReadOptions.TAGS_ONLY)) {
            String k = name + "/edit/" + style + "/" + mode;
            edit(f, mode);
            row(k + "/before", dump(f));
            for (int round = 0; round < 3; round++) {
              if (round == 1 && mode == 9) f.tag().setTitle("s");
              if (round == 2 && mode == 9) f.tag().setTitle("Y".repeat(1200));
              boolean saved = SaveOutcome.save(f, WriteStyle.values()[style]);
              row(k + "/save/" + round, b(saved) + "\t" + f.tell() + "\t" + hx(s.data()));
              row(k + "/live/" + round, dump(f));
            }
            s.seek(0);
            try (var reopened = OpenOutcome.file(() -> new MatroskaFile(s, ReadOptions.TAGS_ONLY))) {
              row(
                k + "/reopen",
                b(reopened != null) + "\t" + (reopened != null ? dump(reopened) : ""));
            }
            if (name.startsWith("upstream-") || name.equals("layout-before-0-1.mka"))
              Files.write(
                root.resolve("outputs").resolve(name + "-" + style + "-" + mode + ".java"),
                s.data().toByteArray());
          }
        }
    }
    for (int target : new int[]{0, 10, 20, 30, 40, 50, 60, 70, 999})
      for (int binary = 0; binary < 2; binary++)
        for (int scope = 0; scope < 5; scope++) {
          var t =
            new SimpleTag(
              "TITLE",
              binary == 1
                ? new SimpleTag.Binary(new byte[]{0, -1})
                : new SimpleTag.Text("Value"),
              target,
              "ces",
              false,
              scope == 1 ? 1 : 0,
              scope == 2 ? 2 : 0,
              scope == 3 ? 3 : 0,
              scope == 4 ? 4 : 0);
          var tag = new Tag();
          tag.addSimpleTag(t);
          String k = "value/" + target + "/" + binary + "/" + scope;
          row(k, st(t) + "\t" + tag(tag));
          var copy = new SimpleTag(t);
          row(k + "/copy", st(t) + "/" + st(copy));
        }
  }
}
