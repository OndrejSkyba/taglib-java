package cz.skyba.taglib;

import cz.skyba.taglib.internal.id3.Id3v2;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TagUnsignedSnapshotTest {
  @Test
  void nativeUnsignedFieldsPreserveTheirFullRange() {
    var unsigned = new Tag("", "", "", "", "", -1, Integer.MIN_VALUE);
    assertEquals(4294967295L, unsigned.year());
    assertEquals(2147483648L, unsigned.track());
    assertEquals(0, new Tag("", "", "", "", "", 0x1_0000_0000L, 0).year());
  }

  @Test
  void duplicatingCommonFieldsRetainsNativeSignedTextSetterFormatting() throws Exception {
    var unsigned = new Tag("", "", "", "", "", 4294967295L, 2147483648L);
    byte[] tag = Id3v2.render(PropertyMap.EMPTY, new byte[0]);
    byte[] duplicate = Id3v2.duplicateMissingBasic(tag, unsigned);
    var properties = Id3v2.read(duplicate, ReadOptions.DEFAULT).properties();
    // Pinned ID3v2::setYear/setTrack call String::number(int) with unsigned arguments.
    assertEquals("-1", properties.first("DATE"));
    assertEquals("-2147483648", properties.first("TRACKNUMBER"));
  }
}
