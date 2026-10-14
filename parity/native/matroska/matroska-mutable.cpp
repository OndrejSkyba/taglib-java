// Ordinary defined pinned Matroska operations; no native fault probes.
#include <algorithm>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <sstream>
#include <matroskafile.h>
#include <matroskatag.h>
#include <matroskachapters.h>
#include <matroskaattachments.h>
#include <matroskaelement.h>
#include <matroskaattachedfile.h>
#include <matroskachapter.h>
#include <matroskachapteredition.h>
#include <tbytevectorstream.h>
#include <tpropertymap.h>
#include <fileref.h>
using namespace TagLib;
namespace fs = std::filesystem;

std::string hx(const ByteVector &b) {
  auto h = b.toHex();
  return std::string(h.data(), h.size());
}

std::string hx(const String &s) { return hx(s.data(String::UTF8)); }
std::string b(bool v) { return v ? "1" : "0"; }
void row(const std::string &key, const std::string &v) { std::cout << key << '\t' << v << '\n'; }

ByteVector bytes(const fs::path &p) {
  std::ifstream f(p, std::ios::binary);
  std::string s((std::istreambuf_iterator<char>(f)), {});
  return ByteVector(s.data(), s.size());
}

std::string sl(const StringList &v) {
  std::string s;
  for (auto &x: v)s += hx(x) + ",";
  return s;
}

std::string props(const PropertyMap &p) {
  std::string s;
  for (auto &x: p)s += hx(x.first) + "=" + sl(x.second) + ";";
  return s + "/" + sl(p.unsupportedData());
}

std::string variant(const Variant &v) {
  std::ostringstream s;
  s << int(v.type()) << ":";
  switch (v.type()) {
    case Variant::Bool: s << v.toBool();
      break;
    case Variant::Int: s << v.toInt();
      break;
    case Variant::UInt: s << v.toUInt();
      break;
    case Variant::LongLong: s << v.toLongLong();
      break;
    case Variant::ULongLong: s << v.toULongLong();
      break;
    case Variant::String: s << hx(v.toString());
      break;
    case Variant::ByteVector: s << hx(v.toByteVector());
      break;
    case Variant::VariantList: s << "[";
      for (auto &x: v.toList())s << variant(x) << ",";
      s << "]";
      break;
    case Variant::VariantMap: s << "{";
      for (auto &x: v.toMap())s << hx(x.first) << "=" << variant(x.second) << ",";
      s << "}";
      break;
    default: s << "unsupported";
  }
  return s.str();
}

std::string st(const Matroska::SimpleTag &t) {
  return hx(t.name()) + ":" + std::to_string(t.type()) + ":" + hx(t.toString()) + ":" + hx(t.toByteVector()) + ":" +
         std::to_string(int(t.targetTypeValue())) + ":" + hx(t.language()) + ":" + b(t.defaultLanguageFlag()) + ":" +
         std::to_string(t.trackUid()) + ":" + std::to_string(t.editionUid()) + ":" + std::to_string(t.chapterUid()) +
         ":" + std::to_string(t.attachmentUid());
}

std::string tag(Matroska::Tag *t) {
  std::string s;
  for (auto &v: t->simpleTagsList())s += st(v) + ";";
  return s + "/" + props(t->properties()) + "/" + hx(t->title()) + "," + hx(t->artist()) + "," + hx(t->album()) + "," +
         hx(t->comment()) + "," + hx(t->genre()) + "," + std::to_string(t->year()) + "," + std::to_string(t->track());
}

std::string complex(TagLib::File &f) {
  std::string s;
  for (auto &k: f.complexPropertyKeys()) {
    s += hx(k) + "=";
    for (auto &m: f.complexProperties(k))s += variant(Variant(m)) + ",";
    s += ";";
  }
  return s;
}

std::string dump(Matroska::File &f) { return tag(f.tag(true)) + "/" + complex(f) + "/" + std::to_string(f.tell()); }

PropertyMap propertyEdit() {
  PropertyMap p;
  for (auto k: {
         "ALBUM", "ALBUMARTIST", "ALBUMARTISTSORT", "ALBUMSORT", "ARTIST", "ARTISTS", "ARTISTSORT", "ASIN", "BARCODE",
         "CATALOGNUMBER", "COMMENT", "DATE", "DISCNUMBER", "DISCTOTAL", "DJMIXER", "ENCODEDBY", "ENCODING",
         "ENCODINGTIME", "GENRE", "INITIALKEY", "ISRC", "LABEL", "MEDIA", "MUSICBRAINZ_ALBUMARTISTID",
         "MUSICBRAINZ_ALBUMID", "MUSICBRAINZ_ARTISTID", "MUSICBRAINZ_RELEASEGROUPID", "MUSICBRAINZ_RELEASETRACKID",
         "MUSICBRAINZ_TRACKID", "OWNER", "ORIGINALDATE", "RELEASECOUNTRY", "RELEASEDATE", "RELEASESTATUS",
         "RELEASETYPE", "REMIXER", "SCRIPT", "TAGGINGDATE", "TITLE", "TRACKNUMBER", "TRACKTOTAL", "BPM", "MOOD",
         "UNKNOWN"
       })
    p.insert(k, StringList{"First", "Second"});
  return p;
}

void edit(Matroska::File &f, int mode) {
  auto *t = f.tag(true);
  switch (mode) {
    case 0: t->setTitle("Title");
      t->setArtist("Artist");
      t->setAlbum("Album");
      t->setComment("Comment");
      t->setGenre("Ska");
      t->setYear(2026);
      t->setTrack(7);
      break;
    case 1: t->addSimpleTag(Matroska::SimpleTag("BIN", ByteVector("\0\xff", 2), Matroska::SimpleTag::Collection, "jpn",
                                                false, 1, 2, 3, 4));
      t->insertSimpleTag(0, Matroska::SimpleTag("FIRST", String("Value")));
      t->insertSimpleTag(0xffffffffU, Matroska::SimpleTag("LAST", String("End")));
      t->removeSimpleTag(1);
      t->removeSimpleTag("MISSING", Matroska::SimpleTag::None);
      break;
    case 2: f.setProperties(propertyEdit());
      break;
    case 3: {
      Matroska::Chapter c(123000, 999000, {{"Chapter", "eng"}, {"", "jpn"}}, 501, true);
      auto *ch = f.chapters(true);
      ch->addChapterEdition(Matroska::ChapterEdition({c}, true, true, 500));
      ch->removeChapterEdition(0xffffffffffffffffULL);
      break;
    }
    case 4: {
      auto *a = f.attachments(true);
      a->addAttachedFile(
        Matroska::AttachedFile(ByteVector("\0\xff" "DOC", 5), "doc.bin", "application/bin", 601, "Doc"));
      a->addAttachedFile(Matroska::AttachedFile("PNG", "cover.png", "image/png", 602, "Front"));
      a->removeAttachedFile(0xffffffffffffffffULL);
      break;
    }
    case 5: t->clearSimpleTags();
      if (f.chapters())f.chapters()->clear();
      if (f.attachments())f.attachments()->clear();
      break;
    case 6: {
      VariantMap m;
      m.insert("data", ByteVector("\x89PNG\x0d\x0a\x1a\x0a", 8));
      m.insert("uid", 701ULL);
      m.insert("description", "Picture");
      f.setComplexProperties("picture", {m});
      break;
    }
    case 7: {
      VariantMap m;
      m.insert("name", "BINARY");
      m.insert("data", ByteVector("\0\xff", 2));
      m.insert("targetTypeValue", 50U);
      m.insert("trackUid", 42ULL);
      m.insert("editionUid", 43ULL);
      m.insert("chapterUid", 44ULL);
      m.insert("attachmentUid", 45ULL);
      m.insert("language", "ces");
      m.insert("defaultLanguage", false);
      f.setComplexProperties("BINARY", {m});
      break;
    }
    case 8: {
      VariantMap d;
      d.insert("string", "Chapter");
      d.insert("language", "ces");
      VariantMap c;
      c.insert("uid", 802ULL);
      c.insert("timeStart", 123000ULL);
      c.insert("timeEnd", 999000ULL);
      c.insert("isHidden", true);
      c.insert("displays", VariantList{Variant(d)});
      VariantMap e;
      e.insert("uid", 801ULL);
      e.insert("isDefault", true);
      e.insert("isOrdered", true);
      e.insert("chapters", VariantList{Variant(c)});
      f.setComplexProperties("chapters", {e});
      break;
    }
    case 9: t->setTitle(String(std::string(900, 'X'), String::UTF8));
      break;
    case 10: t->setTitle("s");
      break;
    case 11: break;
    case 12: t->addSimpleTag(Matroska::SimpleTag("CUSTOM", String("Value"), Matroska::SimpleTag::Album));
      t->addSimpleTag(Matroska::SimpleTag("CUSTOM", String("Scoped"), Matroska::SimpleTag::None, "", true, 42));
      t->removeUnsupportedProperties({"CUSTOM"});
      break;
  }
}

int main(int argc, char **argv) {
  if (argc != 2)return 2;
  fs::path root = argv[1];
  std::vector<fs::path> files;
  for (auto &x: fs::directory_iterator(root / "inputs"))files.push_back(x.path());
  std::sort(files.begin(), files.end());
  for (auto &p: files) {
    auto raw = bytes(p);
    auto name = p.filename().string();
    for (int style = 0; style < 3; style++)
      for (bool audio: {false, true})
        for (int origin: {0, 7}) {
          ByteVector data(origin, 'p');
          data.append(raw);
          ByteVectorStream s(data);
          s.seek(origin);
          Matroska::File f(&s, audio, AudioProperties::ReadStyle(style));
          std::string k = name + "/read/" + std::to_string(style) + "/" + b(audio) + "/" + std::to_string(origin);
          if (!f.isValid()) {
            row(k + "/rejected", "1");
            continue;
          }
          row(k, b(f.isValid()) + "\t" + std::to_string(f.tell()) + "\t" + b(f.tag(false) != nullptr) + "\t" +
                 b(f.chapters() != nullptr) + "\t" + b(f.audioProperties() != nullptr));
          if (f.isValid())row(k + "/state", dump(f));
        }
    if (name == "read-large.mka")continue;
    {
      ByteVectorStream source(raw);
      Matroska::File check(&source, false);
      if (!check.isValid())continue;
    }
    for (int style = 0; style < 3; style++)
      for (int mode = 0; mode < 13; mode++) {
        ByteVectorStream s(raw);
        Matroska::File f(&s, false);
        std::string k = name + "/edit/" + std::to_string(style) + "/" + std::to_string(mode);
        edit(f, mode);
        row(k + "/before", dump(f));
        for (int round = 0; round < 3; round++) {
          if (round == 1 && mode == 9)f.tag(true)->setTitle("s");
          if (round == 2 && mode == 9)f.tag(true)->setTitle(String(std::string(1200, 'Y'), String::UTF8));
          bool saved = f.save(Matroska::WriteStyle(style));
          row(k + "/save/" + std::to_string(round), b(saved) + "\t" + std::to_string(f.tell()) + "\t" + hx(*s.data()));
          row(k + "/live/" + std::to_string(round), dump(f));
        }
        s.seek(0);
        Matroska::File reopened(&s, false);
        row(k + "/reopen", b(reopened.isValid()) + "\t" + (reopened.isValid() ? dump(reopened) : ""));
        if (name.find("upstream-") == 0 || name == "layout-before-0-1.mka") {
          std::ofstream out(
            root / "outputs" / (name + "-" + std::to_string(style) + "-" + std::to_string(mode) + ".native"),
            std::ios::binary);
          out.write(s.data()->data(), s.data()->size());
        }
      }
  }
  for (int target: {0, 10, 20, 30, 40, 50, 60, 70, 999})
    for (int binary = 0; binary < 2; binary++)
      for (
        int scope = 0; scope < 5; scope++) {
        Matroska::SimpleTag t = binary
                                  ? Matroska::SimpleTag("TITLE", ByteVector("\0\xff", 2),
                                                        Matroska::SimpleTag::TargetTypeValue(target), "ces", false,
                                                        scope == 1 ? 1 : 0, scope == 2 ? 2 : 0, scope == 3 ? 3 : 0,
                                                        scope == 4 ? 4 : 0)
                                  : Matroska::SimpleTag("TITLE", String("Value"),
                                                        Matroska::SimpleTag::TargetTypeValue(target), "ces", false,
                                                        scope == 1 ? 1 : 0, scope == 2 ? 2 : 0, scope == 3 ? 3 : 0,
                                                        scope == 4 ? 4 : 0);
        Matroska::Tag tag;
        tag.addSimpleTag(t);
        std::string key = "value/" + std::to_string(target) + "/" + std::to_string(binary) + "/" +
                          std::to_string(scope);
        row(key, st(t) + "\t" + ::tag(&tag));
        auto copy = t;
        row(key + "/copy", st(t) + "/" + st(copy));
      }
}
