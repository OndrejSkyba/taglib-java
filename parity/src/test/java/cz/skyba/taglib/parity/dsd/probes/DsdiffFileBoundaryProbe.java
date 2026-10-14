package cz.skyba.taglib.parity.dsd.probes;


import cz.skyba.taglib.parity.support.TagSelections;
import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.parity.support.SaveOutcome;

import cz.skyba.taglib.dsdiff.DsdiffFile;
import cz.skyba.taglib.id3.v2.Tag;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;

public class DsdiffFileBoundaryProbe {
  static ByteVector chunk(String id, ByteVector d) {
    return new ByteVector(id).append(ByteVector.fromUnsignedLong(d.size())).append(d);
  }

  static ByteVector chunk(String id) {
    return chunk(id, new ByteVector());
  }

  static ByteVector form(ByteVector d) {
    return new ByteVector("FRM8")
      .append(ByteVector.fromUnsignedLong(d.size() + 4))
      .append(new ByteVector("DSD "))
      .append(d);
  }

  public static void main(String[] args) {
    var prop = chunk("PROP", new ByteVector("SND "));
    var audio = chunk("DSD ", new ByteVector(2));
    var first = new ByteVectorStream(form(chunk("ID3 ").append(prop).append(audio)));
    var before = new ByteVector(first.data());
    try (var f = new DsdiffFile(first)) {
      try {
        f.strip(TagSelections.dsdiff(2));
        throw new AssertionError("unsafe first root accepted");
      } catch (IllegalStateException expected) {
      }
      if (!first.data().equals(before)) throw new AssertionError("first root changed");
      System.out.println("first-root\tIllegalStateException\tunchanged");
    }
    var stale =
      new ByteVectorStream(form(chunk("FVER").append(prop).append(audio).append(chunk("DIIN"))));
    try (var f = new DsdiffFile(stale)) {
      f.strip(TagSelections.dsdiff(1));
      f.diinTag().setTitle("x");
      before = new ByteVector(stale.data());
      if (SaveOutcome.save(f, 1, FileBase.StripTags.STRIP_NONE)
        || !stale.data().equals(before)) throw new AssertionError("stale parent accepted");
      System.out.println("stale-parent\tfalse\tunchanged");
    }
    var tag = new Tag();
    tag.setTitle("original");
    var grow =
      new ByteVectorStream(
        form(chunk("FVER").append(prop).append(audio).append(chunk("ID3 ", tag.render()))));
    try (var f = new DsdiffFile(grow)) {
      grow.truncate(0);
      f.id3v2Tag().setTitle("x".repeat(5000));
      if (SaveOutcome.save(f, 2, FileBase.StripTags.STRIP_NONE) || grow.data().size() != 0)
        throw new AssertionError("unsafe memory growth accepted");
      System.out.println("memory-grow\tfalse\tunchanged");
    }
  }
}
