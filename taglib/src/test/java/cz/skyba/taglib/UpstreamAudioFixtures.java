package cz.skyba.taglib;

import cz.skyba.taglib.toolkit.ByteVector;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pinned test assets; synthetic expectations are specified in independent tests.
 */
public final class UpstreamAudioFixtures {
  private UpstreamAudioFixtures() {
  }

  public static Path upstreamSource(Path project) {
    return Path.of(System.getenv().getOrDefault("TAGLIB_SOURCE_DIR",
      project.resolve("work/upstream/verified/taglib-" + TagLib.UPSTREAM_VERSION).toString()));
  }

  public static Path fixture(String name) {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("taglib/src"))) root = root.getParent();
    assertNotNull(root);
    Path p = UpstreamAudioFixtures.upstreamSource(root).resolve("tests/data/" + name);
    assertTrue(Files.isRegularFile(p), name);
    return p;
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
