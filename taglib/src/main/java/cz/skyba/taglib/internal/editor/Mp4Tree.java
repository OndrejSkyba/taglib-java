// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.editor;

import cz.skyba.taglib.MetadataEditException;
import cz.skyba.taglib.MetadataReadException;
import cz.skyba.taglib.TagLibException;
import cz.skyba.taglib.internal.Binary;
import cz.skyba.taglib.internal.BinaryFile;
import cz.skyba.taglib.internal.Rewrite;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

import static cz.skyba.taglib.internal.Binary.*;

/// Bounded physical movie tree. Media bytes are never materialized and relocation is staged.
final class Mp4Tree {
  static final int LIMIT = 64 * 1024 * 1024;
  private static final Set<String> CONTAINERS =
    Set.of(
      "moov", "udta", "meta", "trak", "mdia", "minf", "stbl", "ilst", "edts", "dinf", "mvex",
      "moof", "traf", "mfra", "stsd");
  private static final Set<String> META_CHILDREN = Set.of("hdlr", "ilst", "mhdr", "ctry", "lang");
  private static final Set<String> UNSAFE_TOP_LEVEL = Set.of("moof", "sidx", "mfra");

  record Top(String name, long offset, long size, int header) {
  }

  record Node(String name, int at, int size, int header, List<Node> children) {
    int body() {
      return at + header;
    }

    int end() {
      return at + size;
    }

    Node child(String name) {
      for (var child : children) {
        if (child.name.equals(name)) {
          return child;
        }
      }
      return null;
    }

    Node path(String... route) {
      Node result = this;
      for (var name : route) {
        if (result == null) {
          return null;
        }
        result = result.child(name);
      }
      return result;
    }

    List<Node> children(String name) {
      return children.stream().filter(n -> n.name.equals(name)).toList();
    }

    boolean contains(String name) {
      if (this.name.equals(name)) {
        return true;
      }
      for (var child : children) {
        if (child.contains(name)) {
          return true;
        }
      }
      return false;
    }
  }

  record OffsetTable(byte[] source, Node node, int width, int count) {
    long get(int index) {
      int at = node.body() + 8 + index * width;
      return width == 4 ? u32be(source, at) : u64be(source, at);
    }

    int trailingOffset() {
      return node.body() + 8 + count * width;
    }
  }

  static OffsetTable offsetTable(
    byte[] source, Node node,
    Supplier<? extends TagLibException> shortHeader, Supplier<? extends TagLibException> shortEntries
  ) {
    int width = node.name.equals("stco") ? 4 : 8;
    if (node.size - node.header < 8) {
      throw shortHeader.get();
    }
    long count = u32be(source, node.body() + 4);
    if (count > (node.size - node.header - 8) / width) {
      throw shortEntries.get();
    }
    return new OffsetTable(source, node, width, (int) count);
  }

  final byte[] source;
  final Top top;
  final Node movie;
  final String restriction;

  Mp4Tree(Path path) throws IOException {
    try (var file = new BinaryFile(path)) {
      var tops = tops(file);
      top =
        tops.stream()
          .filter(a -> a.name.equals("moov"))
          .findFirst()
          .orElseThrow(
            () -> new MetadataReadException("Required MP4 file information is missing."));
      if (top.size > LIMIT) {
        throw new MetadataEditException("MP4 metadata exceeds the 64 MB editing limit.");
      }
      source = file.read(top.offset, (int) top.size);
      var parser = new AtomParser(source, file.size() - top.offset);
      movie = parser.parse(0, source.length, 0, false);
      if (!movie.name.equals("moov")) {
        throw damaged();
      }
      boolean unsafe = tops.stream().anyMatch(a -> UNSAFE_TOP_LEVEL.contains(a.name));
      for (var name : List.of("mvex", "iloc", "saio", "cmov")) {
        unsafe |= movie.contains(name);
      }
      if (tops.stream().filter(a -> a.name.equals("moov")).count() != 1) {
        unsafe = true;
      }
      restriction = parser.damagedMetadataPadding
        ? "MP4 metadata padding is damaged. Tags can be viewed but cannot be edited."
        : unsafe ? "Tags in this MP4 layout can be viewed but cannot be edited." : "";
    }
  }

  static List<Top> tops(BinaryFile file) throws IOException {
    var tops = new ArrayList<Top>();
    for (long at = 0; at < file.size(); ) {
      if (file.size() - at < 8) {
        throw damaged();
      }
      byte[] bytes = file.read(at, 8);
      long size = u32be(bytes, 0);
      int header = 8;
      if (size == 1) {
        if (file.size() - at < 16) {
          throw damaged();
        }
        size = file.u64be(at + 8);
        header = 16;
      }
      if (size == 0) {
        size = file.size() - at;
      }
      if (size < header || size > file.size() - at) {
        throw damaged();
      }
      tops.add(new Top(ascii(bytes, 4, 4), at, size, header));
      if (tops.size() > 100_000) {
        throw new MetadataEditException("Too many MP4 atoms.");
      }
      at += size;
    }
    return List.copyOf(tops);
  }

  private static final class AtomParser {
    private final byte[] data;
    private final long fileEnd;
    private int count;
    private boolean damagedMetadataPadding;

    private AtomParser(byte[] data, long fileEnd) {
      this.data = data;
      this.fileEnd = fileEnd;
    }

    private Node parse(int at, int end, int depth, boolean metadataChild) {
      if (depth > 64 || ++count > 100_000) {
        throw new MetadataEditException("The MP4 atom nesting/count limit is exceeded.");
      }
      if (at < 0 || end - at < 8) {
        throw damaged();
      }
      long size = u32be(data, at);
      int header = 8;
      String name = ascii(data, at + 4, 4);
      if (size == 1) {
        if (end - at < 16) {
          throw damaged();
        }
        size = u64be(data, at + 8);
        header = 16;
      }
      if (size == 0) {
        size = end - at;
      }
      if (size > end - at && size <= fileEnd - at && metadataChild
        && name.equals("free") && zeroes(at + header, end)) {
        // Retain readable metadata without trusting this malformed extent for edits.
        damagedMetadataPadding = true;
        size = end - at;
      }
      if (size < header || size > end - at) {
        throw damaged();
      }
      int stop = at + (int) size;
      var children = new ArrayList<Node>();
      if (CONTAINERS.contains(name)) {
        int from = at + header;
        if (name.equals("meta")) {
          boolean full = true;
          if (stop - from >= 8) {
            full = !META_CHILDREN.contains(ascii(data, from + 4, 4));
          }
          if (full) {
            from += 4;
          }
        } else if (name.equals("stsd")) {
          from += 8;
        }
        if (from > stop) {
          throw damaged();
        }
        while (from < stop) {
          if (name.equals("meta") && stop - from < 8 && zeroes(from, stop)) {
            damagedMetadataPadding = true;
            break;
          }
          var child = parse(from, stop, depth + 1, name.equals("meta"));
          children.add(child);
          from = child.end();
        }
      }
      return new Node(name, at, (int) size, header, List.copyOf(children));
    }

    private boolean zeroes(int from, int end) {
      for (int at = from; at < end; at++) {
        if (data[at] != 0) {
          return false;
        }
      }
      return true;
    }
  }

  byte[] payload(Node node) {
    return Arrays.copyOfRange(source, node.body(), node.end());
  }

  byte[] bytes(Node node) {
    return Arrays.copyOfRange(source, node.at, node.end());
  }

  void save(Path path, Mp4EditPlan plan) throws IOException {
    plan.finishAppendices();
    var replacements = plan.replacements;
    var appendices = plan.appendices;
    if (replacements.isEmpty() && appendices.isEmpty()) {
      return;
    }
    if (!restriction.isEmpty()) {
      throw new MetadataEditException(restriction);
    }
    plan = plan.withCopiedReplacements();
    replacements = plan.replacements;
    reusePadding(movie, replacements, new HashSet<>());
    var direct = new ArrayList<Rewrite.Edit>();
    var consumed = new HashSet<Integer>();
    if (appendices.isEmpty()
      && fixedSizeEdits(movie, replacements, direct, consumed)
      && consumed.size() == replacements.size()) {
      validateOffsets(movie);
      Rewrite.replace(path, direct);
      return;
    }
    long delta = 0;
    for (int pass = 0; pass < 8; pass++) {
      byte[] next = render(movie, delta, plan);
      long nextDelta = (long) next.length - source.length;
      if (delta == nextDelta) {
        Rewrite.replace(path, top.offset, top.size, next);
        return;
      }
      delta = nextDelta;
    }
    throw new MetadataEditException("Could not update the MP4 file structure safely.");
  }

  /// Consecutive changed siblings can share padding without changing any parent or media offset.
  private boolean fixedSizeEdits(
    Node parent,
    Map<Integer, byte[]> replacements,
    List<Rewrite.Edit> edits,
    Set<Integer> consumed
  ) {
    var children = parent.children;
    for (int i = 0; i < children.size(); ) {
      Node first = children.get(i);
      if (!replacements.containsKey(first.at)) {
        if (!fixedSizeEdits(first, replacements, edits, consumed)) {
          return false;
        }
        i++;
        continue;
      }
      int end = first.at;
      var bytes = new ByteArrayOutputStream();
      while (i < children.size()) {
        Node child = children.get(i);
        byte[] replacement = replacements.get(child.at);
        if (replacement == null) {
          break;
        }
        if (child.at != end) {
          return false;
        }
        consumed.add(child.at);
        append(bytes, replacement);
        end = child.end();
        i++;
      }
      if (bytes.size() != end - first.at) {
        return false;
      }
      edits.add(new Rewrite.Edit(top.offset + first.at, end - first.at, bytes.toByteArray()));
    }
    return true;
  }

  /// Keep the full renderer's malformed-offset checks, without allocating or rendering tables.
  private void validateOffsets(Node node) {
    if (node.name.equals("stco") || node.name.equals("co64")) {
      var table = offsetTable(source, node, Mp4Tree::damagedAudio, Mp4Tree::damagedAudio);
      for (int i = 0; i < table.count; i++) {
        long value = table.get(i);
        if (value < 0 || (value >= top.offset && value < top.offset + top.size)) {
          throw damagedAudio();
        }
      }
    }
    for (var child : node.children) {
      validateOffsets(child);
    }
  }

  /// Native TagLib's saveExisting policy, scoped to each physical ilst and its adjacent free atoms.
  private void reusePadding(Node parent, Map<Integer, byte[]> replacements, Set<Integer> claimed) {
    var children = parent.children;
    for (int i = 0; i < children.size(); i++) {
      Node list = children.get(i);
      byte[] replacement = replacements.get(list.at);
      if (!list.name.equals("ilst") || replacement == null) {
        reusePadding(list, replacements, claimed);
        continue;
      }
      int capacity = list.size;
      capacity += claimPadding(children, i - 1, replacements, claimed);
      capacity += claimPadding(children, i + 1, replacements, claimed);
      replacements.put(list.at, paddedList(replacement, capacity));
    }
  }

  private static int claimPadding(
    List<Node> siblings,
    int index,
    Map<Integer, byte[]> replacements,
    Set<Integer> claimed
  ) {
    if (index < 0 || index >= siblings.size()) {
      return 0;
    }
    Node neighbor = siblings.get(index);
    if (!neighbor.name.equals("free") || !claimed.add(neighbor.at)) {
      return 0;
    }
    replacements.put(neighbor.at, new byte[0]);
    return neighbor.size;
  }

  static byte[] paddedList(byte[] list, int capacity) {
    int spare = capacity - list.length;
    if (spare == 0) {
      return list;
    }
    int padding = spare >= 8 ? spare - 8 : ((list.length + 1023) & ~1023) - list.length;
    if ((long) list.length + padding + 8 > LIMIT) {
      throw new MetadataEditException("MP4 metadata exceeds the 64 MB editing limit.");
    }
    byte[] fill = new byte[padding];
    Arrays.fill(fill, (byte) 1);
    return Mp4Items.join(list, Mp4Items.atom("free", fill));
  }

  byte[] render(Node node, long delta, Mp4EditPlan plan) {
    var replacements = plan.replacements;
    var appendices = plan.appendices;
    byte[] replacement = replacements.get(node.at);
    if (replacement != null) {
      return replacement;
    }
    var out = new ByteArrayOutputStream();
    String name = node.name;
    if (name.equals("stco") || name.equals("co64")) {
      var table = offsetTable(source, node, Mp4Tree::damagedAudio, Mp4Tree::damagedAudio);
      int width = table.width;
      long[] offsets = new long[table.count];
      boolean promote = false;
      for (int i = 0; i < offsets.length; i++) {
        long value = table.get(i);
        if (value < 0 || (value >= top.offset && value < top.offset + top.size)) {
          throw damagedAudio();
        }
        if (value >= top.offset + top.size) {
          try {
            value = Math.addExact(value, delta);
          } catch (ArithmeticException e) {
            throw damagedAudio();
          }
          if (value < 0) {
            throw damagedAudio();
          }
        }
        offsets[i] = value;
        promote |= value > 0xffffffffL;
      }
      boolean wide = width == 8 || promote;
      if (wide) {
        name = "co64";
      }
      out.write(source, node.body(), 8);
      byte[] value = new byte[wide ? 8 : 4];
      for (long offset : offsets) {
        if (wide) {
          put64be(value, 0, offset);
        } else {
          put32be(value, 0, offset);
        }
        append(out, value);
      }
      int trailing = table.trailingOffset();
      out.write(source, trailing, node.end() - trailing);
    } else {
      int cursor = node.body();
      for (var child : node.children) {
        out.write(source, cursor, child.at - cursor);
        append(out, render(child, delta, plan));
        cursor = child.end();
      }
      out.write(source, cursor, node.end() - cursor);
      byte[] appendix = appendices.get(node.at);
      if (appendix != null) {
        append(out, appendix);
      }
    }
    if (out.size() > LIMIT - node.header) {
      throw new MetadataEditException("MP4 metadata exceeds the 64 MB editing limit.");
    }
    if (node.header == 16) {
      byte[] rendered = new byte[out.size() + 16];
      put32be(rendered, 0, 1);
      System.arraycopy(Binary.bytes(name), 0, rendered, 4, 4);
      put64be(rendered, 8, rendered.length);
      System.arraycopy(out.toByteArray(), 0, rendered, 16, out.size());
      return rendered;
    }
    return Mp4Items.atom(name, out.toByteArray());
  }

  private static void append(ByteArrayOutputStream out, byte[] bytes) {
    if (bytes.length > LIMIT - out.size()) {
      throw new MetadataEditException("MP4 metadata exceeds the 64 MB editing limit.");
    }
    out.writeBytes(bytes);
  }

  private static MetadataReadException damaged() {
    return new MetadataReadException("The MP4 file structure is damaged.");
  }

  private static MetadataReadException damagedAudio() {
    return new MetadataReadException("The MP4 audio layout is damaged.");
  }
}
