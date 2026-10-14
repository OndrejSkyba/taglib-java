package cz.skyba.taglib.parity;

import cz.skyba.taglib.parity.support.SaveOutcome;

import cz.skyba.taglib.ReadOptions;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.parity.support.FileRefAdapter;
import cz.skyba.taglib.TagBase;
import cz.skyba.taglib.matroska.*;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.IOStream;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static cz.skyba.taglib.parity.MatroskaMutableProbe.*;

public class MatroskaMutableCallbacksAndSavingProbe {
  static class E extends Element {
    ByteVector next = new ByteVector();
    boolean accept = true;
    String calls = "";

    E(long id) {
      super(id);
    }

    long remembered() {
      return sizeRenderedOrWritten();
    }

    @Override
    protected ByteVector renderInternal() {
      return next;
    }

    @Override
    public boolean sizeChanged(Element caller, long delta) {
      calls += caller.id() + ":" + caller.offset() + ":" + delta + ",";
      super.sizeChanged(caller, delta);
      return accept;
    }
  }

  static class Raw extends FileBase {
    Raw(IOStream s) {
      super(s);
    }

    IOStream writableView() {
      return ownerStream();
    }

    public TagBase tag() {
      return null;
    }

    public AudioProperties audioProperties() {
      return null;
    }

    public void save() {
      throw new cz.skyba.taglib.TagLibException("Test save rejected");
    }
  }

  static String state(E e) {
    return e.id()
      + ":"
      + e.offset()
      + ":"
      + e.size()
      + ":"
      + e.remembered()
      + ":"
      + e.writeStyle().ordinal()
      + ":"
      + b(e.needsRender())
      + ":"
      + b(e.wasMoved())
      + ":"
      + b(e.isTrailingInSegment())
      + ":"
      + hx(e.data());
  }

  static String display(Chapter.Display d) {
    return hx(d.string()) + ":" + hx(d.language());
  }

  static String chapter(Chapter c) {
    String s =
      Long.toUnsignedString(c.uid())
        + ":"
        + Long.toUnsignedString(c.timeStart())
        + ":"
        + Long.toUnsignedString(c.timeEnd())
        + ":"
        + b(c.isHidden());
    for (var d : c.displayList()) s += "/" + display(d);
    return s;
  }

  static String edition(ChapterEdition e) {
    String s = Long.toUnsignedString(e.uid()) + ":" + b(e.isDefault()) + ":" + b(e.isOrdered());
    for (var c : e.chapterList()) s += "/" + chapter(c);
    return s;
  }

  static String attached(AttachedFile a) {
    return hx(a.byteVector())
      + ":"
      + hx(a.fileName())
      + ":"
      + hx(a.mediaType())
      + ":"
      + Long.toUnsignedString(a.uid())
      + ":"
      + hx(a.description());
  }

  public static void main(String[] args) throws Exception {
    for (int style = 0; style < 3; style++)
      for (boolean fail : new boolean[]{false, true}) {
        var e = new E(-1);
        var a = new E(2);
        var same = new E(-1);
        var stop = new E(3);
        e.setOffset(4);
        e.setSize(3);
        e.setAppendOffset(20);
        e.setWriteStyle(WriteStyle.values()[style]);
        e.setIsLastElement(false);
        e.setIsTrailingInSegment(true);
        a.setOffset(7);
        same.setOffset(9);
        stop.setOffset(12);
        stop.accept = !fail;
        e.addSizeListener(a);
        e.addSizeListeners(List.of(same, stop));
        String k = "element/" + style + "/" + b(fail);
        row(k + "/before", state(e));
        e.next = new ByteVector("abcdef");
        boolean result = e.render();
        row(
          k + "/render",
          b(result)
            + "\t"
            + state(e)
            + "\t"
            + state(a)
            + "\t"
            + state(same)
            + "\t"
            + state(stop)
            + "\t"
            + a.calls
            + "/"
            + same.calls
            + "/"
            + stop.calls);
        row(k + "/cached", b(e.render()) + "\t" + state(e));
        if (!fail) {
          byte[] bytes = new byte[20];
          Arrays.fill(bytes, (byte) 'x');
          var s = new ByteVectorStream(bytes);
          var f = new Raw(s);
          e.write(f.writableView());
          row(k + "/write", state(e) + "\t" + hx(s.data()) + "\t" + s.tell());
        }
        e.clearSizeListeners();
        e.setID(42);
        e.setNeedsRender(true);
        e.next = new ByteVector("a");
        e.adjustOffset(-1);
        row(k + "/shrink", b(e.render()) + "\t" + state(e));
      }
    {
      var e = new E(7);
      var other = new E(8);
      e.setData(new ByteVector("abc"));
      e.setSize(99);
      e.setOffset(3);
      other.setOffset(4);
      e.addSizeListener(other);
      e.emitSizeChanged(-2);
      row("element/direct", state(e) + "/" + state(other));
      e.clearSizeListeners();
      e.emitSizeChanged(9);
      row("element/clear", state(other));
    }
    var d = new Chapter.Display("First", "eng");
    var dc = new Chapter.Display(d);
    row("copy/display", display(d) + "/" + display(dc));
    var c = new Chapter(1, 2, List.of(d), -1, true);
    var cc = new Chapter(c);
    row("copy/chapter", chapter(c) + "|" + chapter(cc));
    var e = new ChapterEdition(List.of(c), true, true, 77);
    var ec = new ChapterEdition(e);
    row("copy/edition", edition(e) + "|" + edition(ec));
    var a = new AttachedFile(new ByteVector("data"), "file.bin", "application/bin", -1, "Desc");
    var ac = new AttachedFile(a);
    row("copy/attachment", attached(a) + "|" + attached(ac));
    var ch = new Chapters();
    ch.addChapterEdition(e);
    ch.addChapterEdition(e);
    ch.removeChapterEdition(77);
    row(
      "collection/chapters",
      ch.chapterEditionList().size() + "/" + edition(ch.chapterEditionList().getFirst()));
    var at = new Attachments();
    at.addAttachedFile(a);
    at.addAttachedFile(a);
    at.removeAttachedFile(a.uid());
    row(
      "collection/attachments",
      at.attachedFileList().size() + "/" + attached(at.attachedFileList().getFirst()));
    {
      var t = new cz.skyba.taglib.matroska.Tag();
      t.addSimpleTags(
        List.of(
          new SimpleTag("A", "One"),
          new SimpleTag(
            "A", "Two", SimpleTag.TargetTypeValue.Album, "eng", false, 1, 2, 3, 4)));
      t.removeSimpleTag("A", SimpleTag.TargetTypeValue.Album, 1, 2, 3, 4);
      row(
        "tag/add-list",
        t.simpleTagsList().size() + "/" + hx(t.simpleTagsList().getFirst().toString()));
      t.clearSimpleTags();
      row("tag/clear-list", b(t.isEmpty()));
    }
    for (long value : new long[]{0, 1, 2147483647L, 2147483648L, 4294967295L}) {
      var t = new cz.skyba.taglib.matroska.Tag();
      t.setYear(value);
      t.setTrack(value);
      var p = t.properties();
      String fields = t.year() + "/" + t.track();
      for (String key : List.of("DATE", "TRACKNUMBER")) {
        fields += "/";
        if (!p.get(key).isEmpty()) fields += hx(p.get(key).getFirst());
      }
      row("tag/unsigned/" + value, fields);
    }
    byte[] original = Files.readAllBytes(Path.of(args[0], "inputs/upstream-no-tags.mka"));
    for (int origin : new int[]{0, 3, 7}) {
      var s = new ByteVectorStream(original);
      s.seek(origin);
      row("support/" + origin, b(MatroskaFile.isSupported(s)) + "/" + s.tell());
    }
    {
      var s = new ByteVectorStream(original);
      try (var f = new MatroskaFile(s, ReadOptions.TAGS_ONLY)) {
        f.getOrCreateTag();
        f.getOrCreateChapters();
        f.getOrCreateAttachments();
        boolean saved = SaveOutcome.save(f);
        row(
          "empty/discard",
          b(saved)
            + "/"
            + b(f.existingTag() == null)
            + "/"
            + b(f.chapters() == null)
            + "/"
            + b(f.attachments() == null)
            + "/"
            + hx(s.data()));
      }
    }
    {
      var s = new ByteVectorStream(original);
      try (var f = new MatroskaFile(s, ReadOptions.TAGS_ONLY)) {
        f.getOrCreateAttachments()
          .addAttachedFile(
            new AttachedFile(new ByteVector("PAYLOAD"), "p.bin", "application/bin", 17));
        SaveOutcome.save(f);
        s.seek(0);
        try (var read = new MatroskaFile(s, ReadOptions.TAGS_ONLY)) {
          s.data().resize(s.data().size() - 3);
          s.seek(5);
          var v = read.attachments().attachedFileList().getFirst();
          row("deferred/short", attached(v) + "/" + s.tell());
        }
      }
    }
    {
      var s = new ByteVectorStream(original);
      try (var ref = new FileRefAdapter(s)) {
        s.data().resize(s.data().size() + 1000);
        row("fileref/lazy", b(ref.file() instanceof MatroskaFile) + "/" + ref.audioProperties().bitrate());
      }
    }
    {
      var s = new ByteVectorStream(original);
      try (var f = new MatroskaFile(s, ReadOptions.TAGS_ONLY)) {
        f.tag().setTitle("Large title");
        SaveOutcome.save(f, WriteStyle.DoNotShrink);
        f.tag().setTitle("s");
        SaveOutcome.save(f, WriteStyle.Compact);
        row("style/sticky", hx(s.data()));
      }
    }
  }
}
