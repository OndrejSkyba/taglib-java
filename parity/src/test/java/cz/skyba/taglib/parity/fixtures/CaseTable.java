package cz.skyba.taglib.parity.fixtures;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;

/**
 * Named recipe fields are written in the column order understood by both probes.
 */
final class CaseTable {
  private final GeneratedFiles files;
  private final LinkedHashMap<String, Object> defaults = new LinkedHashMap<>();
  private final StringBuilder rows = new StringBuilder();
  private int count;

  CaseTable(Path root, Object... fields) throws Exception {
    files = new GeneratedFiles(root);
    for (int i = 0; i < fields.length; i += 2) defaults.put((String) fields[i], fields[i + 1]);
  }

  static String hexField(byte[] bytes) {
    return bytes.length == 0 ? "-" : HexFormat.of().formatHex(bytes);
  }

  static String utf8Field(String value) {
    return hexField(value.getBytes(StandardCharsets.UTF_8));
  }

  static String values(String... values) {
    return values.length == 0
      ? "!"
      : String.join(",", Arrays.stream(values).map(CaseTable::utf8Field).toList());
  }

  void add(String group, Object... fields) {
    var values = new LinkedHashMap<>(defaults);
    for (int i = 0; i < fields.length; i += 2) {
      String field = (String) fields[i];
      if (!defaults.containsKey(field))
        throw new IllegalArgumentException("Unknown case field: " + field);
      values.put(field, fields[i + 1]);
    }
    rows.append(group).append('/').append(count++);
    for (var value : values.values())
      rows.append('\t').append(value instanceof byte[] b ? hexField(b) : value);
    rows.append('\n');
  }

  void finish() throws Exception {
    files.text("cases.tsv", rows.toString());
  }
}
