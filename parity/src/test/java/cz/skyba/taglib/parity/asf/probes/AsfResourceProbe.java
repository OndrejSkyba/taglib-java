package cz.skyba.taglib.parity.asf.probes;

import cz.skyba.taglib.asf.AsfFile;
import cz.skyba.taglib.parity.support.SaveOutcome;

import cz.skyba.taglib.asf.Attribute;
import cz.skyba.taglib.asf.Picture;
import cz.skyba.taglib.toolkit.ByteVector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Finite ordinary ASF workload, run with a named JVM heap cap after all builds stop.
 */
public class AsfResourceProbe {
  private static void require(boolean value) {
    if (!value) throw new AssertionError();
  }

  private static String hash(Path path) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    try (var in = Files.newInputStream(path)) {
      byte[] b = new byte[65536];
      int n;
      while ((n = in.read(b)) >= 0) digest.update(b, 0, n);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  public static void main(String[] args) throws Exception {
    Path original = Path.of(args[0]), output = Path.of(args[1]);
    int payload = Integer.parseInt(args[2]), attributes = Integer.parseInt(args[3]);
    Files.createDirectories(output.getParent());
    Files.copy(original, output, StandardCopyOption.REPLACE_EXISTING);
    String inputHash = hash(original);
    try (var file = new AsfFile(output)) {
      require(file.isValid());
      var picture = new Picture();
      picture.setMimeType("image/test");
      picture.setPicture(new ByteVector(payload, (byte) 0x5a));
      file.tag().setAttribute("WM/Picture", new Attribute(picture));
      for (int i = 0; i < attributes; i++)
        file.tag().setAttribute("Resource/" + i, new Attribute(i));
      require(SaveOutcome.save(file));
      file.tag().setTitle("x".repeat(32000));
      require(SaveOutcome.save(file));
      file.tag().setTitle("small");
      require(SaveOutcome.save(file));
    }
    try (var file = new AsfFile(output)) {
      require(file.isValid() && file.tag().title().equals("small"));
      var bytes = file.tag().attribute("WM/Picture").getFirst().toPicture().picture();
      require(bytes.size() == payload && bytes.at(0) == 0x5a && bytes.at(payload - 1) == 0x5a);
      for (int i = 0; i < attributes; i++)
        require(file.tag().attribute("Resource/" + i).getFirst().toUnsignedInt() == i);
    }
    require(inputHash.equals(hash(original)));
    System.out.println(
      "{\"verified\":true,\"payloadBytes\":"
        + payload
        + ",\"attributes\":"
        + attributes
        + ",\"saves\":3,\"inputSha256\":\""
        + inputHash
        + "\",\"outputSha256\":\""
        + hash(output)
        + "\",\"maxHeapBytes\":"
        + Runtime.getRuntime().maxMemory()
        + "}");
  }
}
