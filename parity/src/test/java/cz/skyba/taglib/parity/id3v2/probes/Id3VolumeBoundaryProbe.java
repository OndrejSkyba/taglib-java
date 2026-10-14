package cz.skyba.taglib.parity.id3v2.probes;

import cz.skyba.taglib.id3.v2.RelativeVolumeFrame;

/**
 * Explicit rejection is a boundary, not a native success equivalence claim.
 */
public class Id3VolumeBoundaryProbe {
  public static void main(String[] args) {
    var frame = new RelativeVolumeFrame();
    frame.setVolumeAdjustmentIndex(17);
    var before = frame.render();
    float f = Float.intBitsToFloat(Integer.parseUnsignedInt(args[0], 16));
    try {
      frame.setVolumeAdjustment(f);
      System.out.println(frame.volumeAdjustmentIndex());
    } catch (IllegalArgumentException expected) {
      if (!frame.render().equals(before) || frame.volumeAdjustmentIndex() != 17)
        throw new AssertionError("changed state");
      System.out.println("REJECTED; state unchanged");
    }
  }
}
