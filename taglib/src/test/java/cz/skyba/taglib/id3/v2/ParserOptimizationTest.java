package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.internal.ByteVectorOutput;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParserOptimizationTest {
  private static ByteVector textFrame(String text) {
    var fields = new ByteVector((byte) 0).append(new ByteVector(text));
    return new ByteVector("TIT2").append(SynchData.fromUnsignedInt(fields.size())).append(new ByteVector(2)).append(fields);
  }

  private static ByteVector tagBytes(ByteVector body) {
    var header = new Header();
    header.setTagSize(body.size());
    return header.render().append(body);
  }

  @Test
  void stockParserHandlesManySmallFramesAndBulkRemoval() {
    var body = new ByteVectorOutput();
    for (int i = 0; i < 2000; i++) body.append(textFrame(Integer.toString(i)));
    body.zeros(20);
    try (var file = new TagOwnerTest.Bare(new ByteVectorStream(tagBytes(body.toByteVector())))) {
      var tag = new Tag(file, 0);
      assertEquals(2000, tag.frameList().size());
      assertEquals("1999", tag.frameList().getLast().toString());
      tag.removeFrames(new ByteVector("TIT2"));
      assertTrue(tag.frameList().isEmpty());
    }
  }

  @Test
  void customFactoryStillReceivesCompleteTails() {
    var first = textFrame("a");
    var second = textFrame("b");
    var body = new ByteVector(first).append(second).append(new ByteVector(20));
    var lengths = new ArrayList<Integer>();
    var factory = new FrameFactory() {
      @Override
      public Frame createFrame(ByteVector data, Header header) {
        lengths.add(data.size());
        return super.createFrame(data, header);
      }
    };
    try (var file = new TagOwnerTest.Bare(new ByteVectorStream(tagBytes(body)))) {
      var tag = new Tag(file, 0, factory);
      assertEquals(2, tag.frameList().size());
      assertEquals(List.of(body.size(), body.size() - first.size()), lengths);
    }
  }

  @Test
  void v4OrdinarySizeFallbackStillLooksAtTheFollowingFrame() {
    var payload = new ByteVector(300, (byte) 'a');
    payload.set(0, (byte) 0);
    var first = new ByteVector("TIT2").append(ByteVector.fromUnsignedInt(300)).append(new ByteVector(2)).append(payload);
    var body = first.append(textFrame("second")).append(new ByteVector(20));
    try (var file = new TagOwnerTest.Bare(new ByteVectorStream(tagBytes(body)))) {
      var tag = new Tag(file, 0);
      assertEquals(2, tag.frameList().size());
      assertEquals(300, tag.frameList().getFirst().size());
      assertEquals("second", tag.frameList().getLast().toString());
    }
  }

  @Test
  void dataLengthIndicatorCanReadPastTheDeclaredFrameExtent() {
    var first = new ByteVector("ZZZZ").append(SynchData.fromUnsignedInt(4))
      .append(new ByteVector(new byte[]{0, 1})).append(SynchData.fromUnsignedInt(5)).append(new ByteVector("abcde"));
    var body = first.append(new ByteVector(20));
    try (var file = new TagOwnerTest.Bare(new ByteVectorStream(tagBytes(body)))) {
      var tag = new Tag(file, 0);
      assertEquals(1, tag.frameList().size());
      var frame = assertInstanceOf(UnknownFrame.class, tag.frameList().getFirst());
      assertEquals(4, frame.size());
      assertEquals(new ByteVector("abcde"), frame.data());
    }
  }

}
