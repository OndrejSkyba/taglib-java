package cz.skyba.taglib.parity.dsd.probes;


import cz.skyba.taglib.dsf.DsfFile;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.FileStream;

import java.nio.file.Path;

public class DsfFileBoundaryProbe {
  public static void main(String[] args) {
    try (var stream = new FileStream(Path.of(args[0]))) {
      throw new AssertionError("Missing path accepted");
    } catch (java.io.UncheckedIOException expected) {
      System.out.println("missing-open\tUncheckedIOException");
    }
    try (var file = new DsfFile(new ByteVectorStream(new ByteVector("bad!")))) {
      throw new AssertionError("Invalid DSF accepted");
    } catch (cz.skyba.taglib.TagLibException expected) {
      System.out.println("invalid-open\tTagLibException");
    }
  }
}
