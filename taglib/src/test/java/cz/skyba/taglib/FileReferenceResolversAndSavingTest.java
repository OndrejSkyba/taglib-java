package cz.skyba.taglib;

import cz.skyba.taglib.ape.ApeFile;
import cz.skyba.taglib.asf.AsfFile;
import cz.skyba.taglib.dsdiff.DsdiffFile;
import cz.skyba.taglib.dsf.DsfFile;
import cz.skyba.taglib.flac.FlacFile;
import cz.skyba.taglib.mp4.Mp4File;
import cz.skyba.taglib.mpc.MpcFile;
import cz.skyba.taglib.mpeg.MpegFile;
import cz.skyba.taglib.ogg.flac.OggFlacFile;
import cz.skyba.taglib.ogg.opus.OpusFile;
import cz.skyba.taglib.ogg.speex.SpeexFile;
import cz.skyba.taglib.ogg.vorbis.VorbisFile;
import cz.skyba.taglib.riff.aiff.AiffFile;
import cz.skyba.taglib.riff.wav.WavFile;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.FileStream;
import cz.skyba.taglib.toolkit.IOStream;
import cz.skyba.taglib.trueaudio.TrueAudioFile;
import cz.skyba.taglib.wavpack.WavPackFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Complete seven-open pinned AudioFile save template and common controls.
 */
class FileReferenceResolversAndSavingTest {
  @TempDir
  Path directory;

  @AfterEach
  void resetResolvers() {
    AudioFile.clearFileTypeResolvers();
  }

  static Path fixture(String name) {
    return AudioFixtures.fixture(name);
  }

  @Test
  void testMusepack() throws Exception {
    saveTemplate("click.mpc", MpcFile.class);
  }

  @Test
  void testASF() throws Exception {
    saveTemplate("silence-1.wma", AsfFile.class);
  }

  @Test
  void testVorbis() throws Exception {
    saveTemplate("empty.ogg", VorbisFile.class);
  }

  @Test
  void testSpeex() throws Exception {
    saveTemplate("empty.spx", SpeexFile.class);
  }

  @Test
  void testFLAC() throws Exception {
    saveTemplate("no-tags.flac", FlacFile.class);
  }

  @Test
  void testMP3() throws Exception {
    saveTemplate("xing.mp3", MpegFile.class);
  }

  @Test
  void testTrueAudio() throws Exception {
    saveTemplate("empty.tta", TrueAudioFile.class);
  }

  @Test
  void testMP4_1() throws Exception {
    saveTemplate("has-tags.m4a", Mp4File.class);
  }

  @Test
  void testMP4_2() throws Exception {
    saveTemplate("no-tags.m4a", Mp4File.class);
  }

  @Test
  void testMP4_3() throws Exception {
    saveTemplate("no-tags.3g2", Mp4File.class);
  }

  @Test
  void testMP4_4() throws Exception {
    saveTemplate("blank_video.m4v", Mp4File.class);
  }

  @Test
  void testWav() throws Exception {
    saveTemplate("empty.wav", WavFile.class);
  }

  @Test
  void testOGA_FLAC() throws Exception {
    saveTemplate("empty_flac.oga", OggFlacFile.class);
  }

  @Test
  void testOGA_Vorbis() throws Exception {
    saveTemplate("empty_vorbis.oga", VorbisFile.class);
  }

  @Test
  void testAPE() throws Exception {
    saveTemplate("mac-399.ape", ApeFile.class);
  }

  @Test
  void testAIFF_1() throws Exception {
    saveTemplate("empty.aiff", AiffFile.class);
  }

  @Test
  void testAIFF_2() throws Exception {
    saveTemplate("alaw.aifc", AiffFile.class);
  }

  @Test
  void testWavPack() throws Exception {
    saveTemplate("click.wv", WavPackFile.class);
  }

  @Test
  void testOpus() throws Exception {
    saveTemplate("correctness_gain_silent_output.opus", OpusFile.class);
  }

  @Test
  void testDSF() throws Exception {
    saveTemplate("empty10ms.dsf", DsfFile.class);
  }

  @Test
  void testDSDIFF() throws Exception {
    saveTemplate("empty10ms.dff", DsdiffFile.class);
  }

  static void mutate(TagBase t, boolean second) {
    t.setArtist(second ? "ttest artist" : "test artist");
    t.setTitle(second ? "ytest title" : "test title");
    t.setGenre(second ? "uTest!" : "Test!");
    t.setAlbum(second ? "ialbummmm" : "albummmm");
    t.setComment(second ? "another comment" : "a comment");
    t.setTrack(second ? 7 : 5);
    t.setYear(second ? 2080 : 2020);
  }

  static void state(AudioFile f, boolean second) {
    assertTrue(f.isOpen());
    var t = f.tag();
    assertEquals(second ? "ttest artist" : "test artist", t.artist());
    assertEquals(second ? "ytest title" : "test title", t.title());
    assertEquals(second ? "uTest!" : "Test!", t.genre());
    assertEquals(second ? "ialbummmm" : "albummmm", t.album());
    assertEquals(second ? "another comment" : "a comment", t.comment());
    assertEquals(second ? 7 : 5, t.track());
    assertEquals(second ? 2080 : 2020, t.year());
  }

  void saveTemplate(String name, Class<? extends FileBase> expected) throws Exception {
    var p = Files.copy(fixture(name), directory.resolve(name));
    try (var f = new AudioFile(p)) {
      assertInstanceOf(expected, f.file());
      assertTrue(f.isOpen());
      mutate(f.tag(), false);
      assertDoesNotThrow(f::save);
    }
    try (var f = new AudioFile(p)) {
      state(f, false);
      mutate(f.tag(), true);
      assertDoesNotThrow(f::save);
    }
    try (var f = new AudioFile(p)) {
      state(f, true);
    }
    try (var stream = new FileStream(p)) {
      try (var f = new AudioFile(stream)) {
        assertInstanceOf(expected, f.file());
        state(f, true);
        mutate(f.tag(), false);
        assertDoesNotThrow(f::save);
      }
      assertTrue(stream.isOpen());
    }
    ByteVector content;
    try (var stream = new FileStream(p)) {
      try (var f = new AudioFile(stream)) {
        assertInstanceOf(expected, f.file());
        state(f, false);
        stream.seek(0);
        content = stream.readBlock(stream.length());
      }
      assertTrue(stream.isOpen());
    }
    var stream = new ByteVectorStream(content);
    try (var f = new AudioFile(stream)) {
      assertInstanceOf(expected, f.file());
      state(f, false);
      mutate(f.tag(), true);
      assertDoesNotThrow(f::save);
      content = stream.data();
    }
    assertTrue(stream.isOpen());
    var reopened = new ByteVectorStream(content);
    try (var f = new AudioFile(reopened)) {
      assertInstanceOf(expected, f.file());
      state(f, true);
    }
    assertTrue(reopened.isOpen());
  }

  @Test
  void testUnsupported() throws Exception {
    assertThrows(TagLibException.class, () -> new AudioFile(fixture("no-extension")));
    assertThrows(TagLibException.class, () -> new AudioFile(fixture("unsupported-extension.xx")));
  }

  @Test
  void testAudioProperties() throws Exception {
    try (var f = new AudioFile(fixture("xing.mp3"))) {
      assertEquals(2, f.audioProperties().lengthInSeconds());
      assertEquals(2064, f.audioProperties().lengthInMilliseconds());
    }
  }

  @Test
  void testDefaultFileExtensions() {
    var extensions = AudioFile.defaultFileExtensions();
    for (String extension : List.of("mpc", "wma", "ogg", "spx", "flac", "mp3", "tta", "m4a", "3g2", "m4v", "wav", "oga", "ape", "aiff", "aifc", "wv", "opus", "xm", "dsf", "dff", "dsdiff", "shn", "mkv", "mka", "webm"))
      assertTrue(extensions.contains(extension), extension);
  }

  @Test
  void testFileResolver() throws Exception {
    try (var f = new AudioFile(fixture("xing.mp3"))) {
      assertInstanceOf(MpegFile.class, f.file());
    }
    AudioFile.addFileTypeResolver((path, options) -> new VorbisFile(path, options));
    assertThrows(TagLibException.class, () -> new AudioFile(fixture("xing.mp3")));
    AudioFile.addFileTypeResolver(new AudioFile.StreamTypeResolver() {
      public FileBase createFile(Path p, ReadOptions options) {
        return null;
      }

      public FileBase createFileFromStream(IOStream stream, ReadOptions options) throws IOException {
        return new Mp4File(stream);
      }
    });
    try (var stream = new FileStream(fixture("xing.mp3"))) {
      assertThrows(TagLibException.class, () -> new AudioFile(stream));
      assertTrue(stream.isOpen());
    }
    AudioFile.clearFileTypeResolvers();
  }

  @Test
  void resolverCallsUseExplicitReadOptions() throws Exception {
    var calls = new ArrayList<String>();
    var resolver = new AudioFile.StreamTypeResolver() {
      @Override
      public FileBase createFile(Path path, ReadOptions options) {
        calls.add("file:" + options.readAudioProperties() + ":" + options.readStyle());
        return null;
      }

      @Override
      public FileBase createFileFromStream(IOStream stream, ReadOptions options) {
        calls.add("stream:" + options.readAudioProperties() + ":" + options.readStyle());
        return null;
      }
    };
    AudioFile.FileTypeResolver fileResolver = resolver;
    fileResolver.createFile(Path.of("unused"), ReadOptions.DEFAULT);
    fileResolver.createFile(Path.of("unused"), ReadOptions.TAGS_ONLY);
    try (var stream = new ByteVectorStream(new byte[0])) {
      resolver.createFileFromStream(stream, ReadOptions.DEFAULT);
      resolver.createFileFromStream(stream, ReadOptions.TAGS_ONLY);
    }
    assertEquals(List.of(
      "file:true:AVERAGE",
      "file:false:AVERAGE",
      "stream:true:AVERAGE",
      "stream:false:AVERAGE"), calls);
  }
}
