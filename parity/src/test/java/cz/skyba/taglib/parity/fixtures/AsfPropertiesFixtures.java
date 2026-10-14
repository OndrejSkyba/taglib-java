package cz.skyba.taglib.parity.fixtures;

import java.nio.file.Path;

import static cz.skyba.taglib.parity.fixtures.AsfFixtures.*;
import static cz.skyba.taglib.parity.fixtures.Bytes.*;

final class AsfPropertiesFixtures {
  private static byte[] entry(int type, String name, String description, byte[] info) {
    byte[] n = TagBytes.encoded(name, 4), d = TagBytes.encoded(description, 4);
    return join(
      pack("<HH", type, n.length / 2),
      n,
      uint(d.length / 2, 2, true),
      d,
      uint(info.length / 2, 2, true),
      info);
  }

  private static byte[] entry(int type, String name, String description) {
    return entry(type, name, description, new byte[0]);
  }

  private static byte[] list(long count, byte[]... entries) {
    return join(new byte[16], uint(count, 4, true), join(entries));
  }

  private static void put(GeneratedFiles f, String name, byte[]... extras) throws Exception {
    f.put(name + ".wma", base(fileData(10000000, 0), streamData(0x161), extras));
  }

  static void generate(Path root) throws Exception {
    var f = new GeneratedFiles(root);
    byte[] file = fileData(10000000, 0), stream = streamData(0x161);
    put(f, "defaults");
    for (int n = 0; n < 81; n++)
      f.put("file-partial-" + n + ".wma", base(slice(file, 0, n), stream));
    for (int n = 0; n < 71; n++)
      f.put("stream-partial-" + n + ".wma", base(file, slice(stream, 0, n)));
    for (int codec : new int[]{0, 1, 0x15f, 0x160, 0x161, 0x162, 0x163, 0x164, 65535})
      f.put("codec-" + codec + ".wma", base(file, streamData(codec)));
    for (int[] pair : new int[][]{{56, 2}, {58, 4}, {62, 4}, {68, 2}})
      for (long v : new long[]{0, 1, 32767, 32768, 65535, 0x7fffffff, 0x80000000L, 0xffffffffL})
        if (v < (1L << (pair[1] * 8))) {
          byte[] b = stream.clone();
          field(b, pair[0], pair[1], v, true);
          f.put("stream-field-" + pair[0] + "-" + v + ".wma", base(file, b));
        }
    for (long duration :
      new long[]{
        Long.MIN_VALUE,
        -10000001,
        -10000,
        -1,
        0,
        1,
        9999,
        10000,
        10000000,
        2147483646L * 10000,
        2147483647L * 10000,
        Long.MAX_VALUE
      })
      for (long preroll : new long[]{Long.MIN_VALUE, -1000, 0, 1000, Long.MAX_VALUE})
        f.put(
          "duration-" + duration + "-" + preroll + ".wma",
          base(fileData(duration, preroll), stream));
    byte[] audio = entry(2, " \t Audio é音 \n ", " description \t "),
      video = entry(1, "Video", "Video desc", hex("0102"));
    for (int n = 0; n < audio.length + 21; n++)
      put(f, "list-partial-" + n, object(LIST, slice(list(1, audio), 0, n)));
    byte[] odd = new byte[27];
    System.arraycopy(pack("<IHHH", 1, 2, 3, 65), 0, odd, 16, 10);
    odd[26] = 66;
    put(f, "list-odd-utf16-unit", object(LIST, odd));
    String[] names = {"empty", "spaces", "null", "unicode", "nbsp", "wide"},
      values = {"", " \t\n\r ", "a\0b", "é音", "\u00a0 value \u00a0", "x".repeat(1024)};
    for (int i = 0; i < names.length; i++)
      put(f, "list-string-" + names[i], object(LIST, list(1, entry(2, values[i], values[i]))));
    for (long count : new long[]{0, 1, 2, 0x7fffffff, 0x80000000L, 0xffffffffL})
      put(f, "list-count-" + count, object(LIST, list(count, video, audio)));
    String[] orders = {
      "video-first", "audio-first", "two-audio", "unknown-first", "no-audio", "empty-audio"
    };
    byte[][][] entries = {
      {video, audio},
      {audio, video},
      {audio, entry(2, "Other", "Other desc")},
      {entry(65535, "Unknown", ""), audio},
      {video},
      {entry(2, "", "")}
    };
    for (int i = 0; i < orders.length; i++)
      put(f, "list-" + orders[i], object(LIST, list(entries[i].length, entries[i])));
    for (int count : new int[]{0, 1, 65535})
      put(
        f,
        "list-info-skip-" + count,
        object(LIST, list(2, join(pack("<HHHH", 1, 0, 0, count), hex("0102"), audio))));
    String[] duplicateNames = {"empty", "no-audio", "audio", "empty-audio"};
    byte[][] duplicates = {
      new byte[0],
      list(1, video),
      list(1, entry(2, "Other", "Other desc")),
      list(1, entry(2, "", ""))
    };
    for (int i = 0; i < duplicates.length; i++)
      put(
        f,
        "list-duplicate-" + duplicateNames[i],
        object(LIST, list(1, audio)),
        object(LIST, duplicates[i]));
    for (String[] pair :
      new String[][]{
        {"content", "fbb3112223bdd211b4b700a0c955fc6e"},
        {"extended-pinned", "14e68a29222620174cb935dae07ee928"},
        {"extended-spec", "14e68a292226174cb935dae07ee9289c"},
        {"advanced", "b69b077aa4da124ea5ca91d38dc11a8d"},
        {"unrelated", "00000000000000000000000000000000"}
      })
      put(f, "encryption-" + pair[0], object(pair[1], text("opaque")));
    f.copyExtensions("upstream-", "", ".asf", ".wma");
  }
}
