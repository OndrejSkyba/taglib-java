package cz.skyba.taglib.parity.id3v2;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("id3v2")
class Id3PictureAndChapterFrameRenderingTest {
  @Test
  void picturesChaptersUrlsAndRenderingMatchCpp() throws Exception {
    BatchedComparison.compare(
      "id3-picture", "id3-picture", "cz.skyba.taglib.id3.v2.Id3PictureProbe", 72773L, "cases.tsv");
  }
}
