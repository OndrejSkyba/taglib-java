// Pinned TagLib mutable APIC/PIC/GEOB operations, independent of Java output.
#include <fstream>
#include <iostream>
#include <memory>
#include <sstream>
#include <string>
#include <vector>
#include "id3v2tag.h"
#include "id3v2header.h"
#include "id3v2framefactory.h"
#include "attachedpictureframe.h"
#include "generalencapsulatedobjectframe.h"
#include "flacpicture.h"
#include "tpropertymap.h"
using namespace TagLib;
namespace I = TagLib::ID3v2;

static ByteVector bytes(const std::string &s) {
  ByteVector v;
  if (s != "-")
    for (size_t i = 0; i < s.size(); i += 2)
      v.append(
        static_cast<char>(std::stoul(s.substr(i, 2), nullptr, 16)));
  return v;
}

static std::string hex(const ByteVector &v) {
  auto h = v.toHex();
  return {h.data(), h.size()};
}

static String string(const std::string &s) {
  auto b = bytes(s);
  return String(std::string(b.data(), b.size()), String::UTF8);
}

static std::string text(const String &s) { return hex(s.data(String::UTF16BE)); }

static std::string fields(const StringList &v) {
  std::string out = std::to_string(v.size()) + ":";
  for (const auto &s: v)out += text(s) + "|";
  return out;
}

static std::string props(const PropertyMap &v) {
  std::string out;
  for (const auto &[k,l]: v)out += text(k) + "=" + fields(l) + ";";
  out += "|";
  for (const auto &s: v.unsupportedData())out += text(s) + ",";
  return out;
}

class Utf8 : public I::Latin1StringHandler {
public:
  String parse(const ByteVector &v) const override { return String(v, String::UTF8); }
};

class Factory : public I::FrameFactory {
public:
  Factory() = default;
};

static std::unique_ptr<I::Frame> create(const std::string &kind, const std::string &mode, const ByteVector &data,
                                        unsigned int version) {
  if (mode == "F") {
    I::Header h;
    h.setMajorVersion(version);
    Factory factory;
    auto f = std::unique_ptr<I::Frame>(factory.createFrame(data, &h));
    if (!f || (kind == "O"
                 ? !dynamic_cast<I::GeneralEncapsulatedObjectFrame *>(f.get())
                 : !dynamic_cast<I::AttachedPictureFrame *>(f.get())) || (
          kind == "V" && !dynamic_cast<I::AttachedPictureFrameV22 *>(f.get())))
      throw std::runtime_error(
        "Wrong native constructor type");
    return f;
  }
  if (kind == "O")
    return std::unique_ptr<I::Frame>(mode == "P"
                                       ? new I::GeneralEncapsulatedObjectFrame(data)
                                       : new I::GeneralEncapsulatedObjectFrame());
  return std::unique_ptr<I::Frame>(mode == "P" ? new I::AttachedPictureFrame(data) : new I::AttachedPictureFrame());
}

static void state(const std::string &id, I::Frame &f) {
  auto h = f.header();
  std::cout << id << '\t' << hex(f.frameID()) << '\t' << f.size() << '\t' << f.headerSize() << '\t' << h->version() <<
      '\t' << text(f.toString()) << '\t' << fields(f.toStringList()) << '\t';
  if (auto a = dynamic_cast<I::AttachedPictureFrame *>(&f))
    std::cout << a->textEncoding() << ":" << text(a->mimeType()) << ":" << a->type() << ":" << text(a->description()) <<
        ":" << hex(a->picture()) << ":" << (dynamic_cast<I::AttachedPictureFrameV22 *>(&f) != nullptr);
  if (auto o = dynamic_cast<I::GeneralEncapsulatedObjectFrame *>(&f))
    std::cout << o->textEncoding() << ":" << text(o->mimeType()) << ":" << text(o->fileName()) << ":" <<
        text(o->description()) << ":" << hex(o->object());
  I::Tag tag;
  tag.addFrame(&f);
  auto p = tag.properties();
  tag.removeFrame(&f, false);
  std::cout << '\t' << props(p) << '\t' << h->tagAlterPreservation() << h->fileAlterPreservation() << h->readOnly() << h
      ->groupingIdentity() << h->compression() << h->encryption() << h->unsynchronisation() << h->dataLengthIndicator()
      << '\n';
}

static void mutate(I::Frame &f, String::Type encoding, const String &mime, const String &name,
                   const String &description, const ByteVector &payload, unsigned int type) {
  f.setText("ignored");
  if (auto a = dynamic_cast<I::AttachedPictureFrame *>(&f)) {
    a->setTextEncoding(encoding);
    a->setMimeType(mime);
    a->setType(I::AttachedPictureFrame::typeFromUInt(type));
    a->setDescription(description);
    a->setPicture(payload);
  }
  if (auto o = dynamic_cast<I::GeneralEncapsulatedObjectFrame *>(&f)) {
    o->setTextEncoding(encoding);
    o->setMimeType(mime);
    o->setFileName(name);
    o->setDescription(description);
    o->setObject(payload);
  }
}

class PictureChild : public I::AttachedPictureFrame {
public:
  int calls{0};

  PictureChild(const ByteVector &data) : AttachedPictureFrame(data) {
  }

protected:
  void parseFields(const ByteVector &data) override {
    ++calls;
    AttachedPictureFrame::parseFields(data);
  }
};

class ObjectChild : public I::GeneralEncapsulatedObjectFrame {
public:
  int calls{0};

  ObjectChild(const ByteVector &data) : GeneralEncapsulatedObjectFrame(data) {
  }

protected:
  void parseFields(const ByteVector &data) override {
    ++calls;
    GeneralEncapsulatedObjectFrame::parseFields(data);
  }
};

class Throw : public I::Latin1StringHandler {
public:
  mutable int calls{0};
  int fail;

  explicit Throw(int n) : fail(n) {
  }

  String parse(const ByteVector &v) const override {
    if (++calls == fail)throw std::runtime_error("callback");
    return "decoded:" + String(v);
  }
};

static void helpers() {
  for (unsigned long long n: {
         0ULL, 1ULL, 20ULL, 21ULL, 31ULL, 255ULL, 0xffffffffULL, 0x100000000ULL, 0x100000003ULL, 0xffffffffffffffffULL
       }) {
    auto narrowed = static_cast<unsigned int>(n);
    auto a = I::AttachedPictureFrame::typeFromUInt(narrowed);
    auto f = FLAC::Picture::typeFromUInt(narrowed);
    std::cout << "helper/uint\t" << n << '\t' << a << '\t' << text(I::AttachedPictureFrame::typeToString(a)) << '\t' <<
        f << '\t' << text(FLAC::Picture::typeToString(f)) << '\n';
  }
  for (int n = 0; n < 256; ++n) {
    auto a = I::AttachedPictureFrame::typeFromByte(static_cast<char>(n));
    std::cout << "helper/byte\t" << n << '\t' << a << '\t' << text(I::AttachedPictureFrame::typeToString(a)) << '\n';
  }
  for (int n = 0; n < 21; ++n) {
    auto a = static_cast<I::AttachedPictureFrame::Type>(n);
    auto s = I::AttachedPictureFrame::typeToString(a);
    std::cout << "helper/string\t" << n << '\t' << text(s) << '\t' << I::AttachedPictureFrame::typeFromString(s) << '\t'
        << I::AttachedPictureFrame::typeFromString(s.upper()) << '\n';
  }
  auto apic = bytes("41504943000000100000006d696d650003646573630064617461");
  PictureChild a(apic);
  std::cout << "virtual/picture-ctor\t" << a.calls << '\n';
  a.setData(apic);
  std::cout << "virtual/picture-reparse\t" << a.calls << '\n';
  state("virtual/picture-state", a);
  auto geob = bytes("47454f42000000140000006d696d65006e616d6500646573630064617461");
  ObjectChild o(geob);
  std::cout << "virtual/object-ctor\t" << o.calls << '\n';
  o.setData(geob);
  std::cout << "virtual/object-reparse\t" << o.calls << '\n';
  state("virtual/object-state", o);
  for (const auto &kind: {std::string("A"), std::string("O")})
    for (int fail = 1; fail <= 3; ++fail) {
      auto frame = create(kind, "B", ByteVector(), 4);
      mutate(*frame, String::UTF8, "old-mime", "old-name", "old-desc", "old-body", 3);
      Throw handler(fail);
      I::Tag::setLatin1StringHandler(&handler);
      bool threw = false;
      try { frame->setData(kind == "A" ? apic : geob); } catch (const std::runtime_error &) { threw = true; }
      I::Tag::setLatin1StringHandler(nullptr);
      std::cout << "callback/" << kind << "/" << fail << '\t' << threw << '\t' << handler.calls << '\n';
      state("callback/" + kind + "/" + std::to_string(fail) + "-state", *frame);
    }
}

int main(int argc, char **argv) {
  if (argc != 2)return 2;
  std::ifstream input(argv[1]);
  std::string line;
  Utf8 utf8;
  while (std::getline(input, line)) {
    std::stringstream row(line);
    std::vector<std::string> p;
    std::string v;
    while (std::getline(row, v, '\t'))p.push_back(v);
    if (p.size() != 13)return 3;
    const auto &id = p[0];
    auto version = static_cast<unsigned int>(std::stoull(p[3]));
    I::Tag::setLatin1StringHandler(p[4] == "1" ? &utf8 : nullptr);
    auto f = create(p[1], p[2], bytes(p[5]), version);
    auto h = f->header();
    state(id + "/ctor", *f);
    std::cout << id << "/render\t" << hex(f->render()) << '\t' << (h == f->header()) << '\n';
    state(id + "/after-render", *f);
    f->setData(bytes(p[6]));
    state(id + "/reparse", *f);
    mutate(*f, static_cast<String::Type>(std::stoi(p[7])), string(p[8]), string(p[9]), string(p[10]), bytes(p[11]),
           static_cast<unsigned int>(std::stoull(p[12])));
    f->header()->setVersion(version);
    state(id + "/mutate", *f);
    std::cout << id << "/mutate-render\t" << hex(f->render()) << '\n';
    state(id + "/mutate-after", *f);
    auto copied = f->toStringList();
    copied.front() = "detached";
    copied.append("added");
    std::cout << id << "/list-copy\t" << fields(copied) << '\t' << fields(f->toStringList()) << '\n';
    if (auto a = dynamic_cast<I::AttachedPictureFrame *>(f.get())) {
      auto d = a->picture();
      d.append('x');
      std::cout << id << "/bytes-copy\t" << hex(d) << '\t' << hex(a->picture()) << '\n';
    } else {
      auto o = dynamic_cast<I::GeneralEncapsulatedObjectFrame *>(f.get());
      auto d = o->object();
      d.append('x');
      std::cout << id << "/bytes-copy\t" << hex(d) << '\t' << hex(o->object()) << '\n';
    }
    f->setData(f->render());
    state(id + "/serialized-reparse", *f);
    std::cout << id << "/serialized-render\t" << hex(f->render()) << '\n';
    I::Tag::setLatin1StringHandler(nullptr);
  }
  helpers();
  return input.eof() ? 0 : 4;
}
