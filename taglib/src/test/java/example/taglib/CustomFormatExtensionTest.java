// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package example.taglib;

import cz.skyba.taglib.*;
import cz.skyba.taglib.matroska.MatroskaFile;
import cz.skyba.taglib.mp4.Mp4File;
import cz.skyba.taglib.ogg.vorbis.VorbisFile;
import cz.skyba.taglib.s3m.S3mFile;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.IOStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A consumer package can implement a format without importing any internal classes.
 */
class CustomFormatExtensionTest {
  @AfterEach
  void clearResolvers() {
    AudioFile.clearFileTypeResolvers();
  }

  private static final class CustomFile extends FileBase {
    private final TagBase tag = new cz.skyba.taglib.ape.Tag();
    private int writes;

    CustomFile(IOStream input) {
      super(input);
      if (!readBlock(4).equals(new ByteVector("DEMO"))) {
        throw new InvalidAudioFileException("Not a DEMO file");
      }
      tag.setTitle(readBlock(length() - 4).toString());
    }

    @Override
    public TagBase tag() {
      return tag;
    }

    @Override
    public AudioProperties audioProperties() {
      return null;
    }

    @Override
    public void save() {
      requireWritable();
      CustomTagWriter.write(ownerStream(), tag.title());
    }

    @Override
    protected void writeBlock(ByteVector data) {
      writes++;
      super.writeBlock(data);
    }
  }

  private static final class CustomTagWriter {
    static void write(IOStream target, String title) {
      target.seek(4);
      target.writeBlock(new ByteVector(title));
      target.truncate(target.tell());
    }
  }

  @Test
  void customFormatAndCollaboratingTagWriterRemainExtensible() {
    AudioFile.addFileTypeResolver(new AudioFile.StreamTypeResolver() {
      @Override
      public FileBase createFile(Path path, ReadOptions options) {
        return null;
      }

      @Override
      public FileBase createFileFromStream(IOStream input, ReadOptions options) {
        return new CustomFile(input);
      }
    });
    try (var stream = new ByteVectorStream(new ByteVector("DEMOold title"))) {
      try (var file = new AudioFile(stream)) {
        var custom = assertInstanceOf(CustomFile.class, file.file());
        assertEquals("old title", file.tag().title());
        file.tag().setTitle("new");
        file.save();
        assertEquals(1, custom.writes, "The stream view must invoke the subclass write hook");
        assertEquals("DEMOnew", stream.data().toString());
      }
      assertTrue(stream.isOpen(), "Closing the file must preserve its borrowed input");
      stream.seek(0);
      try (var reopened = new AudioFile(stream)) {
        assertEquals("new", reopened.tag().title());
      }
    }
  }

  @Test
  void builtInTypedFilesDoNotExposeRawMutationMethods() {
    for (var type : List.of(FileBase.class, Mp4File.class,
      MatroskaFile.class, VorbisFile.class,
      S3mFile.class)) {
      for (var method : type.getMethods()) {
        assertFalse(List.of("writeBlock", "insert", "removeBlock", "truncate").contains(method.getName()),
          () -> type.getName() + " exposes " + method);
      }
    }
    assertTrue(Modifier.isProtected(assertDoesNotThrow(() ->
      FileBase.class.getDeclaredMethod("writeBlock", ByteVector.class)).getModifiers()));
  }
}
