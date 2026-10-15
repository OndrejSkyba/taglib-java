// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.mp4;

import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.TagLibException;
import cz.skyba.taglib.internal.*;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.IOStream;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static cz.skyba.taglib.mp4.ChapterTables.*;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A mutable QuickTime chapter list with explicit storage read, write and removal operations.
 */
public class QtChapterList extends ChapterHolder {
  private record Audio(Atom track, long id) {
  }

  private record Movie(long duration, long milliseconds) {
  }

  private record Box(long offset, long length) {
  }

  private static ByteVector read(IOStream file, Atom atom) {
    file.seek(atom.offset());
    return file.readBlock(atom.length());
  }

  private static ByteVector concat(ByteVector... values) {
    var result = new ByteVectorOutput();
    for (var value : values) {
      result.append(value);
    }
    return result.toByteVector();
  }

  private static ByteVector twelveZeros() {
    return new ByteVector(12, (byte) 0);
  }

  private static ByteVector uints(long... values) {
    var result = new ByteVectorOutput();
    for (long value : values) {
      result.append(ByteVector.fromUnsignedInt(value));
    }
    return result.toByteVector();
  }

  private static ByteVector atom(String name, ByteVector value) {
    return Mp4Save.atom(name, value);
  }

  private static ByteVector full(String name, int flags, ByteVector value) {
    return atom(name, uints(flags).append(value));
  }

  private static Audio audio(IOStream file, Atoms atoms) {
    Atom movie = atoms.find("moov");
    if (movie == null) {
      return new Audio(null, 0);
    }
    for (Atom track : movie.findall("trak")) {
      Atom handler = track.find("mdia", "hdlr");
      if (handler == null || !read(file, handler).containsAt(new ByteVector("soun"), 16)) {
        continue;
      }
      Atom header = track.find("tkhd");
      if (header == null) {
        continue;
      }
      ByteVector data = read(file, header);
      if (data.size() < 9) {
        continue;
      }
      int position = data.at(8) == 1 ? 28 : 20;
      if (data.size() < position + 4) {
        continue;
      }
      return new Audio(track, data.toUnsignedInt(position, true));
    }
    return new Audio(null, 0);
  }

  private static Movie movie(IOStream file, Atoms atoms) {
    Atom header = atoms.find("moov", "mvhd");
    if (header == null) {
      return new Movie(0, 0);
    }
    ByteVector data = read(file, header);
    if (data.size() < 12) {
      return new Movie(0, 0);
    }
    long scale, duration;
    if (data.at(8) == 1 && data.size() >= 36) {
      scale = data.toUnsignedInt(28, true);
      duration = data.toLong(32);
    } else if (data.size() >= 28) {
      scale = data.toUnsignedInt(20, true);
      duration = data.toUnsignedInt(24, true);
    } else {
      return new Movie(0, 0);
    }
    if (scale <= 0 || duration <= 0) {
      return new Movie(0, 0);
    }
    double milliseconds = duration * 1000.0 / scale + 0.5;
    if (!Double.isFinite(milliseconds) || milliseconds >= 0x1p63) {
      throw new IllegalArgumentException("QuickTime movie duration exceeds Java signed range");
    }
    return new Movie(duration & 0xffff_ffffL, (long) milliseconds);
  }

  private static long nextId(IOStream file, Atoms atoms) {
    Atom header = atoms.find("moov", "mvhd");
    if (header == null) {
      return 0;
    }
    ByteVector data = read(file, header);
    if (data.size() < 9) {
      return 0;
    }
    int pos = data.at(8) == 1 ? 116 : 104;
    return data.size() >= pos + 4 ? data.toUnsignedInt(pos, true) : 0;
  }

  private static void nextId(IOStream file, Atoms atoms, long value) {
    Atom header = atoms.find("moov", "mvhd");
    if (header == null) {
      return;
    }
    ByteVector data = read(file, header);
    if (data.size() < 9) {
      return;
    }
    int pos = data.at(8) == 1 ? 116 : 104;
    if (data.size() >= pos + 4) {
      file.seek(header.offset() + pos);
      file.writeBlock(uints(value));
    }
  }

  private static Atom chapterTrack(IOStream file, Atoms atoms, Atom audio) {
    Atom movie = atoms.find("moov");
    if (audio == null || movie == null) {
      return null;
    }
    for (Atom reference : audio.children()) {
      if (reference.name().equals(new ByteVector("tref"))) {
        file.seek(reference.offset() + 8);
        long end = reference.offset() + reference.length();
        while (file.tell() <= end - 8) {
          long start = file.tell();
          ByteVector header = file.readBlock(8);
          if (header.size() < 8) {
            break;
          }
          long size = header.toUnsignedInt();
          if (size < 8 || size > end - start) {
            break;
          }
          if (header.mid(4, 4).equals(new ByteVector("chap")) && size >= 12) {
            long id = file.readBlock(size - 8).toUnsignedInt();
            for (Atom track : movie.findall("trak")) {
              Atom trackHeader = track.find("tkhd");
              if (trackHeader == null) {
                continue;
              }
              ByteVector data = read(file, trackHeader);
              if (data.size() < 24) {
                continue;
              }
              long trackId = data.toUnsignedInt(data.at(8) == 1 && data.size() >= 32 ? 28 : 20, true);
              if (trackId == id) {
                return track;
              }
            }
          }
          file.seek(start + size);
        }
      }
    }
    return null;
  }

  private static Box referenceEntry(IOStream file, Atom reference) {
    long end = reference.offset() + reference.length();
    file.seek(reference.offset() + 8);
    while (file.tell() <= end - 8) {
      long start = file.tell();
      ByteVector header = file.readBlock(8);
      if (header.size() < 8) {
        break;
      }
      long size = header.toUnsignedInt();
      if (size < 8 || size > end - start) {
        break;
      }
      if (header.mid(4, 4).equals(new ByteVector("chap")) && size >= 12) {
        return new Box(start, size);
      }
      file.seek(start + size);
    }
    return null;
  }


  /**
   * Reads and replaces this list from QuickTime chapter metadata. The source remains open.
   *
   * @param file the source or storage target
   * @return whether chapter metadata was found and readable
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public final boolean read(FileBase file) {
    return read(FileOwnerStream.properties(file));
  }

  /**
   * Reads and replaces this list from QuickTime chapter metadata. The source remains open.
   *
   * @param file the source or storage target
   * @return whether chapter metadata was found and readable
   * @throws java.io.UncheckedIOException if storage access fails
   */
  public final boolean read(IOStream file) {
    Atoms atoms = new Atoms(file);
    modified = false;
    chapterList.clear();
    Audio audio = audio(file, atoms);
    if (audio.track == null) {
      return false;
    }
    Atom track = chapterTrack(file, atoms, audio.track);
    if (track == null) {
      return false;
    }
    Atom header = track.find("mdia", "mdhd");
    if (header == null) {
      return false;
    }
    ByteVector data = read(file, header);
    long scale = 0;
    if (data.size() >= 12) {
      if (data.at(8) == 1 && data.size() >= 40) {
        scale = data.toUnsignedInt(28, true);
      } else if (data.at(8) == 0 && data.size() >= 28) {
        scale = data.toUnsignedInt(20, true);
      }
    }
    if (scale == 0) {
      return false;
    }
    var timing = table(file, track, "stts", 8);
    Sizes sizes = sizes(file, track);
    long[] offsets = sampleOffsets(file, track, sizes);
    if (offsets.length == 0) {
      return false;
    }
    int index = 0;
    long current = 0;
    for (int row = 0; row < timing.size(); row++) {
      for (long i = 0; i < timing.first()[row] && index < offsets.length; i++) {
        long at = offsets[index], size = sizes.defaultSize();
        if (size == 0 && index < sizes.sizes().length) {
          size = sizes.sizes()[index];
        }
        if (at > file.length() || size > file.length() - at || index + 1 < offsets.length && (offsets[index + 1] <= at
          || size > offsets[index + 1] - at)) {
          return false;
        }
        file.seek(at);
        data = file.readBlock(size);
        String title = "";
        if (data.size() >= 2) {
          int n = data.toUnsignedShort();
          if (n > 0 && n <= data.size() - 2) {
            title = TextBytes.decodeTerminated(data.data(), 2, n, UTF_8);
          }
        }
        double milliseconds = current * 1000.0 / scale + 0.5;
        if (milliseconds >= 0x1p63) {
          throw new IllegalArgumentException("QuickTime chapter time exceeds signed range");
        }
        chapterList.add(new Chapter(title, (long) milliseconds));
        current = Math.addExact(current, timing.second()[row]);
        index++;
      }
    }
    if (chapterList.size() > 1 && chapterList.getFirst().startTime() == 0 && chapterList.getFirst().title().isEmpty()) {
      chapterList.removeFirst();
    }
    return true;
  }

  static void validateTimes(List<Chapter> chapters) {
    for (Chapter chapter : chapters) {
      uintTime(chapter.startTime());
    }
  }

  private static long uintTime(long milliseconds) {
    double value = milliseconds + 0.5;
    if (!Double.isFinite(value) || value < 0 || value >= 0x1p32) {
      throw new IllegalArgumentException("Undefined native QuickTime unsigned time conversion");
    }
    return (long) value;
  }

  private static ByteVector sample(String title) {
    ByteVector bytes = ByteVector.fromText(title, UTF_8);
    return ByteVector.fromShort((short) bytes.size()).append(bytes).append(atom("encd", new ByteVector(new byte[]{0, 0, 1, 0})));
  }

  private static ByteVector buildTrack(long id, long duration, long movieDuration, List<Chapter> chapters, int[] sizes, long textOffset) {
    byte[] tkhd = new byte[80];
    Binary.put32be(tkhd, 8, id);
    Binary.put32be(tkhd, 16, duration);
    Binary.put32be(tkhd, 36, 0x10000);
    Binary.put32be(tkhd, 52, 0x10000);
    Binary.put32be(tkhd, 68, 0x40000000);
    byte[] mdhd = new byte[20];
    Binary.put32be(mdhd, 8, 1000);
    Binary.put32be(mdhd, 12, duration);
    ByteVector handler = concat(uints(0), new ByteVector("text"), twelveZeros(), new ByteVector("Chapter").append((byte) 0));
    byte[] textInfo = new byte[36];
    textInfo[1] = 1;
    textInfo[17] = 1;
    Binary.put32be(textInfo, 32, 0x40000000);
    ByteVector gmhd = atom("gmhd", concat(full("gmin", 0, new ByteVector(new byte[]{0, 64, -128, 0, -128, 0, -128, 0, 0, 0, 0, 0})),
      atom("text", new ByteVector(textInfo))));
    ByteVector dinf = atom("dinf", full("dref", 0, concat(uints(1), full("url ", 1, new ByteVector()))));
    ByteVector entry = new ByteVector(HexFormat.of()
      .parseHex("00000000000000010000000100000000000000000000000000000000000000010000000000000000000d667461620001000100"));
    ByteVector description = full("stsd", 0, concat(uints(1), atom("text", entry)));
    var timing = new ByteVectorOutput().append(uints(chapters.size()));
    for (int i = 0; i < chapters.size(); i++) {
      long start = uintTime(chapters.get(i).startTime());
      long length = i + 1 < chapters.size()
        ? (uintTime(chapters.get(i + 1).startTime()) - start) & 0xffff_ffffL
        : duration > start ? duration - start : 0;
      timing.append(uints(1, length));
    }
    var sampleSizes = new ByteVectorOutput().append(uints(0, sizes.length));
    for (int size : sizes) {
      sampleSizes.append(uints(size));
    }
    ByteVector table = atom("stbl",
      concat(description, full("stts", 0, timing.toByteVector()), full("stsz", 0, sampleSizes.toByteVector()), full("stsc", 0, uints(1, 1, chapters.size(), 1)),
        full("stco", 0, uints(1, textOffset))));
    ByteVector media = atom("mdia",
      concat(full("mdhd", 0, new ByteVector(mdhd)), full("hdlr", 0, handler), atom("minf", concat(gmhd, dinf, table))));
    ByteVector edits = atom("edts", full("elst", 0, uints(1, movieDuration, 0, 0x10000)));
    return atom("trak", concat(full("tkhd", 2, new ByteVector(tkhd)), edits, media));
  }

  private static Box removeReference(IOStream file, Atoms atoms, Atom audio) {
    for (Atom reference : audio.children()) {
      if (!reference.name().equals(new ByteVector("tref"))) {
        continue;
      }
      Box chapter = referenceEntry(file, reference);
      if (chapter == null) {
        continue;
      }
      boolean sole = chapter.length + 8 == reference.length();
      long at = sole ? reference.offset() : chapter.offset, count = sole ? reference.length() : chapter.length;
      file.seek(reference.offset());
      long referenceSize = file.readBlock(4).toUnsignedInt();
      file.seek(audio.offset());
      long trackSize = file.readBlock(4).toUnsignedInt();
      if (referenceSize != reference.length() || trackSize != audio.length() || referenceSize < count || trackSize < count) {
        return new Box(-1, 0);
      }
      file.removeBlock(at, count);
      if (!sole) {
        file.seek(reference.offset());
        file.writeBlock(uints(referenceSize - count));
      }
      file.seek(audio.offset());
      file.writeBlock(uints(trackSize - count));
      Mp4Save.parents(file, atoms.path("moov"), -count, 0);
      Mp4Save.offsets(file, atoms, -count, at);
      return new Box(at, count);
    }
    return new Box(-1, 0);
  }

  private static boolean mediaUsed(IOStream file, Atoms atoms, long at, long count) {
    Atom movie = atoms.find("moov");
    if (movie == null) {
      return false;
    }
    for (String name : List.of("stco", "co64")) {
      for (Atom table : movie.findall(name, true)) {
        int width = name.equals("stco") ? 4 : 8;
        if (table.length() < 12 + width) {
          continue;
        }
        file.seek(table.offset() + 12);
        ByteVector data = file.readBlock(table.length() - 12);
        if (data.size() < 4) {
          continue;
        }
        long entries = data.toUnsignedInt();
        for (int pos = 4; entries-- > 0 && pos <= data.size() - width; pos += width) {
          long offset = width == 4 ? data.toUnsignedInt(pos, true) : data.toLong(pos);
          if (offset >= at + 8 && offset < at + count) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private static void removeTrack(IOStream file, Atoms atoms, Atom movie, Atom track, Atom audio) {
    long mediaAt = -1, mediaSize = 0;
    var offsets = table(file, track, "stco", 4);
    if (offsets.size() != 0) {
      for (Atom root : atoms.atoms()) {
        if (root.name().equals(new ByteVector("mdat")) && offsets.first()[0] >= root.offset() + 8
          && offsets.first()[0] < root.offset() + root.length()) {
          mediaAt = root.offset();
          mediaSize = root.length();
          break;
        }
      }
    }
    long at = track.offset(), count = track.length();
    movie.removeChild(track);
    file.removeBlock(at, count);
    Mp4Save.parents(file, atoms.path("moov"), -count, 0);
    Mp4Save.offsets(file, atoms, -count, at);
    Box reference = removeReference(file, atoms, audio);
    if (mediaAt < 0) {
      return;
    }
    long adjusted = mediaAt;
    if (mediaAt > at) {
      adjusted -= count;
    }
    if (reference.offset >= 0 && mediaAt > reference.offset) {
      adjusted -= reference.length;
    }
    Atoms clean = new Atoms(file);
    if (mediaUsed(file, clean, adjusted, mediaSize)) {
      return;
    }
    file.seek(adjusted);
    ByteVector header = file.readBlock(8);
    if (header.size() != 8 || !header.mid(4, 4).equals(new ByteVector("mdat")) || header.toUnsignedInt() != mediaSize) {
      return;
    }
    file.removeBlock(adjusted, mediaSize);
  }

  /**
   * Writes this list to storage immediately, updating atom sizes and offsets. An empty list
   * removes chapter metadata. The source remains open; failures can leave partial changes.
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
    // Bound/validate rendered values before native cleanup can modify the original.
    var working = new ArrayList<>(chapters());
    if (working.getFirst().startTime() > 0) {
      working.addFirst(new Chapter("", 0));
    }
    int[] sizes = new int[working.size()];
    var sampleOutput = new ByteVectorOutput();
    for (int i = 0; i < working.size(); i++) {
      uintTime(working.get(i).startTime());
      ByteVector sample = sample(working.get(i).title());
      sizes[i] = sample.size();
      sampleOutput.append(sample);
    }
    var samples = sampleOutput.toByteVector();
    Atoms atoms = new Atoms(file);
    Atom movie = atoms.find("moov");
    if (movie == null) {
      throw new TagLibException("MP4 QuickTime chapters require a moov atom");
    }
    Movie info = movie(file, atoms);
    if (info.milliseconds <= 0) {
      throw new TagLibException("MP4 QuickTime chapters require a positive movie duration");
    }
    long duration = uintTime(info.milliseconds);
    Audio audio = audio(file, atoms);
    if (audio.track == null) {
      throw new TagLibException("MP4 QuickTime chapters require an audio track");
    }
    Atom old = chapterTrack(file, atoms, audio.track);
    if (old != null) {
      removeTrack(file, atoms, movie, old, audio.track);
      atoms = new Atoms(file);
      movie = atoms.find("moov");
      if (movie == null) {
        throw new TagLibException("MP4 moov atom is missing after removing the old chapter track");
      }
      audio = audio(file, atoms);
      if (audio.track == null) {
        throw new TagLibException("MP4 audio track is missing after removing the old chapter track");
      }
    }
    long next = nextId(file, atoms), id = next > 0 ? next : (audio.id + 1) & 0xffff_ffffL;
    Atom reference = audio.track.find("tref");
    ByteVector ref = atom("chap", uints(id));
    if (reference == null) {
      ref = atom("tref", ref);
    }
    long refAt = reference != null ? reference.offset() + reference.length() : audio.track.offset() + audio.track.length();
    ByteVector measured = buildTrack(id, duration, info.duration, working, sizes, 0);
    long total = ref.size() + (long) measured.size();
    long textAt = Math.addExact(file.length(), total + 8);
    ByteVector track = buildTrack(id, duration, info.duration, working, sizes, textAt);
    long trackAt = audio.track.offset() + audio.track.length();
    file.insert(track, trackAt, 0);
    Mp4Save.offsets(file, atoms, track.size(), trackAt);
    file.insert(ref, refAt, 0);
    Mp4Save.offsets(file, atoms, ref.size(), refAt);
    if (reference != null) {
      file.seek(reference.offset());
      long size = file.readBlock(4).toUnsignedInt();
      file.seek(reference.offset());
      file.writeBlock(uints(size + ref.size()));
    }
    file.seek(audio.track.offset());
    long size = file.readBlock(4).toUnsignedInt();
    file.seek(audio.track.offset());
    file.writeBlock(uints(size + ref.size()));
    Mp4Save.parents(file, atoms.path("moov"), total, 0);
    file.seek(0, IOStream.Position.END);
    file.writeBlock(atom("mdat", samples));
    if (id >= nextId(file, atoms)) {
      nextId(file, atoms, (id + 1) & 0xffff_ffffL);
    }
    modified = false;
  }

  /**
   * Removes QuickTime chapter metadata from storage immediately and clears this list.
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
   * Removes QuickTime chapter metadata from storage immediately and clears this list.
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
    Audio audio = audio(file, atoms);
    if (audio.track == null) {
      return;
    }
    Atom track = chapterTrack(file, atoms, audio.track);
    if (track == null) {
      return;
    }
    Atom movie = atoms.find("moov");
    if (movie == null) {
      throw new TagLibException("Cannot remove MP4 QuickTime chapters without a moov atom");
    }
    removeTrack(file, atoms, movie, track, audio.track);
  }
}
