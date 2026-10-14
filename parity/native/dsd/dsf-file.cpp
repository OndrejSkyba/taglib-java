// Independent pinned TagLib DSF File/shared ID3 owner protocol.
#include <filesystem>
#include <fstream>
#include <iostream>
#include <vector>
#include <algorithm>
#include <functional>
#include <stdexcept>
#include "dsffile.h"
#include "id3v2header.h"
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

void state(const std::string &id, DSF::File &f) {
  if (!f.isValid()) {
    std::cout << id << "/rejected\t1" << '\n';
    return;
  }
  auto *t = f.tag();
  auto *p = f.audioProperties();
  std::cout << id << "/handles\t" << f.isOpen() << '\t' << f.isValid() << '\t' << f.tell() << '\t' << (t != nullptr) <<
      '\t' << (p != nullptr) << '\n';
  if (t) {
    auto h = t->header();
    std::cout << id << "/tag\t" << t->isEmpty() << '\t' << hex(t->title()) << '\t' << hex(t->artist()) << '\t' <<
        hex(t->album()) << '\t' << hex(t->comment()) << '\t' << hex(t->genre()) << '\t' << t->year() << '\t' << t->
        track() << '\t' << props(f.properties()) << '\t' << h->majorVersion() << ":" << h->tagSize() << ":" << h->
        completeTagSize() << '\t';
    for (auto frame: t->frameList())
      std::cout << hex(frame->frameID()) << ":" << hex(frame->toString()) << ":" << frame
          ->header()->version() << ":" << frame->size() << ",";
    std::cout << '\n';
  }
  if (p)
    std::cout << id << "/audio\t" << p->lengthInMilliseconds() << '\t' << p->lengthInSeconds() << '\t' << p->
        bitrate() << '\t' << p->sampleRate() << '\t' << p->channels() << '\t' << p->bitsPerSample() << '\t' << p->
        formatVersion() << '\t' << p->formatID() << '\t' << p->channelType() << '\t' << p->sampleCount() << '\t' << p->
        blockSizePerChannel() << '\n';
}

struct Named : ByteVectorStream {
  std::string n, trace;
  bool ro = false;

  Named(ByteVector b, std::string n) : ByteVectorStream(b), n(n) {
  }

  FileName name() const override { return n.c_str(); }
  bool readOnly() const override { return ro; }

  ByteVector readBlock(size_t count) override {
    auto at = tell();
    auto b = ByteVectorStream::readBlock(count);
    trace += std::to_string(at) + "," + std::to_string(count) + "," + std::to_string(b.size()) + ";";
    return b;
  }
};

void edit(DSF::File &f, int mode) {
  auto *t = f.tag();
  if (mode == 0)return;
  if (mode == 1) {
    t->setArtist("edited artist");
    t->setYear(0xffffffff);
    t->setTrack(0x80000000);
    return;
  }
  if (mode == 2) {
    t->removeFrames("TIT2");
    t->removeFrames("ZZZZ");
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
    auto *x = new I::UnknownFrame(ByteVector("ZZZZ\0\0\0\1\0\0x", 11));
    t->addFrame(x);
    auto *apic = new I::AttachedPictureFrame();
    apic->setPicture(ByteVector("opaque picture"));
    apic->setMimeType("image/test");
    t->addFrame(apic);
    return;
  }
  if (mode == 5) {
    for (auto *x: I::FrameList(t->frameList()))t->removeFrame(x);
    return;
  }
  t->setTitle(String(std::string(5000, 'x')));
}

void mutations(const std::string &base, DSF::File &f, int mode, const std::function<ByteVector()> &bytes) {
  if (!f.tag())return;
  auto *t = f.tag();
  auto *h = t->header();
  auto *p = f.audioProperties();
  auto first = (mode == 2 || mode == 3 || mode == 5 || t->frameList().isEmpty()) ? nullptr : t->frameList().front();
  edit(f, mode);
  state(base + "/edited", f);
  for (int n = 0; n < 3; ++n) {
    bool saved = f.save(n == 1 ? I::v3 : I::v4);
    f.seek(f.tell());
    std::cout << base << "/save/" << n << '\t' << saved << '\t' << f.tell() << '\t' << hex(bytes()) << '\t' << (
      t == f.tag()) << ":" << (h == t->header()) << ":" << (p == f.audioProperties()) << ":" << (
      first && t->frameList().contains(first)) << '\n';
    state(base + "/after/" + std::to_string(n), f);
    if (n == 1)t->setTitle("short");
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
        DSF::File f(input.c_str(), enabled, static_cast<AudioProperties::ReadStyle>(style));
        state(base + "/path", f);
        for (int origin: {0, 7}) {
          ByteVectorStream s(data);
          s.seek(origin);
          DSF::File m(&s, enabled, static_cast<AudioProperties::ReadStyle>(style));
          state(base + "/io/" + std::to_string(origin), m);
        }
      }
    for (int origin: {0, 7}) {
      ByteVectorStream s(data);
      s.seek(origin);
      auto supported = DSF::File::isSupported(&s);
      std::cout << id << "/support/" << origin << '\t' << supported << '\t' << s.tell() << '\n';
    }
    for (int mode = 0; mode < 4; ++mode)
      for (int decode = 0; decode < 2; ++decode) {
        Factory c(mode);
        Utf8 u;
        I::Tag::setLatin1StringHandler(decode ? &u : nullptr);
        Named s(data, "sample.dsf");
        DSF::File f(&s, false, AudioProperties::Fast, mode ? &c : nullptr);
        auto base = id + "/factory/" + std::to_string(mode) + "/" + std::to_string(decode);
        state(base, f);
        std::cout << base << "/hooks\t" << c.raw << ":" << c.typed << ":" << c.aggregate << ":" << u.calls << '\t' << s.
            trace << '\n';
        if (f.isValid() && f.tag()) {
          f.tag()->setComment("factory setter");
          std::cout << base << "/render\t" << hex(f.tag()->render()) << '\n';
        }
        I::Tag::setLatin1StringHandler(nullptr);
      }
    for (const std::string &name: std::vector<std::string>{"sample.dsf", "sample.unknown"})
      for (int origin: {0, 7})
        for (
          int enabled: {0, 1}) {
          Named s(data, name);
          s.seek(origin);
          FileRef ref(&s, enabled);
          auto *f = dynamic_cast<DSF::File *>(ref.file());
          auto base = id + "/ref/" + name + "/" + std::to_string(origin) + "/" + std::to_string(enabled);
          std::cout << base << '\t' << ref.isNull() << '\t' << (f != nullptr) << '\t' << s.tell() << '\n';
          if (f) {
            state(base, *f);
            std::cout << base << "/identity\t" << (ref.tag() == f->tag()) << '\n';
          }
        }
    for (int mode = 0; mode < 7; ++mode) {
      auto base = id + "/edit/" + std::to_string(mode);
      ByteVectorStream s(data);
      DSF::File m(&s);
      mutations(base + "/io", m, mode, [&] { return *s.data(); });
      auto copy = root / "outputs" / (id + "." + std::to_string(mode) + ".native");
      std::filesystem::copy_file(input, copy, std::filesystem::copy_options::overwrite_existing);
      DSF::File f(copy.c_str());
      mutations(base + "/path", f, mode, [&] { return read(copy); });
    }
    if (id.rfind("prefix-", 0) == 0) {
      int origin = id.rfind("prefix-7-", 0) == 0 ? 7 : 31;
      ByteVectorStream stream(data);
      stream.seek(origin);
      DSF::File f(&stream);
      mutations(id + "/origin-save/io", f, 1, [&] { return *stream.data(); });
      auto copy = root / "outputs" / (id + ".origin.native");
      std::filesystem::copy_file(input, copy, std::filesystem::copy_options::overwrite_existing);
      FileStream pathStream(copy.c_str());
      pathStream.seek(origin);
      DSF::File pathFile(&pathStream);
      mutations(id + "/origin-save/path", pathFile, 1, [&] { return read(copy); });
      Named named(data, "sample.dsf");
      named.seek(origin);
      FileRef ref(&named);
      auto *actual = dynamic_cast<DSF::File *>(ref.file());
      std::cout << id << "/origin-ref\t" << ref.isNull() << '\t' << (actual != nullptr) << '\t' << (
        actual && ref.tag() == actual->tag()) << '\n';
      if (actual)state(id + "/origin-ref", *actual);
    }
    Named ro(data, "readonly.dsf");
    DSF::File readonly(&ro);
    ro.ro = true;
    auto saved = readonly.save();
    std::cout << id << "/readonly\t" << saved << '\t' << readonly.tell() << '\t' << hex(*ro.data()) << '\n';
  }
  std::cout << "support/null\t" << DSF::File::isSupported(nullptr) << '\n';
  FileStream closed((root / "missing.dsf").c_str());
  std::cout << "open-stream/rejected\t" << !closed.isOpen() << '\n';
  DSF::File missing((root / "missing.dsf").c_str());
  std::cout << "open-file/rejected\t" << !missing.isValid() << '\n';
  auto original = read(root / "inputs/plain.dsf");
  ByteVectorStream stream(original);
  DSF::File f(&stream);
  auto *frame = new ThrowFrame();
  f.tag()->addFrame(frame);
  bool threw = false;
  try { f.save(I::v3); } catch (const std::runtime_error &) { threw = true; }
  std::cout << "helper/render-throw\t" << threw << '\t' << (*stream.data() == original) << '\t' << f.tag()->header()->
      majorVersion() << '\t' << frame->header()->version() << '\n';
}
