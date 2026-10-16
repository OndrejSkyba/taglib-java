// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib;

import cz.skyba.taglib.internal.editor.Mp4Document;
import cz.skyba.taglib.internal.editor.TagDocument;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * A session for editing individual physical metadata entries.
 * Entry edits and artwork replacement are staged until {@link #save()}, which checks that
 * the source file has not changed and updates it in place. An I/O failure can leave partial
 * storage changes. Sessions must be used by one thread at a time.
 *
 * <p>Submit at most one nonempty entry-edit batch per session, followed by optional artwork
 * replacement. Reopen after saving or a failed edit. Entry IDs are specific to the session.
 * Duplicate fields, separate tag blocks, descriptions, languages and opaque entries remain
 * distinct.
 */
public abstract class MetadataDocument {
  /**
   * Opens a physical metadata editing session for the file.
   *
   * @throws UncheckedIOException  if storage access fails
   * @throws MetadataReadException if metadata cannot be decoded
   */
  public static MetadataDocument open(Path path) {
    try {
      String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
      int dot = name.lastIndexOf('.');
      String extension = dot < 0 ? "" : name.substring(dot + 1);
      return switch (extension) {
        case "m4a", "m4r", "m4b", "m4p", "mp4", "3g2", "m4v" -> new Mp4Document(path);
        default -> new TagDocument(path);
      };
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read audio metadata: " + path, failure);
    }
  }

  /**
   * Whether the source contained an ID3v2 tag when this session opened, including an empty tag.
   * Staged edits do not change this snapshot value. Containers without ID3v2 return false.
   */
  public boolean hasId3v2Tag() {
    return false;
  }

  /**
   * Returns immutable snapshots of the physical metadata entries and their editing capabilities.
   * Entry IDs belong to this session and must not be reused in another document.
   *
   * @return the entries in document order
   */
  public abstract List<MetadataEntry> entries();

  /**
   * Returns the tag block used for newly added entries.
   *
   * @return the block name, or an empty string if no default block is available
   */
  public abstract String defaultBlock();

  /**
   * Returns the reason metadata edits are unavailable.
   *
   * @return the reason, or an empty string if editing is available
   */
  public abstract String readOnlyReason();

  /**
   * Reports whether some metadata could not be decoded when the document was opened.
   *
   * @return whether metadata reading failed
   */
  public abstract boolean metadataReadFailed();

  /**
   * Stages one batch of entry additions, updates and removals.
   * An empty batch has no effect. Validate edits against each entry's capabilities. Entries
   * marked as requiring removal must be removed in the same batch. A failed batch can leave
   * partial in-memory changes and makes the session unsuitable for further edits.
   *
   * @param changes at most 10,000 changes using this session's entry IDs
   * @throws MetadataEditException if a change is invalid, unsupported, duplicated, or the session cannot be edited
   * @throws UncheckedIOException  if reading or staging metadata fails
   */
  public abstract void apply(List<? extends MetadataChange> changes);

  /**
   * Stages complete artwork replacement; an empty list requests removal.
   * Call {@link #save()} to persist the result. Reopen after a failed edit.
   *
   * @param pictures replacement artwork
   * @throws MetadataEditException if artwork editing is unsupported or the session cannot be edited
   * @throws UncheckedIOException  if reading or staging artwork fails
   */
  public abstract void replacePictures(List<Picture> pictures);

  /**
   * Saves staged edits in place after checking the source file identity, size and modification time.
   * Saving does not promise atomic replacement: a failure can leave partial storage changes.
   * Reopen the document after saving or after a failed edit or save.
   *
   * @throws UncheckedIOException if storage access fails or the source changed after opening
   * @throws TagLibException      if the edits cannot be saved
   */
  public abstract void save();

}
