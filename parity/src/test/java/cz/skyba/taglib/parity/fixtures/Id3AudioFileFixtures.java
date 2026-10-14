package cz.skyba.taglib.parity.fixtures;

import cz.skyba.taglib.parity.support.Parity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;

import static cz.skyba.taglib.parity.fixtures.Bytes.*;
import static cz.skyba.taglib.parity.fixtures.TagBytes.*;

final class Id3AudioFileFixtures {
  private static byte[] legacyFooter() {
    byte[] b = legacy();
    System.arraycopy(Arrays.copyOf(text("artist"), 30), 0, b, 33, 30);
    b[127] = 14;
    return b;
  }

  private static byte[] title() {
    return hex("007469746c65c3a9");
  }

  private static byte[] modern(int v, int pad) {
    return id3(frame(v == 2 ? "TT2" : "TIT2", title(), v), v, pad, 0);
  }

  private static byte[][] bodies() {
    return new byte[][]{
      new byte[0],
      text("TIT2"),
      frame("ZZZZ", text("opaque"), 4),
      join(frame("TIT2", text("\0a"), 4), frame("TIT2", text("\0b"), 4)),
      join(
        frame("TDRC", hex("03323032362d31302d30355431323a3334"), 4),
        frame("TIPL", hex("0370726f64756365720070"), 4)),
      frame("TCON", text("\0(14)refined"), 4),
      frame("APIC", hex("00696d6167652f706e67000364657363006f70617175652d696d616765"), 4),
      frame(
        "CHAP",
        join(
          text("ch\0"),
          pack(">IIII", 1, 2, 0xffffffffL, 0xffffffffL),
          frame("TIT2", text("\0chapter"), 4)),
        4)
    };
  }

  static void dsf(Path root) throws Exception {
    var f = new GeneratedFiles(root);
    byte[] base = Id3ReleaseFixtures.dsf(new byte[0], range(64));
    f.put("plain.dsf", base);
    f.put("large-plain.dsf", Id3ReleaseFixtures.dsf(new byte[0], new byte[200000]));
    f.put(
      "large-tag-padding.dsf",
      Id3ReleaseFixtures.dsf(id3(frame("TIT2", text("\0a"), 4), 4, 1500, 0), new byte[200000]));
    f.put("trailing-garbage.dsf", join(base, text("trailing opaque bytes")));
    for (int n = 0; n < 111; n++) f.put(String.format("partial-%03d.dsf", n), slice(base, 0, n));
    for (int offset : new int[]{4, 12, 20, 32})
      for (long value :
        new long[]{
          0,
          1,
          27,
          28,
          40,
          51,
          52,
          92,
          base.length - 1,
          base.length,
          base.length + 1,
          Long.MAX_VALUE,
          Long.MIN_VALUE,
          -1
        }) {
        byte[] b = base.clone();
        field(b, offset, 8, value, true);
        f.put("header-" + offset + "-" + Long.toUnsignedString(value) + ".dsf", b);
      }
    for (int offset : new int[]{0, 28})
      for (byte[] sig :
        new byte[][]{new byte[4], text("ID3 "), text("FMT "), text("fmt "), text("DSD ")}) {
        byte[] b = base.clone();
        System.arraycopy(sig, 0, b, offset, 4);
        f.put("signature-" + offset + "-" + HexFormat.of().formatHex(sig) + ".dsf", b);
      }
    for (int v : new int[]{2, 3, 4})
      for (int pad : new int[]{0, 1, 11, 1024, 2048})
        f.put(
          "tag-" + v + "-" + pad + ".dsf",
          Id3ReleaseFixtures.dsf(
            id3(
              join(
                frame(v == 2 ? "TT2" : "TIT2", title(), v),
                frame(v == 2 ? "TP1" : "TPE1", text("\0artist"), v)),
              v,
              pad,
              0),
            range(64)));
    for (int flags : new int[]{0, 16, 32, 64, 128, 255})
      f.put(
        "tag-flags-" + flags + ".dsf",
        Id3ReleaseFixtures.dsf(id3(frame("TIT2", text("\0a"), 4), 4, 20, flags), range(64)));
    for (byte[] body : bodies())
      f.put(
        "body-" + GeneratedFiles.hash(body).substring(0, 12) + ".dsf",
        Id3ReleaseFixtures.dsf(id3(body, 4, 20, 0), range(64)));
    for (int n : new int[]{0, 1, 9, 10, 11, 20, 31})
      f.put(
        "tag-truncated-" + n + ".dsf",
        Id3ReleaseFixtures.dsf(
          slice(id3(frame("TIT2", text("\0a"), 4), 4, 20, 0), 0, n), range(64)));
    for (int offset : new int[]{12, 20, 28, 40, 80, 92, base.length}) {
      byte[] b = base.clone();
      field(b, 20, 8, offset, true);
      f.put("metadata-inside-" + offset + ".dsf", b);
    }
    for (int origin : new int[]{7, 31})
      for (int tagged = 0; tagged < 2; tagged++) {
        byte[] b =
          Id3ReleaseFixtures.dsf(
            tagged == 0 ? new byte[0] : id3(frame("TIT2", text("\0prefix title"), 4), 4, 20, 0),
            range(64));
        field(b, 12, 8, b.length + origin, true);
        if (tagged == 1)
          field(
            b,
            20,
            8,
            b.length - id3(frame("TIT2", text("\0prefix title"), 4), 4, 20, 0).length + origin,
            true);
        f.put("prefix-" + origin + "-" + tagged + ".dsf", join(repeat('p', origin), b));
      }
    f.copyExtensions("upstream-", "", ".dsf");
  }

  static void trueaudio(Path root) throws Exception {
    var f = new GeneratedFiles(root);
    byte[] audio = join(text("TTA1"), pack("<HHHII", 1, 2, 16, 44100, 162496), range(64)),
      legacy = legacyFooter();
    f.put("plain.tta", audio);
    f.put("large-plain.tta", join(audio, new byte[200000]));
    f.put("foreign.tta", text("foreign opaque bytes"));
    for (int n = 0; n < 23; n++) f.put(String.format("partial-%02d.tta", n), slice(audio, 0, n));
    for (int v : new int[]{2, 3, 4})
      for (int pad : new int[]{0, 11, 1024, 2048}) {
        byte[] tag = modern(v, pad);
        f.put("modern-" + v + "-" + pad + ".tta", join(tag, audio));
        f.put("both-" + v + "-" + pad + ".tta", join(tag, audio, legacy));
      }
    f.put("legacy.tta", join(audio, legacy));
    f.put(
      "large-padding.tta",
      join(id3(frame("TIT2", text("\0title"), 4), 4, 1500, 0), audio, new byte[200000], legacy));
    for (int n : new int[]{3, 4, 6, 9, 10, 11, 20})
      f.put(
        String.format("partial-id3-%02d.tta", n),
        slice(id3(frame("TIT2", text("\0a"), 4), 4, 20, 0), 0, n));
    for (int flags : new int[]{16, 32, 64, 128, 255})
      f.put(
        "flags-" + flags + ".tta",
        join(id3(frame("TIT2", text("\0a"), 4), 4, 20, flags), audio, legacy));
    byte[][] all = bodies();
    for (int i : new int[]{0, 1, 2, 3, 6, 7, 4}) {
      byte[] body = all[i];
      f.put(
        "body-" + GeneratedFiles.hash(body).substring(0, 12) + ".tta",
        join(id3(body, 4, 20, 0), audio, legacy));
    }
    f.put("ape-tail.tta", join(audio, text("APETAGEX"), new byte[120]));
    for (int n : new int[]{127, 128, 129, 130, 131})
      f.put(
        "legacy-short-" + n + ".tta",
        n <= 128 ? slice(legacy, 0, n) : join(new byte[n - 128], legacy));
    for (String sig : new String[]{"TTA0", "TTA2", "NOPE"})
      f.put(
        "signature-" + HexFormat.of().formatHex(text(sig)) + ".tta",
        join(text(sig), slice(audio, 4, audio.length)));
    f.copyExtensions("upstream-", "", ".tta");
  }

  private static byte[] mpegApe(boolean header) {
    byte[] item = apeItem("Title", text("ape title"), 0);
    return ape(item, header, item.length + 32, header ? 0x80000000L : 0, 1);
  }

  static void mpeg(Path root) throws Exception {
    var f = new GeneratedFiles(root);
    byte[] audio = join(Collections.nCopies(4, join(hex("fffb9000"), new byte[413]))),
      legacy = legacyFooter();
    var records = new LinkedHashMap<String, byte[]>();
    records.put("plain", audio);
    records.put("empty", new byte[0]);
    records.put("foreign", text("opaque foreign bytes"));
    for (int v : new int[]{2, 3, 4})
      for (int pad : new int[]{0, 20, 1024, 2048}) {
        records.put("modern-" + v + "-" + pad, join(modern(v, pad), audio));
        records.put("both-" + v + "-" + pad, join(modern(v, pad), audio, legacy));
      }
    records.put("legacy", join(audio, legacy));
    records.put("ape", join(audio, mpegApe(true)));
    records.put("ape-no-header", join(audio, mpegApe(false)));
    records.put("ape-legacy", join(audio, mpegApe(true), legacy));
    records.put(
      "all-tags",
      join(id3(frame("TIT2", text("\0modern"), 4), 4, 20, 0), audio, mpegApe(true), legacy));
    for (int n : new int[]{1022, 1023, 1024, 2047}) {
      records.put(
        "garbage-" + n,
        join(repeat('!', n), id3(frame("TIT2", text("\0after junk"), 4), 4, 20, 0), audio));
      records.put("sync-" + n, join(repeat('!', n), audio));
    }
    records.put(
      "duplicate",
      join(
        id3(frame("TIT2", text("\0first"), 4), 4, 20, 0),
        id3(frame("TIT2", text("\0second"), 4), 4, 20, 0),
        audio));
    records.put(
      "unknown",
      join(
        id3(
          join(
            frame("ZZZZ", text("opaque"), 4),
            frame(
              "APIC",
              hex("00696d6167652f7465737400036465736372697074696f6e0070696374757265"),
              4)),
          4,
          20,
          0),
        audio,
        legacy));
    records.put("adts", join(Collections.nCopies(3, join(hex("fff950400220"), new byte[11]))));
    for (int n : new int[]{1, 2, 3, 4, 6, 9, 10, 11, 20})
      records.put(
        "read-partial-id3-" + n, slice(id3(frame("TIT2", text("\0title"), 4), 4, 20, 0), 0, n));
    try (var files = Files.list(Parity.corpus())) {
      for (Path p : files.sorted().toList())
        if (p.toString().endsWith(".mp3") || p.toString().endsWith(".aac"))
          records.put(
            "read-upstream-" + p.getFileName().toString().replace('.', '-'),
            Files.readAllBytes(p));
    }
    records.put(
      "compressed-filled-extent",
      join(
        Files.readAllBytes(Parity.corpus().resolve("compressed_id3_frame.mp3")),
        new byte[1024]));
    for (var record : records.entrySet()) f.put(record.getKey() + ".mp3", record.getValue());
  }
}
