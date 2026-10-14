package cz.skyba.taglib.parity.asf.probes;

import cz.skyba.taglib.asf.Tag;

import java.util.List;

public class AsfMutableBoundaryProbe {
  public static void main(String[] args) {
    for (String mode : List.of("year", "track", "legacy-track")) {
      var t = new Tag();
      String key =
        mode.equals("year") ? "WM/Year" : mode.equals("track") ? "WM/TrackNumber" : "WM/Track";
      t.setAttribute(key, List.of());
      System.out.println("before\t" + mode + "\t" + t.attributeListMap().size());
      boolean rejected = false;
      try {
        if (mode.equals("year")) t.year();
        else t.track();
      } catch (IllegalStateException expected) {
        rejected = true;
      }
      if (!rejected || !t.attributeListMap().get(key).isEmpty())
        throw new AssertionError("Unsafe list getter changed state");
      System.out.println("rejected\tunchanged");
    }
  }
}
