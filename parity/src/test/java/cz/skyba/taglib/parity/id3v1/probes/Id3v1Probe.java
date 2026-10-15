package cz.skyba.taglib.parity.id3v1.probes;

import cz.skyba.taglib.*;
import cz.skyba.taglib.id3.v1.Genres;
import cz.skyba.taglib.id3.v1.StringHandler;
import cz.skyba.taglib.id3.v1.Tag;
import cz.skyba.taglib.internal.Ascii;
import cz.skyba.taglib.internal.TextBytes;
import cz.skyba.taglib.parity.support.FileRefAdapter;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.IOStream;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

class Id3v1Probe {
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
        (k, vs) -> {
          o.append(text(k)).append('=');
          for (var v : vs) o.append(text(v)).append(',');
          o.append(';');
        });
    return o.toString();
  }

  static void common(String n, TagBase t) {
    System.out.print(n + "\t" + (t.isEmpty() ? 1 : 0) + "\t" + t.year() + "\t" + t.track() + "\t");
    for (String v : List.of(t.title(), t.artist(), t.album(), t.comment(), t.genre()))
      System.out.print(text(v) + ";");
    System.out.print("\t" + props(t.properties()));
  }

  static void state(String n, Tag t) {
    state(n, t, -1);
  }

  static void state(String n, Tag t, long cursor) {
    common(n, t);
    System.out.println("\t" + t.genreNumber() + "\t" + hex(t.render()) + "\t" + cursor);
  }

  static class MemoryFile extends FileBase {
    MemoryFile(IOStream s) {
      super(s);
    }

    void invalidate() {
      setValid(false);
    }

    public TagBase tag() {
      return null;
    }

    public AudioProperties audioProperties() {
      return null;
    }

    public void save() {
      throw new cz.skyba.taglib.TagLibException("Test save rejected");
    }
  }

  static int parsed, rendered;
  static boolean counting;
  static StringHandler utf8 =
    new StringHandler() {
      public String parse(ByteVector v) {
        parsed++;
        String result = Ascii.stripWhiteSpace(TextBytes.decodeTerminated(v.data(), UTF_8));
        return counting ? result + "!" + parsed : result;
      }

      public ByteVector render(String s) {
        rendered++;
        return ByteVector.fromText(counting ? s + "~" + rendered : s, UTF_8);
      }
    };

  static PropertyMap input() {
    return PropertyMap.builder()
      .put("TITLE", List.of("first", "second"))
      .put("DATE", "oops")
      .put("TRACKNUMBER", "-1")
      .put("CUSTOM", "value")
      .build();
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 3) {
      counting = args[2].equals("counting");
      Tag.setStringHandler(utf8);
      try (var f = new FileRefAdapter(Path.of(args[0]), false)) {
        if (f.isNull()) throw new AssertionError("null");
        System.out.println("handler-open\t" + parsed + "\t" + rendered);
        common("opened", f.tag());
        System.out.println();
        if (args[1].equals("common")) {
          f.tag().setTitle("日本語");
          f.tag().setComment("Žluť");
          f.tag().setYear(0xffff_ffffL);
        } else if (args[1].equals("import"))
          System.out.println("rejected\t" + props(f.replaceProperties(input()).rejected()));
        common("before", f.tag());
        System.out.println();
        System.out.println("saved\t" + (f.save() ? 1 : 0));
        System.out.println("handler-save\t" + parsed + "\t" + rendered);
        f.close();
        try (var reopened = new FileRefAdapter(Path.of(args[0]), false)) {
          if (reopened.isNull()) throw new AssertionError("null reopened");
          common("reopened", reopened.tag());
          System.out.println();
        }
      } finally {
        Tag.setStringHandler(null);
      }
      return;
    }
    var t = new Tag();
    state("empty", t);
    System.out.println("identifier\t" + hex(Tag.fileIdentifier()));
    t.setTitle("Title");
    t.setArtist("Artist     ");
    t.setAlbum("Album");
    t.setComment("Comment");
    t.setGenre("Hardcore");
    t.setYear(2026);
    t.setTrack(7);
    state("common", t);
    for (long v : new long[]{0, 255, 256, 2147483648L, 4294967295L}) {
      t.setYear(v);
      t.setTrack(v);
      t.setGenreNumber(v);
      state("number-" + v, t);
    }
    t.setTitle("日本語");
    t.setArtist("b".repeat(30) + "日本語");
    t.setComment("c".repeat(31));
    state("truncated-unrepresentable", t);
    System.out.println("rejected\t" + props(t.replaceProperties(input()).rejected()));
    state("properties", t);
    var list = Genres.genreList();
    for (int i = 0; i < list.size(); i++)
      System.out.println(
        "genre\t" + i + "\t" + text(list.get(i)) + "\t" + Genres.genreIndex(list.get(i)));
    Genres.genreMap().forEach((n, i) -> System.out.println("genre-map\t" + text(n) + "\t" + i));
    for (String n :
      List.of(
        "Jazz+Funk",
        "Folk/Rock",
        "Bebob",
        "Avantgarde",
        "Dance Hall",
        "Hardcore",
        "BritPop",
        "Negerpunk",
        "hardcore",
        "unknown"))
      System.out.println("alias\t" + text(n) + "\t" + Genres.genreIndex(n));
    for (int i : new int[]{-1, 0, 191, 192, 255})
      System.out.println("genre-index\t" + i + "\t" + text(Genres.genre(i)));
    try (var paths = Files.list(Path.of(args[0], "inputs"))) {
      for (Path path : paths.sorted().toList())
        for (boolean custom : List.of(false, true)) {
          Tag.setStringHandler(custom ? utf8 : null);
          var b = new ByteVector(7, (byte) '!');
          b.append(new ByteVector(Files.readAllBytes(path)));
          var stream = new ByteVectorStream(b);
          var file = new MemoryFile(stream);
          state(
            path.getFileName() + (custom ? "-utf8" : "-latin1"), new Tag(file, 7), stream.tell());
        }
    }
    Tag.setStringHandler(null);
    var stream = new ByteVectorStream(new byte[128]);
    var file = new MemoryFile(stream);
    stream.seek(4);
    file.invalidate();
    state("invalid-file", new Tag(file, 7), stream.tell());
    state("null-file", new Tag((FileBase) null, 7));
  }
}
