package cz.skyba.taglib.parity.mpeg;

import cz.skyba.taglib.parity.support.BatchedComparison;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("parity")
@Tag("mpeg")
class MpegFrameAndVariableBitrateHeaderTest {
  @Test
  void mpegAdtsXingInfoAndVbriHeadersMatchCpp() throws Exception {
    BatchedComparison.compare(
      "mpeg-headers", "mpeg-header", "cz.skyba.taglib.parity.mpeg.probes.MpegHeaderProbe", 0L, "");
  }
}
