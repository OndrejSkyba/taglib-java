package cz.skyba.taglib.parity.support;

import cz.skyba.taglib.AudioFile;
import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.TagLibException;
import cz.skyba.taglib.dsdiff.DsdiffFile;
import cz.skyba.taglib.dsdiff.DsdiffSaveOptions;
import cz.skyba.taglib.dsf.DsfFile;
import cz.skyba.taglib.id3.v2.Tag.Version;
import cz.skyba.taglib.matroska.MatroskaFile;
import cz.skyba.taglib.matroska.WriteStyle;
import cz.skyba.taglib.mp4.Tag;
import cz.skyba.taglib.mpeg.MpegFile;
import cz.skyba.taglib.mpeg.MpegSaveOptions;
import cz.skyba.taglib.riff.aiff.AiffFile;
import cz.skyba.taglib.riff.wav.WavFile;
import cz.skyba.taglib.riff.wav.WavSaveOptions;

import java.io.UncheckedIOException;

/** Translates Java save exceptions to the native oracle's boolean result. */
public final class SaveOutcome {
  private SaveOutcome() { }

  public static boolean save(Runnable action) {
    try {
      action.run();
      return true;
    } catch (TagLibException | UncheckedIOException | UnsupportedOperationException failure) {
      return false;
    }
  }

  public static boolean save(FileBase file) { return file != null && save(file::save); }
  public static boolean save(AudioFile file) { return file != null && save(file::save); }
  public static boolean save(Tag tag) { return save(tag::save); }

  public static boolean save(MpegFile file, int mask) {
    return save(() -> file.save(new MpegSaveOptions(
      TagSelections.mpeg(mask), FileBase.StripTags.STRIP_OTHERS, Version.V4, FileBase.DuplicateTags.DUPLICATE)));
  }

  public static boolean save(MpegFile file, int mask, FileBase.StripTags strip) {
    return save(() -> file.save(new MpegSaveOptions(
      TagSelections.mpeg(mask), strip, Version.V4, FileBase.DuplicateTags.DUPLICATE)));
  }

  public static boolean save(MpegFile file, int mask, FileBase.StripTags strip, Version version) {
    return save(() -> file.save(new MpegSaveOptions(
      TagSelections.mpeg(mask), strip, version, FileBase.DuplicateTags.DUPLICATE)));
  }

  public static boolean save(MpegFile file, int mask, FileBase.StripTags strip, Version version, FileBase.DuplicateTags duplicate) {
    return save(() -> file.save(new MpegSaveOptions(
      TagSelections.mpeg(mask), strip, version, duplicate)));
  }

  public static boolean save(WavFile file, int mask) {
    return save(() -> file.save(new WavSaveOptions(
      TagSelections.wav(mask), FileBase.StripTags.STRIP_OTHERS, Version.V4)));
  }

  public static boolean save(WavFile file, int mask, FileBase.StripTags strip) {
    return save(() -> file.save(new WavSaveOptions(
      TagSelections.wav(mask), strip, Version.V4)));
  }

  public static boolean save(WavFile file, int mask, FileBase.StripTags strip, Version version) {
    return save(() -> file.save(new WavSaveOptions(
      TagSelections.wav(mask), strip, version)));
  }

  public static boolean save(DsdiffFile file, int mask) {
    return save(() -> file.save(new DsdiffSaveOptions(
      TagSelections.dsdiff(mask), FileBase.StripTags.STRIP_OTHERS, Version.V4)));
  }

  public static boolean save(DsdiffFile file, int mask, FileBase.StripTags strip) {
    return save(() -> file.save(new DsdiffSaveOptions(
      TagSelections.dsdiff(mask), strip, Version.V4)));
  }

  public static boolean save(DsdiffFile file, int mask, FileBase.StripTags strip, Version version) {
    return save(() -> file.save(new DsdiffSaveOptions(
      TagSelections.dsdiff(mask), strip, version)));
  }

  public static boolean save(DsfFile file, Version version) {
    return save(() -> file.save(version));
  }

  public static boolean save(AiffFile file, Version version) {
    return save(() -> file.save(version));
  }

  public static boolean save(MatroskaFile file, WriteStyle style) {
    return save(() -> file.save(style));
  }
}
