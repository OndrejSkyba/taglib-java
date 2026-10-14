package cz.skyba.taglib.parity.fixtures;

import java.nio.file.Path;
import java.util.Arrays;

import static cz.skyba.taglib.parity.fixtures.Bytes.*;
import static cz.skyba.taglib.parity.fixtures.CaseTable.utf8Field;
import static cz.skyba.taglib.parity.fixtures.CaseTable.values;
import static cz.skyba.taglib.parity.fixtures.TagBytes.*;

final class Id3TextFixtures {
  private static final String[] KINDS = {"T", "U", "C", "L", "W", "X", "I", "P", "D"};
  private static final String[] IDS = {
    "TCOM", "TXXX", "COMM", "USLT", "WOAF", "WXXX", "UFID", "PRIV", "PCST"
  };
  private static final String[] LEGACY = {"TCM", "TXX", "COM", "ULT", "WAF", "WXX", "UFI"};

  private static byte[] wire(String k, byte[] b, long v, long declared, String id) {
    int index = Arrays.asList(KINDS).indexOf(k);
    return frame(
      id == null ? (v < 3 ? LEGACY[index] : IDS[index]) : id,
      b,
      v == 3 ? 3 : v < 3 ? 2 : 4,
      declared,
      0);
  }

  private static byte[] wire(String k, byte[] b) {
    return wire(k, b, 4, b.length, null);
  }

  private static byte[] body(String k, int e) {
    byte[] sep = delimiter(e);
    return switch (k) {
      case "T", "U" -> join(
        new byte[]{(byte) e},
        encoded("seed", e),
        sep,
        encoded(k.equals("U") ? "value" : "two", e));
      case "C", "L" -> join(new byte[]{(byte) e}, text("eng"), encoded("seed", e), sep, encoded("value", e));
      case "X" -> join(new byte[]{(byte) e}, encoded("seed", e), sep, text("https://seed"));
      case "W" -> text("https://seed");
      case "I" -> text("http://musicbrainz.org\0seed");
      case "P" -> join(text("seed\0"), hex("0102"));
      default -> new byte[4];
    };
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
        "description",
        utf8Field("changed"),
        "value",
        utf8Field("text"),
        "payload",
        text("fra"),
        "items",
        values("one", "two"));
    var r = new FixtureRandom(230205);
    for (String k : KINDS)
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
              wire(k, body(k, 0)),
              "encoding",
              e,
              "description",
              utf8Field("Žółć"),
              "value",
              utf8Field("雪😀"),
              "payload",
              text("12345"),
              "items",
              values("", "café", "雪😀"));
    for (String k : new String[]{"T", "U", "C", "L", "X"})
      for (int e = 0; e < 256; e++) {
        byte[] b = body(k, 0);
        b[0] = (byte) e;
        for (int p = 0; p < 2; p++)
          t.add(
            "encoding-byte",
            "kind",
            k,
            "initial",
            wire(k, b),
            "reparse",
            wire(k, body(k, 3)),
            "profile",
            p,
            "encoding",
            e % 5);
      }
    byte[][] malformed = {
      hex(""),
      hex("00"),
      hex("01"),
      hex("04"),
      hex("ff"),
      hex("0000"),
      hex("006100"),
      hex("000061"),
      hex("00610000620000"),
      hex("0061000062"),
      hex("00c3a900e900ff"),
      hex("006100620063"),
      hex("01fffe410000004200"),
      hex("01feff004100000042"),
      hex("010000410000004200"),
      hex("01fffe41000000feff0042"),
      hex("01feff00410000fffe4200"),
      hex("01fffe41"),
      hex("02004100000042"),
      hex("03c3a900e9"),
      hex("03f09f9880"),
      hex("00656e676465736300"),
      hex("00656e670076616c7565"),
      hex("01656e67fffe410000004200"),
      hex("00656e67646573630076616c7565006578747261"),
      text("owner"),
      text("owner\0"),
      text("\0payload"),
      hex("c3a900ff007461696c")
    };
    for (String k : KINDS)
      for (int p = 0; p < 2; p++) {
        for (byte[] b : malformed) {
          t.add(
            "partial",
            "kind",
            k,
            "initial",
            wire(k, body(k, 0)),
            "reparse",
            wire(k, b),
            "profile",
            p);
          t.add(
            "partial-ctor",
            "kind",
            k,
            "initial",
            wire(k, b),
            "reparse",
            wire(k, body(k, 0)),
            "profile",
            p);
        }
        byte[] b = wire(k, body(k, 0));
        for (int n = 0; n <= b.length; n++)
          t.add(
            "prefix",
            "kind",
            k,
            "initial",
            slice(b, 0, n),
            "reparse",
            slice(b, 0, n),
            "profile",
            p,
            "encoding",
            n % 5,
            "version",
            3 + n % 2);
        for (int n : new int[]{0, 1, 2, 3, 4, 7, body(k, 0).length, 127, 128, 65535}) {
          byte[] a = wire(k, body(k, 0), 4, n, null);
          t.add("declared-size", "kind", k, "initial", a, "reparse", a, "profile", p);
        }
      }
    for (String k : KINDS)
      for (int v : new int[]{2, 3, 4})
        for (int e = 0; e < 4; e++)
          for (int p = 0; p < 2; p++) {
            if (v == 2 && Arrays.asList(KINDS).indexOf(k) >= LEGACY.length) continue;
            byte[] b = body(k, e);
            t.add(
              "provided-header",
              "kind",
              k,
              "initial",
              wire(k, b, v, b.length, null),
              "reparse",
              wire(k, body(k, 0)),
              "mode",
              "F",
              "version",
              v,
              "profile",
              p,
              "encoding",
              e);
          }
    String[][] propertyFields = {
      {},
      {"arranger", "one,two", "engineer", "three"},
      {"unknown", "one"},
      {"guitar", "one,,two"},
      {"", "one"},
      {"odd"},
      {"17", " -1", "255", "256", "2147483647"},
      {"2026T10T05"}
    };
    for (String id : new String[]{"TIPL", "TMCL", "TCON", "TDRC", "TDAT", "TORY", "WFED", "XXXX"})
      for (String[] fields : propertyFields) {
        byte[] b = join(new byte[1], text(String.join("\0", fields)));
        byte[] a = wire("T", b, 4, b.length, id);
        t.add("properties", "kind", "T", "initial", a, "reparse", a, "items", values(fields));
      }
    String[][] setters = {
      {"", ""},
      {"", ""},
      {"custom::Name", "a\0b"},
      {"café", "é"},
      {"http://musicbrainz.org", "id"},
      {"URL", "url"},
      {"COMMENT", "comment"},
      {"LYRICS", "lyrics"},
      {"雪😀", "😀"}
    };
    String[][] items = {
      {}, {""}, {"a\0b"}, {"é"}, {"one"}, {"one"}, {"one"}, {"one"}, {"one", "two", "three"}
    };
    for (String k : KINDS)
      for (int i = 0; i < setters.length; i++)
        for (int v : new int[]{3, 4})
          for (int e = 0; e < 5; e++)
            t.add(
              "setters",
              "kind",
              k,
              "initial",
              wire(k, body(k, 0)),
              "reparse",
              wire(k, body(k, 0)),
              "version",
              v,
              "encoding",
              e,
              "description",
              utf8Field(setters[i][0]),
              "value",
              utf8Field(setters[i][1]),
              "items",
              values(items[i]),
              "payload",
              hex("00ffc3a900"));
    for (String k : KINDS)
      for (int i = 0; i < 120; i++) {
        byte[] a = r.bytes(r.below(48)), b = r.bytes(r.below(48));
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
          "version",
          3 + i % 2);
      }
    t.finish();
  }
}
