package cz.skyba.taglib.parity.fixtures;

import java.nio.file.Path;

import static cz.skyba.taglib.parity.fixtures.Bytes.*;
import static cz.skyba.taglib.parity.fixtures.CaseTable.utf8Field;
import static cz.skyba.taglib.parity.fixtures.TagBytes.frame;

final class Id3NumericFixtures {
  private static byte[] wire(String k, byte[] b, int v, long n) {
    return frame(k.equals("O") ? "OWNE" : k.equals("P") ? "POPM" : "RVA2", b, v, n, 0);
  }

  private static byte[] wire(String k, byte[] b) {
    return wire(k, b, 4, b.length);
  }

  private static byte[] body(String k) {
    return k.equals("O")
      ? text("\0price\00020261005seller")
      : k.equals("P")
      ? hex("656d61696c000701020304")
      : hex("6964656e74696669636174696f6e0001fffe08aa02000f00");
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
        "price",
        utf8Field("price"),
        "date",
        utf8Field("20261005"),
        "seller",
        utf8Field("seller"),
        "rating",
        7,
        "counter",
        "4294967295",
        "channel",
        1,
        "index",
        17,
        "float",
        "-",
        "bits",
        8,
        "peak",
        hex("aa"));
    var r = new FixtureRandom(232006);
    for (String k : new String[]{"O", "P", "R"}) {
      byte[] base = body(k), w = wire(k, base);
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
              e);
      for (int n = 0; n <= base.length; n++)
        for (int p = 0; p < 2; p++) {
          byte[] b = wire(k, slice(base, 0, n));
          t.add("partial", "kind", k, "initial", w, "reparse", b, "profile", p);
          t.add("partial-ctor", "kind", k, "initial", b, "reparse", w, "profile", p);
        }
      for (int v : new int[]{3, 4})
        for (int p = 0; p < 2; p++)
          t.add(
            "provided",
            "kind",
            k,
            "initial",
            wire(k, base, v, base.length),
            "reparse",
            w,
            "mode",
            "F",
            "version",
            v,
            "profile",
            p);
      for (int n : new int[]{0, 1, 2, 3, 4, 7, 8, 9, 17, 128, 65535}) {
        byte[] b = wire(k, base, 4, n);
        t.add("declared", "kind", k, "initial", b, "reparse", b);
      }
      for (int i = 0; i < 160; i++) {
        byte[] a = r.bytes(r.below(65)), b = r.bytes(r.below(65));
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
          {"雪😀", "20010101", "Ž😀"},
          {"a", "date-short", "seller-long"},
          {"a", "date-longer-than-eight", "seller"}
        })
        for (int e = 0; e < 5; e++)
          for (int v : new int[]{3, 4})
            for (int p = 0; p < 2; p++)
              t.add(
                "string-setters",
                "kind",
                k,
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
                "price",
                utf8Field(fields[0]),
                "date",
                utf8Field(fields[1]),
                "seller",
                utf8Field(fields[2]));
    }
    for (int e = 0; e < 256; e++)
      for (int p = 0; p < 2; p++) {
        byte[] b = body("O");
        b[0] = (byte) e;
        t.add(
          "encoding-byte",
          "kind",
          "O",
          "initial",
          wire("O", b),
          "reparse",
          wire("O", b),
          "profile",
          p,
          "encoding",
          e % 5);
      }
    for (byte[] b :
      new byte[][]{
        hex(""),
        hex("00"),
        text("\0abcdefghi"),
        text("\0price\0short"),
        text("\0price\00020261005"),
        text("\0price\00020261005sell\0after"),
        text("\0p\00020\0001005seller"),
        hex("00c3a9003230323631303035c3a9"),
        hex("0170003230323631303035fffe41004200")
      })
      for (int p = 0; p < 2; p++)
        t.add(
          "ownership-delimiter",
          "kind",
          "O",
          "initial",
          wire("O", b),
          "reparse",
          wire("O", b),
          "profile",
          p);
    for (int rating :
      new int[]{Integer.MIN_VALUE, -257, -1, 0, 1, 255, 256, 257, Integer.MAX_VALUE})
      for (long counter : new long[]{0, 1, 255, 256, 0xffffffffL, 0x100000000L, 0x100000003L, -1})
        t.add(
          "numeric-setters",
          "kind",
          "P",
          "initial",
          wire("P", body("P")),
          "reparse",
          wire("P", body("P")),
          "rating",
          rating,
          "counter",
          Long.toUnsignedString(counter));
    for (int n = 0; n < 12; n++)
      for (int p = 0; p < 2; p++) {
        byte[] b = join(hex("6500ff"), range(n));
        t.add(
          "counter-width",
          "kind",
          "P",
          "initial",
          wire("P", b),
          "reparse",
          wire("P", b),
          "profile",
          p);
      }
    for (byte[] b :
      new byte[][]{
        hex(""),
        text("abcd"),
        text("email"),
        hex("00"),
        hex("0001"),
        hex("00ffffffffffff"),
        hex("c3a900070102")
      })
      for (int p = 0; p < 2; p++)
        t.add(
          "popm-delimiter",
          "kind",
          "P",
          "initial",
          wire("P", b),
          "reparse",
          wire("P", b),
          "profile",
          p);
    for (int c = 0; c < 256; c++)
      for (int bits = 0; bits < 256; bits += 17) {
        byte[] b =
          join(
            text("id\0"),
            new byte[]{(byte) c},
            hex("fff1"),
            new byte[]{(byte) bits},
            r.bytes((bits + 7) / 8));
        byte[] peak = r.bytes(r.below(40));
        t.add(
          "channel-peak",
          "kind",
          "R",
          "initial",
          wire("R", b),
          "reparse",
          wire("R", b),
          "channel",
          c % 9,
          "index",
          -123,
          "bits",
          bits,
          "peak",
          peak);
      }
    for (int bits = 0; bits < 256; bits++)
      for (int length : new int[]{0, Math.max(0, (bits + 7) / 8 - 1)}) {
        byte[] b = join(hex("696400030011"), new byte[]{(byte) bits}, r.bytes(length));
        t.add(
          "short-peak",
          "kind",
          "R",
          "initial",
          wire("R", b),
          "reparse",
          wire("R", b),
          "bits",
          bits);
      }
    for (int c = 0; c < 9; c++)
      for (int n :
        new int[]{
          Integer.MIN_VALUE,
          -65537,
          -32769,
          -32768,
          -1,
          0,
          1,
          32767,
          32768,
          65535,
          65536,
          Integer.MAX_VALUE
        })
        t.add(
          "index-narrow",
          "kind",
          "R",
          "initial",
          wire("R", body("R")),
          "reparse",
          wire("R", body("R")),
          "channel",
          c,
          "index",
          n,
          "bits",
          256,
          "peak",
          hex("00ff"));
    for (double x :
      new double[]{
        -64.0019, -64, -63.999, -1, -0.001, -0.0, 0, 0.001, 1, 63.999, Math.nextDown(64.0)
      }) {
      float f = (float) x;
      if (-32769 < f * 512.0 && f * 512.0 < 32768)
        t.add(
          "float-edge",
          "kind",
          "R",
          "initial",
          wire("R", body("R")),
          "reparse",
          wire("R", body("R")),
          "float",
          pack(">f", f));
    }
    for (int i = 0; i < 500; i++) {
      float f = Float.intBitsToFloat((int) r.bits(32));
      if (Float.isFinite(f) && -32769 < f * 512.0 && f * 512.0 < 32768)
        t.add(
          "float-bits",
          "kind",
          "R",
          "initial",
          wire("R", body("R")),
          "reparse",
          wire("R", body("R")),
          "channel",
          i % 9,
          "float",
          pack(">f", f),
          "bits",
          -1,
          "peak",
          hex("0100"));
    }
    for (byte[] b :
      new byte[][]{
        hex("01000f00"),
        hex("6964000200110002ffff08ff"),
        hex("6964000800010001000200"),
        hex("69640001000100ff000200")
      })
      t.add("volume-delimiter", "kind", "R", "initial", wire("R", b), "reparse", wire("R", b));
    t.finish();
  }
}
