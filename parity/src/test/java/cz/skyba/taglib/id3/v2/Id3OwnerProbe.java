package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.AudioProperties;
import cz.skyba.taglib.FileBase;
import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.TagBase;
import cz.skyba.taglib.internal.TextBytes;
import cz.skyba.taglib.parity.TextCharsets;
import cz.skyba.taglib.toolkit.*;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_16BE;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Independent live Tag/factory operations matching the native transcript schema.
 */
public class Id3OwnerProbe {
  static ByteVector bytes(String s) {
    return s.equals("-") ? new ByteVector() : new ByteVector(HexFormat.of().parseHex(s));
  }

  static String hex(ByteVector d) {
    return d.toHex().toString();
  }

  static String string(String s) {
    return TextBytes.decode(bytes(s).data(), UTF_8);
  }

  static String text(String s) {
    return hex(ByteVector.fromText(s, UTF_16BE));
  }

  static int b(boolean v) {
    return v ? 1 : 0;
  }

  static String fields(List<String> list) {
    var out = new StringBuilder(list.size() + ":");
    list.forEach(s -> out.append(text(s)).append('|'));
    return out.toString();
  }

  static String props(PropertyMap p) {
    var out = new StringBuilder();
    p.asMap().forEach((k, v) -> out.append(text(k)).append('=').append(fields(v)).append(';'));
    out.append('|');
    p.unsupportedData().forEach(s -> out.append(text(s)).append(','));
    return out.toString();
  }

  static String map(Map<ByteVector, List<Frame>> m) {
    var out = new StringBuilder();
    // Empty native buckets are lookup artifacts, not frames or serialized metadata.
    m.forEach((k, v) -> {
      if (!v.isEmpty()) out.append(hex(k)).append(':').append(v.size()).append(',');
    });
    return out.toString();
  }

  static class Factory extends FrameFactory {
    int raw, typed, update, aggregate;

    public Frame createFrame(ByteVector d, Header h) {
      raw++;
      return super.createFrame(d, h);
    }

    protected Frame createFrame(ByteVector d, Frame.Header h, Header t) {
      typed++;
      return super.createFrame(d, h, t);
    }

    protected boolean updateFrame(Frame.Header h) {
      update++;
      return super.updateFrame(h);
    }

    public void rebuildAggregateFrames(Tag t) {
      aggregate++;
      super.rebuildAggregateFrames(t);
    }
  }

  static class Utf8 extends Latin1StringHandler {
    public String parse(ByteVector d) {
      return TextBytes.decodeTerminated(d.data(), UTF_8);
    }
  }

  static class BareFile extends FileBase {
    BareFile(ByteVectorStream s) {
      super(s);
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

  static class LiveTag extends Tag {
    LiveTag(FileBase f, long o, Factory c) {
      super(f, o, c);
    }

    void parseBody(ByteVector d) {
      parse(d);
    }
  }

  static String kind(Frame f) {
    if (f == null) return "null";
    return switch (f) {
      case UserTextIdentificationFrame _ -> "TXXX";
      case TextIdentificationFrame _ -> "TEXT";
      case AttachedPictureFrame _ -> "APIC";
      case CommentsFrame _ -> "COMM";
      case RelativeVolumeFrame _ -> "RVA2";
      case UniqueFileIdentifierFrame _ -> "UFID";
      case GeneralEncapsulatedObjectFrame _ -> "GEOB";
      case UserUrlLinkFrame _ -> "WXXX";
      case UrlLinkFrame _ -> "URL";
      case UnsynchronizedLyricsFrame _ -> "USLT";
      case SynchronizedLyricsFrame _ -> "SYLT";
      case EventTimingCodesFrame _ -> "ETCO";
      case PopularimeterFrame _ -> "POPM";
      case PrivateFrame _ -> "PRIV";
      case OwnershipFrame _ -> "OWNE";
      case ChapterFrame _ -> "CHAP";
      case TableOfContentsFrame _ -> "CTOC";
      case PodcastFrame _ -> "PCST";
      default -> "UNKNOWN";
    };
  }

  static String frame(Frame f) {
    if (f == null) return "null";
    var h = f.header();
    var out =
      new StringBuilder(
        kind(f)
          + ":"
          + hex(f.frameID())
          + ":"
          + f.size()
          + ":"
          + h.version()
          + ":"
          + b(h.tagAlterPreservation())
          + b(h.fileAlterPreservation())
          + b(h.readOnly())
          + b(h.groupingIdentity())
          + b(h.compression())
          + b(h.encryption())
          + b(h.unsynchronisation())
          + b(h.dataLengthIndicator())
          + ":"
          + text(f.toString())
          + ":"
          + fields(f.toStringList()));
    Charset e =
      switch (f) {
        case TextIdentificationFrame t -> t.textEncoding();
        case CommentsFrame t -> t.textEncoding();
        case AttachedPictureFrame t -> t.textEncoding();
        case GeneralEncapsulatedObjectFrame t -> t.textEncoding();
        case UserUrlLinkFrame t -> t.textEncoding();
        case UnsynchronizedLyricsFrame t -> t.textEncoding();
        case SynchronizedLyricsFrame t -> t.textEncoding();
        case OwnershipFrame t -> t.textEncoding();
        default -> null;
      };
    if (e != null) out.append(":e=").append(TextCharsets.id(e));
    if (f instanceof UnknownFrame u) out.append(":u=").append(hex(u.data()));
    if (f instanceof ChapterFrame c) {
      out.append(":c=")
        .append(hex(c.elementID()))
        .append(',')
        .append(c.startTime())
        .append(',')
        .append(c.endTime())
        .append(',')
        .append(c.startOffset())
        .append(',')
        .append(c.endOffset())
        .append(',')
        .append(map(c.embeddedFrameListMap()))
        .append('[');
      c.embeddedFrameList().forEach(child -> out.append(frame(child)).append(';'));
      out.append(']');
    }
    if (f instanceof TableOfContentsFrame c) {
      out.append(":c=")
        .append(hex(c.elementID()))
        .append(',')
        .append(b(c.isTopLevel()))
        .append(b(c.isOrdered()))
        .append(',')
        .append(c.entryCount())
        .append(',');
      c.childElements().forEach(id -> out.append(hex(id)).append('/'));
      out.append(',').append(map(c.embeddedFrameListMap())).append('[');
      c.embeddedFrameList().forEach(child -> out.append(frame(child)).append(';'));
      out.append(']');
    }
    return out.toString();
  }

  static void state(String id, Tag t, Factory c, BareFile file) {
    String title = t.title(),
      artist = t.artist(),
      album = t.album(),
      comment = t.comment(),
      genre = t.genre();
    long year = t.year(), track = t.track();
    var h = t.header();
    var out =
      new StringBuilder(
        id
          + "\t"
          + h.majorVersion()
          + ":"
          + h.tagSize()
          + ":"
          + h.completeTagSize()
          + ":"
          + b(h.unsynchronisation())
          + b(h.extendedHeader())
          + b(h.experimentalIndicator())
          + b(h.footerPresent())
          + ":"
          + (t.extendedHeader() == null ? "-" : t.extendedHeader().size())
          + "\t"
          + file.tell()
          + "\t"
          + c.raw
          + ":"
          + c.typed
          + ":"
          + c.update
          + ":"
          + c.aggregate
          + "\t"
          + text(title)
          + ":"
          + text(artist)
          + ":"
          + text(album)
          + ":"
          + text(comment)
          + ":"
          + text(genre)
          + ":"
          + year
          + ":"
          + track
          + ":"
          + b(t.isEmpty())
          + "\t"
          + map(t.frameListMap())
          + "\t");
    t.frameList().forEach(f -> out.append(frame(f)).append(';'));
    out.append('\t')
      .append(props(t.properties()))
      .append('\t')
      .append(fields(t.complexPropertyKeys().stream()
        .filter(key -> !t.frameList(new ByteVector(key.equals("PICTURE") ? "APIC" : "GEOB")).isEmpty())
        .toList()));
    System.out.println(out);
  }

  static void owner(String[] p, Factory c) {
    var source = bytes(p[6]);
    var stream = new ByteVectorStream(source);
    var file = new BareFile(stream);
    var t = new LiveTag(file, 3, c);
    var id = p[0];
    state(id + "/ctor", t, c, file);
    System.out.println(id + "/render4\t" + hex(t.render(Tag.Version.V4)));
    state(id + "/after4", t, c, file);
    System.out.println(id + "/initial3\t" + hex(t.render(Tag.Version.V3)));
    state(id + "/initial-after3", t, c, file);
    System.out.println(id + "/back4\t" + hex(t.render(Tag.Version.V4)));
    state(id + "/back-after4", t, c, file);
    t.setTitle(string("6e6577007469746c65c3a9"));
    t.setArtist("artist");
    t.setAlbum("");
    t.setComment("comment");
    t.setGenre("14");
    t.setYear(0xffffffffL);
    t.setTrack(0x80000000L);
    state(id + "/common", t, c, file);
    var builder = PropertyMap.builder();
    builder.add("TITLE", List.of("new title", "second"));
    builder.add("COMMENT:X", List.of("comment X"));
    builder.add("ARRANGER", List.of("a", "b"));
    builder.add("PERFORMER:GUITAR", List.of("g"));
    builder.add("MUSICBRAINZ_TRACKID", List.of("track-id"));
    builder.add("URL", List.of("https://example.test"));
    builder.add("EMPTY", List.of());
    var properties = builder.build();
    System.out.println(id + "/set-props\t" + props(t.replaceProperties(properties).rejected()));
    state(id + "/properties", t, c, file);
    var pictures =
      List.of(
        Map.of(
          "data",
          new Variant(new ByteVector("payload")),
          "description",
          new Variant("desc"),
          "mimeType",
          new Variant("image/test"),
          "pictureType",
          new Variant("Front Cover")),
        Map.of("data", new Variant(1), "description", new Variant(false)));
    boolean pResult = t.setComplexProperties("picture", pictures),
      gResult = t.setComplexProperties("generalobject", pictures),
      nResult = t.setComplexProperties("none", pictures);
    var out = new StringBuilder(id + "/complex\t" + b(pResult) + b(gResult) + b(nResult) + "\t");
    for (String key : List.of("PICTURE", "GENERALOBJECT", "none")) {
      var v = t.complexProperties(key);
      out.append(v.size()).append(':');
      for (var m : v)
        for (var entry : m.entrySet()) {
          var value = entry.getValue();
          out.append(text(entry.getKey()))
            .append('=')
            .append(value.type().ordinal())
            .append(':')
            .append(
              value.type() == Variant.Type.BYTE_VECTOR
                ? hex(value.toByteVector())
                : text(value.toString()))
            .append(';');
        }
    }
    System.out.println(out);
    state(id + "/complex-state", t, c, file);
    t.removeUnsupportedProperties(
      List.of("UNKNOWN/ZZZZ", "APIC", "COMM!X", "UFID/http://musicbrainz.org", "CHAP/ch"));
    state(id + "/removed", t, c, file);
    System.out.println(id + "/render3\t" + hex(t.render(Tag.Version.V3)));
    state(id + "/after3", t, c, file);
    t.parseBody(source.mid(13));
    state(id + "/append-parse", t, c, file);
    System.out.println(id + "/repeat3\t" + hex(t.render(Tag.Version.V3)));
  }

  static int constructingHooks;

  static class ChapterChild extends ChapterFrame {
    ChapterChild(cz.skyba.taglib.id3.v2.Header h, ByteVector d) {
      super(h, d);
    }

    protected void parseFields(ByteVector d) {
      constructingHooks++;
      super.parseFields(d);
    }
  }

  static class TocChild extends TableOfContentsFrame {
    TocChild(cz.skyba.taglib.id3.v2.Header h, ByteVector d) {
      super(h, d);
    }

    protected void parseFields(ByteVector d) {
      constructingHooks++;
      super.parseFields(d);
    }
  }

  static class Throw extends Latin1StringHandler {
    int calls;

    public String parse(ByteVector d) {
      if (++calls == 5) throw new IllegalArgumentException("callback");
      return "c";
    }
  }

  static class BucketHook extends Frame {
    final Tag tag;
    final String name;
    final boolean add;
    final List<String> visited;

    BucketHook(Tag tag, String id, boolean add, List<String> visited) {
      super(new ByteVector(id));
      this.tag = tag;
      name = id;
      this.add = add;
      this.visited = visited;
    }

    public String toString() {
      return "";
    }

    protected void parseFields(ByteVector d) {
    }

    protected ByteVector renderFields() {
      return new ByteVector("x");
    }

    protected PropertyMap asProperties() {
      visited.add(name);
      if (add) {
        tag.frameList(new ByteVector("A000"));
        tag.frameList(new ByteVector("ZZZZ"));
        tag.addFrame(new BucketHook(tag, "X001", false, visited));
        tag.addFrame(new BucketHook(tag, "Z999", false, visited));
      }
      return PropertyMap.EMPTY;
    }
  }

  static void helpers() {
    var h = new Header();
    var leaf = bytes("544954320000000200000061");
    var chap = bytes("4348415000000020000063000000000100000002ffffffffffffffff").append(leaf);
    var toc = bytes("43544f43000000140000740003016300").append(leaf);
    constructingHooks = 0;
    var c = new ChapterChild(h, chap);
    System.out.println("helper/chapter-constructor\t" + constructingHooks + "\t" + frame(c));
    c.setData(chap);
    System.out.println("helper/chapter-reparse\t" + constructingHooks + "\t" + frame(c));
    constructingHooks = 0;
    var t = new TocChild(h, toc);
    System.out.println("helper/toc-constructor\t" + constructingHooks + "\t" + frame(t));
    t.setData(toc);
    System.out.println("helper/toc-reparse\t" + constructingHooks + "\t" + frame(t));
    var tag = new Tag();
    var list = tag.frameList();
    var mapped = tag.frameListMap();
    var f = new TextIdentificationFrame(new ByteVector("TIT2"), ISO_8859_1);
    f.setText("a");
    tag.addFrame(f);
    var bucket = tag.frameList(new ByteVector("TIT2"));
    System.out.println(
      "helper/live-add\t"
        + list.size()
        + ":"
        + bucket.size()
        + ":"
        + mapped.size()
        + ":"
        + b(list.getFirst() == f)
        + ":"
        + b(bucket == tag.frameList(new ByteVector("TIT2"))));
    f.header().setFrameID(new ByteVector("TPE1"));
    System.out.println(
      "helper/live-id\t" + text(tag.title()) + ":" + text(tag.artist()) + ":" + map(mapped));
    f.header().setFrameID(new ByteVector("TIT2"));
    tag.removeFrame(f);
    System.out.println(
      "helper/live-remove\t" + list.size() + ":" + bucket.size() + ":" + text(f.toString()));
    var embedded = c.embeddedFrameList();
    var old = embedded.getFirst();
    c.removeEmbeddedFrame(old);
    System.out.println(
      "helper/nested-remove\t"
        + embedded.size()
        + ":"
        + map(c.embeddedFrameListMap())
        + ":"
        + text(old.toString()));
    t.addChildElement(bytes("7800"));
    t.removeChildElement(new ByteVector("x"));
    t.setIsTopLevel(true);
    t.setIsOrdered(false);
    System.out.println("helper/toc-setters\t" + frame(t) + "\t" + hex(t.render()));
    var nested = new ByteVector(chap);
    for (int depth = 0; depth < 65; depth++) {
      var body = bytes("63000000000100000002ffffffffffffffff").append(nested);
      nested =
        new ByteVector("CHAP")
          .append(SynchData.fromUnsignedInt(body.size()))
          .append(new ByteVector(2))
          .append(body);
    }
    var fail = new Throw();
    Tag.setLatin1StringHandler(fail);
    boolean threw = false;
    try {
      FrameFactory.instance().createFrame(nested, h);
    } catch (IllegalArgumentException expected) {
      threw = true;
    }
    Tag.setLatin1StringHandler(null);
    var parsed = FrameFactory.instance().createFrame(nested, h);
    System.out.println("helper/depth-throw\t" + b(threw) + ":" + fail.calls + "\t" + frame(parsed));
    var hooks = new Tag();
    var visited = new ArrayList<String>();
    hooks.addFrame(new BucketHook(hooks, "X001", true, visited));
    hooks.addFrame(new BucketHook(hooks, "Y001", false, visited));
    hooks.replaceProperties(PropertyMap.EMPTY);
    System.out.println(
      "helper/property-hook-insertion\t"
        + String.join(",", visited)
        + ",\t"
        + hooks.frameList().size()
        + ":"
        + map(hooks.frameListMap()));
    var iterations = new Tag();
    var a = new TextIdentificationFrame(new ByteVector("TIT2"), ISO_8859_1);
    a.setText("a");
    var bb = new TextIdentificationFrame(new ByteVector("TIT2"), ISO_8859_1);
    bb.setText("b");
    iterations.addFrame(a);
    iterations.addFrame(bb);
    var iterator = iterations.frameList().iterator();
    iterator.next();
    var second = iterator.next();
    iterations.removeFrame(a);
    var added = new TextIdentificationFrame(new ByteVector("TIT2"), ISO_8859_1);
    added.setText("c");
    iterations.addFrame(added);
    System.out.println(
      "helper/stable-list-iterator\t"
        + text(second.toString())
        + ":"
        + text(iterator.next().toString())
        + ":"
        + b(!iterator.hasNext()));
  }

  public static void main(String[] args) throws Exception {
    var handler = new Utf8();
    for (String line : Files.readAllLines(Path.of(args[0]))) {
      String[] p = line.split("\t", -1);
      if (p.length != 7) throw new IllegalArgumentException(line);
      var c = new Factory();
      if (!p[3].equals("-1"))
        c.setDefaultTextEncoding(TextCharsets.ALL[Integer.parseInt(p[3])]);
      Tag.setLatin1StringHandler(p[4].equals("1") ? handler : null);
      if (p[1].equals("T")) owner(p, c);
      else if (p[1].equals("P")) {
        var values = new ArrayList<String>();
        for (ByteVector v : bytes(p[6]).split(new ByteVector("|")))
          values.add(TextBytes.decode(v.data(), UTF_8));
        Frame f = c.createFrameForProperty(string(p[5]), values);
        System.out.println(p[0] + "/property\t" + frame(f));
        System.out.println(p[0] + "/render\t" + hex(f.render()));
        var t = new Tag();
        t.addFrame(f);
        System.out.println(p[0] + "/props\t" + props(t.properties()));
        t.removeFrame(f);
      } else {
        var hd =
          new ByteVector("ID3")
            .append((byte) Integer.parseInt(p[2]))
            .append((byte) 0)
            .append((byte) Integer.parseInt(p[5]))
            .append(new ByteVector(4));
        var h = new Header(hd);
        h.setMajorVersion(Long.parseUnsignedLong(p[2]));
        var d = bytes(p[6]);
        var prepared = new ByteVector(d);
        var r = c.prepareFrameHeader(prepared, h);
        var ph = r.header();
        System.out.println(
          p[0]
            + "/prepare\t"
            + b(r.supported())
            + "\t"
            + (ph == null
            ? "null"
            : hex(ph.frameID())
            + ":"
            + ph.frameSize()
            + ":"
            + ph.version()
            + ":"
            + b(ph.tagAlterPreservation()))
            + "\t"
            + hex(prepared)
            + "\t"
            + c.update);
        Frame f = c.createFrame(d, h);
        System.out.println(
          p[0] + "/ctor\t" + frame(f) + "\t" + c.raw + ":" + c.typed + ":" + c.update);
        if (f != null) {
          var old = f.header();
          System.out.println(p[0] + "/render\t" + hex(f.render()) + "\t" + b(old == f.header()));
          System.out.println(p[0] + "/after\t" + frame(f));
          if (f instanceof ChapterFrame || f instanceof TableOfContentsFrame) {
            f.setData(f.render());
            System.out.println(p[0] + "/append-reparse\t" + frame(f));
            System.out.println(p[0] + "/append-render\t" + hex(f.render()));
          }
        }
      }
      Tag.setLatin1StringHandler(null);
    }
    helpers();
  }
}
