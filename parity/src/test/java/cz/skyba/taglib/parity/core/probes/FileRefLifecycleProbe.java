package cz.skyba.taglib.parity.core.probes;

import cz.skyba.taglib.parity.support.FileRefAdapter;

import cz.skyba.taglib.*;
import cz.skyba.taglib.toolkit.ByteVectorStream;
import cz.skyba.taglib.toolkit.IOStream;
import cz.skyba.taglib.toolkit.Variant;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class FileRefLifecycleProbe {
  static StringBuilder events = new StringBuilder();
  static int destroyed;

  static class TestFile extends FileBase {
    final cz.skyba.taglib.ape.Tag tag =
      new cz.skyba.taglib.ape.Tag() {
        @Override
        public PropertyMap properties() {
          return PropertyMap.builder().put("TITLE", title()).put("COMMENT", comment()).build();
        }
      };

    TestFile(IOStream stream, boolean valid) {
      super(stream);
      setValid(valid);
    }

    @Override
    public TagBase tag() {
      return tag;
    }

    @Override
    public AudioProperties audioProperties() {
      return null;
    }

    @Override
    public void save() {
      requireWritable();
    }

    @Override
    public void close() {
      destroyed++;
      setValid(false);
    }
  }

  static class BasicTag extends TagBase {
    @Override
    public String title() {
      return "";
    }

    @Override
    public String artist() {
      return "";
    }

    @Override
    public String album() {
      return "";
    }

    @Override
    public String comment() {
      return "";
    }

    @Override
    public String genre() {
      return "";
    }

    @Override
    public long year() {
      return 0;
    }

    @Override
    public long track() {
      return 0;
    }

    @Override
    public void setTitle(String v) {
    }

    @Override
    public void setArtist(String v) {
    }

    @Override
    public void setAlbum(String v) {
    }

    @Override
    public void setComment(String v) {
    }

    @Override
    public void setGenre(String v) {
    }

    @Override
    public void setYear(long v) {
    }

    @Override
    public void setTrack(long v) {
    }
  }

  static class ComplexTag extends cz.skyba.taglib.ape.Tag {
    final boolean accepts;
    int calls, removals;

    ComplexTag(boolean accepts) {
      this.accepts = accepts;
    }

    @Override
    public List<String> complexPropertyKeys() {
      return accepts ? List.of("CUSTOM") : List.of();
    }

    @Override
    public List<Map<String, Variant>> complexProperties(String key) {
      return accepts ? List.of(Map.of("data", new Variant("x"))) : List.of();
    }

    @Override
    public boolean setComplexProperties(String key, List<Map<String, Variant>> values) {
      calls++;
      return accepts;
    }

    @Override
    public void removeUnsupportedProperties(List<String> values) {
      removals++;
    }
  }

  static class Resolver extends FileRefAdapter.StreamTypeResolver {
    final char id;
    final IOStream stream;
    final boolean path, direct, valid;

    Resolver(char id, IOStream stream, boolean path, boolean direct, boolean valid) {
      this.id = id;
      this.stream = stream;
      this.path = path;
      this.direct = direct;
      this.valid = valid;
    }

    int style(ReadStyle style) {
      return switch (style) {
        case FAST -> 0;
        case AVERAGE -> 1;
        case ACCURATE -> 2;
      };
    }

    @Override
    public FileBase createFile(Path name, ReadOptions options) {
      events.append(id).append('P').append(options.readAudioProperties() ? 1 : 0).append(style(options.readStyle())).append(';');
      return path ? new TestFile(stream, valid) : null;
    }

    @Override
    public FileBase createFileFromStream(IOStream value, ReadOptions options) {
      events.append(id).append('S').append(options.readAudioProperties() ? 1 : 0).append(style(options.readStyle())).append(';');
      return direct ? new TestFile(value, valid) : null;
    }
  }

  static class NamedStream extends ByteVectorStream {
    NamedStream() {
      super(new byte[0]);
    }

    @Override
    public String name() {
      return "resolver.unknown";
    }
  }

  static int bit(boolean value) {
    return value ? 1 : 0;
  }

  static void flags(String name, FileRefAdapter ref) {
    System.out.println(
      name
        + "|"
        + bit(ref.isNull())
        + "|"
        + bit(ref.file() != null)
        + "|"
        + bit(ref.tag() != null)
        + "|"
        + bit(ref.audioProperties() != null)
        + "|"
        + ref.properties().size()
        + "|"
        + ref.complexPropertyKeys().size());
  }

  public static void main(String[] args) {
    System.out.println("extensions|" + String.join(",", FileRefAdapter.defaultFileExtensions()));
    var stream = new NamedStream();
    var first = new Resolver('A', stream, true, false, true);
    var second = new Resolver('B', stream, false, false, true);
    FileRefAdapter.addFileTypeResolver(first);
    FileRefAdapter.addFileTypeResolver(second);
    try (var ref = new FileRefAdapter(stream, false, ReadStyle.ACCURATE)) {
      flags("resolved-stream", ref);
      System.out.println("stream-order|" + events);
      events.setLength(0);
      ref.tag().setTitle("shared");
      FileRefAdapter.clearFileTypeResolvers();
      System.out.println("registry-clear|" + bit(ref.save()) + "|" + destroyed);
    }
    System.out.println("last-owner|" + destroyed + "|" + bit(stream.isOpen()));
    FileRefAdapter.addFileTypeResolver(first);
    FileRefAdapter.addFileTypeResolver(second);
    try (var ref = new FileRefAdapter(Path.of("resolver.unknown"), false, ReadStyle.FAST)) {
      flags("resolved-path", ref);
    }
    System.out.println("path-order|" + events);
    events.setLength(0);
    var invalid = new Resolver('I', stream, true, true, false);
    FileRefAdapter.addFileTypeResolver(invalid);
    try (var ref = new FileRefAdapter(stream)) {
      System.out.println("invalid-resolver|" + bit(ref.isNull()));
      System.out.println("invalid-save|" + bit(ref.save()));
    }
    System.out.println("invalid-order|" + events);
    FileRefAdapter.clearFileTypeResolvers();
    {
      var firstComplex = new ComplexTag(false);
      var secondComplex = new ComplexTag(true);
      var thirdComplex = new ComplexTag(false);
      var tags = new TagUnion(firstComplex, secondComplex, thirdComplex);
      System.out.println(
        "union-complex|"
          + String.join(",", tags.complexPropertyKeys())
          + "|"
          + tags.complexProperties("CUSTOM").size()
          + "|"
          + bit(tags.setComplexProperties("CUSTOM", List.of()))
          + "|"
          + firstComplex.calls
          + ","
          + secondComplex.calls
          + ","
          + thirdComplex.calls);
      tags.removeUnsupportedProperties(List.of("opaque"));
      System.out.println(
        "union-removal|"
          + firstComplex.removals
          + ","
          + secondComplex.removals
          + ","
          + thirdComplex.removals);
      var base = new BasicTag();
      System.out.println(
        "base-complex-default|"
          + base.complexPropertyKeys().size()
          + "|"
          + base.complexProperties("UNKNOWN").size()
          + "|"
          + bit(base.setComplexProperties("UNKNOWN", List.of())));
      base.removeUnsupportedProperties(List.of("opaque"));
      var defaults = new TagUnion();
      System.out.println(
        "union-default|"
          + bit(defaults.isEmpty())
          + "|"
          + defaults.properties().size()
          + "|"
          + defaults.complexPropertyKeys().size()
          + "|"
          + defaults.complexProperties("UNKNOWN").size()
          + "|"
          + bit(defaults.setComplexProperties("UNKNOWN", List.of())));
    }
    if (args.length > 0) {
      try (var ref = new FileRefAdapter(Path.of(args[0]), false)) {
        flags("path-tags-only", ref);
      }
      try (var bad = new FileRefAdapter(Path.of("this-path-does-not-exist.flac"))) {
        flags("missing-path", bad);
      }
    }
  }
}
