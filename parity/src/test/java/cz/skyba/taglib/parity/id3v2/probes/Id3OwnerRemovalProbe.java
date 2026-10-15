package cz.skyba.taglib.parity.id3v2.probes;

import cz.skyba.taglib.id3.v2.ChapterFrame;
import cz.skyba.taglib.id3.v2.Tag;
import cz.skyba.taglib.id3.v2.TextIdentificationFrame;
import cz.skyba.taglib.toolkit.ByteVector;

import java.util.List;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

class Id3OwnerRemovalProbe {
  public static void main(String[] args) {
    for (String mode : List.of("tag-missing", "tag-stale", "chapter-missing", "chapter-stale")) {
      var f = new TextIdentificationFrame(new ByteVector("TIT2"), ISO_8859_1);
      boolean threw = false;
      if (mode.startsWith("tag")) {
        var t = new Tag();
        if (mode.endsWith("stale")) {
          t.addFrame(f);
          f.header().setFrameID(new ByteVector("TPE1"));
        }
        try {
          t.removeFrame(f);
        } catch (IllegalStateException expected) {
          threw = true;
        }
      } else {
        var t = new ChapterFrame(new ByteVector("c"), 0, 0, 0, 0);
        if (mode.endsWith("stale")) {
          t.addEmbeddedFrame(f);
          f.header().setFrameID(new ByteVector("TPE1"));
        }
        try {
          t.removeEmbeddedFrame(f);
        } catch (IllegalStateException expected) {
          threw = true;
        }
      }
      if (!threw) throw new AssertionError(mode);
      System.out.println(mode + "\tIllegalStateException");
    }
  }
}
