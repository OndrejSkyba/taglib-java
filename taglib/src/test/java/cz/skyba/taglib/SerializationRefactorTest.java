package cz.skyba.taglib;

import cz.skyba.taglib.id3.v2.ChapterFrame;
import cz.skyba.taglib.id3.v2.EventTimingCodesFrame;
import cz.skyba.taglib.id3.v2.Frame;
import cz.skyba.taglib.matroska.SimpleTag;
import cz.skyba.taglib.mp4.Item;
import cz.skyba.taglib.mp4.ItemFactory;
import cz.skyba.taglib.ogg.XiphComment;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SerializationRefactorTest {
  @Test
  void hundredsOfTimingEventsHaveExactWireWidthsAndOrder() {
    var frame = new EventTimingCodesFrame();
    frame.setTimestampFormat(EventTimingCodesFrame.TimestampFormat.AbsoluteMilliseconds);
    var events = new ArrayList<EventTimingCodesFrame.SynchedEvent>();
    var expected = ByteBuffer.allocate(1 + 300 * 5);
    expected.put((byte) 2);
    for (int i = 0; i < 300; i++) {
      long time = 0xffff_ffffL - i;
      events.add(new EventTimingCodesFrame.SynchedEvent(time, EventTimingCodesFrame.EventType.fromByte((byte) i)));
      expected.put((byte) i).putInt((int) time);
    }
    frame.setSynchedEvents(events);
    assertArrayEquals(expected.array(), frame.render().mid(10).data());
  }

  @Test
  void xiphFieldsRetainLittleEndianLengthsAndSortedDuplicateValues() throws Exception {
    var tag = new XiphComment();
    var expected = new ByteArrayOutputStream();
    expected.write(new byte[4]); // Empty vendor.
    expected.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(200).array());
    for (int i = 0; i < 200; i++) {
      String text = "value-" + i + "-é";
      tag.addField("title", text, false);
      byte[] field = ("TITLE=" + text).getBytes(StandardCharsets.UTF_8);
      expected.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(field.length).array());
      expected.write(field);
    }
    assertArrayEquals(expected.toByteArray(), tag.render(false).data());
    expected.write(1);
    assertArrayEquals(expected.toByteArray(), tag.render().data());
  }

  @Test
  void apeTagRetainsOrderedItemsAndRenderedPayloadCache() throws Exception {
    var tag = new cz.skyba.taglib.ape.Tag();
    var expected = new ByteArrayOutputStream();
    for (int i = 0; i < 200; i++) {
      String key = "FIELD" + String.format(Locale.ROOT, "%03d", i);
      String value = "value-" + i;
      var item = new cz.skyba.taglib.ape.Item(key, List.of(value));
      tag.setItem(key, item);
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      expected.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.length).putInt(0).array());
      expected.write(key.getBytes(StandardCharsets.ISO_8859_1));
      expected.write(0);
      expected.write(bytes);
    }
    var rendered = tag.render();
    assertArrayEquals(expected.toByteArray(), rendered.mid(32, rendered.size() - 64).data());
    assertEquals(new ByteVector("value-199"), tag.itemListMap().get("FIELD199").binaryData());
  }

  @Test
  void mp4RepeatedDataAtomsHaveExactHeadersAndPayloads() throws Exception {
    var item = Item.textValues(List.of("first", "second"));
    var bytes = new ByteArrayOutputStream();
    var expected = new DataOutputStream(bytes);
    expected.writeInt(51);
    expected.write(new byte[]{(byte) 0xa9, 'n', 'a', 'm'});
    for (String value : List.of("first", "second")) {
      byte[] text = value.getBytes(StandardCharsets.UTF_8);
      expected.writeInt(16 + text.length);
      expected.writeBytes("data");
      expected.writeInt(1);
      expected.writeInt(0);
      expected.write(text);
    }
    assertArrayEquals(bytes.toByteArray(), ItemFactory.instance().renderItem("©nam", item).data());
  }

  @Test
  void nestedFramesConsumeReusedPayloadsAndRetainHeaderMutations() {
    var shared = new ByteVector();
    class ReusedFrame extends Frame {
      private final byte marker;

      ReusedFrame(byte marker) {
        super(new ByteVector("XONE"));
        this.marker = marker;
      }

      @Override
      public String toString() {
        return "";
      }

      @Override
      protected void parseFields(ByteVector data) {
      }

      @Override
      protected ByteVector renderFields() {
        shared.setData(new byte[]{marker});
        return shared;
      }
    }
    var first = new ReusedFrame((byte) 1);
    var second = new ReusedFrame((byte) 2);
    var chapter = new ChapterFrame(new ByteVector("C"), 0, 0, 0, 0, List.of(first, second));
    chapter.header().setVersion(3);
    byte[] fields = chapter.render().mid(10).data();
    assertEquals(40, fields.length);
    assertEquals(1, fields[28]);
    assertEquals(2, fields[39]);
    assertEquals(3, first.header().version());
    assertEquals(3, second.header().version());
  }

  @Test
  @SuppressWarnings("resource")
    // Memory stream.
  void simplifiedCopyPathsRemainIndependent() {
    byte[] original = {1, 2, 3};
    var stream = new ByteVectorStream(original);
    var binary = new SimpleTag("DATA", new ByteVector(original));
    original[0] = 9;
    stream.data().set(1, (byte) 9);
    binary.toByteVector().set(2, (byte) 9);
    assertArrayEquals(new byte[]{1, 2, 3}, binary.toByteVector().data());
  }
}
