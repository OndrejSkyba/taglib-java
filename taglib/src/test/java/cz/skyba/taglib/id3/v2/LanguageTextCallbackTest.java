package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.TagString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LanguageTextCallbackTest {
  @AfterEach
  void restoreHandler() {
    Id3Text.setHandler(null);
  }

  private static ByteVector wire(String id, String hex) {
    var body = new ByteVector(HexFormat.of().parseHex(hex));
    return new ByteVector(id).append(ByteVector.fromUnsignedInt(body.size())).append(new ByteVector(2)).append(body);
  }

  private static String description(Frame frame) {
    return frame instanceof CommentsFrame comment ? comment.description()
      : ((UnsynchronizedLyricsFrame) frame).description();
  }

  private static ByteVector language(Frame frame) {
    return frame instanceof CommentsFrame comment ? comment.language()
      : ((UnsynchronizedLyricsFrame) frame).language();
  }

  @Test
  void handlerSeesLanguageThenDescriptionBeforeSecondDecode() {
    for (Frame frame : List.of(new CommentsFrame(), new UnsynchronizedLyricsFrame())) {
      frame.setText("old-text");
      String id = new TagString(frame.frameID()).toString();
      var calls = new ArrayList<String>();
      Id3Text.setHandler(new Latin1StringHandler() {
        public String parse(ByteVector data) {
          calls.add(language(frame) + ":" + description(frame) + ":" + frame);
          return "decoded:" + new TagString(data);
        }
      });
      frame.setData(wire(id, "00656e67646573630074657874"));
      assertEquals(List.of("eng::old-text", "eng:decoded:desc:old-text"), calls);
      assertEquals("decoded:desc", description(frame));
      assertEquals("decoded:text", frame.toString());
    }
  }

  @Test
  void secondDecodeFailureRetainsCompletedDescriptionAndOldText() {
    for (Frame frame : List.of(new CommentsFrame(), new UnsynchronizedLyricsFrame())) {
      frame.setText("old-text");
      String id = new TagString(frame.frameID()).toString();
      var failure = new IllegalArgumentException("decode failed");
      Id3Text.setHandler(new Latin1StringHandler() {
        private int calls;

        public String parse(ByteVector data) {
          if (++calls == 2) {
            throw failure;
          }
          return "new-description";
        }
      });
      assertSame(failure, assertThrows(IllegalArgumentException.class,
        () -> frame.setData(wire(id, "00667261646573630074657874"))));
      assertEquals(new ByteVector("fra"), language(frame));
      assertEquals("new-description", description(frame));
      assertEquals("old-text", frame.toString());
    }
  }
}
