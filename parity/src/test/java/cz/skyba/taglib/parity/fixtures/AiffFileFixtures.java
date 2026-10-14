package cz.skyba.taglib.parity.fixtures;

import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;

import static cz.skyba.taglib.parity.fixtures.Bytes.*;
import static cz.skyba.taglib.parity.fixtures.TagBytes.*;

final class AiffFileFixtures {
  private static byte[] chunk(String id, byte[] b, long size, boolean pad) {
    return join(text(id), uint(size, 4, false), b, new byte[pad ? b.length % 2 : 0]);
  }

  private static byte[] tag(String value) {
    return id3(frame("TIT2", join(new byte[1], text(value)), 4), 4, 0, 0);
  }

  static void generate(Path root) throws Exception {
    var f = new GeneratedFiles(root);
    byte[] comm = join(pack(">HIH", 2, 48000, 16), hex("400ebb80000000000000")),
      audio = join(aiffChunk("COMM", comm), aiffChunk("SSND", text("audio\0payload"))),
      ordinary = aiff("AIFF", audio);
    var cases = new LinkedHashMap<String, byte[]>();
    cases.put("ordinary.aiff", ordinary);
    cases.put("no-chunks.aiff", aiff("AIFF"));
    cases.put("wrong-marker.aiff", join(text("NOPE"), slice(ordinary, 4, ordinary.length)));
    cases.put("wrong-subtype.aiff", aiff("NOPE", audio));
    cases.put("tag-only.aiff", aiff("AIFF", aiffChunk("ID3 ", tag("tag"))));
    cases.put("lowercase.aiff", aiff("AIFF", audio, aiffChunk("id3 ", tag("first"))));
    cases.put(
      "duplicates.aiff",
      aiff(
        "AIFF",
        audio,
        aiffChunk("id3 ", tag("first")),
        aiffChunk("ID3 ", tag("second")),
        aiffChunk("id3 ", tag("third"))));
    cases.put("trailing-unknown.aiff", join(ordinary, hex("01"), text("unrecognized tail")));
    cases.put(
      "unknown-chunks.aiff",
      aiff(
        "AIFF",
        aiffChunk("JUNK", text("opaque!")),
        audio,
        aiffChunk("AB\177D", text("reserved"))));
    for (int n : new int[]{0, 1, 2, 3, 7, 8, 9, 17, 18, 19, 27})
      cases.put(
        "partial-comm-" + n + ".aiff",
        aiff("AIFF", aiffChunk("COMM", slice(comm, 0, n)), aiffChunk("SSND", text("audio"))));
    for (int n : new int[]{0, 1, 2, 7, 8, 9})
      cases.put("partial-file-" + n + ".aiff", slice(ordinary, 0, n));
    for (long n : new long[]{0, 1, 17, 18, 64, 0xffffffffL})
      cases.put("clamp-" + n + ".aiff", aiff("AIFF", chunk("JUNK", text("opaque"), n, false)));
    for (byte[] pad : new byte[][]{hex(""), hex("00"), text("!"), hex("ff")})
      cases.put(
        "padding-" + (pad.length == 0 ? "absent" : HexFormat.of().formatHex(pad)) + ".aiff",
        aiff("AIFF", chunk("JUNK", text("a"), 1, false), pad, audio));
    cases.put("unpad-final.aiff", aiff("AIFF", chunk("JUNK", text("a"), 1, false)));
    cases.put("empty-comm-first.aiff", aiff("AIFF", aiffChunk("COMM", new byte[0]), audio));
    cases.put("short-comm-first.aiff", aiff("AIFF", aiffChunk("COMM", text("!")), audio));
    cases.put("stale-global-size.aiff", join(text("FORM"), uint(1, 4, false), text("AIFF"), audio));
    cases.put(
      "aifc.aifc",
      aiff(
        "AIFC",
        aiffChunk("COMM", join(comm, hex("414c4157046e616d65"))),
        aiffChunk("SSND", text("audio"))));
    for (String magic : new String[]{"RF64", "BW64"})
      for (long span : new long[]{0, 5, -1})
        cases.put(
          magic + "-" + Long.toUnsignedString(span) + ".aiff",
          join(
            text(magic),
            uint(-1, 4, true),
            text("WAVE"),
            riffChunk("ds64", pack("<QQQI", 100, span, 7, 0)),
            text("data"),
            uint(-1, 4, true),
            text("audio"),
            new byte[1]));
    for (var e : cases.entrySet()) f.put(e.getKey(), e.getValue());
    f.copyExtensions("upstream-", "", ".aif", ".aiff", ".afc", ".aifc");
  }
}
