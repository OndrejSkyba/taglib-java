package cz.skyba.taglib.parity.id3v2.probes;

import cz.skyba.taglib.id3.v2.ExtendedHeader;
import cz.skyba.taglib.id3.v2.Footer;
import cz.skyba.taglib.id3.v2.Header;
import cz.skyba.taglib.id3.v2.SynchData;
import cz.skyba.taglib.toolkit.ByteVector;

import java.nio.file.Files;
import java.nio.file.Path;

public class Id3HeaderProbe {
  static ByteVector bytes(String hex) {
    return hex.equals("-")
      ? new ByteVector()
      : new ByteVector(java.util.HexFormat.of().parseHex(hex));
  }

  static String hex(ByteVector data) {
    return data.toHex().toString();
  }

  static int b(boolean value) {
    return value ? 1 : 0;
  }

  static void state(String id, Header h) {
    System.out.println(
      id
        + "\t"
        + h.majorVersion()
        + "\t"
        + h.revisionNumber()
        + "\t"
        + b(h.unsynchronisation())
        + "\t"
        + b(h.extendedHeader())
        + "\t"
        + b(h.experimentalIndicator())
        + "\t"
        + b(h.footerPresent())
        + "\t"
        + h.tagSize()
        + "\t"
        + h.completeTagSize());
  }

  static class HeaderChild extends Header {
    void parsed(ByteVector data) {
      parse(data);
    }
  }

  static class ExtendedChild extends ExtendedHeader {
    void parsed(ByteVector data) {
      parse(data);
    }
  }

  public static void main(String[] args) throws Exception {
    state("default", new Header());
    System.out.println(
      "static\t" + Header.size() + "\t" + Footer.size() + "\t" + hex(Header.fileIdentifier()));
    var seed = bytes("4944330307f000000063");
    try (var lines = Files.lines(Path.of(args[0]))) {
      for (var line : lines.toList()) {
        var f = line.split("\t", -1);
        var id = f[0];
        var data = bytes(f[2]);
        long major = Long.parseUnsignedLong(f[3]), size = Long.parseUnsignedLong(f[4]);
        if (f[1].equals("H")) {
          var ctor = new Header(data);
          state(id + "/ctor", ctor);
          var set = new Header();
          set.setData(data);
          state(id + "/set", set);
          set.setMajorVersion(major);
          set.setTagSize(size);
          state(id + "/override", set);
          System.out.println(id + "/override-render\t" + hex(set.render()));
          state(id + "/override-after", set);
          var retained = new Header(seed);
          retained.setData(data);
          state(id + "/retained", retained);
          var child = new HeaderChild();
          child.setData(seed);
          child.parsed(data);
          state(id + "/protected", child);
          System.out.println(id + "/render\t" + hex(ctor.render()));
          state(id + "/after", ctor);
          var fh = new Header(data);
          var footer = new Footer();
          System.out.println(id + "/footer\t" + hex(footer.render(fh)));
          state(id + "/footer-after", fh);
          System.out.println(id + "/repeat\t" + hex(ctor.render()) + "\t" + hex(footer.render(fh)));
        } else if (f[1].equals("S")) {
          var ext = new ExtendedHeader();
          long before = ext.size();
          ext.setData(data);
          var child = new ExtendedChild();
          child.setData(bytes("00000063"));
          child.parsed(data);
          System.out.println(
            id
              + "/synch\t"
              + SynchData.toUnsignedInt(data)
              + "\t"
              + hex(SynchData.fromUnsignedInt(major))
              + "\t"
              + hex(SynchData.decode(data))
              + "\t"
              + before
              + "\t"
              + ext.size()
              + "\t"
              + child.size()
              + "\t"
              + hex(data));
        } else if (f[1].equals("D")) {
          var decoded = SynchData.decode(data);
          System.out.println(
            id
              + "/decode\t"
              + hex(decoded)
              + "\t"
              + hex(SynchData.decode(decoded))
              + "\t"
              + hex(data));
        } else throw new IllegalArgumentException(line);
      }
    }
  }
}
