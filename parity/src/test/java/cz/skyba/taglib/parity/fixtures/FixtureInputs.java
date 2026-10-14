package cz.skyba.taglib.parity.fixtures;

import java.nio.file.Path;

/**
 * Generates independent inputs at runtime; real audio samples come from the verified cache.
 */
public final class FixtureInputs {
  private FixtureInputs() {
  }

  public static Path generate(String name, Path directory) throws Exception {
    return FixtureCache.copy(name, directory, path -> generateFresh(name, path));
  }

  private static void generateFresh(String name, Path directory) throws Exception {
    switch (name) {
      case "aiff-files" -> AiffFileFixtures.generate(directory);
      case "aiff-properties" -> AiffPropertiesFixtures.generate(directory);
      case "ape-files" -> ApeMusepackWavPackFixtures.apeFiles(directory);
      case "asf-duplicate-objects-and-partial-headers" -> AsfFixtures.duplicateObjects(directory);
      case "asf-mutable" -> AsfFixtures.mutable(directory);
      case "asf-properties" -> AsfPropertiesFixtures.generate(directory);
      case "dsd-tta-properties" -> DsdAudioPropertiesFixtures.generate(directory);
      case "dsdiff-diin" -> DsdiffFixtures.diin(directory);
      case "dsdiff-files" -> DsdiffFixtures.files(directory);
      case "dsf-files" -> Id3AudioFileFixtures.dsf(directory);
      case "flac-wave-tag-owners" -> FlacWaveFixtures.generate(directory);
      case "id3-counters-ratings-and-relative-volume" -> Id3NumericFixtures.generate(directory);
      case "id3-frame-headers-payloads-and-translation" -> Id3FrameHeaderFixtures.generate(directory);
      case "id3-headers" -> Id3HeaderFixtures.generate(directory);
      case "id3-lyrics-events-and-timed-text" -> Id3TimedFixtures.generate(directory);
      case "id3-owner" -> Id3OwnerFixtures.generate(directory);
      case "id3-picture" -> Id3PictureFixtures.generate(directory);
      case "id3-text" -> Id3TextFixtures.generate(directory);
      case "id3-version-conversion-and-repeated-saves" -> Id3ReleaseFixtures.generate(directory);
      case "id3v1-riff-info-reparsing" -> LegacyTagFixtures.reparsing(directory);
      case "id3v1" -> LegacyTagFixtures.id3v1(directory);
      case "matroska-mutable" -> MatroskaFixtures.mutable(directory);
      case "matroska-properties" -> MatroskaFixtures.properties(directory);
      case "mp4-mutable" -> Mp4Fixtures.mutable(directory);
      case "mp4-properties" -> Mp4Fixtures.properties(directory);
      case "mpc-files" -> ApeMusepackWavPackFixtures.musepackFiles(directory);
      case "mpeg-files" -> Id3AudioFileFixtures.mpeg(directory);
      case "mpeg-headers" -> MpegFixtures.headers(directory);
      case "mpeg-properties" -> MpegFixtures.properties(directory);
      case "ogg-properties" -> OggFixtures.properties(directory);
      case "ogg-tag-owners" -> OggFixtures.owners(directory);
      case "shorten-properties" -> ShortenFixtures.properties(directory);
      case "tracker-files" -> TrackerFixtures.files(directory);
      case "tracker-properties" -> TrackerFixtures.properties(directory);
      case "tracker-support" -> ShortenFixtures.trackerSupport(directory);
      case "trueaudio-files" -> Id3AudioFileFixtures.trueaudio(directory);
      case "wavpack-files" -> ApeMusepackWavPackFixtures.wavpackFiles(directory);
      default -> throw new IllegalArgumentException("Unknown fixture recipes: " + name);
    }
  }
}
