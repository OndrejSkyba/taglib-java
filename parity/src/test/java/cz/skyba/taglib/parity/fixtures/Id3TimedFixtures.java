package cz.skyba.taglib.parity.fixtures;

import java.nio.file.Path;

import static cz.skyba.taglib.parity.fixtures.Bytes.*;
import static cz.skyba.taglib.parity.fixtures.CaseTable.utf8Field;
import static cz.skyba.taglib.parity.fixtures.TagBytes.*;

final class Id3TimedFixtures {
  private static byte[] wire(String k, byte[] b, int v, long n) {
    return frame(k.equals("E") ? (v < 3 ? "ETC" : "ETCO") : (v < 3 ? "SLT" : "SYLT"), b, v, n, 0);
  }

  private static byte[] wire(String k, byte[] b) {
    return wire(k, b, 4, b.length);
  }

  private static byte[] body(String k, int e) {
    return k.equals("E")
      ? hex("020201020304ffffffffff")
      : join(
      new byte[]{(byte) e},
      hex("656e670201"),
      encoded("desc", e),
      delimiter(e),
      e == 1 ? encoded("one", 4) : encoded("one", e),
      delimiter(e),
      hex("01020304"),
      encoded("two", e),
      delimiter(e),
      hex("ffffffff"));
  }

  private static String entries(String k) {
    return k.equals("E")
      ? "4294967299:255;18446744073709551615:2"
      : "4294967299:" + utf8Field("one") + ";18446744073709551615:" + utf8Field("two");
  }

  static void generate(Path root) throws Exception {
    var t =
      new CaseTable(
        root,
        "kind",
        "",
        "mode",
        "P",
        "version",
        4,
        "profile",
        0,
        "initial",
        new byte[0],
        "reparse",
        new byte[0],
        "encoding",
        0,
        "format",
        2,
        "type",
        1,
        "description",
        utf8Field("description"),
        "language",
        text("eng"),
        "entries",
        "-");
    var r = new FixtureRandom(232007);
    for (String k : new String[]{"E", "S"}) {
      byte[] base = body(k, 0), w = wire(k, base);
      String entries = entries(k);
      for (int e = 0; e < 5; e++)
        for (long v : new long[]{2, 3, 4, 5, 0xffffffffL})
          for (int p = 0; p < 2; p++)
            t.add(
              "default",
              "kind",
              k,
              "mode",
              "B",
              "version",
              v,
              "profile",
              p,
              "reparse",
              w,
              "encoding",
              e,
              "entries",
              entries);
      for (int n = 0; n <= base.length; n++)
        for (int p = 0; p < 2; p++) {
          byte[] b = wire(k, slice(base, 0, n));
          t.add("partial", "kind", k, "initial", w, "reparse", b, "profile", p, "entries", entries);
          t.add(
            "partial-ctor",
            "kind",
            k,
            "initial",
            b,
            "reparse",
            w,
            "profile",
            p,
            "entries",
            entries);
        }
      for (int v : new int[]{2, 3, 4})
        for (int e = 0; e < 5; e++)
          for (int p = 0; p < 2; p++) {
            byte[] b = body(k, e);
            t.add(
              "provided",
              "kind",
              k,
              "initial",
              wire(k, b, v, b.length),
              "reparse",
              w,
              "mode",
              "F",
              "version",
              v,
              "profile",
              p,
              "encoding",
              e,
              "entries",
              entries);
          }
      for (int n : new int[]{0, 1, 2, 5, 6, 7, 10, 17, 128, 65535}) {
        byte[] b = wire(k, base, 4, n);
        t.add("declared", "kind", k, "initial", b, "reparse", b, "entries", entries);
      }
      for (int i = 0; i < 240; i++) {
        byte[] a = r.bytes(r.below(80)), b = r.bytes(r.below(80));
        t.add(
          "random",
          "kind",
          k,
          "initial",
          wire(k, a),
          "reparse",
          wire(k, b),
          "profile",
          i % 2,
          "encoding",
          i % 5,
          "format",
          i % 3,
          "type",
          i % 9,
          "entries",
          entries);
      }
    }
    for (int n = 0; n < 256; n++)
      for (int p = 0; p < 2; p++) {
        for (int field : new int[]{0, 4, 5}) {
          byte[] b = body("S", 0);
          b[field] = (byte) n;
          t.add(
            "lyric-byte",
            "kind",
            "S",
            "initial",
            wire("S", b),
            "reparse",
            wire("S", b),
            "profile",
            p,
            "encoding",
            n % 5,
            "format",
            n % 3,
            "type",
            n % 9,
            "entries",
            entries("S"));
        }
        byte[] b = body("E", 0);
        b[0] = (byte) n;
        t.add(
          "event-format",
          "kind",
          "E",
          "initial",
          wire("E", b),
          "reparse",
          wire("E", b),
          "profile",
          p,
          "entries",
          entries("E"));
        b = join(new byte[]{2, (byte) n}, hex("ffffffff"));
        t.add(
          "event-type",
          "kind",
          "E",
          "initial",
          wire("E", b),
          "reparse",
          wire("E", b),
          "profile",
          p,
          "entries",
          "4294967295:" + n);
      }
    String[] descriptions = {"", "a\0b", "雪😀", "d"};
    String[] entryValues = {
      "-",
      "0:" + utf8Field("c\0d"),
      "0:" + utf8Field("a") + ";4294967295:" + utf8Field("Ž😀"),
      "0:" + utf8Field("雪")
    };
    byte[] w = wire("S", body("S", 0));
    for (int e = 0; e < 5; e++)
      for (int v : new int[]{3, 4})
        for (int p = 0; p < 2; p++)
          for (int i = 0; i < descriptions.length; i++)
            t.add(
              "setter",
              "kind",
              "S",
              "initial",
              w,
              "reparse",
              w,
              "version",
              v,
              "profile",
              p,
              "encoding",
              e,
              "description",
              utf8Field(descriptions[i]),
              "language",
              text("abcdef"),
              "entries",
              entryValues[i]);
    for (byte[] lang :
      new byte[][]{hex(""), text("a"), text("ab"), text("abc"), text("abcdef"), hex("00ff00")})
      for (int f = 0; f < 3; f++)
        for (int type = 0; type < 9; type++)
          t.add(
            "language-type",
            "kind",
            "S",
            "initial",
            w,
            "reparse",
            w,
            "language",
            lang,
            "format",
            f,
            "type",
            type,
            "entries",
            entries("S"));
    for (boolean little : new boolean[]{true, false})
      for (String desc : new String[]{"", "d"})
        for (int own = 0; own < 2; own++) {
          byte[] bom = hex(little ? "fffe" : "feff");
          byte[] b =
            join(
              hex("01656e670201"),
              bom,
              encoded(desc, little ? 4 : 2),
              new byte[2],
              own == 1 ? bom : new byte[0],
              encoded("a", little ? 4 : 2),
              hex("000001020304"));
          for (int n = 0; n <= b.length; n++)
            t.add(
              "bom",
              "kind",
              "S",
              "initial",
              wire("S", b),
              "reparse",
              wire("S", slice(b, 0, n)),
              "entries",
              entries("S"));
        }
    for (byte[] b :
      new byte[][]{
        hex("00656e67020161626364"),
        hex("00656e6702010061626364"),
        hex("00656e67020100616263"),
        hex("00656e670201640061000102030462000102"),
        hex("01656e67020100006100000001020304")
      })
      for (int p = 0; p < 2; p++)
        t.add(
          "delimiter",
          "kind",
          "S",
          "initial",
          wire("S", b),
          "reparse",
          wire("S", b),
          "profile",
          p,
          "entries",
          entries("S"));
    t.finish();
  }
}
