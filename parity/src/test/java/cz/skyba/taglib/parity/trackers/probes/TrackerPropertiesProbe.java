package cz.skyba.taglib.parity.trackers.probes;

import cz.skyba.taglib.parity.support.FileRefAdapter;
import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.toolkit.ByteVectorStream;

import java.nio.file.Files;
import java.nio.file.Path;

class TrackerPropertiesProbe {
  static void row(String id, String fields, long cursor) {
    System.out.println(id + "\t" + fields + "\t" + cursor);
  }

  static String fields(cz.skyba.taglib.mod.Properties p) {
    return ""
      + p.lengthInMilliseconds()
      + "\t"
      + p.lengthInSeconds()
      + "\t"
      + p.bitrate()
      + "\t"
      + p.sampleRate()
      + "\t"
      + p.channels()
      + "\t"
      + p.instrumentCount()
      + "\t"
      + p.lengthInPatterns();
  }

  static void mutate(cz.skyba.taglib.mod.Properties p, int field, long value) {
    switch (field) {
      case 0 -> p.setChannels((int) value);
      case 1 -> p.setInstrumentCount(value);
      case 2 -> p.setLengthInPatterns((int) value);
      default -> throw new IllegalArgumentException();
    }
  }

  static void operations_mod(String base, int field, long value, int style) {
    var p = new cz.skyba.taglib.mod.Properties(ReadStyle.values()[style]);
    if (field >= 0) mutate(p, field, value);
    else if (field == -2) for (int index = 0; index < 3; index++) mutate(p, index, value);
    row(base + "/" + style, fields(p), -1);
  }

  static String modPath(Path path, ReadStyle style) throws Exception {
    try {
      return fields(
        style == null
          ? new cz.skyba.taglib.mod.Properties(path)
          : new cz.skyba.taglib.mod.Properties(path, style));
    } catch (cz.skyba.taglib.TagLibException | java.io.UncheckedIOException e) {
      return "REJECT";
    }
  }

  static String modStream(ByteVectorStream stream, ReadStyle style) throws Exception {
    try {
      return fields(
        style == null
          ? new cz.skyba.taglib.mod.Properties(stream)
          : new cz.skyba.taglib.mod.Properties(stream, style));
    } catch (cz.skyba.taglib.TagLibException | java.io.UncheckedIOException e) {
      return "REJECT";
    }
  }

  static void file_mod(Path path, byte[] data) throws Exception {
    String name = path.getFileName().toString();
    for (int style = 0; style < 3; style++)
      for (int enabled = 0; enabled < 2; enabled++) {
        var readStyle = ReadStyle.values()[style];
        String fields = modPath(path, readStyle);
        try (var ref = new FileRefAdapter(path, enabled != 0, readStyle)) {
          if (fields.equals("REJECT")
            ? !ref.isNull()
            : ref.isNull()
            || !ref.audioProperties()
            .equals(new cz.skyba.taglib.mod.Properties(path, readStyle).common()))
            throw new AssertionError("tracker read controls " + name);
        }
        row(name + "/path/" + enabled + "/" + style, fields, -1);
        for (int origin : new int[]{0, 7})
          for (int snapshot = 0; snapshot < 2; snapshot++) {
            var stream = new ByteVectorStream(data);
            stream.seek(origin);
            String borrowed = modStream(stream, readStyle);
            if (snapshot != 0) stream.data().clear();
            if (!stream.isOpen()) throw new AssertionError("borrowed stream closed");
            row(
              name + "/io/" + enabled + "/" + style + "/" + origin + "/" + snapshot,
              borrowed,
              stream.tell());
          }
      }
    row(name + "/path/default", modPath(path, null), -1);
    for (int snapshot = 0; snapshot < 2; snapshot++) {
      var stream = new ByteVectorStream(data);
      String fields = modStream(stream, null);
      if (snapshot != 0) stream.data().clear();
      row(name + "/io/default/" + snapshot, fields, stream.tell());
    }
  }

  static String fields(cz.skyba.taglib.s3m.Properties p) {
    return ""
      + p.lengthInMilliseconds()
      + "\t"
      + p.lengthInSeconds()
      + "\t"
      + p.bitrate()
      + "\t"
      + p.sampleRate()
      + "\t"
      + p.lengthInPatterns()
      + "\t"
      + p.channels()
      + "\t"
      + (p.stereo() ? 1 : 0)
      + "\t"
      + p.sampleCount()
      + "\t"
      + p.patternCount()
      + "\t"
      + p.flags()
      + "\t"
      + p.trackerVersion()
      + "\t"
      + p.fileFormatVersion()
      + "\t"
      + p.globalVolume()
      + "\t"
      + p.masterVolume()
      + "\t"
      + p.tempo()
      + "\t"
      + p.bpmSpeed();
  }

  static void mutate(cz.skyba.taglib.s3m.Properties p, int field, long value) {
    switch (field) {
      case 0 -> p.setLengthInPatterns((int) value);
      case 1 -> p.setChannels((int) value);
      case 2 -> p.setStereo(value != 0);
      case 3 -> p.setSampleCount((int) value);
      case 4 -> p.setPatternCount((int) value);
      case 5 -> p.setFlags((int) value);
      case 6 -> p.setTrackerVersion((int) value);
      case 7 -> p.setFileFormatVersion((int) value);
      case 8 -> p.setGlobalVolume((int) value);
      case 9 -> p.setMasterVolume((int) value);
      case 10 -> p.setTempo((int) value);
      case 11 -> p.setBpmSpeed((int) value);
      default -> throw new IllegalArgumentException();
    }
  }

  static void operations_s3m(String base, int field, long value, int style) {
    var p = new cz.skyba.taglib.s3m.Properties(ReadStyle.values()[style]);
    if (field >= 0) mutate(p, field, value);
    else if (field == -2) for (int index = 0; index < 12; index++) mutate(p, index, value);
    row(base + "/" + style, fields(p), -1);
  }

  static void file_s3m(Path path, byte[] data) throws Exception {
    String name = path.getFileName().toString();
    int[] origins =
      data.length > 36 && data[35] == 26 && data[36] == 16 ? new int[]{0, 7} : new int[]{0};
    for (int style = 0; style < 3; style++)
      for (int enabled = 0; enabled < 2; enabled++) {
        var readStyle = ReadStyle.values()[style];
        var p = new cz.skyba.taglib.s3m.Properties(path, readStyle);
        try (var ref = new FileRefAdapter(path, enabled != 0, readStyle)) {
          if (ref.isNull() || !ref.audioProperties().equals(p.common()))
            throw new AssertionError("tracker read controls " + name);
        }
        row(name + "/path/" + enabled + "/" + style, fields(p), -1);
        for (int origin : origins)
          for (int snapshot = 0; snapshot < 2; snapshot++) {
            var stream = new ByteVectorStream(data);
            stream.seek(origin);
            var borrowed = new cz.skyba.taglib.s3m.Properties(stream, readStyle);
            if (snapshot != 0) stream.data().clear();
            if (!stream.isOpen()) throw new AssertionError("borrowed stream closed");
            row(
              name + "/io/" + enabled + "/" + style + "/" + origin + "/" + snapshot,
              fields(borrowed),
              stream.tell());
          }
      }
    row(name + "/path/default", fields(new cz.skyba.taglib.s3m.Properties(path)), -1);
    for (int snapshot = 0; snapshot < 2; snapshot++) {
      var stream = new ByteVectorStream(data);
      var p = new cz.skyba.taglib.s3m.Properties(stream);
      if (snapshot != 0) stream.data().clear();
      row(name + "/io/default/" + snapshot, fields(p), stream.tell());
    }
  }

  static String fields(cz.skyba.taglib.it.Properties p) {
    return ""
      + p.lengthInMilliseconds()
      + "\t"
      + p.lengthInSeconds()
      + "\t"
      + p.bitrate()
      + "\t"
      + p.sampleRate()
      + "\t"
      + p.channels()
      + "\t"
      + p.lengthInPatterns()
      + "\t"
      + p.instrumentCount()
      + "\t"
      + p.sampleCount()
      + "\t"
      + p.patternCount()
      + "\t"
      + p.version()
      + "\t"
      + p.compatibleVersion()
      + "\t"
      + p.flags()
      + "\t"
      + p.special()
      + "\t"
      + p.globalVolume()
      + "\t"
      + p.mixVolume()
      + "\t"
      + p.tempo()
      + "\t"
      + p.bpmSpeed()
      + "\t"
      + p.panningSeparation()
      + "\t"
      + p.pitchWheelDepth()
      + "\t"
      + (p.stereo() ? 1 : 0);
  }

  static void mutate(cz.skyba.taglib.it.Properties p, int field, long value) {
    switch (field) {
      case 0 -> p.setChannels((int) value);
      case 1 -> p.setLengthInPatterns((int) value);
      case 2 -> p.setInstrumentCount((int) value);
      case 3 -> p.setSampleCount((int) value);
      case 4 -> p.setPatternCount((int) value);
      case 5 -> p.setVersion((int) value);
      case 6 -> p.setCompatibleVersion((int) value);
      case 7 -> p.setFlags((int) value);
      case 8 -> p.setSpecial((int) value);
      case 9 -> p.setGlobalVolume((int) value);
      case 10 -> p.setMixVolume((int) value);
      case 11 -> p.setTempo((int) value);
      case 12 -> p.setBpmSpeed((int) value);
      case 13 -> p.setPanningSeparation((int) value);
      case 14 -> p.setPitchWheelDepth((int) value);
      default -> throw new IllegalArgumentException();
    }
  }

  static void operations_it(String base, int field, long value, int style) {
    var p = new cz.skyba.taglib.it.Properties(ReadStyle.values()[style]);
    if (field >= 0) mutate(p, field, value);
    else if (field == -2) for (int index = 0; index < 15; index++) mutate(p, index, value);
    row(base + "/" + style, fields(p), -1);
  }

  static void file_it(Path path, byte[] data) throws Exception {
    String name = path.getFileName().toString();
    for (int style = 0; style < 3; style++)
      for (int enabled = 0; enabled < 2; enabled++) {
        var readStyle = ReadStyle.values()[style];
        var p = new cz.skyba.taglib.it.Properties(path, readStyle);
        try (var ref = new FileRefAdapter(path, enabled != 0, readStyle)) {
          if (ref.isNull() || !ref.audioProperties().equals(p.common()))
            throw new AssertionError("tracker read controls " + name);
        }
        row(name + "/path/" + enabled + "/" + style, fields(p), -1);
        for (int origin : new int[]{0, 7})
          for (int snapshot = 0; snapshot < 2; snapshot++) {
            var stream = new ByteVectorStream(data);
            stream.seek(origin);
            var borrowed = new cz.skyba.taglib.it.Properties(stream, readStyle);
            if (snapshot != 0) stream.data().clear();
            if (!stream.isOpen()) throw new AssertionError("borrowed stream closed");
            row(
              name + "/io/" + enabled + "/" + style + "/" + origin + "/" + snapshot,
              fields(borrowed),
              stream.tell());
          }
      }
    row(name + "/path/default", fields(new cz.skyba.taglib.it.Properties(path)), -1);
    for (int snapshot = 0; snapshot < 2; snapshot++) {
      var stream = new ByteVectorStream(data);
      var p = new cz.skyba.taglib.it.Properties(stream);
      if (snapshot != 0) stream.data().clear();
      row(name + "/io/default/" + snapshot, fields(p), stream.tell());
    }
  }

  static String fields(cz.skyba.taglib.xm.Properties p) {
    return ""
      + p.lengthInMilliseconds()
      + "\t"
      + p.lengthInSeconds()
      + "\t"
      + p.bitrate()
      + "\t"
      + p.sampleRate()
      + "\t"
      + p.lengthInPatterns()
      + "\t"
      + p.channels()
      + "\t"
      + p.version()
      + "\t"
      + p.restartPosition()
      + "\t"
      + p.patternCount()
      + "\t"
      + p.instrumentCount()
      + "\t"
      + p.sampleCount()
      + "\t"
      + p.flags()
      + "\t"
      + p.tempo()
      + "\t"
      + p.bpmSpeed();
  }

  static void mutate(cz.skyba.taglib.xm.Properties p, int field, long value) {
    switch (field) {
      case 0 -> p.setLengthInPatterns((int) value);
      case 1 -> p.setChannels((int) value);
      case 2 -> p.setVersion((int) value);
      case 3 -> p.setRestartPosition((int) value);
      case 4 -> p.setPatternCount((int) value);
      case 5 -> p.setInstrumentCount((int) value);
      case 6 -> p.setSampleCount(value);
      case 7 -> p.setFlags((int) value);
      case 8 -> p.setTempo((int) value);
      case 9 -> p.setBpmSpeed((int) value);
      default -> throw new IllegalArgumentException();
    }
  }

  static void operations_xm(String base, int field, long value, int style) {
    var p = new cz.skyba.taglib.xm.Properties(ReadStyle.values()[style]);
    if (field >= 0) mutate(p, field, value);
    else if (field == -2) for (int index = 0; index < 10; index++) mutate(p, index, value);
    row(base + "/" + style, fields(p), -1);
  }

  static void file_xm(Path path, byte[] data) throws Exception {
    String name = path.getFileName().toString();
    for (int style = 0; style < 3; style++)
      for (int enabled = 0; enabled < 2; enabled++) {
        var readStyle = ReadStyle.values()[style];
        var p = new cz.skyba.taglib.xm.Properties(path, readStyle);
        try (var ref = new FileRefAdapter(path, enabled != 0, readStyle)) {
          if (ref.isNull() || !ref.audioProperties().equals(p.common()))
            throw new AssertionError("tracker read controls " + name);
        }
        row(name + "/path/" + enabled + "/" + style, fields(p), -1);
        for (int origin : new int[]{0, 7})
          for (int snapshot = 0; snapshot < 2; snapshot++) {
            var stream = new ByteVectorStream(data);
            stream.seek(origin);
            var borrowed = new cz.skyba.taglib.xm.Properties(stream, readStyle);
            if (snapshot != 0) stream.data().clear();
            if (!stream.isOpen()) throw new AssertionError("borrowed stream closed");
            row(
              name + "/io/" + enabled + "/" + style + "/" + origin + "/" + snapshot,
              fields(borrowed),
              stream.tell());
          }
      }
    row(name + "/path/default", fields(new cz.skyba.taglib.xm.Properties(path)), -1);
    for (int snapshot = 0; snapshot < 2; snapshot++) {
      var stream = new ByteVectorStream(data);
      var p = new cz.skyba.taglib.xm.Properties(stream);
      if (snapshot != 0) stream.data().clear();
      row(name + "/io/default/" + snapshot, fields(p), stream.tell());
    }
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    System.out.println("constants/mod");
    for (int style = 0; style < 3; style++) operations_mod("default/mod", -1, 0, style);
    System.out.println(
      "constants/s3m"
        + "\t"
        + cz.skyba.taglib.s3m.Properties.ST2Vibrato
        + "\t"
        + cz.skyba.taglib.s3m.Properties.ST2Tempo
        + "\t"
        + cz.skyba.taglib.s3m.Properties.AmigaSlides
        + "\t"
        + cz.skyba.taglib.s3m.Properties.Vol0MixOptimizations
        + "\t"
        + cz.skyba.taglib.s3m.Properties.AmigaLimits
        + "\t"
        + cz.skyba.taglib.s3m.Properties.EnableFilter
        + "\t"
        + cz.skyba.taglib.s3m.Properties.CustomData);
    for (int style = 0; style < 3; style++) operations_s3m("default/s3m", -1, 0, style);
    System.out.println(
      "constants/it"
        + "\t"
        + cz.skyba.taglib.it.Properties.Stereo
        + "\t"
        + cz.skyba.taglib.it.Properties.Vol0MixOptimizations
        + "\t"
        + cz.skyba.taglib.it.Properties.UseInstruments
        + "\t"
        + cz.skyba.taglib.it.Properties.LinearSlides
        + "\t"
        + cz.skyba.taglib.it.Properties.OldEffects
        + "\t"
        + cz.skyba.taglib.it.Properties.LinkEffects
        + "\t"
        + cz.skyba.taglib.it.Properties.UseMidiPitchController
        + "\t"
        + cz.skyba.taglib.it.Properties.RequestEmbeddedMidiConf
        + "\t"
        + cz.skyba.taglib.it.Properties.MessageAttached
        + "\t"
        + cz.skyba.taglib.it.Properties.MidiConfEmbedded);
    for (int style = 0; style < 3; style++) operations_it("default/it", -1, 0, style);
    System.out.println("constants/xm" + "\t" + cz.skyba.taglib.xm.Properties.LinearFreqTable);
    for (int style = 0; style < 3; style++) operations_xm("default/xm", -1, 0, style);
    for (String line : Files.readAllLines(root.resolve("cases.tsv"))) {
      var values = line.split("\t");
      String family = values[0];
      int field = Integer.parseInt(values[1]);
      long value = Long.parseLong(values[2]);
      String base = "setter/" + family + "/" + field + "/" + value;
      for (int style = 0; style < 3; style++)
        switch (family) {
          case "mod" -> operations_mod(base, field, value, style);
          case "s3m" -> operations_s3m(base, field, value, style);
          case "it" -> operations_it(base, field, value, style);
          case "xm" -> operations_xm(base, field, value, style);
          default -> throw new IllegalArgumentException();
        }
    }
    try (var files = Files.list(root.resolve("inputs"))) {
      for (Path path : files.sorted().toList()) {
        byte[] data = Files.readAllBytes(path);
        String name = path.getFileName().toString();
        if (name.endsWith(".mod")) file_mod(path, data);
        else if (name.endsWith(".s3m")) file_s3m(path, data);
        else if (name.endsWith(".it")) file_it(path, data);
        else if (name.endsWith(".xm")) file_xm(path, data);
        else throw new IllegalArgumentException();
      }
    }
  }
}
