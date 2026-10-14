package cz.skyba.taglib.parity.mpeg;

import cz.skyba.taglib.parity.support.FileRefAdapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cz.skyba.taglib.*;
import cz.skyba.taglib.internal.Binary;
import cz.skyba.taglib.parity.Snapshots;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Independently encoded branches of mpegheader/xingheader and trueaudioproperties.cpp.
 */
@org.junit.jupiter.api.Tag("parity")
@org.junit.jupiter.api.Tag("mpeg")
final class MpegAndTrueAudioReadModesAndSaveTest {
  @TempDir
  Path directory;

  private record Fixture(String name, byte[] bytes) {
  }

  @TestFactory
  List<DynamicTest> fileReadModesAndRepeatedSavesMatchCpp() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("parity/native")))
      root = root.getParent();
    assertNotNull(root, "Repository containing required native control");
    Path probe = root.resolve("parity/target/native-build/bin/file-reference-reads-and-mutations");
    assertTrue(Files.isExecutable(probe), "Required native control: ./mvnw -Pparity verify");
    var tests = new ArrayList<DynamicTest>();
    for (Fixture fixture : fixtures()) {
      for (String mode : List.of("FAST", "AVERAGE", "ACCURATE", "TAGS_ONLY")) {
        tests.add(
          DynamicTest.dynamicTest(
            fixture.name() + "/" + mode + "/path", () -> read(probe, fixture, mode, -1)));
        for (int origin : List.of(0, 7))
          tests.add(
            DynamicTest.dynamicTest(
              fixture.name() + "/" + mode + "/stream-" + origin,
              () -> read(probe, fixture, mode, origin)));
      }
      for (String scenario : List.of("save-only", "common-title")) {
        tests.add(
          DynamicTest.dynamicTest(
            fixture.name() + "/" + scenario + "/path",
            () -> save(probe, fixture, scenario, -1, false)));
        for (int origin : List.of(0, 7)) {
          tests.add(
            DynamicTest.dynamicTest(
              fixture.name() + "/" + scenario + "/stream-" + origin,
              () -> save(probe, fixture, scenario, origin, false)));
          tests.add(
            DynamicTest.dynamicTest(
              fixture.name() + "/" + scenario + "/twice-" + origin,
              () -> save(probe, fixture, scenario, origin, true)));
        }
      }
      tests.add(
        DynamicTest.dynamicTest(
          fixture.name() + "/facade-default-save", () -> facadeSave(probe, fixture)));
    }
    return tests;
  }

  @TestFactory
  List<DynamicTest> partialId3HeadersAndEmptySavesMatchCpp() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("parity/native")))
      root = root.getParent();
    assertNotNull(root, "Repository containing required native control");
    Path probe = root.resolve("parity/target/native-build/bin/file-reference-reads-and-mutations");
    assertTrue(Files.isExecutable(probe), "Required native control: ./mvnw -Pparity verify");
    var tests = new ArrayList<DynamicTest>();
    for (String extension : List.of("mp3", "tta"))
      for (int size : List.of(3, 9)) {
        byte[] header = new byte[size];
        System.arraycopy(Binary.bytes("ID3"), 0, header, 0, 3);
        if (size > 3) header[3] = 4;
        var fixture = new Fixture("partial-id3-" + size + "." + extension, header);
        for (String mode : List.of("FAST", "AVERAGE", "ACCURATE", "TAGS_ONLY")) {
          tests.add(
            DynamicTest.dynamicTest(
              fixture.name() + "/" + mode + "/path", () -> read(probe, fixture, mode, -1)));
          for (int origin : List.of(0, 7))
            tests.add(
              DynamicTest.dynamicTest(
                fixture.name() + "/" + mode + "/stream-" + origin,
                () -> read(probe, fixture, mode, origin)));
        }
        tests.add(
          DynamicTest.dynamicTest(
            fixture.name() + "/empty-save/path",
            () -> save(probe, fixture, "save-only", -1, false)));
        for (int origin : List.of(0, 7))
          for (boolean twice : List.of(false, true))
            tests.add(
              DynamicTest.dynamicTest(
                fixture.name() + "/empty-save/stream-" + origin + "/twice-" + twice,
                () -> save(probe, fixture, "save-only", origin, twice)));
        tests.add(
          DynamicTest.dynamicTest(
            fixture.name() + "/facade-empty-save", () -> facadeSave(probe, fixture)));
      }
    return tests;
  }

  @TestFactory
  List<DynamicTest> readStyleTagSpansRemainStableAcrossRepeatedSaves() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("parity/native")))
      root = root.getParent();
    assertNotNull(root, "Repository containing required native control");
    Path probe = root.resolve("parity/target/native-build/bin/mpeg-trueaudio-read-modes-and-saving");
    assertTrue(
      Files.isExecutable(probe),
      "Required native control: parity/cases/formats/mpeg/mpeg_tta_session_probe.cpp");
    var tests = new ArrayList<DynamicTest>();
    for (Fixture fixture : retainedSpanFixtures())
      for (String mode : List.of("FAST", "AVERAGE", "ACCURATE", "TAGS_ONLY"))
        for (String scenario : List.of("save-only", "common-title"))
          for (int origin : List.of(-1, 0, 7))
            tests.add(
              DynamicTest.dynamicTest(
                fixture.name() + "/" + mode + "/" + scenario + "/" + origin,
                () -> styledSave(probe, fixture, mode, scenario, origin)));
    return tests;
  }

  private void styledSave(Path probe, Fixture fixture, String mode, String scenario, int origin)
    throws Exception {
    Path nativePath =
      Files.write(directory.resolve("style-native-" + fixture.name()), fixture.bytes());
    JsonNode expected =
      nativeRun(probe, nativePath.toString(), mode, scenario, Integer.toString(origin));
    Path javaPath = Files.write(directory.resolve("style-java-" + fixture.name()), fixture.bytes());
    var stream = origin < 0 ? null : named(fixture.bytes(), javaPath);
    if (stream != null) stream.seek(origin);
    boolean audio = !mode.equals("TAGS_ONLY");
    ReadStyle style = mode.equals("TAGS_ONLY") ? ReadStyle.AVERAGE : ReadStyle.valueOf(mode);
    try (var ref =
           stream == null ? new FileRefAdapter(javaPath, audio, style) : new FileRefAdapter(stream, audio, style)) {
      assertEquals(expected.path("opened").asBoolean(), !ref.isNull());
      assertEquals(List.of(), Snapshots.differences(expected.path("construction"), snapshot(ref)));
      if (scenario.equals("common-title")) ref.tag().setTitle("Common title");
      assertEquals(List.of(), Snapshots.differences(expected.path("beforeSave"), snapshot(ref)));
      assertEquals(expected.path("beforeSaveIsEmpty").asBoolean(), ref.tag().isEmpty());
      for (int i = 1; i <= 2; i++) {
        assertEquals(expected.path("saved" + i).asBoolean(), ref.save(), "save outcome " + i);
        assertEquals(
          List.of(),
          Snapshots.differences(expected.path("afterSave" + i), snapshot(ref)),
          "live state " + i);
        assertEquals(expected.path("isEmptyAfterSave" + i).asBoolean(), ref.tag().isEmpty());
        byte[] actual = stream == null ? Files.readAllBytes(javaPath) : stream.data().toByteArray();
        byte[] nativeBytes = Files.readAllBytes(Path.of(nativePath + ".save" + i));
        assertArrayEquals(nativeBytes, actual, "Complete serialized file " + i);
        if (stream != null && !Arrays.equals(fixture.bytes(), nativeBytes))
          assertEquals(expected.path("cursorAfterSave" + i).asLong(), stream.tell(), "save cursor " + i);
      }
    }
    if (stream != null) {
      assertTrue(stream.isOpen());
      Files.write(javaPath, stream.data().toByteArray());
    }
    try (var ref = new FileRefAdapter(javaPath)) {
      assertEquals(
        List.of(), Snapshots.differences(expected.path("post"), snapshot(ref)), "Reopen state");
    }
  }

  private void read(Path probe, Fixture fixture, String mode, int origin) throws Exception {
    Path path = Files.write(directory.resolve("read-" + fixture.name()), fixture.bytes());
    boolean audio = !mode.equals("TAGS_ONLY");
    ReadStyle style = mode.equals("TAGS_ONLY") ? ReadStyle.AVERAGE : ReadStyle.valueOf(mode);
    if (origin < 0) {
      JsonNode expected = nativeRun(probe, "snapshot", path.toString(), mode);
      try (var ref = new FileRefAdapter(path, audio, style)) {
        assertEquals(List.of(), Snapshots.differences(expected, snapshot(ref)));
      }
      var file =
        TagLib.read(path, new ReadOptions(audio, style, ReadOptions.DEFAULT.maxMetadataBytes()));
      ObjectNode actual = Snapshots.of(file);
      actual.set(
        "unsupportedData", Snapshots.JSON.valueToTree(file.properties().unsupportedData()));
      assertEquals(List.of(), Snapshots.differences(expected, actual), "facade read");
    } else {
      JsonNode expected =
        nativeRun(probe, "snapshot-stream", path.toString(), mode, Integer.toString(origin));
      var stream = named(fixture.bytes(), path);
      stream.seek(origin);
      try (var ref = new FileRefAdapter(stream, audio, style)) {
        assertEquals(List.of(), Snapshots.differences(expected.path("snapshot"), snapshot(ref)));
      }
      assertTrue(stream.isOpen(), "Borrowed stream lifetime");
    }
  }

  private void save(Path probe, Fixture fixture, String scenario, int origin, boolean twice)
    throws Exception {
    Path path = Files.write(directory.resolve("save-native-" + fixture.name()), fixture.bytes());
    JsonNode expected =
      origin < 0
        ? nativeRun(probe, "mutate", path.toString(), scenario)
        : nativeRun(
        probe,
        twice ? "mutate-stream-twice" : "mutate-stream",
        path.toString(),
        scenario,
        Integer.toString(origin));
    byte[] nativeBytes = Files.readAllBytes(path);
    Path javaPath = Files.write(directory.resolve("save-java-" + fixture.name()), fixture.bytes());
    var stream = origin < 0 ? null : named(fixture.bytes(), javaPath);
    if (stream != null) stream.seek(origin);
    try (var ref = stream == null ? new FileRefAdapter(javaPath, false) : new FileRefAdapter(stream, false)) {
      assertEquals(expected.path("opened").asBoolean(), !ref.isNull(), "constructor validity");
      assertFalse(ref.isNull(), "Named MPEG/TrueAudio source accepts an open file");
      if (scenario.equals("common-title")) ref.tag().setTitle("Common title");
      assertEquals(List.of(), Snapshots.differences(expected.path("beforeSave"), snapshot(ref)));
      assertEquals(expected.path("beforeSaveIsEmpty").asBoolean(), ref.tag().isEmpty());
      boolean saved = ref.save();
      if (twice && saved) saved = ref.save();
      assertEquals(expected.path("saved").asBoolean(), saved, "save outcome");
      if (stream != null && !Arrays.equals(fixture.bytes(), nativeBytes))
        assertEquals(expected.path("borrowedStreamCursorAfterSave").asLong(), stream.tell(), "save cursor");
    }
    byte[] actual = stream == null ? Files.readAllBytes(javaPath) : stream.data().toByteArray();
    assertArrayEquals(nativeBytes, actual, "Complete serialized file");
    Files.write(javaPath, actual);
    try (var ref = new FileRefAdapter(javaPath)) {
      assertEquals(
        List.of(),
        Snapshots.differences(expected.path("post"), snapshot(ref)),
        "Reopened common/audio state");
    }
    if (stream != null) assertTrue(stream.isOpen(), "Borrowed stream lifetime after save");
  }

  private void facadeSave(Path probe, Fixture fixture) throws Exception {
    Path nativePath =
      Files.write(directory.resolve("facade-native-" + fixture.name()), fixture.bytes());
    JsonNode expected = nativeRun(probe, "mutate", nativePath.toString(), "unchanged");
    Path path = Files.write(directory.resolve("facade-java-" + fixture.name()), fixture.bytes());
    PropertyMap properties = TagLib.read(path, ReadOptions.TAGS_ONLY).properties();
    TagLib.write(path, properties);
    assertTrue(expected.path("saved").asBoolean(), "Required native default writer control");
    assertArrayEquals(
      Files.readAllBytes(nativePath),
      Files.readAllBytes(path),
      "Facade complete default serialization");
  }

  private static JsonNode nativeRun(Path probe, String... arguments) throws Exception {
    var command = new ArrayList<String>();
    command.add(probe.toString());
    command.addAll(Arrays.asList(arguments));
    var process =
      new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    byte[] output = process.getInputStream().readAllBytes();
    assertEquals(0, process.waitFor(), "Required native control invocation");
    return Snapshots.JSON.readTree(output);
  }

  private static ByteVectorStream named(byte[] bytes, Path path) {
    return new ByteVectorStream(bytes) {
      @Override
      public String name() {
        return path.toString();
      }
    };
  }

  private static ObjectNode snapshot(FileRefAdapter ref) {
    if (ref.isNull()) return Snapshots.invalid();
    var result = Snapshots.JSON.createObjectNode().put("valid", true);
    result.set("properties", Snapshots.JSON.valueToTree(ref.properties().asMap()));
    result.set("unsupportedData", Snapshots.JSON.valueToTree(ref.properties().unsupportedData()));
    var t = ref.tag();
    result
      .putObject("common")
      .put("title", t.title())
      .put("artist", t.artist())
      .put("album", t.album())
      .put("comment", t.comment())
      .put("genre", t.genre())
      .put("year", t.year())
      .put("track", t.track());
    var a = ref.audioProperties();
    if (a == null) a = AudioProperties.EMPTY;
    result
      .putObject("audio")
      .put("lengthInMilliseconds", a.lengthInMilliseconds())
      .put("bitrate", a.bitrate())
      .put("sampleRate", a.sampleRate())
      .put("channels", a.channels());
    result.putArray("pictures");
    assertEquals(List.of(), ref.complexProperties("PICTURE"), "Fixtures contain no artwork");
    return result;
  }

  private static List<Fixture> fixtures() {
    var cases = new ArrayList<Fixture>();
    cases.add(new Fixture("single-frame.mp3", mpeg(1)));
    cases.add(new Fixture("short-header.mp3", new byte[]{(byte) 255, (byte) 251, (byte) 144}));
    cases.add(new Fixture("coherent-frames.mp3", mpeg(3)));
    byte[] excludedSync = mpeg(3);
    for (int at : List.of(1, 418, 835)) excludedSync[at] = (byte) 255;
    cases.add(new Fixture("excluded-ff-second-sync.mp3", excludedSync));
    byte[] excludedPrefix = new byte[2048 + 1251];
    excludedPrefix[1023] = (byte) 255;
    excludedPrefix[1024] = (byte) 255;
    System.arraycopy(mpeg(3), 0, excludedPrefix, 2048, 1251);
    cases.add(new Fixture("excluded-sync-before-valid-block.mp3", excludedPrefix));
    cases.add(
      new Fixture(
        "adts-short-length-four.mp3", new byte[]{(byte) 255, (byte) 241, 80, (byte) 128}));
    cases.add(
      new Fixture(
        "adts-short-length-five.mp3", new byte[]{(byte) 255, (byte) 241, 80, (byte) 128, 0}));
    byte[] rawNext = Arrays.copyOf(mpeg(1), 421);
    rawNext[417] = (byte) 255;
    rawNext[418] = (byte) 251;
    cases.add(new Fixture("next-masked-invalid-bitrate.mp3", rawNext));
    byte[] inconsistent = mpeg(2);
    inconsistent[418] = (byte) 243;
    cases.add(new Fixture("next-version-mismatch.mp3", inconsistent));
    byte[] prefixed = new byte[1023 + 1251];
    System.arraycopy(mpeg(3), 0, prefixed, 1023, 1251);
    cases.add(new Fixture("sync-block-boundary.mp3", prefixed));
    byte[] priority = mpeg(3);
    vbri(priority, 32, 100, 10000);
    xing(priority, 100, "Info", 200, 20000);
    xing(priority, 180, "Xing", 300, 30000);
    cases.add(new Fixture("xing-before-info-priority.mp3", priority));
    byte[] info = mpeg(3);
    vbri(info, 32, 100, 10000);
    xing(info, 180, "Info", 200, 20000);
    cases.add(new Fixture("info-before-vbri-priority.mp3", info));
    byte[] invalid = priority.clone();
    invalid[187] = 0;
    cases.add(new Fixture("invalid-xing-blocks-fallback.mp3", invalid));
    byte[] zero = priority.clone();
    Binary.put32be(zero, 188, 0);
    cases.add(new Fixture("zero-xing-blocks-fallback.mp3", zero));
    byte[] truncated = info.clone();
    System.arraycopy(Binary.bytes("Xing"), 0, truncated, 410, 4);
    cases.add(new Fixture("truncated-xing-blocks-fallback.mp3", truncated));
    byte[] overflow = mpeg(3);
    xing(overflow, 32, "Xing", 4294967295L, 4294967295L);
    cases.add(new Fixture("xing-length-overflow.mp3", overflow));
    cases.add(new Fixture("first-frame-in-ape-after-last-frame.mp3", tagFrames()));
    for (int count : List.of(1, 2, 15))
      cases.add(new Fixture("adts-" + count + ".aac", adts(count, 4, 2)));
    cases.add(new Fixture("adts-zero-sample-rate.aac", adts(3, 15, 2)));
    cases.add(new Fixture("adts-eight-channels.aac", adts(3, 4, 7)));
    cases.add(new Fixture("tta1.tta", tta(2, 16, 44100, 44100)));
    byte[] tta2 = tta(2, 16, 44100, 44100);
    tta2[3] = '2';
    cases.add(new Fixture("tta2.tta", tta2));
    cases.add(new Fixture("foreign.tta", Binary.bytes("Not a TrueAudio stream")));
    cases.add(new Fixture("empty.tta", new byte[0]));
    for (int size : List.of(3, 4, 17))
      cases.add(
        new Fixture("short-" + size + ".tta", Arrays.copyOf(tta(2, 16, 44100, 44100), size)));
    for (int channels : List.of(32767, 32768, 65535))
      cases.add(
        new Fixture("signed-channels-" + channels + ".tta", tta(channels, 65535, 44100, 44100)));
    for (long rate : List.of(0L, 2147483647L, 2147483648L, 4294967295L))
      cases.add(new Fixture("signed-rate-" + rate + ".tta", tta(2, 16, rate, 44100)));
    cases.add(new Fixture("zero-samples.tta", tta(2, 16, 44100, 0)));
    cases.add(new Fixture("length-overflow.tta", tta(2, 16, 1, 4294967295L)));
    byte[] truncatedTag = new byte[10];
    System.arraycopy(Binary.bytes("ID3"), 0, truncatedTag, 0, 3);
    truncatedTag[3] = 4;
    truncatedTag[9] = 100;
    cases.add(new Fixture("declared-id3-beyond-eof.tta", truncatedTag));
    for (String extension : List.of("mp3", "tta")) {
      for (int version : List.of(0, 1, 5, 254, 255)) {
        byte[] emptyHeader = new byte[10];
        System.arraycopy(Binary.bytes("ID3"), 0, emptyHeader, 0, 3);
        emptyHeader[3] = (byte) version;
        cases.add(new Fixture("empty-id3-version-" + version + "." + extension, emptyHeader));
      }
      byte[] invalidSize = new byte[10];
      System.arraycopy(Binary.bytes("ID3"), 0, invalidSize, 0, 3);
      invalidSize[3] = 4;
      invalidSize[9] = (byte) 128;
      cases.add(new Fixture("invalid-id3-highbit-size." + extension, invalidSize));
      byte[] emptyHeader = new byte[10];
      System.arraycopy(Binary.bytes("ID3"), 0, emptyHeader, 0, 3);
      emptyHeader[3] = 4;
      byte[] media = extension.equals("mp3") ? mpeg(3) : tta(2, 16, 44100, 44100);
      byte[] duplicate = new byte[20 + media.length];
      System.arraycopy(emptyHeader, 0, duplicate, 0, 10);
      System.arraycopy(emptyHeader, 0, duplicate, 10, 10);
      System.arraycopy(media, 0, duplicate, 20, media.length);
      cases.add(new Fixture("duplicate-empty-id3." + extension, duplicate));
    }
    return cases;
  }

  private static byte[] mpeg(int count) {
    byte[] bytes = new byte[count * 417];
    for (int i = 0; i < count; i++) {
      bytes[i * 417] = (byte) 255;
      bytes[i * 417 + 1] = (byte) 251;
      bytes[i * 417 + 2] = (byte) 144;
    }
    return bytes;
  }

  private static List<Fixture> retainedSpanFixtures() {
    byte[] data = Binary.bytes("\0Native title"), title = new byte[20 + data.length];
    System.arraycopy(Binary.bytes("ID3"), 0, title, 0, 3);
    title[3] = 4;
    putSynchsafe(title, 6, 10 + data.length);
    System.arraycopy(Binary.bytes("TIT2"), 0, title, 10, 4);
    putSynchsafe(title, 14, data.length);
    System.arraycopy(data, 0, title, 20, data.length);
    byte[] nonleading = new byte[7 + title.length + 1251];
    Arrays.fill(nonleading, 0, 7, (byte) 'x');
    System.arraycopy(title, 0, nonleading, 7, title.length);
    System.arraycopy(mpeg(3), 0, nonleading, 7 + title.length, 1251);
    byte[] duplicateBody = mpeg(6), tag = new byte[10 + duplicateBody.length];
    System.arraycopy(Binary.bytes("ID3"), 0, tag, 0, 3);
    tag[3] = 4;
    putSynchsafe(tag, 6, duplicateBody.length);
    System.arraycopy(duplicateBody, 0, tag, 10, duplicateBody.length);
    byte[] folded = new byte[10 + tag.length + 1251];
    System.arraycopy(Binary.bytes("ID3"), 0, folded, 0, 3);
    folded[3] = 4;
    System.arraycopy(tag, 0, folded, 10, tag.length);
    System.arraycopy(mpeg(3), 0, folded, 10 + tag.length, 1251);
    return List.of(
      new Fixture("nonleading-id3.mp3", nonleading),
      new Fixture("folded-id3-mpeg-payload.mp3", folded));
  }

  private static void putSynchsafe(byte[] bytes, int offset, int value) {
    for (int i = 3; i >= 0; i--) {
      bytes[offset + i] = (byte) (value & 127);
      value >>>= 7;
    }
  }

  private static byte[] tagFrames() {
    byte[] frames = mpeg(3), tag = new byte[20 + frames.length];
    System.arraycopy(Binary.bytes("ID3"), 0, tag, 0, 3);
    tag[3] = 4;
    Binary.putSynchsafe(tag, 6, 10 + frames.length);
    System.arraycopy(Binary.bytes("ZZZZ"), 0, tag, 10, 4);
    Binary.putSynchsafe(tag, 14, frames.length);
    System.arraycopy(frames, 0, tag, 20, frames.length);
    byte[] item = new byte[13 + frames.length];
    Binary.put32le(item, 0, frames.length);
    Binary.put32le(item, 4, 2);
    System.arraycopy(Binary.bytes("Blob"), 0, item, 8, 4);
    System.arraycopy(frames, 0, item, 13, frames.length);
    byte[] footer = new byte[32];
    System.arraycopy(Binary.bytes("APETAGEX"), 0, footer, 0, 8);
    Binary.put32le(footer, 8, 2000);
    Binary.put32le(footer, 12, item.length + 32);
    Binary.put32le(footer, 16, 1);
    byte[] bytes = Arrays.copyOf(tag, tag.length + item.length + footer.length);
    System.arraycopy(item, 0, bytes, tag.length, item.length);
    System.arraycopy(footer, 0, bytes, tag.length + item.length, 32);
    return bytes;
  }

  private static void xing(byte[] bytes, int at, String name, long frames, long length) {
    System.arraycopy(Binary.bytes(name), 0, bytes, at, 4);
    bytes[at + 7] = 3;
    Binary.put32be(bytes, at + 8, frames);
    Binary.put32be(bytes, at + 12, length);
  }

  private static void vbri(byte[] bytes, int at, long frames, long length) {
    System.arraycopy(Binary.bytes("VBRI"), 0, bytes, at, 4);
    Binary.put32be(bytes, at + 10, length);
    Binary.put32be(bytes, at + 14, frames);
  }

  private static byte[] adts(int count, int rateIndex, int channels) {
    byte[] bytes = new byte[count * 40];
    for (int p = 0; p < bytes.length; p += 40) {
      bytes[p] = (byte) 255;
      bytes[p + 1] = (byte) 241;
      bytes[p + 2] = (byte) (0x40 | rateIndex << 2 | channels >> 2);
      bytes[p + 3] = (byte) ((channels & 3) << 6);
      bytes[p + 4] = 5;
      bytes[p + 5] = 31;
      bytes[p + 6] = (byte) 252;
    }
    return bytes;
  }

  private static byte[] tta(int channels, int bits, long rate, long frames) {
    byte[] bytes = new byte[32];
    System.arraycopy(Binary.bytes("TTA1"), 0, bytes, 0, 4);
    bytes[4] = 1;
    Binary.put16le(bytes, 6, channels);
    Binary.put16le(bytes, 8, bits);
    Binary.put32le(bytes, 10, rate);
    Binary.put32le(bytes, 14, frames);
    return bytes;
  }
}
