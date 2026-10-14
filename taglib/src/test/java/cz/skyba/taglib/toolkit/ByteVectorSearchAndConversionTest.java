package cz.skyba.taglib.toolkit;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.LinkedList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Literal pinned assertions; pointer iterator syntax uses indexed Java mutation.
 */
class ByteVectorSearchAndConversionTest {
  static ByteVector b(String hex) {
    return new ByteVector(HexFormat.of().parseHex(hex));
  }

  static ByteVector v(String s) {
    return new ByteVector(s);
  }

  @Test
  void splitRetainsAlignmentAndLeavesTheLimitedRemainderUnsplit() {
    var data = b("410000004200000043000000");
    var delimiter = b("0000");
    assertEquals(List.of(b("4100"), b("4200"), b("4300")), data.split(delimiter, 2));
    assertEquals(List.of(b("4100"), b("4200000043000000")), data.split(delimiter, 2, 2));
    var unaligned = b("01000001");
    assertEquals(List.of(unaligned), unaligned.split(delimiter, 2));
    assertEquals(List.of(b("01"), b("01")), unaligned.split(delimiter));
  }

  @Test
  void splitAndJoinHaveIndependentByteStorage() {
    var source = v("a::b:");
    var fields = source.split(v(":"));
    source.set(0, (byte) 'x');
    assertEquals(List.of(v("a"), new ByteVector(), v("b")), fields);
    fields.getFirst().set(0, (byte) 'y');
    assertEquals(v("x::b:"), source);
    var separator = v("|");
    var joined = ByteVector.join(new LinkedList<>(fields), separator);
    fields.clear();
    separator.clear();
    assertEquals(v("y||b"), joined);
    joined.set(0, (byte) 'z');
    assertEquals(v("x::b:"), source);
    assertEquals(new ByteVector(), ByteVector.join(List.of()));
  }

  @Test
  void testByteVector() {
    assertEquals(1, v("foo").split(v(" ")).size());
    assertEquals(1, v("f").split(v(" ")).size());
    assertTrue(new ByteVector().isEmpty());
    assertEquals(0, new ByteVector().size());
    assertTrue(v("asdf").clear().isEmpty());
    assertEquals(0, v("asdf").clear().size());
    assertEquals(new ByteVector(), v("asdf").clear());
    var i = v("blah blah");
    var j = v("blah");
    assertTrue(i.containsAt(j, 5, 0));
    assertTrue(i.containsAt(j, 6, 1));
    assertTrue(i.containsAt(j, 6, 1, 3));
    i.clear();
    assertTrue(i.isEmpty());
  }

  @Test
  void testFind1() {
    var v = v("....SggO.");
    assertEquals(4, v.find(v("SggO")));
    for (int i = 0; i <= 8; i++) assertEquals(i <= 4 ? 4 : -1, v.find(v("SggO"), i));
    var trimmed = v("0123456789x").resize(10);
    assertThrows(IndexOutOfBoundsException.class, () -> trimmed.set(10, (byte) 'x'));
    assertEquals(-1, trimmed.find(v("789x"), 7));
  }

  @Test
  void testFind2() {
    assertEquals(0, b("01").find(b("01")));
    assertEquals(0, b("0102").find(b("0102")));
    assertEquals(-1, b("01").find(b("02")));
    assertEquals(-1, b("0102").find(b("0103")));
  }

  @Test
  void testFind3() {
    var v = v("....SggO.");
    assertEquals(4, v.find((byte) 'S'));
    for (int i = 0; i <= 8; i++) assertEquals(i <= 4 ? 4 : -1, v.find((byte) 'S', i));
  }

  @Test
  void testRfind1() {
    var v = v(".OggS....");
    for (int i = 0; i <= 8; i++) assertEquals(1, v.rfind(v("OggS"), i));
    assertEquals(1, v.rfind(v("OggS")));
  }

  @Test
  void testRfind2() {
    String[] input = {"**************", "OggS**********", "**********OggS", "OggS******OggS", "OggS*OggS*OggS"};
    int[] first = {-1, 0, 10, 0};
    int[] last = {-1, 0, 10, 10};
    for (int i = 0; i < 4; i++) {
      assertEquals(first[i], v(input[i]).find(v("OggS")));
      assertEquals(last[i], v(input[i]).rfind(v("OggS")));
    }
    var r = v(input[4]);
    assertEquals(10, r.rfind(v("OggS")));
    assertEquals(10, r.rfind(v("OggS"), 0));
    assertEquals(5, r.rfind(v("OggS"), 7));
    assertEquals(10, r.rfind(v("OggS"), 12));
  }

  @Test
  void testRfind3() {
    var v = v(".OggS....");
    for (int i = 0; i <= 8; i++) assertEquals(1, v.rfind((byte) 'O', i));
    assertEquals(1, v.rfind((byte) 'O'));
  }

  @Test
  void testToHex() {
    assertEquals(v("f0e1d2c3b4a5968778695a4b3c2d1e0f"), b("f0e1d2c3b4a5968778695a4b3c2d1e0f").toHex());
  }

  @Test
  void testIntegerConversion() {
    var data = b("00ff01ff00ff01ff00ff01ff00ff");
    assertEquals((short) 0x00ff, data.toShort());
    assertEquals((short) 0xff00, data.toShort(false));
    assertEquals((short) 0xff01, data.toShort(5));
    assertEquals((short) 0x01ff, data.toShort(5, false));
    assertEquals((short) 0xff, data.toShort(13));
    assertEquals((short) 0xff, data.toShort(13, false));
    assertEquals(ByteVector.fromShort((short) 0x00ff), b("00ff"));
    assertEquals(ByteVector.fromShort((short) 0x00ff, false), b("ff00"));
    assertEquals((int) 0x00ff, data.toUnsignedShort());
    assertEquals((int) 0xff00, data.toUnsignedShort(false));
    assertEquals((int) 0xff01, data.toUnsignedShort(5));
    assertEquals((int) 0x01ff, data.toUnsignedShort(5, false));
    assertEquals((int) 0xff, data.toUnsignedShort(13));
    assertEquals((int) 0xff, data.toUnsignedShort(13, false));
    assertEquals(ByteVector.fromUnsignedShort(0xff00), b("ff00"));
    assertEquals(ByteVector.fromUnsignedShort(0xff00, false), b("00ff"));
    assertEquals(0x00ff01ffL, data.toUnsignedInt());
    assertEquals(0xff01ff00L, data.toUnsignedInt(false));
    assertEquals(0xff01ff00L, data.toUnsignedInt(5));
    assertEquals(0x00ff01ffL, data.toUnsignedInt(5, false));
    assertEquals(0x00ffL, data.toUnsignedInt(12));
    assertEquals(0xff00L, data.toUnsignedInt(12, false));
    assertEquals(0x00ff01L, data.toUnsignedInt(0, 3));
    assertEquals(0x01ff00L, data.toUnsignedInt(0, 3, false));
    assertEquals(0xff01ffL, data.toUnsignedInt(5, 3));
    assertEquals(0xff01ffL, data.toUnsignedInt(5, 3, false));
    assertEquals(0x00ffL, data.toUnsignedInt(12, 3));
    assertEquals(0xff00L, data.toUnsignedInt(12, 3, false));
    assertEquals(ByteVector.fromUnsignedInt(0xff00ff00), b("ff00ff00"));
    assertEquals(ByteVector.fromUnsignedInt(0xff00ff00, false), b("00ff00ff"));
    assertEquals((long) 0x00ff01ff00ff01ffL, data.toLong());
    assertEquals((long) 0xff01ff00ff01ff00L, data.toLong(false));
    assertEquals((long) 0xff01ff00ff01ff00L, data.toLong(5));
    assertEquals((long) 0x00ff01ff00ff01ffL, data.toLong(5, false));
    assertEquals((long) 0x00ffL, data.toLong(12));
    assertEquals((long) 0xff00L, data.toLong(12, false));
    assertEquals(ByteVector.fromLong(0x00ff01ff00ff01ffL), b("00ff01ff00ff01ff"));
    assertEquals(ByteVector.fromLong(0x00ff01ff00ff01ffL, false), b("ff01ff00ff01ff00"));
    var data2 = b("ff01ff00ff01ff00ff01ff00ff01");
    assertEquals(0xff01ff00ff01ff00L, data2.toUnsignedLong());
    assertEquals(0x00ff01ff00ff01ffL, data2.toUnsignedLong(false));
    assertEquals(0x01ff00ff01ff00ffL, data2.toUnsignedLong(5));
    assertEquals(0xff00ff01ff00ff01L, data2.toUnsignedLong(5, false));
    assertEquals(0xff01L, data2.toUnsignedLong(12));
    assertEquals(0x01ffL, data2.toUnsignedLong(12, false));
    assertEquals(ByteVector.fromUnsignedLong(0xff01ff00ff01ff00L), b("ff01ff00ff01ff00"));
    assertEquals(ByteVector.fromUnsignedLong(0xff01ff00ff01ff00L, false), b("00ff01ff00ff01ff"));
  }

  @Test
  void testFloatingPointConversion() {
    for (String h : List.of("db0f4940", "40490fdb", "182d4454fb210940", "400921fb54442d18", "00c06821a2da0fc90040", "4000c90fdaa22168c000")) {
      var p = b(h);
      double n = switch (h) {
        case "db0f4940" -> p.toFloat32LE(0);
        case "40490fdb" -> p.toFloat32BE(0);
        case "182d4454fb210940" -> p.toFloat64LE(0);
        case "400921fb54442d18" -> p.toFloat64BE(0);
        case "00c06821a2da0fc90040" -> p.toFloat80LE(0).doubleValue();
        default -> p.toFloat80BE(0).doubleValue();
      };
      assertTrue(Math.abs(n - Math.PI) < 1e-7);
      if (h.length() == 8)
        assertEquals(p, h.startsWith("db") ? ByteVector.fromFloat32LE((float) n) : ByteVector.fromFloat32BE((float) n));
      else if (h.length() == 16)
        assertEquals(p, h.startsWith("18") ? ByteVector.fromFloat64LE(n) : ByteVector.fromFloat64BE(n));
    }
  }

  @Test
  void testReplace() {
    for (String[] r : new String[][]{{"abcdabf", "", "<a>", "abcdabf"}, {"abcdabf", "foobartoolong", "<a>", "abcdabf"}, {"abcdabf", "xx", "yy", "abcdabf"}, {"abcdabf", "a", "x", "xbcdxbf"}, {"abcdabf", "ab", "xy", "xycdxyf"}, {"abcdabf", "a", "<a>", "<a>bcd<a>bf"}, {"abcdabf", "b", "<b>", "a<b>cda<b>f"}, {"abcdabc", "c", "<c>", "ab<c>dab<c>"}, {"abcdaba", "a", "<a>", "<a>bcd<a>b<a>"}}) {
      var a = v(r[0]);
      assertEquals(v(r[3]), a.replace(v(r[1]), v(r[2])));
      if (!r[0].equals(r[3])) assertEquals(v(r[0]), a.replace(v(r[2]), v(r[1])));
    }
    var a = v("abcdabf");
    assertEquals(v("xbcdxbf"), a.replace((byte) 'a', (byte) 'x'));
    assertEquals(v("abcdabf"), a.replace((byte) 'x', (byte) 'a'));
  }

  @Test
  void testReplaceAndDetach() {
    for (String[] r : new String[][]{{"abcdabf", "a", "x", "xbcdxbf"}, {"abcdabf", "ab", "xy", "xycdxyf"}, {"abcdabf", "a", "<a>", "<a>bcd<a>bf"}, {"ab<c>dab<c>", "<c>", "c", "abcdabc"}}) {
      var a = v(r[0]);
      var copy = new ByteVector(a);
      a.replace(v(r[1]), v(r[2]));
      assertEquals(v(r[3]), a);
      assertEquals(v(r[0]), copy);
    }
    var a = v("abcdabf");
    var c = new ByteVector(a);
    a.replace((byte) 'a', (byte) 'x');
    assertEquals(v("xbcdxbf"), a);
    assertEquals(v("abcdabf"), c);
  }

  @Test
  void testIterator() {
    var a = v("taglib");
    var b = new ByteVector(a);
    assertEquals((byte) 't', a.iterator().next());
    assertEquals((byte) 't', b.iterator().next());
    b.set(4, (byte) 'I');
    assertEquals((byte) 'i', a.at(4));
    assertEquals((byte) 'I', b.at(4));
    assertEquals(v("taglib"), a);
    assertEquals(v("taglIb"), b);
    assertEquals((byte) 'b', a.at(a.size() - 1));
    assertEquals((byte) 'b', b.at(b.size() - 1));
    b.set(1, (byte) 'A');
    assertEquals((byte) 'a', a.at(1));
    assertEquals((byte) 'A', b.at(1));
    assertEquals(v("taglib"), a);
    assertEquals(v("tAglIb"), b);
    var c = v("0123456789").mid(3, 4);
    assertEquals((byte) '3', c.iterator().next());
    assertEquals((byte) '6', c.at(c.size() - 1));
    assertEquals((byte) '6', c.at(c.size() - 1));
    assertEquals((byte) '3', c.at(0));
  }

  @Test
  void testResize() {
    var a = v("0123456789");
    for (var c : List.of(a.mid(3, 4), v("0123456789").mid(3, 4))) {
      c.resize(6, (byte) 'A');
      assertEquals(6, c.size());
      assertEquals((byte) '6', c.at(3));
      assertEquals((byte) 'A', c.at(4));
      assertEquals((byte) 'A', c.at(5));
      c.resize(10, (byte) 'B');
      assertEquals(10, c.size());
      assertEquals((byte) '6', c.at(3));
      assertEquals((byte) 'B', c.at(6));
      assertEquals((byte) 'B', c.at(9));
      c.resize(3, (byte) 'C');
      assertEquals(3, c.size());
      assertEquals(-1, c.find((byte) 'C'));
      c.resize(3);
      assertEquals(3, c.size());
    }
    assertEquals(10, a.size());
    assertEquals((byte) '3', a.at(3));
    assertEquals((byte) '5', a.at(5));
  }

  @Test
  void testAppend1() {
    var a = v("foo");
    assertEquals(v("foobar"), a.append(v("bar")));
    assertEquals(v("foob"), v("foo").append(v("b")));
    assertEquals(v("b"), new ByteVector().append(v("b")));
    assertEquals(v("foofoobar"), v("foo").append(a));
    assertEquals(v("foob"), v("foo").append((byte) 'b'));
    assertEquals(v("b"), new ByteVector().append((byte) 'b'));
    var x = v("taglib");
    var y = new ByteVector(x);
    x.append(v("ABC"));
    assertEquals(v("taglibABC"), x);
    x.append((byte) '1').append((byte) '2').append((byte) '3');
    assertEquals(v("taglibABC123"), x);
    assertEquals(v("taglib"), y);
  }

  @Test
  void testAppend2() {
    var a = v("1234");
    assertEquals(v("12341234"), a.append(a));
  }

  @Test
  void testBase64() {
    for (String[] r : new String[][]{{"", ""}, {"a", "YQ=="}, {"any carnal pleasure.", "YW55IGNhcm5hbCBwbGVhc3VyZS4="}, {"any carnal pleasure", "YW55IGNhcm5hbCBwbGVhc3VyZQ=="}, {"any carnal pleasur", "YW55IGNhcm5hbCBwbGVhc3Vy"}}) {
      assertEquals(v(r[1]), v(r[0]).toBase64());
      assertEquals(v(r[0]), ByteVector.fromBase64(v(r[1])));
    }
  }

  @Test
  void testEmpty() {
    var empty = new ByteVector();
    var non = v("A");
    var m = new ByteVector();
    assertEquals(empty, v(""));
    assertEquals(empty, m.setData(new byte[0]));
    assertEquals(0, empty.data().length);
    assertEquals(0, m.data().length);
    assertEquals(empty, empty.mid(0));
    assertEquals(0, empty.at(0));
    for (var x : List.of(empty, non)) {
      assertEquals(-1, empty.find(x));
      assertEquals(-1, empty.rfind(x));
    }
    assertEquals(-1, non.find(empty));
    assertEquals(-1, empty.find((byte) 0));
    assertEquals(-1, non.rfind(empty));
    assertFalse(empty.containsAt(m, 0));
    assertFalse(empty.startsWith(m));
    assertFalse(empty.startsWith(non));
    assertFalse(non.startsWith(empty));
    assertFalse(empty.endsWith(m));
    assertEquals(-1, empty.endsWithPartialMatch(m));
    assertEquals(empty, m.replace((byte) 'a', (byte) 'b'));
    assertEquals(empty, m.replace(v("abc"), v("")));
    assertEquals(empty, m.append(empty));
    assertEquals(non, m.append(non));
    m.clear();
    assertEquals(empty, m);
    assertEquals(non, new ByteVector(non).append(empty));
    assertEquals(non, m.append((byte) 'A'));
    assertEquals(empty, m.resize(0));
    assertEquals(0, empty.size());
    assertFalse(empty.iterator().hasNext());
    assertFalse(m.iterator().hasNext());
    assertTrue(empty.isEmpty());
    assertEquals(0, empty.toUnsignedInt());
    assertEquals(0, empty.toUnsignedInt(0, true));
    assertEquals(0, empty.toUnsignedInt(0, 0, true));
    assertEquals(0, empty.toShort());
    assertEquals(0, empty.toShort(0, true));
    assertEquals(0, empty.toUnsignedShort());
    assertEquals(0, empty.toUnsignedShort(0, true));
    assertEquals(0, empty.toLong());
    assertEquals(0, empty.toLong(0, true));
    assertEquals(0, empty.toUnsignedLong());
    assertEquals(0, empty.toUnsignedLong(0, true));
    assertEquals(0, empty.toFloat32LE(0));
    assertEquals(0, empty.toFloat32BE(0));
    assertEquals(0, empty.toFloat64LE(0));
    assertEquals(0, empty.toFloat64BE(0));
    assertEquals(0, empty.toFloat80LE(0).doubleValue());
    assertEquals(0, empty.toFloat80BE(0).doubleValue());
    assertEquals(empty, m);
    assertNotEquals(empty, non);
    assertEquals(empty, v(""));
    assertNotEquals(empty, v(" "));
    assertTrue(empty.compareTo(non) < 0);
    assertFalse(empty.compareTo(non) > 0);
    assertEquals(empty, new ByteVector(empty).append(m));
    assertEquals(non, new ByteVector(empty).append(non));
    m.clear();
    assertEquals(empty, m);
    m.setData(non.toByteArray());
    assertEquals(non, m);
    m.clear();
    assertEquals(empty, m);
    assertEquals(empty, empty.toHex());
    assertEquals(empty, empty.toBase64());
  }
}
