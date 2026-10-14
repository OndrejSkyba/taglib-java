// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.editor;

import cz.skyba.taglib.MetadataEditException;
import cz.skyba.taglib.internal.Binary;
import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.Rewrite;
import cz.skyba.taglib.mp4.Chapter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import static cz.skyba.taglib.internal.Binary.*;
import static cz.skyba.taglib.internal.editor.Mp4Items.atom;

/**
 * The release's QuickTime sample-table semantics, with bounded, staged file mutation.
 */
public final class Mp4QuickTime {
  private Mp4QuickTime() {
  }

  private static final int MAX_SAMPLES = 1_000_000;

  private record Reference(int at, int size, long trackId) {
  }

  private record Sizes(long defaultSize, long count, List<Long> values) {
    long get(int index) {
      return defaultSize != 0 ? defaultSize : index < values.size() ? values.get(index) : 0;
    }
  }

  private record TableRow(long first, long second, long third) {
  }

  public static List<Chapter> read(Path path) throws IOException {
    var tree = new Mp4Tree(path);
    var audio = audio(tree);
    var track = chapterTrack(tree, audio);
    if (track == null) {
      return List.of();
    }
    var mdhd = track.path("mdia", "mdhd");
    long timescale = mdhd != null && mdhd.size() >= 40 && tree.source[mdhd.at() + 8] == 1
      ? u32be(tree.source, mdhd.at() + 28)
      : mdhd != null && mdhd.size() >= 28 && tree.source[mdhd.at() + 8] == 0
      ? u32be(tree.source, mdhd.at() + 20) : 0;
    if (timescale == 0) {
      return List.of();
    }
    var sizes = sizes(tree, track);
    try (var file = new BinaryFile(path)) {
      var offsets = offsets(tree, track, sizes, file.size());
      var result = new ArrayList<Chapter>();
      int index = 0;
      long time = 0;
      for (TableRow timing : table(tree, track, "stts", 2)) {
        for (long i = 0; i < timing.first() && index < offsets.size(); i++, index++) {
          long at = offsets.get(index), size = sizes.get(index);
          // Upstream returns false without clearing chapters already read. Its public lazy getter
          // ignores that status; preserve the readable prefix, including an unstripped dummy.
          if (at > file.size() || size > file.size() - at
            || (index + 1 < offsets.size()
            && (offsets.get(index + 1) <= at || size > offsets.get(index + 1) - at))) {
            return List.copyOf(result);
          }
          String title = "";
          if (size >= 2) {
            int length = u16be(file.read(at, 2), 0);
            if (length != 0 && length <= size - 2) {
              title = new String(file.read(at + 2, length), StandardCharsets.UTF_8);
            }
          }
          result.add(new Chapter(title, (long) ((double) time * 1000.0 / timescale + 0.5)));
          time += timing.second();
        }
      }
      if (result.size() > 1 && result.getFirst().startTime() == 0 && result.getFirst().title().isEmpty()) {
        result.removeFirst();
      }
      return List.copyOf(result);
    }
  }

  private static Mp4Tree.Node audio(Mp4Tree tree) {
    for (var track : tree.movie.children("trak")) {
      var handler = track.path("mdia", "hdlr");
      var tkhd = track.child("tkhd");
      if (handler != null && handler.size() >= 20 && ascii(tree.source, handler.at() + 16, 4).equals("soun")
        && tkhd != null && tkhd.size() >= (tkhd.size() >= 9 && tree.source[tkhd.at() + 8] == 1 ? 32 : 24)) {
        return track;
      }
    }
    return null;
  }

  private static long trackId(Mp4Tree tree, Mp4Tree.Node track) {
    var tkhd = track.child("tkhd");
    if (tkhd == null || tkhd.size() < 24) {
      return -1;
    }
    return u32be(tree.source, tkhd.at() + (tree.source[tkhd.at() + 8] == 1 && tkhd.size() >= 32 ? 28 : 20));
  }

  private static Mp4Tree.Node chapterTrack(Mp4Tree tree, Mp4Tree.Node audio) {
    if (audio == null) {
      return null;
    }
    for (var tref : audio.children("tref")) {
      for (var ref : references(tree, tref)) {
        for (var track : tree.movie.children("trak")) {
          if (trackId(tree, track) == ref.trackId) {
            return track;
          }
        }
      }
    }
    return null;
  }

  private static List<Reference> references(Mp4Tree tree, Mp4Tree.Node tref) {
    var result = new ArrayList<Reference>();
    // tref is intentionally opaque to the common atom parser, just as in native TagLib.
    for (int at = tref.at() + 8; tref.end() - at >= 8; ) {
      long size = u32be(tree.source, at);
      if (size < 8 || size > tref.end() - at) {
        break;
      }
      if (size >= 12 && ascii(tree.source, at + 4, 4).equals("chap")) {
        result.add(new Reference(at, (int) size, u32be(tree.source, at + 8)));
      }
      at += (int) size;
    }
    return result;
  }

  private static byte[] bytes(Mp4Tree tree, Mp4Tree.Node node) {
    return node == null ? new byte[0] : tree.bytes(node);
  }

  private static List<TableRow> table(Mp4Tree tree, Mp4Tree.Node track, String name, int width) {
    byte[] data = bytes(tree, track.path("mdia", "minf", "stbl", name));
    if (data.length < 16) {
      return List.of();
    }
    int count = (int) Math.min(u32be(data, 12), (data.length - 16) / (width * 4L));
    var rows = new ArrayList<TableRow>(count);
    for (int i = 0; i < count; i++) {
      int at = 16 + i * width * 4;
      rows.add(new TableRow(
        u32be(data, at),
        width >= 2 ? u32be(data, at + 4) : 0,
        width >= 3 ? u32be(data, at + 8) : 0));
    }
    return rows;
  }

  private static Sizes sizes(Mp4Tree tree, Mp4Tree.Node track) {
    byte[] data = bytes(tree, track.path("mdia", "minf", "stbl", "stsz"));
    if (data.length < 20) {
      return new Sizes(0, 0, List.of());
    }
    long size = u32be(data, 12), count = u32be(data, 16);
    var values = new ArrayList<Long>();
    if (size == 0) {
      for (int i = 0; i < count && i < (data.length - 20) / 4; i++) {
        values.add(u32be(data, 20 + 4 * i));
      }
    }
    return new Sizes(size, count, values);
  }

  private static List<Long> offsets(Mp4Tree tree, Mp4Tree.Node track, Sizes sizes, long fileSize) {
    if (sizes.count == 0 || sizes.count > fileSize / 4) {
      return List.of();
    }
    if (sizes.count > MAX_SAMPLES) {
      throw unsupported("Too many QuickTime chapter samples");
    }
    var chunks = table(tree, track, "stco", 1);
    var mapping = table(tree, track, "stsc", 3);
    if (mapping.isEmpty()) {
      mapping = List.of(new TableRow(1, 1, 1));
    }
    var result = new ArrayList<Long>();
    int row = 0, index = 0;
    for (int chunk = 0; chunk < chunks.size(); chunk++) {
      while (row + 1 < mapping.size() && mapping.get(row + 1).first() <= chunk + 1) {
        row++;
      }
      long count = Math.min(mapping.get(row).second(), sizes.count - index), within = 0;
      for (int j = 0; j < count; j++, index++) {
        result.add((chunks.get(chunk).first() + within) & 0xffffffffL);
        within = (within + sizes.get(index)) & 0xffffffffL;
      }
    }
    return result;
  }

  private record PreparedWrite(
    Mp4Tree tree, Mp4Tree.Node audio, Mp4Tree.Node oldChapter, Mp4Tree.Node tref,
    byte[] mvhd, int mvhdOffset, long durationMs, long movieDuration,
    List<Chapter> working, int[] sampleSizes, byte[] chapterMdat, long chapterId
  ) {
  }

  public static void write(Path path, List<Chapter> chapters) throws IOException {
    var prepared = prepareWrite(path, chapters);
    if (prepared == null) {
      return;
    }
    var tree = prepared.tree;
    var oldChapter = prepared.oldChapter;
    var chapterMdat = prepared.chapterMdat;
    try (var file = new BinaryFile(path)) {
      var topAtoms = Mp4Tree.tops(file);
      Mp4Tree.Top removeMdat = removableMdat(tree, oldChapter, topAtoms);
      // Native checks the literal 32-bit size before deleting, so a size-to-EOF mdat survives.
      if (removeMdat != null && file.u32be(removeMdat.offset()) != removeMdat.size()) {
        removeMdat = null;
      }
      if (removeMdat != null && removeMdat.offset() + removeMdat.size() != file.size()) {
        throw unsupported("Dedicated QuickTime chapter media is not at EOF");
      }
      for (var top : topAtoms) {
        if (top != removeMdat && file.u32be(top.offset()) == 0 && !chapters.isEmpty()) {
          throw unsupported("Cannot append QuickTime media after a size-to-EOF atom");
        }
      }
      long removed = removeMdat == null ? 0 : removeMdat.size();
      long delta = 0;
      for (int pass = 0; pass < 8; pass++) {
        var rendered = renderPass(prepared, file, removed, removeMdat, delta, chapters);
        byte[] movie = rendered.bytes;
        long nextDelta = rendered.delta;
        if (nextDelta == delta) {
          if ((long) movie.length + chapterMdat.length > Mp4Tree.LIMIT) {
            throw unsupported("QuickTime chapter edit exceeds the 64 MiB edit limit");
          }
          var edits = new ArrayList<Rewrite.Edit>();
          edits.add(new Rewrite.Edit(tree.top.offset(), tree.top.size(), movie));
          if (removeMdat != null) {
            edits.add(new Rewrite.Edit(removeMdat.offset(), removeMdat.size(), new byte[0]));
          }
          if (chapterMdat.length != 0) {
            edits.add(new Rewrite.Edit(file.size(), 0, chapterMdat));
          }
          Rewrite.replace(path, edits);
          return;
        }
        delta = nextDelta;
      }
      throw unsupported("QuickTime movie offsets did not stabilize");
    }
  }

  private record RenderedMovie(byte[] bytes, long delta) {
  }

  private static RenderedMovie renderPass(
    PreparedWrite prepared, BinaryFile file, long removed, Mp4Tree.Top removeMdat,
    long delta, List<Chapter> chapters
  ) {
    var tree = prepared.tree;
    var audio = prepared.audio;
    var oldChapter = prepared.oldChapter;
    var tref = prepared.tref;
    var mvhd = prepared.mvhd;
    int mvhdOffset = prepared.mvhdOffset;
    long durationMs = prepared.durationMs, movieDuration = prepared.movieDuration;
    var working = prepared.working;
    var sampleSizes = prepared.sampleSizes;
    long chapterId = prepared.chapterId;
    var plan = new Mp4EditPlan();
    var replacements = plan.replacements;
    if (oldChapter != null) {
      replacements.put(oldChapter.at(), new byte[0]);
    }
    relocate(tree, tree.movie, oldChapter, delta, removeMdat, replacements);
    updateReference(tree, audio, tref, oldChapter != null, chapters.isEmpty() ? null : chapterId,
      plan);
    if (!chapters.isEmpty()) {
      long textOffset = file.size() - removed + delta + 8;
      if (textOffset < 0 || textOffset > 0xffffffffL) {
        throw unsupported("QuickTime chapter output requires a 64-bit chunk offset");
      }
      replacements.put(mvhdOffset, mvhd);
      byte[] updatedAudio = tree.render(audio, 0, plan);
      byte[] chapterTrack = buildTrack(chapterId, durationMs, movieDuration, working, sampleSizes, textOffset);
      replacements.put(audio.at(), join(updatedAudio, chapterTrack));
    }
    byte[] movie = tree.render(tree.movie, 0, plan);
    return new RenderedMovie(movie, (long) movie.length - tree.source.length);
  }

  private static PreparedWrite prepareWrite(Path path, List<Chapter> chapters) throws IOException {
    var tree = new Mp4Tree(path);
    var audio = audio(tree);
    var oldChapter = chapterTrack(tree, audio);
    if (chapters.isEmpty() && oldChapter == null) {
      return null;
    }
    if (!tree.restriction.isEmpty()) {
      throw unsupported(tree.restriction);
    }
    if (audio == null) {
      throw unsupported("QuickTime chapter writing requires an audio track");
    }
    if (audio == oldChapter) {
      throw unsupported("Self-referential QuickTime chapter track");
    }
    if (audio.header() != 8 || u32be(tree.source, audio.at()) != audio.size()
      || audio.children("tref").size() > 1) {
      throw unsupported("Ambiguous or extended QuickTime audio track/reference");
    }
    var tref = audio.child("tref");
    if (tref != null && (tref.header() != 8 || u32be(tree.source, tref.at()) != tref.size()
      || !validTref(tree, tref))) {
      throw unsupported("Damaged QuickTime track references");
    }
    if (oldChapter != null && (tref == null || references(tree, tref).size() != 1)) {
      throw unsupported("Multiple QuickTime chapter references cannot be rewritten safely");
    }

    var mvhdNode = tree.movie.child("mvhd");
    byte[] mvhd = bytes(tree, mvhdNode);
    if (!chapters.isEmpty() && mvhdNode == null) {
      throw unsupported("QuickTime chapter writing requires a movie header");
    }
    int mvhdOffset = mvhdNode == null ? -1 : mvhdNode.at();
    long durationMs = 0, movieDuration = 0;
    if (!chapters.isEmpty()) {
      long scale = 0, duration = 0;
      if (mvhd.length >= 40 && mvhd[8] == 1) {
        scale = u32be(mvhd, 28);
        duration = u64be(mvhd, 32);
      } else if (mvhd.length >= 28) {
        scale = u32be(mvhd, 20);
        duration = u32be(mvhd, 24);
      }
      if (scale <= 0 || duration <= 0) {
        throw unsupported("QuickTime chapter writing requires movie duration");
      }
      durationMs = (long) ((double) duration * 1000.0 / scale + 0.5);
      if (durationMs <= 0 || durationMs > 0xffffffffL) {
        throw unsupported("QuickTime duration exceeds the 32-bit chapter timescale");
      }
      movieDuration = duration & 0xffffffffL;
    }
    var working = new ArrayList<>(chapters);
    if (!working.isEmpty() && working.getFirst().startTime() > 0) {
      working.addFirst(new Chapter("", 0));
    }
    if (working.size() > MAX_SAMPLES) {
      throw unsupported("Too many QuickTime chapter samples");
    }
    var sampleData = new ByteArrayOutputStream();
    int[] sampleSizes = new int[working.size()];
    for (int i = 0; i < working.size(); i++) {
      var chapter = working.get(i);
      if (chapter.startTime() < 0 || chapter.startTime() > 0xffffffffL) {
        throw unsupported("QuickTime chapter time exceeds the unsigned 32-bit timescale");
      }
      byte[] text = chapter.title().getBytes(StandardCharsets.UTF_8);
      if (text.length > Mp4Tree.LIMIT - sampleData.size() - 14) {
        throw unsupported("QuickTime chapter text exceeds the 64 MiB edit limit");
      }
      // Native stores the length modulo 65536, but retains the entire UTF-8 title in the sample.
      sampleData.write(text.length >>> 8);
      sampleData.write(text.length);
      sampleData.writeBytes(text);
      sampleData.writeBytes(atom("encd", new byte[]{0, 0, 1, 0}));
      sampleSizes[i] = text.length + 14;
    }
    byte[] chapterMdat = chapters.isEmpty() ? new byte[0] : atom("mdat", sampleData.toByteArray());
    long nextId = nextId(mvhd), chapterId = nextId > 0 ? nextId : (trackId(tree, audio) + 1) & 0xffffffffL;
    if (!chapters.isEmpty()) {
      int at = mvhd[8] == 1 ? 116 : 104;
      if (mvhd.length >= at + 4) {
        put32be(mvhd, at, (chapterId + 1) & 0xffffffffL);
      }
    }
    return new PreparedWrite(tree, audio, oldChapter, tref, mvhd, mvhdOffset, durationMs,
      movieDuration, working, sampleSizes, chapterMdat, chapterId);
  }

  private static boolean validTref(Mp4Tree tree, Mp4Tree.Node tref) {
    int at = tref.body();
    while (at < tref.end()) {
      if (tref.end() - at < 8) {
        return false;
      }
      long size = u32be(tree.source, at);
      if (size < 8 || size > tref.end() - at) {
        return false;
      }
      at += (int) size;
    }
    return true;
  }

  private static void updateReference(
    Mp4Tree tree, Mp4Tree.Node audio, Mp4Tree.Node tref,
    boolean removeOld, Long chapterId, Mp4EditPlan plan
  ) {
    var replacements = plan.replacements;
    var appendices = plan.appendices;
    byte[] reference = chapterId == null ? new byte[0] : atom("chap", uints(chapterId));
    if (tref == null) {
      if (reference.length != 0) {
        appendices.put(audio.at(), atom("tref", reference));
      }
      return;
    }
    byte[] content = tree.payload(tref);
    if (removeOld) {
      var old = references(tree, tref).getFirst();
      int relative = old.at - tref.body();
      content = join(Arrays.copyOfRange(content, 0, relative),
        Arrays.copyOfRange(content, relative + old.size, content.length));
    }
    content = join(content, reference);
    replacements.put(tref.at(), content.length == 0 ? new byte[0] : atom("tref", content));
  }

  private static Mp4Tree.Top removableMdat(Mp4Tree tree, Mp4Tree.Node chapter, List<Mp4Tree.Top> tops) {
    if (chapter == null) {
      return null;
    }
    var chunks = table(tree, chapter, "stco", 1);
    if (chunks.isEmpty()) {
      return null;
    }
    long first = chunks.getFirst().first();
    for (var top : tops) {
      if (top.name().equals("mdat") && first >= top.offset() + 8 && first < top.offset() + top.size()) {
        if (top.header() != 8 || top.size() > 0xffffffffL) {
          return null;
        }
        return referencesMdat(tree, tree.movie, chapter, top) ? null : top;
      }
    }
    return null;
  }

  private static boolean referencesMdat(
    Mp4Tree tree, Mp4Tree.Node node, Mp4Tree.Node removed,
    Mp4Tree.Top mdat
  ) {
    if (node == removed) {
      return false;
    }
    if (node.name().equals("stco") || node.name().equals("co64")) {
      var table = offsetTable(tree, node);
      for (int i = 0; i < table.count(); i++) {
        long offset = table.get(i);
        if (offset >= mdat.offset() + 8 && offset < mdat.offset() + mdat.size()) {
          return true;
        }
      }
    }
    for (var child : node.children()) {
      if (referencesMdat(tree, child, removed, mdat)) {
        return true;
      }
    }
    return false;
  }

  private static void relocate(
    Mp4Tree tree, Mp4Tree.Node node, Mp4Tree.Node removed, long delta,
    Mp4Tree.Top removedMdat, Map<Integer, byte[]> replacements
  ) {
    if (node == removed) {
      return;
    }
    if (node.name().equals("stco") || node.name().equals("co64")) {
      var table = offsetTable(tree, node);
      long[] values = new long[table.count()];
      boolean wide = table.width() == 8;
      long movieEnd = tree.top.offset() + tree.top.size();
      for (int i = 0; i < values.length; i++) {
        long value = table.get(i);
        if (value < 0 || (value >= tree.top.offset() && value < movieEnd)) {
          throw unsupported("Damaged MP4 chunk offset");
        }
        if (removedMdat != null && value >= removedMdat.offset()) {
          throw unsupported("Surviving chunk offset crosses removed chapter media");
        }
        if (value >= movieEnd) {
          try {
            value = Math.addExact(value, delta);
          } catch (ArithmeticException e) {
            throw unsupported("MP4 chunk offset overflow");
          }
          if (value < 0) {
            throw unsupported("Negative MP4 chunk offset");
          }
        }
        values[i] = value;
        wide |= value > 0xffffffffL;
      }
      var data = new ByteArrayOutputStream();
      data.write(tree.source, node.body(), 8);
      byte[] encoded = new byte[wide ? 8 : 4];
      for (long value : values) {
        if (wide) {
          put64be(encoded, 0, value);
        } else {
          put32be(encoded, 0, value);
        }
        data.writeBytes(encoded);
      }
      int end = table.trailingOffset();
      data.write(tree.source, end, node.end() - end);
      replacements.put(node.at(), atom(wide ? "co64" : "stco", data.toByteArray()));
    }
    for (var child : node.children()) {
      relocate(tree, child, removed, delta, removedMdat, replacements);
    }
  }

  private static Mp4Tree.OffsetTable offsetTable(Mp4Tree tree, Mp4Tree.Node node) {
    return Mp4Tree.offsetTable(tree.source, node,
      () -> unsupported("Truncated MP4 chunk offset table"),
      () -> unsupported("Truncated MP4 chunk offset entries"));
  }

  private static long nextId(byte[] mvhd) {
    int at = mvhd.length >= 9 && mvhd[8] == 1 ? 116 : 104;
    return mvhd.length >= at + 4 ? u32be(mvhd, at) : 0;
  }

  private static byte[] buildTrack(
    long id, long duration, long movieDuration, List<Chapter> chapters,
    int[] sizes, long textOffset
  ) {
    byte[] tkhd = new byte[80];
    put32be(tkhd, 8, id);
    put32be(tkhd, 16, duration);
    put32be(tkhd, 36, 0x10000);
    put32be(tkhd, 52, 0x10000);
    put32be(tkhd, 68, 0x40000000);
    byte[] mdhd = new byte[20];
    put32be(mdhd, 8, 1000);
    put32be(mdhd, 12, duration);
    byte[] hdlr = join(uints(0), Binary.bytes("text"), new byte[12],
      Binary.bytes("Chapter\0"));
    byte[] textInfo = new byte[36];
    textInfo[1] = 1;
    textInfo[17] = 1;
    put32be(textInfo, 32, 0x40000000);
    byte[] gmhd = atom("gmhd", join(full("gmin", 0,
      new byte[]{0, 64, -128, 0, -128, 0, -128, 0, 0, 0, 0, 0}), atom("text", textInfo)));
    byte[] dinf = atom("dinf", full("dref", 0, join(uints(1), full("url ", 1, new byte[0]))));
    byte[] entry = HexFormat.of().parseHex(
      "00000000000000010000000100000000000000000000000000000000000000010000000000000000000d667461620001000100");
    byte[] stsd = full("stsd", 0, join(uints(1), atom("text", entry)));
    var timing = new ByteArrayOutputStream();
    timing.writeBytes(uints(chapters.size()));
    for (int i = 0; i < chapters.size(); i++) {
      long start = chapters.get(i).startTime();
      long length = i + 1 < chapters.size() ? chapters.get(i + 1).startTime() - start
        : duration > start ? duration - start : 0;
      timing.writeBytes(uints(1, length & 0xffffffffL));
    }
    var sampleSizes = new ByteArrayOutputStream();
    sampleSizes.writeBytes(uints(0, sizes.length));
    for (int size : sizes) {
      sampleSizes.writeBytes(uints(size));
    }
    byte[] stbl = atom("stbl", join(stsd, full("stts", 0, timing.toByteArray()),
      full("stsz", 0, sampleSizes.toByteArray()), full("stsc", 0, uints(1, 1, chapters.size(), 1)),
      full("stco", 0, uints(1, textOffset))));
    byte[] mdia = atom("mdia", join(full("mdhd", 0, mdhd), full("hdlr", 0, hdlr),
      atom("minf", join(gmhd, dinf, stbl))));
    byte[] edts = atom("edts", full("elst", 0, uints(1, movieDuration, 0, 0x10000)));
    return atom("trak", join(full("tkhd", 2, tkhd), edts, mdia));
  }

  private static byte[] full(String type, long flags, byte[] bytes) {
    return atom(type, join(uints(flags), bytes));
  }

  private static byte[] uints(long... values) {
    byte[] result = new byte[values.length * 4];
    for (int i = 0; i < values.length; i++) {
      put32be(result, i * 4, values[i]);
    }
    return result;
  }

  private static byte[] join(byte[]... parts) {
    var out = new ByteArrayOutputStream();
    for (byte[] part : parts) {
      if (part.length > Mp4Tree.LIMIT - out.size()) {
        throw unsupported("QuickTime chapter metadata exceeds the 64 MiB edit limit");
      }
      out.writeBytes(part);
    }
    return out.toByteArray();
  }

  private static MetadataEditException unsupported(String message) {
    return new MetadataEditException(message);
  }
}
