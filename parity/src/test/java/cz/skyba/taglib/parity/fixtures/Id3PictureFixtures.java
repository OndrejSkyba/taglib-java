package cz.skyba.taglib.parity.fixtures;

import java.nio.file.Path;

import static cz.skyba.taglib.parity.fixtures.Bytes.*;
import static cz.skyba.taglib.parity.fixtures.CaseTable.utf8Field;
import static cz.skyba.taglib.parity.fixtures.TagBytes.*;

final class Id3PictureFixtures {
  private static byte[] wire(String k, byte[] b, int v, long size) {
    return frame(
      k.equals("V") ? "PIC" : k.equals("O") ? (v < 3 ? "GEO" : "GEOB") : "APIC", b, v, size, 0);
  }

  private static byte[] wire(String k, byte[] b) {
    return wire(k, b, 4, b.length);
  }

  private static byte[] body(String k, int e, int type) {
    return join(
      new byte[]{(byte) e},
      k.equals("V")
        ? text("PNG")
        : text(k.equals("A") ? "image/png\0" : "application/octet-stream\0"),
      k.equals("O") ? join(encoded("name", e), delimiter(e)) : new byte[]{(byte) type},
      encoded("desc", e),
      delimiter(e),
      hex("00ff016461746100"));
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
        "mime",
        utf8Field("image/png"),
        "name",
        utf8Field("name"),
        "description",
        utf8Field("description"),
        "payload",
        hex("00ff7061796c6f616400"),
        "type",
        3);
    var r = new FixtureRandom(232005);
    for (String k : new String[]{"A", "O"}) {
      byte[] base = body(k, 0, 3), w = wire(k, base);
      for (int e = 0; e < 5; e++)
        for (long v : new long[]{2, 3, 4, 5, 0xffffffffL})
          for (int p = 0; p < 2; p++)
            t.add(
              "defaults",
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
              "description",
              utf8Field("雪😀"),
              "name",
              utf8Field("Ž"));
      for (int e = 0; e < 256; e++)
        for (int p = 0; p < 2; p++) {
          byte[] b = base.clone();
          b[0] = (byte) e;
          t.add(
            "encoding-byte",
            "kind",
            k,
            "initial",
            wire(k, b),
            "reparse",
            wire(k, b),
            "profile",
            p,
            "encoding",
            e % 5);
        }
      for (int n = 0; n <= base.length; n++)
        for (int p = 0; p < 2; p++) {
          byte[] b = wire(k, slice(base, 0, n));
          t.add("partial", "kind", k, "initial", w, "reparse", b, "profile", p);
          t.add("partial-ctor", "kind", k, "initial", b, "reparse", w, "profile", p);
        }
      for (int v : k.equals("O") ? new int[]{2, 3, 4} : new int[]{3, 4})
        for (int e = 0; e < 5; e++)
          for (int p = 0; p < 2; p++) {
            byte[] b = body(k, e, 3);
            t.add(
              "provided-header",
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
              e);
          }
      for (byte[] b :
        new byte[][]{
          text("\0abcd"),
          text("\0mime\0"),
          hex("006d696d650003"),
          hex("006d696d65000364657363"),
          hex("006d696d65000300626f6479"),
          text("\0mime\0name\0"),
          hex("00c3a900c3a900c3a90064617461"),
          hex("016d696d6500fffe4100000042000000626f6479")
        })
        for (int p = 0; p < 2; p++)
          t.add("delimiter", "kind", k, "initial", wire(k, b), "reparse", wire(k, b), "profile", p);
      for (int i = 0; i < 180; i++) {
        byte[] a = r.bytes(r.below(64)), b = r.bytes(r.below(64));
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
          i % 5);
      }
      for (String[] fields :
        new String[][]{
          {"", "", ""},
          {"a\0b", "c\0d", "e\0f"},
          {"image/雪", "name😀", "desc😀"},
          {"\0mime", "\0name", "\0desc"}
        })
        for (int e = 0; e < 5; e++)
          for (int v : new int[]{3, 4})
            t.add(
              "setters",
              "kind",
              k,
              "initial",
              w,
              "reparse",
              w,
              "version",
              v,
              "encoding",
              e,
              "mime",
              utf8Field(fields[0]),
              "name",
              utf8Field(fields[1]),
              "description",
              utf8Field(fields[2]));
    }
    for (int type = 0; type < 256; type++)
      for (int e = 0; e < 5; e++)
        for (int p = 0; p < 2; p++) {
          byte[] b = wire("A", body("A", e, type));
          t.add(
            "picture-type",
            "kind",
            "A",
            "initial",
            b,
            "reparse",
            b,
            "profile",
            p,
            "encoding",
            e,
            "type",
            type);
        }
    for (int e = 0; e < 5; e++)
      for (int p = 0; p < 2; p++)
        for (byte[] format :
          new byte[][]{
            text("PNG"),
            text("png"),
            text("JPG"),
            text("jpg"),
            text("GIF"),
            text("\0xx"),
            hex("fffeff")
          }) {
          byte[] b = body("V", e, 3);
          System.arraycopy(format, 0, b, 1, 3);
          for (int n = 0; n <= b.length; n++)
            t.add(
              "legacy",
              "kind",
              "V",
              "initial",
              wire("V", slice(b, 0, n), 2, n),
              "reparse",
              wire("A", b),
              "mode",
              "F",
              "version",
              2,
              "profile",
              p,
              "encoding",
              e);
        }
    for (int type = 0; type < 256; type++)
      for (int p = 0; p < 2; p++) {
        byte[] b = body("V", 0, type);
        t.add(
          "legacy-type",
          "kind",
          "V",
          "initial",
          wire("V", b, 2, b.length),
          "reparse",
          wire("A", b),
          "mode",
          "F",
          "version",
          2,
          "profile",
          p,
          "type",
          type);
      }
    for (String k : new String[]{"A", "O"})
      for (int n : new int[]{0, 1, 2, 3, 4, 5, 17, 128, 65535}) {
        byte[] b = wire(k, body(k, 0, 3), 4, n);
        t.add("declared", "kind", k, "initial", b, "reparse", b);
      }
    t.finish();
  }
}
