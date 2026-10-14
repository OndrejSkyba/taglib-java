// Ordinary defined TagLib MP4 operations; no native fault probes.
#include <algorithm>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <sstream>
#include <mp4file.h>
#include <mp4tag.h>
#include <mp4atom.h>
#include <mp4itemfactory.h>
#include <mp4chapterholder.h>
#include <tbytevectorstream.h>
#include <tpropertymap.h>
#include <fileref.h>
using namespace TagLib;
namespace fs = std::filesystem;

std::string hex(const ByteVector &b) {
  auto h = b.toHex();
  return std::string(h.data(), h.size());
}

std::string hs(const String &s) { return hex(s.data(String::UTF8)); }

ByteVector bytes(const fs::path &p) {
  std::ifstream f(p, std::ios::binary);
  std::string s((std::istreambuf_iterator<char>(f)), {});
  return ByteVector(s.data(), s.size());
}

std::string sl(const StringList &v) {
  std::string s;
  for (const auto &x: v)s += hs(x) + ",";
  return s;
}

std::string props(const PropertyMap &p) {
  std::string s;
  for (const auto &x: p)s += hs(x.first) + "=" + sl(x.second) + ";";
  return s + "/" + sl(p.unsupportedData());
}

std::string item(const MP4::Item &i) {
  std::ostringstream s;
  s << int(i.type()) << ":" << int(i.atomDataType()) << ":";
  switch (i.type()) {
    case MP4::Item::Type::Void: break;
    case MP4::Item::Type::Bool: s << i.toBool();
      break;
    case MP4::Item::Type::Int: s << i.toInt();
      break;
    case MP4::Item::Type::Byte: s << unsigned(i.toByte());
      break;
    case MP4::Item::Type::UInt: s << i.toUInt();
      break;
    case MP4::Item::Type::LongLong: s << i.toLongLong();
      break;
    case MP4::Item::Type::IntPair: s << i.toIntPair().first << "," << i.toIntPair().second;
      break;
    case MP4::Item::Type::StringList: s << sl(i.toStringList());
      break;
    case MP4::Item::Type::ByteVectorList: for (auto &b: i.toByteVectorList())s << hex(b) << ",";
      break;
    case MP4::Item::Type::CoverArtList: for (auto &a: i.toCoverArtList())
        s << int(a.format()) << ":" << hex(a.data()) << ",";
      break;
    case MP4::Item::Type::Stem: s << hex(i.toStem().data());
      break;
  }
  return s.str();
}

std::string tag(MP4::Tag *t) {
  std::string s;
  for (const auto &x: t->itemMap())s += hs(x.first) + "=" + item(x.second) + ";";
  return s;
}

std::string chapters(const MP4::ChapterList &v) {
  std::string s;
  for (auto &c: v)s += hs(c.title()) + ":" + std::to_string(c.startTime()) + ",";
  return s;
}

void row(const std::string &key, const std::string &value) { std::cout << key << "\t" << value << "\n"; }
std::string b(bool v) { return v ? "1" : "0"; }

std::string atom(MP4::Atom *a) {
  std::string s = hex(a->name()) + ":" + std::to_string(a->offset()) + ":" + std::to_string(a->length()) + "[";
  for (auto *c: a->children())s += atom(c) + ",";
  return s + "]";
}

class Custom : public MP4::ItemFactory {
protected:
  NameHandlerMap nameHandlerMap() const override {
    return ItemFactory::nameHandlerMap().insert("tsti", ItemHandlerType::Int).insert("tstt", ItemHandlerType::Text);
  }

  Map<ByteVector, String> namePropertyMap() const override {
    return ItemFactory::namePropertyMap().insert("tsti", "TESTINTEGER");
  }

public:
  Custom() = default;
};

void edit(MP4::File &f, int mode) {
  auto *t = f.tag();
  switch (mode) {
    case 0: t->setTitle("Title");
      t->setArtist("Artist");
      t->setAlbum("Album");
      t->setComment("Comment");
      t->setGenre("Ska");
      t->setYear(2026);
      t->setTrack(7);
      break;
    case 1: t->setItem("----:org.kde.TagLib:Foo", StringList{"Bar", "Baz"});
      t->setItem("----:com.apple.iTunes:RAW", ByteVectorList{"a", "bc"});
      t->setItem("cpil", true);
      t->setItem("tmpo", 123);
      t->setItem("trkn", MP4::Item(2, 10));
      t->setItem("disk", MP4::Item(1, 3));
      t->setItem("plID", MP4::Item(-7LL));
      t->setItem("cnID", MP4::Item(9876543210LL));
      t->setItem("rtng", MP4::Item((unsigned char) 255));
      t->setItem("rate", 80);
      t->setItem("covr", MP4::CoverArtList{
                   MP4::CoverArt(MP4::CoverArt::PNG, "foo"), MP4::CoverArt(MP4::CoverArt::JPEG, "bar")
                 });
      break;
    case 2: {
      PropertyMap p;
      p.insert("TITLE", StringList{"New", "Second"});
      p.insert("MOOD", StringList("Calm"));
      p.insert("TRACKNUMBER", StringList("5/12"));
      p.insert("BPM", StringList("120"));
      p.insert("COMPILATION", StringList("1"));
      p.insert("UNKNOWN", StringList("value"));
      row("REJECT", props(t->setProperties(p)));
      break;
    }
    case 3: t->setItem("stem", MP4::Stem("{\"version\":1}"));
      break;
    case 4: f.setNeroChapters({MP4::Chapter("First", 0), MP4::Chapter(String("日本語", String::UTF8), 1234)});
      break;
    case 5: f.setQtChapters({MP4::Chapter("First", 0), MP4::Chapter(String("日本語", String::UTF8), 1234)});
      break;
    case 6: f.setQtChapters({MP4::Chapter("First", 999), MP4::Chapter("Second", 1999)});
      break;
    case 7: f.setNeroChapters({});
      f.setQtChapters({});
      break;
  }
}

int main(int argc, char **argv) {
  fs::path root = argv[1];
  std::vector<fs::path> files;
  for (auto &x: fs::directory_iterator(root / "inputs"))files.push_back(x.path());
  std::sort(files.begin(), files.end());
  Custom custom;
  for (auto &p: files) {
    std::string k = p.filename().string();
    ByteVector input = bytes(p);
    ByteVectorStream stream(input);
    stream.seek(7);
    MP4::File f(&stream);
    row(k + "/read", b(f.isValid()) + "\t" + std::to_string(f.tell()));
    if (!f.isValid())continue;
    row(k + "/tag", tag(f.tag()));
    row(k + "/properties", props(f.properties()));
    row(k + "/common",
        hs(f.tag()->title()) + "\t" + hs(f.tag()->artist()) + "\t" + std::to_string(f.tag()->year()) + "\t" +
        std::to_string(f.tag()->track()));
    auto n = f.neroChapters();
    row(k + "/nero", chapters(n) + "\t" + std::to_string(f.tell()));
    auto q = f.qtChapters();
    row(k + "/qt", chapters(q) + "\t" + std::to_string(f.tell()));
    MP4::Atoms tree(&f);
    std::string a;
    for (auto *x: tree.atoms())a += atom(x) + ",";
    row(k + "/atoms", a + "\t" + b(tree.checkRootLevelAtoms()) + "\t" + std::to_string(f.tell()));
    for (int mode = 0; mode < 8; mode++) {
      ByteVectorStream out(input);
      MP4::File owner(&out, false);
      if (!owner.isValid())continue;
      std::string key = k + "/edit/" + std::to_string(mode);
      if (mode == 2) {
        PropertyMap v;
        v.insert("TITLE", StringList{"New", "Second"});
        v.insert("MOOD", StringList("Calm"));
        v.insert("TRACKNUMBER", StringList("5/12"));
        v.insert("BPM", StringList("120"));
        v.insert("COMPILATION", StringList("1"));
        v.insert("UNKNOWN", StringList("value"));
        row(key + "/rejected", props(owner.setProperties(v)));
      } else edit(owner, mode);
      bool saved = owner.save();
      row(key + "/save", b(saved) + "\t" + std::to_string(owner.tell()) + "\t" + hex(*out.data()));
      row(key + "/live", tag(owner.tag()) + "\t" + b(owner.hasMP4Tag()));
      if (saved) {
        MP4::File reopened(&out, false);
        row(key + "/reopen", b(reopened.isValid()) + "\t" + (reopened.isValid() ? tag(reopened.tag()) : ""));
        if (reopened.isValid()) {
          row(key + "/chapters", chapters(reopened.neroChapters()) + "/" + chapters(reopened.qtChapters()));
        }
      }
      if (k == "upstream-no-tags.m4a" || k.find("layout-") == 0) {
        fs::path dest = root / "outputs" / (k + "-" + std::to_string(mode) + ".native");
        std::ofstream sink(dest, std::ios::binary);
        auto d = out.data();
        sink.write(d->data(), d->size());
      }
    }
  }
  ByteVectorStream mem(bytes(root / "inputs/upstream-no-tags.m4a"));
  MP4::File c(&mem, true, AudioProperties::Average, &custom);
  c.tag()->setItem("tsti", 123);
  c.tag()->setItem("tstt", StringList("Test text"));
  c.save();
  row("factory/custom-save", hex(*mem.data()));
  MP4::File cr(&mem, true, AudioProperties::Average, &custom);
  row("factory/custom-read", tag(cr.tag()) + "\t" + props(cr.properties()));
  PropertyMap cp = cr.properties();
  cp["TESTINTEGER"] = StringList("456");
  cr.setProperties(cp);
  bool cs = cr.save();
  row("factory/custom-resave", b(cs) + "\t" + hex(*mem.data()));
  auto *factory = MP4::ItemFactory::instance();
  for (auto key: {"TITLE", "MOOD", "BPM", "TRACKNUMBER", "DISCNUMBER", "COMPILATION", "RATING", "ENCODING", "UNKNOWN"})
    for (auto val: {"", "0", "1", "-1", "2/10", "4294967295", "9223372036854775807", "Text"}) {
      auto result = factory->itemFromProperty(key, StringList(val));
      auto back = factory->itemToProperty(result.first, result.second);
      row(std::string("factory/") + key + "/" + val,
          hex(result.first) + "\t" + item(result.second) + "\t" + hs(back.first) + "\t" + sl(back.second));
    }
  MP4::Tag t;
  row("tag/missing-before", std::to_string(t.itemMap().size()));
  auto mi = t.itemMap().value("zzzz");
  row("tag/missing-item", item(mi) + "\t" + std::to_string(t.itemMap().size()));
}
