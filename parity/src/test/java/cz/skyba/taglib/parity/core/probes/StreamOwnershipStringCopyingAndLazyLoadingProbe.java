package cz.skyba.taglib.parity.core.probes;

import cz.skyba.taglib.mp4.Mp4File;
import cz.skyba.taglib.mpeg.MpegFile;
import cz.skyba.taglib.parity.support.OpenOutcome;

import java.io.IOException;

import cz.skyba.taglib.*;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.toolkit.*;

import java.nio.channels.FileChannel;
import java.nio.channels.NonWritableChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

class StreamOwnershipStringCopyingAndLazyLoadingProbe {
  static String hex(ByteVector b) {
    return HexFormat.of().formatHex(b.toByteArray());
  }

  static String text(String s) {
    return hex(new TagString(s).data(TagString.Type.UTF16BE));
  }

  static void put(String k, String s) {
    System.out.println(k + "\t" + s);
  }

  static int bit(boolean b) {
    return b ? 1 : 0;
  }

  static class Bare extends FileBase {
    Bare(IOStream s) {
      super(s);
    }

    IOStream writableView() {
      return ownerStream();
    }

    public TagBase tag() {
      return null;
    }

    public AudioProperties audioProperties() {
      return null;
    }

    public void save() {
      throw new cz.skyba.taglib.TagLibException("Test save rejected");
    }
  }

  static class Footer extends cz.skyba.taglib.ape.Footer {
    void apply(ByteVector b) {
      parse(b);
    }

    ByteVector raw(boolean h) {
      return render(h);
    }
  }

  static class Ape extends cz.skyba.taglib.ape.Tag {
    static String joined(List<String> values) {
      return joinTagValues(values);
    }

    Ape() {
      super();
    }

    Ape(FileBase f, long o) {
      super(f, o);
    }

    void apply(ByteVector b) {
      parse(b);
    }

    void again() {
      read();
    }
  }

  static int concurrent(Runnable task) throws Exception {
    var ready = new CountDownLatch(8);
    var go = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(8)) {
      var fs = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 8; i++)
        fs.add(
          executor.submit(
            () -> {
              ready.countDown();
              go.await();
              try {
                for (int n = 0; n < 25; n++) task.run();
                return true;
              } catch (Throwable failure) {
                return false;
              }
            }));
      if (!ready.await(30, TimeUnit.SECONDS)) throw new AssertionError("threads");
      go.countDown();
      int success = 0;
      for (var f : fs) if (f.get(30, TimeUnit.SECONDS)) success++;
      return success;
    }
  }

  static void lazy(Path corpus) throws Exception {
    var fields = new ByteVector((byte) 0);
    for (String s :
      List.of(
        "Guitar",
        "Artist 1",
        "Drums",
        "Artist 2",
        "Producer",
        "Artist 3",
        "Engineer",
        "Artist 4")) {
      if (fields.size() > 1) fields.append((byte) 0);
      fields.append(new ByteVector(s));
    }
    var frame =
      new ByteVector("IPLS")
        .append(ByteVector.fromUnsignedInt(fields.size()))
        .append(new ByteVector(2))
        .append(fields);
    var data =
      new ByteVector("ID3")
        .append((byte) 3)
        .append(new ByteVector(2))
        .append(cz.skyba.taglib.id3.v2.SynchData.fromUnsignedInt(frame.size()))
        .append(frame);
    put(
      "lazy/map",
      ""
        + concurrent(
        () -> {
          if (cz.skyba.taglib.id3.v2.TextIdentificationFrame.involvedPeopleMap().size() != 5)
            throw new AssertionError();
        }));
    put(
      "lazy/ipls",
      ""
        + concurrent(
        () -> {
          try (var f = new MpegFile(new ByteVectorStream(data), ReadOptions.TAGS_ONLY)) {
            if (!f.hasId3v2Tag()
              || f.id3v2Tag().frameList(new ByteVector("TMCL")).size() != 1)
              throw new AssertionError();
          }
        }));
    var mp4 = new ByteVector(Files.readAllBytes(corpus.resolve("has-tags.m4a")));
    if (mp4.isEmpty()) throw new AssertionError();
    put(
      "lazy/mp4",
      ""
        + concurrent(
        () -> {
          try (var f = new Mp4File(new ByteVectorStream(mp4))) {
            if (!f.isValid() || f.tag().properties().isEmpty()) throw new AssertionError();
          }
        }));
    var p = PropertyMap.builder().put("TITLE", "Title").put("ARTIST", "Artist").build();
    put(
      "lazy/info",
      ""
        + concurrent(
        () -> {
          if (!new cz.skyba.taglib.riff.info.Tag().replaceProperties(p).rejected().isEmpty())
            throw new AssertionError();
        }));
    put(
      "lazy/asf",
      ""
        + concurrent(
        () -> {
          if (!new cz.skyba.taglib.asf.Tag().replaceProperties(p).rejected().isEmpty())
            throw new AssertionError();
        }));
    try (var f = new MpegFile(new ByteVectorStream(data), ReadOptions.TAGS_ONLY)) {
      put(
        "lazy/final",
        bit(f.hasId3v2Tag()) + ":" + f.id3v2Tag().frameList(new ByteVector("TMCL")).size());
    }
  }

  static void values() {
    byte[] buffer = {'a', 0, 'b', 'c', 0};
    for (int n = 0; n <= 4; n++) {
      var b = new ByteVector(buffer, n);
      var setter = new ByteVector().setData(buffer, n);
      put(
        "buffer/" + n,
        hex(b)
          + ":"
          + hex(new ByteVector("a\0bc", n))
          + ":"
          + hex(setter)
          + ":"
          + hex(new ByteVector().setData("a\0bc", n))
          + ":"
          + hex(ByteVector.fromCString("a\0bc", n)));
    }
    var setter = new ByteVector().setData("a\0bc");
    put("buffer/default", hex(ByteVector.fromCString("a\0bc")) + ":" + hex(setter));
    var numbers = new ByteVector(new byte[]{1, 2, 3});
    put(
      "defaults/numbers",
      numbers.toUnsignedInt(0)
        + ":"
        + numbers.toUnsignedInt(0, 3)
        + ":"
        + new ByteVector(".OggS....").rfind((byte) 'O', 4)
        + ":"
        + bit(new ByteVector("blah blah").containsAt(new ByteVector("blah"), 6, 1)));
    put(
      "defaults/stringlist",
      text(
        String.join(" ", List.of(new ByteVector("a"), new ByteVector("b")).stream()
          .map(value -> new TagString(value).toString()).toList())));
    for (int i = 0; i < 4; i++) {
      List<String> v =
        switch (i) {
          case 1 -> List.of("one");
          case 2 -> List.of("one", "two");
          case 3 -> List.of("a\0b", "c");
          default -> List.of();
        };
      put("join/" + i, text(Ape.joined(v)));
    }
    var u = new TagString("a\0b");
    u.append(new TagString("c"));
    put("copy/unique", text(u.toString()));
    var a = new TagString("abc");
    var b = new TagString(a);
    a.append(new TagString("x"));
    put("copy/first", text(a.toString()) + ":" + text(b.toString()));
    b.append(new TagString("y"));
    put("copy/second", text(b.toString()));
    for (String s : List.of("", "abc")) {
      var t = new TagString(s);
      var before = new TagString(t);
      int end = t.at(t.size());
      t.set(t.size(), (char) 0);
      put("terminator/" + s.length(), end + ":" + text(t.toString()) + ":" + bit(t.equals(before)));
    }
  }

  static String common(TagBase t) {
    return text(t.title())
      + ":"
      + text(t.artist())
      + ":"
      + text(t.genre())
      + ":"
      + t.year()
      + ":"
      + t.track()
      + ":"
      + bit(t.isEmpty());
  }

  static void commonCases() {
    var numeric = new TagString("123");
    var signed = numeric.extractLong();
    var unsigned = numeric.extractUnsignedLong();
    put(
      "defaults/string-numeric",
      signed.value()
        + ":"
        + bit(signed.ok())
        + ":"
        + unsigned.value()
        + ":"
        + bit(unsigned.ok()));
    var audio = new AudioProperties(2064, 0, 0, 2, 0, false);
    put("audio/seconds", "" + audio.lengthInSeconds());
    var s = new ByteVectorStream(new ByteVector("abc"));
    var f = new Bare(s);
    var view = f.writableView();
    view.insert(new ByteVector("X"), 1);
    view.removeBlock(0, 0);
    view.removeBlock(1, 0);
    put("removal/file-zero-length", hex(s.data()) + ":" + s.tell());
    s.removeBlock(1, 0);
    put("removal/stream-zero-length", hex(s.data()) + ":" + s.tell());
    var str = TagString.from8Bit(new byte[]{(byte) 0xe9});
    put("defaults/string8", hex(new ByteVector(str.to8Bit())) + ":" + text(str.toString()));
    var a = new cz.skyba.taglib.riff.info.Tag();
    var b = new cz.skyba.taglib.id3.v1.Tag();
    var c = new cz.skyba.taglib.ape.Tag();
    b.setArtist("second");
    b.setTrack(7);
    c.setTitle("third");
    c.setYear(2026);
    var u = new TagUnion(a, b, c);
    put("union/read", common(u));
    u.setTitle("broadcast");
    u.setGenre("Rock");
    u.setYear(0xffff_ffffL);
    put("union/broadcast", common(u) + ":" + bit(u.tag(0) == a) + ":" + bit(u.tag(1) == b));
    u.set(0, null);
    put("union/replace", common(u) + ":" + bit(u.tag(0) == null));
  }

  static void footerState(String key, Footer f) {
    put(
      key,
      f.version()
        + ":"
        + f.tagSize()
        + ":"
        + f.itemCount()
        + ":"
        + bit(f.headerPresent())
        + ":"
        + bit(f.footerPresent())
        + ":"
        + bit(f.isHeader())
        + ":"
        + hex(f.raw(true))
        + ":"
        + hex(f.renderHeader())
        + ":"
        + hex(f.renderFooter()));
  }

  static void apeCases() {
    var f = new Footer();
    f.setTagSize(99);
    f.setItemCount(7);
    for (int n = 0; n <= 32; n++) {
      f.apply(new ByteVector(n));
      footerState("footer/" + n, f);
    }
    var first = new cz.skyba.taglib.ape.Tag();
    first.setTitle("first");
    var data = first.render();
    var s = new ByteVectorStream(data);
    var file = new Bare(s);
    var t = new Ape(file, data.size() - 32);
    put(
      "ape/read",
      text(t.title()) + ":" + text(t.artist()) + ":" + s.tell() + ":" + hex(t.render()));
    var second = new cz.skyba.taglib.ape.Tag();
    second.setArtist("next");
    var next = second.render();
    if (next.size() != data.size()) throw new AssertionError();
    s.seek(0);
    s.writeBlock(next);
    t.again();
    put(
      "ape/repeat",
      text(t.title()) + ":" + text(t.artist()) + ":" + s.tell() + ":" + hex(t.render()));
    var item = new cz.skyba.taglib.ape.Item();
    item.setBinaryData(new ByteVector("inactive"));
    item.setType(cz.skyba.taglib.ape.Item.ItemType.Text);
    put("ape/inactive-text", hex(item.binaryData()));
    item.setType(cz.skyba.taglib.ape.Item.ItemType.Locator);
    put("ape/inactive-locator", hex(item.binaryData()));
  }

  static void streams(Path root) throws Exception {
    for (int mode = 0; mode < 4; mode++) {
      var p = root.resolve("channel-" + mode);
      Files.writeString(p, "abcdef");
      FileChannel channel =
        mode == 2
          ? FileChannel.open(p, StandardOpenOption.READ)
          : mode == 3
          ? FileChannel.open(p, StandardOpenOption.WRITE)
          : FileChannel.open(p, StandardOpenOption.READ, StandardOpenOption.WRITE);
      channel.position(2);
      boolean readOnly = mode == 1;
      FileStream accepted;
      try {
        accepted = new FileStream(channel, readOnly);
      } catch (java.nio.channels.NonReadableChannelException rejected) {
        accepted = null;
      }
      try (var s = accepted) {
        if (s == null) put("channel/" + mode + "/rejected", "1");
        else put(
          "channel/" + mode + "/open",
          bit(s.isOpen()) + ":" + bit(s.readOnly()) + ":" + text(s.name()));
        if (s != null) {
          long before = s.tell();
          var block = s.readBlock(2);
          put("channel/" + mode + "/read", before + ":" + hex(block));
          s.insert(new ByteVector("X"), 1, 2);
          try {
            s.truncate(3);
          } catch (NonWritableChannelException failure) {
            // Native ftruncate logs this failure; Java exposes the read-only channel error.
            if (mode != 2) {
              throw failure;
            }
          }
          put("channel/" + mode + "/end", s.tell() + ":" + s.length());
        }
      }
      put(
        "channel/" + mode + "/closed",
        bit(channel.isOpen()) + ":" + hex(new ByteVector(Files.readAllBytes(p))));
      if (channel.isOpen()) channel.close();
    }
    var p = root.resolve("eof");
    Files.writeString(p, "a");
    try (var s = new FileStream(p)) {
      put("eof/first", hex(s.readBlock(2)));
      Files.writeString(p, "ab");
      put("eof/sticky", hex(s.readBlock(1)));
      s.clear();
      put("eof/clear", hex(s.readBlock(1)));
      put("eof/empty", hex(s.readBlock(1)));
      Files.writeString(p, "abc");
      put("eof/length", "" + s.length());
      put("eof/after-length", hex(s.readBlock(1)));
    }
  }

  static void readonlyCases(Path root) throws Exception {
    var p = root.resolve("readonly");
    Files.writeString(p, "abcd");
    try (var ro = new FileStream(p, true)) {
      ro.seek(2);
      ro.writeBlock(new ByteVector("x"));
      ro.insert(new ByteVector("x"), 1, 1);
      try {
        ro.removeBlock(1, 1);
        throw new AssertionError("Read-only removal must report the failed truncation");
      } catch (NonWritableChannelException failure) {
      }
      put("readonly/state", ro.tell() + ":" + ro.length());
    }
    put("readonly/bytes", hex(new ByteVector(Files.readAllBytes(p))));
  }

  public static void main(String[] args) throws Exception {
    var root = Path.of(args[0]);
    var corpus = Path.of(args[1]);
    lazy(corpus);
    values();
    commonCases();
    apeCases();
    streams(root);
    readonlyCases(root);
  }
}
