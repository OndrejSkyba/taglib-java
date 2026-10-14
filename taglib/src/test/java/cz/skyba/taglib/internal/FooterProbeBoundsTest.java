package cz.skyba.taglib.internal;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.TagBase;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.FileStream;
import cz.skyba.taglib.toolkit.IOStream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FooterProbeBoundsTest {
  @TempDir
  Path directory;

  private IOStream stream(boolean memory, ByteVector bytes) throws IOException {
    if (memory) {
      return new ByteVectorStream(bytes);
    }
    var path = directory.resolve("bytes");
    Files.write(path, bytes.toByteArray());
    return new FileStream(path);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void undersizedFilesCannotContainFooterTags(boolean memory) throws Exception {
    for (int length : new int[]{0, 3, 8, 31, 32, 127}) {
      var bytes = new ByteVector("TAGAPETAGEX").resize(length);
      try (var stream = stream(memory, bytes)) {
        var file = new PlainFile(stream);
        stream.seek(length);
        assertEquals(-1, TagUtils.findID3v1(file));
        assertEquals(length, stream.tell());
        assertEquals(-1, NativeTagIO.id3v1(stream));
        assertEquals(length, stream.tell());
        if (length < 32) {
          assertEquals(-1, TagUtils.findAPE(file, -1));
          assertEquals(length, stream.tell());
          assertEquals(-1, NativeTagIO.ape(stream, -1));
          assertEquals(length, stream.tell());
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void id3v1MinimumExtentAndDetectionBoundariesStillWork(boolean memory) throws Exception {
    for (int length : new int[]{128, 129, 130, 131, 159}) {
      var bytes = new ByteVector(length, (byte) 0);
      int at = length - 128;
      for (int i = 0; i < 3; i++) bytes.set(at + i, (byte) "TAG".charAt(i));
      try (var stream = stream(memory, bytes)) {
        var file = new PlainFile(stream);
        assertEquals(at, TagUtils.findID3v1(file));
        assertEquals(at, NativeTagIO.id3v1(stream));
        assertEquals(-1, TagUtils.findAPE(file, at));
        assertEquals(-1, NativeTagIO.ape(stream, at));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void minimumApeFooterIsFoundWithAndWithoutId3v1(boolean memory) throws Exception {
    var footer = new ByteVector("APETAGEX").resize(32);
    for (boolean legacy : new boolean[]{false, true}) {
      var bytes = new ByteVector(footer);
      if (legacy) bytes.append(new ByteVector("TAG").resize(128));
      try (var stream = stream(memory, bytes)) {
        var file = new PlainFile(stream);
        long id3v1 = legacy ? 32 : -1;
        assertEquals(id3v1, TagUtils.findID3v1(file));
        assertEquals(0, TagUtils.findAPE(file, id3v1));
        assertEquals(id3v1, NativeTagIO.id3v1(stream));
        assertEquals(0, NativeTagIO.ape(stream, id3v1));
      }
    }
  }

  private static final class PlainFile extends FileBase {
    PlainFile(IOStream stream) {
      super(stream);
    }

    @Override
    public TagBase tag() {
      return null;
    }

    @Override
    public AudioProperties audioProperties() {
      return null;
    }

    @Override
    public void save() {
      throw new cz.skyba.taglib.TagLibException("Test save rejected");
    }
  }
}
