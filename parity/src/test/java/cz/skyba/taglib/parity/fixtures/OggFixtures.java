package cz.skyba.taglib.parity.fixtures;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static cz.skyba.taglib.parity.fixtures.Bytes.*;
import static cz.skyba.taglib.parity.fixtures.FlacWaveFixtures.block;
import static cz.skyba.taglib.parity.fixtures.FlacWaveFixtures.comment;

final class OggFixtures {
  static byte[] page(int seq, int flags, long granule, int serial, byte[]... packets) {
    var lace = new java.io.ByteArrayOutputStream();
    for (byte[] b : packets) {
      for (int n = 0; n < b.length / 255; n++) lace.write(255);
      lace.write(b.length % 255);
    }
    return join(
      text("OggS"),
      new byte[]{0, (byte) flags},
      pack("<qIII", granule, serial, seq, 0),
      new byte[]{(byte) lace.size()},
      lace.toByteArray(),
      join(packets));
  }

  private static byte[] simple(String ext, byte[] id, byte[] comment) {
    if (comment == null)
      comment =
        join(
          text(ext.equals("opus") ? "OpusTags" : ext.equals("ogg") ? "\003vorbis" : ""),
          comment("vendor", "TITLE=old", "CUSTOM=retained"),
          ext.equals("opus") ? new byte[0] : new byte[]{1});
    return join(
      page(0, 2, 0, 1, id),
      ext.equals("ogg")
        ? page(1, 0, 0, 1, comment, join(text("\005vorbis"), new byte[16]))
        : page(1, 0, 0, 1, comment),
      page(2, 4, 48000, 1, text("audio"), text("more")));
  }

  private static byte[] flac(
    List<byte[]> rest, int a, int b, int type, byte[] info, boolean legacy) {
    var parts = new ArrayList<byte[]>();
    parts.add(
      legacy
        ? join(page(0, 2, 0, 1, text("fLaC")), page(1, 0, 0, 1, block(type, info)))
        : page(
        0,
        2,
        0,
        1,
        join(
          hex("7f464c4143"),
          new byte[]{(byte) a, (byte) b},
          hex("0001664c6143"),
          block(type, info))));
    int offset = legacy ? 2 : 1;
    for (int i = 0; i < rest.size(); i++) parts.add(page(i + offset, 0, 0, 1, rest.get(i)));
    parts.add(
      page(
        rest.size() + offset, 4, 48000, 1, join(hex("fff80000"), text("audio")), text("more")));
    return join(parts);
  }

  static void owners(Path root) throws Exception {
    var f = new GeneratedFiles(root);
    byte[] opus = join(text("OpusHead"), pack("<BBHIhB", 1, 2, 312, 44100, -17920, 0)),
      vorbis = join(text("\001vorbis"), pack("<IBIIII", 0, 2, 44100, 0, 112000, 0), hex("b801")),
      speex = new byte[80];
    System.arraycopy(text("Speex   "), 0, speex, 0, 8);
    for (int[] pair :
      new int[][]{
        {28, 1},
        {32, 80},
        {36, 44100},
        {40, 1},
        {44, 4},
        {48, 2},
        {52, -1},
        {56, 160},
        {60, 1},
        {64, 1}
      })
      field(speex, pair[0], 4, pair[1], true);
    for (Object[] item : new Object[][]{{"ogg", vorbis}, {"opus", opus}, {"spx", speex}}) {
      String ext = (String) item[0];
      byte[] id = (byte[]) item[1], base = simple(ext, id, null);
      f.put("basic." + ext, base);
      f.put("leading." + ext, join(text("prefix"), base));
      f.put("foreign-first." + ext, join(page(0, 2, 100, 2, text("video")), base));
      f.put("foreign-last." + ext, join(base, page(0, 6, 96000, 2, text("foreign"))));
      f.put("no-comment." + ext, page(0, 6, 48000, 1, id));
      f.put("wrong-comment." + ext, simple(ext, id, text("wrong")));
      f.put("no-identification." + ext, simple(ext, text("wrong"), null));
      f.put("invalid-last-capture." + ext, join(base, text("OggS invalid terminal capture")));
      f.put(
        "empty-comment." + ext,
        simple(
          ext,
          id,
          join(
            text(ext.equals("opus") ? "OpusTags" : ext.equals("ogg") ? "\003vorbis" : ""),
            new byte[8])));
      for (int n : new int[]{8, 9, 10, 11, 12, 18, 19})
        if (n <= id.length)
          f.put("partial-id-" + n + "." + ext, simple(ext, slice(id, 0, n), null));
    }
    byte[] info = new byte[34];
    field(info, 10, 8, (48000L << 44) | (1L << 41) | (15L << 36) | 48000, false);
    byte[] comment = comment("vendor", "TITLE=old", "CUSTOM=retained");
    List<byte[]> basic = List.of(block(132, comment));
    f.put("basic.oga", flac(basic, 1, 0, 0, info, false));
    f.put("historical.oga", flac(basic, 1, 0, 0, info, true));
    for (int a : new int[]{1, 2})
      for (int b : new int[]{0, 9})
        f.put("version-" + a + "-" + b + ".oga", flac(basic, a, b, 0, info, false));
    for (int n : new int[]{0, 1, 17, 18, 33, 34})
      f.put("partial-streaminfo-" + n + ".oga", flac(basic, 1, 0, 0, slice(info, 0, n), false));
    f.put("non-streaminfo-first.oga", flac(basic, 1, 0, 4, info, false));
    f.put(
      "no-comment.oga",
      page(0, 6, 48000, 1, join(hex("7f464c414301000001664c6143"), block(128, info))));
    f.put(
      "declared-long.oga",
      flac(
        List.of(join(new byte[]{(byte) 132}, uint(comment.length + 50, 3, false), comment)),
        1,
        0,
        0,
        info,
        false));
    f.put("missing-last-flag.oga", flac(List.of(block(4, comment)), 1, 0, 0, info, false));
    f.put(
      "duplicate-comments.oga",
      flac(List.of(block(4, comment), block(132, new byte[8])), 1, 0, 0, info, false));
    f.put(
      "comment-not-last.oga",
      flac(List.of(block(4, comment), block(129, text("padding"))), 1, 0, 0, info, false));
    f.put(
      "unknown-blocks.oga",
      flac(
        List.of(block(127, text("x")), block(7, text("unknown")), block(132, comment)),
        1,
        0,
        0,
        info,
        false));
    for (int n : new int[]{1023, 1024, 1025}) {
      var parts = new ArrayList<>(Collections.nCopies(n - 2, block(1, new byte[0])));
      parts.add(block(132, comment));
      f.put("block-count-" + n + ".oga", flac(parts, 1, 0, 0, info, false));
    }
    for (String name :
      new String[]{
        "empty.ogg",
        "test.ogg",
        "multiplex.ogg",
        "lowercase-fields.ogg",
        "empty_vorbis.oga",
        "empty_flac.oga",
        "empty.spx",
        "correctness_gain_silent_output.opus"
      })
      f.copy("upstream-" + name + (name.equals("empty_vorbis.oga") ? ".ogg" : ""), name);
  }

  private static byte[] stream(
    String ext, byte[] header, long start, long end, int payload, boolean foreign) {
    byte[] comment =
      join(
        text(ext.equals("opus") ? "OpusTags" : ext.equals("ogg") ? "\003vorbis" : ""),
        new byte[8]);
    return join(
      foreign ? page(0, 2, start, 2, text("foreign")) : new byte[0],
      page(0, 2, start, 1, header),
      ext.equals("ogg")
        ? page(1, 0, 0, 1, comment, join(text("\005vorbis"), new byte[16]))
        : page(1, 0, 0, 1, comment),
      page(2, 4, end, 1, new byte[payload]));
  }

  private static byte[] stream(String ext, byte[] header) {
    return stream(ext, header, 0, 48000, 64, false);
  }

  static void properties(Path root) throws Exception {
    var f = new GeneratedFiles(root);
    byte[] opus = join(text("OpusHead"), pack("<BBHIhB", 1, 2, 312, 44100, -17920, 0)),
      vorbis = join(text("\001vorbis"), pack("<IBIIII", 0, 2, 44100, 0, 112000, 0), hex("b801")),
      speex = new byte[80];
    System.arraycopy(text("Speex   "), 0, speex, 0, 8);
    for (int[] pair :
      new int[][]{
        {28, 1},
        {32, 80},
        {36, 44100},
        {40, 1},
        {44, 4},
        {48, 2},
        {52, -1},
        {56, 160},
        {60, 1},
        {64, 1}
      })
      field(speex, pair[0], 4, pair[1], true);
    for (Object[] item : new Object[][]{{"opus", opus}, {"ogg", vorbis}, {"spx", speex}}) {
      String ext = (String) item[0];
      byte[] base = (byte[]) item[1];
      for (int n = 8; n <= base.length; n++)
        f.put("partial-" + n + "." + ext, stream(ext, slice(base, 0, n)));
      for (long[] pair :
        new long[][]{
          {0, 0},
          {0, 1},
          {0, 312},
          {0, 313},
          {0, 48000},
          {7, 48007},
          {-1, 48000},
          {0, -1},
          {48000, 0},
          {0, Long.MAX_VALUE},
          {Long.MAX_VALUE, Long.MAX_VALUE},
          {0, 103079215055L},
          {0, 103079215056L}
        })
        f.put(
          "granules-" + pair[0] + "-" + pair[1] + "." + ext,
          stream(ext, base, pair[0], pair[1], 64, false));
      for (int payload : new int[]{0, 1, 254, 255, 256, 4096, 65000})
        f.put("payload-" + payload + "." + ext, stream(ext, base, 0, 48000, payload, false));
      f.put("foreign-first." + ext, stream(ext, base, 0, 48000, 64, true));
    }
    for (int[] pair : new int[][]{{8, 1}, {9, 1}, {10, 2}, {12, 4}, {16, 2}})
      for (long value :
        new long[]{
          0, 1, 127, 128, 255, 256, 32767, 32768, 65535, 0x7fffffff, 0x80000000L, 0xffffffffL
        })
        if (value < (1L << (pair[1] * 8))) {
          byte[] b = opus.clone();
          field(b, pair[0], pair[1], value, true);
          f.put("opus-field-" + pair[0] + "-" + value + ".opus", stream("opus", b));
        }
    for (String ext : new String[]{"ogg", "spx"}) {
      byte[] base = ext.equals("ogg") ? vorbis : speex;
      int[] offsets =
        ext.equals("ogg") ? new int[]{7, 12, 16, 20, 24} : new int[]{28, 36, 40, 48, 52, 60};
      for (int offset : offsets)
        for (long value :
          new long[]{0, 1, 499, 500, 44100, 0x7fffffff, 0x80000000L, 0xffffffffL}) {
          byte[] b = base.clone();
          field(b, offset, 4, value, true);
          f.put(ext + "-field-" + offset + "-" + value + "." + ext, stream(ext, b));
          if (offset == 20 || offset == 52)
            f.put(ext + "-fallback-" + value + "." + ext, stream(ext, b, 0, 0, 64, false));
        }
    }
    for (String name :
      new String[]{
        "correctness_gain_silent_output.opus",
        "empty.spx",
        "empty.ogg",
        "test.ogg",
        "multiplex.ogg",
        "lowercase-fields.ogg",
        "empty_vorbis.oga"
      })
      f.copy("upstream-" + name + (name.endsWith(".oga") ? ".ogg" : ""), name);
  }
}
