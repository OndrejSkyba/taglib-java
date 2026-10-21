package cz.skyba.taglib;

import cz.skyba.taglib.flac.FlacFile;
import cz.skyba.taglib.mp4.Atoms;
import cz.skyba.taglib.mp4.ItemFactory;
import cz.skyba.taglib.mp4.Mp4File;
import cz.skyba.taglib.mpeg.MpegFile;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileRefContentEdgeCaseTest {
  @TempDir
  Path directory;

  private static ByteVector atom(String name, ByteVector payload) {
    return ByteVector.fromUnsignedInt(payload.size() + 8L).append(new ByteVector(name)).append(payload);
  }

  private static ByteVector movie(long mediaOffset) {
    var offsets = new ByteVector(4).append(ByteVector.fromUnsignedInt(2))
      .append(ByteVector.fromUnsignedInt(mediaOffset)).append(ByteVector.fromUnsignedInt(mediaOffset + 417));
    var track = atom("trak", atom("mdia", atom("minf", atom("stbl", atom("stco", offsets)))));
    var tag = new cz.skyba.taglib.mp4.Tag();
    tag.setTitle("MP4 title");
    var title = ItemFactory.instance().renderItem("©nam", tag.item("©nam"));
    var metadata = atom("udta", atom("meta", new ByteVector(4).append(atom("ilst", title))));
    return atom("moov", track.append(metadata));
  }

  private static ByteVector mp4WithMpegFrames() {
    // Two coherent 128 kb/s MPEG Layer III headers also satisfy the MPEG detector.
    var frame = new ByteVector(417);
    byte[] header = {(byte) 0xff, (byte) 0xfb, (byte) 0x90, 0x64};
    System.arraycopy(header, 0, frame.data(), 0, header.length);
    var media = atom("mdat", new ByteVector(frame).append(frame));
    var type = atom("ftyp", new ByteVector("M4A ").append(new ByteVector(4)).append(new ByteVector("isom")));
    var measured = movie(0);
    return type.append(movie(type.size() + measured.size() + 8L)).append(media);
  }

  private static void assertSavedMp4(FileBase file) throws IOException {
    assertInstanceOf(Mp4File.class, file);
    assertEquals("MP4 title", file.tag().title());
    file.tag().setTitle("Changed title ".repeat(150));
    file.save();
  }

  private static void assertMp4Media(ByteVector saved, ByteVector original) throws IOException {
    var stream = new ByteVectorStream(saved);
    try (var file = new Mp4File(stream)) {
      assertTrue(file.isValid());
      assertEquals("Changed title ".repeat(150), file.tag().title());
      var atoms = new Atoms(file);
      assertEquals(new ByteVector("ftyp"), atoms.atoms().getFirst().name());
      var media = atoms.find("mdat");
      assertNotNull(media);
      var originalMedia = original.mid(original.size() - 834, 834);
      assertEquals(originalMedia, saved.mid(Math.toIntExact(media.offset() + 8), 834));
      var offsets = atoms.find("moov", "trak", "mdia", "minf").find("stbl", "stco");
      assertNotNull(offsets);
      file.seek(offsets.offset() + 12);
      var data = file.readBlock(offsets.length() - 12);
      assertEquals(2, data.toUnsignedInt());
      assertEquals(media.offset() + 8, data.toUnsignedInt(4, true));
      assertEquals(media.offset() + 8 + 417, data.toUnsignedInt(8, true));
    }
  }

  @Test
  void unnamedMp4WithMpegFramesSavesMetadataWithoutMovingSamplesOutOfTheirChunks() throws Exception {
    var original = mp4WithMpegFrames();
    var stream = new ByteVectorStream(original);
    assertTrue(MpegFile.isSupported(stream), "Fixture must exercise both format probes");
    try (var reference = new AudioFile(stream)) {
      assertTrue(reference.isOpen());
      assertSavedMp4(reference.file());
    }
    assertTrue(stream.isOpen());
    assertMp4Media(stream.data(), original);
  }

  @Test
  void unknownExtensionMp4WithMpegFramesUsesMp4Save() throws Exception {
    var original = mp4WithMpegFrames();
    var path = directory.resolve("mpeg-audio.bin");
    Files.write(path, original.data());
    try (var reference = new AudioFile(path)) {
      assertTrue(reference.isOpen());
      assertSavedMp4(reference.file());
    }
    assertMp4Media(new ByteVector(Files.readAllBytes(path)), original);
  }

  private static ByteVector flacWithLeadingId3() throws Exception {
    var tag = new cz.skyba.taglib.id3.v2.Tag();
    tag.setTitle("Leading ID3 title");
    var prefix = tag.render();
    assertTrue(prefix.size() > 1024, "FLAC signature must lie beyond the initial prefix window");
    return prefix.append(new ByteVector(Files.readAllBytes(AudioFixtures.fixture("no-tags.flac"))));
  }

  private static void assertPrefixedFlac(AudioFile reference) {
    assertTrue(reference.isOpen());
    assertInstanceOf(FlacFile.class, reference.file());
    assertEquals("Leading ID3 title", reference.tag().title());
    assertEquals(List.of("Leading ID3 title"), reference.properties().get("TITLE"));
    assertEquals(44100, reference.audioProperties().sampleRate());
  }

  @Test
  void unnamedFlacSkipsLeadingId3BeyondThePrefixWindow() throws Exception {
    var stream = new ByteVectorStream(flacWithLeadingId3());
    try (var reference = new AudioFile(stream)) {
      assertPrefixedFlac(reference);
    }
    assertTrue(stream.isOpen());
  }

  @Test
  void unknownExtensionFlacSkipsLeadingId3BeyondThePrefixWindow() throws Exception {
    var path = directory.resolve("prefixed.bin");
    Files.write(path, flacWithLeadingId3().data());
    try (var reference = new AudioFile(path)) {
      assertPrefixedFlac(reference);
    }
  }
}
