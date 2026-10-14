package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.TagString;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Timed-frame operations and private constructors; public factory acceptance remains open.
 */
public class Id3TimedProbe {
  static ByteVector bytes(String s) {
    return s.equals("-") ? new ByteVector() : new ByteVector(HexFormat.of().parseHex(s));
  }

  static String hex(ByteVector v) {
    return v.toHex().toString();
  }

  static String string(String s) {
    return TagString.from8Bit(bytes(s).data(), TagString.Type.UTF8).toString();
  }

  static String text(String s) {
    return hex(new TagString(s).data(TagString.Type.UTF16BE));
  }

  static int b(boolean v) {
    return v ? 1 : 0;
  }

  static String fields(List<String> v) {
    var out = new StringBuilder(v.size() + ":");
    v.forEach(s -> out.append(text(s)).append('|'));
    return out.toString();
  }

  static String props(PropertyMap p) {
    var out = new StringBuilder();
    p.asMap().forEach((k, v) -> out.append(text(k)).append('=').append(fields(v)).append(';'));
    out.append('|');
    p.unsupportedData().forEach(s -> out.append(text(s)).append(','));
    return out.toString();
  }

  static class Utf8 extends Latin1StringHandler {
    public String parse(ByteVector data) {
      return new TagString(data, TagString.Type.UTF8).toString();
    }
  }

  static Frame create(String[] p) {
    var data = bytes(p[5]);
    boolean e = p[1].equals("E"), parsed = p[2].equals("P");
    if (p[2].equals("F")) {
      var h = new Frame.Header(data, Long.parseUnsignedLong(p[3]));
      if (h.version() < 3) h.setFrameID(new ByteVector(e ? "ETCO" : "SYLT"));
      return e ? new EventTimingCodesFrame(data, h) : new SynchronizedLyricsFrame(data, h);
    }
    return e
      ? (parsed ? new EventTimingCodesFrame(data) : new EventTimingCodesFrame())
      : (parsed
      ? new SynchronizedLyricsFrame(data)
      : new SynchronizedLyricsFrame(TagString.Type.values()[Integer.parseInt(p[7])]));
  }

  static void state(String id, Frame f) {
    var h = f.header();
    var detail = new StringBuilder();
    if (f instanceof EventTimingCodesFrame e) {
      var entries = e.synchedEvents();
      detail.append(e.timestampFormat().code()).append(':').append(entries.size()).append(':');
      entries.forEach(x -> detail.append(x.time()).append('@').append(x.type().code()).append(';'));
    } else {
      var s = (SynchronizedLyricsFrame) f;
      var entries = s.synchedText();
      detail
        .append(s.textEncoding().ordinal())
        .append(':')
        .append(hex(s.language()))
        .append(':')
        .append(s.timestampFormat().code())
        .append(':')
        .append(s.type().code())
        .append(':')
        .append(text(s.description()))
        .append(':')
        .append(entries.size())
        .append(':');
      entries.forEach(x -> detail.append(x.time()).append('@').append(text(x.text())).append(';'));
    }
    System.out.println(
      id
        + "\t"
        + hex(f.frameID())
        + "\t"
        + f.size()
        + "\t"
        + f.headerSize()
        + "\t"
        + h.version()
        + "\t"
        + text(f.toString())
        + "\t"
        + fields(f.toStringList())
        + "\t"
        + detail
        + "\t"
        + props(f.asProperties())
        + "\t"
        + b(h.tagAlterPreservation())
        + b(h.fileAlterPreservation())
        + b(h.readOnly())
        + b(h.groupingIdentity())
        + b(h.compression())
        + b(h.encryption())
        + b(h.unsynchronisation())
        + b(h.dataLengthIndicator()));
  }

  static void mutate(Frame f, String[] p) {
    f.setText("ignored");
    var parts = p[12].split(";", -1);
    if (f instanceof EventTimingCodesFrame e) {
      e.setTimestampFormat(EventTimingCodesFrame.TimestampFormat.values()[Integer.parseInt(p[8])]);
      var entries = new ArrayList<EventTimingCodesFrame.SynchedEvent>();
      for (String part : parts)
        if (!part.equals("-")) {
          var a = part.split(":", -1);
          entries.add(
            new EventTimingCodesFrame.SynchedEvent(
              Long.parseUnsignedLong(a[0]),
              EventTimingCodesFrame.EventType.fromByte((byte) Integer.parseInt(a[1]))));
        }
      e.setSynchedEvents(entries);
      entries.clear();
    }
    if (f instanceof SynchronizedLyricsFrame s) {
      s.setTextEncoding(TagString.Type.values()[Integer.parseInt(p[7])]);
      s.setLanguage(bytes(p[11]));
      s.setTimestampFormat(
        SynchronizedLyricsFrame.TimestampFormat.values()[Integer.parseInt(p[8])]);
      s.setType(SynchronizedLyricsFrame.Type.values()[Integer.parseInt(p[9])]);
      s.setDescription(string(p[10]));
      var entries = new ArrayList<SynchronizedLyricsFrame.SynchedText>();
      for (String part : parts)
        if (!part.equals("-")) {
          var a = part.split(":", -1);
          entries.add(
            new SynchronizedLyricsFrame.SynchedText(Long.parseUnsignedLong(a[0]), string(a[1])));
        }
      s.setSynchedText(entries);
      if (!entries.isEmpty()) entries.set(0, new SynchronizedLyricsFrame.SynchedText(entries.getFirst().time(), "detached"));
      entries.clear();
    }
  }

  static class EventChild extends EventTimingCodesFrame {
    int calls;

    EventChild(ByteVector d) {
      super(d);
    }

    protected void parseFields(ByteVector d) {
      calls++;
      super.parseFields(d);
    }
  }

  static class LyricsChild extends SynchronizedLyricsFrame {
    int calls;

    LyricsChild(ByteVector d) {
      super(d);
    }

    protected void parseFields(ByteVector d) {
      calls++;
      super.parseFields(d);
    }
  }

  static class Throw extends Latin1StringHandler {
    int calls;
    final int fail;

    Throw(int n) {
      fail = n;
    }

    public String parse(ByteVector d) {
      if (++calls == fail) throw new IllegalArgumentException("callback");
      return "decoded:" + new TagString(d);
    }
  }

  static void helpers() {
    System.out.println("helper/named/Padding\t" + EventTimingCodesFrame.EventType.Padding.code());
    System.out.println(
      "helper/named/EndOfInitialSilence\t"
        + EventTimingCodesFrame.EventType.EndOfInitialSilence.code());
    System.out.println(
      "helper/named/IntroStart\t" + EventTimingCodesFrame.EventType.IntroStart.code());
    System.out.println(
      "helper/named/MainPartStart\t" + EventTimingCodesFrame.EventType.MainPartStart.code());
    System.out.println(
      "helper/named/OutroStart\t" + EventTimingCodesFrame.EventType.OutroStart.code());
    System.out.println("helper/named/OutroEnd\t" + EventTimingCodesFrame.EventType.OutroEnd.code());
    System.out.println(
      "helper/named/VerseStart\t" + EventTimingCodesFrame.EventType.VerseStart.code());
    System.out.println(
      "helper/named/RefrainStart\t" + EventTimingCodesFrame.EventType.RefrainStart.code());
    System.out.println(
      "helper/named/InterludeStart\t" + EventTimingCodesFrame.EventType.InterludeStart.code());
    System.out.println(
      "helper/named/ThemeStart\t" + EventTimingCodesFrame.EventType.ThemeStart.code());
    System.out.println(
      "helper/named/VariationStart\t" + EventTimingCodesFrame.EventType.VariationStart.code());
    System.out.println(
      "helper/named/KeyChange\t" + EventTimingCodesFrame.EventType.KeyChange.code());
    System.out.println(
      "helper/named/TimeChange\t" + EventTimingCodesFrame.EventType.TimeChange.code());
    System.out.println(
      "helper/named/MomentaryUnwantedNoise\t"
        + EventTimingCodesFrame.EventType.MomentaryUnwantedNoise.code());
    System.out.println(
      "helper/named/SustainedNoise\t" + EventTimingCodesFrame.EventType.SustainedNoise.code());
    System.out.println(
      "helper/named/SustainedNoiseEnd\t"
        + EventTimingCodesFrame.EventType.SustainedNoiseEnd.code());
    System.out.println("helper/named/IntroEnd\t" + EventTimingCodesFrame.EventType.IntroEnd.code());
    System.out.println(
      "helper/named/MainPartEnd\t" + EventTimingCodesFrame.EventType.MainPartEnd.code());
    System.out.println("helper/named/VerseEnd\t" + EventTimingCodesFrame.EventType.VerseEnd.code());
    System.out.println(
      "helper/named/RefrainEnd\t" + EventTimingCodesFrame.EventType.RefrainEnd.code());
    System.out.println("helper/named/ThemeEnd\t" + EventTimingCodesFrame.EventType.ThemeEnd.code());
    System.out.println(
      "helper/named/Profanity\t" + EventTimingCodesFrame.EventType.Profanity.code());
    System.out.println(
      "helper/named/ProfanityEnd\t" + EventTimingCodesFrame.EventType.ProfanityEnd.code());
    System.out.println(
      "helper/named/NotPredefinedSynch0\t"
        + EventTimingCodesFrame.EventType.NotPredefinedSynch0.code());
    System.out.println(
      "helper/named/NotPredefinedSynch1\t"
        + EventTimingCodesFrame.EventType.NotPredefinedSynch1.code());
    System.out.println(
      "helper/named/NotPredefinedSynch2\t"
        + EventTimingCodesFrame.EventType.NotPredefinedSynch2.code());
    System.out.println(
      "helper/named/NotPredefinedSynch3\t"
        + EventTimingCodesFrame.EventType.NotPredefinedSynch3.code());
    System.out.println(
      "helper/named/NotPredefinedSynch4\t"
        + EventTimingCodesFrame.EventType.NotPredefinedSynch4.code());
    System.out.println(
      "helper/named/NotPredefinedSynch5\t"
        + EventTimingCodesFrame.EventType.NotPredefinedSynch5.code());
    System.out.println(
      "helper/named/NotPredefinedSynch6\t"
        + EventTimingCodesFrame.EventType.NotPredefinedSynch6.code());
    System.out.println(
      "helper/named/NotPredefinedSynch7\t"
        + EventTimingCodesFrame.EventType.NotPredefinedSynch7.code());
    System.out.println(
      "helper/named/NotPredefinedSynch8\t"
        + EventTimingCodesFrame.EventType.NotPredefinedSynch8.code());
    System.out.println(
      "helper/named/NotPredefinedSynch9\t"
        + EventTimingCodesFrame.EventType.NotPredefinedSynch9.code());
    System.out.println(
      "helper/named/NotPredefinedSynchA\t"
        + EventTimingCodesFrame.EventType.NotPredefinedSynchA.code());
    System.out.println(
      "helper/named/NotPredefinedSynchB\t"
        + EventTimingCodesFrame.EventType.NotPredefinedSynchB.code());
    System.out.println(
      "helper/named/NotPredefinedSynchC\t"
        + EventTimingCodesFrame.EventType.NotPredefinedSynchC.code());
    System.out.println(
      "helper/named/NotPredefinedSynchD\t"
        + EventTimingCodesFrame.EventType.NotPredefinedSynchD.code());
    System.out.println(
      "helper/named/NotPredefinedSynchE\t"
        + EventTimingCodesFrame.EventType.NotPredefinedSynchE.code());
    System.out.println(
      "helper/named/NotPredefinedSynchF\t"
        + EventTimingCodesFrame.EventType.NotPredefinedSynchF.code());
    System.out.println("helper/named/AudioEnd\t" + EventTimingCodesFrame.EventType.AudioEnd.code());
    System.out.println(
      "helper/named/AudioFileEnds\t" + EventTimingCodesFrame.EventType.AudioFileEnds.code());
    for (int n = 0; n < 256; n++) {
      var e = new EventTimingCodesFrame();
      e.setSynchedEvents(
        List.of(
          new EventTimingCodesFrame.SynchedEvent(
            0xffffffffL, EventTimingCodesFrame.EventType.fromByte((byte) n))));
      System.out.println(
        "helper/event\t"
          + n
          + "\t"
          + e.synchedEvents().getFirst().type().code()
          + "\t"
          + hex(e.render()));
    }
    var ed = bytes("4554434f000000060000020201020304");
    var e = new EventChild(ed);
    System.out.println("virtual/E/ctor\t" + e.calls);
    e.setData(ed);
    System.out.println("virtual/E/reparse\t" + e.calls);
    state("virtual/E/state", e);
    var sd = bytes("53594c5400000015000000656e6702016400610001020304620005060708");
    var s = new LyricsChild(sd);
    System.out.println("virtual/S/ctor\t" + s.calls);
    s.setData(sd);
    System.out.println("virtual/S/reparse\t" + s.calls);
    state("virtual/S/state", s);
    for (int fail = 1; fail <= 3; fail++) {
      var f = new SynchronizedLyricsFrame(TagString.Type.UTF8);
      f.setDescription("old");
      f.setSynchedText(List.of(new SynchronizedLyricsFrame.SynchedText(7, "old-text")));
      var handler = new Throw(fail);
      Id3Text.setHandler(handler);
      boolean threw = false;
      try {
        f.setData(sd);
      } catch (IllegalArgumentException expected) {
        threw = true;
      } finally {
        Id3Text.setHandler(null);
      }
      System.out.println("callback/" + fail + "\t" + b(threw) + "\t" + handler.calls);
      state("callback/" + fail + "-state", f);
    }
  }

  public static void main(String[] args) throws Exception {
    var utf8 = new Utf8();
    try (var input = Files.newBufferedReader(Path.of(args[0]))) {
      String line;
      while ((line = input.readLine()) != null) {
        var p = line.split("\t", -1);
        if (p.length != 13) throw new IllegalArgumentException(line);
        String id = p[0];
        Id3Text.setHandler(p[4].equals("1") ? utf8 : null);
        var f = create(p);
        var h = f.header();
        state(id + "/ctor", f);
        System.out.println(id + "/render\t" + hex(f.render()) + "\t" + b(h == f.header()));
        state(id + "/after-render", f);
        f.setData(bytes(p[6]));
        state(id + "/reparse", f);
        mutate(f, p);
        f.header().setVersion(Long.parseUnsignedLong(p[3]));
        state(id + "/mutate", f);
        System.out.println(id + "/mutate-render\t" + hex(f.render()));
        state(id + "/mutate-after", f);
        var copy = f.toStringList();
        copy.set(0, "detached");
        copy.add("added");
        System.out.println(id + "/list-copy\t" + fields(copy) + "\t" + fields(f.toStringList()));
        if (f instanceof EventTimingCodesFrame e) {
          var entries = e.synchedEvents();
          if (!entries.isEmpty()) {
            entries.set(0, new EventTimingCodesFrame.SynchedEvent(99, EventTimingCodesFrame.EventType.Padding));
          }
          entries.clear();
        } else {
          var s = (SynchronizedLyricsFrame) f;
          var entries = s.synchedText();
          if (!entries.isEmpty()) {
            entries.set(0, new SynchronizedLyricsFrame.SynchedText(99, "detached"));
          }
          entries.clear();
          s.language().clear();
        }
        state(id + "/detached", f);
        f.setData(f.render());
        state(id + "/serialized-reparse", f);
        System.out.println(id + "/serialized-render\t" + hex(f.render()));
        Id3Text.setHandler(null);
      }
    } finally {
      Id3Text.setHandler(null);
    }
    helpers();
  }
}
