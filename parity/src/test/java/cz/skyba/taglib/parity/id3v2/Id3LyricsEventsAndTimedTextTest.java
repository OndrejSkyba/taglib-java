package cz.skyba.taglib.parity.id3v2;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("id3v2")
class Id3LyricsEventsAndTimedTextTest {
  @Test
  void lyricsEventsTimestampsAndRenderingMatchCpp() throws Exception {
    BatchedComparison.compare(
      "id3-lyrics-events-and-timed-text",
      "id3-lyrics-events-and-timed-text",
      "cz.skyba.taglib.id3.v2.Id3TimedProbe",
      42109L,
      "cases.tsv");
  }
}
