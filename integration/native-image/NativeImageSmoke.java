package cz.skyba.taglib.parity;

import cz.skyba.taglib.*;
import cz.skyba.taglib.ape.ApeFile;
import cz.skyba.taglib.asf.AsfFile;
import cz.skyba.taglib.dsdiff.DsdiffFile;
import cz.skyba.taglib.dsdiff.DsdiffSaveOptions;
import cz.skyba.taglib.dsf.DsfFile;
import cz.skyba.taglib.id3.v2.Tag.Version;
import cz.skyba.taglib.it.ItFile;
import cz.skyba.taglib.mod.ModFile;
import cz.skyba.taglib.mpc.MpcFile;
import cz.skyba.taglib.mpeg.MpegFile;
import cz.skyba.taglib.mpeg.MpegSaveOptions;
import cz.skyba.taglib.ogg.OggFile;
import cz.skyba.taglib.s3m.S3mFile;
import cz.skyba.taglib.shorten.ShortenFile;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.Variant;
import cz.skyba.taglib.trueaudio.TrueAudioFile;
import cz.skyba.taglib.wavpack.WavPackFile;
import cz.skyba.taglib.xm.XmFile;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_16;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Dependency-free consumer used to compare JVM execution with a real Native Image executable.
 */
public final class NativeImageSmoke {
  private static final byte[] PNG =
    Base64.getDecoder()
      .decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXioAAAAASUVORK5CYII=");
  private static final List<Picture> PICTURES =
    List.of(
      new Picture(3, "image/png", "Přední cover", PNG),
      new Picture(4, "image/png", "Back cover", PNG));

  private NativeImageSmoke() {
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 3) {
      throw new IllegalArgumentException("NativeImageSmoke CORPUS SCRATCH [--require-all-formats]");
    }
    Path corpus = Path.of(args[0]).toRealPath();
    Path scratch = Path.of(args[1]).toAbsolutePath().normalize();
    Files.createDirectories(scratch);
    scratch = scratch.toRealPath();
    if (scratch.startsWith(corpus))
      throw new IllegalArgumentException("Scratch must be outside corpus");
    List<Path> files;
    try (var walk = Files.walk(corpus)) {
      files = walk.filter(Files::isRegularFile).sorted().toList();
    }
    if (files.isEmpty()) throw new IllegalArgumentException("Empty corpus");
    var formats = EnumSet.noneOf(Format.class);
    int accepted = 0,
      readRejected = 0,
      writes = 0,
      writeRejected = 0,
      verified = 0,
      unverified = 0,
      failures = 0;
    toolkitCheck(scratch.resolve("toolkit-stream.bin"));
    riffInfoCheck();
    id3v1Check();
    id3HeaderHelpersCheck();
    id3MutableFramesCheck();
    id3MutableTextFramesCheck();
    id3MutablePictureObjectFramesCheck();
    id3MutableNumericFramesCheck();
    id3MutableTimedFramesCheck();
    id3MutableOwnerCheck();
    liveDsfFileCheck(corpus);
    liveTrueAudioFileCheck(corpus);
    liveDiinTagCheck();
    dsdiffReadBoundaryCheck();
    liveDsdiffFileCheck(corpus);
    liveMpegFileCheck(corpus);
    liveAsfFileCheck(corpus);
    mpegHeaderCheck();
    partialOpusCheck();
    dsdTtaDataPropertiesCheck();
    asfMutablePropertiesCheck();
    shortenValuesPropertiesCheck();
    matroskaEmptyPropertiesCheck();
    trackerMutablePropertiesCheck();
    partialXmPropertiesCheck(scratch.resolve("partial-properties.xm"));
    liveTrackerTagsAndHelpersCheck(scratch);
    System.out.println("# TagLib Java " + TagLib.UPSTREAM_VERSION + " JVM/Native Image parity v3");
    System.out.println("# write-policy upstream-compatible in-place");
    for (Path file : files) {
      String name = corpus.relativize(file).toString().replace('\\', '/');
      if (name.indexOf('\n') >= 0 || name.indexOf('\t') >= 0) {
        throw new IllegalArgumentException("Corpus paths must not contain tabs/newlines");
      }
      var sourceHash = PayloadHash.wholeFile(file);
      System.out.println(name + "\tinput\t" + sourceHash.bytes() + "\t" + sourceHash.sha256());
      AudioMetadata original;
      try {
        original = TagLib.read(file);
      } catch (UncheckedIOException | TagLibException rejection) {
        System.out.println(name + "\tread\tREJECT\t" + rejection.getClass().getSimpleName());
        readRejected++;
        continue;
      }
      accepted++;
      formats.add(original.format());
      detailedApePropertiesCheck(file, original);
      liveApeFilesCheck(file, original, scratch);
      liveWavpackFilesCheck(file, original, scratch);
      liveMpcFilesCheck(file, original, scratch);
      detailedMpegPropertiesCheck(file, original);
      detailedOggPropertiesCheck(file, original);
      detailedDsdTtaPropertiesCheck(file, original);
      detailedAiffPropertiesCheck(file, original);
      detailedAsfPropertiesCheck(file, original);
      detailedShortenPropertiesCheck(file, original);
      detailedMp4PropertiesCheck(file, original);
      detailedMatroskaPropertiesCheck(file, original);
      detailedTrackerPropertiesCheck(file, original);
      liveTrackerFilesCheck(file, original, scratch);
      liveShortenFilesCheck(file, original);
      System.out.println(name + "\tread\t" + original.format() + "\t" + snapshot(original));
      var memory =
        new cz.skyba.taglib.toolkit.ByteVectorStream(Files.readAllBytes(file)) {
          @Override
          public String name() {
            return file.getFileName().toString();
          }
        };
      if (!original.equals(TagLib.read(memory)))
        throw new AssertionError("Memory stream differs: " + name);
      try (var stream = new cz.skyba.taglib.toolkit.FileStream(file, true)) {
        if (!original.equals(TagLib.read(stream)))
          throw new AssertionError("File stream differs: " + name);
        if (!stream.isOpen()) throw new AssertionError("Borrowed stream was closed");
      }
      System.out.println(name + "\tstream-read\tPASS");
      System.out.println(
        name + "\ttags-only\t" + snapshot(TagLib.read(file, ReadOptions.TAGS_ONLY)));
      for (ReadStyle style : ReadStyle.values()) {
        System.out.println(
          name
            + "\tstyle-"
            + style
            + "\t"
            + snapshot(
            TagLib.read(
              file,
              new ReadOptions(true, style, ReadOptions.DEFAULT.maxMetadataBytes()))));
      }
      try {
        System.out.println(
          name + "\teditor-read\t" + documentSnapshot(MetadataDocument.open(file)));
      } catch (UncheckedIOException | TagLibException | UnsupportedOperationException rejection) {
        System.out.println(name + "\teditor-read\tREJECT\t" + rejection.getClass().getSimpleName());
      }
      var properties =
        original.properties().toBuilder()
          .put("TITLE", "Native image — Žluťoučký")
          .put("ARTIST", List.of("First artist", "Druhý artist"))
          .put("ALBUM", "Native consumer")
          .put("DATE", "2026")
          .put("TRACKNUMBER", "7")
          .build();
      for (String operation :
        List.of(
          "text",
          "artwork",
          "clear-artwork",
          "clear-text",
          "editor-add",
          "editor-update",
          "editor-remove",
          "editor-artwork")) {
        String operationId = operation + "-default";
        Path copy = scratch.resolve(operationId).resolve(corpus.relativize(file));
        Files.createDirectories(copy.getParent());
        Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
        var before = PayloadHash.wholeFile(copy);
        var payloadBefore = PayloadHash.of(copy);
        PropertyMap rejected = PropertyMap.EMPTY;
        try {
          switch (operation) {
            case "text" -> rejected = TagLib.write(copy, properties).rejected();
            case "artwork" -> writePictures(copy, original.properties(), PICTURES);
            case "clear-artwork" -> writePictures(copy, original.properties(), List.of());
            case "clear-text" -> TagLib.write(copy, PropertyMap.EMPTY);
            case "editor-add", "editor-update", "editor-remove", "editor-artwork" -> {
              if (!editPhysical(copy, operation)) {
                System.out.println(name + "\t" + operationId + "\tNO_ELIGIBLE_ENTRY");
                continue;
              }
            }
            default -> throw new AssertionError(operation);
          }
        } catch (UncheckedIOException | TagLibException | UnsupportedOperationException rejection) {
          if (!before.equals(PayloadHash.wholeFile(copy))) {
            System.out.println(
              name + "\t" + operationId + "\tFAIL:REJECTED_WRITE_CHANGED_SOURCE");
            failures++;
            continue;
          }
          System.out.println(
            name + "\t" + operationId + "\tREJECT\t" + rejection.getClass().getSimpleName());
          writeRejected++;
          continue;
        }
        AudioMetadata after;
        String physical;
        try {
          after = TagLib.read(copy);
          physical =
            operation.startsWith("editor-")
              ? "\t" + documentSnapshot(MetadataDocument.open(copy))
              : "";
        } catch (UncheckedIOException | TagLibException | UnsupportedOperationException rejection) {
          System.out.println(
            name
              + "\t"
              + operationId
              + "\tFAIL:POST_WRITE_READ:"
              + rejection.getClass().getSimpleName());
          failures++;
          continue;
        }
        var payloadAfter = PayloadHash.of(copy);
        String preservation;
        if (payloadBefore == null || payloadAfter == null) {
          preservation = "UNVERIFIED";
          unverified++;
        } else if (payloadBefore.bytes() == 0 && payloadAfter.bytes() == 0) {
          preservation = "ABSENT";
          unverified++;
        } else if (!payloadBefore.equals(payloadAfter)) {
          System.out.println(
            name
              + "\t"
              + operationId
              + "\tFAIL:MEDIA_CHANGED:"
              + payloadBefore
              + ":"
              + payloadAfter);
          failures++;
          continue;
        } else {
          preservation = "VERIFIED:" + payloadAfter.bytes() + ":" + payloadAfter.sha256();
          verified++;
        }
        System.out.println(
          name
            + "\t"
            + operationId
            + "\tPASS\t"
            + snapshot(after)
            + "\t"
            + propertyHash(rejected)
            + "\t"
            + preservation
            + physical);
        writes++;
      }
      if (!sourceHash.equals(PayloadHash.wholeFile(file))) {
        throw new AssertionError("Input fixture was modified: " + name);
      }
    }
    keyedEditorCheck(scratch.resolve("keyed-editor-default.m4a"));
    containerNewApisCheck(scratch);
    if (accepted == 0 || writes == 0 || verified == 0) {
      throw new AssertionError(
        "Corpus did not exercise valid reads, writes, and payload preservation");
    }
    if (args.length == 3
      && (!args[2].equals("--require-all-formats") || formats.size() != Format.values().length)) {
      throw new AssertionError("All-format coverage required; observed " + formats);
    }
    System.out.println(
      "# summary fixtures="
        + files.size()
        + " accepted="
        + accepted
        + " rejected="
        + readRejected
        + " writes="
        + writes
        + " writeRejected="
        + writeRejected
        + " payloadVerified="
        + verified
        + " payloadUnverifiedOrAbsent="
        + unverified
        + " failures="
        + failures
        + " formats="
        + formats);
    if (failures != 0)
      throw new AssertionError(failures + " preservation/read failures; see transcript");
  }

  private static void expectThrows(Class<? extends RuntimeException> type, Runnable action) {
    try {
      action.run();
    } catch (RuntimeException failure) {
      if (type.isInstance(failure)) return;
      throw new AssertionError("Expected " + type.getSimpleName() + ", got " + failure, failure);
    }
    throw new AssertionError("Expected " + type.getSimpleName());
  }

  private static void containerNewApisCheck(Path scratch) throws Exception {
    Path asf = scratch.resolve("typed-container-default.wma");
    Path mp4 = scratch.resolve("typed-container-default.m4a");
    Path mkv = scratch.resolve("typed-container-default.mka");
    // Bounded synthetic containers, each with the independently known bytes 0b16212c37424d.
    // MP4 includes a movie duration and an audio chunk table; Matroska retains that byte sequence
    // as an attachment with an explicit UID of 42. This exercises API reachability and persistence, not decoded audio quality.
    Files.write(
      asf,
      Base64.getDecoder()
        .decode(
          "MCaydY5mzxGm2QCqAGLObOwAAAAAAAAAAgAAAAECodyrjEepzxGO5ADADCBTZWgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACRB9y3t6nPEY7mAMAMIFNlZgAAAAAAAABAnmn4TVvPEaj9AIBfXEQrAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABhAQIARKwAAAAAAAAAAAAAAAAAAAAAAAALFiEsN0JN"));
    Files.write(
      mp4,
      Base64.getDecoder()
        .decode(
          "AAAAEGZ0eXBNNEEgAAAAAAAAASRtb292AAAAbG12aGQAAAAAAAAAAAAAAAAAAAPoAAB1MAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAHAAAAsHRyYWsAAABcdGtoZAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAExtZGlhAAAAIGhkbHIAAAAAAAAAAHNvdW4AAAAAAAAAAAAAAAAAAAAkbWluZgAAABxzdGJsAAAAFHN0Y28AAAAAAAAAAQAAATwAAAAPbWRhdAsWISw3Qk0="));
    Files.write(
      mkv,
      Base64.getDecoder()
        .decode(
          "GkXfo4AYU4BnAQAAAAAAACoZQaRppWGnokZuhWNvdmVyRmCJaW1hZ2UvcG5nRlyHCxYhLDdCTUaugSo="));
    var attributes =
      List.of(
        cz.skyba.taglib.asf.Attribute.unsigned(
            "Counter", 4, new java.math.BigInteger("18446744073709551615"))
          .withScope(4, 9),
        cz.skyba.taglib.asf.Attribute.binary("Opaque", new byte[]{0, -1, 7}),
        cz.skyba.taglib.asf.Attribute.text("Scoped", "日本語").withScope(4, 0));
    new cz.skyba.taglib.asf.Tag(attributes).save(asf);
    if (!cz.skyba.taglib.asf.Tag.read(asf).attributes().equals(attributes))
      throw new AssertionError("Typed ASF attributes changed");
    var nero =
      new cz.skyba.taglib.mp4.Tag(
        List.of(new cz.skyba.taglib.mp4.Chapter("Nero", 42)),
        new cz.skyba.taglib.mp4.Stem("{\"stems\":[]}".getBytes(StandardCharsets.UTF_8)));
    nero.save(mp4);
    var quickTime =
      new cz.skyba.taglib.mp4.QuickTimeChapters(
        List.of(
          new cz.skyba.taglib.mp4.Chapter("日本語", 1001),
          new cz.skyba.taglib.mp4.Chapter("Second", 12001)));
    quickTime.save(mp4);
    if (!quickTime.equals(cz.skyba.taglib.mp4.QuickTimeChapters.read(mp4))
      || !nero.equals(cz.skyba.taglib.mp4.Tag.read(mp4)))
      throw new AssertionError("Typed MP4 chapters/stem changed");
    new cz.skyba.taglib.mp4.QuickTimeChapters(List.of()).save(mp4);
    if (!cz.skyba.taglib.mp4.QuickTimeChapters.read(mp4).chapters().isEmpty()
      || !nero.equals(cz.skyba.taglib.mp4.Tag.read(mp4)))
      throw new AssertionError("QuickTime removal changed Nero/stem");
    var tags =
      new cz.skyba.taglib.matroska.Tag(
        List.of(
          new cz.skyba.taglib.matroska.SimpleTag(
            "TITLE",
            new cz.skyba.taglib.matroska.SimpleTag.Text("日本語"),
            30,
            "eng",
            true,
            0,
            0,
            0,
            0),
          new cz.skyba.taglib.matroska.SimpleTag(
            "DATA",
            new cz.skyba.taglib.matroska.SimpleTag.Binary(new byte[]{0, -1, 7}),
            50,
            "deu",
            false,
            -1,
            2,
            3,
            4)));
    tags.save(mkv);
    var editions =
      new cz.skyba.taglib.matroska.Chapters(
        List.of(
          new cz.skyba.taglib.matroska.ChapterEdition(
            List.of(
              new cz.skyba.taglib.matroska.Chapter(
                1000,
                4000,
                List.of(
                  new cz.skyba.taglib.matroska.Chapter.Display("Začátek", "ces"),
                  new cz.skyba.taglib.matroska.Chapter.Display("Opening", "eng")),
                101,
                true)),
            true,
            true,
            9)));
    editions.save(mkv);
    if (!tags.equals(cz.skyba.taglib.matroska.Tag.read(mkv))
      || !editions.equals(cz.skyba.taglib.matroska.Chapters.read(mkv)))
      throw new AssertionError("Typed Matroska tags/editions changed");
    byte[] media = {11, 22, 33, 44, 55, 66, 77};
    if (cz.skyba.taglib.matroska.Attachments.read(mkv).attachedFileList().getFirst().uid() != 42
      || !java.util.Arrays.equals(media, TagLib.read(mkv).pictures().getFirst().data()))
      throw new AssertionError("Typed Matroska save changed attachment data");
    for (Path file : List.of(asf, mp4)) {
      byte[] saved = Files.readAllBytes(file);
      if (!java.util.Arrays.equals(
        media, java.util.Arrays.copyOfRange(saved, saved.length - media.length, saved.length)))
        throw new AssertionError("Typed container save changed media bytes");
    }
    System.out.println(
      "# typed-containers-"
        + "default"
        + " PASS "
        + PayloadHash.wholeFile(asf)
        + ":"
        + PayloadHash.wholeFile(mp4)
        + ":"
        + PayloadHash.wholeFile(mkv));
  }

  private static void writePictures(
    Path path, PropertyMap properties, List<Picture> pictures)
    throws IOException {
    TagLib.write(path, properties, pictures);
  }

  private static void liveApeFilesCheck(Path path, AudioMetadata original, Path scratch) throws Exception {
    if (original.format() != Format.APE) return;
    byte[] bytes = Files.readAllBytes(path);
    for (var style : ReadStyle.values())
      for (boolean enabled : new boolean[]{false, true}) {
        var memory = new ByteVectorStream(bytes);
        try (var file = new ApeFile(path, new ReadOptions(enabled, style, ReadOptions.DEFAULT.maxMetadataBytes())); var borrowed = new ApeFile(memory, new ReadOptions(enabled, style, ReadOptions.DEFAULT.maxMetadataBytes()))) {
          if (!file.isValid() || !borrowed.isValid() || (file.detailedProperties() != null) != enabled
            || (borrowed.detailedProperties() != null) != enabled || !file.properties().equals(borrowed.properties())
            || !file.tag().title().equals(borrowed.tag().title()))
            throw new AssertionError("APE live constructor state");
          if (enabled && (!file.audioProperties().equals(borrowed.audioProperties())
            || file.detailedProperties().version() != borrowed.detailedProperties().version()
            || file.detailedProperties().sampleFrames() != borrowed.detailedProperties().sampleFrames()))
            throw new AssertionError("APE live properties");
          System.out.println(path.getFileName() + "\tlive-ape-controls\t" + style + ":" + enabled + ":" + file.tell() + ":" + memory.tell() + ":" + file.hasApeTag() + ":" + file.hasId3v1Tag());
        }
        try (var ref = new cz.skyba.taglib.AudioFile(new ByteVectorStream(bytes) {
          @Override
          public String name() {
            return "consumer.ape";
          }
        }, new ReadOptions(enabled, style, ReadOptions.DEFAULT.maxMetadataBytes()))) {
          if (!ref.isOpen() || !(ref.file() instanceof ApeFile) || (ref.audioProperties() != null) != enabled)
            throw new AssertionError("APE actual FileRef handle");
        }
      }
    try (var file = new ApeFile(new ByteVectorStream(bytes), ReadOptions.TAGS_ONLY)) {
      file.seek(0);
      var properties = new cz.skyba.taglib.ape.Properties(file, file.length(), ReadStyle.FAST);
      System.out.println(path.getFileName() + "\tlive-ape-direct-properties\t" + properties.version() + ":" + properties.sampleFrames() + ":" + properties.bitrate() + ":" + file.tell());
    }
    Path copy = scratch.resolve("live-ape-" + path.getFileName());
    Files.write(copy, bytes);
    try (var file = new ApeFile(copy)) {
      file.getOrCreateId3v1Tag().setTitle("legacy consumer");
      file.getOrCreateApeTag().setTitle("a longer consumer title");
      file.apeTag().setData("OPAQUE", new ByteVector(new byte[]{0, 1, 2, 3}));
      file.tag().setArtist("broadcast");
      for (int repeat = 0; repeat < 2; repeat++) {
        if (repeat == 1) file.apeTag().setTitle("x");
        file.save();
        System.out.println(path.getFileName() + "\tlive-ape-save\t" + repeat + ":" + file.tell() + ":" + file.hasApeTag() + ":" + file.hasId3v1Tag() + ":" + sha256(Files.readAllBytes(copy)));
      }
      file.removeUnsupportedProperties(List.of("OPAQUE"));
      var rejected = file.replaceProperties(PropertyMap.builder().put("TITLE", List.of("first", "second")).put("ID3", "invalid").build()).rejected();
      if (!rejected.get("ID3").equals(List.of("invalid"))) throw new AssertionError("APE property rejection");
      file.removeTags(Set.of(ApeFile.TagType.APE));
      if (file.apeTag() != null || file.id3v1Tag() == null) throw new AssertionError("APE strip child");
      file.save();
      file.removeTags();
      file.save();
      if (file.hasApeTag() || file.hasId3v1Tag() || file.apeTag() == null || file.id3v1Tag() != null)
        throw new AssertionError("APE physical presence");
    }
    var support = new ByteVectorStream(bytes);
    support.seek(7);
    ApeFile.isSupported(support);
    if (support.tell() != 7 || ApeFile.isSupported(null)) throw new AssertionError("APE support cursor");
    expectThrows(UncheckedIOException.class, () -> new ApeFile(scratch.resolve("missing-ape-file")));
    Files.delete(copy);
  }

  private static void liveWavpackFilesCheck(Path path, AudioMetadata original, Path scratch) throws Exception {
    if (original.format() != Format.WAVPACK) return;
    byte[] bytes = Files.readAllBytes(path);
    for (var style : ReadStyle.values())
      for (boolean enabled : new boolean[]{false, true}) {
        var memory = new ByteVectorStream(bytes);
        try (var file = new WavPackFile(path, new ReadOptions(enabled, style, ReadOptions.DEFAULT.maxMetadataBytes())); var borrowed = new WavPackFile(memory, new ReadOptions(enabled, style, ReadOptions.DEFAULT.maxMetadataBytes()))) {
          if (!file.isValid() || !borrowed.isValid() || (file.detailedProperties() != null) != enabled
            || (borrowed.detailedProperties() != null) != enabled || !file.properties().equals(borrowed.properties())
            || !file.tag().title().equals(borrowed.tag().title()))
            throw new AssertionError("WavPack live constructor state");
          if (enabled && (!file.audioProperties().equals(borrowed.audioProperties())
            || file.detailedProperties().version() != borrowed.detailedProperties().version()
            || file.detailedProperties().sampleFrames() != borrowed.detailedProperties().sampleFrames()))
            throw new AssertionError("WavPack live properties");
          System.out.println(path.getFileName() + "\tlive-wavpack-controls\t" + style + ":" + enabled + ":" + file.tell() + ":" + memory.tell() + ":" + file.hasApeTag() + ":" + file.hasId3v1Tag());
        }
        try (var ref = new cz.skyba.taglib.AudioFile(new ByteVectorStream(bytes) {
          @Override
          public String name() {
            return "consumer.wv";
          }
        }, new ReadOptions(enabled, style, ReadOptions.DEFAULT.maxMetadataBytes()))) {
          if (!ref.isOpen() || !(ref.file() instanceof WavPackFile) || (ref.audioProperties() != null) != enabled)
            throw new AssertionError("WavPack actual FileRef handle");
        }
      }
    try (var file = new WavPackFile(new ByteVectorStream(bytes), ReadOptions.TAGS_ONLY)) {
      file.seek(0);
      var properties = new cz.skyba.taglib.wavpack.Properties(file, file.length(), ReadStyle.FAST);
      System.out.println(path.getFileName() + "\tlive-wavpack-direct-properties\t" + properties.version() + ":" + properties.sampleFrames() + ":" + properties.bitrate() + ":" + file.tell());
    }
    Path copy = scratch.resolve("live-wavpack-" + path.getFileName());
    Files.write(copy, bytes);
    try (var file = new WavPackFile(copy)) {
      file.getOrCreateId3v1Tag().setTitle("legacy consumer");
      file.getOrCreateApeTag().setTitle("a longer consumer title");
      file.apeTag().setData("OPAQUE", new ByteVector(new byte[]{0, 1, 2, 3}));
      file.tag().setArtist("broadcast");
      for (int repeat = 0; repeat < 2; repeat++) {
        if (repeat == 1) file.apeTag().setTitle("x");
        file.save();
        System.out.println(path.getFileName() + "\tlive-wavpack-save\t" + repeat + ":" + file.tell() + ":" + file.hasApeTag() + ":" + file.hasId3v1Tag() + ":" + sha256(Files.readAllBytes(copy)));
      }
      file.removeUnsupportedProperties(List.of("OPAQUE"));
      var rejected = file.replaceProperties(PropertyMap.builder().put("TITLE", List.of("first", "second")).put("ID3", "invalid").build()).rejected();
      if (!rejected.get("ID3").equals(List.of("invalid"))) throw new AssertionError("WavPack property rejection");
      file.removeTags(Set.of(WavPackFile.TagType.APE));
      if (file.apeTag() != null || file.id3v1Tag() == null) throw new AssertionError("WavPack strip child");
      file.save();
      file.removeTags();
      file.save();
      if (file.hasApeTag() || file.hasId3v1Tag() || file.apeTag() == null || file.id3v1Tag() != null)
        throw new AssertionError("WavPack physical presence");
    }
    var support = new ByteVectorStream(bytes);
    support.seek(7);
    WavPackFile.isSupported(support);
    if (support.tell() != 7 || WavPackFile.isSupported(null))
      throw new AssertionError("WavPack support cursor");
    expectThrows(UncheckedIOException.class, () -> new WavPackFile(scratch.resolve("missing-wavpack-file")));
    Files.delete(copy);
  }

  private static void liveMpcFilesCheck(Path path, AudioMetadata original, Path scratch) throws Exception {
    if (original.format() != Format.MPC) return;
    byte[] bytes = Files.readAllBytes(path);
    for (var style : ReadStyle.values())
      for (boolean enabled : new boolean[]{false, true}) {
        var memory = new ByteVectorStream(bytes);
        try (var file = new MpcFile(path, new ReadOptions(enabled, style, ReadOptions.DEFAULT.maxMetadataBytes())); var borrowed = new MpcFile(memory, new ReadOptions(enabled, style, ReadOptions.DEFAULT.maxMetadataBytes()))) {
          if (!file.isValid() || !borrowed.isValid() || (file.detailedProperties() != null) != enabled
            || (borrowed.detailedProperties() != null) != enabled || !file.properties().equals(borrowed.properties())
            || !file.tag().title().equals(borrowed.tag().title()))
            throw new AssertionError("MPC live constructor state");
          if (enabled && (!file.audioProperties().equals(borrowed.audioProperties())
            || file.detailedProperties().mpcVersion() != borrowed.detailedProperties().mpcVersion()
            || file.detailedProperties().sampleFrames() != borrowed.detailedProperties().sampleFrames()))
            throw new AssertionError("MPC live properties");
          System.out.println(path.getFileName() + "\tlive-mpc-controls\t" + style + ":" + enabled + ":" + file.tell() + ":" + memory.tell() + ":" + file.hasApeTag() + ":" + file.hasId3v1Tag());
        }
        try (var ref = new cz.skyba.taglib.AudioFile(new ByteVectorStream(bytes) {
          @Override
          public String name() {
            return "consumer.mpc";
          }
        }, new ReadOptions(enabled, style, ReadOptions.DEFAULT.maxMetadataBytes()))) {
          if (!ref.isOpen() || !(ref.file() instanceof MpcFile) || (ref.audioProperties() != null) != enabled)
            throw new AssertionError("MPC actual FileRef handle");
        }
      }
    try (var file = new MpcFile(new ByteVectorStream(bytes), ReadOptions.TAGS_ONLY)) {
      file.seek(0);
      var properties = new cz.skyba.taglib.mpc.Properties(file, file.length(), ReadStyle.FAST);
      System.out.println(path.getFileName() + "\tlive-mpc-direct-properties\t" + properties.mpcVersion() + ":" + properties.sampleFrames() + ":" + properties.bitrate() + ":" + file.tell());
    }
    Path copy = scratch.resolve("live-mpc-" + path.getFileName());
    Files.write(copy, bytes);
    try (var file = new MpcFile(copy)) {
      file.getOrCreateId3v1Tag().setTitle("legacy consumer");
      file.getOrCreateApeTag().setTitle("a longer consumer title");
      file.apeTag().setData("OPAQUE", new ByteVector(new byte[]{0, 1, 2, 3}));
      file.tag().setArtist("broadcast");
      for (int repeat = 0; repeat < 2; repeat++) {
        if (repeat == 1) file.apeTag().setTitle("x");
        file.save();
        System.out.println(path.getFileName() + "\tlive-mpc-save\t" + repeat + ":" + file.tell() + ":" + file.hasApeTag() + ":" + file.hasId3v1Tag() + ":" + sha256(Files.readAllBytes(copy)));
      }
      file.removeUnsupportedProperties(List.of("OPAQUE"));
      var rejected = file.replaceProperties(PropertyMap.builder().put("TITLE", List.of("first", "second")).put("ID3", "invalid").build()).rejected();
      if (!rejected.get("ID3").equals(List.of("invalid"))) throw new AssertionError("MPC property rejection");
      file.removeTags(Set.of(MpcFile.TagType.ID3V2));
      file.save();
      file.removeTags(Set.of(MpcFile.TagType.APE));
      if (file.apeTag() != null || file.id3v1Tag() == null) throw new AssertionError("MPC strip child");
      file.save();
      file.removeTags();
      file.save();
      if (file.hasApeTag() || file.hasId3v1Tag() || file.apeTag() == null || file.id3v1Tag() != null)
        throw new AssertionError("MPC physical presence");
    }
    var support = new ByteVectorStream(bytes);
    support.seek(7);
    MpcFile.isSupported(support);
    if (support.tell() != 7 || MpcFile.isSupported(null)) throw new AssertionError("MPC support cursor");
    expectThrows(UncheckedIOException.class, () -> new MpcFile(scratch.resolve("missing-mpc-file")));
    Files.delete(copy);
  }

  private static void detailedApePropertiesCheck(Path path, AudioMetadata original) throws Exception {
    if (!EnumSet.of(Format.APE, Format.WAVPACK, Format.MPC).contains(original.format())) return;
    String details;
    var memory = new cz.skyba.taglib.toolkit.ByteVectorStream(Files.readAllBytes(path));
    switch (original.format()) {
      case APE -> {
        var p = new cz.skyba.taglib.ape.Properties(path);
        var m = new cz.skyba.taglib.ape.Properties(memory);
        if (!p.common().equals(original.audioProperties())
          || !p.common().equals(m.common())
          || p.version() != m.version()
          || p.sampleFrames() != m.sampleFrames())
          throw new AssertionError("APE detailed properties changed across stream adapters");
        details = p.version() + ":" + p.bitsPerSample() + ":" + p.sampleFrames();
      }
      case WAVPACK -> {
        var p = new cz.skyba.taglib.wavpack.Properties(path);
        var m = new cz.skyba.taglib.wavpack.Properties(memory);
        if (!p.common().equals(original.audioProperties())
          || !p.common().equals(m.common())
          || p.version() != m.version()
          || p.sampleFrames() != m.sampleFrames()
          || p.isDsd() != m.isDsd())
          throw new AssertionError("WavPack detailed properties changed across stream adapters");
        details =
          p.version()
            + ":"
            + p.bitsPerSample()
            + ":"
            + p.sampleFrames()
            + ":"
            + p.isLossless()
            + ":"
            + p.isDsd();
      }
      case MPC -> {
        var p = new cz.skyba.taglib.mpc.Properties(path);
        var m = new cz.skyba.taglib.mpc.Properties(memory);
        if (!p.common().equals(original.audioProperties())
          || !p.common().equals(m.common())
          || p.mpcVersion() != m.mpcVersion()
          || p.sampleFrames() != m.sampleFrames()
          || p.totalFrames() != m.totalFrames()
          || p.trackGain() != m.trackGain()
          || p.trackPeak() != m.trackPeak()
          || p.albumGain() != m.albumGain()
          || p.albumPeak() != m.albumPeak())
          throw new AssertionError("Musepack detailed properties changed across stream adapters");
        details =
          p.mpcVersion()
            + ":"
            + p.totalFrames()
            + ":"
            + Long.toUnsignedString(p.sampleFrames())
            + ":"
            + p.trackGain()
            + ":"
            + p.trackPeak()
            + ":"
            + p.albumGain()
            + ":"
            + p.albumPeak();
      }
      default -> {
        return;
      }
    }
    System.out.println(path.getFileName() + "\tdetailed-ape-family\t" + details);
  }

  private static byte[] opusPage(int sequence, int flags, long granule, byte[] packet) {
    return ByteBuffer.allocate(28 + packet.length).order(ByteOrder.LITTLE_ENDIAN)
      .put(new byte[]{'O', 'g', 'g', 'S', 0, (byte) flags}).putLong(granule)
      .putInt(1).putInt(sequence).putInt(0).put((byte) 1).put((byte) packet.length)
      .put(packet).array();
  }

  private static void partialOpusCheck() throws Exception {
    var data = new ByteVector(opusPage(0, 2, 0,
      new byte[]{'O', 'p', 'u', 's', 'H', 'e', 'a', 'd', 1, 2, (byte) 128}))
      .append(new ByteVector(opusPage(1, 0, 0,
        new ByteVector("OpusTags").append(new ByteVector(8)).toByteArray())))
      .append(new ByteVector(opusPage(2, 4, 48000, new byte[]{1, 2, 3})));
    var stream = new ByteVectorStream(data) {
      public String name() {
        return "partial-identification.opus";
      }
    };
    for (var style : ReadStyle.values()) {
      var file = TagLib.read(stream, new ReadOptions(true, style, ReadOptions.DEFAULT.maxMetadataBytes()));
      if (file.audioProperties().lengthInMilliseconds() != 997 || !stream.isOpen())
        throw new AssertionError("Opus partial pre-skip conversion");
    }
    if (!java.util.Arrays.equals(data.toByteArray(), stream.data().toByteArray()))
      throw new AssertionError("Opus property read changed bytes");
  }

  private static String trackerFields(cz.skyba.taglib.mod.Properties p) {
    return p.common() + ":" + p.lengthInMilliseconds() + ":" + p.lengthInSeconds() + ":" + p.bitrate() + ":" + p.sampleRate() + ":" + p.channels() + ":" + p.instrumentCount() + ":" + p.lengthInPatterns();
  }

  private static String trackerFields(cz.skyba.taglib.s3m.Properties p) {
    return p.common() + ":" + p.lengthInMilliseconds() + ":" + p.lengthInSeconds() + ":" + p.bitrate() + ":" + p.sampleRate() + ":" + p.lengthInPatterns() + ":" + p.channels() + ":" + p.stereo() + ":" + p.sampleCount() + ":" + p.patternCount() + ":" + p.flags() + ":" + p.trackerVersion() + ":" + p.fileFormatVersion() + ":" + p.globalVolume() + ":" + p.masterVolume() + ":" + p.tempo() + ":" + p.bpmSpeed();
  }

  private static String trackerFields(cz.skyba.taglib.it.Properties p) {
    return p.common() + ":" + p.lengthInMilliseconds() + ":" + p.lengthInSeconds() + ":" + p.bitrate() + ":" + p.sampleRate() + ":" + p.channels() + ":" + p.lengthInPatterns() + ":" + p.instrumentCount() + ":" + p.sampleCount() + ":" + p.patternCount() + ":" + p.version() + ":" + p.compatibleVersion() + ":" + p.flags() + ":" + p.special() + ":" + p.globalVolume() + ":" + p.mixVolume() + ":" + p.tempo() + ":" + p.bpmSpeed() + ":" + p.panningSeparation() + ":" + p.pitchWheelDepth() + ":" + p.stereo();
  }

  private static String trackerFields(cz.skyba.taglib.xm.Properties p) {
    return p.common() + ":" + p.lengthInMilliseconds() + ":" + p.lengthInSeconds() + ":" + p.bitrate() + ":" + p.sampleRate() + ":" + p.lengthInPatterns() + ":" + p.channels() + ":" + p.version() + ":" + p.restartPosition() + ":" + p.patternCount() + ":" + p.instrumentCount() + ":" + p.sampleCount() + ":" + p.flags() + ":" + p.tempo() + ":" + p.bpmSpeed();
  }

  private static void trackerMutablePropertiesCheck() {
    for (var style : ReadStyle.values()) {
      var mod = new cz.skyba.taglib.mod.Properties(style);
      if (!mod.common().equals(AudioProperties.EMPTY)) throw new AssertionError("mod zero native defaults");
      var s3m = new cz.skyba.taglib.s3m.Properties(style);
      if (!s3m.common().equals(AudioProperties.EMPTY)) throw new AssertionError("s3m zero native defaults");
      var it = new cz.skyba.taglib.it.Properties(style);
      if (!it.common().equals(AudioProperties.EMPTY)) throw new AssertionError("it zero native defaults");
      var xm = new cz.skyba.taglib.xm.Properties(style);
      if (!xm.common().equals(AudioProperties.EMPTY)) throw new AssertionError("xm zero native defaults");
      for (long value : new long[]{Long.MIN_VALUE, -1, 0, 256, 65536, Long.MAX_VALUE}) {
        mod.setChannels((int) value);
        mod.setInstrumentCount(value);
        mod.setLengthInPatterns((int) value);
        System.out.println("tracker-properties\tmod-values-" + style + "-" + value + "\t" + trackerFields(mod));
        s3m.setLengthInPatterns((int) value);
        s3m.setChannels((int) value);
        s3m.setStereo(value != 0);
        s3m.setSampleCount((int) value);
        s3m.setPatternCount((int) value);
        s3m.setFlags((int) value);
        s3m.setTrackerVersion((int) value);
        s3m.setFileFormatVersion((int) value);
        s3m.setGlobalVolume((int) value);
        s3m.setMasterVolume((int) value);
        s3m.setTempo((int) value);
        s3m.setBpmSpeed((int) value);
        System.out.println("tracker-properties\ts3m-values-" + style + "-" + value + "\t" + trackerFields(s3m));
        it.setChannels((int) value);
        it.setLengthInPatterns((int) value);
        it.setInstrumentCount((int) value);
        it.setSampleCount((int) value);
        it.setPatternCount((int) value);
        it.setVersion((int) value);
        it.setCompatibleVersion((int) value);
        it.setFlags((int) value);
        it.setSpecial((int) value);
        it.setGlobalVolume((int) value);
        it.setMixVolume((int) value);
        it.setTempo((int) value);
        it.setBpmSpeed((int) value);
        it.setPanningSeparation((int) value);
        it.setPitchWheelDepth((int) value);
        System.out.println("tracker-properties\tit-values-" + style + "-" + value + "\t" + trackerFields(it));
        xm.setLengthInPatterns((int) value);
        xm.setChannels((int) value);
        xm.setVersion((int) value);
        xm.setRestartPosition((int) value);
        xm.setPatternCount((int) value);
        xm.setInstrumentCount((int) value);
        xm.setSampleCount(value);
        xm.setFlags((int) value);
        xm.setTempo((int) value);
        xm.setBpmSpeed((int) value);
        System.out.println("tracker-properties\txm-values-" + style + "-" + value + "\t" + trackerFields(xm));
      }
    }
    if (cz.skyba.taglib.s3m.Properties.ST2Vibrato != 1 || cz.skyba.taglib.s3m.Properties.ST2Tempo != 2
      || cz.skyba.taglib.s3m.Properties.AmigaSlides != 4 || cz.skyba.taglib.s3m.Properties.Vol0MixOptimizations != 8
      || cz.skyba.taglib.s3m.Properties.AmigaLimits != 16 || cz.skyba.taglib.s3m.Properties.EnableFilter != 32
      || cz.skyba.taglib.s3m.Properties.CustomData != 128 || cz.skyba.taglib.it.Properties.Stereo != 1
      || cz.skyba.taglib.it.Properties.Vol0MixOptimizations != 2 || cz.skyba.taglib.it.Properties.UseInstruments != 4
      || cz.skyba.taglib.it.Properties.LinearSlides != 8 || cz.skyba.taglib.it.Properties.OldEffects != 16
      || cz.skyba.taglib.it.Properties.LinkEffects != 32 || cz.skyba.taglib.it.Properties.UseMidiPitchController != 64
      || cz.skyba.taglib.it.Properties.RequestEmbeddedMidiConf != 128 || cz.skyba.taglib.it.Properties.MessageAttached != 1
      || cz.skyba.taglib.it.Properties.MidiConfEmbedded != 8 || cz.skyba.taglib.xm.Properties.LinearFreqTable != 1)
      throw new AssertionError("Tracker installed constants");
  }

  private static void partialXmPropertiesCheck(Path path) throws Exception {
    byte[] data = new byte[69];
    ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(60, 9);
    data[68] = 7;
    Files.write(path, data);
    for (var style : ReadStyle.values()) {
      var stream = new ByteVectorStream(data);
      stream.seek(7);
      var p = new cz.skyba.taglib.xm.Properties(stream, style);
      if (p.channels() != 7 || stream.tell() != 69 || !stream.isOpen())
        throw new AssertionError("XM available-prefix channels/cursor");
      stream.data().clear();
      if (p.channels() != 7) throw new AssertionError("XM retained parsed values");
      try (var ref = new cz.skyba.taglib.AudioFile(path, new ReadOptions(false, style, ReadOptions.DEFAULT.maxMetadataBytes()))) {
        if (!ref.isOpen() || ref.audioProperties().channels() != 7)
          throw new AssertionError("XM short header/read controls");
      }
      System.out.println("tracker-properties\txm-partial-" + style + "\t" + trackerFields(p));
    }
  }

  private static void detailedTrackerPropertiesCheck(Path path, AudioMetadata original) throws Exception {
    if (!java.util.Set.of(Format.MOD, Format.S3M, Format.IT, Format.XM).contains(original.format())) return;
    byte[] data = Files.readAllBytes(path);
    for (var style : ReadStyle.values()) {
      String fields;
      switch (original.format()) {
        case MOD -> {
          var p = new cz.skyba.taglib.mod.Properties(path, style);
          var stream = new ByteVectorStream(data);
          var borrowed = new cz.skyba.taglib.mod.Properties(stream, style);
          fields = trackerFields(p);
          stream.data().clear();
          if (!fields.equals(trackerFields(borrowed)) || !stream.isOpen()
            || !p.common().equals(original.audioProperties())
            || !fields.equals(trackerFields(new cz.skyba.taglib.mod.Properties(path)))
            || !fields.equals(trackerFields(new cz.skyba.taglib.mod.Properties(new ByteVectorStream(data)))))
            throw new AssertionError("mod detailed/default/style/retained properties");
        }
        case S3M -> {
          var p = new cz.skyba.taglib.s3m.Properties(path, style);
          var stream = new ByteVectorStream(data);
          var borrowed = new cz.skyba.taglib.s3m.Properties(stream, style);
          fields = trackerFields(p);
          stream.data().clear();
          if (!fields.equals(trackerFields(borrowed)) || !stream.isOpen()
            || !p.common().equals(original.audioProperties())
            || !fields.equals(trackerFields(new cz.skyba.taglib.s3m.Properties(path)))
            || !fields.equals(trackerFields(new cz.skyba.taglib.s3m.Properties(new ByteVectorStream(data)))))
            throw new AssertionError("s3m detailed/default/style/retained properties");
        }
        case IT -> {
          var p = new cz.skyba.taglib.it.Properties(path, style);
          var stream = new ByteVectorStream(data);
          var borrowed = new cz.skyba.taglib.it.Properties(stream, style);
          fields = trackerFields(p);
          stream.data().clear();
          if (!fields.equals(trackerFields(borrowed)) || !stream.isOpen()
            || !p.common().equals(original.audioProperties())
            || !fields.equals(trackerFields(new cz.skyba.taglib.it.Properties(path)))
            || !fields.equals(trackerFields(new cz.skyba.taglib.it.Properties(new ByteVectorStream(data)))))
            throw new AssertionError("it detailed/default/style/retained properties");
        }
        case XM -> {
          var p = new cz.skyba.taglib.xm.Properties(path, style);
          var stream = new ByteVectorStream(data);
          var borrowed = new cz.skyba.taglib.xm.Properties(stream, style);
          fields = trackerFields(p);
          stream.data().clear();
          if (!fields.equals(trackerFields(borrowed)) || !stream.isOpen()
            || !p.common().equals(original.audioProperties())
            || !fields.equals(trackerFields(new cz.skyba.taglib.xm.Properties(path)))
            || !fields.equals(trackerFields(new cz.skyba.taglib.xm.Properties(new ByteVectorStream(data)))))
            throw new AssertionError("xm detailed/default/style/retained properties");
        }
        default -> throw new AssertionError();
      }
      System.out.println(path.getFileName() + "\tdetailed-tracker-" + style + "\t" + fields);
    }
  }


  private static final class TrackerHelpers extends cz.skyba.taglib.mod.FileBase {
    TrackerHelpers(ByteVectorStream stream) {
      super(stream);
    }

    @Override
    public cz.skyba.taglib.TagBase tag() {
      return null;
    }

    @Override
    public AudioProperties audioProperties() {
      return AudioProperties.EMPTY;
    }

    @Override
    public void save() {
      throw new UnsupportedOperationException("Smoke helper has no save implementation");
    }

    void writeValues() {
      writeString("€ÿx", 4, (byte) 255);
      writeString("pad", 5);
      writeByte(255);
      writeU16L(0x1234);
      writeU16B(0x5678);
      writeU32L(0xabcdef01L);
      writeU32B(0x87654321L);
    }

    void readValues() {
      String[] text = {""};
      int[] byteValue = {0}, shortValue = {0};
      long[] longValue = {0};
      if (!readString(v -> text[0] = v, 4) || !text[0].equals("¬ x ")
        || !readString(v -> text[0] = v, 5) || !text[0].equals("pad")
        || !readByte(v -> byteValue[0] = v) || byteValue[0] != 255
        || !readU16L(v -> shortValue[0] = v) || shortValue[0] != 0x1234
        || !readU16B(v -> shortValue[0] = v) || shortValue[0] != 0x5678
        || !readU32L(v -> longValue[0] = v) || longValue[0] != 0xabcdef01L
        || !readU32B(v -> longValue[0] = v) || longValue[0] != 0x87654321L
        || readU32B(v -> longValue[0] = v) || longValue[0] != 0x87654321L)
        throw new AssertionError("Tracker helper complete/short assignments");
    }
  }

  private static void liveTrackerTagsAndHelpersCheck(Path scratch) throws Exception {
    var tag = new cz.skyba.taglib.mod.Tag();
    var shorten = new cz.skyba.taglib.shorten.Tag();
    for (cz.skyba.taglib.TagBase value : java.util.List.of(tag, shorten)) {
      value.setTitle("title");
      value.setArtist("ignored");
      value.setAlbum("ignored");
      value.setComment("comment");
      value.setGenre("ignored");
      value.setYear(-1);
      value.setTrack(-1);
      if (!value.artist().isEmpty() || !value.album().isEmpty() || !value.genre().isEmpty()
        || value.year() != 0 || value.track() != 0) throw new AssertionError("Tracker no-op fields");
    }
    tag.setTrackerName("tracker");
    var input = cz.skyba.taglib.PropertyMap.builder().put("TITLE", java.util.List.of("first", "extra"))
      .put("COMMENT", java.util.List.of()).put("UNKNOWN", "kept").addUnsupportedData("opaque").build();
    var rejected = tag.replaceProperties(input).rejected();
    if (!tag.title().equals("first") || !tag.comment().isEmpty() || !tag.trackerName().isEmpty()
      || !rejected.get("TITLE").equals(java.util.List.of("extra"))
      || !rejected.unsupportedData().isEmpty() || !shorten.replaceProperties(input).rejected().equals(input)
      || !shorten.properties().equals(cz.skyba.taglib.PropertyMap.EMPTY) || !shorten.isEmpty())
      throw new AssertionError("Tracker tag imports");
    tag.replaceProperties(cz.skyba.taglib.PropertyMap.EMPTY);
    tag.setTrackerName("only tracker");
    if (!tag.isEmpty() || !tag.properties().get("TITLE").equals(java.util.List.of("")))
      throw new AssertionError("Tracker empty field export");
    var stream = new ByteVectorStream(new byte[0]);
    var helpers = new TrackerHelpers(stream);
    helpers.writeValues();
    stream.seek(0);
    helpers.readValues();
    expectThrows(UncheckedIOException.class, () -> new ShortenFile(scratch.resolve("missing.shn")));
    var invalid = new ByteVectorStream(new byte[]{'n', 'o', 'p', 'e'});
    expectThrows(UncheckedIOException.class, () -> new ShortenFile(invalid));
    if (!invalid.isOpen()) throw new AssertionError("Rejected Shorten borrowed lifetime");
    if (ShortenFile.isSupported(null)) throw new AssertionError("Shorten null support");
    byte[] partial = new byte[69];
    ByteBuffer.wrap(partial).order(ByteOrder.LITTLE_ENDIAN).putInt(60, 9);
    partial[68] = 7;
    var borrowed = new ByteVectorStream(partial);
    try (var file = new XmFile(borrowed)) {
      if (!file.isValid() || file.detailedProperties().channels() != 7)
        throw new AssertionError("Live XM partial properties");
      file.tag().setTitle("changed");
      file.tag().setTrackerName("changed tracker");
      expectThrows(TagLibException.class, file::save);
      if (!new String(borrowed.data().toByteArray(), 17, 7, StandardCharsets.ISO_8859_1).equals("changed")
        || borrowed.tell() != 70) throw new AssertionError("XM failed-save preceding writes");
    }
    if (!borrowed.isOpen()) throw new AssertionError("Tracker borrowed ownership");
    System.out.println("tracker-files\tglobal-handles-tags-helpers\t" + HexFormat.of().formatHex(stream.data().toByteArray()));
  }

  private static cz.skyba.taglib.internal.codec.TrackerFile trackerFile(Path path, Format format,
                                                                   boolean read, ReadStyle style) {
    return switch (format) {
      case MOD -> new ModFile(path, style);
      case S3M -> new S3mFile(path, style);
      case IT -> new ItFile(path, style);
      case XM -> new XmFile(path, style);
      default -> throw new AssertionError();
    };
  }

  private static cz.skyba.taglib.internal.codec.TrackerFile trackerFile(ByteVectorStream stream, Format format,
                                                                   boolean read, ReadStyle style) {
    return switch (format) {
      case MOD -> new ModFile(stream, style);
      case S3M -> new S3mFile(stream, style);
      case IT -> new ItFile(stream, style);
      case XM -> new XmFile(stream, style);
      default -> throw new AssertionError();
    };
  }

  private static String trackerFields(cz.skyba.taglib.internal.codec.TrackerFile file) {
    return switch (file) {
      case ModFile f -> trackerFields(f.detailedProperties());
      case S3mFile f -> trackerFields(f.detailedProperties());
      case ItFile f -> trackerFields(f.detailedProperties());
      case XmFile f -> trackerFields(f.detailedProperties());
      default -> throw new AssertionError();
    };
  }

  private static void liveTrackerFilesCheck(Path path, AudioMetadata original, Path scratch) throws Exception {
    var format = original.format();
    if (!java.util.Set.of(Format.MOD, Format.S3M, Format.IT, Format.XM).contains(format)) return;
    byte[] bytes = Files.readAllBytes(path);
    for (var style : ReadStyle.values())
      for (boolean read : new boolean[]{false, true}) {
        var stream = new ByteVectorStream(bytes);
        cz.skyba.taglib.internal.codec.TrackerFile owned;
        String fields;
        try (var file = trackerFile(path, format, read, style); var borrowed = trackerFile(stream, format, read, style);
             var ref = new cz.skyba.taglib.AudioFile(path, new ReadOptions(read, style, ReadOptions.DEFAULT.maxMetadataBytes()))) {
          owned = file;
          fields = trackerFields(file);
          if (!file.isValid() || !borrowed.isValid() || !ref.isOpen()
            || !fields.equals(trackerFields(borrowed))
            || !fields.equals(trackerFields((cz.skyba.taglib.internal.codec.TrackerFile) ref.file()))
            || !file.audioProperties().equals(original.audioProperties())
            || !file.properties().equals(borrowed.properties()))
            throw new AssertionError("Live tracker types/flags/styles");
        }
        if (owned.isOpen() || !stream.isOpen()) throw new AssertionError("Live tracker constructor ownership");
        System.out.println(path.getFileName() + "\tlive-tracker-" + style + "-" + read + "\t" + fields);
      }
    var defaults = switch (format) {
      case MOD -> java.util.List.of(new ModFile(path), new ModFile(path),
        new ModFile(new ByteVectorStream(bytes)), new ModFile(new ByteVectorStream(bytes)));
      case S3M -> java.util.List.of(new S3mFile(path), new S3mFile(path),
        new S3mFile(new ByteVectorStream(bytes)), new S3mFile(new ByteVectorStream(bytes)));
      case IT -> java.util.List.of(new ItFile(path), new ItFile(path),
        new ItFile(new ByteVectorStream(bytes)), new ItFile(new ByteVectorStream(bytes)));
      case XM -> java.util.List.of(new XmFile(path), new XmFile(path),
        new XmFile(new ByteVectorStream(bytes)), new XmFile(new ByteVectorStream(bytes)));
      default -> throw new AssertionError();
    };
    try {
      for (var file : defaults)
        if (!file.isValid() || !file.audioProperties().equals(original.audioProperties()))
          throw new AssertionError("Tracker default/two-argument construction");
    } finally {
      for (var file : defaults) file.close();
    }
    String extension = path.getFileName().toString();
    extension = extension.substring(extension.lastIndexOf('.'));
    Path copy = Files.createTempFile(scratch, "live-tracker-", extension);
    Files.write(copy, bytes);
    try (var file = trackerFile(copy, format, true, ReadStyle.AVERAGE)) {
      file.tag().setTitle("live title");
      file.tag().setComment("first\nsecond\nthird\nmessage");
      file.tag().setTrackerName("live tracker");
      for (int i = 0; i < 2; i++) {
        file.save();
        System.out.println(path.getFileName() + "\tlive-tracker-save-" + i + "\tPASS:" + file.tell() + ":" + sha256(Files.readAllBytes(copy)));
      }
    } finally {
      Files.deleteIfExists(copy);
    }
  }

  private static void liveShortenFilesCheck(Path path, AudioMetadata original) throws Exception {
    if (original.format() != Format.SHORTEN) return;
    byte[] data = Files.readAllBytes(path);
    try (var file = new ShortenFile(path); var twoArg = new ShortenFile(path, ReadOptions.TAGS_ONLY);
         var ioDefault = new ShortenFile(new ByteVectorStream(data));
         var ioTwoArg = new ShortenFile(new ByteVectorStream(data), ReadOptions.TAGS_ONLY)) {
      if (!file.isValid() || !twoArg.isValid() || !ioDefault.isValid() || !ioTwoArg.isValid() || !file.audioProperties().equals(original.audioProperties()))
        throw new AssertionError("Shorten default constructors");
    }
    for (var style : ReadStyle.values())
      for (boolean read : new boolean[]{false, true}) {
        var stream = new ByteVectorStream(data);
        stream.seek(7);
        long before = stream.tell();
        if (!ShortenFile.isSupported(stream) || stream.tell() != before)
          throw new AssertionError("Shorten support cursor");
        stream.seek(0);
        try (var file = new ShortenFile(path, new ReadOptions(read, style, ReadOptions.DEFAULT.maxMetadataBytes()));
             var borrowed = new ShortenFile(stream, new ReadOptions(read, style, ReadOptions.DEFAULT.maxMetadataBytes()))) {
          expectThrows(UnsupportedOperationException.class, file::save);
          expectThrows(UnsupportedOperationException.class, borrowed::save);
          if (!file.isValid() || !borrowed.isValid() || file.tag() == null
            || !file.audioProperties().equals(original.audioProperties())
            || !file.audioProperties().equals(borrowed.audioProperties()))
            throw new AssertionError("Shorten live controls");
        }
        if (!stream.isOpen()) throw new AssertionError("Shorten borrowed lifetime");
      }
    var stream = new ByteVectorStream(data) {
      @Override
      public String name() {
        return "zero-length.shn";
      }

      @Override
      public long length() {
        return 0;
      }
    };
    try (var file = new ShortenFile(stream, ReadOptions.DEFAULT)) {
      if (!file.isValid()) throw new AssertionError("Shorten native refill on reported zero length");
    }
    stream.seek(0);
    try (var ref = new cz.skyba.taglib.AudioFile(stream, ReadOptions.DEFAULT)) {
      if (!ref.isOpen() || !(ref.file() instanceof ShortenFile))
        throw new AssertionError("Shorten single-read FileRef");
    }
    stream.seek(0);
    var props = new cz.skyba.taglib.shorten.Properties(stream, ReadOptions.DEFAULT);
    if (!props.common().equals(original.audioProperties())) throw new AssertionError("Shorten read-limit adapter");
    System.out.println(path.getFileName() + "\tlive-shorten\t" + props.common());
  }

  private static String matroskaFields(cz.skyba.taglib.matroska.Properties p) {
    return p.common() + ":" + p.lengthInMilliseconds() + ":" + p.lengthInSeconds()
      + ":" + p.bitrate() + ":" + p.sampleRate() + ":" + p.channels() + ":" + p.bitsPerSample()
      + ":" + p.docTypeVersion() + ":"
      + HexFormat.of().formatHex(p.docType().getBytes(StandardCharsets.UTF_8)) + ":"
      + HexFormat.of().formatHex(p.codecName().getBytes(StandardCharsets.UTF_8)) + ":"
      + HexFormat.of().formatHex(p.title().getBytes(StandardCharsets.UTF_8));
  }

  private static void matroskaEmptyPropertiesCheck() {
    for (var style : ReadStyle.values()) {
      var p = new cz.skyba.taglib.matroska.Properties(style);
      if (!p.common().equals(AudioProperties.EMPTY) || !p.docType().isEmpty()
        || p.docTypeVersion() != 0 || !p.codecName().isEmpty() || !p.title().isEmpty()
        || !matroskaFields(p).equals(matroskaFields(
        new cz.skyba.taglib.matroska.Properties())))
        throw new AssertionError("Matroska direct/null/default property fields");
    }
  }

  private static void detailedMatroskaPropertiesCheck(Path path, AudioMetadata original) throws Exception {
    if (original.format() != Format.MATROSKA) return;
    byte[] data = Files.readAllBytes(path);
    for (var style : ReadStyle.values()) {
      var p = new cz.skyba.taglib.matroska.Properties(path, style);
      var stream = new ByteVectorStream(data);
      var borrowed = new cz.skyba.taglib.matroska.Properties(stream, style);
      String fields = matroskaFields(p);
      if (!fields.equals(matroskaFields(borrowed)))
        throw new AssertionError("Matroska borrowed properties");
      stream.data().clear();
      var expected = TagLib.read(path,
        new ReadOptions(true, style, ReadOptions.DEFAULT.maxMetadataBytes())).audioProperties();
      if (!fields.equals(matroskaFields(borrowed)) || !p.common().equals(expected) || !stream.isOpen())
        throw new AssertionError("Matroska cached/source/style properties");
      if (style == ReadStyle.AVERAGE
        && (!fields.equals(matroskaFields(new cz.skyba.taglib.matroska.Properties(path)))
        || !fields.equals(matroskaFields(
        new cz.skyba.taglib.matroska.Properties(new ByteVectorStream(data))))))
        throw new AssertionError("Matroska default properties");
      var freshStream = new ByteVectorStream(data);
      var uncached = new cz.skyba.taglib.matroska.Properties(freshStream, style);
      freshStream.data().clear();
      if (uncached.bitrate() != 0) throw new AssertionError("Matroska first bitrate uses current size");
      freshStream.data().append(new ByteVector(new byte[1000]));
      if (uncached.bitrate() != 0 || uncached.lengthInMilliseconds() != p.lengthInMilliseconds()
        || !uncached.title().equals(p.title()) || !freshStream.isOpen())
        throw new AssertionError("Matroska lazy bitrate caches independently of parsed fields");
      System.out.println(path.getFileName() + "\tdetailed-matroska-" + style + "\t" + fields
        + "\t" + stream.tell() + "\tlazy-bitrate=0");
    }
  }

  private static String mp4Fields(cz.skyba.taglib.mp4.Properties p) {
    return p.common() + ":" + p.lengthInMilliseconds() + ":" + p.lengthInSeconds()
      + ":" + p.bitrate() + ":" + p.sampleRate() + ":" + p.channels() + ":" + p.bitsPerSample()
      + ":" + p.codec() + ":" + p.isEncrypted() + ":"
      + HexFormat.of().formatHex(p.codecId().getBytes(StandardCharsets.UTF_8));
  }

  private static void detailedMp4PropertiesCheck(Path path, AudioMetadata original) throws Exception {
    if (original.format() != Format.MP4) return;
    byte[] data = Files.readAllBytes(path);
    for (var style : ReadStyle.values()) {
      var p = new cz.skyba.taglib.mp4.Properties(path, style);
      var stream = new ByteVectorStream(data);
      var borrowed = new cz.skyba.taglib.mp4.Properties(stream, style);
      String fields = mp4Fields(p);
      stream.data().clear();
      if (!fields.equals(mp4Fields(borrowed)) || !p.common().equals(original.audioProperties())
        || !stream.isOpen() || !fields.equals(mp4Fields(new cz.skyba.taglib.mp4.Properties(path)))
        || !fields.equals(mp4Fields(new cz.skyba.taglib.mp4.Properties(new ByteVectorStream(data)))))
        throw new AssertionError("MP4 detailed/default/borrowed/snapshot properties");
      System.out.println(path.getFileName() + "\tdetailed-mp4-" + style + "\t" + fields
        + "\t" + stream.tell());
    }
  }

  private static String shortenFields(cz.skyba.taglib.shorten.Properties p) {
    return p.common() + ":" + p.lengthInMilliseconds() + ":" + p.lengthInSeconds()
      + ":" + p.bitrate() + ":" + p.sampleRate() + ":" + p.channels() + ":" + p.bitsPerSample()
      + ":" + p.shortenVersion() + ":" + p.fileType() + ":" + Long.toUnsignedString(p.sampleFrames());
  }

  private static void shortenValuesPropertiesCheck() {
    for (var style : ReadStyle.values()) {
      var empty = new cz.skyba.taglib.shorten.Properties((cz.skyba.taglib.shorten.Properties.Values) null, style);
      if (!empty.common().equals(AudioProperties.EMPTY)
        || !shortenFields(empty).equals(shortenFields(new cz.skyba.taglib.shorten.Properties((cz.skyba.taglib.shorten.Properties.Values) null))))
        throw new AssertionError("Shorten null/default property fields");
      for (long frames : new long[]{0, 88200, Long.MIN_VALUE, -1}) {
        var values = new cz.skyba.taglib.shorten.Properties.Values(2, 5, 2, 44100, 16, frames);
        var p = new cz.skyba.taglib.shorten.Properties(values, style);
        if (p.sampleFrames() != frames || p.shortenVersion() != 2 || p.fileType() != 5
          || !shortenFields(p).equals(shortenFields(new cz.skyba.taglib.shorten.Properties(values))))
          throw new AssertionError("Shorten value/default fields");
        System.out.println("shorten-properties\tvalues-" + style + "-" + Long.toUnsignedString(frames)
          + "\t" + shortenFields(p));
      }
    }
  }

  private static void detailedShortenPropertiesCheck(Path path, AudioMetadata original) throws Exception {
    if (original.format() != Format.SHORTEN) return;
    byte[] data = Files.readAllBytes(path);
    for (var style : ReadStyle.values()) {
      var p = new cz.skyba.taglib.shorten.Properties(path, style);
      var stream = new ByteVectorStream(data);
      var borrowed = new cz.skyba.taglib.shorten.Properties(stream, style);
      String fields = shortenFields(p);
      stream.data().clear();
      if (!fields.equals(shortenFields(borrowed)) || !p.common().equals(original.audioProperties())
        || !stream.isOpen() || !fields.equals(shortenFields(new cz.skyba.taglib.shorten.Properties(path)))
        || !fields.equals(shortenFields(new cz.skyba.taglib.shorten.Properties(new ByteVectorStream(data)))))
        throw new AssertionError("Shorten detailed/default/borrowed/snapshot properties");
      System.out.println(path.getFileName() + "\tdetailed-shorten-" + style + "\t" + fields
        + "\t" + stream.tell());
    }
  }

  private static String asfFields(cz.skyba.taglib.asf.Properties p) {
    return p.common() + ":" + p.lengthInMilliseconds() + ":" + p.lengthInSeconds()
      + ":" + p.bitrate() + ":" + p.sampleRate() + ":" + p.channels() + ":" + p.bitsPerSample()
      + ":" + p.codec() + ":" + p.isEncrypted() + ":"
      + HexFormat.of().formatHex(p.codecName().getBytes(StandardCharsets.UTF_8)) + ":"
      + HexFormat.of().formatHex(p.codecDescription().getBytes(StandardCharsets.UTF_8));
  }

  private static void asfMutablePropertiesCheck() {
    var p = new cz.skyba.taglib.asf.Properties();
    if (!p.common().equals(AudioProperties.EMPTY) || p.codec() != cz.skyba.taglib.asf.Properties.Codec.Unknown
      || p.isEncrypted() || !p.codecName().isEmpty() || !p.codecDescription().isEmpty())
      throw new AssertionError("ASF default property fields");
    p.setLengthInMilliseconds(-1001);
    p.setBitrate(-17);
    p.setSampleRate(Integer.MIN_VALUE);
    p.setChannels(-2);
    p.setBitsPerSample(65535);
    p.setCodecName(" name ");
    p.setCodecDescription("é音\u0000tail");
    p.setEncrypted(true);
    for (int codec : new int[]{0x160, 0x161, 0x162, 0x163, 0, -1}) {
      p.setCodec(codec);
      if (p.lengthInSeconds() != -1 || p.bitrate() != -17 || p.sampleRate() != Integer.MIN_VALUE
        || p.channels() != -2 || p.bitsPerSample() != 65535 || !p.isEncrypted()
        || !p.codecName().equals(" name ") || !p.codecDescription().equals("é音\u0000tail")
        || p.common().lossless() != (codec == 0x163))
        throw new AssertionError("ASF mutable property fields");
      System.out.println("asf-properties\tset-codec-" + codec + "\t" + asfFields(p));
    }
    p.setEncrypted(false);
    if (p.isEncrypted()) throw new AssertionError("ASF clear encryption");
  }

  private static void detailedAsfPropertiesCheck(Path path, AudioMetadata original) throws Exception {
    if (original.format() != Format.ASF) return;
    byte[] data = Files.readAllBytes(path);
    for (var style : ReadStyle.values()) {
      var p = new cz.skyba.taglib.asf.Properties(path, style);
      var stream = new ByteVectorStream(data);
      var borrowed = new cz.skyba.taglib.asf.Properties(stream, style);
      String fields = asfFields(p);
      stream.data().clear();
      if (!fields.equals(asfFields(borrowed)) || !p.common().equals(original.audioProperties())
        || !stream.isOpen() || !fields.equals(asfFields(new cz.skyba.taglib.asf.Properties(path)))
        || !fields.equals(asfFields(new cz.skyba.taglib.asf.Properties(new ByteVectorStream(data)))))
        throw new AssertionError("ASF detailed/default/borrowed/snapshot properties");
      System.out.println(path.getFileName() + "\tdetailed-asf-" + style + "\t" + fields
        + "\t" + stream.tell());
    }
  }

  private static String aiffFields(cz.skyba.taglib.riff.aiff.Properties p) {
    return p.common() + ":" + p.lengthInMilliseconds() + ":" + p.lengthInSeconds()
      + ":" + p.bitrate() + ":" + p.sampleRate() + ":" + p.channels() + ":" + p.bitsPerSample()
      + ":" + p.sampleFrames() + ":" + p.isAiffC() + ":"
      + HexFormat.of().formatHex(p.compressionType().toByteArray()) + ":"
      + HexFormat.of().formatHex(p.compressionName().getBytes(StandardCharsets.UTF_8));
  }

  private static void detailedAiffPropertiesCheck(Path path, AudioMetadata original) throws Exception {
    if (original.format() != Format.AIFF) return;
    byte[] data = Files.readAllBytes(path);
    for (var style : ReadStyle.values()) {
      var p = new cz.skyba.taglib.riff.aiff.Properties(path, style);
      var stream = new ByteVectorStream(data);
      var borrowed = new cz.skyba.taglib.riff.aiff.Properties(stream, style);
      String fields = aiffFields(p);
      stream.data().clear();
      borrowed.compressionType().clear();
      if (!fields.equals(aiffFields(borrowed)) || !p.common().equals(original.audioProperties())
        || !stream.isOpen() || !fields.equals(aiffFields(new cz.skyba.taglib.riff.aiff.Properties(path)))
        || !fields.equals(aiffFields(new cz.skyba.taglib.riff.aiff.Properties(new ByteVectorStream(data)))))
        throw new AssertionError("AIFF detailed/default/borrowed/snapshot properties");
      System.out.println(path.getFileName() + "\tdetailed-aiff-" + style + "\t" + fields
        + "\t" + stream.tell());
    }
  }

  private static String dsdTtaFields(Object properties) {
    AudioProperties common;
    long ms, seconds;
    int bitrate, rate, channels, bits;
    String fields;
    if (properties instanceof cz.skyba.taglib.dsf.Properties p) {
      common = p.common();
      ms = p.lengthInMilliseconds();
      seconds = p.lengthInSeconds();
      bitrate = p.bitrate();
      rate = p.sampleRate();
      channels = p.channels();
      bits = p.bitsPerSample();
      fields = p.formatVersion() + ":" + p.formatID() + ":" + p.channelType()
        + ":" + p.sampleCount() + ":" + p.blockSizePerChannel();
    } else if (properties instanceof cz.skyba.taglib.dsdiff.Properties p) {
      common = p.common();
      ms = p.lengthInMilliseconds();
      seconds = p.lengthInSeconds();
      bitrate = p.bitrate();
      rate = p.sampleRate();
      channels = p.channels();
      bits = p.bitsPerSample();
      fields = "" + p.sampleCount();
    } else {
      var p = (cz.skyba.taglib.trueaudio.Properties) properties;
      common = p.common();
      ms = p.lengthInMilliseconds();
      seconds = p.lengthInSeconds();
      bitrate = p.bitrate();
      rate = p.sampleRate();
      channels = p.channels();
      bits = p.bitsPerSample();
      fields = p.ttaVersion() + ":" + p.sampleFrames();
    }
    if (!common.equals(new AudioProperties(ms, bitrate, rate, channels, bits, common.lossless()))
      || seconds != ms / 1000) throw new AssertionError("DSD/TrueAudio getter projection");
    return common + ":" + seconds + ":" + fields;
  }

  private static Object dsdTtaProperties(Object source, Format format, ReadStyle style,
                                         boolean defaults) throws Exception {
    return switch (format) {
      case DSF -> source instanceof Path p
        ? defaults ? new cz.skyba.taglib.dsf.Properties(p) : new cz.skyba.taglib.dsf.Properties(p, style)
        : defaults ? new cz.skyba.taglib.dsf.Properties((ByteVectorStream) source)
        : new cz.skyba.taglib.dsf.Properties((ByteVectorStream) source, style);
      case DSDIFF -> source instanceof Path p
        ? defaults ? new cz.skyba.taglib.dsdiff.Properties(p) : new cz.skyba.taglib.dsdiff.Properties(p, style)
        : defaults ? new cz.skyba.taglib.dsdiff.Properties((ByteVectorStream) source)
        : new cz.skyba.taglib.dsdiff.Properties((ByteVectorStream) source, style);
      case TRUEAUDIO -> source instanceof Path p
        ? defaults ? new cz.skyba.taglib.trueaudio.Properties(p) : new cz.skyba.taglib.trueaudio.Properties(p, style)
        : defaults ? new cz.skyba.taglib.trueaudio.Properties((ByteVectorStream) source)
        : new cz.skyba.taglib.trueaudio.Properties((ByteVectorStream) source, style);
      default -> throw new AssertionError("DSD/TrueAudio dispatch");
    };
  }

  private static void dsdTtaDataPropertiesCheck() {
    for (var style : ReadStyle.values()) {
      var fmt = new ByteVector(ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(1).putInt(0).putInt(2).putInt(2).putInt(2822400).putInt(1)
        .putLong(2822400).putInt(4096).putInt(0).array());
      var dsf = new cz.skyba.taglib.dsf.Properties(fmt, style);
      fmt.clear();
      if (dsf.sampleCount() != 2822400 || dsf.formatVersion() != 1 || dsf.formatID() != 0
        || dsf.channelType() != 2 || dsf.blockSizePerChannel() != 4096 || dsf.bitrate() != 5645)
        throw new AssertionError("DSF data constructor");
      var dff = new cz.skyba.taglib.dsdiff.Properties(2822400, 2, 2822400, 5644, style);
      if (dff.sampleCount() != 2822400 || dff.lengthInMilliseconds() != 1000)
        throw new AssertionError("DSDIFF data constructor");
      var raw = new ByteVector(ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN)
        .put(new byte[]{'T', 'T', 'A', '1'}).putShort((short) 1).putShort((short) 2)
        .putShort((short) 16).putInt(44100).putInt(162496).array());
      var tta = new cz.skyba.taglib.trueaudio.Properties(raw, 79517, style);
      var defaults = new cz.skyba.taglib.trueaudio.Properties(raw, 79517);
      raw.clear();
      if (tta.ttaVersion() != 1 || tta.sampleFrames() != 162496 || tta.lengthInMilliseconds() != 3685
        || tta.bitrate() != 173 || !dsdTtaFields(tta).equals(dsdTtaFields(defaults)))
        throw new AssertionError("TrueAudio data constructor");
      dsdTtaFields(dsf);
      dsdTtaFields(dff);
    }
  }

  private static void detailedDsdTtaPropertiesCheck(Path path, AudioMetadata original) throws Exception {
    Format format = original.format();
    if (format != Format.DSF && format != Format.DSDIFF && format != Format.TRUEAUDIO) return;
    byte[] data = Files.readAllBytes(path);
    for (var style : ReadStyle.values()) {
      var p = dsdTtaProperties(path, format, style, false);
      var stream = new ByteVectorStream(data);
      var borrowed = dsdTtaProperties(stream, format, style, false);
      String fields = dsdTtaFields(p);
      stream.data().clear();
      if (!fields.equals(dsdTtaFields(borrowed)) || !stream.isOpen()
        || !fields.equals(dsdTtaFields(dsdTtaProperties(path, format, style, true)))
        || !fields.equals(dsdTtaFields(dsdTtaProperties(new ByteVectorStream(data), format, style, true))))
        throw new AssertionError("DSD/TrueAudio detailed/default/borrowed/snapshot");
      AudioProperties common = p instanceof cz.skyba.taglib.dsf.Properties v ? v.common()
        : p instanceof cz.skyba.taglib.dsdiff.Properties v ? v.common()
        : ((cz.skyba.taglib.trueaudio.Properties) p).common();
      if (!common.equals(original.audioProperties())) throw new AssertionError("DSD/TrueAudio common projection");
      System.out.println(path.getFileName() + "\tdetailed-dsd-tta-" + style + "\t" + fields
        + "\t" + stream.tell());
    }
  }

  private static final class OggPropertyFile extends OggFile {
    OggPropertyFile(ByteVectorStream stream, Format format) {
      super(stream);
      if (format == Format.OGG_VORBIS) {
        selectStream(new ByteVector("\u0001vorbis"));
        packet(1);
      } else {
        packet(0);
        packet(1);
      }
    }

    public cz.skyba.taglib.TagBase tag() {
      return null;
    }

    public AudioProperties audioProperties() {
      return null;
    }
  }

  private static String oggFields(cz.skyba.taglib.ogg.opus.Properties p) {
    return p.common() + ":" + p.lengthInMilliseconds() + ":" + p.lengthInSeconds()
      + ":" + p.bitrate() + ":" + p.sampleRate() + ":" + p.channels()
      + ":" + p.opusVersion() + ":" + p.inputSampleRate() + ":" + p.outputGain();
  }

  private static String oggFields(cz.skyba.taglib.ogg.vorbis.Properties p) {
    return p.common() + ":" + p.lengthInMilliseconds() + ":" + p.lengthInSeconds()
      + ":" + p.bitrate() + ":" + p.sampleRate() + ":" + p.channels()
      + ":" + p.vorbisVersion() + ":" + p.bitrateMaximum() + ":" + p.bitrateNominal()
      + ":" + p.bitrateMinimum();
  }

  private static String oggFields(cz.skyba.taglib.ogg.speex.Properties p) {
    return p.common() + ":" + p.lengthInMilliseconds() + ":" + p.lengthInSeconds()
      + ":" + p.bitrate() + ":" + p.sampleRate() + ":" + p.channels()
      + ":" + p.speexVersion() + ":" + p.bitrateNominal();
  }

  private static void detailedOggPropertiesCheck(Path path, AudioMetadata original) throws Exception {
    if (original.format() != Format.OGG_OPUS && original.format() != Format.OGG_VORBIS
      && original.format() != Format.OGG_SPEEX) return;
    byte[] data = Files.readAllBytes(path);
    for (var style : ReadStyle.values()) {
      var stream = new ByteVectorStream(data);
      stream.seek(7);
      var indexedStream = new ByteVectorStream(data);
      indexedStream.seek(7);
      try (var file = new OggPropertyFile(indexedStream, original.format())) {
        String fields;
        AudioProperties common;
        switch (original.format()) {
          case OGG_OPUS -> {
            var p = new cz.skyba.taglib.ogg.opus.Properties(path, style);
            var borrowed = new cz.skyba.taglib.ogg.opus.Properties(stream, style);
            var indexed = new cz.skyba.taglib.ogg.opus.Properties(file, style);
            fields = oggFields(p);
            common = p.common();
            stream.data().clear();
            indexedStream.data().clear();
            if (!fields.equals(oggFields(borrowed)) || !fields.equals(oggFields(indexed))
              || !fields.equals(oggFields(new cz.skyba.taglib.ogg.opus.Properties(path)))
              || !fields.equals(oggFields(new cz.skyba.taglib.ogg.opus.Properties(new ByteVectorStream(data)))))
              throw new AssertionError("Opus detailed/default/existing/snapshot properties");
            var defaultFile = new OggPropertyFile(new ByteVectorStream(data), original.format());
            if (!fields.equals(oggFields(new cz.skyba.taglib.ogg.opus.Properties(defaultFile))))
              throw new AssertionError("Opus default existing properties");
          }
          case OGG_VORBIS -> {
            var p = new cz.skyba.taglib.ogg.vorbis.Properties(path, style);
            var borrowed = new cz.skyba.taglib.ogg.vorbis.Properties(stream, style);
            var indexed = new cz.skyba.taglib.ogg.vorbis.Properties(file, style);
            fields = oggFields(p);
            common = p.common();
            stream.data().clear();
            indexedStream.data().clear();
            if (!fields.equals(oggFields(borrowed)) || !fields.equals(oggFields(indexed))
              || !fields.equals(oggFields(new cz.skyba.taglib.ogg.vorbis.Properties(path)))
              || !fields.equals(oggFields(new cz.skyba.taglib.ogg.vorbis.Properties(new ByteVectorStream(data)))))
              throw new AssertionError("Vorbis detailed/default/existing/snapshot properties");
            var defaultFile = new OggPropertyFile(new ByteVectorStream(data), original.format());
            if (!fields.equals(oggFields(new cz.skyba.taglib.ogg.vorbis.Properties(defaultFile))))
              throw new AssertionError("Vorbis default existing properties");
          }
          case OGG_SPEEX -> {
            var p = new cz.skyba.taglib.ogg.speex.Properties(path, style);
            var borrowed = new cz.skyba.taglib.ogg.speex.Properties(stream, style);
            var indexed = new cz.skyba.taglib.ogg.speex.Properties(file, style);
            fields = oggFields(p);
            common = p.common();
            stream.data().clear();
            indexedStream.data().clear();
            if (!fields.equals(oggFields(borrowed)) || !fields.equals(oggFields(indexed))
              || !fields.equals(oggFields(new cz.skyba.taglib.ogg.speex.Properties(path)))
              || !fields.equals(oggFields(new cz.skyba.taglib.ogg.speex.Properties(new ByteVectorStream(data)))))
              throw new AssertionError("Speex detailed/default/existing/snapshot properties");
            var defaultFile = new OggPropertyFile(new ByteVectorStream(data), original.format());
            if (!fields.equals(oggFields(new cz.skyba.taglib.ogg.speex.Properties(defaultFile))))
              throw new AssertionError("Speex default existing properties");
          }
          default -> throw new AssertionError("Ogg property dispatch");
        }
        if (!common.equals(original.audioProperties()) || !stream.isOpen() || !indexedStream.isOpen())
          throw new AssertionError("Ogg common projection/borrowed lifetime");
        System.out.println(path.getFileName() + "\tdetailed-ogg-" + style + "\t" + fields
          + "\t" + stream.tell() + "\t" + indexedStream.tell());
      }
    }
  }

  private static void detailedMpegPropertiesCheck(Path path, AudioMetadata original) throws Exception {
    if (original.format() != Format.MPEG) return;
    for (var style : ReadStyle.values()) {
      var p = new cz.skyba.taglib.mpeg.Properties(path, style);
      var stream = new ByteVectorStream(Files.readAllBytes(path));
      stream.seek(7);
      var borrowed = new cz.skyba.taglib.mpeg.Properties(stream, style);
      if (!p.common().equals(borrowed.common()) || !stream.isOpen()
        || !p.common().equals(TagLib.read(path,
        new ReadOptions(true, style, ReadOptions.DEFAULT.maxMetadataBytes())).audioProperties()))
        throw new AssertionError("MPEG detailed/common/borrowed projection");
      var x = p.xingHeader();
      System.out.println(path.getFileName() + "\tdetailed-mpeg-" + style + "\t" + p.common()
        + "\t" + p.lengthInSeconds() + "\t" + p.version() + "\t" + p.layer()
        + "\t" + p.protectionEnabled() + "\t" + p.channelMode() + "\t" + p.channelConfiguration()
        + "\t" + p.isADTS() + "\t" + p.isCopyrighted() + "\t" + p.isOriginal()
        + "\t" + (x == null ? "null" : x.type() + ":" + x.totalFrames() + ":" + x.totalSize())
        + "\t" + stream.tell());
      stream.data().clear();
      if (!p.common().equals(borrowed.common())) throw new AssertionError("MPEG snapshot lifetime");
    }
    if (!new cz.skyba.taglib.mpeg.Properties(path).common().equals(original.audioProperties()))
      throw new AssertionError("MPEG default style");
    var defaultStream = new ByteVectorStream(Files.readAllBytes(path));
    if (!new cz.skyba.taglib.mpeg.Properties(defaultStream).common().equals(original.audioProperties()))
      throw new AssertionError("MPEG default borrowed style");
  }

  private static void id3HeaderHelpersCheck() {
    var data = new ByteVector(java.util.HexFormat.of().parseHex("4944338007f000000063"));
    var h = new cz.skyba.taglib.id3.v2.Header(data);
    if (h.majorVersion() != 0xffff_ff80L || h.revisionNumber() != 7 || h.tagSize() != 99
      || h.completeTagSize() != 119 || !h.unsynchronisation() || !h.extendedHeader()
      || !h.experimentalIndicator() || !h.footerPresent()) throw new AssertionError("ID3 header state");
    var footer = new cz.skyba.taglib.id3.v2.Footer().render(h);
    if (!footer.equals(new ByteVector(java.util.HexFormat.of().parseHex("33444980002000000063")))
      || h.unsynchronisation() || h.extendedHeader() || h.footerPresent() || h.revisionNumber() != 7)
      throw new AssertionError("ID3 footer render state");
    h.setTagSize(-1);
    h.setMajorVersion(3);
    if (h.tagSize() != 0xffff_ffffL || h.completeTagSize() != 9 || h.majorVersion() != 3
      || h.render().at(3) != 3 || cz.skyba.taglib.id3.v2.Header.size() != 10
      || cz.skyba.taglib.id3.v2.Footer.size() != 10 || !cz.skyba.taglib.id3.v2.Header.fileIdentifier().equals(new ByteVector("ID3")))
      throw new AssertionError("ID3 header setters");
    h.setData(data);
    h.setData(new ByteVector());
    if (h.tagSize() != 99) throw new AssertionError("ID3 short header");
    var ext = new cz.skyba.taglib.id3.v2.ExtendedHeader();
    ext.setData(new ByteVector(new byte[]{(byte) 128, 1}));
    if (ext.size() != 0x8001_0000L || cz.skyba.taglib.id3.v2.SynchData.toUnsignedInt(new ByteVector(new byte[]{1, 1})) != 129
      || !cz.skyba.taglib.id3.v2.SynchData.fromUnsignedInt(128).equals(new ByteVector(new byte[]{0, 0, 1, 0}))
      || !cz.skyba.taglib.id3.v2.SynchData.decode(new ByteVector(new byte[]{(byte) 255, 0, 0})).equals(new ByteVector(new byte[]{(byte) 255, 0})))
      throw new AssertionError("ID3 extended/synch helpers");
  }

  private static void id3MutableFramesCheck() {
    var unknown = new cz.skyba.taglib.id3.v2.UnknownFrame(
      new ByteVector(java.util.HexFormat.of().parseHex("58303031000000030000616263")));
    var live = unknown.header();
    var list = unknown.toStringList();
    list.set(0, "detached");
    list.add("added");
    if (!unknown.data().equals(new ByteVector("abc")) || !unknown.toString().isEmpty()
      || !unknown.toStringList().equals(List.of(""))
      || unknown.size() != 3 || unknown.headerSize() != 10) throw new AssertionError("Mutable unknown fields");
    live.setVersion(3);
    live.setFrameSize(-1);
    live.setTagAlterPreservation(true);
    if (!unknown.render().equals(new ByteVector(java.util.HexFormat.of().parseHex("58303031000000030000616263")))
      || unknown.header() != live || unknown.size() != 3 || !live.tagAlterPreservation())
      throw new AssertionError("Mutable frame render/header ownership");
    unknown.setData(new ByteVector(java.util.HexFormat.of().parseHex("4e4558540000000100007a")));
    if (live.version() != 4 || !unknown.frameID().equals(new ByteVector("NEXT"))
      || !unknown.data().equals(new ByteVector("z"))) throw new AssertionError("Mutable frame reparse");
    class Custom extends cz.skyba.taglib.id3.v2.Frame {
      private ByteVector fields = new ByteVector();

      Custom() {
        super(new ByteVector("X001"));
      }

      public String toString() {
        return "custom";
      }

      public void setText(String text) {
        fields = ByteVector.fromText(text, UTF_8);
      }

      protected void parseFields(ByteVector data) {
        fields = new ByteVector(data);
      }

      protected ByteVector renderFields() {
        return new ByteVector(fields);
      }

      PropertyMap projected() {
        return asProperties();
      }

      String field(ByteVector data, int[] position) {
        return readStringField(data, UTF_16, position);
      }

      Charset encoding() {
        return checkTextEncoding(List.of("Ž"), ISO_8859_1);
      }
    }
    var custom = new Custom();
    cz.skyba.taglib.id3.v2.Frame base = custom;
    base.setText("new");
    int[] position = {0};
    if (!base.render().equals(new ByteVector(java.util.HexFormat.of().parseHex("583030310000000300006e6577")))
      || !custom.projected().unsupportedData().equals(List.of("X001"))
      || !custom.field(new ByteVector(java.util.HexFormat.of().parseHex("fffe41000000")), position).equals("A")
      || position[0] != 6 || custom.encoding() != UTF_8
      || !cz.skyba.taglib.id3.v2.Frame.keyToFrameID("title").equals(new ByteVector("TIT2"))
      || !cz.skyba.taglib.id3.v2.Frame.frameIDToKey(new ByteVector("TYER")).equals("DATE"))
      throw new AssertionError("Custom mutable Frame hooks");
  }

  private static void id3MutableTextFramesCheck() {
    var little = new cz.skyba.taglib.id3.v2.TextIdentificationFrame(
      new ByteVector(HexFormat.of().parseHex("54434f4d00000007000004410000004200")));
    if (!little.fieldList().equals(List.of("A", "B"))
      || little.textEncoding() != UTF_16LE
      || !little.asProperties().get("COMPOSER").equals(List.of("A", "B")))
      throw new AssertionError("Mutable UTF16LE text fields");
    little.fieldList().clear();
    little.toStringList().clear();
    var live = little.header();
    little.setText("Ž");
    little.setTextEncoding(ISO_8859_1);
    little.render();
    if (live != little.header() || little.textEncoding() != ISO_8859_1)
      throw new AssertionError("Mutable text encoding/render state");
    var user = new cz.skyba.taglib.id3.v2.UserTextIdentificationFrame();
    if (!user.fieldList().equals(List.of("", "", ""))) throw new AssertionError("Native TXXX default");
    user.setDescription("MusicBrainz Album ID");
    user.setValues(List.of("one", "two"));
    if (!user.asProperties().get("MUSICBRAINZ_ALBUMID").equals(List.of("one", "two"))
      || cz.skyba.taglib.id3.v2.UserTextIdentificationFrame.find(List.of(user), "MusicBrainz Album ID") != user)
      throw new AssertionError("Mutable TXXX properties/lookup");
    user.setText(List.of("base-description", "base-value"));
    user.setText("virtual-value");
    var copied = new cz.skyba.taglib.id3.v2.UserTextIdentificationFrame(user.render());
    if (!copied.fieldList().equals(List.of("base-description", "virtual-value")))
      throw new AssertionError("Native hidden list setter");
    var comment = new cz.skyba.taglib.id3.v2.CommentsFrame();
    comment.setDescription("source");
    comment.setText("雪");
    comment.setLanguage(new ByteVector("eng"));
    var parsedComment = new cz.skyba.taglib.id3.v2.CommentsFrame(comment.render());
    if (!parsedComment.text().equals("雪") || !parsedComment.description().equals("source")
      || !parsedComment.language().equals(new ByteVector("eng")) || !parsedComment.asProperties().first("COMMENT:SOURCE").equals("雪"))
      throw new AssertionError("Mutable COMM rendering");
    var lyrics = new cz.skyba.taglib.id3.v2.UnsynchronizedLyricsFrame();
    lyrics.setDescription("verse");
    lyrics.setText("words");
    lyrics.setLanguage(new ByteVector("fra"));
    if (!new cz.skyba.taglib.id3.v2.UnsynchronizedLyricsFrame(lyrics.render()).asProperties().first("LYRICS:VERSE").equals("words"))
      throw new AssertionError("Mutable USLT rendering");
    var url = new cz.skyba.taglib.id3.v2.UrlLinkFrame(new ByteVector("WOAF"));
    url.setText("https://example.test");
    var userUrl = new cz.skyba.taglib.id3.v2.UserUrlLinkFrame();
    userUrl.setDescription("source");
    userUrl.setUrl(url.url());
    if (!new cz.skyba.taglib.id3.v2.UserUrlLinkFrame(userUrl.render()).asProperties().first("URL:SOURCE").equals(url.url()))
      throw new AssertionError("Mutable URL rendering");
    var identifier = new cz.skyba.taglib.id3.v2.UniqueFileIdentifierFrame("http://musicbrainz.org", new ByteVector("id"));
    var identifierCopy = new cz.skyba.taglib.id3.v2.UniqueFileIdentifierFrame(identifier.render());
    identifierCopy.identifier().clear();
    if (!identifierCopy.asProperties().first("MUSICBRAINZ_TRACKID").equals("id"))
      throw new AssertionError("Mutable UFID ownership");
    var privateFrame = new cz.skyba.taglib.id3.v2.PrivateFrame();
    privateFrame.setOwner("owner");
    privateFrame.setPrivateData(new ByteVector("data"));
    cz.skyba.taglib.id3.v2.Frame privateBase = privateFrame;
    privateBase.setData(privateFrame.render());
    privateFrame.data().clear();
    if (!privateFrame.owner().equals("owner") || !privateFrame.data().equals(new ByteVector("data")))
      throw new AssertionError("Native PRIV hidden payload setter");
    var podcast = new cz.skyba.taglib.id3.v2.PodcastFrame();
    podcast.setData(podcast.render());
    if (!podcast.asProperties().containsKey("PODCAST") || !podcast.asProperties().get("PODCAST").isEmpty())
      throw new AssertionError("Mutable PCST marker");
    var people = cz.skyba.taglib.id3.v2.TextIdentificationFrame.createTIPLFrame(PropertyMap.builder().put("ARRANGER", List.of("one", "two")).build());
    var instruments = cz.skyba.taglib.id3.v2.TextIdentificationFrame.createTMCLFrame(PropertyMap.builder().put("PERFORMER:guitar", "three").build());
    if (!people.asProperties().get("ARRANGER").equals(List.of("one", "two"))
      || !instruments.asProperties().first("PERFORMER:GUITAR").equals("three")
      || !new cz.skyba.taglib.id3.v2.Latin1StringHandler().parse(new ByteVector(new byte[]{(byte) 233})).equals("é"))
      throw new AssertionError("Mutable role/decoder helpers");
  }

  private static void id3MutablePictureObjectFramesCheck() {
    var picture = new cz.skyba.taglib.id3.v2.AttachedPictureFrame();
    picture.setMimeType("image/png");
    picture.setDescription("雪");
    picture.setType(cz.skyba.taglib.id3.v2.AttachedPictureFrame.Type.FrontCover);
    picture.setPicture(new ByteVector(new byte[]{0, 1, (byte) 255, 0}));
    var h = picture.header();
    var rendered = picture.render();
    picture.picture().clear();
    picture.toStringList().clear();
    var parsed = new cz.skyba.taglib.id3.v2.AttachedPictureFrame(rendered);
    if (h != picture.header() || picture.textEncoding() != ISO_8859_1
      || !parsed.description().equals("雪") || !parsed.mimeType().equals("image/png")
      || parsed.type() != cz.skyba.taglib.id3.v2.AttachedPictureFrame.Type.FrontCover
      || !parsed.picture().equals(new ByteVector(new byte[]{0, 1, (byte) 255, 0}))
      || !parsed.toStringList().equals(List.of("雪", "image/png")))
      throw new AssertionError("Mutable APIC encoding/header/payload ownership");
    var object = new cz.skyba.taglib.id3.v2.GeneralEncapsulatedObjectFrame();
    object.setMimeType("application/octet-stream");
    object.setFileName("name😀");
    object.setDescription("description");
    object.setObject(new ByteVector(new byte[]{0, 2, (byte) 255, 0}));
    var objectHeader = object.header();
    var objectBytes = object.render();
    object.object().clear();
    object.toStringList().clear();
    var parsedObject = new cz.skyba.taglib.id3.v2.GeneralEncapsulatedObjectFrame(objectBytes);
    if (objectHeader != object.header() || object.textEncoding() != ISO_8859_1
      || !parsedObject.fileName().equals("name😀") || !parsedObject.description().equals("description")
      || !parsedObject.object().equals(new ByteVector(new byte[]{0, 2, (byte) 255, 0})))
      throw new AssertionError("Mutable GEOB field/payload ownership");
    if (cz.skyba.taglib.id3.v2.AttachedPictureFrame.typeFromUInt(0x1_0000_0003L) != cz.skyba.taglib.id3.v2.AttachedPictureFrame.Type.FrontCover
      || cz.skyba.taglib.flac.Picture.typeFromUInt(0x1_0000_0003L) != cz.skyba.taglib.flac.Picture.Type.FrontCover
      || cz.skyba.taglib.id3.v2.AttachedPictureFrame.typeFromByte((byte) 255) != cz.skyba.taglib.id3.v2.AttachedPictureFrame.Type.Other
      || !cz.skyba.taglib.id3.v2.AttachedPictureFrame.typeToString(cz.skyba.taglib.id3.v2.AttachedPictureFrame.Type.FrontCover).equals("Front Cover")
      || cz.skyba.taglib.id3.v2.AttachedPictureFrame.typeFromString("Front Cover") != cz.skyba.taglib.id3.v2.AttachedPictureFrame.Type.FrontCover)
      throw new AssertionError("Native APIC/FLAC picture type helpers");
    picture.setData(rendered);
    object.setData(objectBytes);
    if (!picture.description().equals("雪") || !object.fileName().equals("name😀"))
      throw new AssertionError("Mutable object/picture reparse");
  }

  private static void id3MutableNumericFramesCheck() {
    var ownership = new cz.skyba.taglib.id3.v2.OwnershipFrame();
    ownership.setPricePaid("GBP1.99");
    ownership.setDatePurchased("20120905");
    ownership.setSeller("Ž");
    var header = ownership.header();
    var parsed = new cz.skyba.taglib.id3.v2.OwnershipFrame(ownership.render());
    ownership.toStringList().clear();
    if (header != ownership.header() || !parsed.pricePaid().equals("GBP1.99")
      || !parsed.datePurchased().equals("20120905") || !parsed.seller().equals("Ž")
      || ownership.textEncoding() != ISO_8859_1)
      throw new AssertionError("Mutable OWNE encoding/field ownership");
    var rating = new cz.skyba.taglib.id3.v2.PopularimeterFrame();
    rating.setEmail("email");
    rating.setRating(-257);
    rating.setCounter(0x1_ffff_ffffL);
    var parsedRating = new cz.skyba.taglib.id3.v2.PopularimeterFrame(rating.render());
    if (rating.rating() != -257 || rating.counter() != 0xffff_ffffL || parsedRating.rating() != 255
      || parsedRating.counter() != 0xffff_ffffL || !rating.toStringList().equals(List.of("email", "-257", "-1")))
      throw new AssertionError("Mutable POPM unsigned state/signed projection");
    var volume = new cz.skyba.taglib.id3.v2.RelativeVolumeFrame();
    var channel = cz.skyba.taglib.id3.v2.RelativeVolumeFrame.ChannelType.FrontRight;
    volume.setIdentification("ident");
    volume.setVolumeAdjustment(15.0f / 512, channel);
    var peak = new cz.skyba.taglib.id3.v2.RelativeVolumeFrame.PeakVolume(8, new ByteVector(new byte[]{69}));
    volume.setPeakVolume(peak, channel);
    peak.peakVolume().clear();
    volume.channels().clear();
    volume.peakVolume(channel).peakVolume().clear();
    var parsedVolume = new cz.skyba.taglib.id3.v2.RelativeVolumeFrame(volume.render());
    if (!parsedVolume.identification().equals("ident") || parsedVolume.volumeAdjustmentIndex(channel) != 15
      || parsedVolume.volumeAdjustment(channel) != 15.0f / 512 || !parsedVolume.channels().equals(List.of(channel))
      || parsedVolume.peakVolume(channel).bitsRepresentingPeak() != 8
      || !parsedVolume.peakVolume(channel).peakVolume().equals(new ByteVector(new byte[]{69})))
      throw new AssertionError("Mutable RVA2 peak/channel ownership");
    var before = volume.render();
    try {
      volume.setVolumeAdjustment(Float.NaN);
      throw new AssertionError("undefined adjustment accepted");
    } catch (IllegalArgumentException expected) {
      if (!before.equals(volume.render())) throw new AssertionError("failed conversion changed state");
    }
    volume.setVolumeAdjustmentIndex(65535);
    if (volume.volumeAdjustmentIndex() != -1 || volume.peakVolume().bitsRepresentingPeak() != 0)
      throw new AssertionError("Mutable RVA2 default channel/narrowing");
  }

  private static void id3MutableTimedFramesCheck() {
    var events = new cz.skyba.taglib.id3.v2.EventTimingCodesFrame();
    var event = new cz.skyba.taglib.id3.v2.EventTimingCodesFrame.SynchedEvent(0x1_0000_0003L,
      cz.skyba.taglib.id3.v2.EventTimingCodesFrame.EventType.fromByte((byte) 255));
    events.setSynchedEvents(List.of(event));
    events.synchedEvents().set(0, new cz.skyba.taglib.id3.v2.EventTimingCodesFrame.SynchedEvent(77,
      cz.skyba.taglib.id3.v2.EventTimingCodesFrame.EventType.Padding));
    var eventHeader = events.header();
    var parsedEvents = new cz.skyba.taglib.id3.v2.EventTimingCodesFrame(events.render());
    if (eventHeader != events.header() || parsedEvents.synchedEvents().size() != 1
      || parsedEvents.synchedEvents().getFirst().time() != 3 || parsedEvents.synchedEvents().getFirst().type().code() != 255
      || cz.skyba.taglib.id3.v2.EventTimingCodesFrame.EventType.IntroStart.code() != 2)
      throw new AssertionError("Mutable ETCO enum/value ownership");
    var lyrics = new cz.skyba.taglib.id3.v2.SynchronizedLyricsFrame();
    lyrics.setLanguage(new ByteVector("eng-more"));
    lyrics.setDescription("description");
    var entry = new cz.skyba.taglib.id3.v2.SynchronizedLyricsFrame.SynchedText(0x1_0000_0003L, "雪");
    lyrics.setSynchedText(List.of(entry));
    lyrics.synchedText().set(0, new cz.skyba.taglib.id3.v2.SynchronizedLyricsFrame.SynchedText(99, "detached"));
    lyrics.language().clear();
    var lyricsHeader = lyrics.header();
    var parsedLyrics = new cz.skyba.taglib.id3.v2.SynchronizedLyricsFrame(lyrics.render());
    if (lyricsHeader != lyrics.header() || lyrics.textEncoding() != ISO_8859_1
      || !parsedLyrics.language().equals(new ByteVector("eng")) || !parsedLyrics.description().equals("description")
      || parsedLyrics.synchedText().getFirst().time() != 3 || !parsedLyrics.synchedText().getFirst().text().equals("雪")
      || parsedLyrics.timestampFormat() != cz.skyba.taglib.id3.v2.SynchronizedLyricsFrame.TimestampFormat.AbsoluteMilliseconds
      || parsedLyrics.type() != cz.skyba.taglib.id3.v2.SynchronizedLyricsFrame.Type.Lyrics)
      throw new AssertionError("Mutable SYLT encoding/entry ownership");
    events.setData(events.render());
    lyrics.setData(lyrics.render());
    if (events.synchedEvents().getFirst().type().code() != 255 || !lyrics.synchedText().getFirst().text().equals("雪"))
      throw new AssertionError("Mutable timed-frame serialized reparse");
  }

  private static ByteVector dsdiffChunk(String id, ByteVector data) {
    return new ByteVector(id).append(ByteVector.fromUnsignedLong(data.size(), true)).append(data)
      .append(new ByteVector(data.size() & 1));
  }

  private static void dsdiffReadBoundaryCheck() throws IOException {
    var prop = new ByteVector("xxxx").append(dsdiffChunk("FS  ", new ByteVector()))
      .append(dsdiffChunk("CHNL", new ByteVector(new byte[]{0, 2})));
    var data = dsdiffChunk("FRM8", new ByteVector("DSD ").append(dsdiffChunk("PROP", prop))
      .append(dsdiffChunk("DSD ", new ByteVector(128))));
    var stream = new ByteVectorStream(data);
    var details = new cz.skyba.taglib.dsdiff.Properties(stream);
    if (details.sampleRate() != 1128812108 || details.channels() != 2 || details.sampleCount() != 512)
      throw new AssertionError("DSDIFF marker/short scalar native reads");
    stream.seek(0);
    try (var ref = new cz.skyba.taglib.AudioFile(stream)) {
      if (!ref.isOpen() || ref.audioProperties().sampleRate() != 1128812108)
        throw new AssertionError("DSDIFF marker/short scalar FileRef");
    }
    if (!stream.isOpen()) throw new AssertionError("Borrowed DSDIFF stream");
  }

  private static void liveAsfFileCheck(Path corpus) throws IOException {
    var descriptorStream = new ByteVectorStream(Files.readAllBytes(corpus.resolve("silence-1.wma")));
    long descriptorOffset = descriptorStream.length();
    descriptorStream.seek(descriptorOffset);
    descriptorStream.writeBlock(new ByteVector(new byte[]{2, 0, 'N', 0, 3, 0, 0, 0, 1, 0, 0, (byte) 128}));
    descriptorStream.seek(0);
    try (var descriptorFile = new AsfFile(descriptorStream, ReadOptions.TAGS_ONLY)) {
      descriptorFile.seek(descriptorOffset);
      var parsed = new cz.skyba.taglib.asf.Attribute("retained");
      if (!parsed.parse(descriptorFile).equals("N") || parsed.toUnsignedInt() != 0x80000001L
        || !parsed.toString().equals("retained") || descriptorFile.tell() != descriptorOffset + 12)
        throw new AssertionError("ASF direct Attribute parser/state/cursor");
    }
    var stream = new ByteVectorStream(Files.readAllBytes(corpus.resolve("silence-1.wma")));
    var picture = new cz.skyba.taglib.asf.Picture();
    picture.setMimeType("image/png");
    picture.setDescription("ASF consumer");
    picture.setType(cz.skyba.taglib.asf.Picture.Type.FrontCover);
    picture.setPicture(new ByteVector("PNG"));
    var attribute = new cz.skyba.taglib.asf.Attribute(picture);
    var copy = new cz.skyba.taglib.asf.Attribute(attribute);
    copy.setStream(32);
    copy.setLanguage(56);
    copy.toPicture().setDescription("edited ASF picture");
    if (attribute.stream() != 0 || attribute.language() != 0
      || !picture.description().equals("ASF consumer")
      || !attribute.toPicture().description().equals("ASF consumer")
      || new cz.skyba.taglib.asf.Attribute(-1L).dataSize() != 5
      || !cz.skyba.taglib.asf.Picture.typeFromByte((byte) 255).equals(cz.skyba.taglib.asf.Picture.Type.Other))
      throw new AssertionError("ASF independent copies/sizes/type helpers");
    try (var ref = new cz.skyba.taglib.AudioFile(stream, ReadOptions.TAGS_ONLY)) {
      if (!ref.isOpen() || !(ref.file() instanceof AsfFile file)
        || ref.tag() != file.tag() || file.detailedProperties() == null)
        throw new AssertionError("ASF live FileRef/ignored read-properties flag");
      var tag = file.tag();
      var properties = file.detailedProperties();
      tag.setTitle("ASF live consumer");
      tag.setRating("rating");
      tag.setCopyright("copyright");
      tag.setAttribute("WM/Picture", copy);
      tag.attribute("WM/Picture").getFirst().toPicture().setDescription("live ASF picture");
      tag.addAttribute("Custom/UInt", new cz.skyba.taglib.asf.Attribute(-1));
      properties.setChannels(7);
      ref.save();
      if (ref.audioProperties().channels() != 7 || ref.tag() != tag
        || file.detailedProperties() != properties || !tag.complexPropertyKeys().equals(List.of("PICTURE")))
        throw new AssertionError("ASF live mutation/save/complex/identity");
    }
    stream.seek(0);
    try (var file = new AsfFile(stream)) {
      var saved = file.tag().attribute("WM/Picture").getFirst();
      if (!file.tag().title().equals("ASF live consumer") || !file.tag().rating().equals("rating")
        || !file.tag().copyright().equals("copyright") || saved.stream() != 32 || saved.language() != 56
        || !saved.toPicture().description().equals("live ASF picture")
        || file.tag().attribute("Custom/UInt").getFirst().toUnsignedInt() != 0xffffffffL)
        throw new AssertionError("ASF scoped picture/unsigned attribute reopen");
    }
    if (!stream.isOpen()) throw new AssertionError("Borrowed ASF stream");
  }

  private static void liveMpegFileCheck(Path corpus) throws IOException {
    var original = new ByteVector(Files.readAllBytes(corpus.resolve("xing.mp3")));
    var stream = new ByteVectorStream(original);
    class Factory extends cz.skyba.taglib.id3.v2.FrameFactory {
      int calls;

      Factory() {
        setDefaultTextEncoding(UTF_8);
      }

      public cz.skyba.taglib.id3.v2.Frame createFrame(ByteVector data, cz.skyba.taglib.id3.v2.Header header) {
        calls++;
        return super.createFrame(data, header);
      }
    }
    var factory = new Factory();
    try (var file = new MpegFile(stream, new ReadOptions(true, ReadStyle.ACCURATE, ReadOptions.DEFAULT.maxMetadataBytes()), factory)) {
      var union = file.tag();
      var properties = file.detailedProperties();
      if (properties == null || new cz.skyba.taglib.mpeg.Properties(file).sampleRate() != properties.sampleRate()
        || file.firstFrameOffset() < 0 || file.lastFrameOffset() < file.firstFrameOffset())
        throw new AssertionError("MPEG live owner/properties/navigation");
      file.id3v2Tag().setTitle("MPEG modern");
      file.id3v1Tag().setTitle("MPEG legacy");
      file.getOrCreateApeTag().setTitle("MPEG APE");
      file.save(new MpegSaveOptions(Set.of(MpegFile.TagType.values()), FileBase.StripTags.STRIP_OTHERS,
        Version.V3, FileBase.DuplicateTags.DO_NOT_DUPLICATE));
      if (union != file.tag() || properties != file.detailedProperties())
        throw new AssertionError("MPEG mask/version/duplication/identity");
    }
    try (var file = new MpegFile(stream, new ReadOptions(false, ReadStyle.FAST, ReadOptions.DEFAULT.maxMetadataBytes()), factory)) {
      if (factory.calls == 0 || file.id3v2Tag().header().majorVersion() != 3
        || !file.hasId3v2Tag() || !file.hasId3v1Tag() || !file.hasApeTag()
        || !file.id3v1Tag().title().equals("MPEG legacy") || !file.apeTag().title().equals("MPEG APE"))
        throw new AssertionError("MPEG retained factory/children");
      var modern = file.id3v2Tag();
      file.strip(Set.of(MpegFile.TagType.ID3V2), false);
      if (file.hasId3v2Tag() || modern != file.id3v2Tag())
        throw new AssertionError("MPEG immediate strip/retained child");
      file.save();
      if (modern != file.id3v2Tag())
        throw new AssertionError("MPEG immediate strip/retained child/default save");
      file.strip();
      if (!stream.data().equals(original))
        throw new AssertionError("MPEG exact original restoration");
    }
    try (var reference = new cz.skyba.taglib.AudioFile(stream, ReadOptions.TAGS_ONLY)) {
      if (!reference.isOpen() || !(reference.file() instanceof MpegFile)
        || reference.tag() != reference.file().tag()) throw new AssertionError("MPEG FileRef owner");
    }
    if (!stream.isOpen()) throw new AssertionError("Borrowed MPEG stream");
  }

  private static void liveDsdiffFileCheck(Path corpus) throws IOException {
    class Factory extends cz.skyba.taglib.id3.v2.FrameFactory {
      int calls;

      Factory() {
        setDefaultTextEncoding(UTF_8);
      }

      public cz.skyba.taglib.id3.v2.Frame createFrame(ByteVector data, cz.skyba.taglib.id3.v2.Header header) {
        calls++;
        return super.createFrame(data, header);
      }
    }
    var factory = new Factory();
    var stream = new ByteVectorStream(Files.readAllBytes(corpus.resolve("empty10ms.dff")));
    var original = new ByteVector(stream.data());
    try (var file = new DsdiffFile(stream, new ReadOptions(true, ReadStyle.FAST, ReadOptions.DEFAULT.maxMetadataBytes()), factory)) {
      var union = file.tag();
      var modern = file.id3v2Tag();
      var diin = file.diinTag();
      var properties = file.detailedProperties();
      modern.setTitle("DSDIFF live modern");
      diin.setTitle("DSDIFF live DIIN");
      file.save(new DsdiffSaveOptions(Set.of(DsdiffFile.TagType.values()), FileBase.StripTags.STRIP_OTHERS,
        Version.V3));
      if (!file.hasId3v2Tag() || !file.hasDiinTag()
        || union != file.tag() || modern != file.id3v2Tag() || diin != file.diinTag()
        || properties != file.detailedProperties() || properties.sampleCount() != 28224)
        throw new AssertionError("DSDIFF live child/versioned save");
    }
    stream.seek(0);
    try (var file = new DsdiffFile(stream, new ReadOptions(false, ReadStyle.ACCURATE, ReadOptions.DEFAULT.maxMetadataBytes()), factory)) {
      if (factory.calls != 1 || file.detailedProperties() != null
        || !file.id3v2Tag().title().equals("DSDIFF live modern")
        || !file.diinTag().title().equals("DSDIFF live DIIN"))
        throw new AssertionError("DSDIFF retained factory/children");
      file.strip(Set.of(DsdiffFile.TagType.ID3V2));
      if (file.hasId3v2Tag() || !file.hasDiinTag() || !file.tag().title().equals("DSDIFF live DIIN"))
        throw new AssertionError("DSDIFF immediate selective strip");
      file.strip();
      if (!stream.data().equals(original)) throw new AssertionError("DSDIFF complete restoration");
    }
    stream.seek(0);
    try (var ref = new cz.skyba.taglib.AudioFile(stream)) {
      if (!(ref.file() instanceof DsdiffFile file) || ref.tag() != file.tag())
        throw new AssertionError("DSDIFF actual FileRef dispatch");
      file.replaceProperties(PropertyMap.builder().put("TITLE", "FileRef DSDIFF").build());
      ref.save();
      file.strip();
    }
    if (!stream.isOpen() || !stream.data().equals(original))
      throw new AssertionError("DSDIFF borrowed lifetime/media preservation");
  }

  private static void liveDiinTagCheck() {
    var tag = new cz.skyba.taglib.dsdiff.diin.Tag();
    if (!tag.isEmpty() || tag.properties().size() != 2) throw new AssertionError("DIIN empty export");
    var input = PropertyMap.builder().put("TITLE", List.of("first", "second"))
      .put("ARTIST", "artist").put("ALBUM", "rejected").build();
    var rejected = tag.replaceProperties(input).rejected();
    tag.setAlbum("ignored");
    tag.setComment("ignored");
    tag.setGenre("ignored");
    tag.setYear(0xffff_ffffL);
    tag.setTrack(0x8000_0000L);
    if (!tag.title().equals("first") || !tag.artist().equals("artist") || !tag.album().isEmpty()
      || tag.year() != 0 || !rejected.get("TITLE").equals(List.of("second"))
      || !rejected.first("ALBUM").equals("rejected")) throw new AssertionError("DIIN import");
    tag.replaceProperties(PropertyMap.EMPTY);
    if (!tag.isEmpty()) throw new AssertionError("DIIN clear");
  }

  private static void liveTrueAudioFileCheck(Path corpus) throws IOException {
    class Factory extends cz.skyba.taglib.id3.v2.FrameFactory {
      int calls;

      public cz.skyba.taglib.id3.v2.Frame createFrame(ByteVector data, cz.skyba.taglib.id3.v2.Header header) {
        calls++;
        return super.createFrame(data, header);
      }
    }
    var factory = new Factory();
    factory.setDefaultTextEncoding(UTF_8);
    var stream = new ByteVectorStream(Files.readAllBytes(corpus.resolve("empty.tta")));
    try (var file = new TrueAudioFile(stream, new ReadOptions(false, ReadStyle.FAST, ReadOptions.DEFAULT.maxMetadataBytes()), factory)) {
      if (file.detailedProperties() != null) throw new AssertionError("TrueAudio readProperties");
      var union = file.tag();
      var modern = file.id3v2Tag();
      modern.setTitle("TrueAudio owner");
      file.getOrCreateId3v1Tag().setTitle("legacy owner");
      var frame = modern.frameList().getFirst();
      if (((cz.skyba.taglib.id3.v2.TextIdentificationFrame) frame).textEncoding() != UTF_8)
        throw new AssertionError("TrueAudio custom factory encoding");
      file.save();
      file.save();
      if (union != file.tag() || modern != file.id3v2Tag()
        || frame != modern.frameList().getFirst() || !file.hasId3v2Tag() || !file.hasId3v1Tag())
        throw new AssertionError("TrueAudio live children and repeated saves");
    }
    stream.seek(0);
    try (var file = new TrueAudioFile(stream, new ReadOptions(true, ReadStyle.ACCURATE, ReadOptions.DEFAULT.maxMetadataBytes()), factory)) {
      if (factory.calls != 1 || file.detailedProperties().sampleFrames() != 162496)
        throw new AssertionError("TrueAudio retained factory/properties");
      file.removeTags(Set.of(TrueAudioFile.TagType.ID3V2));
      if (file.id3v2Tag() != null || !file.properties().first("TITLE").equals("legacy owner")
        || !file.hasId3v2Tag()) throw new AssertionError("TrueAudio child/physical flags");
      file.removeTags();
      if (file.id3v2Tag() == null) throw new AssertionError("TrueAudio empty child after removal");
      file.save();
      if (file.hasId3v2Tag() || file.hasId3v1Tag())
        throw new AssertionError("TrueAudio strip and save");
    }
    if (!stream.isOpen()) throw new AssertionError("TrueAudio borrowed lifetime");
    try (var file = new TrueAudioFile(stream, ReadOptions.TAGS_ONLY, null)) {
      if (!file.id3v2Tag().isEmpty()) throw new AssertionError("TrueAudio default factory empty paths");
      file.save();
      file.id3v2Tag().setTitle("default factory");
      file.save();
    }
    try (var file = new TrueAudioFile(stream)) {
      if (!file.id3v2Tag().title().equals("default factory")) throw new AssertionError("TrueAudio default factory read-back");
    }
  }

  private static void liveDsfFileCheck(Path corpus) throws IOException {
    class Factory extends cz.skyba.taglib.id3.v2.FrameFactory {
      int calls;

      public cz.skyba.taglib.id3.v2.Frame createFrame(ByteVector data, cz.skyba.taglib.id3.v2.Header header) {
        calls++;
        return super.createFrame(data, header);
      }
    }
    var factory = new Factory();
    factory.setDefaultTextEncoding(UTF_8);
    var stream = new ByteVectorStream(Files.readAllBytes(corpus.resolve("empty10ms.dsf")));
    try (var file = new DsfFile(stream, new ReadOptions(false, ReadStyle.FAST, ReadOptions.DEFAULT.maxMetadataBytes()), factory)) {
      if (file.detailedProperties() == null || file.detailedProperties().sampleCount() != 28224)
        throw new AssertionError("DSF native ignored readProperties");
      var tag = file.tag();
      var properties = file.detailedProperties();
      tag.setTitle("DSF live owner");
      var frame = tag.frameList().getFirst();
      if (((cz.skyba.taglib.id3.v2.TextIdentificationFrame) frame).textEncoding() != UTF_8)
        throw new AssertionError("DSF custom factory encoding");
      file.save(Version.V3);
      file.save();
      if (tag != file.tag() || properties != file.detailedProperties() || frame != tag.frameList().getFirst())
        throw new AssertionError("DSF live versioned saves and custom factory");
    }
    stream.seek(0);
    try (var file = new DsfFile(stream, new ReadOptions(true, ReadStyle.ACCURATE, ReadOptions.DEFAULT.maxMetadataBytes()), factory)) {
      if (factory.calls != 1 || !file.tag().title().equals("DSF live owner"))
        throw new AssertionError("DSF constructor custom factory propagation");
      file.tag().frameList().getFirst().setText("changed child");
      if (!file.properties().first("TITLE").equals("changed child"))
        throw new AssertionError("DSF authoritative live frame owner");
      file.save();
    }
    if (!stream.isOpen()) throw new AssertionError("DSF borrowed lifetime");
  }

  private static void id3MutableOwnerCheck() {
    var tag = new cz.skyba.taglib.id3.v2.Tag();
    var list = tag.frameList();
    var missing = tag.frameList(new ByteVector("TIT2"));
    tag.setTitle("owner title");
    var bucket = tag.frameList(new ByteVector("TIT2"));
    if (!missing.isEmpty() || list.size() != 1 || bucket.getFirst() != list.getFirst()
      || !tag.title().equals("owner title")) throw new AssertionError("live ID3v2 owner");
    var child = new cz.skyba.taglib.id3.v2.TextIdentificationFrame(new ByteVector("TIT2"), ISO_8859_1);
    child.setText("chapter title");
    var chapter = new cz.skyba.taglib.id3.v2.ChapterFrame(new ByteVector("ch"), 3, 5, -1, -1, List.of(child));
    var toc = new cz.skyba.taglib.id3.v2.TableOfContentsFrame(new ByteVector("toc"),
      List.of(new ByteVector("ch")));
    toc.setIsTopLevel(true);
    toc.setIsOrdered(true);
    tag.addFrame(chapter);
    tag.addFrame(toc);
    if (cz.skyba.taglib.id3.v2.ChapterFrame.findByElementID(tag, new ByteVector("ch")) != chapter
      || cz.skyba.taglib.id3.v2.TableOfContentsFrame.findTopLevel(tag) != toc)
      throw new AssertionError("ID3v2 nested finders");
    var wire = tag.render();
    var stream = new ByteVectorStream(wire);
    var file = new FileBase(stream) {
      public cz.skyba.taglib.TagBase tag() {
        return null;
      }

      public cz.skyba.taglib.AudioProperties audioProperties() {
        return null;
      }

      public void save() {
        throw new UnsupportedOperationException("Smoke helper has no save implementation");
      }
    };
    class Factory extends cz.skyba.taglib.id3.v2.FrameFactory {
      int calls;

      public cz.skyba.taglib.id3.v2.Frame createFrame(ByteVector data, cz.skyba.taglib.id3.v2.Header header) {
        calls++;
        return super.createFrame(data, header);
      }
    }
    var factory = new Factory();
    factory.setDefaultTextEncoding(UTF_16);
    var parsed = new cz.skyba.taglib.id3.v2.Tag(file, 0, factory);
    if (!parsed.title().equals("owner title") || factory.calls != 3
      || !cz.skyba.taglib.id3.v2.ChapterFrame.findByElementID(parsed, new ByteVector("ch"))
      .embeddedFrameList().getFirst().toString().equals("chapter title"))
      throw new AssertionError("ID3v2 factory/FileBase owner construction");
    parsed.replaceProperties(cz.skyba.taglib.PropertyMap.builder().put("TITLE", "changed").build());
    parsed.setComplexProperties("PICTURE", List.of(Map.of("data", new Variant(new ByteVector("payload")),
      "description", new Variant("picture"), "pictureType", new Variant("Front Cover"))));
    if (parsed.complexProperties("picture").size() != 1 || parsed.render(Version.V3).isEmpty())
      throw new AssertionError("ID3v2 owner properties/complex/render");
    var hooks = new cz.skyba.taglib.id3.v2.Tag();
    class BucketHook extends cz.skyba.taglib.id3.v2.Frame {
      BucketHook(String id) {
        super(new ByteVector(id));
      }

      public String toString() {
        return "";
      }

      protected void parseFields(ByteVector data) {
      }

      protected ByteVector renderFields() {
        return new ByteVector("x");
      }

      protected cz.skyba.taglib.PropertyMap asProperties() {
        hooks.frameList(new ByteVector("ZZZZ"));
        return cz.skyba.taglib.PropertyMap.EMPTY;
      }
    }
    hooks.addFrame(new BucketHook("X001"));
    hooks.addFrame(new BucketHook("Y001"));
    hooks.replaceProperties(cz.skyba.taglib.PropertyMap.EMPTY);
    if (hooks.frameList().size() != 2 || hooks.frameListMap().size() != 2
      || hooks.frameListMap().containsKey(new ByteVector("ZZZZ")))
      throw new AssertionError("ID3v2 reentrant property hook");
    var handler = cz.skyba.taglib.id3.v2.Tag.latin1StringHandler();
    cz.skyba.taglib.id3.v2.Tag.setLatin1StringHandler(new cz.skyba.taglib.id3.v2.Latin1StringHandler() {
      public String parse(ByteVector data) {
        return "callback";
      }
    });
    try {
      if (!cz.skyba.taglib.id3.v2.FrameFactory.instance().createFrame(child.render(), new cz.skyba.taglib.id3.v2.Header())
        .toString().equals("callback")) throw new AssertionError("public ID3v2 handler");
    } finally {
      cz.skyba.taglib.id3.v2.Tag.setLatin1StringHandler(handler);
    }
  }

  private static void mpegHeaderCheck() {
    var stream = new ByteVectorStream(new byte[]{(byte) 255, (byte) 250, (byte) 146, 12});
    var file = new FileBase(stream) {
      public cz.skyba.taglib.TagBase tag() {
        return null;
      }

      public cz.skyba.taglib.AudioProperties audioProperties() {
        return null;
      }

      public void save() {
        throw new UnsupportedOperationException("Smoke helper has no save implementation");
      }
    };
    var header = new cz.skyba.taglib.mpeg.Header(file, 0, false);
    if (!header.isValid() || header.version() != cz.skyba.taglib.mpeg.Header.Version.Version1
      || header.layer() != 3 || !header.protectionEnabled() || header.bitrate() != 128
      || header.sampleRate() != 44100 || !header.isPadded() || !header.isCopyrighted()
      || !header.isOriginal() || header.frameLength() != 418 || header.samplesPerFrame() != 1152
      || header.channelMode() != cz.skyba.taglib.mpeg.Header.ChannelMode.Stereo
      || header.channelConfiguration() != cz.skyba.taglib.mpeg.Header.ChannelConfiguration.Custom
      || header.isADTS() || new cz.skyba.taglib.mpeg.Header(file, 0).isValid())
      throw new AssertionError("MPEG public header");
    var copy = new cz.skyba.taglib.mpeg.Header(header);
    stream.data().setData(new byte[]{(byte) 255, (byte) 241, 81, (byte) 228, 1, (byte) 224});
    var adts = new cz.skyba.taglib.mpeg.Header(file, 0, false);
    if (!adts.isValid() || !adts.isADTS() || adts.version() != cz.skyba.taglib.mpeg.Header.Version.Version4
      || adts.channelConfiguration().ordinal() != 7 || adts.frameLength() != 15
      || copy.bitrate() != 128)
      throw new AssertionError("ADTS public header/copy");
    var xing = new cz.skyba.taglib.mpeg.XingHeader(new ByteVector(
      java.util.HexFormat.of().parseHex("58696e6700000003ffffffff80000000")));
    if (!xing.isValid() || xing.type() != cz.skyba.taglib.mpeg.XingHeader.HeaderType.Xing
      || xing.totalFrames() != 0xffff_ffffL || xing.totalSize() != 0x8000_0000L)
      throw new AssertionError("Xing unsigned totals");
    var vbri = new cz.skyba.taglib.mpeg.XingHeader(new ByteVector(
      java.util.HexFormat.of().parseHex("564252490001000000000000007b0000002a")).resize(32));
    if (!vbri.isValid() || vbri.type() != cz.skyba.taglib.mpeg.XingHeader.HeaderType.VBRI
      || vbri.totalFrames() != 42 || vbri.totalSize() != 123)
      throw new AssertionError("VBRI totals");
  }

  private static void id3v1Check() {
    var tag = new cz.skyba.taglib.id3.v1.Tag();
    tag.setArtist("Artist     ");
    tag.setGenre("Hardcore");
    tag.setYear(0xffff_ffffL);
    tag.setTrack(255);
    var stream = new ByteVectorStream(tag.render());
    var file = new FileBase(stream) {
      public cz.skyba.taglib.TagBase tag() {
        return null;
      }

      public cz.skyba.taglib.AudioProperties audioProperties() {
        return null;
      }

      public void save() {
        throw new UnsupportedOperationException("Smoke helper has no save implementation");
      }
    };
    var parsed = new cz.skyba.taglib.id3.v1.Tag(file, 0);
    if (!parsed.artist().equals("Artist") || parsed.year() != 0xffff_ffffL
      || parsed.track() != 255 || parsed.genreNumber() != 129 || stream.tell() != 128
      || !cz.skyba.taglib.id3.v1.Genres.genreList().contains("Heavy Metal")
      || cz.skyba.taglib.id3.v1.Genres.genreMap().get("Hard Rock") != 79)
      throw new AssertionError("ID3v1 public tag/genre operations");
    cz.skyba.taglib.id3.v1.Tag.setStringHandler(new cz.skyba.taglib.id3.v1.StringHandler() {
      public String parse(ByteVector bytes) {
        return bytes.decodeTerminatedText(UTF_8);
      }

      public ByteVector render(String value) {
        return ByteVector.fromText(value, UTF_8);
      }
    });
    try {
      tag.setTitle("日本語");
      if (!new cz.skyba.taglib.id3.v1.Tag(tag.render()).title().equals("日本語"))
        throw new AssertionError("ID3v1 string handler");
    } finally {
      cz.skyba.taglib.id3.v1.Tag.setStringHandler(null);
    }
    if (!new cz.skyba.taglib.id3.v1.Tag(tag.render()).title().isEmpty())
      throw new AssertionError("ID3v1 default handler reset");
  }

  private static void riffInfoCheck() {
    var vector = new cz.skyba.taglib.toolkit.ByteVector("abc");
    if (!new cz.skyba.taglib.toolkit.ByteVector(vector, 1, 2)
      .equals(new cz.skyba.taglib.toolkit.ByteVector("bc")))
      throw new AssertionError("ByteVector view");
    var parts =
      List.of(vector, new cz.skyba.taglib.toolkit.ByteVector("d"));
    if (!ByteVector.join(parts).toString().equals("abc d"))
      throw new AssertionError("Toolkit defaults");
    var tag = new cz.skyba.taglib.riff.info.Tag();
    var id = new cz.skyba.taglib.toolkit.ByteVector(new byte[]{'Z', 'Z', 'Z', 'Z'});
    tag.setFieldText(id, "opaque");
    if (tag.isEmpty() || !tag.properties().unsupportedData().equals(List.of("")))
      throw new AssertionError("Unknown INFO field state");
    tag.setTitle("Žluťoučký");
    tag.setYear(0xffff_ffffL);
    var parsed = new cz.skyba.taglib.riff.info.Tag(tag.render());
    if (!parsed.title().equals(tag.title()) || parsed.year() != 0xffff_ffffL)
      throw new AssertionError("INFO render/parse");
    parsed.removeUnsupportedProperties(List.of("ZZZZ"));
    if (!parsed.properties().unsupportedData().isEmpty())
      throw new AssertionError("INFO raw removal");
    cz.skyba.taglib.riff.info.Tag.setStringHandler(
      new cz.skyba.taglib.riff.info.StringHandler() {
        @Override
        public cz.skyba.taglib.toolkit.ByteVector render(String text) {
          return ByteVector.fromText(text, ISO_8859_1);
        }

        @Override
        public String parse(cz.skyba.taglib.toolkit.ByteVector bytes) {
          return bytes.decodeTerminatedText(ISO_8859_1);
        }
      });
    try {
      var local = new cz.skyba.taglib.riff.info.Tag();
      local.setTitle("é");
      if (!new cz.skyba.taglib.riff.info.Tag(local.render()).title().equals("é"))
        throw new AssertionError("INFO handler");
    } finally {
      cz.skyba.taglib.riff.info.Tag.setStringHandler(null);
    }
  }

  private static void toolkitCheck(Path path) throws Exception {
    var pages =
      cz.skyba.taglib.ogg.Page.paginate(
        List.of(new cz.skyba.taglib.toolkit.ByteVector(new byte[9000])),
        cz.skyba.taglib.ogg.Page.PaginationStrategy.Repaginate,
        123,
        0,
        false,
        true,
        true);
    for (var page : pages) {
      var bytes = page.render();
      var parsed = new cz.skyba.taglib.ogg.Page(new cz.skyba.taglib.toolkit.ByteVectorStream(bytes), 0);
      if (!parsed.header().isValid() || !parsed.render().equals(bytes))
        throw new AssertionError("Ogg page round trip failed");
      parsed.setFirstPacketIndex(7);
      parsed.containsPacket(7);
      parsed.packets();
    }
    var memory = new cz.skyba.taglib.toolkit.ByteVectorStream(new byte[]{1, 2, 3, 4});
    memory.insert(new cz.skyba.taglib.toolkit.ByteVector(new byte[]{8, 9, 10}), 1, 1);
    memory.removeBlock(2, 1);
    Files.write(path, new byte[]{1, 2, 3, 4});
    try (var file = new cz.skyba.taglib.toolkit.FileStream(path)) {
      file.insert(new cz.skyba.taglib.toolkit.ByteVector(new byte[]{8, 9, 10}), 1, 1);
      file.removeBlock(2, 1);
      file.seek(0);
      if (!memory.data().equals(file.readBlock(file.length())))
        throw new AssertionError("Toolkit stream bytes differ");
    }
    var map = new cz.skyba.taglib.toolkit.MutablePropertyMap();
    map.getOrCreate("title").add("日本語");
    map.add("TITLE", List.of("second"));
    if (!map.values("title").equals(List.of("日本語", "second")))
      throw new AssertionError("Mutable properties failed");
    var value =
      new cz.skyba.taglib.toolkit.Variant(new cz.skyba.taglib.toolkit.ByteVector(new byte[]{0, 1, 2}));
    if (!value.equals(
      new cz.skyba.taglib.toolkit.Variant(new cz.skyba.taglib.toolkit.ByteVector(new byte[]{0, 1, 2}))))
      throw new AssertionError("Variant failed");
    String text = "日本語";
    if (!ByteVector.fromText(text, UTF_8).decodeTerminatedText(UTF_8).equals(text))
      throw new AssertionError("Toolkit string encoding failed");
    var extended =
      new cz.skyba.taglib.toolkit.ByteVector(HexFormat.of().parseHex("3fff8000000000000001"))
        .toFloat80BE(0);
    if (extended.toBigDecimal().compareTo(java.math.BigDecimal.ONE) <= 0
      || extended.doubleValue() != 1.0) throw new AssertionError("Exact Float80 failed");
    var ape = new cz.skyba.taglib.ape.Tag();
    ape.setTitle("日本語");
    ape.setData("BINARY", new cz.skyba.taglib.toolkit.ByteVector(new byte[]{1, 0, 3}));
    if (!ape.properties().unsupportedData().equals(List.of("BINARY")) || !ape.title().equals("日本語"))
      throw new AssertionError("Mutable APE API failed");
    var footer = new cz.skyba.taglib.ape.Footer(ape.render().mid(ape.render().size() - 32));
    if (footer.itemCount() != 2 || footer.version() != 2000)
      throw new AssertionError("APE footer failed");
    System.out.println("# toolkit PASS " + HexFormat.of().formatHex(Files.readAllBytes(path)));
  }

  private static void keyedEditorCheck(Path path) throws Exception {
    // Synthetic ISO-BMFF metadata fixture: UTF-16BE title, UInt64 counter, float gain,
    // nonzero locales and a known mdat. It deliberately has no decodable audio track.
    Files.write(
      path,
      Base64.getDecoder()
        .decode(
          "AAAAEGZ0eXBNNEEgAAAAAAAAAN9tb292AAAA13VkdGEAAADPbWV0YQAAAAAAAAAhaGRscgAAAAAAAAAAbWR0YQAAAAAAAAAAAAAAAAAAAAA4a2V5cwAAAAAAAAADAAAADW1kdGF0aXRsZQAAAA9tZHRhY291bnRlcgAAAAxtZHRhZ2FpbgAAAGppbHN0AAAAJgAAAAEAAAAeZGF0YQAAAAIAAAApAEQAZQB1AHQAcwBjAGgAAAAgAAAAAgAAABhkYXRhAAAATgAAAAf//////////wAAABwAAAADAAAAFGRhdGEAAAAXAAAACT+gAAAAAAAXbWRhdHVuY2hhbmdlZCBtZWRpYQ=="));
    var originalPayload = PayloadHash.of(path);
    if (originalPayload == null || originalPayload.bytes() == 0)
      throw new AssertionError("Missing synthetic mdat");
    Path identityLink = path.resolveSibling(path.getFileName() + ".hardlink");
    Files.deleteIfExists(identityLink);
    boolean identitySupported = true;
    try {
      Files.createLink(identityLink, path);
    } catch (UnsupportedOperationException unsupported) {
      identitySupported = false;
      System.out.println("# keyed-editor-default identity UNSUPPORTED");
    }
    var document = MetadataDocument.open(path);
    var changes = new java.util.ArrayList<MetadataChange>();
    for (var entry : document.entries()) {
      String value =
        switch (entry.key()) {
          case "mdta:title" -> "日本語";
          case "mdta:counter" -> "18446744073709551614";
          case "mdta:gain" -> "-2.5";
          default -> throw new AssertionError("Unexpected synthetic entry: " + entry.key());
        };
      changes.add(new MetadataChange("update", entry.id(), entry.key(), List.of(value), false));
    }
    if (changes.size() != 3) throw new AssertionError("Incomplete synthetic metadata");
    document.apply(changes);
    document.save();
    if (identitySupported) {
      boolean expectedSameFile = true;
      if (!Files.isSameFile(path, identityLink))
        throw new AssertionError("Unexpected file identity for default");
      System.out.println("# keyed-editor-default identity PASS");
      Files.delete(identityLink);
    }
    var updated = MetadataDocument.open(path);
    var values = new TreeMap<String, List<String>>();
    for (var entry : updated.entries()) values.put(entry.key(), entry.values());
    if (!values.equals(
      java.util.Map.of(
        "mdta:title",
        List.of("日本語"),
        "mdta:counter",
        List.of("18446744073709551614"),
        "mdta:gain",
        List.of("-2.5"))))
      throw new AssertionError("Typed metadata did not round-trip: " + values);
    byte[] output = Files.readAllBytes(path);
    requireContains(output, mp4Value(2, 41, "日本語".getBytes(StandardCharsets.UTF_16BE)));
    requireContains(output, mp4Value(78, 7, new byte[]{-1, -1, -1, -1, -1, -1, -1, -2}));
    requireContains(
      output, mp4Value(23, 9, java.nio.ByteBuffer.allocate(4).putFloat(-2.5f).array()));
    var unchanged = PayloadHash.wholeFile(path);
    var counter =
      updated.entries().stream()
        .filter(e -> e.key().equals("mdta:counter"))
        .findFirst()
        .orElseThrow();
    try {
      updated.apply(
        List.of(
          new MetadataChange(
            "update", counter.id(), counter.key(), List.of("18446744073709551616"), false)));
      throw new AssertionError("Out-of-range UInt64 edit accepted");
    } catch (MetadataEditException expected) {
      /* Validation must reject before touching the file. */
    }
    try {
      updated.save();
      throw new AssertionError("Failed edit session was saved");
    } catch (MetadataEditException expected) {
      /* The session remains invalid. */
    }
    if (!unchanged.equals(PayloadHash.wholeFile(path)))
      throw new AssertionError("Rejected UInt64 edit changed file");
    var custom = MetadataDocument.open(path);
    custom.apply(
      List.of(
        new MetadataChange(
          "add", null, "mdta:PrivateNativeTest", List.of("Custom text"), true)));
    custom.save();
    var remove = MetadataDocument.open(path);
    var privateEntry =
      remove.entries().stream()
        .filter(e -> e.key().equals("mdta:PrivateNativeTest"))
        .findFirst()
        .orElseThrow();
    remove.apply(List.of(new MetadataChange("remove", privateEntry.id(), null, null, false)));
    remove.save();
    var artwork = MetadataDocument.open(path);
    artwork.replacePictures(PICTURES);
    artwork.save();
    var finalDocument = MetadataDocument.open(path);
    if (finalDocument.entries().stream().filter(e -> e.picture() != null).count() != 2)
      throw new AssertionError("Keyed artwork did not round-trip");
    if (!originalPayload.equals(PayloadHash.of(path)))
      throw new AssertionError("Synthetic keyed mdat changed");
    System.out.println(
      "# keyed-editor-"
        + "default"
        + " PASS\t"
        + documentSnapshot(finalDocument)
        + "\t"
        + originalPayload.sha256());
  }

  private static byte[] mp4Value(int type, int locale, byte[] payload) {
    return java.nio.ByteBuffer.allocate(16 + payload.length)
      .putInt(16 + payload.length)
      .put(new byte[]{'d', 'a', 't', 'a'})
      .putInt(type)
      .putInt(locale)
      .put(payload)
      .array();
  }

  private static void requireContains(byte[] all, byte[] expected) {
    outer:
    for (int i = 0; i <= all.length - expected.length; i++) {
      for (int j = 0; j < expected.length; j++) if (all[i + j] != expected[j]) continue outer;
      return;
    }
    throw new AssertionError("MP4 type, locale or encoded value changed");
  }

  private static boolean editPhysical(Path copy, String operation)
    throws Exception {
    var document = MetadataDocument.open(copy);
    switch (operation) {
      case "editor-add" -> document.apply(
        List.of(
          new MetadataChange(
            "add", null, "TITLE", List.of("Native physical title"), false)));
      case "editor-update" -> {
        var entry =
          document.entries().stream()
            .filter(e -> e.editable() && e.kind().equals("text"))
            .findFirst();
        if (entry.isEmpty()) return false;
        var field = entry.orElseThrow();
        document.apply(
          List.of(
            new MetadataChange(
              "update", field.id(), field.key(), List.of("Native physical update"), false)));
      }
      case "editor-remove" -> {
        var entry =
          document.entries().stream()
            .filter(e -> e.removable() && !e.kind().equals("artwork"))
            .findFirst();
        if (entry.isEmpty()) return false;
        document.apply(
          List.of(new MetadataChange("remove", entry.orElseThrow().id(), null, null, false)));
      }
      case "editor-artwork" -> document.replacePictures(PICTURES);
      default -> throw new AssertionError(operation);
    }
    document.save();
    return true;
  }

  private static String documentSnapshot(MetadataDocument document) throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(bytes)) {
      text(out, document.defaultBlock());
      text(out, document.readOnlyReason());
      out.writeBoolean(document.metadataReadFailed());
      out.writeBoolean(document.hasId3v2Tag());
      out.writeInt(document.entries().size());
      for (var entry : document.entries()) {
        for (String value :
          List.of(
            entry.id(),
            entry.block(),
            entry.key(),
            entry.standardKey(),
            entry.kind(),
            entry.details()))
          text(out, value);
        out.writeInt(entry.values().size());
        for (String value : entry.values()) text(out, value);
        for (boolean value :
          new boolean[]{
            entry.editable(),
            entry.removable(),
            entry.renameable(),
            entry.multipleValues(),
            entry.requiresRemoval(),
            entry.projected()
          })
          out.writeBoolean(value);
        out.writeInt(entry.precedence());
        canonical(out, entry.constraints());
        var picture = entry.picture();
        out.writeBoolean(picture != null);
        if (picture != null) {
          out.writeInt(picture.type());
          text(out, picture.mimeType());
          text(out, picture.description());
          out.writeInt(picture.width());
          out.writeInt(picture.height());
          out.writeInt(picture.colorDepth());
          out.writeInt(picture.indexedColors());
          byte[] data = picture.data();
          out.writeInt(data.length);
          out.write(data);
        }
      }
    }
    return sha256(bytes.toByteArray());
  }

  private static void canonical(DataOutputStream out, Object value) throws IOException {
    switch (value) {
      case String string -> {
        out.writeByte(1);
        text(out, string);
      }
      case Boolean bool -> {
        out.writeByte(2);
        out.writeBoolean(bool);
      }
      case Number number -> {
        out.writeByte(3);
        text(out, number.toString());
      }
      case List<?> list -> {
        out.writeByte(4);
        out.writeInt(list.size());
        for (Object item : list) canonical(out, item);
      }
      case java.util.Map<?, ?> map -> {
        out.writeByte(5);
        var sorted = new TreeMap<String, Object>();
        map.forEach((key, item) -> sorted.put((String) key, item));
        out.writeInt(sorted.size());
        for (var entry : sorted.entrySet()) {
          text(out, entry.getKey());
          canonical(out, entry.getValue());
        }
      }
      default -> throw new AssertionError(
        "Unsupported metadata constraint value: " + value.getClass().getName());
    }
  }

  private static String snapshot(AudioMetadata file) throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(bytes)) {
      text(out, file.format().name());
      properties(out, file.properties());
      var tag = file.tag();
      for (String s : List.of(tag.title(), tag.artist(), tag.album(), tag.comment(), tag.genre()))
        text(out, s);
      out.writeLong(tag.year());
      out.writeLong(tag.track());
      var audio = file.audioProperties();
      out.writeLong(audio.lengthInMilliseconds());
      out.writeInt(audio.bitrate());
      out.writeInt(audio.sampleRate());
      out.writeInt(audio.channels());
      out.writeInt(audio.bitsPerSample());
      out.writeBoolean(audio.lossless());
      out.writeInt(file.pictures().size());
      for (Picture picture : file.pictures()) {
        out.writeInt(picture.type());
        text(out, picture.mimeType());
        text(out, picture.description());
        out.writeInt(picture.width());
        out.writeInt(picture.height());
        out.writeInt(picture.colorDepth());
        out.writeInt(picture.indexedColors());
        byte[] data = picture.data();
        out.writeInt(data.length);
        out.write(data);
      }
    }
    return sha256(bytes.toByteArray());
  }

  private static String propertyHash(PropertyMap map) throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(bytes)) {
      properties(out, map);
    }
    return sha256(bytes.toByteArray());
  }

  private static void properties(DataOutputStream out, PropertyMap map) throws IOException {
    out.writeInt(map.asMap().size());
    for (var entry : new TreeMap<>(map.asMap()).entrySet()) {
      text(out, entry.getKey());
      out.writeInt(entry.getValue().size());
      for (String value : entry.getValue()) text(out, value);
    }
  }

  private static void text(DataOutputStream out, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
