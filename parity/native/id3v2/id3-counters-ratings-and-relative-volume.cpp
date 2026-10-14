// Independent pinned upstream OWNE/POPM/RVA2 protocol. No Java-derived expected values.
#include <fstream>
#include <iostream>
#include <memory>
#include <sstream>
#include <string>
#include <vector>
#include <cstring>
#include "ownershipframe.h"
#include "popularimeterframe.h"
#include "relativevolumeframe.h"
#include "id3v2tag.h"
#include "id3v2header.h"
#include "id3v2framefactory.h"
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

static unsigned int floatBits(float f) {
  unsigned int n;
  std::memcpy(&n, &f, 4);
  return n;
}

static float floating(const std::string &s) {
  unsigned int n = static_cast<unsigned int>(std::stoul(s, nullptr, 16));
  float f;
  std::memcpy(&f, &n, 4);
  return f;
}

class Utf8 : public I::Latin1StringHandler {
public:
  String parse(const ByteVector &v) const override { return String(v, String::UTF8); }
};

class Factory : public I::FrameFactory {
public:
  Factory() = default;
};

static std::unique_ptr<I::Frame> create(const std::vector<std::string> &p) {
  auto d = bytes(p[5]);
  auto kind = p[1], mode = p[2];
  if (mode == "F") {
    I::Header h;
    h.setMajorVersion(static_cast<unsigned int>(std::stoul(p[3])));
    Factory factory;
    auto f = std::unique_ptr<I::Frame>(factory.createFrame(d, &h));
    if (!f || (kind == "O" && !dynamic_cast<I::OwnershipFrame *>(f.get())) || (
          kind == "P" && !dynamic_cast<I::PopularimeterFrame *>(f.get())) || (
          kind == "R" && !dynamic_cast<I::RelativeVolumeFrame *>(f.get())))
      throw std::runtime_error(
        "Wrong native constructor");
    return f;
  }
  if (kind == "O")
    return std::unique_ptr<I::Frame>(mode == "P"
                                       ? new I::OwnershipFrame(d)
                                       : new I::OwnershipFrame(
                                         static_cast<String::Type>(std::stoi(p[7]))));
  if (kind == "P")
    return std::unique_ptr<I::Frame>(mode == "P"
                                       ? new I::PopularimeterFrame(d)
                                       : new I::PopularimeterFrame());
  return std::unique_ptr<I::Frame>(mode == "P" ? new I::RelativeVolumeFrame(d) : new I::RelativeVolumeFrame());
}

static void state(const std::string &id, I::Frame &f) {
  auto h = f.header();
  std::cout << id << '\t' << hex(f.frameID()) << '\t' << f.size() << '\t' << f.headerSize() << '\t' << h->version() <<
      '\t' << text(f.toString()) << '\t' << fields(f.toStringList()) << '\t';
  if (auto o = dynamic_cast<I::OwnershipFrame *>(&f))
    std::cout << o->textEncoding() << ":" << text(o->pricePaid()) << ":" << text(o->datePurchased()) << ":" <<
        text(o->seller());
  if (auto p = dynamic_cast<I::PopularimeterFrame *>(&f))
    std::cout << text(p->email()) << ":" << p->rating() << ":" << p->counter();
  if (auto r = dynamic_cast<I::RelativeVolumeFrame *>(&f)) {
    std::cout << text(r->identification()) << ":";
    for (auto c: r->channels())std::cout << c << ",";
    std::cout << ":";
    for (int c = 0; c <= 8; ++c) {
      auto t = static_cast<I::RelativeVolumeFrame::ChannelType>(c);
      auto peak = r->peakVolume(t);
      std::cout << c << "=" << r->volumeAdjustmentIndex(t) << "," << floatBits(r->volumeAdjustment(t)) << "," <<
          static_cast<unsigned int>(peak.bitsRepresentingPeak) << "," << hex(peak.peakVolume) << ";";
    }
    auto peak = r->peakVolume();
    std::cout << ":" << r->volumeAdjustmentIndex() << "," << floatBits(r->volumeAdjustment()) << "," << static_cast<
      unsigned int>(peak.bitsRepresentingPeak) << "," << hex(peak.peakVolume);
  }
  I::Tag tag;
  tag.addFrame(&f);
  auto p = tag.properties();
  tag.removeFrame(&f, false);
  std::cout << '\t' << props(p) << '\t' << h->tagAlterPreservation() << h->fileAlterPreservation() << h->readOnly() << h
      ->groupingIdentity() << h->compression() << h->encryption() << h->unsynchronisation() << h->dataLengthIndicator()
      << '\n';
}

static void mutate(I::Frame &f, const std::vector<std::string> &p) {
  f.setText("ignored");
  if (auto o = dynamic_cast<I::OwnershipFrame *>(&f)) {
    o->setTextEncoding(static_cast<String::Type>(std::stoi(p[7])));
    o->setPricePaid(string(p[8]));
    o->setDatePurchased(string(p[9]));
    o->setSeller(string(p[10]));
  }
  if (auto o = dynamic_cast<I::PopularimeterFrame *>(&f)) {
    o->setEmail(string(p[8]));
    o->setRating(std::stoi(p[11]));
    o->setCounter(static_cast<unsigned int>(std::stoull(p[12])));
  }
  if (auto r = dynamic_cast<I::RelativeVolumeFrame *>(&f)) {
    auto t = static_cast<I::RelativeVolumeFrame::ChannelType>(std::stoi(p[13]));
    r->setIdentification(string(p[8]));
    r->setVolumeAdjustmentIndex(static_cast<short>(std::stoll(p[14])), t);
    I::RelativeVolumeFrame::PeakVolume peak;
    peak.bitsRepresentingPeak = static_cast<unsigned char>(std::stoll(p[16]));
    peak.peakVolume = bytes(p[17]);
    r->setPeakVolume(peak, t);
    peak.peakVolume.clear();
    if (p[15] != "-")r->setVolumeAdjustment(floating(p[15]), t);
  }
}

class OwnershipChild : public I::OwnershipFrame {
public:
  int calls{0};

  OwnershipChild(const ByteVector &v) : OwnershipFrame(v) {
  }

protected:
  void parseFields(const ByteVector &v) override {
    ++calls;
    OwnershipFrame::parseFields(v);
  }
};

class PopularChild : public I::PopularimeterFrame {
public:
  int calls{0};

  PopularChild(const ByteVector &v) : PopularimeterFrame(v) {
  }

protected:
  void parseFields(const ByteVector &v) override {
    ++calls;
    PopularimeterFrame::parseFields(v);
  }
};

class VolumeChild : public I::RelativeVolumeFrame {
public:
  int calls{0};

  VolumeChild(const ByteVector &v) : RelativeVolumeFrame(v) {
  }

protected:
  void parseFields(const ByteVector &v) override {
    ++calls;
    RelativeVolumeFrame::parseFields(v);
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
  I::RelativeVolumeFrame r;
  for (int n = -32768; n <= 32767; ++n) {
    r.setVolumeAdjustmentIndex(static_cast<short>(n));
    auto value = r.volumeAdjustment();
    r.setVolumeAdjustment(value);
    std::cout << "helper/index\t" << n << '\t' << r.volumeAdjustmentIndex() << '\t' << floatBits(value) << '\t' <<
        hex(r.render()) << '\n';
  }
  auto owne = bytes("4f574e450000000f0000007000323032363130303573656c6c");
  OwnershipChild o(owne);
  std::cout << "virtual/O/ctor\t" << o.calls << '\n';
  o.setData(owne);
  std::cout << "virtual/O/reparse\t" << o.calls << '\n';
  state("virtual/O/state", o);
  auto popm = bytes("504f504d00000007000065000701020304");
  PopularChild p(popm);
  std::cout << "virtual/P/ctor\t" << p.calls << '\n';
  p.setData(popm);
  std::cout << "virtual/P/reparse\t" << p.calls << '\n';
  state("virtual/P/state", p);
  auto rva = bytes("5256413200000007000069640002000f00");
  VolumeChild v(rva);
  std::cout << "virtual/R/ctor\t" << v.calls << '\n';
  v.setData(rva);
  std::cout << "virtual/R/reparse\t" << v.calls << '\n';
  state("virtual/R/state", v);
  for (const auto &kind: {std::string("O"), std::string("P"), std::string("R")})
    for (
      int fail = 1; fail <= (kind == "O" ? 2 : 1); ++fail) {
      std::vector<std::string> row = {
        "", kind, "B", "4", "0", "-", "-", "3", "6f6c64", "6f6c642d64617465", "6f6c642d73656c6c", "333", "4294967295",
        "1", "-123", "-", "8", "aa"
      };
      auto f = create(row);
      mutate(*f, row);
      Throw handler(fail);
      I::Tag::setLatin1StringHandler(&handler);
      bool threw = false;
      try { f->setData(kind == "O" ? owne : kind == "P" ? popm : rva); } catch (const std::runtime_error &) {
        threw = true;
      }
      I::Tag::setLatin1StringHandler(nullptr);
      std::cout << "callback/" << kind << "/" << fail << '\t' << threw << '\t' << handler.calls << '\n';
      state("callback/" + kind + "/" + std::to_string(fail) + "-state", *f);
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
    if (p.size() != 18)return 3;
    const auto &id = p[0];
    I::Tag::setLatin1StringHandler(p[4] == "1" ? &utf8 : nullptr);
    auto f = create(p);
    auto h = f->header();
    state(id + "/ctor", *f);
    std::cout << id << "/render\t" << hex(f->render()) << '\t' << (h == f->header()) << '\n';
    state(id + "/after-render", *f);
    f->setData(bytes(p[6]));
    state(id + "/reparse", *f);
    mutate(*f, p);
    f->header()->setVersion(static_cast<unsigned int>(std::stoul(p[3])));
    state(id + "/mutate", *f);
    std::cout << id << "/mutate-render\t" << hex(f->render()) << '\n';
    state(id + "/mutate-after", *f);
    auto copy = f->toStringList();
    copy.front() = "detached";
    copy.append("added");
    std::cout << id << "/list-copy\t" << fields(copy) << '\t' << fields(f->toStringList()) << '\n';
    if (auto r = dynamic_cast<I::RelativeVolumeFrame *>(f.get())) {
      auto channels = r->channels();
      channels.clear();
      auto peak = r->peakVolume(static_cast<I::RelativeVolumeFrame::ChannelType>(std::stoi(p[13])));
      peak.bitsRepresentingPeak = 77;
      peak.peakVolume.clear();
      state(id + "/detached", *f);
    } else state(id + "/detached", *f);
    f->setData(f->render());
    state(id + "/serialized-reparse", *f);
    std::cout << id << "/serialized-render\t" << hex(f->render()) << '\n';
    I::Tag::setLatin1StringHandler(nullptr);
  }
  helpers();
  return input.eof() ? 0 : 4;
}
