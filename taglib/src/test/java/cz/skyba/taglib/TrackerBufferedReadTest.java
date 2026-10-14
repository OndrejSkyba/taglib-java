package cz.skyba.taglib;

import cz.skyba.taglib.internal.codec.TrackerFile;
import cz.skyba.taglib.it.ItFile;
import cz.skyba.taglib.mod.ModFile;
import cz.skyba.taglib.s3m.S3mFile;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.IOStream;
import cz.skyba.taglib.xm.XmFile;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class TrackerBufferedReadTest {
  @TempDir
  Path directory;
  private static final ThreadLocal<List<String>> READS = ThreadLocal.withInitial(ArrayList::new);

  private static void text(byte[] bytes, int offset, String value) {
    byte[] data = value.getBytes(StandardCharsets.ISO_8859_1);
    System.arraycopy(data, 0, bytes, offset, data.length);
  }

  private static void u16(byte[] bytes, int offset, int value) {
    bytes[offset] = (byte) value;
    bytes[offset + 1] = (byte) (value >>> 8);
  }

  private static void u32(byte[] bytes, int offset, int value) {
    for (int i = 0; i < 4; i++) bytes[offset + i] = (byte) (value >>> (i * 8));
  }

  private static byte[] fixture(Format format) {
    return switch (format) {
      case MOD -> {
        byte[] bytes = new byte[1084];
        text(bytes, 0, "Title");
        text(bytes, 20, "Instrument");
        text(bytes, 1080, "M.K.");
        yield bytes;
      }
      case S3M -> {
        byte[] bytes = new byte[192];
        text(bytes, 0, "Title");
        bytes[28] = 26;
        bytes[29] = 16;
        text(bytes, 44, "SCRM");
        yield bytes;
      }
      case IT -> {
        byte[] bytes = new byte[192];
        text(bytes, 0, "IMPM");
        text(bytes, 4, "Title");
        yield bytes;
      }
      case XM -> {
        // Place the instrument beyond the construction window, retaining header padding.
        byte[] bytes = new byte[8229];
        text(bytes, 0, "Extended Module: ");
        text(bytes, 17, "Title");
        bytes[37] = 26;
        text(bytes, 38, "Tracker");
        u16(bytes, 58, 0x104);
        u32(bytes, 60, 8140);
        u16(bytes, 72, 1);
        u32(bytes, 8200, 29);
        text(bytes, 8204, "Instrument");
        yield bytes;
      }
      default -> throw new AssertionError(format);
    };
  }

  private static TrackerFile open(Format format, Path path, ReadStyle style) throws IOException {
    return switch (format) {
      case MOD -> new ModFile(path, style);
      case S3M -> new S3mFile(path, style);
      case IT -> new ItFile(path, style);
      case XM -> new XmFile(path, style);
      default -> throw new AssertionError(format);
    };
  }

  private static TrackerFile custom(Format format, Path path) throws IOException {
    return switch (format) {
      case MOD -> new CustomMod(path);
      case S3M -> new CustomS3m(path);
      case IT -> new CustomIt(path);
      case XM -> new CustomXm(path);
      default -> throw new AssertionError(format);
    };
  }

  private static TrackerFile custom(Format format, IOStream stream) throws IOException {
    return switch (format) {
      case MOD -> new CustomMod(stream);
      case S3M -> new CustomS3m(stream);
      case IT -> new CustomIt(stream);
      case XM -> new CustomXm(stream);
      default -> throw new AssertionError(format);
    };
  }

  private static final class CustomMod extends ModFile {
    CustomMod(Path path) throws IOException {
      super(path);
    }

    CustomMod(IOStream stream) throws IOException {
      super(stream);
    }

    @Override
    public ByteVector readBlock(long count) {
      READS.get().add(tell() + ":" + count);
      return super.readBlock(count);
    }
  }

  private static final class CustomS3m extends S3mFile {
    CustomS3m(Path path) throws IOException {
      super(path);
    }

    CustomS3m(IOStream stream) throws IOException {
      super(stream);
    }

    @Override
    public ByteVector readBlock(long count) {
      READS.get().add(tell() + ":" + count);
      return super.readBlock(count);
    }
  }

  private static final class CustomIt extends ItFile {
    CustomIt(Path path) throws IOException {
      super(path);
    }

    CustomIt(IOStream stream) throws IOException {
      super(stream);
    }

    @Override
    public ByteVector readBlock(long count) {
      READS.get().add(tell() + ":" + count);
      return super.readBlock(count);
    }
  }

  private static final class CustomXm extends XmFile {
    CustomXm(Path path) throws IOException {
      super(path);
    }

    CustomXm(IOStream stream) throws IOException {
      super(stream);
    }

    @Override
    public ByteVector readBlock(long count) {
      READS.get().add(tell() + ":" + count);
      return super.readBlock(count);
    }
  }

  private Path input(Format format) throws Exception {
    Path path = directory.resolve("input." + format.name().toLowerCase(Locale.ROOT));
    Files.write(path, fixture(format));
    return path;
  }

  @ParameterizedTest
  @EnumSource(value = Format.class, names = {"MOD", "S3M", "IT", "XM"})
  void ownedReadsMatchBorrowedAndFileRefSnapshots(Format format) throws Exception {
    Path path = input(format);
    for (var style : ReadStyle.values()) {
      var options = new ReadOptions(true, style, ReadOptions.DEFAULT.maxMetadataBytes());
      try (var memory = new ByteVectorStream(fixture(format)) {
        @Override
        public String name() {
          return path.toString();
        }
      }; var borrowed = new AudioFile(memory, options); var owned = open(format, path, style)) {
        assertTrue(owned.isValid());
        assertTrue(borrowed.isOpen());
        assertEquals(borrowed.properties(), owned.properties());
        assertEquals(borrowed.audioProperties(), owned.audioProperties());
        assertEquals(assertInstanceOf(TrackerFile.class, borrowed.file()).tell(), owned.tell());
        memory.seek(0);
        assertEquals(TagLib.read(memory, options), TagLib.read(path, options));
      }
    }
  }

  @ParameterizedTest
  @EnumSource(value = Format.class, names = {"MOD", "S3M", "IT", "XM"})
  void customOwnersRetainTheirBorrowedReadSequence(Format format) throws Exception {
    Path path = input(format);
    try {
      READS.get().clear();
      List<String> expected;
      try (var owned = custom(format, path)) {
        assertTrue(owned.isValid());
        expected = List.copyOf(READS.get());
      }
      READS.get().clear();
      try (var memory = new ByteVectorStream(fixture(format)); var borrowed = custom(format, memory)) {
        assertTrue(borrowed.isValid());
        assertEquals(expected, READS.get());
      }
    } finally {
      READS.remove();
    }
  }

  @ParameterizedTest
  @EnumSource(value = Format.class, names = {"MOD", "S3M", "IT", "XM"})
  void constructionWindowDoesNotRetainStaleBytesForReadsOrSaves(Format format) throws Exception {
    Path path = input(format);
    try (var file = open(format, path, ReadStyle.AVERAGE)) {
      int offset = switch (format) {
        case IT -> 4;
        case XM -> 17;
        default -> 0;
      };
      byte[] changed = fixture(format);
      changed[offset] = 'z';
      Files.write(path, changed);
      file.seek(offset);
      assertEquals(new ByteVector((byte) 'z'), file.readBlock(1));
      for (String title : List.of("First update", "Second update")) {
        file.tag().setTitle(title);
        file.save();
        assertEquals(title, TagLib.read(path).tag().title());
      }
    }
  }
}
