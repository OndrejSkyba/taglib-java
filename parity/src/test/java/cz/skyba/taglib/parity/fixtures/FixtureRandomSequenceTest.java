package cz.skyba.taglib.parity.fixtures;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("support")
class FixtureRandomSequenceTest {
  @Test
  void originalFixtureSeedPreservesBitByteAndBoundedIntegerSequence() {
    var random = new FixtureRandom(232008);
    assertEquals(1, random.bits(1));
    assertEquals(57, random.bits(7));
    assertEquals(2289262451L, random.bits(32));
    assertEquals("12007427899972356602", Long.toUnsignedString(random.bits(64)));
    assertEquals("2269df04a4a0ee69a7d187b1cf", HexFormat.of().formatHex(random.bytes(13)));
    assertEquals(1, random.below(2));
    assertEquals(20, random.below(24));
    assertEquals(2, random.below(65));
    assertEquals(3634, random.below(65536));
  }
}
