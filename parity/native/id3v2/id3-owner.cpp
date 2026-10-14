// Independent pinned TagLib live Tag/factory/nested-frame protocol.
#include <fstream>
#include <iostream>
#include <memory>
#include <sstream>
#include <string>
#include <vector>
#include "id3v2tag.h"
#include "id3v2header.h"
#include "id3v2synchdata.h"
#include "id3v2extendedheader.h"
#include "id3v2framefactory.h"
#include "chapterframe.h"
#include "tableofcontentsframe.h"
#include "attachedpictureframe.h"
#include "commentsframe.h"
#include "relativevolumeframe.h"
#include "textidentificationframe.h"
#include "uniquefileidentifierframe.h"
#include "unknownframe.h"
#include "generalencapsulatedobjectframe.h"
#include "urllinkframe.h"
#include "unsynchronizedlyricsframe.h"
#include "popularimeterframe.h"
#include "privateframe.h"
#include "ownershipframe.h"
#include "synchronizedlyricsframe.h"
#include "eventtimingcodesframe.h"
#include "podcastframe.h"
#include "tbytevectorstream.h"
#include "tfile.h"
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

static std::string map(const I::FrameListMap &m) {
  std::string out;
  for (const auto &[k,v]: m)if (!v.isEmpty())out += hex(k) + ":" + std::to_string(v.size()) + ",";
  return out;
}

class Factory : public I::FrameFactory {
public:
  mutable int raw = 0, typed = 0, update = 0, aggregate = 0;

  Factory() = default;

  I::Frame *createFrame(const ByteVector &d, const I::Header *h) const override {
    ++raw;
    return FrameFactory::createFrame(d, h);
  }

  I::Frame *createFrame(const ByteVector &d, I::Frame::Header *h, const I::Header *t) const override {
    ++typed;
    return FrameFactory::createFrame(d, h, t);
  }

  bool updateFrame(I::Frame::Header *h) const override {
    ++update;
    return FrameFactory::updateFrame(h);
  }

  void rebuildAggregateFrames(I::Tag *t) const override {
    ++aggregate;
    FrameFactory::rebuildAggregateFrames(t);
  }

  auto prepare(ByteVector &d, const I::Header *h) const { return prepareFrameHeader(d, h); }
};

class Utf8 : public I::Latin1StringHandler {
public:
  String parse(const ByteVector &d) const override { return String(d, String::UTF8); }
};

class BareFile : public File {
public:
  explicit BareFile(IOStream *s) : File(s) {
  }

  TagLib::Tag *tag() const override { return nullptr; }
  AudioProperties *audioProperties() const override { return nullptr; }
  bool save() override { return false; }
};

class LiveTag : public I::Tag {
public:
  LiveTag(File *f, offset_t o, const I::FrameFactory *c) : I::Tag(f, o, c) {
  }

  void parseBody(const ByteVector &d) { parse(d); }
};

static std::string kind(I::Frame *f) {
  if (!f)return "null";
  if (dynamic_cast<I::UserTextIdentificationFrame *>(f))return "TXXX";
  if (dynamic_cast<I::TextIdentificationFrame *>(f))return "TEXT";
  if (dynamic_cast<I::AttachedPictureFrame *>(f))return "APIC";
  if (dynamic_cast<I::CommentsFrame *>(f))return "COMM";
  if (dynamic_cast<I::RelativeVolumeFrame *>(f))return "RVA2";
  if (dynamic_cast<I::UniqueFileIdentifierFrame *>(f))return "UFID";
  if (dynamic_cast<I::GeneralEncapsulatedObjectFrame *>(f))return "GEOB";
  if (dynamic_cast<I::UserUrlLinkFrame *>(f))return "WXXX";
  if (dynamic_cast<I::UrlLinkFrame *>(f))return "URL";
  if (dynamic_cast<I::UnsynchronizedLyricsFrame *>(f))return "USLT";
  if (dynamic_cast<I::SynchronizedLyricsFrame *>(f))return "SYLT";
  if (dynamic_cast<I::EventTimingCodesFrame *>(f))return "ETCO";
  if (dynamic_cast<I::PopularimeterFrame *>(f))return "POPM";
  if (dynamic_cast<I::PrivateFrame *>(f))return "PRIV";
  if (dynamic_cast<I::OwnershipFrame *>(f))return "OWNE";
  if (dynamic_cast<I::ChapterFrame *>(f))return "CHAP";
  if (dynamic_cast<I::TableOfContentsFrame *>(f))return "CTOC";
  if (dynamic_cast<I::PodcastFrame *>(f))return "PCST";
  return "UNKNOWN";
}

static std::string frame(I::Frame *f) {
  if (!f)return "null";
  auto h = f->header();
  std::ostringstream o;
  o << kind(f) << ":" << hex(f->frameID()) << ":" << f->size() << ":" << h->version() << ":" << h->
      tagAlterPreservation() << h->fileAlterPreservation() << h->readOnly() << h->groupingIdentity() << h->compression()
      << h->encryption() << h->unsynchronisation() << h->dataLengthIndicator() << ":" << text(f->toString()) << ":" <<
      fields(f->toStringList());
  if (auto t = dynamic_cast<I::TextIdentificationFrame *>(f))o << ":e=" << t->textEncoding();
  if (auto t = dynamic_cast<I::CommentsFrame *>(f))o << ":e=" << t->textEncoding();
  if (auto t = dynamic_cast<I::AttachedPictureFrame *>(f))o << ":e=" << t->textEncoding();
  if (auto t = dynamic_cast<I::GeneralEncapsulatedObjectFrame *>(f))o << ":e=" << t->textEncoding();
  if (auto t = dynamic_cast<I::UserUrlLinkFrame *>(f))o << ":e=" << t->textEncoding();
  if (auto t = dynamic_cast<I::UnsynchronizedLyricsFrame *>(f))o << ":e=" << t->textEncoding();
  if (auto t = dynamic_cast<I::SynchronizedLyricsFrame *>(f))o << ":e=" << t->textEncoding();
  if (auto t = dynamic_cast<I::OwnershipFrame *>(f))o << ":e=" << t->textEncoding();
  if (auto u = dynamic_cast<I::UnknownFrame *>(f))o << ":u=" << hex(u->data());
  if (auto c = dynamic_cast<I::ChapterFrame *>(f)) {
    o << ":c=" << hex(c->elementID()) << "," << c->startTime() << "," << c->endTime() << "," << c->startOffset() << ","
        << c->endOffset() << "," << map(c->embeddedFrameListMap()) << "[";
    for (auto child: c->embeddedFrameList())o << frame(child) << ";";
    o << "]";
  }
  if (auto c = dynamic_cast<I::TableOfContentsFrame *>(f)) {
    o << ":c=" << hex(c->elementID()) << "," << c->isTopLevel() << c->isOrdered() << "," << c->entryCount() << ",";
    for (const auto &id: c->childElements())o << hex(id) << "/";
    o << "," << map(c->embeddedFrameListMap()) << "[";
    for (auto child: c->embeddedFrameList())o << frame(child) << ";";
    o << "]";
  }
  return o.str();
}

// Compare populated metadata; native getters can leave empty buckets behind.
static StringList populatedComplexKeys(I::Tag &tag) {
  StringList result;
  for (const auto &key: tag.complexPropertyKeys())
    if (!tag.frameList(key == "PICTURE" ? "APIC" : "GEOB").isEmpty())
      result.append(key);
  return result;
}

static void state(const std::string &id, I::Tag &t, Factory &c, BareFile &file) {
  // Getters are intentionally observed before the map, including their empty-bucket insertions.
  auto title = t.title(), artist = t.artist(), album = t.album(), comment = t.comment(), genre = t.genre();
  auto year = t.year(), track = t.track();
  auto h = t.header();
  std::cout << id << '\t' << h->majorVersion() << ":" << h->tagSize() << ":" << h->completeTagSize() << ":" << h->
      unsynchronisation() << h->extendedHeader() << h->experimentalIndicator() << h->footerPresent() << ":" << (
        t.extendedHeader() ? std::to_string(t.extendedHeader()->size()) : "-") << '\t' << file.tell() << '\t' << c.raw
      << ":" << c.typed << ":" << c.update << ":" << c.aggregate << '\t' << text(title) << ":" << text(artist) << ":" <<
      text(album) << ":" << text(comment) << ":" << text(genre) << ":" << year << ":" << track << ":" << t.isEmpty() <<
      '\t' << map(t.frameListMap()) << '\t';
  for (auto f: t.frameList())std::cout << frame(f) << ";";
  std::cout << '\t' << props(t.properties()) << '\t' << fields(populatedComplexKeys(t)) << '\n';
}

static void owner(const std::vector<std::string> &p, Factory &c) {
  auto source = bytes(p[6]);
  ByteVectorStream stream(source);
  BareFile file(&stream);
  LiveTag t(&file, 3, &c);
  auto id = p[0];
  state(id + "/ctor", t, c, file);
  std::cout << id << "/render4\t" << hex(t.render(I::v4)) << '\n';
  state(id + "/after4", t, c, file);
  std::cout << id << "/initial3\t" << hex(t.render(I::v3)) << '\n';
  state(id + "/initial-after3", t, c, file);
  std::cout << id << "/back4\t" << hex(t.render(I::v4)) << '\n';
  state(id + "/back-after4", t, c, file);
  t.setTitle(string("6e6577007469746c65c3a9"));
  t.setArtist("artist");
  t.setAlbum("");
  t.setComment("comment");
  t.setGenre("14");
  t.setYear(0xffffffff);
  t.setTrack(0x80000000);
  state(id + "/common", t, c, file);
  PropertyMap props;
  props.insert("TITLE", StringList{"new title", "second"});
  props.insert("COMMENT:X", StringList{"comment X"});
  props.insert("ARRANGER", StringList{"a", "b"});
  props.insert("PERFORMER:GUITAR", StringList{"g"});
  props.insert("MUSICBRAINZ_TRACKID", StringList{"track-id"});
  props.insert("URL", StringList{"https://example.test"});
  props.insert("EMPTY", StringList{});
  std::cout << id << "/set-props\t" << ::props(t.setProperties(props)) << '\n';
  state(id + "/properties", t, c, file);
  List<VariantMap> pictures;
  VariantMap picture;
  picture.insert("data", ByteVector("payload"));
  picture.insert("description", String("desc"));
  picture.insert("mimeType", String("image/test"));
  picture.insert("pictureType", String("Front Cover"));
  pictures.append(picture);
  VariantMap wrong;
  wrong.insert("data", 1);
  wrong.insert("description", false);
  pictures.append(wrong);
  auto pResult = t.setComplexProperties("picture", pictures);
  auto gResult = t.setComplexProperties("generalobject", pictures);
  auto nResult = t.setComplexProperties("none", pictures);
  std::cout << id << "/complex\t" << pResult << gResult << nResult << '\t';
  for (const auto &key: StringList{"PICTURE", "GENERALOBJECT", "none"}) {
    auto v = t.complexProperties(key);
    std::cout << v.size() << ":";
    for (const auto &m: v)
      for (const auto &[k,value]: m) {
        std::cout << text(k) << "=" << value.type() << ":";
        if (value.type() == Variant::ByteVector)std::cout << hex(value.toByteVector());
        else std::cout << text(value.toString());
        std::cout << ";";
      }
  }
  std::cout << '\n';
  state(id + "/complex-state", t, c, file);
  t.removeUnsupportedProperties(StringList{"UNKNOWN/ZZZZ", "APIC", "COMM!X", "UFID/http://musicbrainz.org", "CHAP/ch"});
  state(id + "/removed", t, c, file);
  std::cout << id << "/render3\t" << hex(t.render(I::v3)) << '\n';
  state(id + "/after3", t, c, file);
  t.parseBody(source.mid(13));
  state(id + "/append-parse", t, c, file);
  std::cout << id << "/repeat3\t" << hex(t.render(I::v3)) << '\n';
}

static int constructingHooks = 0;

class ChapterChild : public I::ChapterFrame {
public:
  ChapterChild(const I::Header *h, const ByteVector &d) : I::ChapterFrame(h, d) {
  }

protected:
  void parseFields(const ByteVector &d) override {
    ++constructingHooks;
    ChapterFrame::parseFields(d);
  }
};

class TocChild : public I::TableOfContentsFrame {
public:
  TocChild(const I::Header *h, const ByteVector &d) : I::TableOfContentsFrame(h, d) {
  }

protected:
  void parseFields(const ByteVector &d) override {
    ++constructingHooks;
    TableOfContentsFrame::parseFields(d);
  }
};

class Throw : public I::Latin1StringHandler {
public:
  mutable int calls = 0;

  String parse(const ByteVector &) const override {
    if (++calls == 5)throw std::runtime_error("callback");
    return String("c");
  }
};

class BucketHook : public I::Frame {
  I::Tag &t;
  std::string name;
  bool add;
  std::vector<std::string> &visited;

public:
  BucketHook(I::Tag &t, const char *id, bool add, std::vector<std::string> &visited) : Frame(id), t(t), name(id),
    add(add), visited(visited) {
  }

  String toString() const override { return ""; }

protected:
  void parseFields(const ByteVector &) override {
  }

  ByteVector renderFields() const override { return "x"; }

  PropertyMap asProperties() const override {
    visited.push_back(name);
    if (add) {
      t.frameList("A000");
      t.frameList("ZZZZ");
      t.addFrame(new BucketHook(t, "X001", false, visited));
      t.addFrame(new BucketHook(t, "Z999", false, visited));
    }
    return {};
  }
};

static void helpers() {
  I::Header h;
  auto leaf = bytes("544954320000000200000061");
  auto chap = bytes("4348415000000020000063000000000100000002ffffffffffffffff") + leaf;
  auto toc = bytes("43544f43000000140000740003016300") + leaf;
  constructingHooks = 0;
  ChapterChild c(&h, chap);
  std::cout << "helper/chapter-constructor\t" << constructingHooks << '\t' << frame(&c) << '\n';
  c.setData(chap);
  std::cout << "helper/chapter-reparse\t" << constructingHooks << '\t' << frame(&c) << '\n';
  constructingHooks = 0;
  TocChild t(&h, toc);
  std::cout << "helper/toc-constructor\t" << constructingHooks << '\t' << frame(&t) << '\n';
  t.setData(toc);
  std::cout << "helper/toc-reparse\t" << constructingHooks << '\t' << frame(&t) << '\n';
  I::Tag tag;
  const auto &list = tag.frameList();
  const auto &mapped = tag.frameListMap();
  auto f = new I::TextIdentificationFrame("TIT2");
  f->setText("a");
  tag.addFrame(f);
  const auto &bucket = tag.frameList("TIT2");
  std::cout << "helper/live-add\t" << list.size() << ":" << bucket.size() << ":" << mapped.size() << ":" << (
    list.front() == f) << ":" << (&bucket == &tag.frameList("TIT2")) << '\n';
  f->header()->setFrameID("TPE1");
  std::cout << "helper/live-id\t" << text(tag.title()) << ":" << text(tag.artist()) << ":" << map(mapped) << '\n';
  f->header()->setFrameID("TIT2");
  tag.removeFrame(f, false);
  std::cout << "helper/live-remove\t" << list.size() << ":" << bucket.size() << ":" << text(f->toString()) << '\n';
  delete f;
  const auto &embedded = c.embeddedFrameList();
  auto old = embedded.front();
  c.removeEmbeddedFrame(old, false);
  std::cout << "helper/nested-remove\t" << embedded.size() << ":" << map(c.embeddedFrameListMap()) << ":" <<
      text(old->toString()) << '\n';
  delete old;
  t.addChildElement(ByteVector("x\0", 2));
  t.removeChildElement("x");
  t.setIsTopLevel(true);
  t.setIsOrdered(false);
  std::cout << "helper/toc-setters\t" << frame(&t) << '\t' << hex(t.render()) << '\n';
  auto nested = chap;
  for (int depth = 0; depth < 65; ++depth) {
    ByteVector body = bytes("63000000000100000002ffffffffffffffff") + nested;
    nested = ByteVector("CHAP") + I::SynchData::fromUInt(body.size()) + ByteVector(2U) + body;
  }
  Throw fail;
  I::Tag::setLatin1StringHandler(&fail);
  bool threw = false;
  try { std::unique_ptr<I::Frame> parsed(I::FrameFactory::instance()->createFrame(nested, &h)); } catch (const
    std::runtime_error &) { threw = true; }
  I::Tag::setLatin1StringHandler(nullptr);
  std::unique_ptr<I::Frame> parsed(I::FrameFactory::instance()->createFrame(nested, &h));
  std::cout << "helper/depth-throw\t" << threw << ":" << fail.calls << '\t' << frame(parsed.get()) << '\n';
  I::Tag hooks;
  std::vector<std::string> visited;
  hooks.addFrame(new BucketHook(hooks, "X001", true, visited));
  hooks.addFrame(new BucketHook(hooks, "Y001", false, visited));
  hooks.setProperties({});
  std::cout << "helper/property-hook-insertion\t";
  for (const auto &v: visited)std::cout << v << ",";
  std::cout << '\t' << hooks.frameList().size() << ":" << map(hooks.frameListMap()) << '\n';
  I::Tag iterations;
  auto a = new I::TextIdentificationFrame("TIT2");
  a->setText("a");
  auto b = new I::TextIdentificationFrame("TIT2");
  b->setText("b");
  iterations.addFrame(a);
  iterations.addFrame(b);
  auto it = iterations.frameList().begin();
  ++it;
  iterations.removeFrame(a, false);
  delete a;
  auto added = new I::TextIdentificationFrame("TIT2");
  added->setText("c");
  iterations.addFrame(added);
  std::cout << "helper/stable-list-iterator\t" << text((*it)->toString());
  ++it;
  std::cout << ":" << text((*it)->toString());
  ++it;
  std::cout << ":" << (it == iterations.frameList().end()) << '\n';
}

int main(int argc, char **argv) {
  if (argc != 2)return 2;
  std::ifstream input(argv[1]);
  std::string line;
  Utf8 handler;
  while (std::getline(input, line)) {
    std::stringstream row(line);
    std::vector<std::string> p;
    std::string part;
    while (std::getline(row, part, '\t'))p.push_back(part);
    if (p.size() != 7)return 3;
    Factory c;
    if (p[3] != "-1")c.setDefaultTextEncoding(static_cast<String::Type>(std::stoi(p[3])));
    I::Tag::setLatin1StringHandler(p[4] == "1" ? &handler : nullptr);
    if (p[1] == "T")owner(p, c);
    else if (p[1] == "P") {
      StringList values;
      for (const auto &v: ByteVectorList::split(bytes(p[6]), ByteVector("|")))
        values.append(
          String(std::string(v.data(), v.size()), String::UTF8));
      std::unique_ptr<I::Frame> f(c.createFrameForProperty(string(p[5]), values));
      std::cout << p[0] << "/property\t" << frame(f.get()) << '\n';
      std::cout << p[0] << "/render\t" << hex(f->render()) << '\n';
      I::Tag t;
      t.addFrame(f.get());
      std::cout << p[0] << "/props\t" << props(t.properties()) << '\n';
      t.removeFrame(f.get(), false);
    } else {
      I::Header tagHeader;
      auto hd = ByteVector("ID3");
      hd.append(static_cast<char>(std::stoi(p[2])));
      hd.append('\0');
      hd.append(static_cast<char>(std::stoi(p[5])));
      hd.append(ByteVector(4U));
      tagHeader.setData(hd);
      tagHeader.setMajorVersion(static_cast<unsigned int>(std::stoul(p[2])));
      auto d = bytes(p[6]);
      auto prepared = d;
      auto [h,ok] = c.prepare(prepared, &tagHeader);
      std::cout << p[0] << "/prepare\t" << ok << '\t' << (h
                                                            ? hex(h->frameID()) + ":" + std::to_string(h->frameSize()) +
                                                              ":" + std::to_string(h->version()) + ":" + std::to_string(
                                                                h->tagAlterPreservation())
                                                            : "null") << '\t' << hex(prepared) << '\t' << c.update <<
          '\n';
      delete h;
      std::unique_ptr<I::Frame> f(c.createFrame(d, &tagHeader));
      std::cout << p[0] << "/ctor\t" << frame(f.get()) << '\t' << c.raw << ":" << c.typed << ":" << c.update << '\n';
      if (f) {
        auto old = f->header();
        std::cout << p[0] << "/render\t" << hex(f->render()) << '\t' << (old == f->header()) << '\n';
        std::cout << p[0] << "/after\t" << frame(f.get()) << '\n';
        if (dynamic_cast<I::ChapterFrame *>(f.get()) || dynamic_cast<I::TableOfContentsFrame *>(f.get())) {
          f->setData(f->render());
          std::cout << p[0] << "/append-reparse\t" << frame(f.get()) << '\n';
          std::cout << p[0] << "/append-render\t" << hex(f->render()) << '\n';
        }
      }
    }
    I::Tag::setLatin1StringHandler(nullptr);
  }
  helpers();
  return input.eof() ? 0 : 4;
}
