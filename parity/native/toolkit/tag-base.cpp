#include <iostream>
#include <tag.h>
#include <tfile.h>
#include <tbytevectorstream.h>
#include <tagunion.h>
#include <tpropertymap.h>
#include <tvariant.h>
using namespace TagLib;

struct Memory : Tag {
  String t, a, b, c, g;
  unsigned y = 0, n = 0;
  String title() const override { return t; }
  String artist() const override { return a; }
  String album() const override { return b; }
  String comment() const override { return c; }
  String genre() const override { return g; }
  unsigned year() const override { return y; }
  unsigned track() const override { return n; }
  void setTitle(const String &v) override { t = v; }
  void setArtist(const String &v) override { a = v; }
  void setAlbum(const String &v) override { b = v; }
  void setComment(const String &v) override { c = v; }
  void setGenre(const String &v) override { g = v; }
  void setYear(unsigned v) override { y = v; }
  void setTrack(unsigned v) override { n = v; }
};

struct StreamFile : File {
  Memory value;

  explicit StreamFile(IOStream *s) : File(s) {
  }

  Tag *tag() const override { return const_cast<Memory *>(&value); }
  AudioProperties *audioProperties() const override { return nullptr; }
  bool save() override { return false; }
};

static void show(const char *label, const PropertyMap &map) {
  std::cout << label << '=' << map.toString().data(String::UTF8).toHex() << '\n';
}

int main() {
  for (int offset: {0, 1019, 1020, 1021, 1022, 1023, 1024, 2046, 2047, 2048}) {
    ByteVector bytes(3100, 'x');
    for (int i = 0; i < 4; i++)bytes[offset + i] = "ABCD"[i];
    ByteVectorStream stream(bytes);
    StreamFile file(&stream);
    file.seek(33);
    for (int start: {0, 2, 1020, 1024, 2048})
      for (const char *before: {"", "xx", "AB"}) {
        std::cout << "file-search=" << offset << ',' << start << ',' << before << ':' << file.
            find("ABCD", start, before) << ':' << file.rfind("ABCD", start, before) << ':' << file.tell() << '\n';
      }
  }

  for (const char *value: {
         "", "0", "2026", "-2", "2026-10", " 42", "42 ", "2147483647", "-2147483648", "2147483646", "4294967295", "+7"
       }) {
    Memory m;
    PropertyMap p;
    p["TITLE"] = {"first", "second"};
    p["DATE"] = {value};
    p["TRACKNUMBER"] = {value};
    p["EMPTY"] = {};
    p["PRIVATE"] = {"x"};
    p.addUnsupportedData("opaque");
    auto left = m.setProperties(p);
    std::cout << "numbers=" << ByteVector(value).toHex() << ':' << m.year() << ':' << m.track() << '\n';
    show("properties", m.properties());
    show("remainder", left);
  }
  Memory source, target;
  source.setTitle("source");
  source.setArtist("artist");
  source.setTrack(7);
  target.setTitle("kept");
  target.setYear(2026);
  Tag::duplicate(&source, &target, false);
  show("duplicate-fill", target.properties());
  Tag::duplicate(&source, &target);
  show("duplicate-all", target.properties());
  auto first = new Memory;
  auto second = new Memory;
  auto third = new Memory;
  first->setTitle("first");
  second->setArtist("second");
  second->setYear(2026);
  third->setTrack(4);
  TagUnion tags(first, second, third);
  std::cout << "union=" << tags.title() << ':' << tags.artist() << ':' << tags.year() << ':' << tags.track() << '\n';
  show("union-map", tags.properties());
  PropertyMap p;
  p["TITLE"] = {"all"};
  p["DATE"] = {"2025"};
  show("union-remainder", tags.setProperties(p));
  for (int i = 0; i < 3; i++)show("union-child", tags.tag(i)->properties());
  PropertyMap mut;
  mut["mixed"].append("one");
  mut["MIXED"].append("two");
  auto v = mut.value("mixed");
  v.append("copy");
  mut["empty"];
  mut.addUnsupportedData("opaque");
  show("mutable", mut);
  auto keys = mut;
  mut.erase(keys);
  show("mutable-erase", mut);
}
