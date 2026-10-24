// SPDX-License-Identifier: LGPL-2.1-only OR MPL-2.0
package cz.skyba.taglib.internal.editor;

import cz.skyba.taglib.mp4.Chapter;
import cz.skyba.taglib.mp4.Stem;
import cz.skyba.taglib.mp4.Tag;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static cz.skyba.taglib.internal.Binary.*;

public final class Mp4Structured {
  private Mp4Structured() {
  }

  public static Tag read(Path path) throws IOException {
    var tree = new Mp4Tree(path);
    var chapterAtom = tree.movie.path("udta", "chpl");
    var stem = tree.movie.path("udta", "stem");
    return new Tag(chapterAtom == null ? List.of() : chapters(tree.payload(chapterAtom)),
      stem == null ? null : new Stem(tree.payload(stem)));
  }

  private static List<Chapter> chapters(byte[] bytes) {
    if (bytes.length < 5) {
      return List.of();
    }
    int at = (bytes[0] & 255) >= 1 ? 8 : 4;
    if (at >= bytes.length) {
      return List.of();
    }
    int count = bytes[at++] & 255;
    var result = new ArrayList<Chapter>();
    for (int i = 0; i < count && bytes.length - at >= 9; i++) {
      long time = u64be(bytes, at) / 10_000;
      at += 8;
      int length = bytes[at++] & 255;
      String title = "";
      if (length > 0 && length <= bytes.length - at) {
        title = new String(bytes, at, length, StandardCharsets.UTF_8);
        at += length;
      }
      result.add(new Chapter(title, time));
    }
    return List.copyOf(result);
  }

  private static byte[] renderChapters(List<Chapter> chapters) throws IOException {
    if (chapters.isEmpty()) {
      return new byte[0];
    }
    var out = new ByteArrayOutputStream();
    out.writeBytes(new byte[]{1, 0, 0, 0, 0, 0, 0, 0});
    int count = Math.min(chapters.size(), 255);
    out.write(count);
    for (int i = 0; i < count; i++) {
      var chapter = chapters.get(i);
      byte[] time = new byte[8];
      try {
        put64be(time, 0, Math.multiplyExact(chapter.startTime(), 10_000));
      } catch (ArithmeticException e) {
        throw new IOException("MP4 chapter timestamp overflows its on-disk representation", e);
      }
      out.writeBytes(time);
      byte[] title = chapter.title().getBytes(StandardCharsets.UTF_8);
      int length = Math.min(title.length, 255);
      out.write(length);
      out.write(title, 0, length);
    }
    return Mp4Items.atom("chpl", out.toByteArray());
  }

  public static void write(Path path, Tag tag) throws IOException {
    var tree = new Mp4Tree(path);
    var plan = new Mp4EditPlan();
    byte[] chapters = renderChapters(tag.neroChapters());
    put(tree, List.of("udta", "chpl"), chapters, plan);
    byte[] stem = tag.stem().isEmpty() ? new byte[0]
      : Mp4Items.atom("stem", tag.stem().orElseThrow().data());
    put(tree, List.of("udta", "stem"), stem, plan);
    tree.save(path, plan);
  }

  private static void put(
    Mp4Tree tree, List<String> route, byte[] value,
    Mp4EditPlan plan
  ) {
    var replacements = plan.replacements;
    var appendices = plan.appendices;
    var parent = tree.movie;
    for (int i = 0; i < route.size(); i++) {
      var child = parent.child(route.get(i));
      if (child == null) {
        if (value.length == 0) {
          return;
        }
        // Missing udta shared by chapters and stem must be merged, not appended twice.
        if (i == 0 && appendices.containsKey(parent.at())) {
          byte[] old = appendices.remove(parent.at());
          byte[] nested = wrap(route, i + 1, value);
          appendices.put(parent.at(), Mp4Items.atom("udta",
            Mp4Items.join(Arrays.copyOfRange(old, 8, old.length), nested)));
        } else {
          byte[] nested = wrap(route, i, value);
          appendices.merge(parent.at(), nested, Mp4Items::join);
        }
        return;
      }
      if (i == route.size() - 1) {
        replacements.put(child.at(), value);
        return;
      }
      parent = child;
    }
  }

  private static byte[] wrap(List<String> route, int from, byte[] value) {
    for (int i = route.size() - 2; i >= from; i--) {
      if (route.get(i).equals("meta")) {
        byte[] handler = new byte[25];
        System.arraycopy(bytes("mdirappl"), 0, handler, 8, 8);
        value = Mp4Items.join(new byte[4],
          Mp4Items.join(Mp4Items.atom("hdlr", handler), value));
      }
      value = Mp4Items.atom(route.get(i), value);
    }
    return value;
  }
}
