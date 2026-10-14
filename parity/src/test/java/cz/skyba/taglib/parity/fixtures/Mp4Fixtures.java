package cz.skyba.taglib.parity.fixtures;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;

import static cz.skyba.taglib.parity.fixtures.Bytes.*;

/**
 * The same independently encoded MP4 layouts used by the original comparison scripts.
 */
public final class Mp4Fixtures {
  private Mp4Fixtures() {
  }

  private static byte[] atom(String name, byte[] data, boolean extended) {
    return join(
      extended
        ? pack(">I4sQ", 1, text(name), data.length + 16L)
        : pack(">I4s", data.length + 8, text(name)),
      data);
  }

  private static byte[] atom(String name, byte[] data) {
    return atom(name, data, false);
  }

  private static byte[] atom(String name) {
    return atom(name, new byte[0]);
  }

  private static byte[] data(int flags, byte[] payload, long locale) {
    return atom("data", join(pack(">II", flags, locale), payload));
  }

  private static byte[] entry(String name, int flags, byte[] payload, long locale) {
    return atom(name, data(flags, payload, locale));
  }

  public static Fixtures mutable(Path directory) throws Exception {
    Fixtures f = new Fixtures(directory);
    f.upstream(".m4a", ".m4b", ".m4v", ".mp4");
    byte[] ftyp = atom("ftyp", join(text("M4A "), new byte[8])),
      mdat = atom("mdat", join(text("AUDIO"), range(128)));
    for (boolean extended : new boolean[]{false, true})
      for (String placement :
        List.of(
          "none",
          "empty-udta",
          "empty-meta",
          "empty-ilst",
          "padded-ilst",
          "non-full",
          "unknown-udta")) {
        byte[] ilst =
          atom(
            "ilst",
            join(entry("©nam", 1, text("Before"), 0), entry("zzzz", 1, text("Unknown"), 0)));
        byte[] meta = atom("meta", join(new byte[4], ilst), extended);
        byte[] udta =
          switch (placement) {
            case "none" -> new byte[0];
            case "empty-udta" -> atom("udta");
            case "empty-meta" -> atom("udta", atom("meta", new byte[4]));
            case "empty-ilst" -> atom("udta", atom("meta", join(new byte[4], atom("ilst"))));
            case "padded-ilst" -> atom(
              "udta",
              atom(
                "meta",
                join(
                  new byte[4],
                  atom("free", new byte[20]),
                  ilst,
                  atom("free", new byte[32]))));
            case "non-full" -> atom("udta", atom("meta", ilst));
            default -> atom("udta", join(meta, atom("chpl", new byte[9]), atom("uuid", text("KEEP"))));
          };
        byte[] track =
          atom(
            "trak",
            atom(
              "mdia",
              atom(
                "minf",
                atom(
                  "stbl",
                  join(
                    atom("stco", pack(">IIII", 0, 2, 0, 1000)),
                    atom("co64", pack(">IIQQ", 0, 2, 0, 1000)))))));
        f.write(
          "layout-" + bool(extended) + "-" + placement + ".m4a",
          join(
            ftyp,
            atom("moov", join(track, udta), extended),
            mdat,
            atom("moof", atom("traf", atom("tfhd", pack(">IIQ", 1, 1, 1000))))));
      }
    Object[][] typed = {
      {"cpil", 21, hex("01")},
      {"pgap", 21, hex("00")},
      {"rtng", 21, hex("ff")},
      {"tmpo", 21, hex("fffe")},
      {"trkn", 0, hex("00000002000a0000")},
      {"disk", 0, hex("000000010003")},
      {"gnre", 0, hex("0016")},
      {"plID", 21, pack(">q", -7)},
      {"cnID", 21, pack(">I", 0xffffffffL)},
      {"covr", 27, text("BMfoo")},
      {"covr", 99, text("GIF89a")},
      {"©nam", 1, hex("ff80")},
      {"©nam", 0, text("implicit")}
    };
    for (Object[] row : typed)
      for (long locale : new long[]{0, 0xffffffffL}) {
        String name = (String) row[0];
        int flags = (int) row[1];
        f.write(
          "typed-" + HexFormat.of().formatHex(text(name)) + "-" + flags + "-" + locale + ".m4a",
          join(
            ftyp,
            atom(
              "moov",
              atom(
                "udta",
                atom(
                  "meta",
                  join(
                    new byte[4],
                    atom("ilst", entry(name, flags, (byte[]) row[2], locale)))))),
            mdat));
      }
    for (int flags :
      new int[]{0, 1, 2, 3, 6, 7, 8, 9, 10, 12, 13, 14, 15, 16, 17, 18, 21, 24, 25, 27, 255}) {
      byte[] mean = atom("mean", join(new byte[4], text("com.apple.iTunes"))),
        name = atom("name", join(new byte[4], text("TEST")));
      f.write(
        "freeform-" + flags + ".m4a",
        join(
          ftyp,
          atom(
            "moov",
            atom(
              "udta",
              atom(
                "meta",
                join(
                  new byte[4],
                  atom(
                    "ilst",
                    atom(
                      "----",
                      join(
                        mean,
                        name,
                        data(flags, text("one"), 0),
                        data(flags, text("two"), 0)))))))),
          mdat));
    }
    f.manifest("inputs");
    return f;
  }

  private static byte[] sample(byte[] codec, int size) {
    byte[] d = new byte[size];
    field(d, 4, 4, 1, false);
    field(d, 8, 4, size - 8, false);
    System.arraycopy(codec, 0, d, 12, Math.min(4, size - 12));
    field(d, 32, 2, 2, false);
    field(d, 34, 2, 16, false);
    field(d, 38, 4, 44100, false);
    return d;
  }

  private static byte[] mdhd(long unit, long frames, int version, Integer size, boolean extended) {
    byte[] d = new byte[version == 1 ? 36 : 24];
    d[0] = (byte) version;
    field(d, version == 1 ? 20 : 12, 4, unit, false);
    field(d, version == 1 ? 24 : 16, version == 1 ? 8 : 4, frames, false);
    return atom("mdhd", size == null ? d : slice(d, 0, size), extended);
  }

  private static byte[] track(byte[] description, boolean header, byte[] media, boolean extended) {
    return atom(
      "trak",
      atom(
        "mdia",
        join(
          header
            ? atom("hdlr", join(new byte[8], text("soun"), new byte[12]), extended)
            : new byte[0],
          media == null ? mdhd(44100, 44100, 0, null, false) : media,
          atom(
            "minf",
            atom(
              "stbl",
              atom(
                "stsd",
                description == null ? sample(text("mp4a"), 42) : description,
                extended))))));
  }

  private static byte[] track(byte[] description) {
    return track(description, true, null, false);
  }

  private static byte[] mp4(List<byte[]> tracks, int mdat, byte[] extras, byte[] nested) {
    return join(
      atom("ftyp", join(text("M4A "), new byte[8])),
      atom("moov", join(tracks == null ? track(null) : join(tracks), extras, nested)),
      atom("mdat", new byte[mdat]));
  }

  private static byte[] mp4(List<byte[]> tracks) {
    return mp4(tracks, 128, new byte[0], new byte[0]);
  }

  public static Fixtures properties(Path directory) throws Exception {
    Fixtures f = new Fixtures(directory);
    for (byte[] codec :
      new byte[][]{
        text("mp4a"),
        text("alac"),
        text("ac-3"),
        text("ec-3"),
        text("fLaC"),
        text("Opus"),
        text("dtsc"),
        text("dtse"),
        text("dtsh"),
        text("dtsl"),
        text("zzzz"),
        hex("00ff0080")
      })
      for (int size = 16; size < 85; size++)
        f.write(
          "partial-" + HexFormat.of().formatHex(codec) + "-" + size + ".m4a",
          mp4(List.of(track(sample(codec, size)))));
    f.write("no-tracks.m4a", mp4(List.of()));
    f.write("no-first-handler.m4a", mp4(List.of(track(null, false, null, false), track(null))));
    f.write("no-media-header.m4a", mp4(List.of(track(null, true, new byte[0], false))));
    for (int size = 0; size < 37; size++)
      for (int version : new int[]{0, 1})
        f.write(
          "mdhd-partial-" + version + "-" + size + ".m4a",
          mp4(List.of(track(null, true, mdhd(44100, 44100, version, size, false), false))));
    for (int version : new int[]{0, 1})
      for (long unit : new long[]{0, 1, 1000, 44100, 1L << 31, 0xffffffffL})
        for (long frames :
          version == 1
            ? new long[]{
            0, 1, 44100, 0x7fffffffL, 0xffffffffL, Long.MAX_VALUE, Long.MIN_VALUE, -1L
          }
            : new long[]{0, 1, 44100, 0x7fffffffL, 0xffffffffL})
          f.write(
            "timing-" + version + "-" + unit + "-" + Long.toUnsignedString(frames) + ".m4a",
            mp4(List.of(track(null, true, mdhd(unit, frames, version, null, false), false))));
    for (int[] field : new int[][]{{32, 2}, {34, 2}, {38, 4}})
      for (String codec : List.of("mp4a", "zzzz"))
        for (long value :
          new long[]{0, 1, 32767, 32768, 65535, 0x7fffffffL, 1L << 31, 0xffffffffL}) {
          if (value >= (1L << (field[1] * 8))) continue;
          byte[] d = sample(text(codec), 42);
          field(d, field[0], field[1], value, false);
          f.write(
            "field-"
              + HexFormat.of().formatHex(text(codec))
              + "-"
              + field[0]
              + "-"
              + value
              + ".m4a",
            mp4(List.of(track(d))));
        }
    for (boolean lead : new boolean[]{false, true})
      for (boolean second : new boolean[]{false, true})
        for (long nominal : new long[]{0, 1, 499, 500, 128000, 0xffffffffL}) {
          byte[] d = sample(text("mp4a"), 90);
          System.arraycopy(text("esds"), 0, d, 48, 4);
          d[56] = 3;
          int pos = 57;
          if (lead) {
            Arrays.fill(d, pos, pos + 3, (byte) 0x80);
            pos += 3;
          }
          pos += 4;
          d[pos++] = 4;
          if (second) {
            Arrays.fill(d, pos, pos + 3, (byte) 0x80);
            pos += 3;
          }
          pos += 10;
          field(d, pos, 4, nominal, false);
          for (int size :
            new TreeSet<>(List.of(52, 57, pos, pos + 1, pos + 2, pos + 3, pos + 4, 90))) {
            byte[] partial = slice(d, 0, size);
            field(partial, 8, 4, size - 8, false);
            f.write(
              "esds-" + bool(lead) + "-" + bool(second) + "-" + nominal + "-" + size + ".m4a",
              mp4(List.of(track(partial))));
          }
        }
    for (int bits : new int[]{0, 16, 127, 128, 255})
      for (int channels : new int[]{0, 2, 127, 128, 255}) {
        byte[] d = sample(text("alac"), 80);
        System.arraycopy(text("alac"), 0, d, 48, 4);
        d[61] = (byte) bits;
        d[65] = (byte) channels;
        field(d, 72, 4, 0, false);
        field(d, 76, 4, 44100, false);
        f.write("alac-" + bits + "-" + channels + ".m4a", mp4(List.of(track(d))));
      }
    for (String[] codec : new String[][]{{"fLaC", "dfLa"}, {"ec-3", "dec3"}})
      for (int size = 42; size < 85; size++) {
        byte[] d = sample(text(codec[0]), 84);
        System.arraycopy(text(codec[1]), 0, d, 44, 4);
        field(d, 48, 2, 65535, false);
        field(d, 66, 4, (96000L << 12) | (7L << 9) | (23L << 4), false);
        d = slice(d, 0, size);
        field(d, 8, 4, size - 8, false);
        f.write(
          "config-" + HexFormat.of().formatHex(text(codec[0])) + "-" + size + ".m4a",
          mp4(List.of(track(d))));
      }
    for (String where : List.of("root", "moov", "udta", "stsd", "moof"))
      for (int size : new int[]{0, 1, 127, 65535}) {
        byte[] nested = atom("mdat", new byte[size]);
        byte[] data = mp4(null, 0, new byte[0], new byte[0]);
        data =
          switch (where) {
            case "root" -> join(data, nested);
            case "moov" -> mp4(List.of(track(sample(text("zzzz"), 42))), 0, new byte[0], nested);
            case "udta" -> mp4(
              List.of(track(sample(text("zzzz"), 42))),
              0,
              new byte[0],
              atom("udta", nested));
            case "stsd" -> mp4(
              List.of(track(join(sample(text("zzzz"), 42), nested))),
              0,
              new byte[0],
              new byte[0]);
            default -> join(
              mp4(List.of(track(sample(text("zzzz"), 42))), 0, new byte[0], new byte[0]),
              atom("moof", atom("traf", nested)));
          };
        f.write("mdat-" + where + "-" + size + ".m4a", data);
      }
    for (String first : List.of("drms", "mp4a", "zzzz"))
      for (int count : new int[]{0, 1, 2}) {
        byte[] d = sample(text(first), 42);
        field(d, 4, 4, count, false);
        f.write(
          "encrypted-" + HexFormat.of().formatHex(text(first)) + "-" + count + ".m4a",
          mp4(List.of(track(join(d, atom("drms"))))));
      }
    for (String kind : List.of("hdlr", "stsd", "mdhd"))
      f.write(
        "extended-" + kind + ".m4a",
        mp4(
          List.of(
            kind.equals("mdhd")
              ? track(null, true, mdhd(44100, 44100, 0, null, true), false)
              : track(null, true, null, true))));
    for (int version : new int[]{0, 1})
      for (int size : new int[]{0, 19, 20, 24, 36}) {
        byte[] movie = mdhd(1000, 1000, version, size, false);
        System.arraycopy(text("mvhd"), 0, movie, 4, 4);
        f.write(
          "movie-fallback-" + version + "-" + size + ".m4a",
          mp4(
            List.of(track(null, true, mdhd(44100, 0, 0, null, false), false)),
            128,
            movie,
            new byte[0]));
      }
    f.upstream(".m4a", ".m4b", ".mp4", ".m4v");
    f.manifest("recipes");
    return f;
  }
}
