package cz.skyba.taglib.flac;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FlacPictureParsingAndRenderingTest {
  static byte[] pixel() {
    return HexFormat.of().parseHex("0000000300000009696d6167652f706e67000000084120706978656c2e000000010000000100000018000000000000009689504e470d0a1a0a0000000d4948445200000001000000010802000000907753de000000097048597300000b1300000b1301009a9c180000000774494d4507d60b1c0a360608443d320000001d74455874436f6d6d656e7400437265617465642077697468205468652047494d50ef64256e0000000c4944415408d763f8ffff3f0005fe02fedccc59e70000000049454e44ae426082 ".trim());
  }

  @Test
  void testParse() {
    var p = new Picture(pixel());
    assertEquals(3, p.type().code());
    assertEquals(1, p.width());
    assertEquals(1, p.height());
    assertEquals(24, p.colorDepth());
    assertEquals(0, p.numColors());
    assertEquals("image/png", p.mimeType());
    assertEquals("A pixel.", p.description());
    assertEquals(150, p.data().length);
  }

  @Test
  void testPassThrough() {
    assertArrayEquals(pixel(), new Picture(pixel()).render());
  }

  @Test
  void testAccessors() {
    byte[] data = {'a', 'b', 'c', 1};
    var b = new UnknownMetadataBlock(42, data);
    assertEquals(42, b.code());
    assertArrayEquals(data, b.data());
    assertArrayEquals(data, b.render());
    byte[] other = {'x', 'x', 'x'};
    b.setCode(13);
    b.setData(other);
    assertEquals(13, b.code());
    assertArrayEquals(other, b.data());
    assertArrayEquals(other, b.render());
  }
}
