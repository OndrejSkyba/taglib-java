package cz.skyba.taglib;

import cz.skyba.taglib.ogg.XiphComment;
import org.junit.jupiter.api.Test;

class TagPropertyGettersAndSettersTest {
  @Test
  void testGetSetId3v2() {
    Id3v1RiffInfoAndApeOwnerMutationTest.fullPropertyContract(new cz.skyba.taglib.id3.v2.Tag());
  }

  @Test
  void testGetSetApe() {
    Id3v1RiffInfoAndApeOwnerMutationTest.fullPropertyContract(new cz.skyba.taglib.ape.Tag());
  }

  @Test
  void testGetSetAsf() {
    Id3v1RiffInfoAndApeOwnerMutationTest.fullPropertyContract(new cz.skyba.taglib.asf.Tag());
  }

  @Test
  void testGetSetMp4() {
    Id3v1RiffInfoAndApeOwnerMutationTest.fullPropertyContract(new cz.skyba.taglib.mp4.Tag());
  }

  @Test
  void testGetSetXiphComment() {
    Id3v1RiffInfoAndApeOwnerMutationTest.fullPropertyContract(new XiphComment());
  }
}
