package cz.skyba.taglib.parity.fixtures;

import cz.skyba.taglib.parity.support.Parity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Names, byte counts, and fixture copies shared by both independent consumers.
 */
public final class Fixtures {
  public final Path root;
  public final Path inputs;
  public final List<Map<String, Object>> recipes = new ArrayList<>();

  public Fixtures(Path root) throws Exception {
    this.root = root;
    inputs = Files.createDirectories(root.resolve("inputs"));
    Files.createDirectories(root.resolve("outputs"));
  }

  public void write(String name, byte[] bytes) throws Exception {
    Files.write(inputs.resolve(name), bytes);
    recipes.add(new LinkedHashMap<>(Map.of("name", name, "bytes", bytes.length)));
  }

  public void upstream(String... extensions) throws Exception {
    try (var paths = Files.list(Parity.corpus())) {
      for (Path path : paths.sorted().toList())
        if (Arrays.stream(extensions).anyMatch(ext -> path.getFileName().toString().endsWith(ext)))
          write("upstream-" + path.getFileName(), Files.readAllBytes(path));
    }
  }

  public void manifest(String key) throws Exception {
    Parity.JSON.writeValue(root.resolve("recipes.json").toFile(), Map.of(key, recipes));
  }
}
