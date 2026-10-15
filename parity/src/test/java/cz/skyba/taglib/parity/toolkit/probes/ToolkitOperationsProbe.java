package cz.skyba.taglib.parity.toolkit.probes;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.internal.TextBytes;
import cz.skyba.taglib.internal.TextNumbers;
import cz.skyba.taglib.parity.TextCharsets;
import cz.skyba.taglib.toolkit.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_16;
import static java.nio.charset.StandardCharsets.UTF_8;

public class ToolkitOperationsProbe {
  static void report(IOStream s, String label, int step) {
    long pos = s.tell();
    s.seek(0);
    var bytes = s.readBlock(s.length());
    s.seek(pos);
    System.out.println(label + step + "=" + pos + ":" + s.length() + ":" + bytes.toHex());
  }

  static void streamCases(IOStream s, String label) {
    report(s, label, 0);
    s.insert(new ByteVector("xx"), 1, 1);
    report(s, label, 1);
    s.insert(new ByteVector("y"), 1, 2);
    report(s, label, 2);
    s.removeBlock(1, 1);
    report(s, label, 3);
    s.seek(7);
    s.writeBlock(new ByteVector("!"));
    report(s, label, 4);
    s.truncate(2);
    report(s, label, 5);
    s.seek(-1, IOStream.Position.END);
    report(s, label, 6);
  }

  static int b(boolean value) {
    return value ? 1 : 0;
  }

  public static void main(String[] args) throws Exception {
    for (int offset = 0; offset <= 3; offset++)
      for (int length = 0; length <= 3 - offset; length++)
        System.out.println(
          "slice="
            + offset
            + ","
            + length
            + ":"
            + new ByteVector(new ByteVector("abc"), offset, length).toHex());
    var defaultList =
      List.of(new ByteVector("a"), new ByteVector(), new ByteVector("b"));
    System.out.println("default-join=" + ByteVector.join(defaultList).toHex());
    var aligned = new ByteVector("a::b:").split(new ByteVector(":"), 1);
    System.out.println("default-max=" + aligned.size() + ":" + ByteVector.join(aligned).toHex());
    String[] texts = {"", "a", "abcab", "OggS*OggS*OggS", "....SggO."};
    String[] patterns = {"", "a", "ab", "OggS", "SggO"};
    for (int t = 0; t < 5; t++)
      for (int p = 0; p < 5; p++)
        for (int off = 0; off < texts[t].length() + 3; off++)
          for (int align = 0; align < 4; align++) {
            var v = new ByteVector(texts[t]);
            var pat = new ByteVector(patterns[p]);
            System.out.println(
              "search="
                + t
                + ","
                + p
                + ","
                + off
                + ","
                + align
                + ":"
                + v.find(pat, off, align)
                + ":"
                + v.rfind(pat, off, align));
          }
    var numbers =
      new ByteVector(new byte[]{0, (byte) 255, (byte) 128, 1, (byte) 254, 127, (byte) 129, 17});
    for (int off = 0; off < 10; off++)
      for (int width = 0; width < 6; width++)
        for (boolean big : new boolean[]{false, true})
          System.out.println(
            "number=" + off + "," + width + "," + b(big) + ":" + numbers.toUnsignedInt(off, width, big));
    for (String value :
      List.of("aA==", "aA=X", "aA==x", "aGVsbG8=", "aGVsbG8", "", "!!!!", "YWJj", "YWJj\n"))
      System.out.println(
        "base64="
          + new ByteVector(value).toHex()
          + ":"
          + ByteVector.fromBase64(new ByteVector(value)).toHex());
    for (int max = 0; max < 6; max++) {
      var split = new ByteVector("a::b:").split(new ByteVector(":"), 1, max);
      System.out.println(
        "split="
          + max
          + ":"
          + split.size()
          + ":"
          + ByteVector.join(split, new ByteVector("|")).toHex());
    }
    for (int replace : new int[]{2, 3}) {
      var beyond = new ByteVectorStream(new ByteVector("abc"));
      beyond.insert(new ByteVector("XY"), 5, replace);
      System.out.println("memory-beyond=" + beyond.data().toHex() + ":" + beyond.tell());
    }
    streamCases(new ByteVectorStream(new ByteVector("abcd")), "memory");
    var path = Path.of(args[0]);
    Files.writeString(path, "abcd");
    try (var file = new FileStream(path)) {
      streamCases(file, "file");
    }
    var values =
      List.of(
        new Variant(),
        new Variant(true),
        new Variant(-4),
        Variant.fromUnsignedInt(5),
        new Variant(-6L),
        Variant.fromUnsignedLong(7),
        new Variant(1.23),
        new Variant("te\"st"),
        Variant.fromStringList(List.of("a", "b")),
        new Variant(new ByteVector(new byte[]{1, 0, (byte) 255})),
        Variant.fromByteVectorList(List.of(new ByteVector("ab"), new ByteVector("cd"))),
        Variant.fromList(List.of(new Variant("nested"), new Variant(5))),
        Variant.fromMap(Map.of("b", new Variant(true), "a", new Variant(1))));
    for (var v : values)
      System.out.println(
        "variant="
          + v.type().ordinal()
          + ":"
          + b(v.isEmpty())
          + ":"
          + v.toInt()
          + ":"
          + b(v.extractInt().ok())
          + ":"
          + v.render());
    for (double d :
      new double[]{
        0.0,
        -0.0,
        1.2345678,
        1000000.0,
        0.00000123,
        123.450,
        1.234565,
        2.345675,
        0.00001234565,
        -1.234565,
        1.234555,
        999999.5,
        Double.NaN,
        Double.longBitsToDouble(0xfff8000000000000L),
        Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY,
        Double.MIN_VALUE,
        Double.MAX_VALUE
      })
      System.out.println("double=" + new Variant(d).render());
    var props =
      PropertyMap.builder()
        .put("title", List.of("one", "two"))
        .put("empty", List.<String>of())
        .addUnsupportedData("raw");
    System.out.println(
      "property_text="
        + new ByteVector(
        props.build().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .toHex());
    ByteVector[] encodings = {
      new ByteVector("abc"),
      new ByteVector(new byte[]{97, 0, 98}),
      new ByteVector(new byte[]{(byte) 0xc0, (byte) 0x80}),
      new ByteVector(new byte[]{(byte) 255, (byte) 254, 65, 0}),
      new ByteVector(new byte[]{(byte) 254, (byte) 255, 0, 65}),
      new ByteVector(
        new byte[]{
          (byte) 0xc3, (byte) 0xa9, (byte) 0xf0, (byte) 0x9f, (byte) 0x98, (byte) 0x80
        }),
      new ByteVector(new byte[]{0, (byte) 0xd8})
    };
    for (int i = 0; i < 7; i++)
      for (int type = 0; type < 5; type++) {
        var text = TextBytes.decodeTerminated(encodings[i].data(), TextCharsets.ALL[type]);
        System.out.println(
          "string="
            + i
            + ","
            + type
            + ":"
            + text.length()
            + ":"
            + ByteVector.fromText(text, UTF_8).toHex()
            + ":"
            + ByteVector.fromText(text, UTF_16).toHex());
      }
    for (String input :
      List.of(
        "",
        "0",
        "-1",
        " +12",
        "12 ",
        "12foo",
        "2147483647",
        "2147483648",
        "-2147483648",
        "9223372036854775808",
        "-9223372036854775809",
        "18446744073709551615",
        "-18446744073709551615",
        "18446744073709551616",
        "010",
        "0xff",
        "0x",
        "0b10",
        "xyz")) {
      String text = input;
      var iv = TextNumbers.extractInt(text);
      var row = new StringBuilder("integer=" + input + ":" + iv.value() + ":" + b(iv.ok()));
      for (int base : new int[]{0, 10, 16, 36}) {
        var n = TextNumbers.extractLong(text, base);
        var u = TextNumbers.extractUnsignedLong(text, base);
        row.append(
          ":"
            + n.value()
            + ":"
            + b(n.ok())
            + ":"
            + Long.toUnsignedString(u.value())
            + ":"
            + b(u.ok()));
      }
      System.out.println(row);
    }
    var v = new VersionNumber(257, 258, 259);
    System.out.println("version=" + v + ":" + v.combinedVersion());
  }
}
