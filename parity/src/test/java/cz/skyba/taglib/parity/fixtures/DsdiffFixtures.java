package cz.skyba.taglib.parity.fixtures;

import cz.skyba.taglib.parity.support.Parity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static cz.skyba.taglib.parity.fixtures.Bytes.*;
import static cz.skyba.taglib.parity.fixtures.Id3ReleaseFixtures.dff;
import static cz.skyba.taglib.parity.fixtures.Id3ReleaseFixtures.dffChunk;
import static cz.skyba.taglib.parity.fixtures.TagBytes.*;

final class DsdiffFixtures {
  private static byte[] tag(byte[] title, int v, int pad) {
    return id3(frame("TIT2", join(new byte[1], title), v), v, pad, 0);
  }

  private static byte[] tag() {
    return tag(text("native title"), 4, 0);
  }

  private static byte[] dit(String id, byte[] b) {
    return dffChunk(id, join(uint(b.length, 4, false), b));
  }

  static void files(Path root) throws Exception {
    var f = new GeneratedFiles(root);
    byte[] version = dffChunk("FVER", hex("01050000")),
      fs = dffChunk("FS  ", uint(2822400, 4, false)),
      ch = dffChunk("CHNL", hex("0002534c465453524754")),
      prop = dffChunk("PROP", join(text("SND "), fs, ch)),
      audio = dffChunk("DSD ", range(128)),
      unknown = dffChunk("ZZZZ", text("opaque payload")),
      id = dffChunk("ID3 ", tag()),
      diin =
        dffChunk(
          "DIIN", join(dit("DITI", text("diin title")), dit("DIAR", text("diin artist"))));
    var cases = new LinkedHashMap<String, byte[]>();
    Set<String> readonly = new HashSet<>();
    byte[] outer = dff(version, prop, audio, unknown);
    cases.put("plain", outer);
    cases.put(
      "wrong-format", join(slice(outer, 0, 12), text("JUNK"), slice(outer, 16, outer.length)));
    cases.put("wrong-type", join(text("NOPE"), slice(outer, 4, outer.length)));
    cases.put("upstream", Files.readAllBytes(Parity.corpus().resolve("empty10ms.dff")));
    for (int v : new int[]{3, 4})
      for (int pad : new int[]{0, 20, 1500})
        cases.put(
          "root-" + v + "-" + pad,
          dff(
            version,
            prop,
            audio,
            dffChunk("ID3 ", tag(text("native title"), v, pad)),
            unknown));
    cases.put("lowercase-root", dff(version, prop, audio, dffChunk("id3 ", tag()), unknown));
    cases.put(
      "latin1-root",
      dff(version, prop, audio, dffChunk("ID3 ", tag(hex("636166c3a9"), 4, 0)), unknown));
    cases.put(
      "unknown-frame",
      dff(
        version,
        prop,
        audio,
        dffChunk("ID3 ", id3(frame("ZZZZ", text("x\0y"), 4), 4, 0, 0)),
        unknown));
    cases.put("root-empty", dff(version, prop, audio, dffChunk("ID3 ", new byte[0]), unknown));
    cases.put(
      "prop-id3", dff(version, dffChunk("PROP", join(text("SND "), fs, ch, id)), audio, unknown));
    cases.put(
      "prop-id3-before-fields",
      dff(version, dffChunk("PROP", join(text("SND "), id, fs, ch)), audio, unknown));
    cases.put(
      "lowercase-prop",
      dff(
        version,
        dffChunk("PROP", join(text("SND "), fs, ch, dffChunk("id3 ", tag()))),
        audio,
        unknown));
    cases.put(
      "duplicate",
      dff(version, dffChunk("PROP", join(text("SND "), fs, ch, id)), audio, id, unknown));
    cases.put("diin", dff(version, prop, audio, diin, unknown));
    cases.put("both", dff(version, prop, audio, id, diin, unknown));
    cases.put(
      "diin-opaque",
      dff(
        version,
        prop,
        audio,
        dffChunk(
          "DIIN",
          join(
            dit("DITI", text("a")),
            dit("DIAR", text("b")),
            dffChunk("ZZZZ", text("opaque")))),
        unknown));
    cases.put("diin-empty", dff(version, prop, audio, dffChunk("DIIN", new byte[0]), unknown));
    cases.put(
      "diin-null",
      dff(
        version,
        prop,
        audio,
        dffChunk("DIIN", join(dit("DITI", text("a\0b")), dit("DIAR", hex("ffe9")))),
        unknown));
    cases.put(
      "prop-marker", dff(version, dffChunk("PROP", join(text("JUNK"), fs, ch)), audio, unknown));
    cases.put(
      "short-fs",
      dff(
        version,
        dffChunk("PROP", join(text("SND "), dffChunk("FS  ", new byte[0]), ch)),
        audio,
        unknown));
    cases.put(
      "short-chnl",
      dff(
        version,
        dffChunk("PROP", join(text("SND "), fs, dffChunk("CHNL", new byte[0]))),
        audio,
        unknown));
    cases.put("short-prop", dff(version, dffChunk("PROP", text("SN")), audio, unknown));
    cases.put(
      "split-prop",
      dff(
        version,
        dffChunk("PROP", join(text("SND "), fs)),
        dffChunk("PROP", join(text("SND "), ch)),
        audio,
        unknown));
    cases.put(
      "split-diin",
      dff(
        version,
        prop,
        audio,
        dffChunk("DIIN", dit("DITI", text("first"))),
        dffChunk("DIIN", dit("DIAR", text("second"))),
        unknown));
    readonly.add("split-diin");
    cases.put(
      "dst",
      dff(
        version,
        prop,
        dffChunk(
          "DST ",
          join(dffChunk("FRTE", pack(">IH", 75, 75)), dffChunk("DSTF", text("compressed")))),
        unknown));
    cases.put(
      "short-frte",
      dff(
        version,
        prop,
        dffChunk(
          "DST ",
          join(dffChunk("FRTE", uint(75, 4, false)), dffChunk("DSTF", text("compressed")))),
        unknown));
    byte[] bad = join(text("\0BAD"), new byte[8]);
    cases.put(
      "frte-stop",
      dff(
        version,
        prop,
        dffChunk("DST ", join(dffChunk("FRTE", pack(">IH", 75, 75)), bad)),
        unknown));
    cases.put(
      "cross-diin",
      dff(
        version,
        prop,
        audio,
        dffChunk(
          "DIIN",
          join(
            dffChunk("DITI", join(uint(6, 4, false), text("ab"))),
            dit("DIAR", text("artist")))),
        unknown));
    cases.put(
      "length-diin",
      dff(
        version,
        prop,
        audio,
        dffChunk("DIIN", dffChunk("DITI", join(uint(99, 4, false), text("ab")))),
        unknown));
    cases.put("bad-root", dff(version, prop, audio, id, bad));
    cases.put("bad-prop", dff(version, dffChunk("PROP", join(text("SND "), fs, bad)), audio, id));
    cases.put(
      "bad-diin",
      dff(version, prop, audio, id, dffChunk("DIIN", join(dit("DITI", text("a")), bad))));
    cases.put("large-root", dff(version, prop, audio, join(text("TEST"), uint(-1, 8, false))));
    cases.put("missing-prop", dff(version, audio, id));
    cases.put("foreign", text("not a DSDIFF file at all"));
    readonly.addAll(
      List.of("bad-root", "bad-prop", "bad-diin", "large-root", "missing-prop", "foreign"));
    for (int n : new int[]{0, 4, 8, 12, 16, 24, 27}) {
      cases.put("partial-" + n, slice(dff(version, prop, audio), 0, n));
      readonly.add("partial-" + n);
    }
    cases.put(
      "odd-unpadded",
      dff(version, prop, join(text("ODD "), uint(1, 8, false), text("x")), audio));
    cases.put("metadata-first", dff(id, prop, audio));
    readonly.addAll(List.of("odd-unpadded", "metadata-first"));
    for (var e : cases.entrySet())
      f.putSaveCase(e.getKey() + ".dff", e.getValue(), !readonly.contains(e.getKey()));
    f.saveCases();
  }

  static void diin(Path root) throws Exception {
    StringBuilder units = new StringBuilder();
    for (int n = 0; n < 65536; n++) units.append((char) n);
    StringBuilder edges = new StringBuilder();
    for (int n :
      new int[]{
        0, 1, 31, 32, 127, 128, 255, 256, 0x7fff, 0x8000, 0xd7ff, 0xd800, 0xdc00, 0xdfff, 0xe000,
        0xffff
      })
      edges.append((char) n);
    String[] values = {
      "",
      "plain",
      "  whitespace \t\n",
      "a\0b",
      "\0",
      "\0end",
      "日本語",
      "éäÿ",
      "\ud800",
      "\udfff",
      "\ud800\udfff",
      "\ud83c\udfb5",
      edges.toString(),
      "x".repeat(4097)
    };
    var rows = new StringBuilder();
    for (int i = 0; i < values.length; i++)
      for (int nt = 0; nt < 5; nt++)
        for (int na = 0; na < 5; na++)
          rows.append("value-")
            .append(i)
            .append('-')
            .append(nt)
            .append('-')
            .append(na)
            .append('\t')
            .append(HexFormat.of().formatHex(encoded(values[i], 2)))
            .append('\t')
            .append(HexFormat.of().formatHex(encoded(values[(i + 3) % values.length], 2)))
            .append('\t')
            .append(nt)
            .append('\t')
            .append(na)
            .append('\n');
    rows.append("all-utf16-units\t")
      .append(HexFormat.of().formatHex(encoded(units.toString(), 2)))
      .append('\t')
      .append(HexFormat.of().formatHex(encoded("artist", 2)))
      .append("\t2\t3\n");
    var f = new GeneratedFiles(root);
    f.text("cases.tsv", rows.toString());
  }
}
