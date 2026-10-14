package cz.skyba.taglib.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cz.skyba.taglib.AudioMetadata;
import cz.skyba.taglib.TagLib;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;

/**
 * Stable cross-language representation; ordered property values and artwork are significant.
 */
public final class Snapshots {
  public static final ObjectMapper JSON = new ObjectMapper();

  private Snapshots() {
  }

  public static String upstreamVersion() {
    try {
      return cz.skyba.taglib.parity.support.Parity.upstream().getProperty("version");
    } catch (java.io.IOException e) {
      return TagLib.UPSTREAM_VERSION;
    }
  }

  public static java.nio.file.Path upstreamSource(java.nio.file.Path project) {
    try {
      return java.nio.file.Path.of(
        cz.skyba.taglib.parity.support.Parity.upstream().getProperty("source"));
    } catch (java.io.IOException e) {
      return project.resolve("work/upstream/verified/taglib-" + TagLib.UPSTREAM_VERSION);
    }
  }

  public static ObjectNode invalid() {
    return JSON.createObjectNode().put("valid", false);
  }

  public static long fnvValue(byte[] bytes) {
    long hash = 0xcbf29ce484222325L;
    for (byte b : bytes) hash = (hash ^ (b & 255L)) * 0x100000001b3L;
    return hash;
  }

  public static String fnv(byte[] bytes) {
    return HexFormat.of().toHexDigits(fnvValue(bytes));
  }

  public static long hash(String value) {
    return fnvValue(value.getBytes(StandardCharsets.UTF_8));
  }

  public static ObjectNode of(AudioMetadata file) {
    var root = JSON.createObjectNode().put("valid", true);
    root.set("properties", JSON.valueToTree(file.properties().asMap()));
    var tag = file.tag();
    root.putObject("common")
      .put("title", tag.title())
      .put("artist", tag.artist())
      .put("album", tag.album())
      .put("comment", tag.comment())
      .put("genre", tag.genre())
      .put("year", tag.year())
      .put("track", tag.track());
    var audio = file.audioProperties();
    root.putObject("audio")
      .put("lengthInMilliseconds", audio.lengthInMilliseconds())
      .put("bitrate", audio.bitrate())
      .put("sampleRate", audio.sampleRate())
      .put("channels", audio.channels());
    var pictures = root.putArray("pictures");
    for (var picture : file.pictures()) {
      var bytes = picture.data();
      pictures
        .addObject()
        .put("type", picture.type())
        .put("mimeType", picture.mimeType())
        .put("description", picture.description())
        .put("size", bytes.length)
        .put("fnv1a64", fnv(bytes))
        .put("width", picture.width())
        .put("height", picture.height())
        .put("colorDepth", picture.colorDepth())
        .put("indexedColors", picture.indexedColors());
    }
    return root;
  }

  /**
   * Same work as C++: consume all common tags, full property maps, audio fields, and artwork bytes.
   */
  public static long checksum(AudioMetadata file) {
    long sum = 1;
    for (var entry : file.properties().asMap().entrySet()) {
      sum += hash(entry.getKey());
      for (var value : entry.getValue()) sum += hash(value);
    }
    var t = file.tag();
    for (var s : List.of(t.title(), t.artist(), t.album(), t.comment(), t.genre())) sum += hash(s);
    var a = file.audioProperties();
    sum +=
      t.year()
        + (long) t.track()
        + a.lengthInMilliseconds()
        + a.bitrate()
        + a.sampleRate()
        + a.channels();
    for (var p : file.pictures()) {
      var bytes = p.data();
      sum +=
        bytes.length
          + (long) p.type()
          + p.width()
          + p.height()
          + p.colorDepth()
          + p.indexedColors();
      sum += hash(p.mimeType()) + hash(p.description()) + hash(fnv(bytes));
    }
    return sum;
  }

  public static List<String> differences(JsonNode expected, JsonNode actual) {
    var result = new ArrayList<String>();
    compare("$", expected, actual, result);
    return result;
  }

  private static void compare(
    String path, JsonNode expected, JsonNode actual, List<String> differences) {
    if (expected == null || actual == null) {
      if (expected != actual)
        differences.add(path + ": missing value; native=" + expected + ", java=" + actual);
    } else if (expected.isNumber() && actual.isNumber()) {
      if (expected.decimalValue().compareTo(actual.decimalValue()) != 0)
        differences.add(path + ": native=" + expected + ", java=" + actual);
    } else if (expected.isObject() && actual.isObject()) {
      var keys = new TreeSet<String>();
      expected.fieldNames().forEachRemaining(keys::add);
      actual.fieldNames().forEachRemaining(keys::add);
      for (var key : keys)
        compare(path + "." + key, expected.get(key), actual.get(key), differences);
    } else if (expected.isArray() && actual.isArray()) {
      if (expected.size() != actual.size())
        differences.add(
          path + ": array size native=" + expected.size() + ", java=" + actual.size());
      for (int i = 0; i < Math.min(expected.size(), actual.size()); i++)
        compare(path + "[" + i + "]", expected.get(i), actual.get(i), differences);
    } else if (!expected.equals(actual))
      differences.add(path + ": native=" + expected + ", java=" + actual);
  }
}
