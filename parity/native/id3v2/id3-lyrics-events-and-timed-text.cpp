// Pure pinned TagLib SYLT/ETCO protocol; independent of Java output.
#include <fstream>
#include <iostream>
#include <memory>
#include <sstream>
#include <string>
#include <vector>
#include "eventtimingcodesframe.h"
#include "synchronizedlyricsframe.h"
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
  auto k = p[1], m = p[2];
  if (m == "F") {
    I::Header h;
    h.setMajorVersion(static_cast<unsigned int>(std::stoul(p[3])));
    Factory factory;
    auto f = std::unique_ptr<I::Frame>(factory.createFrame(d, &h));
    if (!f || (k == "E" && !dynamic_cast<I::EventTimingCodesFrame *>(f.get())) || (
          k == "S" && !dynamic_cast<I::SynchronizedLyricsFrame *>(f.get())))
      throw std::runtime_error(
        "Wrong native constructor");
    return f;
  }
  if (k == "E")
    return std::unique_ptr<I::Frame>(m == "P"
                                       ? new I::EventTimingCodesFrame(d)
                                       : new I::EventTimingCodesFrame());
  return std::unique_ptr<I::Frame>(m == "P"
                                     ? new I::SynchronizedLyricsFrame(d)
                                     : new I::SynchronizedLyricsFrame(static_cast<String::Type>(std::stoi(p[7]))));
}

static void state(const std::string &id, I::Frame &f) {
  auto h = f.header();
  std::cout << id << '\t' << hex(f.frameID()) << '\t' << f.size() << '\t' << f.headerSize() << '\t' << h->version() <<
      '\t' << text(f.toString()) << '\t' << fields(f.toStringList()) << '\t';
  if (auto e = dynamic_cast<I::EventTimingCodesFrame *>(&f)) {
    auto entries = e->synchedEvents();
    std::cout << e->timestampFormat() << ":" << entries.size() << ":";
    for (const auto &entry: entries)std::cout << entry.time << "@" << entry.type << ";";
  }
  if (auto s = dynamic_cast<I::SynchronizedLyricsFrame *>(&f)) {
    auto entries = s->synchedText();
    std::cout << s->textEncoding() << ":" << hex(s->language()) << ":" << s->timestampFormat() << ":" << s->type() <<
        ":" << text(s->description()) << ":" << entries.size() << ":";
    for (const auto &entry: entries)std::cout << entry.time << "@" << text(entry.text) << ";";
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
  std::stringstream parts(p[12]);
  std::string part;
  if (auto e = dynamic_cast<I::EventTimingCodesFrame *>(&f)) {
    e->setTimestampFormat(static_cast<I::EventTimingCodesFrame::TimestampFormat>(std::stoi(p[8])));
    I::EventTimingCodesFrame::SynchedEventList list;
    while (std::getline(parts, part, ';'))
      if (part != "-") {
        auto pos = part.find(':');
        list.append(I::EventTimingCodesFrame::SynchedEvent(static_cast<unsigned int>(std::stoull(part.substr(0, pos))),
                                                           static_cast<I::EventTimingCodesFrame::EventType>(std::stoi(
                                                             part.substr(pos + 1)))));
      }
    e->setSynchedEvents(list);
    list.clear();
  }
  if (auto s = dynamic_cast<I::SynchronizedLyricsFrame *>(&f)) {
    s->setTextEncoding(static_cast<String::Type>(std::stoi(p[7])));
    s->setLanguage(bytes(p[11]));
    s->setTimestampFormat(static_cast<I::SynchronizedLyricsFrame::TimestampFormat>(std::stoi(p[8])));
    s->setType(static_cast<I::SynchronizedLyricsFrame::Type>(std::stoi(p[9])));
    s->setDescription(string(p[10]));
    I::SynchronizedLyricsFrame::SynchedTextList list;
    while (std::getline(parts, part, ';'))
      if (part != "-") {
        auto pos = part.find(':');
        list.append(I::SynchronizedLyricsFrame::SynchedText(static_cast<unsigned int>(std::stoull(part.substr(0, pos))),
                                                            string(part.substr(pos + 1))));
      }
    s->setSynchedText(list);
    if (!list.isEmpty())list.front().text = "detached";
    list.clear();
  }
}

class EventChild : public I::EventTimingCodesFrame {
public:
  int calls{0};

  EventChild(const ByteVector &d) : EventTimingCodesFrame(d) {
  }

protected:
  void parseFields(const ByteVector &d) override {
    ++calls;
    EventTimingCodesFrame::parseFields(d);
  }
};

class LyricsChild : public I::SynchronizedLyricsFrame {
public:
  int calls{0};

  LyricsChild(const ByteVector &d) : SynchronizedLyricsFrame(d) {
  }

protected:
  void parseFields(const ByteVector &d) override {
    ++calls;
    SynchronizedLyricsFrame::parseFields(d);
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
  std::cout << "helper/named/Padding\t" << I::EventTimingCodesFrame::Padding << '\n';
  std::cout << "helper/named/EndOfInitialSilence\t" << I::EventTimingCodesFrame::EndOfInitialSilence << '\n';
  std::cout << "helper/named/IntroStart\t" << I::EventTimingCodesFrame::IntroStart << '\n';
  std::cout << "helper/named/MainPartStart\t" << I::EventTimingCodesFrame::MainPartStart << '\n';
  std::cout << "helper/named/OutroStart\t" << I::EventTimingCodesFrame::OutroStart << '\n';
  std::cout << "helper/named/OutroEnd\t" << I::EventTimingCodesFrame::OutroEnd << '\n';
  std::cout << "helper/named/VerseStart\t" << I::EventTimingCodesFrame::VerseStart << '\n';
  std::cout << "helper/named/RefrainStart\t" << I::EventTimingCodesFrame::RefrainStart << '\n';
  std::cout << "helper/named/InterludeStart\t" << I::EventTimingCodesFrame::InterludeStart << '\n';
  std::cout << "helper/named/ThemeStart\t" << I::EventTimingCodesFrame::ThemeStart << '\n';
  std::cout << "helper/named/VariationStart\t" << I::EventTimingCodesFrame::VariationStart << '\n';
  std::cout << "helper/named/KeyChange\t" << I::EventTimingCodesFrame::KeyChange << '\n';
  std::cout << "helper/named/TimeChange\t" << I::EventTimingCodesFrame::TimeChange << '\n';
  std::cout << "helper/named/MomentaryUnwantedNoise\t" << I::EventTimingCodesFrame::MomentaryUnwantedNoise << '\n';
  std::cout << "helper/named/SustainedNoise\t" << I::EventTimingCodesFrame::SustainedNoise << '\n';
  std::cout << "helper/named/SustainedNoiseEnd\t" << I::EventTimingCodesFrame::SustainedNoiseEnd << '\n';
  std::cout << "helper/named/IntroEnd\t" << I::EventTimingCodesFrame::IntroEnd << '\n';
  std::cout << "helper/named/MainPartEnd\t" << I::EventTimingCodesFrame::MainPartEnd << '\n';
  std::cout << "helper/named/VerseEnd\t" << I::EventTimingCodesFrame::VerseEnd << '\n';
  std::cout << "helper/named/RefrainEnd\t" << I::EventTimingCodesFrame::RefrainEnd << '\n';
  std::cout << "helper/named/ThemeEnd\t" << I::EventTimingCodesFrame::ThemeEnd << '\n';
  std::cout << "helper/named/Profanity\t" << I::EventTimingCodesFrame::Profanity << '\n';
  std::cout << "helper/named/ProfanityEnd\t" << I::EventTimingCodesFrame::ProfanityEnd << '\n';
  std::cout << "helper/named/NotPredefinedSynch0\t" << I::EventTimingCodesFrame::NotPredefinedSynch0 << '\n';
  std::cout << "helper/named/NotPredefinedSynch1\t" << I::EventTimingCodesFrame::NotPredefinedSynch1 << '\n';
  std::cout << "helper/named/NotPredefinedSynch2\t" << I::EventTimingCodesFrame::NotPredefinedSynch2 << '\n';
  std::cout << "helper/named/NotPredefinedSynch3\t" << I::EventTimingCodesFrame::NotPredefinedSynch3 << '\n';
  std::cout << "helper/named/NotPredefinedSynch4\t" << I::EventTimingCodesFrame::NotPredefinedSynch4 << '\n';
  std::cout << "helper/named/NotPredefinedSynch5\t" << I::EventTimingCodesFrame::NotPredefinedSynch5 << '\n';
  std::cout << "helper/named/NotPredefinedSynch6\t" << I::EventTimingCodesFrame::NotPredefinedSynch6 << '\n';
  std::cout << "helper/named/NotPredefinedSynch7\t" << I::EventTimingCodesFrame::NotPredefinedSynch7 << '\n';
  std::cout << "helper/named/NotPredefinedSynch8\t" << I::EventTimingCodesFrame::NotPredefinedSynch8 << '\n';
  std::cout << "helper/named/NotPredefinedSynch9\t" << I::EventTimingCodesFrame::NotPredefinedSynch9 << '\n';
  std::cout << "helper/named/NotPredefinedSynchA\t" << I::EventTimingCodesFrame::NotPredefinedSynchA << '\n';
  std::cout << "helper/named/NotPredefinedSynchB\t" << I::EventTimingCodesFrame::NotPredefinedSynchB << '\n';
  std::cout << "helper/named/NotPredefinedSynchC\t" << I::EventTimingCodesFrame::NotPredefinedSynchC << '\n';
  std::cout << "helper/named/NotPredefinedSynchD\t" << I::EventTimingCodesFrame::NotPredefinedSynchD << '\n';
  std::cout << "helper/named/NotPredefinedSynchE\t" << I::EventTimingCodesFrame::NotPredefinedSynchE << '\n';
  std::cout << "helper/named/NotPredefinedSynchF\t" << I::EventTimingCodesFrame::NotPredefinedSynchF << '\n';
  std::cout << "helper/named/AudioEnd\t" << I::EventTimingCodesFrame::AudioEnd << '\n';
  std::cout << "helper/named/AudioFileEnds\t" << I::EventTimingCodesFrame::AudioFileEnds << '\n';
  for (int n = 0; n < 256; ++n) {
    I::EventTimingCodesFrame e;
    I::EventTimingCodesFrame::SynchedEventList list;
    list.append(
      I::EventTimingCodesFrame::SynchedEvent(0xffffffff, static_cast<I::EventTimingCodesFrame::EventType>(n)));
    e.setSynchedEvents(list);
    std::cout << "helper/event\t" << n << '\t' << e.synchedEvents().front().type << '\t' << hex(e.render()) << '\n';
  }
  auto ed = bytes("4554434f000000060000020201020304");
  EventChild e(ed);
  std::cout << "virtual/E/ctor\t" << e.calls << '\n';
  e.setData(ed);
  std::cout << "virtual/E/reparse\t" << e.calls << '\n';
  state("virtual/E/state", e);
  auto sd = bytes("53594c5400000015000000656e6702016400610001020304620005060708");
  LyricsChild s(sd);
  std::cout << "virtual/S/ctor\t" << s.calls << '\n';
  s.setData(sd);
  std::cout << "virtual/S/reparse\t" << s.calls << '\n';
  state("virtual/S/state", s);
  for (int fail = 1; fail <= 3; ++fail) {
    I::SynchronizedLyricsFrame frame(String::UTF8);
    frame.setDescription("old");
    I::SynchronizedLyricsFrame::SynchedTextList old;
    old.append(I::SynchronizedLyricsFrame::SynchedText(7, "old-text"));
    frame.setSynchedText(old);
    Throw handler(fail);
    I::Tag::setLatin1StringHandler(&handler);
    bool threw = false;
    try { frame.setData(sd); } catch (const std::runtime_error &) { threw = true; }
    I::Tag::setLatin1StringHandler(nullptr);
    std::cout << "callback/" << fail << '\t' << threw << '\t' << handler.calls << '\n';
    state("callback/" + std::to_string(fail) + "-state", frame);
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
    auto id = p[0];
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
    if (auto e = dynamic_cast<I::EventTimingCodesFrame *>(f.get())) {
      auto entries = e->synchedEvents();
      if (!entries.isEmpty()) {
        entries.front().time = 99;
        entries.front().type = I::EventTimingCodesFrame::Padding;
      }
      entries.clear();
    } else {
      auto s = dynamic_cast<I::SynchronizedLyricsFrame *>(f.get());
      auto entries = s->synchedText();
      if (!entries.isEmpty()) {
        entries.front().time = 99;
        entries.front().text = "detached";
      }
      entries.clear();
      auto language = s->language();
      language.clear();
    }
    state(id + "/detached", *f);
    f->setData(f->render());
    state(id + "/serialized-reparse", *f);
    std::cout << id << "/serialized-render\t" << hex(f->render()) << '\n';
    I::Tag::setLatin1StringHandler(nullptr);
  }
  helpers();
  return input.eof() ? 0 : 4;
}
