package cz.skyba.taglib;

import cz.skyba.taglib.ape.ApeFile;
import cz.skyba.taglib.asf.AsfFile;
import cz.skyba.taglib.dsdiff.DsdiffFile;
import cz.skyba.taglib.dsf.DsfFile;
import cz.skyba.taglib.flac.FlacFile;
import cz.skyba.taglib.matroska.MatroskaFile;
import cz.skyba.taglib.mp4.Mp4File;
import cz.skyba.taglib.mpc.MpcFile;
import cz.skyba.taglib.mpeg.MpegFile;
import cz.skyba.taglib.ogg.flac.OggFlacFile;
import cz.skyba.taglib.ogg.opus.OpusFile;
import cz.skyba.taglib.ogg.speex.SpeexFile;
import cz.skyba.taglib.ogg.vorbis.VorbisFile;
import cz.skyba.taglib.riff.aiff.AiffFile;
import cz.skyba.taglib.riff.wav.WavFile;
import cz.skyba.taglib.shorten.ShortenFile;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.FileStream;
import cz.skyba.taglib.trueaudio.TrueAudioFile;
import cz.skyba.taglib.wavpack.WavPackFile;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent full pinned content-detector helper assertions.
 */
class FileReferenceContentDetectionTest {
  static Path fixture(String name) {
    return AudioFixtures.fixture(name);
  }

  @Test
  void testApeId3v1Mp3() throws Exception {
    detect("ape-id3v1.mp3", MpegFile.class);
  }

  @Test
  void testApeId3v2Mp3() throws Exception {
    detect("ape-id3v2.mp3", MpegFile.class);
  }

  @Test
  void testApeMp3() throws Exception {
    detect("ape.mp3", MpegFile.class);
  }

  @Test
  void testBladeencMp3() throws Exception {
    detect("bladeenc.mp3", MpegFile.class);
  }

  @Test
  void testEmpty1sAac() throws Exception {
    detect("empty1s.aac", MpegFile.class);
  }

  @Test
  void testId3v22TdaMp3() throws Exception {
    detect("id3v22-tda.mp3", MpegFile.class);
  }

  @Test
  void testInvalidFrames1Mp3() throws Exception {
    detect("invalid-frames1.mp3", MpegFile.class);
  }

  @Test
  void testInvalidFrames2Mp3() throws Exception {
    detect("invalid-frames2.mp3", MpegFile.class);
  }

  @Test
  void testInvalidFrames3Mp3() throws Exception {
    detect("invalid-frames3.mp3", MpegFile.class);
  }

  @Test
  void testItunes10Mp3() throws Exception {
    detect("itunes10.mp3", MpegFile.class);
  }

  @Test
  void testLameCbrMp3() throws Exception {
    detect("lame_cbr.mp3", MpegFile.class);
  }

  @Test
  void testLameVbrMp3() throws Exception {
    detect("lame_vbr.mp3", MpegFile.class);
  }

  @Test
  void testMpeg2Mp3() throws Exception {
    detect("mpeg2.mp3", MpegFile.class);
  }

  @Test
  void testRareFramesMp3() throws Exception {
    detect("rare_frames.mp3", MpegFile.class);
  }

  @Test
  void testTocManyChildrenMp3() throws Exception {
    detect("toc_many_children.mp3", MpegFile.class);
  }

  @Test
  void testXingMp3() throws Exception {
    detect("xing.mp3", MpegFile.class);
  }

  @Test
  void testEmptyOgg() throws Exception {
    detect("empty.ogg", VorbisFile.class);
  }

  @Test
  void testEmptyVorbisOga() throws Exception {
    detect("empty_vorbis.oga", VorbisFile.class);
  }

  @Test
  void testLowercaseFieldsOgg() throws Exception {
    detect("lowercase-fields.ogg", VorbisFile.class);
  }

  @Test
  void testTestOgg() throws Exception {
    detect("test.ogg", VorbisFile.class);
  }

  @Test
  void testEmptyFlacOga() throws Exception {
    detect("empty_flac.oga", OggFlacFile.class);
  }

  @Test
  void testEmptySeektableFlac() throws Exception {
    detect("empty-seektable.flac", FlacFile.class);
  }

  @Test
  void testMultipleVcFlac() throws Exception {
    detect("multiple-vc.flac", FlacFile.class);
  }

  @Test
  void testNoTagsFlac() throws Exception {
    detect("no-tags.flac", FlacFile.class);
  }

  @Test
  void testSilence44SFlac() throws Exception {
    detect("silence-44-s.flac", FlacFile.class);
  }

  @Test
  void testSinewaveFlac() throws Exception {
    detect("sinewave.flac", FlacFile.class);
  }

  @Test
  void testZeroSizedPaddingFlac() throws Exception {
    detect("zero-sized-padding.flac", FlacFile.class);
  }

  @Test
  void testFLACWithMPEGSyncBytes() throws Exception {
    detect("mpeg-sync-flac.flac", FlacFile.class);
  }

  @Test
  void testEmptySpx() throws Exception {
    detect("empty.spx", SpeexFile.class);
  }

  @Test
  void testCorrectnessGainSilentOutputOpus() throws Exception {
    detect("correctness_gain_silent_output.opus", OpusFile.class);
  }

  @Test
  void testClickMpc() throws Exception {
    detect("click.mpc", MpcFile.class);
  }

  @Test
  void testInfloopMpc() throws Exception {
    detect("infloop.mpc", MpcFile.class);
  }

  @Test
  void testSegfaultMpc() throws Exception {
    detect("segfault.mpc", MpcFile.class);
  }

  @Test
  void testSegfault2Mpc() throws Exception {
    detect("segfault2.mpc", MpcFile.class);
  }

  @Test
  void testSv8HeaderMpc() throws Exception {
    detect("sv8_header.mpc", MpcFile.class);
  }

  @Test
  void testZerodivMpc() throws Exception {
    detect("zerodiv.mpc", MpcFile.class);
  }

  @Test
  void testClickWv() throws Exception {
    detect("click.wv", WavPackFile.class);
  }

  @Test
  void testDsdStereoWv() throws Exception {
    detect("dsd_stereo.wv", WavPackFile.class);
  }

  @Test
  void testFourChannelsWv() throws Exception {
    detect("four_channels.wv", WavPackFile.class);
  }

  @Test
  void testInfloopWv() throws Exception {
    detect("infloop.wv", WavPackFile.class);
  }

  @Test
  void testNoLengthWv() throws Exception {
    detect("no_length.wv", WavPackFile.class);
  }

  @Test
  void testNonStandardRateWv() throws Exception {
    detect("non_standard_rate.wv", WavPackFile.class);
  }

  @Test
  void testTaggedWv() throws Exception {
    detect("tagged.wv", WavPackFile.class);
  }

  @Test
  void testLongloopApe() throws Exception {
    detect("longloop.ape", ApeFile.class);
  }

  @Test
  void testMac390HdrApe() throws Exception {
    detect("mac-390-hdr.ape", ApeFile.class);
  }

  @Test
  void testMac396Ape() throws Exception {
    detect("mac-396.ape", ApeFile.class);
  }

  @Test
  void testMac399Id3v2Ape() throws Exception {
    detect("mac-399-id3v2.ape", ApeFile.class);
  }

  @Test
  void testMac399TaggedApe() throws Exception {
    detect("mac-399-tagged.ape", ApeFile.class);
  }

  @Test
  void testMac399Ape() throws Exception {
    detect("mac-399.ape", ApeFile.class);
  }

  @Test
  void testZerodivApe() throws Exception {
    detect("zerodiv.ape", ApeFile.class);
  }

  @Test
  void testEmptyTta() throws Exception {
    detect("empty.tta", TrueAudioFile.class);
  }

  @Test
  void testTaggedTta() throws Exception {
    detect("tagged.tta", TrueAudioFile.class);
  }

  @Test
  void testBlankVideoM4v() throws Exception {
    detect("blank_video.m4v", Mp4File.class);
  }

  @Test
  void testCovrJunkM4a() throws Exception {
    detect("covr-junk.m4a", Mp4File.class);
  }

  @Test
  void testEmptyAlacM4a() throws Exception {
    detect("empty_alac.m4a", Mp4File.class);
  }

  @Test
  void testGnreM4a() throws Exception {
    detect("gnre.m4a", Mp4File.class);
  }

  @Test
  void testHasTagsM4a() throws Exception {
    detect("has-tags.m4a", Mp4File.class);
  }

  @Test
  void testIlstIsLastM4a() throws Exception {
    detect("ilst-is-last.m4a", Mp4File.class);
  }

  @Test
  void testInfloopM4a() throws Exception {
    detect("infloop.m4a", Mp4File.class);
  }

  @Test
  void testNoTags3g2() throws Exception {
    detect("no-tags.3g2", Mp4File.class);
  }

  @Test
  void testNoTagsM4a() throws Exception {
    detect("no-tags.m4a", Mp4File.class);
  }

  @Test
  void testNonFullMetaM4a() throws Exception {
    detect("non-full-meta.m4a", Mp4File.class);
  }

  @Test
  void testNonprintableAtomTypeM4a() throws Exception {
    detect("nonprintable-atom-type.m4a", Mp4File.class);
  }

  @Test
  void testZeroLengthMdatM4a() throws Exception {
    detect("zero-length-mdat.m4a", Mp4File.class);
  }

  @Test
  void testLosslessWma() throws Exception {
    detect("lossless.wma", AsfFile.class);
  }

  @Test
  void testRealExampleWma() throws Exception {
    detect("real_example.wma", AsfFile.class);
  }

  @Test
  void testSilence1Wma() throws Exception {
    detect("silence-1.wma", AsfFile.class);
  }

  @Test
  void testAlawAifc() throws Exception {
    detect("alaw.aifc", AiffFile.class);
  }

  @Test
  void testDuplicateId3v2Aiff() throws Exception {
    detect("duplicate_id3v2.aiff", AiffFile.class);
  }

  @Test
  void testEmptyAiff() throws Exception {
    detect("empty.aiff", AiffFile.class);
  }

  @Test
  void testNoiseAif() throws Exception {
    detect("noise.aif", AiffFile.class);
  }

  @Test
  void testNoiseOddAif() throws Exception {
    detect("noise_odd.aif", AiffFile.class);
  }

  @Test
  void testSegfaultAif() throws Exception {
    detect("segfault.aif", AiffFile.class);
  }

  @Test
  void testAlawWav() throws Exception {
    detect("alaw.wav", WavFile.class);
  }

  @Test
  void testDuplicateTagsWav() throws Exception {
    detect("duplicate_tags.wav", WavFile.class);
  }

  @Test
  void testEmptyWav() throws Exception {
    detect("empty.wav", WavFile.class);
  }

  @Test
  void testFloat64Wav() throws Exception {
    detect("float64.wav", WavFile.class);
  }

  @Test
  void testInfloopWav() throws Exception {
    detect("infloop.wav", WavFile.class);
  }

  @Test
  void testInvalidChunkWav() throws Exception {
    detect("invalid-chunk.wav", WavFile.class);
  }

  @Test
  void testPcmWithFactChunkWav() throws Exception {
    detect("pcm_with_fact_chunk.wav", WavFile.class);
  }

  @Test
  void testSegfaultWav() throws Exception {
    detect("segfault.wav", WavFile.class);
  }

  @Test
  void testUint8weWav() throws Exception {
    detect("uint8we.wav", WavFile.class);
  }

  @Test
  void testZeroSizeChunkWav() throws Exception {
    detect("zero-size-chunk.wav", WavFile.class);
  }

  @Test
  void testEmpty10msDsf() throws Exception {
    detect("empty10ms.dsf", DsfFile.class);
  }

  @Test
  void testEmpty10msDff() throws Exception {
    detect("empty10ms.dff", DsdiffFile.class);
  }

  @Test
  void test2SecSilenceShn() throws Exception {
    detect("2sec-silence.shn", ShortenFile.class);
  }

  @Test
  void testNoTagsMka() throws Exception {
    detect("no-tags.mka", MatroskaFile.class);
  }

  @Test
  void testNoTagsWebm() throws Exception {
    detect("no-tags.webm", MatroskaFile.class);
  }

  @Test
  void testOptimizedMkv() throws Exception {
    detect("optimized.mkv", MatroskaFile.class);
  }

  @Test
  void testTagsBeforeCuesMkv() throws Exception {
    detect("tags-before-cues.mkv", MatroskaFile.class);
  }

  @Test
  void testNullSegfaultOga() throws Exception {
    detect("segfault.oga", null);
  }

  static void detect(String name, Class<? extends FileBase> expected) throws Exception {
    try (var source = new FileStream(fixture(name))) {
      assertTrue(source.isOpen());
      var stream = new ByteVectorStream(source.readBlock(source.length()));
      if (expected == null) {
        assertThrows(TagLibException.class, () -> new AudioFile(stream));
      } else {
        try (var ref = new AudioFile(stream)) {
          assertTrue(ref.isOpen());
          assertInstanceOf(expected, ref.file());
        }
      }
      assertTrue(stream.isOpen());
    }
  }
}
