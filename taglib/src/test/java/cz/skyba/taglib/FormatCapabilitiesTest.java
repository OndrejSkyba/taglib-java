package cz.skyba.taglib;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class FormatCapabilitiesTest {
  @TempDir
  Path directory;

  static Stream<Arguments> formats() {
    return Stream.of(
      Arguments.of(Format.MPEG, "lame_cbr.mp3"),
      Arguments.of(Format.FLAC, "no-tags.flac"),
      Arguments.of(Format.OGG_VORBIS, "test.ogg"),
      Arguments.of(Format.OGG_OPUS, "correctness_gain_silent_output.opus"),
      Arguments.of(Format.OGG_SPEEX, "empty.spx"),
      Arguments.of(Format.OGG_FLAC, "empty_flac.oga"),
      Arguments.of(Format.MP4, "has-tags.m4a"),
      Arguments.of(Format.ASF, "silence-1.wma"),
      Arguments.of(Format.WAV, "empty.wav"),
      Arguments.of(Format.AIFF, "noise.aif"),
      Arguments.of(Format.APE, "mac-399.ape"),
      Arguments.of(Format.WAVPACK, "click.wv"),
      Arguments.of(Format.MPC, "click.mpc"),
      Arguments.of(Format.TRUEAUDIO, "tagged.tta"),
      Arguments.of(Format.DSF, "empty10ms.dsf"),
      Arguments.of(Format.DSDIFF, "empty10ms.dff"),
      Arguments.of(Format.MOD, "test.mod"),
      Arguments.of(Format.S3M, "test.s3m"),
      Arguments.of(Format.IT, "test.it"),
      Arguments.of(Format.XM, "test.xm"),
      Arguments.of(Format.MATROSKA, "no-tags.mka"),
      Arguments.of(Format.SHORTEN, "2sec-silence.shn"));
  }

  @ParameterizedTest
  @MethodSource("formats")
  void advertisedCatalogKeysSurviveSaving(Format format, String fixture) throws Exception {
    Path path = directory.resolve(fixture);
    for (var key : PropertyKey.values()) {
      if (!format.supportedPropertyKeys().contains(key.key())) {
        continue;
      }
      Files.copy(AudioFixtures.fixture(fixture), path, StandardCopyOption.REPLACE_EXISTING);
      String value = sampleValue(key);

      TagLib.edit(path).set(key, value).save();

      String saved = TagLib.read(path).properties().first(key);
      if (key.equals(PropertyKey.COMMENT) && Set.of(Format.MOD, Format.S3M, Format.IT, Format.XM).contains(format)) {
        // Tracker comments include empty instrument/sample name slots as trailing lines.
        saved = saved.stripTrailing();
      }
      assertEquals(value, saved, format + ": " + key);
    }
  }

  @ParameterizedTest
  @MethodSource("formats")
  void customPropertyCapabilityMatchesEditing(Format format, String fixture) throws Exception {
    Path path = AudioFixtures.copy(directory, fixture);
    var edit = TagLib.edit(path).set("MY_CUSTOM_FIELD", "Custom value");
    if (format.supportsCustomProperties()) {
      edit.save();
      assertEquals("Custom value", TagLib.read(path).properties().first("MY_CUSTOM_FIELD"));
    } else {
      byte[] before = Files.readAllBytes(path);
      assertThrows(MetadataEditException.class, edit::save);
      assertArrayEquals(before, Files.readAllBytes(path));
    }
  }

  @ParameterizedTest
  @MethodSource("formats")
  void advertisedPictureRolesMatchEditing(Format format, String fixture) throws Exception {
    Path path = directory.resolve(fixture);
    Files.copy(AudioFixtures.fixture(fixture), path);
    try (var file = new AudioFile(path, ReadOptions.TAGS_ONLY)) {
      assertEquals(!format.supportedPictureTypes().isEmpty(), file.supportsPictureEditing());
    }
    for (var type : format.supportedPictureTypes()) {
      Files.copy(AudioFixtures.fixture(fixture), path, StandardCopyOption.REPLACE_EXISTING);
      var picture = new Picture(type, "image/jpeg", "", ComplexPropertyAccessTest.PIXEL.data());

      TagLib.edit(path).replacePictures(picture).save();

      assertTrue(TagLib.read(path).pictures().stream().anyMatch(saved -> saved.type() == type.code()),
        format + ": " + type);
    }
  }

  @Test
  void capabilitiesExposeRestrictionsWithoutOpeningAFile() {
    assertTrue(Format.ASF.supportsProperty(PropertyKey.ALBUM_ARTIST));
    assertFalse(Format.ASF.supportsProperty(PropertyKey.TRACK_TOTAL));
    assertFalse(Format.ASF.supportsProperty(PropertyKey.DISC_TOTAL));
    assertFalse(Format.IT.supportsProperty(PropertyKey.TRACKER_NAME));
    assertTrue(Format.XM.supportsProperty(PropertyKey.TRACKER_NAME));
    assertTrue(Format.SHORTEN.supportedPropertyKeys().isEmpty());
    assertEquals(Set.of(PictureType.OTHER), Format.MP4.supportedPictureTypes());
    assertEquals(Set.of(PictureType.FRONT_COVER, PictureType.BACK_COVER), Format.APE.supportedPictureTypes());
    assertTrue(Format.MP4.supportsPictureType(PictureType.OTHER));
    assertFalse(Format.MP4.supportsPictureType(PictureType.FRONT_COVER));
    assertTrue(Format.APE.supportsPictureType(PictureType.BACK_COVER));
    assertFalse(Format.MOD.supportsPictureType(PictureType.FRONT_COVER));
    assertTrue(Format.FLAC.supportsPictureType(42));
    assertFalse(Format.MP4.supportsPictureType(42));
    assertFalse(Format.APE.supportsPictureType(42));
    assertFalse(Format.MOD.supportsPictureType(42));
    assertThrows(UnsupportedOperationException.class, () -> Format.ASF.supportedPropertyKeys().clear());
    assertThrows(UnsupportedOperationException.class, () -> Format.FLAC.supportedPropertyKeys().clear());
    assertThrows(UnsupportedOperationException.class, () -> Format.FLAC.supportedPictureTypes().clear());
  }

  private static String sampleValue(PropertyKey key) {
    return switch (key) {
      case DATE, ORIGINAL_DATE -> "2026";
      case TRACK_NUMBER -> "3/12";
      case DISC_NUMBER -> "1/2";
      case TRACK_TOTAL -> "12";
      case DISC_TOTAL -> "2";
      case BPM -> "120";
      case COMPILATION -> "1";
      case REPLAYGAIN_TRACK_GAIN, REPLAYGAIN_ALBUM_GAIN -> "-7.0 dB";
      case REPLAYGAIN_TRACK_PEAK, REPLAYGAIN_ALBUM_PEAK -> "0.98";
      default -> "Example";
    };
  }
}
