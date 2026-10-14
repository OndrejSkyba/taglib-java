package cz.skyba.taglib.asf;

import cz.skyba.taglib.ReadOptions;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.TagLibException;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.IOStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent fixtures for the caller-selected Java metadata limit and checked cursor.
 */
class AsfResourceTest {
  private static byte[] hex(String value) {
    return HexFormat.of().parseHex(value);
  }

  private static final byte[] HEADER = hex("3026b2758e66cf11a6d900aa0062ce6c");
  private static final byte[] FILE = hex("a1dcab8c47a9cf118ee400c00c205365");
  private static final byte[] STREAM = hex("9107dcb7b7a9cf118ee600c00c205365");
  private static final byte[] EXTENDED = hex("40a4d0d207e3d21197f000a0c95ea850");
  private static final byte[] UNKNOWN = new byte[16];

  private static void number(ByteArrayOutputStream out, long value, int width) {
    for (int i = 0; i < width; i++) out.write((int) (value >>> (8 * i)) & 255);
  }

  private static byte[] object(byte[] guid, byte[] body) {
    var out = new ByteArrayOutputStream();
    out.writeBytes(guid);
    number(out, body.length + 24L, 8);
    out.writeBytes(body);
    return out.toByteArray();
  }

  private static byte[] file(List<byte[]> extra) {
    var body = new ByteArrayOutputStream();
    body.writeBytes(object(FILE, new byte[80]));
    body.writeBytes(object(STREAM, new byte[70]));
    extra.forEach(body::writeBytes);
    var out = new ByteArrayOutputStream();
    out.writeBytes(HEADER);
    number(out, body.size() + 30L, 8);
    number(out, 2 + extra.size(), 4);
    out.write(1);
    out.write(2);
    out.writeBytes(body.toByteArray());
    return out.toByteArray();
  }

  private static final class TracedStream extends ByteVectorStream {
    final List<Long> requests = new ArrayList<>();
    int writes;

    TracedStream(byte[] input) {
      super(input);
    }

    @Override
    public ByteVector readBlock(long length) {
      requests.add(length);
      return super.readBlock(length);
    }

    @Override
    public void writeBlock(ByteVector data) {
      writes++;
      super.writeBlock(data);
    }

    @Override
    public void insert(ByteVector data, long start, long replace) {
      writes++;
      super.insert(data, start, replace);
    }
  }

  private static ReadOptions limit(int bytes) {
    return new ReadOptions(false, ReadStyle.FAST, bytes);
  }

  private static void assertLimited(byte[] input) throws IOException {
    var stream = new TracedStream(input);
    assertThrows(java.io.UncheckedIOException.class, () -> new AsfFile(stream, limit(1024)));
    assertEquals(0, stream.writes);
    assertArrayEquals(input, stream.data().toByteArray());
    assertTrue(stream.requests.stream().mapToLong(Long::longValue).sum() <= 1040,
      "Budget excludes only the initial 16-byte signature");
    assertTrue(stream.requests.stream().allMatch(n -> n <= 1024));
    assertTrue(stream.isOpen(), "The rejected constructor still borrows its stream");
  }

  @Test
  void smallOpaqueObjectsCannotEachReuseTheWholeBudget() throws IOException {
    var extra = new ArrayList<byte[]>();
    for (int i = 0; i < 4; i++) extra.add(object(UNKNOWN, new byte[300]));
    byte[] input = file(extra);
    try (var ordinary = new AsfFile(new ByteVectorStream(input))) {
      assertTrue(ordinary.isValid());
    }
    assertLimited(input);
  }

  @Test
  void smallAttributeValuesShareTheBudgetAcrossOneCarrier() throws IOException {
    var body = new ByteArrayOutputStream();
    number(body, 4, 2);
    for (int i = 0; i < 4; i++) {
      number(body, 2, 2);
      body.write('A' + i);
      body.write(0);
      number(body, Attribute.BytesType, 2);
      number(body, 300, 2);
      body.writeBytes(new byte[300]);
    }
    byte[] input = file(List.of(object(EXTENDED, body.toByteArray())));
    try (var ordinary = new AsfFile(new ByteVectorStream(input))) {
      assertTrue(ordinary.isValid());
      assertEquals(4, ordinary.tag().attributeListMap().size());
    }
    assertLimited(input);
  }

  @Test
  void exactReadBudgetSucceedsAndOneByteLessRejectsWithoutWriting() throws IOException {
    byte[] input = file(List.of(object(UNKNOWN, new byte[1000])));
    // The signature is read separately and the two reserved header bytes are skipped.
    int budget = input.length - 18;
    try (var file = new AsfFile(new ByteVectorStream(input), limit(budget))) {
      assertTrue(file.isValid());
    }
    var stream = new TracedStream(input);
    assertThrows(java.io.UncheckedIOException.class, () -> new AsfFile(stream, limit(budget - 1)));
    assertEquals(0, stream.writes);
    assertArrayEquals(input, stream.data().toByteArray());
  }

  @Test
  void hugeDeclaredOpaqueExtentsNeverBecomeAllocationRequests() throws IOException {
    for (long declared : new long[]{Long.MAX_VALUE, Long.MIN_VALUE, -1L}) {
      byte[] unknown = object(UNKNOWN, new byte[0]);
      for (int i = 0; i < 8; i++) unknown[16 + i] = (byte) (declared >>> (8 * i));
      byte[] input = file(List.of(unknown));
      var stream = new TracedStream(input);
      try (var file = new AsfFile(stream, limit(1024))) {
        assertTrue(file.isValid(), "Retain the native ignored opaque-size behavior");
        assertTrue(stream.requests.stream().allMatch(n -> n <= 80));
        assertArrayEquals(input, stream.data().toByteArray());
      }
    }
  }

  @Test
  void excessiveObjectCountIsRejectedBeforeFollowingAnyObjects() throws IOException {
    byte[] input = file(List.of());
    int count = 50001;
    for (int i = 0; i < 4; i++) input[24 + i] = (byte) (count >>> (8 * i));
    var stream = new TracedStream(input);
    assertThrows(TagLibException.class, () -> new AsfFile(stream, limit(1024)));
    assertEquals(List.of(16L, 8L, 4L), stream.requests);
    assertEquals(0, stream.writes);
    assertArrayEquals(input, stream.data().toByteArray());
  }

  @Test
  void signatureAtAnUnrepresentableNextCursorIsRejected() throws IOException {
    var stream = new ByteVectorStream(HEADER) {
      @Override
      public long tell() {
        return Long.MAX_VALUE - 8;
      }

      @Override
      public long length() {
        return Long.MAX_VALUE;
      }

      @Override
      public void seek(long offset, IOStream.Position origin) {
      }

      @Override
      public ByteVector readBlock(long length) {
        return new ByteVector(HEADER);
      }
    };
    assertThrows(TagLibException.class, () -> new AsfFile(stream, limit(1024)));
  }

  @Test
  void unsignedHeaderSpanCannotWrapPastTheSaveGuard() throws IOException {
    for (long span : new long[]{Long.MIN_VALUE, Long.MIN_VALUE + 29, -1L, 29}) {
      byte[] input = file(List.of());
      for (int i = 0; i < 8; i++) input[16 + i] = (byte) (span >>> (8 * i));
      var stream = new TracedStream(input);
      try (var file = new AsfFile(stream)) {
        assertTrue(file.isValid(), "Reading still follows the count independently of header extent");
        file.tag().setTitle("pending edit");
        assertThrows(TagLibException.class, file::save);
        assertEquals(0, stream.writes);
        assertArrayEquals(input, stream.data().toByteArray());
        assertEquals("pending edit", file.tag().title());
      }
    }
  }
}
