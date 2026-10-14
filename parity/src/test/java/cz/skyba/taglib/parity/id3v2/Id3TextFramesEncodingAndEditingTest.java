package cz.skyba.taglib.parity.id3v2;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("id3v2")
class Id3TextFramesEncodingAndEditingTest {
  @Test
  void textFieldsEncodingsDelimitersAndRenderingMatchCpp() throws Exception {
    BatchedComparison.compare(
      "id3-text", "id3-text", "cz.skyba.taglib.id3.v2.Id3TextProbe", 62382L, "cases.tsv");
  }
}
