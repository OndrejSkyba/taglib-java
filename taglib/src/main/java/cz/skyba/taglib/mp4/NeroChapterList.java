// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.mp4;

import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.TagLibException;
import cz.skyba.taglib.internal.ByteVectorOutput;
import cz.skyba.taglib.internal.FileOwnerStream;
import cz.skyba.taglib.internal.Mp4Save;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.IOStream;
import cz.skyba.taglib.toolkit.TagString;

import java.util.List;

/**
 * A mutable Nero chapter list with explicit storage read, write and removal operations.
 */
public class NeroChapterList extends ChapterHolder {
  /**
   * Reads and replaces this list from Nero chapter metadata. The source remains open.
   *
   * @param file the source or storage target
   * @return whether chapter metadata was found and readable
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public final boolean read(FileBase file) {
    return read(FileOwnerStream.properties(file));
  }

  /**
   * Reads and replaces this list from Nero chapter metadata. The source remains open.
   *
   * @param file the source or storage target
   * @return whether chapter metadata was found and readable
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public final boolean read(IOStream file) {
    Atoms atoms = new Atoms(file);
    Atom atom = atoms.find("moov", "udta", "chpl");
    modified = false;
    chapterList.clear();
    if (atom == null) {
      return false;
    }
    file.seek(atom.offset() + 8);
    ByteVector data = file.readBlock(atom.length() - 8);
    if (data.size() < 5) {
      return true;
    }
    int pos = (data.at(0) & 255) >= 1 ? 8 : 4;
    if (pos >= data.size()) {
      return true;
    }
    int count = data.at(pos++) & 255;
    for (int i = 0; i < count && pos <= data.size() - 9; i++) {
      long time = data.toLong(pos);
      pos += 8;
      int size = data.at(pos++) & 255;
      String text = "";
      if (size > 0 && size <= data.size() - pos) {
        text = new TagString(data.mid(pos, size), TagString.Type.UTF8).toString();
        pos += size;
      }
      chapterList.add(new Chapter(text, time / 10_000));
    }
    return true;
  }

  /**
   * Writes this list to storage immediately, updating atom sizes and offsets. An empty list
   * removes chapter metadata. The source remains open; failures can leave partial changes.
   * The chapter count and each UTF-8 title are limited to 255; excess entries or title bytes are omitted.
   *
   * @param file the source or storage target
   * @throws IllegalStateException        if storage is closed
   * @throws TagLibException              if storage is read-only or chapter data cannot be represented
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public final void write(Mp4File file) {
    write(file.metadataStream());
  }

  /**
   * Writes this list to storage immediately, updating atom sizes and offsets. An empty list
   * removes chapter metadata. The source remains open; failures can leave partial changes.
   * The chapter count and each UTF-8 title are limited to 255; excess entries or title bytes are omitted.
   *
   * @param file the source or storage target
   * @throws IllegalStateException        if storage is closed
   * @throws TagLibException              if storage is read-only or chapter data cannot be represented
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public final void write(IOStream file) {
    if (chapterList.isEmpty()) {
      remove(file);
      return;
    }
    requireWritable(file);
    Atoms atoms = new Atoms(file);
    if (atoms.find("moov") == null) {
      throw new TagLibException("MP4 Nero chapters require a moov atom");
    }
    var data = new ByteVectorOutput().append(new byte[]{1, 0, 0, 0, 0, 0, 0, 0}).append((byte) Math.min(chapterList.size(), 255));
    for (int i = 0; i < Math.min(chapterList.size(), 255); i++) {
      Chapter chapter = chapterList.get(i);
      long time = Math.multiplyExact(chapter.startTime(), 10_000);
      ByteVector title = new TagString(chapter.title()).data(TagString.Type.UTF8);
      int size = Math.min(title.size(), 255);
      data.append(ByteVector.fromLong(time)).append((byte) size).append(title.mid(0, size));
    }
    var rendered = Mp4Save.atom("chpl", data.toByteVector());
    Atom existing = atoms.find("moov", "udta", "chpl");
    if (existing != null) {
      long delta = rendered.size() - existing.length();
      file.insert(rendered, existing.offset(), existing.length());
      if (delta != 0) {
        Mp4Save.parents(file, atoms.path("moov", "udta", "chpl"), delta, 1);
        Mp4Save.offsets(file, atoms, delta, existing.offset());
      }
    } else {
      List<Atom> path = atoms.path("moov", "udta");
      if (path.size() != 2) {
        path = atoms.path("moov");
        rendered = Mp4Save.atom("udta", rendered);
      }
      long offset = path.getLast().offset() + 8;
      file.insert(rendered, offset, 0);
      Mp4Save.parents(file, path, rendered.size(), 0);
      Mp4Save.offsets(file, atoms, rendered.size(), offset);
    }
    modified = false;
  }

  /**
   * Removes Nero chapter metadata from storage immediately and clears this list.
   * The source remains open; failures can leave partial changes.
   *
   * @param file the source or storage target
   * @throws IllegalStateException        if storage is closed
   * @throws TagLibException              if storage is read-only or chapter data cannot be represented
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public final void remove(Mp4File file) {
    remove(file.metadataStream());
  }

  /**
   * Removes Nero chapter metadata from storage immediately and clears this list.
   * The source remains open; failures can leave partial changes.
   *
   * @param file the source or storage target
   * @throws IllegalStateException        if storage is closed
   * @throws TagLibException              if storage is read-only or chapter data cannot be represented
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public final void remove(IOStream file) {
    requireWritable(file);
    Atoms atoms = new Atoms(file);
    chapterList.clear();
    modified = false;
    Atom atom = atoms.find("moov", "udta", "chpl");
    if (atom == null) {
      return;
    }
    file.removeBlock(atom.offset(), atom.length());
    Mp4Save.parents(file, atoms.path("moov", "udta", "chpl"), -atom.length(), 1);
    Mp4Save.offsets(file, atoms, -atom.length(), atom.offset());
  }
}
