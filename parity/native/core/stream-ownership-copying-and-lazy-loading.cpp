// Defined common/stream/APE protected contracts; no intentional native fault operations.
#include <filesystem>
#include <fstream>
#include <iostream>
#include <thread>
#include <vector>
#include <functional>
#include <atomic>
#include <fcntl.h>
#include <unistd.h>
#include <tfilestream.h>
#include <tbytevectorstream.h>
#include <tstringlist.h>
#include <tpropertymap.h>
#include <tag.h>
#include <tagunion.h>
#include <apefooter.h>
#include <apetag.h>
#include <apeitem.h>
#include <mpegfile.h>
#include <id3v2tag.h>
#include <id3v1tag.h>
#include <id3v2synchdata.h>
#include <textidentificationframe.h>
#include <mp4file.h>
#include <mp4tag.h>
#include <asftag.h>
#include <infotag.h>
using namespace TagLib;
namespace fs = std::filesystem;

static std::string hex(const ByteVector &b) {
  auto h = b.toHex();
  return {h.data(), h.size()};
}

static std::string text(const String &s) { return hex(s.data(String::UTF16BE)); }
static void put(const std::string &k, const std::string &s) { std::cout << k << '\t' << s << '\n'; }

static void write(const fs::path &p, const std::string &s) {
  std::ofstream f(p, std::ios::binary);
  f.write(s.data(), s.size());
}

static ByteVector read(const fs::path &p) {
  std::ifstream f(p, std::ios::binary);
  std::string s((std::istreambuf_iterator<char>(f)), {});
  return ByteVector(s.data(), s.size());
}

struct Stream : FileStream {
  using FileStream::FileStream;
  void resize(offset_t n) { truncate(n); }
};

struct Bare : File {
  explicit Bare(IOStream *s) : File(s) {
  }

  TagLib::Tag *tag() const override { return nullptr; }
  AudioProperties *audioProperties() const override { return nullptr; }
  bool save() override { return false; }
};

struct Footer : APE::Footer {
  void apply(const ByteVector &b) { parse(b); }
  ByteVector raw(bool h) { return render(h); }
};

struct Ape : APE::Tag {
  Ape() = default;

  Ape(File *f, offset_t o) : Tag(f, o) {
  }

  void apply(const ByteVector &b) { parse(b); }
  void again() { read(); }
};

static int concurrent(const std::function<void()> &work) {
  std::atomic<int> ready{0}, success{0};
  std::atomic<bool> go{false};
  std::vector<std::thread> threads;
  for (int i = 0; i < 8; i++)
    threads.emplace_back([&] {
      ready++;
      while (!go.load())std::this_thread::yield();
      try {
        for (int n = 0; n < 25; n++)work();
        success++;
      } catch (...) {
      }
    });
  while (ready < 8)std::this_thread::yield();
  go = true;
  for (auto &t: threads)t.join();
  return success;
}

static void lazy(const fs::path &corpus) {
  ByteVector fields(1, 0);
  for (const auto &s: {"Guitar", "Artist 1", "Drums", "Artist 2", "Producer", "Artist 3", "Engineer", "Artist 4"}) {
    if (fields.size() > 1)fields.append('\0');
    fields.append(ByteVector(s));
  }
  auto frame = ByteVector("IPLS") + ByteVector::fromUInt(fields.size()) + ByteVector(2, 0) + fields;
  auto data = ByteVector("ID3") + ByteVector(1, 3) + ByteVector(2, 0) + ID3v2::SynchData::fromUInt(frame.size()) +
              frame;
  put("lazy/map", std::to_string(concurrent([] {
    if (ID3v2::TextIdentificationFrame::involvedPeopleMap().size() != 5)throw 1;
  })));
  put("lazy/ipls", std::to_string(concurrent([&] {
    ByteVectorStream s(data);
    MPEG::File f(&s, false);
    if (!f.hasID3v2Tag() || f.ID3v2Tag()->frameList("TMCL").size() != 1)throw 1;
  })));
  auto mp4 = read(corpus / "has-tags.m4a");
  if (mp4.isEmpty())throw 1;
  put("lazy/mp4", std::to_string(concurrent([&] {
    ByteVectorStream s(mp4);
    MP4::File f(&s);
    if (!f.isValid() || f.tag()->properties().isEmpty())throw 1;
  })));
  put("lazy/info", std::to_string(concurrent([] {
    PropertyMap p;
    p["TITLE"] = StringList("Title");
    p["ARTIST"] = StringList("Artist");
    RIFF::Info::Tag t;
    if (!t.setProperties(p).isEmpty())throw 1;
  })));
  put("lazy/asf", std::to_string(concurrent([] {
    PropertyMap p;
    p["TITLE"] = StringList("Title");
    p["ARTIST"] = StringList("Artist");
    ASF::Tag t;
    if (!t.setProperties(p).isEmpty())throw 1;
  })));
  ByteVectorStream s(data);
  MPEG::File f(&s, false);
  put("lazy/final", std::to_string(f.hasID3v2Tag()) + ":" + std::to_string(f.ID3v2Tag()->frameList("TMCL").size()));
}

static String nul() { return String(std::string("a\0b", 3)); }

static void values() {
  const char buffer[] = {'a', 0, 'b', 'c', 0};
  for (unsigned n = 0; n <= 4; n++) {
    ByteVector b(buffer, n), setter;
    setter.setData(buffer, n);
    put("buffer/" + std::to_string(n),
        hex(b) + ":" + hex(ByteVector(buffer, n)) + ":" + hex(setter) + ":" + hex(setter) + ":" + hex(
          ByteVector::fromCString(buffer, n)));
  }
  ByteVector setter;
  setter.setData(buffer);
  put("buffer/default", hex(ByteVector::fromCString(buffer)) + ":" + hex(setter));
  ByteVector numbers("\1\2\3", 3);
  put("defaults/numbers",
      std::to_string(numbers.toUInt(0U)) + ":" + std::to_string(numbers.toUInt(0U, 3U)) + ":" +
      std::to_string(ByteVector(".OggS....").rfind('O', 4)) + ":" + std::to_string(
        ByteVector("blah blah").containsAt("blah", 6, 1)));
  StringList list(ByteVectorList{"a", "b"});
  put("defaults/stringlist", text(list.toString()));
  for (int i = 0; i < 4; i++) {
    StringList v;
    if (i == 1)v.append("one");
    if (i == 2) {
      v.append("one");
      v.append("two");
    }
    if (i == 3) {
      v.append(nul());
      v.append("c");
    }
    put("join/" + std::to_string(i), text(TagLib::Tag::joinTagValues(v)));
  }

}

struct Audio : AudioProperties {
  Audio() : AudioProperties(Average) {
  }

  int lengthInMilliseconds() const override { return 2064; }
  int channels() const override { return 2; }
};

static std::string common(TagLib::Tag &t) {
  return text(t.title()) + ":" + text(t.artist()) + ":" + text(t.genre()) + ":" + std::to_string(t.year()) + ":" +
         std::to_string(t.track()) + ":" + std::to_string(t.isEmpty());
}

static void commonCases() {
  String numeric("123");
  bool signedOk = false, unsignedOk = false;
  auto signedValue = numeric.toLongLong(&signedOk);
  auto unsignedValue = numeric.toULongLong(&unsignedOk);
  put("defaults/string-numeric",
      std::to_string(signedValue) + ":" + std::to_string(signedOk) + ":" + std::to_string(unsignedValue) + ":" +
      std::to_string(unsignedOk));
  Audio audio;
  put("audio/seconds", std::to_string(audio.lengthInSeconds()));
  ByteVectorStream s(ByteVector("abc"));
  Bare f(&s);
  f.insert("X", 1);
  f.removeBlock(0, 0);
  f.removeBlock(1, 0);
  put("removal/file-zero-length", hex(*s.data()) + ":" + std::to_string(s.tell()));
  s.removeBlock(1, 0);
  put("removal/stream-zero-length", hex(*s.data()) + ":" + std::to_string(s.tell()));
  String str(std::string("\xe9", 1));
  put("defaults/string8", hex(ByteVector(str.to8Bit().data(), str.to8Bit().size())) + ":" + text(str));
  auto *a = new RIFF::Info::Tag;
  auto *b = new ID3v1::Tag;
  auto *c = new APE::Tag;
  b->setArtist("second");
  b->setTrack(7);
  c->setTitle("third");
  c->setYear(2026);
  TagUnion u(a, b, c);
  put("union/read", common(u));
  u.setTitle("broadcast");
  u.setGenre("Rock");
  u.setYear(4294967295U);
  put("union/broadcast", common(u) + ":" + std::to_string(u.tag(0) == a) + ":" + std::to_string(u[1] == b));
  u.set(0, nullptr);
  put("union/replace", common(u) + ":" + std::to_string(u.tag(0) == nullptr));
}

static void footerState(const std::string &key, Footer &f) {
  put(key, std::to_string(f.version()) + ":" + std::to_string(f.tagSize()) + ":" + std::to_string(f.itemCount()) + ":" +
           std::to_string(f.headerPresent()) + ":" + std::to_string(f.footerPresent()) + ":" +
           std::to_string(f.isHeader()) + ":" + hex(f.raw(true)) + ":" + hex(f.renderHeader()) + ":" + hex(
             f.renderFooter()));
}

static void apeCases() {
  Footer f;
  f.setTagSize(99);
  f.setItemCount(7);
  for (int n = 0; n <= 32; n++) {
    f.apply(ByteVector(n, 0));
    footerState("footer/" + std::to_string(n), f);
  }
  APE::Tag first;
  first.setTitle("first");
  auto data = first.render();
  ByteVectorStream s(data);
  Bare file(&s);
  Ape t(&file, data.size() - 32);
  put("ape/read", text(t.title()) + ":" + text(t.artist()) + ":" + std::to_string(s.tell()) + ":" + hex(t.render()));
  APE::Tag second;
  second.setArtist("next");
  auto next = second.render();
  if (next.size() != data.size())throw 1;
  s.seek(0);
  s.writeBlock(next);
  t.again();
  put("ape/repeat", text(t.title()) + ":" + text(t.artist()) + ":" + std::to_string(s.tell()) + ":" + hex(t.render()));
  APE::Item item;
  item.setBinaryData("inactive");
  item.setType(APE::Item::Text);
  put("ape/inactive-text", hex(item.binaryData()));
  item.setType(APE::Item::Locator);
  put("ape/inactive-locator", hex(item.binaryData()));
}

static void streams(const fs::path &root) {
  for (int mode = 0; mode < 4; mode++) {
    auto p = root / ("channel-" + std::to_string(mode));
    write(p, "abcdef");
    int fd = ::open(p.c_str(), mode == 2 ? O_RDONLY : mode == 3 ? O_WRONLY : O_RDWR);
    if (fd < 0)throw 1;
    ::lseek(fd, 2, SEEK_SET);
    {
      Stream s(fd, mode == 1);
      if (!s.isOpen())put("channel/" + std::to_string(mode) + "/rejected", "1");
      else put("channel/" + std::to_string(mode) + "/open",
          std::to_string(s.isOpen()) + ":" + std::to_string(s.readOnly()) + ":" + text(s.name()));
      if (s.isOpen()) {
        auto before = s.tell();
        auto block = s.readBlock(2);
        put("channel/" + std::to_string(mode) + "/read", std::to_string(before) + ":" + hex(block));
        s.insert("X", 1, 2);
        s.resize(3);
        put("channel/" + std::to_string(mode) + "/end", std::to_string(s.tell()) + ":" + std::to_string(s.length()));
      }
    }
    bool stillOpen = ::fcntl(fd, F_GETFD) != -1;
    put("channel/" + std::to_string(mode) + "/closed", std::to_string(stillOpen) + ":" + hex(read(p)));
    if (stillOpen)::close(fd);
  }
  auto p = root / "eof";
  write(p, "a");
  Stream s(p.c_str());
  put("eof/first", hex(s.readBlock(2)));
  write(p, "ab");
  put("eof/sticky", hex(s.readBlock(1)));
  s.clear();
  put("eof/clear", hex(s.readBlock(1)));
  put("eof/empty", hex(s.readBlock(1)));
  write(p, "abc");
  put("eof/length", std::to_string(s.length()));
  put("eof/after-length", hex(s.readBlock(1)));
}

static void readonlyCases(const fs::path &root) {
  auto r = root / "readonly";
  write(r, "abcd");
  {
    Stream ro(r.c_str(), true);
    ro.seek(2);
    ro.writeBlock("x");
    ro.insert("x", 1, 1);
    ro.removeBlock(1, 1);
    put("readonly/state", std::to_string(ro.tell()) + ":" + std::to_string(ro.length()));
  }
  put("readonly/bytes", hex(read(r)));
}

int main(int argc, char **argv) {
  fs::path root = argv[1], corpus = argv[2];
  lazy(corpus);
  values();
  commonCases();
  apeCases();
  streams(root);
  readonlyCases(root);
}
