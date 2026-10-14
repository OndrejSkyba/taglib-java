package cz.skyba.taglib.parity.core.probes;

import cz.skyba.taglib.parity.support.FileRefAdapter;
import cz.skyba.taglib.toolkit.TagString;

import java.nio.file.Path;
import java.util.Arrays;

class FileRefRemovalProbe {
  static String hex(String s) {
    return new TagString(s).data(TagString.Type.UTF8).toHex().toString();
  }

  static void dump(String label, FileRefAdapter file) {
    var out = new StringBuilder(label + "|" + (file.isNull() ? 1 : 0) + "|");
    file.properties()
      .asMap()
      .forEach(
        (key, values) -> {
          out.append(hex(key)).append('=');
          values.forEach(value -> out.append(hex(value)).append(','));
          out.append(';');
        });
    out.append('/');
    file.properties().unsupportedData().forEach(value -> out.append(hex(value)).append(','));
    out.append('/');
    file.complexPropertyKeys().forEach(value -> out.append(hex(value)).append(','));
    System.out.println(out);
  }

  public static void main(String[] args) {
    try (var file = new FileRefAdapter(Path.of(args[0]))) {
      dump("before", file);
      var removals =
        args.length > 1
          ? Arrays.asList(args[1].split(",", -1))
          : file.properties().unsupportedData();
      file.removeUnsupportedProperties(removals);
      dump("staged", file);
      System.out.println("saved|" + (file.save() ? 1 : 0) + "|" + (file.save() ? 1 : 0));
      try (var readback = new FileRefAdapter(Path.of(args[0]))) {
        dump("after", readback);
      }
    }
  }
}
