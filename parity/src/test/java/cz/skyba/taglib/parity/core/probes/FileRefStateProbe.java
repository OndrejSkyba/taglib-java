package cz.skyba.taglib.parity.core.probes;

import cz.skyba.taglib.parity.support.FileRefAdapter;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.TagString;
import cz.skyba.taglib.toolkit.Variant;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

class FileRefStateProbe {
  static class Memory extends ByteVectorStream {
    final String name;
    boolean locked;

    Memory(byte[] bytes, String name) {
      super(bytes);
      this.name = name;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public boolean readOnly() {
      return locked;
    }
  }

  static String hex(String value) {
    return new TagString(value).data(TagString.Type.UTF8).toHex().toString();
  }

  static void dump(String label, FileRefAdapter file) {
    var out = new StringBuilder(label + "|" + (file.isNull() ? 1 : 0) + "|");
    if (file.isNull()) {
      System.out.println(out);
      return;
    }
    var properties = file.properties();
    properties
      .asMap()
      .forEach(
        (key, values) -> {
          out.append(hex(key)).append('=');
          values.forEach(value -> out.append(hex(value)).append(','));
          out.append(';');
        });
    out.append('/');
    properties.unsupportedData().forEach(value -> out.append(hex(value)).append(','));
    var tag = file.tag();
    out.append('/')
      .append(hex(tag.title()))
      .append(',')
      .append(hex(tag.artist()))
      .append(',')
      .append(hex(tag.album()))
      .append(',')
      .append(hex(tag.comment()))
      .append(',')
      .append(hex(tag.genre()))
      .append(',')
      .append(tag.year())
      .append(',')
      .append(tag.track())
      .append(',')
      .append(tag.isEmpty() ? 1 : 0);
    out.append('/');
    file.complexPropertyKeys().forEach(key -> out.append(hex(key)).append(','));
    out.append('/');
    for (var picture : file.complexProperties("PICTURE")) {
      new TreeMap<>(picture)
        .forEach(
          (key, value) -> {
            out.append(hex(key)).append('=');
            out.append(
                key.equals("data")
                  ? value.toByteVector().toHex()
                  : key.equals("uid")
                  ? Long.toUnsignedString(value.toUnsignedLong())
                  : Set.of("width", "height", "colorDepth", "numColors").contains(key)
                  ? Integer.toString(value.toInt())
                  : hex(value.toText()))
              .append(',');
          });
      out.append(';');
    }
    System.out.println(out);
  }

  public static void main(String[] args) throws Exception {
    var stream = new Memory(Files.readAllBytes(Path.of(args[0])), args[0]);
    try (var file = new FileRefAdapter(stream, false)) {
      dump("initial", file);
      String scenario = args[1];
      switch (scenario) {
        case "empty-common" -> file.replaceProperties(PropertyMap.EMPTY);
        case "date" -> file.replaceProperties(file.properties());
        case "empty-frame" -> file.replaceProperties(
          PropertyMap.builder()
            .put("ARTIST", List.of())
            .put("CUSTOM", List.of())
            .put("COMMENT", List.of("first", "second"))
            .build());
        case "picture" -> System.out.println(
          "picture-supported|"
            + (file.setComplexProperties(
            "PICTURE",
            List.of(
              Map.of(
                "data",
                new Variant(
                  new ByteVector(
                    new byte[]{
                      (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10
                    })),
                "description",
                new Variant("cover"),
                "fileName",
                new Variant("given.bin"),
                "uid",
                Variant.fromUnsignedLong(77))))
            ? 1
            : 0));
      }
      if (scenario.equals("common")) {
        file.tag().setTitle("title");
        file.tag().setAlbum("album");
        file.tag().setGenre("Jazz");
        file.tag().setYear(4294967295L);
        file.tag().setTrack(2147483648L);
      } else if (scenario.equals("asf-nul-common")) {
        file.tag().setAlbum("A\0B");
        file.tag().setGenre("G\0H");
      } else if (scenario.equals("asf-nul-properties"))
        file.replaceProperties(PropertyMap.builder().put("ALBUM", "A\0B").put("GENRE", "G\0H").build());
      else if (scenario.equals("properties"))
        file.replaceProperties(
          PropertyMap.builder()
            .put("TITLE", List.of("one", "two"))
            .put("ALBUM", List.of("mapped", "duplicate"))
            .put("DATE", "2026-10")
            .put("PEAKVALUE", "4294967295")
            .put("ISVBR", "1")
            .build());
      else if (scenario.equals("remove")) file.removeUnsupportedProperties(List.of("Opaque"));
      dump("staged", file);
      stream.locked = true;
      System.out.println("blocked-save|" + (file.save() ? 1 : 0));
      dump("failed", file);
      stream.locked = false;
      System.out.println("first-save|" + (file.save() ? 1 : 0));
      dump("first", file);
      System.out.println("second-save|" + (file.save() ? 1 : 0));
      dump("second", file);
      if (scenario.equals("empty-common")) {
        file.tag().setTitle("changed");
        file.tag().setYear(4294967295L);
        file.tag().setTrack(4294967295L);
        dump("common", file);
        System.out.println("common-save|" + (file.save() ? 1 : 0) + "|" + (file.save() ? 1 : 0));
        dump("common-after", file);
      }
      if (args.length > 2) Files.write(Path.of(args[2]), stream.data().toByteArray());
      try (var reopened = new FileRefAdapter(new Memory(stream.data().toByteArray(), args[0]), false)) {
        dump("reopened", reopened);
      }
    }
  }
}
