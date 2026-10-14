package cz.skyba.taglib.parity.mpeg.probes;


import cz.skyba.taglib.parity.support.SaveOutcome;

import cz.skyba.taglib.ReadOptions;

import cz.skyba.taglib.id3.v2.Tag;
import cz.skyba.taglib.mpeg.MpegFile;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;

public class MpegFileBoundaryProbe {
  public static void main(String[] args) {
    var tag = new Tag();
    tag.setTitle("small");
    var frame = new ByteVector(java.util.HexFormat.of().parseHex("fffb9000")).resize(417);
    var stream = new ByteVectorStream(tag.render().append(frame).append(frame));
    try (var file = new MpegFile(stream, ReadOptions.TAGS_ONLY)) {
      stream.truncate(3);
      file.id3v2Tag().setTitle("x".repeat(5000));
      var before = new ByteVector(stream.data());
      System.out.println(
        "before\t"
          + file.id3v2Tag().header().completeTagSize()
          + "\t"
          + stream.length()
          + "\t"
          + stream.data().toHex());
      boolean saved = SaveOutcome.save(file);
      if (saved || !before.equals(stream.data()))
        throw new AssertionError("Unsafe native span changed bytes");
      System.out.println("rejected\tfalse\tunchanged");
    }
  }
}
