// TagLib ID3v1 public operations and globally configured file behavior.
#include <iostream>
#include <iomanip>
#include <sstream>
#include <filesystem>
#include <fstream>
#include <map>
#include <tfile.h>
#include <tbytevectorstream.h>
#include <tpropertymap.h>
#include <fileref.h>
#include <id3v1tag.h>
#include <id3v1genres.h>
using namespace TagLib;

static std::string hex(const ByteVector &v) {
  std::ostringstream o;
  for (unsigned char c: v)o << std::hex << std::setfill('0') << std::setw(2) << unsigned(c);
  return o.str();
}

static std::string text(const String &s) { return hex(s.data(String::UTF8)); }

static std::string props(const PropertyMap &p) {
  std::ostringstream o;
  for (const auto &[k,vs]: p) {
    o << text(k) << '=';
    for (const auto &v: vs)o << text(v) << ',';
    o << ';';
  }
  return o.str();
}

static void common(const std::string &n, const TagLib::Tag &t) {
  std::cout << n << '\t' << t.isEmpty() << '\t' << t.year() << '\t' << t.track() << '\t';
  for (const auto &v: {t.title(), t.artist(), t.album(), t.comment(), t.genre()})std::cout << text(v) << ';';
  std::cout << '\t' << props(t.properties());
}

static void state(const std::string &n, const ID3v1::Tag &t, long cursor = -1) {
  common(n, t);
  std::cout << '\t' << t.genreNumber() << '\t' << hex(t.render()) << '\t' << cursor << '\n';
}

class MemoryFile : public File {
public:
  explicit MemoryFile(IOStream *s) : File(s) {
  }

  TagLib::Tag *tag() const override { return nullptr; }
  AudioProperties *audioProperties() const override { return nullptr; }
  bool save() override { return false; }
  void invalidate() { setValid(false); }
};

class Utf8 : public ID3v1::StringHandler {
public:
  mutable int parsed = 0, rendered = 0;
  bool counting = false;

  String parse(const ByteVector &v) const override {
    ++parsed;
    auto result = String(v, String::UTF8).stripWhiteSpace();
    return counting ? result + String("!") + String::number(parsed) : result;
  }

  ByteVector render(const String &s) const override {
    ++rendered;
    return (counting ? s + String("~") + String::number(rendered) : s).data(String::UTF8);
  }
};

static PropertyMap input() {
  PropertyMap p;
  p["TITLE"] = StringList{"first", "second"};
  p["DATE"] = StringList{"oops"};
  p["TRACKNUMBER"] = StringList{"-1"};
  p["CUSTOM"] = StringList{"value"};
  return p;
}

int main(int argc, char **argv) {
  Utf8 utf8;
  if (argc == 4) {
    utf8.counting = std::string(argv[3]) == "counting";
    ID3v1::Tag::setStringHandler(&utf8);
    FileRef f(argv[1], false);
    if (f.isNull())return 3;
    std::cout << "handler-open\t" << utf8.parsed << '\t' << utf8.rendered << '\n';
    common("opened", *f.tag());
    std::cout << '\n';
    std::string op = argv[2];
    if (op == "common") {
      f.tag()->setTitle(String("日本語", String::UTF8));
      f.tag()->setComment(String("Žluť", String::UTF8));
      f.tag()->setYear(0xffffffffU);
    } else if (op == "import")std::cout << "rejected\t" << props(f.setProperties(input())) << '\n';
    common("before", *f.tag());
    std::cout << '\n';
    std::cout << "saved\t" << f.save() << '\n';
    std::cout << "handler-save\t" << utf8.parsed << '\t' << utf8.rendered << '\n';
    f = FileRef();
    FileRef reopened(argv[1], false);
    if (reopened.isNull())return 4;
    common("reopened", *reopened.tag());
    std::cout << '\n';
    ID3v1::Tag::setStringHandler(nullptr);
    return 0;
  }
  ID3v1::Tag t;
  state("empty", t);
  std::cout << "identifier\t" << hex(ID3v1::Tag::fileIdentifier()) << '\n';
  t.setTitle("Title");
  t.setArtist("Artist     ");
  t.setAlbum("Album");
  t.setComment("Comment");
  t.setGenre("Hardcore");
  t.setYear(2026);
  t.setTrack(7);
  state("common", t);
  for (auto v: {0U, 255U, 256U, 2147483648U, 4294967295U}) {
    t.setYear(v);
    t.setTrack(v);
    t.setGenreNumber(v);
    state("number-" + std::to_string(v), t);
  }
  t.setTitle(String("日本語", String::UTF8));
  t.setArtist(String(std::string(30, 'b')) + String("日本語", String::UTF8));
  t.setComment(String(std::string(31, 'c')));
  state("truncated-unrepresentable", t);
  std::cout << "rejected\t" << props(t.setProperties(input())) << '\n';
  state("properties", t);
  auto list = ID3v1::genreList();
  for (unsigned i = 0; i < list.size(); i++)
    std::cout << "genre\t" << i << '\t' << text(list[i]) << '\t' <<
        ID3v1::genreIndex(list[i]) << '\n';
  for (const auto &[name,number]: ID3v1::genreMap())std::cout << "genre-map\t" << text(name) << '\t' << number << '\n';
  for (const auto &name: {
         "Jazz+Funk", "Folk/Rock", "Bebob", "Avantgarde", "Dance Hall", "Hardcore", "BritPop", "Negerpunk", "hardcore",
         "unknown"
       })
    std::cout << "alias\t" << text(name) << '\t' << ID3v1::genreIndex(name) << '\n';
  for (int i: {-1, 0, 191, 192, 255})std::cout << "genre-index\t" << i << '\t' << text(ID3v1::genre(i)) << '\n';
  std::map<std::string, std::filesystem::path> files;
  for (const auto &e: std::filesystem::directory_iterator(std::filesystem::path(argv[1]) / "inputs"))
    files[e.path().filename().string()] = e.path();
  for (const auto &[name,path]: files) {
    std::ifstream in(path, std::ios::binary);
    std::string raw((std::istreambuf_iterator<char>(in)), {});
    ByteVector bytes(raw.data(), raw.size());
    for (bool custom: {false, true}) {
      ID3v1::Tag::setStringHandler(custom ? &utf8 : nullptr);
      ByteVectorStream stream(ByteVector(7, '!') + bytes);
      MemoryFile file(&stream);
      ID3v1::Tag parsed(&file, 7);
      state(name + (custom ? "-utf8" : "-latin1"), parsed, stream.tell());
    }
  }
  ID3v1::Tag::setStringHandler(nullptr);
  ByteVectorStream stream(ByteVector(128, 0));
  MemoryFile file(&stream);
  stream.seek(4);
  file.invalidate();
  ID3v1::Tag invalid(&file, 7);
  state("invalid-file", invalid, stream.tell());
  ID3v1::Tag null(nullptr, 7);
  state("null-file", null);
}
