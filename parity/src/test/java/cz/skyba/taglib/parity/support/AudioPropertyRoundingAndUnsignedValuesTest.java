package cz.skyba.taglib.parity.support;


import cz.skyba.taglib.ape.ApeFile;
import cz.skyba.taglib.it.ItFile;
import cz.skyba.taglib.matroska.MatroskaFile;
import cz.skyba.taglib.mod.ModFile;
import cz.skyba.taglib.mpc.MpcFile;
import cz.skyba.taglib.parity.support.SaveOutcome;

import cz.skyba.taglib.parity.support.FileRefAdapter;

import cz.skyba.taglib.ReadStyle;
import cz.skyba.taglib.TagLib;
import cz.skyba.taglib.parity.Snapshots;
import cz.skyba.taglib.s3m.S3mFile;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.wavpack.WavPackFile;
import cz.skyba.taglib.xm.XmFile;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Independently retains every assertion from the pinned upstream codec property tests.
 */
class AudioPropertyRoundingAndUnsignedValuesTest {
  private static Path fixture(String name) {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("parity/native")))
      root = root.getParent();
    assertNotNull(root, "Repository root");
    Path path = Snapshots.upstreamSource(root).resolve("tests/data/" + name);
    assertTrue(Files.isRegularFile(path), "Required checksum-verified upstream fixture: " + path);
    return path;
  }

  @Test
  void opusAudioProperties() throws Exception {
    var p = new cz.skyba.taglib.ogg.opus.Properties(fixture("correctness_gain_silent_output.opus"));
    assertNotNull(p);
    assertEquals(7, p.lengthInSeconds());
    assertEquals(7737, p.lengthInMilliseconds());
    assertEquals(36, p.bitrate());
    assertEquals(1, p.channels());
    assertEquals(48000, p.sampleRate());
    assertEquals(48000, p.inputSampleRate());
    assertEquals(1, p.opusVersion());
    assertEquals(-17920, p.outputGain());
  }

  @Test
  void vorbisAudioProperties() throws Exception {
    var p = new cz.skyba.taglib.ogg.vorbis.Properties(fixture("empty.ogg"));
    assertNotNull(p);
    assertEquals(3, p.lengthInSeconds());
    assertEquals(3685, p.lengthInMilliseconds());
    assertEquals(1, p.bitrate());
    assertEquals(2, p.channels());
    assertEquals(44100, p.sampleRate());
    assertEquals(0, p.vorbisVersion());
    assertEquals(0, p.bitrateMaximum());
    assertEquals(112000, p.bitrateNominal());
    assertEquals(0, p.bitrateMinimum());
  }

  @Test
  void speexAudioProperties() throws Exception {
    var p = new cz.skyba.taglib.ogg.speex.Properties(fixture("empty.spx"));
    assertNotNull(p);
    assertEquals(3, p.lengthInSeconds());
    assertEquals(3685, p.lengthInMilliseconds());
    assertEquals(53, p.bitrate());
    assertEquals(-1, p.bitrateNominal());
    assertEquals(2, p.channels());
    assertEquals(44100, p.sampleRate());
  }

  @Test
  void dsfProperties() throws Exception {
    var p = new cz.skyba.taglib.dsf.Properties(fixture("empty10ms.dsf"));
    assertNotNull(p);
    assertEquals(0, p.lengthInSeconds());
    assertEquals(10, p.lengthInMilliseconds());
    assertEquals(5645, p.bitrate());
    assertEquals(2, p.channels());
    assertEquals(2822400, p.sampleRate());
    assertEquals(1, p.formatVersion());
    assertEquals(0, p.formatID());
    assertEquals(2, p.channelType());
    assertEquals(1, p.bitsPerSample());
    assertEquals(28224, p.sampleCount());
    assertEquals(4096, p.blockSizePerChannel());
  }

  @Test
  void dsdiffProperties() throws Exception {
    var p = new cz.skyba.taglib.dsdiff.Properties(fixture("empty10ms.dff"));
    assertNotNull(p);
    assertEquals(0, p.lengthInSeconds());
    assertEquals(10, p.lengthInMilliseconds());
    assertEquals(5644, p.bitrate());
    assertEquals(2, p.channels());
    assertEquals(2822400, p.sampleRate());
    assertEquals(1, p.bitsPerSample());
    assertEquals(28224, p.sampleCount());
  }

  private static void ttaProperties(String name) throws Exception {
    var p = new cz.skyba.taglib.trueaudio.Properties(fixture(name));
    assertNotNull(p);
    assertEquals(3, p.lengthInSeconds());
    assertEquals(3685, p.lengthInMilliseconds());
    assertEquals(173, p.bitrate());
    assertEquals(2, p.channels());
    assertEquals(44100, p.sampleRate());
    assertEquals(16, p.bitsPerSample());
    assertEquals(162496, p.sampleFrames());
    assertEquals(1, p.ttaVersion());
  }

  @Test
  void trueAudioReadPropertiesWithoutId3v2() throws Exception {
    ttaProperties("empty.tta");
  }

  @Test
  void trueAudioReadPropertiesWithTags() throws Exception {
    ttaProperties("tagged.tta");
  }

  @Test
  void aiffProperties() throws Exception {
    var p = new cz.skyba.taglib.riff.aiff.Properties(fixture("empty.aiff"));
    assertNotNull(p);
    assertEquals(0, p.lengthInSeconds());
    assertEquals(67, p.lengthInMilliseconds());
    assertEquals(706, p.bitrate());
    assertEquals(44100, p.sampleRate());
    assertEquals(1, p.channels());
    assertEquals(16, p.bitsPerSample());
    assertEquals(2941, p.sampleFrames());
    assertFalse(p.isAiffC());
  }

  @Test
  void aiffCProperties() throws Exception {
    var p = new cz.skyba.taglib.riff.aiff.Properties(fixture("alaw.aifc"));
    assertNotNull(p);
    assertEquals(0, p.lengthInSeconds());
    assertEquals(37, p.lengthInMilliseconds());
    assertEquals(355, p.bitrate());
    assertEquals(44100, p.sampleRate());
    assertEquals(1, p.channels());
    assertEquals(16, p.bitsPerSample());
    assertEquals(1622, p.sampleFrames());
    assertTrue(p.isAiffC());
    assertEquals(new cz.skyba.taglib.toolkit.ByteVector("ALAW"), p.compressionType());
    assertEquals("SGI CCITT G.711 A-law", p.compressionName());
  }

  @Test
  void asfAudioProperties() throws Exception {
    var p = new cz.skyba.taglib.asf.Properties(fixture("silence-1.wma"));
    assertNotNull(p);
    assertEquals(3, p.lengthInSeconds());
    assertEquals(3712, p.lengthInMilliseconds());
    assertEquals(64, p.bitrate());
    assertEquals(2, p.channels());
    assertEquals(48000, p.sampleRate());
    assertEquals(16, p.bitsPerSample());
    assertEquals(cz.skyba.taglib.asf.Properties.Codec.WMA2, p.codec());
    assertEquals("Windows Media Audio 9.1", p.codecName());
    assertEquals("64 kbps, 48 kHz, stereo 2-pass CBR", p.codecDescription());
    assertFalse(p.isEncrypted());
  }

  @Test
  void asfLosslessProperties() throws Exception {
    var p = new cz.skyba.taglib.asf.Properties(fixture("lossless.wma"));
    assertNotNull(p);
    assertEquals(3, p.lengthInSeconds());
    assertEquals(3549, p.lengthInMilliseconds());
    assertEquals(1152, p.bitrate());
    assertEquals(2, p.channels());
    assertEquals(44100, p.sampleRate());
    assertEquals(16, p.bitsPerSample());
    assertEquals(cz.skyba.taglib.asf.Properties.Codec.WMA9Lossless, p.codec());
    assertEquals("Windows Media Audio 9.2 Lossless", p.codecName());
    assertEquals("VBR Quality 100, 44 kHz, 2 channel 16 bit 1-pass VBR", p.codecDescription());
    assertFalse(p.isEncrypted());
  }

  @Test
  void shortenBasicProperties() throws Exception {
    var p = new cz.skyba.taglib.shorten.Properties(fixture("2sec-silence.shn"));
    assertNotNull(p);
    assertEquals(2, p.lengthInSeconds());
    assertEquals(2000, p.lengthInMilliseconds());
    assertEquals(1411, p.bitrate());
    assertEquals(2, p.channels());
    assertEquals(44100, p.sampleRate());
    assertEquals(2, p.shortenVersion());
    assertEquals(5, p.fileType());
    assertEquals(16, p.bitsPerSample());
    assertEquals(88200, p.sampleFrames());
  }

  private static void mp4Full(
    cz.skyba.taglib.mp4.Properties p,
    int seconds,
    int milliseconds,
    int bitrate,
    cz.skyba.taglib.mp4.Properties.Codec codec) {
    assertNotNull(p);
    assertEquals(seconds, p.lengthInSeconds());
    assertEquals(milliseconds, p.lengthInMilliseconds());
    assertEquals(bitrate, p.bitrate());
    assertEquals(2, p.channels());
    assertEquals(44100, p.sampleRate());
    assertEquals(16, p.bitsPerSample());
    assertFalse(p.isEncrypted());
    assertEquals(codec, p.codec());
  }

  @Test
  void mp4PropertiesAac() throws Exception {
    mp4Full(
      new cz.skyba.taglib.mp4.Properties(fixture("has-tags.m4a")),
      3,
      3708,
      3,
      cz.skyba.taglib.mp4.Properties.Codec.AAC);
  }

  @Test
  void mp4PropertiesAacWithoutBitrate() throws Exception {
    byte[] data = Files.readAllBytes(fixture("has-tags.m4a"));
    assertTrue(data.length > 1960);
    assertArrayEquals(
      "mp4a".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
      java.util.Arrays.copyOfRange(data, 1890, 1894));
    java.util.Arrays.fill(data, 1956, 1960, (byte) 0);
    mp4Full(
      new cz.skyba.taglib.mp4.Properties(new ByteVectorStream(data)),
      3,
      3708,
      3,
      cz.skyba.taglib.mp4.Properties.Codec.AAC);
  }

  @Test
  void mp4PropertiesAlac() throws Exception {
    mp4Full(
      new cz.skyba.taglib.mp4.Properties(fixture("empty_alac.m4a")),
      3,
      3705,
      3,
      cz.skyba.taglib.mp4.Properties.Codec.ALAC);
  }

  @Test
  void mp4PropertiesAlacWithoutBitrate() throws Exception {
    byte[] data = Files.readAllBytes(fixture("empty_alac.m4a"));
    assertTrue(data.length > 474);
    assertArrayEquals(
      "alac".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
      java.util.Arrays.copyOfRange(data, 446, 450));
    java.util.Arrays.fill(data, 470, 474, (byte) 0);
    mp4Full(
      new cz.skyba.taglib.mp4.Properties(new ByteVectorStream(data)),
      3,
      3705,
      2,
      cz.skyba.taglib.mp4.Properties.Codec.ALAC);
  }

  @Test
  void mp4PropertiesAacWithoutLength() throws Exception {
    byte[] data = Files.readAllBytes(fixture("no-tags.m4a"));
    assertEquals(2898, data.length);
    assertArrayEquals(
      "mdhd".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
      java.util.Arrays.copyOfRange(data, 1749, 1753));
    java.util.Arrays.fill(data, 1769, 1773, (byte) 0);
    mp4Full(
      new cz.skyba.taglib.mp4.Properties(new ByteVectorStream(data)),
      3,
      3707,
      3,
      cz.skyba.taglib.mp4.Properties.Codec.AAC);
  }

  @Test
  void mp4PropertiesAc3() throws Exception {
    var p = new cz.skyba.taglib.mp4.Properties(fixture("ac3.m4a"));
    assertNotNull(p);
    assertEquals(1, p.lengthInSeconds());
    assertEquals(2, p.channels());
    assertEquals(48000, p.sampleRate());
    assertTrue(p.bitrate() > 0);
    assertFalse(p.isEncrypted());
    assertEquals(cz.skyba.taglib.mp4.Properties.Codec.AC3, p.codec());
    assertEquals("ac-3", p.codecId());
  }

  @Test
  void mp4PropertiesEac3() throws Exception {
    var p = new cz.skyba.taglib.mp4.Properties(fixture("eac3.m4a"));
    assertNotNull(p);
    assertEquals(1, p.lengthInSeconds());
    assertEquals(2, p.channels());
    assertEquals(48000, p.sampleRate());
    assertEquals(128, p.bitrate());
    assertFalse(p.isEncrypted());
    assertEquals(cz.skyba.taglib.mp4.Properties.Codec.EAC3, p.codec());
    assertEquals("ec-3", p.codecId());
  }

  @Test
  void mp4PropertiesFlac() throws Exception {
    var p = new cz.skyba.taglib.mp4.Properties(fixture("flac.m4a"));
    assertNotNull(p);
    assertEquals(1, p.lengthInSeconds());
    assertEquals(2, p.channels());
    assertEquals(48000, p.sampleRate());
    assertEquals(16, p.bitsPerSample());
    assertTrue(p.bitrate() > 0);
    assertFalse(p.isEncrypted());
    assertEquals(cz.skyba.taglib.mp4.Properties.Codec.FLAC, p.codec());
    assertEquals("fLaC", p.codecId());
  }

  @Test
  void mp4PropertiesFlacHighResolution() throws Exception {
    var p = new cz.skyba.taglib.mp4.Properties(fixture("flac96.m4a"));
    assertNotNull(p);
    assertEquals(1, p.lengthInSeconds());
    assertEquals(2, p.channels());
    assertEquals(96000, p.sampleRate());
    assertEquals(24, p.bitsPerSample());
    assertTrue(p.bitrate() > 0);
    assertFalse(p.isEncrypted());
    assertEquals(cz.skyba.taglib.mp4.Properties.Codec.FLAC, p.codec());
    assertEquals("fLaC", p.codecId());
  }

  @Test
  void mp4PropertiesOpus() throws Exception {
    var p = new cz.skyba.taglib.mp4.Properties(fixture("opus.m4a"));
    assertNotNull(p);
    assertEquals(1, p.lengthInSeconds());
    assertEquals(2, p.channels());
    assertEquals(48000, p.sampleRate());
    assertTrue(p.bitrate() > 0);
    assertFalse(p.isEncrypted());
    assertEquals(cz.skyba.taglib.mp4.Properties.Codec.Opus, p.codec());
    assertEquals("Opus", p.codecId());
  }

  @Test
  void mp4PropertiesM4v() throws Exception {
    mp4Full(
      new cz.skyba.taglib.mp4.Properties(fixture("blank_video.m4v")),
      0,
      975,
      96,
      cz.skyba.taglib.mp4.Properties.Codec.AAC);
  }

  @Test
  void matroskaPropertiesMka() throws Exception {
    try (var owner = new MatroskaFile(fixture("no-tags.mka"))) {
      var p = owner.detailedProperties();
      assertNotNull(p);
      assertEquals(0, p.lengthInSeconds());
      assertEquals(444, p.lengthInMilliseconds());
      assertEquals(223, p.bitrate());
      assertEquals(2, p.channels());
      assertEquals(44100, p.sampleRate());
      assertEquals("matroska", p.docType());
      assertEquals(4, p.docTypeVersion());
      assertEquals("A_MPEG/L3", p.codecName());
      assertEquals("", p.title());
    }
  }

  @Test
  void matroskaPropertiesMkv() throws Exception {
    try (var owner = new MatroskaFile(fixture("tags-before-cues.mkv"))) {
      var p = owner.detailedProperties();
      assertNotNull(p);
      assertEquals(0, p.lengthInSeconds());
      assertEquals(120, p.lengthInMilliseconds());
      assertEquals(227, p.bitrate());
      assertEquals(0, p.channels());
      assertEquals(0, p.sampleRate());
      assertEquals("matroska", p.docType());
      assertEquals(4, p.docTypeVersion());
      assertEquals("", p.codecName());
      assertEquals("handbrake", p.title());
    }
  }

  @Test
  void matroskaPropertiesWebm() throws Exception {
    var path = fixture("no-tags.webm");
    try (var owner = new MatroskaFile(path)) {
      var p = owner.detailedProperties();
      assertNotNull(p);
      assertEquals(0, p.lengthInSeconds());
      assertEquals(1, p.lengthInMilliseconds());
      assertEquals(2816, p.bitrate());
      assertEquals(0, p.channels());
      assertEquals(0, p.sampleRate());
      assertEquals("webm", p.docType());
      assertEquals(4, p.docTypeVersion());
      assertEquals("", p.codecName());
      assertEquals("", p.title());
      try (var noProperties = new cz.skyba.taglib.parity.support.FileRefAdapter(path, false)) {
        assertNull(noProperties.audioProperties());
      }
    }
  }

  private static void trackerCommon(
    Path path, String title, String comment, String tracker, int channels) throws Exception {
    var audio = TagLib.read(path);
    try (var ref = new cz.skyba.taglib.parity.support.FileRefAdapter(path)) {
      assertFalse(ref.isNull());
      assertNotNull(ref.tag());
      assertNotNull(ref.audioProperties());
    }
    var tag = audio.tag();
    assertNotNull(tag);
    assertEquals(title, tag.title());
    assertEquals("", tag.artist());
    assertEquals("", tag.album());
    assertEquals(comment, tag.comment());
    assertEquals("", tag.genre());
    assertEquals(0, tag.year());
    assertEquals(0, tag.track());
    assertEquals(tracker, audio.properties().first("TRACKERNAME"));
    var p = audio.audioProperties();
    assertNotNull(p);
    assertEquals(0, p.lengthInSeconds());
    assertEquals(0, p.bitrate());
    assertEquals(0, p.sampleRate());
    assertEquals(channels, p.channels());
  }

  private static void assertModRead(Path path, String title, String comment) throws Exception {
    trackerCommon(path, title, comment, "StarTrekker", 8);
    try (var file = new ModFile(path)) {
      assertTrue(file.isValid());
      assertNotNull(file.tag());
      assertEquals(title, file.tag().title());
      assertEquals(comment, file.tag().comment());
      var p = file.detailedProperties();
      assertNotNull(p);
      assertEquals(0, p.lengthInSeconds());
      assertEquals(0, p.bitrate());
      assertEquals(0, p.sampleRate());
      assertEquals(31, p.instrumentCount());
      assertEquals(1, p.lengthInPatterns());
      assertEquals(8, p.channels());
    }
  }

  @Test
  void modReadTagsAndProperties() throws Exception {
    assertModRead(
      fixture("test.mod"),
      "title of song",
      "Instrument names\n"
        + "are abused as\n"
        + "comments in\n"
        + "module file formats.\n"
        + "-+-+-+-+-+-+-+-+-+-+-+\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n");
  }

  @Test
  void modWriteTags() throws Exception {
    Path copy = Files.createTempFile("taglib-upstream-", ".mod");
    try {
      Files.copy(fixture("test.mod"), copy, StandardCopyOption.REPLACE_EXISTING);
      try (var file = new ModFile(copy)) {
        assertNotNull(file.tag());
        file.tag().setTitle("changed title");
        file.tag()
          .setComment(
            "This line will be truncated because it is too long for a mod instrument name.\n"
              + "This line is ok.");
        assertTrue(SaveOutcome.save(file));
      }
      assertModRead(
        copy,
        "changed title",
        "This line will be trun\n"
          + "This line is ok.\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n");
      assertEquals(-1, Files.mismatch(fixture("changed.mod"), copy));
    } finally {
      Files.deleteIfExists(copy);
    }
  }

  private static void assertS3mRead(Path path, String title, String comment) throws Exception {
    trackerCommon(path, title, comment, "ScreamTracker III", 16);
    try (var file = new S3mFile(path)) {
      assertTrue(file.isValid());
      assertNotNull(file.tag());
      assertEquals(title, file.tag().title());
      assertEquals(comment, file.tag().comment());
      var p = file.detailedProperties();
      assertNotNull(p);
      assertEquals(0, p.lengthInSeconds());
      assertEquals(0, p.bitrate());
      assertEquals(0, p.sampleRate());
      assertEquals(16, p.channels());
      assertEquals(0, p.lengthInPatterns());
      assertFalse(p.stereo());
      assertEquals(5, p.sampleCount());
      assertEquals(1, p.patternCount());
      assertEquals(0, p.flags());
      assertEquals(4896, p.trackerVersion());
      assertEquals(2, p.fileFormatVersion());
      assertEquals(64, p.globalVolume());
      assertEquals(48, p.masterVolume());
      assertEquals(125, p.tempo());
      assertEquals(6, p.bpmSpeed());
    }
  }

  @Test
  void s3mReadTagsAndProperties() throws Exception {
    assertS3mRead(
      fixture("test.s3m"),
      "test song name",
      "This is an instrument name.\n"
        + "Module file formats\n"
        + "abuse instrument names\n"
        + "as multiline comments.\n"
        + " ");
  }

  @Test
  void s3mWriteTags() throws Exception {
    Path copy = Files.createTempFile("taglib-upstream-", ".s3m");
    try {
      Files.copy(fixture("test.s3m"), copy, StandardCopyOption.REPLACE_EXISTING);
      try (var file = new S3mFile(copy)) {
        assertNotNull(file.tag());
        file.tag().setTitle("changed title");
        file.tag()
          .setComment(
            "This is an instrument name!\n"
              + "Module file formats\n"
              + "abuse instrument names\n"
              + "as multiline comments.\n"
              + "-----------------------------------\n"
              + "This line will be dropped and the previous is truncated.");
        file.tag().setTrackerName("won't be saved");
        assertTrue(SaveOutcome.save(file));
      }
      assertS3mRead(
        copy,
        "changed title",
        "This is an instrument name!\n"
          + "Module file formats\n"
          + "abuse instrument names\n"
          + "as multiline comments.\n"
          + "---------------------------");
      assertEquals(-1, Files.mismatch(fixture("changed.s3m"), copy));
    } finally {
      Files.deleteIfExists(copy);
    }
  }

  private static void assertItRead(Path path, String title, String comment) throws Exception {
    trackerCommon(path, title, comment, "Impulse Tracker", 64);
    try (var file = new ItFile(path)) {
      assertTrue(file.isValid());
      assertNotNull(file.tag());
      assertEquals(title, file.tag().title());
      assertEquals(comment, file.tag().comment());
      var p = file.detailedProperties();
      assertNotNull(p);
      assertEquals(0, p.lengthInSeconds());
      assertEquals(0, p.bitrate());
      assertEquals(0, p.sampleRate());
      assertEquals(64, p.channels());
      assertEquals(0, p.lengthInPatterns());
      assertTrue(p.stereo());
      assertEquals(0, p.instrumentCount());
      assertEquals(5, p.sampleCount());
      assertEquals(1, p.patternCount());
      assertEquals(535, p.version());
      assertEquals(532, p.compatibleVersion());
      assertEquals(9, p.flags());
      assertEquals(128, p.globalVolume());
      assertEquals(48, p.mixVolume());
      assertEquals(125, p.tempo());
      assertEquals(6, p.bpmSpeed());
      assertEquals(128, p.panningSeparation());
      assertEquals(0, p.pitchWheelDepth());
    }
  }

  @Test
  void itReadTagsAndProperties() throws Exception {
    assertItRead(
      fixture("test.it"),
      "test song name",
      "This is a sample name.\n"
        + "In module file formats\n"
        + "sample names are abused\n"
        + "as multiline comments.\n"
        + " ");
  }

  @Test
  void itWriteTags() throws Exception {
    Path copy = Files.createTempFile("taglib-upstream-", ".it");
    try {
      Files.copy(fixture("test.it"), copy, StandardCopyOption.REPLACE_EXISTING);
      try (var file = new ItFile(copy)) {
        assertNotNull(file.tag());
        file.tag().setTitle("changed title");
        file.tag()
          .setComment(
            "This is a sample name!\n"
              + "In module file formats\n"
              + "sample names are abused\n"
              + "as multiline comments.\n"
              + "-----------------------------------\n"
              + "The previous line is truncated but starting with this line\n"
              + "the comment is not limeted in the line length but to 8000\n"
              + "additional characters (bytes).\n\n"
              + "This is because it is saved in the 'message' proportion of\n"
              + "IT files.");
        file.tag().setTrackerName("won't be saved");
        assertTrue(SaveOutcome.save(file));
      }
      assertItRead(
        copy,
        "changed title",
        "This is a sample name!\n"
          + "In module file formats\n"
          + "sample names are abused\n"
          + "as multiline comments.\n"
          + "-------------------------\n"
          + "The previous line is truncated but starting with this line\n"
          + "the comment is not limeted in the line length but to 8000\n"
          + "additional characters (bytes).\n\n"
          + "This is because it is saved in the 'message' proportion of\n"
          + "IT files.");
    } finally {
      Files.deleteIfExists(copy);
    }
  }

  private static void assertXmRead(Path path, String title, String comment, String tracker)
    throws Exception {
    trackerCommon(path, title, comment, tracker, 8);
    try (var file = new XmFile(path)) {
      assertTrue(file.isValid());
      assertNotNull(file.tag());
      assertEquals(title, file.tag().title());
      assertEquals(comment, file.tag().comment());
      var p = file.detailedProperties();
      assertNotNull(p);
      assertEquals(0, p.lengthInSeconds());
      assertEquals(0, p.bitrate());
      assertEquals(0, p.sampleRate());
      assertEquals(8, p.channels());
      assertEquals(1, p.lengthInPatterns());
      assertEquals(260, p.version());
      assertEquals(0, p.restartPosition());
      assertEquals(1, p.patternCount());
      assertEquals(128, p.instrumentCount());
      assertEquals(1, p.flags());
      assertEquals(6, p.tempo());
      assertEquals(125, p.bpmSpeed());
    }
  }

  @Test
  void xmReadTagsAndProperties() throws Exception {
    assertXmRead(
      fixture("test.xm"),
      "title of song",
      "Instrument names\n"
        + "are abused as\n"
        + "comments in\n"
        + "module file formats.\n"
        + "-+-+-+-+-+-+-+-+-+-+-+\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n"
        + "Sample\n"
        + "names\n"
        + "are sometimes\n"
        + "also abused as\n"
        + "comments.",
      "MilkyTracker        ");
  }

  @Test
  void xmWriteTagsShort() throws Exception {
    Path copy = Files.createTempFile("taglib-upstream-", ".xm");
    try {
      Files.copy(fixture("test.xm"), copy, StandardCopyOption.REPLACE_EXISTING);
      try (var file = new XmFile(copy)) {
        assertNotNull(file.tag());
        file.tag().setTitle("changed title");
        file.tag()
          .setComment(
            "Instrument names\n"
              + "are abused as\n"
              + "comments in\n"
              + "module file formats.\n"
              + "======================\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n"
              + "Sample names\n"
              + "are sometimes\n"
              + "also abused as\n"
              + "comments.");
        file.tag().setTrackerName("TagLib");
        assertTrue(SaveOutcome.save(file));
      }
      assertXmRead(
        copy,
        "changed title",
        "Instrument names\n"
          + "are abused as\n"
          + "comments in\n"
          + "module file formats.\n"
          + "======================\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n"
          + "Sample names\n"
          + "are sometimes\n"
          + "also abused as\n"
          + "comments.\n",
        "TagLib");
      assertEquals(-1, Files.mismatch(fixture("changed.xm"), copy));
    } finally {
      Files.deleteIfExists(copy);
    }
  }

  @Test
  void xmWriteTagsLong() throws Exception {
    Path copy = Files.createTempFile("taglib-upstream-", ".xm");
    try {
      Files.copy(fixture("test.xm"), copy, StandardCopyOption.REPLACE_EXISTING);
      try (var file = new XmFile(copy)) {
        assertNotNull(file.tag());
        file.tag().setTitle("changed title");
        file.tag()
          .setComment(
            "Instrument names\n"
              + "are abused as\n"
              + "comments in\n"
              + "module file formats.\n"
              + "======================\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n"
              + "Sample names\n"
              + "are sometimes\n"
              + "also abused as\n"
              + "comments.\n\n\n\n\n\n\n\n"
              + "TEST");
        file.tag().setTrackerName("TagLib");
        assertTrue(SaveOutcome.save(file));
      }
      assertXmRead(
        copy,
        "changed title",
        "Instrument names\n"
          + "are abused as\n"
          + "comments in\n"
          + "module file formats.\n"
          + "======================\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n"
          + "Sample names\n"
          + "are sometimes\n"
          + "also abused as\n"
          + "comments.\n",
        "TagLib");
      assertEquals(-1, Files.mismatch(fixture("changed.xm"), copy));
    } finally {
      Files.deleteIfExists(copy);
    }
  }

  @Test
  void modPropertyInterface() {
    var tag = new cz.skyba.taglib.mod.Tag();
    var original =
      cz.skyba.taglib.PropertyMap.builder()
        .put("BLA", "bla")
        .put("ARTIST", java.util.List.of("artist1", "artist2"))
        .put("TITLE", "title")
        .build();
    var unsupported = tag.replaceProperties(original).rejected();
    assertTrue(unsupported.containsKey("BLA"));
    assertTrue(unsupported.containsKey("ARTIST"));
    assertEquals(original.get("ARTIST"), unsupported.get("ARTIST"));
    assertFalse(unsupported.containsKey("TITLE"));
    assertEquals(java.util.List.of("title"), tag.properties().get("TITLE"));
  }

  @Test
  void xmReadStrippedTags() throws Exception {
    Path path = fixture("stripped.xm");
    trackerCommon(path, "title of song", "", "", 8);
    try (var file = new XmFile(path)) {
      assertTrue(file.isValid());
      assertNotNull(file.tag());
      var p = file.detailedProperties();
      assertNotNull(p);
      assertEquals(0, p.lengthInSeconds());
      assertEquals(0, p.bitrate());
      assertEquals(0, p.sampleRate());
      assertEquals(8, p.channels());
      assertEquals(1, p.lengthInPatterns());
      assertEquals(0, p.version());
      assertEquals(0, p.restartPosition());
      assertEquals(1, p.patternCount());
      assertEquals(0, p.instrumentCount());
      assertEquals(1, p.flags());
      assertEquals(6, p.tempo());
      assertEquals(125, p.bpmSpeed());
      assertEquals("title of song", file.tag().title());
      assertEquals("", file.tag().artist());
      assertEquals("", file.tag().album());
      assertEquals("", file.tag().comment());
      assertEquals("", file.tag().genre());
      assertEquals(0, file.tag().year());
      assertEquals(0, file.tag().track());
      assertEquals("", file.tag().trackerName());
    }
  }

  @Test
  void releasedFixturesAgreeAcrossBorrowedConstructorsAndReadStyles() throws Exception {
    for (var style : ReadStyle.values()) {
      var opus =
        new cz.skyba.taglib.ogg.opus.Properties(
          new ByteVectorStream(
            Files.readAllBytes(fixture("correctness_gain_silent_output.opus"))),
          style);
      assertEquals(7737, opus.lengthInMilliseconds());
      assertEquals(-17920, opus.outputGain());
      var vorbis =
        new cz.skyba.taglib.ogg.vorbis.Properties(
          new ByteVectorStream(Files.readAllBytes(fixture("empty.ogg"))), style);
      assertEquals(3685, vorbis.lengthInMilliseconds());
      assertEquals(112000, vorbis.bitrateNominal());
      var speex =
        new cz.skyba.taglib.ogg.speex.Properties(
          new ByteVectorStream(Files.readAllBytes(fixture("empty.spx"))), style);
      assertEquals(3685, speex.lengthInMilliseconds());
      assertEquals(-1, speex.bitrateNominal());
    }
  }

  private static void apeProperties(
    String name, int seconds, int milliseconds, int bitrate, long samples, int version)
    throws Exception {
    try (var file = new ApeFile(fixture(name))) {
      var p = file.detailedProperties();
      assertNotNull(p);
      assertEquals(seconds, p.lengthInSeconds());
      assertEquals(milliseconds, p.lengthInMilliseconds());
      assertEquals(bitrate, p.bitrate());
      assertEquals(2, p.channels());
      assertEquals(44100, p.sampleRate());
      assertEquals(16, p.bitsPerSample());
      assertEquals(samples, p.sampleFrames());
      assertEquals(version, p.version());
    }
  }

  @Test
  void apeProperties399() throws Exception {
    apeProperties("mac-399.ape", 3, 3550, 192, 156556, 3990);
  }

  @Test
  void apeProperties399Tagged() throws Exception {
    apeProperties("mac-399-tagged.ape", 3, 3550, 192, 156556, 3990);
  }

  @Test
  void apeProperties399Id3v2() throws Exception {
    apeProperties("mac-399-id3v2.ape", 3, 3550, 192, 156556, 3990);
  }

  @Test
  void apeProperties396() throws Exception {
    apeProperties("mac-396.ape", 3, 3685, 0, 162496, 3960);
  }

  @Test
  void apeProperties390() throws Exception {
    apeProperties("mac-390-hdr.ape", 15, 15630, 0, 689262, 3900);
  }

  @Test
  void apeFuzzedFile1() {
    try (var f = new ApeFile(fixture("longloop.ape"))) {
      assertTrue(f.isValid());
    }
  }

  @Test
  void apeFuzzedFile2() {
    try (var f = new ApeFile(fixture("zerodiv.ape"))) {
      assertTrue(f.isValid());
    }
  }

  @Test
  void apeStripAndProperties() throws Exception {
    var copy = Files.createTempFile("ape-strip-", ".ape");
    try {
      Files.copy(fixture("mac-399.ape"), copy, StandardCopyOption.REPLACE_EXISTING);
      try (var f = new ApeFile(copy)) {
        f.getOrCreateApeTag().setTitle("APE");
        f.getOrCreateId3v1Tag().setTitle("ID3v1");
        SaveOutcome.save(f);
      }
      try (var f = new ApeFile(copy)) {
        assertEquals("APE", f.properties().get("TITLE").getFirst());
        f.removeTags(TagSelections.ape(2));
        assertEquals("ID3v1", f.properties().get("TITLE").getFirst());
        f.removeTags(TagSelections.ape(1));
        assertTrue(f.properties().isEmpty());
      }
    } finally {
      Files.deleteIfExists(copy);
    }
  }

  @Test
  void apeRepeatedSave() throws Exception {
    var copy = Files.createTempFile("ape-repeat-", ".ape");
    try {
      Files.copy(fixture("mac-399.ape"), copy, StandardCopyOption.REPLACE_EXISTING);
      try (var f = new ApeFile(copy)) {
        assertFalse(f.hasApeTag());
        assertFalse(f.hasId3v1Tag());
        f.getOrCreateApeTag().setTitle("01234 56789 ABCDE FGHIJ");
        SaveOutcome.save(f);
        f.apeTag().setTitle("0");
        SaveOutcome.save(f);
        f.getOrCreateId3v1Tag().setTitle("01234 56789 ABCDE FGHIJ");
        f.apeTag().setTitle("01234 56789 ABCDE FGHIJ 01234 56789 ABCDE FGHIJ 01234 56789");
        SaveOutcome.save(f);
      }
      try (var f = new ApeFile(copy)) {
        assertTrue(f.hasApeTag());
        assertTrue(f.hasId3v1Tag());
      }
    } finally {
      Files.deleteIfExists(copy);
    }
  }

  private static void wavpackProperties(
    String name,
    int seconds,
    int milliseconds,
    int bitrate,
    int channels,
    int bits,
    boolean lossless,
    int rate,
    long samples,
    int version) {
    try (var f = new WavPackFile(fixture(name))) {
      var p = f.detailedProperties();
      assertNotNull(p);
      assertEquals(seconds, p.lengthInSeconds());
      assertEquals(milliseconds, p.lengthInMilliseconds());
      assertEquals(bitrate, p.bitrate());
      assertEquals(channels, p.channels());
      assertEquals(bits, p.bitsPerSample());
      assertEquals(lossless, p.isLossless());
      assertEquals(rate, p.sampleRate());
      assertEquals(samples, p.sampleFrames());
      assertEquals(version, p.version());
    }
  }

  @Test
  void wavpackNoLengthProperties() {
    wavpackProperties("no_length.wv", 3, 3705, 1, 2, 16, true, 44100, 163392, 1031);
  }

  @Test
  void wavpackMultiChannelProperties() {
    wavpackProperties("four_channels.wv", 3, 3833, 112, 4, 16, false, 44100, 169031, 1031);
  }

  @Test
  void wavpackDsdStereoProperties() {
    wavpackProperties("dsd_stereo.wv", 0, 200, 2096, 2, 8, true, 352800, 70560, 1040);
  }

  @Test
  void wavpackNonStandardRateProperties() {
    wavpackProperties("non_standard_rate.wv", 3, 3675, 0, 2, 16, true, 1000, 3675, 1040);
  }

  @Test
  void wavpackTaggedProperties() {
    wavpackProperties("tagged.wv", 3, 3550, 172, 2, 16, false, 44100, 156556, 1031);
  }

  @Test
  void wavpackFuzzedFile() {
    try (var f = new WavPackFile(fixture("infloop.wv"))) {
      assertTrue(f.isValid());
    }
  }

  @Test
  void wavpackStripAndProperties() throws Exception {
    var copy = Files.createTempFile("wavpack-strip-", ".wv");
    try {
      Files.copy(fixture("click.wv"), copy, StandardCopyOption.REPLACE_EXISTING);
      try (var f = new WavPackFile(copy)) {
        f.getOrCreateApeTag().setTitle("APE");
        f.getOrCreateId3v1Tag().setTitle("ID3v1");
        assertTrue(SaveOutcome.save(f));
      }
      try (var f = new WavPackFile(copy)) {
        assertEquals("APE", f.properties().get("TITLE").getFirst());
        f.removeTags(TagSelections.wavPack(2));
        assertEquals("ID3v1", f.properties().get("TITLE").getFirst());
        f.removeTags(TagSelections.wavPack(1));
        assertTrue(f.properties().isEmpty());
      }
    } finally {
      Files.deleteIfExists(copy);
    }
  }

  @Test
  void wavpackRepeatedSave() throws Exception {
    var copy = Files.createTempFile("wavpack-repeat-", ".wv");
    try {
      Files.copy(fixture("click.wv"), copy, StandardCopyOption.REPLACE_EXISTING);
      try (var f = new WavPackFile(copy)) {
        assertFalse(f.hasApeTag());
        assertFalse(f.hasId3v1Tag());
        f.getOrCreateApeTag().setTitle("01234 56789 ABCDE FGHIJ");
        assertTrue(SaveOutcome.save(f));
        f.apeTag().setTitle("0");
        assertTrue(SaveOutcome.save(f));
        f.getOrCreateId3v1Tag().setTitle("01234 56789 ABCDE FGHIJ");
        f.apeTag().setTitle("01234 56789 ABCDE FGHIJ 01234 56789 ABCDE FGHIJ 01234 56789");
        assertTrue(SaveOutcome.save(f));
      }
      try (var f = new WavPackFile(copy)) {
        assertTrue(f.hasApeTag());
        assertTrue(f.hasId3v1Tag());
      }
    } finally {
      Files.deleteIfExists(copy);
    }
  }

  private static void mpcProperties(
    String name, int version, int seconds, int milliseconds, int bitrate, long samples) {
    try (var f = new MpcFile(fixture(name))) {
      var p = f.detailedProperties();
      assertNotNull(p);
      assertEquals(version, p.mpcVersion());
      assertEquals(seconds, p.lengthInSeconds());
      assertEquals(milliseconds, p.lengthInMilliseconds());
      assertEquals(bitrate, p.bitrate());
      assertEquals(2, p.channels());
      assertEquals(44100, p.sampleRate());
      assertEquals(samples, p.sampleFrames());
      if (version == 7) {
        assertEquals(14221, p.trackGain());
        assertEquals(19848, p.trackPeak());
        assertEquals(14221, p.albumGain());
        assertEquals(19848, p.albumPeak());
      }
    }
  }

  @Test
  void mpcPropertiesSV8() {
    mpcProperties("sv8_header.mpc", 8, 1, 1497, 1, 66014);
  }

  @Test
  void mpcPropertiesSV7() {
    mpcProperties("click.mpc", 7, 0, 40, 318, 1760);
  }

  @Test
  void mpcPropertiesSV5() {
    mpcProperties("sv5_header.mpc", 5, 26, 26371, 0, 1162944);
  }

  @Test
  void mpcPropertiesSV4() {
    mpcProperties("sv4_header.mpc", 4, 26, 26371, 0, 1162944);
  }

  @Test
  void mpcFuzzedFile1() {
    try (var f = new MpcFile(fixture("zerodiv.mpc"))) {
      assertTrue(f.isValid());
    }
  }

  @Test
  void mpcFuzzedFile2() {
    try (var f = new MpcFile(fixture("infloop.mpc"))) {
      assertTrue(f.isValid());
    }
  }

  @Test
  void mpcFuzzedFile3() {
    try (var f = new MpcFile(fixture("segfault.mpc"))) {
      assertTrue(f.isValid());
    }
  }

  @Test
  void mpcFuzzedFile4() {
    try (var f = new MpcFile(fixture("segfault2.mpc"))) {
      assertTrue(f.isValid());
    }
  }

  @Test
  void mpcStripAndProperties() throws Exception {
    var copy = Files.createTempFile("mpc-strip-", ".mpc");
    try {
      Files.copy(fixture("click.mpc"), copy, StandardCopyOption.REPLACE_EXISTING);
      try (var f = new MpcFile(copy)) {
        f.getOrCreateApeTag().setTitle("APE");
        f.getOrCreateId3v1Tag().setTitle("ID3v1");
        assertTrue(SaveOutcome.save(f));
      }
      try (var f = new MpcFile(copy)) {
        assertEquals("APE", f.properties().get("TITLE").getFirst());
        f.removeTags(TagSelections.mpc(4));
        assertEquals("ID3v1", f.properties().get("TITLE").getFirst());
        f.removeTags(TagSelections.mpc(1));
        assertTrue(f.properties().isEmpty());
        assertTrue(SaveOutcome.save(f));
      }
      try (var f = new MpcFile(copy)) {
        assertFalse(f.hasApeTag());
        assertFalse(f.hasId3v1Tag());
        assertTrue(f.properties().get("TITLE").isEmpty());
        assertTrue(f.properties().isEmpty());
      }
    } finally {
      Files.deleteIfExists(copy);
    }
  }

  @Test
  void mpcRepeatedSave() throws Exception {
    var copy = Files.createTempFile("mpc-repeat-", ".mpc");
    try {
      Files.copy(fixture("click.mpc"), copy, StandardCopyOption.REPLACE_EXISTING);
      try (var f = new MpcFile(copy)) {
        assertFalse(f.hasApeTag());
        assertFalse(f.hasId3v1Tag());
        f.getOrCreateApeTag().setTitle("01234 56789 ABCDE FGHIJ");
        assertTrue(SaveOutcome.save(f));
        f.apeTag().setTitle("0");
        assertTrue(SaveOutcome.save(f));
        f.getOrCreateId3v1Tag().setTitle("01234 56789 ABCDE FGHIJ");
        f.apeTag().setTitle("01234 56789 ABCDE FGHIJ 01234 56789 ABCDE FGHIJ 01234 56789");
        assertTrue(SaveOutcome.save(f));
      }
      try (var f = new MpcFile(copy)) {
        assertTrue(f.hasApeTag());
        assertTrue(f.hasId3v1Tag());
      }
    } finally {
      Files.deleteIfExists(copy);
    }
  }
}
