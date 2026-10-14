package cz.skyba.taglib;

import cz.skyba.taglib.mp4.ItemFactory;
import cz.skyba.taglib.toolkit.ByteVector;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemFactoryHelperTest {
  private static class Helpers extends ItemFactory {
    static void verifyDataHelpers() {
      var bytes = new ByteVector(HexFormat.of().parseHex("00000013646174610000000100000000414243"));
      var expected = new ByteVector("ABC");
      assertEquals(expected, parseData(bytes).getFirst());
      assertEquals(expected, parseData(bytes, 1).getFirst());
      assertEquals(expected, parseData(bytes, 1, false).getFirst());
      assertTrue(parseData(bytes, 0).isEmpty());
      assertTrue(parseData(bytes, 1, true).isEmpty());
      assertEquals(expected, parseData2(bytes).getFirst().data);
      assertEquals(expected, parseData2(bytes, 1).getFirst().data);
      var typed = parseData2(bytes, 1, false).getFirst();
      assertEquals(expected, typed.data);
      assertEquals(1, typed.type.value());
      assertEquals(0, typed.locale);
      assertTrue(parseData2(bytes, 0).isEmpty());
      assertTrue(parseData2(bytes, 1, true).isEmpty());
    }

    static void verifyGenreHelper() {
      var bytes = new ByteVector(HexFormat.of().parseHex("000000126461746100000000000000000001"));
      var parsed = parseGnre(bytes);
      assertEquals("©gen", parsed.name());
      assertEquals("Blues", parsed.item().toStringList().getFirst().toString());
    }
  }

  @Test
  void externalSubclassCallsAllDataOverloadsWithoutAnAtom() {
    Helpers.verifyDataHelpers();
  }

  @Test
  void externalSubclassCallsGenreHelperWithoutAnAtom() {
    Helpers.verifyGenreHelper();
  }
}
