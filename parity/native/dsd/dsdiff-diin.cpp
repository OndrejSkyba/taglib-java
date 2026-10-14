// Independent pinned TagLib DSDIFF::DIIN::Tag operation protocol.
#include <fstream>
#include <iostream>
#include <sstream>
#include <vector>
#include "dsdiffdiintag.h"
#include "tbytevector.h"
#include "tpropertymap.h"
#include "tagunion.h"
using namespace TagLib;

std::string hex(const String &s) {
  auto b = s.data(String::UTF16BE).toHex();
  return {b.data(), b.size()};
}

String text(const std::string &raw) {
  std::wstring s;
  for (size_t i = 0; i < raw.size(); i += 4)
    s.
        push_back(static_cast<wchar_t>(std::stoul(raw.substr(i, 4), nullptr, 16)));
  return String(s);
}

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

void state(const std::string &id, const TagLib::Tag &t) {
  std::cout << id << '\t' << t.isEmpty() << '\t' << hex(t.title()) << '\t' << hex(t.artist()) << '\t' << hex(t.album())
      << '\t' << hex(t.comment()) << '\t' << hex(t.genre()) << '\t' << t.year() << '\t' << t.track() << '\t' <<
      props(t.properties()) << '\t' << props(t.TagLib::Tag::properties()) << '\n';
}

struct Custom : DSDIFF::DIIN::Tag {
  int calls = 0;
  String title() const override { return "custom getter"; }

  void setTitle(const String &s) override {
    ++calls;
    DSDIFF::DIIN::Tag::setTitle(String("custom:") + s);
  }
};

int main(int argc, char **argv) {
  std::ifstream in(std::string(argv[1]) + "/cases.tsv");
  std::string line;
  DSDIFF::DIIN::Tag empty;
  state("empty", empty);
  while (std::getline(in, line)) {
    std::istringstream row(line);
    std::vector<std::string> v;
    std::string f;
    while (std::getline(row, f, '\t'))v.push_back(f);
    auto id = v[0];
    auto title = text(v[1]), artist = text(v[2]);
    unsigned nt = std::stoul(v[3]), na = std::stoul(v[4]);
    DSDIFF::DIIN::Tag t;
    t.setTitle(title);
    t.setArtist(artist);
    state(id + "/stored", t);
    auto snapshot = t.properties();
    t.setTitle("later");
    std::cout << id << "/snapshot\t" << props(snapshot) << '\n';
    // Two fields are retained; the five unsupported common setters leave them unchanged.
    t.setTitle(title);
    t.setAlbum("unsupported");
    t.setComment(title);
    t.setGenre(artist);
    t.setYear(0xffffffff);
    t.setTrack(0x80000000);
    state(id + "/unsupported", t);
    PropertyMap input;
    StringList titles, artists;
    for (unsigned n = 0; n < nt; ++n)
      titles.append(n
                      ? String(
                        title.toWString() + std::wstring(L":") + std::to_wstring(n))
                      : title);
    for (unsigned n = 0; n < na; ++n)
      artists.append(
        n ? String(artist.toWString() + std::wstring(L":") + std::to_wstring(n)) : artist);
    input.insert("TITLE", titles);
    input.insert("ARTIST", artists);
    input.insert("ALBUM", {"album", "album2"});
    input.insert("EMPTY", {});
    input.insert("DATE", {"-1"});
    input.insert("CUSTOM", {"x", ""});
    input.insert(text("00e4006b00650079"), {"unicode key"});
    input.addUnsupportedData("opaque");
    input.addUnsupportedData("TITLE");
    input.addUnsupportedData("opaque");
    auto rejected = t.setProperties(input);
    std::cout << id << "/import\t" << props(rejected) << '\t' << props(input) << '\n';
    state(id + "/imported", t);
    t.removeUnsupportedProperties({"TITLE", "ARTIST", "opaque"});
    std::cout << id << "/complex\t" << list(t.complexPropertyKeys()) << '\t' << t.complexProperties("PICTURE").size() <<
        '\t' << t.setComplexProperties("PICTURE", {}) << '\t' << props(t.properties()) << '\n';
    DSDIFF::DIIN::Tag source;
    source.setTitle(title);
    source.setArtist(artist);
    Tag::duplicate(&source, &t, false);
    state(id + "/duplicate-fill", t);
    Tag::duplicate(&source, &t);
    state(id + "/duplicate-overwrite", t);
    t.setProperties(PropertyMap());
    state(id + "/cleared", t);
  }
  Custom custom;
  custom.setTitle("direct");
  state("custom/direct", custom);
  PropertyMap p;
  p.insert("TITLE", {"imported", "rejected"});
  std::cout << "custom/import\t" << props(custom.setProperties(p)) << '\t' << custom.calls << '\n';
  state("custom/imported", custom);
  TagUnion u(new DSDIFF::DIIN::Tag, new DSDIFF::DIIN::Tag);
  auto *a = dynamic_cast<DSDIFF::DIIN::Tag *>(u.tag(0));
  auto *b = dynamic_cast<DSDIFF::DIIN::Tag *>(u.tag(1));
  state("union/empty", u);
  b->setTitle("second");
  b->setArtist("artist");
  state("union/fallback", u);
  a->setArtist("first");
  state("union/map-primary", u);
  u.setTitle("broadcast");
  state("union/broadcast", u);
  state("union/child0", *a);
  state("union/child1", *b);
}
