package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.internal.TextBytes;
import cz.skyba.taglib.toolkit.ByteVector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class TimedFramesTest {
  static ByteVector bytes(String hex) {
    return new ByteVector(HexFormat.of().parseHex(hex));
  }

  static ByteVector wire(String id, String hex) {
    return PictureObjectFramesTest.wire(id, hex);
  }

  @AfterEach
  void restoreDecoder() {
    Id3Text.setHandler(null);
  }

  @Test
  void eventDefaultsAndEmptyReparseKeepListAndFormat() {
    var f = new EventTimingCodesFrame();
    assertEquals(EventTimingCodesFrame.TimestampFormat.AbsoluteMilliseconds, f.timestampFormat());
    assertEquals(List.of(""), f.toStringList());
    assertEquals(wire("ETCO", "02"), f.render());
    f.setSynchedEvents(List.of(new EventTimingCodesFrame.SynchedEvent(7, EventTimingCodesFrame.EventType.IntroStart)));
    f.setTimestampFormat(EventTimingCodesFrame.TimestampFormat.AbsoluteMpegFrames);
    f.setData(wire("ETCO", ""));
    assertEquals(EventTimingCodesFrame.TimestampFormat.AbsoluteMpegFrames, f.timestampFormat());
    assertEquals(7, f.synchedEvents().getFirst().time());
  }

  @Test
  void eventNonemptyReparseClearsListAndIgnoresTrailingShortRecord() {
    var f = new EventTimingCodesFrame();
    f.setSynchedEvents(List.of(new EventTimingCodesFrame.SynchedEvent(7, EventTimingCodesFrame.EventType.IntroStart)));
    f.setData(wire("ETCO", "ff0201020304ff0506"));
    assertEquals(EventTimingCodesFrame.TimestampFormat.Unknown, f.timestampFormat());
    assertEquals(1, f.synchedEvents().size());
    assertEquals(0x01020304L, f.synchedEvents().getFirst().time());
    assertEquals(EventTimingCodesFrame.EventType.IntroStart, f.synchedEvents().getFirst().type());
  }

  @Test
  void unnamedEventTypeAndUnsignedTimestampSurviveExactSerialization() {
    var f = new EventTimingCodesFrame(wire("ETCO", "02ffffffffff"));
    assertEquals(255, f.synchedEvents().getFirst().type().code());
    assertEquals(0xffffffffL, f.synchedEvents().getFirst().time());
    assertEquals(wire("ETCO", "02ffffffffff"), f.render());
  }

  @Test
  void eventListsRemainDetachedWithImmutableEntries() {
    var entry = new EventTimingCodesFrame.SynchedEvent(0x1_0000_0003L, EventTimingCodesFrame.EventType.IntroStart);
    var list = new ArrayList<>(List.of(entry));
    var f = new EventTimingCodesFrame();
    f.setSynchedEvents(list);
    list.set(0, new EventTimingCodesFrame.SynchedEvent(9, EventTimingCodesFrame.EventType.AudioEnd));
    list.clear();
    var copy = f.synchedEvents();
    copy.set(0, new EventTimingCodesFrame.SynchedEvent(17, EventTimingCodesFrame.EventType.AudioFileEnds));
    copy.clear();
    assertEquals(3, f.synchedEvents().getFirst().time());
    assertEquals(EventTimingCodesFrame.EventType.IntroStart, f.synchedEvents().getFirst().type());
    var equal = new EventTimingCodesFrame.SynchedEvent(3, EventTimingCodesFrame.EventType.IntroStart);
    assertEquals(equal, entry);
    assertEquals(equal.hashCode(), entry.hashCode());
    assertThrows(NullPointerException.class, () -> new EventTimingCodesFrame.SynchedEvent(0, null));
    var before = f.render();
    assertThrows(NullPointerException.class, () -> f.setSynchedEvents(Arrays.asList(entry, null)));
    assertEquals(before, f.render());
  }

  @Test
  void lyricsDefaultsAndLanguageClipRemainNativeState() {
    var f = new SynchronizedLyricsFrame();
    assertEquals(ISO_8859_1, f.textEncoding());
    assertTrue(f.language().isEmpty());
    assertEquals(SynchronizedLyricsFrame.TimestampFormat.AbsoluteMilliseconds, f.timestampFormat());
    assertEquals(SynchronizedLyricsFrame.Type.Lyrics, f.type());
    assertEquals(List.of(""), f.toStringList());
    assertEquals(wire("SYLT", "00585858020100"), f.render());
    var language = new ByteVector("eng-extra");
    f.setLanguage(language);
    language.clear();
    f.language().clear();
    assertEquals(new ByteVector("eng"), f.language());
    f.setLanguage(new ByteVector("en"));
    assertEquals(new ByteVector("en"), f.language());
    assertEquals(wire("SYLT", "00585858020100"), f.render());
  }

  @Test
  void lyricsShortBodyKeepsEveryOldField() {
    var f = new SynchronizedLyricsFrame(UTF_8);
    f.setLanguage(new ByteVector("eng"));
    f.setDescription("old");
    f.setSynchedText(List.of(new SynchronizedLyricsFrame.SynchedText(7, "old-text")));
    f.setData(wire("SYLT", "006672610108"));
    assertEquals(UTF_8, f.textEncoding());
    assertEquals(new ByteVector("eng"), f.language());
    assertEquals("old", f.description());
    assertEquals(7, f.synchedText().getFirst().time());
  }

  @Test
  void lyricsMissingDescriptionDelimiterChangesHeaderFieldsAndDescriptionButRetainsList() {
    var f = new SynchronizedLyricsFrame();
    f.setDescription("old");
    f.setSynchedText(List.of(new SynchronizedLyricsFrame.SynchedText(7, "old-text")));
    f.setData(wire("SYLT", "03667261ffff616263"));
    assertEquals(UTF_8, f.textEncoding());
    assertEquals(new ByteVector("fra"), f.language());
    assertEquals(SynchronizedLyricsFrame.TimestampFormat.Unknown, f.timestampFormat());
    assertEquals(SynchronizedLyricsFrame.Type.Other, f.type());
    assertEquals("", f.description());
    assertEquals("old-text", f.synchedText().getFirst().text());
  }

  @Test
  void lyricsMissingEntryDelimiterUsesTextBytesAsTimeWhenFourRemain() {
    var f = new SynchronizedLyricsFrame(wire("SYLT", "00656e6702010061626364"));
    assertEquals(1, f.synchedText().size());
    assertEquals("", f.synchedText().getFirst().text());
    assertEquals(0x61626364L, f.synchedText().getFirst().time());
  }

  @Test
  void lyricsShortTimestampStopsAfterRetainingCompletedEntries() {
    var f = new SynchronizedLyricsFrame(wire("SYLT", "00656e6702010061000102030462000102"));
    assertEquals(1, f.synchedText().size());
    assertEquals("a", f.synchedText().getFirst().text());
    assertEquals(0x01020304L, f.synchedText().getFirst().time());
  }

  @Test
  void lyricsDescriptorBomIsInheritedByEntriesWithoutTheirOwnBom() {
    var f = new SynchronizedLyricsFrame(wire("SYLT", "01656e670201fffe640000006100000001020304feff0062000005060708"));
    assertEquals("d", f.description());
    assertEquals(2, f.synchedText().size());
    assertEquals("a", f.synchedText().get(0).text());
    assertEquals("b", f.synchedText().get(1).text());
    assertEquals(0x05060708L, f.synchedText().get(1).time());
  }

  @Test
  void lyricsFailedDescriptorCallbackKeepsOldDescriptionAndListAfterEarlierFieldsChange() {
    var f = new SynchronizedLyricsFrame(UTF_8);
    f.setDescription("old");
    f.setSynchedText(List.of(new SynchronizedLyricsFrame.SynchedText(7, "old-text")));
    Id3Text.setHandler(new Latin1StringHandler() {
      public String parse(ByteVector data) {
        throw new IllegalArgumentException("callback");
      }
    });
    assertThrows(IllegalArgumentException.class, () -> f.setData(wire("SYLT", "0066726101086400610001020304")));
    assertEquals(ISO_8859_1, f.textEncoding());
    assertEquals(new ByteVector("fra"), f.language());
    assertEquals(SynchronizedLyricsFrame.TimestampFormat.AbsoluteMpegFrames, f.timestampFormat());
    assertEquals(SynchronizedLyricsFrame.Type.ImageUrls, f.type());
    assertEquals("old", f.description());
    assertEquals("old-text", f.synchedText().getFirst().text());
  }

  @Test
  void lyricsEntryCallbackThrowLeavesAlreadyCompletedEntriesAfterOldListClear() {
    var f = new SynchronizedLyricsFrame();
    f.setSynchedText(List.of(new SynchronizedLyricsFrame.SynchedText(7, "old-text")));
    int[] calls = {0};
    Id3Text.setHandler(new Latin1StringHandler() {
      public String parse(ByteVector data) {
        if (++calls[0] == 3) throw new IllegalArgumentException("third");
        return "decoded:" + TextBytes.decodeTerminated(data.data(), ISO_8859_1);
      }
    });
    assertThrows(IllegalArgumentException.class, () -> f.setData(wire("SYLT", "00656e6702016400610001020304620005060708")));
    assertEquals("decoded:d", f.description());
    assertEquals(1, f.synchedText().size());
    assertEquals("decoded:a", f.synchedText().getFirst().text());
    assertEquals(0x01020304L, f.synchedText().getFirst().time());
  }

  @Test
  void lyricsListsRemainDetachedWithImmutableNormalizedEntries() {
    var entry = new SynchronizedLyricsFrame.SynchedText(0x1_0000_0003L, "text");
    var list = new ArrayList<>(List.of(entry));
    var f = new SynchronizedLyricsFrame();
    f.setSynchedText(list);
    list.set(0, new SynchronizedLyricsFrame.SynchedText(99, "changed"));
    list.clear();
    var copy = f.synchedText();
    copy.set(0, new SynchronizedLyricsFrame.SynchedText(19, "detached"));
    copy.clear();
    assertEquals("text", f.synchedText().getFirst().text());
    assertEquals(3, f.synchedText().getFirst().time());
    var equal = new SynchronizedLyricsFrame.SynchedText(3, "text");
    assertEquals(equal, entry);
    assertEquals(equal.hashCode(), entry.hashCode());
    assertThrows(NullPointerException.class, () -> new SynchronizedLyricsFrame.SynchedText(0, null));
    var before = f.render();
    assertThrows(NullPointerException.class, () -> f.setSynchedText(Arrays.asList(entry, null)));
    assertEquals(before, f.render());
  }

  @Test
  void lyricsEncodingFallbackUsesEveryEntryAndKeepsRequestedEncoding() {
    var f = new SynchronizedLyricsFrame();
    f.setLanguage(new ByteVector("eng"));
    f.setDescription("d");
    f.setSynchedText(List.of(new SynchronizedLyricsFrame.SynchedText(7, "雪")));
    var h = f.header();
    var parsed = new SynchronizedLyricsFrame(f.render());
    assertSame(h, f.header());
    assertEquals(ISO_8859_1, f.textEncoding());
    assertEquals(UTF_8, parsed.textEncoding());
    assertEquals("雪", parsed.synchedText().getFirst().text());
    f.header().setVersion(3);
    assertEquals(1, f.render().at(10));
  }

  @Test
  void timedFrameConstructorsAvoidFurtherDerivedDispatchButReparseCallsIt() {
    class E extends EventTimingCodesFrame {
      boolean ready;
      int calls;

      E(ByteVector data) {
        super(data);
        ready = true;
      }

      protected void parseFields(ByteVector data) {
        assertTrue(ready);
        calls++;
        super.parseFields(data);
      }
    }
    class S extends SynchronizedLyricsFrame {
      boolean ready;
      int calls;

      S(ByteVector data) {
        super(data);
        ready = true;
      }

      protected void parseFields(ByteVector data) {
        assertTrue(ready);
        calls++;
        super.parseFields(data);
      }
    }
    var ed = wire("ETCO", "020201020304");
    var e = new E(ed);
    assertEquals(0, e.calls);
    e.setData(ed);
    assertEquals(1, e.calls);
    var sd = wire("SYLT", "00656e67020100");
    var s = new S(sd);
    assertEquals(0, s.calls);
    s.setData(sd);
    assertEquals(1, s.calls);
  }

  @Test
  void inheritedTextIsNoOpAndPropertiesRemainUnsupported() {
    for (var f : List.of(new EventTimingCodesFrame(), new SynchronizedLyricsFrame())) {
      f.setText("ignored");
      assertEquals("", f.toString());
      assertEquals(List.of(TextBytes.decodeTerminated(f.frameID().data(), ISO_8859_1)), f.asProperties().unsupportedData());
    }
  }

  @Test
  void upstreamParseEventTimingCodesFrame() {
    var f = new EventTimingCodesFrame(bytes("4554434f0000000b000002020000f35cfe0036ee80"));
    assertEquals(EventTimingCodesFrame.TimestampFormat.AbsoluteMilliseconds, f.timestampFormat());
    assertEquals(2, f.synchedEvents().size());
    assertEquals(EventTimingCodesFrame.EventType.IntroStart, f.synchedEvents().get(0).type());
    assertEquals(62300, f.synchedEvents().get(0).time());
    assertEquals(EventTimingCodesFrame.EventType.AudioFileEnds, f.synchedEvents().get(1).type());
    assertEquals(3600000, f.synchedEvents().get(1).time());
  }

  @Test
  void upstreamRenderEventTimingCodesFrame() {
    var f = new EventTimingCodesFrame();
    f.setTimestampFormat(EventTimingCodesFrame.TimestampFormat.AbsoluteMilliseconds);
    f.setSynchedEvents(List.of(new EventTimingCodesFrame.SynchedEvent(62300, EventTimingCodesFrame.EventType.IntroStart), new EventTimingCodesFrame.SynchedEvent(3600000, EventTimingCodesFrame.EventType.AudioFileEnds)));
    assertEquals(bytes("4554434f0000000b000002020000f35cfe0036ee80"), f.render());
  }

  static void assertNativeLyrics(SynchronizedLyricsFrame f, String descriptor) {
    assertEquals(ISO_8859_1, f.textEncoding());
    assertEquals(new ByteVector("eng"), f.language());
    assertEquals(SynchronizedLyricsFrame.TimestampFormat.AbsoluteMilliseconds, f.timestampFormat());
    assertEquals(SynchronizedLyricsFrame.Type.Lyrics, f.type());
    assertEquals(descriptor, f.description());
    assertEquals(2, f.synchedText().size());
    assertEquals("Example", f.synchedText().get(0).text());
    assertEquals(1234, f.synchedText().get(0).time());
    assertEquals("Lyrics", f.synchedText().get(1).text());
    assertEquals(4567, f.synchedText().get(1).time());
  }

  @Test
  void upstreamParseSynchronizedLyricsFrame() {
    assertNativeLyrics(new SynchronizedLyricsFrame(bytes("53594c5400000021000000656e670201666f6f004578616d706c6500000004d24c797269637300000011d7")), "foo");
  }

  @Test
  void upstreamParseSynchronizedLyricsFrameWithEmptyDescritpion() {
    assertNativeLyrics(new SynchronizedLyricsFrame(bytes("53594c540000001e000000656e670201004578616d706c6500000004d24c797269637300000011d7")), "");
  }

  @Test
  void upstreamRenderSynchronizedLyricsFrame() {
    var f = new SynchronizedLyricsFrame();
    f.setTextEncoding(ISO_8859_1);
    f.setLanguage(new ByteVector("eng"));
    f.setTimestampFormat(SynchronizedLyricsFrame.TimestampFormat.AbsoluteMilliseconds);
    f.setType(SynchronizedLyricsFrame.Type.Lyrics);
    f.setDescription("foo");
    f.setSynchedText(List.of(new SynchronizedLyricsFrame.SynchedText(1234, "Example"), new SynchronizedLyricsFrame.SynchedText(4567, "Lyrics")));
    assertEquals(bytes("53594c5400000021000000656e670201666f6f004578616d706c6500000004d24c797269637300000011d7"), f.render());
  }
}
