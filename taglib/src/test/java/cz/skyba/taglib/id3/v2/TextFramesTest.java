package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.internal.TextBytes;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.MutablePropertyMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;

import static java.nio.charset.StandardCharsets.*;
import static org.junit.jupiter.api.Assertions.*;

class TextFramesTest {
  static ByteVector bytes(String hex) {
    return new ByteVector(HexFormat.of().parseHex(hex));
  }

  static ByteVector wire(String id, ByteVector fields) {
    int n = fields.size();
    return new ByteVector(id).append(new ByteVector(new byte[]{(byte) (n >>> 21 & 127), (byte) (n >>> 14 & 127), (byte) (n >>> 7 & 127), (byte) (n & 127), 0, 0})).append(fields);
  }

  static ByteVector wire(String id, String hex) {
    return wire(id, bytes(hex));
  }

  @AfterEach
  void resetHandler() {
    Id3Text.setHandler(null);
  }

  @Test
  void nativeUserDefaultIncludesThreeEmptyFields() {
    var frame = new UserTextIdentificationFrame();
    assertEquals(List.of("", "", ""), frame.fieldList());
    assertEquals(List.of("", ""), frame.asProperties().get(""));
    assertEquals(bytes("54585858000000030000000000"), frame.render());
  }

  @Test
  void parsedUserConstructorRepairsFieldsOnlyDuringConstruction() {
    var frame = new UserTextIdentificationFrame(wire("TXXX", "00"));
    assertEquals(List.of("", ""), frame.fieldList());
    frame.setData(wire("TXXX", "000000"));
    assertEquals(List.of(), frame.fieldList());
    assertEquals("[] ", frame.toString());
    assertThrows(IllegalStateException.class, frame::asProperties);
    assertEquals(bytes("5458585800000001000000"), frame.render());
  }

  @Test
  void hiddenUserSetterAndExplicitBaseSetterHaveSeparateSemantics() {
    var frame = new UserTextIdentificationFrame("owner", List.of("old"));
    frame.setValues(List.of("one", "two"));
    assertEquals(List.of("owner", "one", "two"), frame.fieldList());
    TextIdentificationFrame base = frame;
    base.setText(List.of("description", "value"));
    assertEquals("description", frame.description());
    assertEquals(List.of("value"), frame.asProperties().get("DESCRIPTION"));
    Frame polymorphic = frame;
    polymorphic.setText("virtual");
    assertEquals(List.of("description", "virtual"), frame.fieldList());
  }

  @Test
  void userValueConstructorAllowsDescriptionWithNoValues() {
    var frame = new UserTextIdentificationFrame("owner", List.of());
    assertEquals(UTF_8, frame.textEncoding());
    assertEquals(List.of("owner"), frame.fieldList());
    assertEquals(PropertyMap.EMPTY, frame.asProperties());
    assertEquals("[owner] ", frame.toString());
  }

  @Test
  void partialTextReparseRetainsStoredEncodingAndFields() {
    var frame = new TextIdentificationFrame(wire("TCOM", "036f6e650074776f"));
    var h = frame.header();
    frame.setData(wire("TIT2", "01"));
    assertSame(h, frame.header());
    assertEquals(new ByteVector("TIT2"), frame.frameID());
    assertEquals(UTF_8, frame.textEncoding());
    assertEquals(List.of("one", "two"), frame.fieldList());
    assertEquals(List.of("one", "two"), frame.asProperties().get("TITLE"));
  }

  @Test
  void utf16LittleEndianEncodingByteFourIsAccepted() {
    var frame = new TextIdentificationFrame(wire("TCOM", "04410000004200"));
    assertEquals(UTF_16LE, frame.textEncoding());
    assertEquals(List.of("A", "B"), frame.fieldList());
    assertEquals(wire("TCOM", "04410000004200"), frame.render());
  }

  @Test
  void unrecognizedEncodingByteUsesLatin1() {
    var frame = new TextIdentificationFrame(wire("TCOM", "ff636166e9"));
    assertEquals(ISO_8859_1, frame.textEncoding());
    assertEquals(List.of("café"), frame.fieldList());
  }

  @Test
  void utf16LaterFieldsInheritFirstBomWithoutRewritingStoredEncoding() {
    var frame = new TextIdentificationFrame(wire("TCOM", "01fffe410000004200"));
    assertEquals(List.of("A", "B"), frame.fieldList());
    assertEquals(wire("TCOM", "01fffe41000000fffe4200"), frame.render());
  }

  @Test
  void nativeTrailingNullStrippingAndInteriorEmptyFieldsRemainDistinct() {
    var ordinary = new TextIdentificationFrame(wire("TCOM", "006f6e65000074776f000000"));
    assertEquals(List.of("one", "two"), ordinary.fieldList());
    var user = new UserTextIdentificationFrame(wire("TXXX", "000076616c756500"));
    assertEquals(List.of("", "value"), user.fieldList());
  }

  @Test
  void renderingFallbackDoesNotMutateRequestedEncoding() {
    var frame = new TextIdentificationFrame(new ByteVector("TIT2"), ISO_8859_1);
    frame.setText("Ž");
    assertEquals(wire("TIT2", "03c5bd"), frame.render());
    assertEquals(ISO_8859_1, frame.textEncoding());
    frame.header().setVersion(3);
    assertEquals(wire("TIT2", "01fffe7d01"), frame.render());
    assertEquals(ISO_8859_1, frame.textEncoding());
    assertEquals(5, frame.size());
  }

  @Test
  void detachedListsAndMutableHeaderPreserveOwnership() {
    var frame = new TextIdentificationFrame(wire("TCOM", "006f6e650074776f"));
    var fields = frame.fieldList();
    fields.set(0, "changed");
    fields.add("third");
    frame.toStringList().clear();
    frame.frameID().set(0, (byte) 'X');
    assertEquals(List.of("one", "two"), frame.fieldList());
    assertEquals(new ByteVector("TCOM"), frame.frameID());
    var h = frame.header();
    h.setFrameID(new ByteVector("TIT2"));
    assertEquals(List.of("one", "two"), frame.asProperties().get("TITLE"));
    frame.render();
    assertSame(h, frame.header());
    assertEquals(8, frame.size());
  }

  @Test
  void nativeGenreAndDateProjectionLeaveStoredFieldsUnchanged() {
    var genre = new TextIdentificationFrame(new ByteVector("TCON"), ISO_8859_1);
    genre.setText(List.of("17", "255", "-1", "2147483647", "17x"));
    assertEquals(List.of("Rock", "", "", "2147483647", "17x"), genre.asProperties().get("GENRE"));
    assertEquals("17", genre.fieldList().getFirst());
    var date = new TextIdentificationFrame(new ByteVector("TDRC"), UTF_8);
    date.setText("2026T10T05");
    assertEquals("2026 10T05", date.asProperties().first("DATE"));
    assertEquals("2026T10T05", date.toString());
  }

  @Test
  void rolesRequireEvenPairsAndRejectWholeFrameForUnknownRole() {
    var frame = new TextIdentificationFrame(new ByteVector("TIPL"), ISO_8859_1);
    frame.setText(List.of("arranger", "one,,two", "ENGINEER", "three"));
    assertEquals(List.of("one", "", "two"), frame.asProperties().get("ARRANGER"));
    frame.setText(List.of("ARRANGER", "one", "unknown", "two"));
    assertEquals(List.of("TIPL"), frame.asProperties().unsupportedData());
    assertTrue(frame.asProperties().isEmpty());
    frame.setText(List.of("odd"));
    assertEquals(List.of("TIPL"), frame.asProperties().unsupportedData());
  }

  @Test
  void instrumentCreationCanProduceUnsupportedEmptyInstrument() {
    var p = PropertyMap.builder();
    p.add("PERFORMER:", List.of("one"));
    var frame = TextIdentificationFrame.createTMCLFrame(p.build());
    assertEquals(List.of("", "one"), frame.fieldList());
    assertEquals(List.of("TMCL"), frame.asProperties().unsupportedData());
  }

  @Test
  void propertySplittingResetsValuesAndIdentifiersInReusedDestinations() {
    var original = new MutablePropertyMap(PropertyMap.builder()
      .put("TITLE", "title").put("ARRANGER", "arranger").put("PERFORMER:VIOLIN", "musician")
      .build());
    var single = new MutablePropertyMap();
    var tipl = new MutablePropertyMap();
    var tmcl = new MutablePropertyMap();
    for (var destination : List.of(single, tipl, tmcl)) {
      destination.put("STALE", List.of("old"));
      destination.addUnsupportedData("stale identifier");
    }
    Frame.splitProperties(original, single, tipl, tmcl);
    assertEquals(List.of("title"), single.values("TITLE"));
    assertEquals(List.of("arranger"), tipl.values("ARRANGER"));
    assertEquals(List.of("musician"), tmcl.values("PERFORMER:VIOLIN"));
    for (var destination : List.of(single, tipl, tmcl)) {
      assertEquals(1, destination.size());
      assertTrue(destination.unsupportedData().isEmpty());
    }
  }

  @Test
  void unsupportedRoleCreationCannotPoisonLaterTitleImports() {
    var properties = PropertyMap.builder().put("TITLE", "retained title").build();
    TextIdentificationFrame.createTIPLFrame(properties);
    assertFalse(TextIdentificationFrame.involvedPeopleMap().containsKey("TITLE"));
    var tag = new Tag();
    assertTrue(tag.replaceProperties(properties).rejected().isEmpty());
    assertEquals("retained title", tag.title());
    var reparsed = new TextIdentificationFrame(tag.frameList(new ByteVector("TIT2")).getFirst().render());
    assertEquals("retained title", reparsed.toString());
  }

  @Test
  void unknownRolesDoNotChangeTheMapOrLaterPropertyClassification() {
    var view = TextIdentificationFrame.involvedPeopleMap();
    var p = PropertyMap.builder();
    p.add("JUST-REGRESSION", List.of("ignored"));
    p.add("DJMIXER", List.of("one", "two"));
    var frame = TextIdentificationFrame.createTIPLFrame(p.build());
    assertNull(view.get("JUST-REGRESSION"));
    assertFalse(view.containsKey("JUST-REGRESSION"));
    assertEquals(List.of("DJ-MIX", "one,two"), frame.fieldList());
    var single = new MutablePropertyMap();
    var tipl = new MutablePropertyMap();
    var tmcl = new MutablePropertyMap();
    Frame.splitProperties(new MutablePropertyMap(p.build()), single, tipl, tmcl);
    assertTrue(single.containsKey("JUST-REGRESSION"));
    assertFalse(tipl.containsKey("JUST-REGRESSION"));
    assertTrue(tipl.containsKey("DJMIXER"));
    assertThrows(UnsupportedOperationException.class, () -> view.put("DJMIXER", "changed"));
  }

  @Test
  void readOnlyRoleMapLookupDoesNotInsertAnEntry() {
    var map = TextIdentificationFrame.involvedPeopleMap();
    assertFalse(map.containsKey("CONST-REGRESSION"));
    assertNull(map.get("CONST-REGRESSION"));
    assertFalse(map.containsKey("CONST-REGRESSION"));
    assertEquals(5, map.size());
    assertThrows(UnsupportedOperationException.class, () -> map.put("CONST-REGRESSION", "changed"));
  }

  @Test
  void userTranslationUsesActualSourceWithoutDocCommentRestrictions() {
    var frame = new UserTextIdentificationFrame("MusicBrainz Album Id", List.of("one", "two"));
    assertEquals(List.of("one", "two"), frame.asProperties().get("MUSICBRAINZ_ALBUMID"));
    frame.setDescription("custom::Name");
    assertEquals(List.of("one", "two"), frame.asProperties().get("CUSTOM::NAME"));
    frame.setDescription("");
    assertTrue(frame.asProperties().containsKey(""));
    assertEquals("custom::Name", UserTextIdentificationFrame.keyToTXXX("custom::Name"));
  }

  @Test
  void copiedNativeConcatenationTruncatesDescriptionButRetainsItsValue() {
    var user = new UserTextIdentificationFrame("a\0b", List.of("x\0y"));
    assertEquals("[a] x\0y", user.toString());
    assertEquals("a\0b", user.description());
    assertEquals(List.of("x\0y"), user.asProperties().get("A\0B"));
    var url = new UserUrlLinkFrame();
    url.setDescription("a\0b");
    url.setUrl("x\0y");
    assertEquals("[a] x\0y", url.toString());
    assertEquals("a\0b", url.description());
  }

  @Test
  void commentsPartialParseChangesLanguageAndEncodingBeforeStrings() {
    var frame = new CommentsFrame();
    frame.setDescription("old");
    frame.setText("text");
    frame.setData(wire("COMM", "036672616e657700"));
    assertEquals(UTF_8, frame.textEncoding());
    assertEquals(new ByteVector("fra"), frame.language());
    assertEquals("old", frame.description());
    assertEquals("text", frame.text());
    frame.setData(wire("COMM", "00656e67006e6577"));
    assertEquals("", frame.description());
    assertEquals("new", frame.text());
  }

  @Test
  void commentsAndLyricsHaveSeparateDefaultPrefixesAndLanguageFallback() {
    var comment = new CommentsFrame();
    comment.setLanguage(new ByteVector("fr"));
    comment.setText("one");
    comment.setDescription("COMMENT");
    assertEquals(new ByteVector("fr"), comment.language());
    assertEquals(wire("COMM", "00585858434f4d4d454e54006f6e65"), comment.render());
    assertEquals("one", comment.asProperties().first("COMMENT"));
    var lyrics = new UnsynchronizedLyricsFrame();
    lyrics.setDescription("LYRICS");
    lyrics.setText("two");
    assertEquals("two", lyrics.asProperties().first("LYRICS"));
    lyrics.setDescription("verse");
    assertEquals("two", lyrics.asProperties().first("LYRICS:VERSE"));
  }

  @Test
  void customHandlerAffectsTextCommentsLyricsAndUfidOwnerOnly() {
    Id3Text.setHandler(new Latin1StringHandler() {
      public String parse(ByteVector data) {
        return TextBytes.decodeTerminated(data.data(), UTF_8);
      }
    });
    assertEquals("é", new TextIdentificationFrame(wire("TCOM", "00c3a9")).toString());
    assertEquals("é", new CommentsFrame(wire("COMM", "00656e67c3a900c3a9")).description());
    assertEquals("é", new UnsynchronizedLyricsFrame(wire("USLT", "00656e67c3a900c3a9")).text());
    assertEquals("é", new UniqueFileIdentifierFrame(wire("UFID", "c3a900c3a9")).owner());
    assertEquals("Ã©", new PrivateFrame(wire("PRIV", "c3a900c3a9")).owner());
    assertEquals("Ã©", new UrlLinkFrame(wire("WOAF", "c3a9")).url());
    var userUrl = new UserUrlLinkFrame(wire("WXXX", "00c3a900c3a9"));
    assertEquals("Ã©", userUrl.description());
    assertEquals("Ã©", userUrl.url());
  }

  @Test
  void throwingHandlerLeavesNativePartialFieldStateAndUpdatedHeader() {
    int[] calls = {0};
    Id3Text.setHandler(new Latin1StringHandler() {
      public String parse(ByteVector data) {
        if (++calls[0] == 2) throw new IllegalArgumentException("second");
        return "decoded:" + TextBytes.decodeTerminated(data.data(), ISO_8859_1);
      }
    });
    var frame = new TextIdentificationFrame(new ByteVector("TCOM"), UTF_8);
    frame.setText("old");
    var h = frame.header();
    assertThrows(IllegalArgumentException.class, () -> frame.setData(wire("TIT2", "006f6e650074776f")));
    assertSame(h, frame.header());
    assertEquals(new ByteVector("TIT2"), frame.frameID());
    assertEquals(8, frame.size());
    assertEquals(ISO_8859_1, frame.textEncoding());
    assertEquals(List.of("decoded:one"), frame.fieldList());
  }

  @Test
  void handlerReceivesDetachedBytesAndDefaultRestorationWorks() {
    Id3Text.setHandler(new Latin1StringHandler() {
      public String parse(ByteVector data) {
        var value = data.toString();
        data.clear();
        return value;
      }
    });
    var input = wire("TCOM", "006f6e650074776f");
    var before = new ByteVector(input);
    var frame = new TextIdentificationFrame(input);
    assertEquals(List.of("one", "two"), frame.fieldList());
    assertEquals(before, input);
    Id3Text.setHandler(null);
    assertEquals("café", new TextIdentificationFrame(wire("TCOM", "00636166e9")).toString());
  }

  @Test
  void userUrlConstructorRetainsBaseRawUrlOnMissingDelimiter() {
    var frame = new UserUrlLinkFrame(wire("WXXX", "03726177"));
    assertEquals("\3raw", frame.url());
    assertEquals("", frame.description());
    frame.setUrl("old");
    frame.setDescription("kept");
    frame.setData(wire("WXXX", "016e6f64656c696d"));
    assertEquals(UTF_16, frame.textEncoding());
    assertEquals("old", frame.url());
    assertEquals("kept", frame.description());
  }

  @Test
  void urlGetterAndSetterHooksDoNotOverrideBaseStoredFieldRendering() {
    class Child extends UrlLinkFrame {
      Child() {
        super(new ByteVector("WOAF"));
      }

      public String url() {
        return "virtual";
      }

      public void setUrl(String s) {
        super.setUrl("set:" + s);
      }
    }
    var frame = new Child();
    Frame base = frame;
    base.setText("value");
    assertEquals("virtual", base.toString());
    assertEquals("virtual", frame.asProperties().first("FILEWEBPAGE"));
    assertEquals(wire("WOAF", new ByteVector("set:value")), frame.render());
  }

  @Test
  void derivedParsingAndUserUrlSetterAreDeferredUntilAfterConstruction() {
    class TextChild extends TextIdentificationFrame {
      boolean ready;
      int calls;

      TextChild(ByteVector data) {
        super(data);
        ready = true;
      }

      protected void parseFields(ByteVector data) {
        assertTrue(ready);
        calls++;
        super.parseFields(data);
      }
    }
    var data = wire("TCOM", "006f6e65");
    var frame = new TextChild(data);
    assertEquals(0, frame.calls);
    frame.setData(data);
    assertEquals(1, frame.calls);
    class UrlChild extends UserUrlLinkFrame {
      boolean ready;
      int calls;

      UrlChild(ByteVector value) {
        super(value);
        ready = true;
      }

      public void setUrl(String value) {
        assertTrue(ready);
        calls++;
        super.setUrl("set:" + value);
      }
    }
    var urlData = wire("WXXX", "00646573630075726c");
    var url = new UrlChild(urlData);
    assertEquals(0, url.calls);
    assertEquals("url", url.url());
    url.setData(urlData);
    assertEquals(1, url.calls);
    assertEquals("set:url", url.url());
  }

  @Test
  void ufidMissingDelimiterAndMusicBrainzIdentifierFollowDifferentStringRules() {
    var frame = new UniqueFileIdentifierFrame(wire("UFID", "6f776e6572"));
    assertEquals("", frame.owner());
    assertEquals(new ByteVector("owner"), frame.identifier());
    frame.setOwner("http://musicbrainz.org");
    var id = bytes("c3a9007461696c");
    frame.setIdentifier(id);
    id.clear();
    frame.identifier().clear();
    assertEquals("Ã©", frame.asProperties().first("MUSICBRAINZ_TRACKID"));
    assertEquals(bytes("c3a9007461696c"), frame.identifier());
  }

  @Test
  void privateMissingDelimiterDuplicatesOwnerAndPayloadLikeNative() {
    var frame = new PrivateFrame(wire("PRIV", "6f776e6572"));
    assertEquals("owner", frame.owner());
    assertEquals(new ByteVector("owner"), frame.data());
    assertEquals(wire("PRIV", bytes("6f776e6572006f776e6572")), frame.render());
    frame.setPrivateData(new ByteVector("new"));
    assertEquals("owner", frame.owner());
    Frame base = frame;
    base.setData(wire("PRIV", "780079"));
    assertEquals("x", frame.owner());
    assertEquals(new ByteVector("y"), frame.data());
  }

  @Test
  void upstreamParsePrivateFrame() {
    // Every assertion from TestID3v2::testParsePrivateFrame, including its fixed wire size.
    var frame = new PrivateFrame(bytes("505249560000000e0000574d2f50726f766964657200544c"));
    assertEquals("WM/Provider", frame.owner());
    assertEquals(new ByteVector("TL"), frame.data());
  }

  @Test
  void upstreamRenderPrivateFrame() {
    var frame = new PrivateFrame();
    frame.setOwner("WM/Provider");
    frame.setPrivateData(new ByteVector("TL"));
    assertEquals(bytes("505249560000000e0000574d2f50726f766964657200544c"), frame.render());
  }

  @Test
  void upstreamRenderUserTextIdentificationFrame() {
    // Both successive mutations/assertions from the pinned registration remain in one test.
    var frame = new UserTextIdentificationFrame();
    frame.setDescription("");
    frame.setText("Text");
    assertEquals(bytes("54585858000000060000000054657874"), frame.render());
    frame.setDescription("Description");
    frame.setText("Text");
    assertEquals(bytes("54585858000000110000004465736372697074696f6e0054657874"), frame.render());
  }

  @Test
  void privateShortDataRetainsPayloadAndReturnedBytesAreDetached() {
    var frame = new PrivateFrame();
    frame.setOwner("owner");
    var value = bytes("010203");
    frame.setPrivateData(value);
    value.clear();
    frame.data().clear();
    frame.setData(wire("PRIV", "00"));
    assertEquals("owner", frame.owner());
    assertEquals(bytes("010203"), frame.data());
    assertEquals(List.of("PRIV"), frame.asProperties().unsupportedData());
  }

  @Test
  void podcastDefaultMarkerAndEmptyPropertyValuesRemainPresent() {
    var frame = new PodcastFrame();
    assertEquals(wire("PCST", "00000000"), frame.render());
    assertTrue(frame.asProperties().containsKey("PODCAST"));
    assertEquals(List.of(), frame.asProperties().get("PODCAST"));
    frame.setData(wire("PCST", ""));
    assertEquals(wire("PCST", ""), frame.render());
  }

  @Test
  void nativeBucketFinderUsesFirstTypedInstanceAndCaseSensitiveDescription() {
    var first = new UserTextIdentificationFrame("target", List.of("one"));
    var second = new UserTextIdentificationFrame("target", List.of("two"));
    var bucket = List.of(new UnknownFrame(new ByteVector("TXXX")), first, second);
    first.header().setFrameID(new ByteVector("XXXX"));
    assertSame(first, UserTextIdentificationFrame.find(bucket, "target"));
    assertNull(UserTextIdentificationFrame.find(bucket, "TARGET"));
    var ufid = new UniqueFileIdentifierFrame("owner", new ByteVector());
    assertSame(ufid, UniqueFileIdentifierFrame.findByOwner(List.of(ufid), "owner"));
  }
}
