package cz.skyba.taglib.flac;

import cz.skyba.taglib.internal.Binary;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class MetadataBlockTest {
  @Test
  void unknownBlocksExposeMutableCodeAndOwnedBytes() {
    byte[] bytes = {1, 2, 3};
    var block = new UnknownMetadataBlock(9, bytes);
    bytes[0] = 7;
    assertArrayEquals(new byte[]{1, 2, 3}, block.data());
    block.setCode(2);
    assertEquals(MetadataBlock.BlockType.Application.code(), block.code());
    block.setData(new byte[]{4, 5});
    byte[] rendered = block.render();
    rendered[0] = 9;
    assertArrayEquals(new byte[]{4, 5}, block.render());
  }

  @Test
  void pictureFieldsRenderAndParseWithDefensivePayloads() {
    var picture = new Picture();
    picture.setType(Picture.Type.FrontCover);
    picture.setMimeType("image/png");
    picture.setDescription("Žluť 日本");
    picture.setWidth(1024);
    picture.setHeight(768);
    picture.setColorDepth(24);
    picture.setNumColors(256);
    picture.setData(new byte[]{7, 8, 9});
    var parsed = new Picture(picture.render());
    assertEquals(picture.snapshot(), parsed.snapshot());
    assertEquals(6, picture.code());
    assertEquals(21, Picture.Type.values().length);
    for (Picture.Type type : Picture.Type.values()) {
      assertEquals(type, Picture.typeFromString(Picture.typeToString(type)));
    }
    assertEquals(Picture.Type.Other, Picture.typeFromUInt(0xffffffffL));
    assertEquals(Picture.Type.Other, Picture.typeFromString("front cover"));
    assertEquals(Picture.Type.Other, Picture.typeFromByte((byte) 255));
    byte[] data = picture.data();
    data[0] = 0;
    assertEquals(7, picture.data()[0]);
  }

  @Test
  void malformedPictureKeepsOnlySuccessfullyParsedFields() {
    var picture = new Picture();
    picture.setMimeType("previous");
    picture.setData(new byte[]{3});
    byte[] malformed = new byte[32];
    Binary.put32be(malformed, 0, 4);
    Binary.put32be(malformed, 4, 0xffffffffL);
    assertFalse(picture.parse(malformed));
    assertEquals(Picture.Type.BackCover, picture.type());
    assertEquals("previous", picture.mimeType());
    assertArrayEquals(new byte[]{3}, picture.data());
    byte[] valid = picture.render();
    byte[] truncated = Arrays.copyOf(valid, valid.length - 1);
    assertFalse(picture.parse(truncated));
    assertArrayEquals(new byte[]{3}, picture.data());
  }

  @Test
  void pictureTypeHelperNarrowsTheNativeUnsignedParameterBeforeLookup() {
    assertEquals(Picture.Type.FrontCover, Picture.typeFromUInt(0x1_0000_0003L));
    assertEquals(Picture.Type.FrontCover, Picture.typeFromUInt(-0xffff_fffdL));
    assertEquals(Picture.Type.Other, Picture.typeFromUInt(0x1_0000_0015L));
    assertEquals(Picture.Type.Other, Picture.typeFromUInt(31));
    assertEquals(Picture.Type.Other, Picture.typeFromUInt(-1));
  }
}
