package cz.skyba.taglib.parity.support;

import cz.skyba.taglib.*;
import cz.skyba.taglib.toolkit.IOStream;
import cz.skyba.taglib.toolkit.Variant;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Test-only translation of Java open/save exceptions to the C++ oracle's null/boolean results.
 * It does not emulate C++ copying, assignment, reference counting, or swap.
 */
public final class FileRefAdapter implements AutoCloseable {
  private final AudioFile file;

  @FunctionalInterface
  private interface Open {
    AudioFile open();
  }

  private FileRefAdapter(Open open) {
    AudioFile resolved;
    try {
      resolved = open.open();
    } catch (TagLibException | java.io.UncheckedIOException failure) {
      resolved = null;
    }
    file = resolved;
  }

  public FileRefAdapter(Path path) { this(() -> new AudioFile(path)); }
  public FileRefAdapter(Path path, ReadOptions options) { this(() -> new AudioFile(path, options)); }
  public FileRefAdapter(Path path, boolean read) { this(path, read, ReadStyle.AVERAGE); }
  public FileRefAdapter(Path path, boolean read, ReadStyle style) {
    this(path, new ReadOptions(read, style, ReadOptions.DEFAULT.maxMetadataBytes()));
  }
  public FileRefAdapter(IOStream stream) { this(() -> new AudioFile(stream)); }
  public FileRefAdapter(IOStream stream, ReadOptions options) { this(() -> new AudioFile(stream, options)); }
  public FileRefAdapter(IOStream stream, boolean read) { this(stream, read, ReadStyle.AVERAGE); }
  public FileRefAdapter(IOStream stream, boolean read, ReadStyle style) {
    this(stream, new ReadOptions(read, style, ReadOptions.DEFAULT.maxMetadataBytes()));
  }

  public interface FileTypeResolver extends AudioFile.FileTypeResolver { }
  public abstract static class StreamTypeResolver extends AudioFile.StreamTypeResolver implements FileTypeResolver { }

  public static <T extends AudioFile.FileTypeResolver> T addFileTypeResolver(T resolver) {
    return AudioFile.addFileTypeResolver(resolver);
  }
  public static void clearFileTypeResolvers() { AudioFile.clearFileTypeResolvers(); }
  public static List<String> defaultFileExtensions() { return AudioFile.defaultFileExtensions(); }

  public boolean isNull() { return file == null || !file.isOpen(); }
  public FileBase file() { return isNull() ? null : file.file(); }
  public TagBase tag() { return isNull() ? null : file.tag(); }
  public AudioProperties audioProperties() { return isNull() ? null : file.audioProperties(); }
  public PropertyMap properties() { return isNull() ? PropertyMap.EMPTY : file.properties(); }
  public PropertyUpdateResult replaceProperties(PropertyMap values) { return isNull()
    ? PropertyUpdateResult.fromRejections(values, PropertyMap.EMPTY) : file.replaceProperties(values); }
  public void removeUnsupportedProperties(List<String> values) { if (!isNull()) file.removeUnsupportedProperties(values); }
  public List<String> complexPropertyKeys() { return isNull() ? List.of() : file.complexPropertyKeys(); }
  public List<Map<String, Variant>> complexProperties(String key) { return isNull() ? List.of() : file.complexProperties(key); }
  public boolean setComplexProperties(String key, List<Map<String, Variant>> values) {
    return !isNull() && file.setComplexProperties(key, values);
  }
  public boolean save() {
    if (isNull()) return false;
    try {
      file.save();
      return true;
    } catch (TagLibException | java.io.UncheckedIOException | UnsupportedOperationException failure) {
      return false;
    }
  }
  @Override public void close() { if (file != null) file.close(); }
}
