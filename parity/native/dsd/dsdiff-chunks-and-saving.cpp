// TagLib DSDIFF common mutation/source-helper contracts.
#include <iostream>
#include <string>
#include <iomanip>
#include "fileref.h"
#include "tag.h"
#include "tpropertymap.h"

static std::string hex(const TagLib::String &s) {
  std::string x = s.to8Bit(true), out;
  const char *d = "0123456789abcdef";
  for (unsigned char c: x) {
    out += d[c >> 4];
    out += d[c & 15];
  }
  return out;
}

static void dump(TagLib::FileRef &f) {
  if (f.isNull()) {
    std::cout << "valid=0\n";
    return;
  }
  auto *t = f.tag();
  std::cout << "valid=1\nempty=" << t->isEmpty() << "\ntitle=" << hex(t->title()) << "\nartist=" << hex(t->artist()) <<
      "\nalbum=" << hex(t->album()) << "\ncomment=" << hex(t->comment()) << "\ngenre=" << hex(t->genre()) << "\nyear="
      << t->year() << "\ntrack=" << t->track() << '\n';
  auto p = f.properties();
  for (auto const &[k,v]: p) {
    std::cout << "P\t" << hex(k);
    for (auto const &x: v)std::cout << '\t' << hex(x);
    std::cout << '\n';
  }
  for (auto const &x: p.unsupportedData())std::cout << "U\t" << hex(x) << '\n';
}

int main(int argc, char **argv) {
  if (argc != 4)return 2;
  {
    TagLib::FileRef f(argv[1], false);
    dump(f);
    if (f.isNull())return 0;
    std::string op = argv[2];
    TagLib::PropertyMap p;
    if (op == "unicode") {
      p["TITLE"].append(TagLib::String("Žluť", TagLib::String::UTF8));
      p["ARTIST"].append(TagLib::String("東京", TagLib::String::UTF8));
      p["COMMENT"].append("comment");
    }
    if (op == "empty" || op == "unicode")f.setProperties(p);
    if (op == "common") {
      f.tag()->setTitle("new title");
      f.tag()->setArtist("new artist");
      f.tag()->setAlbum("new album");
      f.tag()->setYear(4294967295U);
      f.tag()->setTrack(2147483648U);
    }
    dump(f);
    for (int i = 0; i < std::stoi(argv[3]); i++) {
      std::cout << "save=" << f.save() << '\n';
      dump(f);
    }
  }
  TagLib::FileRef reread(argv[1], false);
  std::cout << "reread\n";
  dump(reread);
}
