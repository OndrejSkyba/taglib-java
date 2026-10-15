package cz.skyba.taglib.toolkit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class RoundTwoOwnershipTest {
  @Test
  void memoryWritesPreserveAliasedSourceAcrossGrowthAndOverlap() {
    var growing = new ByteVectorStream(new ByteVector("abcd"));
    var exposed = growing.data();
    growing.seek(1);
    growing.writeBlock(exposed);
    assertSame(exposed, growing.data());
    assertEquals("aabcd", growing.data().toString());
    assertEquals(5, growing.tell());

    var ordinary = new ByteVectorStream(new ByteVector("abcdef"));
    var source = new ByteVector("XYZ");
    ordinary.seek(1);
    ordinary.writeBlock(source);
    source.clear();
    assertEquals("aXYZef", ordinary.data().toString());
    assertEquals(4, ordinary.tell());
  }

}
