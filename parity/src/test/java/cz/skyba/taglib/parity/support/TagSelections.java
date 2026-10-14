package cz.skyba.taglib.parity.support;

import java.util.EnumSet;
import java.util.Set;

import cz.skyba.taglib.mpeg.MpegFile;
import cz.skyba.taglib.riff.wav.WavFile;
import cz.skyba.taglib.dsdiff.DsdiffFile;
import cz.skyba.taglib.flac.FlacFile;
import cz.skyba.taglib.trueaudio.TrueAudioFile;
import cz.skyba.taglib.ape.ApeFile;
import cz.skyba.taglib.wavpack.WavPackFile;
import cz.skyba.taglib.mpc.MpcFile;
import cz.skyba.taglib.mp4.Mp4File;

/** Translates C++ oracle masks to the Java API's format-specific tag selections. */
public final class TagSelections {
  private TagSelections() { }

  public static Set<MpegFile.TagType> mpeg(int mask) {
    var types = EnumSet.noneOf(MpegFile.TagType.class);
    if ((mask & 1) != 0) types.add(MpegFile.TagType.ID3V1);
    if ((mask & 2) != 0) types.add(MpegFile.TagType.ID3V2);
    if ((mask & 4) != 0) types.add(MpegFile.TagType.APE);
    return types;
  }

  public static Set<WavFile.TagType> wav(int mask) {
    var types = EnumSet.noneOf(WavFile.TagType.class);
    if ((mask & 1) != 0) types.add(WavFile.TagType.ID3V2);
    if ((mask & 2) != 0) types.add(WavFile.TagType.INFO);
    return types;
  }

  public static Set<DsdiffFile.TagType> dsdiff(int mask) {
    var types = EnumSet.noneOf(DsdiffFile.TagType.class);
    if ((mask & 2) != 0) types.add(DsdiffFile.TagType.ID3V2);
    if ((mask & 1) != 0) types.add(DsdiffFile.TagType.DIIN);
    return types;
  }

  public static Set<FlacFile.TagType> flac(int mask) {
    var types = EnumSet.noneOf(FlacFile.TagType.class);
    if ((mask & 2) != 0) types.add(FlacFile.TagType.ID3V1);
    if ((mask & 4) != 0) types.add(FlacFile.TagType.ID3V2);
    if ((mask & 1) != 0) types.add(FlacFile.TagType.XIPH_COMMENT);
    return types;
  }

  public static Set<TrueAudioFile.TagType> trueAudio(int mask) {
    var types = EnumSet.noneOf(TrueAudioFile.TagType.class);
    if ((mask & 1) != 0) types.add(TrueAudioFile.TagType.ID3V1);
    if ((mask & 2) != 0) types.add(TrueAudioFile.TagType.ID3V2);
    return types;
  }

  public static Set<ApeFile.TagType> ape(int mask) {
    var types = EnumSet.noneOf(ApeFile.TagType.class);
    if ((mask & 1) != 0) types.add(ApeFile.TagType.ID3V1);
    if ((mask & 2) != 0) types.add(ApeFile.TagType.APE);
    return types;
  }

  public static Set<WavPackFile.TagType> wavPack(int mask) {
    var types = EnumSet.noneOf(WavPackFile.TagType.class);
    if ((mask & 1) != 0) types.add(WavPackFile.TagType.ID3V1);
    if ((mask & 2) != 0) types.add(WavPackFile.TagType.APE);
    return types;
  }

  public static Set<MpcFile.TagType> mpc(int mask) {
    var types = EnumSet.noneOf(MpcFile.TagType.class);
    if ((mask & 1) != 0) types.add(MpcFile.TagType.ID3V1);
    if ((mask & 2) != 0) types.add(MpcFile.TagType.ID3V2);
    if ((mask & 4) != 0) types.add(MpcFile.TagType.APE);
    return types;
  }

  public static Set<Mp4File.TagType> mp4(int mask) {
    var types = EnumSet.noneOf(Mp4File.TagType.class);
    if ((mask & 1) != 0) types.add(Mp4File.TagType.MP4);
    return types;
  }

}
