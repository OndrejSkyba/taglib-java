package cz.skyba.taglib.parity.fixtures;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@Tag("support")
class FixtureCacheInputDependenciesTest {
  @TempDir
  Path directory;

  @Test
  void syntheticOnlyFixturesIgnoreUpstreamChanges() throws Exception {
    var upstream = new Properties();
    String original = FixtureCache.inputsIdentity("id3-headers", upstream);
    upstream.setProperty("corpusSha256", "different samples");
    upstream.setProperty("sourceSha256", "different C++ implementation");
    upstream.setProperty("version", "another version");
    assertEquals(original, FixtureCache.inputsIdentity("id3-headers", upstream));
  }

  @Test
  void fixturesCopyingSamplesTrackSamplesButNotTheCppImplementation() throws Exception {
    var upstream = new Properties();
    upstream.setProperty("corpusSha256", "original samples");
    String original = FixtureCache.inputsIdentity("mpeg-files", upstream);
    upstream.setProperty("sourceSha256", "changed implementation");
    assertEquals(original, FixtureCache.inputsIdentity("mpeg-files", upstream));
    upstream.setProperty("corpusSha256", "changed samples");
    assertNotEquals(original, FixtureCache.inputsIdentity("mpeg-files", upstream));
  }

  @Test
  void translationFixturesTrackTheMappingWithoutTrackingOtherSourceChanges() throws Exception {
    Path source = directory.resolve("taglib/mpeg/id3v2/id3v2frame.cpp");
    Files.createDirectories(source.getParent());
    String table =
      "constexpr std::array frameTranslation {\n  std::pair(\"TIT2\", \"TITLE\"),\n};\n";
    Files.writeString(source, table);
    var upstream = new Properties();
    upstream.setProperty("source", directory.toString());
    String name = "id3-frame-headers-payloads-and-translation";
    String original = FixtureCache.inputsIdentity(name, upstream);
    Files.writeString(source, "// Unrelated implementation change\n" + table);
    upstream.setProperty("sourceSha256", "changed source");
    assertEquals(original, FixtureCache.inputsIdentity(name, upstream));
    Files.writeString(source, table.replace("TITLE", "NEW_TITLE"));
    assertNotEquals(original, FixtureCache.inputsIdentity(name, upstream));
  }
}
