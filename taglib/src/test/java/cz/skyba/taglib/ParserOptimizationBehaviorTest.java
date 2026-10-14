package cz.skyba.taglib;

import cz.skyba.taglib.internal.*;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.TagString;
import cz.skyba.taglib.toolkit.Variant;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ParserOptimizationBehaviorTest {
  @Test
  void fixedWidthParsingPreservesAllBasesAndSaturation() {
    BigInteger signedMax = BigInteger.valueOf(Long.MAX_VALUE);
    BigInteger unsignedMax = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    for (int base = 2; base <= 36; base++) {
      for (BigInteger value : List.of(BigInteger.ZERO, BigInteger.ONE, signedMax,
        signedMax.add(BigInteger.ONE), unsignedMax, unsignedMax.add(BigInteger.ONE))) {
        for (boolean negative : List.of(false, true)) {
          String text = (negative ? "-" : "") + value.toString(base);
          var unsigned = new TagString(text).extractUnsignedLong(base);
          boolean overflow = value.compareTo(unsignedMax) > 0;
          long bits = overflow ? -1L : value.longValue();
          assertEquals(negative && !overflow ? -bits : bits, unsigned.value(), text);
          assertEquals(!overflow, unsigned.ok(), text);
          var signed = new TagString(text).extractLong(base);
          BigInteger limit = negative ? signedMax.add(BigInteger.ONE) : signedMax;
          BigInteger magnitude = value.min(limit);
          assertEquals((negative ? magnitude.negate() : magnitude).longValue(), signed.value(), text);
          assertEquals(value.compareTo(limit) <= 0, signed.ok(), text);
        }
      }
    }
  }

  @Test
  void copiedNulValuesRemainIndependentOnAppend() {
    var original = new TagString("x\0y");
    var copied = new TagString(original);
    copied.append(new TagString("q"));
    original.append(new TagString("r"));
    assertEquals("x\0yq", copied.toString());
    assertEquals("x\0yr", original.toString());
    var simple = new TagString("plain");
    var simpleCopy = new TagString(simple);
    simple.append(new TagString("\0tail"));
    var nulCopy = new TagString(simple);
    simple.append(new TagString("!"));
    assertEquals("plain\0tail!", simple.toString());
    assertEquals("plain", simpleCopy.toString());
    assertEquals("plain\0tail", nulCopy.toString());
  }

  @Test
  void outputSnapshotsAndBinaryVariantsOwnTheirBytes() {
    var output = new ByteVectorOutput().append(new byte[]{1, 2, 3});
    var first = output.toByteVector();
    first.set(0, (byte) 9);
    output.append((byte) 4);
    assertArrayEquals(new byte[]{1, 2, 3, 4, 0, 0}, output.toByteVector(2).data());
    byte[] source = {4, 5};
    Variant value = Variant.fromByteArray(source);
    source[0] = 0;
    value.toByteVector().set(1, (byte) 0);
    assertArrayEquals(new byte[]{4, 5}, value.toByteVector().data());
  }

  @Test
  void textRangesKeepOffsetsNulsAndMalformedInputRules() {
    assertEquals("é", TextBytes.terminated(new byte[]{9, (byte) 0xc3, (byte) 0xa9, 0, 65, 9}, 1, 4, TagString.Type.UTF8));
    assertEquals("A", TextBytes.terminated(new byte[]{9, (byte) 0xff, (byte) 0xfe, 65, 0, 0, 0, 9}, 1, 6, TagString.Type.UTF16));
    assertEquals("", TextBytes.terminated(new byte[]{9, (byte) 0xe2, (byte) 0x82, 9}, 1, 2, TagString.Type.UTF8));
  }

  @Test
  void nativeEditOffsetIndexKeepsOrderAndEqualOffsetInsertions() throws Exception {
    byte[] initial = new byte[1024];
    new Random(42).nextBytes(initial);
    var edits = new ArrayList<Rewrite.Edit>();
    for (int i = 0; i < 12; i++) {
      byte[] bytes = new byte[i % 3 == 0 ? 0 : 40 + i];
      new Random(i).nextBytes(bytes);
      edits.add(new Rewrite.Edit(i * 64L, 20, bytes));
    }
    edits.add(new Rewrite.Edit(900, 0, new byte[]{1, 2}));
    edits.add(new Rewrite.Edit(900, 0, new byte[]{3, 4}));
    Collections.shuffle(edits, new Random(7));
    try (var expected = new ByteVectorStream(initial); var actual = new ByteVectorStream(initial)) {
      var preceding = new ArrayList<Rewrite.Edit>();
      for (var edit : edits) {
        long offset = edit.offset();
        for (var prior : preceding)
          if (prior.offset() < edit.offset()) offset += prior.insertion().length - prior.removeLength();
        if (edit.insertion().length == 0) expected.removeBlock(offset, edit.removeLength());
        else expected.insert(new ByteVector(edit.insertion()), offset, edit.removeLength());
        preceding.add(edit);
      }
      try (var file = new BinaryFile(actual)) {
        Rewrite.replaceNative(file, edits);
      }
      assertEquals(expected.data(), actual.data());
      assertEquals(expected.tell(), actual.tell());
    }
  }

  @Test
  void voidSizingMatchesEncodedBoundariesWithoutAllocatingForValidation() {
    for (int size : new int[]{0, 1, 2, 3, 127, 128, 1024}) {
      assertEquals(Math.max(2, size), MatroskaWire.voidLength(size));
      assertEquals(MatroskaWire.voidLength(size), MatroskaWire.voidElement(size).length);
    }
    assertThrows(IllegalArgumentException.class, () -> MatroskaWire.voidLength(Integer.MAX_VALUE));
  }
}
