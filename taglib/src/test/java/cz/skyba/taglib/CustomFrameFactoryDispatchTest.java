package cz.skyba.taglib;

import cz.skyba.taglib.dsdiff.DsdiffFile;
import cz.skyba.taglib.dsf.DsfFile;
import cz.skyba.taglib.flac.FlacFile;
import cz.skyba.taglib.id3.v2.Frame;
import cz.skyba.taglib.id3.v2.FrameFactory;
import cz.skyba.taglib.mpeg.MpegFile;
import cz.skyba.taglib.riff.aiff.AiffFile;
import cz.skyba.taglib.riff.wav.WavFile;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.TagString;
import cz.skyba.taglib.trueaudio.TrueAudioFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Complete pinned custom FrameFactory template, independently applied to all seven owners.
 */
class CustomFrameFactoryDispatchTest {
  @TempDir
  Path directory;

  static class CustomFrame extends Frame {
    long value;

    CustomFrame(long n) {
      super(new ByteVector("CUST"));
      value = n & 0xffff_ffffL;
    }

    CustomFrame(ByteVector d, Frame.Header h) {
      super(h);
      value = fieldData(d).toUnsignedInt();
    }

    public String toString() {
      return Integer.toString((int) value);
    }

    protected PropertyMap asProperties() {
      return PropertyMap.builder().put("CUSTOM", toString()).build();
    }

    protected void parseFields(ByteVector d) {
      value = d.toUnsignedInt();
    }

    protected ByteVector renderFields() {
      return ByteVector.fromUnsignedInt(value);
    }
  }

  static class CustomFactory extends FrameFactory {
    public Frame createFrameForProperty(String key, List<String> values) {
      return key.equals("CUSTOM") ? new CustomFrame(values.isEmpty() ? 0 : new TagString(values.getFirst()).toInt()) : super.createFrameForProperty(key, values);
    }

    protected Frame createFrame(ByteVector d, Frame.Header h, cz.skyba.taglib.id3.v2.Header t) {
      return h.frameID().equals(new ByteVector("CUST")) ? new CustomFrame(d, h) : super.createFrame(d, h, t);
    }
  }

  static FileBase open(Path p, int kind, FrameFactory factory) throws IOException {
    return switch (kind) {
      case 0 -> new MpegFile(p, ReadOptions.DEFAULT, factory);
      case 1 -> new FlacFile(p, ReadOptions.DEFAULT, factory);
      case 2 -> new TrueAudioFile(p, ReadOptions.DEFAULT, factory);
      case 3 -> new WavFile(p, ReadOptions.DEFAULT, factory);
      case 4 -> new AiffFile(p, ReadOptions.DEFAULT, factory);
      case 5 -> new DsfFile(p, ReadOptions.DEFAULT, factory);
      default -> new DsdiffFile(p, ReadOptions.DEFAULT, factory);
    };
  }

  static cz.skyba.taglib.id3.v2.Tag child(FileBase f, int k) {
    return switch (k) {
      case 0 -> ((MpegFile) f).getOrCreateId3v2Tag();
      case 1 -> ((FlacFile) f).getOrCreateId3v2Tag();
      case 2 -> ((TrueAudioFile) f).getOrCreateId3v2Tag();
      case 3 -> ((WavFile) f).id3v2Tag();
      case 4 -> ((AiffFile) f).tag();
      case 5 -> ((DsfFile) f).tag();
      default -> ((DsdiffFile) f).getOrCreateId3v2Tag();
    };
  }

  static boolean present(FileBase f, int k) {
    return switch (k) {
      case 0 -> ((MpegFile) f).hasId3v2Tag();
      case 1 -> ((FlacFile) f).hasId3v2Tag();
      case 2 -> ((TrueAudioFile) f).hasId3v2Tag();
      case 3 -> ((WavFile) f).hasId3v2Tag();
      case 4 -> ((AiffFile) f).hasId3v2Tag();
      case 5 -> !f.tag().isEmpty();
      default -> ((DsdiffFile) f).hasId3v2Tag();
    };
  }

  static void strip(FileBase f, int k) throws IOException {
    switch (k) {
      case 0 -> ((MpegFile) f).strip();
      case 1 -> {
        ((FlacFile) f).removeTags();
        f.save();
      }
      case 2 -> {
        ((TrueAudioFile) f).removeTags();
        f.save();
      }
      case 3 -> ((WavFile) f).strip();
      case 4, 5 -> {
        f.replaceProperties(PropertyMap.EMPTY);
        f.save();
      }
      default -> ((DsdiffFile) f).strip();
    }
  }

  void contract(String name, int kind) throws Exception {
    var p = Files.copy(FileReferenceResolversAndSavingTest.fixture(name), directory.resolve(name));
    var defaults = FrameFactory.instance();
    var factory = new CustomFactory();
    try (var f = open(p, kind, defaults)) {
      assertTrue(f.isValid());
      var tag = child(f, kind);
      for (var frame : List.copyOf(tag.frameList())) tag.removeFrame(frame);
      tag.setArtist("An artist");
      tag.setTitle("A title");
      f.save();
    }
    try (var f = open(p, kind, defaults)) {
      assertTrue(f.isValid());
      assertTrue(present(f, kind));
      child(f, kind).addFrame(new CustomFrame(1234567890));
      f.save();
    }
    try (var f = open(p, kind, defaults)) {
      assertTrue(f.isValid());
      assertTrue(present(f, kind));
      var frames = child(f, kind).frameList(new ByteVector("CUST"));
      assertFalse(frames.isEmpty());
      assertFalse(frames.getFirst() instanceof CustomFrame);
    }
    try (var f = open(p, kind, factory)) {
      assertTrue(f.isValid());
      assertTrue(present(f, kind));
      var tag = child(f, kind);
      var frames = tag.frameList(new ByteVector("CUST"));
      assertFalse(frames.isEmpty());
      assertEquals(1234567890, assertInstanceOf(CustomFrame.class, frames.getFirst()).value);
      var properties = tag.properties();
      assertEquals(List.of("1234567890"), properties.get("CUSTOM"));
      assertEquals(List.of("An artist"), properties.get("ARTIST"));
      assertEquals(List.of("A title"), properties.get("TITLE"));
      strip(f, kind);
    }
    try (var f = open(p, kind, factory)) {
      assertTrue(f.isValid());
      assertFalse(present(f, kind));
      var tag = child(f, kind);
      assertTrue(tag.properties().isEmpty());
      tag.replaceProperties(PropertyMap.builder().put("CUSTOM", "305419896").build());
      f.save();
    }
    try (var f = open(p, kind, factory)) {
      assertTrue(f.isValid());
      assertTrue(present(f, kind));
      var tag = child(f, kind);
      assertEquals(List.of("305419896"), tag.properties().get("CUSTOM"));
      var frames = tag.frameList(new ByteVector("CUST"));
      assertFalse(frames.isEmpty());
      assertEquals(0x12345678, assertInstanceOf(CustomFrame.class, frames.getFirst()).value);
    }
  }

  @Test
  void testMPEG() throws Exception {
    contract("lame_cbr.mp3", 0);
  }

  @Test
  void testFLAC() throws Exception {
    contract("no-tags.flac", 1);
  }

  @Test
  void testTrueAudio() throws Exception {
    contract("empty.tta", 2);
  }

  @Test
  void testWAV() throws Exception {
    contract("empty.wav", 3);
  }

  @Test
  void testAIFF() throws Exception {
    contract("empty.aiff", 4);
  }

  @Test
  void testDSF() throws Exception {
    contract("empty10ms.dsf", 5);
  }

  @Test
  void testDSDIFF() throws Exception {
    contract("empty10ms.dff", 6);
  }
}
