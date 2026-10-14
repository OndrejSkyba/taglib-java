package cz.skyba.taglib.id3.v2;

import cz.skyba.taglib.PropertyMap;
import cz.skyba.taglib.toolkit.ByteVector;
import cz.skyba.taglib.toolkit.TagString;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

/**
 * OWNE/POPM/RVA2 operations; provided-header paths do not qualify a public factory.
 */
public class Id3NumericProbe {
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

  static int b(boolean v) {
    return v ? 1 : 0;
  }

  static String fields(List<String> v) {
    var out = new StringBuilder(v.size() + ":");
    v.forEach(s -> out.append(text(s)).append('|'));
    return out.toString();
  }

  static String props(PropertyMap p) {
    var out = new StringBuilder();
    p.asMap().forEach((k, v) -> out.append(text(k)).append('=').append(fields(v)).append(';'));
    out.append('|');
    p.unsupportedData().forEach(s -> out.append(text(s)).append(','));
    return out.toString();
  }

  static String floatBits(float f) {
    return Integer.toUnsignedString(Float.floatToRawIntBits(f));
  }

  static class Utf8 extends Latin1StringHandler {
    public String parse(ByteVector data) {
      return new TagString(data, TagString.Type.UTF8).toString();
    }
  }

  static RelativeVolumeFrame.ChannelType channel(String s) {
    return RelativeVolumeFrame.ChannelType.values()[Integer.parseInt(s)];
  }

  static Frame create(String[] p) {
    var data = bytes(p[5]);
    String kind = p[1], mode = p[2];
    if (mode.equals("F")) {
      var h = new Frame.Header(data, Long.parseUnsignedLong(p[3]));
      if (h.version() < 3) h.setFrameID(new ByteVector(kind.equals("P") ? "POPM" : "RVA2"));
      return switch (kind) {
        case "O" -> new OwnershipFrame(data, h);
        case "P" -> new PopularimeterFrame(data, h);
        default -> new RelativeVolumeFrame(data, h);
      };
    }
    return switch (kind) {
      case "O" -> mode.equals("P")
        ? new OwnershipFrame(data)
        : new OwnershipFrame(TagString.Type.values()[Integer.parseInt(p[7])]);
      case "P" -> mode.equals("P") ? new PopularimeterFrame(data) : new PopularimeterFrame();
      default -> mode.equals("P") ? new RelativeVolumeFrame(data) : new RelativeVolumeFrame();
    };
  }

  static void state(String id, Frame f) {
    var h = f.header();
    String detail;
    if (f instanceof OwnershipFrame o)
      detail =
        o.textEncoding().ordinal()
          + ":"
          + text(o.pricePaid())
          + ":"
          + text(o.datePurchased())
          + ":"
          + text(o.seller());
    else if (f instanceof PopularimeterFrame p)
      detail = text(p.email()) + ":" + p.rating() + ":" + p.counter();
    else {
      var r = (RelativeVolumeFrame) f;
      var out = new StringBuilder(text(r.identification()) + ":");
      r.channels().forEach(c -> out.append(c.code()).append(','));
      out.append(':');
      for (var c : RelativeVolumeFrame.ChannelType.values()) {
        var peak = r.peakVolume(c);
        out.append(c.code())
          .append('=')
          .append(r.volumeAdjustmentIndex(c))
          .append(',')
          .append(floatBits(r.volumeAdjustment(c)))
          .append(',')
          .append(peak.bitsRepresentingPeak())
          .append(',')
          .append(hex(peak.peakVolume()))
          .append(';');
      }
      var peak = r.peakVolume();
      out.append(':')
        .append(r.volumeAdjustmentIndex())
        .append(',')
        .append(floatBits(r.volumeAdjustment()))
        .append(',')
        .append(peak.bitsRepresentingPeak())
        .append(',')
        .append(hex(peak.peakVolume()));
      detail = out.toString();
    }
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
        + props(f.asProperties())
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

  static void mutate(Frame f, String[] p) {
    f.setText("ignored");
    if (f instanceof OwnershipFrame o) {
      o.setTextEncoding(TagString.Type.values()[Integer.parseInt(p[7])]);
      o.setPricePaid(string(p[8]));
      o.setDatePurchased(string(p[9]));
      o.setSeller(string(p[10]));
    }
    if (f instanceof PopularimeterFrame o) {
      o.setEmail(string(p[8]));
      o.setRating(Integer.parseInt(p[11]));
      o.setCounter(Long.parseUnsignedLong(p[12]));
    }
    if (f instanceof RelativeVolumeFrame r) {
      var c = channel(p[13]);
      r.setIdentification(string(p[8]));
      r.setVolumeAdjustmentIndex(Integer.parseInt(p[14]), c);
      var peak = new RelativeVolumeFrame.PeakVolume(Integer.parseInt(p[16]), bytes(p[17]));
      r.setPeakVolume(peak, c);
      peak.peakVolume().clear();
      if (!p[15].equals("-"))
        r.setVolumeAdjustment(Float.intBitsToFloat(Integer.parseUnsignedInt(p[15], 16)), c);
    }
  }

  static class OwnershipChild extends OwnershipFrame {
    int calls;

    OwnershipChild(ByteVector data) {
      super(data);
    }

    @Override
    protected void parseFields(ByteVector data) {
      calls++;
      super.parseFields(data);
    }
  }

  static class PopularChild extends PopularimeterFrame {
    int calls;

    PopularChild(ByteVector data) {
      super(data);
    }

    @Override
    protected void parseFields(ByteVector data) {
      calls++;
      super.parseFields(data);
    }
  }

  static class VolumeChild extends RelativeVolumeFrame {
    int calls;

    VolumeChild(ByteVector data) {
      super(data);
    }

    @Override
    protected void parseFields(ByteVector data) {
      calls++;
      super.parseFields(data);
    }
  }

  static class Throw extends Latin1StringHandler {
    int calls;
    final int fail;

    Throw(int n) {
      fail = n;
    }

    public String parse(ByteVector data) {
      if (++calls == fail) throw new IllegalArgumentException("callback");
      return "decoded:" + new TagString(data);
    }
  }

  static void helpers() {
    var r = new RelativeVolumeFrame();
    for (int n = -32768; n <= 32767; n++) {
      r.setVolumeAdjustmentIndex(n);
      float value = r.volumeAdjustment();
      r.setVolumeAdjustment(value);
      System.out.println(
        "helper/index\t"
          + n
          + "\t"
          + r.volumeAdjustmentIndex()
          + "\t"
          + floatBits(value)
          + "\t"
          + hex(r.render()));
    }
    var owne = bytes("4f574e450000000f0000007000323032363130303573656c6c");
    var o = new OwnershipChild(owne);
    System.out.println("virtual/O/ctor\t" + o.calls);
    o.setData(owne);
    System.out.println("virtual/O/reparse\t" + o.calls);
    state("virtual/O/state", o);
    var popm = bytes("504f504d00000007000065000701020304");
    var p = new PopularChild(popm);
    System.out.println("virtual/P/ctor\t" + p.calls);
    p.setData(popm);
    System.out.println("virtual/P/reparse\t" + p.calls);
    state("virtual/P/state", p);
    var rva = bytes("5256413200000007000069640002000f00");
    var v = new VolumeChild(rva);
    System.out.println("virtual/R/ctor\t" + v.calls);
    v.setData(rva);
    System.out.println("virtual/R/reparse\t" + v.calls);
    state("virtual/R/state", v);
    for (String kind : List.of("O", "P", "R"))
      for (int fail = 1; fail <= (kind.equals("O") ? 2 : 1); fail++) {
        String[] row = {
          "",
          kind,
          "B",
          "4",
          "0",
          "-",
          "-",
          "3",
          "6f6c64",
          "6f6c642d64617465",
          "6f6c642d73656c6c",
          "333",
          "4294967295",
          "1",
          "-123",
          "-",
          "8",
          "aa"
        };
        var f = create(row);
        mutate(f, row);
        var handler = new Throw(fail);
        Id3Text.setHandler(handler);
        boolean threw = false;
        try {
          f.setData(kind.equals("O") ? owne : kind.equals("P") ? popm : rva);
        } catch (IllegalArgumentException expected) {
          threw = true;
        } finally {
          Id3Text.setHandler(null);
        }
        System.out.println(
          "callback/" + kind + "/" + fail + "\t" + b(threw) + "\t" + handler.calls);
        state("callback/" + kind + "/" + fail + "-state", f);
      }
  }

  public static void main(String[] args) throws Exception {
    var utf8 = new Utf8();
    try (var input = Files.newBufferedReader(Path.of(args[0]))) {
      String line;
      while ((line = input.readLine()) != null) {
        var p = line.split("\t", -1);
        if (p.length != 18) throw new IllegalArgumentException(line);
        String id = p[0];
        Id3Text.setHandler(p[4].equals("1") ? utf8 : null);
        var f = create(p);
        var h = f.header();
        state(id + "/ctor", f);
        System.out.println(id + "/render\t" + hex(f.render()) + "\t" + b(h == f.header()));
        state(id + "/after-render", f);
        f.setData(bytes(p[6]));
        state(id + "/reparse", f);
        mutate(f, p);
        f.header().setVersion(Long.parseUnsignedLong(p[3]));
        state(id + "/mutate", f);
        System.out.println(id + "/mutate-render\t" + hex(f.render()));
        state(id + "/mutate-after", f);
        var copy = f.toStringList();
        copy.set(0, "detached");
        copy.add("added");
        System.out.println(id + "/list-copy\t" + fields(copy) + "\t" + fields(f.toStringList()));
        if (f instanceof RelativeVolumeFrame r) {
          r.channels().clear();
          var peak = r.peakVolume(channel(p[13]));
          peak.peakVolume().clear();
        }
        state(id + "/detached", f);
        f.setData(f.render());
        state(id + "/serialized-reparse", f);
        System.out.println(id + "/serialized-render\t" + hex(f.render()));
        Id3Text.setHandler(null);
      }
    } finally {
      Id3Text.setHandler(null);
    }
    helpers();
  }
}
