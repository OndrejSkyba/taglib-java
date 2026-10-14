// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.matroska;

import cz.skyba.taglib.*;
import cz.skyba.taglib.internal.*;
import cz.skyba.taglib.internal.codec.MatroskaCodec;
import cz.skyba.taglib.toolkit.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;

import static cz.skyba.taglib.internal.codec.MatroskaCodec.LiveState.ElementKind.*;

/**
 * An editable Matroska or WebM file with lazy metadata and attachment loading.
 * Path construction owns storage; stream construction borrows it. Tag, chapter and attachment
 * edits are staged until saving. Attachment access can read deferred payloads from storage,
 * so the file must remain open until those payloads have been loaded.
 */
public class MatroskaFile extends FileBase {
  private static final Variant EMPTY_VARIANT = new Variant();

  private final IOStream source;
  private final boolean owned;
  private final ReadOptions options;
  private final MatroskaCodec.LiveState state;
  private Tag tag;
  private Chapters chapters;
  private Attachments attachments;
  private Properties details;

  /**
   * Opens a Matroska or WebM file and owns the file handle.
   *
   * @param path the file to open
   * @throws UncheckedIOException if storage access fails
   * @throws TagLibException      if parsing rejects the container or its metadata
   * @see ReadOptions#DEFAULT
   */
  public MatroskaFile(Path path) {
    this(path, ReadOptions.DEFAULT);
  }

  /**
   * Opens a Matroska or WebM file and owns the file handle.
   *
   * @param path    the file to open
   * @param options audio property selection and metadata allocation limit
   * @throws UncheckedIOException if storage access fails
   * @throws TagLibException      if parsing rejects the container or its metadata
   */
  public MatroskaFile(Path path, ReadOptions options) {
    Objects.requireNonNull(options);
    this(new FileStream(path), true, options);
  }

  /**
   * Opens a Matroska or WebM file over a borrowed stream that remains open when the file closes.
   *
   * @param stream the open, seekable stream
   * @throws UncheckedIOException if storage access fails
   * @throws TagLibException      if parsing rejects the container or its metadata
   * @see ReadOptions#DEFAULT
   */
  public MatroskaFile(IOStream stream) {
    this(stream, ReadOptions.DEFAULT);
  }

  /**
   * Opens a Matroska or WebM file over a borrowed stream that remains open when the file closes.
   *
   * @param stream  the open, seekable stream
   * @param options audio property selection and metadata allocation limit
   * @throws UncheckedIOException if storage access fails
   * @throws TagLibException      if parsing rejects the container or its metadata
   */
  public MatroskaFile(IOStream stream, ReadOptions options) {
    this(stream, false, options);
  }

  private MatroskaFile(IOStream stream, boolean owned, ReadOptions options) {
    this(ConstructionReadStream.wrap(stream, owned), owned, options);
  }

  private MatroskaFile(ConstructionReadStream stream, boolean owned, ReadOptions options) {
    super(stream);
    source = stream.source();
    this.owned = owned;
    this.options = Objects.requireNonNull(options);
    stream.begin(getClass() == MatroskaFile.class || stream.usesDefaultIo(), options.maxMetadataBytes());
    try (var binary = new BinaryFile(ownerStream(), stream.isBuffered())) {
      state = new MatroskaCodec().readLive(binary, options);
      var metadata = state.metadata();
      if (state.has(TAG)) {
        tag = new Tag(metadata.simpleTags(), () -> state.mark(TAG));
        tag.setSegmentTitle(metadata.segmentTitle());
      }
      if (state.has(CHAPTER)) {
        chapters = new Chapters(metadata.chapters(), () -> state.mark(CHAPTER));
      }
      if (state.has(ATTACHMENT)) {
        var files = new ArrayList<AttachedFile>();
        for (var file : metadata.attachments().files()) {
          files.add(new AttachedFile(file.data().getDataOffset(), file.data().getDataSize(), file.fileName(), file.mediaType(),
            file.uid(), file.description()));
        }
        attachments = new Attachments(files, () -> state.mark(ATTACHMENT));
      }
      if (options.readAudioProperties()) {
        details = Properties.live(state.details(), source);
      }
    } catch (IOException failure) {
      var translated = new UncheckedIOException("Cannot read Matroska file: " + name(), failure);
      if (owned) {
        FailureSupport.closeOnFailure(stream, translated);
      }
      throw translated;
    } catch (RuntimeException | Error failure) {
      if (owned) {
        FailureSupport.closeOnFailure(stream, failure);
      }
      throw failure;
    } finally {
      stream.finish();
    }
  }

  /**
   * Returns the live tag view. An absent tag view is created in memory.
   *
   * @return the live view
   */
  @Override
  public Tag tag() {
    return getOrCreateTag();
  }

  /**
   * Returns the live tag view without creating one.
   *
   * @return the live view, or {@code null} if no view exists
   */
  public final Tag existingTag() {
    return tag;
  }

  /**
   * Returns the live tag view, creating it in memory if absent. Creation does not write storage.
   */
  public final Tag getOrCreateTag() {
    if (tag == null) {
      if (state != null) {
        state.ensure(TAG);
      }
      tag = new Tag(List.of(), () -> {
        if (state != null) {
          state.mark(TAG);
        }
      });
      if (state != null) {
        tag.setSegmentTitle(state.metadata().segmentTitle());
      }
    }
    return tag;
  }

  /**
   * Returns the live chapters view. An absent view is not created.
   *
   * @return the live view, or {@code null} if no view exists
   */
  public final Chapters chapters() {
    return chapters;
  }

  /**
   * Returns the live chapters view, creating it in memory if absent.
   *
   * @return the live view
   */
  public final Chapters getOrCreateChapters() {
    if (chapters == null) {
      if (state != null) {
        state.ensure(CHAPTER);
      }
      chapters = new Chapters(List.of(), () -> {
        if (state != null) {
          state.mark(CHAPTER);
        }
      });
    }
    return chapters;
  }

  /**
   * Returns the live attachments view. An absent view is not created.
   * Deferred attachment bytes are loaded before returning; the cursor can move.
   *
   * @return the live view, or {@code null} if no view exists
   * @throws UncheckedIOException if loading attachment data fails
   */
  public final Attachments attachments() {
    return attachments(false);
  }

  /**
   * Returns the live attachments view, creating it in memory if absent.
   * Deferred attachment bytes are loaded before returning; the cursor can move.
   *
   * @return the live view
   * @throws UncheckedIOException if loading attachment data fails
   */
  public final Attachments getOrCreateAttachments() {
    return attachments(true);
  }

  private Attachments attachments(boolean create) {
    if (attachments == null && create) {
      if (state != null) {
        state.ensure(ATTACHMENT);
      }
      attachments = new Attachments(List.of(), () -> {
        if (state != null) {
          state.mark(ATTACHMENT);
        }
      });
    }
    try {
      loadAttachedFileData();
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read attachments: " + name(), failure);
    }
    return attachments;
  }

  private void loadAttachedFileData() throws IOException {
    if (attachments == null) {
      return;
    }
    long budget = state == null ? 0 : state.metadataBytes();
    for (var file : attachments.storedFiles()) {
      budget = Math.addExact(budget, file.isDataDeferred() ? file.deferredDataSize() : file.loadedDataSize());
      if (budget > options.maxMetadataBytes()) {
        throw new IOException("Aggregate Matroska attachment data exceeds allocation limit");
      }
    }
    long position = tell();
    try {
      for (var file : attachments.storedFiles()) {
        if (file.isDataDeferred()) {
          seek(file.deferredDataOffset());
          file.setLoadedData(readBlock(file.deferredDataSize()).toByteArray());
        }
      }
    } finally {
      seek(position);
    }
  }

  @Override
  public AudioProperties audioProperties() {
    return details == null ? null : details.common();
  }

  public final Properties detailedProperties() {
    return details;
  }

  @Override
  public PropertyMap properties() {
    return tag == null ? PropertyMap.builder().build() : tag.properties();
  }

  @Override
  public PropertyUpdateResult replaceProperties(PropertyMap values) {
    if (tag == null) {
      if (state != null) {
        state.ensure(TAG);
      }
      tag = new Tag(List.of(), () -> {
        if (state != null) {
          state.mark(TAG);
        }
      });
    }
    return tag.replaceProperties(values);
  }

  @Override
  public void removeUnsupportedProperties(List<String> values) {
    if (tag != null) {
      tag.removeUnsupportedProperties(values);
    }
  }

  private static String key(AttachedFile file) {
    return file.mediaType().startsWith("image/")
      ? "PICTURE"
      : !file.fileName().isEmpty()
      ? file.fileName()
      : !file.mediaType().isEmpty() ? file.mediaType() : Long.toUnsignedString(file.uid());
  }

  private static boolean matches(String key, AttachedFile file) {
    return !key.isEmpty() && (key.equals("PICTURE") && file.mediaType().startsWith("image/") || key.equals(file.fileName())
      || key.equals(file.mediaType()) || key.equals(Long.toUnsignedString(file.uid())));
  }

  private static Variant value(Map<String, Variant> map, String name) {
    return map.getOrDefault(name, EMPTY_VARIANT);
  }

  @Override
  public boolean supportsPictureEditing() {
    return true;
  }

  /**
   * Returns the names of complex-property categories currently present.
   */
  @Override
  public List<String> complexPropertyKeys() {
    var result = new ArrayList<>(super.complexPropertyKeys());
    if (attachments != null) {
      for (var file : attachments.storedFiles()) {
        String key = key(file);
        if (!key.isEmpty() && !result.contains(key)) {
          result.add(key);
        }
      }
    }
    if (chapters != null && !chapters.chapterEditionList().isEmpty()) {
      result.add("CHAPTERS");
    }
    return List.copyOf(result);
  }

  @Override
  public List<Map<String, Variant>> complexProperties(String key) {
    var result = new ArrayList<>(super.complexProperties(key));
    if (Ascii.upper(key).equals("CHAPTERS") && chapters != null) {
      for (var edition : chapters.chapterEditionList()) {
        var map = new TreeMap<String, Variant>();
        if (edition.uid() != 0) {
          map.put("uid", Variant.fromUnsignedLong(edition.uid()));
        }
        if (edition.isDefault()) {
          map.put("isDefault", new Variant(true));
        }
        if (edition.isOrdered()) {
          map.put("isOrdered", new Variant(true));
        }
        var chaps = new ArrayList<Variant>();
        for (var chapter : edition.chapterList()) {
          var fields = new TreeMap<String, Variant>();
          if (chapter.uid() != 0) {
            fields.put("uid", Variant.fromUnsignedLong(chapter.uid()));
          }
          if (chapter.isHidden()) {
            fields.put("isHidden", new Variant(true));
          }
          fields.put("timeStart", Variant.fromUnsignedLong(chapter.timeStart()));
          if (chapter.timeEnd() != 0) {
            fields.put("timeEnd", Variant.fromUnsignedLong(chapter.timeEnd()));
          }
          if (!chapter.displayList().isEmpty()) {
            fields.put("displays", Variant.fromList(chapter.displayList().stream().map(display -> {
              var disp = new TreeMap<String, Variant>();
              if (!display.string().isEmpty()) {
                disp.put("string", new Variant(display.string()));
              }
              if (!display.language().isEmpty()) {
                disp.put("language", new Variant(display.language()));
              }
              return Variant.fromMap(disp);
            }).toList()));
          }
          chaps.add(Variant.fromMap(fields));
        }
        if (!chaps.isEmpty()) {
          map.put("chapters", Variant.fromList(chaps));
        }
        result.add(Collections.unmodifiableMap(map));
      }
    }
    if (attachments != null) {
      // Load once, only if an attachment matches the requested key.
      boolean loaded = false;
      for (var file : attachments.storedFiles()) {
        if (matches(key, file)) {
          if (!loaded) {
            try {
              loadAttachedFileData();
            } catch (IOException failure) {
              throw new UncheckedIOException("Cannot read attachments: " + name(), failure);
            }
            loaded = true;
          }
          result.add(
            Map.of("data", new Variant(file.byteVector()), "mimeType", new Variant(file.mediaType()), "description",
              new Variant(file.description()), "fileName", new Variant(file.fileName()), "uid",
              Variant.fromUnsignedLong(file.uid())));
        }
      }
    }
    return List.copyOf(result);
  }

  @Override
  public boolean setComplexProperties(String key, List<Map<String, Variant>> values) {
    if (super.setComplexProperties(key, values)) {
      return true;
    }
    String uppercase = Ascii.upper(key);
    if (uppercase.equals("CHAPTERS")) {
      var collection = getOrCreateChapters();
      collection.clear();
      for (var edition : values) {
        var chaps = new ArrayList<Chapter>();
        for (var chapterValue : value(edition, "chapters").toList()) {
          var chapter = chapterValue.toMap();
          var displays = new ArrayList<Chapter.Display>();
          for (var displayValue : value(chapter, "displays").toList()) {
            var display = displayValue.toMap();
            displays.add(new Chapter.Display(value(display, "string").toText(), value(display, "language").toText()));
          }
          chaps.add(new Chapter(value(chapter, "timeStart").toUnsignedLong(), value(chapter, "timeEnd").toUnsignedLong(), displays,
            value(chapter, "uid").toUnsignedLong(), value(chapter, "isHidden").toBoolean()));
        }
        collection.addChapterEdition(
          new ChapterEdition(chaps, value(edition, "isDefault").toBoolean(), value(edition, "isOrdered").toBoolean(),
            value(edition, "uid").toUnsignedLong()));
      }
      return true;
    }
    Attachments collection = getOrCreateAttachments();
    var files = collection.attachedFiles();
    files.removeIf(file -> matches(key, file));
    for (var map : values) {
      if (map.isEmpty()) {
        continue;
      }
      // Native attachment values use exact Variant types, unlike chapter conversions.
      String mime = value(map, "mimeType").toText();
      String name = value(map, "fileName").toText();
      ByteVector data = value(map, "data").toByteVector();
      long uid = value(map, "uid").type() == Variant.Type.UNSIGNED_LONG ? value(map, "uid").toUnsignedLong() : 0;
      if (uppercase.equals("PICTURE") && !mime.startsWith("image/")) {
        mime = data.startsWith(new ByteVector(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10})) ? "image/png" : "image/jpeg";
      } else if (mime.isEmpty() && key.contains("/")) {
        mime = key;
      } else if (name.isEmpty() && key.contains(".")) {
        name = key;
      } else if (uid == 0) {
        var parsed = new TagString(key).extractUnsignedLong(10);
        if (parsed.ok() && parsed.value() != 0) {
          uid = parsed.value();
        }
      }
      if (name.isEmpty() && !mime.isEmpty()) {
        String ext = mime.substring(mime.lastIndexOf('/') + 1);
        name = "attachment." + (ext.equals("jpeg") ? "jpg" : ext);
      }
      if (!mime.isEmpty()) {
        collection.addAttachedFile(new AttachedFile(data, name, mime, uid, value(map, "description").toText()));
      }
    }
    return true;
  }

  /**
   * Writes staged Matroska metadata and loaded attachment payloads in place.
   * Rendering can discard unmodified empty views. A failure can leave partial storage changes. The layout policy is {@code Compact}.
   *
   * @throws UncheckedIOException  if storage access fails
   * @throws TagLibException       if layout validation or a size-change listener rejects the save
   * @throws IllegalStateException if the file is closed
   */
  @Override
  public void save() {
    save(WriteStyle.Compact);
  }

  /**
   * Writes staged Matroska metadata and loaded attachment payloads in place.
   * Rendering can discard unmodified empty views. A failure can leave partial storage changes.
   *
   * @param style the metadata layout policy
   * @throws UncheckedIOException  if storage access fails
   * @throws TagLibException       if layout validation or a size-change listener rejects the save
   * @throws IllegalStateException if the file is closed
   */
  public final void save(WriteStyle style) {
    requireWritable();
    Objects.requireNonNull(style);
    try {
      loadAttachedFileData();
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read attachments: " + name(), failure);
    }
    boolean saved = state.save(ownerStream(), chapters == null ? null : chapters.chapterEditionList(),
      attachments == null ? null : attachments.attachedFileList(), tag == null ? null : tag.simpleTagsList(), style,
      this::validateWritePlan);
    if (state.discarded(CHAPTER)) {
      chapters = null;
    }
    if (state.discarded(ATTACHMENT)) {
      attachments = null;
    }
    if (state.discarded(TAG)) {
      tag = null;
    }
    if (!saved) {
      throw new TagLibException("Matroska size-change listener rejected metadata rendering: " + name());
    }
  }

  IOStream metadataStream() {
    return ownerStream();
  }

  private void validateWritePlan(List<Element> elements) {
    for (var element : elements) {
      element.validateAllocation(options.maxMetadataBytes());
    }
    if (source instanceof ByteVectorStream) {
      long available = length();
      for (var element : elements) {
        available = element.validatedMemoryLength(available);
      }
    }
  }

  @Override
  public ByteVector readBlock(long count) {
    if (count < 0 || count > options.maxMetadataBytes()) {
      throw new MetadataLimitException("Matroska metadata exceeds allocation limit");
    }
    return super.readBlock(count);
  }

  @Override
  protected void insert(ByteVector data, long offset, long replace) {
    if (source instanceof ByteVectorStream && data.size() > replace && (offset < 0 || offset > length()
      || replace > length() - offset)) {
      throw new IllegalArgumentException("Unsafe cached Matroska memory insertion span");
    }
    super.insert(data, offset, replace);
  }

  @Override
  public void close() {
    if (owned) {
      source.close();
    }
  }

  /**
   * Probes the stream for this format and restores its original position.
   */
  public static boolean isSupported(IOStream stream) {
    if (stream == null || !stream.isOpen()) {
      return false;
    }
    long position = stream.tell();
    try {
      stream.seek(0);
      return stream.readBlock(4).equals(new ByteVector(new byte[]{0x1a, 0x45, (byte) 0xdf, (byte) 0xa3}));
    } finally {
      stream.seek(position);
    }
  }
}
