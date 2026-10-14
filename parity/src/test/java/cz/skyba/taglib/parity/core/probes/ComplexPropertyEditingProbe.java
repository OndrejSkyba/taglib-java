package cz.skyba.taglib.parity.core.probes;

import cz.skyba.taglib.parity.support.FileRefAdapter;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.Variant;

import java.nio.file.Path;
import java.util.*;

class ComplexPropertyEditingProbe {
  static String hex(String s) {
    return HexFormat.of().formatHex(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  static String variant(Variant v) {
    String value =
      switch (v.type()) {
        case BOOL -> v.toBoolean() ? "1" : "0";
        case INT -> Integer.toString(v.toInt());
        case UNSIGNED_INT -> Long.toString(v.toUnsignedInt());
        case LONG -> Long.toString(v.toLong());
        case UNSIGNED_LONG -> Long.toUnsignedString(v.toUnsignedLong());
        case STRING -> hex(v.toText());
        case BYTE_VECTOR -> HexFormat.of().formatHex(v.toByteVector().toByteArray());
        case VARIANT_LIST -> "["
          + v.toList().stream()
          .map(x -> variant(x) + ",")
          .collect(java.util.stream.Collectors.joining())
          + "]";
        case VARIANT_MAP -> "{"
          + new TreeMap<>(v.toMap())
          .entrySet().stream()
          .map(e -> hex(e.getKey()) + "=" + variant(e.getValue()) + ",")
          .collect(java.util.stream.Collectors.joining())
          + "}";
        default -> "unsupported";
      };
    return v.type().ordinal() + ":" + value;
  }

  static void dump(FileRefAdapter f) {
    System.out.println("null=" + (f.isNull() ? 1 : 0));
    if (f.isNull()) return;
    for (String key : f.complexPropertyKeys()) {
      System.out.println("KEY\t" + hex(key));
      for (var map : f.complexProperties(key))
        System.out.println("VALUE\t" + variant(Variant.fromMap(map)));
    }
    f.properties()
      .asMap()
      .forEach(
        (key, values) ->
          System.out.println(
            "P\t"
              + hex(key)
              + values.stream()
              .map(v -> "\t" + hex(v))
              .collect(java.util.stream.Collectors.joining())));
    for (String v : f.properties().unsupportedData()) System.out.println("U\t" + hex(v));
  }

  public static void main(String[] args) {
    String scenario = args[1], key;
    var values = new ArrayList<Map<String, Variant>>();
    var v = new TreeMap<String, Variant>();
    var data = new Variant(new ByteVector(new byte[]{0, 1, 2, 3}));
    switch (scenario) {
      case "general", "general-clear" -> {
        key = "GENERALOBJECT";
        if (scenario.equals("general")) {
          v.put("data", data);
          v.put("mimeType", new Variant("application/octet-stream"));
          v.put("description", new Variant("Object Ž"));
          v.put("uid", Variant.fromUnsignedLong(77));
          v.put("fileName", new Variant("obj.bin"));
          values.add(v);
        }
      }
      case "scoped", "scoped-clear" -> {
        key = "BINARY";
        if (scenario.equals("scoped")) {
          v.put("name", new Variant("BINARY"));
          v.put("data", data);
          v.put("targetTypeValue", Variant.fromUnsignedInt(30));
          v.put("trackUid", Variant.fromUnsignedLong(42));
          v.put("language", new Variant("jpn"));
          v.put("defaultLanguage", new Variant(false));
          values.add(v);
        }
      }
      case "chapters" -> {
        key = "CHAPTERS";
        var display =
          Variant.fromMap(
            Map.of("string", new Variant("Chapter Ž"), "language", new Variant("ces")));
        var chapter =
          Variant.fromMap(
            Map.of(
              "uid",
              Variant.fromUnsignedLong(88),
              "timeStart",
              Variant.fromUnsignedLong(123000),
              "timeEnd",
              Variant.fromUnsignedLong(999000),
              "displays",
              Variant.fromList(List.of(display))));
        v.put("uid", Variant.fromUnsignedLong(99));
        v.put("isDefault", new Variant(true));
        v.put("isOrdered", new Variant(false));
        v.put("chapters", Variant.fromList(List.of(chapter)));
        values.add(v);
      }
      case "mime", "filename", "uid" -> {
        key =
          scenario.equals("mime")
            ? "application/pdf"
            : scenario.equals("filename") ? "manual.bin" : "77";
        v.put("data", data);
        v.put("description", new Variant("document"));
        if (!scenario.equals("mime")) v.put("mimeType", new Variant("application/octet-stream"));
        if (!scenario.equals("filename")) v.put("fileName", new Variant("doc.bin"));
        if (!scenario.equals("uid")) v.put("uid", Variant.fromUnsignedLong(77));
        values.add(v);
      }
      case "stem", "stem-clear" -> {
        key = "STEM";
        if (scenario.equals("stem")) {
          v.put(
            "manifest",
            new Variant(
              new ByteVector(
                "{\"version\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
          values.add(v);
        }
      }
      case "unknown" -> {
        key = "UNKNOWN";
        values.add(v);
      }
      default -> throw new IllegalArgumentException(scenario);
    }
    try (var f = new FileRefAdapter(Path.of(args[0]), false)) {
      dump(f);
      if (f.isNull()) return;
      System.out.println("accepted=" + (f.setComplexProperties(key, values) ? 1 : 0));
      dump(f);
      for (int i = 0; i < 2; i++) {
        System.out.println("saved=" + (f.save() ? 1 : 0));
        dump(f);
      }
    }
    System.out.println("reopened");
    try (var f = new FileRefAdapter(Path.of(args[0]), false)) {
      dump(f);
    }
  }
}
