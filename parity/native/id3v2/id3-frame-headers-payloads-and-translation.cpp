// Pinned TagLib mutable Frame/Header/UnknownFrame operation protocol.
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>
#include "id3v2frame.h"
#include "unknownframe.h"
#include "tpropertymap.h"
#include "tstringlist.h"
using namespace TagLib;

static ByteVector bytes(const std::string &s) {
  ByteVector data;
  if (s != "-")
    for (size_t i = 0; i < s.size(); i += 2)
      data.append(
        static_cast<char>(std::stoul(s.substr(i, 2), nullptr, 16)));
  return data;
}

static std::string hex(const ByteVector &data) {
  auto h = data.toHex();
  return {h.data(), h.size()};
}

static std::string text(const String &s) { return hex(s.data(String::UTF16BE)); }

static void state(const std::string &id, const ID3v2::Frame::Header &h) {
  std::cout << id << '\t' << hex(h.frameID()) << '\t' << h.frameSize() << '\t' << h.version() << '\t' << h.size() <<
      '\t' << h.tagAlterPreservation() << h.fileAlterPreservation() << h.readOnly() << h.groupingIdentity() << h.
      compression() << h.encryption() << h.unsynchronisation() << h.dataLengthIndicator() << '\t' << hex(h.render()) <<
      '\n';
}

static std::string props(const PropertyMap &p) {
  std::string out;
  for (const auto &[k,values]: p) {
    out += text(k) + "=";
    for (const auto &v: values)out += text(v) + ",";
    out += ";";
  }
  out += '|';
  for (const auto &v: p.unsupportedData())out += text(v) + ",";
  return out;
}

class Raw : public ID3v2::Frame {
public:
  ByteVector fields;
  unsigned int parses = 0;
  mutable unsigned int renders = 0;
  Raw(const ByteVector &data, unsigned int version) : Frame(new Header(data, version)) { parseFields(fieldData(data)); }
  String toString() const override { return "custom"; }

  void parseFields(const ByteVector &data) override {
    fields = data;
    ++parses;
  }

  ByteVector renderFields() const override {
    ++renders;
    return fields;
  }

  ByteVector extract(const ByteVector &data) const { return fieldData(data); }
  void parsed(const ByteVector &data) { parse(data); }
  void replace(Header *h, bool destroy = true) { setHeader(h, destroy); }

  String stringField(const ByteVector &data, String::Type type, int *pos = nullptr) {
    return readStringField(data, type, pos);
  }

  String::Type check(const StringList &fields, String::Type type) { return checkTextEncoding(fields, type); }
  PropertyMap properties() const { return asProperties(); }

  static void split(const PropertyMap &a, PropertyMap &b, PropertyMap &c, PropertyMap &d) {
    splitProperties(a, b, c, d);
  }
};

class UnknownChild : public ID3v2::UnknownFrame {
public:
  explicit UnknownChild(const ByteVector &v) : UnknownFrame(v) {
  }

  PropertyMap properties() const { return asProperties(); }
};

class Custom : public Raw {
public:
  Custom() : Raw("X001", 4) {
  }

  void setText(const String &s) override { fields = s.data(String::UTF8); }
  StringList toStringList() const override { return StringList("virtual-list"); }

  PropertyMap asProperties() const override {
    PropertyMap p;
    p.insert("TITLE", StringList("virtual-properties"));
    return p;
  }
};

int main(int argc, char **argv) {
  if (argc != 2)return 2;
  std::ifstream input(argv[1]);
  std::string line;
  const auto seed = bytes("5345454400000003ff7f010203");
  while (std::getline(input, line)) {
    std::stringstream stream(line);
    std::vector<std::string> f;
    std::string part;
    while (std::getline(stream, part, '\t'))f.push_back(part);
    if (f.size() != 6)return 3;
    auto id = f[0], kind = f[1];
    auto data = bytes(f[2]);
    auto version = static_cast<unsigned int>(std::stoull(f[3]));
    auto number = static_cast<unsigned int>(std::stoull(f[4]));
    auto other = bytes(f[5]);
    if (kind == "H") {
      ID3v2::Frame::Header h(data, version);
      state(id + "/ctor", h);
      ID3v2::Frame::Header kept(seed);
      kept.setData(data, version);
      state(id + "/kept", kept);
      kept.setData(data);
      state(id + "/default-version", kept);
      h.setFrameID(other);
      h.setFrameSize(number);
      h.setVersion(number);
      h.setTagAlterPreservation(true);
      state(id + "/set", h);
      h.setTagAlterPreservation(false);
      state(id + "/unset", h);
    } else if (kind == "F") {
      Raw r(data, version);
      state(id + "/ctor", *r.header());
      std::cout << id << "/fields\t" << hex(r.fields) << '\t' << r.parses << '\t' << r.renders << '\t' <<
          text(r.toString()) << '\t' << text(r.toStringList().toString()) << '\t' << props(r.properties()) << '\n';
      r.setText("ignored");
      auto live = r.header();
      auto rendered = r.render();
      std::cout << id << "/render\t" << hex(rendered) << '\t' << r.renders << '\t' << (live == r.header()) << '\n';
      state(id + "/after", *r.header());
      r.setData(other);
      std::cout << id << "/set-data\t" << hex(r.fields) << '\t' << r.parses << '\t' << r.size() << '\t' << r.
          headerSize() << '\n';
      state(id + "/set-header", *r.header());
      r.parsed(data);
      std::cout << id << "/protected-parse\t" << hex(r.fields) << '\t' << r.parses << '\n';
      r.replace(nullptr);
      std::cout << id << "/null-header\t" << hex(r.frameID()) << '\t' << r.size() << '\n';
      r.setData(data);
      state(id + "/restored", *r.header());
      auto replacement = new ID3v2::Frame::Header(other, version);
      r.replace(replacement);
      r.header()->setFrameSize(number);
      std::cout << id << "/replacement\t" << hex(r.extract(data)) << '\t' << (replacement == r.header()) << '\n';
      UnknownChild u(data);
      std::cout << id << "/unknown\t" << hex(u.data()) << '\t' << text(u.toString()) << '\t' << props(u.properties()) <<
          '\t' << hex(u.render()) << '\n';
      u.setData(other);
      std::cout << id << "/unknown-set\t" << hex(u.data()) << '\t' << hex(u.render()) << '\n';
    } else if (kind == "R") {
      Raw r("X001", 4);
      int position = static_cast<int>(number);
      auto value = r.stringField(data, static_cast<String::Type>(version), &position);
      std::cout << id << "/string\t" << text(value) << '\t' << position << '\t' << text(
        r.stringField(data, static_cast<String::Type>(version))) << '\t' << hex(data) << '\n';
    } else if (kind == "E") {
      Raw r("X001", number);
      StringList fields;
      fields.append(String(data, String::UTF8));
      fields.append(String(other, String::UTF8));
      std::cout << id << "/encoding\t" << r.check(fields, static_cast<String::Type>(version)) << '\t' << hex(
        ID3v2::Frame::textDelimiter(static_cast<String::Type>(version))) << '\n';
    } else if (kind == "K") {
      String key(data, String::UTF8);
      std::cout << id << "/mapping\t" << hex(ID3v2::Frame::keyToFrameID(key)) << '\t' << text(
        ID3v2::Frame::frameIDToKey(other)) << '\n';
    } else return 4;
  }
  PropertyMap a, b, c, d;
  a.insert("title", StringList("title"));
  a.insert("ARRANGER", StringList("arranger"));
  a.insert("DJMIXER", StringList("dj"));
  a.insert("DJ-MIX", StringList("other"));
  a.insert("PERFORMER:guitar", StringList("performer"));
  a.insert("MIXER", StringList());
  a.addUnsupportedData("ignored");
  Raw::split(a, b, c, d);
  std::cout << "split\t" << props(a) << '\t' << props(b) << '\t' << props(c) << '\t' << props(d) << '\n';
  Custom custom;
  ID3v2::Frame *base = &custom;
  base->setText("changed");
  std::cout << "virtual\t" << hex(custom.render()) << '\t' << text(base->toStringList().toString()) << '\t' <<
      props(custom.properties()) << '\n';
  Raw listOwner("X001", 4);
  auto detached = listOwner.toStringList();
  detached[0] = "changed";
  detached.append("added");
  std::cout << "list-copy\t" << text(detached.toString()) << '\t' << text(listOwner.toStringList().toString()) << '\n';
  std::cout << "prefix\t" << text(ID3v2::Frame::instrumentPrefix) << '\t' << text(ID3v2::Frame::commentPrefix) << '\t'
      << text(ID3v2::Frame::lyricsPrefix) << '\t' << text(ID3v2::Frame::urlPrefix) << '\n';
  return input.eof() ? 0 : 5;
}
