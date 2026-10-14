// Pinned TagLib native mutable text/common-frame operation protocol.
#include <fstream>
#include <iostream>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>
#include "id3v2tag.h"
#include "id3v2header.h"
#include "id3v2framefactory.h"
#include "id3v2synchdata.h"
#include "textidentificationframe.h"
#include "commentsframe.h"
#include "unsynchronizedlyricsframe.h"
#include "urllinkframe.h"
#include "uniquefileidentifierframe.h"
#include "privateframe.h"
#include "podcastframe.h"
#include "unknownframe.h"
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

static StringList list(const std::string &s) {
  StringList out;
  if (s == "!")return out;
  std::stringstream in(s);
  std::string v;
  while (std::getline(in, v, ','))out.append(string(v));
  return out;
}

static std::string fields(const StringList &v) {
  std::string out = std::to_string(v.size()) + ":";
  for (const auto &s: v)out += text(s) + "|";
  return out;
}

static std::string properties(const PropertyMap &v) {
  std::string out;
  for (const auto &[k,l]: v)out += text(k) + "=" + fields(l) + ";";
  out += "|";
  for (const auto &s: v.unsupportedData())out += text(s) + ",";
  return out;
}

class Utf8Handler : public I::Latin1StringHandler {
public:
  String parse(const ByteVector &v) const override { return String(v, String::UTF8); }
};

class FreshFactory : public I::FrameFactory {
public:
  FreshFactory() = default;
};

class SplitFrame : public I::Frame {
public:
  SplitFrame() : Frame("XXXX") {
  }

  String toString() const override { return String(); }
  using Frame::splitProperties;

protected:
  void parseFields(const ByteVector &) override {
  }

  ByteVector renderFields() const override { return ByteVector(); }
};

static ByteVector identifier(const std::string &kind) {
  if (kind == "T")return "TCOM";
  if (kind == "U")return "TXXX";
  if (kind == "C")return "COMM";
  if (kind == "L")return "USLT";
  if (kind == "W")return "WOAF";
  if (kind == "X")return "WXXX";
  if (kind == "I")return "UFID";
  if (kind == "P")return "PRIV";
  return "PCST";
}

static bool expected(const std::string &k, I::Frame *f) {
  if (k == "U")return dynamic_cast<I::UserTextIdentificationFrame *>(f);
  if (k == "T")return dynamic_cast<I::TextIdentificationFrame *>(f);
  if (k == "C")return dynamic_cast<I::CommentsFrame *>(f);
  if (k == "L")return dynamic_cast<I::UnsynchronizedLyricsFrame *>(f);
  if (k == "X")return dynamic_cast<I::UserUrlLinkFrame *>(f);
  if (k == "W")return dynamic_cast<I::UrlLinkFrame *>(f);
  if (k == "I")return dynamic_cast<I::UniqueFileIdentifierFrame *>(f);
  if (k == "P")return dynamic_cast<I::PrivateFrame *>(f);
  return dynamic_cast<I::PodcastFrame *>(f);
}

static std::unique_ptr<I::Frame> create(const std::string &kind, const std::string &mode, const ByteVector &data,
                                        unsigned int version, String::Type encoding) {
  I::Frame *f = nullptr;
  if (mode == "F") {
    I::Header h;
    h.setMajorVersion(version);
    FreshFactory factory;
    f = factory.createFrame(data, &h);
    if (!f || !expected(kind, f))throw std::runtime_error("Native factory did not return required type");
    return std::unique_ptr<I::Frame>(f);
  }
  if (kind == "T")
    f = mode == "P"
          ? new I::TextIdentificationFrame(data)
          : new I::TextIdentificationFrame(identifier(kind), encoding);
  else if (kind == "U")
    f = mode == "P"
          ? new I::UserTextIdentificationFrame(data)
          : new I::UserTextIdentificationFrame(encoding);
  else if (kind == "C")f = mode == "P" ? new I::CommentsFrame(data) : new I::CommentsFrame(encoding);
  else if (kind == "L")
    f = mode == "P"
          ? new I::UnsynchronizedLyricsFrame(data)
          : new I::UnsynchronizedLyricsFrame(encoding);
  else if (kind == "W")f = new I::UrlLinkFrame(mode == "P" ? data : identifier(kind));
  else if (kind == "X")f = mode == "P" ? new I::UserUrlLinkFrame(data) : new I::UserUrlLinkFrame(encoding);
  else if (kind == "I")
    f = mode == "P"
          ? new I::UniqueFileIdentifierFrame(data)
          : new I::UniqueFileIdentifierFrame("", ByteVector());
  else if (kind == "P")f = mode == "P" ? new I::PrivateFrame(data) : new I::PrivateFrame();
  else {
    f = new I::PodcastFrame();
    if (mode == "P")f->setData(data);
  }
  return std::unique_ptr<I::Frame>(f);
}

static void state(const std::string &id, I::Frame &f) {
  auto h = f.header();
  std::cout << id << '\t' << hex(f.frameID()) << '\t' << f.size() << '\t' << f.headerSize() << '\t' << h->version() <<
      '\t' << text(f.toString()) << '\t' << fields(f.toStringList()) << '\t';
  if (auto v = dynamic_cast<I::TextIdentificationFrame *>(&f))
    std::cout << v->textEncoding() << ":" << fields(v->fieldList());
  if (auto v = dynamic_cast<I::CommentsFrame *>(&f))
    std::cout << v->textEncoding() << ":" << hex(v->language()) << ":" << text(v->description()) << ":" <<
        text(v->text());
  if (auto v = dynamic_cast<I::UnsynchronizedLyricsFrame *>(&f))
    std::cout << v->textEncoding() << ":" << hex(v->language()) << ":" << text(v->description()) << ":" <<
        text(v->text());
  if (auto v = dynamic_cast<I::UserUrlLinkFrame *>(&f))
    std::cout << v->textEncoding() << ":" << text(v->description()) << ":";
  if (auto v = dynamic_cast<I::UrlLinkFrame *>(&f))std::cout << text(v->url());
  if (auto v = dynamic_cast<I::UniqueFileIdentifierFrame *>(&f))
    std::cout << text(v->owner()) << ":" << hex(v->identifier());
  if (auto v = dynamic_cast<I::PrivateFrame *>(&f))std::cout << text(v->owner()) << ":" << hex(v->data());
  std::cout << '\t';
  auto user = dynamic_cast<I::UserTextIdentificationFrame *>(&f);
  if (user && user->fieldList().isEmpty())std::cout << "UNDEFINED_EMPTY_TXXX_PROPERTY";
  else {
    I::Tag tag;
    tag.addFrame(&f);
    auto p = tag.properties();
    tag.removeFrame(&f, false);
    std::cout << properties(p);
  }
  std::cout << '\t' << h->tagAlterPreservation() << h->fileAlterPreservation() << h->readOnly() << h->groupingIdentity()
      << h->compression() << h->encryption() << h->unsynchronisation() << h->dataLengthIndicator() << '\n';
}

static void mutate(I::Frame &f, String::Type encoding, const String &desc, const String &value,
                   const ByteVector &payload, const StringList &values) {
  f.setText(value);
  if (auto u = dynamic_cast<I::UserTextIdentificationFrame *>(&f)) {
    u->setDescription(desc);
    u->setText(values);
    u->setTextEncoding(encoding);
  } else if (auto t = dynamic_cast<I::TextIdentificationFrame *>(&f)) {
    t->setText(values);
    t->setTextEncoding(encoding);
  }
  if (auto c = dynamic_cast<I::CommentsFrame *>(&f)) {
    c->setDescription(desc);
    c->setLanguage(payload);
    c->setTextEncoding(encoding);
  }
  if (auto l = dynamic_cast<I::UnsynchronizedLyricsFrame *>(&f)) {
    l->setDescription(desc);
    l->setLanguage(payload);
    l->setTextEncoding(encoding);
  }
  if (auto x = dynamic_cast<I::UserUrlLinkFrame *>(&f)) {
    x->setDescription(desc);
    x->setTextEncoding(encoding);
  }
  if (auto i = dynamic_cast<I::UniqueFileIdentifierFrame *>(&f)) {
    i->setOwner(desc);
    i->setIdentifier(payload);
  }
  if (auto p = dynamic_cast<I::PrivateFrame *>(&f)) {
    p->setOwner(desc);
    p->setData(payload);
  }
}

static void helpers() {
  PropertyMap p;
  p.insert("ARRANGER", StringList("one").append("two"));
  p.insert("DJMIXER", StringList());
  p.insert("DJ-MIX", StringList("ignored"));
  p.insert("PERFORMER:guitar", StringList("three"));
  p.insert("PERFORMER:", StringList("empty"));
  p.insert("TITLE", StringList("title"));
  // Filter supported roles before native creation to avoid mutating its global lookup map.
  PropertyMap roles;
  for (const auto &[key,values]: p)
    if (I::TextIdentificationFrame::involvedPeopleMap().contains(key))
      roles.
          insert(key, values);
  auto a = std::unique_ptr<I::TextIdentificationFrame>(I::TextIdentificationFrame::createTIPLFrame(roles));
  auto b = std::unique_ptr<I::TextIdentificationFrame>(I::TextIdentificationFrame::createTMCLFrame(p));
  state("helper/TIPL", *a);
  std::cout << "helper/TIPL-render\t" << hex(a->render()) << '\n';
  state("helper/TMCL", *b);
  std::cout << "helper/TMCL-render\t" << hex(b->render()) << '\n';
  std::cout << "helper/people\t";
  for (const auto &[k,v]: I::TextIdentificationFrame::involvedPeopleMap())std::cout << text(k) << "=" << text(v) << ",";
  std::cout << '\n';
  PropertyMap single, tipl, tmcl;
  SplitFrame::splitProperties(p, single, tipl, tmcl);
  std::cout << "helper/split-after-creation\t" << properties(single) << '\t' << properties(tipl) << '\t' <<
      properties(tmcl) << '\n';
  const auto &map = I::TextIdentificationFrame::involvedPeopleMap();
  bool before = map.contains("CONST-INDEX");
  auto indexed = map.value("CONST-INDEX");
  std::cout << "helper/const-map-index\t" << before << '\t' << text(indexed) << '\t' << map.contains("CONST-INDEX") <<
      '\n';
  for (const auto &key: {
         String("MUSICBRAINZ_ALBUMID"), String("musicbrainz album id"), String("custom::Name"), String(),
         String("MUSICIP_PUID")
       })
    std::cout << "helper/mapping\t" << text(key) << '\t' << text(I::UserTextIdentificationFrame::keyToTXXX(key)) <<
        '\t' << text(I::UserTextIdentificationFrame::txxxToKey(key)) << '\n';
  I::Tag tag;
  auto u = new I::UserTextIdentificationFrame("target", StringList("one"));
  auto u2 = new I::UserTextIdentificationFrame("target", StringList("two"));
  tag.addFrame(new I::UnknownFrame("TXXX"));
  tag.addFrame(u);
  tag.addFrame(u2);
  u->header()->setFrameID("XXXX");
  auto c = new I::CommentsFrame();
  c->setDescription("target");
  auto l = new I::UnsynchronizedLyricsFrame();
  l->setDescription("target");
  auto x = new I::UserUrlLinkFrame();
  x->setDescription("target");
  auto i = new I::UniqueFileIdentifierFrame("target", ByteVector());
  tag.addFrame(c);
  tag.addFrame(l);
  tag.addFrame(x);
  tag.addFrame(i);
  std::cout << "helper/find\t" << (I::UserTextIdentificationFrame::find(&tag, "target") == u) << '\t' << (
    I::UserTextIdentificationFrame::find(&tag, "TARGET") == nullptr) << '\t' << (
    I::CommentsFrame::findByDescription(&tag, "target") == c) << '\t' << (
    I::UnsynchronizedLyricsFrame::findByDescription(&tag, "target") == l) << '\t' << (
    I::UserUrlLinkFrame::find(&tag, "target") == x) << '\t' << (
    I::UniqueFileIdentifierFrame::findByOwner(&tag, "target") == i) << '\n';
}

class VirtualUrl : public I::UrlLinkFrame {
public:
  VirtualUrl() : UrlLinkFrame("WOAF") {
  }

  String url() const override { return "virtual"; }
  void setUrl(const String &s) override { UrlLinkFrame::setUrl("set:" + s); }
};

static ByteVector wire(const char *id, const ByteVector &body) {
  return ByteVector(id) + I::SynchData::fromUInt(body.size()) + ByteVector(2, '\0') + body;
}

class TextChild : public I::TextIdentificationFrame {
public:
  int calls{0};

  TextChild(const ByteVector &data) : TextIdentificationFrame(data) {
  }

protected:
  void parseFields(const ByteVector &data) override {
    ++calls;
    TextIdentificationFrame::parseFields(data);
  }
};

class UserUrlChild : public I::UserUrlLinkFrame {
public:
  int calls{0};

  UserUrlChild(const ByteVector &data) : UserUrlLinkFrame(data) {
  }

  void setUrl(const String &s) override {
    ++calls;
    UrlLinkFrame::setUrl("set:" + s);
  }
};

class ThrowHandler : public I::Latin1StringHandler {
public:
  mutable int calls{0};

  String parse(const ByteVector &v) const override {
    if (++calls == 2)throw std::runtime_error("second callback");
    return "decoded:" + String(v);
  }
};

static void callbacks() {
  auto data = wire("TCOM", ByteVector("\0one\0two", 8));
  TextChild t(data);
  std::cout << "virtual/text-ctor\t" << t.calls << '\n';
  t.setData(data);
  std::cout << "virtual/text-reparse\t" << t.calls << '\n';
  state("virtual/text-state", t);
  auto urlData = wire("WXXX", ByteVector("\0desc\0url", 9));
  UserUrlChild u(urlData);
  std::cout << "virtual/user-url-ctor\t" << u.calls << '\n';
  state("virtual/user-url-ctor-state", u);
  u.setData(urlData);
  std::cout << "virtual/user-url-reparse\t" << u.calls << '\n';
  state("virtual/user-url-state", u);
  for (const auto &kind: {std::string("T"), std::string("U"), std::string("C"), std::string("L"), std::string("I")}) {
    auto f = create(kind, "B", ByteVector(), 4, String::Latin1);
    mutate(*f, String::Latin1, "old-desc", "old-text", ByteVector("eng"), StringList("old"));
    ThrowHandler handler;
    I::Tag::setLatin1StringHandler(&handler);
    auto body = kind == "I"
                  ? ByteVector("owner\0id", 8)
                  : kind == "C" || kind == "L"
                      ? ByteVector("\0engdesc\0text", 13)
                      : ByteVector("\0one\0two", 8);
    auto ident = identifier(kind);
    bool threw = false;
    try { f->setData(wire(std::string(ident.data(), ident.size()).c_str(), body)); } catch (const std::runtime_error &
    ) { threw = true; }
    I::Tag::setLatin1StringHandler(nullptr);
    std::cout << "callback/" << kind << '\t' << threw << '\t' << handler.calls << '\n';
    state("callback/" + kind + "-state", *f);
  }
  for (const auto &description: {String(), String("desc"), String(std::string("a\0b", 3), String::UTF8)})
    for (const auto &values: {StringList(), StringList("").append("one")}) {
      I::UserTextIdentificationFrame frame(description, values);
      state("helper/user-value-ctor", frame);
      std::cout << "helper/user-value-render\t" << hex(frame.render()) << '\n';
    }
}

int main(int argc, char **argv) {
  if (argc != 2)return 2;
  std::ifstream input(argv[1]);
  std::string line;
  Utf8Handler utf8;
  while (std::getline(input, line)) {
    std::stringstream row(line);
    std::vector<std::string> f;
    std::string part;
    while (std::getline(row, part, '\t'))f.push_back(part);
    if (f.size() != 12)return 3;
    const auto &id = f[0];
    unsigned int version = static_cast<unsigned int>(std::stoull(f[3]));
    auto encoding = static_cast<String::Type>(std::stoi(f[7]));
    I::Tag::setLatin1StringHandler(f[4] == "1" ? &utf8 : nullptr);
    auto value = create(f[1], f[2], bytes(f[5]), version, encoding);
    auto live = value->header();
    state(id + "/ctor", *value);
    std::cout << id << "/render\t" << hex(value->render()) << '\t' << (live == value->header()) << '\n';
    state(id + "/after-render", *value);
    value->setData(bytes(f[6]));
    state(id + "/reparse", *value);
    mutate(*value, encoding, string(f[8]), string(f[9]), bytes(f[10]), list(f[11]));
    value->header()->setVersion(version);
    state(id + "/mutate", *value);
    std::cout << id << "/mutate-render\t" << hex(value->render()) << '\n';
    state(id + "/mutate-after", *value);
    if (auto user = dynamic_cast<I::UserTextIdentificationFrame *>(value.get())) {
      user->TextIdentificationFrame::setText(list(f[11]));
      state(id + "/base-list", *user);
    }
    auto copy = value->toStringList();
    copy.append("added");
    if (!copy.isEmpty())copy.front() = "detached";
    std::cout << id << "/detached-list\t" << fields(copy) << '\t' << fields(value->toStringList()) << '\n';
    auto data = bytes(f[5]);
    auto before = hex(data);
    I::Latin1StringHandler defaults;
    std::cout << id << "/handler\t" << text(defaults.parse(data)) << '\t' << text(utf8.parse(data)) << '\t' << (
      before == hex(data)) << '\n';
    I::Tag::setLatin1StringHandler(nullptr);
  }
  helpers();
  VirtualUrl url;
  url.setText("value");
  state("virtual/url", url);
  std::cout << "virtual/url-render\t" << hex(url.render()) << '\n';
  callbacks();
  return input.eof() ? 0 : 4;
}
