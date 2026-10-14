#include <filesystem>
#include <fstream>
#include <iostream>
#include <algorithm>
#include <vector>
#include <stdexcept>
#include "dsdifffile.h"
#include "id3v2header.h"
#include "id3v2framefactory.h"
#include "textidentificationframe.h"
#include "tbytevectorstream.h"
#include "tpropertymap.h"
#include "tfilestream.h"
#include "fileref.h"
using namespace TagLib;
namespace D = DSDIFF;
namespace I = ID3v2;

std::string hex(const ByteVector &b) {
  auto h = b.toHex();
  return {h.data(), h.size()};
}

std::string hex(const String &s) { return hex(s.data(String::UTF16BE)); }

std::string list(const StringList &v) {
  std::string s = std::to_string(v.size()) + ":";
  for (auto &a: v)s += hex(a) + ",";
  return s;
}

std::string props(const PropertyMap &p) {
  std::string s;
  for (auto &[k,v]: p)s += hex(k) + "=" + list(v) + ";";
  return s + "/" + list(p.unsupportedData());
}

PropertyMap pm(std::initializer_list<std::pair<String, StringList> > entries) {
  PropertyMap p;
  for (auto &[k,v]: entries)p.insert(k, v);
  return p;
}

ByteVector read(const std::filesystem::path &p) {
  std::ifstream f(p, std::ios::binary);
  std::string s((std::istreambuf_iterator<char>(f)), {});
  return ByteVector(s.data(), s.size());
}

void state(const std::string &id, D::File &f) {
  if (!f.isValid()) {
    std::cout << id << "/rejected\t1" << '\n';
    return;
  }
  auto *t = f.tag();
  auto *p = f.audioProperties();
  auto *m = f.ID3v2Tag();
  auto *d = f.DIINTag();
  std::cout << id << "/handles\t" << f.isOpen() << ':' << f.isValid() << ':' << f.tell() << ':' << (p != nullptr) << ':'
      << (m != nullptr) << ':' << (d != nullptr) << ':' << f.hasID3v2Tag() << ':' << f.hasDIINTag() << '\n';
  std::cout << id << "/union\t" << t->isEmpty() << '\t' << hex(t->title()) << '\t' << hex(t->artist()) << '\t' <<
      hex(t->album()) << '\t' << hex(t->comment()) << '\t' << hex(t->genre()) << '\t' << t->year() << '\t' << t->track()
      << '\t' << props(f.properties()) << '\n';
  if (m) {
    auto *h = m->header();
    std::cout << id << "/modern\t" << m->isEmpty() << '\t' << hex(m->title()) << '\t' << props(m->properties()) << '\t'
        << h->majorVersion() << ':' << h->tagSize() << ':' << h->completeTagSize() << '\t';
    for (auto *x: m->frameList())std::cout << hex(x->frameID()) << ':' << hex(x->toString()) << ',';
    std::cout << '\n';
  }
  if (d)
    std::cout << id << "/diin\t" << d->isEmpty() << '\t' << hex(d->title()) << '\t' << hex(d->artist()) << '\t' <<
        props(d->properties()) << '\n';
  if (p)
    std::cout << id << "/audio\t" << p->lengthInMilliseconds() << ':' << p->lengthInSeconds() << ':' << p->bitrate()
        << ':' << p->sampleRate() << ':' << p->channels() << ':' << p->bitsPerSample() << ':' << p->sampleCount() <<
        '\n';
}

struct Factory : I::FrameFactory {
  mutable int raw = 0, typed = 0, aggregate = 0;
  int mode;
  Factory(int m) : mode(m) { setDefaultTextEncoding(String::UTF8); }

  I::Frame *createFrame(const ByteVector &b, const I::Header *h) const override {
    raw++;
    return mode == 2 ? nullptr : FrameFactory::createFrame(b, h);
  }

protected:
  I::Frame *createFrame(const ByteVector &b, I::Frame::Header *h, const I::Header *t) const override {
    typed++;
    auto *f = FrameFactory::createFrame(b, h, t);
    if (mode == 3 && f)if (auto *x = dynamic_cast<I::TextIdentificationFrame *>(f))x->setText("factory text");
    return f;
  }

public:
  void rebuildAggregateFrames(I::Tag *t) const override {
    aggregate++;
    FrameFactory::rebuildAggregateFrames(t);
  }
};

struct Named : ByteVectorStream {
  std::string trace, n;
  bool ro = false;

  Named(ByteVector b) : ByteVectorStream(b) {
  }

  FileName name() const override { return n.c_str(); }
  bool readOnly() const override { return ro; }

  ByteVector readBlock(size_t n) override {
    auto at = tell();
    auto v = ByteVectorStream::readBlock(n);
    trace += std::to_string(at) + "," + std::to_string(n) + "," + std::to_string(v.size()) + ";";
    return v;
  }
};

int main(int argc, char **argv) {
  std::filesystem::path root(argv[1]), input = root / "inputs" / argv[2];
  std::string id = argv[2];
  auto data = read(input);
  bool writes = argc > 3;
  for (int style = 0; style < 3; ++style)
    for (int enabled = 0; enabled < 2; ++enabled) {
      auto base = id + "/controls/" + std::to_string(style) + "/" + std::to_string(enabled);
      D::File f(input.c_str(), enabled, static_cast<AudioProperties::ReadStyle>(style));
      state(base + "/path", f);
      for (int origin: {0, 7}) {
        ByteVectorStream s(data);
        s.seek(origin);
        D::File m(&s, enabled, static_cast<AudioProperties::ReadStyle>(style));
        state(base + "/io/" + std::to_string(origin), m);
      }
      ByteVector pref("prefix!", 7);
      pref.append(data);
      ByteVectorStream s(pref);
      s.seek(7);
      D::File m(&s, enabled, static_cast<AudioProperties::ReadStyle>(style));
      state(base + "/prefixed", m);
    }
  for (int origin: {0, 7}) {
    ByteVectorStream s(data);
    s.seek(origin);
    std::cout << id << "/support/" << origin << '\t' << D::File::isSupported(&s) << ':' << s.tell() << '\n';
  }
  for (int mode = 0; mode < 4; ++mode) {
    Factory c(mode);
    Named s(data);
    D::File f(&s, false, AudioProperties::Fast, mode ? &c : nullptr);
    auto base = id + "/factory/" + std::to_string(mode);
    state(base, f);
    std::cout << base << "/hooks\t" << c.raw << ':' << c.typed << ':' << c.aggregate << '\t' << s.trace << '\n';
    if (f.isValid()) {
      f.setProperties(pm({{"TITLE", {"factory setter"}}}));
      std::cout << base << "/render\t" << hex(f.ID3v2Tag()->render()) << '\n';
    }
  }
  for (const std::string &name: std::vector<std::string>{"sample.dff", "sample.unknown"})
    for (int origin: {0, 7})
      for (
        int enabled: {0, 1}) {
        Named s(data);
        s.n = name;
        s.seek(origin);
        FileRef ref(&s, enabled);
        auto *f = dynamic_cast<D::File *>(ref.file());
        auto base = id + "/ref/" + name + "/" + std::to_string(origin) + "/" + std::to_string(enabled);
        std::cout << base << '\t' << ref.isNull() << ':' << (f != nullptr) << ':' << s.tell() << '\n';
        if (f) {
          state(base, *f);
          std::cout << base << "/identity\t" << (ref.tag() == f->tag()) << '\n';
        }
      }
  {
    ByteVectorStream s(data);
    auto before = *s.data();
    D::File f(&s);
    if (!f.isValid()) {
      std::cout << id << "/invalid-open\t" << !f.isValid() << ':' << (*s.data() == before) << '\n';
    }
  }
  if (!writes)return 0;
  for (int mask: {0, 1, 2, 3, 4, 65535, -1, static_cast<int>(0x80000000u)})
    for (int strip = 0; strip < 2; ++strip)
      for (int ver: {3, 4}) {
        std::string base = id + "/save/" + std::to_string(mask) + "/" + std::to_string(strip) + "/" +
                           std::to_string(ver);
        ByteVectorStream s(data);
        D::File f(&s);
        f.ID3v2Tag(true)->setTitle("modern edited");
        f.DIINTag(true)->setTitle("diin edited");
        f.DIINTag()->setArtist("diin artist");
        auto *t = f.tag();
        auto *p = f.audioProperties();
        auto *m = f.ID3v2Tag();
        auto *d = f.DIINTag();
        for (int n = 0; n < 3; ++n) {
          bool ok = f.save(mask, static_cast<TagLib::File::StripTags>(strip), static_cast<I::Version>(ver));
          std::cout << base << '/' << n << "/bytes\t" << ok << ':' << f.tell() << ':' << (t == f.tag()) << ':' << (
            p == f.audioProperties()) << ':' << ((mask & 2) ? m == f.ID3v2Tag() : true) << ':' << (
            (mask & 1) ? d == f.DIINTag() : true) << '\t' << hex(*s.data()) << '\n';
          state(base + '/' + std::to_string(n), f);
          ByteVectorStream r(*s.data());
          D::File reopened(&r);
          state(base + '/' + std::to_string(n) + "/reopen", reopened);
          if (n == 0)f.tag()->setTitle("x");
          if (n == 1) {
            f.ID3v2Tag(true)->setTitle("");
            f.ID3v2Tag()->setArtist("");
            f.DIINTag(true)->setTitle("");
            f.DIINTag()->setArtist("");
          }
        }
      }
  for (int mask: {0, 1, 2, 3, 4, 65535, -1, static_cast<int>(0x80000000u)}) {
    Named s(data);
    D::File f(&s);
    s.ro = true;
    auto base = id + "/strip/" + std::to_string(mask);
    auto saved = f.save(mask);
    std::cout << base << "/readonly-save\t" << saved << '\t' << hex(*s.data()) << '\n';
    f.strip(mask);
    state(base, f);
    std::cout << base << "/bytes\t" << hex(*s.data()) << '\n';
    ByteVectorStream r(*s.data());
    D::File reopened(&r);
    state(base + "/reopen", reopened);
  }
  for (int operation = 0; operation < 6; ++operation) {
    auto copy = root / "outputs" / (id + "." + std::to_string(operation) + ".native");
    std::filesystem::copy_file(input, copy, std::filesystem::copy_options::overwrite_existing);
    D::File f(copy.c_str());
    auto base = id + "/path/" + std::to_string(operation);
    if (operation == 0)f.tag()->setArtist("union artist");
    if (operation == 1)f.setProperties(pm({{"TITLE", {"one", "two"}}, {"DATE", {"2026-10-05"}}, {"ALBUM", {"album"}}}));
    if (operation == 2)f.DIINTag(true)->setTitle(String(std::wstring(L"A\u0100\u00e9")));
    if (operation == 3)f.ID3v2Tag(true)->setTitle(String(std::string(5000, 'x')));
    if (operation == 4) {
      f.ID3v2Tag(true)->setTitle("");
      f.DIINTag(true)->setTitle("");
      f.DIINTag()->setArtist("");
    }
    if (operation == 5)f.removeUnsupportedProperties(f.properties().unsupportedData());
    for (int n = 0; n < 3; ++n) {
      auto ok = f.save();
      f.seek(f.tell());
      std::cout << base << '/' << n << "/bytes\t" << ok << ':' << f.tell() << '\t' << hex(read(copy)) << '\n';
      state(base + '/' + std::to_string(n), f);
    }
  }
  return 0;
}
