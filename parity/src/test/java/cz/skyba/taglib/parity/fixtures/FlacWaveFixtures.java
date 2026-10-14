package cz.skyba.taglib.parity.fixtures;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;

import static cz.skyba.taglib.parity.fixtures.Bytes.*;
import static cz.skyba.taglib.parity.fixtures.TagBytes.riffChunk;
import static cz.skyba.taglib.parity.fixtures.TagBytes.wave;

final class FlacWaveFixtures {
  static byte[] block(int type, byte[] b) {
    return join(new byte[]{(byte) type}, uint(b.length, 3, false), b);
  }

  static byte[] comment(String vendor, String... fields) {
    var parts = new ArrayList<byte[]>();
    parts.add(uint(text(vendor).length, 4, true));
    parts.add(text(vendor));
    parts.add(uint(fields.length, 4, true));
    for (String field : fields) {
      parts.add(uint(text(field).length, 4, true));
      parts.add(text(field));
    }
    return join(parts);
  }

  static void generate(Path root) throws Exception {
    var f = new GeneratedFiles(root);
    byte[] info = new byte[34],
      audio = text("unchanged audio data"),
      comment = comment("vendor", "TITLE=first", "ARTIST=one", "ARTIST=two", "CUSTOM=opaque");
    for (String name :
      new String[]{
        "no-tags.flac",
        "silence-44-s.flac",
        "multiple-vc.flac",
        "zero-sized-padding.flac",
        "empty-seektable.flac",
        "empty.wav",
        "duplicate_tags.wav",
        "rf64.wav",
        "alaw.wav",
        "float64.wav",
        "pcm_with_fact_chunk.wav",
        "wavex.wav",
        "zero-size-chunk.wav"
      })
      if (java.nio.file.Files.exists(cz.skyba.taglib.parity.support.Parity.corpus().resolve(name)))
        f.copy("upstream-" + name, name);
    var flac = new LinkedHashMap<String, byte[]>();
    flac.put("bare.flac", block(128, info));
    flac.put(
      "mixed.flac",
      join(
        block(0, info),
        block(4, comment),
        block(4, comment("duplicate", "TITLE=second")),
        block(130, text("opaque"))));
    flac.put(
      "broadcast.flac",
      join(
        block(0, info),
        block(2, text("iXMLfirst")),
        block(2, text("iXMLlast")),
        block(2, text("bextfirst")),
        block(2, join(text("riffbext"), uint(3, 4, true), text("last"))),
        block(2, join(text("riffJUNK"), uint(3, 4, true), text("raw"))),
        block(132, comment)));
    flac.put("empty-comment.flac", join(block(0, info), block(132, comment("vendor"))));
    flac.put(
      "padding.flac", join(block(0, info), block(3, new byte[0]), block(129, new byte[5000])));
    flac.put("short-info.flac", block(128, text("x")));
    flac.put("invalid.flac", block(128, new byte[0]));
    flac.put("preamble.flac", join(text("junkprefix"), block(128, info)));
    for (var e : flac.entrySet()) f.put(e.getKey(), join(text("fLaC"), e.getValue(), audio));
    byte[] fmt = riffChunk("fmt ", pack("<HHIIHH", 1, 1, 8000, 16000, 2, 16)),
      data = riffChunk("data", audio),
      list =
        riffChunk(
          "LIST",
          join(
            text("INFO"),
            riffChunk("INAM", text("first\0")),
            riffChunk("ZZZZ", text("opaque\0"))));
    f.put("bare.wav", wave(fmt, data));
    f.put(
      "mixed.wav",
      wave(
        fmt,
        list,
        riffChunk("LIST", join(text("INFO"), riffChunk("INAM", text("second\0")))),
        riffChunk("LIST", text("adtlunchanged")),
        riffChunk("JUNK", text("opaque")),
        data));
    f.put(
      "broadcast.wav",
      wave(
        fmt,
        riffChunk("iXML", text("first")),
        riffChunk("iXML", text("last")),
        riffChunk("bext", text("first")),
        riffChunk("bext", text("last")),
        data));
    f.put("anchorless.wav", wave());
    f.put("partial.wav", wave(riffChunk("JUNK", text("abc"))));
    f.put("offset.wav", wave(riffChunk("JUNK", text("offset!")), fmt, data));
  }
}
