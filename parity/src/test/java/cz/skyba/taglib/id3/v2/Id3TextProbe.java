package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.TagString;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Package-local constructor and decoder protocol; does not stand in for a live Tag or factory.
 */
public class Id3TextProbe {
  static ByteVector bytes(String s) {
    return s.equals("-") ? new ByteVector() : new ByteVector(HexFormat.of().parseHex(s));
  }

  static String hex(ByteVector v) {
    return v.toHex().toString();
  }

  static String string(String s) {
    return TagString.from8Bit(bytes(s).data(), TagString.Type.UTF8).toString();
  }

  static String text(String s) {
    return hex(new TagString(s).data(TagString.Type.UTF16BE));
  }

  static List<String> list(String s) {
    return s.equals("!")
      ? List.of()
      : Arrays.stream(s.split(",", -1)).map(Id3TextProbe::string).toList();
  }

  static String fields(List<String> values) {
    var out = new StringBuilder(values.size() + ":");
    values.forEach(s -> out.append(text(s)).append('|'));
    return out.toString();
  }

  static String properties(PropertyMap p) {
    var out = new StringBuilder();
    p.asMap().forEach((k, v) -> out.append(text(k)).append('=').append(fields(v)).append(';'));
    out.append('|');
    p.unsupportedData().forEach(s -> out.append(text(s)).append(','));
    return out.toString();
  }

  static int b(boolean value) {
    return value ? 1 : 0;
  }

  static class Utf8Handler extends Latin1StringHandler {
    @Override
    public String parse(ByteVector data) {
      return new TagString(data, TagString.Type.UTF8).toString();
    }
  }

  static ByteVector identifier(String kind) {
    return new ByteVector(
      switch (kind) {
        case "T" -> "TCOM";
        case "U" -> "TXXX";
        case "C" -> "COMM";
        case "L" -> "USLT";
        case "W" -> "WOAF";
        case "X" -> "WXXX";
        case "I" -> "UFID";
        case "P" -> "PRIV";
        default -> "PCST";
      });
  }

  static Frame create(
    String kind, String mode, ByteVector data, long version, TagString.Type encoding) {
    if (mode.equals("F")) {
      var h = new Frame.Header(data, version);
      // These fixtures isolate constructors with provided headers, including the factory's
      // documented v2 ID upgrade. Factory dispatch/acceptance is a separate pending API.
      if (version < 3) h.setFrameID(identifier(kind));
      return switch (kind) {
        case "T" -> new TextIdentificationFrame(data, h);
        case "U" -> new UserTextIdentificationFrame(data, h);
        case "C" -> new CommentsFrame(data, h);
        case "L" -> new UnsynchronizedLyricsFrame(data, h);
        case "W" -> new UrlLinkFrame(data, h);
        case "X" -> new UserUrlLinkFrame(data, h);
        case "I" -> new UniqueFileIdentifierFrame(data, h);
        case "P" -> new PrivateFrame(data, h);
        default -> new PodcastFrame(data, h);
      };
    }
    boolean parsed = mode.equals("P");
    return switch (kind) {
      case "T" -> parsed
        ? new TextIdentificationFrame(data)
        : new TextIdentificationFrame(identifier(kind), encoding);
      case "U" -> parsed
        ? new UserTextIdentificationFrame(data)
        : new UserTextIdentificationFrame(encoding);
      case "C" -> parsed ? new CommentsFrame(data) : new CommentsFrame(encoding);
      case "L" -> parsed ? new UnsynchronizedLyricsFrame(data) : new UnsynchronizedLyricsFrame(encoding);
      case "W" -> new UrlLinkFrame(parsed ? data : identifier(kind));
      case "X" -> parsed ? new UserUrlLinkFrame(data) : new UserUrlLinkFrame(encoding);
      case "I" -> parsed
        ? new UniqueFileIdentifierFrame(data)
        : new UniqueFileIdentifierFrame("", new ByteVector());
      case "P" -> parsed ? new PrivateFrame(data) : new PrivateFrame();
      default -> {
        var frame = new PodcastFrame();
        if (parsed) frame.setData(data);
        yield frame;
      }
    };
  }

  static void state(String id, Frame f) {
    var h = f.header();
    var detail = new StringBuilder();
    if (f instanceof TextIdentificationFrame v)
      detail.append(v.textEncoding().ordinal()).append(':').append(fields(v.fieldList()));
    if (f instanceof CommentsFrame v)
      detail
        .append(v.textEncoding().ordinal())
        .append(':')
        .append(hex(v.language()))
        .append(':')
        .append(text(v.description()))
        .append(':')
        .append(text(v.text()));
    if (f instanceof UnsynchronizedLyricsFrame v)
      detail
        .append(v.textEncoding().ordinal())
        .append(':')
        .append(hex(v.language()))
        .append(':')
        .append(text(v.description()))
        .append(':')
        .append(text(v.text()));
    if (f instanceof UserUrlLinkFrame v)
      detail
        .append(v.textEncoding().ordinal())
        .append(':')
        .append(text(v.description()))
        .append(':');
    if (f instanceof UrlLinkFrame v) detail.append(text(v.url()));
    if (f instanceof UniqueFileIdentifierFrame v)
      detail.append(text(v.owner())).append(':').append(hex(v.identifier()));
    if (f instanceof PrivateFrame v)
      detail.append(text(v.owner())).append(':').append(hex(v.data()));
    String props;
    if (f instanceof UserTextIdentificationFrame v && v.fieldList().isEmpty()) {
      try {
        v.asProperties();
        throw new AssertionError("Missing checked native undefined boundary");
      } catch (IllegalStateException expected) {
        props = "UNDEFINED_EMPTY_TXXX_PROPERTY";
      }
    } else props = properties(f.asProperties());
    System.out.println(
      id
        + "\t"
        + hex(f.frameID())
        + "\t"
        + f.size()
        + "\t"
        + f.headerSize()
        + "\t"
        + h.version()
        + "\t"
        + text(f.toString())
        + "\t"
        + fields(f.toStringList())
        + "\t"
        + detail
        + "\t"
        + props
        + "\t"
        + b(h.tagAlterPreservation())
        + b(h.fileAlterPreservation())
        + b(h.readOnly())
        + b(h.groupingIdentity())
        + b(h.compression())
        + b(h.encryption())
        + b(h.unsynchronisation())
        + b(h.dataLengthIndicator()));
  }

  static void mutate(
    Frame f,
    TagString.Type encoding,
    String desc,
    String value,
    ByteVector payload,
    List<String> values) {
    f.setText(value);
    if (f instanceof UserTextIdentificationFrame u) {
      u.setDescription(desc);
      u.setValues(values);
      u.setTextEncoding(encoding);
    } else if (f instanceof TextIdentificationFrame t) {
      t.setText(values);
      t.setTextEncoding(encoding);
    }
    if (f instanceof CommentsFrame c) {
      c.setDescription(desc);
      c.setLanguage(payload);
      c.setTextEncoding(encoding);
    }
    if (f instanceof UnsynchronizedLyricsFrame l) {
      l.setDescription(desc);
      l.setLanguage(payload);
      l.setTextEncoding(encoding);
    }
    if (f instanceof UserUrlLinkFrame x) {
      x.setDescription(desc);
      x.setTextEncoding(encoding);
    }
    if (f instanceof UniqueFileIdentifierFrame i) {
      i.setOwner(desc);
      i.setIdentifier(payload);
    }
    if (f instanceof PrivateFrame p) {
      p.setOwner(desc);
      p.setPrivateData(payload);
    }
  }

  static void helpers() {
    var p = PropertyMap.builder();
    p.add("ARRANGER", List.of("one", "two"));
    p.add("DJMIXER", List.of());
    p.add("DJ-MIX", List.of("ignored"));
    p.add("PERFORMER:guitar", List.of("three"));
    p.add("PERFORMER:", List.of("empty"));
    p.add("TITLE", List.of("title"));
    var a = TextIdentificationFrame.createTIPLFrame(p.build());
    var c = TextIdentificationFrame.createTMCLFrame(p.build());
    state("helper/TIPL", a);
    System.out.println("helper/TIPL-render\t" + hex(a.render()));
    state("helper/TMCL", c);
    System.out.println("helper/TMCL-render\t" + hex(c.render()));
    var people = new StringBuilder();
    TextIdentificationFrame.involvedPeopleMap()
      .forEach((k, v) -> people.append(text(k)).append('=').append(text(v)).append(','));
    System.out.println("helper/people\t" + people);
    var single = new cz.skyba.taglib.toolkit.MutablePropertyMap();
    var tipl = new cz.skyba.taglib.toolkit.MutablePropertyMap();
    var tmcl = new cz.skyba.taglib.toolkit.MutablePropertyMap();
    Frame.splitProperties(new cz.skyba.taglib.toolkit.MutablePropertyMap(p.build()), single, tipl, tmcl);
    System.out.println(
      "helper/split-after-creation\t"
        + properties(single.snapshot())
        + "\t"
        + properties(tipl.snapshot())
        + "\t"
        + properties(tmcl.snapshot()));
    var map = TextIdentificationFrame.involvedPeopleMap();
    boolean before = map.containsKey("CONST-INDEX");
    var indexed = map.getOrDefault("CONST-INDEX", "");
    System.out.println(
      "helper/const-map-index\t"
        + b(before)
        + "\t"
        + text(indexed)
        + "\t"
        + b(map.containsKey("CONST-INDEX")));
    for (String k :
      List.of("MUSICBRAINZ_ALBUMID", "musicbrainz album id", "custom::Name", "", "MUSICIP_PUID"))
      System.out.println(
        "helper/mapping\t"
          + text(k)
          + "\t"
          + text(UserTextIdentificationFrame.keyToTXXX(k))
          + "\t"
          + text(UserTextIdentificationFrame.txxxToKey(k)));
    var u = new UserTextIdentificationFrame("target", List.of("one"));
    var u2 = new UserTextIdentificationFrame("target", List.of("two"));
    var bucket = List.of(new UnknownFrame(new ByteVector("TXXX")), u, u2);
    u.header().setFrameID(new ByteVector("XXXX"));
    var comm = new CommentsFrame();
    comm.setDescription("target");
    var lyrics = new UnsynchronizedLyricsFrame();
    lyrics.setDescription("target");
    var url = new UserUrlLinkFrame();
    url.setDescription("target");
    var ufid = new UniqueFileIdentifierFrame("target", new ByteVector());
    System.out.println(
      "helper/find\t"
        + b(UserTextIdentificationFrame.find(bucket, "target") == u)
        + "\t"
        + b(UserTextIdentificationFrame.find(bucket, "TARGET") == null)
        + "\t"
        + b(CommentsFrame.findByDescription(List.of(comm), "target") == comm)
        + "\t"
        + b(UnsynchronizedLyricsFrame.findByDescription(List.of(lyrics), "target") == lyrics)
        + "\t"
        + b(UserUrlLinkFrame.find(List.of(url), "target") == url)
        + "\t"
        + b(UniqueFileIdentifierFrame.findByOwner(List.of(ufid), "target") == ufid));
  }

  static class VirtualUrl extends UrlLinkFrame {
    VirtualUrl() {
      super(new ByteVector("WOAF"));
    }

    @Override
    public String url() {
      return "virtual";
    }

    @Override
    public void setUrl(String value) {
      super.setUrl("set:" + value);
    }
  }

  static ByteVector wire(String id, ByteVector body) {
    return new ByteVector(id)
      .append(SynchData.fromUnsignedInt(body.size()))
      .append(new ByteVector(2))
      .append(body);
  }

  static class TextChild extends TextIdentificationFrame {
    int calls;

    TextChild(ByteVector data) {
      super(data);
    }

    @Override
    protected void parseFields(ByteVector data) {
      calls++;
      super.parseFields(data);
    }
  }

  static class UserUrlChild extends UserUrlLinkFrame {
    int calls;

    UserUrlChild(ByteVector data) {
      super(data);
    }

    @Override
    public void setUrl(String value) {
      calls++;
      super.setUrl("set:" + value);
    }
  }

  static class ThrowHandler extends Latin1StringHandler {
    int calls;

    @Override
    public String parse(ByteVector data) {
      if (++calls == 2) throw new IllegalArgumentException("second callback");
      return "decoded:" + new TagString(data);
    }
  }

  static void callbacks() {
    var data = wire("TCOM", bytes("006f6e650074776f"));
    var t = new TextChild(data);
    System.out.println("virtual/text-ctor\t" + t.calls);
    t.setData(data);
    System.out.println("virtual/text-reparse\t" + t.calls);
    state("virtual/text-state", t);
    var urlData = wire("WXXX", bytes("00646573630075726c"));
    var u = new UserUrlChild(urlData);
    System.out.println("virtual/user-url-ctor\t" + u.calls);
    state("virtual/user-url-ctor-state", u);
    u.setData(urlData);
    System.out.println("virtual/user-url-reparse\t" + u.calls);
    state("virtual/user-url-state", u);
    for (String kind : List.of("T", "U", "C", "L", "I")) {
      var f = create(kind, "B", new ByteVector(), 4, TagString.Type.LATIN1);
      mutate(
        f, TagString.Type.LATIN1, "old-desc", "old-text", new ByteVector("eng"), List.of("old"));
      var handler = new ThrowHandler();
      Id3Text.setHandler(handler);
      var body =
        bytes(
          kind.equals("I")
            ? "6f776e6572006964"
            : kind.equals("C") || kind.equals("L")
            ? "00656e67646573630074657874"
            : "006f6e650074776f");
      boolean threw = false;
      try {
        f.setData(wire(identifier(kind).toString(), body));
      } catch (IllegalArgumentException expected) {
        threw = true;
      } finally {
        Id3Text.setHandler(null);
      }
      System.out.println("callback/" + kind + "\t" + b(threw) + "\t" + handler.calls);
      state("callback/" + kind + "-state", f);
    }
    for (String description : List.of("", "desc", "a\0b"))
      for (List<String> values : List.<List<String>>of(List.of(), List.of("", "one"))) {
        var f = new UserTextIdentificationFrame(description, values);
        state("helper/user-value-ctor", f);
        System.out.println("helper/user-value-render\t" + hex(f.render()));
      }
  }

  public static void main(String[] args) throws Exception {
    var utf8 = new Utf8Handler();
    try (var input = Files.newBufferedReader(Path.of(args[0]))) {
      String line;
      while ((line = input.readLine()) != null) {
        var p = line.split("\t", -1);
        if (p.length != 12) throw new IllegalArgumentException(line);
        String id = p[0];
        long version = Long.parseUnsignedLong(p[3]);
        var encoding = TagString.Type.values()[Integer.parseInt(p[7])];
        Id3Text.setHandler(p[4].equals("1") ? utf8 : null);
        var value = create(p[1], p[2], bytes(p[5]), version, encoding);
        var live = value.header();
        state(id + "/ctor", value);
        System.out.println(
          id + "/render\t" + hex(value.render()) + "\t" + b(live == value.header()));
        state(id + "/after-render", value);
        value.setData(bytes(p[6]));
        state(id + "/reparse", value);
        mutate(value, encoding, string(p[8]), string(p[9]), bytes(p[10]), list(p[11]));
        value.header().setVersion(version);
        state(id + "/mutate", value);
        System.out.println(id + "/mutate-render\t" + hex(value.render()));
        state(id + "/mutate-after", value);
        if (value instanceof UserTextIdentificationFrame u) {
          u.setText(list(p[11]));
          state(id + "/base-list", u);
        }
        var copy = value.toStringList();
        copy.add("added");
        if (!copy.isEmpty()) copy.set(0, "detached");
        System.out.println(
          id + "/detached-list\t" + fields(copy) + "\t" + fields(value.toStringList()));
        var data = bytes(p[5]);
        var before = hex(data);
        System.out.println(
          id
            + "/handler\t"
            + text(new Latin1StringHandler().parse(data))
            + "\t"
            + text(utf8.parse(data))
            + "\t"
            + b(before.equals(hex(data))));
        Id3Text.setHandler(null);
      }
    } finally {
      Id3Text.setHandler(null);
    }
    helpers();
    var url = new VirtualUrl();
    url.setText("value");
    state("virtual/url", url);
    System.out.println("virtual/url-render\t" + hex(url.render()));
    callbacks();
  }
}
