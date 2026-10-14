// Independent pinned TagLib TrueAudio File/shared ID3 owner protocol.
#include <filesystem>
#include <fstream>
#include <iostream>
#include <vector>
#include <algorithm>
#include <functional>
#include <stdexcept>
#include "trueaudiofile.h"
#include "id3v2header.h"
#include "id3v2tag.h"
#include "id3v1tag.h"
#include "id3v2framefactory.h"
#include "textidentificationframe.h"
#include "unknownframe.h"
#include "attachedpictureframe.h"
#include "tbytevectorstream.h"
#include "tpropertymap.h"
#include "fileref.h"
#include "tfilestream.h"
using namespace TagLib;
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

ByteVector read(const std::filesystem::path &p) {
  std::ifstream f(p, std::ios::binary);
  std::string s((std::istreambuf_iterator<char>(f)), {});
  return ByteVector(s.data(), s.size());
}

struct Factory : I::FrameFactory {
  mutable int raw = 0, typed = 0, aggregate = 0;
  int mode;
  explicit Factory(int m) : mode(m) { setDefaultTextEncoding(String::UTF8); }

  I::Frame *createFrame(const ByteVector &d, const I::Header *h) const override {
    ++raw;
    return mode == 2 ? nullptr : FrameFactory::createFrame(d, h);
  }

  I::Frame *createFrame(const ByteVector &d, I::Frame::Header *h, const I::Header *t) const override {
    ++typed;
    auto *f = FrameFactory::createFrame(d, h, t);
    if (mode == 3)if (auto *s = dynamic_cast<I::TextIdentificationFrame *>(f))s->setText("factory text");
    return f;
  }

  void rebuildAggregateFrames(I::Tag *t) const override {
    ++aggregate;
    FrameFactory::rebuildAggregateFrames(t);
  }
};

struct Utf8 : I::Latin1StringHandler {
  mutable int calls = 0;

  String parse(const ByteVector &d) const override {
    ++calls;
    return String(d, String::UTF8);
  }
};

void state(const std::string &id, TrueAudio::File &f) {
  auto *t = f.tag();
  auto *p = f.audioProperties();
  auto *v2 = f.ID3v2Tag();
  auto *v1 = f.ID3v1Tag();
  std::cout << id << "/handles\t" << f.isOpen() << '\t' << f.isValid() << '\t' << (t != nullptr) << '\t' << (
        p != nullptr) << '\t' << (v2 != nullptr) << ":" << (v1 != nullptr) << ":" << f.hasID3v2Tag() << ":" << f.
      hasID3v1Tag() << '\n';
  std::cout << id << "/union\t" << t->isEmpty() << '\t' << hex(t->title()) << '\t' << hex(t->artist()) << '\t' <<
      hex(t->album()) << '\t' << hex(t->comment()) << '\t' << hex(t->genre()) << '\t' << t->year() << '\t' << t->track()
      << '\t' << props(f.properties()) << '\n';
  if (v2) {
    auto h = v2->header();
    std::cout << id << "/modern\t" << v2->isEmpty() << '\t' << hex(v2->title()) << '\t' << props(v2->properties()) <<
        '\t' << h->majorVersion() << ":" << h->tagSize() << ":" << h->completeTagSize() << '\t';
    for (auto frame: v2->frameList())
      std::cout << hex(frame->frameID()) << ":" << hex(frame->toString()) << ":" << frame
          ->header()->version() << ":" << frame->size() << ",";
    std::cout << '\n';
  }
  if (v1)
    std::cout << id << "/legacy\t" << v1->isEmpty() << '\t' << hex(v1->title()) << '\t' << props(v1->properties())
        << '\n';
  if (p)
    std::cout << id << "/audio\t" << p->lengthInMilliseconds() << '\t' << p->lengthInSeconds() << '\t' << p->
        bitrate() << '\t' << p->sampleRate() << '\t' << p->channels() << '\t' << p->bitsPerSample() << '\t' << p->
        ttaVersion() << '\t' << p->sampleFrames() << '\n';
}

struct Named : ByteVectorStream {
  std::string n;
  bool ro = false;

  Named(ByteVector b, std::string n) : ByteVectorStream(b), n(n) {
  }

  FileName name() const override { return n.c_str(); }
  bool readOnly() const override { return ro; }
};

void edit(TrueAudio::File &f, int mode) {
  if (mode == 0)return;
  if (mode == 1) {
    f.tag()->setArtist("edited artist");
    f.tag()->setYear(0xffffffff);
    f.tag()->setTrack(0x80000000);
    return;
  }
  if (mode == 2) {
    f.ID3v2Tag(true)->setTitle("modern edited");
    f.ID3v1Tag(true)->setTitle("legacy edited");
    return;
  }
  if (mode == 3) {
    PropertyMap p;
    p.insert("TITLE", {"one", "two"});
    p.insert("DATE", {"2026-10-05T12:34"});
    p.insert("ARRANGER", {"a", "b"});
    p.insert("PERFORMER:GUITAR", {"g"});
    p.insert("ALBUMARTIST", {"other"});
    f.setProperties(p);
    return;
  }
  if (mode == 4) {
    auto *t = f.ID3v2Tag(true);
    t->addFrame(new I::UnknownFrame(ByteVector("ZZZZ\0\0\0\1\0\0x", 11)));
    auto *apic = new I::AttachedPictureFrame();
    apic->setPicture(ByteVector("opaque picture"));
    apic->setMimeType("image/test");
    t->addFrame(apic);
    return;
  }
  if (mode >= 5 && mode <= 7) {
    f.strip(mode == 5 ? TrueAudio::File::ID3v2 : mode == 6 ? TrueAudio::File::ID3v1 : TrueAudio::File::AllTags);
    return;
  }
  if (mode == 8) {
    f.ID3v2Tag(true)->setTitle(String(std::string(5000, 'x')));
    return;
  }
  if (mode == 9) {
    f.ID3v1Tag(true)->setTitle("legacy only");
    f.strip(TrueAudio::File::ID3v2);
    return;
  }
  if (mode == 10) {
    if (auto *t = f.ID3v2Tag())for (auto *x: I::FrameList(t->frameList()))t->removeFrame(x);
    if (auto *t = f.ID3v1Tag())t->setProperties(PropertyMap());
    return;
  }
  f.removeUnsupportedProperties(f.properties().unsupportedData());
}

void mutations(const std::string &base, TrueAudio::File &f, int mode, const std::function<ByteVector()> &bytes,
               bool memory) {
  long original = f.hasID3v2Tag() ? f.ID3v2Tag()->header()->completeTagSize() : 0;
  edit(f, mode);
  auto *t = f.tag();
  auto *p = f.audioProperties();
  auto *v2 = f.ID3v2Tag();
  auto *v1 = f.ID3v1Tag();
  auto *h = v2 ? v2->header() : nullptr;
  auto *first = v2 && !v2->frameList().isEmpty() ? v2->frameList().front() : nullptr;
  state(base + "/edited", f);
  for (int n = 0; n < 3; ++n) {
    if (memory && original > bytes().size() && v2 && !v2->isEmpty()) {
      auto candidate = v2->render();
      if (candidate.size() > original) {
        std::cout << base << "/undefined-memory-growth/" << n << '\t' << original << '\t' << bytes().size() << '\t' <<
            candidate.size() << '\n';
        break;
      }
    }
    bool saved = f.save();
    f.seek(f.tell());
    std::cout << base << "/save/" << n << '\t' << saved << '\t' << f.tell() << '\t' << hex(bytes()) << '\t' << (
          t == f.tag()) << ":" << (p == f.audioProperties()) << ":" << (v2 == f.ID3v2Tag()) << ":" << (
          v1 == f.ID3v1Tag())
        << ":" << (h && h == v2->header()) << ":" << (first && v2->frameList().contains(first)) << '\n';
    state(base + "/after/" + std::to_string(n), f);
    original = f.hasID3v2Tag() ? v2->header()->completeTagSize() : 0;
    if (n == 1)f.tag()->setTitle("short");
  }
}

struct ThrowFrame : I::Frame {
  ThrowFrame() : Frame("X001") {
  }

  String toString() const override { return ""; }

protected:
  void parseFields(const ByteVector &) override {
  }

  ByteVector renderFields() const override { throw std::runtime_error("render hook"); }
};

struct CustomFrame : I::Frame {
  unsigned int value;

  explicit CustomFrame(unsigned int v) : Frame("CUST"), value(v) {
  }

  CustomFrame(const ByteVector &d, Header *h) : Frame(h), value(fieldData(d).toUInt()) {
  }

  String toString() const override { return String::number(value); }

  PropertyMap asProperties() const override {
    PropertyMap p;
    p.insert("CUSTOM", {toString()});
    return p;
  }

protected:
  void parseFields(const ByteVector &d) override { value = d.toUInt(); }
  ByteVector renderFields() const override { return ByteVector::fromUInt(value); }
};

struct CustomFactory : I::FrameFactory {
  I::Frame *createFrameForProperty(const String &k, const StringList &v) const override {
    return k == "CUSTOM"
             ? new CustomFrame(v.isEmpty() ? 0 : v.front().toInt())
             : FrameFactory::createFrameForProperty(k, v);
  }

protected:
  I::Frame *createFrame(const ByteVector &d, I::Frame::Header *h, const I::Header *t) const override {
    return h->frameID() == "CUST" ? new CustomFrame(d, h) : FrameFactory::createFrame(d, h, t);
  }
};

int main(int argc, char **argv) {
  std::filesystem::path root(argv[1]);
  std::vector<std::filesystem::path> paths;
  for (auto &p: std::filesystem::directory_iterator(root / "inputs"))paths.push_back(p.path());
  std::sort(paths.begin(), paths.end());
  std::filesystem::create_directories(root / "outputs");
  for (auto &input: paths) {
    auto id = input.filename().string();
    auto data = read(input);
    for (int style = 0; style < 3; ++style)
      for (int enabled = 0; enabled < 2; ++enabled) {
        auto base = id + "/controls/" + std::to_string(style) + "/" + std::to_string(enabled);
        TrueAudio::File f(input.c_str(), enabled, static_cast<AudioProperties::ReadStyle>(style));
        state(base + "/path", f);
        for (int origin: {0, 7}) {
          ByteVectorStream s(data);
          s.seek(origin);
          TrueAudio::File m(&s, enabled, static_cast<AudioProperties::ReadStyle>(style));
          state(base + "/io/" + std::to_string(origin), m);
        }
      }
    for (int origin: {0, 7}) {
      ByteVectorStream s(data);
      s.seek(origin);
      auto supported = TrueAudio::File::isSupported(&s);
      std::cout << id << "/support/" << origin << '\t' << supported << '\t' << s.tell() << '\n';
    }
    for (int mode = 0; mode < 4; ++mode)
      for (int decode = 0; decode < 2; ++decode) {
        Factory c(mode);
        Utf8 u;
        I::Tag::setLatin1StringHandler(decode ? &u : nullptr);
        Named s(data, "sample.tta");
        TrueAudio::File f(&s, false, AudioProperties::Fast, mode ? &c : nullptr);
        auto base = id + "/factory/" + std::to_string(mode) + "/" + std::to_string(decode);
        state(base, f);
        std::cout << base << "/hooks\t" << c.raw << ":" << c.typed << ":" << c.aggregate << ":" << u.calls << '\n';
        if (f.tag()) {
          f.tag()->setComment("factory setter");
          std::cout << base << "/render\t" << hex(f.ID3v2Tag(true)->render()) << '\n';
        }
        I::Tag::setLatin1StringHandler(nullptr);
      }
    for (const std::string &name: std::vector<std::string>{"sample.tta", "sample.unknown"})
      for (int origin: {0, 7})
        for (
          int enabled: {0, 1}) {
          Named s(data, name);
          s.seek(origin);
          FileRef ref(&s, enabled);
          auto *f = dynamic_cast<TrueAudio::File *>(ref.file());
          auto base = id + "/ref/" + name + "/" + std::to_string(origin) + "/" + std::to_string(enabled);
          std::cout << base << '\t' << ref.isNull() << '\t' << (f != nullptr) << '\n';
          if (f) {
            state(base, *f);
            std::cout << base << "/identity\t" << (ref.tag() == f->tag()) << '\n';
          }
        }
    for (int mode = 0; mode < 12; ++mode) {
      auto base = id + "/edit/" + std::to_string(mode);
      ByteVectorStream s(data);
      TrueAudio::File m(&s);
      mutations(base + "/io", m, mode, [&] { return *s.data(); }, true);
      auto copy = root / "outputs" / (id + "." + std::to_string(mode) + ".native");
      std::filesystem::copy_file(input, copy, std::filesystem::copy_options::overwrite_existing);
      TrueAudio::File f(copy.c_str());
      mutations(base + "/path", f, mode, [&] { return read(copy); }, false);
    }
    for (int mask: {0, 1, 2, 3, 4, 65535, -1, static_cast<int>(0x80000000u)}) {
      ByteVectorStream stream(data);
      TrueAudio::File f(&stream, false);
      f.strip(mask);
      state(id + "/strip/" + std::to_string(mask), f);
    }
    Factory custom(1);
    ByteVectorStream stream(data);
    TrueAudio::File old(&stream, false, AudioProperties::Average, &custom);
    state(id + "/custom-factory", old);
    std::cout << id << "/custom-factory/hooks\t" << custom.raw << ":" << custom.typed << ":" << custom.aggregate <<
        '\n';
    Named ro(data, "readonly.tta");
    TrueAudio::File readonly(&ro);
    ro.ro = true;
    auto saved = readonly.save();
    std::cout << id << "/readonly\t" << saved << '\t' << readonly.tell() << '\t' << hex(*ro.data()) << '\n';
  }
  std::cout << "support/null\t" << TrueAudio::File::isSupported(nullptr) << '\n';
  FileStream closed((root / "missing.tta").c_str());
  std::cout << "open-stream/rejected\t" << !closed.isOpen() << '\n';
  TrueAudio::File missing((root / "missing.tta").c_str());
  std::cout << "open-file/rejected\t" << !missing.isValid() << '\n';
  auto original = read(root / "inputs/plain.tta");
  ByteVectorStream stream(original);
  TrueAudio::File f(&stream);
  auto *frame = new ThrowFrame();
  f.ID3v2Tag(true)->addFrame(frame);
  bool threw = false;
  try { f.save(); } catch (const std::runtime_error &) { threw = true; }
  std::cout << "helper/render-throw\t" << threw << '\t' << (*stream.data() == original) << '\t' << f.ID3v2Tag()->
      header()->majorVersion() << '\t' << frame->header()->version() << '\t' << f.hasID3v2Tag() << '\n';
  for (int mode = 0; mode < 3; ++mode) {
    ByteVectorStream stream(read(root / "inputs/plain.tta"));
    TrueAudio::File f(&stream, false, AudioProperties::Average, nullptr);
    f.seek(0);
    auto *t = f.ID3v2Tag();
    t->setTitle("");
    t->setComment("");
    if (mode == 1) {
      auto *title = new I::TextIdentificationFrame("TIT2", String::Latin1);
      title->setText("existing");
      t->addFrame(title);
      t->setTitle("changed");
    }
    if (mode == 2) {
      PropertyMap values;
      values.insert("ARRANGER", {"a"});
      f.setProperties(values);
    }
    state("helper/null-factory/" + std::to_string(mode), f);
    auto saved = f.save();
    std::cout << "helper/null-factory/" << mode << "/save\t" << saved << '\t' << f.tell() << '\t' << hex(*stream.data())
        << '\n';
  }
  struct Trace : ByteVectorStream {
    std::string ops;
    TrueAudio::File *file = nullptr;
    bool callback = false;

    explicit Trace(ByteVector b) : ByteVectorStream(b) {
    }

    void insert(const ByteVector &d, offset_t start, size_t replace) override {
      ops += "insert:" + std::to_string(start) + ":" + std::to_string(replace) + ":" + std::to_string(d.size()) + ";";
      ByteVectorStream::insert(d, start, replace);
      if (callback)file->ID3v1Tag(true)->setTitle("from IO callback");
    }

    void removeBlock(offset_t start, size_t size) override {
      ops += "remove:" + std::to_string(start) + ":" + std::to_string(size) + ";";
      ByteVectorStream::removeBlock(start, size);
    }

    void writeBlock(const ByteVector &d) override {
      ops += "write:" + std::to_string(tell()) + ":" + std::to_string(d.size()) + ";";
      ByteVectorStream::writeBlock(d);
    }

    void truncate(offset_t size) override {
      ops += "truncate:" + std::to_string(size) + ";";
      ByteVectorStream::truncate(size);
    }
  };
  for (int mode = 0; mode < 2; ++mode) {
    Trace stream(read(root / "inputs/plain.tta"));
    TrueAudio::File f(&stream, false);
    stream.file = &f;
    stream.callback = mode == 1;
    f.ID3v2Tag()->setTitle("modern");
    if (mode == 0)f.ID3v1Tag(true)->setTitle("legacy");
    for (int n = 0; n < 3; ++n) {
      stream.ops.clear();
      auto saved = f.save();
      std::cout << "helper/io-order/" << mode << "/" << n << '\t' << saved << '\t' << stream.ops << '\t' <<
          hex(*stream.data()) << '\n';
      state("helper/io-order/" + std::to_string(mode) + "/" + std::to_string(n), f);
      if (n == 0)f.ID3v2Tag()->setTitle(String(std::string(5000, 'x')));
      if (n == 1)f.strip();
    }
  }
  for (int mode = 0; mode < 4; ++mode) {
    Factory c(1);
    auto path = root / "inputs/plain.tta";
    if (mode == 0) {
      TrueAudio::File f(path.c_str(), false, AudioProperties::Fast, &c);
      state("helper/path-factory/0", f);
    }
    if (mode == 1) {
      TrueAudio::File f(path.c_str(), false, AudioProperties::Average, &c);
      state("helper/path-factory/1", f);
    }
    if (mode == 2) {
      TrueAudio::File f(path.c_str(), false, AudioProperties::Average, nullptr);
      state("helper/path-factory/2", f);
    }
    if (mode == 3) {
      TrueAudio::File f(path.c_str(), false, AudioProperties::Fast, nullptr);
      state("helper/path-factory/3", f);
    }
  }

  struct Failing : ByteVectorStream {
    explicit Failing(ByteVector d) : ByteVectorStream(d) {
    }

    void truncate(offset_t) override { throw std::runtime_error("growth hook"); }
  };
  Failing fail(read(root / "inputs/plain.tta"));
  TrueAudio::File failing(&fail, false);
  failing.ID3v1Tag(true)->setTitle("legacy");
  bool failed = false;
  try { failing.save(); } catch (const std::runtime_error &) { failed = true; }
  std::cout << "helper/io-failure\t" << failed << '\t' << failing.hasID3v1Tag() << '\t' << failing.hasID3v2Tag() << '\t'
      << hex(*fail.data()) << '\n';

  auto customPath = root / "outputs/helper-custom-factory.native";
  std::filesystem::copy_file(root / "inputs/plain.tta", customPath, std::filesystem::copy_options::overwrite_existing);
  CustomFactory custom;
  for (int n = 0; n < 6; ++n) {
    TrueAudio::File f(customPath.c_str(), true, AudioProperties::Average, n >= 3 ? &custom : nullptr);
    auto *t = f.ID3v2Tag(true);
    if (n == 0) {
      for (auto *x: I::FrameList(t->frameList()))t->removeFrame(x);
      t->setArtist("An artist");
      t->setTitle("A title");
    }
    if (n == 1)t->addFrame(new CustomFrame(1234567890));
    if (n == 3)f.strip();
    if (n == 4) {
      PropertyMap values;
      values.insert("CUSTOM", {"305419896"});
      t->setProperties(values);
    }
    state("helper/custom-factory/" + std::to_string(n), f);
    if (n == 0 || n == 1 || n == 3 || n == 4) {
      auto saved = f.save();
      f.seek(f.tell());
      std::cout << "helper/custom-factory/save/" << n << '\t' << saved << '\t' << f.tell() << '\t' <<
          hex(read(customPath)) << '\n';
    }
  }
}
