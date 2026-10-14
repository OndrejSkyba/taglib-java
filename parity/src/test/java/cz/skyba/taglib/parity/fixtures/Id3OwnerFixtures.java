package cz.skyba.taglib.parity.fixtures;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import static cz.skyba.taglib.parity.fixtures.Bytes.*;
import static cz.skyba.taglib.parity.fixtures.CaseTable.hexField;
import static cz.skyba.taglib.parity.fixtures.CaseTable.utf8Field;
import static cz.skyba.taglib.parity.fixtures.TagBytes.*;

final class Id3OwnerFixtures {
  private final StringBuilder rows = new StringBuilder();
  private final Map<String, Integer> groups = new LinkedHashMap<>();

  private void add(
    String group,
    String mode,
    int version,
    int encoding,
    int handler,
    Object param,
    byte[] bytes) {
    int index = groups.getOrDefault(group, 0);
    rows.append(group)
      .append('/')
      .append(index)
      .append('\t')
      .append(mode)
      .append('\t')
      .append(version)
      .append('\t')
      .append(encoding)
      .append('\t')
      .append(handler)
      .append('\t')
      .append(param)
      .append('\t')
      .append(hexField(bytes))
      .append('\n');
    groups.put(group, index + 1);
  }

  private static byte[] wire(String id, byte[] b, int v) {
    return frame(id, b, v);
  }

  private static byte[] wire(String id, byte[] b) {
    return wire(id, b, 4);
  }

  private static byte[] owner(byte[] b, int v, int flags) {
    return join(text("PRE"), id3(b, v, 0, flags), text("END"));
  }

  private static byte[] textFrame(String id, String value, int encoding) {
    return wire(id, join(new byte[]{(byte) encoding}, encoded(value, encoding)));
  }

  private static LinkedHashMap<String, byte[]> payloads() {
    var payloads = new LinkedHashMap<String, byte[]>();
    payloads.put("TIT2", hex("005469746c65005365636f6e64"));
    payloads.put("TXXX", hex("00446573630056616c7565"));
    payloads.put("TCON", hex("00283929283133382956696b696e67204d6574616c"));
    payloads.put("TIPL", hex("00617272616e6765720061006775697461720067"));
    payloads.put("COMM", hex("00656e67640074657874"));
    payloads.put("APIC", hex("00696d6167652f706e670003640044415441"));
    payloads.put("RVA2", hex("6f776e65720001000408ff"));
    payloads.put("UFID", hex("6f776e6572006964"));
    payloads.put("GEOB", hex("006d696d65006e616d6500646573630064617461"));
    payloads.put("WXXX", hex("00646573630068747470733a2f2f75726c"));
    payloads.put("WOAF", hex("68747470733a2f2f75726c007461696c"));
    payloads.put("USLT", hex("00656e67646573630074657874"));
    payloads.put("SYLT", hex("00656e6702016465736300656e7472790000000007"));
    payloads.put("ETCO", hex("020300000007"));
    payloads.put("POPM", hex("61406200ffffffffff"));
    payloads.put("PRIV", hex("6f776e65720064617461"));
    payloads.put("OWNE", hex("00707269636500323032363130303573656c6c6572"));
    payloads.put("PCST", hex("00000000"));
    payloads.put("ZZZZ", hex("6f7061717565"));
    payloads.put("WFED", hex("0075726c"));
    payloads.put("MVNM", hex("004d6f76656d656e74"));
    payloads.put("MVIN", hex("00322f33"));
    payloads.put("GRP1", hex("0047726f7570"));
    return payloads;
  }

  static void generate(Path root) throws Exception {
    var g = new Id3OwnerFixtures();
    var rng = new FixtureRandom(232008);
    var payloads = payloads();
    var reverse = new LinkedHashMap<String, String>();
    String[] legacy = {
      "TT2", "TXX", "TCO", "IPL", "COM", "PIC", "RVA", "UFI", "GEO", "WXX", "WAF", "ULT", "SLT",
      "ETC", "POP", "PRI", "OWN", "PCS", "ZZZ", "WFD", "MVN", "MVI", "GP1"
    };
    int pos = 0;
    for (String key : payloads.keySet()) reverse.put(key, legacy[pos++]);
    for (int v : new int[]{0, 1, 2, 3, 4, 5, 255})
      for (var item : payloads.entrySet()) {
        String name = v <= 2 ? reverse.get(item.getKey()) : item.getKey();
        byte[] b = name.equals("PIC") ? hex("00504e4703640044415441") : item.getValue();
        for (int e : new int[]{-1, 0, 1, 2, 3, 4})
          for (int h = 0; h < 2; h++) g.add("dispatch", "F", v, e, h, 0, wire(name, b, v));
      }
    String[] conversions =
      "BUF CNT COM CRA ETC GEO IPL MCI MLL POP REV SLT STC TAL TBP TCM TCO TCP TCR TDY TEN TFT TKE TLA TLE TMT TOA TOF TOL TOR TOT TP1 TP2 TP3 TP4 TPA TPB TRC TRD TRK TS2 TSA TSC TSP TSS TST TT1 TT2 TT3 TXT TXX TYE UFI ULT WAF WAR WAS WCM WCP WPB WXX PCS TCT TDR TDS TID WFD MVN MVI GP1 CRM EQU LNK RVA TIM TSI TDA PIC ZZZ"
        .split(" ");
    for (String id : conversions)
      g.add("v22-conversion", "F", 2, -1, 0, 0, wire(id, text("\0text"), 2));
    for (String id :
      new String[]{
        "TORY", "TYER", "IPLS", "EQUA", "RVAD", "TIME", "TRDA", "TSIZ", "TDAT", "TRDC"
      })
      for (int v : new int[]{3, 4})
        g.add("v23-conversion", "F", v, -1, 0, 0, wire(id, text("\0002026"), v));
    for (String fourth : new String[]{"\0", " "})
      for (String id : conversions)
        g.add("itunes-v23", "F", 3, -1, 0, 0, wire(id + fourth, text("\0value"), 3));
    for (int v : new int[]{2, 3, 4, 5}) {
      String id = v == 2 ? "TT2" : "TIT2";
      byte[] b = wire(id, text("\0abcdef"), v);
      for (int n = 0; n <= b.length; n++) g.add("partial", "F", v, -1, 0, 0, slice(b, 0, n));
      for (int n : new int[]{0, 1, 3, 4, 7, b.length, b.length + 1, 127, 255})
        g.add("declared", "F", v, -1, 0, 0, frame(id, text("\0abcdef"), v, n, 0));
    }
    for (int f = 0; f < 256; f++)
      for (int first : new int[]{0, 32, 64, 128, 255})
        for (int v : new int[]{3, 4})
          g.add(
            "flags",
            "F",
            v,
            -1,
            0,
            (f & 2) == 0 ? 0 : 128,
            frame("TIT2", hex("006162ff006364"), v, 7, (first << 8) | f));
    for (var item : payloads.entrySet())
      for (int n = 0; n < Math.min(12, item.getValue().length + 1); n++)
        g.add("short-fields", "F", 4, -1, 0, 0, wire(item.getKey(), slice(item.getValue(), 0, n)));
    String[] ids = payloads.keySet().toArray(String[]::new);
    for (int n = 0; n < 300; n++) {
      String id = rng.choose(ids);
      int v = rng.choose(new Integer[]{2, 3, 4});
      byte[] b = rng.bytes(rng.below(24));
      g.add(
        "random-fields", "F", v, -1, rng.below(2), 0, wire(v == 2 ? reverse.get(id) : id, b, v));
    }
    for (String value :
      new String[]{
        "",
        "()",
        "(22)Death Metal",
        "(4)Eurodisco",
        "(9)(138)Viking Metal",
        "(RX)(CR)Metal",
        "(-1)Text",
        "(255)",
        "(999)Text",
        "(01)Classic Rock",
        "14\0Eurodisco"
      })
      for (int v : new int[]{2, 3, 4})
        g.add(
          "genre",
          "F",
          v,
          -1,
          0,
          0,
          wire(
            v == 2 ? "TCO" : "TCON",
            join(new byte[1], value.getBytes(StandardCharsets.UTF_8)),
            v));
    byte[] leaf = wire("TIT2", text("\0title"));
    for (String id : new String[]{"CHAP", "CTOC"}) {
      byte[] prefix =
        id.equals("CHAP")
          ? join(text("ch\0"), pack(">IIII", 3, 5, 2, 0xffffffffL))
          : hex("746f6300030263680074776f00");
      for (byte[] tail :
        new byte[][]{
          new byte[0],
          leaf,
          join(leaf, leaf),
          join(wire("ZZZZ", text("data")), leaf),
          join(wire("TIT2", new byte[0]), leaf),
          join(text("BAD!"), new byte[12])
        })
        for (int e : new int[]{-1, 2})
          for (int h = 0; h < 2; h++)
            g.add("nested", "F", 4, e, h, 0, wire(id, join(prefix, tail)));
      byte[] full = join(prefix, leaf);
      for (int n = 0; n <= full.length; n++)
        g.add("nested-partial", "F", 4, -1, 0, 0, wire(id, slice(full, 0, n)));
      for (int n = 0; n < 4; n++)
        g.add(
          "nested-missing-null", "F", 4, -1, 0, 0, wire(id, join(repeat('X', 20), new byte[n])));
    }
    for (int depth : new int[]{1, 2, 63, 64, 65, 66}) {
      byte[] b = leaf;
      for (int n = 0; n < depth; n++) b = wire("CHAP", join(text("ch\0"), new byte[16], b));
      g.add("depth", "F", 4, -1, 0, 0, b);
    }
    for (String key :
      new String[]{
        "TITLE",
        "title",
        "COMMENT",
        "COMMENT:X",
        "comment:X",
        "LYRICS",
        "LYRICS:X",
        "URL",
        "URL:X",
        "MUSICBRAINZ_TRACKID",
        "musicbrainz_trackid",
        "PODCAST",
        "WFED",
        "MOVEMENTNAME",
        "GROUPING",
        "MUSICBRAINZ_ALBUMID",
        "EMPTY",
        "FOO"
      })
      for (byte[] values :
        new byte[][]{
          text(""),
          text("one"),
          text("one|two"),
          text("|one"),
          text("one|"),
          text("a\0b"),
          "žlutý".getBytes(StandardCharsets.UTF_8)
        })
        for (int e : new int[]{-1, 1, 3}) g.add("property", "P", 4, e, 0, utf8Field(key), values);
    for (int v : new int[]{2, 3, 4}) {
      var chunks = new ArrayList<byte[]>();
      for (var item : payloads.entrySet())
        if (!Set.of("APIC", "RVA2", "OWNE").contains(item.getKey()))
          chunks.add(wire(v == 2 ? reverse.get(item.getKey()) : item.getKey(), item.getValue(), v));
      var patterns = new ArrayList<byte[]>();
      patterns.add(new byte[0]);
      patterns.add(wire(v == 2 ? "TT2" : "TIT2", text("\0first"), v));
      patterns.add(join(chunks));
      patterns.add(
        join(
          wire(v == 2 ? "TYE" : "TYER", text("\0002026"), v),
          wire(v == 2 ? "TDA" : "TDAT", text("\0001005"), v),
          wire(v == 2 ? "TIM" : "TIME", text("\0001234"), v)));
      patterns.add(wire(v == 2 ? "IPL" : "IPLS", text("\0arranger\0a\0guitar\0g"), v));
      patterns.add(
        join(
          wire(v == 2 ? "ZZZ" : "ZZZZ", text("opaque"), v),
          wire(v == 2 ? "TT2" : "TIT2", text("\0first"), v)));
      if (v == 4) {
        patterns.add(wire("CHAP", join(text("ch\0"), pack(">IIII", 3, 5, 2, 3), leaf)));
        patterns.add(wire("CTOC", join(hex("746f63000301636800"), leaf)));
      }
      for (byte[] body : patterns)
        for (int pad : new int[]{0, 1, 10, 1024, 1100})
          for (int flag : new int[]{0, 32, 128, 16})
            g.add(
              "tag-owner",
              "T",
              v,
              -1,
              0,
              0,
              owner(join(body, new byte[pad + (flag == 16 ? 10 : 0)]), v, flag));
    }
    for (int size : new int[]{0, 1, 6, 10, 1000})
      g.add(
        "extended",
        "T",
        4,
        -1,
        0,
        0,
        owner(join(sync(size), new byte[2], leaf, new byte[20]), 4, 64));
    for (int n : new int[]{1, 2, 10})
      g.add(
        "duplicates",
        "T",
        4,
        -1,
        0,
        0,
        join(text("PRE"), join(Collections.nCopies(n + 1, id3(leaf, 4, 30, 0))), text("END")));
    for (int e = 0; e < 5; e++)
      for (String date :
        new String[]{
          "",
          "202",
          "2026",
          "2026-10-05",
          "2026-10-05T12:34",
          "2026/10/05T12:34",
          "2026-10-05 12:34"
        }) {
        byte[] body =
          join(
            textFrame("TDOR", "2011-03-16", e),
            textFrame("TDRC", date, e),
            textFrame("TIPL", "arranger\0a\0producer\0p", e),
            textFrame("TMCL", "guitar\0g\0odd", e),
            textFrame("TCON", "14\0RX\0CR\0Rock\0Refinement", e),
            wire("RVA2", text("owner\0")),
            textFrame("TSOA", "sort", e),
            new byte[30]);
        g.add("downgrade", "T", 4, e, 0, 0, owner(body, 4, 0));
      }
    for (String scenario :
      new String[]{"duplicate-date", "wrong-type-last", "all-empty", "odd-people"}) {
      byte[] b = textFrame("TDRC", "2026-10-05T12:34", 0);
      b =
        switch (scenario) {
          case "duplicate-date" -> join(b, textFrame("TDRC", "2027", 0));
          case "wrong-type-last" -> join(b, frame("TDRC", text("opaque"), 4, 6, 4));
          case "all-empty" -> join(textFrame("TCON", "", 0), textFrame("TIPL", "", 0), textFrame("TMCL", "", 0));
          default -> join(
            b,
            textFrame("TIPL", "arranger\0a\0odd", 0),
            textFrame("TMCL", "guitar\0g\0odd", 3));
        };
      g.add("downgrade-special", "T", 4, -1, 0, 0, owner(join(b, new byte[30]), 4, 0));
    }
    var files = new GeneratedFiles(root);
    files.text("cases.tsv", g.rows.toString());
  }
}
