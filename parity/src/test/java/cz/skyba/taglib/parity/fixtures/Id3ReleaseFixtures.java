package cz.skyba.taglib.parity.fixtures;

import cz.skyba.taglib.parity.support.Parity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Set;

import static cz.skyba.taglib.parity.fixtures.Bytes.*;
import static cz.skyba.taglib.parity.fixtures.TagBytes.*;

final class Id3ReleaseFixtures {
  static byte[] dffChunk(String id, byte[] b) {
    return join(text(id), uint(b.length, 8, false), b, new byte[b.length % 2]);
  }

  static byte[] dff(byte[]... chunks) {
    byte[] body = join(text("DSD "), join(chunks));
    return join(text("FRM8"), uint(body.length, 8, false), body);
  }

  static byte[] dsf(byte[] tag, byte[] audio) {
    byte[] prefix =
      join(
        text("fmt "),
        uint(52, 8, true),
        pack("<IIIIIIqII", 1, 0, 2, 2, 2822400, 1, 28224, 4096, 0),
        text("data"),
        uint(audio.length + 12, 8, true),
        audio);
    return join(
      text("DSD "),
      pack("<QQQ", 28, 28 + prefix.length + tag.length, tag.length == 0 ? 0 : 28 + prefix.length),
      prefix,
      tag);
  }

  static void generate(Path root) throws Exception {
    var f = new GeneratedFiles(root);
    var inputs = new StringBuilder();
    Set<String> mutable =
      Set.of(
        "xing.mp3",
        "rare_frames.mp3",
        "duplicate_id3v2.mp3",
        "toc_many_children.mp3",
        "id3v22-tda.mp3",
        "w000.mp3");
    for (String name :
      new String[]{
        "xing.mp3",
        "rare_frames.mp3",
        "duplicate_id3v2.mp3",
        "toc_many_children.mp3",
        "id3v22-tda.mp3",
        "unsynch.id3",
        "unsynch24.id3",
        "broken-tenc.id3",
        "005411.id3",
        "w000.mp3",
        "compressed_id3_frame_invalid.mp3",
        "compressed_id3_frame.mp3"
      }) {
      boolean save = mutable.contains(name);
      f.put(name, Files.readAllBytes(Parity.corpus().resolve(name)));
      inputs.append(name).append('\t').append(save ? 1 : 0).append('\n');
    }
    byte[] audio = join(Collections.nCopies(4, join(hex("fffb9000"), new byte[413])));
    for (int v : new int[]{2, 3, 4}) {
      byte[] body =
        join(
          frame(v == 2 ? "TT2" : "TIT2", hex("005469746c6520636166c3a9"), v),
          frame(v == 2 ? "TCO" : "TCON", text("\0(22)Death Metal"), v),
          frame(
            v == 2 ? "COM" : "COMM",
            hex("00656e6773636f706500436f6d6d656e7420636166c3a9"),
            v),
          frame(v == 2 ? "X00" : "X000", text("opaque"), v));
      String name = "authored-v" + v + ".mp3";
      f.put(name, join(id3(body, v, 32, 0), audio));
      inputs.append(name).append("\t1\n");
    }
    f.text("inputs.tsv", inputs.toString());

    byte[]
      tag =
      id3(
        join(frame("TIT2", hex("00636166c3a9"), 4), frame("X000", text("opaque"), 4)),
        4,
        32,
        0),
      sound = text("unchanged audio bytes");
    var parents = new LinkedHashMap<String, byte[]>();
    parents.put("mpeg.mp3", join(tag, audio));
    parents.put("flac.flac", join(tag, text("fLaC"), hex("80000022"), new byte[34], sound));
    parents.put(
      "wav.wav",
      wave(
        riffChunk("fmt ", pack("<HHIIHH", 1, 1, 8000, 16000, 2, 16)),
        riffChunk("data", sound),
        riffChunk("id3 ", tag)));
    parents.put(
      "aiff.aiff",
      aiff(
        "AIFF",
        aiffChunk("COMM", join(pack(">HIH", 2, 48000, 16), hex("400ebb80000000000000"))),
        aiffChunk("SSND", join(new byte[8], sound)),
        aiffChunk("ID3 ", tag)));
    parents.put("dsf.dsf", dsf(tag, sound));
    parents.put(
      "tta.tta",
      join(tag, text("TTA1"), pack("<HHHII", 1, 2, 16, 44100, 162496), new byte[4], sound));
    parents.put(
      "dff.dff",
      dff(
        dffChunk("FVER", hex("01050000")),
        dffChunk(
          "PROP",
          join(
            text("SND "),
            dffChunk("FS  ", uint(2822400, 4, false)),
            dffChunk("CHNL", hex("0002534c465453524754")))),
        dffChunk("DSD ", sound),
        dffChunk("ID3 ", tag)));
    for (var entry : parents.entrySet()) {
      f.raw("parents/" + entry.getKey(), entry.getValue());
    }
  }
}
