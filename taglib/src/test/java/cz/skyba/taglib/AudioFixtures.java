package cz.skyba.taglib;

import cz.skyba.taglib.toolkit.ByteVector;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Local audio samples for fixed assertions and round-trip tests.
 */
public final class AudioFixtures {
  private AudioFixtures() {
  }

  public static Path fixture(String name) {
    var resource = AudioFixtures.class.getResource("/audio/" + name);
    assertNotNull(resource, name);
    return Path.of(URI.create(resource.toExternalForm()));
  }

  public static Path copy(Path dir, String name) throws Exception {
    Path p = dir.resolve(name);
    Files.copy(fixture(name), p);
    return p;
  }

  public static ByteVector flacBlock(int type, ByteVector payload) {
    var h = ByteVector.fromUnsignedInt(payload.size(), true);
    h.set(0, (byte) type);
    return h.append(payload);
  }

  public static ByteVector flacApp(ByteVector payload) {
    return new ByteVector("fLaC").append(flacBlock(0, new ByteVector(34))).append(flacBlock(130, payload));
  }

  public static ByteVector wrap(String id, ByteVector payload) {
    return new ByteVector("riff").append(new ByteVector(id)).append(ByteVector.fromUnsignedInt(payload.size(), false)).append(payload);
  }
}
