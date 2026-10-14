package cz.skyba.taglib.parity.riff.probes;

import cz.skyba.taglib.parity.support.FileRefAdapter;

import java.nio.file.Path;

public class UnknownInfoProbe {
  public static void main(String[] args) throws Exception {
    try (var ref = new FileRefAdapter(Path.of(args[0]))) {
      System.out.println("valid=" + !ref.isNull());
      System.out.println("unsupportedDataCount=" + ref.properties().unsupportedData().size());
      System.out.println("tagIsEmpty=" + ref.tag().isEmpty());
    }
  }
}
