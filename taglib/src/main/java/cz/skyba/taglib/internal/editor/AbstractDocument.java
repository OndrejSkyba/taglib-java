// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.editor;

import cz.skyba.taglib.*;
import cz.skyba.taglib.internal.WriteGuard;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/// Shared snapshot identity and edit validation. Concrete documents own physical serialization.
public abstract class AbstractDocument extends MetadataDocument {
  protected final Path path;
  protected final List<MutableEntry> entries = new ArrayList<>();
  private final Map<String, MutableEntry> entriesById = new HashMap<>();
  protected String defaultBlock = "";
  protected String readOnlyReason = "";
  protected boolean metadataReadFailed;
  private final BasicFileAttributes original;
  private Lifecycle lifecycle = Lifecycle.OPEN;

  private enum Lifecycle {
    OPEN, APPLIED, SAVED, FAILED
  }

  protected AbstractDocument(Path path) throws IOException {
    this.path = path.toRealPath();
    original = Files.readAttributes(this.path, BasicFileAttributes.class);
  }

  @FunctionalInterface
  public interface EditAction {
    void update(String key, List<String> values) throws IOException;
  }

  @FunctionalInterface
  public interface Removal {
    void remove() throws IOException;
  }

  protected final MutableEntry addEntry(
    String block,
    String key,
    String standardKey,
    String kind,
    List<String> values,
    EditAction edit,
    Removal remove,
    boolean renameable,
    String details
  ) {
    var entry =
      new MutableEntry(
        Integer.toString(entries.size()),
        block,
        key,
        standardKey,
        kind,
        List.copyOf(values),
        edit,
        remove,
        renameable,
        details);
    entries.add(entry);
    entriesById.put(entry.id, entry);
    return entry;
  }

  protected final MutableEntry picture(
    String block, String key, Picture picture, String details, Removal remove) {
    return addEntry(block, key, "PICTURE", "artwork", List.of(), null, remove, false, details)
      .picture(picture);
  }

  protected abstract void add(String key, List<String> values, boolean custom) throws IOException;

  protected abstract void saveChanges() throws IOException;

  @Override
  public final String defaultBlock() {
    return defaultBlock;
  }

  @Override
  public final String readOnlyReason() {
    return readOnlyReason;
  }

  @Override
  public final boolean metadataReadFailed() {
    return metadataReadFailed;
  }

  @Override
  public final List<MetadataEntry> entries() {
    return entries.stream().map(e -> e.snapshot(!readOnlyReason.isEmpty())).toList();
  }

  protected final void requireWritable() {
    if (lifecycle == Lifecycle.FAILED) {
      throw new MetadataEditException(
        "An earlier edit failed; reopen the document before editing.");
    }
    if (lifecycle == Lifecycle.SAVED) {
      throw new MetadataEditException("This document was saved; reopen it before editing.");
    }
    if (!readOnlyReason.isEmpty()) {
      throw new MetadataEditException(readOnlyReason);
    }
  }

  @Override
  public final void apply(List<? extends MetadataChange> changes) {
    if (changes.isEmpty()) {
      return;
    }
    requireWritable();
    try {
      if (lifecycle == Lifecycle.APPLIED) {
        throw new MetadataEditException(
          "Submit one edit batch per document; reopen before further entry edits.");
      }
      lifecycle = Lifecycle.APPLIED;
      if (changes.size() > 10000) {
        throw new MetadataEditException("Too many metadata operations.");
      }
      var touched = new HashSet<String>();
      var removed = new HashSet<String>();
      var pending = new ArrayList<MetadataChange>(changes.size());
      for (var change : changes) {
        switch (change) {
          case MetadataChange.Add(var key, var values, _) -> validateText(key, values);
          case MetadataChange.Update(var id, var key, var values) -> {
            validateText(key, values);
            var entry = entryToChange(id, touched);
            if (entry.edit == null || entry.requiresRemoval) {
              throw new MetadataEditException("This entry cannot be edited safely.");
            }
            if (!entry.renameable && !entry.key.equals(key)) {
              throw new MetadataEditException("This entry cannot be renamed.");
            }
            if (!entry.multipleValues && values.size() != entry.constraints.valueCount()) {
              throw new MetadataEditException("This field accepts one value.");
            }
          }
          case MetadataChange.Remove(var id) -> {
            var entry = entryToChange(id, touched);
            if (entry.remove == null) {
              throw new MetadataEditException("This entry cannot be removed safely.");
            }
            removed.add(entry.id);
          }
        }
        pending.add(change);
      }
      for (var entry : entries) {
        if (entry.requiresRemoval && !removed.contains(entry.id)) {
          throw new MetadataEditException(
            "Remove metadata marked for removal before saving other edits.");
        }
      }
      for (var operation : pending) {
        // Resolve IDs at execution time, preserving callbacks that change the entry registry.
        switch (operation) {
          case MetadataChange.Add(var key, var values, boolean custom) -> add(key, values, custom);
          case MetadataChange.Update(var id, var key, var values) -> lookup(id).edit.update(key, values);
          case MetadataChange.Remove(var id) -> lookup(id).remove.remove();
        }
      }
    } catch (IOException e) {
      lifecycle = Lifecycle.FAILED;
      throw new UncheckedIOException("Metadata edit failed: " + path, e);
    } catch (RuntimeException e) {
      lifecycle = Lifecycle.FAILED;
      throw e;
    }
  }

  private static void validateText(String key, List<String> values) {
    if (key == null || key.isEmpty() || key.indexOf('\0') >= 0) {
      throw new MetadataEditException(
        "A nonempty metadata key without NUL characters is required.");
    }
    if (values == null || values.isEmpty() || values.stream().anyMatch(v -> v.indexOf('\0') >= 0)) {
      throw new MetadataEditException(
        "Nonempty text values without NUL characters are required.");
    }
  }

  private MutableEntry entryToChange(String id, Set<String> touched) {
    var entry = lookup(id);
    if (!touched.add(entry.id)) {
      throw new MetadataEditException("A metadata entry may only be edited once in a batch.");
    }
    return entry;
  }

  private MutableEntry lookup(String id) throws MetadataEditException {
    MutableEntry entry = entriesById.get(id);
    if (entry != null) {
      return entry;
    }
    throw new MetadataEditException("Unknown metadata entry ID: " + id);
  }

  @Override
  public final void replacePictures(List<Picture> pictures) {
    requireWritable();
    try {
      replacePicturesChanges(List.copyOf(pictures));
    } catch (IOException e) {
      lifecycle = Lifecycle.FAILED;
      throw new UncheckedIOException("Metadata edit failed: " + path, e);
    } catch (RuntimeException e) {
      lifecycle = Lifecycle.FAILED;
      throw e;
    }
  }

  protected void replacePicturesChanges(List<Picture> pictures) throws IOException {
    throw new MetadataEditException("Artwork editing is not supported for this container.");
  }

  @Override
  public final void save() {
    requireWritable();
    try {
      WriteGuard.run(
        path,
        () -> {
          var now = Files.readAttributes(path, BasicFileAttributes.class);
          if (now.size() != original.size()
            || !Objects.equals(now.fileKey(), original.fileKey())
            || !now.lastModifiedTime().equals(original.lastModifiedTime())) {
            throw new IOException("Source changed since opening metadata; refusing stale write.");
          }
          saveChanges();
        });
      // A subclass may catch a failed reentrant edit inside saveChanges(). Failure stays sticky.
      if (lifecycle != Lifecycle.FAILED) {
        lifecycle = Lifecycle.SAVED;
      }
    } catch (IOException e) {
      lifecycle = Lifecycle.FAILED;
      throw new UncheckedIOException("Metadata edit failed: " + path, e);
    } catch (RuntimeException e) {
      lifecycle = Lifecycle.FAILED;
      throw e;
    }
  }

  protected static final class MutableEntry {
    final String id, block, key, standardKey, kind, details;
    final List<String> values;
    final EditAction edit;
    final Removal remove;
    final boolean renameable;
    boolean multipleValues, requiresRemoval, projected = true;
    int precedence;
    Picture picture;
    EntryConstraints constraints = new UnrestrictedConstraints();

    MutableEntry(
      String id,
      String block,
      String key,
      String standardKey,
      String kind,
      List<String> values,
      EditAction edit,
      Removal remove,
      boolean renameable,
      String details
    ) {
      this.id = id;
      this.block = block;
      this.key = key;
      this.standardKey = standardKey;
      this.kind = kind;
      this.values = values;
      this.edit = edit;
      this.remove = remove;
      this.renameable = renameable;
      this.details = details;
    }

    public MutableEntry multipleValues(boolean value) {
      multipleValues = value;
      return this;
    }

    public MutableEntry requiresRemoval(boolean value) {
      requiresRemoval = value;
      return this;
    }

    public MutableEntry projected(boolean value) {
      projected = value;
      return this;
    }

    public MutableEntry precedence(int value) {
      precedence = value;
      return this;
    }

    public MutableEntry picture(Picture value) {
      picture = value;
      return this;
    }

    public MutableEntry constraints(Map<String, Object> value) {
      constraints = new ImportedConstraints(Map.copyOf(value));
      return this;
    }

    void setValueCount(int value) {
      constraints = new FixedValueCount(value);
    }

    MetadataEntry snapshot(boolean readOnly) {
      return new MetadataEntry(
        id,
        block,
        key,
        standardKey,
        kind,
        values,
        edit != null && !readOnly && !requiresRemoval,
        remove != null && !readOnly,
        renameable && !readOnly && !requiresRemoval,
        multipleValues,
        requiresRemoval,
        details,
        picture,
        constraints.values(),
        projected,
        precedence);
    }
  }

  private sealed interface EntryConstraints {
    Map<String, Object> values();

    int valueCount();
  }

  private record UnrestrictedConstraints() implements EntryConstraints {
    public Map<String, Object> values() {
      return Map.of();
    }

    public int valueCount() {
      return 1;
    }
  }

  private record FixedValueCount(int valueCount) implements EntryConstraints {
    public Map<String, Object> values() {
      return Map.of("valueCount", valueCount);
    }
  }

  /// Retains arbitrary constraints and their deferred conversion for subclasses.
  private record ImportedConstraints(Map<String, Object> values) implements EntryConstraints {
    public int valueCount() {
      return ((Number) values.getOrDefault("valueCount", 1)).intValue();
    }
  }
}
