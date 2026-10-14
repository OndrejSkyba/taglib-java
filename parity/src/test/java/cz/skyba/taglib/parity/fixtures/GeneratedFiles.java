package cz.skyba.taglib.parity.fixtures;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/**
 * Writes temporary fixture bytes, independently of the implementation under test.
 */
final class GeneratedFiles {
  final Path root;
  private final List<Map<String, Object>> saveCases = new ArrayList<>();

  GeneratedFiles(Path root) throws Exception {
    this.root = root;
    for (String name : List.of("inputs", "outputs", "parents", "parent-outputs"))
      Files.createDirectories(root.resolve(name));
  }

  static String hash(byte[] data) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
  }

  void put(String name, byte[] data) throws Exception {
    Files.write(root.resolve("inputs").resolve(name), data);
  }

  void putSaveCase(String name, byte[] data, boolean writable) throws Exception {
    put(name, data);
    var record = new LinkedHashMap<String, Object>();
    record.put("name", name);
    record.put("write", writable);
    saveCases.add(record);
  }

  void saveCases() throws Exception {
    cz.skyba.taglib.parity.support.Parity.JSON.writeValue(
      root.resolve("recipes.json").toFile(), Map.of("inputs", saveCases));
  }

  void raw(String name, byte[] data) throws Exception {
    Path path = root.resolve(name);
    Files.createDirectories(path.getParent());
    Files.write(path, data);
  }

  void text(String name, String value) throws Exception {
    Files.writeString(root.resolve(name), value);
  }

  void copy(String name, String upstream) throws Exception {
    put(name, Files.readAllBytes(cz.skyba.taglib.parity.support.Parity.corpus().resolve(upstream)));
  }

  void copyExtensions(String prefix, String suffix, String... extensions) throws Exception {
    try (var files = Files.list(cz.skyba.taglib.parity.support.Parity.corpus())) {
      for (Path p : files.sorted().toList())
        if (Arrays.stream(extensions)
          .anyMatch(e -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(e)))
          put(prefix + p.getFileName() + suffix, Files.readAllBytes(p));
    }
  }
}
