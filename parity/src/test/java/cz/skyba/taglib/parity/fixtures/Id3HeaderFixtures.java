package cz.skyba.taglib.parity.fixtures;

import java.nio.file.Path;

import static cz.skyba.taglib.parity.fixtures.Bytes.*;
import static cz.skyba.taglib.parity.fixtures.TagBytes.sync;

final class Id3HeaderFixtures {
  private final StringBuilder rows = new StringBuilder();
  private int count;

  private static byte[] header(int v, int revision, int flags) {
    return join(text("xyz"), new byte[]{(byte) v, (byte) revision, (byte) flags}, sync(123));
  }

  private void add(String kind, byte[] data, long major, long size) {
    rows.append(
      String.format(
        "%s-%06d\t%s\t%s\t%s\t%s\n",
        kind,
        count++,
        kind,
        CaseTable.hexField(data),
        Long.toUnsignedString(major),
        Long.toUnsignedString(size)));
  }

  private void add(String kind, byte[] data) {
    add(kind, data, 0, 0);
  }

  private void combinations(byte[] data, int at) {
    if (at == data.length) {
      add("D", data);
      return;
    }
    for (int value : new int[]{0, 1, 127, 128, 254, 255}) {
      data[at] = (byte) value;
      combinations(data, at + 1);
    }
  }

  static void generate(Path root) throws Exception {
    var g = new Id3HeaderFixtures();
    for (int v : new int[]{0, 2, 3, 4, 5, 127, 128, 254, 255})
      for (int r : new int[]{0, 7, 128, 254, 255})
        for (int f = 0; f < 256; f++) g.add("H", header(v, r, f), 0xffffffffL, 0xffffffffL);
    for (int n = 0; n < 17; n++)
      g.add(
        "H", slice(join(header(3, 7, 240), text("trailing")), 0, n), 0x100000003L, 0x100000001L);
    for (int pos = 6; pos < 10; pos++)
      for (int value : new int[]{128, 129, 254, 255}) {
        byte[] b = header(4, 0, 0);
        b[pos] = (byte) value;
        g.add("H", b, 128, 0xffffffefL);
      }
    for (long value :
      new long[]{
        0,
        1,
        127,
        128,
        0xfffffff,
        0x10000000,
        0x7fffffff,
        0xfffffff5L,
        0xfffffff6L,
        0xffffffefL,
        0xffffffffL,
        0x100000000L,
        0x100000003L,
        -1
      }) {
      g.add("H", header(3, 7, 240), value, value);
      g.add("S", sync(value), value, value);
    }
    g.add("S", new byte[0]);
    for (int v = 0; v < 256; v++) g.add("S", new byte[]{(byte) v}, v, v);
    for (int v = 0; v < 65536; v++) g.add("S", uint(v, 2, false), v, v);
    for (int n = 0; n < 6; n++) g.combinations(new byte[n], 0);
    var r = new FixtureRandom(0x232);
    for (int n = 3; n < 17; n++) for (int i = 0; i < 32; i++) g.add("S", r.bytes(n), r.bits(64), 0);
    for (long v : new long[]{0, 1, 127, 128, 0xfffffff, 0xffffffffL})
      for (int n = 0; n < 6; n++) g.add("S", slice(join(uint(v, 4, false), hex("ff")), 0, n), v, 0);
    var files = new GeneratedFiles(root);
    files.text("cases.tsv", g.rows.toString());
  }
}
