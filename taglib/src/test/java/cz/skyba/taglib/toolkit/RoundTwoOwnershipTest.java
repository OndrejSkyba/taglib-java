package cz.skyba.taglib.toolkit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

class RoundTwoOwnershipTest {
  @Test
  void stringIteratorCapturesCodeUnitsBeforeMutation() {
    var text = new TagString("A\ud83d\ude00\0Z");
    var iterator = text.iterator();
    text.clear().append(new TagString("changed"));
    var characters = new ArrayList<Character>();
    iterator.forEachRemaining(characters::add);
    assertEquals(List.of('A', '\ud83d', '\ude00', '\0', 'Z'), characters);
    assertFalse(iterator.hasNext());
    assertThrows(NoSuchElementException.class, iterator::next);
  }

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
